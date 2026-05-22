package com.acard.acard.net;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 网络管理器
 * 提供统一的网络操作接口，整合HTTP和WebSocket功能
 */
public class NetworkManager {
    
    private static volatile NetworkManager instance;
    private final HttpClientManager httpClient;
    private final StompWebSocketClient webSocketClient;
    // 全局STOMP连接状态
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private final java.util.List<java.util.function.Consumer<ConnectionState>> stateListeners = new java.util.ArrayList<>();
    
    private NetworkManager() {
        this.httpClient = HttpClientManager.getInstance();
        this.webSocketClient = StompWebSocketClient.getInstance();
    }
    
    /**
     * 获取单例实例
     */
    public static NetworkManager getInstance() {
        if (instance == null) {
            synchronized (NetworkManager.class) {
                if (instance == null) {
                    instance = new NetworkManager();
                }
            }
        }
        return instance;
    }
    
    // 业务API方法
    
    /**
     * 控制端注册
     * @param username 用户名（6位字母或数字）
     * @param password 密码（6-20位）
     * @param nickname 昵称（6位数字，自动生成）
     * @return 注册响应
     */
    public CompletableFuture<ApiResponse<RegisterResponse>> registerControl(String username, String password, String nickname) {
        RegisterRequest request = new RegisterRequest(username, password, nickname);
        return httpClient.post("/api/auth/register/control", request, RegisterResponse.class);
    }
    
    /**
     * 用户登录（控制端）
     * @param username 用户名
     * @param password 密码
     * @return 登录响应
     */
    public CompletableFuture<ApiResponse<LoginResponse>> login(String username, String password) {
        java.util.Map<String, String> request = new java.util.HashMap<>();
        request.put("username", username);
        request.put("password", password);
        return httpClient.post("/api/auth/login/control", request, LoginResponse.class);
    }
    
    /**
     * 用户登录（控制端，指定设备）
     * @param username 用户名
     * @param password 密码
     * @param deviceUsername 设备端账号（可选）
     * @return 登录响应
     */
    public CompletableFuture<ApiResponse<LoginResponse>> login(String username, String password, String deviceUsername) {
        java.util.Map<String, String> request = new java.util.HashMap<>();
        request.put("username", username);
        request.put("password", password);
        if (deviceUsername != null && !deviceUsername.isEmpty()) {
            request.put("deviceUsername", deviceUsername);
        }
        return httpClient.post("/api/auth/login/control", request, LoginResponse.class);
    }
    
    /**
     * 查询绑定设备列表
     * @param controlUsername 控制端账号
     * @return 设备列表响应
     */
    public CompletableFuture<ApiResponse<BindingDeviceListResponse>> getBindingDevices(String controlUsername) {
        return httpClient.get("/api/binding/devices?controlUsername=" + controlUsername, BindingDeviceListResponse.class);
    }
    
    /**
     * 批量查询在线状态
     * @param controlUsernames 控制端账号列表
     * @return 在线状态响应
     */
    public CompletableFuture<ApiResponse<OnlineStatusResponse>> getOnlineStatus(java.util.List<String> controlUsernames) {
        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("controlUsernames", controlUsernames);
        return httpClient.post("/api/binding/online-status", request, OnlineStatusResponse.class);
    }
    
    /**
     * 设置设备备注
     * @param controlUsername 控制端账号
     * @param deviceUsername 设备端账号
     * @param remark 备注内容（可为空，表示清空备注）
     * @return 设置备注响应
     */
    public CompletableFuture<ApiResponse<SetRemarkResponse>> setRemark(String controlUsername, String deviceUsername, String remark) {
        SetRemarkRequest request = new SetRemarkRequest(controlUsername, deviceUsername, remark);
        return httpClient.post("/api/binding/set-remark", request, SetRemarkResponse.class);
    }
    
    // HTTP请求方法
    
    /**
     * GET请求
     */
    public <T> CompletableFuture<ApiResponse<T>> get(String endpoint, Class<T> responseType) {
        return httpClient.get(endpoint, responseType);
    }
    
    /**
     * GET请求（带参数）
     */
    public <T> CompletableFuture<ApiResponse<T>> get(String endpoint, Map<String, String> params, Class<T> responseType) {
        return httpClient.get(endpoint, params, responseType);
    }
    
    /**
     * POST请求
     */
    public <T> CompletableFuture<ApiResponse<T>> post(String endpoint, Object requestBody, Class<T> responseType) {
        return httpClient.post(endpoint, requestBody, responseType);
    }
    
    /**
     * PUT请求
     */
    public <T> CompletableFuture<ApiResponse<T>> put(String endpoint, Object requestBody, Class<T> responseType) {
        return httpClient.put(endpoint, requestBody, responseType);
    }
    
    /**
     * DELETE请求
     */
    public <T> CompletableFuture<ApiResponse<T>> delete(String endpoint, Class<T> responseType) {
        return httpClient.delete(endpoint, responseType);
    }
    
