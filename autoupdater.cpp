#include "autoupdater.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QCoreApplication>
#include <QDir>
#include <QProcess>
#include <QStandardPaths>
#include <QSettings>
#include <QDebug>
#include <QNetworkRequest>
#include <QUrl>
#include <QDateTime>
#include <QFileInfo>
#include <QTimer>
#ifdef Q_OS_WIN
#  include <windows.h>
#  include <shellapi.h>
#endif

static QString qlgxPath()
{
    return QCoreApplication::applicationDirPath() + "/qlgx.txt";
}

static void qlgxLog(const QString &msg)
{
    QFile f(qlgxPath());
    if (f.open(QIODevice::Append | QIODevice::Text)) {
        QTextStream ts(&f);
        ts << QDateTime::currentDateTime().toString("yyyy-MM-dd HH:mm:ss") << "  " << msg << "\n";
        f.close();
    }
    qDebug().noquote() << "[qlgx]" << msg;
}

AutoUpdater* AutoUpdater::s_instance = nullptr;

AutoUpdater* AutoUpdater::instance()
{
    if (!s_instance) {
        s_instance = new AutoUpdater();
    }
    return s_instance;
}

AutoUpdater::AutoUpdater(QObject *parent)
    : QObject(parent)
    , m_networkManager(new QNetworkAccessManager(this))
    , m_currentVersion("8.1.0")  // 当前版本号，每次发布时更新
{
    // 从设置中读取跳过的版本
    QSettings settings;
    m_skippedVersion = settings.value("update/skippedVersion", "").toString();
    
    qDebug() << "AutoUpdater initialized, current version:" << m_currentVersion;
}

void AutoUpdater::setUpdateUrl(const QString &url)
{
    m_updateCheckUrl = url;
    qDebug() << "Update check URL set to:" << url;
}

void AutoUpdater::checkForUpdates()
{
    if (m_updateCheckUrl.isEmpty()) {
        qlgxLog("checkForUpdates: 更新服务器地址未配置, 跳过");
        emit updateError("更新服务器地址未配置");
        return;
    }
    
    if (m_isChecking) {
        return;
    }
    
    m_isChecking = true;
    emit checkingChanged();
    
    qlgxLog(QString("checkForUpdates: 开始检查, URL=%1").arg(m_updateCheckUrl));
    
    QUrl url(m_updateCheckUrl);
    QNetworkRequest req(url);
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    
    QNetworkReply *reply = m_networkManager->get(req);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        m_isChecking = false;
        emit checkingChanged();
        
        if (reply->error() != QNetworkReply::NoError) {
            qlgxLog(QString("checkForUpdates: 网络请求失败, error=%1").arg(reply->errorString()));
            emit updateError("检查更新失败: " + reply->errorString());
            reply->deleteLater();
            return;
        }
        
        QByteArray data = reply->readAll();
        qlgxLog(QString("checkForUpdates: 收到响应 %1 bytes").arg(data.size()));
        reply->deleteLater();
        
        parseVersionInfo(data);
    });
}

void AutoUpdater::parseVersionInfo(const QByteArray &data)
{
    QJsonDocument doc = QJsonDocument::fromJson(data);
    if (!doc.isObject()) {
        qlgxLog("parseVersionInfo: JSON 格式错误, 原始数据=" + QString::fromUtf8(data).left(200));
        emit updateError("版本信息格式错误");
        return;
    }
    
    QJsonObject obj = doc.object();
    
    m_latestVersion = obj["version"].toString();
    m_downloadUrl = obj["downloadUrl"].toString();
    m_downloadExeUrl = obj["downloadExe"].toString();
    m_changelog = obj["changelog"].toString();
    m_forceUpdate = obj["forceUpdate"].toBool(false);
    m_updateMode = obj["updateMode"].toInt(0);
    
    QString info = QString("parseVersionInfo: 当前=%1, 最新=%2, updateMode=%3(%4), "
                           "downloadUrl=%5, downloadExe=%6, forceUpdate=%7")
        .arg(m_currentVersion, m_latestVersion)
        .arg(m_updateMode)
        .arg(m_updateMode == 0 ? "只更新exe" : "全部覆盖")
        .arg(m_downloadUrl, m_downloadExeUrl)
        .arg(m_forceUpdate ? "true" : "false");
    qlgxLog(info);
    
    qDebug() << "📋 版本信息解析结果:";
    qDebug() << "   - 最新版本:" << m_latestVersion;
    qDebug() << "   - 当前版本:" << m_currentVersion;
    qDebug() << "   - 下载地址(zip):" << m_downloadUrl;
    qDebug() << "   - 下载地址(exe):" << m_downloadExeUrl;
    qDebug() << "   - 更新日志:" << m_changelog.left(50) << "...";
    qDebug() << "   - 强制更新:" << m_forceUpdate;
    qDebug() << "   - 更新模式:" << m_updateMode << (m_updateMode == 0 ? "(只更新exe)" : "(全部覆盖)");
    
    if (compareVersions(m_latestVersion, m_currentVersion)) {
        m_hasUpdate = true;
        qlgxLog("parseVersionInfo: 发现新版本, 弹出更新对话框");
        emit updateAvailable(m_latestVersion, m_changelog);
    } else {
        m_hasUpdate = false;
        qlgxLog("parseVersionInfo: 已是最新版本, 无需更新");
    }
    
    emit updateInfoChanged();
}

