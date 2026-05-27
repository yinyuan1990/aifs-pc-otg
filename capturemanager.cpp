#include "capturemanager.h"
#include "gpupipeline.h"
#include "imageprovider.h"
#include "slowmotionplayer.h"
#include "capturedebuglog.h"
#include <QtConcurrent>
#include <QStandardPaths>
#include <QCoreApplication>
#include <QElapsedTimer>
#include <QDir>
#include <QFile>
#include <QBuffer>
#include <QDebug>
#include <QTextStream>
#include <QDateTime>
#include <QTransform>

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
    for (auto &state : m_itemDecoders) {
        delete state.decoder;
    }
    m_itemDecoders.clear();
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
        if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
            disconnect(m_gstPlayer->naluFrameStore(), &NaluFrameStore::frameStored,
                       this, &CaptureManager::onFrameEncoded);
        }

        m_gstPlayer = player;

        if (m_gstPlayer->naluFrameStore()) {
            connect(m_gstPlayer->naluFrameStore(), &NaluFrameStore::frameStored,
                    this, &CaptureManager::onFrameEncoded, Qt::QueuedConnection);
        }
        qDebug() << "CaptureManager: GPU sync decoder ready";

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
    checkPendingCaptures(index);
}

void CaptureManager::checkPendingCaptures(qint64 frameIndex)
{
    QMutexLocker lock(&m_mutex);

    NaluFrameStore *store = (m_gstPlayer && m_gstPlayer->naluFrameStore())
                            ? m_gstPlayer->naluFrameStore() : nullptr;

    for (int i = m_pendingCaptures.size() - 1; i >= 0; i--) {
        PendingCapture &pending = m_pendingCaptures[i];

        if (pending.itemIndex < 0 || pending.itemIndex >= m_items.size()) {
            m_pendingCaptures.removeAt(i);
            continue;
        }

        CaptureItem &item = m_items[pending.itemIndex];

        // 保存到达的帧到磁盘
        if (store && frameIndex >= item.startIndex && frameIndex <= pending.targetEndIndex) {
            int offset = static_cast<int>(frameIndex - item.startIndex);
            if (offset >= 0 && offset < item.totalFrames() && store->hasFrame(frameIndex)) {
                QByteArray data = store->getFrame(frameIndex);
                QString path = item.naluDir + QString("/%1.nalu").arg(offset, 6, 10, QChar('0'));
                QFile file(path);
                if (file.open(QIODevice::WriteOnly)) {
                    file.write(data);
                }
                item.savedFrameCount++;
            }
        }

        // 检查是否完成
        if (frameIndex >= pending.targetEndIndex) {
            m_pendingCaptures.removeAt(i);
        }
    }
}

