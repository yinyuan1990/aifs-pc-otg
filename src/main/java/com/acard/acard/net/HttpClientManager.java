package com.acard.acard.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import okhttp3.logging.HttpLoggingInterceptor;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP客户端管理器
 * 提供全局的HTTP网络请求功能，支持GET、POST、PUT、DELETE等方法
 */
public class HttpClientManager {
    
    private static volatile HttpClientManager instance;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final NetworkConfig config;
    
    // 媒体类型常量
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    public static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    
    private HttpClientManager() {
        this.config = NetworkConfig.getInstance();
        this.gson = new GsonBuilder()
                .setDateFormat("yyyy-MM-dd HH:mm:ss")
                .create();
        
        // 构建OkHttpClient
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(config.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(config.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
        
        // 添加日志拦截器
        if (config.isEnableLogging()) {
            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
            builder.addInterceptor(loggingInterceptor);
        }
        
        this.httpClient = builder.build();
    }
    
    /**
     * 获取单例实例
     */
    public static HttpClientManager getInstance() {
        if (instance == null) {
            synchronized (HttpClientManager.class) {
                if (instance == null) {
                    instance = new HttpClientManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * GET请求
     */
    public <T> CompletableFuture<ApiResponse<T>> get(String endpoint, Class<T> responseType) {
        return get(endpoint, null, responseType);
    }
    
    /**
     * GET请求（带请求头）
     */
    public <T> CompletableFuture<ApiResponse<T>> get(String endpoint, Map<String, String> headers, Class<T> responseType) {
        String url = config.buildApiUrl(endpoint);
        System.out.println("[HTTP] GET -> " + url);
        Request.Builder requestBuilder = new Request.Builder().url(url);
        applyDefaultHeaders(requestBuilder);
        
        // 添加请求头
        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }
        // 自动附加认证头
        attachAuthHeader(requestBuilder);
        
        Request request = requestBuilder.build();
        return executeRequest(request, responseType);
    }
    
    /**
     * POST请求（JSON数据）
     */
    public <T> CompletableFuture<ApiResponse<T>> post(String endpoint, Object requestBody, Class<T> responseType) {
        return post(endpoint, requestBody, null, responseType);
    }
    
    /**
     * POST请求（JSON数据，带请求头）
     */
    public <T> CompletableFuture<ApiResponse<T>> post(String endpoint, Object requestBody, Map<String, String> headers, Class<T> responseType) {
        String url = config.buildApiUrl(endpoint);
        System.out.println("[HTTP] POST -> " + url);
        String jsonBody = gson.toJson(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .post(body);
        // 明确声明 JSON 请求类型
        requestBuilder.addHeader("Content-Type", "application/json; charset=utf-8");
        applyDefaultHeaders(requestBuilder);
        
        // 添加请求头
        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }
        // 登录等公开接口不附带认证头，避免403
        if (!isAuthExemptEndpoint(endpoint)) {
            attachAuthHeader(requestBuilder);
        }
        
        Request request = requestBuilder.build();
        // 调试打印：请求头和请求体
        try {
            System.out.println("[HTTP] Headers -> " + request.headers());
            System.out.println("[HTTP] Body   -> " + jsonBody);
        } catch (Exception ignore) {}
        return executeRequest(request, responseType);
    }
    
    /**
     * PUT请求
     */
    public <T> CompletableFuture<ApiResponse<T>> put(String endpoint, Object requestBody, Class<T> responseType) {
        return put(endpoint, requestBody, null, responseType);
    }
    
    /**
     * PUT请求（带请求头）
     */
    public <T> CompletableFuture<ApiResponse<T>> put(String endpoint, Object requestBody, Map<String, String> headers, Class<T> responseType) {
        String url = config.buildApiUrl(endpoint);
        System.out.println("[HTTP] PUT -> " + url);
        String jsonBody = gson.toJson(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .put(body);
        // 明确声明 JSON 请求类型
        requestBuilder.addHeader("Content-Type", "application/json; charset=utf-8");
        applyDefaultHeaders(requestBuilder);
        
        // 添加请求头
        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }
        // 自动附加认证头
        attachAuthHeader(requestBuilder);
        
        Request request = requestBuilder.build();
        return executeRequest(request, responseType);
    }
    
    /**
     * DELETE请求
     */
    public <T> CompletableFuture<ApiResponse<T>> delete(String endpoint, Class<T> responseType) {
        return delete(endpoint, null, responseType);
    }
    
    /**
     * DELETE请求（带请求头）
     */
    public <T> CompletableFuture<ApiResponse<T>> delete(String endpoint, Map<String, String> headers, Class<T> responseType) {
        String url = config.buildApiUrl(endpoint);
        System.out.println("[HTTP] DELETE -> " + url);
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .delete();
        applyDefaultHeaders(requestBuilder);
        
        // 添加请求头
        if (headers != null) {
            headers.forEach(requestBuilder::addHeader);
        }
        // 自动附加认证头
        attachAuthHeader(requestBuilder);
        
        Request request = requestBuilder.build();
        return executeRequest(request, responseType);
    }
    
    /**
     * DELETE请求（带请求体）
     */
    public <T> CompletableFuture<ApiResponse<T>> delete(String endpoint, Object requestBody, Class<T> responseType) {
        String url = config.buildApiUrl(endpoint);
        System.out.println("[HTTP] DELETE (with body) -> " + url);
        String jsonBody = gson.toJson(requestBody);
        RequestBody body = RequestBody.create(jsonBody, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .delete(body);
        requestBuilder.addHeader("Content-Type", "application/json; charset=utf-8");
        applyDefaultHeaders(requestBuilder);
        // 自动附加认证头
        attachAuthHeader(requestBuilder);
        
        Request request = requestBuilder.build();
        return executeRequest(request, responseType);
    }
    
    /**
     * 执行HTTP请求
     */
    private <T> CompletableFuture<ApiResponse<T>> executeRequest(Request request, Class<T> responseType) {
        CompletableFuture<ApiResponse<T>> future = new CompletableFuture<>();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                NetworkException networkException;
                if (e instanceof java.net.SocketTimeoutException) {
                    networkException = new NetworkException.TimeoutException("请求超时: " + e.getMessage(), e);
                } else if (e instanceof java.net.ConnectException) {
                    networkException = new NetworkException.ConnectionException("连接失败: " + e.getMessage(), e);
                } else {
                    networkException = new NetworkException("网络请求失败: " + e.getMessage(), e);
                }
                
                ApiResponse<T> errorResponse = ApiResponse.error(networkException.getErrorCode(), networkException.getMessage());
                future.complete(errorResponse);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    // 调试：打印成功响应的原始响应体，便于核对字段是否正确返回
                    try {
                        System.out.println("[HTTP] Response <- " + response.code() + " [" + request.method() + " " + request.url() + "]\nBody: " + responseBody);
                    } catch (Exception ignore) {}
                    
                    if (response.isSuccessful()) {
                        T data = null;
                        if (responseType != Void.class && !responseBody.isEmpty()) {
                            try {
                                data = gson.fromJson(responseBody, responseType);
                            } catch (Exception e) {
                                ApiResponse<T> errorResponse = ApiResponse.error(500, "响应数据解析失败: " + e.getMessage());
                                future.complete(errorResponse);
                                return;
                            }
                        }
                        
                        ApiResponse<T> successResponse = new ApiResponse<>(response.code(), "Success", data);
                        future.complete(successResponse);
                    } else {
                        // 调试打印：错误响应体
                        try {
                            System.err.println("[HTTP] ERROR " + response.code() + " <- " + request.url() + "\nBody: " + responseBody);
                        } catch (Exception ignore) {}
                        // 尝试从响应体中提取后端返回的错误信息和业务错误码
                        String backendMessage = null;
                        int businessCode = response.code();  // 默认使用HTTP状态码
                        try {
                            // 常见后端错误结构：{"error":"...", "code": 1001} 或 {"message":"..."}
                            java.util.Map<String, Object> map = gson.fromJson(responseBody, new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>(){}.getType());
                            if (map != null) {
                                Object err = map.get("error");
                                Object msg = map.get("message");
                                Object code = map.get("code");
                                if (err instanceof String) backendMessage = (String) err;
                                else if (msg instanceof String) backendMessage = (String) msg;
                                // ⭐ 提取业务错误码（如 1001、1002 等）
                                if (code instanceof Number) {
                                    businessCode = ((Number) code).intValue();
                                    System.out.println("[HTTP] 业务错误码: " + businessCode);
                                }
                            }
                        } catch (Exception ignore) {
                            // 响应可能不是JSON，忽略解析错误
                        }
                        String msg = backendMessage != null && !backendMessage.isEmpty()
                                ? backendMessage
                                : ("HTTP错误(" + response.code() + "): " + response.message());
                        // ⭐ 使用业务错误码（如果有的话）
                        ApiResponse<T> errorResponse = ApiResponse.error(businessCode, msg);
                        future.complete(errorResponse);
                    }
                } catch (Exception e) {
                    ApiResponse<T> errorResponse = ApiResponse.error(500, "处理响应时发生错误: " + e.getMessage());
                    future.complete(errorResponse);
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }

    /**
     * 如果存在 token，自动附加 Authorization 头
     */
    private void attachAuthHeader(Request.Builder builder) {
        String token = config.getAuthToken();
        if (token != null && !token.isEmpty()) {
            builder.addHeader("Authorization", "Bearer " + token);
            System.out.println("[HTTP] ✅ Token已附加: Bearer " + token.substring(0, Math.min(20, token.length())) + "...");
        } else {
            System.err.println("[HTTP] ⚠️ Token为空，未附加Authorization头！");
        }
    }

    /** 默认头：统一 Accept 和 User-Agent */
    private void applyDefaultHeaders(Request.Builder builder) {
        builder.addHeader("Accept", "application/json");
        builder.addHeader("User-Agent", "Acard/1.0 (Windows; JavaFX)");
    }

    /**
     * 判断是否为无需认证的公开接口（如登录、注册）
     */
    private boolean isAuthExemptEndpoint(String endpoint) {
        if (endpoint == null) return false;
        String p = endpoint.toLowerCase();
        // 兼容多种写法：登录和注册接口都不需要认证
        return p.contains("/api/auth/login") || p.contains("/auth/login") || p.equals("login") || p.endsWith("/login")
            || p.contains("/api/auth/register") || p.contains("/auth/register") || p.contains("/register");
    }
    
    /**
     * 取消所有请求
     */
    public void cancelAllRequests() {
        httpClient.dispatcher().cancelAll();
    }
    
    /**
     * 获取Gson实例
     */
    public Gson getGson() {
        return gson;
    }
    
    /**
     * 获取OkHttpClient实例
     */
    public OkHttpClient getHttpClient() {
        return httpClient;
    }
    
    /**
     * 关闭HTTP客户端并释放资源
     */
    public void shutdown() {
        // 取消所有请求
        cancelAllRequests();
        
        // 关闭连接池和线程池
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        
        // 关闭缓存（如果有的话）
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (IOException e) {
                System.err.println("关闭HTTP缓存时发生错误: " + e.getMessage());
            }
        }
    }
}