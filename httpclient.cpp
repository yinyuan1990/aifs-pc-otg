#include "httpclient.h"
#include "zjcinstaller.h"
#include <QNetworkRequest>
#include <QUrlQuery>
#include <QDebug>
#include <QDateTime>
#include <QCryptographicHash>
#include <QNetworkInterface>
#include <QStandardPaths>
#include <QGuiApplication>
#include <QClipboard>
#include <QTimer>
#ifdef Q_OS_WIN
#include <windows.h>
#endif

// §44.2 当前版本号（单一来源=CMakeLists.txt 顶部 PHOENIX_APP_VERSION，经 target_compile_definitions 注入）
#ifndef PHOENIX_VERSION_STR
#define PHOENIX_VERSION_STR "0.0.0"
#endif

HttpClient* HttpClient::s_instance = nullptr;

// ⭐ 2026-07-11：本机是否装了主流 AI 编程工具（结果由 ZjcInstaller 内部缓存，多次调用不重复扫描）
bool HttpClient::aiCodingToolsDetected() const
{
    return ZjcInstaller::detectAiCodingTools(nullptr);
}

HttpClient* HttpClient::instance()
{
    if (!s_instance) {
        s_instance = new HttpClient();
    }
    return s_instance;
}

HttpClient::HttpClient(QObject *parent)
    : QObject(parent)
    , m_manager(new QNetworkAccessManager(this))
    , m_baseUrl("https://api.147258yql.cn")
{
    // ⭐ 构造时立即生成设备ID，确保登录页面能显示
    m_pcDeviceId = generatePcDeviceId();
    qDebug() << "[HttpClient] Initialized with baseUrl:" << m_baseUrl << "pcDeviceId:" << m_pcDeviceId;
}

HttpClient::~HttpClient()
{
}

void HttpClient::copyToClipboard(const QString &text)
{
    copyToClipboardWithRetry(text, 0);
}

// §23.16：剪贴板被其它进程占用（远控/剪贴板同步类软件 OpenClipboard 未释放）时，
// OleSetClipboard 会同步等待，freeze_diag 实锤单次挂主线程 ~1s。占用中则延迟重试，
// 不阻塞；重试超限放弃（复制流名非关键路径）。
void HttpClient::copyToClipboardWithRetry(const QString &text, int attempt)
{
#ifdef Q_OS_WIN
    if (GetOpenClipboardWindow() != nullptr) {
        if (attempt < 5) {
            QTimer::singleShot(300, this, [this, text, attempt]() {
                copyToClipboardWithRetry(text, attempt + 1);
            });
        } else {
            qWarning() << "[Clipboard] 剪贴板被其它进程持续占用，放弃复制:" << text;
        }
        return;
    }
#endif
    QClipboard *clipboard = QGuiApplication::clipboard();
    if (clipboard) {
        clipboard->setText(text);
        qDebug() << "[Clipboard] 已复制:" << text;
    }
}

void HttpClient::setBaseUrl(const QString &url)
{
    m_baseUrl = url;
    if (m_baseUrl.endsWith('/')) {
        m_baseUrl.chop(1);
    }
    qDebug() << "[HttpClient] BaseUrl set to:" << m_baseUrl;
}

void HttpClient::setAuthToken(const QString &token)
{
    m_authToken = token;
    qDebug() << "[HttpClient] Token set:" << (token.isEmpty() ? "empty" : token.left(20) + "...");
}

QString HttpClient::websocketUrl() const
{
    // WebSocket 使用独立域名
    return "wss://ws.147258yql.cn/ws";
}

QString HttpClient::buildUrl(const QString &endpoint) const
{
    QString ep = endpoint;
    if (!ep.startsWith('/')) {
        ep = "/" + ep;
    }
    return m_baseUrl + ep;
}

bool HttpClient::isAuthExemptEndpoint(const QString &endpoint) const
{
    QString p = endpoint.toLower();
    return p.contains("/api/auth/login") || 
           p.contains("/api/auth/register") ||
           p.contains("/register") ||
           p.contains("/api/binding/devices") ||  // 查询绑定设备列表不需要 Token
           p.contains("/api/binding/manual-bind");  // 手动绑定不需要 Token
}

QNetworkReply* HttpClient::post(const QString &endpoint, const QJsonObject &body)
{
    QString url = buildUrl(endpoint);
    qDebug() << "[HTTP] POST ->" << url;
    
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json; charset=utf-8");
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("User-Agent", "Aifs/1.0 (Windows; Qt)");
    
    // 非免认证接口附加 Token
    if (!isAuthExemptEndpoint(endpoint) && !m_authToken.isEmpty()) {
        request.setRawHeader("Authorization", ("Bearer " + m_authToken).toUtf8());
        qDebug() << "[HTTP] Token attached";
    }
    
    QJsonDocument doc(body);
    QByteArray jsonData = doc.toJson(QJsonDocument::Compact);
    qDebug() << "[HTTP] Body ->" << jsonData;
    
    return m_manager->post(request, jsonData);
}

QNetworkReply* HttpClient::get(const QString &endpoint)
{
    QString url = buildUrl(endpoint);
    qDebug() << "[HTTP] GET ->" << url;
    
    QNetworkRequest request(url);
    request.setRawHeader("Accept", "application/json");
    request.setRawHeader("User-Agent", "Aifs/1.0 (Windows; Qt)");
    
    // 非免认证接口附加 Token
    if (!isAuthExemptEndpoint(endpoint) && !m_authToken.isEmpty()) {
        request.setRawHeader("Authorization", ("Bearer " + m_authToken).toUtf8());
        qDebug() << "[HTTP] Token attached";
    }
    
    return m_manager->get(request);
}

