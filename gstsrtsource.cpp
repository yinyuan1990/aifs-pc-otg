//
//  gstsrtsource.cpp
//  Aifs（PC 拉流端）
//
//  方案 B：PC 端独立 SRT 拉流前段实现（解耦，不写进 gstplayer.cpp）。
//

#include "gstsrtsource.h"
#include <QDebug>

GstSrtSource::~GstSrtSource()
{
    // 元素若仍被某个 pipeline 持有，由该 pipeline 负责释放；
    // 这里仅在「未加入 pipeline」的异常路径下兜底释放。
    if (m_srtsrc && !GST_OBJECT_PARENT(m_srtsrc)) {
        gst_object_unref(m_srtsrc);
    }
    if (m_netQueue && !GST_OBJECT_PARENT(m_netQueue)) {
        gst_object_unref(m_netQueue);
    }
    if (m_tsdemux && !GST_OBJECT_PARENT(m_tsdemux)) {
        gst_object_unref(m_tsdemux);
    }
    m_srtsrc = nullptr;
    m_netQueue = nullptr;
    m_tsdemux = nullptr;
    m_h264parse = nullptr;
}

bool GstSrtSource::pluginsAvailable(QString *missing)
{
    QStringList lack;
    GstElementFactory *srt = gst_element_factory_find("srtsrc");
    if (!srt) {
        lack << "srtsrc";
    } else {
        gst_object_unref(srt);
    }
    GstElementFactory *ts = gst_element_factory_find("tsdemux");
    if (!ts) {
        lack << "tsdemux";
    } else {
        gst_object_unref(ts);
    }
    if (!lack.isEmpty()) {
        if (missing) {
            *missing = lack.join(", ");
        }
        return false;
    }
    return true;
}

