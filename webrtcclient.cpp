#include "webrtcclient.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QDebug>
#include <QTimer>
#include <QDateTime>
#include <QRegularExpression>
#include <QCoreApplication>
#include <QFile>
#include <cstring>
#include <ctime>

#ifdef Q_OS_WIN
#include <windows.h>
#include <excpt.h>

// SEH 辅助函数：在独立函数中调用 rtcInitLogger，避免与 C++ 对象冲突
static bool safeInitLogger(DWORD *exceptionCode) {
    __try {
        rtcInitLogger(RTC_LOG_WARNING, nullptr);
        return true;
    }
    __except(EXCEPTION_EXECUTE_HANDLER) {
        if (exceptionCode) {
            *exceptionCode = GetExceptionCode();
        }
        return false;
    }
}
#endif

// ============================================================================
// libdatachannel C API - 使用 char* 缓冲区，无 ABI 问题
// ============================================================================

WebRTCClient::WebRTCClient(QObject *parent)
    : QObject(parent)
    , m_networkManager(new QNetworkAccessManager(this))
{
    qDebug() << "📦 WebRTCClient 构造开始改动...";
    
    // 延迟初始化 libdatachannel（避免在某些系统上构造时崩溃）
    // rtcInitLogger 会在 connect() 时调用
    m_initialized = false;
    
    qDebug() << "📦 WebRTCClient 构造完成（延迟初始化）";
}

WebRTCClient::~WebRTCClient()
{
    disconnect();
}

void WebRTCClient::connect(const QString &host, const QString &app, const QString &stream)
{
    // 延迟初始化 libdatachannel（首次连接时）
    if (!m_initialized) {
        qDebug() << "🔧 WebRTC: 检查 libdatachannel DLL...";
        
        // 检查 DLL 文件是否存在
        QString appDir = QCoreApplication::applicationDirPath();
        QStringList requiredDlls = {"datachannel.dll", "libcrypto-3-x64.dll", "libssl-3-x64.dll"};
        QStringList missingDlls;
        
        for (const QString &dll : requiredDlls) {
            QString dllPath = appDir + "/" + dll;
            if (!QFile::exists(dllPath)) {
                missingDlls.append(dll);
                qWarning() << "❌ 缺少 DLL:" << dllPath;
            } else {
                qDebug() << "✅ 找到 DLL:" << dll;
            }
        }
        
        if (!missingDlls.isEmpty()) {
            QString errorMsg = "缺少必需的 DLL 文件: " + missingDlls.join(", ");
            qCritical() << errorMsg;
            m_status = errorMsg;
            emit statusChanged(m_status);
            emit error(errorMsg);
            return;
        }
        
        qDebug() << "🔧 WebRTC: 初始化 libdatachannel...";
        
#ifdef Q_OS_WIN
        // 使用 SEH 辅助函数捕获 libdatachannel 初始化时可能的崩溃
        DWORD exceptionCode = 0;
        if (!safeInitLogger(&exceptionCode)) {
            QString errorMsg = QString("libdatachannel 初始化崩溃 (异常代码: 0x%1)，可能是 DLL 不兼容")
                .arg(exceptionCode, 8, 16, QChar('0'));
            qCritical() << "❌" << errorMsg;
            m_status = errorMsg;
            emit statusChanged(m_status);
            emit error(errorMsg);
            return;
        }
#else
        rtcInitLogger(RTC_LOG_WARNING, nullptr);
#endif
        
        m_initialized = true;
        qDebug() << "✅ WebRTC: libdatachannel 初始化成功";
    }
    
    if (m_connected) {
        disconnect();
    }
    
    m_host = host;
    m_app = app;
    m_stream = stream;
    
    m_status = "Connecting...";
    emit statusChanged(m_status);
    
    qDebug() << "WebRTC connecting to:" << host << "/" << app << "/" << stream;
    
    setupPeerConnection();
}

