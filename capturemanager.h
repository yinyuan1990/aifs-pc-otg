#ifndef CAPTUREMANAGER_H
#define CAPTUREMANAGER_H

#include <QObject>
#include <QImage>
#include <QVector>
#include <QMutex>
#include <QQueue>
#include <QThread>
#include <QWaitCondition>
#include <QSettings>
#include <QDir>
#include <QDateTime>
#include <QSet>
#include <QPair>
#include <atomic>
#include "gpupipeline.h"
#include "gstplayer.h"
#include "gstcapturedecoder.h"
#include "naluframestore.h"

// 前向声明
struct AVCodecContext;
struct AVFrame;
struct AVPacket;
struct SwsContext;

#include "slowmotionplayer.h"

/**
 * JPEG 帧 - 压缩后的数据
 */
struct JpegFrame {
    QByteArray data;        // JPEG 压缩数据 (~150KB)
    qint64 frameIndex = 0;  // 全局帧索引
};

/**
 * JPEG Ring Buffer - 内存中存储压缩后的帧
 * 120帧 × 150KB = 18MB 内存
 */
class JpegRingBuffer
{
public:
    explicit JpegRingBuffer(int capacity = 120);
    
    void addFrame(const QByteArray &jpegData, qint64 frameIndex);
    JpegFrame getFrame(int offset) const;  // offset: 0=最新, -1=前一帧
    JpegFrame getFrameByIndex(qint64 frameIndex) const;
    bool hasFrame(qint64 frameIndex) const;
    
    int size() const;
    int capacity() const { return m_capacity; }
    qint64 oldestIndex() const;
    qint64 newestIndex() const;
    void clear();

private:
    QVector<JpegFrame> m_buffer;
    int m_capacity;
    int m_head = 0;     // 写入位置
    int m_count = 0;
    mutable QMutex m_mutex;
};

/**
 * JPEG 编码器线程 - 将 QImage 编码为 JPEG 存入 Ring Buffer
 */
class JpegEncoder : public QThread
{
    Q_OBJECT
public:
    explicit JpegEncoder(JpegRingBuffer *buffer, QObject *parent = nullptr);
    ~JpegEncoder();
    
    void submitFrame(const QImage &frame, qint64 frameIndex);
    void stop();
    
    qint64 currentIndex() const { return m_currentIndex.load(); }

signals:
    void frameEncoded(qint64 index);

protected:
    void run() override;

private:
    bool initEncoder(int width, int height);
    void cleanupEncoder();
    QByteArray encodeJpeg(const QImage &image);

private:
    struct Task {
        QImage frame;
        qint64 frameIndex;
    };
    
    JpegRingBuffer *m_ringBuffer;
    QQueue<Task> m_queue;
    QMutex m_mutex;
    QWaitCondition m_condition;
    std::atomic<bool> m_running{true};
    std::atomic<qint64> m_currentIndex{0};
    
    // FFmpeg MJPEG 编码器
    AVCodecContext *m_codecCtx = nullptr;
    AVFrame *m_frame = nullptr;
    AVPacket *m_packet = nullptr;
    SwsContext *m_swsCtx = nullptr;
    int m_encoderWidth = 0;
    int m_encoderHeight = 0;
    bool m_useHardware = false;
    
    static constexpr int MAX_QUEUE_SIZE = 5;   // 编码队列
    static constexpr int JPEG_QUALITY = 85;
};

/**
 * NALU 磁盘写入线程
 *
 * 把 .nalu 文件落盘从主线程（同时是渲染线程）移到后台，
 * 避免抓拍时同步磁盘 I/O 阻塞 onRenderTick 导致直播画面卡顿。
 */
class NaluDiskWriter : public QThread
{
    Q_OBJECT
public:
    explicit NaluDiskWriter(QObject *parent = nullptr);
    ~NaluDiskWriter();