void CaptureManager::capture()
{
    
    qint64 eventIndex = -1;
    
    qDebug() << "📷 Capture: slowMotionActive=" << m_slowMotionActive
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

    if (m_gstPlayer) {
        m_gstPlayer->requestKeyFrame();
        captureDebugLog("CAP", "requestKeyFrame before capture");
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
        NaluFrameStore *store = m_gstPlayer->naluFrameStore();
        oldestAvailable = store->oldestIndex();
        startIndex = qMax(startIndex, oldestAvailable);

        qint64 idrIndex = -1;
        for (qint64 idx = eventIndex; idx >= oldestAvailable; --idx) {
            if (!store->hasFrame(idx)) {
                continue;
            }
            const QByteArray data = store->getFrame(idx);
            if (store->isKeyFrame(idx)
                || captureDebugAnnexBHasNalType(data, 5)
                || captureDebugAnnexBHasNalType(data, 7)) {
                idrIndex = idx;
                break;
            }
        }
        if (idrIndex >= 0 && idrIndex < startIndex) {
            captureDebugLog("CAP", QString("extend capture start to sync global=%1 was=%2")
                .arg(idrIndex).arg(startIndex));
            startIndex = idrIndex;
        }
    } else if (m_gpuPipeline) {
        oldestAvailable = m_gpuPipeline->oldestFrame();
        startIndex = qMax(startIndex, oldestAvailable);
    }
    
    qDebug() << "📷 Capture: 最终范围" << startIndex << "-" << endIndex 
             << "总帧数=" << (endIndex - startIndex + 1);
    
    CaptureItem item;
    item.id = m_nextId++;
    item.startIndex = startIndex;
    item.eventIndex = eventIndex;
    item.endIndex = endIndex;
    item.currentOffset = item.eventOffset();
    item.timestamp = QDateTime::currentMSecsSinceEpoch();

    // 创建磁盘目录存 NALU 文件
    item.naluDir = m_capturesDir + QString("/nalu_%1").arg(item.id, 6, 10, QChar('0'));
    QDir().mkpath(item.naluDir);

    // 保存 SPS/PPS 供离线回放解码器初始化
    if (m_gstPlayer) {
        const QByteArray spsPps = m_gstPlayer->spsPpsAnnexB();
        if (!spsPps.isEmpty()) {
            QFile spsFile(item.naluDir + "/sps_pps.bin");
            if (spsFile.open(QIODevice::WriteOnly)) {
                spsFile.write(spsPps);
                captureDebugLog("CAP", QString("saved sps_pps.bin size=%1 item=%2")
                    .arg(spsPps.size()).arg(item.id));
            } else {
                captureDebugLog("CAP", QString("WARN sps_pps.bin write FAIL item=%1").arg(item.id));
            }
        } else {
            captureDebugLog("CAP", QString("WARN no SPS/PPS at capture time item=%1").arg(item.id));
        }
    }

    // 直接抓取当前直播画面（已解码的 BGRA，零延迟兜底）
    if (m_gstPlayer) {
        item.liveSnapshot = m_gstPlayer->grabCurrentFrame();
    }
    if (!item.liveSnapshot.isNull() && m_videoRotation != 0) {
        QTransform transform;
        transform.rotate(m_videoRotation);
        item.liveSnapshot = item.liveSnapshot.transformed(transform, Qt::FastTransformation);
    }

    // 从环形缓冲拷贝已有帧到磁盘
    if (m_gstPlayer && m_gstPlayer->naluFrameStore()) {
        NaluFrameStore *store = m_gstPlayer->naluFrameStore();
        qint64 saveTo = qMin(endIndex, store->newestIndex());
        for (qint64 idx = startIndex; idx <= saveTo; idx++) {
            if (store->hasFrame(idx)) {
                int offset = static_cast<int>(idx - startIndex);
                QByteArray data = store->getFrame(idx);
                QString path = item.naluDir + QString("/%1.nalu").arg(offset, 6, 10, QChar('0'));
                QFile file(path);
                if (file.open(QIODevice::WriteOnly)) {
                    file.write(data);
                }
                item.savedFrameCount++;
            }
        }
    }

    m_items.append(item);
    int newIndex = m_items.size() - 1;

    // 后续帧待到达后再保存
    if (item.savedFrameCount < item.totalFrames()) {
        PendingCapture pending;
        pending.itemIndex = newIndex;
        pending.targetEndIndex = endIndex;
        m_pendingCaptures.append(pending);
    }

    qDebug() << "Capture: item" << item.id
             << "dir:" << item.naluDir
             << "range:" << startIndex << "-" << endIndex
             << "saved:" << item.savedFrameCount << "/" << item.totalFrames()
             << "keyframes:" << item.keyFrameOffsets.size();

    emit countChanged();
    emit itemAdded(newIndex);
    emit captureComplete(newIndex);

    scheduleFrameDecode(newIndex, item.currentOffset);
    for (int d = -3; d <= 3; ++d) {
        if (d == 0) continue;
        const int off = item.currentOffset + d;
        if (off >= 0 && off < item.totalFrames()) {
            scheduleFrameDecode(newIndex, off);
        }
    }
}


