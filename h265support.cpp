#include "h265support.h"
#include "p2ploguploader.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QFile>
#include <QMutex>
#include <QTextStream>
#include <QThreadPool>
#include <atomic>

#include <gst/rtp/gstrtpbuffer.h>

namespace {

// 会话开关（GstPlayer 主线程写、日志线程读；简单标志用 atomic 即可）
std::atomic<bool> g_h265Active{false};

// ---------- 独立日志文件 h265_diag.txt（写盘走独立单线程池，FIFO 保序，不阻塞调用线程） ----------
QThreadPool* h265LogPool() {
    static QThreadPool* pool = []() {
        auto* p = new QThreadPool();
        p->setMaxThreadCount(1);
        return p;
    }();
    return pool;
}

QMutex g_h265LogMutex;
QFile* g_h265LogFile = nullptr;
QTextStream* g_h265LogStream = nullptr;

void h265LogWrite(const QString &timestamp, const QString &msg) {
    QMutexLocker locker(&g_h265LogMutex);
    if (!g_h265LogFile) {
        const QString logPath = QCoreApplication::applicationDirPath() + "/h265_diag.txt";
        g_h265LogFile = new QFile(logPath);
        if (g_h265LogFile->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
            g_h265LogStream = new QTextStream(g_h265LogFile);
            *g_h265LogStream << "========== H265 (HEVC) P2P 专项诊断 ==========" << Qt::endl;
            *g_h265LogStream << "启动时间: " << QDateTime::currentDateTime().toString("yyyy-MM-dd HH:mm:ss") << Qt::endl;
            *g_h265LogStream << "说明: 仅 H265 会话写本文件；H264 会话日志在 p2p_diag.txt/sh.txt（互不混写）。" << Qt::endl;
            *g_h265LogStream << "上报前缀: pc-gstream-p2p-h265（总后台可与 H264 分开下载对比卡顿）" << Qt::endl;
            *g_h265LogStream << "==============================================" << Qt::endl;
        } else {
            delete g_h265LogFile;
            g_h265LogFile = nullptr;
            return;
        }
    }
    if (g_h265LogStream) {
        *g_h265LogStream << "[" << timestamp << "] " << msg << Qt::endl;
        g_h265LogStream->flush();
    }
}

bool setBoolIfExists(GstElement *elem, const char *prop, gboolean value) {
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(elem), prop)) {
        g_object_set(elem, prop, value, nullptr);
        return true;
    }
    return false;
}

bool setIntIfExists(GstElement *elem, const char *prop, int value) {
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(elem), prop)) {
        g_object_set(elem, prop, value, nullptr);
        return true;
    }
    return false;
}

// H265 NAL 类型：(首字节 >> 1) & 0x3F。IRAP = 16~21（BLA 16-18 / IDR 19-20 / CRA 21）
inline bool isIrapNal(guint8 firstByte) {
    const guint8 t = (firstByte >> 1) & 0x3F;
    return t >= 16 && t <= 21;
}

// ---------- 收流入口探针状态（每次 createDepay 重置；探针回调在流线程，全用 atomic） ----------
std::atomic<int>  g_rtpInCount{0};       // depay sink 收到的 RTP 包数
std::atomic<int>  g_depayOutCount{0};    // depay src 输出的 buffer 数
std::atomic<bool> g_irapSeen{false};     // 是否见过 IRAP(16~21)
std::atomic<bool> g_paramSetSeen{false}; // 是否见过 VPS(32)/SPS(33)/PPS(34)
std::atomic<bool> g_waitFallbackDone{false}; // wait-for-keyframe 兜底是否已触发
std::atomic<guint64> g_nalTypeMask{0};   // 出现过的 NAL 类型位图（type 0~63 → bit）

QString nalTypeName(int t) {
    switch (t) {
        case 19: return QStringLiteral("IDR_W_RADL(19)");
        case 20: return QStringLiteral("IDR_N_LP(20)");
        case 21: return QStringLiteral("CRA(21)");
        case 16: case 17: case 18: return QString("BLA(%1)").arg(t);
        case 32: return QStringLiteral("VPS(32)");
        case 33: return QStringLiteral("SPS(33)");
        case 34: return QStringLiteral("PPS(34)");
        case 35: return QStringLiteral("AUD(35)");
        case 39: case 40: return QString("SEI(%1)").arg(t);
        case 48: return QStringLiteral("AP(48)");
        case 49: return QStringLiteral("FU(49)");
        default: return QString::number(t);
    }
}

