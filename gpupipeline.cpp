#include "gpupipeline.h"
#include <QDebug>
#include <QSGSimpleTextureNode>
#include <QQuickWindow>
#include <QOpenGLContext>
#include <QOpenGLFunctions>
#include <QBuffer>
#include <QVideoFrameFormat>
#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QDateTime>
#include <cmath>
#include <algorithm>

#ifdef Q_OS_WIN
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#endif

extern "C" {
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
}

// ==================== GpuDecoder ====================

GpuDecoder::GpuDecoder(QObject *parent)
    : QObject(parent)
{
}

GpuDecoder::~GpuDecoder()
{
    cleanup();
}

void GpuDecoder::cleanup()
{
    if (m_parser) {
        av_parser_close(m_parser);
        m_parser = nullptr;
    }
    if (m_codecCtx) {
        avcodec_free_context(&m_codecCtx);
        m_codecCtx = nullptr;
    }
    if (m_hwDeviceCtx) {
        av_buffer_unref(&m_hwDeviceCtx);
        m_hwDeviceCtx = nullptr;
    }
    if (m_swsCtx) {
        sws_freeContext(m_swsCtx);
        m_swsCtx = nullptr;
    }
    m_swsWidth = 0;
    m_swsHeight = 0;
    m_swsFmt = -1;
}

void GpuDecoder::reinit()
{
    qDebug() << "⭐ GpuDecoder::reinit() - 重新初始化解码器（分辨率变化）";
    cleanup();
    m_hwAccel = false;
    m_decodeErrors = 0;
    m_consecutiveErrors = 0;
    m_needReinit = false;
    m_width = 0;   // ⭐ 重置分辨率，让新分辨率的帧能正确初始化
    m_height = 0;
    // 不重置 m_frameIndex，保持帧计数连续
    if (init()) {
        qDebug() << "⭐ GpuDecoder::reinit() - 成功，等待下一个关键帧";
        emit decoderReinitialized();
        // 不主动请求关键帧，避免影响 WebRTC 状态
        // 等待流中的下一个自然关键帧即可
    }
}

bool GpuDecoder::init()
{
    qDebug() << "🔧 GpuDecoder::init() 开始";
    
    // 尝试硬件解码
    qDebug() << "🔧 尝试初始化硬件解码器 (NVIDIA CUVID)...";
    if (initHardwareDecoder()) {
        qDebug() << "✅ 硬件解码器初始化成功:" << m_decoderName;
        return true;
    }
    qDebug() << "⚠️ 硬件解码器初始化失败，尝试软件解码...";
    
    // 回退到软件解码
    qDebug() << "🔧 尝试初始化软件解码器...";
    if (initSoftwareDecoder()) {
        qDebug() << "✅ 软件解码器初始化成功:" << m_decoderName;
        return true;
    }
    
    qCritical() << "❌ 所有解码器初始化失败！";
    return false;
}

bool GpuDecoder::initHardwareDecoder()
{
#ifdef Q_OS_WIN
    // ⭐ 只使用 NVIDIA CUVID（GPU 直通到 NVENC）- 不支持回退
    const AVCodec *codec = avcodec_find_decoder_by_name("h264_cuvid");
    if (!codec) {
        qCritical() << "❌ NVIDIA CUVID 解码器不可用，请确保安装了 NVIDIA 显卡和驱动";
        emit error("硬件不支持：需要 NVIDIA 显卡（CUVID）");
        return false;
    }
    
    int ret = av_hwdevice_ctx_create(&m_hwDeviceCtx, AV_HWDEVICE_TYPE_CUDA, nullptr, nullptr, 0);
    if (ret < 0) {
        char errBuf[256];
        av_strerror(ret, errBuf, sizeof(errBuf));
        qCritical() << "❌ CUDA 设备创建失败:" << errBuf;
        emit error("CUDA 初始化失败，请检查 NVIDIA 驱动");
        return false;
    }
    
    m_codecCtx = avcodec_alloc_context3(codec);
    if (!m_codecCtx) {
        av_buffer_unref(&m_hwDeviceCtx);
        return false;
    }
    
    m_codecCtx->hw_device_ctx = av_buffer_ref(m_hwDeviceCtx);
    m_codecCtx->thread_count = 1;
    
    AVDictionary *opts = nullptr;
    av_dict_set(&opts, "gpu", "0", 0);
    av_dict_set(&opts, "surfaces", "16", 0);
    
    ret = avcodec_open2(m_codecCtx, codec, &opts);
    av_dict_free(&opts);
    
    if (ret < 0) {
        char errBuf[256];
        av_strerror(ret, errBuf, sizeof(errBuf));
        qCritical() << "❌ CUVID 解码器打开失败:" << errBuf;
        avcodec_free_context(&m_codecCtx);
        av_buffer_unref(&m_hwDeviceCtx);
        return false;
    }
    
    m_parser = av_parser_init(AV_CODEC_ID_H264);
    if (!m_parser) {
        qWarning() << "Failed to create H264 parser";
        avcodec_free_context(&m_codecCtx);
        av_buffer_unref(&m_hwDeviceCtx);
        return false;
    }
    
    m_hwAccel = true;
    m_decoderName = "h264_cuvid (CUDA → GPU Direct)";
    qDebug() << "⭐ NVIDIA CUVID 初始化成功 - GPU 直通模式";
    return true;
#else
    return false;
#endif
}

bool GpuDecoder::initSoftwareDecoder()
{
    // ❌ 不支持软件解码
    qCritical() << "❌ 软件解码不支持，需要 NVIDIA 显卡";
    return false;
    
    // 以下代码保留但不使用
    const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
    if (!codec) {
        return false;
    }
    
    m_codecCtx = avcodec_alloc_context3(codec);
    if (!m_codecCtx) {
        return false;
    }
    
    m_codecCtx->thread_count = 4;
    m_codecCtx->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    
    if (avcodec_open2(m_codecCtx, codec, nullptr) < 0) {
        avcodec_free_context(&m_codecCtx);
        return false;
    }
    
    m_parser = av_parser_init(AV_CODEC_ID_H264);
    if (!m_parser) {
        return false;
    }
    
    m_hwAccel = false;
    m_decoderName = QString("Software (%1)").arg(codec->name);
    
    return true;
}

