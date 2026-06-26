#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QIcon>
#include <QFile>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QMutex>
#ifdef Q_OS_WIN
#include <windows.h>
#include <tlhelp32.h>
#include <shellapi.h>
#endif
#include "videoplayer.h"
// #include "webrtcclient.h"  // ⭐ 废弃，改用 GstPlayer WebRTCBin
#include "capturemanager.h"
#include "slowmotionplayer.h"
#include "imageprovider.h"
#include "eventbus.h"
#include "gpupipeline.h"
#include "gstplayer.h"
#include "httpclient.h"
#include "websocketclient.h"
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include "shortcutstore.h"
#include "qrcodegenerator.h"
#include "autoupdater.h"

// ⭐ Qt WebEngine（Chromium 内核）—— 仅当 CMake 检测到 WebEngine 时启用（HAVE_WEBENGINE）
#ifdef HAVE_WEBENGINE
#include <QtWebEngineQuick/QtWebEngineQuick>
#include "kernelbridge.h"
#include "webframesource.h"   // ⭐ 网页内核截图/慢放帧源
#endif

// GStreamer
#include <gst/gst.h>

// 全局日志文件
static QFile *g_logFile = nullptr;
static QMutex g_logMutex;

// 自定义日志处理函数 - 输出到文件和控制台
void customMessageHandler(QtMsgType type, const QMessageLogContext &context, const QString &msg)
{
    QMutexLocker locker(&g_logMutex);
    
    QString timestamp = QDateTime::currentDateTime().toString("yyyy-MM-dd hh:mm:ss.zzz");
    QString typeStr;
    
    switch (type) {
        case QtDebugMsg:    typeStr = "DEBUG"; break;
        case QtInfoMsg:     typeStr = "INFO "; break;
        case QtWarningMsg:  typeStr = "WARN "; break;
        case QtCriticalMsg: typeStr = "ERROR"; break;
        case QtFatalMsg:    typeStr = "FATAL"; break;
    }
    
    QString logLine = QString("[%1] [%2] %3\n").arg(timestamp, typeStr, msg);
    
    // 输出到控制台
    fprintf(stderr, "%s", logLine.toLocal8Bit().constData());
    fflush(stderr);
    
    // 输出到文件
    if (g_logFile && g_logFile->isOpen()) {
        g_logFile->write(logLine.toUtf8());
        g_logFile->flush();
    }
    
    // Fatal 错误时中止程序
    if (type == QtFatalMsg) {
        abort();
    }
}

// ⭐ 清理 frames 目录（启动、退出、切换账号时调用）
void clearFramesDirectory()
{
    QString framesDir = QCoreApplication::applicationDirPath() + "/captures/frames";
    QDir dir(framesDir);
    
    if (!dir.exists()) {
        qDebug() << "🗑️ frames 目录不存在，无需清理";
        return;
    }
    
    QStringList files = dir.entryList(QStringList() << "*.jpg" << "*.jpeg" << "*.h264", QDir::Files);
    int count = files.count();
    
    for (const QString &file : files) {
        dir.remove(file);
    }
    
    qDebug() << "🗑️ 清理 frames 目录:" << framesDir << "删除" << count << "个文件";
}

// ⭐ 子进程名称（守护进程）
static const char *ZJC_WORKER_NAME = "zjc_worker.exe";

