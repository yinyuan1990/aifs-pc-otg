#include "gstplayer.h"
#include "capturedebuglog.h"
#include <QDebug>
#include <QVideoFrameFormat>
#include <QDir>
#include <QFile>
#include <QTextStream>
#include <QMutex>
#include <QCoreApplication>
#include <QDateTime>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QNetworkRequest>
#include <QTimer>
#include <QUrl>
#include <cmath>

#ifdef Q_OS_WIN
#include <windows.h>
#include <psapi.h>
#endif

// GStreamer WebRTC 相关 (GST_USE_UNSTABLE_API 已在 CMakeLists.txt 中定义)
#include <gst/webrtc/webrtc.h>
#include <gst/sdp/sdp.h>
#include <gst/video/video.h>  // gst_video_event_new_upstream_force_key_unit

// ========== 诊断日志文件（独立输出，便于分析马赛克问题）==========
static QMutex g_diagLogMutex;
static QFile* g_diagLogFile = nullptr;
static QTextStream* g_diagLogStream = nullptr;

// ========== GStreamer 属性安全设置 ==========
static bool setBoolIfExists(GstElement* elem, const char* prop, gboolean value) {
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(elem), prop)) {
        g_object_set(elem, prop, value, nullptr);
        return true;
    }
    return false;
}

static bool setIntIfExists(GstElement* elem, const char* prop, int value) {
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(elem), prop)) {
        g_object_set(elem, prop, value, nullptr);
        return true;
    }
    return false;
}

static bool hasIdrInBuffer(GstBuffer* buf) {
    GstMapInfo map;
    if (!buf || !gst_buffer_map(buf, &map, GST_MAP_READ) || !map.data || map.size < 5) {
        return false;
    }
    const guint8* data = map.data;
    const gsize size = map.size;

    auto isIdrNal = [](guint8 nalHeader) {
        return (nalHeader & 0x1F) == 5; // IDR
    };

    // 1) 尝试 Annex-B（start code）
    for (gsize i = 0; i + 4 < size; ++i) {
        if (data[i] == 0x00 && data[i + 1] == 0x00 &&
            ((data[i + 2] == 0x01) || (data[i + 2] == 0x00 && data[i + 3] == 0x01))) {
            gsize nalIndex = (data[i + 2] == 0x01) ? (i + 3) : (i + 4);
            if (nalIndex < size && isIdrNal(data[nalIndex])) {
                gst_buffer_unmap(buf, &map);
                return true;
            }
            i = nalIndex;
        }
    }

    // 2) 尝试 AVCC（length-prefixed，4字节大端长度）
    gsize pos = 0;
    while (pos + 4 < size) {
        guint32 nalLen = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
        pos += 4;
        if (nalLen == 0 || pos + nalLen > size) break;
        if (isIdrNal(data[pos])) {
            gst_buffer_unmap(buf, &map);
            return true;
        }
        pos += nalLen;
    }

    gst_buffer_unmap(buf, &map);
    return false;
}

static void initDiagLog() {
    if (g_diagLogFile) return;
    
    QMutexLocker locker(&g_diagLogMutex);
    if (g_diagLogFile) return;
    
    QString logPath = QCoreApplication::applicationDirPath() + "/gst_diag.log";
    g_diagLogFile = new QFile(logPath);
    if (g_diagLogFile->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        g_diagLogStream = new QTextStream(g_diagLogFile);
        *g_diagLogStream << "========== GStreamer 诊断日志 ==========" << Qt::endl;
        *g_diagLogStream << "启动时间: " << QDateTime::currentDateTime().toString("yyyy-MM-dd HH:mm:ss") << Qt::endl;
        *g_diagLogStream << "========================================" << Qt::endl << Qt::endl;
        g_diagLogStream->flush();
        qDebug() << "📝 诊断日志文件:" << logPath;
    }
}

static void diagLog(const QString& msg) {
    initDiagLog();
    if (!g_diagLogStream) return;
    
    QMutexLocker locker(&g_diagLogMutex);
    QString timestamp = QDateTime::currentDateTime().toString("HH:mm:ss.zzz");
    *g_diagLogStream << "[" << timestamp << "] " << msg << Qt::endl;
    g_diagLogStream->flush();
}

// ========== 系统信息获取函数 ==========
static long getSystemMemoryGB() {
#ifdef Q_OS_WIN
    MEMORYSTATUSEX memInfo;
    memInfo.dwLength = sizeof(MEMORYSTATUSEX);
    if (GlobalMemoryStatusEx(&memInfo)) {
        long totalMemoryGB = memInfo.ullTotalPhys / (1024 * 1024 * 1024);
        qDebug() << "💾 系统物理内存:" << totalMemoryGB << "GB";
        return totalMemoryGB;
    }
#endif
    qDebug() << "⚠️ 无法获取系统内存，默认按8GB处理";
    return 8; // 默认按低端机处理
}

static int getCore() {
#ifdef Q_OS_WIN
    SYSTEM_INFO sysInfo;
    GetSystemInfo(&sysInfo);
    int cores = sysInfo.dwNumberOfProcessors;
    qDebug() << "💻 CPU 核心数:" << cores;
    return cores;
#endif
    return 4; // 默认值
}

GstPlayer::GstPlayer(QObject *parent)
    : QObject(parent)
    , m_networkManager(new QNetworkAccessManager(this))
{
    qDebug() << "📦 GstPlayer 构造函数 (WebRTCBin 版本)";
    
    // NALU 帧存储（H.264 ring buffer，零格式转换）
    m_naluStore = new NaluFrameStore(NaluFrameStore::DEFAULT_CAPACITY, this);
    
    // ⭐⭐⭐ 创建自适应渲染定时器（应用层 Jitter Buffer 方案）
    m_renderTimer = new QTimer(this);
    m_renderTimer->setTimerType(Qt::PreciseTimer);  // 高精度定时器
    connect(m_renderTimer, &QTimer::timeout, this, &GstPlayer::onRenderTick);
    m_renderTimer->start(33);  // 初始 33ms（目标30fps）
    m_bufferingStarted.store(false);
    
    // 🔥🔥🔥 v11.3 动态队列策略（初始化时损坏率为0）
    int queueMin, queueOptimal, queueMax;
    getQueueSizeByFps(m_configFps, queueMin, queueOptimal, queueMax, 0.0, m_useP2P);
    m_queueTarget = queueOptimal;
    m_queueTargetSmooth = m_queueTarget;
    
    int targetDelayMs = (m_configFps > 0) ? static_cast<int>(queueOptimal * 1000.0 / m_configFps) : 100;
    int totalDelay = targetDelayMs + GST_JITTER_LATENCY;
    
    qDebug().noquote() << QString("⏱️ v11动态队列 | FPS=%1 | 队列=%2帧(范围%3-%4) | 延迟=%5ms+%6ms=%7ms")
        .arg((int)m_configFps).arg(m_queueTarget).arg(queueMin).arg(queueMax)
        .arg(targetDelayMs).arg(GST_JITTER_LATENCY).arg(totalDelay);
}

GstPlayer::~GstPlayer()
{
    stopAutoKeyFrameRequest();  // 停止周期性关键帧请求
    
    // ⭐ 停止渲染定时器
    if (m_renderTimer) {
        m_renderTimer->stop();
    }
    
    // ⭐ 释放队列中所有帧
    {
        QMutexLocker lock(&m_queueMutex);
        for (GstSample *sample : m_frameQueue) {
            gst_sample_unref(sample);
        }
        m_frameQueue.clear();
    }
    
    // ⭐ 释放最后有效帧
    if (m_lastValidSample) {
        gst_sample_unref(m_lastValidSample);
        m_lastValidSample = nullptr;
    }
    
    stop();
    destroyPipeline();
    qDebug() << "📦 GstPlayer 析构完成";
}

void GstPlayer::setVideoSink(QVideoSink *sink)
{
    if (m_videoSink != sink) {
        m_videoSink = sink;
        emit videoSinkChanged();
    }
}

// ========== GPU 类型检测（与 Java 一致）==========
QString GstPlayer::detectGpuType()
{
    // Windows: 通过 WMIC 命令获取显卡信息
#ifdef Q_OS_WIN
    QProcess process;
    process.start("cmd.exe", QStringList() << "/c" << "wmic path win32_VideoController get name");
    process.waitForFinished(3000);
    
    QString output = QString::fromLocal8Bit(process.readAllStandardOutput()).toLower();
    qDebug() << "🖥️ 检测到 GPU:" << output.trimmed();
    
    // 判断 GPU 类型（按优先级）
    if (output.contains("nvidia") || output.contains("geforce") || 
        output.contains("rtx") || output.contains("gtx")) {
        return "NVIDIA";
    } else if (output.contains("amd") || output.contains("radeon") || output.contains("rx ")) {
        return "AMD";
    } else if (output.contains("intel") || output.contains("uhd") || output.contains("iris")) {
        return "Intel";
    }
#endif
    return "Unknown";
}

