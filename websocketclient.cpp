#include "websocketclient.h"
#include <QDebug>
#include <QUrl>
#include <QUrlQuery>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QDateTime>
#include <QNetworkInterface>

WebSocketClient* WebSocketClient::instance()
{
    static WebSocketClient inst;
    return &inst;
}

WebSocketClient::WebSocketClient(QObject *parent)
    : QObject(parent)
    , m_socket(nullptr)
    , m_heartbeatTimer(new QTimer(this))
    , m_reconnectTimer(new QTimer(this))
{
    // ⭐ §53.23.6：socket 由 recreateSocket() 统一创建（每次连接全新对象，不复用）
    recreateSocket();
    
    // 心跳定时器
    m_heartbeatTimer->setInterval(HEARTBEAT_INTERVAL_MS);
    connect(m_heartbeatTimer, &QTimer::timeout, this, &WebSocketClient::onHeartbeatTimeout);
    
    // 重连定时器
    m_reconnectTimer->setInterval(RECONNECT_INTERVAL_MS);
    m_reconnectTimer->setSingleShot(true);
    connect(m_reconnectTimer, &QTimer::timeout, this, &WebSocketClient::onReconnectTimeout);
    
    // ⭐ §53.23：连接看门狗——每次 connectToServer 发起时启动，STOMP CONNECTED 到达时停止。
    //   超时 = 本次尝试挂死（TCP/TLS/WS/STOMP 任一阶段无响应）→ abort 强拆立刻重试。
    m_connectWatchdog = new QTimer(this);
    m_connectWatchdog->setInterval(10000);
    m_connectWatchdog->setSingleShot(true);
    connect(m_connectWatchdog, &QTimer::timeout, this, &WebSocketClient::onConnectWatchdogTimeout);
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

// ⭐ §53.23.6：销毁旧 socket、创建全新 QWebSocket 并接好信号。
//   旧对象先断开全部信号再 deleteLater——防止销毁前迟到的 disconnected/error
//   信号串到新连接的状态机上。
void WebSocketClient::recreateSocket()
{
    if (m_socket) {
        m_socket->disconnect(this);   // 掐断全部信号，迟到事件不再进入本类
        m_socket->abort();
        m_socket->deleteLater();
    }
    m_socket = new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this);
    connect(m_socket, &QWebSocket::connected, this, &WebSocketClient::onConnected);
    connect(m_socket, &QWebSocket::disconnected, this, &WebSocketClient::onDisconnected);
    connect(m_socket, &QWebSocket::textMessageReceived, this, &WebSocketClient::onTextMessageReceived);
    connect(m_socket, &QWebSocket::errorOccurred, this, &WebSocketClient::onError);
}

void WebSocketClient::connectToServer()
{
    if (m_connected) {
        return;
    }
    
    if (m_connecting) {
        return;
    }
    
    // ⭐⭐ §53.23.6（2026-07-30 01:37 日志实锤）：**每次连接用全新的 QWebSocket 对象**。
    //   复用同一对象 abort()→open() 会踩 Qt 内部状态残留：升级握手请求发不完整/不发，
    //   socket 卡死在 ConnectingState，nginx 等 ~10s 收不到完整请求就掐连接
    //   （RemoteHostClosedError）。现场铁证：HTTP 秒回、/ws 路径 curl 探测 0.24s 响应、
    //   进程首连正常，唯独"退出登录 abort 过的 socket 再连"必挂。
    recreateSocket();
    m_manualClose = false;
    
    setConnecting(true);
    setStatusMessage("正在建立 STOMP 连接...");
    
    QString wsUrl = buildWebSocketUrl();
    m_connectStartedAtMs = QDateTime::currentMSecsSinceEpoch();   // §53.23：挂起判定基线
    qDebug() << "[WebSocket] 🔗 发起连接（第" << (m_reconnectAttempts + 1) << "次尝试）";
    m_socket->open(QUrl(wsUrl));
    m_connectWatchdog->start();   // §53.23：10s 内必须到 STOMP CONNECTED，否则强拆重试
}