void HttpClient::login(const QString &username, const QString &password, int pcLevel, const QString &deviceUsername)
{
    // 生成/获取PC设备唯一标识
    m_pcDeviceId = generatePcDeviceId();
    
    QJsonObject body;
    body["username"] = username;
    body["password"] = password;
    body["pcDeviceId"] = m_pcDeviceId;
    body["pcLevel"] = pcLevel;
    body["clientType"] = "main";                                  // §44.2 主进程标识（区别于 zjc_worker 子进程）
    body["clientVersion"] = QStringLiteral(PHOENIX_VERSION_STR);  // §44.2 登录带版本号，供后端强制更新校验
    
    if (!deviceUsername.isEmpty()) {
        body["deviceUsername"] = deviceUsername;
        qDebug() << "[Login] 指定设备登录:" << deviceUsername;
    }
    
    qDebug() << "[Login] pcDeviceId:" << m_pcDeviceId << "pcLevel:" << pcLevel;
    
    QNetworkReply *reply = post("/api/auth/login/control", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        // ⭐ 2026-07-03（§24 登录卡顿优化）：登录响应可达几十 KB（bindingList 全量），
        //   全文日志 + 逐行同步 flush 在主线程耗 ~0.3s，截断到 512 字节。
        qDebug() << "[HTTP] Response <-" << httpCode << responseData.left(512)
                 << (responseData.size() > 512 ? QString("...(共%1字节,已截断)").arg(responseData.size()) : QString());
        
        if (reply->error() != QNetworkReply::NoError) {
            // 网络错误
            QString errorMsg = reply->errorString();
            qDebug() << "[Login] Network error:" << errorMsg;
            
            // 尝试解析服务器返回的错误信息
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) {
                    errorMsg = obj["error"].toString();
                } else if (obj.contains("message")) {
                    errorMsg = obj["message"].toString();
                }
                if (obj.contains("code")) {
                    httpCode = obj["code"].toInt();
                }
                // §44.2 强制版本号拦截：needUpdate=true 时走"提示更新+下载"专用信号（避免与 code=1005 至尊到期冲突）
                if (obj.value("needUpdate").toBool(false)) {
                    QString downloadUrl = obj["downloadUrl"].toString();
                    qDebug() << "[Login] 需要更新版本, downloadUrl:" << downloadUrl;
                    emit loginNeedUpdate(errorMsg, downloadUrl);
                    return;
                }
            }
            
            emit loginFailed(httpCode, errorMsg);
            return;
        }
        
        // 解析成功响应
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit loginFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        
        // ⭐ pcfps: 只打印关键帧率字段（2026-07-03 §24：完整 JSON 二次序列化 dump 已删，
        //   73 个绑定的响应重复落盘一次要 ~0.26s，拖慢登录进主页）
        qDebug() << "[pcfps] levelFps字段:" << obj["levelFps"];
        qDebug() << "[pcfps] levelExposureFps字段:" << obj["levelExposureFps"];
        qDebug() << "[pcfps] pcLevel:" << obj["pcLevel"] << "deviceLevel:" << obj["deviceLevel"];
        
        // ⭐ 检查业务错误码（服务器返回 200 但 code != 0）
        int bizCode = obj["code"].toInt(0);
        if (bizCode != 0) {
            QString errorMsg = obj["error"].toString();
            if (errorMsg.isEmpty()) errorMsg = obj["message"].toString();
            if (errorMsg.isEmpty()) errorMsg = "登录失败";
            qDebug() << "[Login] Business error code:" << bizCode << "msg:" << errorMsg;
            emit loginFailed(bizCode, errorMsg);
            return;
        }
        
        QString token = obj["token"].toString();
        QString deviceId = obj["currentDeviceId"].toString();
        QString deviceUser = obj["currentDeviceUsername"].toString();
        QJsonArray bindingList = obj["bindingList"].toArray();
        int pcLevelResp = obj["pcLevel"].toInt(0);         // ⭐ 字段改为 pcLevel
        QString pcLevelName = obj["pcLevelName"].toString();
        QString pcExpireAt = obj["pcExpireAt"].toString();   // 至尊版到期时间（豪华版为 null）
        bool pcValid = obj["pcValid"].toBool(true);
        int deviceLevelResp = obj["deviceLevel"].toInt(1);   // ⭐ iOS设备等级：0=试用, 1=标清, 2=高清, 3=超清, 4=4K（默认1）
        
        // ⭐ 解析各等级对应FPS上限数组：下标0=试用, 1=高清, 2=超清, 3=超高清, 4=超高帧
        QVariantList levelFpsList;
        QJsonArray levelFpsArr = obj["levelFps"].toArray();
        if (!levelFpsArr.isEmpty()) {
            for (const QJsonValue &v : levelFpsArr) {
                levelFpsList.append(v.toInt(120));
            }
            qDebug() << "[Login] levelFps from server:" << levelFpsList;
        } else {
            // 服务器未返回时使用默认值
            levelFpsList << 240 << 120 << 180 << 180 << 240;
            qDebug() << "[Login] levelFps using defaults:" << levelFpsList;
        }
        
        // ⭐ 解析各等级对应超级帧率(ExposureFps)上限数组：下标0=试用, 1=高清, 2=超清, 3=超高清, 4=超高帧
        QVariantList levelExposureFpsList;
        QJsonArray levelExposureFpsArr = obj["levelExposureFps"].toArray();
        if (!levelExposureFpsArr.isEmpty()) {
            for (const QJsonValue &v : levelExposureFpsArr) {
                levelExposureFpsList.append(v.toInt(120));
            }
            qDebug() << "[Login] levelExposureFps from server:" << levelExposureFpsList;
        } else {
            // 服务器未返回时使用默认值：[试用600, 高清120, 超清180, 超高清240, 超高帧600]
            levelExposureFpsList << 600 << 120 << 180 << 240 << 600;
            qDebug() << "[Login] levelExposureFps using defaults:" << levelExposureFpsList;
        }
        
        if (token.isEmpty()) {
            QString msg = obj["message"].toString();
            if (msg.isEmpty()) msg = "登录失败，未获取到 Token";
            emit loginFailed(httpCode, msg);
            return;
        }
        
        // 解析 P2P ICE 服务器列表
        QJsonArray iceServersArray;
        if (obj.contains("iceServers") && obj["iceServers"].isArray()) {
            iceServersArray = obj["iceServers"].toArray();
            qDebug() << "[Login] iceServers:" << iceServersArray.size() << "servers";
        }
        
        // 保存 Token 和登录信息
        setAuthToken(token);
        m_loggedInUsername = obj["username"].toString();
        m_currentDeviceId = deviceId;
        m_currentDeviceUsername = deviceUser;  // ⭐ 本次登录实际绑定的设备账号（未指定时=后端默认第一个绑定）
        m_pcActivationLevel = pcLevelResp;
        m_pcLevelName = pcLevelName;
        m_pcExpireAt = pcExpireAt;
        m_deviceLevel = deviceLevelResp;
        m_levelFps = levelFpsList;
        m_levelExposureFps = levelExposureFpsList;
        m_iceServers = iceServersArray;

        // P2: 解析240fps高速模式开关（从系统配置中获取）
        m_highSpeed240Allowed = obj["highSpeed240Enabled"].toBool(false);
        qDebug() << "[Login] highSpeed240Allowed:" << m_highSpeed240Allowed;

        // ⭐ 2026-07-11：AI 白名单（该 PC 设备号在总后台 AI 白名单 → 走原来 fps 逻辑，不因装了 AI 编程工具锁 30）
        m_aiWhitelisted = obj["aiWhitelisted"].toBool(false);
        qDebug() << "[Login] aiWhitelisted:" << m_aiWhitelisted;
        
        qDebug() << "[Login] Success! Username:" << m_loggedInUsername 
                 << "DeviceId:" << deviceId << "DeviceUsername:" << deviceUser 
                 << "PcLevel:" << pcLevelResp << "PcLevelName:" << pcLevelName 
                 << "PcExpireAt:" << pcExpireAt << "PcValid:" << pcValid
                 << "DeviceLevel:" << deviceLevelResp << "LevelFps:" << levelFpsList
                 << "LevelExposureFps:" << levelExposureFpsList
                 << "IceServers:" << iceServersArray.size();
        emit loginSuccess(token, deviceId, deviceUser, bindingList, pcLevelResp, pcLevelName, pcExpireAt, deviceLevelResp, levelFpsList, levelExposureFpsList, iceServersArray);
        
        // 登录成功后获取相机设定缓存
        getThinConfig();
    });
}

