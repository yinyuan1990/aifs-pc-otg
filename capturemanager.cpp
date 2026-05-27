#include "capturemanager.h"
#include "gpupipeline.h"
#include "imageprovider.h"
#include "slowmotionplayer.h"
#include <QStandardPaths>
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QBuffer>
#include <QDebug>
#include <QTextStream>
#include <QDateTime>
#include <QtConcurrent>

#ifdef Q_OS_WIN
#include <windows.h>
#include <psapi.h>
#endif

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libswscale/swscale.h>
}

// ============== 内存监控 ==============
static double getMemoryUsageMB() {
#ifdef Q_OS_WIN
    PROCESS_MEMORY_COUNTERS pmc;
    if (GetProcessMemoryInfo(GetCurrentProcess(), &pmc, sizeof(pmc))) {
        return pmc.WorkingSetSize / (1024.0 * 1024.0);
    }
#endif
    return 0.0;
}

// ============== JpegRingBuffer ==============

JpegRingBuffer::JpegRingBuffer(int capacity)
    : m_capacity(capacity)
{
    m_buffer.resize(capacity);
}

void JpegRingBuffer::addFrame(const QByteArray &jpegData, qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);
    
    // 直接移动数据，避免复制
    m_buffer[m_head].data = jpegData;
    m_buffer[m_head].frameIndex = frameIndex;
    m_head = (m_head + 1) % m_capacity;
    if (m_count < m_capacity) {
        m_count++;
    }
}

JpegFrame JpegRingBuffer::getFrame(int offset) const
{
    QMutexLocker lock(&m_mutex);
    
    if (m_count == 0 || offset > 0 || -offset >= m_count) {
        return JpegFrame();
    }
    
    // offset: 0=最新, -1=前一帧, ...
    int pos = (m_head - 1 + offset + m_capacity) % m_capacity;
    return m_buffer[pos];
}

JpegFrame JpegRingBuffer::getFrameByIndex(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    
    if (m_count == 0) return JpegFrame();
    
    // 查找帧
    for (int i = 0; i < m_count; i++) {
        int pos = (m_head - 1 - i + m_capacity) % m_capacity;
        if (m_buffer[pos].frameIndex == frameIndex) {
            return m_buffer[pos];
        }
    }
    
    return JpegFrame();
}

bool JpegRingBuffer::hasFrame(qint64 frameIndex) const
{
    QMutexLocker lock(&m_mutex);
    
    if (m_count == 0) return false;
    
    qint64 newest = newestIndex();
    qint64 oldest = oldestIndex();
    
    return frameIndex >= oldest && frameIndex <= newest;
}

int JpegRingBuffer::size() const
{
    QMutexLocker lock(&m_mutex);
    return m_count;
}

qint64 JpegRingBuffer::oldestIndex() const
{
    // 注意：调用者应该已经持有锁
    if (m_count == 0) return -1;
    int pos = (m_head - m_count + m_capacity) % m_capacity;
    return m_buffer[pos].frameIndex;
}

qint64 JpegRingBuffer::newestIndex() const
{
    // 注意：调用者应该已经持有锁
    if (m_count == 0) return -1;
    int pos = (m_head - 1 + m_capacity) % m_capacity;
    return m_buffer[pos].frameIndex;
}

void JpegRingBuffer::clear()
{
    QMutexLocker lock(&m_mutex);
    m_head = 0;
    m_count = 0;
    for (auto &frame : m_buffer) {
        frame = JpegFrame();
    }
}

// ============== JpegEncoder ==============

JpegEncoder::JpegEncoder(JpegRingBuffer *buffer, QObject *parent)
    : QThread(parent)
    , m_ringBuffer(buffer)
{
    start(QThread::LowPriority);  // 低优先级，不抢占主线程
}

JpegEncoder::~JpegEncoder()
{
    stop();
    wait();
}

void JpegEncoder::stop()
{
    m_running = false;
    m_condition.wakeAll();
}

void JpegEncoder::submitFrame(const QImage &frame, qint64 frameIndex)
{
    if (frame.isNull() || !m_running) return;
    
    // 尝试获取锁，如果锁被占用则跳过这帧（避免阻塞主线程）
    if (!m_mutex.tryLock()) {
        return;  // 编码器忙，跳过这帧
    }
    
    // 队列满时丢弃最老的帧
    while (m_queue.size() >= MAX_QUEUE_SIZE) {
        m_queue.dequeue();
    }
    
    m_queue.enqueue({frame, frameIndex});
    m_condition.wakeOne();
    m_mutex.unlock();
}

