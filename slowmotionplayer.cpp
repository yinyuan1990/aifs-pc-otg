#include "slowmotionplayer.h"
#include "gpupipeline.h"
#include "gstplayer.h"
#include "imageprovider.h"
#include <QCoreApplication>
#include <QFile>
#include <QDebug>
#include <QMutexLocker>
#include <QDateTime>
#include <QVideoFrame>
#include <QBuffer>

SlowMotionPlayer::SlowMotionPlayer(QObject *parent)
    : QObject(parent)
    , m_settings("Acard", "Aifs")
    , m_frameCache(FRAME_CACHE_SIZE)  // 初始化帧缓存
{
    qDebug() << "📦 SlowMotionPlayer 构造开始...";
    m_playbackTimer = new QTimer(this);
    connect(m_playbackTimer, &QTimer::timeout, this, &SlowMotionPlayer::onPlaybackTimer);
    qDebug() << "📦 SlowMotionPlayer 播放定时器已创建";
    
    // 自动注册到 ImageProvider
    if (CaptureImageProvider::instance()) {
        CaptureImageProvider::instance()->setSlowMotionPlayer(this);
        qDebug() << "SlowMotionPlayer: registered to ImageProvider";
    }
    
    // 加载持久化设置
    loadSettings();
    qDebug() << "📦 SlowMotionPlayer 构造完成";
}

SlowMotionPlayer::~SlowMotionPlayer()
{
    m_playbackTimer->stop();
    
    // 停止解码线程
    if (m_decodeThread) {
        m_decodeThread->stop();
        delete m_decodeThread;
        m_decodeThread = nullptr;
    }
    
    saveSettings();
}

void SlowMotionPlayer::loadSettings()
{
    m_playbackMultiplier = m_settings.value("slowmo/multiplier", 1.0).toDouble();
    m_maxFrameRate = m_settings.value("slowmo/maxFrameRate", 60).toInt();
    m_maxFrames = m_settings.value("slowmo/maxFrames", 5000).toInt();
    
    // 确保值在有效范围内
    m_playbackMultiplier = qBound(MULTIPLIER_MIN, m_playbackMultiplier, MULTIPLIER_MAX);
    m_maxFrameRate = qBound(1, m_maxFrameRate, 120);
    m_maxFrames = qBound(100, m_maxFrames, 10000);
    
    qDebug() << "SlowMotionPlayer: loaded settings - multiplier:" << m_playbackMultiplier
             << "maxFps:" << m_maxFrameRate << "maxFrames:" << m_maxFrames;
}

void SlowMotionPlayer::saveSettings()
{
    m_settings.setValue("slowmo/multiplier", m_playbackMultiplier);
    m_settings.setValue("slowmo/maxFrameRate", m_maxFrameRate);
    m_settings.setValue("slowmo/maxFrames", m_maxFrames);
    m_settings.sync();
}

void SlowMotionPlayer::setGpuPipeline(GpuPipeline* pipeline)
{
    if (m_gpuPipeline != pipeline) {
        // 停止旧的解码线程
        if (m_decodeThread) {
            m_decodeThread->stop();
            delete m_decodeThread;
            m_decodeThread = nullptr;
        }
        
        m_gpuPipeline = pipeline;
        
        // 创建新的解码线程
        if (m_gpuPipeline) {
            m_decodeThread = new SlowMotionDecodeThread(m_gpuPipeline, this);
            connect(m_decodeThread, &SlowMotionDecodeThread::frameDecoded,
                    this, &SlowMotionPlayer::onFrameDecoded, Qt::QueuedConnection);
            m_decodeThread->start();
            qDebug() << "SlowMotionPlayer: decode thread started";
        }
        
        // 注意：jpegEncoder 可能在 init() 后才创建，所以连接在 startRecording() 中建立
        emit gpuPipelineChanged();
    }
}

void SlowMotionPlayer::setGstPlayer(GstPlayer* player)
{
    if (m_gstPlayer != player) {
        // 断开旧连接
        if (m_gstPlayer) {
            disconnect(m_gstPlayer, &GstPlayer::frameEncoded,
                       this, &SlowMotionPlayer::onFrameEncoded);
        }
        
        m_gstPlayer = player;
        
        // 建立新连接（JPEG 保存成功后的回调）
        if (m_gstPlayer) {
            connect(m_gstPlayer, &GstPlayer::frameEncoded,
                    this, &SlowMotionPlayer::onFrameEncoded, Qt::QueuedConnection);
            qDebug() << "SlowMotionPlayer: connected to gstPlayer.frameEncoded";
        }
        
        emit gstPlayerChanged();
        qDebug() << "SlowMotionPlayer: gstPlayer set, framesDir:" << (player ? player->framesDir() : "null");
    }
}

