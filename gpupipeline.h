#ifndef GPUPIPELINE_H
#define GPUPIPELINE_H

#include <QObject>
#include <QQuickItem>
#include <QSGTexture>
#include <QMutex>
#include <QThread>
#include <QQueue>
#include <QWaitCondition>
#include <QVideoFrame>
#include <QVideoSink>
#include <QTimer>
#include <atomic>
#include <memory>

#ifdef Q_OS_WIN
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>
#include <wincodec.h>  // WIC for GPU JPEG encoding
using Microsoft::WRL::ComPtr;
#endif

// libjpeg-turbo 软件 JPEG 编码/解码
#include <turbojpeg.h>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/hwcontext.h>
#include <libavutil/hwcontext_d3d11va.h>
#include <libswscale/swscale.h>
}

/**
 * GPU 视频管道
 * 
 * 完整 GPU 加速流程：
 * 1. H.264 NALU → D3D11VA 硬件解码 → D3D11 Texture
 * 2. D3D11 Texture → OpenGL Texture (NV_DX_interop) → QML 显示
 * 3. D3D11 Texture → GPU JPEG 编码 → 抓拍保存
 * 4. Shader 实现实时色彩调整（GPU）
 */

// ==================== GPU Frame ====================
struct GpuFrame {
    qint64 frameIndex = 0;
    qint64 pts = 0;
    int width = 0;
    int height = 0;
    
#ifdef Q_OS_WIN
    // D3D11 纹理 (全 GPU 路径 - 用于显示和 JPEG 编码)
    ComPtr<ID3D11Texture2D> d3d11Texture;
    int textureArrayIndex = 0;  // 纹理数组中的索引
#endif
    
    // ⭐ CUDA 硬件帧（GPU 直通：解码→编码，不经过 CPU）
    AVFrame *cudaFrame = nullptr;  // 保持在 GPU 内存中的 NV12 帧
    
    // QVideoFrame 用于 GPU 渲染
    QVideoFrame videoFrame;
    
    // QImage 用于后备显示
    QImage image;
    
    // 原始 YUV 数据 (用于 JPEG 编码回退，GPU 直通时不使用)
    QByteArray yuvData;
    
    // JPEG 数据 (已编码，可直接保存)
    QByteArray jpegData;
    
    bool isHardware = false;
    
    ~GpuFrame() {
        if (cudaFrame) {
            av_frame_free(&cudaFrame);
        }
    }
};

// ==================== GPU Decoder ====================
/**
 * GPU 硬件解码器
 * 使用 D3D11VA 进行硬件解码，输出 D3D11 纹理
 */
class GpuDecoder : public QObject
{
    Q_OBJECT
public:
    explicit GpuDecoder(QObject *parent = nullptr);
    ~GpuDecoder();
    
    // 初始化解码器
    bool init();
    
    // 重新初始化解码器（分辨率变化时调用）
    void reinit();
    
    // 输入 NALU 数据
    void decodeNalu(const QByteArray &nalu, bool isKeyFrame);
    
    // 获取状态
    bool isHardwareAccelerated() const { return m_hwAccel; }
    QString decoderName() const { return m_decoderName; }
    
    // ⭐ 获取 CUDA 设备上下文（供编码器共享使用）
    AVBufferRef* cudaDeviceContext() const { return m_hwDeviceCtx; }
    
#ifdef Q_OS_WIN
    ID3D11Device* d3d11Device() const { return m_d3d11Device.Get(); }
    ID3D11DeviceContext* d3d11Context() const { return m_d3d11Context.Get(); }
#endif

signals:
    void frameReady(std::shared_ptr<GpuFrame> frame);
    void error(const QString &msg);
    void keyframeNeeded();
    void decoderReinitialized();  // 解码器重新初始化后发出，通知编码器更新 CUDA 上下文
    void resolutionChanged();     // 分辨率变化，通知 UI 显示黑屏遮罩

private:
    bool initHardwareDecoder();
    bool initSoftwareDecoder();
    void processFrame(AVFrame *frame);
    void cleanup();  // 清理解码器资源
    
#ifdef Q_OS_WIN
    ComPtr<ID3D11Device> m_d3d11Device;
    ComPtr<ID3D11DeviceContext> m_d3d11Context;
#endif
    
    AVCodecContext *m_codecCtx = nullptr;
    AVBufferRef *m_hwDeviceCtx = nullptr;
    AVCodecParserContext *m_parser = nullptr;
    SwsContext *m_swsCtx = nullptr;
    int m_swsWidth = 0;
    int m_swsHeight = 0;
    int m_swsFmt = -1;
    