void GpuDecoder::decodeNalu(const QByteArray &nalu, bool isKeyFrame)
{
    Q_UNUSED(isKeyFrame);
    
    // ⭐ 检查是否需要重新初始化（分辨率变化后）
    if (m_needReinit) {
        reinit();
    }
    
    if (!m_codecCtx || !m_parser) {
        qWarning() << "GpuDecoder: codec not initialized";
        return;
    }
    
    static int naluCount = 0;
    naluCount++;
    
    const uint8_t *data = reinterpret_cast<const uint8_t*>(nalu.constData());
    int size = nalu.size();
    
    AVPacket *pkt = av_packet_alloc();
    
    while (size > 0) {
        int ret = av_parser_parse2(m_parser, m_codecCtx,
                                    &pkt->data, &pkt->size,
                                    data, size,
                                    AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
        
        if (ret < 0) {
            break;
        }
        
        data += ret;
        size -= ret;
        
        if (pkt->size > 0) {
            ret = avcodec_send_packet(m_codecCtx, pkt);
            if (ret < 0) {
                char errBuf[256];
                av_strerror(ret, errBuf, sizeof(errBuf));
                
                m_consecutiveErrors++;
                m_decodeErrors++;
                
                // ⭐ 检测 CUVID 分辨率变化错误，需要重新初始化
                QString errStr(errBuf);
                if (errStr.contains("incompatible") || errStr.contains("INVALID_HANDLE") || 
                    m_consecutiveErrors >= 10) {
                    qWarning() << "⚠️ GpuDecoder: 检测到分辨率变化或持续错误，标记需要重新初始化";
                    m_needReinit = true;
                    av_packet_free(&pkt);
                    return;  // 退出当前解码，下次调用时重新初始化
                }
                
                if (naluCount <= 10) {
                    qWarning() << "GpuDecoder: send_packet error:" << errBuf;
                }
                
                if (m_decodeErrors >= 6) {
                    qint64 now = QDateTime::currentMSecsSinceEpoch();
                    if (now - m_lastKeyframeRequest > 2000) {
                        qWarning() << "Too many decode errors (" << m_decodeErrors << "), requesting keyframe";
                        emit keyframeNeeded();
                        m_lastKeyframeRequest = now;
                        m_decodeErrors = 0;
                    }
                }
                continue;
            }
            
            // 成功发送，重置连续错误计数
            m_consecutiveErrors = 0;
            
            AVFrame *frame = av_frame_alloc();
            if (!frame) {
                qWarning() << "GpuDecoder: failed to alloc frame";
                continue;
            }
            
            while ((ret = avcodec_receive_frame(m_codecCtx, frame)) >= 0) {
                m_decodeErrors = 0;
                try {
                    processFrame(frame);
                } catch (...) {
                    // 忽略异常
                }
                av_frame_unref(frame);
            }
            if (ret != AVERROR(EAGAIN) && ret != AVERROR_EOF) {
                char errBuf[256];
                av_strerror(ret, errBuf, sizeof(errBuf));
                if (naluCount <= 10) {
                    qDebug() << "GpuDecoder: receive_frame:" << errBuf;
                }
            }
            av_frame_free(&frame);
        }
    }
    
    av_packet_free(&pkt);
}

void GpuDecoder::processFrame(AVFrame *frame)
{
    auto gpuFrame = std::make_shared<GpuFrame>();
    gpuFrame->frameIndex = m_frameIndex++;
    gpuFrame->pts = frame->pts;
    gpuFrame->width = frame->width;
    gpuFrame->height = frame->height;
    
    // 记录分辨率变化（总是打印）
    if (m_width != frame->width || m_height != frame->height) {
        qDebug() << "🎬 视频流分辨率变化: " << m_width << "x" << m_height << " → " << frame->width << "x" << frame->height 
                 << " (frame:" << gpuFrame->frameIndex << ")";
        m_width = frame->width;
        m_height = frame->height;
    }
    
    // 首帧打印详细信息
    if (gpuFrame->frameIndex == 0) {
        qDebug() << "🎬 First frame: format=" << frame->format 
                 << "分辨率=" << frame->width << "x" << frame->height
                 << "hw=" << (frame->hw_frames_ctx ? "yes" : "no");
    }
    
    gpuFrame->isHardware = m_hwAccel;
    
    // ⭐ GPU 直通：保存 CUDA 硬件帧供编码器直接使用（不经过 CPU）
    gpuFrame->cudaFrame = av_frame_alloc();
    if (gpuFrame->cudaFrame) {
        av_frame_ref(gpuFrame->cudaFrame, frame);
    }
    
    // 传输到 CPU 用于显示（仅显示需要，编码不需要）
    AVFrame *swFrame = frame;
    AVFrame *tmpFrame = nullptr;
    
#ifdef Q_OS_WIN
    if (frame->hw_frames_ctx != nullptr) {
        tmpFrame = av_frame_alloc();
        if (!tmpFrame) return;
        
        int ret = av_hwframe_transfer_data(tmpFrame, frame, 0);
        if (ret < 0) {
            av_frame_free(&tmpFrame);
            return;
        }
        swFrame = tmpFrame;
    }
#endif
    
    int w = swFrame->width;
    int h = swFrame->height;
    
    // ========== 创建 QVideoFrame 用于显示 ==========
    if (swFrame->format == AV_PIX_FMT_NV12) {
        int ySize = w * h;
        int uvSize = ySize / 2;
        
        // 创建 QVideoFrame 用于显示
        QVideoFrameFormat format(QSize(w, h), QVideoFrameFormat::Format_NV12);
        gpuFrame->videoFrame = QVideoFrame(format);
        
        if (gpuFrame->videoFrame.map(QVideoFrame::WriteOnly)) {
            uchar *yDst = gpuFrame->videoFrame.bits(0);
            uchar *uvDst = gpuFrame->videoFrame.bits(1);
            int yStride = gpuFrame->videoFrame.bytesPerLine(0);
            int uvStride = gpuFrame->videoFrame.bytesPerLine(1);
            
            // Y 平面
            if (swFrame->linesize[0] == yStride) {
                memcpy(yDst, swFrame->data[0], yStride * h);
            } else {
                for (int y = 0; y < h; y++) {
                    memcpy(yDst + y * yStride, swFrame->data[0] + y * swFrame->linesize[0], w);
                }
            }
            
            // UV 平面
            if (swFrame->linesize[1] == uvStride) {
                memcpy(uvDst, swFrame->data[1], uvStride * h / 2);
            } else {
                for (int y = 0; y < h / 2; y++) {
                    memcpy(uvDst + y * uvStride, swFrame->data[1] + y * swFrame->linesize[1], w);
                }
            }
            
            gpuFrame->videoFrame.unmap();
        }
        
        if (gpuFrame->frameIndex == 0) {
            qDebug() << "Created NV12 QVideoFrame:" << w << "x" << h 
                     << "(GPU direct:" << (gpuFrame->cudaFrame ? "yes" : "no") << ")";
        }
    } else {
        // 非 NV12 格式回退到 sws_scale
        if (!m_swsCtx || m_swsWidth != w || m_swsHeight != h || m_swsFmt != swFrame->format) {
            if (m_swsCtx) sws_freeContext(m_swsCtx);
            m_swsCtx = sws_getContext(
                w, h, (AVPixelFormat)swFrame->format,
                w, h, AV_PIX_FMT_BGRA,
                SWS_POINT, nullptr, nullptr, nullptr
            );
            m_swsWidth = w;
            m_swsHeight = h;
            m_swsFmt = swFrame->format;
            qDebug() << "Fallback to sws_scale:" << w << "x" << h;
        }
        
        if (m_swsCtx) {
            gpuFrame->image = QImage(w, h, QImage::Format_ARGB32);
            uint8_t *rgbData[1] = { gpuFrame->image.bits() };
            int rgbLinesize[1] = { static_cast<int>(gpuFrame->image.bytesPerLine()) };
            sws_scale(m_swsCtx, swFrame->data, swFrame->linesize, 0, h, rgbData, rgbLinesize);
            gpuFrame->videoFrame = QVideoFrame(gpuFrame->image);
        }
    }
    
    if (tmpFrame) {
        av_frame_free(&tmpFrame);
    }
    
    emit frameReady(gpuFrame);
}

// ==================== GpuVideoSink ====================

GpuVideoSink::GpuVideoSink(QQuickItem *parent)
    : QQuickItem(parent)
{
    qDebug() << "📦 GpuVideoSink 构造开始...";
    setFlag(ItemHasContents, true);
    m_videoSink = new QVideoSink(this);
    qDebug() << "📦 GpuVideoSink 构造完成";
}

GpuVideoSink::~GpuVideoSink()
{
}

std::shared_ptr<GpuFrame> GpuVideoSink::currentFrame() const
{
    QMutexLocker lock(&const_cast<GpuVideoSink*>(this)->m_mutex);
    return m_currentFrame;
}

void GpuVideoSink::onFrameReady(std::shared_ptr<GpuFrame> frame)
{
    if (!frame) return;
    
    {
        QMutexLocker lock(&m_mutex);
        m_currentFrame = frame;
        
        if (frame->width != m_videoWidth || frame->height != m_videoHeight) {
            m_videoWidth = frame->width;
            m_videoHeight = frame->height;
            m_hwAccel = frame->isHardware;
            emit videoSizeChanged();
        }
        
        // 使用 QVideoFrame 进行 GPU 渲染
        if (!frame->videoFrame.isValid() && !frame->image.isNull()) {
            // 从 QImage 创建 QVideoFrame
            m_currentVideoFrame = QVideoFrame(frame->image);
        } else if (frame->videoFrame.isValid()) {
            m_currentVideoFrame = frame->videoFrame;
        }
    }
    
    // 发送到 QVideoSink
    if (m_videoSink && m_currentVideoFrame.isValid()) {
        m_videoSink->setVideoFrame(m_currentVideoFrame);
    }
    
    update();
}

void GpuVideoSink::displayVideoFrame(const QVideoFrame &frame)
{
    if (!frame.isValid()) return;
    
    {
        QMutexLocker lock(&m_mutex);
        m_currentVideoFrame = frame;
        
        if (frame.width() != m_videoWidth || frame.height() != m_videoHeight) {
            m_videoWidth = frame.width();
            m_videoHeight = frame.height();
            emit videoSizeChanged();
        }
    }
    
    if (m_videoSink) {
        m_videoSink->setVideoFrame(frame);
    }
    
    update();
}

QSGNode *GpuVideoSink::updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *)
{
    QSGSimpleTextureNode *node = static_cast<QSGSimpleTextureNode*>(oldNode);
    
    if (!node) {
        node = new QSGSimpleTextureNode();
        node->setFiltering(QSGTexture::Linear);
        node->setOwnsTexture(true);
    }
    
    // 获取当前帧
    std::shared_ptr<GpuFrame> frame;
    QVideoFrame videoFrame;
    {
        QMutexLocker lock(&m_mutex);
        frame = m_currentFrame;
        videoFrame = m_currentVideoFrame;
    }
    
    QImage displayImage;
    
    // 优先使用 QVideoFrame（GPU 路径）
    if (videoFrame.isValid()) {
        // QVideoFrame 可以直接转换为 QImage（Qt 内部会使用 GPU）
        displayImage = videoFrame.toImage();
    } else if (frame && !frame->image.isNull()) {
        displayImage = frame->image;
    }
    
    if (displayImage.isNull()) {
        displayImage = QImage(16, 16, QImage::Format_RGB32);
        displayImage.fill(Qt::black);
    }
    
    QQuickWindow *win = window();
    if (!win) {
        return node;
    }
    
    QSGTexture *texture = win->createTextureFromImage(displayImage);
    if (!texture) {
        return node;
    }
    
    node->setTexture(texture);
    
    // 保持宽高比
    QRectF bounds = boundingRect();
    if (bounds.width() > 0 && bounds.height() > 0 && displayImage.width() > 16) {
        qreal aspectRatio = (qreal)displayImage.width() / displayImage.height();
        qreal boundsRatio = bounds.width() / bounds.height();
        
        QRectF targetRect;
        if (aspectRatio > boundsRatio) {
            qreal h = bounds.width() / aspectRatio;
            targetRect = QRectF(0, (bounds.height() - h) / 2, bounds.width(), h);
        } else {
            qreal w = bounds.height() * aspectRatio;
            targetRect = QRectF((bounds.width() - w) / 2, 0, w, bounds.height());
        }
        node->setRect(targetRect);
    } else {
        node->setRect(bounds);
    }
    
    return node;
}