bool GstPlayer::createPipeline()
{
    QMutexLocker lock(&m_mutex);
    
    if (m_pipeline) {
        qDebug() << "⚠️ Pipeline 已存在，先销毁";
        destroyPipeline();
    }
    
    if (m_useWebRTC) {
        qDebug() << "🔧 创建 GStreamer Pipeline (WebRTCBin 模式)...";
    } else {
        qDebug() << "🔧 创建 GStreamer Pipeline (AppSrc 模式)...";
    }
    
    // 创建 Pipeline
    m_pipeline = gst_pipeline_new("webrtc-player");
    if (!m_pipeline) {
        qCritical() << "❌ 创建 Pipeline 失败";
        emit error("创建 GStreamer Pipeline 失败");
        return false;
    }
    
    // ========== 创建源元素（根据模式选择）==========
    if (m_useWebRTC) {
        // WebRTC 模式：使用 webrtcbin + rtph264depay
        m_webrtcbin = gst_element_factory_make("webrtcbin", "webrtcbin");
        m_rtph264depay = gst_element_factory_make("rtph264depay", "depay");
        
        if (!m_webrtcbin) {
            qCritical() << "❌ webrtcbin 不可用，请检查 GStreamer 插件安装";
            emit error("webrtcbin 不可用，请安装 gst-plugins-bad");
            gst_object_unref(m_pipeline);
            m_pipeline = nullptr;
            return false;
        }
        
        if (!m_rtph264depay) {
            qCritical() << "❌ rtph264depay 不可用";
            emit error("rtph264depay 不可用");
            gst_object_unref(m_pipeline);
            m_pipeline = nullptr;
            return false;
        }
        
        // ⭐⭐⭐ 机型自适应配置（与 Java 完全一致）
        long memoryGB = getSystemMemoryGB();
        int cpuCores = getCore();
        bool isLowEndCPU = cpuCores <= 6;
        
        // ⭐⭐⭐ v9.3双缓冲方案：根据连接模式选择 jitterbuffer 延迟
        // P2P 直连：RTT 低(10-50ms)，用 150ms 即可吸收抖动，延迟更低
        // SRS 中转：经过服务器，网络路径长，保持 600ms 保守缓冲
        int jitterLatencyMs = m_useP2P ? 150 : 600;
        int retryTimeoutMs = m_useP2P ? 15 : 25;
        int dropoutMs = m_useP2P ? 500 : 1200;
        int misorderMs = m_useP2P ? 300 : 800;
        QString machineType;
        
        if (memoryGB <= 8 || isLowEndCPU) {
            machineType = (memoryGB <= 8 && isLowEndCPU) ? "极低端机(≤8GB+≤6核)" :
                          (memoryGB <= 8 ? "低端机(≤8GB)" : "低端CPU(≤6核)");
        } else if (memoryGB < 16) {
            machineType = "中端机(8-16GB)";
        } else if (memoryGB < 32) {
            machineType = "高端机(16-32GB)";
        } else {
            machineType = "超高端机(≥32GB)";
        }
        // 所有机型使用相同的抖动缓冲参数
        
        diagLog(QString("🎯 机型自适应配置 [%1, %2GB内存, %3核]:").arg(machineType).arg(memoryGB).arg(cpuCores));
        diagLog(QString("   - jitter=%1ms, retry=15×%2ms, dropout=%3ms, misorder=%4ms")
            .arg(jitterLatencyMs).arg(retryTimeoutMs).arg(dropoutMs).arg(misorderMs));
        
        // 配置 webrtcbin
        g_object_set(m_webrtcbin,
            "bundle-policy", 3,  // max-bundle
            "latency", jitterLatencyMs,
            nullptr);
        
        // ⭐ 配置 rtph264depay（防马赛克关键！）
        bool setWait = setBoolIfExists(m_rtph264depay, "wait-for-keyframe", TRUE);           // ⭐ 必须！等待关键帧才开始解包
        bool setReq = setBoolIfExists(m_rtph264depay, "request-keyframe", TRUE);             // 启用关键帧请求
        bool setDiscont = setBoolIfExists(m_rtph264depay, "request-keyframe-on-discont", TRUE); // ⚡ 发现不连续时立即请求关键帧
        diagLog(QString("✅ rtph264depay: wait=%1, request=%2, on-discont=%3")
            .arg(setWait).arg(setReq).arg(setDiscont));
        
        // ⭐⭐⭐ 关键：监听 webrtcbin 内部元素添加，配置 jitterbuffer（防马赛克核心！）
        // 使用结构体传递参数给回调
        struct JitterConfig {
            int latency;
            int retryTimeout;
            int dropout;
            int misorder;
        };
        static JitterConfig jitterConfig;
        jitterConfig = {jitterLatencyMs, retryTimeoutMs, dropoutMs, misorderMs};
        
        g_signal_connect(m_webrtcbin, "element-added", G_CALLBACK(+[](GstBin*, GstElement* element, gpointer) {
            const gchar* name = GST_ELEMENT_NAME(element);
            if (name && g_strstr_len(name, -1, "jitterbuffer")) {
                diagLog(QString("🎯 发现 jitterbuffer: %1，配置防马赛克参数...").arg(name));
                
                // ⭐⭐⭐ v10.1 防花屏配置（核心！）
                // 🔥 关键修改：drop-on-latency=FALSE，防止丢弃关键帧导致花屏
                // 延迟控制改为在应用层丢弃 P 帧（不丢 I 帧）
                g_object_set(element,
                    "latency", jitterConfig.latency,   // 100ms（超低延迟）
                    "drop-on-latency", FALSE,          // 🔥🔥🔥 v10.1: 改回FALSE，防止丢I帧花屏！
                    "max-dropout-time", jitterConfig.dropout,
                    "max-misorder-time", jitterConfig.misorder,
                    "do-retransmission", FALSE,        // 禁用重传（延迟太高）
                    "do-lost", TRUE,                   // 🔥 v10.1: 发送丢包事件，触发等待关键帧
                    "mode", 0,                         // none模式（纯透传）
                    "max-rtcp-rtp-time-diff", -1,      // 禁用 RTCP 检查
                    nullptr);
                
                diagLog(QString("✅ v10.1 jitterbuffer: latency=%1ms, drop-on-latency=FALSE(防花屏), do-lost=TRUE")
                    .arg(jitterConfig.latency));
            }
        }), nullptr);
        
        qDebug() << "✅ WebRTCBin 创建成功 [" << machineType << "]";
    } else {
        // 传统模式：使用 appsrc
        m_appsrc = gst_element_factory_make("appsrc", "src");
    }
    
    m_h264parse = gst_element_factory_make("h264parse", "parse");
    
    // ⭐ 配置 h264parse（与 Java 完全一致）
    if (m_h264parse) {
        g_object_set(m_h264parse,
            "config-interval", 1,     // 1 = 每个关键帧前插入SPS/PPS（与Java一致）
            "update-timecode", FALSE, // 不更新时间码，避免时间戳问题
            nullptr);
        
        // ⭐⭐⭐ 关键：与 Java 一致的额外配置（防止绿幕）
        // output-format: 强制输出 byte-stream 格式
        // disable-passthrough: 强制处理每一帧，不跳过（防止档位切换绿幕）
        GstElementFactory *factory = gst_element_get_factory(m_h264parse);
        if (factory) {
            // 检查属性是否存在再设置
            GParamSpec *spec = g_object_class_find_property(G_OBJECT_GET_CLASS(m_h264parse), "disable-passthrough");
            if (spec) {
                g_object_set(m_h264parse, "disable-passthrough", TRUE, nullptr);
                qDebug() << "✅ h264parse: disable-passthrough=true（防止绿幕）";
            }
        }
        bool setOutputFormat = setIntIfExists(m_h264parse, "output-format", 1); // 1=byte-stream
        if (setOutputFormat) {
            qDebug() << "✅ h264parse: output-format=byte-stream";
        }
        qDebug() << "✅ h264parse: config-interval=1（关键帧前插入SPS/PPS）";
    }

    // NALU 存储分支（tee + leaky queue + appsink，不阻塞直播主路径）
    m_naluTee = gst_element_factory_make("tee", "nalu_tee");
    m_naluQueue = gst_element_factory_make("queue", "nalu_store_queue");
    m_naluAppsink = gst_element_factory_make("appsink", "nalu_store_sink");
    if (m_naluQueue) {
        g_object_set(m_naluQueue,
            "max-size-buffers", 5,
            "max-size-bytes", 0,
            "max-size-time", 0,
            "leaky", 2,   // downstream leaky：存储慢则丢旧帧
            "silent", TRUE,
            nullptr);
    }
    if (m_naluAppsink) {
        g_object_set(m_naluAppsink,
            "emit-signals", TRUE,
            "sync", FALSE,
            "async", FALSE,
            "max-buffers", 2,
            "drop", TRUE,
            nullptr);
        GstCaps *naluCaps = gst_caps_from_string(
            "video/x-h264, stream-format=(string)byte-stream, alignment=(string)au");
        gst_app_sink_set_caps(GST_APP_SINK(m_naluAppsink), naluCaps);
        gst_caps_unref(naluCaps);
        g_signal_connect(m_naluAppsink, "new-sample", G_CALLBACK(onNaluStoreSample), this);
    }
    if (m_naluTee && m_naluQueue && m_naluAppsink) {
        qDebug() << "✅ NALU tee branch: nalu_queue(leaky=2) → nalu_appsink";
        captureDebugLog("GST", "NALU tee branch created (leaky queue + async appsink)");
    }

    // H.264 Intra-only 重编码（tee存储分支：解码→重编码为全IDR）
    m_useIntraEncode = false;
    m_storeDecoder = gst_element_factory_make("avdec_h264", "store_dec");
    m_storeConvert = gst_element_factory_make("videoconvert", "store_convert");
    m_storeEncoder = gst_element_factory_make("mfh264enc", "store_enc");
    if (m_storeEncoder) {
        qDebug() << "✅ mfh264enc available for intra-only encode";
        captureDebugLog("GST", "mfh264enc created OK (Media Foundation → GPU)");
        setIntIfExists(m_storeEncoder, "gop-size", 1);
        setIntIfExists(m_storeEncoder, "bframes", 0);
        setIntIfExists(m_storeEncoder, "b-frames", 0);
        setIntIfExists(m_storeEncoder, "ref", 0);
        setBoolIfExists(m_storeEncoder, "low-latency", TRUE);
        setIntIfExists(m_storeEncoder, "min-qp", 17);
        setIntIfExists(m_storeEncoder, "max-qp", 17);
        setIntIfExists(m_storeEncoder, "qp", 17);
    } else {
        qDebug() << "❌ mfh264enc not available, intra-only encode disabled";
        captureDebugLog("GST", "FAIL: mfh264enc not available");
    }
    m_storeParse = gst_element_factory_make("h264parse", "store_parse");
    if (m_storeParse) {
        g_object_set(m_storeParse, "config-interval", (gint)-1, nullptr);
        setIntIfExists(m_storeParse, "output-format", 1);
    }
    bool storeElementsOk = m_storeDecoder && m_storeConvert && m_storeEncoder && m_storeParse;
    if (storeElementsOk) {
        m_useIntraEncode = true;
        qDebug() << "✅ H.264 intra-only storage encoder pipeline ready: mfh264enc";
        captureDebugLog("GST", "H.264 intra-only store encoder: avdec_h264 → videoconvert → mfh264enc(gop=1) → h264parse");
    } else {
        qDebug() << "❌ Intra-only encode not available, store branch disabled";
        captureDebugLog("GST", "FAIL: intra-only encode elements missing, store branch disabled");
        if (m_storeDecoder) { gst_object_unref(m_storeDecoder); m_storeDecoder = nullptr; }
        if (m_storeConvert) { gst_object_unref(m_storeConvert); m_storeConvert = nullptr; }
        if (m_storeEncoder) { gst_object_unref(m_storeEncoder); m_storeEncoder = nullptr; }
        if (m_storeParse) { gst_object_unref(m_storeParse); m_storeParse = nullptr; }
    }

    // ⭐⭐⭐ v10超低延迟：小队列（配合 QUEUE_ABSOLUTE_MAX）
    // 方案B（平衡）：5帧缓冲，兼顾低延迟和平滑
    m_queueDepay = gst_element_factory_make("queue", "queue_depay");
    if (m_queueDepay) {
        // 🔥 v10: 队列大小与 QUEUE_ABSOLUTE_MAX 保持一致
        g_object_set(m_queueDepay,
            "max-size-buffers", QUEUE_ABSOLUTE_MAX, // 🔥 v10: 与应用层队列一致
            "max-size-bytes", 0,              // 不限制字节
            "max-size-time", (guint64)200000000, // 🔥 200ms（纳秒），平衡延迟和平滑
            "leaky", 2,                       // 丢弃老帧，保持最新帧
            "silent", FALSE,                  // 开启日志
            "flush-on-eos", TRUE,
            nullptr);
        
        // ⭐ 监听 overrun 信号（队列满时触发）
        g_signal_connect(m_queueDepay, "overrun", G_CALLBACK(+[](GstElement*, gpointer) {
            diagLog("⚠️ queueDepay OVERRUN - 队列满，可能丢帧！");
        }), nullptr);
        g_signal_connect(m_queueDepay, "underrun", G_CALLBACK(+[](GstElement*, gpointer) {
            diagLog("⚠️ queueDepay UNDERRUN - 队列空，数据不足！");
        }), nullptr);
        
        qDebug() << "⭐ v10 queueDepay: buffers=" << QUEUE_ABSOLUTE_MAX << ", time=200ms, leaky=2（平衡方案）";
        diagLog(QString("✅ v10 queueDepay: buffers=%1, time=200ms, leaky=2（平衡方案）").arg(QUEUE_ABSOLUTE_MAX));
    }
    
    // ========== 解码器智能选择（与 Java 一致）==========
    // 根据 GPU 类型选择解码器优先级
    m_useHardwareDecoder = false;
    
    // 检测 GPU 类型
    QString gpuType = detectGpuType();
    qDebug() << "🎮 检测到 GPU 类型:" << gpuType;
    
    // 根据 GPU 类型确定解码器优先级（与 Java 一致）
    QStringList decoderPriority;
    if (gpuType == "NVIDIA") {
        decoderPriority << "nvh264dec" << "d3d11h264dec";
        qDebug() << "📋 NVIDIA GPU - 解码器优先级: nvh264dec > d3d11h264dec";
    } else if (gpuType == "AMD") {
        decoderPriority << "d3d11h264dec" << "amfh264dec";
        qDebug() << "📋 AMD GPU - 解码器优先级: d3d11h264dec > amfh264dec";
    } else if (gpuType == "Intel") {
        decoderPriority << "msdkh264dec" << "d3d11h264dec";
        qDebug() << "📋 Intel GPU - 解码器优先级: msdkh264dec > d3d11h264dec";
    } else {
        decoderPriority << "d3d11h264dec" << "nvh264dec" << "msdkh264dec";
        qDebug() << "📋 未知 GPU - 解码器优先级: d3d11h264dec > nvh264dec > msdkh264dec";
    }
    
    // ⭐ 诊断：检查 GStreamer 插件注册表
    GstRegistry *registry = gst_registry_get();
    qDebug() << "📋 检查 GStreamer 插件注册表...";
    diagLog("📋 检查 GStreamer 插件注册表...");
    
    // ⭐ 列出所有已加载的插件
    GList *plugins = gst_registry_get_plugin_list(registry);
    int pluginCount = g_list_length(plugins);
    qDebug() << "📦 已加载插件总数:" << pluginCount;
    diagLog(QString("📦 已加载插件总数: %1").arg(pluginCount));
    
    // 列出前20个插件名称（诊断用）
    int i = 0;
    for (GList *l = plugins; l != nullptr && i < 20; l = l->next, i++) {
        GstPlugin *p = (GstPlugin *)l->data;
        const gchar *name = gst_plugin_get_name(p);
        const gchar *filename = gst_plugin_get_filename(p);
        diagLog(QString("   [%1] %2 -> %3").arg(i).arg(name, filename ? filename : "内置"));
    }
    if (pluginCount > 20) {
        diagLog(QString("   ... 还有 %1 个插件").arg(pluginCount - 20));
    }
    gst_plugin_list_free(plugins);
    
    // 检查关键插件是否已注册
    QStringList checkPlugins = {"libav", "nvcodec", "d3d11"};
    for (const QString &pluginName : checkPlugins) {
        GstPlugin *plugin = gst_registry_find_plugin(registry, pluginName.toUtf8().constData());
        if (plugin) {
            const gchar *filename = gst_plugin_get_filename(plugin);
            qDebug() << "   ✅ 插件" << pluginName << "已注册:" << (filename ? filename : "内置");
            diagLog(QString("   ✅ 插件 %1 已注册: %2").arg(pluginName, filename ? filename : "内置"));
            gst_object_unref(plugin);
        } else {
            qDebug() << "   ❌ 插件" << pluginName << "未注册！";
            diagLog(QString("   ❌ 插件 %1 未注册！").arg(pluginName));
        }
    }
    
    // 检查关键 element factory 是否存在
    QStringList checkElements = {"avdec_h264", "nvh264dec", "d3d11h264dec"};
    for (const QString &elementName : checkElements) {
        GstElementFactory *factory = gst_element_factory_find(elementName.toUtf8().constData());
        if (factory) {
            qDebug() << "   ✅ Element" << elementName << "可用";
            diagLog(QString("   ✅ Element %1 可用").arg(elementName));
            gst_object_unref(factory);
        } else {
            qDebug() << "   ❌ Element" << elementName << "不可用！";
            diagLog(QString("   ❌ Element %1 不可用！").arg(elementName));
        }
    }
    
    // 按优先级尝试硬件解码器
    for (const QString &decoderName : decoderPriority) {
        qDebug() << "   🔍 尝试:" << decoderName << "...";
        m_decoder = gst_element_factory_make(decoderName.toUtf8().constData(), "decoder");
        if (m_decoder) {
            m_decoderName = QString("%1 (%2 硬解)").arg(decoderName).arg(gpuType);
            m_useHardwareDecoder = true;
            qDebug() << "   ✅ 成功:" << decoderName;
            
            // ⭐ 防马赛克配置（按属性存在性设置）
            bool setDiscard = setBoolIfExists(m_decoder, "discard-corrupted-frames", TRUE);
            bool setOutput = setBoolIfExists(m_decoder, "output-corrupt", FALSE);
            
            // ⭐⭐⭐ 关键：自动请求同步点（与 Java 一致，防止微卡顿）
            GParamSpec *spec = g_object_class_find_property(G_OBJECT_GET_CLASS(m_decoder), "automatic-request-sync-points");
            if (spec) {
                g_object_set(m_decoder, "automatic-request-sync-points", TRUE, nullptr);
                qDebug() << "✅ 解码器: automatic-request-sync-points=true（自动请求关键帧）";
            }
            
            qDebug() << "✅ 解码器防马赛克配置：丢弃损坏帧"
                     << "(discard=" << setDiscard << "output-corrupt=" << setOutput << ")";
            break;
        } else {
            qDebug() << "   ⚠️ 跳过:" << decoderName << "(不可用)";
        }
    }
    
    // 所有硬解都失败，回退到软解
    if (!m_decoder) {
        qDebug() << "⚠️ 所有硬件解码器不可用，回退到软件解码 avdec_h264...";
        m_decoder = gst_element_factory_make("avdec_h264", "decoder");
        if (m_decoder) {
            m_decoderName = "avdec_h264 (FFmpeg 软解)";
            m_useHardwareDecoder = false;
            qDebug() << "✅ 使用 avdec_h264 软件解码";
            
            // 软解配置（按属性存在性设置）
            bool setSkip = setIntIfExists(m_decoder, "skip-frame", 0);      // 不跳帧
            bool setLowres = setIntIfExists(m_decoder, "lowres", 0);        // 不降低分辨率
            bool setOutput = setBoolIfExists(m_decoder, "output-corrupt", FALSE); // 不输出损坏帧
            qDebug() << "✅ 软解配置"
                     << "(skip=" << setSkip << "lowres=" << setLowres
                     << "output-corrupt=" << setOutput << ")";
            
            // ⭐⭐⭐ 关键：自动请求同步点（与 Java 一致，防止微卡顿）
            GParamSpec *spec = g_object_class_find_property(G_OBJECT_GET_CLASS(m_decoder), "automatic-request-sync-points");
            if (spec) {
                g_object_set(m_decoder, "automatic-request-sync-points", TRUE, nullptr);
                qDebug() << "✅ 软解码器: automatic-request-sync-points=true（自动请求关键帧）";
            }
        }
    }
    
    if (!m_decoder) {
        qCritical() << "❌ 所有解码器都不可用！";
        diagLog("❌ 所有解码器都不可用！检查 GST_PLUGIN_PATH 和插件 DLL 依赖");
        emit error("无可用的 H264 解码器");
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
        return false;
    }
    
    qDebug() << "🎯 最终解码器:" << m_decoderName;
    
    // ⭐⭐⭐ 关键防马赛克队列 2：解码后缓冲（与 Java 一致）
    m_queueDecode = gst_element_factory_make("queue", "queue_decode");
    if (m_queueDecode) {
        // 至少25帧缓冲（与 Java 一致）
        g_object_set(m_queueDecode,
            "max-size-buffers", 25,           // ⭐ 25帧缓冲（平滑解码波动）
            "max-size-bytes", 0,              // 不限制字节
            "max-size-time", 0,               // 不限制时间，只限制帧数
            "leaky", 2,                       // 丢弃老帧，保持最新帧
            "silent", FALSE,                  // ⭐ 开启日志，诊断丢帧
            "flush-on-eos", TRUE,
            nullptr);
        
        // ⭐ 监听 overrun 信号（队列满时触发）
        g_signal_connect(m_queueDecode, "overrun", G_CALLBACK(+[](GstElement*, gpointer) {
            diagLog("⚠️ queueDecode OVERRUN - 解码队列满，可能丢帧！");
        }), nullptr);
        g_signal_connect(m_queueDecode, "underrun", G_CALLBACK(+[](GstElement*, gpointer) {
            diagLog("⚠️ queueDecode UNDERRUN - 解码队列空！");
        }), nullptr);
        
        qDebug() << "⭐ queueDecode: buffers=25, leaky=2（防马赛克关键）";
        diagLog("✅ queueDecode 已创建: buffers=25, leaky=2");
    }
    
    // ⭐ 硬解需要 d3d11download（GPU→CPU），软解不需要
    if (m_useHardwareDecoder) {
        m_download = gst_element_factory_make("d3d11download", "download");
        if (!m_download) {
            qWarning() << "⚠️ d3d11download 不可用，回退到软解...";
            // 释放硬解解码器，重新尝试软解
            gst_object_unref(m_decoder);
            m_decoder = gst_element_factory_make("avdec_h264", "decoder");
            if (!m_decoder) {
                qCritical() << "❌ avdec_h264 也不可用";
                emit error("无可用的 H264 解码器");
                gst_object_unref(m_pipeline);
                m_pipeline = nullptr;
                return false;
            }
            m_decoderName = "avdec_h264 (FFmpeg 软解)";
            m_useHardwareDecoder = false;
            qDebug() << "✅ 回退到 avdec_h264 软件解码";
        } else {
            qDebug() << "✅ d3d11download 创建成功（硬解 GPU→CPU）";
        }
    } else {
        m_download = nullptr;  // 软解不需要 download
        qDebug() << "ℹ️ 软解模式，跳过 d3d11download";
    }
    
    // ========== 创建 videoscale（处理动态分辨率变化，防绿幕）==========
    m_videoScale = gst_element_factory_make("videoscale", "video_scale");
    if (!m_videoScale) {
        qWarning() << "⚠️ 创建 videoscale 元素失败，分辨率变化时可能绿幕";
    } else {
        // 配置 videoscale：使用双线性插值，允许任意分辨率
        g_object_set(m_videoScale,
            "method", 1,  // 1 = bilinear（双线性插值，质量/速度平衡）
            "add-borders", FALSE,  // 不添加黑边
            nullptr);
        qDebug() << "✅ videoscale 创建成功（处理动态分辨率变化）";
    }
    
    // ========== 创建图像调节元素 ==========
    m_videoBalance = gst_element_factory_make("videobalance", "video_balance");
    m_gamma = gst_element_factory_make("gamma", "gamma");
    if (!m_videoBalance || !m_gamma) {
        qCritical() << "❌ 创建 videobalance 或 gamma 元素失败";
        emit error("创建图像调节元素失败");
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
        return false;
    }
    
    // ⭐ 临时禁用 PC 端后期色彩调整 — 用于对比 iOS 原画效果, 代码保留可随时恢复
    //   videobalance / gamma 元素仍在管线中, 但走 GStreamer 默认中性值
    //   (brightness=0, contrast=1.0, saturation=1.0, hue=0, gamma=1.0) → 不做任何颜色处理
    qDebug() << "⚪ [Filter] PC 后期色彩调整已禁用 (videobalance/gamma 走中性默认值)";
    /*
    // 初始化默认值（与 CaptureManager 保持一致）
    g_object_set(m_videoBalance,
        "brightness", -0.02, // -1.0 ~ 1.0（默认 -0.02）
        "contrast", 1.10,    // 0.0 ~ 2.0（默认 1.10）
        "saturation", 1.10,  // 0.0 ~ 2.0（默认 1.10）
        "hue", -0.02,        // -1.0 ~ 1.0（默认 -0.02）
        nullptr);
    g_object_set(m_gamma,
        "gamma", 1.08,       // 0.01 ~ 10.0（默认 1.08）
        nullptr);
    qDebug() << "✅ videobalance 和 gamma 元素已创建并初始化（对比度=1.10, 饱和度=1.10, 伽马=1.08）";
    */
    
    // ========== 显示分支元素 ==========
    m_displayQueue = gst_element_factory_make("queue", "display_queue");
    m_clockSync = nullptr;
    m_convert = gst_element_factory_make("videoconvert", "convert");
    m_appsink = gst_element_factory_make("appsink", "sink");

    // 检查所有元素
    bool srcOk = m_useWebRTC ? (m_webrtcbin && m_rtph264depay) : (m_appsrc != nullptr);
    if (!srcOk || !m_h264parse || !m_naluTee || !m_naluQueue || !m_naluAppsink
        || !m_queueDepay || !m_decoder || !m_queueDecode ||
        !m_displayQueue || !m_convert || !m_appsink ||
        !m_videoBalance || !m_gamma) {
        qCritical() << "❌ 创建 GStreamer 元素失败";
        emit error("创建 GStreamer 元素失败");
        destroyPipeline();
        return false;
    }
    
    // ========== 配置源元素 ==========
    if (m_useWebRTC) {
        // WebRTC 模式：设置 webrtcbin 信号
        setupWebRTCSignals();
    } else {
        // 传统模式：配置 appsrc
        g_object_set(m_appsrc,
            "stream-type", 0,
            "format", GST_FORMAT_TIME,
            "is-live", TRUE,
            "do-timestamp", TRUE,
            nullptr);
        
        GstCaps *srcCaps = gst_caps_new_simple("video/x-h264",
            "stream-format", G_TYPE_STRING, "byte-stream",
            "alignment", G_TYPE_STRING, "au",
            nullptr);
        g_object_set(m_appsrc, "caps", srcCaps, nullptr);
        gst_caps_unref(srcCaps);
    }
    
    // ========== 配置显示分支（根据机型自适应配置）==========
    // ⚡ 优化延迟与抖动容忍度的平衡
    // 根据内存和 CPU 动态调整显示缓冲
    long memoryGB = getSystemMemoryGB();
    int cpuCores = getCore();
    bool isLowEndCPU = cpuCores <= 6;
    
    int displayBuffers;
    if (memoryGB <= 8 || isLowEndCPU) {
        displayBuffers = 5;   // 🔥 低端机：5帧=166ms@30fps（最小稳定缓冲）
        qDebug() << "🔥 低端机配置: 显示缓冲=" << displayBuffers << "帧";
        diagLog(QString("📺 displayQueue: %1 帧（低端机配置）").arg(displayBuffers));
    } else if (memoryGB < 16) {
        displayBuffers = 3;   // 🔧 中端机：3帧=50ms@60fps（与Java一致）
        qDebug() << "🔧 中端机配置: 显示缓冲=" << displayBuffers << "帧";
        diagLog(QString("📺 displayQueue: %1 帧（中端机配置）").arg(displayBuffers));
    } else {
        displayBuffers = 2;   // 🎯 高端机：2帧=33ms@60fps（与Java一致）
        qDebug() << "🎯 高端机配置: 显示缓冲=" << displayBuffers << "帧";
        diagLog(QString("📺 displayQueue: %1 帧（高端机配置）").arg(displayBuffers));
    }
    
    g_object_set(m_displayQueue,
        "max-size-buffers", displayBuffers,
        "max-size-bytes", 0,
        "max-size-time", 0,  // 不限制时间
        "leaky", 2,  // ⭐ 保持leaky=2，防止延迟累积
        "silent", TRUE,
        "flush-on-eos", TRUE,
        nullptr);
    
    // 🔥🔥🔥 v10超低延迟方案（平衡版）：150-300ms延迟，兼顾平滑
    // 核心：小缓冲 + PTS漂移检测 + 定时渲染
    // jitterbuffer(100ms) + 应用层(≤5帧) = 目标延迟 ~200-300ms
    qDebug() << "⭐ v10平衡方案: jitterbuffer(100ms) + appsink(max-buffers=" << QUEUE_ABSOLUTE_MAX << ")";
    diagLog(QString("✅ v10平衡方案: jitterbuffer(100ms) → 解码 → appsink(max-buffers=%1)").arg(QUEUE_ABSOLUTE_MAX));
    
    // 🔥 v10: appsink 缓冲与应用层队列一致
    g_object_set(m_appsink,
        "emit-signals", TRUE,
        "sync", FALSE,            // 不做时间戳同步（在应用层用定时器+PTS校准）
        "async", FALSE,
        "max-buffers", QUEUE_ABSOLUTE_MAX,  // 🔥 v10: 与 QUEUE_ABSOLUTE_MAX 保持一致
        "drop", TRUE,             // 满了丢弃老帧，防止堆积
        nullptr);
    
    qDebug() << "⭐ v10配置: appsink max-buffers=" << QUEUE_ABSOLUTE_MAX << " + PTS漂移检测 + 定时渲染";
    diagLog(QString("✅ v10方案: appsink(max-buffers=%1) + jitterbuffer(100ms) + 定时渲染").arg(QUEUE_ABSOLUTE_MAX));
    
    GstCaps *sinkCaps = gst_caps_new_simple("video/x-raw",
        "format", G_TYPE_STRING, "BGRA",
        "colorimetry", G_TYPE_STRING, "bt709",
        nullptr);
    gst_app_sink_set_caps(GST_APP_SINK(m_appsink), sinkCaps);
    gst_caps_unref(sinkCaps);
    qDebug() << "✅ appsink caps: BGRA + bt709 (修复发黄: 强制 BT.709 矩阵 + full-range 输出)";
    
    g_signal_connect(m_appsink, "new-sample", G_CALLBACK(onNewSample), this);
    
    // ========== Bus Sync Handler ==========
    GstBus *bus = gst_element_get_bus(m_pipeline);
    gst_bus_set_sync_handler(bus, onBusSyncMessage, this, nullptr);
    gst_object_unref(bus);
    
    // ========== 添加所有元素到 Pipeline ==========
    if (m_useWebRTC) {
        // WebRTC 模式（配合 200ms + videorate 方案）
        if (m_useHardwareDecoder && m_download) {
            // 硬解：webrtcbin → rtph264depay → h264parse → queueDepay → decoder → queueDecode → download → videoscale → ...
            gst_bin_add_many(GST_BIN(m_pipeline),
                m_webrtcbin, m_rtph264depay, m_h264parse, m_naluTee, m_naluQueue, m_naluAppsink,
                m_queueDepay, m_decoder, m_queueDecode,
                m_download, m_videoScale, m_videoBalance, m_gamma,
                m_displayQueue, m_convert, m_appsink,
                nullptr);
            if (m_useIntraEncode) {
                gst_bin_add_many(GST_BIN(m_pipeline),
                    m_storeDecoder, m_storeConvert,
                    m_storeEncoder, m_storeParse, nullptr);
            }

            if (!gst_element_link(m_rtph264depay, m_h264parse) || !linkNaluTeeBranch()
                || !gst_element_link_many(m_queueDepay, m_decoder, m_queueDecode,
                                       m_download, m_videoScale, m_videoBalance, m_gamma,
                                       m_displayQueue, m_convert, m_appsink, nullptr)) {
                qCritical() << "❌ 链接主路径失败 (WebRTC 硬解模式)";
                emit error("链接主路径失败");
                destroyPipeline();
                return false;
            }
            qDebug() << "✅ WebRTC 硬解：depay→parse→tee(main→decode, store→appsink)";
        } else {
            // 软解：webrtcbin → rtph264depay → h264parse → queueDepay → decoder → queueDecode → videoscale → videoBalance → ...
            gst_bin_add_many(GST_BIN(m_pipeline),
                m_webrtcbin, m_rtph264depay, m_h264parse, m_naluTee, m_naluQueue, m_naluAppsink,
                m_queueDepay, m_decoder, m_queueDecode,
                m_videoScale, m_videoBalance, m_gamma,
                m_displayQueue, m_convert, m_appsink,
                nullptr);
            if (m_useIntraEncode) {
                gst_bin_add_many(GST_BIN(m_pipeline),
                    m_storeDecoder, m_storeConvert,
                    m_storeEncoder, m_storeParse, nullptr);
            }

            if (!gst_element_link(m_rtph264depay, m_h264parse) || !linkNaluTeeBranch()
                || !gst_element_link_many(m_queueDepay, m_decoder, m_queueDecode,
                                       m_videoScale, m_videoBalance, m_gamma,
                                       m_displayQueue, m_convert, m_appsink, nullptr)) {
                qCritical() << "❌ 链接主路径失败 (WebRTC 软解模式)";
                emit error("链接主路径失败");
                destroyPipeline();
                return false;
            }
            qDebug() << "✅ WebRTC 软解：depay→parse→tee(main→decode, store→appsink)";
        }
        
        // 🔥🔥🔥 v9.3双缓冲：简化probe，只做统计，不丢帧（对齐copygstream）
        // copygstream 版本不做帧丢弃，依赖解码器自身处理
        GstPad *depaySrcPad = gst_element_get_static_pad(m_rtph264depay, "src");
        if (depaySrcPad) {
            m_depayProbeId = gst_pad_add_probe(depaySrcPad, GST_PAD_PROBE_TYPE_BUFFER,
                [](GstPad*, GstPadProbeInfo* info, gpointer userData) -> GstPadProbeReturn {
                    GstPlayer* self = static_cast<GstPlayer*>(userData);
                    GstBuffer* buffer = GST_PAD_PROBE_INFO_BUFFER(info);
                    if (!buffer) return GST_PAD_PROBE_OK;
                    
                    // 统计（始终执行）
                    self->m_totalFrameCount.fetch_add(1);
                    self->m_currentSecondFrames++;  // 帧到达计数
                    
                    // 检测 IDR 帧（仅用于统计）
                    bool isIdr = hasIdrInBuffer(buffer);
                    if (isIdr) {
                        self->m_preDecodeIdr.store(true);
                    }
                    
                    // 🔥 v9.3: 所有帧都通过，不丢弃任何帧
                    return GST_PAD_PROBE_OK;
                    
                }, this, nullptr);
            gst_object_unref(depaySrcPad);
            qDebug() << "✅ v9.3 简化probe（只统计不丢帧）";
        }

        captureDebugLog("GST", "WebRTC pipeline linked with NALU tee branch (no sync probe on live path)");
        
        diagLog(QString("✅ 管道已创建, 解码器: %1").arg(m_decoderName));
    } else {
        // 传统模式（AppSrc，配合 200ms + videorate 方案）
        if (m_useHardwareDecoder && m_download) {
            gst_bin_add_many(GST_BIN(m_pipeline),
                m_appsrc, m_h264parse, m_naluTee, m_naluQueue, m_naluAppsink,
                m_queueDepay, m_decoder, m_queueDecode,
                m_download, m_videoScale, m_videoBalance, m_gamma,
                m_displayQueue, m_convert, m_appsink,
                nullptr);
            if (m_useIntraEncode) {
                gst_bin_add_many(GST_BIN(m_pipeline),
                    m_storeDecoder, m_storeConvert,
                    m_storeEncoder, m_storeParse, nullptr);
            }

            if (!gst_element_link(m_appsrc, m_h264parse) || !linkNaluTeeBranch()
                || !gst_element_link_many(m_queueDepay, m_decoder, m_queueDecode,
                                       m_download, m_videoScale, m_videoBalance, m_gamma,
                                       m_displayQueue, m_convert, m_appsink, nullptr)) {
                qCritical() << "❌ 链接主路径失败 (AppSrc 硬解模式)";
                emit error("链接主路径失败");
                destroyPipeline();
                return false;
            }
            qDebug() << "✅ AppSrc 硬解：src→parse→tee(main→decode, store→appsink)";
        } else {
            gst_bin_add_many(GST_BIN(m_pipeline),
                m_appsrc, m_h264parse, m_naluTee, m_naluQueue, m_naluAppsink,
                m_queueDepay, m_decoder, m_queueDecode,
                m_videoScale, m_videoBalance, m_gamma,
                m_displayQueue, m_convert, m_appsink,
                nullptr);
            if (m_useIntraEncode) {
                gst_bin_add_many(GST_BIN(m_pipeline),
                    m_storeDecoder, m_storeConvert,
                    m_storeEncoder, m_storeParse, nullptr);
            }

            if (!gst_element_link(m_appsrc, m_h264parse) || !linkNaluTeeBranch()
                || !gst_element_link_many(m_queueDepay, m_decoder, m_queueDecode,
                                       m_videoScale, m_videoBalance, m_gamma,
                                       m_displayQueue, m_convert, m_appsink, nullptr)) {
                qCritical() << "❌ 链接主路径失败 (AppSrc 软解模式)";
                emit error("链接主路径失败");
                destroyPipeline();
                return false;
            }
            qDebug() << "✅ AppSrc 软解：src→parse→tee(main→decode, store→appsink)";
        }
        captureDebugLog("GST", "AppSrc pipeline linked with NALU tee branch");
    }

    qDebug() << "✅ GStreamer Pipeline 创建成功，解码器:" << m_decoderName;
    emit decoderChanged();
    return true;
}