void WebRTCClient::disconnect()
{
    qDebug() << "WebRTC disconnect called";
    
    if (m_videoTrack >= 0) {
        rtcDeleteTrack(m_videoTrack);
        m_videoTrack = -1;
    }
    
    if (m_dataChannel >= 0) {
        rtcDeleteDataChannel(m_dataChannel);
        m_dataChannel = -1;
    }
    
    if (m_pc >= 0) {
        rtcDeletePeerConnection(m_pc);
        m_pc = -1;
    }
    
    m_connected = false;
    m_offerSent = false;
    m_waitingForKeyFrame = true;
    m_needNewKeyFrame = false;
    m_naluBuffer.clear();
    m_fuaInProgress = false;
    m_fuaCorrupted = false;
    m_fuaStartSeq = 0;
    m_fuaExpectedSeq = 0;
    m_lastSeqNum = 0;
    m_packetCount = 0;
    m_lostPackets = 0;
    m_lostSinceKeyFrame = 0;
    m_lastKeyFrameTime = 0;
    m_reorderBuffer.clear();
    m_nextExpectedSeq = 0;
    m_seqInitialized = false;
    
    m_status = "Disconnected";
    emit statusChanged(m_status);
    emit disconnected();
    
    qDebug() << "WebRTC disconnected";
}

void WebRTCClient::setupPeerConnection()
{
    qDebug() << "Creating PeerConnection (C API)...";
    
    // 配置
    rtcConfiguration config;
    memset(&config, 0, sizeof(config));
    config.disableAutoNegotiation = true;
    config.forceMediaTransport = true;
    
    // 创建 PeerConnection
    m_pc = rtcCreatePeerConnection(&config);
    if (m_pc < 0) {
        qWarning() << "Failed to create PeerConnection, error:" << m_pc;
        m_status = "Error";
        emit statusChanged(m_status);
        emit error("Failed to create PeerConnection");
        return;
    }
    qDebug() << "PeerConnection created, handle:" << m_pc;
    
    // 设置用户指针（供回调使用）
    rtcSetUserPointer(m_pc, this);
    
    // 设置回调（只传回调函数，userData 通过 rtcSetUserPointer 设置）
    rtcSetLocalDescriptionCallback(m_pc, onLocalDescription);
    rtcSetStateChangeCallback(m_pc, onStateChange);
    rtcSetGatheringStateChangeCallback(m_pc, onGatheringStateChange);
    rtcSetTrackCallback(m_pc, onTrack);
    
    // 创建 DataChannel 来触发 ICE gathering
    qDebug() << "Creating DataChannel to trigger ICE gathering...";
    m_dataChannel = rtcCreateDataChannel(m_pc, "init");
    if (m_dataChannel < 0) {
        qWarning() << "Failed to create DataChannel, error:" << m_dataChannel;
    } else {
        qDebug() << "DataChannel created, handle:" << m_dataChannel;
    }
    
    // 因为 disableAutoNegotiation = true，需要手动触发 SDP 生成
    qDebug() << "Triggering local description generation...";
    int result = rtcSetLocalDescription(m_pc, "offer");
    qDebug() << "rtcSetLocalDescription result:" << result;
}

// ============================================================================
// C API 回调函数（静态）
// ============================================================================

