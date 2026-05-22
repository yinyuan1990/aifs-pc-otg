#ifndef WEBRTCCLIENT_H
#define WEBRTCCLIENT_H

#include <QObject>
#include <QByteArray>
#include <QNetworkAccessManager>
#include <QNetworkReply>

// 使用 libdatachannel C API（避免 C++ ABI 问题）
#include <rtc/rtc.h>

/**
 * WebRTC 客户端 - 使用 libdatachannel C API 连接 SRS WHEP
 * 
 * 为什么用 C API？
 * - C++ API 在 vcpkg 编译时有 ABI 不兼容问题
 * - std::string 跨 DLL 边界时会损坏
 * - C API 使用 char* 缓冲区，无此问题
 */
class WebRTCClient : public QObject
{
    Q_OBJECT

public:
    explicit WebRTCClient(QObject *parent = nullptr);
    ~WebRTCClient();

    Q_INVOKABLE void connect(const QString &host, const QString &app, const QString &stream);
    Q_INVOKABLE void disconnect();
    Q_INVOKABLE bool isConnected() const { return m_connected; }
    Q_INVOKABLE QString status() const { return m_status; }

    // 请求关键帧（可从 QML 调用）
    Q_INVOKABLE void requestKeyFrame();

signals:
    void naluReady(const QByteArray &nalu, bool isKeyFrame);
    void statusChanged(const QString &status);
    void connected();
    void disconnected();
    void error(const QString &message);

private:
    void setupPeerConnection();
    void sendOfferToSRS(const QString &sdp);
    void onAnswer(const QString &sdp);
    void processRtpPacket(const char *data, int size);
    void extractNaluFromRtp(const uint8_t *data, size_t size, uint16_t seqNum);
    
    // C API 回调（静态函数，需要 RTC_API 调用约定）
    static void RTC_API onLocalDescription(int pc, const char *sdp, const char *type, void *userData);
    static void RTC_API onStateChange(int pc, rtcState state, void *userData);
    static void RTC_API onGatheringStateChange(int pc, rtcGatheringState state, void *userData);
    static void RTC_API onTrack(int pc, int tr, void *userData);
    static void RTC_API onTrackOpen(int tr, void *userData);
    static void RTC_API onTrackMessage(int tr, const char *data, int size, void *userData);

private:
    int m_pc = -1;           // C API PeerConnection handle
    int m_videoTrack = -1;   // C API Track handle
    int m_dataChannel = -1;  // C API DataChannel handle
    
    QNetworkAccessManager *m_networkManager;
    
    QString m_host;
    QString m_app;
    QString m_stream;
    
    bool m_connected = false;
    QString m_status = "Ready";
    bool m_offerSent = false;
    bool m_initialized = false;  // libdatachannel 是否已初始化
    
    // H.264 解包
    QByteArray m_naluBuffer;
    bool m_waitingForKeyFrame = true;
    bool m_needNewKeyFrame = false;  // 丢帧后需要等待新 IDR
    uint8_t m_currentFuaNaluType = 0;  // 当前 FU-A 分片的 NALU 类型
    
    // 丢包检测
    uint16_t m_lastSeqNum = 0;
    int m_packetCount = 0;
    int m_lostPackets = 0;
    int m_lostSinceKeyFrame = 0;  // 自上次关键帧以来的丢包数
    bool m_fuaInProgress = false;  // 是否正在接收 FU-A 分片
    qint64 m_lastKeyFrameTime = 0;  // 上次关键帧时间
    
    // FU-A 序列号跟踪（检测 NALU 内丢包）
    uint16_t m_fuaStartSeq = 0;     // FU-A 开始时的序列号
    uint16_t m_fuaExpectedSeq = 0;  // 下一个期望的序列号
    bool m_fuaCorrupted = false;    // 当前 FU-A 是否已损坏
    
    // 简单的包重排序缓冲区
    struct RtpPacket {
        uint16_t seq;
        QByteArray data;
    };
    QList<RtpPacket> m_reorderBuffer;
    // ⭐ 增加缓冲区大小：32→64，更好应对网络抖动（高端机内存充足）
    static const int REORDER_BUFFER_SIZE = 64;  // 缓冲区大小
    uint16_t m_nextExpectedSeq = 0;
    bool m_seqInitialized = false;
    
    // ⭐ 丢包统计（用于调试和自适应）
    int m_consecutiveLostPackets = 0;  // 连续丢包计数
    qint64 m_lastPacketTime = 0;       // 上次收包时间（检测网络中断）
    
    void processBufferedPackets();
    void processRtpPayload(const uint8_t *data, size_t size, uint16_t seqNum);
};

#endif // WEBRTCCLIENT_H