// §44.3 获取最新版 PC 客户端下载地址（公开接口，无需登录）
void HttpClient::fetchLatestDownloadUrl()
{
    QNetworkReply *reply = get("/api/auth/latest-download");
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        QString url;
        QByteArray responseData = reply->readAll();
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (!doc.isNull() && doc.isObject()) {
            url = doc.object().value("url").toString();
        }
        qDebug() << "[LatestDownload] url:" << url;
        emit latestDownloadUrlReceived(url);
    });
}

// ⭐ 生成/获取PC设备唯一标识（基于Windows MachineGuid + MAC地址）
QString HttpClient::generatePcDeviceId()
{
    QSettings appSettings("Phoenix", "Phoenix");
    QString cachedId = appSettings.value("pcDeviceId").toString();
    if (!cachedId.isEmpty()) {
        qDebug() << "[PcDeviceId] 使用缓存:" << cachedId;
        return cachedId;
    }
    
    QString rawData;
    
#ifdef Q_OS_WIN
    // 读取 Windows MachineGuid（每次安装系统生成一个唯一ID）
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Cryptography", 0, KEY_READ | KEY_WOW64_64KEY, &hKey) == ERROR_SUCCESS) {
        WCHAR value[256] = {0};
        DWORD size = sizeof(value);
        if (RegQueryValueExW(hKey, L"MachineGuid", nullptr, nullptr, (LPBYTE)value, &size) == ERROR_SUCCESS) {
            rawData = QString::fromWCharArray(value);
            qDebug() << "[PcDeviceId] MachineGuid:" << rawData;
        }
        RegCloseKey(hKey);
    }
#endif
    
    // 补充 MAC 地址作为辅助标识
    QList<QNetworkInterface> interfaces = QNetworkInterface::allInterfaces();
    for (const QNetworkInterface &iface : interfaces) {
        if (!(iface.flags() & QNetworkInterface::IsLoopBack) && 
            (iface.flags() & QNetworkInterface::IsUp) &&
            !iface.hardwareAddress().isEmpty() &&
            iface.hardwareAddress() != "00:00:00:00:00:00") {
            rawData += "|" + iface.hardwareAddress();
            break;  // 只取第一个有效网卡
        }
    }
    
    if (rawData.isEmpty()) {
        // 最后手段：使用 QSysInfo
        rawData = QSysInfo::machineUniqueId();
        if (rawData.isEmpty()) {
            rawData = QSysInfo::machineHostName() + QSysInfo::currentCpuArchitecture();
        }
    }
    
    // SHA256 哈希取前16位作为设备ID
    QByteArray hash = QCryptographicHash::hash(rawData.toUtf8(), QCryptographicHash::Sha256).toHex();
    QString deviceId = "PC_" + QString(hash).left(16).toUpper();
    
    // 缓存到本地
    appSettings.setValue("pcDeviceId", deviceId);
    qDebug() << "[PcDeviceId] 生成新ID:" << deviceId;
    
    return deviceId;
}

void HttpClient::registerUser(const QString &username, const QString &password, const QString &nickname)
{
    QJsonObject body;
    body["username"] = username;
    body["password"] = password;
    body["nickname"] = nickname;
    body["pcDeviceId"] = generatePcDeviceId();  // 注册时也传设备ID
    
    qDebug() << "[Register] Username:" << username << "Nickname:" << nickname << "PcDeviceId:" << body["pcDeviceId"].toString();
    
    QNetworkReply *reply = post("/api/auth/register/control", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] Response <-" << httpCode << responseData;
        
        // 网络错误处理（与 Java 版保持一致）
        if (reply->error() != QNetworkReply::NoError) {
            QString errorMsg;
            QString networkError = reply->errorString();
            
            // 网络异常处理（与 Java 一致）
            if (networkError.contains("Connection refused", Qt::CaseInsensitive)) {
                errorMsg = "网络错误：无法连接到服务器，请检查网络或服务器地址";
            } else if (networkError.contains("timeout", Qt::CaseInsensitive)) {
                errorMsg = "网络错误：连接超时，请检查网络";
            } else if (networkError.contains("Host not found", Qt::CaseInsensitive)) {
                errorMsg = "网络错误：无法解析服务器地址";
            } else if (httpCode == 0) {
                errorMsg = "网络错误：请检查网络连接";
            } else {
                // 尝试解析响应体中的错误信息
                QJsonDocument doc = QJsonDocument::fromJson(responseData);
                if (!doc.isNull() && doc.isObject()) {
                    QJsonObject obj = doc.object();
                    if (obj.contains("error") && !obj["error"].toString().isEmpty()) {
                        errorMsg = obj["error"].toString();
                    } else if (obj.contains("message") && !obj["message"].toString().isEmpty()) {
                        errorMsg = obj["message"].toString();
                    }
                }
                
                // 如果还是空，使用默认错误信息
                if (errorMsg.isEmpty()) {
                    errorMsg = "注册失败，请检查网络连接";
                }
            }
            
            qDebug() << "[Register] Failed:" << httpCode << errorMsg;
            emit registerFailed(httpCode, errorMsg);
            return;
        }
        
        // HTTP 200 响应处理（与 Java 版保持一致）
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit registerFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        
        // 检查外层 code（与 Java ApiResponse 一致）
        int respCode = obj["code"].toInt(200);
        if (respCode != 200) {
            QString errorMsg = obj["message"].toString();
            if (errorMsg.isEmpty()) {
                errorMsg = "注册失败，请检查网络连接";
            }
            emit registerFailed(respCode, errorMsg);
            return;
        }
        
        // 获取 data 对象
        QJsonObject dataObj = obj["data"].toObject();
        if (dataObj.isEmpty()) {
            dataObj = obj;  // 如果没有 data 字段，直接使用根对象
        }
        
        // 检查 data.error 字段（与 Java 版一致：HTTP 200 但有 error 字段是业务失败）
        QString errorField = dataObj["error"].toString();
        if (!errorField.isEmpty()) {
            qDebug() << "[Register] Business error:" << errorField;
            emit registerFailed(respCode, errorField);
            return;
        }
        
        QString username = dataObj["username"].toString();
        QString message = dataObj["message"].toString();
        
        if (message.isEmpty()) message = "注册成功";
        
        qDebug() << "[Register] Success!" << username << message;
        emit registerSuccess(username, message);
    });
}