void RTC_API WebRTCClient::onLocalDescription(int pc, const char *sdp, const char *type, void *userData)
{
    WebRTCClient *self = static_cast<WebRTCClient*>(userData);
    
    qDebug() << "=== Local Description Callback ===";
    qDebug() << "Type:" << type;
    qDebug() << "SDP length:" << strlen(sdp);
    
    // 打印 SDP 内容
    fprintf(stderr, "=== LOCAL SDP ===\n%s\n=== END ===\n", sdp);
    fflush(stderr);
    
    if (self->m_offerSent) {
        qDebug() << "Offer already sent, ignoring";
        return;
    }
    self->m_offerSent = true;
    
    QString sdpStr = QString::fromUtf8(sdp);
    
    // 检查是否有视频 m= 行，如果没有，需要构建视频 SDP
    if (!sdpStr.contains("m=video")) {
        qDebug() << "No video in SDP, constructing video offer...";
        
        // 提取 ICE 凭据
        QString iceUfrag, icePwd, fingerprint;
        
        QRegularExpression ufragRe("a=ice-ufrag:([^\r\n]+)");
        QRegularExpression pwdRe("a=ice-pwd:([^\r\n]+)");
        QRegularExpression fpRe("a=fingerprint:([^\r\n]+)");
        
        auto ufragMatch = ufragRe.match(sdpStr);
        auto pwdMatch = pwdRe.match(sdpStr);
        auto fpMatch = fpRe.match(sdpStr);
        
        if (ufragMatch.hasMatch()) iceUfrag = ufragMatch.captured(1);
        if (pwdMatch.hasMatch()) icePwd = pwdMatch.captured(1);
        if (fpMatch.hasMatch()) fingerprint = fpMatch.captured(1);
        
        qDebug() << "Extracted: ufrag=" << iceUfrag << "pwd=" << icePwd.left(10) + "..." << "fp=" << fingerprint.left(30);
        
        if (iceUfrag.isEmpty() || icePwd.isEmpty() || fingerprint.isEmpty()) {
            qWarning() << "Failed to extract ICE credentials!";
            return;
        }
        
        // 构建视频 SDP
        QString videoSdp = QString(
            "v=0\r\n"
            "o=- %1 1 IN IP4 127.0.0.1\r\n"
            "s=libdatachannel\r\n"
            "t=0 0\r\n"
            "a=group:BUNDLE 0\r\n"
            "a=msid-semantic:WMS *\r\n"
            "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
            "c=IN IP4 0.0.0.0\r\n"
            "a=mid:0\r\n"
            "a=ice-ufrag:%2\r\n"
            "a=ice-pwd:%3\r\n"
            "a=fingerprint:%4\r\n"
            "a=setup:actpass\r\n"
            "a=rtcp-mux\r\n"
            "a=rtcp-rsize\r\n"
            "a=recvonly\r\n"
            "a=rtpmap:96 H264/90000\r\n"
            "a=rtcp-fb:96 nack\r\n"              // 启用 NACK（丢包重传）
            "a=rtcp-fb:96 nack pli\r\n"          // 启用 PLI
            "a=rtcp-fb:96 transport-cc\r\n"      // 传输层拥塞控制
            "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n"
        ).arg(time(nullptr)).arg(iceUfrag).arg(icePwd).arg(fingerprint);
        
        fprintf(stderr, "=== VIDEO SDP ===\n%s\n=== END ===\n", videoSdp.toUtf8().constData());
        fflush(stderr);
        
        // 在主线程发送
        QMetaObject::invokeMethod(self, [self, videoSdp]() {
            self->sendOfferToSRS(videoSdp);
        }, Qt::QueuedConnection);
    } else {
        // SDP 已经包含视频，直接发送
        QMetaObject::invokeMethod(self, [self, sdpStr]() {
            self->sendOfferToSRS(sdpStr);
        }, Qt::QueuedConnection);
    }
}

void RTC_API WebRTCClient::onStateChange(int pc, rtcState state, void *userData)
{
    WebRTCClient *self = static_cast<WebRTCClient*>(userData);
    
    QString stateStr;
    switch (state) {
        case RTC_NEW: stateStr = "New"; break;
        case RTC_CONNECTING: stateStr = "Connecting"; break;
        case RTC_CONNECTED: 
            stateStr = "Connected"; 
            self->m_connected = true;
            QMetaObject::invokeMethod(self, [self]() {
                emit self->connected();
            }, Qt::QueuedConnection);
            break;
        case RTC_DISCONNECTED: stateStr = "Disconnected"; break;
        case RTC_FAILED: stateStr = "Failed"; break;
        case RTC_CLOSED: stateStr = "Closed"; break;
        default: stateStr = "Unknown"; break;
    }
    
    qDebug() << "PeerConnection state:" << stateStr;
    
    QMetaObject::invokeMethod(self, [self, stateStr]() {
        self->m_status = stateStr;
        emit self->statusChanged(self->m_status);
    }, Qt::QueuedConnection);
}