void SlowMotionPlayer::setVideoSink(QVideoSink* sink)
{
    if (m_videoSink != sink) {
        m_videoSink = sink;
        emit videoSinkChanged();
        qDebug() << "SlowMotionPlayer: videoSink set to" << sink;
    }
}

void SlowMotionPlayer::ensureJpegEncoderConnected()
{
    // 确保与 jpegEncoder 的连接已建立
    if (m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
        // 先断开防止重复连接
        disconnect(m_gpuPipeline->jpegEncoder(), &GpuJpegEncoder::frameEncoded,
                   this, &SlowMotionPlayer::onFrameEncoded);
        // 建立连接（使用 Qt::QueuedConnection 确保跨线程安全）
        bool ok = connect(m_gpuPipeline->jpegEncoder(), &GpuJpegEncoder::frameEncoded,
                          this, &SlowMotionPlayer::onFrameEncoded, Qt::QueuedConnection);
        qDebug() << "SlowMotionPlayer: connected to jpegEncoder frameEncoded signal, success:" << ok;
    } else {
        qWarning() << "SlowMotionPlayer: cannot connect - gpuPipeline:" << m_gpuPipeline
                   << "jpegEncoder:" << (m_gpuPipeline ? m_gpuPipeline->jpegEncoder() : nullptr);
    }
}

void SlowMotionPlayer::setState(State state)
{
    if (m_state != state) {
        m_state = state;
        emit stateChanged();
        emit hasContentChanged();
    }
}

void SlowMotionPlayer::setCurrentFrame(int frame)
{
    if (m_recordedFrames <= 0) return;
    
    frame = qBound(0, frame, m_recordedFrames - 1);
    if (m_currentFrame != frame) {
        m_currentFrame = frame;
        emit currentFrameChanged();
        
        // 如果有 videoSink，直接渲染
        if (m_videoSink) {
            renderToVideoSink(m_currentFrame);
        }
    }
}

void SlowMotionPlayer::setMaxFrames(int frames)
{
    frames = qBound(100, frames, 10000);
    if (m_maxFrames != frames) {
        m_maxFrames = frames;
        emit maxFramesChanged();
        saveSettings();
    }
}

void SlowMotionPlayer::setPlaybackMultiplier(double multiplier)
{
    multiplier = qBound(1.0, multiplier, 10.0);
    if (!qFuzzyCompare(m_playbackMultiplier, multiplier)) {
        double oldMultiplier = m_playbackMultiplier;
        m_playbackMultiplier = multiplier;
        emit playbackMultiplierChanged();
        saveSettings();
        
        // 录制状态下切换倍数：更新 followLive 和定时器
        if (m_state == RECORDING) {
            bool wasFollowLive = m_followLive;
            m_followLive = (multiplier <= 1.0);  // 使用 <= 1 确保 1x 模式正确启用
            
            Q_UNUSED(oldMultiplier);
            Q_UNUSED(wasFollowLive);
            
            if (multiplier <= 1.0) {
                // 切换到 1x：停止定时器，跟随实时流
                m_playbackTimer->stop();
                // 立即跳到最新帧
                if (m_recordedFrames > 0) {
                    m_currentFrame = m_recordedFrames - 1;
                    emit currentFrameChanged();
                    if (m_videoSink) {
                        renderToVideoSink(m_currentFrame);
                    }
                }
            } else {
                // 切换到 >1x：启动定时器，开始减速播放
                updateTimerInterval();
                if (!m_playbackTimer->isActive()) {
                    m_playbackTimer->start();
                }
            }
        } else if (m_isPlaying) {
            // 回放状态：只更新定时器间隔
            updateTimerInterval();
        }
    }
}

void SlowMotionPlayer::setMaxFrameRate(int fps)
{
    fps = qBound(1, fps, 120);
    if (m_maxFrameRate != fps) {
        m_maxFrameRate = fps;
        emit maxFrameRateChanged();
        saveSettings();
        
        if (m_isPlaying) {
            updateTimerInterval();
        }
    }
}