    // 单帧写入
    void submit(const QString &path, const QByteArray &data);
    // 批量写入，整批写完后发出 batchWritten(tag)（tag 用 CaptureItem.id）
    void submitBatch(const QVector<QPair<QString, QByteArray>> &tasks, int tag);
    void stop();

signals:
    void batchWritten(int tag);

protected:
    void run() override;

private:
    struct WriteTask {
        QString path;
        QByteArray data;
        int batchTag = -1;   // >=0 表示该任务是某批次的最后一个，写完后发信号
    };
    QQueue<WriteTask> m_queue;
    QMutex m_mutex;
    QWaitCondition m_condition;
    std::atomic<bool> m_running{true};
};

/**
 * 抓拍 Item
 */
struct CaptureItem {
    int id = 0;
    qint64 startIndex = 0;      // 起始帧索引
    qint64 eventIndex = 0;      // 事件帧索引
    qint64 endIndex = 0;        // 结束帧索引
    int currentOffset = 0;      // 当前显示偏移
    qint64 timestamp = 0;
    QString naluDir;            // 旧 NALU 目录（兼容旧 item）
    QVector<int> keyFrameOffsets; // 旧关键帧偏移列表（兼容旧 item）
    int savedFrameCount = 0;    // 已保存/可用帧数
    int h264ValidRangeId = -1;  // H.264 独立帧文件保护范围
    QImage liveSnapshot;        // 抓拍瞬间的直播画面（仅缩略/首屏兜底）

    int totalFrames() const { return static_cast<int>(endIndex - startIndex + 1); }
    int eventOffset() const { return static_cast<int>(eventIndex - startIndex); }
};

/**
 * 待收集后续帧的抓拍
 */
struct PendingCapture {
    int itemIndex;
    qint64 targetEndIndex;
};

/**
 * 抓拍管理器 - 内存 JPEG Ring Buffer 方案
 * 
 * 工作流程：
 * 1. 每帧编码成 JPEG 存入内存 Ring Buffer（约 18MB）
 * 2. 抓拍时从 Ring Buffer 取帧，异步写入磁盘
 * 3. 播放时从内存读取（如果还在 buffer 中）或从磁盘读取
 */
class CaptureManager : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int count READ count NOTIFY countChanged)
    Q_PROPERTY(int maxCount READ maxCount CONSTANT)
    Q_PROPERTY(int gridRows READ gridRows WRITE setGridRows NOTIFY gridSettingsChanged)
    Q_PROPERTY(int gridCols READ gridCols WRITE setGridCols NOTIFY gridSettingsChanged)
    Q_PROPERTY(bool isHorizontalLayout READ isHorizontalLayout WRITE setIsHorizontalLayout NOTIFY gridSettingsChanged)
    Q_PROPERTY(int preFrameCount READ preFrameCount WRITE setPreFrameCount NOTIFY captureSettingsChanged)
    Q_PROPERTY(int postFrameCount READ postFrameCount WRITE setPostFrameCount NOTIFY captureSettingsChanged)
    Q_PROPERTY(int currentItemIndex READ currentItemIndex WRITE setCurrentItemIndex NOTIFY currentItemChanged)
    Q_PROPERTY(GpuPipeline* gpuPipeline READ gpuPipeline WRITE setGpuPipeline NOTIFY gpuPipelineChanged)
    Q_PROPERTY(GstPlayer* gstPlayer READ gstPlayer WRITE setGstPlayer NOTIFY gstPlayerChanged)
    
    // 相机设定（色彩调整）
    Q_PROPERTY(double brightness READ brightness WRITE setBrightness NOTIFY cameraSettingsChanged)
    Q_PROPERTY(double contrast READ contrast WRITE setContrast NOTIFY cameraSettingsChanged)
    Q_PROPERTY(double saturation READ saturation WRITE setSaturation NOTIFY cameraSettingsChanged)
    Q_PROPERTY(double hue READ hue WRITE setHue NOTIFY cameraSettingsChanged)
    Q_PROPERTY(double gamma READ gamma WRITE setGamma NOTIFY cameraSettingsChanged)
    Q_PROPERTY(double exposure READ exposure WRITE setExposure NOTIFY cameraSettingsChanged)
    
    // 视频旋转角度 (0, 90, 180, 270)
    Q_PROPERTY(int videoRotation READ videoRotation WRITE setVideoRotation NOTIFY videoRotationChanged)
    
    // 视频缩放参数（用于截图时应用相同的缩放裁剪）
    Q_PROPERTY(double videoZoom READ videoZoom WRITE setVideoZoom NOTIFY videoZoomChanged)
    Q_PROPERTY(double videoOffsetX READ videoOffsetX WRITE setVideoOffsetX NOTIFY videoZoomChanged)
    Q_PROPERTY(double videoOffsetY READ videoOffsetY WRITE setVideoOffsetY NOTIFY videoZoomChanged)
    Q_PROPERTY(double displayWidth READ displayWidth WRITE setDisplayWidth NOTIFY videoZoomChanged)
    Q_PROPERTY(double displayHeight READ displayHeight WRITE setDisplayHeight NOTIFY videoZoomChanged)
    
    // 慢放抓拍模式
    Q_PROPERTY(bool slowMotionActive READ slowMotionActive WRITE setSlowMotionActive NOTIFY slowMotionActiveChanged)
    Q_PROPERTY(SlowMotionPlayer* slowMotionPlayer READ slowMotionPlayer WRITE setSlowMotionPlayer NOTIFY slowMotionPlayerChanged)