QString nalMaskToString(guint64 mask) {
    QStringList parts;
    for (int t = 0; t < 64; ++t) {
        if (mask & (G_GUINT64_CONSTANT(1) << t)) parts << nalTypeName(t);
    }
    return parts.join(", ");
}

void noteNalType(int t, QStringList *firstPacketDetail) {
    g_nalTypeMask.fetch_or(G_GUINT64_CONSTANT(1) << (t & 0x3F));
    if (t >= 16 && t <= 21) {
        if (!g_irapSeen.exchange(true)) {
            H265Support::log(QString("[收流入口] 🔑 首个 IRAP 关键帧到达! 类型=%1（第%2包）→ depay 应开始输出；若之后仍无[收流]输出=depay 不认该类型")
                                 .arg(nalTypeName(t)).arg(g_rtpInCount.load()));
        }
    }
    if (t >= 32 && t <= 34) g_paramSetSeen.store(true);
    if (firstPacketDetail) *firstPacketDetail << nalTypeName(t);
}

// depay sink 探针：解 RTP 载荷首层 NAL 类型（FU 49/AP 48 解出内层类型）
GstPadProbeReturn rtpInputProbe(GstPad *pad, GstPadProbeInfo *info, gpointer userData) {
    Q_UNUSED(pad);
    GstBuffer *buffer = GST_PAD_PROBE_INFO_BUFFER(info);
    if (!buffer) return GST_PAD_PROBE_OK;

    const int count = g_rtpInCount.fetch_add(1) + 1;
    const bool detail = count <= 20;

    GstRTPBuffer rtp = GST_RTP_BUFFER_INIT;
    if (gst_rtp_buffer_map(buffer, GST_MAP_READ, &rtp)) {
        const guint8 *payload = static_cast<const guint8*>(gst_rtp_buffer_get_payload(&rtp));
        const guint payloadLen = gst_rtp_buffer_get_payload_len(&rtp);
        QStringList types;
        if (payload && payloadLen >= 3) {
            const int outerType = (payload[0] >> 1) & 0x3F;
            if (outerType == 49 && payloadLen >= 3) {
                // FU：第 3 字节 FU header，低 6 位 = 真实类型；S 位=分片首包（只有首包能看到类型边界）
                const int fuType = payload[2] & 0x3F;
                const bool fuStart = (payload[2] & 0x80) != 0;
                if (fuStart) noteNalType(fuType, detail ? &types : nullptr);
                if (detail) types << QString("FU(49)%1→%2").arg(fuStart ? "S" : "").arg(nalTypeName(fuType));
            } else if (outerType == 48) {
                // AP 聚合包：2 字节 payload hdr 后为 [2字节长度 + NALU] 列表
                guint pos = 2;
                while (pos + 2 < payloadLen) {
                    const guint nalLen = (payload[pos] << 8) | payload[pos + 1];
                    pos += 2;
                    if (nalLen == 0 || pos + nalLen > payloadLen) break;
                    noteNalType((payload[pos] >> 1) & 0x3F, detail ? &types : nullptr);
                    pos += nalLen;
                }
                if (detail) types.prepend(QStringLiteral("AP(48)"));
            } else {
                noteNalType(outerType, detail ? &types : nullptr);
            }
        }
        // 前 8 字节 hex（确认打包格式：标准 H265 RTP 应见 62 01 xx(FU) / 60 01(AP)；
        // 若是 00 00 00 01 开头 = 发送端把 Annex-B 字节流直接塞进了 RTP，非标准封装）
        QString hex;
        if (detail && payload) {
            for (guint i = 0; i < qMin<guint>(8u, payloadLen); ++i) {
                hex += QString("%1 ").arg(payload[i], 2, 16, QLatin1Char('0'));
            }
        }
        gst_rtp_buffer_unmap(&rtp);

        if (detail) {
            H265Support::log(QString("[收流入口] RTP包#%1 载荷=%2B NAL=%3 首字节=[%4]")
                                 .arg(count).arg(payloadLen).arg(types.join(" ")).arg(hex.trimmed()));
        }
    }

    if (count % 500 == 0) {
        H265Support::log(QString("[收流入口] 已收 %1 包 | depay输出=%2 | 见过IRAP=%3 见过VPS/SPS/PPS=%4 | NAL类型分布: %5")
                             .arg(count).arg(g_depayOutCount.load())
                             .arg(g_irapSeen.load() ? "是" : "否❌")
                             .arg(g_paramSetSeen.load() ? "是" : "否❌")
                             .arg(nalMaskToString(g_nalTypeMask.load())));
    }

    // 兜底：收了 ≈几秒的包（>250）但 depay 零输出 → 关掉 wait-for-keyframe。
    // 场景：Android MediaCodec 关键帧类型/封装不被 rtph265depay 的等待逻辑识别 → 永远黑屏。
    // 关掉后 h265parse+解码器自会丢弃 sync 前的数据，最多起播瞬间花屏一下。
    if (count > 250 && g_depayOutCount.load() == 0 && !g_waitFallbackDone.exchange(true)) {
        GstElement *depay = static_cast<GstElement*>(userData);
        if (depay) {
            g_object_set(depay, "wait-for-keyframe", FALSE, nullptr);
            H265Support::log(QString("[收流入口] ⚠️ 兜底触发：已收 %1 包但 depay 零输出（wait-for-keyframe 一直没等到它认的关键帧）"
                                     "→ 已动态关闭 wait-for-keyframe，让数据流下去由 h265parse/解码器自行等 sync。"
                                     "见过IRAP=%2 NAL分布: %3")
                                 .arg(count)
                                 .arg(g_irapSeen.load() ? "是(说明depay不认此IRAP类型❗)" : "否(发送端真没发IRAP)")
                                 .arg(nalMaskToString(g_nalTypeMask.load())));
        }
    }
    return GST_PAD_PROBE_OK;
}

} // namespace

