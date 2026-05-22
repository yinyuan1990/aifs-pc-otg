#ifndef AUTOUPDATER_H
#define AUTOUPDATER_H

#include <QObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QFile>
#include <QProcess>
#include <QUrl>

class AutoUpdater : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QString currentVersion READ currentVersion CONSTANT)
    Q_PROPERTY(QString latestVersion READ latestVersion NOTIFY updateInfoChanged)
    Q_PROPERTY(QString changelog READ changelog NOTIFY updateInfoChanged)
    Q_PROPERTY(bool hasUpdate READ hasUpdate NOTIFY updateInfoChanged)
    Q_PROPERTY(bool isChecking READ isChecking NOTIFY checkingChanged)
    Q_PROPERTY(bool isDownloading READ isDownloading NOTIFY downloadingChanged)
    Q_PROPERTY(int downloadProgress READ downloadProgress NOTIFY downloadProgressChanged)
    Q_PROPERTY(bool isInstalling READ isInstalling NOTIFY installingChanged)

public:
    static AutoUpdater* instance();
    
    QString currentVersion() const { return m_currentVersion; }
    QString latestVersion() const { return m_latestVersion; }
    QString changelog() const { return m_changelog; }
    bool hasUpdate() const { return m_hasUpdate; }
    bool isChecking() const { return m_isChecking; }
    bool isDownloading() const { return m_isDownloading; }
    int downloadProgress() const { return m_downloadProgress; }
    bool isInstalling() const { return m_isInstalling; }
    
    // 设置版本检查URL
    Q_INVOKABLE void setUpdateUrl(const QString &url);
    
    // 检查更新
    Q_INVOKABLE void checkForUpdates();
    
    // 下载并安装更新
    Q_INVOKABLE void downloadAndInstall();
    
    // 跳过此版本
    Q_INVOKABLE void skipVersion();

signals:
    void updateInfoChanged();
    void checkingChanged();
    void downloadingChanged();
    void downloadProgressChanged();
    void installingChanged();
    void updateAvailable(const QString &version, const QString &changelog);
    void updateError(const QString &error);
    void downloadComplete();
    void installReady();  // 下载完成，准备安装

private:
    explicit AutoUpdater(QObject *parent = nullptr);
    static AutoUpdater *s_instance;
    
    void parseVersionInfo(const QByteArray &data);
    bool compareVersions(const QString &v1, const QString &v2);  // v1 > v2 返回 true
    
    QNetworkAccessManager *m_networkManager;
    QString m_updateCheckUrl;
    QString m_currentVersion;
    QString m_latestVersion;
    QString m_changelog;
    QString m_downloadUrl;
    QString m_downloadExeUrl;  // 单独的 exe 下载地址（updateMode=0 时使用）
    QString m_skippedVersion;
    bool m_hasUpdate = false;
    bool m_isChecking = false;
    bool m_isDownloading = false;
    bool m_isInstalling = false;
    bool m_forceUpdate = false;
    int m_updateMode = 0;  // 0=只更新exe, 1=全部覆盖更新
    int m_downloadProgress = 0;
    
    QFile *m_downloadFile = nullptr;
    QNetworkReply *m_downloadReply = nullptr;
};

#endif // AUTOUPDATER_H