void WebSocketClient::disconnectFromServer()
{
    // ⭐ §53.22：手动断开（退出登录/解绑）：
    //   ① m_manualClose 标记——迟到的 disconnected/error 信号不得触发自动重连
    //     （人已回登录页，拿旧 token 自动重连毫无意义，还会与下次登录的 open() 抢 socket）；
    //   ② close() 改 abort()——退出登录不需要优雅关闭握手，abort 同步进 Unconnected，
    //     彻底消灭「几秒内重新登录时 open() 撞上 ClosingState」的竞态。
    m_manualClose = true;
    cancelReconnect();
    stopHeartbeat();
    m_connectWatchdog->stop();   // §53.23
    
    if (m_connected) {
        // 发送 DISCONNECT 帧（尽力而为，socket 立刻会被 abort）
        sendStompFrame("DISCONNECT", {});
    }
    
    m_socket->abort();
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

// ⭐ §53.4：本机全部局域网 IPv4（逗号分隔）。WEBRTC_REQUEST 与 PC_PRESENCE 共用同一份。
QString WebSocketClient::localIpv4List() const
{
    QStringList localIps;
    const auto ifaces = QNetworkInterface::allInterfaces();
    for (const auto &iface : ifaces) {
        if (!(iface.flags() & QNetworkInterface::IsUp) ||
            (iface.flags() & QNetworkInterface::IsLoopBack)) continue;
        for (const auto &entry : iface.addressEntries()) {
            const QHostAddress addr = entry.ip();
            if (addr.protocol() != QAbstractSocket::IPv4Protocol) continue;
            if (addr.isLoopback() || addr.isLinkLocal()) continue;
            localIps << addr.toString();
        }
    }
    return localIps.join(",");
}

// P2P WebRTC 信令发送
void WebSocketClient::sendWebRTCSignaling(const QString &type, const QString &toDevice,
                                           const QString &sdpType, const QString &sdp,
                                           const QString &candidate, const QString &sdpMid,
                                           int sdpMLineIndex, const QString &reason,
                                           qlonglong epoch)
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
    // ⭐ §53.25：本轮协商 epoch（设备端记住并在该会话所有信令里回带；双方据此丢过期轮次）
    if (epoch > 0) {
        payload["epoch"] = epoch;
    }
    
    if (type == "WEBRTC_SDP") {
        payload["sdpType"] = sdpType;
        payload["sdp"] = sdp;
    } else if (type == "WEBRTC_ICE") {
        payload["candidate"] = candidate;
        payload["sdpMid"] = sdpMid;
        payload["sdpMLineIndex"] = sdpMLineIndex;
    } else if (type == "WEBRTC_HANGUP") {
        payload["reason"] = reason;
    } else if (type == "WEBRTC_REQUEST") {
        // ⭐ §25.7e 线路预判定：把 PC 全部局域网 IPv4 带给手机端（逗号分隔）。
        //   手机端拿自己的 WiFi IP 与之比网段：同网段=同 WiFi → 建会话就走直连；
        //   否则建会话就 relay-only——一次 ICE 定终身，避免「直连先通后换车再切中继」的 40s 折腾。
        payload["networkType"] = "wifi";   // PC 桌面端视为非蜂窝宽带
        payload["localIps"] = localIpv4List();
        // ⭐ §53.3① / §53.16：**逐条消息**的标识，仅用于两端日志关联（谁的 Offer 对应哪次请求）。
        //   ⚠️ 它**不是"一轮请求"的 id** —— 重发（1.5s 一次）也会换新值。
        //   设备端曾据此判断"新一轮请求就拆旧建新"，结果每次重发都把刚发完 Offer 的会话拆掉，
        //   PC 拿旧 Offer 回的 Answer 落到新会话上、SDP 对不上 → 永远连不通（§53.16 已改回纯时间窗）。
        //   要改成真正的轮次 id，得在 GstPlayer 的 connectP2P/重连处生成并透传，别在这里按消息生成。
        payload["requestId"] = QDateTime::currentMSecsSinceEpoch();
        qDebug() << "[WebRTC信令] REQUEST 附带本机IP:" << payload["localIps"].toString()
                 << "requestId:" << payload["requestId"].toVariant().toLongLong();
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
    
    // ⭐ §53.23：连接生命周期日志（此前 onConnected/onDisconnected/onError 零日志，
    //   「连接为什么要几十秒/为什么连不上」在 phoenix_log 里全是盲区）
    qDebug() << "[WebSocket] ✅ TCP/WS 已连接，发送 STOMP CONNECT";
    
    // 发送 STOMP CONNECT 帧
    QMap<QString, QString> headers;
    headers["accept-version"] = "1.0,1.1,2.0";
    headers["heart-beat"] = "10000,10000";
    
    sendStompFrame("CONNECT", headers);
}

void WebSocketClient::onDisconnected()
{
    stopHeartbeat();
    m_connectWatchdog->stop();   // §53.23：本次尝试已出结果，重试交给 scheduleReconnect
    setConnected(false);
    setConnecting(false);
    
    // ⭐⭐ §53.23 关键修复：断开时必须清本地订阅缓存！
    //   broker 侧的订阅随连接断开全部消失，但本地 m_subscriptions 不清的话，
    //   自动重连成功后 QML 的 subscribe() 会被「已订阅」缓存挡掉直接 return——
    //   新连接上一个 SUBSCRIBE 帧都没发 → 收不到任何消息 → 在线灯灭/无画面，
    //   且不退出登录永远不恢复（只有 disconnectFromServer 才清缓存）。
    //   实测现象就是「断线重连后 PC 变聋，要退出重登、甚至十几分钟才反应过来」。
    m_subscriptions.clear();
    
    QString reason = m_socket->closeReason().isEmpty() ? "连接断开" : m_socket->closeReason();
    qDebug() << "[WebSocket] 🔌 连接断开:" << reason << "(manualClose=" << m_manualClose << ")";
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
    QString errorMsg = m_socket->errorString();
    
    // ⭐ §53.23：错误必须落日志（此前零日志，连接失败原因全是盲区）
    qDebug() << "[WebSocket] ❌ 连接错误:" << error << errorMsg
             << "(state=" << m_socket->state() << ")";
    
    m_connectWatchdog->stop();   // §53.23：本次尝试已出结果，重试交给 scheduleReconnect
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

// ⭐ §53.23：连接看门狗超时——本次尝试在 TCP/TLS/WS/STOMP 某一阶段挂死（此前这条路径
//   没有任何超时，挂起时 m_connecting=true 还会闸死所有恢复入口）→ 强拆，立刻重来。
void WebSocketClient::onConnectWatchdogTimeout()
{
    if (m_connected || m_manualClose) {
        return;
    }
    qDebug() << "[WebSocket] ⏰ 连接看门狗：10s 未建立 STOMP 会话(state=" << m_socket->state()
             << ") → abort 强拆，立即重试";
    m_socket->abort();
    setConnecting(false);
    connectToServer();
}

void WebSocketClient::onReconnectTimeout()
{
    if (m_connected) {
        return;
    }
    // ⭐ §53.23：上一次连接尝试还挂着（connecting=true，TLS/WS 握手无响应）时，
    //   旧逻辑直接 return 且定时器不续 → 若该尝试永远不报错就彻底卡死。
    //   现在：挂起超 20s 判定为死尝试 → abort 强拆后立刻重试；未超则再等 5s 观察。
    if (m_connecting) {
        qint64 hungMs = QDateTime::currentMSecsSinceEpoch() - m_connectStartedAtMs;
        if (hungMs < 20000) {
            qDebug() << "[WebSocket] 重连定时器触发，上次尝试仍在进行(" << hungMs << "ms) → 再等 5s";
            m_reconnectTimer->start(RECONNECT_INTERVAL_MS);
            return;
        }
        qDebug() << "[WebSocket] ⚠️ 连接尝试挂起" << hungMs << "ms 无结果 → abort 强拆重试";
        m_socket->abort();
        setConnecting(false);
    }
    connectToServer();
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
        m_connectWatchdog->stop();   // §53.23：会话建立，看门狗解除
        setConnected(true);
        setConnecting(false);
        setStatusMessage("STOMP 连接成功");
        qDebug() << "[WebSocket] ✅ STOMP CONNECTED（会话建立，通知上层订阅）";
        
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
    // ⭐ §53.22：手动断开（退出登录）后不自动重连——等下次登录 connectToServer 重来
    if (m_manualClose) {
        qDebug() << "[WebSocket] 手动断开状态，不自动重连";
        return;
    }
    // ⭐ §53.23：达到快速重连上限后**不再彻底放弃**（旧行为：10 次×5s 后躺平，
    //   之后无人再救，PC 永久失联直到重启/重登）。改为降频到 15s 一次、无限重试。
    if (m_reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
        setStatusMessage("连接失败，持续重试中...");
        qDebug() << "[WebSocket] ⏳ 已连续失败" << m_reconnectAttempts << "次 → 降频 15s 重试（不放弃）";
        ++m_reconnectAttempts;
        m_reconnectTimer->start(15000);
        return;
    }
    
    ++m_reconnectAttempts;
    qDebug() << "[WebSocket] 🔄 计划第" << m_reconnectAttempts << "次重连（" << RECONNECT_INTERVAL_MS << "ms 后）";
    m_reconnectTimer->start(RECONNECT_INTERVAL_MS);
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