bool GstSrtSource::build(GstBin *pipeline, GstElement *h264parse, const QString &uri)
{
    if (!pipeline || !h264parse) {
        qCritical() << "[SRT] build 失败：pipeline 或 h264parse 为空";
        return false;
    }
    if (uri.isEmpty()) {
        qCritical() << "[SRT] build 失败：uri 为空";
        return false;
    }

    QString missing;
    if (!pluginsAvailable(&missing)) {
        qCritical() << "[SRT] 缺少 GStreamer 插件:" << missing << "（请安装 gst-plugins-bad）";
        return false;
    }

    m_h264parse = h264parse;
    m_videoLinked = false;

    m_srtsrc = gst_element_factory_make("srtsrc", "srt_src");
    m_netQueue = gst_element_factory_make("queue", "srt_net_queue");
    m_tsdemux = gst_element_factory_make("tsdemux", "srt_tsdemux");
    if (!m_srtsrc || !m_netQueue || !m_tsdemux) {
        qCritical() << "[SRT] 创建 srtsrc/queue/tsdemux 失败";
        if (m_srtsrc) { gst_object_unref(m_srtsrc); m_srtsrc = nullptr; }
        if (m_netQueue) { gst_object_unref(m_netQueue); m_netQueue = nullptr; }
        if (m_tsdemux) { gst_object_unref(m_tsdemux); m_tsdemux = nullptr; }
        return false;
    }

    // ⚠️ 关键：GStreamer 的 URI 解析会把 streamid 里的 '#' 当作 fragment 分隔符丢弃，
    // 导致 srtsrc 实际传给服务器的是「空 streamid」（SRS 日志: srt get empty streamid）。
    // 正确做法：uri 只保留 srt://host:port，streamid 用 srtsrc 独立的 "streamid" 属性原样传入。
    QString baseUri = uri;
    QString streamId;
    const QString kKey = QStringLiteral("streamid=");
    int qpos = uri.indexOf(kKey);
    if (qpos >= 0) {
        streamId = uri.mid(qpos + kKey.size());
        // 去掉 streamid= 前的 '?' 或 '&'，得到纯净的 srt://host:port
        int sep = uri.lastIndexOf(QLatin1Char('?'), qpos);
        if (sep < 0) sep = uri.lastIndexOf(QLatin1Char('&'), qpos);
        baseUri = (sep >= 0) ? uri.left(sep) : uri.left(qpos);
    }

    // srtsrc 作为 caller 主动连服务器（SRS srt_server）。
    // latency 是 SRT 抗丢包缓冲（≈协议层缓冲窗口，越大越抗丢包但延迟越高）。
    // ⭐ 取舍（2026-06-23 改）：用户实测端到端延迟达 ~3s、远高于 P2P/SRS，要求「先压延迟」。
    //   场景为同城（iOS/SRS/PC 均国内），SRT 的"抗跨国大丢包"优势用不上，没必要给大 latency。
    //   故从 700 下调到 300ms（同城 RTT 通常 <30ms，300ms 已远够重传余量）。
    //   碎花改交给：tsdemux 关 skew（下方方案一）+ iOS 端缩短关键帧间隔（治本，需 iOS 配合）。
    //   若同城网络仍有明显丢包碎花，可在 300~450 间上调权衡。
    g_object_set(m_srtsrc,
                 "uri", baseUri.toUtf8().constData(),
                 "latency", 300,
                 nullptr);

    // streamid 单独设置，原样传入（含 #!::r=live/...,m=request），不做任何 URL 编码。
    if (!streamId.isEmpty()) {
        g_object_set(m_srtsrc,
                     "streamid", streamId.toUtf8().constData(),
                     nullptr);
        qDebug() << "[SRT] baseUri=" << baseUri << " streamid=" << streamId;
    } else {
        qWarning() << "[SRT] ⚠️ 未从 uri 解析到 streamid，uri=" << uri;
    }

    // 治「滑动碎花」：在 srtsrc 与 tsdemux 之间插入缓冲队列。
    // 根因——srtsrc(SRT 协议层突发) 直冲 tsdemux/解码器，稳态 UNDERRUN(decode) 每秒 ~60 次
    //   （每帧解码队列都空），滑动高码率时解码不连续 → 碎花。
    // 队列只平滑突发、不丢数据（网络源 leaky=0），缓冲的是 H.264 码流（解码前），
    //   不引入额外可感延迟（端到端延迟由 srtsrc latency / 应用层队列统筹）。
    // ⭐ 压延迟（2026-06-23）：300ms→120ms。这一级只为「平滑 SRT 突发」，
    //   120ms 足够吸收 tsdemux 一批 TS 包的抖动，又比 300ms 砍掉 180ms 端到端延迟。
    //   仍 leaky=0 不丢数据（网络源），缓冲的是解码前 H.264 码流。
    g_object_set(m_netQueue,
                 "max-size-time", (guint64)120000000ULL,  // 120ms（原 300ms，压延迟）
                 "max-size-buffers", (guint)0,            // 不按帧数限制
                 "max-size-bytes", (guint)0,              // 不按字节限制
                 "leaky", (gint)0,                        // 0=不丢（网络源必须不丢）
                 nullptr);

    // 治「滑动碎花」方案一：关闭 tsdemux 对 live 输入的时钟 skew 校正。
    //   根因——tsdemux 对 live 输入默认开 skew 校正：当发送端(iOS)与 PC 本地时钟漂移时，
    //   它会主动丢/挤帧来对齐时钟。iOS 快速滑动→码率突变、PCR 间隔波动，正好触发该机制
    //   → 丢帧 → 解码不连续 → 碎花。我们拉流后续不依赖严格时钟（只管平滑显示），
    //   关掉它可避免「为对齐时钟而丢帧」。
    //   兼容性：GStreamer ≥1.28 用 "skew-corrections"=false；旧版本用 "ignore-pcr"=true。
    //   两者都按属性存在性设置，自动适配本机 GStreamer 版本，缺失则跳过（不报错）。
    auto setBoolPropIfExists = [](GstElement *el, const char *prop, gboolean val) -> bool {
        if (!el) return false;
        GParamSpec *spec = g_object_class_find_property(G_OBJECT_GET_CLASS(el), prop);
        if (!spec) return false;
        g_object_set(el, prop, val, nullptr);
        return true;
    };
    bool setSkew = setBoolPropIfExists(m_tsdemux, "skew-corrections", FALSE);  // GStreamer ≥1.28
    bool setIgnorePcr = setBoolPropIfExists(m_tsdemux, "ignore-pcr", TRUE);    // GStreamer <1.28
    qDebug() << "[SRT] tsdemux 关闭时钟 skew 校正（治滑动碎花）："
             << "skew-corrections=false 已设=" << setSkew
             << "ignore-pcr=true 已设=" << setIgnorePcr;

    // tsdemux：仅取视频，pad 动态出现时回调链接。
    g_signal_connect(m_tsdemux, "pad-added", G_CALLBACK(onTsDemuxPadAdded), this);

    gst_bin_add_many(pipeline, m_srtsrc, m_netQueue, m_tsdemux, nullptr);

    // srtsrc → queue → tsdemux（两段静态 link）
    if (!gst_element_link(m_srtsrc, m_netQueue) ||
        !gst_element_link(m_netQueue, m_tsdemux)) {
        qCritical() << "[SRT] 链接 srtsrc → queue → tsdemux 失败";
        gst_bin_remove(pipeline, m_srtsrc);
        gst_bin_remove(pipeline, m_netQueue);
        gst_bin_remove(pipeline, m_tsdemux);
        m_srtsrc = nullptr;
        m_netQueue = nullptr;
        m_tsdemux = nullptr;
        return false;
    }

    qDebug() << "[SRT] 前段就绪：srtsrc → queue(300ms) → tsdemux（等待视频 pad）uri=" << uri;
    return true;
}