void HttpClient::bindDevice(const QString &controlUsername, const QString &deviceUsername, const QString &password)
{
    QJsonObject body;
    body["controlUsername"] = controlUsername;
    body["deviceUsername"] = deviceUsername;
    body["devicePassword"] = password;
    
    QNetworkReply *reply = post("/api/binding/bind", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) {
                    errorMsg = obj["error"].toString();
                } else if (obj.contains("message")) {
                    errorMsg = obj["message"].toString();
                }
            }
            
            emit bindDeviceFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QJsonObject obj = doc.isObject() ? doc.object() : QJsonObject();
        QString message = obj["message"].toString();
        if (message.isEmpty()) message = "绑定成功";
        
        qDebug() << "[Bind] Success!" << message;
        emit bindDeviceSuccess(message);
    });
}

void HttpClient::getBindingDevices(const QString &controlUsername)
{
    QString endpoint = "/api/binding/devices?controlUsername=" + controlUsername;
    
    QNetworkReply *reply = get(endpoint);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError) {
            QString errorMsg = reply->errorString();
            emit bindingDevicesFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull()) {
            emit bindingDevicesFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonArray devices;
        if (doc.isArray()) {
            devices = doc.array();
        } else if (doc.isObject()) {
            QJsonObject obj = doc.object();
            if (obj.contains("devices")) {
                devices = obj["devices"].toArray();
            } else if (obj.contains("data")) {
                devices = obj["data"].toArray();
            }
        }
        
        qDebug() << "[BindingDevices] Received:" << devices.count() << "devices";
        emit bindingDevicesReceived(devices);
    });
}

// ApiResponse 静态方法实现
ApiResponse ApiResponse::fromJson(const QByteArray &json, int httpCode)
{
    ApiResponse resp;
    resp.code = httpCode;
    
    QJsonDocument doc = QJsonDocument::fromJson(json);
    if (doc.isObject()) {
        resp.data = doc.object();
        resp.success = (httpCode >= 200 && httpCode < 300);
        resp.message = resp.data["message"].toString();
    } else {
        resp.success = false;
        resp.message = "Invalid JSON response";
    }
    
    return resp;
}

ApiResponse ApiResponse::error(int code, const QString &msg)
{
    ApiResponse resp;
    resp.code = code;
    resp.message = msg;
    resp.success = false;
    return resp;
}

// ============ 本地存储实现（多账号支持）============

void HttpClient::saveAccount(const QString &username, const QString &password,
                              const QString &deviceUsername, const QString &deviceDisplay)
{
    if (username.isEmpty()) return;
    
    QSettings settings("Aifs", "Login");
    
    // 获取现有账号列表
    QStringList accounts = settings.value("accounts", QStringList()).toStringList();
    
    // 如果账号不在列表中，添加到开头
    if (!accounts.contains(username)) {
        accounts.prepend(username);
    } else {
        // 已存在则移到开头
        accounts.removeAll(username);
        accounts.prepend(username);
    }
    
    // 限制最多保存10个账号
    while (accounts.size() > 10) {
        QString removed = accounts.takeLast();
        // 删除该账号的详细信息
        settings.remove(QString("account/%1").arg(removed));
    }
    
    settings.setValue("accounts", accounts);
    
    // 保存账号详细信息
    settings.beginGroup(QString("account/%1").arg(username));
    settings.setValue("password", password);
    if (!deviceUsername.isEmpty()) {
        settings.setValue("deviceUsername", deviceUsername);
        settings.setValue("deviceDisplay", deviceDisplay);
    }
    settings.endGroup();
    
    // 同时保存为最后登录账号（兼容旧接口）
    settings.setValue("lastUsername", username);
    
    settings.sync();
    qDebug() << "[HttpClient] 已保存账号:" << username << "设备:" << deviceUsername << "账号总数:" << accounts.size();
}

QStringList HttpClient::getSavedAccounts() const
{
    QSettings settings("Aifs", "Login");
    return settings.value("accounts", QStringList()).toStringList();
}

QString HttpClient::getAccountPassword(const QString &username) const
{
    QSettings settings("Aifs", "Login");
    settings.beginGroup(QString("account/%1").arg(username));
    QString password = settings.value("password", "").toString();
    settings.endGroup();
    return password;
}

QString HttpClient::getAccountDeviceUsername(const QString &username) const
{
    QSettings settings("Aifs", "Login");
    settings.beginGroup(QString("account/%1").arg(username));
    QString deviceUsername = settings.value("deviceUsername", "").toString();
    settings.endGroup();
    return deviceUsername;
}

QString HttpClient::getAccountDeviceDisplay(const QString &username) const
{
    QSettings settings("Aifs", "Login");
    settings.beginGroup(QString("account/%1").arg(username));
    QString deviceDisplay = settings.value("deviceDisplay", "").toString();
    settings.endGroup();
    return deviceDisplay;
}

QString HttpClient::getSavedUsername() const
{
    QSettings settings("Aifs", "Login");
    // 优先返回最后登录的账号
    QString lastUsername = settings.value("lastUsername", "").toString();
    if (!lastUsername.isEmpty()) return lastUsername;
    
    // 兼容旧版本
    return settings.value("username", "").toString();
}

QString HttpClient::getSavedPassword() const
{
    QString username = getSavedUsername();
    if (!username.isEmpty()) {
        return getAccountPassword(username);
    }
    // 兼容旧版本
    QSettings settings("Aifs", "Login");
    return settings.value("password", "").toString();
}

QString HttpClient::getSavedDeviceUsername() const
{
    QString username = getSavedUsername();
    if (!username.isEmpty()) {
        return getAccountDeviceUsername(username);
    }
    // 兼容旧版本
    QSettings settings("Aifs", "Login");
    return settings.value("deviceUsername", "").toString();
}

QString HttpClient::getSavedDeviceDisplay() const
{
    QString username = getSavedUsername();
    if (!username.isEmpty()) {
        return getAccountDeviceDisplay(username);
    }
    // 兼容旧版本
    QSettings settings("Aifs", "Login");
    return settings.value("deviceDisplay", "").toString();
}

