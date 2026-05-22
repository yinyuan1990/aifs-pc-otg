package com.acard.acard.viewmodel;

import com.acard.acard.model.User;
import com.acard.acard.net.NetworkConfig;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.LoginResponse;
import com.acard.acard.net.ApiResponse;
import com.acard.acard.storage.AuthStore;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.EventHandler;

/**
 * 登录界面的ViewModel
 * 实现MVVM模式中的业务逻辑层
 */
public class LoginViewModel {
    private final User user;
    private final BooleanProperty loginInProgress;
    private final StringProperty errorMessage;
    private final BooleanProperty loginButtonDisabled;
    private final StringProperty selectedDeviceUsername;  // 选择的设备账号
    private int lastErrorCode = 0;  // 最后一次登录失败的错误码
    
    public LoginViewModel() {
        this.user = new User();
        this.loginInProgress = new SimpleBooleanProperty(false);
        this.errorMessage = new SimpleStringProperty("");
        this.loginButtonDisabled = new SimpleBooleanProperty(false);
        this.selectedDeviceUsername = new SimpleStringProperty(null);
        
        // 绑定登录按钮状态：当用户名或密码为空时禁用
        this.loginButtonDisabled.bind(
            user.usernameProperty().isEmpty()
            .or(user.passwordProperty().isEmpty())
            .or(loginInProgress)
        );
    }
    
    /**
     * 执行登录操作
     * @param onSuccess 登录成功回调
     * @param onFailure 登录失败回调
     */
    public void login(Runnable onSuccess, Runnable onFailure) {
        if (loginInProgress.get()) {
            return;
        }
        
        // 清除之前的错误信息
        errorMessage.set("");
        
        loginInProgress.set(true);
        String username = user.getUsername();
        String password = user.getPassword();


        System.err.println("登路开始---> "+username+" password: "+password);

        // 切换账号时，清空之前缓存的数据
        com.acard.acard.storage.AuthStore authStore = com.acard.acard.storage.AuthStore.getInstance();
        com.acard.acard.net.LoginResponse cached = authStore.getLoginResponse();
        if (cached != null && cached.getUsername() != null && !cached.getUsername().equals(username)) {
            authStore.clearLogin();
        }
        
        // 配置后端基础地址
        NetworkManager network = NetworkManager.getInstance();
        network.setBaseUrl(NetworkConfig.getInstance().getBaseUrl());
        network.setLoggingEnabled(true);
        
        // 构造请求体（使用Map确保JSON序列化稳定）
        java.util.Map<String, String> req = new java.util.HashMap<>();
        req.put("username", username);
        req.put("password", password);
        
        // 如果指定了设备账号，则添加到请求中
        String deviceUsername = selectedDeviceUsername.get();
        if (deviceUsername != null && !deviceUsername.isEmpty()) {
            req.put("deviceUsername", deviceUsername);
            System.out.println("🔗 指定设备登录: " + deviceUsername);
        }

        System.err.println("登路开始---> 2");
        // 使用控制端登录接口
        network.post("/api/auth/login/control", req, LoginResponse.class)
            .thenAccept((ApiResponse<LoginResponse> resp) -> {
                Platform.runLater(() -> {
                    System.err.println("登路开始---> 3");
                    if (resp.isSuccess() && resp.getData() != null) {
                        lastErrorCode = 0;  // 成功时重置错误码
                        LoginResponse data = resp.getData();
                        // 保存 token 到全局配置
                        com.acard.acard.net.NetworkConfig.getInstance().setAuthToken(data.getToken());
                        // 缓存完整登录响应，便于后续复用
                        AuthStore.getInstance().saveLoginResponse(data);
                        
                        // ⭐ 登录成功后立即获取设备配置
                        String deviceId = data.getDeviceId();
                        if (deviceId != null && !deviceId.isBlank()) {
                            System.out.println("📡 开始获取设备配置，deviceId: " + deviceId);
                            com.acard.acard.storage.ConfigStore.getInstance()
                                .prefetchThinConfig(deviceId)
                                .thenAccept(config -> {
                                    if (config != null) {
                                        System.out.println("✅ 设备配置获取成功");
                                    } else {
                                        System.err.println("⚠️ 设备配置获取失败或为空");
                                    }
                                })
                                .exceptionally(ex -> {
                                    System.err.println("❌ 获取设备配置异常: " + ex.getMessage());
                                    return null;
                                });
                        } else {
                            System.err.println("⚠️ deviceId 为空，跳过设备配置获取");
                        }
                        
                        user.setStatus("已登录");
                        errorMessage.set("");
                        if (onSuccess != null) onSuccess.run();
                    } else {
                        System.err.println("登录失败---> code: " + resp.getCode() + ", message: " + resp.getMessage());
                        
                        // 保存错误码，供控制器使用
                        lastErrorCode = resp.getCode();
                        
                        // ⭐ 错误码 1001: 账户不存在，清除相关缓存
                        if (resp.getCode() == 1001) {
                            System.out.println("⚠️ 账户不存在，清除账户相关缓存: " + username);
                            // 清除设备列表缓存
                            com.acard.acard.storage.DeviceListStore.getInstance().clearDevices(username);
                            // 清除账户列表中的该账户
                            com.acard.acard.storage.AccountListStore.getInstance().removeAccount(username);
                            System.out.println("✅ 已清除账户 [" + username + "] 的设备列表和账户缓存");
                            // ⭐ 显示具体账户名
                            errorMessage.set("账户 " + username + " 不存在");
                        } else {
                            errorMessage.set(resp.getMessage() != null ? resp.getMessage() : "登录失败");
                        }
                        
                        user.setStatus("登录失败");
                        if (onFailure != null) onFailure.run();
                    }
                    loginInProgress.set(false);
                });
            })
            .exceptionally(ex -> {
                System.err.println("登录失败--->"+ex.getMessage());
                Platform.runLater(() -> {
                    user.setStatus("登录失败");
                    errorMessage.set("网络错误: " + ex.getMessage());
                    if (onFailure != null) onFailure.run();
                    loginInProgress.set(false);
                });
                return null;
            });
    }
    
    /**
     * 清空表单
     */
    public void clearForm() {
        user.setUsername("");
        user.setPassword("");
        user.setAccountNumber("");
        errorMessage.set("");
        user.setStatus("未登录");
    }
    
    // Getter方法用于数据绑定
    public User getUser() {
        return user;
    }
    
    public BooleanProperty loginInProgressProperty() {
        return loginInProgress;
    }
    
    public boolean isLoginInProgress() {
        return loginInProgress.get();
    }
    
    public StringProperty errorMessageProperty() {
        return errorMessage;
    }
    
    public String getErrorMessage() {
        return errorMessage.get();
    }
    
    public BooleanProperty loginButtonDisabledProperty() {
        return loginButtonDisabled;
    }
    
    public boolean isLoginButtonDisabled() {
        return loginButtonDisabled.get();
    }
    
    // 设备选择相关
    public StringProperty selectedDeviceUsernameProperty() {
        return selectedDeviceUsername;
    }
    
    public String getSelectedDeviceUsername() {
        return selectedDeviceUsername.get();
    }
    
    public void setSelectedDeviceUsername(String deviceUsername) {
        selectedDeviceUsername.set(deviceUsername);
    }
    
    // 错误码相关
    public int getLastErrorCode() {
        return lastErrorCode;
    }
    
    public void resetLastErrorCode() {
        lastErrorCode = 0;
    }
}