void RTC_API WebRTCClient::onGatheringStateChange(int pc, rtcGatheringState state, void *userData)
{
    qDebug() << "Gathering state changed:" << state;
}

void RTC_API WebRTCClient::onTrack(int pc, int tr, void *userData)
{
    WebRTCClient *self = static_cast<WebRTCClient*>(userData);
    
    qDebug() << "Received remote track, handle:" << tr;
    self->m_videoTrack = tr;
    
    // 启用 RTCP 接收会话（用于发送 NACK 请求丢包重传）
    int result = rtcChainRtcpReceivingSession(tr);
    qDebug() << "rtcChainRtcpReceivingSession result:" << result;
    
    // 设置 track 的用户指针和回调
    rtcSetUserPointer(tr, userData);
    rtcSetOpenCallback(tr, onTrackOpen);      // Track 打开时的回调
    rtcSetMessageCallback(tr, onTrackMessage); // 消息回调
}

void RTC_API WebRTCClient::onTrackOpen(int tr, void *userData)
{
    WebRTCClient *self = static_cast<WebRTCClient*>(userData);
    
    qDebug() << "*** Track is now OPEN, handle:" << tr << "***";
    
    // Track 打开后，立即请求关键帧（不用 QueuedConnection 以减少延迟）
    // 直接在回调线程中请求
    int result = rtcRequestKeyframe(tr);
    // qDebug() << "Immediate PLI on track open, result:" << result;
    
    // 在主线程设置定时器
    QMetaObject::invokeMethod(self, [self]() {
        // 再请求一次以确保
        self->requestKeyFrame();
        
        // ⭐ 每 1 秒自动请求一个关键帧（用于快速恢复，之前是 5 秒太慢）
        // 高端机网络稳定，1秒请求一次不会造成带宽压力
        QTimer *pliTimer = new QTimer(self);
        pliTimer->setInterval(1000);  // 从 5000ms 改为 1000ms
        QObject::connect(pliTimer, &QTimer::timeout, self, [self]() {
            if (self->m_videoTrack >= 0 && self->m_connected && rtcIsOpen(self->m_videoTrack)) {
                // 只有在需要关键帧时才请求（避免不必要的网络开销）
                if (self->m_needNewKeyFrame || self->m_waitingForKeyFrame) {
                    qDebug() << "⏱️ 定时 PLI 请求 (等待关键帧)";
                    rtcRequestKeyframe(self->m_videoTrack);
                }
            }
        });
        pliTimer->start();
    }, Qt::QueuedConnection);
}

void RTC_API WebRTCClient::onTrackMessage(int tr, const char *data, int size, void *userData)
{
    WebRTCClient *self = static_cast<WebRTCClient*>(userData);
    self->processRtpPacket(data, size);
}

// ============================================================================
// SRS WHEP 通信
// ============================================================================

void WebRTCClient::sendOfferToSRS(const QString &sdp)
{
    qDebug() << "Sending offer to SRS, SDP length:" << sdp.length();
    
    QString apiUrl = QString("http://%1:1985/rtc/v1/play/").arg(m_host);
    QString streamUrl = QString("webrtc://%1/%2/%3").arg(m_host, m_app, m_stream);
    
    qDebug() << "API URL:" << apiUrl;
    qDebug() << "Stream URL:" << streamUrl;
    
    QJsonObject json;
    json["api"] = apiUrl;
    json["streamurl"] = streamUrl;
    json["sdp"] = sdp;
    
    QJsonDocument doc(json);
    QByteArray requestData = doc.toJson();
    
    QUrl url(apiUrl);
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    
    QNetworkReply *reply = m_networkManager->post(request, requestData);
    
    QObject::connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        if (reply->error() != QNetworkReply::NoError) {
            QString errMsg = QString("SRS request failed: %1").arg(reply->errorString());
            qWarning() << errMsg;
            m_status = "Error";
            emit statusChanged(m_status);
            emit error(errMsg);
            return;
        }
        
        QByteArray responseData = reply->readAll();
        qDebug() << "SRS response:" << responseData.left(500);
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QJsonObject json = doc.object();
        
        int code = json["code"].toInt(-1);
        if (code != 0) {
            QString errMsg = QString("SRS error code: %1").arg(code);
            qWarning() << errMsg;
            m_status = "Error";
            emit statusChanged(m_status);
            emit error(errMsg);
            return;
        }
        
        QString answerSdp = json["sdp"].toString();
        qDebug() << "Received answer SDP, length:" << answerSdp.length();
        
        onAnswer(answerSdp);
    });
}