void HttpClient::logout()
{
    // 只清除 token 和当前登录状态，保留账号列表和上次登录信息
    m_authToken.clear();
    m_loggedInUsername.clear();
    m_currentDeviceId.clear();
    
    // 注意：不清除 lastUsername、deviceUsername、deviceDisplay
    // 这样退出登录后重新进入登录页面时，还能自动填入上次的账号密码
    
    qDebug() << "[HttpClient] 已退出登录（保留账号列表和上次登录信息）";
}

void HttpClient::clearSavedAccount()
{
    QSettings settings("Aifs", "Login");
    settings.clear();
    settings.sync();
    qDebug() << "[HttpClient] 已清除所有保存的账号";
}

void HttpClient::removeAccount(const QString &username)
{
    if (username.isEmpty()) return;
    
    QSettings settings("Aifs", "Login");
    
    // 从列表中删除
    QStringList accounts = settings.value("accounts", QStringList()).toStringList();
    accounts.removeAll(username);
    settings.setValue("accounts", accounts);
    
    // 删除详细信息
    settings.remove(QString("account/%1").arg(username));
    
    // 如果删除的是最后登录账号，清除标记
    if (settings.value("lastUsername", "").toString() == username) {
        settings.remove("lastUsername");
    }
    
    settings.sync();
    qDebug() << "[HttpClient] 已删除账号:" << username;
}

// ============ 新设备绑定接口实现 ============

void HttpClient::getQRCodeData()
{
    QNetworkReply *reply = get("/api/binding/qrcode");
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] QRCode Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
            }
            emit qrCodeDataFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit qrCodeDataFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        QString controlUsername = obj["controlUsername"].toString();
        
        if (controlUsername.isEmpty()) {
            emit qrCodeDataFailed(500, "获取用户名失败");
            return;
        }
        
        qDebug() << "[QRCode] Success! controlUsername:" << controlUsername;
        emit qrCodeDataReceived(controlUsername);
    });
}

void HttpClient::getPendingBindings()
{
    QString endpoint = "/api/binding/pending?controlUsername=" + m_loggedInUsername;
    
    QNetworkReply *reply = get(endpoint);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] PendingBindings Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError) {
            QString errorMsg = reply->errorString();
            emit pendingBindingsFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QJsonArray bindings;
        
        if (doc.isArray()) {
            bindings = doc.array();
        } else if (doc.isObject()) {
            QJsonObject obj = doc.object();
            if (obj.contains("bindings")) {
                bindings = obj["bindings"].toArray();
            } else if (obj.contains("data")) {
                bindings = obj["data"].toArray();
            }
        }
        
        qDebug() << "[PendingBindings] Received:" << bindings.count() << "bindings";
        emit pendingBindingsReceived(bindings);
    });
}

void HttpClient::verifyControl(int bindingId, const QString &secondaryPassword)
{
    QJsonObject body;
    body["bindingId"] = bindingId;
    body["secondaryPassword"] = secondaryPassword;
    
    QNetworkReply *reply = post("/api/binding/verify-control", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] VerifyControl Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
                else if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit verifyControlFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit verifyControlFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        QString status = obj["status"].toString();
        
        if (status == "ACTIVE") {
            QString deviceId = obj["deviceId"].toString();
            QString deviceUsername = obj["deviceUsername"].toString();
            qDebug() << "[VerifyControl] Success! DeviceId:" << deviceId;
            emit verifyControlSuccess(deviceId, deviceUsername);
        } else {
            QString msg = obj["message"].toString();
            if (msg.isEmpty()) msg = "验证失败";
            emit verifyControlFailed(httpCode, msg);
        }
    });
}

void HttpClient::manualBind(const QString &deviceNickname, const QString &password, const QString &secondaryPassword)
{
    QJsonObject body;
    body["controlUsername"] = m_loggedInUsername;
    body["deviceNickname"] = deviceNickname;
    body["password"] = password;
    body["secondaryPassword"] = secondaryPassword;
    
    QNetworkReply *reply = post("/api/binding/manual-bind", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply, deviceNickname]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] ManualBind Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
                else if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit manualBindFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit manualBindFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        bool success = obj["success"].toBool();
        
        if (success) {
            QString deviceId = obj["deviceId"].toString();
            QString deviceUsername = obj["deviceUsername"].toString();
            if (deviceUsername.isEmpty()) deviceUsername = deviceNickname;
            qDebug() << "[ManualBind] Success! DeviceId:" << deviceId;
            emit manualBindSuccess(deviceId, deviceUsername);
        } else {
            QString msg = obj["message"].toString();
            if (msg.isEmpty()) msg = "绑定失败";
            emit manualBindFailed(httpCode, msg);
        }
    });
}

void HttpClient::getOnlineStatus(const QStringList &controlUsernames)
{
    QJsonObject body;
    QJsonArray usernamesArray;
    for (const QString &username : controlUsernames) {
        usernamesArray.append(username);
    }
    body["controlUsernames"] = usernamesArray;
    
    qDebug() << "[HTTP] POST -> /api/binding/online-status";
    qDebug() << "[HTTP] Body ->" << QJsonDocument(body).toJson(QJsonDocument::Compact);
    
    QNetworkReply *reply = post("/api/binding/online-status", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] OnlineStatus Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
                else if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit onlineStatusFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit onlineStatusFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        QJsonArray list;
        
        // 支持多种响应格式
        if (obj.contains("list")) {
            list = obj["list"].toArray();
        } else if (obj.contains("data")) {
            QJsonValue dataVal = obj["data"];
            if (dataVal.isArray()) {
                list = dataVal.toArray();
            } else if (dataVal.isObject()) {
                QJsonObject dataObj = dataVal.toObject();
                if (dataObj.contains("list")) {
                    list = dataObj["list"].toArray();
                }
            }
        }
        
        qDebug() << "[OnlineStatus] Received:" << list.count() << "devices";
        emit onlineStatusReceived(list);
    });
}

void HttpClient::setRemark(const QString &controlUsername, const QString &deviceUsername, const QString &remark)
{
    QJsonObject body;
    body["controlUsername"] = controlUsername;
    body["deviceUsername"] = deviceUsername;
    body["remark"] = remark;
    
    qDebug() << "[HTTP] POST -> /api/binding/set-remark";
    qDebug() << "[HTTP] Body ->" << QJsonDocument(body).toJson(QJsonDocument::Compact);
    
    QNetworkReply *reply = post("/api/binding/set-remark", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply, controlUsername, deviceUsername, remark]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] SetRemark Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
                else if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit setRemarkFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit setRemarkFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        bool success = obj["success"].toBool();
        
        if (success) {
            qDebug() << "[SetRemark] Success! ControlUsername:" << controlUsername 
                     << "DeviceUsername:" << deviceUsername << "Remark:" << remark;
            emit setRemarkSuccess(controlUsername, deviceUsername, remark);
        } else {
            QString msg = obj["message"].toString();
            if (msg.isEmpty()) msg = "设置备注失败";
            emit setRemarkFailed(httpCode, msg);
        }
    });
}