    bool m_hwAccel = false;
    QString m_decoderName;
    qint64 m_frameIndex = 0;
    int m_decodeErrors = 0;
    qint64 m_lastKeyframeRequest = 0;
    bool m_needReinit = false;  // 分辨率变化时需要重新初始化
    int m_consecutiveErrors = 0;  // 连续错误计数
    
    int m_width = 0;
    int m_height = 0;
};

// ==================== GPU Video Sink ====================
/**
 * GPU 视频渲染器
 * 使用 Qt Multimedia 的 QVideoSink 进行全 GPU 渲染
 */
class GpuVideoSink : public QQuickItem
{
    Q_OBJECT
    Q_PROPERTY(int videoWidth READ videoWidth NOTIFY videoSizeChanged)
    Q_PROPERTY(int videoHeight READ videoHeight NOTIFY videoSizeChanged)
    Q_PROPERTY(QVideoSink* videoSink READ videoSink CONSTANT)
    Q_PROPERTY(bool hardwareAccelerated READ isHardwareAccelerated NOTIFY hwStatusChanged)

public:
    explicit GpuVideoSink(QQuickItem *parent = nullptr);
    ~GpuVideoSink();
    
    int videoWidth() const { return m_videoWidth; }
    int videoHeight() const { return m_videoHeight; }
    bool isHardwareAccelerated() const { return m_hwAccel; }
    
    QVideoSink* videoSink() const { return m_videoSink; }
    
    // 获取当前帧（用于抓拍）
    std::shared_ptr<GpuFrame> currentFrame() const;

public slots:
    void onFrameReady(std::shared_ptr<GpuFrame> frame);
    void displayVideoFrame(const QVideoFrame &frame);

signals:
    void videoSizeChanged();
    void hwStatusChanged();
    void frameDisplayed(qint64 frameIndex);

protected:
    QSGNode *updatePaintNode(QSGNode *oldNode, UpdatePaintNodeData *) override;

private:
    QVideoSink *m_videoSink = nullptr;
    std::shared_ptr<GpuFrame> m_currentFrame;
    QVideoFrame m_currentVideoFrame;
    
    int m_videoWidth = 0;
    int m_videoHeight = 0;
    bool m_hwAccel = false;
    
    QMutex m_mutex;
};

// ==================== 编码模式枚举 ====================
enum class FrameEncoderMode {
    JPEG_QSV = 1,    // Intel QSV JPEG 编码（已弃用）
    H264_NVENC = 2,  // NVIDIA NVENC H.264 I-frame 编码
    JPEG_SOFT = 3    // libjpeg-turbo 软件 JPEG 编码（兼容所有机型）
};

// ==================== GPU Frame Encoder ====================
/**
 * GPU 帧编码器（直接写文件模式）
 * 支持两种模式：
 * 1. JPEG (Intel QSV) - 需要 Intel 核显
 * 2. H.264 I-frame (NVIDIA NVENC) - 需要 NVIDIA 显卡
 */
class GpuJpegEncoder : public QThread
{
    Q_OBJECT
public:
    explicit GpuJpegEncoder(QObject *parent = nullptr);
    ~GpuJpegEncoder();
    
    // 提交帧进行编码
    void submitFrame(std::shared_ptr<GpuFrame> frame);
    
    // 停止
    void stop();
    
    // 清空（重新连接时调用）
    void reset();
    
    // 获取已编码帧数据（从文件读取）
    QByteArray getJpeg(qint64 frameIndex) const;
    bool hasJpeg(qint64 frameIndex) const;
    
    // 获取帧数据（根据当前编码模式，支持 JPEG 和 H.264）
    QByteArray getFrameData(qint64 frameIndex) const;
    
    // 获取当前编码模式
    FrameEncoderMode encoderMode() const { return m_encoderMode; }
    QString encoderModeName() const;
    
    // 状态
    qint64 oldestFrame() const { return m_oldestFrame; }
    qint64 newestFrame() const { return m_newestFrame; }
    int bufferSize() const { return static_cast<int>(m_newestFrame - m_oldestFrame + 1); }
    
    // 文件目录
    QString framesDir() const { return m_framesDir; }
    
    bool isHardwareAccelerated() const { return m_hwAccel; }
    
    // 颜色调整参数（在编码前应用）
    void setColorParams(double brightness, double contrast, double saturation, double hue, double gamma);
    
    // ⭐ 设置共享的 CUDA 设备上下文（GPU 直通）
    void setCudaDeviceContext(AVBufferRef *cudaCtx);
    bool isGpuDirect() const { return m_gpuDirect; }
    
    // 判断帧索引是否有效（在保留范围内）
    bool isIndexValid(qint64 frameIndex) const;
    