// ==================== GpuJpegDecoder ====================

GpuJpegDecoder::GpuJpegDecoder(QObject *parent)
    : QObject(parent)
{
    // 初始化 libjpeg-turbo 解码器
    m_tjDecoder = tjInitDecompress();
    if (!m_tjDecoder) {
        qCritical() << "❌ GpuJpegDecoder: tjInitDecompress failed!";
        m_initFailed = true;
    } else {
        m_decoderName = "libjpeg-turbo (CPU)";
        m_initialized = true;
        qDebug() << "✅ GpuJpegDecoder: libjpeg-turbo 软件解码器就绪";
    }
}

GpuJpegDecoder::~GpuJpegDecoder()
{
    if (m_tjDecoder) {
        tjDestroy(m_tjDecoder);
        m_tjDecoder = nullptr;
    }
    cleanupDecoder();
}

bool GpuJpegDecoder::initDecoder(int width, int height)
{
    // libjpeg-turbo 已在构造函数中初始化，无需额外操作
    Q_UNUSED(width);
    Q_UNUSED(height);
    return m_tjDecoder != nullptr && !m_initFailed;
}

void GpuJpegDecoder::cleanupDecoder()
{
    // libjpeg-turbo 在析构函数中清理
    // 保留此函数供兼容性使用
    m_width = 0;
    m_height = 0;
}