public:
    static constexpr int MAX_ITEMS = 1000;
    static constexpr int DEFAULT_PRE_FRAMES = 10;
    static constexpr int DEFAULT_POST_FRAMES = 10;
    static constexpr int DEFAULT_GRID_ROWS = 2;
    static constexpr int DEFAULT_GRID_COLS = 2;
    static constexpr int MAX_GRID_SIZE = 10;
    static constexpr int MAX_PRE_POST_FRAMES = 1000;  // 前抓拍最大120（QML限制），后抓拍可无限
    static constexpr int RING_BUFFER_SIZE = 120;  // 2秒 @ 60fps
    
    // 相机设定默认值（与 GStreamer videobalance/gamma 一致）
    static constexpr double DEFAULT_BRIGHTNESS = -0.02;  // 亮度：不再联动，独立控制
    static constexpr double DEFAULT_CONTRAST = 1.10;     // 对比度：1.0 ~ 1.35（综合亮度20→1.10, 100→1.35）
    static constexpr double DEFAULT_SATURATION = 1.10;   // 饱和度：1.0 ~ 1.35（综合亮度20→1.10, 100→1.35）
    static constexpr double DEFAULT_HUE = -0.02;         // 色调：-1.0 ~ 1.0（不再联动，独立控制）
    static constexpr double DEFAULT_GAMMA = 1.08;        // 伽马：1.0 ~ 1.35（综合亮度20→1.08, 100→1.35）
    static constexpr double DEFAULT_EXPOSURE = 20.0;     // 曝光度：0 ~ 100%（综合调节，Java 默认 20%）

    explicit CaptureManager(QObject *parent = nullptr);
    ~CaptureManager();
    
    void loadSettings();
    void saveSettings();

    int count() const { return m_items.size(); }
    int maxCount() const { return MAX_ITEMS; }
    int gridRows() const { return m_gridRows; }
    int gridCols() const { return m_gridCols; }
    bool isHorizontalLayout() const { return m_isHorizontalLayout; }
    int preFrameCount() const { return m_preFrameCount; }
    int postFrameCount() const { return m_postFrameCount; }
    int currentItemIndex() const { return m_currentItemIndex; }
    
    void setGridRows(int rows);
    void setGridCols(int cols);
    void setIsHorizontalLayout(bool horizontal);
    void setPreFrameCount(int count);
    void setPostFrameCount(int count);
    void setCurrentItemIndex(int index);
    
    // 计算格子在网格中的位置（根据横向/纵向模式）
    Q_INVOKABLE int getGridRow(int index) const;
    Q_INVOKABLE int getGridCol(int index) const;
    
    // 相机设定 getter
    double brightness() const { return m_brightness; }
    double contrast() const { return m_contrast; }
    double saturation() const { return m_saturation; }
    double hue() const { return m_hue; }
    double gamma() const { return m_gamma; }
    double exposure() const { return m_exposure; }
    
    // 曝光预览（只应用效果，不保存到本地）
    Q_INVOKABLE void applyExposurePreview(double value);
    
    // 视频旋转
    int videoRotation() const { return m_videoRotation; }
    void setVideoRotation(int rotation);
    
    // 视频缩放
    double videoZoom() const { return m_videoZoom; }
    double videoOffsetX() const { return m_videoOffsetX; }
    double videoOffsetY() const { return m_videoOffsetY; }
    double displayWidth() const { return m_displayWidth; }
    double displayHeight() const { return m_displayHeight; }
    void setVideoZoom(double zoom);
    void setVideoOffsetX(double offsetX);
    void setVideoOffsetY(double offsetY);
    void setDisplayWidth(double width);
    void setDisplayHeight(double height);
    
    // 相机设定 setter
    void setBrightness(double value);
    void setContrast(double value);
    void setSaturation(double value);
    void setHue(double value);
    void setGamma(double value);
    void setExposure(double value);  // 综合调节算法
    Q_INVOKABLE void resetCameraSettings();  // 恢复默认
    Q_INVOKABLE void zoomLog(const QString &msg);  // 缩放调试日志写入 zp.txt
    
    // 慢放抓拍模式
    bool slowMotionActive() const { return m_slowMotionActive; }
    void setSlowMotionActive(bool active);
    SlowMotionPlayer* slowMotionPlayer() const { return m_slowMotionPlayer; }
    void setSlowMotionPlayer(SlowMotionPlayer* player);

    Q_INVOKABLE void capture();
    Q_INVOKABLE void clearAll();
    Q_INVOKABLE void removeItem(int index);
    Q_INVOKABLE void reset();
    
    Q_INVOKABLE int itemCount() const { return m_items.size(); }
    Q_INVOKABLE int getTotalFrames(int itemIndex) const;
    Q_INVOKABLE int getEventOffset(int itemIndex) const;
    Q_INVOKABLE int getCurrentOffset(int itemIndex) const;
    Q_INVOKABLE bool isItemReady(int itemIndex) const;
    
    // 帧导航
    Q_INVOKABLE void gotoFrame(int itemIndex, int frameOffset);
    Q_INVOKABLE void nextFrame(int itemIndex);
    Q_INVOKABLE void prevFrame(int itemIndex);
    Q_INVOKABLE void gotoEventFrame(int itemIndex);
    
    // 获取帧图像
    Q_INVOKABLE QImage getFrameImage(int itemIndex, int frameOffset);
    
    // 当前帧索引
    Q_INVOKABLE qint64 currentFrameIndex() const;
    
    QString capturesDir() const { return m_capturesDir; }
    
    // GpuPipeline 访问
    GpuPipeline* gpuPipeline() const { return m_gpuPipeline; }
    void setGpuPipeline(GpuPipeline* pipeline);
    
    // GstPlayer 访问（JPEG 读取）
    GstPlayer* gstPlayer() const { return m_gstPlayer; }
    void setGstPlayer(GstPlayer* player);

