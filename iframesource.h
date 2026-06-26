#ifndef IFRAMESOURCE_H
#define IFRAMESOURCE_H

#include <QByteArray>
#include <QImage>
#include <QString>
#include <QtGlobal>

class QObject;

// ⭐ 帧源抽象接口（2026-06-24，第三步 P4 纯重构）
//
//   背景：截图(CaptureManager)/慢放(SlowMotionPlayer) 原本直接依赖 GstPlayer 的逐帧
//   H.264 落盘体系。网页内核(Chromium WebEngine)作主播放器时 GStreamer 完全让出，那套
//   帧源为空。为「截图/慢放 UI 完全复用、只换数据源」，把两个 Manager 真正用到的方法/信号
//   抽成此接口，运行时按内核模式注入不同实现：
//     - GStreamer 模式 → GstPlayer（逐帧 H.264 落盘，现状逻辑零改动）
//     - 网页内核模式   → WebFrameSource（webview 帧环 → JPEG 落盘）
//
//   设计约束：
//   1) 方法名沿用 GstPlayer 既有命名（newestH264Frame/readH264Frame/...），这样
//      CaptureManager/SlowMotionPlayer 的调用点一行都不用改，回归风险最低。
//   2) IFrameSource 不是 QObject（Qt 单继承 QObject，GstPlayer 已是 QObject）。
//      帧就绪信号仍由具体类（GstPlayer/WebFrameSource）发出，两者都声明同名信号
//      `h264FrameStored(qint64)`；Manager 在注入时用 asQObject() + SIGNAL 宏连接。
//   3) frameFormat() 标记读出的帧编码：H264 走 GstCaptureDecoder；JPEG 走 QImage 直读。
class IFrameSource
{
public:
    enum class FrameFormat {
        H264,   // Annex-B H.264，需 GstCaptureDecoder 解码
        JPEG    // JPEG，QImage::loadFromData 直读
    };

    virtual ~IFrameSource() = default;

    // 用于信号连接：返回发出 h264FrameStored(qint64) 信号的 QObject。
    virtual QObject *asQObject() = 0;

    // 帧索引范围
    virtual qint64 newestH264Frame() const = 0;
    virtual qint64 oldestH264Frame() const = 0;
    virtual int bufferSize() const = 0;          // 显示侧缓冲深度（live 事件帧滞后补偿）

    // 读取一帧编码数据（按 frameFormat 解释：H264 Annex-B 或 JPEG）
    virtual QByteArray readH264Frame(qint64 frameIndex) const = 0;

    // 帧保护范围（防被滚动清理回收）
    virtual int registerH264ValidRange(qint64 start, qint64 end) = 0;
    virtual void updateH264ValidRange(int id, qint64 start, qint64 end) = 0;
    virtual void unregisterH264ValidRange(int id) = 0;

    // 当前显示帧快照（缩略/首屏兜底）
    virtual QImage grabCurrentFrame() = 0;

    // 请求关键帧（GStreamer 发 PLI；WebFrameSource 可空实现）
    virtual void requestKeyFrame() = 0;

    // 帧目录（仅调试日志用）
    virtual QString h264FrameDirectory() const = 0;

    // 读出帧的编码格式（解码侧据此分支）
    virtual FrameFormat frameFormat() const = 0;
};

#endif // IFRAMESOURCE_H
