#include "kernelbridge.h"
#include "websocketclient.h"
#include "httpclient.h"
#include "webframesource.h"
#include <QDebug>
#include <QDateTime>

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

void KernelBridge::requestKeyframe()
{
    // ⭐ 与 MainPage.qml 的 sendConfigUpdate("request_keyframe", ...) 同口径：
    //   发 CONFIG_UPDATE 到 /topic/device/<deviceId>/config，iOS P0-1 监听 ptype=request_keyframe 补 IDR。
    //   花屏恢复轻量通道，替代旧的 rebuildPC / 重发 Offer。
    const QString deviceId = HttpClient::instance()->currentDeviceId();
    if (deviceId.isEmpty()) {
        qDebug() << "[KernelBridge] requestKeyframe 跳过：deviceId 为空";
        return;
    }
    const qint64 nowMs = QDateTime::currentMSecsSinceEpoch();

    QJsonObject config;
    config["device_id"] = deviceId;
    config["ptype"]     = "request_keyframe";
    config["cmd"]       = "request_keyframe";
    config["ts"]        = QString::number(nowMs);

    QJsonObject notification;
    notification["type"]      = "CONFIG_UPDATE";
    notification["deviceId"]  = deviceId;
    notification["config"]    = config;
    notification["operator"]  = HttpClient::instance()->getSavedUsername();
    notification["timestamp"] = static_cast<double>(nowMs);

    const QString destination = "/topic/device/" + deviceId + "/config";
    WebSocketClient::instance()->sendMessageJson(
        destination, QString::fromUtf8(QJsonDocument(notification).toJson(QJsonDocument::Compact)));
    qDebug() << "[KernelBridge] 已发送 WebSocket request_keyframe →" << destination;
}

void KernelBridge::notifyViewerFps(int fps)
{
    // 仅转发给 QML（MainPage 的拉流心跳定时器据此在内核模式发 VIEWER_HEARTBEAT）。
    emit viewerFpsChanged(fps);
}

double KernelBridge::pushCaptureFrame(const QString &base64Jpeg, double frameIndex)
{
    if (!m_webFrameSource) return -1.0;
    if (base64Jpeg.isEmpty()) return -1.0;

    // JS 传来的是纯 base64（已去掉 data:image/jpeg;base64, 前缀）。
    const QByteArray jpeg = QByteArray::fromBase64(base64Jpeg.toLatin1());
    if (jpeg.isEmpty()) return -1.0;

    const qint64 idx = m_webFrameSource->pushJpegFrame(jpeg, static_cast<qint64>(frameIndex));
    return static_cast<double>(idx);
}

void KernelBridge::resetCaptureFrames()
{
    if (m_webFrameSource) m_webFrameSource->reset();
}

void KernelBridge::notifyWheelZoom(double deltaY, double mouseX, double mouseY)
{
    emit wheelZoomRequested(deltaY, mouseX, mouseY);
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