bool JpegEncoder::initEncoder(int width, int height)
{
    if (m_codecCtx && m_encoderWidth == width && m_encoderHeight == height) {
        return true;
    }
    
    cleanupEncoder();
    
    // 列出所有可用的 MJPEG 编码器
    qDebug() << "=== Searching for MJPEG encoders ===";
    void *iter = nullptr;
    const AVCodec *c;
    while ((c = av_codec_iterate(&iter))) {
        if (av_codec_is_encoder(c) && c->id == AV_CODEC_ID_MJPEG) {
            qDebug() << "Found MJPEG encoder:" << c->name << "-" << (c->long_name ? c->long_name : "");
        }
    }
    qDebug() << "=== End encoder search ===";
    
    // 尝试硬件加速编码器
    const AVCodec *codec = nullptr;
    bool useHardware = false;
    
    // 1. 尝试 Intel QSV (mjpeg_qsv) - 需要 Intel GPU
    codec = avcodec_find_encoder_by_name("mjpeg_qsv");
    if (codec) {
        qDebug() << "Trying Intel QSV MJPEG encoder";
        useHardware = true;
    }
    
    // 2. 尝试 VAAPI (Linux/一些 Windows)
    if (!codec) {
        codec = avcodec_find_encoder_by_name("mjpeg_vaapi");
        if (codec) {
            qDebug() << "Trying VAAPI MJPEG encoder";
            useHardware = true;
        }
    }
    
    // 3. 回退到软件编码器
    if (!codec) {
        codec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
        qDebug() << "Using software MJPEG encoder";
    }
    
    if (!codec) {
        qWarning() << "No MJPEG encoder found";
        return false;
    }
    
    m_codecCtx = avcodec_alloc_context3(codec);
    if (!m_codecCtx) return false;
    
    m_codecCtx->width = width;
    m_codecCtx->height = height;
    m_codecCtx->time_base = {1, 60};
    
    if (useHardware) {
        // QSV 使用 NV12 格式
        m_codecCtx->pix_fmt = AV_PIX_FMT_NV12;
    } else {
        m_codecCtx->pix_fmt = AV_PIX_FMT_YUVJ444P;
    }
    
    // 使用多线程加速（软件编码时）
    if (!useHardware) {
        m_codecCtx->thread_count = 4;
        m_codecCtx->thread_type = FF_THREAD_SLICE;  // 使用 slice 线程避免警告
    }
    
    // 固定质量
    m_codecCtx->flags |= AV_CODEC_FLAG_QSCALE;
    m_codecCtx->global_quality = FF_QP2LAMBDA * 5;
    
    if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
        qWarning() << "Failed to open encoder, trying software fallback";
        avcodec_free_context(&m_codecCtx);
        
        // 回退到软件
        codec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
        if (!codec) return false;
        
        m_codecCtx = avcodec_alloc_context3(codec);
        m_codecCtx->width = width;
        m_codecCtx->height = height;
        m_codecCtx->time_base = {1, 60};
        m_codecCtx->pix_fmt = AV_PIX_FMT_YUVJ444P;
        m_codecCtx->thread_count = 4;
        m_codecCtx->thread_type = FF_THREAD_SLICE;
        m_codecCtx->flags |= AV_CODEC_FLAG_QSCALE;
        m_codecCtx->global_quality = FF_QP2LAMBDA * 5;
        
        if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
            avcodec_free_context(&m_codecCtx);
            return false;
        }
        useHardware = false;
    }
    
    m_frame = av_frame_alloc();
    m_frame->format = m_codecCtx->pix_fmt;
    m_frame->width = width;
    m_frame->height = height;
    if (!useHardware) {
        m_frame->color_range = AVCOL_RANGE_JPEG;
    }
    av_frame_get_buffer(m_frame, 32);
    
    m_packet = av_packet_alloc();
    
    // 设置色彩空间转换
    AVPixelFormat dstFmt = useHardware ? AV_PIX_FMT_NV12 : AV_PIX_FMT_YUVJ444P;
    m_swsCtx = sws_getContext(
        width, height, AV_PIX_FMT_RGB32,
        width, height, dstFmt,
        SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
    
    m_encoderWidth = width;
    m_encoderHeight = height;
    m_useHardware = useHardware;
    
    qDebug() << "MJPEG encoder initialized:" << width << "x" << height 
             << (useHardware ? "(Hardware)" : "(Software)");
    
    return true;
}

void JpegEncoder::cleanupEncoder()
{
    if (m_swsCtx) {
        sws_freeContext(m_swsCtx);
        m_swsCtx = nullptr;
    }
    if (m_packet) {
        av_packet_free(&m_packet);
        m_packet = nullptr;
    }
    if (m_frame) {
        av_frame_free(&m_frame);
        m_frame = nullptr;
    }
    if (m_codecCtx) {
        avcodec_free_context(&m_codecCtx);
        m_codecCtx = nullptr;
    }
    m_encoderWidth = 0;
    m_encoderHeight = 0;
}

QByteArray JpegEncoder::encodeJpeg(const QImage &image)
{
    if (image.isNull()) return QByteArray();
    
    QImage img = image;
    if (img.format() != QImage::Format_RGB32 && img.format() != QImage::Format_ARGB32) {
        img = img.convertToFormat(QImage::Format_RGB32);
    }
    
    if (!initEncoder(img.width(), img.height())) {
        // 回退到 Qt 编码
        QByteArray data;
        QBuffer buffer(&data);
        buffer.open(QIODevice::WriteOnly);
        img.save(&buffer, "JPEG", JPEG_QUALITY);
        return data;
    }
    
    // RGB -> YUV
    const uint8_t *srcData[1] = {img.constBits()};
    int srcLinesize[1] = {static_cast<int>(img.bytesPerLine())};
    
    sws_scale(m_swsCtx, srcData, srcLinesize, 0, img.height(),
              m_frame->data, m_frame->linesize);
    
    m_frame->pts = m_currentIndex.load();
    
    // 编码
    int ret = avcodec_send_frame(m_codecCtx, m_frame);
    if (ret < 0) return QByteArray();
    
    ret = avcodec_receive_packet(m_codecCtx, m_packet);
    if (ret < 0) return QByteArray();
    
    QByteArray result(reinterpret_cast<char*>(m_packet->data), m_packet->size);
    av_packet_unref(m_packet);
    
    return result;
}

