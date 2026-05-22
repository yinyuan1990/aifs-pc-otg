package com.acard.acard.net;

import java.util.Map;
import java.util.HashMap;

/**
 * 网络组件使用示例
 * 演示如何使用HttpClientManager和StompWebSocketClient
 */
public class NetworkUsageExample {
    
    public static void main(String[] args) {
        // 获取网络管理器实例
        NetworkManager networkManager = NetworkManager.getInstance();
        
        // 配置网络设置
        networkManager.setBaseUrl("https://api.example.com");
        networkManager.setWebSocketUrl("ws://localhost:8080/websocket");
        networkManager.setConnectTimeout(30);
        networkManager.setReadTimeout(30);
        networkManager.setLoggingEnabled(true);
        
        // HTTP请求示例
        demonstrateHttpRequests(networkManager);
        
        // WebSocket示例
        demonstrateWebSocket(networkManager);
    }
    
    /**
     * 演示HTTP请求
     */
    private static void demonstrateHttpRequests(NetworkManager networkManager) {
        System.out.println("=== HTTP请求示例 ===");
        
        // GET请求示例
        networkManager.get("/users/1", UserInfo.class)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    System.out.println("获取用户信息成功: " + response.getData());
                } else {
                    System.err.println("获取用户信息失败: " + response.getMessage());
                }
            })
            .exceptionally(throwable -> {
                System.err.println("请求异常: " + throwable.getMessage());
                return null;
            });
        
        // GET请求带参数示例
        Map<String, String> params = new HashMap<>();
        params.put("page", "1");
        params.put("size", "10");
        
        networkManager.get("/users", params, UserListResponse.class)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    System.out.println("获取用户列表成功，数量: " + response.getData().getUsers().size());
                } else {
                    System.err.println("获取用户列表失败: " + response.getMessage());
                }
            });
        
        // POST请求示例
        UserInfo newUser = new UserInfo();
        newUser.setName("张三");
        newUser.setEmail("zhangsan@example.com");
        
        networkManager.post("/users", newUser, UserInfo.class)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    System.out.println("创建用户成功: " + response.getData());
                } else {
                    System.err.println("创建用户失败: " + response.getMessage());
                }
            });
        
        // PUT请求示例
        UserInfo updateUser = new UserInfo();
        updateUser.setId(1L);
        updateUser.setName("李四");
        updateUser.setEmail("lisi@example.com");
        
        networkManager.put("/users/1", updateUser, UserInfo.class)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    System.out.println("更新用户成功: " + response.getData());
                } else {
                    System.err.println("更新用户失败: " + response.getMessage());
                }
            });
        
        // DELETE请求示例
        networkManager.delete("/users/1", String.class)
            .thenAccept(response -> {
                if (response.isSuccess()) {
                    System.out.println("删除用户成功");
                } else {
                    System.err.println("删除用户失败: " + response.getMessage());
                }
            });
    }
    
    /**
     * 演示WebSocket功能
     */
    private static void demonstrateWebSocket(NetworkManager networkManager) {
        System.out.println("\n=== WebSocket示例 ===");
        
        // 连接WebSocket
        networkManager.connectWebSocket(new StompWebSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                System.out.println("WebSocket连接成功！");
                
                // 订阅消息
                networkManager.subscribeWebSocket("/topic/notifications", message -> {
                    System.out.println("收到通知消息: " + message);
                });
                
                networkManager.subscribeWebSocket("/user/queue/private", message -> {
                    System.out.println("收到私人消息: " + message);
                });
                
                // 发送消息
                ChatMessage chatMessage = new ChatMessage();
                chatMessage.setContent("Hello, WebSocket!");
                chatMessage.setSender("用户1");
                
                networkManager.sendWebSocketMessage("/app/chat", chatMessage);
            }
            
            @Override
            public void onDisconnected(String reason) {
                System.out.println("WebSocket连接断开: " + reason);
            }
            
            @Override
            public void onError(Exception error) {
                System.err.println("WebSocket错误: " + error.getMessage());
            }
        });
        
        // 模拟运行一段时间后断开连接
        new Thread(() -> {
            try {
                Thread.sleep(30000); // 30秒后断开
                networkManager.disconnectWebSocket();
                networkManager.shutdown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    // 示例数据模型
    public static class UserInfo {
        private Long id;
        private String name;
        private String email;
        
        // Getters and Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        
        @Override
        public String toString() {
            return "UserInfo{id=" + id + ", name='" + name + "', email='" + email + "'}";
        }
    }
    
    public static class UserListResponse {
        private java.util.List<UserInfo> users;
        private int total;
        
        // Getters and Setters
        public java.util.List<UserInfo> getUsers() { return users; }
        public void setUsers(java.util.List<UserInfo> users) { this.users = users; }
        
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
    }
    
    public static class ChatMessage {
        private String content;
        private String sender;
        private long timestamp;
        
        public ChatMessage() {
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters and Setters
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}