QVideoFrame GpuJpegDecoder::decode(const QByteArray &frameData)
{
    QMutexLocker locker(&m_mutex);
    
    if (frameData.isEmpty()) {
        return QVideoFrame();
    }
    
    if (m_initFailed || !m_tjDecoder) {
        return QVideoFrame();
    }
    
    // 获取 JPEG 图像信息
    int width, height, jpegSubsamp, jpegColorspace;
    int ret = tjDecompressHeader3(
        m_tjDecoder,
        reinterpret_cast<const unsigned char*>(frameData.constData()),
        frameData.size(),
        &width, &height, &jpegSubsamp, &jpegColorspace
    );
    
    if (ret != 0) {
        qWarning() << "GpuJpegDecoder::decode: tjDecompressHeader3 failed:" << tjGetErrorStr2(m_tjDecoder);
        return QVideoFrame();
    }
    
    // 分辨率变化时打印
    if (m_width != width || m_height != height) {
        qDebug() << "🖼️ JPEG解码分辨率变化:" << m_width << "x" << m_height << " → " << width << "x" << height;
    }
    
    // 解码为 RGBX (32-bit RGB with padding) 便于转换为 QVideoFrame
    QByteArray rgbBuffer(width * height * 4, 0);
    
    ret = tjDecompress2(
        m_tjDecoder,
        reinterpret_cast<const unsigned char*>(frameData.constData()),
        frameData.size(),
        reinterpret_cast<unsigned char*>(rgbBuffer.data()),
        width,
        width * 4,  // pitch
        height,
        TJPF_RGBX,
        TJFLAG_ACCURATEDCT          // ⭐ 跟编码端一致, 精确 IDCT 还原, 慢放回放不再糊
    );
    
    if (ret != 0) {
        qWarning() << "GpuJpegDecoder::decode: tjDecompress2 failed:" << tjGetErrorStr2(m_tjDecoder);
        return QVideoFrame();
    }
    
    m_width = width;
    m_height = height;
    
    // 创建 RGBX 格式的 QVideoFrame
    QVideoFrameFormat format(QSize(width, height), QVideoFrameFormat::Format_RGBX8888);
    QVideoFrame videoFrame(format);
    
    if (!videoFrame.map(QVideoFrame::WriteOnly)) {
        return QVideoFrame();
    }
    
    // 拷贝数据
    memcpy(videoFrame.bits(0), rgbBuffer.constData(), rgbBuffer.size());
    
    videoFrame.unmap();
    
    return videoFrame;
}

QImage GpuJpegDecoder::decodeToImage(const QByteArray &frameData)
{
    QMutexLocker locker(&m_mutex);
    
    if (frameData.isEmpty() || m_initFailed || !m_tjDecoder) {
        return QImage();
    }
    
    // 获取 JPEG 图像信息
    int width, height, jpegSubsamp, jpegColorspace;
    int ret = tjDecompressHeader3(
        m_tjDecoder,
        reinterpret_cast<const unsigned char*>(frameData.constData()),
        frameData.size(),
        &width, &height, &jpegSubsamp, &jpegColorspace
    );
    
    if (ret != 0) {
        qWarning() << "GpuJpegDecoder::decodeToImage: tjDecompressHeader3 failed:" << tjGetErrorStr2(m_tjDecoder);
        return QImage();
    }
    
    // 直接解码到 QImage (RGBA)
    QImage result(width, height, QImage::Format_RGBA8888);
    
    ret = tjDecompress2(
        m_tjDecoder,
        reinterpret_cast<const unsigned char*>(frameData.constData()),
        frameData.size(),
        result.bits(),
        width,
        result.bytesPerLine(),
        height,
        TJPF_RGBA,
        TJFLAG_ACCURATEDCT          // ⭐ 跟编码端一致, 精确 IDCT, 保存到 QImage 也不丢质量
    );
    
    if (ret != 0) {
        qWarning() << "GpuJpegDecoder::decodeToImage: tjDecompress2 failed:" << tjGetErrorStr2(m_tjDecoder);
        return QImage();
    }
    
    m_width = width;
    m_height = height;
    
    return result;
}

// ==================== GpuJpegEncoder ====================

GpuJpegEncoder::GpuJpegEncoder(QObject *parent)
    : QThread(parent)
{
    // 使用 libjpeg-turbo 软件 JPEG 编码（兼容所有机型）
    m_encoderMode = FrameEncoderMode::JPEG_SOFT;
    m_fileExtension = ".jpg";
    qDebug() << "GpuJpegEncoder: Using libjpeg-turbo software JPEG encoding";
    
    // 初始化 libjpeg-turbo 编码器
    m_tjEncoder = tjInitCompress();
    if (!m_tjEncoder) {
        qCritical() << "❌ GpuJpegEncoder: tjInitCompress failed!";
    }
    
    // 根据编码模式设置目录
    m_framesDir = QCoreApplication::applicationDirPath() + "/captures/jpeg";
    QDir dir(m_framesDir);
    
    // 清空目录中的所有帧文件（程序启动时重新开始）
    if (dir.exists()) {
        QStringList filters;
        filters << "*.jpeg" << "*.jpg" << "*.h264";
        QStringList files = dir.entryList(filters, QDir::Files);
        for (const QString &file : files) {
            dir.remove(file);
        }
        qDebug() << "GpuJpegEncoder: startup cleanup, removed" << files.size() << "files from" << m_framesDir;
    }
    
    // 确保目录存在
    QDir().mkpath(m_framesDir);
    
    // 初始化状态
    m_oldestFrame = -1;
    m_newestFrame = -1;
    m_latestFrameIndex = -1;
    
    qDebug() << "GpuJpegEncoder: initialized with mode" << encoderModeName() << ", dir:" << m_framesDir;
}

QString GpuJpegEncoder::encoderModeName() const
{
    switch (m_encoderMode) {
        case FrameEncoderMode::JPEG_QSV: return "JPEG (Intel QSV)";
        case FrameEncoderMode::H264_NVENC: return "H.264 I-frame (NVIDIA NVENC)";
        case FrameEncoderMode::JPEG_SOFT: return "JPEG (libjpeg-turbo CPU)";
        default: return "Unknown";
    }
}

GpuJpegEncoder::~GpuJpegEncoder()
{
    stop();
    wait();
    
    // 退出时清理所有帧文件
    QDir dir(m_framesDir);
    if (dir.exists()) {
        QStringList filters;
        filters << "*.jpeg" << "*.jpg" << "*.h264";
        QStringList files = dir.entryList(filters, QDir::Files);
        for (const QString &file : files) {
            dir.remove(file);
        }
        qDebug() << "GpuJpegEncoder: exit cleanup, removed" << files.size() << "files";
    }
    
    // 清理 libjpeg-turbo 编码器
    if (m_tjEncoder) {
        tjDestroy(m_tjEncoder);
        m_tjEncoder = nullptr;
    }
    
    if (m_swsCtx) sws_freeContext(m_swsCtx);
    if (m_packet) av_packet_free(&m_packet);
    if (m_frame) av_frame_free(&m_frame);
    if (m_hwFrame) av_frame_free(&m_hwFrame);
    if (m_hwFramesCtx) av_buffer_unref(&m_hwFramesCtx);
    if (m_hwDeviceCtx) av_buffer_unref(&m_hwDeviceCtx);
    if (m_codecCtx) avcodec_free_context(&m_codecCtx);
}