void JpegEncoder::run()
{
    while (m_running) {
        Task task;
        
        {
            QMutexLocker lock(&m_mutex);
            while (m_queue.isEmpty() && m_running) {
                m_condition.wait(&m_mutex);
            }
            
            if (!m_running && m_queue.isEmpty()) {
                break;
            }
            
            task = m_queue.dequeue();
        }
        
        if (task.frame.isNull()) continue;
        
        // 编码 JPEG
        QByteArray jpeg = encodeJpeg(task.frame);
        
        if (!jpeg.isEmpty()) {
            // 存入 Ring Buffer
            m_ringBuffer->addFrame(jpeg, task.frameIndex);
            m_currentIndex = task.frameIndex;
            emit frameEncoded(task.frameIndex);
        }
    }
    
    cleanupEncoder();
}

// ============== CaptureManager ==============

CaptureManager::CaptureManager(QObject *parent)
    : QObject(parent)
    , m_settings(new QSettings("Acard", "Aifs", this))
{
    qDebug() << "📦 CaptureManager 构造开始...";
    loadSettings();
    ensureCapturesDir();
    qDebug() << "📦 CaptureManager 设置和目录完成";

    // 自动注册到 ImageProvider（因为通过 Loader 加载，main.cpp 的 findChild 找不到）
    if (CaptureImageProvider::instance()) {
        CaptureImageProvider::instance()->setCaptureManager(this);
        qDebug() << "CaptureManager: registered to ImageProvider";
    }
}

CaptureManager::~CaptureManager()
{
    delete m_naluDecoder;
}

void CaptureManager::ensureCapturesDir()
{
    // 帧文件由 GpuJpegEncoder 管理在 captures/frames/
    // CaptureManager 不再需要单独的目录
    m_capturesDir = QCoreApplication::applicationDirPath() + "/captures";
}

void CaptureManager::loadSettings()
{
    m_gridRows = m_settings->value("capture/gridRows", DEFAULT_GRID_ROWS).toInt();
    m_gridCols = m_settings->value("capture/gridCols", DEFAULT_GRID_COLS).toInt();
    m_isHorizontalLayout = m_settings->value("capture/horizontalLayout", true).toBool();
    m_preFrameCount = m_settings->value("capture/preFrames", DEFAULT_PRE_FRAMES).toInt();
    m_postFrameCount = m_settings->value("capture/postFrames", DEFAULT_POST_FRAMES).toInt();
    
    m_gridRows = qBound(1, m_gridRows, MAX_GRID_SIZE);
    m_gridCols = qBound(1, m_gridCols, MAX_GRID_SIZE);
    m_preFrameCount = qBound(10, m_preFrameCount, MAX_PRE_POST_FRAMES);
    m_postFrameCount = qBound(10, m_postFrameCount, MAX_PRE_POST_FRAMES);
    
    // 相机设定
    m_brightness = m_settings->value("camera/brightness", DEFAULT_BRIGHTNESS).toDouble();
    m_contrast = m_settings->value("camera/contrast", DEFAULT_CONTRAST).toDouble();
    m_saturation = m_settings->value("camera/saturation", DEFAULT_SATURATION).toDouble();
    m_hue = m_settings->value("camera/hue", DEFAULT_HUE).toDouble();
    m_gamma = m_settings->value("camera/gamma", DEFAULT_GAMMA).toDouble();
    m_exposure = m_settings->value("camera/exposure", DEFAULT_EXPOSURE).toDouble();
    
    // 范围限制
    m_brightness = qBound(-1.0, m_brightness, 1.0);
    m_contrast = qBound(0.0, m_contrast, 2.0);
    m_saturation = qBound(0.0, m_saturation, 2.0);
    m_hue = qBound(-1.0, m_hue, 1.0);
    m_gamma = qBound(0.01, m_gamma, 10.0);
    // 曝光值存储的是0-100百分比
    m_exposure = qBound(0.0, m_exposure, 100.0);
    
    // ★ 根据曝光值计算联动参数（启动时必须执行）
    // ⚠️ 亮度、色调不再联动，使用独立保存的值
    double slider = m_exposure;  // 0-100
    // m_brightness 不再联动，保持从配置文件读取的值
    
    // ★ 饱和度线性公式：20→1.10, 100→1.35
    m_saturation = 1.0375 + 0.003125 * slider;
    
    // ★ 对比度线性公式：20→1.10, 100→1.35
    m_contrast = 1.0375 + 0.003125 * slider;
    
    // m_hue 不再联动，保持从配置文件读取的值
    
    // ★ 伽马线性公式：20→1.08, 100→1.35
    m_gamma = 1.0125 + 0.003375 * slider;
    
    // 范围保护
    // m_brightness 保持独立设置的值
    m_saturation = qBound(1.0, m_saturation, 1.35);   // 饱和度范围
    m_contrast = qBound(1.0, m_contrast, 1.35);       // 对比度范围
    // m_hue 已在上面 qBound 过，不需要重复
    m_gamma = qBound(1.0, m_gamma, 1.35);             // 伽马范围
    
    qDebug() << "[CaptureManager] loadSettings: exposure =" << m_exposure << "%"
             << "→ 联动: 饱和度=" << m_saturation << ", 对比度=" << m_contrast << ", 伽马=" << m_gamma
             << " | 独立: 亮度=" << m_brightness << ", 色调=" << m_hue;
    
    // 通知 QML 设置已加载
    emit cameraSettingsChanged();
}