QImage GstPlayer::grabCurrentFrame()
{
    if (!m_lastValidSample) return QImage();

    GstBuffer *buffer = gst_sample_get_buffer(m_lastValidSample);
    GstCaps *caps = gst_sample_get_caps(m_lastValidSample);
    if (!buffer || !caps) return QImage();

    GstStructure *s = gst_caps_get_structure(caps, 0);
    int w = 0, h = 0;
    gst_structure_get_int(s, "width", &w);
    gst_structure_get_int(s, "height", &h);
    if (w <= 0 || h <= 0) return QImage();

    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) return QImage();

    QImage img(w, h, QImage::Format_ARGB32);
    int srcStride = w * 4;
    int dstStride = img.bytesPerLine();
    if (srcStride == dstStride) {
        memcpy(img.bits(), map.data, qMin(map.size, (gsize)(h * dstStride)));
    } else {
        for (int y = 0; y < h; y++) {
            memcpy(img.bits() + y * dstStride, map.data + y * srcStride, srcStride);
        }
    }
    gst_buffer_unmap(buffer, &map);
    return img;
}

void GstPlayer::destroyPipeline()
{
    QMutexLocker lock(&m_mutex);
    
    // 🔥 v10.4: 移除解码前 probe
    if (m_depayProbeId != 0 && m_rtph264depay) {
        GstPad *depaySrcPad = gst_element_get_static_pad(m_rtph264depay, "src");
        if (depaySrcPad) {
            gst_pad_remove_probe(depaySrcPad, m_depayProbeId);
            gst_object_unref(depaySrcPad);
        }
        m_depayProbeId = 0;
    }

    if (m_naluTee && m_naluTeePadMain) {
        gst_element_release_request_pad(m_naluTee, m_naluTeePadMain);
        gst_object_unref(m_naluTeePadMain);
        m_naluTeePadMain = nullptr;
    }
    if (m_naluTee && m_naluTeePadStore) {
        gst_element_release_request_pad(m_naluTee, m_naluTeePadStore);
        gst_object_unref(m_naluTeePadStore);
        m_naluTeePadStore = nullptr;
    }
    
    if (m_pipeline) {
        gst_element_set_state(m_pipeline, GST_STATE_NULL);
        gst_object_unref(m_pipeline);
        m_pipeline = nullptr;
    }
    
    // 元素已经被 Pipeline 管理，不需要单独释放
    m_appsrc = nullptr;
    m_webrtcbin = nullptr;     // ⭐ WebRTC 元素
    m_rtph264depay = nullptr;  // ⭐ WebRTC 元素
    m_h264parse = nullptr;
    m_naluTee = nullptr;
    m_naluQueue = nullptr;
    m_naluAppsink = nullptr;
    m_storeDecoder = nullptr;
    m_storeConvert = nullptr;
    m_storeEncoder = nullptr;
    m_storeParse = nullptr;
    m_useIntraEncode = false;
    m_queueDepay = nullptr;    // ⭐ 解码前缓冲队列
    m_decoder = nullptr;
    m_queueDecode = nullptr;   // ⭐ 解码后缓冲队列
    m_download = nullptr;
    m_videoScale = nullptr;    // ⭐ 动态分辨率处理
    m_videoBalance = nullptr;
    m_gamma = nullptr;
    m_displayQueue = nullptr;
    m_clockSync = nullptr;
    m_convert = nullptr;
    m_appsink = nullptr;
    
    // ⭐ 重置 transceiver 标志（下次连接需要重新添加）
    m_transceiverAdded = false;
    
    // 重置 AVCC→Annex-B 状态（下次连接重新提取 SPS/PPS）
    m_spsPpsAnnexB.clear();
    m_nalLengthSize = 4;

    // 🔥 v10.3: 重置防花屏状态
    m_waitingForKeyframe.store(false);
    m_preDecodeDiscont.store(false);
    m_preDecodeIdr.store(false);
    m_consecutiveGoodFrames.store(0);
    m_pliRequestCount = 0;
    
    m_videoWidth = 0;
    m_videoHeight = 0;
    m_frameIndex = 0;
    m_firstFrame = false;
}

void GstPlayer::start()
{
    qDebug() << "🚀 GstPlayer::start() 调用, m_playing=" << m_playing;
    
    if (m_playing) {
        qDebug() << "⚠️ GstPlayer 已在播放中";
        return;
    }
    
    if (!m_pipeline && !createPipeline()) {
        qCritical() << "❌ GstPlayer::start() createPipeline 失败";
        return;
    }
    
    qDebug() << "▶️ GstPlayer 开始播放, Pipeline 已创建";
    
    GstStateChangeReturn ret = gst_element_set_state(m_pipeline, GST_STATE_PLAYING);
    qDebug() << "▶️ GstPlayer set_state 返回:" << ret 
             << "(0=FAILURE, 1=SUCCESS, 2=ASYNC, 3=NO_PREROLL)";
    
    if (ret == GST_STATE_CHANGE_FAILURE) {
        qCritical() << "❌ Pipeline 启动失败";
        emit error("GStreamer Pipeline 启动失败");
        return;
    }
    
    m_playing = true;
    qDebug() << "✅ GstPlayer 已启动, m_playing=true";
    emit playingChanged();
}

void GstPlayer::stop()
{
    if (!m_playing) {
        return;
    }
    
    qDebug() << "⏹️ GstPlayer 停止播放";
    
    if (m_pipeline) {
        gst_element_set_state(m_pipeline, GST_STATE_NULL);
    }
    
    m_playing = false;
    m_firstFrame = false;
    
    // 🔥 v10: 重置 PTS 基准
    m_startPts = -1;
    m_startSystemTime = 0;
    
    // 🔥 v10.1: 重置等待关键帧状态
    m_waitingForKeyframe.store(false);
    m_lastKeyframeRequestMs = 0;
    m_pliRequestCount = 0;
    
    // 🔥🔥🔥 v11.2: 清除最后有效帧 + 显示黑屏
    if (m_lastValidSample) {
        gst_sample_unref(m_lastValidSample);
        m_lastValidSample = nullptr;
    }
    // 清空帧队列
    {
        QMutexLocker lock(&m_queueMutex);
        while (!m_frameQueue.isEmpty()) {
            GstSample *s = m_frameQueue.takeFirst();
            gst_sample_unref(s);
        }
    }
    // 显示黑屏
    if (m_videoSink) {
        QVideoFrame emptyFrame;
        m_videoSink->setVideoFrame(emptyFrame);
    }
    
    // 🔥🔥🔥 v14: 重置缓冲状态（修复重连时帧积压问题）
    // 问题：之前重连时 m_bufferingStarted 保持 true，导致跳过"首帧即播"逻辑
    m_bufferingStarted.store(false);
    m_renderFrameCounter.store(0);
    m_emergencyHold = false;
    m_emptyQueueCount = 0;
    m_corruptRatioEma = 0.0;
    m_intervalEma = 33.0;  // 重置为 30fps 默认值
    m_arrivalRateEma = 30.0;  // 重置为默认值
    m_playbackRate = 1.0;  // 重置播放速度
    m_currentSecondFrames = 0;
    m_lastSecondFps = 0;
    m_queueTarget = 9;  // 🔥 v9.3双缓冲：重置为30fps×300ms=9帧
    
    // ⭐ 重置 FPS 统计
    m_fpsFrameCounter = 0;
    m_fpsEma = 0.0;
    m_fpsEmaInitialized = false;
    if (m_receiveFps.load() != 0) {
        m_receiveFps = 0;
        emit receiveFpsChanged();
    }
    
    emit playingChanged();
}

void GstPlayer::reset()
{
    qDebug() << "🔄 GstPlayer 重置";
    stop();
    destroyPipeline();
    
    // ⭐ 释放最后有效帧
    if (m_lastValidSample) {
        gst_sample_unref(m_lastValidSample);
        m_lastValidSample = nullptr;
    }
    m_emergencyHold = false;
    
    m_frameIndex = 0;
}

void GstPlayer::pushNalu(const QByteArray &nalu)
{
    if (!m_playing || !m_appsrc || nalu.isEmpty()) {
        return;
    }
    
    // 创建 GstBuffer
    GstBuffer *buffer = gst_buffer_new_allocate(nullptr, nalu.size(), nullptr);
    if (!buffer) {
        qWarning() << "⚠️ 分配 GstBuffer 失败";
        return;
    }
    
    // 复制数据
    GstMapInfo map;
    if (gst_buffer_map(buffer, &map, GST_MAP_WRITE)) {
        memcpy(map.data, nalu.constData(), nalu.size());
        gst_buffer_unmap(buffer, &map);
    }
    
    // 推送到 appsrc
    GstFlowReturn ret = gst_app_src_push_buffer(GST_APP_SRC(m_appsrc), buffer);
    if (ret != GST_FLOW_OK) {
        qWarning() << "⚠️ 推送数据到 appsrc 失败:" << ret;
    }
}

static bool hasAnnexBNalType(const guint8 *raw, int rawSize, quint8 nalType)
{
    for (int i = 0; i + 4 < rawSize; ++i) {
        if (raw[i] == 0 && raw[i + 1] == 0 &&
            ((raw[i + 2] == 0 && raw[i + 3] == 1) || raw[i + 2] == 1)) {
            const int nalIndex = (raw[i + 2] == 1) ? (i + 3) : (i + 4);
            if (nalIndex < rawSize && (raw[nalIndex] & 0x1F) == nalType) {
                return true;
            }
        }
    }
    return false;
}

static QByteArray extractSpsPpsFromAnnexB(const guint8 *raw, int rawSize)
{
    static const char sc[4] = {0, 0, 0, 1};
    QByteArray result;
    for (int i = 0; i + 5 <= rawSize; ++i) {
        if (raw[i] == 0 && raw[i + 1] == 0 && raw[i + 2] == 0 && raw[i + 3] == 1) {
            const quint8 nalType = raw[i + 4] & 0x1F;
            if (nalType == 7 || nalType == 8) {
                int j = i + 4;
                while (j + 4 <= rawSize) {
                    if (raw[j] == 0 && raw[j + 1] == 0 && raw[j + 2] == 0 && raw[j + 3] == 1) {
                        break;
                    }
                    ++j;
                }
                result.append(sc, 4);
                result.append(reinterpret_cast<const char*>(raw + i + 4), j - (i + 4));
            }
        }
    }
    return result;
}

bool GstPlayer::linkNaluTeeBranch()
{
    if (!m_h264parse || !m_naluTee || !m_naluQueue || !m_naluAppsink || !m_queueDepay) {
        captureDebugLog("GST", "linkNaluTeeBranch FAIL missing elements");
        return false;
    }

    if (!gst_element_link(m_h264parse, m_naluTee)) {
        captureDebugLog("GST", "linkNaluTeeBranch FAIL h264parse->tee");
        return false;
    }

    m_naluTeePadMain = gst_element_request_pad_simple(m_naluTee, "src_%u");
    GstPad *depaySink = gst_element_get_static_pad(m_queueDepay, "sink");
    if (!m_naluTeePadMain || !depaySink
        || gst_pad_link(m_naluTeePadMain, depaySink) != GST_PAD_LINK_OK) {
        captureDebugLog("GST", "linkNaluTeeBranch FAIL tee->queueDepay");
        if (depaySink) gst_object_unref(depaySink);
        return false;
    }
    gst_object_unref(depaySink);

    if (!m_useIntraEncode) {
        captureDebugLog("GST", "linkNaluTeeBranch SKIP: intra-encode not available, store branch disabled");
        return true;
    }

    // naluQueue → avdec_h264 → videoconvert → mfh264enc → h264parse → appsink
    if (!gst_element_link_many(m_naluQueue, m_storeDecoder, m_storeConvert,
                                m_storeEncoder, m_storeParse,
                                m_naluAppsink, nullptr)) {
        captureDebugLog("GST", "linkNaluTeeBranch FAIL: intra-encode link failed, store branch disabled");
        m_useIntraEncode = false;
        return true;
    }

    m_naluTeePadStore = gst_element_request_pad_simple(m_naluTee, "src_%u");
    GstPad *naluQueueSink = gst_element_get_static_pad(m_naluQueue, "sink");
    if (!m_naluTeePadStore || !naluQueueSink
        || gst_pad_link(m_naluTeePadStore, naluQueueSink) != GST_PAD_LINK_OK) {
        captureDebugLog("GST", "linkNaluTeeBranch FAIL tee->naluQueue, store branch disabled");
        if (naluQueueSink) gst_object_unref(naluQueueSink);
        m_useIntraEncode = false;
        return true;
    }
    gst_object_unref(naluQueueSink);

    captureDebugLog("GST", "linkNaluTeeBranch OK: intra-encode active (mfh264enc)");
    return true;
}

void GstPlayer::extractSpsPpsFromCaps()
{
    if (!m_h264parse || !m_spsPpsAnnexB.isEmpty()) {
        return;
    }

    GstPad *pad = gst_element_get_static_pad(m_h264parse, "src");
    if (!pad) return;

    GstCaps *caps = gst_pad_get_current_caps(pad);
    gst_object_unref(pad);
    if (!caps) return;

    GstStructure *s = gst_caps_get_structure(caps, 0);
    const GValue *cdVal = gst_structure_get_value(s, "codec_data");
    if (!cdVal) {
        gst_caps_unref(caps);
        return;
    }

    GstBuffer *cdBuf = gst_value_get_buffer(cdVal);
    GstMapInfo cdMap;
    if (!gst_buffer_map(cdBuf, &cdMap, GST_MAP_READ)) {
        gst_caps_unref(caps);
        return;
    }

    const guint8 *cd = cdMap.data;
    const int cdSize = static_cast<int>(cdMap.size);
    if (cdSize >= 7) {
        static const char sc[4] = {0, 0, 0, 1};
        m_nalLengthSize = (cd[4] & 0x03) + 1;
        int numSPS = cd[5] & 0x1F;
        int pos = 6;
        QByteArray annexB;
        for (int i = 0; i < numSPS && pos + 2 <= cdSize; i++) {
            int len = (cd[pos] << 8) | cd[pos + 1]; pos += 2;
            if (pos + len <= cdSize) {
                annexB.append(sc, 4);
                annexB.append(reinterpret_cast<const char*>(cd + pos), len);
                pos += len;
            }
        }
        if (pos < cdSize) {
            int numPPS = cd[pos] & 0xFF; pos++;
            for (int i = 0; i < numPPS && pos + 2 <= cdSize; i++) {
                int len = (cd[pos] << 8) | cd[pos + 1]; pos += 2;
                if (pos + len <= cdSize) {
                    annexB.append(sc, 4);
                    annexB.append(reinterpret_cast<const char*>(cd + pos), len);
                    pos += len;
                }
            }
        }
        m_spsPpsAnnexB = annexB;
        captureDebugLog("GST", QString("extractSpsPps OK size=%1 nalLenSize=%2")
            .arg(annexB.size()).arg(m_nalLengthSize));
        qDebug() << "NALU store: 提取 SPS/PPS" << annexB.size() << "bytes";
    }
    gst_buffer_unmap(cdBuf, &cdMap);
    gst_caps_unref(caps);
}

void GstPlayer::storeNaluFromBuffer(GstBuffer *buffer)
{
    if (!buffer || !m_naluStore) {
        return;
    }

    if (m_spsPpsAnnexB.isEmpty()) {
        extractSpsPpsFromCaps();
    }

    GstMapInfo map;
    if (!gst_buffer_map(buffer, &map, GST_MAP_READ)) {
        return;
    }

    const guint8 *raw = map.data;
    const int rawSize = static_cast<int>(map.size);
    const bool isAnnexB = (rawSize >= 4 && raw[0] == 0 && raw[1] == 0 &&
                           ((raw[2] == 0 && raw[3] == 1) || raw[2] == 1));

    if (m_spsPpsAnnexB.isEmpty() && isAnnexB) {
        const QByteArray ps = extractSpsPpsFromAnnexB(raw, rawSize);
        if (!ps.isEmpty()) {
            m_spsPpsAnnexB = ps;
            captureDebugLog("GST", QString("extractSpsPps from Annex-B stream size=%1").arg(ps.size()));
        }
    }

    bool isKeyFrame = !GST_BUFFER_FLAG_IS_SET(buffer, GST_BUFFER_FLAG_DELTA_UNIT);
    if (!isKeyFrame) {
        isKeyFrame = hasIdrInBuffer(buffer);
    }

    QByteArray naluData;
    if (isAnnexB) {
        naluData.reserve(rawSize + m_spsPpsAnnexB.size() + 16);
        if (isKeyFrame && !m_spsPpsAnnexB.isEmpty()
            && !hasAnnexBNalType(raw, rawSize, 7)) {
            naluData.append(m_spsPpsAnnexB);
        }
        naluData.append(reinterpret_cast<const char*>(raw), rawSize);
        if (!isKeyFrame && isAnnexB) {
            isKeyFrame = hasAnnexBNalType(raw, rawSize, 5) || hasAnnexBNalType(raw, rawSize, 7);
        }
    } else {
        static const char sc[4] = {0, 0, 0, 1};
        const int nlSize = m_nalLengthSize;
        naluData.reserve(rawSize + 128);

        if (isKeyFrame && !m_spsPpsAnnexB.isEmpty()) {
            naluData.append(m_spsPpsAnnexB);
        }

        int pos = 0;
        while (pos + nlSize <= rawSize) {
            quint32 nalLen = 0;
            for (int i = 0; i < nlSize; i++) {
                nalLen = (nalLen << 8) | raw[pos + i];
            }
            pos += nlSize;
            if (nalLen == 0 || pos + static_cast<int>(nalLen) > rawSize) {
                break;
            }
            if ((raw[pos] & 0x1F) == 5) {
                isKeyFrame = true;
            }
            naluData.append(sc, 4);
            naluData.append(reinterpret_cast<const char*>(raw + pos), static_cast<int>(nalLen));
            pos += static_cast<int>(nalLen);
        }
    }

    const qint64 idx = m_naluFrameIndex.fetch_add(1, std::memory_order_relaxed);
    gst_buffer_unmap(buffer, &map);
    m_naluStore->addFrame(naluData, idx, isKeyFrame);

    static std::atomic<int> s_storeLogCounter{0};
    const int n = s_storeLogCounter.fetch_add(1) + 1;
    if (n <= 3 || (n % 300) == 0) {
        captureDebugLog("GST", QString("storeNalu idx=%1 key=%2 size=%3 annexB=%4")
            .arg(idx).arg(isKeyFrame ? "Y" : "N").arg(naluData.size()).arg(isAnnexB ? "Y" : "N"));
    }
}