void GpuJpegEncoder::stop()
{
    m_running = false;
    m_condition.wakeAll();
}

void GpuJpegEncoder::reset()
{
    QMutexLocker lock(&m_mutex);
    
    // 清空队列
    m_queue.clear();
    
    // 清空目录（根据编码模式清理对应扩展名）
    QDir dir(m_framesDir);
    QStringList filters;
    filters << "*.jpeg" << "*.jpg" << "*.h264";
    QStringList files = dir.entryList(filters, QDir::Files);
    for (const QString &file : files) {
        dir.remove(file);
    }
    
    m_oldestFrame = -1;
    m_newestFrame = -1;
    m_latestJpeg.clear();
    m_latestFrameIndex = -1;
    
    qDebug() << "GpuJpegEncoder: reset, cleared" << files.size() << "files";
}

void GpuJpegEncoder::submitFrame(std::shared_ptr<GpuFrame> frame)
{
    if (!frame || !m_running) return;
    
    // 异步编码：放入队列，由 run() 线程处理
    QMutexLocker lock(&m_mutex);
    
    // 如果队列快满了，等待一会儿让消费者消费（最多等10ms，避免阻塞太久）
    if (m_queue.size() >= MAX_QUEUE_SIZE) {
        // 队列满了，等待消费
        m_condition.wait(&m_mutex, 10);
        if (m_queue.size() >= MAX_QUEUE_SIZE) {
            qWarning() << "⚠️ GpuJpegEncoder: queue still full, force enqueue frame" << frame->frameIndex;
        }
    }
    
    m_queue.enqueue(frame);
    m_condition.wakeOne();  // 唤醒 run() 线程
}

QString GpuJpegEncoder::frameFilePath(qint64 frameIndex) const
{
    // 文件格式: s_000000123.jpeg 或 s_000000123.h264 (9位数字)
    return QString("%1/s_%2%3").arg(m_framesDir).arg(frameIndex, 9, 10, QChar('0')).arg(m_fileExtension);
}

QByteArray GpuJpegEncoder::getJpeg(qint64 frameIndex) const
{
    // 检查是否是最新帧（已缓存）
    if (frameIndex == m_latestFrameIndex && !m_latestJpeg.isEmpty()) {
        return m_latestJpeg;
    }
    
    // 从文件读取
    QString path = frameFilePath(frameIndex);
    QFile file(path);
    if (file.open(QIODevice::ReadOnly)) {
        return file.readAll();
    }
    
    return QByteArray();
}

bool GpuJpegEncoder::hasJpeg(qint64 frameIndex) const
{
    if (m_newestFrame < 0 || m_oldestFrame < 0) return false;
    return frameIndex >= m_oldestFrame && frameIndex <= m_newestFrame;
}

QByteArray GpuJpegEncoder::getFrameData(qint64 frameIndex) const
{
    // 使用 frameFilePath 获取正确路径（根据编码模式自动选择扩展名）
    QString path = frameFilePath(frameIndex);
    QFile file(path);
    if (file.open(QIODevice::ReadOnly)) {
        return file.readAll();
    }
    qDebug() << "GpuJpegEncoder::getFrameData: File not found:" << path;
    return QByteArray();
}

bool GpuJpegEncoder::initEncoder(int width, int height)
{
    // 软件 JPEG 编码不需要特殊初始化，只需记录分辨率
    if (m_width == width && m_height == height && m_tjEncoder) {
        return true;
    }
    
    qDebug() << "⭐ GpuJpegEncoder::initEncoder (软件JPEG) 分辨率:" << width << "x" << height;
    
    // 清理旧资源
    if (m_swsCtx) { sws_freeContext(m_swsCtx); m_swsCtx = nullptr; }
    if (m_frame) { av_frame_free(&m_frame); m_frame = nullptr; }
    
    // 确保 libjpeg-turbo 编码器存在
    if (!m_tjEncoder) {
        m_tjEncoder = tjInitCompress();
        if (!m_tjEncoder) {
            QString errMsg = "libjpeg-turbo 初始化失败";
            qCritical() << "❌" << errMsg;
            emit error(errMsg);
            return false;
        }
    }
    
    // 创建 RGB 帧用于转换
    m_frame = av_frame_alloc();
    m_frame->format = AV_PIX_FMT_RGB24;
    m_frame->width = width;
    m_frame->height = height;
    av_frame_get_buffer(m_frame, 32);
    
    m_width = width;
    m_height = height;
    m_hwAccel = false;  // 软件编码
    
    qDebug() << "✅ libjpeg-turbo 软件 JPEG 编码器就绪, size:" << width << "x" << height;
    return true;
}

// ⭐ 设置共享的 CUDA 设备上下文
void GpuJpegEncoder::setCudaDeviceContext(AVBufferRef *cudaCtx)
{
    // 先释放旧的上下文
    if (m_hwDeviceCtx) {
        av_buffer_unref(&m_hwDeviceCtx);
        m_hwDeviceCtx = nullptr;
    }
    
    if (cudaCtx) {
        m_hwDeviceCtx = av_buffer_ref(cudaCtx);
        qDebug() << "GpuJpegEncoder: CUDA device context set/updated for GPU direct mode";
    }
}