void WebRTCClient::requestKeyFrame()
{
    if (m_videoTrack < 0) {
        // qDebug() << "Cannot request keyframe: no video track";
        return;
    }
    
    // 检查 track 是否已打开
    if (!rtcIsOpen(m_videoTrack)) {
        qDebug() << "Track not open yet, will retry in 50ms";
        QTimer::singleShot(50, this, [this]() {
            if (m_videoTrack >= 0) {
                requestKeyFrame();
            }
        });
        return;
    }
    
    // qDebug() << "Requesting keyframe (PLI) for track:" << m_videoTrack;
    int result = rtcRequestKeyframe(m_videoTrack);
    if (result < 0) {
        qDebug() << "rtcRequestKeyframe failed:" << result << ", will retry";
    }
    
    // 如果还在等首帧，快速重试（每 300ms）
    if (m_waitingForKeyFrame) {
        QTimer::singleShot(300, this, [this]() {
            if (m_waitingForKeyFrame && m_videoTrack >= 0) {
                requestKeyFrame();
            }
        });
    }
}

void WebRTCClient::onAnswer(const QString &sdp)
{
    qDebug() << "Processing SRS answer...";
    fprintf(stderr, "=== ANSWER SDP ===\n%s\n=== END ===\n", sdp.toUtf8().constData());
    fflush(stderr);
    
    // 设置远程描述
    int result = rtcSetRemoteDescription(m_pc, sdp.toUtf8().constData(), "answer");
    if (result < 0) {
        qWarning() << "Failed to set remote description, error:" << result;
        
        // 尝试手动添加 candidate
        QRegularExpression candidateRe("a=candidate:([^\r\n]+)");
        QRegularExpression midRe("a=mid:([^\r\n]+)");
        
        auto candidateMatch = candidateRe.match(sdp);
        auto midMatch = midRe.match(sdp);
        
        if (candidateMatch.hasMatch()) {
            QString candidate = "candidate:" + candidateMatch.captured(1);
            QString mid = midMatch.hasMatch() ? midMatch.captured(1) : "0";
            
            qDebug() << "Adding remote candidate manually:" << candidate;
            int addResult = rtcAddRemoteCandidate(m_pc, candidate.toUtf8().constData(), mid.toUtf8().constData());
            qDebug() << "Add candidate result:" << addResult;
        }
    } else {
        qDebug() << "Remote description set successfully!";
    }
    
    m_status = "Streaming";
    emit statusChanged(m_status);
}

// ============================================================================
// RTP 处理
// ============================================================================

