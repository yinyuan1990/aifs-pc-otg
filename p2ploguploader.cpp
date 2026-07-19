#include "p2ploguploader.h"
#include "httpclient.h"

#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUrl>
#include <QDebug>

namespace {
constexpr int kFlushIntervalMs  = 10 * 1000;   // 批量上报间隔
constexpr int kConfigIntervalMs = 60 * 1000;   // 开关复查间隔
constexpr int kMaxBatchBytes    = 256 * 1024;  // 单批上限（服务端 512KB，留余量）
constexpr int kMaxBufferBytes   = 2 * 1024 * 1024; // 本地缓冲上限（网络异常时防内存膨胀）
}

P2PLogUploader* P2PLogUploader::instance()
{
    static P2PLogUploader s_instance;
    return &s_instance;
}

P2PLogUploader::P2PLogUploader(QObject *parent)
    : QObject(parent)
{
    m_flushTimer.setInterval(kFlushIntervalMs);
    connect(&m_flushTimer, &QTimer::timeout, this, &P2PLogUploader::flush);

    m_configTimer.setInterval(kConfigIntervalMs);
    connect(&m_configTimer, &QTimer::timeout, this, &P2PLogUploader::checkConfig);
}

void P2PLogUploader::append(const QString &prefix, const QString &line)
{
    if (!m_active) return;                       // 未激活：零成本丢弃
    // 激活初期开关查询还没返回时先缓冲（否则 activate 后最初几百毫秒的关键事件行
    // —— [connect]/[request] 等 —— 会被丢掉）；查询返回若为「关」再统一清空。
    if (m_configKnown && !m_enabled) return;     // 已确认开关关闭：零成本丢弃

    QMutexLocker locker(&m_mutex);
    QString &buf = m_buffer[prefix];
    if (buf.size() > kMaxBufferBytes) return;  // 网络长时间不通时防内存膨胀
    buf += line;
    if (!line.endsWith('\n')) buf += '\n';
}

void P2PLogUploader::setStreamId(const QString &streamId)
{
    m_streamId = streamId;
}

void P2PLogUploader::activate()
{
    if (m_active) return;
    m_active = true;
    checkConfig();          // 立即查一次开关
    m_configTimer.start();
    m_flushTimer.start();
}

void P2PLogUploader::deactivate()
{
    if (!m_active) return;
    flush();                // 收尾把剩余日志发出去
    m_active = false;
    m_configTimer.stop();
    m_flushTimer.stop();
}

void P2PLogUploader::checkConfig()
{
    QUrl url(HttpClient::instance()->baseUrl() + "/api/p2plog/config");
    QNetworkRequest req(url);
    QNetworkReply *reply = m_nam.get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) return; // 网络异常保持现状
        const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
        const bool enabled = obj.value("enabled").toBool(false);
        m_configKnown = true;
        if (enabled != m_enabled) {
            m_enabled = enabled;
            qDebug() << "📤 [P2P日志上报] 服务器开关:" << (enabled ? "开" : "关");
        }
        if (!enabled) {
            // 开关关（含激活初期预缓冲后确认为关）：清掉缓冲
            QMutexLocker locker(&m_mutex);
            m_buffer.clear();
        }
    });
}

void P2PLogUploader::flush()
{
    if (!m_enabled || !m_active) return;

    QHash<QString, QString> pending;
    {
        QMutexLocker locker(&m_mutex);
        if (m_buffer.isEmpty()) return;
        pending.swap(m_buffer);
    }

    const QString streamId = m_streamId.isEmpty() ? QStringLiteral("unknown") : m_streamId;
    for (auto it = pending.constBegin(); it != pending.constEnd(); ++it) {
        QString content = it.value();
        if (content.size() > kMaxBatchBytes) {
            content = content.right(kMaxBatchBytes);   // 超限只留最新（旧的本地文件里还有）
        }

        QJsonObject body;
        body["prefix"] = it.key();
        body["streamId"] = streamId;
        body["content"] = content;

        QNetworkRequest req(QUrl(HttpClient::instance()->baseUrl() + "/api/p2plog/upload"));
        req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
        QNetworkReply *reply = m_nam.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
        connect(reply, &QNetworkReply::finished, this, [this, reply]() {
            reply->deleteLater();
            if (reply->error() != QNetworkReply::NoError) return;
            const QJsonObject obj = QJsonDocument::fromJson(reply->readAll()).object();
            // 服务端已关闭开关 → 本地同步关，等下轮 config 复查再开
            if (obj.contains("enabled") && !obj.value("enabled").toBool(true)) {
                m_enabled = false;
            }
        });
    }
}
