#ifndef AUTOUPDATER_H
#define AUTOUPDATER_H

#include <QObject>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QFile>
#include <QProcess>
#include <QUrl>
#include <QVector>

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
    Q_PROPERTY(QString statusText READ statusText NOTIFY statusTextChanged)

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
    QString statusText() const { return m_statusText; }
    
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
    void statusTextChanged();
    void updateAvailable(const QString &version, const QString &changelog);
    void updateError(const QString &error);
    void downloadComplete();
    void installReady();  // 下载完成，准备安装

private:
    explicit AutoUpdater(QObject *parent = nullptr);
    static AutoUpdater *s_instance;
    
    void parseVersionInfo(const QByteArray &data);
    bool compareVersions(const QString &v1, const QString &v2);  // v1 > v2 返回 true
    void checkPendingUpdateResult();   // §43 启动自检：上次更新是否成功
    void setStatusText(const QString &s);

    // ---- §43 清单差量更新（manifest.json 逐文件比对，只下差异文件）----
    struct ManifestFile {
        QString path;      // 相对路径（manifest 内统一 '/' 分隔）
        qint64  size = 0;
        QString sha256;    // 小写 hex
    };
    void startManifestUpdate();                       // 拉 manifest.json
    void onManifestReceived(const QByteArray &data);  // 解析 + 触发后台比对
    void onDiffReady();                               // 比对完成 → 开始逐文件下载
    void downloadNextManifestFile();                  // 串行下载 m_needFiles[m_fileIndex]
    void finalizeManifestUpdate();                    // 写 bat + 重启换入
    void abortManifestUpdate(const QString &reason);
    static QString fileSha256(const QString &filePath);   // 流式 SHA256，失败返回空
    static bool isSafeRelPath(const QString &p);          // 防路径穿越

    QNetworkAccessManager *m_networkManager;
    QString m_updateCheckUrl;
    QString m_currentVersion;
    QString m_latestVersion;
    QString m_changelog;
    QString m_downloadUrl;
    QString m_downloadExeUrl;  // 单独的 exe 下载地址（updateMode=0 时使用）
    QString m_manifestUrl;     // §43 清单地址（非空时优先走清单差量，忽略 updateMode）
    QString m_skippedVersion;
    QString m_statusText;
    bool m_hasUpdate = false;
    bool m_isChecking = false;
    bool m_isDownloading = false;
    bool m_isInstalling = false;
    bool m_forceUpdate = false;
    int m_updateMode = 0;  // 0=只更新exe, 1=全部覆盖（legacy，manifestUrl 为空时才用）
    int m_downloadProgress = 0;
    
    // §43 清单流程状态
    QString m_manifestVersion;
    QString m_manifestBaseUrl;
    QVector<ManifestFile> m_manifestAll;    // 清单全量（比对输入）
    QVector<ManifestFile> m_needFiles;      // 比对后需下载的文件
    int m_fileIndex = 0;
    int m_fileRetry = 0;
    qint64 m_needTotalBytes = 0;
    qint64 m_doneBytes = 0;
    QString m_stageDir;                     // appDir/update_stage

    QFile *m_downloadFile = nullptr;
    QNetworkReply *m_downloadReply = nullptr;
};

#endif // AUTOUPDATER_H