void WebRTCClient::processRtpPacket(const char *data, int size)
{
    if (size < 12) return;
    
    const uint8_t *ptr = reinterpret_cast<const uint8_t*>(data);
    
    // 解析 RTP 序列号
    uint16_t seqNum = (ptr[2] << 8) | ptr[3];
    
    m_packetCount++;
    
    // ⭐ 记录收包时间（用于检测网络中断）
    qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (m_lastPacketTime > 0) {
        qint64 gap = now - m_lastPacketTime;
        // 如果超过 500ms 没收到包，可能是网络中断，打印警告
        if (gap > 500) {
            qDebug() << "⚠️ 网络抖动: 包间隔" << gap << "ms（可能导致卡顿）";
        }
    }
    m_lastPacketTime = now;
    
    // 首次收到数据时立即请求关键帧（加速首帧显示）
    if (m_packetCount == 1 && m_waitingForKeyFrame) {
        // qDebug() << "First RTP packet received, requesting keyframe immediately";
        QMetaObject::invokeMethod(this, &WebRTCClient::requestKeyFrame, Qt::QueuedConnection);
    }
    
    // ⭐ 每 30 秒打印一次网络统计（帮助诊断问题）
    static qint64 lastStatTime = 0;
    if (now - lastStatTime >= 30000) {
        lastStatTime = now;
        qDebug() << "📊 [网络统计] 总包数:" << m_packetCount 
                 << "丢包:" << m_lostPackets 
                 << "自上次关键帧丢包:" << m_lostSinceKeyFrame
                 << "重排序缓冲:" << m_reorderBuffer.size();
    }
    
    // 解析 RTP 头
    uint8_t csrcCount = ptr[0] & 0x0F;
    size_t headerSize = 12 + csrcCount * 4;
    
    bool hasExtension = (ptr[0] >> 4) & 0x01;
    if (hasExtension && size > (int)(headerSize + 4)) {
        uint16_t extLength = (ptr[headerSize + 2] << 8) | ptr[headerSize + 3];
        headerSize += 4 + extLength * 4;
    }
    
    if (size <= (int)headerSize) return;
    
    // 将包加入重排序缓冲区
    RtpPacket pkt;
    pkt.seq = seqNum;
    pkt.data = QByteArray(data + headerSize, size - headerSize);
    
    // 初始化序列号
    if (!m_seqInitialized) {
        m_nextExpectedSeq = seqNum;
        m_seqInitialized = true;
    }
    
    // 按序列号插入到正确位置
    bool inserted = false;
    for (int i = 0; i < m_reorderBuffer.size(); i++) {
        int diff = (int)seqNum - (int)m_reorderBuffer[i].seq;
        if (diff < -30000) diff += 65536;
        if (diff > 30000) diff -= 65536;
        
        if (diff == 0) {
            // 重复包，忽略
            inserted = true;
            break;
        }
        if (diff < 0) {
            m_reorderBuffer.insert(i, pkt);
            inserted = true;
            break;
        }
    }
    if (!inserted) {
        m_reorderBuffer.append(pkt);
    }
    
    // 处理缓冲区中的包
    processBufferedPackets();
}

void WebRTCClient::processBufferedPackets()
{
    // 当缓冲区有足够的包时，或者包已经按顺序排列时，处理它们
    while (!m_reorderBuffer.isEmpty()) {
        int diff = (int)m_reorderBuffer.first().seq - (int)m_nextExpectedSeq;
        if (diff < -30000) diff += 65536;
        if (diff > 30000) diff -= 65536;
        
        if (diff == 0) {
            // 正好是期望的包，处理它
            RtpPacket pkt = m_reorderBuffer.takeFirst();
            processRtpPayload(reinterpret_cast<const uint8_t*>(pkt.data.constData()), 
                             pkt.data.size(), pkt.seq);
            m_nextExpectedSeq = (pkt.seq + 1) & 0xFFFF;
            m_consecutiveLostPackets = 0;  // ⭐ 重置连续丢包计数
        }
        else if (diff < 0) {
            // 旧包（已处理过），丢弃
            m_reorderBuffer.removeFirst();
        }
        else if (diff > 0 && m_reorderBuffer.size() >= REORDER_BUFFER_SIZE) {
            // 缓冲区满了，期望的包可能丢失了，跳过它
            m_lostPackets++;
            m_lostSinceKeyFrame++;
            m_consecutiveLostPackets++;  // ⭐ 连续丢包计数
            m_nextExpectedSeq = (m_nextExpectedSeq + 1) & 0xFFFF;
            
            // 如果在 FU-A 中，标记为损坏
            if (m_fuaInProgress) {
                m_fuaCorrupted = true;
            }
            
            // ⭐ 连续丢包超过阈值，立即请求关键帧（不等定时器）
            if (m_consecutiveLostPackets >= 5) {
                if (!m_needNewKeyFrame) {
                    qDebug() << "⚠️ 连续丢包" << m_consecutiveLostPackets << "个，立即请求关键帧";
                    m_needNewKeyFrame = true;
                    QMetaObject::invokeMethod(this, &WebRTCClient::requestKeyFrame, Qt::QueuedConnection);
                }
                m_consecutiveLostPackets = 0;  // 重置，避免重复请求
            }
        }
        else {
            // 还没收到期望的包，但缓冲区还没满，等待
            break;
        }
    }
}