void CaptureManager::saveSettings()
{
    m_settings->setValue("capture/gridRows", m_gridRows);
    m_settings->setValue("capture/gridCols", m_gridCols);
    m_settings->setValue("capture/horizontalLayout", m_isHorizontalLayout);
    m_settings->setValue("capture/preFrames", m_preFrameCount);
    m_settings->setValue("capture/postFrames", m_postFrameCount);
    
    // 相机设定
    m_settings->setValue("camera/brightness", m_brightness);
    m_settings->setValue("camera/contrast", m_contrast);
    m_settings->setValue("camera/saturation", m_saturation);
    m_settings->setValue("camera/hue", m_hue);
    m_settings->setValue("camera/gamma", m_gamma);
    m_settings->setValue("camera/exposure", m_exposure);
    
    // 立即写入磁盘
    m_settings->sync();
    
    qDebug() << "[CaptureManager] saveSettings: exposure =" << m_exposure;
}

void CaptureManager::setGridRows(int rows)
{
    rows = qBound(1, rows, MAX_GRID_SIZE);
    if (m_gridRows != rows) {
        m_gridRows = rows;
        saveSettings();
        emit gridSettingsChanged();
    }
}

void CaptureManager::setGridCols(int cols)
{
    cols = qBound(1, cols, MAX_GRID_SIZE);
    if (m_gridCols != cols) {
        m_gridCols = cols;
        saveSettings();
        emit gridSettingsChanged();
    }
}

void CaptureManager::setIsHorizontalLayout(bool horizontal)
{
    if (m_isHorizontalLayout != horizontal) {
        m_isHorizontalLayout = horizontal;
        saveSettings();
        emit gridSettingsChanged();
        qDebug() << "Grid layout:" << (horizontal ? "横向(行优先)" : "纵向(列优先)");
    }
}

int CaptureManager::getGridRow(int index) const
{
    if (m_isHorizontalLayout) {
        // 横向（行优先）：从左到右填满一行，再填下一行
        return index / m_gridCols;
    } else {
        // 纵向（列优先）：从上到下填满一列，再填下一列
        return index % m_gridRows;
    }
}

int CaptureManager::getGridCol(int index) const
{
    if (m_isHorizontalLayout) {
        // 横向（行优先）
        return index % m_gridCols;
    } else {
        // 纵向（列优先）
        return index / m_gridRows;
    }
}

void CaptureManager::setPreFrameCount(int count)
{
    count = qBound(10, count, MAX_PRE_POST_FRAMES);  // 最小值 10
    if (m_preFrameCount != count) {
        m_preFrameCount = count;
        saveSettings();
        emit captureSettingsChanged();
    }
}

void CaptureManager::setPostFrameCount(int count)
{
    count = qBound(10, count, MAX_PRE_POST_FRAMES);  // 最小值 10
    if (m_postFrameCount != count) {
        m_postFrameCount = count;
        saveSettings();
        emit captureSettingsChanged();
    }
}

void CaptureManager::setCurrentItemIndex(int index)
{
    if (m_currentItemIndex != index) {
        m_currentItemIndex = index;
        emit currentItemChanged();
    }
}

void CaptureManager::setGpuPipeline(GpuPipeline *pipeline)
{
    if (m_gpuPipeline != pipeline) {
        m_gpuPipeline = pipeline;
        // 同步已加载的颜色参数到 JPEG 编码器
        syncColorToJpegEncoder();
        emit gpuPipelineChanged();
        qDebug() << "CaptureManager: GpuPipeline set, color params synced";
    }
}

void CaptureManager::setGstPlayer(GstPlayer *player)
{
    if (m_gstPlayer != player) {
        // 断开旧连接
        if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
            disconnect(m_gstPlayer->naluFrameStore(), &NaluFrameStore::frameStored,
                       this, &CaptureManager::onFrameEncoded);
        }

        m_gstPlayer = player;

        // 创建 NaluDecoder 并连接 frameStored 信号
        if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
            delete m_naluDecoder;
            m_naluDecoder = new NaluDecoder(m_gstPlayer->naluFrameStore(), this);
            connect(m_gstPlayer->naluFrameStore(), &NaluFrameStore::frameStored,
                    this, &CaptureManager::onFrameEncoded, Qt::QueuedConnection);
            qDebug() << "CaptureManager: NaluDecoder created, connected to NaluFrameStore";
        }

        emit gstPlayerChanged();
    }
}

void CaptureManager::onFrameReceived(const QImage &frame, qint64 frameIndex)
{
    Q_UNUSED(frame);
    Q_UNUSED(frameIndex);
}

void CaptureManager::onFrameIndexReady(qint64 frameIndex)
{
    // 更新当前帧索引
    m_currentFrameIdx = frameIndex;
    
    // 检查待完成的抓拍（等待后续帧）
    checkPendingCaptures(frameIndex);
}

void CaptureManager::onFrameEncoded(qint64 index)
{
    // 检查待完成的抓拍
    checkPendingCaptures(index);
}

void CaptureManager::checkPendingCaptures(qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);
    
    for (int i = m_pendingCaptures.size() - 1; i >= 0; i--) {
        PendingCapture &pending = m_pendingCaptures[i];
        
        if (pending.itemIndex < 0 || pending.itemIndex >= m_items.size()) {
            m_pendingCaptures.removeAt(i);
            continue;
        }
        
        CaptureItem &item = m_items[pending.itemIndex];
        
        // 更新结束索引
        if (frameIndex > item.endIndex && frameIndex <= pending.targetEndIndex) {
            item.endIndex = frameIndex;
        }
        
        // 检查是否完成
        if (item.endIndex >= pending.targetEndIndex) {
            int idx = pending.itemIndex;
            m_pendingCaptures.removeAt(i);
            
            // 异步保存到磁盘
            QMetaObject::invokeMethod(this, [this, idx]() {
                saveItemToDisk(idx);
            }, Qt::QueuedConnection);
        }
    }
}

