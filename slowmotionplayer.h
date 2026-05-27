#ifndef SLOWMOTIONPLAYER_H
#define SLOWMOTIONPLAYER_H

#include <QObject>
#include <QImage>
#include <QTimer>
#include <QSettings>
#include <QCache>
#include <QMutex>
#include <QVideoSink>
#include <QVideoFrame>
#include <QThread>
#include <QQueue>
#include <QWaitCondition>
#include <atomic>

class GpuPipeline;
class GstPlayer;
class NaluFrameStore;
class GstCaptureDecoder;

class SlowMotionDecodeThread : public QThread
{
    Q_OBJECT
public:
    explicit SlowMotionDecodeThread(NaluFrameStore *store, QObject *parent = nullptr);
    ~SlowMotionDecodeThread();

    void requestDecode(qint64 globalFrameIndex, int frameOffset, bool scale = false);
    void stop();

signals:
    void frameDecoded(int frameOffset, const QVideoFrame &frame);

protected:
    void run() override;

private:
    NaluFrameStore *m_store;
    GstCaptureDecoder *m_decoder = nullptr;
    std::atomic<bool> m_running{true};
    QMutex m_queueMutex;
    QWaitCondition m_queueCondition;
    struct DecodeRequest {
        qint64 globalIndex;
        int frameOffset;
        bool scale;
    };
    QQueue<DecodeRequest> m_decodeQueue;
};

/**
 * 慢放播放器
 * 
 * 状态机：
 * - IDLE: 空闲，无慢放数据
 * - RECORDING: 录制中（持续保存JPEG，显示按倍数减速）
 * - PLAYBACK: 回放（可拖动进度条，按倍数调速播放）
 * 
 * 核心逻辑：
 * - 复用 GpuJpegEncoder 的 JPEG 保存（不额外维护文件夹）
 * - 记录 startIndex/endIndex（与抓拍逻辑一致，只是跨度大）
 * - 通过 registerValidRange 保护帧不被清理
 */
class SlowMotionPlayer : public QObject
{
    Q_OBJECT
    
    // 状态
    Q_PROPERTY(State state READ state NOTIFY stateChanged)
    Q_PROPERTY(bool isRecording READ isRecording NOTIFY stateChanged)
    Q_PROPERTY(bool isPlayback READ isPlayback NOTIFY stateChanged)
    Q_PROPERTY(bool hasContent READ hasContent NOTIFY hasContentChanged)
    
    // 帧信息
    Q_PROPERTY(int currentFrame READ currentFrame WRITE setCurrentFrame NOTIFY currentFrameChanged)
    Q_PROPERTY(int recordedFrames READ recordedFrames NOTIFY recordedFramesChanged)
    Q_PROPERTY(int maxFrames READ maxFrames WRITE setMaxFrames NOTIFY maxFramesChanged)
    
    // 播放控制
    Q_PROPERTY(double playbackMultiplier READ playbackMultiplier WRITE setPlaybackMultiplier NOTIFY playbackMultiplierChanged)
    Q_PROPERTY(int maxFrameRate READ maxFrameRate WRITE setMaxFrameRate NOTIFY maxFrameRateChanged)
    Q_PROPERTY(bool isPlaying READ isPlaying NOTIFY playingChanged)
    
    // GpuPipeline
    Q_PROPERTY(GpuPipeline* gpuPipeline READ gpuPipeline WRITE setGpuPipeline NOTIFY gpuPipelineChanged)
    Q_PROPERTY(GstPlayer* gstPlayer READ gstPlayer WRITE setGstPlayer NOTIFY gstPlayerChanged)
    
    // VideoSink（用于直接渲染，避免 QImage 内存开销）
    Q_PROPERTY(QVideoSink* videoSink READ videoSink WRITE setVideoSink NOTIFY videoSinkChanged)

public:
    enum State {
        IDLE,       // 空闲
        RECORDING,  // 录制中
        PLAYBACK    // 回放
    };
    Q_ENUM(State)
    
    explicit SlowMotionPlayer(QObject *parent = nullptr);
    ~SlowMotionPlayer();

    // 属性访问
    State state() const { return m_state; }
    bool isRecording() const { return m_state == RECORDING; }
    bool isPlayback() const { return m_state == PLAYBACK; }
    // 有内容：有录制帧（确保有实际数据）
    bool hasContent() const { return m_recordedFrames > 0; }
    
    int currentFrame() const { return m_currentFrame; }
    int recordedFrames() const { return m_recordedFrames; }
    int maxFrames() const { return m_maxFrames; }
    
