#ifndef P2PLOGUPLOADER_H
#define P2PLOGUPLOADER_H

// ⭐ P2P 诊断日志上报器（第二十二章）
//   总后台「P2P日志」开关打开时，把 PC 端 P2P 诊断日志批量上报到后端，
//   按推流ID分流落盘，供总后台下载离线排查（卡顿等问题）。
//
//   - 前缀：pc-gstream-p2p（GStreamer 路径 p2pLog）/ pc-web-p2p（网页内核 nhLog）
//   - 开关：GET /api/p2plog/config（激活期间每 60s 复查；关=停止上报，本地日志不受影响）
//   - 上报：POST /api/p2plog/upload（每 10s 批量一次，单批上限 256KB）
//   - append() 线程安全（GStreamer 回调线程直接调），网络收发在主线程定时器里做。

#include <QObject>
#include <QString>
#include <QHash>
#include <QMutex>
#include <QTimer>
#include <QNetworkAccessManager>

class P2PLogUploader : public QObject
{
    Q_OBJECT
public:
    static P2PLogUploader* instance();

    // 线程安全：缓冲一行日志（开关关闭/未激活时直接丢弃，零开销）
    void append(const QString &prefix, const QString &line);

    // QML 接口：推流开始时设置推流ID并激活；停止时 deactivate
    Q_INVOKABLE void setStreamId(const QString &streamId);
    Q_INVOKABLE void activate();
    Q_INVOKABLE void deactivate();

    // 服务器开关当前状态（QML 可读，仅调试显示用）
    Q_INVOKABLE bool uploadEnabled() const { return m_enabled; }

private:
    explicit P2PLogUploader(QObject *parent = nullptr);
    void checkConfig();
    void flush();

    QNetworkAccessManager m_nam;
    QTimer m_flushTimer;    // 10s 批量上报
    QTimer m_configTimer;   // 60s 复查开关

    QMutex m_mutex;
    QHash<QString, QString> m_buffer;   // prefix -> 累积文本

    QString m_streamId;
    bool m_active = false;      // 推流会话进行中
    bool m_enabled = false;     // 服务器开关
    bool m_configKnown = false; // 是否已拿到过一次服务器开关结果（防激活初期丢行）
};

#endif // P2PLOGUPLOADER_H