void SlowMotionPlayer::startRecording()
{
    // 优先使用 gstPlayer
    qint64 newest = -1;
    if (m_gstPlayer) {
        newest = m_gstPlayer->newestFrame();
    } else if (m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
        newest = m_gpuPipeline->newestFrame();
        ensureJpegEncoderConnected();
    }
    
    if (newest < 0) {
        qWarning() << "SlowMotionPlayer: cannot start recording - no frames available";
        return;
    }
    
    // 如果已经有内容，先清空
    if (m_state != IDLE) {
        clear();
    }
    
    // 记录起始帧索引
    m_startIndex = newest;  // 从当前最新帧开始
    m_endIndex = m_startIndex;
    m_currentFrame = 0;
    m_recordedFrames = 0;  // 开始时没有录制帧，等第一帧到来
    
    // ⭐ 保存当前会话前缀（用于后续读取JPEG文件）
    if (m_gstPlayer) {
        m_sessionPrefix = m_gstPlayer->sessionPrefix();
        qDebug() << "SlowMotionPlayer: 保存会话前缀:" << m_sessionPrefix;
        
        // ⭐ 同步前缀到解码线程
        if (m_decodeThread) {
            m_decodeThread->setSessionPrefix(m_sessionPrefix);
        }
    }
    
    // 1x跟随实时流，大于1x直接慢放
    m_followLive = (m_playbackMultiplier == 1);
    
    qDebug() << "SlowMotionPlayer: startIndex:" << m_startIndex << "multiplier:" << m_playbackMultiplier << "followLive:" << m_followLive;
    
    // 注册有效范围（保护帧不被清理）
    if (m_gstPlayer) {
        m_validRangeId = m_gstPlayer->registerValidRange(m_startIndex, m_startIndex + m_maxFrames);
    } else if (m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
        m_validRangeId = m_gpuPipeline->jpegEncoder()->registerValidRange(m_startIndex, m_startIndex + m_maxFrames);
    }
    
    setState(RECORDING);
    
    // 触发信号让 QML Image 刷新
    emit currentFrameChanged();
    emit recordedFramesChanged();
    
    // 开始播放
    m_isPlaying = true;
    updateTimerInterval();
    
    // 大于1x直接启动定时器慢放，1x跟随实时流不需要定时器
    if (!m_followLive) {
        m_playbackTimer->start();
    }
    emit playingChanged();
    
    qDebug() << "SlowMotionPlayer: started recording at index" << m_startIndex
             << "multiplier:" << m_playbackMultiplier
             << "followLive:" << m_followLive
             << "endIndex:" << m_endIndex;
}

void SlowMotionPlayer::stopRecording()
{
    if (m_state != RECORDING) return;
    
    pause();
    
    // ⭐ 进入回放模式，使用原图（不缩放）
    m_followLive = false;
    
    // 更新有效范围为实际录制的范围
    if (m_validRangeId >= 0) {
        if (m_gstPlayer) {
            m_gstPlayer->unregisterValidRange(m_validRangeId);
            m_validRangeId = m_gstPlayer->registerValidRange(m_startIndex, m_endIndex);
        } else if (m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
            m_gpuPipeline->jpegEncoder()->unregisterValidRange(m_validRangeId);
            m_validRangeId = m_gpuPipeline->jpegEncoder()->registerValidRange(m_startIndex, m_endIndex);
        }
    }
    
    setState(PLAYBACK);
    
    // 跳到第一帧
    setCurrentFrame(0);
    
    qDebug() << "SlowMotionPlayer: stopped recording, frames:" << m_recordedFrames
             << "range: [" << m_startIndex << "-" << m_endIndex << "]";
}

void SlowMotionPlayer::clear()
{
    pause();
    
    // 取消注册有效范围（允许清理）
    if (m_validRangeId >= 0) {
        if (m_gstPlayer) {
            m_gstPlayer->unregisterValidRange(m_validRangeId);
        } else if (m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
            m_gpuPipeline->jpegEncoder()->unregisterValidRange(m_validRangeId);
        }
        m_validRangeId = -1;
    }
    
    // 清空帧缓存
    {
        QMutexLocker locker(&m_cacheMutex);
        m_frameCache.clear();
    }
    
    m_startIndex = -1;
    m_endIndex = -1;
    m_currentFrame = 0;
    m_recordedFrames = 0;
    m_followLive = true;
    m_sessionPrefix.clear();  // ⭐ 清空会话前缀
    
    setState(IDLE);
    emit recordedFramesChanged();
    emit currentFrameChanged();
    
    qDebug() << "SlowMotionPlayer: cleared";
}

