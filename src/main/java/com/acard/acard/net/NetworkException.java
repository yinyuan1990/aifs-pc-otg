package com.acard.acard.net;

/**
 * 网络异常类
 * 封装网络请求过程中的各种异常情况
 */
public class NetworkException extends Exception {
    
    private final int errorCode;
    private final String errorMessage;
    private final Throwable originalCause;
    
    public NetworkException(String message) {
        super(message);
        this.errorCode = -1;
        this.errorMessage = message;
        this.originalCause = null;
    }
    
    public NetworkException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = message;
        this.originalCause = null;
    }
    
    public NetworkException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = -1;
        this.errorMessage = message;
        this.originalCause = cause;
    }
    
    public NetworkException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorMessage = message;
        this.originalCause = cause;
    }
    
    public int getErrorCode() {
        return errorCode;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public Throwable getOriginalCause() {
        return originalCause;
    }
    
    /**
     * 网络连接异常
     */
    public static class ConnectionException extends NetworkException {
        public ConnectionException(String message) {
            super(message);
        }
        
        public ConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * 超时异常
     */
    public static class TimeoutException extends NetworkException {
        public TimeoutException(String message) {
            super(message);
        }
        
        public TimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * HTTP状态码异常
     */
    public static class HttpException extends NetworkException {
        public HttpException(int statusCode, String message) {
            super(statusCode, message);
        }
        
        public HttpException(int statusCode, String message, Throwable cause) {
            super(statusCode, message, cause);
        }
    }
    
    /**
     * JSON解析异常
     */
    public static class ParseException extends NetworkException {
        public ParseException(String message) {
            super(message);
        }
        
        public ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    /**
     * WebSocket异常
     */
    public static class WebSocketException extends NetworkException {
        public WebSocketException(String message) {
            super(message);
        }
        
        public WebSocketException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    @Override
    public String toString() {
        return "NetworkException{" +
                "errorCode=" + errorCode +
                ", errorMessage='" + errorMessage + '\'' +
                ", originalCause=" + originalCause +
                '}';
    }
}