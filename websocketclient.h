#ifndef WEBSOCKETCLIENT_H
#define WEBSOCKETCLIENT_H

#include <QObject>
#include <QWebSocket>
#include <QTimer>
#include <QMap>
#include <QJsonObject>
#include <QJsonArray>
#include <functional>

/**
 * STOMP WebSocket 客户端
 * 用于与后端建立实时通信连接
 */
class WebSocketClient : public QObject
{
    Q_OBJECT
    Q_PROPERTY(bool connected READ isConnected NOTIFY connectedChanged)
    Q_PROPERTY(bool connecting READ isConnecting NOTIFY connectingChanged)
    Q_PROPERTY(QString statusMessage READ statusMessage NOTIFY statusMessageChanged)

public:
    static WebSocketClient* instance();
    
    // 连接状态
    bool isConnected() const { return m_connected; }
    bool isConnecting() const { return m_connecting; }
    QString statusMessage() const { return m_statusMessage; }
    
    // 设置连接参数
    Q_INVOKABLE void setConnectionParams(const QString &wsBaseUrl, const QString &token,
                                          const QString &username, const QString &deviceId = QString());
    
    // 连接和断开
    Q_INVOKABLE void connectToServer();
    Q_INVOKABLE void disconnectFromServer();
    
    // 订阅/取消订阅
    Q_INVOKABLE void subscribe(const QString &destination);
    Q_INVOKABLE void unsubscribe(const QString &destination);
    
    // 发送消息
    Q_INVOKABLE void sendMessage(const QString &destination, const QJsonObject &payload);
    Q_INVOKABLE void sendMessage(const QString &destination, const QVariantMap &payload);
    Q_INVOKABLE void sendMessageJson(const QString &destination, const QString &jsonPayload);
    
    // 获取当前 deviceId
    QString deviceId() const { return m_deviceId; }
    void setDeviceId(const QString &deviceId);

    // P2P WebRTC 信令发送
    // ⭐ §53.25：epoch = 本轮协商标识（GstPlayer.p2pEpoch()，一轮一个）。>0 时写入 payload，
    //   设备端会话回带、双方按它丢弃过期轮次信令。0 = 不带（老行为）。
    Q_INVOKABLE void sendWebRTCSignaling(const QString &type, const QString &toDevice,
                                          const QString &sdpType = QString(), const QString &sdp = QString(),
                                          const QString &candidate = QString(), const QString &sdpMid = QString(),
                                          int sdpMLineIndex = -1, const QString &reason = QString(),
                                          qlonglong epoch = 0);
    
    // P2P WebRTC 信令频道订阅
    Q_INVOKABLE void subscribeWebRTCSignaling();
    Q_INVOKABLE void unsubscribeWebRTCSignaling();

    // ⭐ §53.4：本机全部局域网 IPv4（逗号分隔）。设备端拿它与自己比 /24 网段，
    //   **在推流前**就能判定"同不同 WiFi"，据此决定走 P2P 还是 SRS（不必先建 WebRTC 会话再看 ICE）。
    //   与 WEBRTC_REQUEST 里带的 localIps 同一份数据，抽出来供 PC_PRESENCE 心跳复用。
    Q_INVOKABLE QString localIpv4List() const;

signals:
    void connectedChanged();
    void connectingChanged();
    void statusMessageChanged();
    
    // STOMP 连接事件
    void stompConnected();
    void stompDisconnected(const QString &reason);
    void stompError(const QString &error);
    
    // 频道消息
    void bindingMessageReceived(const QJsonObject &message);  // /user/queue/binding
    void deviceConfigReceived(const QJsonObject &message);    // /topic/device/{deviceId}/config
    
    // P2P WebRTC 信令消息
    void webrtcSignalingReceived(const QJsonObject &message); // /topic/device/{username}/webrtc

private slots:
    void onConnected();
    void onDisconnected();
    void onTextMessageReceived(const QString &message);
    void onError(QAbstractSocket::SocketError error);
    void onHeartbeatTimeout();
    void onReconnectTimeout();
    void onConnectWatchdogTimeout();   // §53.23：连接看门狗（发起→STOMP CONNECTED 限时）

private:
    explicit WebSocketClient(QObject *parent = nullptr);
    
    // STOMP 帧处理
    void sendStompFrame(const QString &command, const QMap<QString, QString> &headers, 
                        const QString &body = QString());
    void handleStompMessage(const QString &message);
    
    // 心跳
    void startHeartbeat();
    void stopHeartbeat();
    
    // 重连
    void scheduleReconnect();
    void cancelReconnect();
    
    // 状态更新
    void setConnected(bool connected);
    void setConnecting(bool connecting);
    void setStatusMessage(const QString &msg);
    
    // 构建 WebSocket URL
    QString buildWebSocketUrl() const;
    
    // ⭐ §53.23.6：每次连接用全新 QWebSocket（复用 abort 过的对象会卡死在 ConnectingState）
    void recreateSocket();

private:
    QWebSocket *m_socket;
    QTimer *m_heartbeatTimer;
    QTimer *m_reconnectTimer;
    // ⭐ §53.23：连接看门狗。连接路径原本**没有任何超时**——CONNECT 发出后若 CONNECTED
    //   迟迟不来，m_connecting=true 会把 connectToServer/重连定时器全部闸死，挂起无解。
    //   看门狗保证任何一次连接尝试要么 10s 内建立会话，要么被强拆立刻重试。
    QTimer *m_connectWatchdog;
    
    // 连接参数
    QString m_wsBaseUrl;
    QString m_token;
    QString m_username;
    QString m_deviceId;
    
    // 状态
    bool m_connected = false;
    bool m_connecting = false;
    // ⭐ §53.22：手动断开标记（退出登录/解绑）。置位期间迟到的 disconnected/error
    //   不触发自动重连；下次 connectToServer() 时清除。
    bool m_manualClose = false;
    // ⭐ §53.23：本次连接尝试的发起时刻（挂起判定：重连定时器触发时若尝试超 20s 无结果则强拆重试）
    qint64 m_connectStartedAtMs = 0;
    QString m_statusMessage;
    
    // 订阅管理
    QMap<QString, int> m_subscriptions;  // destination -> subscription id
    int m_subscriptionIdCounter = 0;
    
    // 重连计数
    int m_reconnectAttempts = 0;
    static const int MAX_RECONNECT_ATTEMPTS = 10;
    static const int RECONNECT_INTERVAL_MS = 5000;
    static const int HEARTBEAT_INTERVAL_MS = 10000;
};

#endif // WEBSOCKETCLIENT_H

