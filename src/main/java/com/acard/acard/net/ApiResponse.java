package com.acard.acard.net;

/**
 * API响应数据模型
 * 统一封装HTTP请求的响应结果
 */
public class ApiResponse<T> {
    
    private int code;
    private String message;
    private T data;
    private boolean success;
    private long timestamp;
    
    public ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.success = code >= 200 && code < 300;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * 创建成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "Success", data);
    }
    
    /**
     * 创建成功响应（无数据）
     */
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "Success", null);
    }
    
    /**
     * 创建失败响应
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
    
    /**
     * 创建失败响应（默认500错误）
     */
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message, null);
    }
    
    // Getter和Setter方法
    public int getCode() {
        return code;
    }
    
    public void setCode(int code) {
        this.code = code;
        this.success = code >= 200 && code < 300;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public T getData() {
        return data;
    }
    
    public void setData(T data) {
        this.data = data;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    @Override
    public String toString() {
        return "ApiResponse{" +
                "code=" + code +
                ", message='" + message + '\'' +
                ", data=" + data +
                ", success=" + success +
                ", timestamp=" + timestamp +
                '}';
    }
}