void CaptureManager::capture()
{
    
    qint64 eventIndex = -1;
    
    qDebug() << "📷 Capture: naluDecoder=" << (m_naluDecoder ? "有效" : "NULL")
             << ", slowMotionActive=" << m_slowMotionActive
             << ", slowMotionPlayer=" << (m_slowMotionPlayer ? "有效" : "NULL");

    if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        qDebug() << "📷 Capture: naluStore count=" << m_gstPlayer->naluFrameStore()->count()
                 << ", newest=" << m_gstPlayer->naluFrameStore()->newestIndex()
                 << ", oldest=" << m_gstPlayer->naluFrameStore()->oldestIndex();
    }
    
    // 根据慢放模式选择事件帧来源
    if (m_slowMotionActive && m_slowMotionPlayer) {
        eventIndex = m_slowMotionPlayer->currentGlobalFrameIndex();
        qDebug() << "📷 Capture (SlowMotion): eventIndex=" << eventIndex
                 << "currentFrame=" << m_slowMotionPlayer->currentFrame()
                 << "startIndex=" << m_slowMotionPlayer->startIndex()
                 << "endIndex=" << m_slowMotionPlayer->endIndex()
                 << "recordedFrames=" << m_slowMotionPlayer->recordedFrames();
    } else {
        // 实时流模式：从 NaluFrameStore 获取最新帧索引
        if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
            qint64 newestIdx = m_gstPlayer->naluFrameStore()->newestIndex();
            int queueDepth = m_gstPlayer->bufferSize();
            qint64 oldestIdx = m_gstPlayer->naluFrameStore()->oldestIndex();

            eventIndex = newestIdx - queueDepth;
            eventIndex = qMax(oldestIdx, eventIndex);

            qDebug() << "📷 Capture (NALU): newest=" << newestIdx
                     << "队列=" << queueDepth
                     << "eventIndex=" << eventIndex;
        } else if (m_gpuPipeline) {
            eventIndex = m_gpuPipeline->newestFrame();
        }
    }
    
    if (eventIndex < 1) {
        qDebug() << "❌ Capture: no frames yet (eventIndex=" << eventIndex << ")";
        return;
    }
    
    if (m_items.size() >= MAX_ITEMS) {
        removeOldest();
    }
    
    // 计算索引范围
    qint64 rawStartIndex = eventIndex - m_preFrameCount;
    qint64 rawEndIndex = eventIndex + m_postFrameCount;
    qint64 startIndex = qMax(0LL, rawStartIndex);
    qint64 endIndex = rawEndIndex;
    
    qDebug() << "📷 Capture: eventIndex=" << eventIndex 
             << "preCount=" << m_preFrameCount << "postCount=" << m_postFrameCount
             << "rawRange=" << rawStartIndex << "-" << rawEndIndex;
    
    // 确保范围在可用帧内
    qint64 oldestAvailable = 0;

    if (m_slowMotionActive && m_slowMotionPlayer) {
        oldestAvailable = m_slowMotionPlayer->startIndex();
        qint64 newestAvailable = m_slowMotionPlayer->endIndex();
        qDebug() << "📷 Capture (SlowMotion): 慢放可用范围" << oldestAvailable << "-" << newestAvailable;
        startIndex = qMax(startIndex, oldestAvailable);
        endIndex = qMin(endIndex, newestAvailable);
    } else if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        oldestAvailable = m_gstPlayer->naluFrameStore()->oldestIndex();
        startIndex = qMax(startIndex, oldestAvailable);
    } else if (m_gpuPipeline) {
        oldestAvailable = m_gpuPipeline->oldestFrame();
        startIndex = qMax(startIndex, oldestAvailable);
    }
    
    qDebug() << "📷 Capture: 最终范围" << startIndex << "-" << endIndex 
             << "总帧数=" << (endIndex - startIndex + 1);
    
    // 创建 CaptureItem（不需要单独目录，帧文件已经在 captures/frames/ 中）
    CaptureItem item;
    item.id = m_nextId++;
    item.startIndex = startIndex;
    item.eventIndex = eventIndex;
    item.endIndex = endIndex;  // 直接设置目标结束帧
    item.currentOffset = item.eventOffset();  // 默认显示事件帧
    item.timestamp = QDateTime::currentMSecsSinceEpoch();
    item.dirPath = "";
    item.saved = true;

    // 注册有效范围（保护 NALU 帧不被环形缓冲覆盖）
    if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        item.validRangeId = m_gstPlayer->naluFrameStore()->registerValidRange(startIndex, endIndex);
    }

    // 直接抓取当前直播画面（已解码的 BGRA，零延迟）
    if (m_gstPlayer) {
        item.liveSnapshot = m_gstPlayer->grabCurrentFrame();
    }
    if (!item.liveSnapshot.isNull() && m_videoRotation != 0) {
        QTransform transform;
        transform.rotate(m_videoRotation);
        item.liveSnapshot = item.liveSnapshot.transformed(transform, Qt::FastTransformation);
    }

    item.jpegCache.resize(item.totalFrames());

    m_items.append(item);
    int newIndex = m_items.size() - 1;

    qDebug() << "Capture: item" << item.id
             << "start:" << startIndex
             << "event:" << eventIndex
             << "end:" << endIndex
             << "total:" << item.totalFrames()
             << "snapshot:" << (item.liveSnapshot.isNull() ? "NULL" : "OK");

    emit countChanged();
    emit itemAdded(newIndex);
    emit captureComplete(newIndex);

    // 后台预解码全部帧 → JPEG 压缩缓存（借鉴 JPEG 思路：每帧独立可读）
    int totalFrames = item.totalFrames();
    int rotation = m_videoRotation;
    QtConcurrent::run([this, newIndex, startIndex, totalFrames, rotation]() {
        if (!m_naluDecoder) return;

        for (int i = 0; i < totalFrames; i++) {
            qint64 globalIdx = startIndex + i;
            QImage img = m_naluDecoder->decodeFrame(globalIdx);
            if (img.isNull()) continue;

            if (rotation != 0) {
                QTransform t;
                t.rotate(rotation);
                img = img.transformed(t, Qt::FastTransformation);
            }

            QByteArray jpegData;
            QBuffer buf(&jpegData);
            buf.open(QIODevice::WriteOnly);
            img.save(&buf, "JPEG", 92);

            int itemIdx = newIndex;
            int frameIdx = i;
            QMetaObject::invokeMethod(this, [this, itemIdx, frameIdx, jpegData]() {
                if (itemIdx < m_items.size()) {
                    auto &cache = m_items[itemIdx].jpegCache;
                    if (frameIdx < cache.size()) {
                        cache[frameIdx] = jpegData;
                    }
                    m_cachedImage = QImage();
                    emit frameChanged(itemIdx, frameIdx);
                }
            }, Qt::QueuedConnection);
        }
    });
}