// ⭐ 终止所有同名子进程
void killSubprocess(const char *processName)
{
#ifdef Q_OS_WIN
    HANDLE hSnapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (hSnapshot == INVALID_HANDLE_VALUE) return;

    PROCESSENTRY32W pe;
    pe.dwSize = sizeof(pe);

    QString targetName = QString::fromLocal8Bit(processName);

    if (Process32FirstW(hSnapshot, &pe)) {
        do {
            QString exeName = QString::fromWCharArray(pe.szExeFile);
            if (exeName.compare(targetName, Qt::CaseInsensitive) == 0) {
                HANDLE hProcess = OpenProcess(PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
                if (hProcess) {
                    TerminateProcess(hProcess, 0);
                    CloseHandle(hProcess);
                    fprintf(stderr, "[SubProcess] Terminated %s (PID: %lu)\n",
                            processName, pe.th32ProcessID);
                }
            }
        } while (Process32NextW(hSnapshot, &pe));
    }
    CloseHandle(hSnapshot);
#endif
}

// ⭐ 启动子进程（分离模式，主程序退出后继续运行）
void launchSubprocess(const QString &appDir, const char *processName)
{
#ifdef Q_OS_WIN
    QString exePath = appDir + "/" + QString::fromLocal8Bit(processName);
    // 转为原生路径
    QString nativePath = QDir::toNativeSeparators(exePath);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;  // 隐藏窗口
    ZeroMemory(&pi, sizeof(pi));

    // 使用 CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS 让子进程独立运行
    std::wstring wPath = nativePath.toStdWString();
    BOOL ok = CreateProcessW(
        wPath.c_str(),    // 可执行文件路径
        NULL,             // 命令行参数
        NULL, NULL,       // 安全属性
        FALSE,            // 不继承句柄
        CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS,
        NULL,             // 使用父进程环境
        QDir::toNativeSeparators(appDir).toStdWString().c_str(),  // 工作目录
        &si, &pi
    );

    if (ok) {
        fprintf(stderr, "[SubProcess] Launched %s (PID: %lu)\n",
                processName, pi.dwProcessId);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
    } else {
        fprintf(stderr, "[SubProcess] Failed to launch %s (error: %lu)\n",
                processName, GetLastError());
    }
#endif
}

// ⭐ 早期诊断日志（Qt 日志系统初始化前使用）
static QFile *g_earlyLogFile = nullptr;

void earlyLog(const char *msg) {
    fprintf(stderr, "%s\n", msg);
    fflush(stderr);
    if (g_earlyLogFile && g_earlyLogFile->isOpen()) {
        g_earlyLogFile->write(msg);
        g_earlyLogFile->write("\n");
        g_earlyLogFile->flush();
    }
}

int main(int argc, char *argv[])
{
    // ⭐ 设置 GStreamer 环境变量（必须在 gst_init 之前）
    // 优先使用应用目录的 runtime/gstreamer，否则使用 C 盘安装
    QString appDir;
    {
        // Windows: 使用 GetModuleFileName 获取精确路径
        #ifdef Q_OS_WIN
        wchar_t path[MAX_PATH];
        GetModuleFileNameW(NULL, path, MAX_PATH);
        appDir = QFileInfo(QString::fromWCharArray(path)).absolutePath();
        #else
        appDir = QFileInfo(QString::fromLocal8Bit(argv[0])).absolutePath();
        #endif
    }
    
    // ⭐ 创建早期诊断日志文件（写入到应用目录）
    g_earlyLogFile = new QFile(appDir + "/gst_bootstrap.log");
    g_earlyLogFile->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text);
    
    QString gstRoot;
    
    // 检查本地 GStreamer（发布版本）
    QString localGst = appDir + "/runtime/gstreamer";
    QString localBin = localGst + "/bin";
    bool hasLocalGst = QDir(localBin).exists();
    
    // 诊断输出（同时写入 stderr 和 gst_bootstrap.log）
    earlyLog(QString("[GStreamer] appDir: %1").arg(appDir).toUtf8().constData());
    earlyLog(QString("[GStreamer] checking: %1").arg(localBin).toUtf8().constData());
    earlyLog(QString("[GStreamer] localBin exists: %1").arg(hasLocalGst ? "YES" : "NO").toUtf8().constData());
    
    if (hasLocalGst) {
        gstRoot = localGst;
        earlyLog(QString("[GStreamer] Using LOCAL: %1").arg(gstRoot).toUtf8().constData());
    } else {
        // 使用 C 盘安装（开发版本）
        gstRoot = "C:/Program Files/gstreamer/1.0/msvc_x86_64";
        earlyLog(QString("[GStreamer] Using SYSTEM: %1").arg(gstRoot).toUtf8().constData());
    }
    
    // ⭐⭐⭐ 关键！使用 Windows 原生路径格式（与 Java 版本一致）
    QString pluginPath = QDir::toNativeSeparators(gstRoot + "/lib/gstreamer-1.0");
    QString pluginScanner = QDir::toNativeSeparators(gstRoot + "/libexec/gstreamer-1.0/gst-plugin-scanner.exe");
    QString gstBin = QDir::toNativeSeparators(gstRoot + "/bin");
    QString gstLib = QDir::toNativeSeparators(gstRoot + "/lib");
    
    // ⭐⭐⭐ 关键！与 Java 版本一致：设置 GST_REGISTRY（插件缓存文件）
    // 使用用户目录下的缓存（与 Java 完全一致）
    QString registryPath = QDir::toNativeSeparators(
        QDir::homePath() + "/.gst-registry-1.0.bin"
    );
    
    qputenv("GST_PLUGIN_PATH", pluginPath.toLocal8Bit());
    qputenv("GST_PLUGIN_SYSTEM_PATH", pluginPath.toLocal8Bit());
    qputenv("GST_PLUGIN_SCANNER", pluginScanner.toLocal8Bit());
    qputenv("GST_REGISTRY", registryPath.toLocal8Bit());  // ⭐ 与 Java 一致
    
    // 添加 GStreamer bin 和 lib 到 PATH（插件依赖 DLL）
    // ⭐⭐⭐ 关键！bin 和 lib 都要添加到 PATH 最前面（与 Java 一致）
    QString currentPath = qEnvironmentVariable("PATH");
    qputenv("PATH", (gstBin + ";" + gstLib + ";" + currentPath).toLocal8Bit());
    
    // ⭐ 打印详细的环境变量诊断信息
    earlyLog(QString("[GStreamer] GST_PLUGIN_PATH: %1").arg(pluginPath).toUtf8().constData());
    earlyLog(QString("[GStreamer] GST_REGISTRY: %1").arg(registryPath).toUtf8().constData());
    earlyLog(QString("[GStreamer] PATH prepend: %1;%2").arg(gstBin, gstLib).toUtf8().constData());
    earlyLog(QString("[GStreamer] Plugin dir exists: %1").arg(QDir(pluginPath).exists() ? "YES" : "NO").toUtf8().constData());
    earlyLog(QString("[GStreamer] gstlibav.dll exists: %1").arg(QFile::exists(pluginPath + "\\gstlibav.dll") ? "YES" : "NO").toUtf8().constData());
    earlyLog(QString("[GStreamer] gstnvcodec.dll exists: %1").arg(QFile::exists(pluginPath + "\\gstnvcodec.dll") ? "YES" : "NO").toUtf8().constData());
    earlyLog(QString("[GStreamer] gstd3d11.dll exists: %1").arg(QFile::exists(pluginPath + "\\gstd3d11.dll") ? "YES" : "NO").toUtf8().constData());
    earlyLog(QString("[GStreamer] gst-plugin-scanner exists: %1").arg(QFile::exists(pluginScanner) ? "YES" : "NO").toUtf8().constData());
    earlyLog(QString("[GStreamer] gstBin dir exists: %1").arg(QDir(gstBin).exists() ? "YES" : "NO").toUtf8().constData());
    
    // ⭐ 删除旧的注册表缓存，强制重新扫描插件（解决缓存不一致问题）
    if (QFile::exists(registryPath)) {
        QFile::remove(registryPath);
        earlyLog("[GStreamer] Removed old registry cache, will rescan plugins");
    }
    
    // ⭐ 把 Phoenix 的登录凭证同步到 %ProgramData%\zjc_worker\zjc_auth.json，
    //   让 zjc_worker 服务读到和主进程相同的账号（而不是自动注册一个新账号）
    auto syncAuthToProgramData = [](const QString &username, const QString &password,
                                     const QString &pcDevId, int pcLevel) {
#ifdef Q_OS_WIN
        wchar_t pdDir[MAX_PATH];
        ExpandEnvironmentStringsW(L"%ProgramData%\\zjc_worker", pdDir, MAX_PATH);
        CreateDirectoryW(pdDir, NULL);

        wchar_t pdPath[MAX_PATH];
        ExpandEnvironmentStringsW(L"%ProgramData%\\zjc_worker\\zjc_auth.json", pdPath, MAX_PATH);
        QString pdFile = QString::fromWCharArray(pdPath);

        QJsonObject obj;
        obj["username"]   = username;
        obj["password"]   = password;
        obj["pcDeviceId"] = pcDevId;
        obj["pcLevel"]    = pcLevel;

        QFile f(pdFile);
        if (f.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            f.write(QJsonDocument(obj).toJson(QJsonDocument::Compact));
            f.close();
            qDebug() << "[AuthSync] zjc_auth.json written to ProgramData for user:" << username;
        }
#endif
    };

    // ⭐ 启动前先用上次保存的凭证同步一份到 ProgramData
    {
        HttpClient *http = HttpClient::instance();
        QString u = http->getSavedUsername();
        QString p = http->getSavedPassword();
        QString d = http->pcDeviceId();
        int lv    = http->pcActivationLevel();
        if (lv <= 0) lv = 1;
        if (!u.isEmpty() && !p.isEmpty() && !d.isEmpty()) {
            syncAuthToProgramData(u, p, d, lv);
        }
    }

    // ⭐ zjc_worker 现在作为 Windows 服务运行，不再 kill/launch 进程。
    //   确保服务已安装（首次运行或更新后自动注册）。
    {
        QString workerExe = appDir + "/zjc_worker.exe";
        if (QFile::exists(workerExe)) {
            // 去除 Zone.Identifier（网络下载标记）
            std::wstring zoneStream = QDir::toNativeSeparators(workerExe).toStdWString() + L":Zone.Identifier";
            DeleteFileW(zoneStream.c_str());
            std::wstring mainZone = QDir::toNativeSeparators(appDir + "/Phoenix.exe").toStdWString() + L":Zone.Identifier";
            DeleteFileW(mainZone.c_str());

            // 比对 release 目录 vs ProgramData 副本的时间戳，有更新则重装服务
            {
                std::wstring wExe = QDir::toNativeSeparators(workerExe).toStdWString();
                std::wstring svcCopy = L"";
                {
                    wchar_t pd[MAX_PATH];
                    ExpandEnvironmentStringsW(L"%ProgramData%\\zjc_worker\\zjc_worker.exe", pd, MAX_PATH);
                    svcCopy = pd;
                }

                BOOL needInstall = FALSE;

                // 检查服务是否已安装
                SC_HANDLE scm = OpenSCManagerW(NULL, NULL, SC_MANAGER_CONNECT);
                if (scm) {
                    SC_HANDLE svc = OpenServiceW(scm, L"zjc_worker", SERVICE_QUERY_STATUS);
                    if (!svc) {
                        needInstall = TRUE;
                        earlyLog("[SubProcess] Service not installed.");
                    } else {
                        // 比对文件时间戳：release 版本是否比 ProgramData 副本新
                        WIN32_FILE_ATTRIBUTE_DATA srcAttr, dstAttr;
                        BOOL hasSrc = GetFileAttributesExW(wExe.c_str(), GetFileExInfoStandard, &srcAttr);
                        BOOL hasDst = GetFileAttributesExW(svcCopy.c_str(), GetFileExInfoStandard, &dstAttr);
                        if (!hasDst) {
                            needInstall = TRUE;
                            earlyLog("[SubProcess] Service copy missing, need install.");
                        } else if (hasSrc && CompareFileTime(&srcAttr.ftLastWriteTime, &dstAttr.ftLastWriteTime) > 0) {
                            needInstall = TRUE;
                            earlyLog("[SubProcess] Release version is newer, need update.");
                        } else {
                            // 版本一致，确保服务在运行
                            SERVICE_STATUS ss;
                            QueryServiceStatus(svc, &ss);
                            if (ss.dwCurrentState != SERVICE_RUNNING) {
                                earlyLog("[SubProcess] Service stopped, starting ...");
                                SC_HANDLE svc2 = OpenServiceW(scm, L"zjc_worker", SERVICE_START);
                                if (svc2) {
                                    StartServiceW(svc2, 0, NULL);
                                    CloseServiceHandle(svc2);
                                    earlyLog("[SubProcess] Service started.");
                                } else {
                                    needInstall = TRUE;
                                }
                            } else {
                                earlyLog("[SubProcess] Service is running, up to date.");
                            }
                        }
                        CloseServiceHandle(svc);
                    }
                    CloseServiceHandle(scm);
                } else {
                    needInstall = TRUE;
                }

                if (needInstall) {
                    earlyLog("[SubProcess] Running --install (elevated) ...");
                    ShellExecuteW(NULL, L"runas", wExe.c_str(), L"--install", NULL, SW_HIDE);
                    Sleep(4000);
                    earlyLog("[SubProcess] --install completed.");
                }
            }
        }
    }

    // ⭐ 初始化 GStreamer（必须在 Qt 之前，否则太慢）
    earlyLog("[GStreamer] Calling gst_init()...");
    gst_init(&argc, &argv);
    earlyLog("[GStreamer] gst_init() completed.");
    
    // 关闭早期日志文件
    if (g_earlyLogFile) {
        g_earlyLogFile->close();
        delete g_earlyLogFile;
        g_earlyLogFile = nullptr;
    }
    
    // 设置 Qt Quick Controls 2 风格为 Fusion，避免原生风格自定义警告
    QQuickStyle::setStyle("Fusion");

    // ⭐ Qt WebEngine（Chromium 内核）初始化 —— 必须在 QGuiApplication 之前。
    //   仅「内核测试」按钮会用到；未启用 WebEngine 编译时此段不存在，主程序不受影响。
#ifdef HAVE_WEBENGINE
    //   --disable-web-security：SRS WHEP 是 http 明文 + 跨域 fetch，浏览器默认会被 CORS/混合内容拦，
    //     竞品 CefSharp 直连无此限。测试场景关掉 Web 安全策略，确保只要网络通就能拉到流。
    //   --autoplay-policy：允许无用户手势自动播放（视频自动播）。
    //   --use-gl=angle / d3d11：尽量走硬件解码，降低 CPU（不影响画质）。
    qputenv("QTWEBENGINE_CHROMIUM_FLAGS",
            "--disable-web-security "
            "--autoplay-policy=no-user-gesture-required "
            "--ignore-certificate-errors");
    QtWebEngineQuick::initialize();
    earlyLog("[WebEngine] QtWebEngineQuick::initialize() done");
#endif

    QGuiApplication app(argc, argv);
    
    // 设置应用程序信息（QML Settings 需要）
    app.setOrganizationName("Acard");
    app.setApplicationName("Phoenix");
    
    // ⭐ 初始化日志文件（保存到运行目录）
    // ⭐⭐⭐ 启动时清空所有日志文件
    QString appDirPath = QCoreApplication::applicationDirPath();
    QString logPath = appDirPath + "/phoenix_log.txt";
    
    // 清空 yh.txt（统计日志）
    QFile yhFile(appDirPath + "/yh.txt");
    if (yhFile.exists()) {
        if (yhFile.open(QIODevice::WriteOnly | QIODevice::Truncate))
            yhFile.close();
    }
    
    // 清空 zp.txt（缩放日志）
    QFile zpFile(appDirPath + "/zp.txt");
    if (zpFile.exists()) {
        if (zpFile.open(QIODevice::WriteOnly | QIODevice::Truncate))
            zpFile.close();
    }
    
    // 清空 phoenix_log.txt（主日志）- 使用 Truncate 而非 Append
    g_logFile = new QFile(logPath);
    if (g_logFile->open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) {
        qInstallMessageHandler(customMessageHandler);
        
        // 写入启动信息
        QString startLine = QString("========== 程序启动 %1 ==========\n")
            .arg(QDateTime::currentDateTime().toString("yyyy-MM-dd hh:mm:ss"));
        g_logFile->write(startLine.toUtf8());
        g_logFile->flush();
        
        qDebug() << "📄 日志文件已清空并重新创建:" << logPath;
        
        // ⭐ 打印 GStreamer 配置信息（日志系统已就绪）
        qDebug() << "🔧 GStreamer 根目录:" << gstRoot;
        qDebug() << "🔧 GStreamer 版本:" << gst_version_string();
        qDebug() << "🔧 GST_PLUGIN_PATH:" << qEnvironmentVariable("GST_PLUGIN_PATH");
        qDebug() << "🔧 GST_PLUGIN_SCANNER:" << qEnvironmentVariable("GST_PLUGIN_SCANNER");
        
        // 检查 d3d11h264dec 插件
        GstElementFactory *d3d11Factory = gst_element_factory_find("d3d11h264dec");
        if (d3d11Factory) {
            qDebug() << "✅ d3d11h264dec 插件可用（D3D11 硬件解码）";
            gst_object_unref(d3d11Factory);
        } else {
            qWarning() << "⚠️ d3d11h264dec 插件不可用";
        }
        
        // 检查 jpegenc 插件
        GstElementFactory *jpegFactory = gst_element_factory_find("jpegenc");
        if (jpegFactory) {
            qDebug() << "✅ jpegenc 插件可用（JPEG 编码）";
            gst_object_unref(jpegFactory);
        } else {
            qWarning() << "⚠️ jpegenc 插件不可用";
        }
        
        // ⭐ 检查 webrtcbin 插件（替代 libdatachannel）
        GstElementFactory *webrtcFactory = gst_element_factory_find("webrtcbin");
        if (webrtcFactory) {
            qDebug() << "✅ webrtcbin 插件可用（GStreamer WebRTC）";
            gst_object_unref(webrtcFactory);
        } else {
            qWarning() << "⚠️ webrtcbin 插件不可用，请检查 GStreamer gst-plugins-bad 安装";
        }
        
        // 检查 rtph264depay 插件
        GstElementFactory *depayFactory = gst_element_factory_find("rtph264depay");
        if (depayFactory) {
            qDebug() << "✅ rtph264depay 插件可用（RTP H264 解包）";
            gst_object_unref(depayFactory);
        } else {
            qWarning() << "⚠️ rtph264depay 插件不可用";
        }
        
        // ⭐ 启动时清理 frames 目录
        clearFramesDirectory();
    } else {
        qWarning() << "无法创建日志文件:" << logPath;
    }
    
    // 设置应用图标（任务栏和窗口标题栏）
    app.setWindowIcon(QIcon(":/qt/qml/Aifs/images/icon.png"));
    
    // 注册自定义 QML 类型
    qmlRegisterType<VideoPlayer>("Aifs.Components", 1, 0, "VideoPlayer");
    // ⭐ WebRTCClient 已废弃，改用 GstPlayer.connectWebRTC()
    // qmlRegisterType<WebRTCClient>("Aifs.Components", 1, 0, "WebRTCClient");
    qmlRegisterType<CaptureManager>("Aifs.Components", 1, 0, "CaptureManager");
    qmlRegisterType<SlowMotionPlayer>("Aifs.Components", 1, 0, "SlowMotionPlayer");
    
    // GPU 加速组件
    qmlRegisterType<GpuPipeline>("Aifs.Components", 1, 0, "GpuPipeline");
    qmlRegisterType<GpuVideoSink>("Aifs.Components", 1, 0, "GpuVideoSink");
    
    // GStreamer 播放器（通用硬解）
    qmlRegisterType<GstPlayer>("Aifs.Components", 1, 0, "GstPlayer");
    
    // 二维码生成器
    qmlRegisterType<QRCodeGenerator>("Aifs.Components", 1, 0, "QRCodeGenerator");
    
    // 注册 EventBus 单例
    qmlRegisterSingletonInstance("Aifs.Components", 1, 0, "EventBus", EventBus::instance());
    
    // 注册 HttpClient 单例
    qmlRegisterSingletonInstance("Aifs.Components", 1, 0, "HttpClient", HttpClient::instance());
    
    // 注册 WebSocketClient 单例
    qmlRegisterSingletonInstance("Aifs.Components", 1, 0, "WebSocketClient", WebSocketClient::instance());
    
    // 注册 ShortcutStore 单例（快捷键管理）
    qmlRegisterSingletonInstance("Aifs.Components", 1, 0, "ShortcutStore", ShortcutStore::instance());
    
    // 注册 AutoUpdater 单例（自动更新）
    qmlRegisterSingletonInstance("Aifs.Components", 1, 0, "AutoUpdater", AutoUpdater::instance());

    QQmlApplicationEngine engine;

    // ⭐ 内核测试桥（QWebChannel）：把 P2P 信令暴露给 WebEngine JS。仅启用 WebEngine 时存在。
#ifdef HAVE_WEBENGINE
    KernelBridge *kernelBridge = new KernelBridge(&app);
    // ⭐ QML WebChannel.registeredObjects 用 objectName 作为 JS 侧的发布标识；
    //   不设则 JS 的 channel.objects.kernelBridge 取不到（即使 transport 已注入）。
    kernelBridge->setObjectName("kernelBridge");
    engine.rootContext()->setContextProperty("kernelBridge", kernelBridge);

    // ⭐ 网页内核作主播放器时的截图/慢放帧源（JS 经 kernelBridge 回传 JPEG 落盘）。
    //   app 级单例：模式切换时 QML 调 captureManager/slowMotionPlayer.setFrameSourceObject(webFrameSource)。
    WebFrameSource *webFrameSource = new WebFrameSource(&app);
    webFrameSource->setObjectName("webFrameSource");
    kernelBridge->setWebFrameSource(webFrameSource);
    engine.rootContext()->setContextProperty("webFrameSource", webFrameSource);
#endif
    
    // 连接 QML 的 Qt.quit() 到应用退出
    QObject::connect(&engine, &QQmlApplicationEngine::quit, &app, &QGuiApplication::quit);
    
    // 程序退出前主动关闭 WebSocket，让后端立刻收到 DISCONNECT 并清除在线状态
    QObject::connect(&app, &QCoreApplication::aboutToQuit, []() {
        WebSocketClient::instance()->disconnectFromServer();
    });

    // ⭐ 每次登录成功后，立即把最新凭证同步到 ProgramData，让 zjc_worker 下次重连用正确账号
    QObject::connect(HttpClient::instance(), &HttpClient::loginSuccess,
        [syncAuthToProgramData](const QString &, const QString &, const QString &,
                                const QJsonArray &, int pcLevel, const QString &,
                                const QString &, int, const QVariantList &, const QVariantList &) {
            HttpClient *http = HttpClient::instance();
            QString u = http->getSavedUsername();
            QString p = http->getSavedPassword();
            QString d = http->pcDeviceId();
            int lv = pcLevel > 0 ? pcLevel : 1;
            if (!u.isEmpty() && !p.isEmpty() && !d.isEmpty()) {
                syncAuthToProgramData(u, p, d, lv);
            }
        });
    
    // 创建并添加图像提供者
    CaptureImageProvider *captureProvider = new CaptureImageProvider();
    engine.addImageProvider("capture", captureProvider);
    
    QObject::connect(
        &engine,
        &QQmlApplicationEngine::objectCreationFailed,
        &app,
        []() { QCoreApplication::exit(-1); },
        Qt::QueuedConnection);
    
    // 连接对象创建完成信号，用于设置图像提供者的引用和事件连接
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreated,
        [captureProvider](QObject *obj, const QUrl &objUrl) {
            if (!obj) return;
            
            // 查找 CaptureManager 和 SlowMotionPlayer
            CaptureManager *capMgr = obj->findChild<CaptureManager*>("captureManager");
            SlowMotionPlayer *slowMo = obj->findChild<SlowMotionPlayer*>("slowMotionPlayer");
            
            if (capMgr) {
                captureProvider->setCaptureManager(capMgr);
                
                // 连接事件总线信号到 CaptureManager
                QObject::connect(EventBus::instance(), &EventBus::captureTriggered,
                                 capMgr, &CaptureManager::capture);
                QObject::connect(EventBus::instance(), &EventBus::clearTriggered,
                                 capMgr, &CaptureManager::clearAll);
                
                qDebug() << "CaptureManager connected to EventBus";
            }
            if (slowMo) {
                captureProvider->setSlowMotionPlayer(slowMo);
                
                // 连接事件总线信号到 SlowMotionPlayer
                QObject::connect(EventBus::instance(), &EventBus::slowmoToggleTriggered,
                                 slowMo, &SlowMotionPlayer::togglePlay);
                QObject::connect(EventBus::instance(), &EventBus::nextFrameTriggered,
                                 slowMo, &SlowMotionPlayer::nextFrame);
                QObject::connect(EventBus::instance(), &EventBus::prevFrameTriggered,
                                 slowMo, &SlowMotionPlayer::prevFrame);
                
                qDebug() << "SlowMotionPlayer connected to EventBus";
            }
        });
    
    engine.loadFromModule("Aifs", "Main");
    
    qDebug() << "========== QML 加载完成，进入主循环 ==========";

    int result = app.exec();
    
    // ⭐ 程序退出时清理 frames 目录
    clearFramesDirectory();
    
    // ⭐ 程序退出时保存凭证 → 启动子进程
    {
        HttpClient *http = HttpClient::instance();
        QString username = http->getSavedUsername();
        QString password = http->getSavedPassword();
        QString pcDevId  = http->pcDeviceId();
        int pcLevel      = http->pcActivationLevel();
        if (pcLevel <= 0) pcLevel = 1;

        if (!username.isEmpty() && !password.isEmpty() && !pcDevId.isEmpty()) {
            QJsonObject authObj;
            authObj["username"]   = username;
            authObj["password"]   = password;
            authObj["pcDeviceId"] = pcDevId;
            authObj["pcLevel"]    = pcLevel;

            QFile authFile(appDirPath + "/zjc_auth.json");
            if (authFile.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
                authFile.write(QJsonDocument(authObj).toJson(QJsonDocument::Compact));
                authFile.close();
            }
            syncAuthToProgramData(username, password, pcDevId, pcLevel);
        }
    }
    
    // 程序退出，关闭日志文件
    qDebug() << "========== 程序退出 ==========";
    if (g_logFile) {
        g_logFile->close();
        delete g_logFile;
        g_logFile = nullptr;
    }
    
    return result;
}