    // 注册有效的抓拍范围（返回注册 ID，用于取消注册）
    int registerValidRange(qint64 startIndex, qint64 endIndex);
    void unregisterValidRange(int rangeId);
    void clearValidRanges();

signals:
    void frameEncoded(qint64 frameIndex);
    void error(const QString &msg);

protected:
    void run() override;

private:
    bool initEncoder(int width, int height);
    QByteArray encodeFrame(std::shared_ptr<GpuFrame> frame);
    void applyColorAdjust(uint8_t *yPlane, uint8_t *uvPlane, int width, int height);  // YUV 颜色调整
    QString frameFilePath(qint64 frameIndex) const;
    void cleanupOldFiles();  // 清理过期文件
    bool deleteIfInvalid(qint64 index);  // 删除无效文件
    
    QQueue<std::shared_ptr<GpuFrame>> m_queue;
    
    // 文件存储
    QString m_framesDir;
    qint64 m_oldestFrame = -1;
    qint64 m_newestFrame = -1;
    
    // 最新帧缓存（避免频繁读文件）
    QByteArray m_latestJpeg;
    qint64 m_latestFrameIndex = -1;
    
    // 有效抓拍范围 (rangeId -> {startIndex, endIndex})
    struct ValidRange {
        qint64 startIndex;
        qint64 endIndex;
    };
    QMap<int, ValidRange> m_validRanges;
    int m_nextRangeId = 1;
    mutable QMutex m_rangesMutex;
    
    // 清理计数器
    int m_cleanupCounter = 0;
    
    // 颜色调整（使用 LUT 优化，避免每帧浮点运算）
    bool m_colorEnabled = false;        // 是否启用颜色调整
    uint8_t m_yLUT[256];                // Y 通道查找表（伽马+亮度+对比度）
    int16_t m_uvLUT[256][256][2];       // UV 通道查找表 [U][V] -> {newU, newV}（饱和度+色调）
    QMutex m_colorMutex;
    void rebuildColorLUT(double brightness, double contrast, double saturation, double hue, double gamma);
    
    AVCodecContext *m_codecCtx = nullptr;
    AVFrame *m_frame = nullptr;      // CPU 帧（回退用）
    AVFrame *m_hwFrame = nullptr;    // ⭐ GPU 硬件帧（CUDA）
    AVPacket *m_packet = nullptr;
    SwsContext *m_swsCtx = nullptr;
    AVBufferRef *m_hwDeviceCtx = nullptr;     // ⭐ 共享的 CUDA 设备上下文
    AVBufferRef *m_hwFramesCtx = nullptr;     // NVENC 硬件帧池（分辨率变化时重建）
    
    bool m_hwAccel = false;
    bool m_gpuDirect = false;        // ⭐ 是否使用 GPU 直通
    int m_width = 0;
    int m_height = 0;
    
    // 编码模式
    FrameEncoderMode m_encoderMode = FrameEncoderMode::H264_NVENC;  // 默认使用 H264
    QString m_fileExtension = ".h264";  // 文件扩展名
    
    // libjpeg-turbo 软件编码器（回退方案）
    tjhandle m_tjEncoder = nullptr;
    
    QMutex m_mutex;
    QMutex m_ringMutex;
    QWaitCondition m_condition;
    std::atomic<bool> m_running{true};
    
    // 常量
    static constexpr int RING_BUFFER_SIZE = 120;
    static constexpr int MAX_QUEUE_SIZE = 30;   // 增大队列避免丢帧
    // ⭐ JPEG 质量 — 慢放回放与实时画质对齐的关键
    //   85 → 95: 量化误差大幅降低, 卡牌字符/边缘细节保留 (Kirin 竞品默认 ~92, 95 更优)
    //   配合下面 TJSAMP_444 (不降色度) + TJFLAG_ACCURATEDCT (精确DCT) 才有效果
    //   代价: 单帧 JPEG ~30KB → ~90KB (3x), 60fps 1080p 30秒 = 160MB (从原 54MB)
    //   编码 CPU 约 +20%, 但走异步线程不影响实时流
    static constexpr int JPEG_QUALITY = 95;
    static constexpr int CLEANUP_CHECK_RANGE = 10000; // 清理检查范围（检查最近 10000 帧）
    static constexpr int CLEANUP_INTERVAL = 1200;     // 每 1200 帧清理一次（降低频率）
    static constexpr int SAFETY_MARGIN = 3600;        // 安全边界：保留最新 3600 帧（约1分钟@60fps）
};

// ==================== GPU H.264 Decoder ====================
/**
 * GPU H.264 I-frame 解码器（硬件加速）
 * 使用 NVIDIA CUVID 或 Intel QSV
 * 输出 QVideoFrame 用于直接 GPU 渲染
 */
class GpuJpegDecoder : public QObject
{
    Q_OBJECT
public:
    explicit GpuJpegDecoder(QObject *parent = nullptr);
    ~GpuJpegDecoder();
    
