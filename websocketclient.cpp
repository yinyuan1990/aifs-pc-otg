#include "websocketclient.h"
#include <QDebug>
#include <QUrl>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QDateTime>

WebSocketClient* WebSocketClient::instance()
{
    static WebSocketClient inst;
    return &inst;
}

WebSocketClient::WebSocketClient(QObject *parent)
    : QObject(parent)
    , m_socket(new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this))
    , m_heartbeatTimer(new QTimer(this))
    , m_reconnectTimer(new QTimer(this))
{
    // 连接 WebSocket 信号
    connect(m_socket, &QWebSocket::connected, this, &WebSocketClient::onConnected);
    connect(m_socket, &QWebSocket::disconnected, this, &WebSocketClient::onDisconnected);
    connect(m_socket, &QWebSocket::textMessageReceived, this, &WebSocketClient::onTextMessageReceived);
    connect(m_socket, &QWebSocket::errorOccurred, this, &WebSocketClient::onError);
    
    // 心跳定时器
    m_heartbeatTimer->setInterval(HEARTBEAT_INTERVAL_MS);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &WebSocketClient::onHeartbeatTimeout);
    
    // 重连定时器
    m_reconnectTimer->setInterval(RECONNECT_INTERVAL_MS);
    m_reconnectTimer->setSingleShot(true);
    connect(m_reconnectTimer, &QTimer::timeout, this, &WebSocketClient::onReconnectTimeout);
}

void WebSocketClient::setConnectionParams(const QString &wsBaseUrl, const QString &token,
                                           const QString &username, const QString &deviceId)
{
    m_wsBaseUrl = wsBaseUrl;
    m_token = token;
    m_username = username;
    m_deviceId = deviceId;
}

void WebSocketClient::setDeviceId(const QString &deviceId)
{
    m_deviceId = deviceId;
}

QString WebSocketClient::buildWebSocketUrl() const
{
    QUrl url(m_wsBaseUrl);
    QUrlQuery query;
    
    if (!m_token.isEmpty()) {
        query.addQueryItem("token", m_token);
    }
    if (!m_username.isEmpty()) {
        query.addQueryItem("username", m_username);
    }
    // ⭐ 后端需要 pcDeviceId 来写 Redis 在线标记，断线时清除
    if (!m_deviceId.isEmpty()) {
        query.addQueryItem("pcDeviceId", m_deviceId);
    }
    // ⭐ 标识主进程，后端区分 main / subprocess
    query.addQueryItem("clientType", "main");
    
    url.setQuery(query);
    return url.toString();
}

void WebSocketClient::connectToServer()
{
    if (m_connected) {
        return;
    }
    
    if (m_connecting) {
        return;
    }
    
    setConnecting(true);
    setStatusMessage("正在建立 STOMP 连接...");
    
    QString wsUrl = buildWebSocketUrl();
    m_socket->open(QUrl(wsUrl));
}

void WebSocketClient::disconnectFromServer()
{
    cancelReconnect();
    stopHeartbeat();
    
    if (m_connected) {
        // 发送 DISCONNECT 帧
        sendStompFrame("DISCONNECT", {});
    }
    
    m_socket->close();
    m_subscriptions.clear();
    setConnected(false);
    setConnecting(false);
    setStatusMessage("已断开连接");
}

void WebSocketClient::subscribe(const QString &destination)
{
    if (!m_connected) {
        return;
    }
    
    if (m_subscriptions.contains(destination)) {
        return;
    }
    
    int subId = ++m_subscriptionIdCounter;
    m_subscriptions[destination] = subId;
    
    QMap<QString, QString> headers;
    headers["id"] = QString("sub-%1").arg(subId);
    headers["destination"] = destination;
    
    sendStompFrame("SUBSCRIBE", headers);
}

void WebSocketClient::unsubscribe(const QString &destination)
{
    if (!m_subscriptions.contains(destination)) {
        return;
    }
    
    int subId = m_subscriptions.take(destination);
    
    if (m_connected) {
        QMap<QString, QString> headers;
        headers["id"] = QString("sub-%1").arg(subId);
        sendStompFrame("UNSUBSCRIBE", headers);
    }
}