qint64 SlowMotionPlayer::currentGlobalFrameIndex() const
{
    if (m_startIndex < 0 || m_recordedFrames <= 0) {
        return -1;  // 无有效数据
    }
    
    // 当前帧的全局索引 = 起始索引 + 当前帧偏移
    qint64 globalIndex = m_startIndex + m_currentFrame;
    
    // 确保不超出范围
    if (globalIndex > m_endIndex) {
        globalIndex = m_endIndex;
    }
    
    return globalIndex;
}

void SlowMotionPlayer::play()
{
    if (m_recordedFrames <= 0 && m_state != RECORDING) return;
    
    if (!m_isPlaying) {
        m_isPlaying = true;
        
        // 根据状态和倍数决定播放方式
        if (m_state == RECORDING && m_playbackMultiplier <= 1.0) {
            // 录制中 + 1倍：恢复跟随实时流，不需要定时器
            m_followLive = true;
            // 立即跳到最新帧
            if (m_recordedFrames > 0) {
                m_currentFrame = m_recordedFrames - 1;
                emit currentFrameChanged();
                if (m_videoSink) {
                    renderToVideoSink(m_currentFrame);
                }
            }
        } else {
            // 其他情况：定时器播放
            updateTimerInterval();
            m_playbackTimer->start();
        }
        
        emit playingChanged();
    }
}

void SlowMotionPlayer::pause()
{
    if (m_isPlaying) {
        m_isPlaying = false;
        m_playbackTimer->stop();
        emit playingChanged();
    }
}

void SlowMotionPlayer::togglePlay()
{
    if (m_isPlaying) {
        pause();
    } else {
        play();
    }
}

void SlowMotionPlayer::nextFrame()
{
    // 用户手动操作：暂停播放，切换到手动模式
    if (m_followLive) {
        // 从跟随实时流切换到慢放，当前帧位置不变
        m_followLive = false;
    }
    // 暂停定时器
    if (m_playbackTimer->isActive()) {
        m_playbackTimer->stop();
        m_isPlaying = false;
        emit playingChanged();
    }
    
    if (m_currentFrame < m_recordedFrames - 1) {
        setCurrentFrame(m_currentFrame + 1);
    }
}

void SlowMotionPlayer::prevFrame()
{
    // 用户手动操作：暂停播放，切换到手动模式
    if (m_followLive) {
        // 从跟随实时流切换到慢放，当前帧位置不变
        m_followLive = false;
    }
    // 暂停定时器
    if (m_playbackTimer->isActive()) {
        m_playbackTimer->stop();
        m_isPlaying = false;
        emit playingChanged();
    }
    
    if (m_currentFrame > 0) {
        setCurrentFrame(m_currentFrame - 1);
    }
}

void SlowMotionPlayer::jumpToFrame(int frame)
{
    // 用户手动跳转（拖动滑块），停止跟随实时流
    m_followLive = false;
    setCurrentFrame(frame);
    
    // 如果正在播放但定时器没启动（1x倍速时），启动定时器
    if (m_isPlaying && !m_playbackTimer->isActive()) {
        updateTimerInterval();
        m_playbackTimer->start();
    }
}

QImage SlowMotionPlayer::getFrameImage(int frameOffset) const
{
    if (m_startIndex < 0) {
        return QImage();
    }
    
    qint64 globalIndex = m_startIndex + frameOffset;
    
    // 录制中时，如果请求的帧超出当前范围，使用最新帧
    if (m_state == RECORDING && globalIndex > m_endIndex) {
        globalIndex = m_endIndex;
    } else if (globalIndex > m_endIndex) {
        return QImage();
    }
    
    QImage img;
    
    // ⭐ 优先使用 GstPlayer 读取 JPEG（使用录制时的会话前缀）
    if (m_gstPlayer) {
        QByteArray jpegData;
        if (!m_sessionPrefix.isEmpty()) {
            jpegData = m_gstPlayer->getJpegWithPrefix(globalIndex, m_sessionPrefix);
        } else {
            jpegData = m_gstPlayer->getJpeg(globalIndex);
        }
        if (!jpegData.isEmpty()) {
            img.loadFromData(jpegData, "JPEG");
        }
    }
    
    // 回退到 GpuPipeline
    if (img.isNull() && m_gpuPipeline && m_gpuPipeline->jpegEncoder()) {
        QByteArray frameData = m_gpuPipeline->jpegEncoder()->getFrameData(globalIndex);
        if (!frameData.isEmpty()) {
            auto decoder = m_gpuPipeline->jpegDecoder();
            if (decoder) {
                img = decoder->decodeToImage(frameData);
            }
        }
    }
    
    if (img.isNull()) {
        return QImage();
    }
    
    // 追实时流时，快速缩放到640宽度（FastTransformation约5-8ms）
    if (m_state == RECORDING && m_followLive && img.width() > 640) {
        int newWidth = 640;
        int newHeight = img.height() * 640 / img.width();
        return img.scaled(newWidth, newHeight, Qt::KeepAspectRatio, Qt::FastTransformation);
    }
    
    return img;
}