GstFlowReturn GstPlayer::onNaluStoreSample(GstAppSink *sink, gpointer userData)
{
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample) {
        return GST_FLOW_OK;
    }

    if (hasIdrInBuffer(gst_sample_get_buffer(sample))) {
        self->m_preDecodeIdr.store(true);
    }

    GstBuffer *buffer = gst_sample_get_buffer(sample);
    if (buffer) {
        self->storeNaluFromBuffer(buffer);
    }

    gst_sample_unref(sample);
    return GST_FLOW_OK;
}

GstFlowReturn GstPlayer::onNewSample(GstAppSink *sink, gpointer userData)
{
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    
    GstSample *sample = gst_app_sink_pull_sample(sink);
    if (!sample) {
        return GST_FLOW_OK;
    }
    
    // 🔥🔥🔥 v12 简化：坏帧已在 probe 中被 DROP，这里收到的帧都是干净的！
    // probe 已处理：
    // 1. 坏帧统计 (m_corruptFrameCount)
    // 2. 帧到达计数 (m_currentSecondFrames)
    // 3. 等待 IDR 逻辑（坏帧 DROP，非 IDR 在等待期间也 DROP）
    // 4. IDR 检测和等待模式退出
    
    GstBuffer *buffer = gst_sample_get_buffer(sample);
    GstCaps *caps = gst_sample_get_caps(sample);
    
    // 首帧和分辨率检测
    if (buffer && caps) {
        GstStructure *structure = gst_caps_get_structure(caps, 0);
        int width = 0, height = 0;
        gst_structure_get_int(structure, "width", &width);
        gst_structure_get_int(structure, "height", &height);
        
        // 更新分辨率
        if (width != self->m_videoWidth || height != self->m_videoHeight) {
            self->m_videoWidth = width;
            self->m_videoHeight = height;
            qDebug() << "🎬 视频分辨率:" << width << "x" << height;
            emit self->videoSizeChanged();
        }
        
        // 首帧通知
        if (!self->m_firstFrame.exchange(true)) {
            qDebug() << "🎬 首帧已接收";
            emit self->firstFrameReceived();
        }
    }
    
    // ⭐⭐⭐ 应用层 Jitter Buffer：将帧放入队列（FIFO）
    // 🔥🔥🔥 v11.3 核心原则：不跳帧！靠追帧速度消耗队列
    // 只在极端异常时（队列 > 2×硬限制 = 60帧 = 1秒@60fps）才强制丢帧
    {
        QMutexLocker lock(&self->m_queueMutex);
        
        // 🔥 v11.3: 只在极端情况（2×限制 = 60帧）才丢帧，正常靠追帧消耗
        int extremeLimit = QUEUE_ABSOLUTE_MAX * 2;  // 60帧
        if (self->m_frameQueue.size() >= extremeLimit) {
            int dropCount = 0;
        while (self->m_frameQueue.size() >= QUEUE_ABSOLUTE_MAX) {
                GstSample *oldest = self->m_frameQueue.takeFirst();
                gst_sample_unref(oldest);
                dropCount++;
            }
            
            qWarning() << "⚠️⚠️⚠️ v11.3 队列异常积压，强制丢弃" << dropCount << "帧！";
            qWarning() << "    原因：队列超过" << extremeLimit << "帧（正常应<" << QUEUE_ABSOLUTE_MAX << "帧）";
            
            // 丢帧后请求关键帧
            QMetaObject::invokeMethod(self, "requestKeyFrame", Qt::QueuedConnection);
        }
        
        // 直接入队（pull_sample 返回的已有引用计数，由队列接管）
        self->m_frameQueue.append(sample);
        
        // ⭐⭐⭐ v8.3 帧间隔抖动检测（预测式降帧）
        qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
        if (self->m_lastFrameArrivalMs > 0) {
            double fps = self->m_configFps > 0 ? self->m_configFps : 30.0;
            double expectedInterval = 1000.0 / fps;  // 期望间隔（如16.7ms@60fps）
            double actualInterval = static_cast<double>(nowMs - self->m_lastFrameArrivalMs);
            double jitter = std::abs(actualInterval - expectedInterval);  // 瞬时抖动
            
            // 抖动EMA：J_ema = α × J + (1-α) × J_ema
            self->m_jitterEma = JITTER_ALPHA * jitter + (1.0 - JITTER_ALPHA) * self->m_jitterEma;
        }
        self->m_lastFrameArrivalMs = nowMs;
    }
    
    // 显示帧计数（JPEG 帧索引在 bus 消息回调中更新）
    self->m_frameIndex++;
    
    // ⭐ 帧率统计：EMA 指数移动平均（极度平滑，避免跳动）
    self->m_fpsFrameCounter.fetch_add(1);
    qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - self->m_fpsLastSecondMs >= 1000) {
        int currentSecondFps = self->m_fpsFrameCounter.exchange(0);
        self->m_fpsLastSecondMs = now;
        
        // EMA 计算：newEma = alpha * current + (1-alpha) * oldEma
        // alpha=0.2 意味着当前值只占 20%，历史值占 80%（非常平滑）
        if (!self->m_fpsEmaInitialized) {
            self->m_fpsEma = currentSecondFps;  // 第一次直接赋值
            self->m_fpsEmaInitialized = true;
        } else {
            self->m_fpsEma = FPS_EMA_ALPHA * currentSecondFps + (1.0 - FPS_EMA_ALPHA) * self->m_fpsEma;
        }
        
        // ⭐⭐⭐ 更新帧到达速率EMA（区分配置变化 vs 网络波动）
        // 
        // 三种情况：
        // 1. iOS固定fps：configFps稳定，只有网络波动 → EMA平滑
        // 2. PC手动改fps：configFps变化 → setConfigFps()已处理
        // 3. 网络波动：实测fps短暂波动 → EMA平滑，不过度反应
        //
        // 检测网络质量：实测fps与配置fps的偏差
        double configFps = self->m_configFps > 1.0 ? self->m_configFps : 30.0;
        double deliveryRatio = currentSecondFps / configFps;  // 实际/配置
        
        // ⭐⭐⭐ 关键修复：FPS突变时立即重置EMA（不等待3秒检测期）
        // 当实测fps与当前EMA差距>50%时，说明FPS发生了突变
        // 此时应立即重置EMA，让播放间隔快速跟随新帧率
        double fpsChangeRatio = (self->m_arrivalRateEma > 1.0) 
            ? currentSecondFps / self->m_arrivalRateEma 
            : 1.0;
        
        if (fpsChangeRatio < 0.5 || fpsChangeRatio > 2.0) {
            // FPS突变（变化超过50%），立即重置EMA
            self->m_arrivalRateEma = currentSecondFps;
            qDebug().noquote() << QString("⚡ FPS突变检测 | EMA立即重置为%1fps (比例=%2)")
                .arg(currentSecondFps).arg(fpsChangeRatio, 0, 'f', 2);
        } else {
            // 正常平滑更新EMA
            self->m_arrivalRateEma = ALPHA_RATE * currentSecondFps + (1.0 - ALPHA_RATE) * self->m_arrivalRateEma;
        }
        
        // 防止EMA过低/过高（最小10fps，保证播放流畅）
        if (self->m_arrivalRateEma < 10.0) self->m_arrivalRateEma = 10.0;  // 最小10fps
        if (self->m_arrivalRateEma > 240.0) self->m_arrivalRateEma = 240.0;
        
        // ⭐⭐⭐ 自动检测fps变化并同步调整（自动唤醒机制）
        // 当实测fps稳定在不同于配置fps的值时，自动调整
        static int stableFpsCount = 0;
        static double lastStableFps = 0;
        
        // 检测实测fps是否稳定（与上一秒差距<20%）
        if (lastStableFps > 0 && std::abs(currentSecondFps - lastStableFps) / lastStableFps < 0.2) {
            stableFpsCount++;
        } else {
            stableFpsCount = 0;
        }
        lastStableFps = currentSecondFps;
        
        // 实测fps稳定3秒，且与配置fps差距>30%，自动调整
        if (stableFpsCount >= 3) {
            double fpsRatio = currentSecondFps / configFps;
            if (fpsRatio < 0.7 || fpsRatio > 1.3) {
                // ⭐⭐⭐ 自动调整配置fps（最小10fps）
                int newConfigFps = qRound(currentSecondFps / 5.0) * 5;
                if (newConfigFps < 10) newConfigFps = 10;  // 最低10fps
                newConfigFps = qBound(10, newConfigFps, 120);  // 最小10fps
                
                qDebug().noquote() << QString("🔄 EMA检测FPS变化 | 配置%1fps 实测%2fps | 新配置=%3fps")
                    .arg((int)configFps).arg(currentSecondFps).arg(newConfigFps);
                
                // 调用 setConfigFps 同步调整所有参数
                self->setConfigFps(newConfigFps);
                stableFpsCount = 0;
            }
        }
        
        // ⭐ 网络质量检测日志
        static int poorNetworkCount = 0;
        if (deliveryRatio < 0.7 && stableFpsCount < 3) {
            // 只有在fps还未稳定时才报警（排除fps变化情况）
            poorNetworkCount++;
            if (poorNetworkCount == 3) {
                qDebug().noquote() << QString("⚠️ 网络质量差 | 配置%1fps 实收%2fps (%3%)")
                    .arg((int)configFps).arg(currentSecondFps).arg((int)(deliveryRatio*100));
            }
        } else {
            poorNetworkCount = 0;
        }
        
        // ⭐ x4 得到真实接收帧率，四舍五入
        int newFps = qRound(self->m_fpsEma * 4);
        
        // ⭐⭐⭐ 统计日志（每秒写入 yh.txt）
        {
            static bool yhHeaderWritten = false;
            
            QString timestamp = QDateTime::currentDateTime().toString("HH:mm:ss.zzz");
            int renderFps = self->m_renderFrameCounter.exchange(0);  // 获取并重置渲染帧计数
            
            // 应用层 Jitter Buffer 队列深度
            int appQueueDepth = 0;
            {
                QMutexLocker lock(&self->m_queueMutex);
                appQueueDepth = self->m_frameQueue.size();
            }
            int currentInterval = self->m_renderTimer ? self->m_renderTimer->interval() : 0;
            
            // 计算总延迟（GStreamer固定100ms + 实际队列深度 × 配置帧间隔）
            // ⭐ 使用配置fps计算延迟（更稳定，不受网络波动影响）
            int gstLatencyMs = GST_JITTER_LATENCY;
            double fps = self->m_configFps > 1.0 ? self->m_configFps : 30.0;
            int appLatencyMs = static_cast<int>(appQueueDepth * (1000.0 / fps));
            int totalLatencyMs = gstLatencyMs + appLatencyMs;
            
            // 计算水位状态
            double waterLevel = (self->m_queueTarget > 0) ? 
                (double)appQueueDepth / self->m_queueTarget * 100.0 : 0.0;
            QString waterStatus;
            if (waterLevel < W_EMERGENCY * 100) {
                waterStatus = "🛑";  // 紧急
            } else if (waterLevel < W_EXPAND * 100) {
                waterStatus = "⚠️";  // 恢复中
            } else if (waterLevel > W_CATCHUP * 100) {
                waterStatus = "🚀";  // 追帧
            } else {
                waterStatus = "✅";  // 正常
            }
            
            // ⭐⭐⭐ v9核心：基于实际到达帧率EMA计算所有指标
            double arrivalEma = qMax(10.0, self->m_arrivalRateEma);  // 到达帧率EMA（核心指标）
            double playbackRate = self->m_playbackRate;
            double intervalEma = self->m_intervalEma;
            // 🔥 v11.3：动态队列（根据帧率+损坏率）
            int qMin, qOptimal, qMax;
            GstPlayer::getQueueSizeByFps(arrivalEma, qMin, qOptimal, qMax, self->m_corruptRatioEma, self->m_useP2P);
            int optimalQueue = qOptimal;
            int appDelayMs = (arrivalEma > 0) ? static_cast<int>(appQueueDepth * 1000.0 / arrivalEma) : 0;
            
            // 播放速度状态
            QString speedIcon = playbackRate > 1.01 ? "🚀" : (playbackRate < 0.99 ? "🐢" : "");
            
            // 状态标识
            QString emergencyStr = self->m_emergencyHold ? "🛑紧急" : "";
            // 🔥 v13：PC端不再控制帧率，移除降帧显示
            QString fpsReqStr = "";
            
            // 主日志：收/渲/队列/间隔/速度
            QString statsMsg = QString("[%1] 收=%2 渲=%3 | 队列=%4/%5帧(%6) 最佳=%7帧 | 间隔=%8ms(EMA=%9) | 速度=%10%%11")
                .arg(timestamp)
                .arg(currentSecondFps)      // 实际接收fps
                .arg(renderFps)             // 渲染fps
                .arg(appQueueDepth)         // 当前队列
                .arg(self->m_queueTarget)   // 目标队列
                .arg(waterStatus)           // 水位状态
                .arg(optimalQueue)          // 最佳队列（基于到达帧率EMA）
                .arg(currentInterval)       // 当前渲染间隔
                .arg((int)intervalEma)      // 间隔EMA
                .arg((int)(playbackRate * 100))  // 播放速度
                .arg(speedIcon);
            
            // 附加信息：到达EMA/配置fps/应用延迟/状态/损坏率
            // 🔥 v13 显示损坏率（仅用于监控网络质量）
            QString corruptIcon = "";
            if (self->m_corruptRatioEma >= CORRUPT_RATIO_CRITICAL) {
                corruptIcon = "🔴";  // >=30% 弱网
            } else if (self->m_corruptRatioEma >= CORRUPT_RATIO_WEAK) {
                corruptIcon = "🟡";  // >=10% 轻度弱网
            } else if (self->m_corruptRatioEma < 0.05) {
                corruptIcon = "🟢";  // <5% 正常
            }
            QString corruptStr = QString(" %1损坏=%2%").arg(corruptIcon).arg((int)(self->m_corruptRatioEma * 100));
            QString extraMsg = QString(" | 配置=%1fps 到达EMA=%2fps 应延=%3ms%4 %5%6")
                .arg((int)self->m_configFps)  // 配置fps（参考）
                .arg((int)arrivalEma)         // 到达帧率EMA（核心）
                .arg(appDelayMs)              // 应用层延迟
                .arg(corruptStr)              // 🔥 损坏帧比例（网络质量）
                .arg(emergencyStr)            // 紧急状态
                .arg(fpsReqStr);              // 降帧状态
            
            // GStreamer 队列统计
            QString queueMsg;
            if (self->m_queueDepay) {
                guint lvl = 0;
                g_object_get(self->m_queueDepay, "current-level-buffers", &lvl, nullptr);
                queueMsg += QString(" | GST=%1").arg(lvl);
            }
            
            // 控制台每秒打印
            qDebug().noquote() << "📊" << statsMsg << extraMsg << queueMsg;
            
            // ⭐⭐⭐ v12.1 每秒写入 sh.txt（包含完整状态）
            QFile shFile("sh.txt");
            if (shFile.open(QIODevice::WriteOnly | QIODevice::Append | QIODevice::Text)) {
                QTextStream ts(&shFile);
                
                // 首次写入添加会话头
                if (!yhHeaderWritten) {
                    ts << "\n";
                    ts << "╔══════════════════════════════════════════════════════════════════════════════╗\n";
                    ts << "║  v12.1 自适应播放日志 - " << QDateTime::currentDateTime().toString("yyyy-MM-dd HH:mm:ss") << "  ║\n";
                    ts << "╠══════════════════════════════════════════════════════════════════════════════╣\n";
                    ts << "║ 字段说明:                                                                     ║\n";
                    ts << "║   收=每秒接收帧数  渲=每秒渲染帧数  速度=播放速度(100%=正常,>100追帧,<100慢放) ║\n";
                    ts << "║   队列=当前帧数/目标帧数  损坏=损坏帧比例(网络质量指标,iOS自适应)             ║\n";
                    ts << "║   抖动=帧间隔抖动(ms)  到达EMA=平滑后的到达帧率                               ║\n";
                    ts << "╚══════════════════════════════════════════════════════════════════════════════╝\n\n";
                    yhHeaderWritten = true;
                }
                
                // 🔥 v12.1 简化格式：关键指标一目了然
                QString shLog = QString("[%1] 收=%2 渲=%3 速度=%4% | 队列=%5/%6 损坏=%7% 抖动=%8ms | 到达EMA=%9fps 配置=%10fps")
                    .arg(timestamp)
                    .arg(currentSecondFps)                              // 接收帧数
                    .arg(renderFps)                                     // 渲染帧数
                    .arg((int)(playbackRate * 100))                     // 播放速度
                    .arg(appQueueDepth)                                 // 队列深度
                    .arg(self->m_queueTarget)                           // 目标队列
                    .arg((int)(self->m_corruptRatioEma * 100))          // 损坏率
                    .arg((int)intervalEma)                              // 抖动
                    .arg((int)arrivalEma)                               // 到达帧率EMA
                    .arg((int)self->m_configFps);                       // 配置帧率
                
                // 状态标识
                QString statusStr;
                if (self->m_emergencyHold) {
                    statusStr += " [紧急保护]";
                }
                // 🔥 v13：PC端不再控制帧率，只显示弱网状态
                if (self->m_corruptRatioEma >= CORRUPT_RATIO_WEAK) {
                    statusStr += " [弱网]";
                }
                
                ts << shLog << statusStr << "\n";
                ts.flush();
                shFile.close();
            }
            
            // 🔥 v14: 同步更新 QML 显示的队列状态（和 sh.txt 日志保持一致）
            if (self->m_bufferSize.load() != appQueueDepth) {
                self->m_bufferSize.store(appQueueDepth);
                emit self->bufferSizeChanged();
            }
            // 🔥 v14: 使用最佳队列作为目标（而不是 m_queueTarget）
            if (self->m_bufferTarget.load() != optimalQueue) {
                self->m_bufferTarget.store(optimalQueue);
                emit self->bufferTargetChanged();
            }
        }
        
        if (newFps != self->m_receiveFps.load()) {
            self->m_receiveFps = newFps;
            emit self->receiveFpsChanged();
        }
    }
    
    return GST_FLOW_OK;
}

// ========== GStreamer Bus 同步消息处理（JPEG 保存成功回调）==========
GstBusSyncReply GstPlayer::onBusSyncMessage(GstBus *bus, GstMessage *message, gpointer userData)
{
    Q_UNUSED(bus);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    
    switch (GST_MESSAGE_TYPE(message)) {
    case GST_MESSAGE_ERROR: {
        GError *err = nullptr;
        gchar *debug = nullptr;
        gst_message_parse_error(message, &err, &debug);
        diagLog(QString("❌ ERROR: %1 | debug: %2").arg(err->message).arg(debug));
        qCritical() << "❌ GStreamer 错误:" << err->message;
        g_error_free(err);
        g_free(debug);
        break;
    }
    case GST_MESSAGE_WARNING: {
        GError *err = nullptr;
        gchar *debug = nullptr;
        gst_message_parse_warning(message, &err, &debug);
        diagLog(QString("⚠️ WARNING: %1 | debug: %2").arg(err->message).arg(debug));
        g_error_free(err);
        g_free(debug);
        break;
    }
    case GST_MESSAGE_QOS: {
        // QoS 消息表示丢帧
        gboolean live = FALSE;
        guint64 running_time = 0, stream_time = 0, timestamp = 0, duration = 0;
        gst_message_parse_qos(message, &live, &running_time, &stream_time, &timestamp, &duration);
        
        gint64 jitter = 0;
        gdouble proportion = 0;
        gint quality = 0;
        gst_message_parse_qos_values(message, &jitter, &proportion, &quality);
        
        guint64 processed = 0, dropped = 0;
        gst_message_parse_qos_stats(message, nullptr, &processed, &dropped);
        
        // 记录所有 QoS 消息
        diagLog(QString("📉 QOS: processed=%1 dropped=%2 jitter=%3 来源=%4")
            .arg(processed).arg(dropped).arg(jitter).arg(GST_MESSAGE_SRC_NAME(message)));
        break;
    }
    case GST_MESSAGE_STREAM_STATUS: {
        GstStreamStatusType type;
        GstElement *owner = nullptr;
        gst_message_parse_stream_status(message, &type, &owner);
        if (type == GST_STREAM_STATUS_TYPE_ENTER || type == GST_STREAM_STATUS_TYPE_LEAVE) {
            diagLog(QString("📺 STREAM_STATUS: type=%1 owner=%2")
                .arg((int)type).arg(owner ? GST_ELEMENT_NAME(owner) : "null"));
        }
        break;
    }
    case GST_MESSAGE_ELEMENT: {
        const GstStructure *s = gst_message_get_structure(message);
        if (!s) break;
        
        const gchar *name = gst_structure_get_name(s);
        
        // ⭐ 检测 H264 解码器/RTP 相关消息
        if (g_str_has_prefix(name, "GstVideoDecoder") || 
            g_str_has_prefix(name, "d3d11") ||
            g_str_has_prefix(name, "h264") ||
            g_str_has_prefix(name, "rtp") ||
            g_str_has_prefix(name, "Rtp")) {
            diagLog(QString("🎬 ELEMENT: %1").arg(name));
        }
        
        break;
    }
    default:
        break;
    }
    
    return GST_BUS_PASS;  // 继续传递消息
}