QByteArray GpuJpegEncoder::encodeFrame(std::shared_ptr<GpuFrame> frame)
{
    if (!frame || !frame->cudaFrame) {
        return QByteArray();
    }
    
    // ⭐ 获取 CUDA 帧的实际分辨率（从解码器输出）
    int cudaWidth = frame->cudaFrame->width;
    int cudaHeight = frame->cudaFrame->height;
    
    // 分辨率变化时打印日志（总是打印）
    static int lastLoggedWidth = 0, lastLoggedHeight = 0;
    if (cudaWidth != lastLoggedWidth || cudaHeight != lastLoggedHeight) {
        qDebug() << "📐 编码分辨率变化: " << lastLoggedWidth << "x" << lastLoggedHeight 
                 << " → " << cudaWidth << "x" << cudaHeight
                 << " (GpuFrame记录:" << frame->width << "x" << frame->height << ")"
                 << " (frame:" << frame->frameIndex << ")";
        lastLoggedWidth = cudaWidth;
        lastLoggedHeight = cudaHeight;
    }
    
    // 使用 CUDA 帧的实际分辨率（而非 GpuFrame 记录的）
    int encodeWidth = cudaWidth;
    int encodeHeight = cudaHeight;
    
    if (!initEncoder(encodeWidth, encodeHeight)) {
        return QByteArray();
    }
    
    if (!m_tjEncoder) {
        qWarning() << "⚠️ libjpeg-turbo 编码器未初始化";
        return QByteArray();
    }
    
    // 1. 从 CUDA 帧下载到 CPU（NV12 格式）
    AVFrame *swFrame = av_frame_alloc();
    swFrame->format = AV_PIX_FMT_NV12;
    swFrame->width = encodeWidth;
    swFrame->height = encodeHeight;
    
    int ret = av_hwframe_transfer_data(swFrame, frame->cudaFrame, 0);
    if (ret < 0) {
        char errBuf[256];
        av_strerror(ret, errBuf, sizeof(errBuf));
        qWarning() << "⚠️ CUDA → CPU 传输失败, frame:" << frame->frameIndex << "error:" << errBuf;
        av_frame_free(&swFrame);
        return QByteArray();
    }
    
    // 2. NV12 → RGB24 转换（分辨率变化时重建 sws_ctx）
    if (!m_swsCtx || m_width != encodeWidth || m_height != encodeHeight) {
        if (m_swsCtx) sws_freeContext(m_swsCtx);
        m_swsCtx = sws_getContext(
            encodeWidth, encodeHeight, AV_PIX_FMT_NV12,
            encodeWidth, encodeHeight, AV_PIX_FMT_RGB24,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr
        );
        qDebug() << "🔄 重建 sws_ctx:" << encodeWidth << "x" << encodeHeight;
    }
    
    sws_scale(m_swsCtx, swFrame->data, swFrame->linesize, 0, encodeHeight,
              m_frame->data, m_frame->linesize);
    
    av_frame_free(&swFrame);
    
    // 3. 使用 libjpeg-turbo 编码 RGB24 → JPEG
    unsigned char *jpegBuf = nullptr;
    unsigned long jpegSize = 0;
    
    ret = tjCompress2(
        m_tjEncoder,
        m_frame->data[0],           // RGB 数据
        encodeWidth,
        m_frame->linesize[0],       // pitch
        encodeHeight,
        TJPF_RGB,                   // RGB 格式
        &jpegBuf,
        &jpegSize,
        // ⭐ YUV 4:4:4 — 不降色度, 红/黑边缘锐利 (4:2:0 会让红心/方块的红边糊掉)
        //   看牌场景关键: 卡牌的颜色边界 + 数字字符 "10/J/Q/K/A" 都吃色度精度
        TJSAMP_444,
        JPEG_QUALITY,               // 质量 95 (gpupipeline.h)
        // ⭐ 精确 DCT — 消除振铃伪影, 文字边缘锐利
        //   代价: 比 FASTDCT 慢约 30%, 但走异步线程不阻塞实时流
        TJFLAG_ACCURATEDCT
    );
    
    if (ret != 0) {
        qWarning() << "⚠️ libjpeg-turbo 编码失败, frame:" << frame->frameIndex << "error:" << tjGetErrorStr2(m_tjEncoder);
        if (jpegBuf) tjFree(jpegBuf);
        return QByteArray();
    }
    
    QByteArray result(reinterpret_cast<char*>(jpegBuf), static_cast<int>(jpegSize));
    tjFree(jpegBuf);
    
    return result;
}

void GpuJpegEncoder::run()
{
    qDebug() << "GpuJpegEncoder thread started (async mode), output dir:" << m_framesDir;
    
    while (m_running) {
        std::shared_ptr<GpuFrame> frame;
        
        {
            QMutexLocker lock(&m_mutex);
            
            while (m_queue.isEmpty() && m_running) {
                m_condition.wait(&m_mutex, 100);  // 等待新帧或停止信号
            }
            
            if (!m_running && m_queue.isEmpty()) break;
            
            if (!m_queue.isEmpty()) {
                frame = m_queue.dequeue();
                m_condition.wakeAll();  // 唤醒可能在等待的 submitFrame
            }
        }
        
        if (!frame) continue;
        
        // 在线程中执行编码（不阻塞主线程）
        QByteArray encoded = encodeFrame(frame);
        
        if (encoded.isEmpty()) {
            qWarning() << "⚠️ GpuJpegEncoder: encode failed for frame" << frame->frameIndex;
            continue;
        }
        
        // 写入文件
        QString path = frameFilePath(frame->frameIndex);
        QFile file(path);
        if (file.open(QIODevice::WriteOnly)) {
            file.write(encoded);
            file.close();
            
            // 更新索引范围（需要加锁）
            {
                QMutexLocker lock(&m_mutex);
                if (m_oldestFrame < 0) {
                    m_oldestFrame = frame->frameIndex;
                }
                m_newestFrame = frame->frameIndex;
                m_latestJpeg = encoded;
                m_latestFrameIndex = frame->frameIndex;
            }
            
            emit frameEncoded(frame->frameIndex);
            
            // 定期清理
            m_cleanupCounter++;
            if (m_cleanupCounter >= CLEANUP_INTERVAL) {
                m_cleanupCounter = 0;
                cleanupOldFiles();
            }
        } else {
            qWarning() << "⚠️ GpuJpegEncoder: file write failed for frame" << frame->frameIndex << path;
        }
    }
    
    qDebug() << "GpuJpegEncoder thread stopped";
}

// ==================== 文件清理逻辑 ====================

bool GpuJpegEncoder::isIndexValid(qint64 frameIndex) const
{
    QMutexLocker lock(&m_rangesMutex);
    
    // 遍历所有有效范围
    for (auto it = m_validRanges.constBegin(); it != m_validRanges.constEnd(); ++it) {
        const ValidRange &range = it.value();
        if (frameIndex >= range.startIndex && frameIndex <= range.endIndex) {
            return true;
        }
    }
    return false;
}

int GpuJpegEncoder::registerValidRange(qint64 startIndex, qint64 endIndex)
{
    QMutexLocker lock(&m_rangesMutex);
    int rangeId = m_nextRangeId++;
    m_validRanges[rangeId] = ValidRange{startIndex, endIndex};
    qDebug() << "GpuJpegEncoder: registered valid range ID" << rangeId << "[" << startIndex << "-" << endIndex << "]";
    return rangeId;
}

void GpuJpegEncoder::unregisterValidRange(int rangeId)
{
    QMutexLocker lock(&m_rangesMutex);
    if (m_validRanges.remove(rangeId)) {
        qDebug() << "GpuJpegEncoder: unregistered range ID" << rangeId;
    }
}

void GpuJpegEncoder::clearValidRanges()
{
    QMutexLocker lock(&m_rangesMutex);
    m_validRanges.clear();
    qDebug() << "GpuJpegEncoder: cleared all valid ranges";
}

