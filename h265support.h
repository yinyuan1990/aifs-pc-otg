#ifndef H265SUPPORT_H
#define H265SUPPORT_H

// ============================================================================
// H265 (HEVC) P2P 支持 —— PC 端全部 H265 专属逻辑集中在本文件，与 GstPlayer 的
// H264 主链路解耦（用户要求：新增 H265 不散落在原类里，好维护）。
//
// GstPlayer 只在既有分叉点各加一行分派：
//   - createPipeline：depay/parse/解码器按 codec 从这里取
//   - onWebRTCPadAdded：encoding-name 判断
//   - NALU 存储：关键帧判断（H265 IRAP ≠ H264 IDR type5）
//   - 日志：H265 会话独立文件 + 独立上报前缀（与 H264 分开下载分析）
//
// 日志约定（用户要求 H265/H264 日志分开）：
//   本地：h265_diag.txt（事件） 由 H265Support::log 写；
//        sh.txt/p2p_diag.txt 等既有文件不动（H264 路径无感知）。
//   上报：统一在前缀上加 -h265 后缀（pc-gstream-p2p-h265 / pc-web-p2p-h265），
//        总后台按前缀分文件落盘，可单独下载 H265 卡顿日志。
//
// 环境要求：GStreamer 1.26.6 已确认带 rtph265depay/h265parse/avdec_h265/
//   d3d11h265dec/nvh265dec（本机已验证）。
// ============================================================================

#include <QString>
#include <QStringList>
#include <gst/gst.h>

namespace H265Support {

// ---------- 会话开关（GstPlayer 在 connectP2P/setVideoCodec 时设置） ----------

/// 标记当前 GStreamer 会话是否 H265（供静态日志函数路由上报前缀）
void setActive(bool active);
bool isActive();

/// "h265"（大小写不敏感）→ true
bool isH265CodecName(const QString &codec);

// ---------- GStreamer 元素工厂（与 H264 主链路同参数策略） ----------

/// rtph265depay，同 rtph264depay 配置 wait-for-keyframe / request-keyframe /
/// request-keyframe-on-discont（属性存在性保护）
GstElement* createDepay();

/// h265parse，同 h264parse 配置 config-interval=1（关键帧前插 VPS/SPS/PPS）、
/// disable-passthrough、output-format=byte-stream（属性存在性保护）
GstElement* createParse();

/// 按 GPU 类型的 H265 硬解优先级（对齐 H264 的选择策略；msdk/amf 本机无 H265 版，
/// 统一回落 d3d11h265dec）
QStringList decoderPriority(const QString &gpuType);

/// 软解回退元素名
const char* softwareDecoderName();   // "avdec_h265"

/// NALU 存储 appsink 的 caps 字符串
const char* naluCapsString();        // "video/x-h265, stream-format=byte-stream, alignment=au"

/// pad-added 时 encoding-name 是否 H265/HEVC
bool isH265EncodingName(const char *encoding);

// ---------- 关键帧判断（H265 IRAP：NAL type 16~21，含 IDR 19/20、CRA 21） ----------

/// buffer 内是否含 IRAP（关键帧）。支持 Annex-B 与 4 字节长度前缀两种封装。
bool hasKeyframeInBuffer(GstBuffer *buf);

// ---------- 收流入口诊断（Android H265 黑屏定位：depay 收到什么 NAL？） ----------
//
// 背景（2026-07-08 Android H265 无画面）：协商/ICE/RTP 全通（pad-added 有 H265 pad），
// 但 rtph265depay(wait-for-keyframe=1) 12s 内零输出 = 从未识别出 IRAP。
// iOS(VideoToolbox 硬编发 IDR 19/20) 正常、Android(MediaCodec 部分机型同步帧是 CRA 21)
// 异常 → 疑似 depay 的关键帧判定不认 Android 的关键帧类型。本探针把 depay sink 收到的
// RTP 载荷 NAL 类型直接打进 h265_diag.txt，一次日志即可确诊。

/// 在 depay 的 sink pad 挂 RTP 载荷探针：
///   - 前 20 包逐包打 NAL 类型（FU 49/AP 48 解出内层真实类型）；
///   - 之后每 500 包打一行类型分布汇总 + 是否出现过 IRAP/VPS/SPS/PPS；
///   - 首个 IRAP 到达时醒目打印。
/// 同时自带兜底：收包 >250（≈几秒）而 depay 仍零输出时，把 wait-for-keyframe 关掉
/// （h265parse/解码器自会等 sync，最多起播瞬间花一下，好过永远黑屏），并打日志。
void attachRtpInputProbe(GstElement *depay);

/// gstplayer 的 depay src 探针每输出一个 buffer 调一次（喂给上面兜底判断“零输出”）
void noteDepayOutput();

// ---------- 日志（与 H264 完全分开） ----------

/// H265 专属事件日志：写 exe 目录 h265_diag.txt（后台线程池落盘，不阻塞调用线程），
/// 同时按 pc-gstream-p2p-h265 前缀上报（总后台开关打开时）
void log(const QString &msg);

/// 上报前缀路由：H265 会话时 base → base-h265，否则原样。
/// GstPlayer/KernelBridge 的既有 P2PLogUploader::append 调用点改用此函数取前缀。
QString uploadPrefix(const QString &base);

} // namespace H265Support

#endif // H265SUPPORT_H
