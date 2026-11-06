#ifndef NETPROXY_H
#define NETPROXY_H

#include <QDebug>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkProxyFactory>
#include <QNetworkReply>
#include <QSettings>
#include <QUrl>
#include <atomic>

// 同行软件会把 Windows 系统代理改成本机一个早就不在的端口（11-05 shiyong888888 那台：127.0.0.1:10808），
//   浏览器和大部分软件全部「无网」，ToDesk 这类自己直连的照常。我们的请求默认直连、不走系统代理；
//   直连连续 2 次连不上服务器（一个 HTTP 应答都没收到）才改走系统代理（少数客户确实靠代理上网），
//   系统代理也连续连不上就再切回直连。当前模式存 QSettings("Aifs","Net")，下次启动（含网页内核启动参数）沿用。
namespace NetProxy {

inline std::atomic<bool> &systemMode()
{
    static std::atomic<bool> v{QSettings("Aifs", "Net").value("useSystemProxy", false).toBool()};
    return v;
}

inline std::atomic<int> &failStreak()
{
    static std::atomic<int> v{0};
    return v;
}

inline bool usingSystem() { return systemMode().load(); }

inline QNetworkProxy forUrl(const QUrl &url)
{
    if (!usingSystem()) return QNetworkProxy(QNetworkProxy::NoProxy);
    const QList<QNetworkProxy> list = QNetworkProxyFactory::systemProxyForQuery(QNetworkProxyQuery(url));
    return list.isEmpty() ? QNetworkProxy(QNetworkProxy::NoProxy) : list.first();
}

inline void apply(QNetworkAccessManager *nam, const QUrl &url)
{
    if (nam) nam->setProxy(forUrl(url));
}

inline void reportSuccess() { failStreak().store(0); }

inline void reportTransportFailure(const QString &where)
{
    if (failStreak().fetch_add(1) + 1 < 2) return;
    failStreak().store(0);
    const bool sys = !systemMode().load();
    systemMode().store(sys);
    QSettings("Aifs", "Net").setValue("useSystemProxy", sys);
    qWarning().noquote() << "[网络]" << where << "连续 2 次连不上服务器 →"
                         << (sys ? "改走系统代理" : "改回直连（不走系统代理）");
}

// 请求结束时调用：收到任何 HTTP 应答 = 网络通；没收到（连不上 / 超时 / 代理错误）= 失败；主动取消不算
inline void noteReply(QNetworkReply *reply, const QString &where)
{
    if (!reply) return;
    if (reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() > 0) {
        reportSuccess();
    } else if (reply->error() != QNetworkReply::NoError && reply->error() != QNetworkReply::OperationCanceledError) {
        reportTransportFailure(where);
    }
}

} // namespace NetProxy

#endif // NETPROXY_H