void WebRTCClient::processRtpPayload(const uint8_t *data, size_t size, uint16_t seqNum)
{
    extractNaluFromRtp(data, size, seqNum);
}

void WebRTCClient::extractNaluFromRtp(const uint8_t *data, size_t size, uint16_t seqNum)
{
    if (size < 1) return;
    
    uint8_t naluHeader = data[0];
    uint8_t naluType = naluHeader & 0x1F;
    
    // NALU 调试日志已禁用
    
    if (naluType >= 1 && naluType <= 23) {
        // 单个 NALU
        // SPS(7), PPS(8) 必须始终传递
        // IDR(5) 是关键帧，收到后开始正常解码
        if (naluType == 5 || naluType == 7 || naluType == 8) {
            m_waitingForKeyFrame = false;
            m_needNewKeyFrame = false;  // IDR 收到
            m_lostSinceKeyFrame = 0;
            m_lastKeyFrameTime = QDateTime::currentMSecsSinceEpoch();
        }
        
        if (m_waitingForKeyFrame) {
            return;  // 还在等关键帧，跳过普通帧
        }
        
        // 如果需要新 IDR 但这是 P 帧，跳过
        if (m_needNewKeyFrame && naluType == 1) {
            return;
        }
        
        QByteArray nalu;
        nalu.append("\x00\x00\x00\x01", 4);
        nalu.append(reinterpret_cast<const char*>(data), size);
        emit naluReady(nalu, naluType == 5);
    }
    else if (naluType == 28) {
        // FU-A 分片
        if (size < 2) return;
        
        uint8_t fuHeader = data[1];
        bool isStart = (fuHeader >> 7) & 0x01;
        bool isEnd = (fuHeader >> 6) & 0x01;
        uint8_t realNaluType = fuHeader & 0x1F;
        
        // FU-A 调试日志已禁用
        
        // 发现关键帧/SPS/PPS，停止等待
        if (realNaluType == 5 || realNaluType == 7 || realNaluType == 8) {
            if (m_waitingForKeyFrame) {
                // qDebug() << "*** Got keyframe! Starting decode. ***";
                m_waitingForKeyFrame = false;
            }
            if (isStart && realNaluType == 5) {
                m_lostSinceKeyFrame = 0;  // 新的关键帧开始
                m_lastKeyFrameTime = QDateTime::currentMSecsSinceEpoch();
                // 注意：m_needNewKeyFrame 在 isEnd 时 IDR 完整后才清除
            }
        }
        
        if (m_waitingForKeyFrame) {
            m_naluBuffer.clear();
            m_fuaInProgress = false;
            m_fuaCorrupted = false;
            return;
        }
        
        if (isStart) {
            // 如果之前有未完成的 NALU，丢弃它（日志已禁用）
            if (m_fuaInProgress && !m_naluBuffer.isEmpty()) {
                // dropped incomplete NALU
            }
            
            m_naluBuffer.clear();
            m_naluBuffer.append("\x00\x00\x00\x01", 4);
            uint8_t reconstructedHeader = (naluHeader & 0xE0) | realNaluType;
            m_naluBuffer.append(static_cast<char>(reconstructedHeader));
            m_currentFuaNaluType = realNaluType;
            m_fuaInProgress = true;
            m_fuaCorrupted = false;  // 新的 FU-A 开始，未损坏
            m_fuaStartSeq = seqNum;
            m_fuaExpectedSeq = (seqNum + 1) & 0xFFFF;
        }
        else if (m_fuaInProgress) {
            // 检查序列号是否连续
            if (seqNum != m_fuaExpectedSeq) {
                // 序列号不连续 = 丢包，标记为损坏
                if (!m_fuaCorrupted) {
                    m_fuaCorrupted = true;
                    // ⭐ 立即请求关键帧（不等帧结束，减少等待时间）
                    if (!m_needNewKeyFrame) {
                        qDebug() << "⚠️ FU-A 分片丢包 (seq:" << seqNum << "期望:" << m_fuaExpectedSeq << ")，立即请求关键帧";
                        m_needNewKeyFrame = true;
                        QMetaObject::invokeMethod(this, &WebRTCClient::requestKeyFrame, Qt::QueuedConnection);
                    }
                }
            }
            m_fuaExpectedSeq = (seqNum + 1) & 0xFFFF;
        }
        
        // 只有在 FU-A 进行中且未损坏才追加数据
        if (m_fuaInProgress && !m_fuaCorrupted && !m_naluBuffer.isEmpty()) {
            m_naluBuffer.append(reinterpret_cast<const char*>(data + 2), size - 2);
        }
        
        if (isEnd) {
            if (m_fuaInProgress && !m_fuaCorrupted && m_naluBuffer.size() > 5) {
                // 完整且未损坏的 NALU
                if (m_currentFuaNaluType == 5) {
                    m_needNewKeyFrame = false;  // IDR 收到，可以正常解码了
                }
                
                // 如果需要新 IDR 但这是 P 帧，跳过
                if (!(m_needNewKeyFrame && m_currentFuaNaluType == 1)) {
                    emit naluReady(m_naluBuffer, m_currentFuaNaluType == 5);
                }
            } else if (m_fuaCorrupted) {
                // 损坏的 NALU，丢弃 → 需要等待新的 IDR
                if (!m_needNewKeyFrame) {
                    m_needNewKeyFrame = true;
                    QMetaObject::invokeMethod(this, &WebRTCClient::requestKeyFrame, Qt::QueuedConnection);
                }
            } else if (!m_fuaInProgress) {
                // 收到 END 但没有 START - 丢包后需要等新 IDR
                if (!m_needNewKeyFrame) {
                    m_needNewKeyFrame = true;
                    QMetaObject::invokeMethod(this, &WebRTCClient::requestKeyFrame, Qt::QueuedConnection);
                }
            }
            m_naluBuffer.clear();
            m_fuaInProgress = false;
            m_fuaCorrupted = false;
        }
    }
    else if (naluType == 24) {
        // STAP-A: 多个 NALU 聚合在一个 RTP 包
        
        size_t offset = 1;  // 跳过 STAP-A 头
        while (offset + 2 < size) {
            uint16_t naluSize = (data[offset] << 8) | data[offset + 1];
            offset += 2;
            
            if (offset + naluSize > size) break;
            
            uint8_t innerNaluType = data[offset] & 0x1F;
            
            if (innerNaluType == 5 || innerNaluType == 7 || innerNaluType == 8) {
                m_waitingForKeyFrame = false;
                m_needNewKeyFrame = false;  // IDR 收到
                m_lostSinceKeyFrame = 0;
                m_lastKeyFrameTime = QDateTime::currentMSecsSinceEpoch();
            }
            
            if (!m_waitingForKeyFrame) {
                QByteArray nalu;
                nalu.append("\x00\x00\x00\x01", 4);
                nalu.append(reinterpret_cast<const char*>(data + offset), naluSize);
                emit naluReady(nalu, innerNaluType == 5);
            }
            
            offset += naluSize;
        }
    }
}