bool SlowMotionPlayer::saveCurrentFrame(const QString &path)
{
    QImage img = getFrameImage(m_currentFrame);
    if (img.isNull()) {
        return false;
    }
    return img.save(path, "JPEG", 95);
}

void SlowMotionPlayer::onPlaybackTimer()
{
    // 慢放模式（非跟随实时流）：定时器推进帧
    if ((m_state == RECORDING && !m_followLive) || m_state == PLAYBACK) {
        // 检查是否有帧可以推进
        bool canAdvance = (m_recordedFrames > 0 && m_currentFrame < m_recordedFrames - 1);
        
        if (canAdvance) {
            m_currentFrame = m_currentFrame + 1;
            emit currentFrameChanged();
            // 如果有 videoSink，直接渲染
            if (m_videoSink) {
                renderToVideoSink(m_currentFrame);
            }
        } else if (m_state == PLAYBACK && m_currentFrame >= m_recordedFrames - 1) {
            // 回放模式到达末尾，停止
            pause();
        }
        // 录制模式追上进度时，等待新帧（定时器继续运行）
    }
}

void SlowMotionPlayer::onFrameEncoded(qint64 frameIndex)
{
    if (m_state != RECORDING) return;
    if (m_startIndex < 0) return;
    
    // 检查是否在我们的范围内
    if (frameIndex >= m_startIndex) {
        updateEndIndex(frameIndex);
    }
    
}

void SlowMotionPlayer::updateEndIndex(qint64 frameIndex)
{
    if (frameIndex >= m_startIndex && frameIndex > m_endIndex) {
        m_endIndex = frameIndex;
        int newRecorded = static_cast<int>(m_endIndex - m_startIndex + 1);
        
        if (newRecorded != m_recordedFrames) {
            bool wasEmpty = (m_recordedFrames == 0);
            m_recordedFrames = newRecorded;
            emit recordedFramesChanged();
            
            // 第一帧到达时，通知 hasContent 变化
            if (wasEmpty && m_recordedFrames > 0) {
                emit hasContentChanged();
            }
            
            // 1x跟随实时流模式：显示最新帧（必须正在播放状态才渲染）
            if (m_state == RECORDING && m_followLive && m_isPlaying && m_videoSink) {
                m_currentFrame = m_recordedFrames - 1;
                emit currentFrameChanged();
                renderToVideoSink(m_currentFrame);
            }
            
            // 检查是否达到最大帧数
            if (m_recordedFrames >= m_maxFrames) {
                qDebug() << "SlowMotionPlayer: reached max frames, stopping recording";
                stopRecording();
            }
        }
    }
}

void SlowMotionPlayer::updateTimerInterval()
{
    // 实际帧率 = 最大帧率 / 倍数
    double fps = static_cast<double>(m_maxFrameRate) / m_playbackMultiplier;
    int interval = static_cast<int>(1000.0 / fps);
    m_playbackTimer->setInterval(qMax(1, interval));
}

void SlowMotionPlayer::emitCurrentFrame()
{
    QImage img = getFrameImage(m_currentFrame);
    if (!img.isNull()) {
        emit frameReady(img);
    }
}

