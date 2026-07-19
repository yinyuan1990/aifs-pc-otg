#include "carddetector.h"

#include <QFile>
#include <QDebug>
#include <QElapsedTimer>
#include <algorithm>
#include <cmath>

#include <onnxruntime_cxx_api.h>

// ── ONNX Runtime 句柄（pimpl） ─────────────────────────────
struct CardDetector::OrtImpl {
    Ort::Env env{ORT_LOGGING_LEVEL_WARNING, "CardDetector"};
    Ort::SessionOptions options;
    std::unique_ptr<Ort::Session> session;
    Ort::AllocatorWithDefaultOptions allocator;

    std::string inputName;
    std::string outputName;
    std::vector<const char*> inputNames;
    std::vector<const char*> outputNames;
};

CardDetector::CardDetector(QObject *parent)
    : QThread(parent)
{
}

CardDetector::~CardDetector()
{
    stop();
}

bool CardDetector::loadModel(const QString &onnxPath)
{
    if (!QFile::exists(onnxPath)) {
        qWarning() << "[CardDetector] 模型文件不存在:" << onnxPath;
        return false;
    }

    try {
        m_ort = std::make_unique<OrtImpl>();

        // ⭐ CPU 执行：与实时流的 D3D11/CUDA 视频管线物理隔离，不卡实时流
        //   限制线程数，避免单帧推理瞬时抢占过多 CPU 影响其它后台线程
        m_ort->options.SetIntraOpNumThreads(2);
        m_ort->options.SetInterOpNumThreads(1);
        m_ort->options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

#ifdef _WIN32
        std::wstring wpath = onnxPath.toStdWString();
        m_ort->session = std::make_unique<Ort::Session>(m_ort->env, wpath.c_str(), m_ort->options);
#else
        std::string path = onnxPath.toStdString();
        m_ort->session = std::make_unique<Ort::Session>(m_ort->env, path.c_str(), m_ort->options);
#endif

        // 取输入/输出名（不写死，兼容不同导出命名）
        Ort::AllocatedStringPtr inName = m_ort->session->GetInputNameAllocated(0, m_ort->allocator);
        Ort::AllocatedStringPtr outName = m_ort->session->GetOutputNameAllocated(0, m_ort->allocator);
        m_ort->inputName = inName.get();
        m_ort->outputName = outName.get();
        m_ort->inputNames = { m_ort->inputName.c_str() };
        m_ort->outputNames = { m_ort->outputName.c_str() };

        m_ready.store(true);
        qInfo() << "[CardDetector] 模型加载成功(CPU):" << onnxPath
                << "input=" << QString::fromStdString(m_ort->inputName)
                << "output=" << QString::fromStdString(m_ort->outputName);
        return true;
    } catch (const Ort::Exception &e) {
        qWarning() << "[CardDetector] ORT 加载失败:" << e.what();
        m_ort.reset();
        m_ready.store(false);
        return false;
    } catch (const std::exception &e) {
        qWarning() << "[CardDetector] 加载异常:" << e.what();
        m_ort.reset();
        m_ready.store(false);
        return false;
    }
}

void CardDetector::submit(int itemIndex, int frameOffset, const QImage &frame)
{
    if (!m_ready.load() || frame.isNull())
        return;

    QMutexLocker lk(&m_mutex);
    // 丢弃队列里属于同一 item 的旧任务 → 永远只检测"最新请求的那一张"
    QQueue<Task> kept;
    while (!m_queue.isEmpty()) {
        Task t = m_queue.dequeue();
        if (t.itemIndex != itemIndex)
            kept.enqueue(std::move(t));
    }
    m_queue = std::move(kept);

    Task task;
    task.itemIndex = itemIndex;
    task.frameOffset = frameOffset;
    task.frame = frame.copy();   // 深拷贝，脱离实时流/解码缓冲
    m_queue.enqueue(std::move(task));
    m_cond.wakeOne();
}

void CardDetector::stop()
{
    if (!isRunning() && !m_running.load())
        return;
    {
        QMutexLocker lk(&m_mutex);
        m_running.store(false);
        m_queue.clear();
        m_cond.wakeAll();
    }
    wait();
}

void CardDetector::run()
{
    while (m_running.load()) {
        Task task;
        {
            QMutexLocker lk(&m_mutex);
            while (m_queue.isEmpty() && m_running.load())
                m_cond.wait(&m_mutex);
            if (!m_running.load())
                return;
            task = std::move(m_queue.dequeue());
        }

        if (task.frame.isNull() || !m_ready.load())
            continue;

        const int origW = task.frame.width();
        const int origH = task.frame.height();

        QElapsedTimer timer;
        timer.start();
        CardBox box;
        try {
            std::vector<float> input;
            if (preprocess(task.frame, input))
                box = infer(input);
        } catch (const std::exception &e) {
            qWarning() << "[CardDetector] 推理异常:" << e.what();
            box = CardBox{};
        }
        box.inferMs = static_cast<int>(timer.elapsed());

        emit detected(task.itemIndex, task.frameOffset, box, origW, origH);
    }
}

