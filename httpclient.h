#ifndef HTTPCLIENT_H
#define HTTPCLIENT_H

#include <QObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QJsonObject>
#include <QJsonArray>
#include <QJsonDocument>
#include <QSettings>
#include <QSysInfo>

/**
 * 登录响应数据结构
 */
struct LoginResponse {
    QString token;
    QString username;
    QString userType;
    QString currentDeviceId;
    QString currentDeviceUsername;
    int bindingCount = 0;
    QString membershipType;
    QString status;
    QString message;
    QJsonArray bindingList;
    int pcActivationLevel = 0;  // PC端等级，1=豪华版，2=至尊版
    QString pcLevelName;         // 等级名称："豪华版"或"至尊版"
    QString pcExpireAt;          // 至尊版到期时间（豪华版为空）
    bool pcValid = true;         // 当前等级是否有效
    int deviceLevel = 1;         // iOS设备等级：0=试用, 1=标清, 2=高清, 3=超清, 4=4K
    
    bool isValid() const { return !token.isEmpty(); }
};

/**
 * 保存的账号信息
 */
struct SavedAccount {
    QString username;
    QString password;
    QString lastDeviceUsername;  // 上次选择的设备账号
    QString lastDeviceDisplay;   // 上次选择的设备显示文本
};

/**
 * API 响应包装
 */
struct ApiResponse {
    int code = 0;
    QString message;
    bool success = false;
    QJsonObject data;
    
    static ApiResponse fromJson(const QByteArray &json, int httpCode);
    static ApiResponse error(int code, const QString &msg);
};

/**
 * HTTP 客户端管理器
 * 提供全局的 HTTP 网络请求功能
 */
class HttpClient : public QObject
{
    Q_OBJECT
    
public:
    static HttpClient* instance();
    
    // 设置服务器地址
    void setBaseUrl(const QString &url);
    Q_INVOKABLE QString baseUrl() const { return m_baseUrl; }
    
    // 设置认证 Token
    void setAuthToken(const QString &token);
    Q_INVOKABLE QString authToken() const { return m_authToken; }
    
    // WebSocket URL
    Q_INVOKABLE QString websocketUrl() const;
    
    // 登录信息
    Q_INVOKABLE QString loggedInUsername() const { return m_loggedInUsername; }
    Q_INVOKABLE QString currentDeviceId() const { return m_currentDeviceId; }
    Q_INVOKABLE int pcActivationLevel() const { return m_pcActivationLevel; }
    Q_INVOKABLE QString pcLevelName() const { return m_pcLevelName; }
    Q_INVOKABLE QString pcExpireAt() const { return m_pcExpireAt; }
    Q_INVOKABLE QString pcDeviceId() const { return m_pcDeviceId; }
    Q_INVOKABLE int deviceLevel() const { return m_deviceLevel; }
    Q_INVOKABLE QVariantList levelFps() const { return m_levelFps; }
    Q_INVOKABLE QVariantList levelExposureFps() const { return m_levelExposureFps; }
    Q_INVOKABLE QJsonArray iceServers() const { return m_iceServers; }
    Q_INVOKABLE bool highSpeed240Allowed() const { return m_highSpeed240Allowed; }
    Q_INVOKABLE void copyToClipboard(const QString &text);
    
    // 登录接口（pcLevel: 1=豪华版, 2=至尊版）
    Q_INVOKABLE void login(const QString &username, const QString &password, int pcLevel, const QString &deviceUsername = QString());
    
    // 注册接口（带昵称）
    Q_INVOKABLE void registerUser(const QString &username, const QString &password, const QString &nickname);
    
    // 绑定设备接口（旧接口，保留兼容）
    Q_INVOKABLE void bindDevice(const QString &controlUsername, const QString &deviceUsername, const QString &password);
    
    // 获取绑定设备列表
    Q_INVOKABLE void getBindingDevices(const QString &controlUsername);
    
    // ============ 新设备绑定接口 ============
    // 获取扫码绑定二维码数据
    Q_INVOKABLE void getQRCodeData();
    
    // 查询待验证绑定
    Q_INVOKABLE void getPendingBindings();
    
    // 验证控制端（扫码绑定第二步）
    Q_INVOKABLE void verifyControl(int bindingId, const QString &secondaryPassword);
    
    // 手动绑定设备
    Q_INVOKABLE void manualBind(const QString &deviceNickname, const QString &password, const QString &secondaryPassword);
    
    // 批量查询在线状态（用于切换账号）
    Q_INVOKABLE void getOnlineStatus(const QStringList &controlUsernames);
    
    // 设置设备备注
    Q_INVOKABLE void setRemark(const QString &controlUsername, const QString &deviceUsername, const QString &remark);
    
    // Windows端解绑设备
    Q_INVOKABLE void windowsUnbind(qlonglong bindingId, const QString &password);
    