    /**
     * DELETE请求（带请求体）
     */
    public <T> CompletableFuture<ApiResponse<T>> delete(String endpoint, Object requestBody, Class<T> responseType) {
        return httpClient.delete(endpoint, requestBody, responseType);
    }
    
    // WebSocket方法
    
    /**
     * 连接WebSocket
     */
    public CompletableFuture<Void> connectWebSocket() {
        connectionState = ConnectionState.CONNECTING;
        dispatchState(connectionState);
        return webSocketClient.connect(new StompWebSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                connectionState = ConnectionState.CONNECTED;
                dispatchState(connectionState);
            }
            @Override
            public void onDisconnected(String reason) {
                connectionState = ConnectionState.DISCONNECTED;
                dispatchState(connectionState);
            }
            @Override
            public void onError(Exception error) {
                connectionState = ConnectionState.ERROR;
                dispatchState(connectionState);
            }
        });
    }
    
    /**
     * 连接WebSocket（带回调）
     */
    public CompletableFuture<Void> connectWebSocket(StompWebSocketClient.ConnectionCallback callback) {
        connectionState = ConnectionState.CONNECTING;
        dispatchState(connectionState);
        // 包装原始回调，先更新全局状态再转发
        StompWebSocketClient.ConnectionCallback wrapper = new StompWebSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                connectionState = ConnectionState.CONNECTED;
                dispatchState(connectionState);
                if (callback != null) callback.onConnected();
            }
            @Override
            public void onDisconnected(String reason) {
                connectionState = ConnectionState.DISCONNECTED;
                dispatchState(connectionState);
                if (callback != null) callback.onDisconnected(reason);
            }
            @Override
            public void onError(Exception error) {
                connectionState = ConnectionState.ERROR;
                dispatchState(connectionState);
                if (callback != null) callback.onError(error);
            }
        };
        return webSocketClient.connect(wrapper);
    }
    
    /**
     * 订阅WebSocket消息
     */
    public void subscribeWebSocket(String destination, java.util.function.Consumer<String> messageHandler) {
        webSocketClient.subscribe(destination, messageHandler);
    }
    
    /**
     * 取消WebSocket订阅
     */
    public void unsubscribeWebSocket(String destination) {
        webSocketClient.unsubscribe(destination);
    }
    
    /**
     * 发送WebSocket消息
     */
    public void sendWebSocketMessage(String destination, Object message) {
        webSocketClient.sendMessage(destination, message);
    }
    
    /**
     * 断开WebSocket连接
     */
    public void disconnectWebSocket() {
        webSocketClient.disconnect();
        connectionState = ConnectionState.DISCONNECTED;
        dispatchState(connectionState);
    }
    
    /**
     * 检查WebSocket连接状态
     */
    public boolean isWebSocketConnected() {
        return webSocketClient.isConnected();
    }

    /** 添加状态监听器 */
    public void addConnectionStateListener(java.util.function.Consumer<ConnectionState> listener) {
        if (listener != null) stateListeners.add(listener);
    }

    /** 移除状态监听器 */
    public void removeConnectionStateListener(java.util.function.Consumer<ConnectionState> listener) {
        if (listener != null) stateListeners.remove(listener);
    }

    /** 获取当前连接状态 */
    public ConnectionState getConnectionState() {
        return connectionState;
    }

    /** 分发状态变化到监听器 */
    private void dispatchState(ConnectionState state) {
        for (var l : stateListeners) {
            try { l.accept(state); } catch (Exception ignore) {}
        }
    }
    
    // 配置方法
    
    /**
     * 设置基础URL
     */
    public void setBaseUrl(String baseUrl) {
        NetworkConfig.getInstance().setBaseUrl(baseUrl);
    }
    
    /**
     * 设置WebSocket URL
     */
    public void setWebSocketUrl(String websocketUrl) {
        NetworkConfig.getInstance().setWebsocketUrl(websocketUrl);
    }
    
    /**
     * 设置连接超时时间
     */
    public void setConnectTimeout(int timeoutSeconds) {
        NetworkConfig.getInstance().setConnectTimeoutSeconds(timeoutSeconds);
    }
    
    /**
     * 设置读取超时时间
     */
    public void setReadTimeout(int timeoutSeconds) {
        NetworkConfig.getInstance().setReadTimeoutSeconds(timeoutSeconds);
    }
    
    /**
     * 设置写入超时时间
     */
    public void setWriteTimeout(int timeoutSeconds) {
        NetworkConfig.getInstance().setWriteTimeoutSeconds(timeoutSeconds);
    }
    
    /**
     * 启用/禁用日志
     */
    public void setLoggingEnabled(boolean enabled) {
        NetworkConfig.getInstance().setEnableLogging(enabled);
    }
    
    /**
     * 关闭网络管理器并释放资源
     */
    public void shutdown() {
        webSocketClient.shutdown();
        httpClient.shutdown();
    }
}