QByteArray CaptureManager::readNaluFile(const QString &dir, int frameOffset)
{
    QString path = dir + QString("/%1.nalu").arg(frameOffset, 6, 10, QChar('0'));
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return QByteArray();
    return file.readAll();
}

QImage CaptureManager::decodeFromDisk(int itemIndex, int frameOffset)
{
    CaptureDebugScope scope("CAP", QString("decodeFromDisk item=%1 frame=%2").arg(itemIndex).arg(frameOffset), 80);

    const int gen = m_clearGeneration.load(std::memory_order_acquire);

    QString naluDir;
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return QImage();
        const CaptureItem &item = m_items[itemIndex];
        if (frameOffset < 0 || frameOffset >= item.totalFrames()) return QImage();
        naluDir = item.naluDir;
    }

    QByteArray data = readNaluFile(naluDir, frameOffset);
    if (data.isEmpty()) {
        captureDebugLog("CAP", QString("decodeFromDisk file MISSING item=%1 frame=%2")
            .arg(itemIndex).arg(frameOffset));
        return QImage();
    }

    if (m_clearGeneration.load(std::memory_order_acquire) != gen) return QImage();

    QMutexLocker decodeLock(&m_decodeMutex);

    if (m_clearGeneration.load(std::memory_order_acquire) != gen) return QImage();

    ItemDecodeState &state = m_itemDecoders[itemIndex];
    if (!state.decoder) {
        state.decoder = new GstCaptureDecoder();
        captureDebugLog("CAP", QString("decodeFromDisk create decoder item=%1").arg(itemIndex));
    }

    QImage result = state.decoder->decodeNalu(data);

    if (result.isNull()) {
        captureDebugLog("CAP", QString("decodeFromDisk FAIL item=%1 frame=%2 %3")
            .arg(itemIndex).arg(frameOffset).arg(captureDebugNaluPreview(data)));
        return QImage();
    }

    state.lastOffset = frameOffset;

    if (m_videoRotation != 0) {
        QTransform t;
        t.rotate(m_videoRotation);
        result = result.transformed(t, Qt::FastTransformation);
    }

    captureDebugLog("CAP", QString("decodeFromDisk OK item=%1 frame=%2 size=%3x%4")
        .arg(itemIndex).arg(frameOffset).arg(result.width()).arg(result.height()));
    return result;
}

void CaptureManager::evictFrameCache()
{
    while (m_frameCache.size() >= MAX_FRAME_CACHE) {
        qint64 lruKey = -1;
        qint64 lruOrder = INT64_MAX;
        for (auto it = m_frameCache.begin(); it != m_frameCache.end(); ++it) {
            if (it.value().accessOrder < lruOrder) {
                lruOrder = it.value().accessOrder;
                lruKey = it.key();
            }
        }
        if (lruKey >= 0) m_frameCache.remove(lruKey);
        else break;
    }
}

void CaptureManager::clearAll()
{
    m_clearGeneration.fetch_add(1, std::memory_order_release);

    QMutexLocker lock(&m_mutex);
    m_pendingCaptures.clear();

    QStringList dirsToDelete;
    for (int i = 0; i < m_items.size(); i++) {
        if (!m_items[i].naluDir.isEmpty()) {
            dirsToDelete.append(m_items[i].naluDir);
        }
    }

    m_items.clear();
    m_cachedItemIndex = -1;
    m_cachedImage = QImage();
    {
        QMutexLocker decodeLock(&m_decodeMutex);
        m_frameCache.clear();
        m_frameCacheCounter = 0;
        m_pendingDecodes.clear();
        for (auto &state : m_itemDecoders) delete state.decoder;
        m_itemDecoders.clear();
    }

    emit countChanged();
    setCurrentItemIndex(-1);

    if (!dirsToDelete.isEmpty()) {
        QtConcurrent::run([dirsToDelete]() {
            for (const QString &dir : dirsToDelete) {
                QDir(dir).removeRecursively();
            }
        });
    }
}