void WebSocketClient::sendMessage(const QString &destination, const QJsonObject &payload)
{
    if (!m_connected) {
        qDebug() << "[WebSocket] sendMessage: 未连接，跳过发送";
        return;
    }
    
    QMap<QString, QString> headers;
    headers["destination"] = destination;
    headers["content-type"] = "application/json";
    
    QJsonDocument doc(payload);
    QString body = QString::fromUtf8(doc.toJson(QJsonDocument::Compact));
    
    qDebug() << "[WebSocket] sendMessage to:" << destination;
    qDebug() << "[WebSocket] payload:" << body;
    
    sendStompFrame("SEND", headers, body);
}

void WebSocketClient::sendMessage(const QString &destination, const QVariantMap &payload)
{
    // 将 QVariantMap 转换为 QJsonObject
    QJsonObject jsonObj = QJsonObject::fromVariantMap(payload);
    sendMessage(destination, jsonObj);
}

void WebSocketClient::sendMessageJson(const QString &destination, const QString &jsonPayload)
{
    if (!m_connected) {
        qDebug() << "[WebSocket] sendMessageJson: 未连接，跳过发送";
        return;
    }
    
    QMap<QString, QString> headers;
    headers["destination"] = destination;
    headers["content-type"] = "application/json";
    
    qDebug() << "[WebSocket] sendMessageJson to:" << destination;
    qDebug() << "[WebSocket] payload:" << jsonPayload;
    
    sendStompFrame("SEND", headers, jsonPayload);
}

// P2P WebRTC 信令发送
void WebSocketClient::sendWebRTCSignaling(const QString &type, const QString &toDevice,
                                           const QString &sdpType, const QString &sdp,
                                           const QString &candidate, const QString &sdpMid,
                                           int sdpMLineIndex, const QString &reason)
{
    if (!m_connected) {
        qWarning() << "[WebRTC信令] 发送失败: 未连接";
        return;
    }
    if (m_username.isEmpty()) {
        qWarning() << "[WebRTC信令] 发送失败: username为空";
        return;
    }
    
    QJsonObject payload;
    payload["type"] = type;
    payload["fromDevice"] = m_username;
    payload["toDevice"] = toDevice;
    payload["timestamp"] = QDateTime::currentMSecsSinceEpoch();
    
    if (type == "WEBRTC_SDP") {
        payload["sdpType"] = sdpType;
        payload["sdp"] = sdp;
    } else if (type == "WEBRTC_ICE") {
        payload["candidate"] = candidate;
        payload["sdpMid"] = sdpMid;
        payload["sdpMLineIndex"] = sdpMLineIndex;
    } else if (type == "WEBRTC_HANGUP") {
        payload["reason"] = reason;
    }
    
    sendMessage("/app/webrtc/signal", payload);
    qDebug() << "[WebRTC信令] 发送" << type << "给" << toDevice;
}

void WebSocketClient::subscribeWebRTCSignaling()
{
    if (m_username.isEmpty()) {
        qWarning() << "[WebRTC信令] 订阅失败: username为空";
        return;
    }
    QString destination = QString("/topic/device/%1/webrtc").arg(m_username);
    subscribe(destination);
    qDebug() << "已订阅 WebRTC 信令频道:" << destination;
}

void WebSocketClient::unsubscribeWebRTCSignaling()
{
    if (m_username.isEmpty()) return;
    QString destination = QString("/topic/device/%1/webrtc").arg(m_username);
    unsubscribe(destination);
    qDebug() << "已取消订阅 WebRTC 信令频道:" << destination;
}

void WebSocketClient::onConnected()
{
    m_reconnectAttempts = 0;
    
    // 发送 STOMP CONNECT 帧
    QMap<QString, QString> headers;
    headers["accept-version"] = "1.0,1.1,2.0";
    headers["heart-beat"] = "10000,10000";
    
    sendStompFrame("CONNECT", headers);
}

void WebSocketClient::onDisconnected()
{
    stopHeartbeat();
    setConnected(false);
    setConnecting(false);
    
    QString reason = m_socket->closeReason().isEmpty() ? "连接断开" : m_socket->closeReason();
    setStatusMessage("STOMP 连接断开: " + reason);
    
    emit stompDisconnected(reason);
    
    // 尝试重连
    scheduleReconnect();
}

void WebSocketClient::onTextMessageReceived(const QString &message)
{
    handleStompMessage(message);
}

void WebSocketClient::onError(QAbstractSocket::SocketError error)
{
    Q_UNUSED(error)
    QString errorMsg = m_socket->errorString();
    
    setConnecting(false);
    setStatusMessage("STOMP 连接失败: " + errorMsg);
    
    emit stompError(errorMsg);
    
    // 尝试重连
    scheduleReconnect();
}