public slots:
    void onFrameReceived(const QImage &frame, qint64 frameIndex);
    void onFrameIndexReady(qint64 frameIndex);  // 新增：只接收帧索引
    void onNaluReceived(const QByteArray &nalu, bool isKeyFrame) { Q_UNUSED(nalu); Q_UNUSED(isKeyFrame); }

signals:
    void countChanged();
    void gridSettingsChanged();
    void captureSettingsChanged();
    void currentItemChanged();
    void gpuPipelineChanged();
    void gstPlayerChanged();
    void cameraSettingsChanged();  // 相机设定变化
    void videoRotationChanged();   // 视频旋转变化
    void videoZoomChanged();       // 视频缩放变化
    void slowMotionActiveChanged();  // 慢放抓拍模式变化
    void slowMotionPlayerChanged();  // 慢放播放器变化
    void itemAdded(int index);
    void itemRemoved(int index);
    void captureComplete(int index);
    void frameChanged(int itemIndex, int frameOffset);
    void frameImageReady(int itemIndex, int frameOffset);

private slots:
    void onFrameEncoded(qint64 index);
    void onBatchWritten(int tag);  // 后台落盘完成后触发可见帧解码

private:
    void removeOldest();
    void ensureCapturesDir();
    void checkPendingCaptures(qint64 frameIndex);
    QImage decodeFromDisk(int itemIndex, int frameOffset);
    static QByteArray readNaluFile(const QString &dir, int frameOffset);
    void scheduleFrameDecode(int itemIndex, int frameOffset);
    bool tryGetFrameCache(int itemIndex, int frameOffset, QImage *out) const;
    void putFrameCache(int itemIndex, int frameOffset, const QImage &img);
    void evictFrameCache();
    void syncColorToJpegEncoder();