namespace H265Support {

void setActive(bool active) {
    const bool prev = g_h265Active.exchange(active);
    if (prev != active) {
        log(active ? QStringLiteral("🎞️ H265 会话开始（日志与上报已切到 h265 独立通道）")
                   : QStringLiteral("🎞️ H265 会话结束（恢复 H264 通道）"));
    }
}

bool isActive() {
    return g_h265Active.load();
}

bool isH265CodecName(const QString &codec) {
    const QString c = codec.trimmed().toLower();
    return c == QLatin1String("h265") || c == QLatin1String("hevc");
}

GstElement* createDepay() {
    GstElement *depay = gst_element_factory_make("rtph265depay", "depay");
    if (!depay) {
        log("❌ rtph265depay 不可用（请检查 gst-plugins-good 安装）");
        return nullptr;
    }
    // 与 rtph264depay 相同的防马赛克配置（属性存在性保护）
    const bool setWait = setBoolIfExists(depay, "wait-for-keyframe", TRUE);
    const bool setReq = setBoolIfExists(depay, "request-keyframe", TRUE);
    const bool setDiscont = setBoolIfExists(depay, "request-keyframe-on-discont", TRUE);
    log(QString("✅ rtph265depay: wait=%1, request=%2, on-discont=%3")
            .arg(setWait).arg(setReq).arg(setDiscont));
    // 新会话重置收流入口诊断状态
    g_rtpInCount.store(0);
    g_depayOutCount.store(0);
    g_irapSeen.store(false);
    g_paramSetSeen.store(false);
    g_waitFallbackDone.store(false);
    g_nalTypeMask.store(0);
    return depay;
}

void attachRtpInputProbe(GstElement *depay) {
    if (!depay) return;
    GstPad *sinkPad = gst_element_get_static_pad(depay, "sink");
    if (!sinkPad) {
        log("⚠️ 收流入口探针挂载失败：depay 无 sink pad");
        return;
    }
    gst_pad_add_probe(sinkPad, GST_PAD_PROBE_TYPE_BUFFER, rtpInputProbe, depay, nullptr);
    gst_object_unref(sinkPad);
    log("✅ 收流入口探针已挂载（depay sink：逐包解析 H265 NAL 类型，定位关键帧识别问题）");
}

void noteDepayOutput() {
    g_depayOutCount.fetch_add(1);
}

GstElement* createParse() {
    GstElement *parse = gst_element_factory_make("h265parse", "parse");
    if (!parse) {
        log("❌ h265parse 不可用（请检查 gst-plugins-bad 安装）");
        return nullptr;
    }
    // 对齐 h264parse 配置：关键帧前插参数集（H265 是 VPS/SPS/PPS）、强制逐帧处理、byte-stream 输出
    g_object_set(parse, "config-interval", 1, nullptr);
    const bool setPassthrough = setBoolIfExists(parse, "disable-passthrough", TRUE);
    const bool setOutputFormat = setIntIfExists(parse, "output-format", 1); // 1=byte-stream
    log(QString("✅ h265parse: config-interval=1, disable-passthrough=%1, output-format(byte-stream)=%2")
            .arg(setPassthrough).arg(setOutputFormat));
    return parse;
}

QStringList decoderPriority(const QString &gpuType) {
    // ⭐ H265 硬解：优先 d3d11h265dec（输出 D3D11 显存，配现有 d3d11download，与能用的 H264 d3d11
    //   路径同款）。
    //   注：早期 not-negotiated 的真因是 webrtcbin 回了 H264 喂进 rtph265depay（数据没到解码器），
    //   已由 transceiver codec-preferences=H265 修复；现在硬解链路可正常协商。
    //   若 d3d11h265dec 因 P010/10-bit 等格式仍协商失败，createPipeline 会自动回退 avdec_h265 软解。
    QStringList list;
    if (gpuType == QLatin1String("NVIDIA")) {
        list << QStringLiteral("d3d11h265dec") << QStringLiteral("nvh265dec");
    } else {
        list << QStringLiteral("d3d11h265dec");
    }
    log(QString("H265 解码器优先级: %1 → 回退 avdec_h265 软解").arg(list.join(" > ")));
    return list;
}

const char* softwareDecoderName() {
    return "avdec_h265";
}

const char* naluCapsString() {
    return "video/x-h265, stream-format=(string)byte-stream, alignment=(string)au";
}

bool isH265EncodingName(const char *encoding) {
    return encoding && (g_ascii_strcasecmp(encoding, "H265") == 0 ||
                        g_ascii_strcasecmp(encoding, "HEVC") == 0);
}

bool hasKeyframeInBuffer(GstBuffer *buf) {
    GstMapInfo map;
    if (!buf || !gst_buffer_map(buf, &map, GST_MAP_READ) || !map.data || map.size < 6) {
        return false;
    }
    const guint8 *data = map.data;
    const gsize size = map.size;

    // 1) Annex-B（start code 00 00 01 / 00 00 00 01）
    for (gsize i = 0; i + 5 < size; ++i) {
        if (data[i] == 0x00 && data[i + 1] == 0x00 &&
            ((data[i + 2] == 0x01) || (data[i + 2] == 0x00 && data[i + 3] == 0x01))) {
            const gsize nalIndex = (data[i + 2] == 0x01) ? (i + 3) : (i + 4);
            if (nalIndex < size && isIrapNal(data[nalIndex])) {
                gst_buffer_unmap(buf, &map);
                return true;
            }
            i = nalIndex;
        }
    }

    // 2) 长度前缀（4 字节大端，hvcC 常见）
    gsize pos = 0;
    while (pos + 4 < size) {
        const guint32 nalLen = (data[pos] << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
        pos += 4;
        if (nalLen == 0 || pos + nalLen > size) break;
        if (isIrapNal(data[pos])) {
            gst_buffer_unmap(buf, &map);
            return true;
        }
        pos += nalLen;
    }

    gst_buffer_unmap(buf, &map);
    return false;
}

void log(const QString &msg) {
    const QString timestamp = QDateTime::currentDateTime().toString("HH:mm:ss.zzz");
    h265LogPool()->start([timestamp, msg]() { h265LogWrite(timestamp, msg); });
    // 上报（总后台开关打开时）：H265 专属前缀，与 H264 的 pc-gstream-p2p 分文件落盘
    P2PLogUploader::instance()->append(QStringLiteral("pc-gstream-p2p-h265"),
                                       "[" + timestamp + "] " + msg);
}

QString uploadPrefix(const QString &base) {
    return g_h265Active.load() ? base + QStringLiteral("-h265") : base;
}

} // namespace H265Support