bool CardDetector::preprocess(const QImage &src, std::vector<float> &out) const
{
    // 拉伸到 640×640 + 转 RGB888（对应黄金瞳 sharp fit:'fill' + removeAlpha）
    QImage img = src.convertToFormat(QImage::Format_RGB888)
                    .scaled(MODEL_SIZE, MODEL_SIZE,
                            Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    if (img.isNull())
        return false;

    const int pixels = MODEL_SIZE * MODEL_SIZE;
    out.resize(static_cast<size_t>(3) * pixels);

    // HWC(RGBRGB...) → CHW 三平面，并 /255 归一化
    for (int y = 0; y < MODEL_SIZE; ++y) {
        const uchar *line = img.constScanLine(y);
        for (int x = 0; x < MODEL_SIZE; ++x) {
            const int i = y * MODEL_SIZE + x;
            out[i]                    = line[x * 3 + 0] / 255.0f;  // R
            out[pixels + i]           = line[x * 3 + 1] / 255.0f;  // G
            out[2 * pixels + i]       = line[x * 3 + 2] / 255.0f;  // B
        }
    }
    return true;
}

CardBox CardDetector::infer(const std::vector<float> &input)
{
    if (!m_ort || !m_ort->session)
        return CardBox{};

    Ort::MemoryInfo memInfo = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    const std::array<int64_t, 4> inputShape{1, 3, MODEL_SIZE, MODEL_SIZE};

    Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
        memInfo, const_cast<float*>(input.data()), input.size(),
        inputShape.data(), inputShape.size());

    auto outputs = m_ort->session->Run(
        Ort::RunOptions{nullptr},
        m_ort->inputNames.data(), &inputTensor, 1,
        m_ort->outputNames.data(), 1);

    if (outputs.empty())
        return CardBox{};

    const float *data = outputs[0].GetTensorData<float>();
    auto shape = outputs[0].GetTensorTypeAndShapeInfo().GetShape();

    // 期望 (1, 5, 8400)。容错：按实际 shape 取 dim/numPreds
    int dim = NUM_DIM, numPreds = NUM_PREDS;
    if (shape.size() == 3) {
        dim = static_cast<int>(shape[1]);
        numPreds = static_cast<int>(shape[2]);
    }
    if (dim < 5 || numPreds <= 0)
        return CardBox{};

    struct Raw { float cx, cy, w, h, conf; };
    std::vector<Raw> raw;
    raw.reserve(64);
    float maxConfSeen = 0.0f;  // 全体候选最高分（含低于阈值的），用于区分"差一点"vs"完全无牌"
    for (int i = 0; i < numPreds; ++i) {
        const float conf = data[4 * numPreds + i];
        if (conf > maxConfSeen)
            maxConfSeen = conf;
        if (conf < CONF_THRESHOLD)
            continue;
        raw.push_back({
            data[i],
            data[numPreds + i],
            data[2 * numPreds + i],
            data[3 * numPreds + i],
            conf
        });
    }
    if (raw.empty()) {
        // 未检出：valid=false，但带回最高分——conf 接近阈值(如 0.45)=模型临界抖动；
        // conf 极低(如 0.05)=画面里确实没识别到牌。供 ai_zoom.txt 定性用。
        CardBox miss;
        miss.confidence = maxConfSeen;
        return miss;
    }
    const int numCandidates = static_cast<int>(raw.size());

    std::sort(raw.begin(), raw.end(), [](const Raw &a, const Raw &b) {
        return a.conf > b.conf;
    });

    // 贪心 NMS（IoU > 0.45 抑制）；本场景只需要最高分那个框
    std::vector<char> used(raw.size(), 0);
    const Raw &best = raw[0];
    for (size_t j = 1; j < raw.size(); ++j) {
        const Raw &b = raw[j];
        const float x1 = std::max(best.cx - best.w / 2, b.cx - b.w / 2);
        const float y1 = std::max(best.cy - best.h / 2, b.cy - b.h / 2);
        const float x2 = std::min(best.cx + best.w / 2, b.cx + b.w / 2);
        const float y2 = std::min(best.cy + best.h / 2, b.cy + b.h / 2);
        const float inter = std::max(0.0f, x2 - x1) * std::max(0.0f, y2 - y1);
        const float uni = best.w * best.h + b.w * b.h - inter;
        if (uni > 0 && inter / uni > IOU_THRESHOLD)
            used[j] = 1;
    }

    // 朴素四舍五入，不依赖 libm 的 lround/lroundf（部分 MinGW 工具链缺这些符号）
    auto roundi = [](float v) -> int {
        return static_cast<int>(v >= 0.0f ? v + 0.5f : v - 0.5f);
    };

    CardBox out;
    out.x = roundi(best.cx - best.w / 2);
    out.y = roundi(best.cy - best.h / 2);
    out.w = roundi(best.w);
    out.h = roundi(best.h);
    out.confidence = best.conf;
    out.valid = (out.w > 0 && out.h > 0);
    out.candidates = numCandidates;
    return out;
}