void GpuJpegEncoder::cleanupOldFiles()
{
    qint64 currentFrameId = m_newestFrame;
    
    if (currentFrameId <= SAFETY_MARGIN) {
        return;  // 文件数量较少，无需清理
    }
    
    // 只检查最近 CLEANUP_CHECK_RANGE 帧范围（不是限制，只是检查窗口）
    qint64 lowIndex = qMax(1LL, currentFrameId - CLEANUP_CHECK_RANGE);
    
    // 应用安全边界：不检查最新的 SAFETY_MARGIN 帧（可能正在被抓拍）
    qint64 safeEndIndex = currentFrameId - SAFETY_MARGIN;
    
    if (safeEndIndex <= lowIndex) {
        return;  // 所有文件都在安全边界内
    }
    
    int deletedCount = 0;
    int checkedCount = 0;
    
    for (qint64 i = lowIndex; i <= safeEndIndex; i++) {
        checkedCount++;
        if (deleteIfInvalid(i)) {
            deletedCount++;
        }
    }
    
}

bool GpuJpegEncoder::deleteIfInvalid(qint64 index)
{
    // 检查是否在有效范围内
    if (isIndexValid(index)) {
        return false;  // 有效帧，不删除
    }
    
    // 无效帧，删除文件
    QString path = frameFilePath(index);
    QFile file(path);
    if (file.exists() && file.remove()) {
        return true;
    }
    return false;
}

// ==================== GpuPipeline ====================

GpuPipeline::GpuPipeline(QObject *parent)
    : QObject(parent)
{
    qDebug() << "📦 GpuPipeline 构造函数完成";
}

GpuPipeline::~GpuPipeline()
{
    if (m_jpegEncoder) {
        m_jpegEncoder->stop();
        m_jpegEncoder->wait();
        delete m_jpegEncoder;
    }
    delete m_jpegDecoder;
    delete m_captureDecoder;  // 清理抓拍解码器
    delete m_decoder;
    // m_videoSink 由 QML 管理
}

bool GpuPipeline::init()
{
    qDebug() << "========== GpuPipeline::init() 开始 ==========";
    
    // 创建解码器
    qDebug() << "🔧 正在创建 GpuDecoder...";
    m_decoder = new GpuDecoder(this);
    
    qDebug() << "🔧 正在初始化 GpuDecoder...";
    if (!m_decoder->init()) {
        m_status = "Failed to initialize decoder";
        qCritical() << "❌ GpuDecoder 初始化失败";
        emit statusChanged();
        return false;
    }
    qDebug() << "✅ GpuDecoder 初始化成功";
    
    // 启用 H.264 编码器（后台预编码到磁盘文件）
    m_jpegEncoder = new GpuJpegEncoder(this);
    
    // ⭐ GPU 直通：共享 CUDA 设备上下文（解码器 → 编码器）
    if (m_decoder->cudaDeviceContext()) {
        m_jpegEncoder->setCudaDeviceContext(m_decoder->cudaDeviceContext());
        qDebug() << "⭐ GPU Direct pipeline: CUVID → NVENC (no CPU copy)";
    }
    
    // 连接编码器错误信号
    connect(m_jpegEncoder, &GpuJpegEncoder::error, this, &GpuPipeline::jpegEncoderError);
    
    m_jpegEncoder->start(QThread::LowPriority);
    qDebug() << "H.264 encoder started (GPU Direct:" << m_jpegEncoder->isGpuDirect() 
             << ", output:" << m_jpegEncoder->framesDir() << ")";
    
    // 创建两个独立的解码器，避免慢放和抓拍竞争
    // 解码器 1: 慢放用
    m_jpegDecoder = new GpuJpegDecoder(this);
    connect(m_jpegDecoder, &GpuJpegDecoder::initError, this, &GpuPipeline::jpegDecoderError);
    qDebug() << "H.264 decoder #1 created for slow motion playback";
    
    // 解码器 2: 抓拍用（独立解码器，不与慢放竞争）
    m_captureDecoder = new GpuJpegDecoder(this);
    connect(m_captureDecoder, &GpuJpegDecoder::initError, this, &GpuPipeline::jpegDecoderError);
    qDebug() << "H.264 decoder #2 created for capture (independent)";
    
    // 连接信号 - 使用队列连接确保线程安全
    connect(m_decoder, &GpuDecoder::frameReady, this, &GpuPipeline::onDecoderFrameReady, Qt::QueuedConnection);
    connect(m_decoder, &GpuDecoder::keyframeNeeded, this, &GpuPipeline::keyframeNeeded);
    connect(m_decoder, &GpuDecoder::decoderReinitialized, this, &GpuPipeline::onDecoderReinitialized);
    connect(m_decoder, &GpuDecoder::resolutionChanged, this, &GpuPipeline::resolutionChanged);
    
    m_status = m_decoder->isHardwareAccelerated() 
        ? "GPU Hardware Accelerated" 
        : "Software Mode";
    emit statusChanged();
    
    qDebug() << "GpuPipeline initialized:" << m_status;
    return true;
}

void GpuPipeline::decodeNalu(const QByteArray &nalu, bool isKeyFrame)
{
    if (m_decoder) {
        m_decoder->decodeNalu(nalu, isKeyFrame);
    } else {
        qWarning() << "GpuPipeline: decoder is null!";
    }
}

void GpuPipeline::setVideoSink(QVideoSink *sink)
{
    if (m_videoSink != sink) {
        m_videoSink = sink;
        qDebug() << "GpuPipeline: QVideoSink set for full GPU rendering";
    }
}

void GpuPipeline::onDecoderFrameReady(std::shared_ptr<GpuFrame> frame)
{
    if (!frame) return;
    
    // 发送 QVideoFrame 到 VideoOutput
    if (m_videoSink && frame->videoFrame.isValid()) {
        m_videoSink->setVideoFrame(frame->videoFrame);
    }
    
    // 提交给 H.264 编码器（GPU 直通）
    if (m_jpegEncoder && frame->cudaFrame) {
        m_jpegEncoder->submitFrame(frame);
    }
    
    // 发送 QImage 给 CaptureManager（用于抓拍）
    if (!frame->image.isNull()) {
        emit imageReady(frame->image, frame->frameIndex);
    }
    
    emit frameReady(frame->frameIndex);
}

void GpuPipeline::onFrameEncoded(qint64 frameIndex)
{
    Q_UNUSED(frameIndex);
    // 不再使用此槽函数，FPS 由 WebSocket 消息推送
}

void GpuPipeline::onDecoderReinitialized()
{
    qDebug() << "⭐ GpuPipeline::onDecoderReinitialized() - 更新编码器 CUDA 上下文";
    
    // 解码器重新初始化后，需要更新编码器的 CUDA 上下文
    // ⚠️ 注意：不要调用 reset()，否则会删除所有帧文件导致抓拍失效
    if (m_jpegEncoder && m_decoder && m_decoder->cudaDeviceContext()) {
        // 只更新 CUDA 上下文，不删除帧文件
        m_jpegEncoder->setCudaDeviceContext(m_decoder->cudaDeviceContext());
        qDebug() << "⭐ 编码器 CUDA 上下文已更新（保留帧文件）";
    }
}