    // 修改设备登录密码和管理密码
    Q_INVOKABLE void changeDevicePassword(const QString &controlUsername, const QString &deviceUsername, 
                                          const QString &currentSecondaryPassword, 
                                          const QString &newLoginPassword, const QString &newSecondaryPassword);

    // 修改当前登录(控制端)账号自己的登录密码
    Q_INVOKABLE void changeLoginPassword(const QString &oldPassword, const QString &newPassword);

    // PC端自助删除控制账号（同时解除其所有 iOS/Android 设备绑定）
    Q_INVOKABLE void deletePcAccount(const QString &username, const QString &password);
    
    // ============ iOS相机设定接口 ============
    // 获取相机设定（登录成功后调用）
    Q_INVOKABLE void getThinConfig();

    // ⭐ 拉取 iOS 滤镜默认值 (后台动态配置, 用于 PC iosFilterPopup 滑块的 from/to/默认值/步进/联动)
    //   成功时发 iosFilterDefaultsReceived(configJson) 信号, 失败发 iosFilterDefaultsFailed.
    Q_INVOKABLE void getIosFilterDefaults();

    // ⭐ 拉取 iOS 三链路开关/硬件默认值/LUT (GET /api/config/ios-pipeline)
    //   成功时发 iosPipelineReceived(configJson) 信号, 失败发 iosPipelineFailed.
    Q_INVOKABLE void getIosPipeline();

    // 更新对焦距离
    Q_INVOKABLE void updateFocusDistance(double value);
    
    // 更新曝光补偿
    Q_INVOKABLE void updateExposure(int value);
    
    // 更新图像闪烁
    Q_INVOKABLE void updateFlicker(int value);
    
    // 更新帧率
    Q_INVOKABLE void updateFps(int value);
    
    // 更新镜头变倍
    Q_INVOKABLE void updateZoom(double value);
    
    // 更新清晰度(码率)
    Q_INVOKABLE void updateClarity(int value);
    
    // 更新摄像头方向
    Q_INVOKABLE void updateDirection(const QString &direction);
    
    // 更新画质类型（4k/ultra/high/standard）
    Q_INVOKABLE void updateQualityType(const QString &type);
    
    // 获取缓存的相机设定值（MainPage 初始化时使用）
    Q_INVOKABLE double getCachedZoom() const { return m_cachedZoom; }
    Q_INVOKABLE QString getCachedQualityType() const { return m_cachedQualityType; }
    Q_INVOKABLE int getCachedFps() const { return m_cachedFps; }
    Q_INVOKABLE QString getCachedDirection() const { return m_cachedDirection; }
    
    // ============ 本地存储（多账号支持）============
    // 保存账号信息（添加或更新到账号列表）
    Q_INVOKABLE void saveAccount(const QString &username, const QString &password, 
                                  const QString &deviceUsername = QString(), 
                                  const QString &deviceDisplay = QString());
    
    // 获取所有已保存的账号列表
    Q_INVOKABLE QStringList getSavedAccounts() const;
    
    // 获取指定账号的密码
    Q_INVOKABLE QString getAccountPassword(const QString &username) const;
    
    // 获取指定账号的设备信息
    Q_INVOKABLE QString getAccountDeviceUsername(const QString &username) const;
    Q_INVOKABLE QString getAccountDeviceDisplay(const QString &username) const;
    
    // 读取上次登录的账号（兼容旧接口）
    Q_INVOKABLE QString getSavedUsername() const;
    Q_INVOKABLE QString getSavedPassword() const;
    Q_INVOKABLE QString getSavedDeviceUsername() const;
    Q_INVOKABLE QString getSavedDeviceDisplay() const;
    
    // 退出登录（只清除token，保留账号列表）
    Q_INVOKABLE void logout();
    
    // 清除保存的账号（清除所有）
    Q_INVOKABLE void clearSavedAccount();
    
    // 删除指定账号
    Q_INVOKABLE void removeAccount(const QString &username);
    
signals:
    // 登录结果信号
    void loginSuccess(const QString &token, const QString &deviceId, const QString &deviceUsername, const QJsonArray &bindingList, int pcActivationLevel, const QString &pcLevelName, const QString &pcExpireAt, int deviceLevel, const QVariantList &levelFps, const QVariantList &levelExposureFps, const QJsonArray &iceServers);
    void loginFailed(int code, const QString &message);
    
    // 注册结果信号
    void registerSuccess(const QString &username, const QString &message);
    void registerFailed(int code, const QString &message);
    
    // 绑定设备结果信号
    void bindDeviceSuccess(const QString &message);
    void bindDeviceFailed(int code, const QString &message);
    
    // 获取绑定设备列表结果信号
    void bindingDevicesReceived(const QJsonArray &devices);
    void bindingDevicesFailed(int code, const QString &message);
    
    // 二维码数据结果信号
    void qrCodeDataReceived(const QString &controlUsername);
    void qrCodeDataFailed(int code, const QString &message);
    