void CaptureManager::removeItem(int index)
{
    m_clearGeneration.fetch_add(1, std::memory_order_release);

    QMutexLocker lock(&m_mutex);
    if (index < 0 || index >= m_items.size()) return;

    {
        QMutexLocker decodeLock(&m_decodeMutex);
        auto decIt = m_itemDecoders.find(index);
        if (decIt != m_itemDecoders.end()) {
            delete decIt.value().decoder;
            m_itemDecoders.erase(decIt);
        }
        for (auto it = m_frameCache.begin(); it != m_frameCache.end(); ) {
            if (static_cast<int>(it.key() / 100000) == index) {
                it = m_frameCache.erase(it);
            } else {
                ++it;
            }
        }
    }

    QString dirToDelete = m_items[index].naluDir;

    m_items.removeAt(index);
    m_cachedItemIndex = -1;
    m_cachedImage = QImage();

    for (auto &pending : m_pendingCaptures) {
        if (pending.itemIndex > index) {
            pending.itemIndex--;
        } else if (pending.itemIndex == index) {
            pending.itemIndex = -1;
        }
    }

    lock.unlock();
    emit itemRemoved(index);
    emit countChanged();

    if (!dirToDelete.isEmpty()) {
        QtConcurrent::run([dirToDelete]() {
            QDir(dirToDelete).removeRecursively();
        });
    }
}

void CaptureManager::removeOldest()
{
    if (m_items.isEmpty()) return;
    removeItem(0);
}

void CaptureManager::reset()
{
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
    int totalFrames = 0;
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return;

        CaptureItem &item = m_items[itemIndex];
        totalFrames = item.totalFrames();
        frameOffset = qBound(0, frameOffset, totalFrames - 1);

        if (item.currentOffset != frameOffset) {
            item.currentOffset = frameOffset;
        }
    }

    emit frameChanged(itemIndex, frameOffset);

    scheduleFrameDecode(itemIndex, frameOffset);
    for (int d = -2; d <= 2; ++d) {
        if (d == 0) continue;
        const int off = frameOffset + d;
        if (off >= 0 && off < totalFrames) {
            scheduleFrameDecode(itemIndex, off);
        }
    }
}

bool CaptureManager::tryGetFrameCache(int itemIndex, int frameOffset, QImage *out) const
{
    if (!out) return false;

    if (m_cachedItemIndex == itemIndex && m_cachedFrameOffset == frameOffset
        && m_cachedRotation == m_videoRotation
        && !m_cachedImage.isNull()) {
        *out = m_cachedImage;
        return true;
    }

    const qint64 key = qint64(itemIndex) * 100000 + frameOffset;
    auto cacheIt = m_frameCache.find(key);
    if (cacheIt != m_frameCache.end()) {
        *out = cacheIt.value().image;
        return true;
    }
    return false;
}

void CaptureManager::putFrameCache(int itemIndex, int frameOffset, const QImage &img)
{
    if (img.isNull()) return;

    evictFrameCache();
    const qint64 key = qint64(itemIndex) * 100000 + frameOffset;
    m_frameCache[key] = {img, ++m_frameCacheCounter};
    m_cachedItemIndex = itemIndex;
    m_cachedFrameOffset = frameOffset;
    m_cachedRotation = m_videoRotation;
    m_cachedImage = img;
}