void CaptureManager::saveItemToDisk(int itemIndex)
{
    // 帧文件已经在 GpuJpegEncoder 的 captures/frames/ 目录中
    // 这个函数现在只用于兼容，实际不需要复制文件
    if (itemIndex < 0 || itemIndex >= m_items.size()) return;
    
    CaptureItem &item = m_items[itemIndex];
    item.saved = true;
    
    qDebug() << "Item" << item.id << "ready, frames:" << item.totalFrames();
    emit captureComplete(itemIndex);
}

void CaptureManager::clearAll()
{
    QMutexLocker lock(&m_mutex);
    m_pendingCaptures.clear();
    
    for (const CaptureItem &item : m_items) {
        if (item.validRangeId >= 0 && m_gstPlayer && m_gstPlayer->naluFrameStore()) {
            m_gstPlayer->naluFrameStore()->unregisterValidRange(item.validRangeId);
        }
    }
    
    m_items.clear();
    m_cachedItemIndex = -1;
    m_cachedImage = QImage();
    
    emit countChanged();
    setCurrentItemIndex(-1);
}

void CaptureManager::removeItem(int index)
{
    if (index < 0 || index >= m_items.size()) return;
    
    CaptureItem &item = m_items[index];
    
    if (item.validRangeId >= 0 && m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        m_gstPlayer->naluFrameStore()->unregisterValidRange(item.validRangeId);
    }
    
    m_items.removeAt(index);
    m_cachedItemIndex = -1;
    m_cachedImage = QImage();
    
    // 更新 pending 索引
    for (auto &pending : m_pendingCaptures) {
        if (pending.itemIndex > index) {
            pending.itemIndex--;
        } else if (pending.itemIndex == index) {
            pending.itemIndex = -1;
        }
    }
    
    emit itemRemoved(index);
    emit countChanged();
}

void CaptureManager::removeOldest()
{
    if (m_items.isEmpty()) return;
    removeItem(0);
}

void CaptureManager::reset()
{
    if (m_naluDecoder) m_naluDecoder->clearCache();
    clearAll();

    m_nextId = 1;
    qDebug() << "CaptureManager: reset complete";
}

int CaptureManager::getTotalFrames(int itemIndex) const
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return 0;
    return m_items[itemIndex].totalFrames();
}

int CaptureManager::getEventOffset(int itemIndex) const
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return 0;
    return m_items[itemIndex].eventOffset();
}

int CaptureManager::getCurrentOffset(int itemIndex) const
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return 0;
    return m_items[itemIndex].currentOffset;
}

bool CaptureManager::isItemReady(int itemIndex) const
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return false;
    // GPU 模式下，帧文件已经在磁盘，只要有帧就可以滚轮浏览
    // 检查是否有至少一帧
    return m_items[itemIndex].totalFrames() > 0;
}

void CaptureManager::gotoFrame(int itemIndex, int frameOffset)
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) {
        qDebug() << "⚠️ gotoFrame: invalid itemIndex" << itemIndex << "size:" << m_items.size();
        return;
    }
    
    CaptureItem &item = m_items[itemIndex];
    int oldOffset = item.currentOffset;
    int totalFrames = item.totalFrames();
    frameOffset = qBound(0, frameOffset, totalFrames - 1);
    
    qDebug() << "🎞️ gotoFrame: item=" << itemIndex << "old=" << oldOffset 
             << "new=" << frameOffset << "total=" << totalFrames;
    
    if (item.currentOffset != frameOffset) {
        item.currentOffset = frameOffset;
        emit frameChanged(itemIndex, frameOffset);
        qDebug() << "✅ frameChanged emitted: item=" << itemIndex << "frame=" << frameOffset;
    } else {
        qDebug() << "⏭️ gotoFrame: no change, skip emit";
    }
}

void CaptureManager::nextFrame(int itemIndex)
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return;
    gotoFrame(itemIndex, m_items[itemIndex].currentOffset + 1);
}

void CaptureManager::prevFrame(int itemIndex)
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return;
    gotoFrame(itemIndex, m_items[itemIndex].currentOffset - 1);
}

void CaptureManager::gotoEventFrame(int itemIndex)
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) return;
    gotoFrame(itemIndex, m_items[itemIndex].eventOffset());
}

