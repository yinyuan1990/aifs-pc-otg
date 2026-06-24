#include "kernelbridge.h"
#include "websocketclient.h"
#include "httpclient.h"
#include <QDebug>

KernelBridge::KernelBridge(QObject *parent)
    : QObject(parent)
{
    // 监听现有 WebSocketClient 的 P2P 信令 → 转发给 JS
    connect(WebSocketClient::instance(), &WebSocketClient::webrtcSignalingReceived,
            this, &KernelBridge::onWebrtcSignalingReceived);
}

QString KernelBridge::getIceServers() const
{
    QJsonArray arr = HttpClient::instance()->iceServers();
    return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
}

QString KernelBridge::getPairedDeviceId() const
{
    return HttpClient::instance()->currentDeviceId();
}

QString KernelBridge::getUsername() const
{
    return HttpClient::instance()->getSavedUsername();
}

void KernelBridge::sendSignal(const QString &type, const QString &toDevice,
                              const QString &sdpType, const QString &sdp,
                              const QString &candidate, const QString &sdpMid,
                              int sdpMLineIndex)
{
    WebSocketClient::instance()->sendWebRTCSignaling(
        type, toDevice, sdpType, sdp, candidate, sdpMid, sdpMLineIndex);
}

void KernelBridge::notifyTestStarted()
{
    qDebug() << "[KernelBridge] 内核测试开始 → 请求断开 GStreamer P2P（让出 username 会话）";
    // 确保订阅了 P2P 信令频道（GStreamer 断开后可能取消订阅）
    WebSocketClient::instance()->subscribeWebRTCSignaling();
    emit requestStopGstP2P();
}

void KernelBridge::notifyTestStopped()
{
    qDebug() << "[KernelBridge] 内核测试结束 → 请求恢复 GStreamer P2P";
    emit requestResumeGstP2P();
}

void KernelBridge::notifyViewerFps(int fps)
{
    // 仅转发给 QML（MainPage 的拉流心跳定时器据此在内核模式发 VIEWER_HEARTBEAT）。
    emit viewerFpsChanged(fps);
}

void KernelBridge::log(const QString &msg)
{
    qDebug().noquote() << "[KernelTest JS]" << msg;
}

void KernelBridge::onWebrtcSignalingReceived(const QJsonObject &message)
{
    // 原样转发给 JS（JS 自行按 type/fromDevice/sdpType/sdp/candidate 解析）
    const QString jsonStr = QString::fromUtf8(
        QJsonDocument(message).toJson(QJsonDocument::Compact));
    emit signalReceived(jsonStr);
}
