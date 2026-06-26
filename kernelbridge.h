#ifndef KERNELBRIDGE_H
#define KERNELBRIDGE_H

// ⭐ 内核测试桥（QWebChannel）：把 PC 端现有的 P2P 信令（WebSocketClient/STOMP）
//   桥接给 WebEngine 里的 JS。JS 只负责 RTCPeerConnection（Chromium 内核），
//   信令收发 100% 复用现有 C++ 逻辑，消息格式与 GStreamer P2P 完全一致。
//   仅「内核测试」用，HAVE_WEBENGINE 时才编译。

#include <QObject>
#include <QString>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonDocument>

class WebFrameSource;

class KernelBridge : public QObject
{
    Q_OBJECT
public:
    explicit KernelBridge(QObject *parent = nullptr);

    // ⭐ 截图/慢放数据源：网页内核模式下，JS 把帧 JPEG 回传到这里 → 落盘到 WebFrameSource。
    void setWebFrameSource(WebFrameSource *src) { m_webFrameSource = src; }
    WebFrameSource *webFrameSource() const { return m_webFrameSource; }

    // JS 拉取配置（P2P 用）
    Q_INVOKABLE QString getIceServers() const;     // 返回 JSON 字符串（数组）
    Q_INVOKABLE QString getPairedDeviceId() const; // iOS 设备 id（toDevice）
    Q_INVOKABLE QString getUsername() const;        // 本端 username（fromDevice）

    // JS 发送 P2P 信令（参数与 WebSocketClient::sendWebRTCSignaling 对齐）
    Q_INVOKABLE void sendSignal(const QString &type, const QString &toDevice,
                                const QString &sdpType = QString(), const QString &sdp = QString(),
                                const QString &candidate = QString(), const QString &sdpMid = QString(),
                                int sdpMLineIndex = 0);

    // JS 告知：内核测试开始（C++ 据此断开 GStreamer 的 P2P，让出 username 会话）/ 结束
    Q_INVOKABLE void notifyTestStarted();
    Q_INVOKABLE void notifyTestStopped();

    // ⭐ JS 请求 iOS 补一个关键帧（花屏恢复用，轻量、不重建 PeerConnection）。
    //   走与 GStreamer 端 requestKeyframeWithFallback 完全相同的 WebSocket 通道：
    //   发 CONFIG_UPDATE(ptype=request_keyframe) 到 /topic/device/<id>/config，iOS P0-1 监听并补 IDR。
    Q_INVOKABLE void requestKeyframe();

    // ⭐ JS 周期上报当前接收帧率（内核作主播放器时，GStreamer receiveFps 恒为 0，
    //   PC 拉流心跳无法据此发出 → iOS 收不到「PC 已连接」。改由 webview 上报 fps，
    //   QML 的 viewerHeartbeatTimer 在内核模式据此发 VIEWER_HEARTBEAT）。
    Q_INVOKABLE void notifyViewerFps(int fps);

    // ⭐ JS 回传一帧 JPEG（base64，不含 data: 前缀）给 C++ 落盘，供截图/慢放复用。
    //   frameIndex 用 double（JS number → qint64 经 QVariant 可能精度问题，统一走 double）。
    //   frameIndex<0 时由 WebFrameSource 自增分配。返回实际写入的全局帧索引（-1 失败）。
    Q_INVOKABLE double pushCaptureFrame(const QString &base64Jpeg, double frameIndex = -1);

    // ⭐ JS 通知复位帧源（切流/停播时清空 webframes，避免旧索引串号）。
    Q_INVOKABLE void resetCaptureFrames();

    // ⭐ JS 转发滚轮事件给 QML（网页内核作主播放器时 webview 吃掉了滚轮，
    //   原 videoZoomArea.onWheel 收不到）。deltaY>0 放大、<0 缩小；mouseX/Y 为
    //   相对 webview 的像素坐标（= 相对 videoContainer），供 QML 做聚焦缩放。
    Q_INVOKABLE void notifyWheelZoom(double deltaY, double mouseX, double mouseY);

    // JS 打日志到 C++（便于在 phoenix_log.txt 里看）
    Q_INVOKABLE void log(const QString &msg);

signals:
    // 转发 iOS 收到的 P2P 信令给 JS（原始 JSON 字符串，JS 自行解析）
    void signalReceived(const QString &jsonStr);

    // 通知 QML：内核测试请求断开/恢复 GStreamer P2P（在 MainPage.qml 里接）
    void requestStopGstP2P();
    void requestResumeGstP2P();

    // ⭐ 通知 QML：webview 当前接收帧率（fps>0 表示画面在播）。MainPage 据此发拉流心跳。
    void viewerFpsChanged(int fps);

    // ⭐ 通知 QML：webview 内滚轮缩放请求（MainPage 复用 GStreamer 同款缩放/偏移数学）。
    void wheelZoomRequested(double deltaY, double mouseX, double mouseY);

private slots:
    void onWebrtcSignalingReceived(const QJsonObject &message);

private:
    WebFrameSource *m_webFrameSource = nullptr;
};

#endif // KERNELBRIDGE_H