QImage CaptureManager::getFrameImage(int itemIndex, int frameOffset)
{
    if (itemIndex < 0 || itemIndex >= m_items.size()) {
        return QImage();
    }
    
    CaptureItem &item = m_items[itemIndex];

    // 1. 检查 QImage 缓存（最近一帧）
    if (m_cachedItemIndex == itemIndex && m_cachedFrameOffset == frameOffset
        && m_cachedRotation == m_videoRotation
        && !m_cachedImage.isNull()) {
        return m_cachedImage;
    }

    QImage img;

    // 2. 检查 JPEG 预解码缓存（借鉴 JPEG 思路：每帧独立可读，~1ms 解压）
    if (frameOffset >= 0 && frameOffset < item.jpegCache.size()
        && !item.jpegCache[frameOffset].isEmpty()) {
        img.loadFromData(item.jpegCache[frameOffset], "JPEG");
    }

    // 3. NALU 实时解码（预解码还没完成时的 fallback）
    if (img.isNull() && m_naluDecoder) {
        qint64 globalIndex = item.startIndex + frameOffset;
        img = m_naluDecoder->tryCachedFrame(globalIndex);
        if (img.isNull()) {
            // 返回上一帧缓存或直播快照，不返回空图
            if (!m_cachedImage.isNull()) return m_cachedImage;
            return item.liveSnapshot;
        }
        // NALU 解码出的帧需要旋转
        if (!img.isNull() && m_videoRotation != 0) {
            QTransform t;
            t.rotate(m_videoRotation);
            img = img.transformed(t, Qt::FastTransformation);
        }
    }

    // 4. 最终兜底
    if (img.isNull()) {
        return item.liveSnapshot;
    }

    m_cachedItemIndex = itemIndex;
    m_cachedFrameOffset = frameOffset;
    m_cachedRotation = m_videoRotation;
    m_cachedImage = img;

    return img;
}

void CaptureManager::setVideoRotation(int rotation)
{
    // 确保角度是 0, 90, 180, 270 中的一个
    rotation = ((rotation % 360) + 360) % 360;
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
        rotation = 0;
    }
    
    if (m_videoRotation != rotation) {
        m_videoRotation = rotation;
        // 清除缓存，强制重新加载
        m_cachedImage = QImage();
        emit videoRotationChanged();
    }
}

void CaptureManager::setVideoZoom(double zoom)
{
    zoom = qBound(1.0, zoom, 5.0);
    if (!qFuzzyCompare(m_videoZoom, zoom)) {
        m_videoZoom = zoom;
        m_cachedImage = QImage();  // 清除缓存
        emit videoZoomChanged();
    }
}

void CaptureManager::setVideoOffsetX(double offsetX)
{
    if (!qFuzzyCompare(m_videoOffsetX, offsetX)) {
        m_videoOffsetX = offsetX;
        m_cachedImage = QImage();
        emit videoZoomChanged();
    }
}

void CaptureManager::setVideoOffsetY(double offsetY)
{
    if (!qFuzzyCompare(m_videoOffsetY, offsetY)) {
        m_videoOffsetY = offsetY;
        m_cachedImage = QImage();
        emit videoZoomChanged();
    }
}

void CaptureManager::setDisplayWidth(double width)
{
    if (width > 0 && !qFuzzyCompare(m_displayWidth, width)) {
        m_displayWidth = width;
        m_cachedImage = QImage();
        emit videoZoomChanged();
    }
}

void CaptureManager::setDisplayHeight(double height)
{
    if (height > 0 && !qFuzzyCompare(m_displayHeight, height)) {
        m_displayHeight = height;
        m_cachedImage = QImage();
        emit videoZoomChanged();
    }
}

void CaptureManager::setSlowMotionActive(bool active)
{
    if (m_slowMotionActive != active) {
        m_slowMotionActive = active;
        qDebug() << "CaptureManager: slowMotionActive changed to" << active;
        emit slowMotionActiveChanged();
    }
}

void CaptureManager::setSlowMotionPlayer(SlowMotionPlayer* player)
{
    if (m_slowMotionPlayer != player) {
        m_slowMotionPlayer = player;
        emit slowMotionPlayerChanged();
    }
}

QImage CaptureManager::loadFrameFromDisk(qint64 globalFrameIndex, const QString &sessionPrefix)
{
    Q_UNUSED(sessionPrefix);
    static qint64 lastValidFrame = -1;
    static QImage lastValidImage;

    if (m_gpuPipeline) {
        QImage img = m_gpuPipeline->decodeFrameToImage(globalFrameIndex);
        if (!img.isNull()) {
            lastValidFrame = globalFrameIndex;
            lastValidImage = img;
            return img;
        }
    }

    if (lastValidFrame >= 0 && !lastValidImage.isNull()) {
        return lastValidImage;
    }

    return QImage();
}

qint64 CaptureManager::currentFrameIndex() const
{
    if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        return m_gstPlayer->naluFrameStore()->newestIndex();
    }
    if (m_gpuPipeline) {
        return m_gpuPipeline->newestFrame();
    }
    return 0;
}

// ============ 相机设定 ============

void CaptureManager::setBrightness(double value)
{
    value = qBound(-1.0, value, 1.0);
    if (!qFuzzyCompare(m_brightness, value)) {
        m_brightness = value;
        saveSettings();
        syncColorToJpegEncoder();
        emit cameraSettingsChanged();
    }
}

void CaptureManager::setContrast(double value)
{
    value = qBound(0.0, value, 2.0);
    if (!qFuzzyCompare(m_contrast, value)) {
        m_contrast = value;
        saveSettings();
        syncColorToJpegEncoder();
        emit cameraSettingsChanged();
    }
}