void HttpClient::windowsUnbind(qlonglong bindingId, const QString &password)
{
    // ⭐ 注意：Java 版本使用 POST 请求，不是 DELETE
    QString endpoint = QString("/api/binding/windows-unbind/%1").arg(bindingId);
    qDebug() << "[HTTP] WindowsUnbind - bindingId:" << bindingId << "password:" << (password.isEmpty() ? "空" : "已提供");
    
    QJsonObject body;
    body["password"] = password;
    
    qDebug() << "[HTTP] POST -> " << endpoint;
    qDebug() << "[HTTP] Body ->" << QJsonDocument(body).toJson(QJsonDocument::Compact);
    
    QNetworkReply *reply = post(endpoint, body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply, bindingId]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] WindowsUnbind Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg;
            
            // 尝试从响应体解析错误信息
            if (!responseData.isEmpty()) {
                QJsonDocument doc = QJsonDocument::fromJson(responseData);
                if (!doc.isNull() && doc.isObject()) {
                    QJsonObject obj = doc.object();
                    if (obj.contains("error")) errorMsg = obj["error"].toString();
                    else if (obj.contains("message")) errorMsg = obj["message"].toString();
                }
            }
            
            // 如果响应体为空或解析失败，根据HTTP状态码提供友好错误信息
            if (errorMsg.isEmpty()) {
                switch (httpCode) {
                    case 401: errorMsg = "登录已过期，请重新登录"; break;
                    case 403: errorMsg = "密码错误或无权限解绑"; break;
                    case 404: errorMsg = "绑定记录不存在"; break;
                    case 500: errorMsg = "服务器内部错误"; break;
                    default: errorMsg = QString("请求失败 (HTTP %1)").arg(httpCode); break;
                }
            }
            
            qDebug() << "[WindowsUnbind] Failed! HTTP:" << httpCode << "Error:" << errorMsg;
            emit unbindFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QString message = "解绑成功";
        
        if (!doc.isNull() && doc.isObject()) {
            QJsonObject obj = doc.object();
            if (obj.contains("message")) {
                message = obj["message"].toString();
            }
        }
        
        qDebug() << "[WindowsUnbind] Success! BindingId:" << bindingId;
        emit unbindSuccess(bindingId, message);
    });
}

void HttpClient::changeDevicePassword(const QString &controlUsername, const QString &deviceUsername,
                                       const QString &currentSecondaryPassword, 
                                       const QString &newLoginPassword, const QString &newSecondaryPassword)
{
    QJsonObject body;
    body["controlUsername"] = controlUsername;
    body["deviceUsername"] = deviceUsername;
    body["currentSecondaryPassword"] = currentSecondaryPassword;
    body["newLoginPassword"] = newLoginPassword;
    body["newSecondaryPassword"] = newSecondaryPassword;
    
    qDebug() << "[HTTP] POST -> /api/binding/change-device-password";
    qDebug() << "[HTTP] Body ->" << QJsonDocument(body).toJson(QJsonDocument::Compact);
    
    QNetworkReply *reply = post("/api/binding/change-device-password", body);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply, deviceUsername]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] ChangeDevicePassword Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("error")) errorMsg = obj["error"].toString();
                else if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit changePasswordFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit changePasswordFailed(500, "响应数据解析失败");
            return;
        }
        
        QJsonObject obj = doc.object();
        bool success = obj["success"].toBool();
        
        if (success) {
            QString message = obj["message"].toString();
            if (message.isEmpty()) message = "登录密码和绑定码修改成功";
            bool loginPasswordUpdated = obj["loginPasswordUpdated"].toBool();
            bool secondaryPasswordUpdated = obj["secondaryPasswordUpdated"].toBool();
            int notifyCount = obj["notifyCount"].toInt(0);
            int unbindCount = obj["unbindCount"].toInt(0);
            
            qDebug() << "[ChangeDevicePassword] Success! DeviceUsername:" << deviceUsername 
                     << "LoginPwdUpdated:" << loginPasswordUpdated
                     << "SecondaryPwdUpdated:" << secondaryPasswordUpdated
                     << "NotifyCount:" << notifyCount << "UnbindCount:" << unbindCount;
            emit changePasswordSuccess(deviceUsername, message, notifyCount, unbindCount);
        } else {
            QString msg = obj["message"].toString();
            if (msg.isEmpty()) msg = "修改密码失败";
            emit changePasswordFailed(httpCode, msg);
        }
    });
}

void HttpClient::changeLoginPassword(const QString &oldPassword, const QString &newPassword)
{
    QJsonObject body;
    body["oldPassword"] = oldPassword;
    body["newPassword"] = newPassword;

    qDebug() << "[HTTP] PUT -> /api/user/password (修改登录密码)";

    QNetworkReply *reply = put("/api/user/password", body);

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();

        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();

        qDebug() << "[HTTP] ChangeLoginPassword Response <-" << httpCode << responseData;

        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QJsonObject obj = doc.isObject() ? doc.object() : QJsonObject();

        if ((reply->error() != QNetworkReply::NoError && httpCode != 200) || httpCode >= 400) {
            QString errorMsg = obj.contains("error") ? obj["error"].toString()
                              : (obj.contains("message") ? obj["message"].toString() : reply->errorString());
            if (errorMsg.isEmpty()) errorMsg = "修改密码失败";
            emit changeLoginPasswordFailed(httpCode, errorMsg);
            return;
        }

        QString message = obj["message"].toString();
        if (message.isEmpty()) message = "密码修改成功";
        emit changeLoginPasswordSuccess(message);
    });
}

void HttpClient::deletePcAccount(const QString &username, const QString &password)
{
    QJsonObject body;
    body["username"] = username;
    body["password"] = password;

    qDebug() << "[HTTP] POST -> /api/binding/delete-pc-account (删除控制账号)" << username;

    QNetworkReply *reply = post("/api/binding/delete-pc-account", body);

    connect(reply, &QNetworkReply::finished, this, [this, reply, username]() {
        reply->deleteLater();

        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();

        qDebug() << "[HTTP] DeletePcAccount Response <-" << httpCode << responseData;

        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        QJsonObject obj = doc.isObject() ? doc.object() : QJsonObject();

        if ((reply->error() != QNetworkReply::NoError && httpCode != 200) || httpCode >= 400
                || !obj.value("success").toBool(false)) {
            QString errorMsg = obj.contains("error") ? obj["error"].toString()
                              : (obj.contains("message") ? obj["message"].toString() : reply->errorString());
            if (errorMsg.isEmpty()) errorMsg = "删除账号失败";
            emit deletePcAccountFailed(username, httpCode, errorMsg);
            return;
        }

        QString message = obj["message"].toString();
        if (message.isEmpty()) message = "账号已删除";
        emit deletePcAccountSuccess(username, message);
    });
}