void SlowMotionPlayer::renderToVideoSink(int frameOffset)
{
    if (!m_videoSink || !m_gpuPipeline || m_startIndex < 0) {
        return;
    }
    
    qint64 globalIndex = m_startIndex + frameOffset;
    
    // 录制中时，如果请求的帧超出当前范围，使用最新帧
    if (m_state == RECORDING && globalIndex > m_endIndex) {
        globalIndex = m_endIndex;
    } else if (globalIndex > m_endIndex) {
        return;
    }
    
    // 异步解码：将请求发送到解码线程
    // followLive=true 时缩放（追时时流），false 时原图（回放）
    if (m_decodeThread) {
        m_pendingFrameOffset = frameOffset;  // 记录最新请求，用于跳帧
        m_decodeThread->requestDecode(globalIndex, frameOffset, m_followLive);
    } else {
        qWarning() << "SlowMotionPlayer::renderToVideoSink: no decode thread!";
    }
}

void SlowMotionPlayer::onFrameDecoded(int frameOffset, const QVideoFrame &frame)
{
    // 跳帧优化：如果已经有更新的请求，忽略旧帧
    // 但如果当前在 PLAYBACK 状态，不跳帧（需要顺序播放）
    if (m_state == RECORDING && m_followLive && frameOffset != m_pendingFrameOffset) {
        return;  // 跳过旧帧
    }
    
    if (m_videoSink && frame.isValid()) {
        m_videoSink->setVideoFrame(frame);
    }
}

// ============ SlowMotionDecodeThread 实现 ============

SlowMotionDecodeThread::SlowMotionDecodeThread(GpuPipeline *pipeline, QObject *parent)
    : QThread(parent)
    , m_pipeline(pipeline)
{
}

void SlowMotionDecodeThread::setSessionPrefix(const QString &prefix)
{
    QMutexLocker locker(&m_queueMutex);
    m_sessionPrefix = prefix;
    qDebug() << "SlowMotionDecodeThread: 设置会话前缀:" << m_sessionPrefix;
}

SlowMotionDecodeThread::~SlowMotionDecodeThread()
{
    stop();
}

void SlowMotionDecodeThread::stop()
{
    m_running = false;
    m_queueCondition.wakeAll();
    if (isRunning()) {
        wait(3000);
    }
}

void SlowMotionDecodeThread::requestDecode(qint64 globalFrameIndex, int frameOffset, bool scale)
{
    QMutexLocker locker(&m_queueMutex);
    
    // 清空队列，只保留最新请求（跳帧优化）
    m_decodeQueue.clear();
    m_decodeQueue.enqueue({globalFrameIndex, frameOffset, scale});
    
    m_queueCondition.wakeOne();
}

void SlowMotionDecodeThread::run()
{
    while (m_running) {
        DecodeRequest request{-1, -1, false};
        
        {
            QMutexLocker locker(&m_queueMutex);
            while (m_decodeQueue.isEmpty() && m_running) {
                m_queueCondition.wait(&m_queueMutex);
            }
            
            if (!m_running) break;
            
            // 取出最新的请求
            request = m_decodeQueue.dequeue();
        }
        
        if (request.globalIndex >= 0) {
            QString sessionPrefix;
            {
                QMutexLocker locker(&m_queueMutex);
                sessionPrefix = m_sessionPrefix;
            }
            QString jpegPath = QCoreApplication::applicationDirPath() +
                QString("/captures/frames/%1_%2.png").arg(sessionPrefix).arg(request.globalIndex, 9, 10, QChar('0'));
            
            QFile file(jpegPath);
            if (file.open(QIODevice::ReadOnly)) {
                QByteArray jpegData = file.readAll();
                file.close();
                
                QImage img;
                if (img.loadFromData(jpegData, "JPEG")) {
                    int origW = img.width();
                    int origH = img.height();
                    
                    // ⭐ 只有追时时流(scale=true)时才缩放，回放/滚轮/小键盘都用原图
                    if (request.scale && (origW > 640 || origH > 480)) {
                        img = img.scaled(640, 480, Qt::KeepAspectRatio, Qt::FastTransformation);
                    }
                    
                    // 转换为 QVideoFrame
                    QVideoFrame frame(QVideoFrameFormat(img.size(), QVideoFrameFormat::Format_BGRA8888));
                    if (frame.map(QVideoFrame::WriteOnly)) {
                        QImage converted = img.convertToFormat(QImage::Format_ARGB32);
                        memcpy(frame.bits(0), converted.bits(), converted.sizeInBytes());
                        frame.unmap();
                        emit frameDecoded(request.frameOffset, frame);
                    }
                }
            } else {
                qWarning() << "❌ SlowMotion: 无法打开文件" << jpegPath;
            }
        }
    }
    
    qDebug() << "SlowMotionDecodeThread: stopped";
}