// ========== 图像调节方法（使用 GStreamer videobalance 和 gamma）==========

void GstPlayer::setBrightness(double value)
{
    value = qBound(-1.0, value, 1.0);
    if (m_videoBalance) {
        g_object_set(m_videoBalance, "brightness", value, nullptr);
        qDebug() << "✅ 亮度已设置:" << value;
    }
}

void GstPlayer::setContrast(double value)
{
    value = qBound(0.0, value, 2.0);
    if (m_videoBalance) {
        g_object_set(m_videoBalance, "contrast", value, nullptr);
        qDebug() << "✅ 对比度已设置:" << value;
    }
}

void GstPlayer::setSaturation(double value)
{
    value = qBound(0.0, value, 2.0);
    if (m_videoBalance) {
        g_object_set(m_videoBalance, "saturation", value, nullptr);
        qDebug() << "✅ 饱和度已设置:" << value;
    }
}

void GstPlayer::setHue(double value)
{
    value = qBound(-1.0, value, 1.0);
    if (m_videoBalance) {
        g_object_set(m_videoBalance, "hue", value, nullptr);
        qDebug() << "✅ 色调已设置:" << value;
    }
}

void GstPlayer::setGamma(double value)
{
    value = qBound(0.01, value, 10.0);
    if (m_gamma) {
        g_object_set(m_gamma, "gamma", value, nullptr);
        qDebug() << "✅ 伽马已设置:" << value;
    }
}

void GstPlayer::setAllImageParams(double brightness, double contrast, double saturation, double hue, double gamma)
{
    // ⭐ 临时禁用 PC 端后期色彩调整 — 用于对比 iOS 原画效果, 代码保留可随时恢复
    //   syncColorToJpegEncoder 启动/参数变化时会调到这里, short-circuit 后
    //   videobalance/gamma 永远停在 GStreamer 中性默认值
    qDebug() << "⚪ [Filter] setAllImageParams 已禁用 (传入值忽略: b=" << brightness
             << "c=" << contrast << "s=" << saturation << "h=" << hue << "g=" << gamma << ")";
    return;
    /*
    setBrightness(brightness);
    setContrast(contrast);
    setSaturation(saturation);
    setHue(hue);
    setGamma(gamma);
    */
}

void GstPlayer::setConfigFps(double fps)
{
    // 🔥🔥🔥 v11 防抖动：限制 FPS 设置频率，避免 UI 卡顿
    static qint64 lastSetTime = 0;
    qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - lastSetTime < 100) {  // 100ms 内只处理一次
        return;  // 忽略过于频繁的调用
    }
    lastSetTime = now;
    
    // ⭐⭐⭐ v8客户方案：FPS变化时重新计算队列目标
    fps = qBound(10.0, fps, 240.0);
    
    if (std::abs(m_configFps - fps) > 1.0) {
        double oldFps = m_configFps;
        int oldQueueTarget = m_queueTarget;
        
        m_configFps = fps;
        
        // 🔥🔥🔥 v11 修复：不要重置 m_arrivalRateEma！
        // arrivalRateEma 应该反映实际到达帧率，而不是配置帧率
        // 重置它会导致定时器间隔变得很短，引起 UI 卡顿
        // m_arrivalRateEma = fps;  // ❌ 删除这行！
        
        // 🔥🔥🔥 v11.3 动态队列策略（手动调整时使用当前损坏率）
        int queueMin, queueOptimal, queueMax;
        getQueueSizeByFps(fps, queueMin, queueOptimal, queueMax, m_corruptRatioEma, m_useP2P);
        int newQueueTarget = queueOptimal;
        
        m_queueTarget = newQueueTarget;
        m_queueTargetSmooth = newQueueTarget;
        
        // ⭐⭐⭐ 重置所有状态（新fps基准）
        m_playbackRate = 1.0;
        m_targetRate = 1.0;
        m_emergencyHold = false;
        m_slowdownActive = false;
        m_stableCounter = 0;
        m_fpsChangeCounter = 0;
        
        // 🔥 v13：PC端不再自动升降帧，这些变量保留但不再使用
        m_requestedFps = 0;
        m_originalFps = 0;
        m_fpsAdjustCooldownMs = 0;
        qDebug() << "🔄 手动调整FPS→" << fps;
        
        int targetDelayMs = 150;  // 标准延迟150ms
        int totalDelayMs = targetDelayMs + GST_JITTER_LATENCY;
        
        qDebug().noquote() << QString("⚙️ v8 FPS变更 | %1→%2fps | 最佳缓冲%3→%4帧(15%) | 范围=%5-%6帧 | 延迟=%7ms+%8ms=%9ms")
            .arg((int)oldFps).arg((int)fps)
            .arg(oldQueueTarget).arg(m_queueTarget)
            .arg(queueMin).arg(queueMax)
            .arg(targetDelayMs).arg(GST_JITTER_LATENCY).arg(totalDelayMs);
    }
}

// ⭐ P2: 240fps 高速模式切换
void GstPlayer::setHighSpeedMode(bool enabled)
{
    if (m_highSpeedMode == enabled) return;
    m_highSpeedMode = enabled;

    if (enabled) {
        // 240fps 模式：调整渲染定时器和缓冲策略
        m_renderTimer->start(4);  // 4ms = 240fps
        setConfigFps(240.0);

        // appsink 不丢帧（240fps 模式下每帧都重要）
        if (m_appsink) {
            g_object_set(m_appsink,
                "drop", FALSE,
                "max-buffers", (guint)0,  // 不限制缓冲
                nullptr);
        }
        qDebug() << "✅ [240fps] 高速模式已启用 (4ms渲染, 不丢帧)";
    } else {
        // 恢复普通模式
        m_renderTimer->start(33);  // 33ms = 30fps
        setConfigFps(30.0);

        // 恢复 appsink 丢帧策略
        if (m_appsink) {
            g_object_set(m_appsink,
                "drop", TRUE,
                "max-buffers", (guint)QUEUE_ABSOLUTE_MAX,
                nullptr);
        }
        qDebug() << "✅ [普通模式] 已恢复 (33ms渲染, drop=true)";
    }
}

// ============================================================================
// WebRTCBin 实现（替代 libdatachannel）
// ============================================================================

void GstPlayer::connectWebRTC(const QString &host, const QString &app, const QString &stream)
{
    qDebug() << "🌐 WebRTC 连接:" << host << "/" << app << "/" << stream;
    
    m_webrtcHost = host;
    m_webrtcApp = app;
    m_webrtcStream = stream;
    m_useWebRTC = true;
    
    // 🔥 重置错误标志和无帧计数
    m_srsError.store(false);
    m_srsRetryCount.store(0);  // 🔥 重置重试计数
    m_pendingOfferSdp.clear();
    m_noFpsSeconds.store(0);
    m_reconnectScheduled.store(false);
    m_offerSentForSession.store(false);  // 🔥 重置会话级 Offer 标志
    m_offerInProgress.store(false);      // 🔥 重置 Offer 进行中标志
    
    m_webrtcStatus = "Connecting...";
    emit webrtcStatusChanged(m_webrtcStatus);
    
    // 停止现有管道
    if (m_pipeline) {
        stop();
        destroyPipeline();
    }
    
    // 创建 WebRTC 管道
    if (!createPipeline()) {
        m_webrtcStatus = "Pipeline creation failed";
        emit webrtcStatusChanged(m_webrtcStatus);
        emit error("Failed to create WebRTC pipeline");
        return;
    }
    
    // 启动管道
    start();
    
    // 对于接收端 WebRTC，需要主动创建 offer（on-negotiation-needed 可能不会自动触发）
    if (m_webrtcbin) {
        qDebug() << "📞 主动创建 WebRTC Offer（接收端模式）...";
        // 稍微延迟以确保管道完全启动
        QTimer::singleShot(100, this, &GstPlayer::createWebRTCOffer);
    }
}

void GstPlayer::disconnectWebRTC()
{
    qDebug() << "🔌 WebRTC 断开连接";
    
    stop();
    destroyPipeline();
    
    m_webrtcConnected = false;
    m_noFpsSeconds.store(0);  // 🔥 重置无帧计数
    m_offerSentForSession.store(false);  // 🔥 重置会话级 Offer 标志
    m_offerInProgress.store(false);      // 🔥 重置 Offer 进行中标志
    m_webrtcStatus = "Disconnected";
    emit webrtcStatusChanged(m_webrtcStatus);
    emit webrtcDisconnected();
}

// ★★★ P2P 直连模式 BEGIN ★★★

void GstPlayer::connectP2P(const QString &pairedIosDeviceId, const QJsonArray &iceServers)
{
    qDebug() << "[P2P] 启动 P2P 直连模式，配对设备:" << pairedIosDeviceId;
    
    m_pairedIosDeviceId = pairedIosDeviceId;
    m_useP2P = true;
    m_useWebRTC = true;
    
    m_srsError.store(false);
    m_srsRetryCount.store(0);
    m_pendingOfferSdp.clear();
    m_noFpsSeconds.store(0);
    m_reconnectScheduled.store(false);
    m_offerSentForSession.store(false);
    m_offerInProgress.store(false);
    m_waitingForP2POffer.store(false);
    m_p2pViewRequestRetryCount.store(0);
    
    m_webrtcStatus = "P2P Connecting...";
    emit webrtcStatusChanged(m_webrtcStatus);
    
    if (m_pipeline) {
        stop();
        destroyPipeline();
    }
    
    if (!createPipeline()) {
        m_webrtcStatus = "P2P Pipeline creation failed";
        emit webrtcStatusChanged(m_webrtcStatus);
        emit error("Failed to create P2P WebRTC pipeline");
        return;
    }
    
    addP2PIceServers(iceServers);
    
    start();
    
    m_waitingForP2POffer.store(true);
    emit sendViewRequest(m_pairedIosDeviceId);
    qDebug() << "[P2P] 已发送 WEBRTC_REQUEST，等待 iOS Offer...";
    scheduleP2PViewRequestRetry();
}

void GstPlayer::disconnectP2P()
{
    qDebug() << "[P2P] 断开 P2P 连接";
    stopP2PViewRequestRetry("本地主动断开");
    
    if (!m_pairedIosDeviceId.isEmpty()) {
        emit sendHangup("pc_disconnect", m_pairedIosDeviceId);
    }
    
    stop();
    destroyPipeline();
    
    m_webrtcConnected = false;
    m_useP2P = false;
    m_pairedIosDeviceId.clear();
    m_noFpsSeconds.store(0);
    m_offerSentForSession.store(false);
    m_offerInProgress.store(false);
    m_webrtcStatus = "P2P Disconnected";
    emit webrtcStatusChanged(m_webrtcStatus);
    emit webrtcDisconnected();
}

void GstPlayer::handleWebRTCSignaling(const QJsonObject &message)
{
    QString type = message.value("type").toString();
    
    if (type == "WEBRTC_SDP") {
        QString sdpType = message.value("sdpType").toString();
        QString sdp = message.value("sdp").toString();
        
        if (sdpType == "offer") {
            qDebug() << "[P2P] 收到 iOS Offer";
            stopP2PViewRequestRetry("收到 iOS Offer");
            handleP2POffer(sdp);
        } else if (sdpType == "answer") {
            qDebug() << "[P2P] 意外收到 Answer，忽略";
        }
        
    } else if (type == "WEBRTC_ICE") {
        QString candidate = message.value("candidate").toString();
        QString sdpMid = message.value("sdpMid").toString();
        int sdpMLineIndex = message.value("sdpMLineIndex").toInt(0);
        handleP2PIce(candidate, sdpMid, sdpMLineIndex);
        
    } else if (type == "WEBRTC_HANGUP") {
        stopP2PViewRequestRetry("收到远端挂断");
        QString reason = message.value("reason").toString();
        qDebug() << "[P2P] iOS 端挂断:" << reason;
        if (reason == "ice_failed") {
            m_webrtcStatus = "P2P connection failed (ICE)";
            emit webrtcStatusChanged(m_webrtcStatus);
            emit error("P2P 直连失败：NAT穿透失败，请检查网络环境");
        }
        handleP2PHangup();
        
    } else if (type == "WEBRTC_REJECT") {
        stopP2PViewRequestRetry("收到观看请求拒绝");
        QString reason = message.value("reason").toString();
        qDebug() << "[P2P] 观看请求被拒绝:" << reason;
        
        if (reason == "max_viewers_reached") {
            m_webrtcStatus = "P2P rejected: max viewers";
            emit error("该设备已达到最大观看人数上限");
        } else if (reason == "not_ready") {
            m_webrtcStatus = "P2P rejected: not ready";
            emit error("设备未就绪，请稍后重试");
            QTimer::singleShot(3000, this, [this]() {
                if (m_useP2P && !m_pairedIosDeviceId.isEmpty()) {
                    qDebug() << "[P2P] 自动重试 WEBRTC_REQUEST...";
                    emit sendViewRequest(m_pairedIosDeviceId);
                }
            });
        }
        emit webrtcStatusChanged(m_webrtcStatus);
    }
}

void GstPlayer::handleP2POffer(const QString &sdp)
{
    if (!m_webrtcbin) {
        qWarning() << "[P2P] webrtcbin 未初始化，无法处理 Offer";
        return;
    }
    
    GstSDPMessage *sdpMsg;
    gst_sdp_message_new(&sdpMsg);
    
    QByteArray sdpBytes = sdp.toUtf8();
    if (gst_sdp_message_parse_buffer((const guint8*)sdpBytes.constData(), sdpBytes.size(), sdpMsg) != GST_SDP_OK) {
        qWarning() << "[P2P] 解析 Offer SDP 失败";
        gst_sdp_message_free(sdpMsg);
        return;
    }
    
    GstWebRTCSessionDescription *offer = gst_webrtc_session_description_new(GST_WEBRTC_SDP_TYPE_OFFER, sdpMsg);
    
    GstPromise *setPromise = gst_promise_new();
    g_signal_emit_by_name(m_webrtcbin, "set-remote-description", offer, setPromise);
    gst_promise_interrupt(setPromise);
    gst_promise_unref(setPromise);
    gst_webrtc_session_description_free(offer);
    
    qDebug() << "[P2P] 已设置远端 Offer SDP";
    
    qDebug() << "[P2P] 创建 Answer...";
    GstPromise *answerPromise = gst_promise_new_with_change_func(
        [](GstPromise *promise, gpointer userData) {
            GstPlayer *self = static_cast<GstPlayer*>(userData);
            
            const GstStructure *reply = gst_promise_get_reply(promise);
            if (!reply) {
                qWarning() << "[P2P] 创建 Answer 失败：无回复";
                return;
            }
            
            GstWebRTCSessionDescription *answer = nullptr;
            gst_structure_get(reply, "answer", GST_TYPE_WEBRTC_SESSION_DESCRIPTION, &answer, nullptr);
            
            if (answer) {
                self->onP2PAnswerCreated(answer);
                gst_webrtc_session_description_free(answer);
            } else {
                qWarning() << "[P2P] 创建 Answer 失败：无 SDP";
            }
        },
        this, nullptr);
    
    g_signal_emit_by_name(m_webrtcbin, "create-answer", nullptr, answerPromise);
}

void GstPlayer::onP2PAnswerCreated(GstWebRTCSessionDescription *answer)
{
    qDebug() << "[P2P] Answer 创建成功";
    
    GstPromise *localPromise = gst_promise_new();
    g_signal_emit_by_name(m_webrtcbin, "set-local-description", answer, localPromise);
    gst_promise_interrupt(localPromise);
    gst_promise_unref(localPromise);
    
    gchar *sdpText = gst_sdp_message_as_text(answer->sdp);
    QString sdpStr = QString::fromUtf8(sdpText);
    g_free(sdpText);
    
    QMetaObject::invokeMethod(this, [this, sdpStr]() {
        qDebug() << "[P2P] 发送 Answer SDP 给 iOS";
        emit sendSdpAnswer(sdpStr, m_pairedIosDeviceId);
    }, Qt::QueuedConnection);
}

void GstPlayer::handleP2PIce(const QString &candidate, const QString &sdpMid, int sdpMLineIndex)
{
    if (!m_webrtcbin) {
        qWarning() << "[P2P] webrtcbin 未初始化，缓存 ICE 候选者";
        return;
    }
    
    qDebug() << "[P2P] 添加远端 ICE:" << candidate.left(50) << "...";
    g_signal_emit_by_name(m_webrtcbin, "add-ice-candidate", (guint)sdpMLineIndex,
                          candidate.toUtf8().constData());
}

void GstPlayer::handleP2PHangup()
{
    qDebug() << "[P2P] 处理挂断";
    stopP2PViewRequestRetry("处理挂断");
    
    stop();
    destroyPipeline();
    
    m_webrtcConnected = false;
    m_webrtcStatus = "P2P Hangup";
    emit webrtcStatusChanged(m_webrtcStatus);
    emit webrtcDisconnected();
}

void GstPlayer::scheduleP2PViewRequestRetry()
{
    QTimer::singleShot(P2P_VIEW_REQUEST_RETRY_INTERVAL_MS, this, [this]() {
        if (!m_waitingForP2POffer.load() || !m_useP2P || m_pairedIosDeviceId.isEmpty()) {
            return;
        }

        int retry = m_p2pViewRequestRetryCount.fetch_add(1) + 1;
        if (retry > P2P_VIEW_REQUEST_RETRY_MAX) {
            m_waitingForP2POffer.store(false);
            m_webrtcStatus = "P2P waiting offer timeout";
            emit webrtcStatusChanged(m_webrtcStatus);
            qWarning() << "[P2P] 等待 iOS Offer 超时，停止重试";
            emit error("P2P 连接超时：未收到 iOS Offer，请检查设备在线状态");
            return;
        }

        qDebug() << "[P2P] 未收到 Offer，重发 WEBRTC_REQUEST (" << retry
                 << "/" << P2P_VIEW_REQUEST_RETRY_MAX << ")";
        emit sendViewRequest(m_pairedIosDeviceId);
        scheduleP2PViewRequestRetry();
    });
}

void GstPlayer::stopP2PViewRequestRetry(const QString &reason)
{
    bool wasWaiting = m_waitingForP2POffer.exchange(false);
    m_p2pViewRequestRetryCount.store(0);
    if (wasWaiting && !reason.isEmpty()) {
        qDebug() << "[P2P] 停止 WEBRTC_REQUEST 重试:" << reason;
    }
}

void GstPlayer::addP2PIceServers(const QJsonArray &iceServers)
{
    if (!m_webrtcbin || iceServers.isEmpty()) return;
    
    qDebug() << "[P2P] 配置 ICE 服务器，共" << iceServers.size() << "个";
    
    for (const auto &server : iceServers) {
        QJsonObject obj = server.toObject();
        QJsonArray urls = obj["urls"].toArray();
        QString username = obj["username"].toString();
        QString credential = obj["credential"].toString();
        
        for (const auto &urlVal : urls) {
            QString urlStr = urlVal.toString();
            
            if (urlStr.startsWith("stun:")) {
                g_object_set(m_webrtcbin, "stun-server",
                             urlStr.toUtf8().constData(), nullptr);
                qDebug() << "  STUN:" << urlStr;
            } else if (urlStr.startsWith("turn:") || urlStr.startsWith("turns:")) {
                QString prefix = urlStr.startsWith("turns:") ? "turns:" : "turn:";
                QString encodedUser = QString::fromUtf8(QUrl::toPercentEncoding(username));
                QString encodedPass = QString::fromUtf8(QUrl::toPercentEncoding(credential));
                QString turnUri = QString("turn://%1:%2@%3")
                    .arg(encodedUser, encodedPass,
                         urlStr.mid(prefix.length()));
                
                gboolean addResult = FALSE;
                g_signal_emit_by_name(m_webrtcbin, "add-turn-server", 
                                      turnUri.toUtf8().constData(), &addResult);
                if (addResult) {
                    qDebug() << "  TURN (add-turn-server):" << turnUri.left(60) << "...";
                } else {
                    g_object_set(m_webrtcbin, "turn-server",
                                 turnUri.toUtf8().constData(), nullptr);
                    qDebug() << "  TURN (property fallback):" << turnUri.left(60) << "...";
                }
            }
        }
    }
}