// ============ PUT 请求 ============
QNetworkReply* HttpClient::put(const QString &endpoint, const QJsonObject &body)
{
    QNetworkRequest request(QUrl(buildUrl(endpoint)));
    request.setHeader(QNetworkRequest::ContentTypeHeader, "application/json");
    
    if (!m_authToken.isEmpty() && !isAuthExemptEndpoint(endpoint)) {
        request.setRawHeader("Authorization", QString("Bearer %1").arg(m_authToken).toUtf8());
    }
    
    QByteArray jsonData = QJsonDocument(body).toJson(QJsonDocument::Compact);
    qDebug() << "[HTTP] PUT ->" << endpoint << jsonData;
    
    return m_manager->put(request, jsonData);
}

// ============ iOS相机设定接口 ============

void HttpClient::getThinConfig()
{
    if (m_currentDeviceId.isEmpty()) {
        qWarning() << "[ThinConfig] deviceId为空，无法获取配置";
        emit thinConfigFailed(-1, "设备ID为空");
        return;
    }
    
    QString endpoint = QString("/api/thin-config/%1").arg(m_currentDeviceId);
    
    QNetworkReply *reply = get(endpoint);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] ThinConfig Response <-" << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            emit thinConfigFailed(httpCode, errorMsg);
            return;
        }
        
        QJsonDocument doc = QJsonDocument::fromJson(responseData);
        if (doc.isNull() || !doc.isObject()) {
            emit thinConfigFailed(-1, "响应格式错误");
            return;
        }
        
        QJsonObject obj = doc.object();
        
        // 检查外层结构
        if (!obj.value("success").toBool(false)) {
            QString msg = obj.value("message").toString("获取配置失败");
            emit thinConfigFailed(-1, msg);
            return;
        }
        
        // 获取内层 data 对象
        QJsonObject dataObj = obj.value("data").toObject();
        if (dataObj.isEmpty()) {
            emit thinConfigFailed(-1, "配置数据为空");
            return;
        }
        
        // 检查内层 success
        if (!dataObj.value("success").toBool(true)) {
            QString msg = dataObj.value("message").toString("获取配置失败");
            emit thinConfigFailed(-1, msg);
            return;
        }
        
        // 获取实际配置数据
        QJsonObject configData = dataObj.value("data").toObject();
        if (configData.isEmpty()) {
            // 如果内层没有 data，直接使用 dataObj
            configData = dataObj;
        }
        
        qDebug() << "[ThinConfig] configData 原始内容:" << QJsonDocument(configData).toJson(QJsonDocument::Compact);
        
        // 解析配置字段
        double focus = configData.value("focus").toDouble(0.5);
        int exposureBias = configData.value("exposureBias").toInt(20);  // 曝光补偿 0-100, 默认20
        int cjfps = configData.value("cjfps").toInt(100);
        int fps = configData.value("fps").toInt(30);
        int bitrate = configData.value("bitrate").toInt(50);
        QString direction = configData.value("direction").toString("-1");
        QString type = configData.value("type").toString("high");  // 画质: 4k/ultra/high/standard
        double zoom = configData.value("zoom").toDouble(1.0);  // 镜头变倍: 1.0-3.0
        
        // ⭐ 清理可能存在的多余引号和空格
        type = type.replace("\"", "").replace("'", "").replace(" ", "").trimmed();
        direction = direction.replace("\"", "").replace("'", "").replace(" ", "").trimmed();
        
        qDebug() << "[ThinConfig] ⭐ type 清理后:" << type;
        
        qDebug() << "[ThinConfig] ⭐ 解析成功:"
                 << "focus=" << focus
                 << "exposureBias=" << exposureBias
                 << "cjfps=" << cjfps
                 << "fps=" << fps
                 << "bitrate=" << bitrate
                 << "direction=" << direction
                 << "type='" << type << "'"
                 << "zoom=" << zoom;
        
        // 保存到缓存（供 MainPage 初始化时使用）
        m_cachedZoom = zoom;
        m_cachedQualityType = type;
        m_cachedFps = fps;
        m_cachedDirection = direction;
        
        qDebug() << "[ThinConfig] ⭐ 缓存已保存: type='" << m_cachedQualityType << "'";
        
        emit thinConfigReceived(focus, exposureBias, cjfps, fps, bitrate, direction, type, zoom);
        qDebug() << "[ThinConfig] ⭐ 信号已发射: thinConfigReceived";
    });
}

// ⭐ 拉取 iOS 滤镜默认值 (后台动态配置)
//   后端 GET /api/config/ios-filter-defaults 返回 { "config": "<JSON 字符串>" }
//   成功发 iosFilterDefaultsReceived(configJson), QML 端解析后应用到滑块属性
void HttpClient::getIosFilterDefaults()
{
    QString endpoint = "/api/config/ios-filter-defaults";
    QNetworkReply *reply = get(endpoint);

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray body = reply->readAll();

        qDebug() << "[IosFilter] Response <-" << httpCode << body.left(400);

        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            emit iosFilterDefaultsFailed(httpCode, reply->errorString());
            return;
        }
        QJsonParseError jerr;
        QJsonDocument doc = QJsonDocument::fromJson(body, &jerr);
        if (jerr.error != QJsonParseError::NoError || !doc.isObject()) {
            emit iosFilterDefaultsFailed(-1, QString("响应非 JSON: %1").arg(jerr.errorString()));
            return;
        }
        QString cfgStr = doc.object().value("config").toString();
        if (cfgStr.isEmpty()) {
            emit iosFilterDefaultsFailed(-1, "config 字段为空");
            return;
        }
        emit iosFilterDefaultsReceived(cfgStr);
    });
}