void GstSrtSource::onTsDemuxPadAdded(GstElement *demux, GstPad *pad, gpointer userData)
{
    Q_UNUSED(demux);
    GstSrtSource *self = static_cast<GstSrtSource *>(userData);
    if (!self || !self->m_h264parse) {
        return;
    }
    if (self->m_videoLinked) {
        return;  // 只接第一路视频
    }

    // 判断该 pad 是否为视频（video/x-h264 或 video/mpegts 解出的视频 PES）。
    GstCaps *caps = gst_pad_get_current_caps(pad);
    if (!caps) {
        caps = gst_pad_query_caps(pad, nullptr);
    }
    bool isVideo = false;
    if (caps) {
        const GstStructure *s = gst_caps_get_structure(caps, 0);
        const gchar *name = s ? gst_structure_get_name(s) : nullptr;
        if (name && (g_str_has_prefix(name, "video/") || g_strrstr(name, "h264"))) {
            isVideo = true;
        }
        gst_caps_unref(caps);
    } else {
        // 无 caps 时按 pad 名兜底（tsdemux 视频 pad 通常名为 video_xxxx）。
        gchar *padName = gst_pad_get_name(pad);
        if (padName && g_str_has_prefix(padName, "video")) {
            isVideo = true;
        }
        if (padName) g_free(padName);
    }

    if (!isVideo) {
        qDebug() << "[SRT] tsdemux 非视频 pad，忽略";
        return;
    }

    GstPad *sinkPad = gst_element_get_static_pad(self->m_h264parse, "sink");
    if (!sinkPad) {
        qCritical() << "[SRT] 取 h264parse sink pad 失败";
        return;
    }
    if (gst_pad_is_linked(sinkPad)) {
        gst_object_unref(sinkPad);
        return;
    }

    GstPadLinkReturn ret = gst_pad_link(pad, sinkPad);
    gst_object_unref(sinkPad);

    if (ret == GST_PAD_LINK_OK) {
        self->m_videoLinked = true;
        qDebug() << "[SRT] ✅ tsdemux 视频 pad → h264parse 链接成功";
    } else {
        qCritical() << "[SRT] ❌ tsdemux → h264parse 链接失败, ret=" << ret;
    }
}

void GstSrtSource::teardown(GstBin *pipeline)
{
    Q_UNUSED(pipeline);
    // 元素由 pipeline 持有，会随 pipeline 置 NULL + unref 一并销毁。
    // 这里只置空指针，避免悬空（与 GstPlayer::destroyPipeline 中其它元素一致处理）。
    m_srtsrc = nullptr;
    m_netQueue = nullptr;
    m_tsdemux = nullptr;
    m_h264parse = nullptr;
    m_videoLinked = false;
}