void GstPlayer::onIceConnectionStateChanged(GstElement *webrtcbin, GParamSpec *pspec, gpointer userData)
{
    Q_UNUSED(pspec);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    
    GstWebRTCICEConnectionState state;
    g_object_get(webrtcbin, "ice-connection-state", &state, nullptr);
    
    QMetaObject::invokeMethod(self, [self, state]() {
        switch (state) {
        case GST_WEBRTC_ICE_CONNECTION_STATE_NEW:
            qDebug() << "[P2P] ICE Connection: New";
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_CHECKING:
            qDebug() << "[P2P] ICE Connection: Checking...";
            self->m_webrtcStatus = "P2P: ICE Checking...";
            emit self->webrtcStatusChanged(self->m_webrtcStatus);
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_CONNECTED:
            qDebug() << "[P2P] ICE Connection: Connected!";
            self->m_webrtcStatus = "P2P Connected";
            self->m_p2pConnected = true;
            self->m_iceRetryCount = 0;
            emit self->webrtcStatusChanged(self->m_webrtcStatus);
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_COMPLETED:
            qDebug() << "[P2P] ICE Connection: Completed!";
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_FAILED:
            qDebug() << "[P2P] ICE Connection: FAILED!";
            self->m_webrtcStatus = "P2P: ICE Failed";
            emit self->webrtcStatusChanged(self->m_webrtcStatus);
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_DISCONNECTED:
            qDebug() << "[P2P] ICE Connection: Disconnected";
            self->m_webrtcStatus = "P2P: Reconnecting...";
            emit self->webrtcStatusChanged(self->m_webrtcStatus);
            break;
        case GST_WEBRTC_ICE_CONNECTION_STATE_CLOSED:
            qDebug() << "[P2P] ICE Connection: Closed";
            break;
        }
    }, Qt::QueuedConnection);
}

// ★★★ P2P 直连模式 END ★★★

void GstPlayer::setupWebRTCSignals()
{
    if (!m_webrtcbin) return;
    
    qDebug() << "📡 设置 WebRTCBin 信号...";
    
    // on-negotiation-needed：当需要协商时创建 offer
    g_signal_connect(m_webrtcbin, "on-negotiation-needed",
                     G_CALLBACK(onNegotiationNeeded), this);
    
    // on-ice-candidate：收集到 ICE 候选者
    g_signal_connect(m_webrtcbin, "on-ice-candidate",
                     G_CALLBACK(onIceCandidate), this);
    
    // pad-added：新的媒体 pad 添加时连接到解码器
    g_signal_connect(m_webrtcbin, "pad-added",
                     G_CALLBACK(onWebRTCPadAdded), this);
    
    // 连接状态变化
    g_signal_connect(m_webrtcbin, "notify::connection-state",
                     G_CALLBACK(onConnectionStateChanged), this);
    
    // ICE 收集状态变化
    g_signal_connect(m_webrtcbin, "notify::ice-gathering-state",
                     G_CALLBACK(onIceGatheringStateChanged), this);
    
    // ICE 连接状态变化（P2P 模式需要）
    g_signal_connect(m_webrtcbin, "notify::ice-connection-state",
                     G_CALLBACK(onIceConnectionStateChanged), this);
    
    qDebug() << "✅ WebRTCBin 信号设置完成";
}

// 静态回调：需要协商
void GstPlayer::onNegotiationNeeded(GstElement *webrtcbin, gpointer userData)
{
    Q_UNUSED(webrtcbin);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    qDebug() << "🔄 on-negotiation-needed 触发";
    
    // 在主线程中创建 offer
    QMetaObject::invokeMethod(self, "createWebRTCOffer", Qt::QueuedConnection);
}

// 静态回调：ICE 候选者
void GstPlayer::onIceCandidate(GstElement *webrtcbin, guint mlineindex, gchar *candidate, gpointer userData)
{
    Q_UNUSED(webrtcbin);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    qDebug() << "🧊 ICE 候选者:" << mlineindex << candidate;
    
    // P2P 模式：发送本地 ICE 候选者给远端
    if (self->m_useP2P && !self->m_pairedIosDeviceId.isEmpty()) {
        QString candidateStr = QString::fromUtf8(candidate);
        QMetaObject::invokeMethod(self, [self, candidateStr, mlineindex]() {
            emit self->sendIceCandidate(candidateStr, "0", (int)mlineindex, self->m_pairedIosDeviceId);
        }, Qt::QueuedConnection);
    }
    // SRS 模式使用 ice-lite，不需要发送本地候选者
}

// 静态回调：新 pad 添加（连接到解码链）
void GstPlayer::onWebRTCPadAdded(GstElement *webrtcbin, GstPad *pad, gpointer userData)
{
    Q_UNUSED(webrtcbin);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    
    gchar *padName = gst_pad_get_name(pad);
    qDebug() << "🔥 WebRTCBin pad-added:" << padName;
    
    // 检查是否是视频 RTP pad
    GstCaps *caps = gst_pad_get_current_caps(pad);
    if (!caps) {
        caps = gst_pad_query_caps(pad, nullptr);
    }
    
    if (caps) {
        GstStructure *s = gst_caps_get_structure(caps, 0);
        const gchar *mediaType = gst_structure_get_string(s, "media");
        const gchar *encoding = gst_structure_get_string(s, "encoding-name");
        
        qDebug() << "   媒体类型:" << (mediaType ? mediaType : "unknown")
                 << "编码:" << (encoding ? encoding : "unknown");
        
        // 只处理视频 H264
        bool isVideo = mediaType && g_strcmp0(mediaType, "video") == 0;
        bool isH264 = encoding && g_strcmp0(encoding, "H264") == 0;
        
        if (isVideo || isH264 || g_str_has_prefix(padName, "recv_rtp_src_")) {
            qDebug() << "✅ 发现视频 RTP pad，连接到解码器...";
            
            // 获取 rtph264depay 的 sink pad
            if (self->m_rtph264depay) {
                GstPad *sinkPad = gst_element_get_static_pad(self->m_rtph264depay, "sink");
                if (sinkPad && !gst_pad_is_linked(sinkPad)) {
                    GstPadLinkReturn ret = gst_pad_link(pad, sinkPad);
                    if (ret == GST_PAD_LINK_OK) {
                        qDebug() << "✅ 视频 pad 连接成功";
                        
                        // ⭐⭐⭐ 关键：与 Java 一致，pad 连接后立即请求关键帧（防止黑屏/绿幕）
                        qDebug() << "🔥 视频流已连接，立即请求首个关键帧...";
                        QMetaObject::invokeMethod(self, "requestKeyFrame", Qt::QueuedConnection);
                        // 100ms 后再发送一次
                        QTimer::singleShot(100, self, [self]() {
                            if (self->m_pipeline) {
                                self->sendPLIRequest();
                            }
                        });
                        // 300ms 后再发送一次
                        QTimer::singleShot(300, self, [self]() {
                            if (self->m_pipeline) {
                                self->sendPLIRequest();
                            }
                        });
                    } else {
                        qWarning() << "❌ 视频 pad 连接失败:" << ret;
                    }
                }
                if (sinkPad) gst_object_unref(sinkPad);
            }
        }
        
        gst_caps_unref(caps);
    }
    
    g_free(padName);
}

// 静态回调：连接状态变化
void GstPlayer::onConnectionStateChanged(GstElement *webrtcbin, GParamSpec *pspec, gpointer userData)
{
    Q_UNUSED(pspec);
    GstPlayer *self = static_cast<GstPlayer*>(userData);
    
    GstWebRTCPeerConnectionState state;
    g_object_get(webrtcbin, "connection-state", &state, nullptr);
    
    QString stateStr;
    switch (state) {
        case GST_WEBRTC_PEER_CONNECTION_STATE_NEW:
            stateStr = "New";
            break;
        case GST_WEBRTC_PEER_CONNECTION_STATE_CONNECTING:
            stateStr = "Connecting";
            break;
        case GST_WEBRTC_PEER_CONNECTION_STATE_CONNECTED:
            stateStr = "Connected";
            self->m_webrtcConnected = true;
            QMetaObject::invokeMethod(self, "webrtcConnected", Qt::QueuedConnection);
            // ⭐⭐⭐ 关键：与 Java 一致，连接成功后多次请求关键帧（防止绿幕）
            // 立即发送第一次 PLI
            QMetaObject::invokeMethod(self, "requestKeyFrame", Qt::QueuedConnection);
            // 100ms 后发送第二次 PLI
            QTimer::singleShot(100, self, [self]() {
                if (self->m_webrtcConnected) {
                    self->sendPLIRequest();
                    qDebug() << "📨 PLI 请求 (100ms)";
                }
            });
            // 300ms 后发送第三次 PLI
            QTimer::singleShot(300, self, [self]() {
                if (self->m_webrtcConnected) {
                    self->sendPLIRequest();
                    qDebug() << "📨 PLI 请求 (300ms)";
                }
            });
            qDebug() << "✅ WebRTC 连接已建立，已发送 3 次 PLI 请求";
            break;
        case GST_WEBRTC_PEER_CONNECTION_STATE_DISCONNECTED:
            stateStr = "Disconnected";
            self->m_webrtcConnected = false;
            break;
        case GST_WEBRTC_PEER_CONNECTION_STATE_FAILED:
            stateStr = "Failed";
            self->m_webrtcConnected = false;
            // 🔥 只断开，不自动重连（由 QML 层决定是否重连）
            QMetaObject::invokeMethod(self, "disconnectWebRTC", Qt::QueuedConnection);
            break;
        case GST_WEBRTC_PEER_CONNECTION_STATE_CLOSED:
            stateStr = "Closed";
            self->m_webrtcConnected = false;
            // 🔥 只断开，不自动重连
            QMetaObject::invokeMethod(self, "disconnectWebRTC", Qt::QueuedConnection);
            break;
        default:
            stateStr = "Unknown";
    }
    
    qDebug() << "🔗 WebRTC 连接状态:" << stateStr;
    self->m_webrtcStatus = stateStr;
    
    QMetaObject::invokeMethod(self, [self, stateStr]() {
        emit self->webrtcStatusChanged(stateStr);
    }, Qt::QueuedConnection);
}

// 静态回调：ICE 收集状态变化
void GstPlayer::onIceGatheringStateChanged(GstElement *webrtcbin, GParamSpec *pspec, gpointer userData)
{
    Q_UNUSED(pspec);
    Q_UNUSED(userData);
    
    GstWebRTCICEGatheringState state;
    g_object_get(webrtcbin, "ice-gathering-state", &state, nullptr);
    
    QString stateStr;
    switch (state) {
        case GST_WEBRTC_ICE_GATHERING_STATE_NEW:
            stateStr = "New";
            break;
        case GST_WEBRTC_ICE_GATHERING_STATE_GATHERING:
            stateStr = "Gathering";
            break;
        case GST_WEBRTC_ICE_GATHERING_STATE_COMPLETE:
            stateStr = "Complete";
            break;
        default:
            stateStr = "Unknown";
    }
    
    qDebug() << "🧊 ICE 收集状态:" << stateStr;
}

void GstPlayer::createWebRTCOffer()
{
    // P2P 模式：PC 是 Answerer，不主动创建 Offer
    if (m_useP2P) {
        qDebug() << "[P2P] P2P 模式：跳过自动 Offer 创建（等待 iOS Offer）";
        return;
    }
    
    // 🔥 会话级防重：同一次 connectWebRTC() 只允许发送一个 Offer
    // 防止 on-negotiation-needed 和 QTimer 双重触发导致发送两个 Offer 到 SRS
    if (m_offerSentForSession.exchange(true)) {
        qDebug() << "⚠️ 本次连接已发送过 Offer，跳过重复请求";
        return;
    }

    if (m_offerInProgress.exchange(true)) {
        qDebug() << "⚠️ Offer 正在创建中，跳过重复请求";
        m_offerSentForSession.store(false);  // 回退会话标志
        return;
    }

    if (!m_webrtcbin) {
        qWarning() << "❌ webrtcbin 未初始化";
        m_offerInProgress.store(false);
        m_offerSentForSession.store(false);  // 回退会话标志
        return;
    }
    
    qDebug() << "📝 创建 WebRTC Offer...";
    
    // ⭐ 添加 recvonly transceiver（与 Java 版本一致）
    // 这是接收端 WebRTC 的关键：必须告诉 SRS 我们只接收视频
    if (!m_transceiverAdded) {
        // ⭐ 移除 profile-level-id 限制，让 WebRTC 自动协商
        // 支持所有 Profile (Baseline/Main/High) 和 Level (3.1~5.2+)
        // 解决 iPhone 16 等新设备可能使用不同编码参数的问题
        GstCaps *videoCaps = gst_caps_from_string(
            "application/x-rtp,media=video,payload=109,encoding-name=H264,"
            "clock-rate=90000,packetization-mode=(string)1,"
            "level-asymmetry-allowed=(string)1"
        );
        
        if (videoCaps) {
            GstWebRTCRTPTransceiver *transceiver = nullptr;
            g_signal_emit_by_name(m_webrtcbin, "add-transceiver", 
                                  GST_WEBRTC_RTP_TRANSCEIVER_DIRECTION_RECVONLY,
                                  videoCaps, &transceiver);
            gst_caps_unref(videoCaps);
            
            if (transceiver) {
                qDebug() << "✅ 已添加 recvonly H264 视频 transceiver";
                gst_object_unref(transceiver);
                m_transceiverAdded = true;
            } else {
                qWarning() << "⚠️ 添加 transceiver 失败";
            }
        }
    }
    
    // 使用 GStreamer promise 创建 offer
    GstPromise *promise = gst_promise_new_with_change_func(
        [](GstPromise *promise, gpointer userData) {
            GstPlayer *self = static_cast<GstPlayer*>(userData);
            
            const GstStructure *reply = gst_promise_get_reply(promise);
            if (!reply) {
                qWarning() << "❌ 创建 offer 失败：无回复";
                self->m_offerInProgress.store(false);
                self->m_offerSentForSession.store(false);  // 🔥 允许重试
                return;
            }
            
            GstWebRTCSessionDescription *offer = nullptr;
            gst_structure_get(reply, "offer", GST_TYPE_WEBRTC_SESSION_DESCRIPTION, &offer, nullptr);
            
            if (offer) {
                self->onOfferCreated(offer);
                gst_webrtc_session_description_free(offer);
            } else {
                qWarning() << "❌ 创建 offer 失败：无 SDP";
                self->m_offerInProgress.store(false);
                self->m_offerSentForSession.store(false);  // 🔥 允许重试
            }
        },
        this, nullptr);
    
    // 发出 create-offer 信号
    g_signal_emit_by_name(m_webrtcbin, "create-offer", nullptr, promise);
}

void GstPlayer::onOfferCreated(GstWebRTCSessionDescription *offer)
{
    qDebug() << "✅ Offer 创建成功";
    
    // 设置本地描述
    GstPromise *localPromise = gst_promise_new();
    g_signal_emit_by_name(m_webrtcbin, "set-local-description", offer, localPromise);
    gst_promise_interrupt(localPromise);
    gst_promise_unref(localPromise);
    
    // 获取 SDP 文本
    gchar *sdpText = gst_sdp_message_as_text(offer->sdp);
    QString sdpStr = QString::fromUtf8(sdpText);
    g_free(sdpText);
    
    qDebug() << "📤 发送 Offer 到 SRS...";
    
    // ⭐ 在主线程中发送 HTTP 请求（GStreamer 回调在其他线程）
    QMetaObject::invokeMethod(this, [this, sdpStr]() {
        sendOfferToSRS(sdpStr);
        // 等待 HTTP 回包后再释放 m_offerInProgress
    }, Qt::QueuedConnection);
}

void GstPlayer::sendOfferToSRS(const QString &sdp)
{
    // 🔥 保存 SDP 用于重试
    m_pendingOfferSdp = sdp;
    
    // 构建 API URL
    QString apiUrl = QString("http://%1:1985/rtc/v1/play/").arg(m_webrtcHost);
    
    // SRS streamurl 格式：webrtc://host/app/stream?vhost=xxx&eip=xxx
    // 与 Java 版本保持一致
    QString vhost = "vid-7gg4748";  // 默认 vhost
    QString streamUrl = QString("webrtc://%1/%2/%3?vhost=%4&eip=%1")
        .arg(m_webrtcHost, m_webrtcApp, m_webrtcStream, vhost);
    
    // 确保 SDP 使用 CRLF 行尾（SRS 要求）
    QString sdpCRLF = sdp;
    sdpCRLF.replace("\r\n", "\n");  // 先统一为 LF
    sdpCRLF.replace("\n", "\r\n");  // 再转为 CRLF
    
    int retryCount = m_srsRetryCount.load();
    qDebug() << "📤 API URL:" << apiUrl << "(重试:" << retryCount << "/5)";
    qDebug() << "📤 Stream URL:" << streamUrl;
    
    // 调试：检查 SDP 是否包含视频 m-line
    bool hasVideo = sdpCRLF.contains("m=video");
    bool hasAudio = sdpCRLF.contains("m=audio");
    qDebug() << "📋 SDP 检查: hasVideo=" << hasVideo << "hasAudio=" << hasAudio;
    qDebug() << "📄 SDP 预览:" << sdpCRLF.left(300);
    
    // 构建 JSON 请求体
    QJsonObject json;
    json["api"] = apiUrl;
    json["streamurl"] = streamUrl;
    json["sdp"] = sdpCRLF;
    
    QJsonDocument doc(json);
    QByteArray jsonData = doc.toJson(QJsonDocument::Compact);
    
    // 发送 HTTP POST 请求
    QUrl url(apiUrl);
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    request.setRawHeader("Accept", "application/json");
    
    QNetworkReply *reply = m_networkManager->post(request, jsonData);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        if (reply->error() != QNetworkReply::NoError) {
            qWarning() << "❌ HTTP 请求失败:" << reply->errorString();
            m_webrtcStatus = "HTTP Error";
            emit webrtcStatusChanged(m_webrtcStatus);
            emit error(reply->errorString());
            m_offerInProgress.store(false);
            reply->deleteLater();
            return;
        }
        
        QByteArray responseData = reply->readAll();
        qDebug() << "📥 收到 SRS 响应:" << responseData.left(500);
        
        // 解析响应
        QJsonDocument responseDoc = QJsonDocument::fromJson(responseData);
        if (responseDoc.isObject()) {
            QJsonObject responseObj = responseDoc.object();
            
            // 检查错误
            if (responseObj.contains("code") && responseObj["code"].toInt() != 0) {
                int errorCode = responseObj["code"].toInt();
                QString errorMsg = responseObj["msg"].toString();
                int retryCount = m_srsRetryCount.load();
                
                qWarning() << "❌ SRS 返回错误 code=" << errorCode << ":" << errorMsg << "(重试:" << retryCount << "/5)";
                
                // 🔥 SRS 400/404 错误可能是流还没准备好，进行重试
                if ((errorCode == 400 || errorCode == 404) && retryCount < 5) {
                    m_srsRetryCount.fetch_add(1);
                    qDebug() << "🔄 SRS 流可能未就绪，" << (retryCount + 1) << "秒后重试...";
                    m_webrtcStatus = QString("Retrying... (%1/5)").arg(retryCount + 1);
                    emit webrtcStatusChanged(m_webrtcStatus);
                    
                    // 延迟重试（1秒后）
                    QTimer::singleShot(1000 * (retryCount + 1), this, [this]() {
                        if (!m_pendingOfferSdp.isEmpty() && m_useWebRTC) {
                            qDebug() << "🔄 重试发送 Offer 到 SRS...";
                            sendOfferToSRS(m_pendingOfferSdp);
                        }
                    });
                    
                    m_offerInProgress.store(false);
                    reply->deleteLater();
                    return;
                }
                
                // 重试次数用尽，放弃
                m_webrtcStatus = "SRS Error";
                emit webrtcStatusChanged(m_webrtcStatus);
                m_srsError.store(true);
                qDebug() << "⚠️ SRS 错误（重试用尽），禁用自动重连";
                
                emit error(errorMsg);
                m_offerInProgress.store(false);
                reply->deleteLater();
                return;
            }
            
            // 🔥 成功，重置重试计数
            m_srsRetryCount.store(0);
            m_pendingOfferSdp.clear();
            
            // 获取 Answer SDP
            QString answerSdp = responseObj["sdp"].toString();
            if (!answerSdp.isEmpty()) {
                onAnswerReceived(answerSdp);
            } else {
                qWarning() << "❌ 响应中没有 SDP";
            }
        }
        
        m_offerInProgress.store(false);
        reply->deleteLater();
    });
}

void GstPlayer::onAnswerReceived(const QString &sdp)
{
    qDebug() << "📥 收到 Answer SDP，设置远程描述...";
    
    // 解析 SDP
    GstSDPMessage *sdpMsg;
    gst_sdp_message_new(&sdpMsg);
    
    QByteArray sdpBytes = sdp.toUtf8();
    if (gst_sdp_message_parse_buffer((const guint8*)sdpBytes.constData(), sdpBytes.size(), sdpMsg) != GST_SDP_OK) {
        qWarning() << "❌ 解析 Answer SDP 失败";
        gst_sdp_message_free(sdpMsg);
        return;
    }
    
    // 创建 WebRTC Session Description
    GstWebRTCSessionDescription *answer = gst_webrtc_session_description_new(GST_WEBRTC_SDP_TYPE_ANSWER, sdpMsg);
    
    // 设置远程描述
    GstPromise *promise = gst_promise_new();
    g_signal_emit_by_name(m_webrtcbin, "set-remote-description", answer, promise);
    gst_promise_interrupt(promise);
    gst_promise_unref(promise);
    
    gst_webrtc_session_description_free(answer);
    
    qDebug() << "✅ 远程 SDP 描述设置完成";
    
    m_webrtcStatus = "Negotiating...";
    emit webrtcStatusChanged(m_webrtcStatus);
}