private:
    // 每个 item 独立 GStreamer 解码器（avdec_h264，不与实时流 GPU 竞争）
    struct ItemDecodeState {
        GstCaptureDecoder *decoder = nullptr;
        int lastOffset = -1;
    };
    QHash<int, ItemDecodeState> m_itemDecoders;
    GpuPipeline *m_gpuPipeline = nullptr;  // GPU 管道（颜色调整）
    GstPlayer *m_gstPlayer = nullptr;      // GStreamer 播放器（NALU 帧存储）
    NaluDiskWriter *m_diskWriter = nullptr; // NALU 后台落盘线程
    QVector<CaptureItem> m_items;
    QList<PendingCapture> m_pendingCaptures;
    QSettings *m_settings;
    QString m_capturesDir;
    
    int m_nextId = 1;
    int m_gridRows = DEFAULT_GRID_ROWS;
    int m_gridCols = DEFAULT_GRID_COLS;
    bool m_isHorizontalLayout = true;  // true=横向(行优先), false=纵向(列优先)
    int m_preFrameCount = DEFAULT_PRE_FRAMES;
    int m_postFrameCount = DEFAULT_POST_FRAMES;
    int m_currentItemIndex = -1;
    qint64 m_currentFrameIdx = 0;  // 当前帧索引
    
    // 相机设定
    double m_brightness = DEFAULT_BRIGHTNESS;
    double m_contrast = DEFAULT_CONTRAST;
    double m_saturation = DEFAULT_SATURATION;
    double m_hue = DEFAULT_HUE;
    double m_gamma = DEFAULT_GAMMA;
    double m_exposure = DEFAULT_EXPOSURE;
    
    // 视频旋转角度
    int m_videoRotation = 0;
    
    // 视频缩放参数
    double m_videoZoom = 1.0;
    double m_videoOffsetX = 0.0;
    double m_videoOffsetY = 0.0;
    double m_displayWidth = 1920.0;
    double m_displayHeight = 1080.0;
    
    // 慢放抓拍模式
    bool m_slowMotionActive = false;
    SlowMotionPlayer *m_slowMotionPlayer = nullptr;
    
    // 单帧快速缓存（最近一帧）
    mutable int m_cachedItemIndex = -1;
    mutable int m_cachedFrameOffset = -1;
    mutable int m_cachedRotation = 0;
    mutable QImage m_cachedImage;

    // LRU 帧缓存（同步解码后缓存）
    struct FrameCacheEntry {
        QImage image;
        qint64 accessOrder = 0;
    };
    QHash<qint64, FrameCacheEntry> m_frameCache;
    qint64 m_frameCacheCounter = 0;
    static constexpr int MAX_FRAME_CACHE = 300;

    QMutex m_mutex;
    QMutex m_decodeMutex;
    QSet<qint64> m_pendingDecodes;
    std::atomic<int> m_clearGeneration{0};

    // 日志计数
    qint64 m_lastLogFrame = 0;
};

#endif // CAPTUREMANAGER_H