bool AutoUpdater::compareVersions(const QString &v1, const QString &v2)
{
    // 比较版本号，v1 > v2 返回 true
    QStringList parts1 = v1.split('.');
    QStringList parts2 = v2.split('.');
    
    int maxLen = qMax(parts1.size(), parts2.size());
    
    for (int i = 0; i < maxLen; i++) {
        int num1 = (i < parts1.size()) ? parts1[i].toInt() : 0;
        int num2 = (i < parts2.size()) ? parts2[i].toInt() : 0;
        
        if (num1 > num2) return true;
        if (num1 < num2) return false;
    }
    
    return false;  // 相等
}

void AutoUpdater::downloadAndInstall()
{
    qlgxLog(QString("downloadAndInstall: 开始, updateMode=%1").arg(m_updateMode));
    
    QString actualDownloadUrl;
    QString fileName;
    QString tempDir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    
    if (m_updateMode == 0 && !m_downloadExeUrl.isEmpty()) {
        actualDownloadUrl = m_downloadExeUrl;
        fileName = tempDir + "/Phoenix_update.exe";
        qlgxLog("downloadAndInstall: 模式0, 直接下载 exe, url=" + actualDownloadUrl);
    } else {
        actualDownloadUrl = m_downloadUrl;
        fileName = tempDir + "/Phoenix_update.zip";
        qlgxLog(QString("downloadAndInstall: 模式%1, 下载完整包 zip, url=%2")
                .arg(m_updateMode).arg(actualDownloadUrl));
    }
    
    if (actualDownloadUrl.isEmpty()) {
        qlgxLog("downloadAndInstall: 下载地址为空! 中止");
        emit updateError("下载地址为空");
        return;
    }
    
    if (m_isDownloading) {
        qlgxLog("downloadAndInstall: 已在下载中, 忽略重复请求");
        return;
    }
    
    m_isDownloading = true;
    m_downloadProgress = 0;
    emit downloadingChanged();
    emit downloadProgressChanged();
    
    m_downloadFile = new QFile(fileName);
    if (!m_downloadFile->open(QIODevice::WriteOnly)) {
        qlgxLog("downloadAndInstall: 无法创建下载文件: " + m_downloadFile->errorString());
        emit updateError("无法创建下载文件: " + m_downloadFile->errorString());
        m_isDownloading = false;
        emit downloadingChanged();
        delete m_downloadFile;
        m_downloadFile = nullptr;
        return;
    }
    
    qlgxLog("downloadAndInstall: 开始下载 -> " + fileName);
    
    QUrl downloadUrl(actualDownloadUrl);
    QNetworkRequest downloadReq(downloadUrl);
    m_downloadReply = m_networkManager->get(downloadReq);
    
    connect(m_downloadReply, &QNetworkReply::downloadProgress, this, [this](qint64 received, qint64 total) {
        if (total > 0) {
            int pct = static_cast<int>(received * 100 / total);
            if (pct != m_downloadProgress) {
                m_downloadProgress = pct;
                emit downloadProgressChanged();
                if (pct % 25 == 0)
                    qlgxLog(QString("downloadAndInstall: 下载进度 %1% (%2/%3 bytes)")
                            .arg(pct).arg(received).arg(total));
            }
        }
    });
    
    connect(m_downloadReply, &QNetworkReply::readyRead, this, [this]() {
        if (m_downloadFile) {
            m_downloadFile->write(m_downloadReply->readAll());
        }
    });
    
    connect(m_downloadReply, &QNetworkReply::finished, this, [this]() {
        m_isDownloading = false;
        emit downloadingChanged();
        
        if (m_downloadFile) {
            m_downloadFile->close();
            qlgxLog(QString("downloadAndInstall: 文件已写入, 大小=%1 bytes, 路径=%2")
                    .arg(m_downloadFile->size()).arg(m_downloadFile->fileName()));
        }
        
        if (m_downloadReply->error() != QNetworkReply::NoError) {
            qlgxLog("downloadAndInstall: 下载失败! error=" + m_downloadReply->errorString()
                    + ", httpCode=" + QString::number(
                          m_downloadReply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt()));
            emit updateError("下载失败: " + m_downloadReply->errorString());
            if (m_downloadFile) {
                m_downloadFile->remove();
                delete m_downloadFile;
                m_downloadFile = nullptr;
            }
            m_downloadReply->deleteLater();
            m_downloadReply = nullptr;
            return;
        }
        
        qlgxLog("downloadAndInstall: 下载完成, 准备安装...");
        emit downloadComplete();

        QString appDir      = QCoreApplication::applicationDirPath();
        QString actualExeName = QFileInfo(QCoreApplication::applicationFilePath()).fileName();
        QString tempDir     = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
        QString zipFile     = tempDir + "/Phoenix_update.zip";
        QString exeFile     = tempDir + "/Phoenix_update.exe";
        QString batFile     = tempDir + "/update_phoenix.bat";

        bool directExeDownload = (m_updateMode == 0 && !m_downloadExeUrl.isEmpty());
        bool exeRenamed        = (actualExeName.compare("Phoenix.exe", Qt::CaseInsensitive) != 0);

        QString nAppDir  = QDir::toNativeSeparators(appDir);
        QString nTempDir = QDir::toNativeSeparators(tempDir);
        QString nZipFile = QDir::toNativeSeparators(zipFile);
        QString nExeFile = QDir::toNativeSeparators(exeFile);
        QString nExePath = nAppDir + "\\" + actualExeName;

        qlgxLog(QString("downloadAndInstall: appDir=%1, actualExe=%2, directExe=%3, renamed=%4")
                .arg(appDir, actualExeName)
                .arg(directExeDownload ? "true" : "false")
                .arg(exeRenamed ? "true" : "false"));

        QFile batScript(batFile);
        if (batScript.open(QIODevice::WriteOnly | QIODevice::Text)) {
            QTextStream out(&batScript);
            out.setEncoding(QStringConverter::System);

            out << "@echo off\n";
            // 不切代码页 — bat 用 GBK 写入，与 cmd 默认代码页一致，中文路径才能正确解析
            out << "echo.\n";
            out << "echo  Phoenix Updater\n";
            out << "echo  =====================================\n";
            out << "echo.\n";

            // 解压明细日志（脚本在程序退出后运行，必须自己写文件）
            out << "set \"ULOG=" << nAppDir << "\\update_extract.log\"\n";
            out << "echo ==== Phoenix update %date% %time% ==== > \"%ULOG%\"\n";
            out << "echo  解压明细将写入: %ULOG%\n";

            // 强制结束 Phoenix 进程并等待释放文件锁
            out << "echo  [1/3] 等待旧程序退出...\n";
            out << "taskkill /F /IM Phoenix.exe /T >nul 2>&1\n";
            out << "taskkill /F /IM " << actualExeName << " /T >nul 2>&1\n";
            out << "timeout /t 2 /nobreak >nul\n";
            out << "echo.\n";

            // 防呆：解压若被多套了一层目录(zip 带 release/ 之类前缀，文件落到 appDir\子目录\)，
            // 把那层目录里的内容挪回根目录覆盖。正常解压时无子目录含 Phoenix.exe，自动跳过。
            auto writeUnnestFix = [&]() {
                out << "for /d %%D in (\"" << nAppDir << "\\*\") do (\n";
                out << "    if exist \"%%D\\Phoenix.exe\" (\n";
                out << "        echo  检测到多层目录，正在修正: %%D\n";
                out << "        echo --- fix nested dir: %%D --- >> \"%ULOG%\"\n";
                out << "        xcopy \"%%D\\*\" \"" << nAppDir << "\\\" /E /H /Y >> \"%ULOG%\" 2>&1\n";
                out << "        rd /s /q \"%%D\"\n";
                out << "    )\n";
                out << ")\n";
            };

            if (directExeDownload) {
                out << "echo  [2/3] 正在替换程序文件...\n";
                out << "echo --- copy exe --- >> \"%ULOG%\"\n";
                out << "copy /y \"" << nExeFile << "\" \"" << nExePath << "\" >> \"%ULOG%\" 2>&1\n";
                out << "del \"" << nExeFile << "\" >nul 2>&1\n";
            } else if (m_updateMode == 0) {
                out << "echo  [2/3] 正在解压程序包（请稍候）...\n";
                // tar.exe 是 Win10 1803+ 内置工具，对中文路径支持比 powershell Expand-Archive 好
                // -C 切到目标目录，-x 解压，-v 逐个列出文件（含覆盖失败的错误行），-f 指定 zip 文件
                out << "if not exist \"" << nAppDir << "\" md \"" << nAppDir << "\"\n";
                out << "echo --- tar -xv --- >> \"%ULOG%\"\n";
                out << "tar -xv -f \"" << nZipFile << "\" -C \"" << nAppDir << "\" >> \"%ULOG%\" 2>&1\n";
                out << "if errorlevel 1 (\n";
                out << "    echo  tar 解压失败，回退到 powershell ...\n";
                out << "    echo --- powershell Expand-Archive --- >> \"%ULOG%\"\n";
                out << "    powershell -NoProfile -Command \"Expand-Archive -LiteralPath \\\""
                    << nZipFile << "\\\" -DestinationPath \\\"" << nAppDir << "\\\" -Force\" >> \"%ULOG%\" 2>&1\n";
                out << ")\n";
                out << "del \"" << nZipFile << "\" >nul 2>&1\n";
                writeUnnestFix();
                if (exeRenamed) {
                    out << "if exist \"" << nAppDir << "\\Phoenix.exe\" "
                        << "ren \"" << nAppDir << "\\Phoenix.exe\" \"" << actualExeName << "\"\n";
                }
            } else {
                // 模式1 全量更新：直接解压到 appDir
                out << "echo  [2/3] 正在解压完整更新包（请稍候，文件较大）...\n";
                out << "if not exist \"" << nAppDir << "\" md \"" << nAppDir << "\"\n";
                out << "echo --- tar -xv --- >> \"%ULOG%\"\n";
                out << "tar -xv -f \"" << nZipFile << "\" -C \"" << nAppDir << "\" >> \"%ULOG%\" 2>&1\n";
                out << "if errorlevel 1 (\n";
                out << "    echo  tar 解压失败，回退到 powershell ...\n";
                out << "    echo --- powershell Expand-Archive --- >> \"%ULOG%\"\n";
                out << "    powershell -NoProfile -Command \"Expand-Archive -LiteralPath \\\""
                    << nZipFile << "\\\" -DestinationPath \\\"" << nAppDir << "\\\" -Force\" >> \"%ULOG%\" 2>&1\n";
                out << "    if errorlevel 1 (\n";
                out << "        echo  解压失败，请手动更新（详细见 %ULOG%）\n";
                out << "        pause\n";
                out << "        exit /b 1\n";
                out << "    )\n";
                out << ")\n";
                out << "del \"" << nZipFile << "\" >nul 2>&1\n";
                writeUnnestFix();
                if (exeRenamed) {
                    out << "if exist \"" << nAppDir << "\\Phoenix.exe\" "
                        << "ren \"" << nAppDir << "\\Phoenix.exe\" \"" << actualExeName << "\"\n";
                }
            }

            out << "echo.\n";
            out << "echo  [3/3] 更新完成，正在重启程序...\n";
            out << "echo.\n";
            out << "start \"\" \"" << nExePath << "\"\n";
            out << "del \"%~f0\"\n";
            batScript.close();

            qlgxLog("downloadAndInstall: bat 已写入 " + batFile);

            m_isInstalling = true;
            emit installingChanged();
            emit installReady();

            // ShellExecuteW 弹出可见 bat 窗口（与父进程是否有控制台无关）
            QString nBatFile = QDir::toNativeSeparators(batFile);
            QString shellParam = QString("/c \"%1\"").arg(nBatFile);
            ShellExecuteW(nullptr, L"open", L"cmd.exe",
                          reinterpret_cast<const wchar_t*>(shellParam.utf16()),
                          reinterpret_cast<const wchar_t*>(nAppDir.utf16()),
                          SW_SHOWNORMAL);

            qlgxLog("downloadAndInstall: ShellExecuteW 已调用，立即退出");
            // 与测试版一致：立即退出，bat 的 timeout/t 3 等待结束后再解压
            QCoreApplication::quit();
        } else {
            qlgxLog("downloadAndInstall: 无法创建 bat 脚本!");
            emit updateError("无法创建更新脚本");
        }

        if (m_downloadFile) {
            delete m_downloadFile;
            m_downloadFile = nullptr;
        }
        m_downloadReply->deleteLater();
        m_downloadReply = nullptr;
    });
}

void AutoUpdater::skipVersion()
{
    if (!m_latestVersion.isEmpty()) {
        m_skippedVersion = m_latestVersion;
        QSettings settings;
        settings.setValue("update/skippedVersion", m_skippedVersion);
        qDebug() << "Skipped version:" << m_skippedVersion;
    }
    m_hasUpdate = false;
    emit updateInfoChanged();
}
