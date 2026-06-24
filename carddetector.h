#ifndef CARDDETECTOR_H
#define CARDDETECTOR_H

#include <QObject>
#include <QThread>
#include <QImage>
#include <QQueue>
#include <QMutex>
#include <QWaitCondition>
#include <QString>
#include <atomic>
#include <memory>
#include <vector>

/**
 * 牌位置检测结果（坐标在 640×640 模型空间，左上角原点）
 *
 * 只描述"牌在画面里的位置"，不含牌型/花色/大小信息。
 */
struct CardBox {
    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
    float confidence = 0.0f;
    bool valid = false;
};

/**
 * AI 牌位置检测器
 *
 * 设计目标（与实时流彻底解耦）：
 *  - 独立后台线程：QImage 进 / CardBox 出，不接触 GStreamer / GpuPipeline / 渲染线程
 *  - 只处理"当前这一张"：submit() 会丢弃队列里同 item 的旧请求
 *  - 默认 CPU 执行：与实时流的 D3D11/CUDA 视频管线零争抢，不卡实时流
 *
 * 模型：cardYolov8.onnx（YOLOv8 单类检测，输出 (1,5,8400)）
 */
class CardDetector : public QThread
{
    Q_OBJECT
public:
    explicit CardDetector(QObject *parent = nullptr);
    ~CardDetector() override;

    /**
     * 加载 ONNX 模型（在启用 AI 功能前调用一次，主线程）。
     * @return 加载成功返回 true；模型不存在或运行时初始化失败返回 false。
     */
    bool loadModel(const QString &onnxPath);

    bool isReady() const { return m_ready.load(); }

    /**
     * 投递一帧检测请求（异步，立即返回）。
     * 同一 itemIndex 的旧请求会被丢弃，保证"只处理最新这一张"。
     * frame 会被深拷贝，调用方无需保留所有权。
     */
    void submit(int itemIndex, int frameOffset, const QImage &frame);

    void stop();

signals:
    /**
     * 检测完成。box 在 640 空间；同时带回原图尺寸，便于业务层换算到原图/控件坐标。
     * box.valid == false 表示该帧未检出牌。
     */
    void detected(int itemIndex, int frameOffset, CardBox box, int origW, int origH);

protected:
    void run() override;

private:
    struct Task {
        int itemIndex = -1;
        int frameOffset = 0;
        QImage frame;
    };

    // QImage → 3*640*640 的 float NCHW（拉伸 + /255 归一化），与黄金瞳预处理一致
    bool preprocess(const QImage &img, std::vector<float> &out) const;
    // ORT 推理 + 置信度过滤 + NMS，返回最高分框（640 空间）
    CardBox infer(const std::vector<float> &input);

    static constexpr int MODEL_SIZE = 640;
    static constexpr int NUM_PREDS = 8400;   // YOLOv8 (1,5,8400)
    static constexpr int NUM_DIM = 5;        // cx, cy, w, h, conf（单类，无类别通道）
    static constexpr float CONF_THRESHOLD = 0.5f;
    static constexpr float IOU_THRESHOLD = 0.45f;

    // ONNX Runtime 句柄用 pimpl 隐藏，避免 ORT 头文件污染本头文件的包含方
    struct OrtImpl;
    std::unique_ptr<OrtImpl> m_ort;

    QQueue<Task> m_queue;
    QMutex m_mutex;
    QWaitCondition m_cond;
    std::atomic<bool> m_running{true};
    std::atomic<bool> m_ready{false};
};

#endif // CARDDETECTOR_H