bool GpuPipeline::isHardwareAccelerated() const
{
    return m_decoder && m_decoder->isHardwareAccelerated();
}

QByteArray GpuPipeline::getJpeg(qint64 frameIndex) const
{
    return m_jpegEncoder ? m_jpegEncoder->getJpeg(frameIndex) : QByteArray();
}

bool GpuPipeline::hasJpeg(qint64 frameIndex) const
{
    return m_jpegEncoder ? m_jpegEncoder->hasJpeg(frameIndex) : false;
}

qint64 GpuPipeline::newestFrame() const
{
    return m_jpegEncoder ? m_jpegEncoder->newestFrame() : -1;
}

qint64 GpuPipeline::oldestFrame() const
{
    return m_jpegEncoder ? m_jpegEncoder->oldestFrame() : -1;
}

int GpuPipeline::jpegBufferSize() const
{
    return m_jpegEncoder ? m_jpegEncoder->bufferSize() : 0;
}

QString GpuPipeline::framesDir() const
{
    return m_jpegEncoder ? m_jpegEncoder->framesDir() : QString();
}

void GpuPipeline::resetJpegEncoder()
{
    if (m_jpegEncoder) {
        m_jpegEncoder->reset();
    }
}

void GpuPipeline::setJpegColorParams(double brightness, double contrast, double saturation, double hue, double gamma)
{
    if (m_jpegEncoder) {
        m_jpegEncoder->setColorParams(brightness, contrast, saturation, hue, gamma);
        qDebug() << "GpuPipeline: JPEG color params updated - brightness:" << brightness 
                 << "contrast:" << contrast << "saturation:" << saturation 
                 << "hue:" << hue << "gamma:" << gamma;
    }
}

QVideoFrame GpuPipeline::decodeJpegToFrame(const QByteArray &jpegData)
{
    if (m_jpegDecoder) {
        return m_jpegDecoder->decode(jpegData);
    }
    return QVideoFrame();
}

QVideoFrame GpuPipeline::decodeJpegToFrame(qint64 frameIndex)
{
    QByteArray jpegData = getJpeg(frameIndex);
    return decodeJpegToFrame(jpegData);
}

QImage GpuPipeline::decodeFrameToImage(qint64 frameIndex)
{
    // 使用独立的抓拍解码器，不与慢放竞争
    if (!m_captureDecoder) {
        qDebug() << "❌ decodeFrameToImage: m_captureDecoder 为空";
        return QImage();
    }
    
    if (!m_jpegEncoder) {
        qDebug() << "❌ decodeFrameToImage: m_jpegEncoder 为空";
        return QImage();
    }
    
    QByteArray frameData = m_jpegEncoder->getFrameData(frameIndex);
    if (frameData.isEmpty()) {
        qDebug() << "❌ decodeFrameToImage: 帧" << frameIndex << "数据为空"
                 << ", oldest=" << m_jpegEncoder->oldestFrame()
                 << ", newest=" << m_jpegEncoder->newestFrame();
        return QImage();
    }
    
    QImage img = m_captureDecoder->decodeToImage(frameData);
    if (img.isNull()) {
        qDebug() << "❌ decodeFrameToImage: 解码帧" << frameIndex << "失败, 数据大小=" << frameData.size();
    }
    return img;
}

// ==================== 颜色调整实现（LUT 优化）====================

void GpuJpegEncoder::setColorParams(double brightness, double contrast, double saturation, double hue, double gamma)
{
    // 检查是否为默认值
    bool isDefault = qFuzzyCompare(brightness, 0.0) && qFuzzyCompare(contrast, 1.0) &&
                     qFuzzyCompare(saturation, 1.0) && qFuzzyCompare(hue, 0.0) &&
                     qFuzzyCompare(gamma, 1.0);
    
    QMutexLocker lock(&m_colorMutex);
    
    if (isDefault) {
        m_colorEnabled = false;
        return;
    }
    
    // 只在参数变化时重建 LUT（一次性开销）
    rebuildColorLUT(brightness, contrast, saturation, hue, gamma);
    m_colorEnabled = true;
}

void GpuJpegEncoder::rebuildColorLUT(double brightness, double contrast, double saturation, double hue, double gamma)
{
    // 构建 Y 通道 LUT：伽马 → 亮度 → 对比度
    for (int i = 0; i < 256; i++) {
        double y = i;
        
        // 伽马校正
        y = std::pow(y / 255.0, 1.0 / gamma) * 255.0;
        
        // 亮度调整
        y = y + brightness * 128.0;
        
        // 对比度调整
        y = (y - 128.0) * contrast + 128.0;
        
        m_yLUT[i] = static_cast<uint8_t>(std::clamp(y, 0.0, 255.0));
    }
    
    // 构建 UV 通道 LUT：饱和度 + 色调旋转
    double cosHue = std::cos(hue * M_PI);
    double sinHue = std::sin(hue * M_PI);
    
    for (int u = 0; u < 256; u++) {
        for (int v = 0; v < 256; v++) {
            double uc = u - 128.0;
            double vc = v - 128.0;
            
            // 饱和度调整
            uc *= saturation;
            vc *= saturation;
            
            // 色调旋转
            double newU = uc * cosHue - vc * sinHue;
            double newV = uc * sinHue + vc * cosHue;
            
            m_uvLUT[u][v][0] = static_cast<int16_t>(std::clamp(newU + 128.0, 0.0, 255.0));
            m_uvLUT[u][v][1] = static_cast<int16_t>(std::clamp(newV + 128.0, 0.0, 255.0));
        }
    }
    
    qDebug() << "GpuJpegEncoder: Color LUT rebuilt - brightness:" << brightness
             << "contrast:" << contrast << "saturation:" << saturation
             << "hue:" << hue << "gamma:" << gamma;
}

void GpuJpegEncoder::applyColorAdjust(uint8_t *yPlane, uint8_t *uvPlane, int width, int height)
{
    // 快速检查：未启用颜色调整则直接返回（零开销）
    if (!m_colorEnabled) {
        return;
    }
    
    int ySize = width * height;
    int uvSize = (width * height) / 2;
    
    // Y 通道：直接查表（无浮点运算）
    for (int i = 0; i < ySize; i++) {
        yPlane[i] = m_yLUT[yPlane[i]];
    }
    
    // UV 通道：查表（NV12 交错格式 U0V0 U1V1...）
    for (int i = 0; i < uvSize; i += 2) {
        uint8_t u = uvPlane[i];
        uint8_t v = uvPlane[i + 1];
        uvPlane[i] = static_cast<uint8_t>(m_uvLUT[u][v][0]);
        uvPlane[i + 1] = static_cast<uint8_t>(m_uvLUT[u][v][1]);
    }
}