    double playbackMultiplier() const { return m_playbackMultiplier; }
    int maxFrameRate() const { return m_maxFrameRate; }
    bool isPlaying() const { return m_isPlaying; }
    
    GpuPipeline* gpuPipeline() const { return m_gpuPipeline; }
    GstPlayer* gstPlayer() const { return m_gstPlayer; }
    QVideoSink* videoSink() const { return m_videoSink; }
    
    // 设置
    void setCurrentFrame(int frame);
    void setVideoSink(QVideoSink* sink);
    void setMaxFrames(int frames);
    void setPlaybackMultiplier(double multiplier);
    void setMaxFrameRate(int fps);
    void setGpuPipeline(GpuPipeline* pipeline);
    void setGstPlayer(GstPlayer* player);

    // 控制
    Q_INVOKABLE void startRecording();   // 开启慢放（进入RECORDING状态）
    Q_INVOKABLE void stopRecording();    // 停止录制（进入PLAYBACK状态）
    Q_INVOKABLE void clear();            // 清空（进入IDLE状态）
    
    // 获取当前帧的全局文件索引（用于慢放抓拍）
    Q_INVOKABLE qint64 currentGlobalFrameIndex() const;
    
    // 播放控制
    Q_INVOKABLE void play();
    Q_INVOKABLE void pause();
    Q_INVOKABLE void togglePlay();
    
    // 帧步进
    Q_INVOKABLE void nextFrame();
    Q_INVOKABLE void prevFrame();
    Q_INVOKABLE void jumpToFrame(int frame);
    
    // 获取帧图像（从JPEG文件读取）
    Q_INVOKABLE QImage getFrameImage(int frameOffset) const;
    
    // 保存当前帧
    Q_INVOKABLE bool saveCurrentFrame(const QString &path);
    
    // 获取帧的全局索引
    qint64 startIndex() const { return m_startIndex; }
    qint64 endIndex() const { return m_endIndex; }

signals:
    void stateChanged();
    void currentFrameChanged();
    void recordedFramesChanged();
    void maxFramesChanged();
    void playbackMultiplierChanged();
    void maxFrameRateChanged();
    void playingChanged();
    void hasContentChanged();
    void gpuPipelineChanged();
    void gstPlayerChanged();
    void videoSinkChanged();
    void frameReady(const QImage &frame);

private slots:
    void onPlaybackTimer();
    void onFrameEncoded(qint64 frameIndex);

private:
    void setState(State state);
    void updateTimerInterval();
    void emitCurrentFrame();
    void loadSettings();
    void saveSettings();
    void updateEndIndex(qint64 frameIndex);
    void ensureJpegEncoderConnected();
    void renderToVideoSink(int frameOffset);  // 直接渲染到 VideoSink

private:
    GpuPipeline *m_gpuPipeline = nullptr;
    GstPlayer *m_gstPlayer = nullptr;
    QVideoSink *m_videoSink = nullptr;
    
    State m_state = IDLE;
    
    // 帧范围（全局 NALU 索引）
    qint64 m_startIndex = -1;
    qint64 m_endIndex = -1;
    int m_validRangeId = -1;
    GstCaptureDecoder *m_gstDecoder = nullptr;
    
    // 当前播放位置（相对于startIndex的偏移，0 ~ recordedFrames-1）
    int m_currentFrame = 0;
    int m_recordedFrames = 0;
    
    // 配置
    int m_maxFrames = 5000;      // 最大录制帧数
    double m_playbackMultiplier = 1.0; // 慢放倍数 1-10，步长0.5
    int m_maxFrameRate = 60;      // 最大帧率
    
    bool m_isPlaying = false;
    bool m_isDragging = false;    // 是否正在拖动滑块
    bool m_followLive = true;     // 是否跟随实时流（录制时）
    
    QTimer *m_playbackTimer;
    QSettings m_settings;
    
    // 帧缓存（LRU缓存，避免重复解码大图像 - 仅用于保存）
    mutable QCache<qint64, QImage> m_frameCache;
    mutable QMutex m_cacheMutex;
    static constexpr int FRAME_CACHE_SIZE = 10;  // 缓存10帧
    
    // 异步解码线程（避免主线程阻塞导致实时流卡顿）
    SlowMotionDecodeThread *m_decodeThread = nullptr;
    int m_pendingFrameOffset = -1;  // 等待解码的帧（用于跳帧优化）
    
    // 常量
    static constexpr double MULTIPLIER_MIN = 1.0;
    static constexpr double MULTIPLIER_MAX = 10.0;
    
private slots:
    void onFrameDecoded(int frameOffset, const QVideoFrame &frame);
};

#endif // SLOWMOTIONPLAYER_H