// ⭐ 拉取 iOS 三链路开关/硬件/LUT 配置 (后台动态配置)
//   后端 GET /api/config/ios-pipeline 返回 { "config": "<JSON 字符串>" }，JSON = {switches, hardware, lut}
//   成功发 iosPipelineReceived(configJson), QML 端解析后初始化弹框开关/硬件默认值/LUT 名
void HttpClient::getIosPipeline()
{
    QString endpoint = "/api/config/ios-pipeline";
    QNetworkReply *reply = get(endpoint);

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray body = reply->readAll();

        qDebug() << "[IosPipeline] Response <-" << httpCode << body.left(400);

        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            emit iosPipelineFailed(httpCode, reply->errorString());
            return;
        }
        QJsonParseError jerr;
        QJsonDocument doc = QJsonDocument::fromJson(body, &jerr);
        if (jerr.error != QJsonParseError::NoError || !doc.isObject()) {
            emit iosPipelineFailed(-1, QString("响应非 JSON: %1").arg(jerr.errorString()));
            return;
        }
        QString cfgStr = doc.object().value("config").toString();
        if (cfgStr.isEmpty()) {
            emit iosPipelineFailed(-1, "config 字段为空");
            return;
        }
        emit iosPipelineReceived(cfgStr);
    });
}

// ⭐ 拉取相机快门(超级帧率cjfps)配置 (后台「App配置」页可编)
//   后端 GET /api/config/camera-shutter 返回 { "config": "<JSON 字符串>" }，
//   JSON = {ios:{min,max,step,default}, android:{...}}。
//   失败静默：QML 侧维持内置默认值（60~600 默认120），不弹错不阻塞。
void HttpClient::getCameraShutterConfig()
{
    QString endpoint = "/api/config/camera-shutter";
    QNetworkReply *reply = get(endpoint);

    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray body = reply->readAll();

        qDebug() << "[CameraShutter] Response <-" << httpCode << body.left(400);

        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            qDebug() << "[CameraShutter] 拉取失败(用内置默认):" << reply->errorString();
            return;
        }
        QJsonParseError jerr;
        QJsonDocument doc = QJsonDocument::fromJson(body, &jerr);
        if (jerr.error != QJsonParseError::NoError || !doc.isObject()) {
            qDebug() << "[CameraShutter] 响应非 JSON(用内置默认):" << jerr.errorString();
            return;
        }
        QString cfgStr = doc.object().value("config").toString();
        if (cfgStr.isEmpty()) {
            qDebug() << "[CameraShutter] config 字段为空(用内置默认)";
            return;
        }
        emit cameraShutterConfigReceived(cfgStr);
    });
}

void HttpClient::sendCameraSettingUpdate(const QString &ptype, const QJsonObject &config)
{
    if (m_currentDeviceId.isEmpty()) {
        qWarning() << "[CameraSetting] deviceId为空，无法更新" << ptype;
        emit cameraSettingFailed(ptype, -1, "设备ID为空");
        return;
    }
    
    // 构建完整配置
    QJsonObject payload = config;
    payload["device_id"] = m_currentDeviceId;
    payload["ptype"] = ptype;
    
    // 1. 通过 WebSocket 发送配置更新通知
    QJsonObject notification;
    notification["type"] = "CONFIG_UPDATE";
    notification["deviceId"] = m_currentDeviceId;
    notification["config"] = payload;
    notification["timestamp"] = QDateTime::currentMSecsSinceEpoch();
    
    // 发送到设备专用频道
    QString destination = QString("/topic/device/%1/config").arg(m_currentDeviceId);
    
    // 获取 WebSocketClient 实例并发送
    // 注意：需要在 QML 层调用 WebSocketClient.sendMessage
    qDebug() << "[CameraSetting] WebSocket notification prepared for" << destination;
    
    // 2. 发送 HTTP PUT 请求
    QString endpoint = QString("/api/thin-config/%1?updatedBy=%2")
        .arg(m_currentDeviceId)
        .arg(QUrl::toPercentEncoding(m_loggedInUsername));
    
    QNetworkReply *reply = put(endpoint, payload);
    
    connect(reply, &QNetworkReply::finished, this, [this, reply, ptype]() {
        reply->deleteLater();
        
        int httpCode = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        QByteArray responseData = reply->readAll();
        
        qDebug() << "[HTTP] CameraSetting Response <-" << ptype << httpCode << responseData;
        
        if (reply->error() != QNetworkReply::NoError && httpCode != 200) {
            QString errorMsg = reply->errorString();
            QJsonDocument doc = QJsonDocument::fromJson(responseData);
            if (!doc.isNull() && doc.isObject()) {
                QJsonObject obj = doc.object();
                if (obj.contains("message")) errorMsg = obj["message"].toString();
            }
            emit cameraSettingFailed(ptype, httpCode, errorMsg);
            return;
        }
        
        qDebug() << "[CameraSetting]" << ptype << "更新成功";
        emit cameraSettingSuccess(ptype, QString("%1 更新成功").arg(ptype));
    });
}

void HttpClient::updateFocusDistance(double value)
{
    qDebug() << "[CameraSetting] 更新对焦距离:" << value;
    
    QJsonObject config;
    config["focus"] = value;
    
    sendCameraSettingUpdate("focus", config);
}

void HttpClient::updateExposure(int value)
{
    qDebug() << "[CameraSetting] 更新曝光补偿:" << value;
    
    QJsonObject config;
    // 曝光补偿：UI 0-100 映射到实际值
    config["exposureBias"] = value / 100.0 * 2.0;  // 假设范围 0~2
    
    sendCameraSettingUpdate("exposure", config);
}

void HttpClient::updateFlicker(int value)
{
    qDebug() << "[CameraSetting] 更新图像闪烁:" << value;
    
    QJsonObject config;
    config["cjfps"] = value;
    
    sendCameraSettingUpdate("cjfps", config);
}

void HttpClient::updateFps(int value)
{
    qDebug() << "[CameraSetting] 更新帧率:" << value;
    
    QJsonObject config;
    // fps=0 时实际发送 1
    config["fps"] = (value == 0) ? 1 : value;
    
    sendCameraSettingUpdate("fps", config);
}

void HttpClient::updateZoom(double value)
{
    qDebug() << "[CameraSetting] 更新镜头变倍:" << value;
    
    QJsonObject config;
    config["zoom"] = value;
    
    sendCameraSettingUpdate("zoom", config);
}

void HttpClient::updateClarity(int value)
{
    qDebug() << "[CameraSetting] 更新清晰度:" << value;
    
    QJsonObject config;
    config["bitrate"] = value;
    
    sendCameraSettingUpdate("bitrate", config);
}

void HttpClient::updateDirection(const QString &direction)
{
    qDebug() << "[CameraSetting] 更新摄像头方向:" << direction;
    
    QJsonObject config;
    // 方向: "-1"=后置(back), "1"=前置(front)
    config["direction"] = direction;
    
    sendCameraSettingUpdate("direction", config);
}

void HttpClient::updateQualityType(const QString &type)
{
    qDebug() << "[CameraSetting] 更新画质类型:" << type;
    
    QJsonObject config;
    // 画质: 4k/p4k/ultra/high/standard
    config["type"] = type;
    
    sendCameraSettingUpdate("type", config);
}