void GstPlayer::requestKeyFrame()
{
    sendPLIRequest();
}

void GstPlayer::sendPLIRequest()
{
    if (!m_webrtcbin) {
        qDebug() << "⚠️ webrtcbin 未初始化，无法发送 PLI";
        return;
    }
    
    // 🔥 v10.4: 统一 PLI 速率限制，避免疯狂请求导致卡死
    qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
    if (m_lastKeyframeRequestMs > 0 && (nowMs - m_lastKeyframeRequestMs) < PLI_INTERVAL_WEAK_MS) {
        return;
    }
    m_lastKeyframeRequestMs = nowMs;
    
    // 🔥🔥🔥 v10.2 修复：正确的 PLI 发送方式
    // upstream 事件必须从元素的 srcpad 发送（不是 sinkpad！）
    // 事件会沿着 pipeline 向上游传递到 webrtcbin
    
    bool sent = false;
    
    // 方法1：通过 rtph264depay 的 srcpad 发送（最接近 webrtcbin）
    if (m_rtph264depay) {
        GstPad *srcpad = gst_element_get_static_pad(m_rtph264depay, "src");
        if (srcpad) {
            GstEvent *event = gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0);
            sent = gst_pad_send_event(srcpad, event);
            gst_object_unref(srcpad);
            if (sent) {
                qDebug() << "🔑 PLI 请求已发送（通过 rtph264depay srcpad）";
                return;
            }
        }
    }
    
    // 方法2：通过 h264parse 的 srcpad 发送
    if (m_h264parse) {
        GstPad *srcpad = gst_element_get_static_pad(m_h264parse, "src");
        if (srcpad) {
            GstEvent *event = gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0);
            sent = gst_pad_send_event(srcpad, event);
            gst_object_unref(srcpad);
            if (sent) {
                qDebug() << "🔑 PLI 请求已发送（通过 h264parse srcpad）";
                return;
            }
        }
    }
    
    // 方法3：通过解码器的 srcpad 发送
    if (m_decoder) {
        GstPad *srcpad = gst_element_get_static_pad(m_decoder, "src");
        if (srcpad) {
            GstEvent *event = gst_video_event_new_upstream_force_key_unit(GST_CLOCK_TIME_NONE, TRUE, 0);
            sent = gst_pad_send_event(srcpad, event);
            gst_object_unref(srcpad);
            if (sent) {
                qDebug() << "🔑 PLI 请求已发送（通过 decoder srcpad）";
                return;
        }
    }
    }
    
    qDebug() << "⚠️ PLI 请求发送失败，所有方法都不可用";
}

void GstPlayer::startAutoKeyFrameRequest(int intervalMs)
{
    if (m_autoKeyFrameEnabled) {
        qDebug() << "⚠️ 周期性关键帧请求已在运行";
        return;
    }
    
    qDebug() << "🔄 启动周期性关键帧请求，间隔:" << intervalMs << "ms";
    
    if (!m_keyFrameTimer) {
        m_keyFrameTimer = new QTimer(this);
        connect(m_keyFrameTimer, &QTimer::timeout, this, &GstPlayer::sendPLIRequest);
    }
    
    m_keyFrameTimer->start(intervalMs);
    m_autoKeyFrameEnabled = true;
}

void GstPlayer::stopAutoKeyFrameRequest()
{
    if (!m_autoKeyFrameEnabled) {
        return;
    }
    
    qDebug() << "⏹️ 停止周期性关键帧请求";
    
    if (m_keyFrameTimer) {
        m_keyFrameTimer->stop();
    }
    m_autoKeyFrameEnabled = false;
}

void GstPlayer::flushDecoder()
{
    if (!m_pipeline) {
        qDebug() << "⚠️ Pipeline 未初始化，无法 flush";
        return;
    }
    
    qDebug() << "🔄 执行 flush 解码器...";
    
    // 向 pipeline 发送 flush 事件
    // 这会清空所有元素的缓冲区，包括解码器的参考帧
    GstEvent *flushStart = gst_event_new_flush_start();
    GstEvent *flushStop = gst_event_new_flush_stop(TRUE);  // TRUE = 重置运行时间
    
    // 发送 flush-start
    if (m_rtph264depay) {
        GstPad *sinkpad = gst_element_get_static_pad(m_rtph264depay, "sink");
        if (sinkpad) {
            gst_pad_send_event(sinkpad, flushStart);
            gst_object_unref(sinkpad);
        }
    }
    
    // 发送 flush-stop（重新开始处理）
    if (m_rtph264depay) {
        GstPad *sinkpad = gst_element_get_static_pad(m_rtph264depay, "sink");
        if (sinkpad) {
            gst_pad_send_event(sinkpad, flushStop);
            gst_object_unref(sinkpad);
        }
    }
    
    qDebug() << "✅ 解码器 flush 完成";
}

// ⭐⭐⭐ v8.1 简单版本：计算消费间隔
// 核心：基于到达帧率EMA计算渲染间隔，EMA本身已经平滑
double GstPlayer::calcSmoothInterval(int queueDepth)
{
    // 1. 更新队列深度EMA（用于日志和播放速度调整）
    m_depthEma = BETA_DEPTH * queueDepth + (1.0 - BETA_DEPTH) * m_depthEma;
    
    // 2. 目标平滑过渡（防止跳变）
    double targetDiff = m_queueTarget - m_queueTargetSmooth;
    if (std::abs(targetDiff) > 0.5) {
        m_queueTargetSmooth += (targetDiff > 0) ? 0.5 : -0.5;
    } else {
        m_queueTargetSmooth = m_queueTarget;
    }
    
    // 3. ⭐⭐⭐ v8.1核心：基于到达帧率EMA计算基础间隔
    // 到达帧率EMA本身已经有平滑效果，不需要额外处理
    double safeArrivalRate = qMax(10.0, m_arrivalRateEma);
    double safePlaybackRate = qMax(0.5, m_playbackRate);
    double baseInterval = 1000.0 / safeArrivalRate / safePlaybackRate;
    
    // 4. 队列积压时稍微加速消费（温和追赶）
    double waterLevel = (m_queueTargetSmooth > 0) ? (double)queueDepth / m_queueTargetSmooth : 1.0;
    double rawInterval = baseInterval;
    if (waterLevel > 1.2) {
        // 队列积压超过120%：轻微加速追赶（最多8%）
        rawInterval = baseInterval / R_MAX;  // 🔥 v11: 使用 R_MAX (1.08)
    }
    
    // 5. EMA平滑（α=0.3，约3帧平滑）
    m_intervalEma = GAMMA_INTERVAL * rawInterval + (1.0 - GAMMA_INTERVAL) * m_intervalEma;
    
    // 6. 检查 NaN/Inf
    if (std::isnan(m_intervalEma) || std::isinf(m_intervalEma)) {
        m_intervalEma = 1000.0 / safeArrivalRate;
    }
    
    // 7. 间隔变化速度限制（每次最多变化15%，防止跳帧）
    static double lastInterval = 33.0;
    double maxChange = lastInterval * 0.15;
    double finalInterval = m_intervalEma;
    if (finalInterval > lastInterval + maxChange) {
        finalInterval = lastInterval + maxChange;
    } else if (finalInterval < lastInterval - maxChange) {
        finalInterval = lastInterval - maxChange;
    }
    lastInterval = finalInterval;
    
    // 8. 绝对边界：8ms(120fps)~200ms(5fps)
    finalInterval = qBound(8.0, finalInterval, 200.0);
    
    return finalInterval;
}

// ⭐⭐⭐ 分段函数：水位 → 目标播放速率
// 数学模型：
//   W < 0.15:           R = 0.7 (紧急)
//   0.15 ≤ W < 0.35:    R = 0.7 + 0.3×(W-0.15)/0.2 (线性恢复)
//   0.35 ≤ W < 1.05:    R = 1.0 (正常)
//   1.05 ≤ W < 1.5:     R = 1.0 + 0.2×(W-1.05)/0.45 (线性追帧)
//   W ≥ 1.5:            R = 1.2 (最大追帧)
double GstPlayer::piecewiseRate(double W)
{
    if (W < W_EMERGENCY) {
        // 紧急：最低速度
        return R_MIN;
    } else if (W < W_EXPAND) {
        // 恢复中：线性从0.7升到1.0
        double t = (W - W_EMERGENCY) / (W_EXPAND - W_EMERGENCY);
        return R_MIN + (1.0 - R_MIN) * t;
    } else if (W < W_CATCHUP) {
        // 正常：标准速度
        return 1.0;
    } else if (W < W_CATCHUP_MAX) {
        // 追帧：线性从1.0升到1.2
        double t = (W - W_CATCHUP) / (W_CATCHUP_MAX - W_CATCHUP);
        return 1.0 + (R_MAX - 1.0) * t;
    } else {
        // 最大追帧
        return R_MAX;
    }
}

// ⭐⭐⭐ FPS变化检测（自动唤醒机制）
// 当检测到实际FPS与配置FPS差异超过30%持续3秒，自动重配置
void GstPlayer::detectFpsChange()
{
    if (m_lastSecondFps < 1.0) return;  // 数据不足
    
    double ratio = m_lastSecondFps / m_configFps;
    double deviation = std::abs(ratio - 1.0);
    
    if (deviation > FPS_CHANGE_THRESHOLD) {
        // FPS变化超过阈值
        m_fpsChangeCounter++;
        
        if (m_fpsChangeCounter >= FPS_CHANGE_STABLE_SEC) {
            // 持续3秒，触发自动重配置
            double newFps = m_lastSecondFps;
            qDebug().noquote() << QString("🔄 自动检测FPS变化 | %1fps→%2fps | 差异=%3% | 触发重配置")
                .arg((int)m_configFps).arg((int)newFps).arg((int)(deviation*100));
            
            setConfigFps(newFps);
            m_fpsChangeCounter = 0;
        } else {
            qDebug().noquote() << QString("⏳ FPS变化检测中 | 当前%1fps 配置%2fps | 差异=%3% | 计数=%4/%5秒")
                .arg((int)m_lastSecondFps).arg((int)m_configFps)
                .arg((int)(deviation*100))
                .arg(m_fpsChangeCounter).arg(FPS_CHANGE_STABLE_SEC);
        }
    } else {
        // FPS正常，重置计数器
        if (m_fpsChangeCounter > 0) {
            qDebug().noquote() << QString("✅ FPS恢复正常 | %1fps ≈ 配置%2fps | 计数器重置")
                .arg((int)m_lastSecondFps).arg((int)m_configFps);
        }
        m_fpsChangeCounter = 0;
    }
}

// ⭐⭐⭐ v13 PC端不再控制帧率升降，改由iOS自己控制
// 
// 原v11-v12逻辑已禁用：
//   - PC端不再根据损坏帧比例发送 set_fps 命令给iOS
//   - iOS端自己根据网络状况进行自适应帧率调整
//
// 保留的功能：
//   - 损坏帧统计（用于日志显示和队列调整）
//   - 队列控制（adjustQueueTarget）
//   - 播放速度控制（onRenderTick中的速率调整）
void GstPlayer::checkPushFpsControl(double W)
{
    Q_UNUSED(W);
    
    // 🔥 v13：PC端不再发送升降帧命令，由iOS自己控制
    // 只保留计数器重置，其他逻辑全部移除
        m_lowWaterHoldSec = 0;
        m_highWaterHoldSec = 0;
}

// ⭐⭐⭐ v8客户方案第一道保险：通过队列控制延迟
// 核心公式：
//   最佳缓冲 = fps × 15%（150ms）
//   队列下限 = fps × 8%（80ms）
//   队列上限 = fps × 40%（400ms）
void GstPlayer::adjustQueueTarget(int queueDepth)
{
    if (!m_bufferingStarted.load()) return;
    
    // ⭐⭐⭐ v9核心修改：使用实际到达帧率EMA而非配置帧率
    // 这样当配置fps=15但实际到达fps=30时，队列目标会基于30fps计算
    double fps = qMax(10.0, m_arrivalRateEma);  // 使用到达帧率EMA，最小10fps
    
    // 🔥🔥🔥 v11.3：动态队列（根据帧率+损坏率）
    int queueMin, queueOptimal, queueMax;
    getQueueSizeByFps(fps, queueMin, queueOptimal, queueMax, m_corruptRatioEma, m_useP2P);
    
    int oldTarget = m_queueTarget;
    
    // ⭐⭐⭐ FPS变化时自动调整队列目标到最佳值
    if (m_queueTarget != queueOptimal && std::abs(m_queueTarget - queueOptimal) > 1) {
        // 渐进调整到最佳值
        int step = (queueOptimal > m_queueTarget) ? 1 : -1;
        m_queueTarget += step;
        m_queueTarget = qBound(queueMin, m_queueTarget, queueMax);
        
        if (m_queueTarget != oldTarget) {
            int delayMs = static_cast<int>(m_queueTarget * 1000.0 / fps);
            qDebug().noquote() << QString("⚡ v9队列调整 | %1→%2帧 | 最佳=%3帧 | 延迟=%4ms | 到达fps=%5 配置fps=%6")
                .arg(oldTarget).arg(m_queueTarget).arg(queueOptimal).arg(delayMs).arg((int)fps).arg((int)m_configFps);
        }
    }
    
    // 计算当前延迟（用于第二道保险判断）
    int currentDelayMs = (fps > 0) ? static_cast<int>(queueDepth * 1000.0 / fps) : 150;
    
    // ⭐⭐⭐ v9.1紧急保护：只在队列=0时触发（避免过早停止消耗导致堆积）
    if (queueDepth == 0 && !m_emergencyHold) {
        m_emergencyHold = true;
        qDebug().noquote() << QString("🛑 v9.1紧急保护 | 队列=0帧 | 停止消耗等待帧到达")
            .arg(currentDelayMs);
    }
    
    // ⭐⭐⭐ 更新队列目标平滑值（用于水位计算）
    m_queueTargetSmooth = m_queueTarget;
}

// ⭐⭐⭐ v9.1核心方案：基于实际到达帧率EMA计算播放速度
// v9.1修复：
//   - 紧急恢复后快速回升（不再慢慢+5%）
//   - 队列堆积时立即追帧（不等堆积到2倍）
//   - 只在队列=0时才真正紧急降速
void GstPlayer::adjustPlaybackRate(int queueDepth)
{
    if (!m_bufferingStarted.load()) return;
    
    // ⭐⭐⭐ v9核心：使用实际到达帧率EMA（已在onNewSample中平滑更新）
    double fps = qMax(10.0, m_arrivalRateEma);  // 到达帧率EMA，最小10fps
    double oldRate = m_playbackRate;
    
    // ⭐⭐⭐ v9.1: 队列深度EMA平滑（系数改小，更快响应）
    if (m_queueDepthEma < 1.0) {
        m_queueDepthEma = queueDepth;  // 初始化
    } else {
        // 系数0.3：更快响应队列变化
        m_queueDepthEma = 0.3 * queueDepth + 0.7 * m_queueDepthEma;
    }
    double smoothQueueDepth = m_queueDepthEma;
    
    // 🔥🔥🔥 v11.3：动态队列（根据帧率+损坏率）
    int queueMin, queueOptimal, queueMax;
    getQueueSizeByFps(fps, queueMin, queueOptimal, queueMax, m_corruptRatioEma, m_useP2P);
    
    // 计算当前延迟
    int currentDelayMs = (fps > 0) ? static_cast<int>(smoothQueueDepth * 1000.0 / fps) : 150;
    
    // 🔥🔥🔥 v11 播放速度控制（更平滑，减少跳动）
    // 核心原则：
    //   1. 速度变化要平滑，避免大幅跳动
    //   2. 根据队列偏离程度线性调整目标速度
    //   3. 最小范围：85%-108%（比之前70%-110%更窄）
    
    // 计算队列偏离度（-1.0 = 空, 0 = 最佳, 1.0 = 满）
    double deviation = 0.0;
    if (queueDepth < queueOptimal) {
        // 队列偏少：deviation 为负数
        deviation = (double)(queueDepth - queueOptimal) / qMax(1, queueOptimal);
    } else if (queueDepth > queueOptimal) {
        // 队列偏多：deviation 为正数
        int range = qMax(1, queueMax - queueOptimal);
        deviation = (double)(queueDepth - queueOptimal) / range;
        if (deviation > 1.0) deviation = 1.0;
    }
    
    // 根据偏离度计算目标速度（更平滑的线性映射）
    // deviation = -1.0 → 目标 85%
    // deviation = 0    → 目标 100%
    // deviation = 1.0  → 目标 108%
    if (deviation < 0) {
        // 队列偏少：减速 (85% - 100%)
        m_targetRate = 1.0 + deviation * 0.15;  // -1.0 → 0.85
    } else {
        // 队列偏多或正常：加速 (100% - 108%)
        m_targetRate = 1.0 + deviation * 0.08;  // 1.0 → 1.08
    }
    
    // 特殊情况：队列完全空 → 紧急保护（但不要跳动太大）
    bool isEmergency = false;
    if (queueDepth == 0) {
        m_targetRate = 0.85;  // 🔥 v11: 85% 而不是 70%，减少跳动
        isEmergency = true;
    }
    
    // ========== 平滑速度调整（始终渐变，不直接跳）==========
    double newRate;
        double delta = m_targetRate - m_playbackRate;
    
    // 🔥 v11: 无论什么情况都平滑过渡，最大每次 ±3%
    double maxChange = 0.03;  // 每次最多变化 3%
    if (delta > maxChange) delta = maxChange;
    else if (delta < -maxChange) delta = -maxChange;
        newRate = m_playbackRate + delta;
    
    // 快速恢复：如果速度太低且队列已恢复，稍微加快恢复
    bool isFastRecover = false;
    if (m_playbackRate < 0.95 && queueDepth >= queueOptimal) {
        delta = m_targetRate - m_playbackRate;
        maxChange = 0.05;  // 恢复时可以快一点
        if (delta > maxChange) delta = maxChange;
        newRate = m_playbackRate + delta;
        isFastRecover = true;
    }
    
    // ========== 边界保护 ==========
    if (newRate < R_MIN) newRate = R_MIN;
    if (newRate > R_MAX) newRate = R_MAX;
    
    // ========== 日志输出（速度变化时）==========
    if (std::abs(newRate - oldRate) > 0.01) {
        QString statusIcon;
        QString statusText;
        
        if (queueDepth == 0) {
            statusIcon = "🛑"; statusText = "紧急";
        } else if (queueDepth <= 2) {
            statusIcon = "⚠️"; statusText = "危险";
        } else if (queueDepth < queueMin) {
            statusIcon = "⬆️"; statusText = "恢复中";
        } else if (queueDepth <= queueMax) {
            statusIcon = "✅"; statusText = "正常";
        } else {
            statusIcon = "🚀"; statusText = "追帧";
        }
        
        int totalDelayMs = currentDelayMs + GST_JITTER_LATENCY;
        QString modeTag = isEmergency ? " ⚡直调" : (isFastRecover ? " ⚡快恢" : "");
        
        qDebug().noquote() << QString("%1 v9.1速率[%2] | 队列=%3帧(最佳%4,范围%5-%6) | 到达=%7fps | 延迟=%8ms | 速度%9%→%10%%11")
            .arg(statusIcon).arg(statusText)
            .arg(queueDepth).arg(queueOptimal).arg(queueMin).arg(queueMax)
            .arg((int)fps)
            .arg(totalDelayMs)
            .arg((int)(oldRate*100)).arg((int)(newRate*100))
            .arg(modeTag);
    }
    
    m_playbackRate = newRate;
}