void CaptureManager::scheduleFrameDecode(int itemIndex, int frameOffset)
{
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return;
        if (frameOffset < 0 || frameOffset >= m_items[itemIndex].totalFrames()) return;
    }

    const qint64 jobKey = qint64(itemIndex) * 1000000 + frameOffset;
    {
        QMutexLocker lock(&m_decodeMutex);
        QImage cached;
        if (tryGetFrameCache(itemIndex, frameOffset, &cached)) {
            return;
        }
        if (m_pendingDecodes.contains(jobKey)) {
            return;
        }
        m_pendingDecodes.insert(jobKey);
    }

    const int gen = m_clearGeneration.load(std::memory_order_acquire);

    (void)QtConcurrent::run([this, itemIndex, frameOffset, jobKey, gen]() {
        if (m_clearGeneration.load(std::memory_order_acquire) != gen) {
            QMutexLocker lock(&m_decodeMutex);
            m_pendingDecodes.remove(jobKey);
            return;
        }

        const QImage img = decodeFromDisk(itemIndex, frameOffset);
        bool shouldNotify = false;
        {
            QMutexLocker lock(&m_decodeMutex);
            m_pendingDecodes.remove(jobKey);
            if (!img.isNull()) {
                putFrameCache(itemIndex, frameOffset, img);
                shouldNotify = true;
            }
        }
        if (shouldNotify) {
            QMetaObject::invokeMethod(this, [this, itemIndex, frameOffset]() {
                emit frameImageReady(itemIndex, frameOffset);
            }, Qt::QueuedConnection);
        }
    });
}

void CaptureManager::nextFrame(int itemIndex)
{
    int newOffset;
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return;
        newOffset = m_items[itemIndex].currentOffset + 1;
    }
    gotoFrame(itemIndex, newOffset);
}

void CaptureManager::prevFrame(int itemIndex)
{
    int newOffset;
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return;
        newOffset = m_items[itemIndex].currentOffset - 1;
    }
    gotoFrame(itemIndex, newOffset);
}

void CaptureManager::gotoEventFrame(int itemIndex)
{
    int eventOff;
    {
        QMutexLocker lock(&m_mutex);
        if (itemIndex < 0 || itemIndex >= m_items.size()) return;
        eventOff = m_items[itemIndex].eventOffset();
    }
    gotoFrame(itemIndex, eventOff);
}

QImage CaptureManager::getFrameImage(int itemIndex, int frameOffset)
{
    CaptureDebugScope scope("CAP", QString("getFrameImage item=%1 frame=%2").arg(itemIndex).arg(frameOffset), 80);

    if (itemIndex < 0 || itemIndex >= m_items.size()) {
        captureDebugLog("CAP", QString("getFrameImage invalid item=%1").arg(itemIndex));
        return QImage();
    }

    QImage cached;
    QImage fallback;
    bool haveFallback = false;
    {
        QMutexLocker decodeLock(&m_decodeMutex);
        CaptureItem &item = m_items[itemIndex];

        if (tryGetFrameCache(itemIndex, frameOffset, &cached)) {
            auto cacheIt = m_frameCache.find(qint64(itemIndex) * 100000 + frameOffset);
            if (cacheIt != m_frameCache.end()) {
                cacheIt.value().accessOrder = ++m_frameCacheCounter;
            }
            captureDebugLog("CAP", "getFrameImage HIT cache");
            return cached;
        }

        if (m_cachedItemIndex == itemIndex && !m_cachedImage.isNull()) {
            fallback = m_cachedImage;
            haveFallback = true;
        } else if (!item.liveSnapshot.isNull()) {
            fallback = item.liveSnapshot;
            haveFallback = true;
        }
    }

    scheduleFrameDecode(itemIndex, frameOffset);

    if (haveFallback) {
        captureDebugLog("CAP", QString("getFrameImage ASYNC pending return fallback item=%1 frame=%2")
            .arg(itemIndex).arg(frameOffset));
        return fallback;
    }

    captureDebugLog("CAP", QString("getFrameImage ASYNC pending return empty item=%1 frame=%2")
        .arg(itemIndex).arg(frameOffset));
    return QImage();
}

void CaptureManager::setVideoRotation(int rotation)
{
    rotation = ((rotation % 360) + 360) % 360;
    if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
        rotation = 0;
    }

    if (m_videoRotation != rotation) {
        m_videoRotation = rotation;
        m_cachedImage = QImage();
        {
            QMutexLocker decodeLock(&m_decodeMutex);
            m_frameCache.clear();
            m_frameCacheCounter = 0;
        }
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