void CaptureManager::setSaturation(double value)
{
    value = qBound(0.0, value, 2.0);
    if (!qFuzzyCompare(m_saturation, value)) {
        m_saturation = value;
        saveSettings();
        syncColorToJpegEncoder();
        emit cameraSettingsChanged();
    }
}

void CaptureManager::setHue(double value)
{
    value = qBound(-1.0, value, 1.0);
    if (!qFuzzyCompare(m_hue, value)) {
        m_hue = value;
        saveSettings();
        syncColorToJpegEncoder();
        emit cameraSettingsChanged();
    }
}

void CaptureManager::setGamma(double value)
{
    value = qBound(0.01, value, 10.0);
    if (!qFuzzyCompare(m_gamma, value)) {
        m_gamma = value;
        saveSettings();
        syncColorToJpegEncoder();
        emit cameraSettingsChanged();
    }
}

// 只应用曝光效果，不保存（用于滑动预览）
void CaptureManager::applyExposurePreview(double value)
{
    value = qBound(0.0, value, 100.0);
    m_exposure = value;
    
    // ⭐ 曝光值联动计算3个参数（亮度、色调不再联动）
    double slider = value;  // 0-100
    // m_brightness 不再联动，保持用户独立设置的值
    
    // ★ 饱和度线性公式：20→1.10, 100→1.35
    m_saturation = 1.0375 + 0.003125 * slider;
    
    // ★ 对比度线性公式：20→1.10, 100→1.35
    m_contrast = 1.0375 + 0.003125 * slider;
    
    // m_hue 不再联动，保持用户独立设置的值
    
    // ★ 伽马线性公式：20→1.08, 100→1.35
    m_gamma = 1.0125 + 0.003375 * slider;
    
    // 范围保护
    // m_brightness 保持独立设置的值
    m_saturation = qBound(1.0, m_saturation, 1.35);   // 饱和度范围
    m_contrast = qBound(1.0, m_contrast, 1.35);       // 对比度范围
    // m_hue 保持不变
    m_gamma = qBound(1.0, m_gamma, 1.35);             // 伽马范围
    
    // 只同步渲染到 GStreamer，不保存
    syncColorToJpegEncoder();
    emit cameraSettingsChanged();
}

void CaptureManager::setExposure(double value)
{
    // 曝光范围 0-100（百分比），与 Java 一致
    value = qBound(0.0, value, 100.0);
    if (!qFuzzyCompare(m_exposure, value)) {
        m_exposure = value;
        
        // ⭐ 曝光值联动计算3个参数（亮度、色调不再联动）
        double slider = value;  // 0-100
        // m_brightness 不再联动，保持用户独立设置的值
        
        // ★ 饱和度线性公式：20→1.10, 100→1.35
        m_saturation = 1.0375 + 0.003125 * slider;
        
        // ★ 对比度线性公式：20→1.10, 100→1.35
        m_contrast = 1.0375 + 0.003125 * slider;
        
        // m_hue 不再联动，保持用户独立设置的值
        
        // ★ 伽马线性公式：20→1.08, 100→1.35
        m_gamma = 1.0125 + 0.003375 * slider;
        
        // 范围保护
        // m_brightness 保持独立设置的值
        m_saturation = qBound(1.0, m_saturation, 1.35);   // 饱和度范围
        m_contrast = qBound(1.0, m_contrast, 1.35);       // 对比度范围
        // m_hue 保持不变
        m_gamma = qBound(1.0, m_gamma, 1.35);             // 伽马范围
        
        saveSettings();
        syncColorToJpegEncoder();  // 同步到 GStreamer videobalance 和 gamma
        emit cameraSettingsChanged();
        qDebug() << "📷 曝光联动更新: 曝光=" << value << "% → 饱和度=" << m_saturation 
                 << ", 对比度=" << m_contrast << ", 伽马=" << m_gamma
                 << " | 独立参数: 亮度=" << m_brightness << ", 色调=" << m_hue;
    }
}

void CaptureManager::resetCameraSettings()
{
    m_brightness = DEFAULT_BRIGHTNESS;
    m_contrast = DEFAULT_CONTRAST;
    m_saturation = DEFAULT_SATURATION;
    m_hue = DEFAULT_HUE;
    m_gamma = DEFAULT_GAMMA;
    m_exposure = DEFAULT_EXPOSURE;
    saveSettings();
    syncColorToJpegEncoder();
    emit cameraSettingsChanged();
    qDebug() << "Camera: settings reset to default";
}

void CaptureManager::zoomLog(const QString &msg)
{
    // 写入缩放调试日志到 zp.txt
    static QFile file(QCoreApplication::applicationDirPath() + "/zp.txt");
    static bool opened = false;
    
    if (!opened) {
        // 首次打开时清空文件
        file.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text);
        opened = true;
    }
    
    if (file.isOpen()) {
        QTextStream stream(&file);
        stream << QDateTime::currentDateTime().toString("[hh:mm:ss.zzz] ") << msg << "\n";
        stream.flush();
    }
}

void CaptureManager::syncColorToJpegEncoder()
{
    // 同步颜色参数到 GStreamer（使用 videobalance 和 gamma，不再使用 shader）
    if (m_gstPlayer) {
        m_gstPlayer->setAllImageParams(m_brightness, m_contrast, m_saturation, m_hue, m_gamma);
    }
    // 保留 GpuPipeline 的调用（如果存在）用于兼容
    if (m_gpuPipeline) {
        m_gpuPipeline->setJpegColorParams(m_brightness, m_contrast, m_saturation, m_hue, m_gamma);
    }
}