// ⭐⭐⭐ 自适应渲染定时器回调（双缓冲策略核心）
// v9.3 优化：帧率<15fps时跳过缓冲，直接渲染（避免卡顿）
void GstPlayer::onRenderTick()
{
    GstSample *sample = nullptr;
    int queueDepth = 0;
    qint64 now = QDateTime::currentMSecsSinceEpoch();

    // P2: 240fps 验证日志（每100ms打印一次收帧数）
    if (m_highSpeedMode) {
        m_hsWindowFrameCount++;
        if (m_hsWindowStartMs == 0) m_hsWindowStartMs = now;
        qint64 elapsed = now - m_hsWindowStartMs;
        if (elapsed >= 100) {
            qDebug() << QString("⚡ [240fps] 100ms窗口: 收=%1帧 (期望24帧) 间隔=%2ms")
                .arg(m_hsWindowFrameCount).arg(elapsed);
            m_hsWindowFrameCount = 0;
            m_hsWindowStartMs = now;
        }
    }
    
    if (m_useWebRTC && !m_webrtcConnected.load()) {
        return;
    }
    
    // ⭐⭐⭐ v9.3 低帧率直通模式：帧率<15fps时跳过缓冲，直接渲染
    // 原因：极弱网下缓冲机制会增加延迟和卡顿，不如直接渲染收到的帧
    bool lowFpsMode = (m_arrivalRateEma < 15.0 && m_bufferingStarted.load());
    if (lowFpsMode) {
        // 🔥🔥🔥 v13 修复：弱网模式也需要队列控制！
        // 不再强制重置为100%，让队列积压时能通过加速消耗
        // 之前的问题：重置为100%后队列无法消耗
        
        // 获取队列深度
        QMutexLocker lock(&m_queueMutex);
        int weakQueueDepth = m_frameQueue.size();
        if (!m_frameQueue.isEmpty()) {
            sample = m_frameQueue.takeFirst();
            // 🔥 v11.3: 移除跳帧逻辑！队列积压用速度控制
            // 之前这里清空队列导致跳帧
        }
        lock.unlock();
        
        if (sample) {
            // 🔥🔥🔥 v12 简化：坏帧已在 probe 中被 DROP，这里的帧都是干净的！
            // 浏览器模式：解码前就过滤掉坏帧，解码器永远看不到损坏数据
            
            GstBuffer *buffer = gst_sample_get_buffer(sample);
            GstCaps *caps = gst_sample_get_caps(sample);
            
            // 保存为有效帧
            if (m_lastValidSample) {
                gst_sample_unref(m_lastValidSample);
            }
            m_lastValidSample = gst_sample_ref(sample);
            
            if (buffer && caps) {
                GstStructure *structure = gst_caps_get_structure(caps, 0);
                int width = 0, height = 0;
                gst_structure_get_int(structure, "width", &width);
                gst_structure_get_int(structure, "height", &height);
                
                GstMapInfo map;
                if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
                    if (m_videoSink && width > 0 && height > 0) {
                        QVideoFrameFormat format(QSize(width, height), QVideoFrameFormat::Format_BGRA8888);
                        QVideoFrame frame(format);
                        
                        if (frame.map(QVideoFrame::WriteOnly)) {
                            int srcStride = width * 4;
                            int dstStride = frame.bytesPerLine(0);
                            
                            if (srcStride == dstStride) {
                                memcpy(frame.bits(0), map.data, map.size);
                            } else {
                                for (int y = 0; y < height; y++) {
                                    memcpy(frame.bits(0) + y * dstStride, 
                                           map.data + y * srcStride, srcStride);
                                }
                            }
                            frame.unmap();
                            m_videoSink->setVideoFrame(frame);
                            
                            // 🔥🔥🔥 v11 修复：弱网模式也更新渲染计数器！
                            m_renderFrameCounter++;
                        }
                    }
                    gst_buffer_unmap(buffer, &map);
                }
            }
            if (sample) {
                gst_sample_unref(sample);
            }
        }
        // 🔥🔥🔥 v10.5 修复：如果队列空且有上一帧，显示冻结画面
        else if (m_lastValidSample) {
            GstBuffer *lastBuffer = gst_sample_get_buffer(m_lastValidSample);
            GstCaps *lastCaps = gst_sample_get_caps(m_lastValidSample);
            if (lastBuffer && lastCaps) {
                GstStructure *structure = gst_caps_get_structure(lastCaps, 0);
                int width = 0, height = 0;
                gst_structure_get_int(structure, "width", &width);
                gst_structure_get_int(structure, "height", &height);
                
                GstMapInfo map;
                if (gst_buffer_map(lastBuffer, &map, GST_MAP_READ)) {
                    if (m_videoSink && width > 0 && height > 0) {
                        QVideoFrameFormat format(QSize(width, height), QVideoFrameFormat::Format_BGRA8888);
                        QVideoFrame frame(format);
                        
                        if (frame.map(QVideoFrame::WriteOnly)) {
                            int srcStride = width * 4;
                            int dstStride = frame.bytesPerLine(0);
                            
                            if (srcStride == dstStride) {
                                memcpy(frame.bits(0), map.data, map.size);
                            } else {
                                for (int y = 0; y < height; y++) {
                                    memcpy(frame.bits(0) + y * dstStride, 
                                           map.data + y * srcStride, srcStride);
                                }
                            }
                            frame.unmap();
                            m_videoSink->setVideoFrame(frame);
                        }
                    }
                    gst_buffer_unmap(lastBuffer, &map);
                }
            }
            // 静默冻结，不频繁打印日志
        }
        
        // 低帧率模式下仍然需要定时检测
        if (now - m_lastQualityCheckMs >= 1000) {
            m_lastSecondFps = m_currentSecondFrames;
            m_currentSecondFrames = 0;
            m_lastQualityCheckMs = now;
            
            // 更新 EMA
            if (m_lastSecondFps > 0) {
                m_arrivalRateEma = 0.3 * m_lastSecondFps + 0.7 * m_arrivalRateEma;
            } else {
                m_arrivalRateEma = qMax(5.0, m_arrivalRateEma * 0.5);
            }
            
            qDebug().noquote() << QString("🔴 v9.3低帧率直通 | 收=%1fps EMA=%2fps | 跳过缓冲直接渲染")
                .arg((int)m_lastSecondFps).arg((int)m_arrivalRateEma);
            
            // ⭐⭐⭐ v9.3 防马赛克：低帧率时每秒请求一次关键帧
            // 确保即使网络差，也能定期获得完整的 I 帧
            if (m_webrtcConnected) {
                QMetaObject::invokeMethod(this, "requestKeyFrame", Qt::QueuedConnection);
                qDebug() << "🔑 低帧率模式：请求关键帧(防马赛克)";
            }
            
            // 检查是否恢复到正常模式
            if (m_arrivalRateEma >= 15.0) {
                qDebug().noquote() << QString("🟢 帧率恢复 %1fps >= 15fps | 恢复缓冲模式")
                    .arg((int)m_arrivalRateEma);
            }
        }
        
        return;  // 低帧率模式直接返回，跳过后续缓冲逻辑
    }
    
    // ========== 每秒执行：FPS检测 + 双缓冲策略调整 ==========
    if (now - m_lastQualityCheckMs >= 1000) {
        // 计算上一秒的实际到达帧数
        m_lastSecondFps = m_currentSecondFrames;
        m_currentSecondFrames = 0;  // 重置计数
        m_lastQualityCheckMs = now;
        
        // 🔥🔥🔥 v12.1 计算损坏帧比例（网络质量核心指标）
        m_lastSecondCorruptFrames = m_corruptFrameCount.exchange(0);
        m_lastSecondTotalFrames = m_totalFrameCount.exchange(0);
        static int s_consecutiveCleanSeconds = 0;  // 连续无损坏帧秒数
        
        if (m_lastSecondTotalFrames > 0) {
            double currentCorruptRatio = (double)m_lastSecondCorruptFrames / m_lastSecondTotalFrames;
            
            // 🔥 v12.1 修复：当没有损坏帧时，更快地衰减 EMA
            // 问题：之前 EMA 衰减太慢，导致网络恢复后仍长时间处于弱网模式
            // 解决：当前损坏率=0 时，使用更激进的衰减系数
            if (currentCorruptRatio < 0.01) {
                // 当前几乎没有损坏帧，快速衰减（每秒衰减 50%）
                m_corruptRatioEma = 0.5 * m_corruptRatioEma;
                s_consecutiveCleanSeconds++;
                
                // 如果连续 3 秒无损坏帧，直接清零
                if (s_consecutiveCleanSeconds >= 3) {
                    m_corruptRatioEma = 0.0;
                    s_consecutiveCleanSeconds = 0;
                }
            } else {
                // 有损坏帧，正常 EMA 更新
                m_corruptRatioEma = 0.3 * currentCorruptRatio + 0.7 * m_corruptRatioEma;
                s_consecutiveCleanSeconds = 0;  // 重置连续清洁计数
            }
        } else {
            // 没有帧到达，保持上一秒的值
        }
        
        // ⭐⭐⭐ 关键修复：强制同步 EMA 与实际到达帧率
        // 解决网络断开时 m_arrivalRateEma 不更新导致队列目标错误的问题
        if (m_bufferingStarted.load()) {
            if (m_lastSecondFps == 0) {
                // 网络完全断开：快速衰减 EMA 到最小值
                if (m_arrivalRateEma > 10.0) {
                    double oldEma = m_arrivalRateEma;
                    m_arrivalRateEma = qMax(10.0, m_arrivalRateEma * 0.3);  // 每秒衰减70%，最低10fps
                    qDebug().noquote() << QString("📉 网络断开检测 | EMA衰减 %1→%2fps | 到达=0帧")
                        .arg((int)oldEma).arg((int)m_arrivalRateEma);
                }
                
                // 🔥 5秒无帧自动断开
                int noFpsCount = m_noFpsSeconds.fetch_add(1) + 1;
                qDebug().noquote() << QString("⚠️ 连续无帧 %1/5 秒").arg(noFpsCount);
                if (noFpsCount >= 5 && m_webrtcConnected.load()) {
                    qWarning() << "❌ 连续5秒无帧，自动断开连接";
                    m_srsError.store(true);  // 防止自动重连
                    QMetaObject::invokeMethod(this, "disconnectWebRTC", Qt::QueuedConnection);
                    // 通知QML层
                    QMetaObject::invokeMethod(this, [this]() {
                        emit webrtcStatusChanged("No Frames");
                        emit error("连续5秒无帧，已自动断开");
                    }, Qt::QueuedConnection);
                }
            } else if (m_lastSecondFps > 0 && m_arrivalRateEma > 0) {
                // 有帧到达：重置无帧计数器
                m_noFpsSeconds.store(0);
                // 有帧到达：检查 EMA 与实际到达帧率的偏差
                double ratio = m_lastSecondFps / m_arrivalRateEma;
                if (ratio < 0.3 || ratio > 3.0) {
                    // 偏差超过3倍：立即重置 EMA
                    double oldEma = m_arrivalRateEma;
                    m_arrivalRateEma = m_lastSecondFps;
                    qDebug().noquote() << QString("⚡ EMA强制重置 | %1→%2fps | 偏差=%3x")
                        .arg((int)oldEma).arg((int)m_arrivalRateEma).arg(ratio, 0, 'f', 1);
                }
            }
        }
        
        // 获取当前队列深度用于决策
        {
            QMutexLocker lock(&m_queueMutex);
            queueDepth = m_frameQueue.size();
        }
        
        // ⭐⭐⭐ FPS变化检测（自动唤醒机制）
        // 当实际FPS与配置FPS差异>30%持续3秒，自动重配置
        if (m_bufferingStarted.load() && m_lastSecondFps > 0) {
            detectFpsChange();
        }
        
        // 第一道防线：动态调整队列目标
        adjustQueueTarget(queueDepth);
        
        // 第二道防线：播放速率调整（分段函数 + 导数限制）
        adjustPlaybackRate(queueDepth);
        
        // 第三道防线：推流帧率控制（边缘化触发）
        {
            double W = (m_queueTarget > 0) ? (double)queueDepth / m_queueTarget : 1.0;
            checkPushFpsControl(W);
        }
        
        // ⭐⭐⭐ v9.1状态日志（每秒输出一次）
        if (m_bufferingStarted.load()) {
            // 基于实际到达帧率EMA计算所有指标
            double arrivalFps = qMax(10.0, m_arrivalRateEma);
            
            // 🔥 v11.3：动态队列（根据帧率+损坏率）
            int queueMin, optimalQueue, queueMax;
            getQueueSizeByFps(arrivalFps, queueMin, optimalQueue, queueMax, m_corruptRatioEma, m_useP2P);
            
            int appDelayMs = static_cast<int>(queueDepth * 1000.0 / arrivalFps);
            int totalDelayMs = appDelayMs + GST_JITTER_LATENCY;
            
            // 状态判断（根据动态队列范围）
            QString status;
            if (m_emergencyHold || queueDepth == 0) status = "🛑紧急";
            else if (queueDepth < queueMin) status = "⚠️偏少";
            else if (queueDepth > queueMax) status = "🚀追帧";
            else status = "✅正常";
            
            // 降帧状态
            QString fpsStatus = m_requestedFps > 0 ? QString(" | 📉已降帧→%1fps").arg(m_requestedFps) : "";
            
            // 队列健康诊断（根据动态队列范围）
            QString healthInfo;
            if (queueDepth == 0) healthInfo = " ⚠️队列空";
            else if (queueDepth < queueMin) healthInfo = " ⚠️队列偏少";
            else if (queueDepth > queueMax) healthInfo = " ⚠️队列积压";
            
            qDebug().noquote() << QString("📊 v9.1[%1] | 收=%2fps 到达=%3fps | 队列=%4帧(最佳%5,范围%6-%7) | 速度=%8% | 延迟=%9ms%10%11")
                .arg(status)
                .arg((int)m_lastSecondFps)       // 实际接收fps
                .arg((int)arrivalFps)            // 到达帧率EMA
                .arg(queueDepth)                 // 当前队列
                .arg(optimalQueue)               // 最佳队列
                .arg(queueMin)                   // 最小队列
                .arg(queueMax)                   // 最大队列
                .arg((int)(m_playbackRate*100))  // 播放速度
                .arg(totalDelayMs)               // 总延迟
                .arg(fpsStatus)                  // 降帧状态
                .arg(healthInfo);                // 队列健康诊断
            
            // ⭐ 更新缓冲队列状态（供 QML 显示）
            if (m_bufferSize.load() != queueDepth) {
                m_bufferSize.store(queueDepth);
                emit bufferSizeChanged();
            }
            if (m_bufferTarget.load() != m_queueTarget) {
                m_bufferTarget.store(m_queueTarget);
                emit bufferTargetChanged();
            }
        }
    }
    
    // ========== 取帧逻辑 ==========
    bool lowWaterHold = false;
    {
        QMutexLocker lock(&m_queueMutex);
        queueDepth = m_frameQueue.size();
        
        // 🔥🔥🔥 v9.3 双缓冲策略：等待积累到目标深度再开始播放
        // 核心：用 ~200ms 额外延迟换取流畅体验
        if (!m_bufferingStarted.load()) {
            if (queueDepth >= m_queueTarget) {  // 🔥 v9.3: 等待缓冲完成
                m_bufferingStarted.store(true);
                double fps = m_configFps > 1.0 ? m_configFps : 30.0;
                int delayMs = static_cast<int>(m_queueTarget * 1000.0 / fps) + GST_JITTER_LATENCY;
                qDebug().noquote() << QString("🎬 v9.3缓冲完成 | 队列=%1帧 目标=%2帧 | 延迟≈%3ms @%4fps")
                    .arg(queueDepth).arg(m_queueTarget).arg(delayMs).arg((int)fps);
                
                // 初始化FPS统计
                m_currentSecondFrames = 0;
                m_fpsChangeCounter = 0;
            } else {
                return;  // 继续等待缓冲
            }
        }
        
        // ⭐⭐⭐ v9.1紧急保护解除：只要队列>0就立即恢复（不等水位）
        if (m_emergencyHold && queueDepth > 0) {
            m_emergencyHold = false;
            m_emergencyFpsLowered = false;
            
            qint64 recoveryTime = QDateTime::currentMSecsSinceEpoch() - m_emptyQueueStartMs;
            qDebug().noquote() << QString("✅ v9.1紧急保护解除 | 队列=%1帧 | 立即恢复消耗")
                .arg(queueDepth);
        }
        
        // ⭐⭐⭐ v9.3取帧决策（简化版，对齐copygstream）
        // 简化逻辑：只在队列=0时停止消耗，其他情况正常取帧
        // 播放速度调整已经会根据队列深度自动调节消耗速度
        // 🔥 v9.3: 去掉等待IDR逻辑，所有帧正常消耗
        
        if (m_emergencyHold) {
            // 紧急保护中：停止消耗
            lowWaterHold = true;
        } else if (queueDepth >= 1) {
            // 正常：取帧消耗
            sample = m_frameQueue.takeFirst();
            
            // 保存最后有效帧（用于紧急时重复显示）
            if (m_lastValidSample) {
                gst_sample_unref(m_lastValidSample);
            }
            m_lastValidSample = gst_sample_ref(sample);
        } else if (queueDepth == 0) {
            // ⭐⭐⭐ v11.4 修复：队列频繁空也算作"问题"（网络不稳定的信号）
            if (!m_emergencyHold) {
                m_emergencyHold = true;
                m_emptyQueueCount++;
                
                // 🔥🔥🔥 v11.4: 每 5 次队列空计入 1 次损坏帧（避免过度计数）
                // 原因：偶尔队列空是正常的，频繁才是问题
                if (m_emptyQueueCount % 5 == 0) {
                    m_corruptFrameCount.fetch_add(1);
                    m_totalFrameCount.fetch_add(1);
                    qDebug().noquote() << QString("🔴 v11.4队列频繁空 | 次数=%1 | 损坏率=%2% | 计入问题帧")
                        .arg(m_emptyQueueCount).arg((int)(m_corruptRatioEma * 100));
                } else {
                    qDebug().noquote() << QString("⚠️ v11.4队列=0 | 次数=%1 | 损坏率=%2%")
                        .arg(m_emptyQueueCount).arg((int)(m_corruptRatioEma * 100));
                    }
                
                // 请求关键帧（有助于快速恢复）
                    QMetaObject::invokeMethod(this, "requestKeyFrame", Qt::QueuedConnection);
            }
            lowWaterHold = true;  // 标记使用最后有效帧
        }
    }
    
    // 根据队列深度计算极致平滑间隔
    double smoothInterval = calcSmoothInterval(queueDepth);
    int nextInterval = qRound(smoothInterval);
    if (m_renderTimer->interval() != nextInterval) {
        m_renderTimer->setInterval(nextInterval);
    }
    
    if (sample) {
        // 🔥🔥🔥 v12 简化：坏帧已在 probe 中被 DROP，这里的帧都是干净的！
        GstBuffer *buffer = gst_sample_get_buffer(sample);
        GstCaps *caps = gst_sample_get_caps(sample);
        
        if (buffer && caps) {
            // 🔥🔥🔥 v10超低延迟：PTS 漂移检测 + 定时渲染（保证平滑）
            // 核心：不阻塞线程，而是通过调整渲染间隔来平滑播放
            GstClockTime pts = GST_BUFFER_PTS(buffer);
            qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
            
            if (GST_CLOCK_TIME_IS_VALID(pts)) {
                qint64 ptsMs = pts / GST_MSECOND;  // 纳秒转毫秒
                
                // 首帧：记录 PTS 基准
                if (m_startPts < 0) {
                    m_startPts = ptsMs;
                    m_startSystemTime = nowMs;
                    qDebug().noquote() << QString("🎬 v10 PTS基准设置 | startPts=%1ms | systemTime=%2")
                        .arg(m_startPts).arg(m_startSystemTime);
                }
                
                // 计算漂移量（正值=播放慢了，负值=播放快了）
                qint64 expectedPts = (nowMs - m_startSystemTime) + m_startPts - PTS_OFFSET_MS;
                qint64 drift = ptsMs - expectedPts;
                
                // 🔥 v10平滑策略：不阻塞，而是记录漂移用于间隔调整
                // 大漂移（>200ms）说明网络恢复或严重延迟，重置基准
                if (qAbs(drift) > 200) {
                    m_startPts = ptsMs;
                    m_startSystemTime = nowMs;
                    qDebug().noquote() << QString("⚡ v10 PTS重校准 | drift=%1ms | 重置基准").arg(drift);
                }
                // 其他情况：正常渲染，漂移会被 calcSmoothInterval 的速度调整机制吸收
            }
            
            GstStructure *structure = gst_caps_get_structure(caps, 0);
            int width = 0, height = 0;
            gst_structure_get_int(structure, "width", &width);
            gst_structure_get_int(structure, "height", &height);
            
            GstMapInfo map;
            if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
                // 创建 QVideoFrame 并显示
                if (m_videoSink && width > 0 && height > 0) {
                    QVideoFrameFormat format(QSize(width, height), QVideoFrameFormat::Format_BGRA8888);
                    QVideoFrame frame(format);
                    
                    if (frame.map(QVideoFrame::WriteOnly)) {
                        // 复制 BGRA 数据
                        int srcStride = width * 4;
                        int dstStride = frame.bytesPerLine(0);
                        
                        if (srcStride == dstStride) {
                            memcpy(frame.bits(0), map.data, map.size);
                        } else {
                            // 逐行复制
                            for (int y = 0; y < height; y++) {
                                memcpy(frame.bits(0) + y * dstStride, 
                                       map.data + y * srcStride, 
                                       srcStride);
                            }
                        }
                        
                        frame.unmap();
                        m_videoSink->setVideoFrame(frame);
                        
                        // 渲染帧计数（用于日志）
                        m_renderFrameCounter.fetch_add(1);
                    }
                }
                gst_buffer_unmap(buffer, &map);
            }
        }
        
        gst_sample_unref(sample);
    } else if (lowWaterHold && m_lastValidSample) {
        // ⭐ 紧急保护：使用最后有效帧重复渲染（防止马赛克）
        GstBuffer *buffer = gst_sample_get_buffer(m_lastValidSample);
        GstCaps *caps = gst_sample_get_caps(m_lastValidSample);
        
        if (buffer && caps) {
            GstStructure *structure = gst_caps_get_structure(caps, 0);
            int width = 0, height = 0;
            gst_structure_get_int(structure, "width", &width);
            gst_structure_get_int(structure, "height", &height);
            
            GstMapInfo map;
            if (gst_buffer_map(buffer, &map, GST_MAP_READ)) {
                if (m_videoSink && width > 0 && height > 0) {
                    QVideoFrameFormat format(QSize(width, height), QVideoFrameFormat::Format_BGRA8888);
                    QVideoFrame frame(format);
                    
                    if (frame.map(QVideoFrame::WriteOnly)) {
                        int srcStride = width * 4;
                        int dstStride = frame.bytesPerLine(0);
                        
                        if (srcStride == dstStride) {
                            memcpy(frame.bits(0), map.data, map.size);
                        } else {
                            for (int y = 0; y < height; y++) {
                                memcpy(frame.bits(0) + y * dstStride, 
                                       map.data + y * srcStride, 
                                       srcStride);
                            }
                        }
                        
                        frame.unmap();
                        m_videoSink->setVideoFrame(frame);
                        // 注意：不增加渲染帧计数，因为是重复帧
                    }
                }
                gst_buffer_unmap(buffer, &map);
            }
        }
    }
    // 没有帧也没有备份：保持上一帧显示（什么都不做，画面自然保持）
}