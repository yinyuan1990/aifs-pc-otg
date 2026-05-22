package com.acard.acard.net;

/**
 * 网络配置类
 * 管理全局网络设置，包括服务器地址、超时配置等
 */
public class NetworkConfig {


    public static final String DEFAULT_BASE_IP = "171.80.4.72";
    
    // ⭐ 动态拉流 IP（从 CONFIG_STATE 消息中解析获取，默认为空）
    private static volatile String streamPushIp = "";

    public static final int apiPort=1985;

    public static final String app="tenantA";

    // 默认配置
    private static final String DEFAULT_BASE_URL = "http://"+DEFAULT_BASE_IP+":9999";
    private static final String DEFAULT_WS_URL = "ws://"+DEFAULT_BASE_IP+":9999/ws";


    private static final int DEFAULT_CONNECT_TIMEOUT = 30; // 秒
    private static final int DEFAULT_READ_TIMEOUT = 30; // 秒
    private static final int DEFAULT_WRITE_TIMEOUT = 30; // 秒
    
    // 单例实例
    private static volatile NetworkConfig instance;
    
    // 配置字段
    private String baseUrl;
    private String websocketUrl;
    private int connectTimeoutSeconds;
    private int readTimeoutSeconds;
    private int writeTimeoutSeconds;
    private boolean enableLogging;
    private String authToken;
    
    private NetworkConfig() {
        // 初始化默认配置，并支持通过系统属性/环境变量覆盖
        String envBase = System.getenv("ACARD_BASE_URL");
        String sysBase = System.getProperty("acard.baseUrl");
        String resolvedBase = (envBase != null && !envBase.isBlank())
                ? envBase
                : (sysBase != null && !sysBase.isBlank() ? sysBase : DEFAULT_BASE_URL);

        String envWs = System.getenv("ACARD_WS_URL");
        String sysWs = System.getProperty("acard.wsUrl");
        String resolvedWs = (envWs != null && !envWs.isBlank())
                ? envWs
                : (sysWs != null && !sysWs.isBlank() ? sysWs : DEFAULT_WS_URL);

        this.baseUrl = resolvedBase;
        this.websocketUrl = resolvedWs;
        this.connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT;
        this.readTimeoutSeconds = DEFAULT_READ_TIMEOUT;
        this.writeTimeoutSeconds = DEFAULT_WRITE_TIMEOUT;
        // 默认开启HTTP日志，便于定位问题；可通过setEnableLogging关闭
        this.enableLogging = true;
        this.authToken = null;
    }
    
    /**
     * 获取单例实例
     */
    public static NetworkConfig getInstance() {
        if (instance == null) {
            synchronized (NetworkConfig.class) {
                if (instance == null) {
                    instance = new NetworkConfig();
                }
            }
        }
        return instance;
    }
    
    // Getter和Setter方法
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public NetworkConfig setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }
    
    public String getWebsocketUrl() {
        return websocketUrl;
    }
    
    public NetworkConfig setWebsocketUrl(String websocketUrl) {
        this.websocketUrl = websocketUrl;
        return this;
    }
    
    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }
    
    public NetworkConfig setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        return this;
    }
    
    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }
    
    public NetworkConfig setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
        return this;
    }
    
    public int getWriteTimeoutSeconds() {
        return writeTimeoutSeconds;
    }
    
    public NetworkConfig setWriteTimeoutSeconds(int writeTimeoutSeconds) {
        this.writeTimeoutSeconds = writeTimeoutSeconds;
        return this;
    }
    
    public boolean isEnableLogging() {
        return enableLogging;
    }
    
    public NetworkConfig setEnableLogging(boolean enableLogging) {
        this.enableLogging = enableLogging;
        return this;
    }

    /**
     * 获取/设置认证令牌
     */
    public String getAuthToken() { return authToken; }
    public NetworkConfig setAuthToken(String authToken) {
        this.authToken = authToken;
        return this;
    }
    
    /**
     * 获取动态拉流 IP（从 CONFIG_STATE 消息解析）
     */
    public static String getStreamPushIp() {
        return streamPushIp;
    }
    
    /**
     * 设置动态拉流 IP（由 CONFIG_STATE 消息解析后调用）
     */
    public static void setStreamPushIp(String ip) {
        streamPushIp = (ip != null) ? ip : "";
    }
    
    /**
     * 获取有效的拉流 IP（优先使用动态 streamPushIp，若为空则使用默认 IP）
     */
    public static String getEffectiveStreamIp() {
        return (streamPushIp != null && !streamPushIp.isEmpty()) ? streamPushIp : DEFAULT_BASE_IP;
    }
    
    /**
     * 构建完整的API URL
     */
    public String buildApiUrl(String endpoint) {
        if (endpoint.startsWith("/")) {
            return baseUrl + endpoint;
        } else {
            return baseUrl + "/" + endpoint;
        }
    }
    
    /**
     * 重置为默认配置
     */
    public void resetToDefaults() {
        this.baseUrl = DEFAULT_BASE_URL;
        this.websocketUrl = DEFAULT_WS_URL;
        this.connectTimeoutSeconds = DEFAULT_CONNECT_TIMEOUT;
        this.readTimeoutSeconds = DEFAULT_READ_TIMEOUT;
        this.writeTimeoutSeconds = DEFAULT_WRITE_TIMEOUT;
        this.enableLogging = false;
        this.authToken = null;
    }
    
    @Override
    public String toString() {
        return "NetworkConfig{" +
                "baseUrl='" + baseUrl + '\'' +
                ", websocketUrl='" + websocketUrl + '\'' +
                ", connectTimeoutSeconds=" + connectTimeoutSeconds +
                ", readTimeoutSeconds=" + readTimeoutSeconds +
                ", writeTimeoutSeconds=" + writeTimeoutSeconds +
                ", enableLogging=" + enableLogging +
                ", authTokenSet=" + (authToken != null) +
                '}';
    }
}