    // 待验证绑定结果信号
    void pendingBindingsReceived(const QJsonArray &bindings);
    void pendingBindingsFailed(int code, const QString &message);
    
    // 验证控制端结果信号
    void verifyControlSuccess(const QString &deviceId, const QString &deviceUsername);
    void verifyControlFailed(int code, const QString &message);
    
    // 手动绑定结果信号
    void manualBindSuccess(const QString &deviceId, const QString &deviceUsername);
    void manualBindFailed(int code, const QString &message);
    
    // 在线状态结果信号
    void onlineStatusReceived(const QJsonArray &list);
    void onlineStatusFailed(int code, const QString &message);
    
    // 设置备注结果信号
    void setRemarkSuccess(const QString &controlUsername, const QString &deviceUsername, const QString &remark);
    void setRemarkFailed(int code, const QString &message);
    
    // 解绑结果信号
    void unbindSuccess(qlonglong bindingId, const QString &message);
    void unbindFailed(int code, const QString &message);
    
    // 修改设备管理密码结果信号
    void changePasswordSuccess(const QString &deviceUsername, const QString &message, int notifyCount, int unbindCount);
    void changePasswordFailed(int code, const QString &message);

    // 修改当前登录账号密码结果信号
    void changeLoginPasswordSuccess(const QString &message);
    void changeLoginPasswordFailed(int code, const QString &message);

    // PC端自助删除控制账号结果信号
    void deletePcAccountSuccess(const QString &username, const QString &message);
    void deletePcAccountFailed(const QString &username, int code, const QString &message);
    
    // iOS相机设定结果信号
    void cameraSettingSuccess(const QString &ptype, const QString &message);
    void cameraSettingFailed(const QString &ptype, int code, const QString &message);
    
    // 获取相机设定结果信号
    void thinConfigReceived(double focus, int exposureBias, int cjfps, int fps, int bitrate, const QString &direction, const QString &type, double zoom);
    void thinConfigFailed(int code, const QString &message);

    // ⭐ iOS 滤镜默认值 (后台动态配置)
    //   configJson 是后端 SystemConfig 表中存的整段 JSON 字符串, QML 端解析后应用到滑块属性
    void iosFilterDefaultsReceived(const QString &configJson);
    void iosFilterDefaultsFailed(int code, const QString &message);

    // ⭐ iOS 三链路开关/硬件/LUT 配置 ({switches, hardware, lut} 的 JSON 字符串)
    void iosPipelineReceived(const QString &configJson);
    void iosPipelineFailed(int code, const QString &message);

private:
    explicit HttpClient(QObject *parent = nullptr);
    ~HttpClient();
    
    // 通用 POST 请求
    QNetworkReply* post(const QString &endpoint, const QJsonObject &body);
    
    // 通用 GET 请求
    QNetworkReply* get(const QString &endpoint);
    
    // 通用 PUT 请求
    QNetworkReply* put(const QString &endpoint, const QJsonObject &body);
    
    // 发送iOS相机设定更新（通用方法）
    void sendCameraSettingUpdate(const QString &ptype, const QJsonObject &config);
    
    // 构建完整 URL
    QString buildUrl(const QString &endpoint) const;
    
    // 判断是否为免认证接口
    bool isAuthExemptEndpoint(const QString &endpoint) const;
    
    // 生成/获取PC设备唯一标识
    QString generatePcDeviceId();
    
private:
    static HttpClient *s_instance;
    QNetworkAccessManager *m_manager;
    QString m_baseUrl;
    QString m_authToken;
    QString m_loggedInUsername;
    QString m_currentDeviceId;
    int m_pcActivationLevel = 0;  // PC端等级，1=豪华版，2=至尊版
    QString m_pcLevelName;         // 等级名称
    QString m_pcExpireAt;          // 至尊版到期时间
    QString m_pcDeviceId;          // PC设备唯一标识
    int m_deviceLevel = 1;         // iOS设备等级：0=试用, 1=标清, 2=高清, 3=超清, 4=4K
    QVariantList m_levelFps;       // 各等级对应FPS上限，下标0=试用, 1=高清, 2=超清, 3=超高清, 4=超高帧
    QVariantList m_levelExposureFps; // 各等级对应超级帧率上限，下标0=试用, 1=高清, 2=超清, 3=超高清, 4=超高帧
    QJsonArray m_iceServers;         // P2P ICE 服务器列表
    bool m_highSpeed240Allowed = false; // 240fps高速模式总开关（从后端 device.highspeed240.enabled 获取）
    
    // 相机设定缓存（登录成功后从 getThinConfig 获取）
    double m_cachedZoom = 1.0;
    QString m_cachedQualityType = "high";
    int m_cachedFps = 30;
    QString m_cachedDirection = "-1";
};

#endif // HTTPCLIENT_H