    // 解码 JPEG 到 QVideoFrame
    QVideoFrame decode(const QByteArray &frameData);
    
    // 解码 JPEG 到 QImage
    QImage decodeToImage(const QByteArray &frameData);
    
    bool isHardwareAccelerated() const { return m_hwAccel; }
    bool isInitFailed() const { return m_initFailed; }
    QString decoderName() const { return m_decoderName; }

signals:
    void initError(const QString &message);  // 初始化失败时发出

private:
    bool initDecoder(int width, int height);
    void cleanupDecoder();
    
    // libjpeg-turbo 软件解码器
    tjhandle m_tjDecoder = nullptr;
    
    // 备用 FFmpeg 解码器（不再使用）
    AVCodecContext *m_codecCtx = nullptr;
    AVBufferRef *m_hwDeviceCtx = nullptr;
    AVFrame *m_frame = nullptr;
    AVFrame *m_hwFrame = nullptr;
    AVPacket *m_packet = nullptr;
    SwsContext *m_swsCtx = nullptr;
    
    bool m_hwAccel = false;
    bool m_initFailed = false;
    bool m_initialized = false;
    QString m_decoderName;
    int m_width = 0;
    int m_height = 0;
    
    QMutex m_mutex;
};

// ==================== GPU Pipeline ====================
/**
 * 完整 GPU 管道
 * 整合解码器、渲染器、编码器
 */
class GpuPipeline : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool hardwareAccelerated READ isHardwareAccelerated NOTIFY statusChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)

public:
    explicit GpuPipeline(QObject *parent = nullptr);
    ~GpuPipeline();
    
    // 初始化管道
    Q_INVOKABLE bool init();
    
    // 输入 NALU
    Q_INVOKABLE void decodeNalu(const QByteArray &nalu, bool isKeyFrame);
    
    // 获取组件
    GpuDecoder* decoder() const { return m_decoder; }
    GpuJpegEncoder* jpegEncoder() const { return m_jpegEncoder; }
    GpuJpegDecoder* jpegDecoder() const { return m_jpegDecoder; }         // 慢放用
    GpuJpegDecoder* captureDecoder() const { return m_captureDecoder; }   // 抓拍用
    
    // 硬件解码到 QVideoFrame（慢放用）
    Q_INVOKABLE QVideoFrame decodeJpegToFrame(const QByteArray &jpegData);
    Q_INVOKABLE QVideoFrame decodeJpegToFrame(qint64 frameIndex);
    
    // 硬件解码到 QImage（抓拍用，独立解码器）
    Q_INVOKABLE QImage decodeFrameToImage(qint64 frameIndex);
    
    // 设置 QML VideoOutput 的 videoSink（全 GPU 渲染）
    Q_INVOKABLE void setVideoSink(QVideoSink *sink);
    
    // JPEG 访问（给 CaptureManager 用）
    Q_INVOKABLE QByteArray getJpeg(qint64 frameIndex) const;
    Q_INVOKABLE bool hasJpeg(qint64 frameIndex) const;
    Q_INVOKABLE qint64 newestFrame() const;
    Q_INVOKABLE qint64 oldestFrame() const;
    Q_INVOKABLE int jpegBufferSize() const;
    Q_INVOKABLE QString framesDir() const;
    
    // 重置（重连时调用）
    Q_INVOKABLE void resetJpegEncoder();
    
    // 设置 JPEG 编码的颜色参数
    Q_INVOKABLE void setJpegColorParams(double brightness, double contrast, double saturation, double hue, double gamma);
    
    bool isHardwareAccelerated() const;
    QString status() const { return m_status; }

signals:
    void statusChanged();
    void frameReady(qint64 frameIndex);
    void keyframeNeeded();
    void imageReady(const QImage &image, qint64 frameIndex);  // 用于 CaptureManager
    void jpegEncoderError(const QString &message);  // 硬件 JPEG 编码器错误
    void jpegDecoderError(const QString &message);  // 硬件 JPEG 解码器错误（慢放用）
    void resolutionChanged();  // 分辨率变化，通知 UI 显示黑屏遮罩

private slots:
    void onDecoderFrameReady(std::shared_ptr<GpuFrame> frame);
    void onFrameEncoded(qint64 frameIndex);
    void onDecoderReinitialized();  // 解码器重新初始化后更新编码器

private:
    GpuDecoder *m_decoder = nullptr;
    QVideoSink *m_videoSink = nullptr;
    GpuJpegEncoder *m_jpegEncoder = nullptr;
    GpuJpegDecoder *m_jpegDecoder = nullptr;      // 慢放用解码器
    GpuJpegDecoder *m_captureDecoder = nullptr;   // 抓拍用解码器（独立）
    QString m_status = "Not initialized";
};

#endif // GPUPIPELINE_H