void WebSocketClient::onHeartbeatTimeout()
{
    if (m_connected && m_socket->state() == QAbstractSocket::ConnectedState) {
        // 发送心跳（空行）
        m_socket->sendTextMessage("\n");
    }
}

void WebSocketClient::onReconnectTimeout()
{
    if (!m_connected && !m_connecting) {
        connectToServer();
    }
}

void WebSocketClient::sendStompFrame(const QString &command, const QMap<QString, QString> &headers, 
                                      const QString &body)
{
    if (m_socket->state() != QAbstractSocket::ConnectedState) {
        return;
    }
    
    QString frame;
    frame += command + "\n";
    
    for (auto it = headers.begin(); it != headers.end(); ++it) {
        frame += it.key() + ":" + it.value() + "\n";
    }
    
    frame += "\n";
    
    if (!body.isEmpty()) {
        frame += body;
    }
    
    frame += QChar('\0');  // STOMP 帧结束符
    
    m_socket->sendTextMessage(frame);
}

void WebSocketClient::handleStompMessage(const QString &message)
{
    QStringList lines = message.split('\n');
    if (lines.isEmpty()) return;
    
    QString command = lines[0].trimmed();
    QMap<QString, QString> headers;
    int bodyStartIndex = 1;
    
    // 解析头部
    for (int i = 1; i < lines.size(); ++i) {
        QString line = lines[i];
        if (line.isEmpty()) {
            bodyStartIndex = i + 1;
            break;
        }
        
        int colonIndex = line.indexOf(':');
        if (colonIndex > 0) {
            QString key = line.left(colonIndex);
            QString value = line.mid(colonIndex + 1);
            headers[key] = value;
        }
    }
    
    // 解析消息体
    QString body;
    for (int i = bodyStartIndex; i < lines.size(); ++i) {
        if (i > bodyStartIndex) body += '\n';
        QString line = lines[i];
        // 移除 STOMP 结束符
        line.remove(QChar('\0'));
        body += line;
    }
    
    // 处理不同类型的 STOMP 帧
    if (command == "CONNECTED") {
        setConnected(true);
        setConnecting(false);
        setStatusMessage("STOMP 连接成功");
        
        // 启动心跳
        startHeartbeat();
        
        emit stompConnected();
        
    } else if (command == "MESSAGE") {
        QString destination = headers.value("destination");
        
        // 解析 JSON
        QJsonParseError parseError;
        QJsonDocument doc = QJsonDocument::fromJson(body.toUtf8(), &parseError);
        
        if (parseError.error != QJsonParseError::NoError) {
            return;
        }
        
        QJsonObject json = doc.object();
        
        // 根据 destination 分发消息
        if (destination.contains("/queue/binding") || destination.endsWith("/queue/binding")) {
            // 绑定消息
            emit bindingMessageReceived(json);
            
        } else if (destination.contains("/config")) {
            // 设备配置消息
            emit deviceConfigReceived(json);
            
        } else if (destination.contains("/webrtc")) {
            // P2P WebRTC 信令消息
            QString msgType = json.value("type").toString();
            qDebug() << "[WebRTC信令] 收到" << msgType << "from" << json.value("fromDevice").toString();
            emit webrtcSignalingReceived(json);
        }
        
    } else if (command == "ERROR") {
        QString errorMessage = headers.value("message", "未知错误");
        emit stompError(errorMessage);
    }
}

void WebSocketClient::startHeartbeat()
{
    m_heartbeatTimer->start();
}

void WebSocketClient::stopHeartbeat()
{
    m_heartbeatTimer->stop();
}

void WebSocketClient::scheduleReconnect()
{
    if (m_reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        setStatusMessage("连接失败，请检查网络");
        return;
    }
    
    ++m_reconnectAttempts;
    m_reconnectTimer->start();
}

void WebSocketClient::cancelReconnect()
{
    m_reconnectTimer->stop();
    m_reconnectAttempts = 0;
}

void WebSocketClient::setConnected(bool connected)
{
    if (m_connected != connected) {
        m_connected = connected;
        emit connectedChanged();
    }
}

void WebSocketClient::setConnecting(bool connecting)
{
    if (m_connecting != connecting) {
        m_connecting = connecting;
        emit connectingChanged();
    }
}

void WebSocketClient::setStatusMessage(const QString &msg)
{
    if (m_statusMessage != msg) {
        m_statusMessage = msg;
        emit statusMessageChanged();
    }
}
