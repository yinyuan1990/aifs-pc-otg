package com.acard.acard.net;

import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.File;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/**
 * STOMP WebSocket客户端
 * 提供全局的WebSocket连接管理和消息订阅功能
 */
public class StompWebSocketClient {
    
    private static volatile StompWebSocketClient instance;
    private WebSocketClient webSocketClient;
    private final NetworkConfig config;
    private final Gson gson;
    private final Map<String, Consumer<String>> subscriptions;
    private final ScheduledExecutorService heartbeatExecutor;
    
    // 连接状态
    private volatile boolean connected = false;
    private volatile boolean connecting = false;
    
    // 消息ID计数器
    private int messageId = 0;
    
    // 回调接口
    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onError(Exception error);
    }
    
    private ConnectionCallback connectionCallback;
    
    private StompWebSocketClient() {
        this.config = NetworkConfig.getInstance();
        this.gson = new Gson();
        this.subscriptions = new ConcurrentHashMap<>();
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "STOMP-Heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 获取单例实例
     */
    public static StompWebSocketClient getInstance() {
        if (instance == null) {
            synchronized (StompWebSocketClient.class) {
                if (instance == null) {
                    instance = new StompWebSocketClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * 连接WebSocket服务器
     */
    public CompletableFuture<Void> connect() {
        return connect(null);
    }
    
    /**
     * 连接WebSocket服务器（带回调）
     */
    public CompletableFuture<Void> connect(ConnectionCallback callback) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        if (connected) {
            future.complete(null);
            return future;
        }
        
        if (connecting) {
            future.completeExceptionally(new NetworkException.WebSocketException("正在连接中，请稍后再试"));
            return future;
        }
        
        this.connectionCallback = callback;
        connecting = true;
        
        try {
            URI serverUri = URI.create(config.getWebsocketUrl());
            
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    System.out.println("WebSocket连接已建立");
                    
                    // 发送STOMP CONNECT帧
                    sendStompFrame("CONNECT", Map.of(
                        "accept-version", "1.0,1.1,2.0",
                        "heart-beat", "10000,10000"
                    ), null);
                }
                
                @Override
                public void onMessage(String message) {
                    handleStompMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("WebSocket连接已关闭: " + reason);
                    connected = false;
                    connecting = false;
                    
                    if (connectionCallback != null) {
                        connectionCallback.onDisconnected(reason);
                    }
                    
                    // 停止心跳
                    stopHeartbeat();
                }
                
                @Override
                public void onError(Exception ex) {
                    System.err.println("WebSocket错误: " + ex.getMessage());
                    connected = false;
                    connecting = false;
                    
                    if (connectionCallback != null) {
                        connectionCallback.onError(ex);
                    }
                    
                    future.completeExceptionally(new NetworkException.WebSocketException("WebSocket连接失败", ex));
                }
            };
            
            webSocketClient.connect();
            // —— 新增：启动断线重连监控（每5秒检查一次） ——
            startConnectionMonitor();
            
        } catch (Exception e) {
            connecting = false;
            future.completeExceptionally(new NetworkException.WebSocketException("创建WebSocket连接失败", e));
        }
        
        return future;
    }
    
    /**
     * 处理STOMP消息
     */
    private void handleStompMessage(String message) {
        try {
            String[] lines = message.split("\n");
            if (lines.length == 0) return;
            
            String command = lines[0];
            Map<String, String> headers = new ConcurrentHashMap<>();
            int bodyStartIndex = 1;
            
            // 解析头部
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) {
                    bodyStartIndex = i + 1;
                    break;
                }
                
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex);
                    String value = line.substring(colonIndex + 1);
                    headers.put(key, value);
                }
            }
            
            // 解析消息体
            StringBuilder bodyBuilder = new StringBuilder();
            for (int i = bodyStartIndex; i < lines.length; i++) {
                if (i > bodyStartIndex) bodyBuilder.append("\n");
                bodyBuilder.append(lines[i]);
            }
            String body = bodyBuilder.toString();
            
            // 处理不同类型的STOMP帧
            switch (command) {
                case "CONNECTED":
                    connected = true;
                    connecting = false;
                    System.out.println("STOMP连接已建立");
                    com.acard.acard.tools.LogTools.getInstance().logRecord4("✅ STOMP 连接成功！");
                    
                    if (connectionCallback != null) {
                        connectionCallback.onConnected();
                    }
                    
                    // 启动心跳
                    startHeartbeat();
                    break;
                    
                case "MESSAGE":
                    String destination = headers.get("destination");
                    com.acard.acard.tools.LogTools.getInstance().logRecord4("📨 STOMP MESSAGE 收到: destination=" + destination);
                    com.acard.acard.tools.LogTools.getInstance().logRecord4("📨 已订阅的频道: " + subscriptions.keySet());
                    com.acard.acard.tools.LogTools.getInstance().logRecord4("📨 消息内容: " + body);
                    
                    if (destination != null && subscriptions.containsKey(destination)) {
                        subscriptions.get(destination).accept(body);
                    } else {
                        // ⭐ 尝试匹配 /user/{username}/queue/xxx 格式
                        // 服务端 convertAndSendToUser 会发送到 /user/{username}/queue/xxx
                        // 客户端订阅的是 /user/queue/xxx
                        for (String subDest : subscriptions.keySet()) {
                            if (subDest.startsWith("/user/queue/") && destination != null 
                                && destination.contains("/queue/") && destination.startsWith("/user/")) {
                                // 提取 /queue/xxx 部分进行匹配
                                String suffix = subDest.substring("/user".length()); // /queue/binding
                                if (destination.endsWith(suffix)) {
                                    com.acard.acard.tools.LogTools.getInstance().logRecord4("📨 ✅ 用户队列匹配成功: " + subDest + " ← " + destination);
                                    subscriptions.get(subDest).accept(body);
                                    break;
                                }
                            }
                        }
                    }
                    break;
                    
                case "ERROR":
                    String errorMessage = headers.getOrDefault("message", "未知错误");
                    System.err.println("STOMP错误: " + errorMessage);
                    if (connectionCallback != null) {
                        connectionCallback.onError(new NetworkException.WebSocketException(errorMessage));
                    }
                    break;
            }
            
        } catch (Exception e) {
            System.err.println("处理STOMP消息时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 订阅消息
     */
    public void subscribe(String destination, Consumer<String> messageHandler) {
        if (!connected) {
            throw new IllegalStateException("WebSocket未连接，无法订阅消息");
        }
        
        subscriptions.put(destination, messageHandler);
        
        // 发送SUBSCRIBE帧
        sendStompFrame("SUBSCRIBE", Map.of(
            "id", "sub-" + (++messageId),
            "destination", destination
        ), null);
        
        System.out.println("已订阅: " + destination);
        com.acard.acard.tools.LogTools.getInstance().logRecord4("📡 已订阅频道: " + destination);
        com.acard.acard.tools.LogTools.getInstance().logRecord4("📡 当前所有订阅: " + subscriptions.keySet());
    }
    
    /**
     * 取消订阅
     */
    public void unsubscribe(String destination) {
        subscriptions.remove(destination);
        
        if (connected) {
            // 发送UNSUBSCRIBE帧
            sendStompFrame("UNSUBSCRIBE", Map.of(
                "id", "sub-" + destination
            ), null);
        }
        
        System.out.println("已取消订阅: " + destination);
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(String destination, Object message) {
        if (!connected) {
            throw new IllegalStateException("WebSocket未连接，无法发送消息");
        }
        
        String jsonMessage = gson.toJson(message);
        
        sendStompFrame("SEND", Map.of(
            "destination", destination,
            "content-type", "application/json"
        ), jsonMessage);
    }
    
    /**
     * 发送 RESET_PUBLISH 消息（自动从登录信息获取 deviceId）
     * 推荐使用此方法，无需手动传入 deviceId
     */
    public void sendResetPublish() {
        try {
            // 从 AuthStore 获取登录信息
            com.acard.acard.storage.AuthStore authStore = com.acard.acard.storage.AuthStore.getInstance();
            LoginResponse loginResponse = authStore.getLoginResponse();
            
            if (loginResponse == null) {
                LogTools.getInstance().logRecord3("⚠️ sendResetPublish: 未找到登录信息，无法获取 deviceId");
                return;
            }
            
            String deviceId = loginResponse.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                LogTools.getInstance().logRecord3("⚠️ sendResetPublish: deviceId 为空，跳过发送");
                return;
            }
            
            // 调用重载方法发送
            sendResetPublish(deviceId);
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ sendResetPublish: 获取 deviceId 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendResetPublish2() {
        try {
            // 从 AuthStore 获取登录信息
            com.acard.acard.storage.AuthStore authStore = com.acard.acard.storage.AuthStore.getInstance();
            LoginResponse loginResponse = authStore.getLoginResponse();

            if (loginResponse == null) {
                LogTools.getInstance().logRecord3("⚠️ sendResetPublish: 未找到登录信息，无法获取 deviceId");
                return;
            }

            String deviceId = loginResponse.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                LogTools.getInstance().logRecord3("⚠️ sendResetPublish: deviceId 为空，跳过发送");
                return;
            }

            // 调用重载方法发送
            sendResetPublish(deviceId);

        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ sendResetPublish: 获取 deviceId 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void sendRESET_SHENGDIANG() {
        try {
            // 从 AuthStore 获取登录信息
            com.acard.acard.storage.AuthStore authStore = com.acard.acard.storage.AuthStore.getInstance();
            LoginResponse loginResponse = authStore.getLoginResponse();

            if (loginResponse == null) {
                LogTools.getInstance().logRecord("⚠️ sendResetPublish: 未找到登录信息，无法获取 deviceId");
                return;
            }

            String deviceId = loginResponse.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                LogTools.getInstance().logRecord("⚠️ sendResetPublish: deviceId 为空，跳过发送");
                return;
            }

            // 调用重载方法发送
            sendRESET_SHENGDIANG(deviceId);

        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ sendResetPublish: 获取 deviceId 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendRESET_SHENGDIANG(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            LogTools.getInstance().logRecord("⚠️ sendResetPublish: deviceId 为空，跳过发送");
            return;
        }

        if (!connected) {
            LogTools.getInstance().logRecord("⚠️ sendResetPublish: WebSocket 未连接，无法发送消息");
            return;
        }

        try {
            // 构建目标 destination
            String destination = "/topic/device/" + deviceId + "/config";
            // 构建消息体
            Map<String, Object> payload = Map.of(
                    "type", "RESET_SHENGDIANG",
                    "deviceId", deviceId,
                    "timestamp", System.currentTimeMillis(),
                    "reason","后台管理员操作"
            );
            // 发送消息
            sendMessage(destination, payload);
            LogTools.getInstance().logRecord("✅ 已发送 RESET_PUBLISH 到: " + destination);

        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ 发送 RESET_PUBLISH 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void notifyDeviceConfigUpdate(ThinRemoteConfig config,String eventType) {
        try {

            com.acard.acard.storage.AuthStore authStore = com.acard.acard.storage.AuthStore.getInstance();
            LoginResponse loginResponse = authStore.getLoginResponse();

            if (loginResponse == null) {
                LogTools.getInstance().logRecord("⚠️ sendResetPublish: 未找到登录信息，无法获取 deviceId");
                return;
            }

            String deviceId = loginResponse.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                LogTools.getInstance().logRecord("⚠️ sendResetPublish: deviceId 为空，跳过发送");
                return;
            }

            LogTools.getInstance().logRecord("配置更新通知已发送: 1");
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "CONFIG_UPDATE");
            notification.put("deviceId", deviceId);
            notification.put("config", config);
            notification.put("timestamp", System.currentTimeMillis());

            // 发送到设备专用频道
            //messagingTemplate.convertAndSend("/topic/device/" + deviceId + "/config", notification);
            LogTools.getInstance().logRecord("配置更新通知已发送: 2");
            String destination = "/topic/device/" + deviceId + "/config";
            LogTools.getInstance().logRecord("配置更新通知已发送: {}"+ destination);
            sendMessage(destination,notification);

            if(eventType.equals(FileToos.CameraType)){
                 FileToos.FbCameraSettingsDialog();
            }else if(eventType.equals(FileToos.GpuViewType)){
                FileToos.FbGpuViewCameraEvent();
            }


            LogTools.getInstance().logRecord("配置更新通知已发送: {}"+ deviceId);
        } catch (Exception e) {
            LogTools.getInstance().logRecord("发送配置更新通知失败: {} "+e.getMessage());
        }
    }
    
    /**
     * 发送 RESET_PUBLISH 消息（通知前端重置推流状态）
     * 
     * @param deviceId 设备ID
     */
    public void sendResetPublish(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            LogTools.getInstance().logRecord3("⚠️ sendResetPublish: deviceId 为空，跳过发送");
            return;
        }
        
        if (!connected) {
            LogTools.getInstance().logRecord3("⚠️ sendResetPublish: WebSocket 未连接，无法发送消息");
            return;
        }
        
        try {
            // 构建目标 destination
            String destination = "/topic/device/" + deviceId + "/config";
            // 构建消息体
            Map<String, Object> payload = Map.of(
                "type", "RESET_PUBLISH",
                "deviceId", deviceId,
                "timestamp", System.currentTimeMillis()
            );
            // 发送消息
            sendMessage(destination, payload);
            LogTools.getInstance().logRecord3("✅ 已发送 RESET_PUBLISH 到: " + destination);
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ 发送 RESET_PUBLISH 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void sendResetPublish2(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            LogTools.getInstance().logRecord3("⚠️ sendResetPublish: deviceId 为空，跳过发送");
            return;
        }

        if (!connected) {
            LogTools.getInstance().logRecord3("⚠️ sendResetPublish: WebSocket 未连接，无法发送消息");
            return;
        }

        try {
            // 构建目标 destination
            String destination = "/topic/device/" + deviceId + "/config";
            // 构建消息体
            Map<String, Object> payload = Map.of(
                    "type", "RESET_LP",
                    "deviceId", deviceId,
                    "timestamp", System.currentTimeMillis()
            );
            // 发送消息
            sendMessage(destination, payload);
            LogTools.getInstance().logRecord3("✅ 已发送 RESET_PUBLISH 到: " + destination);

        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ 发送 RESET_PUBLISH 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 发送 RESET_PUBLISH 消息（重载方法，支持自定义 destination）
     * 
     * @param deviceId 设备ID
     * @param customDestination 自定义目标地址（如果为 null，使用默认格式）
     */
    public void sendResetPublish(String deviceId, String customDestination) {
        if (deviceId == null || deviceId.isEmpty()) {
            System.err.println("⚠️ sendResetPublish: deviceId 为空，跳过发送");
            return;
        }
        
        if (!connected) {
            System.err.println("⚠️ sendResetPublish: WebSocket 未连接，无法发送消息");
            return;
        }
        
        try {
            // 使用自定义 destination 或默认格式
            String destination = (customDestination != null && !customDestination.isEmpty()) 
                ? customDestination 
                : "/topic/device/" + deviceId + "/config";
            
            // 构建消息体
            Map<String, Object> payload = Map.of(
                "type", "RESET_PUBLISH",
                "deviceId", deviceId,
                "timestamp", System.currentTimeMillis()
            );
            
            // 发送消息
            sendMessage(destination, payload);
            
            System.out.println("✅ 已发送 RESET_PUBLISH 到: " + destination);
            
        } catch (Exception e) {
            System.err.println("❌ 发送 RESET_PUBLISH 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 发送STOMP帧
     */
    private void sendStompFrame(String command, Map<String, String> headers, String body) {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            return;
        }
        
        StringBuilder frame = new StringBuilder();
        frame.append(command).append("\n");
        
        if (headers != null) {
            headers.forEach((key, value) -> 
                frame.append(key).append(":").append(value).append("\n")
            );
        }
        
        frame.append("\n");
        
        if (body != null) {
            frame.append(body);
        }
        
        frame.append("\0"); // STOMP帧结束符
        
        webSocketClient.send(frame.toString());
    }
    
    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (connected && webSocketClient != null && webSocketClient.isOpen()) {
                webSocketClient.send("\n"); // 发送心跳
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
    
    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        // 心跳任务会在下次检查时自动停止
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        if (webSocketClient != null) {
            if (connected) {
                // 发送DISCONNECT帧
                sendStompFrame("DISCONNECT", null, null);
            }
            
            webSocketClient.close();
            webSocketClient = null;
        }
        
        connected = false;
        connecting = false;
        subscriptions.clear();
        stopHeartbeat();
        // —— 新增：停止断线重连监控 ——
        stopConnectionMonitor();
        
        System.out.println("WebSocket连接已断开");
    }
    
    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return connected;
    }
    
    /**
     * 检查是否正在连接
     */
    public boolean isConnecting() {
        return connecting;
    }
    
    /**
     * 获取订阅列表
     */
    public Map<String, Consumer<String>> getSubscriptions() {
        return new ConcurrentHashMap<>(subscriptions);
    }
    
    /**
     * 关闭客户端并释放资源
     */
    public void shutdown() {
        disconnect();
        heartbeatExecutor.shutdown();
        // —— 新增：停止断线重连监控（保险） ——
        stopConnectionMonitor();
        try {
            if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // —— 新增：断线重连监控任务引用 ——
    private ScheduledFuture<?> monitorFuture;
    
    // —— 新增：断线重连监控（每5秒检查一次） ——
    private synchronized void startConnectionMonitor() {
        if (monitorFuture != null && !monitorFuture.isCancelled()) {
            return; // 已启动
        }
        monitorFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                if (!connected && !connecting) {
                    // 断开状态下尝试一次重连；不等待结果，失败则下次再试
                    connect(connectionCallback);
                }
            } catch (Exception ex) {
                // 忽略异常，等待下一轮
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private synchronized void stopConnectionMonitor() {
        if (monitorFuture != null) {
            monitorFuture.cancel(false);
            monitorFuture = null;
        }
    }
}