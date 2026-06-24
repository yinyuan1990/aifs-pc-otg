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

class KernelBridge : public QObject
{
    Q_OBJECT
public:
    explicit KernelBridge(QObject *parent = nullptr);

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

    // ⭐ JS 周期上报当前接收帧率（内核作主播放器时，GStreamer receiveFps 恒为 0，
    //   PC 拉流心跳无法据此发出 → iOS 收不到「PC 已连接」。改由 webview 上报 fps，
    //   QML 的 viewerHeartbeatTimer 在内核模式据此发 VIEWER_HEARTBEAT）。
    Q_INVOKABLE void notifyViewerFps(int fps);

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

private slots:
    void onWebrtcSignalingReceived(const QJsonObject &message);
};

#endif // KERNELBRIDGE_H
