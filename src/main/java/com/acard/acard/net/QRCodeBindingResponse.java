package com.acard.acard.net;

/**
 * 设备绑定二维码响应
 */
public class QRCodeBindingResponse {
    private String controlUsername;
    private String message;
    
    public QRCodeBindingResponse() {
    }
    
    public QRCodeBindingResponse(String controlUsername, String message) {
        this.controlUsername = controlUsername;
        this.message = message;
    }
    
    public String getControlUsername() {
        return controlUsername;
    }
    
    public void setControlUsername(String controlUsername) {
        this.controlUsername = controlUsername;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    @Override
    public String toString() {
        return "QRCodeBindingResponse{" +
                "controlUsername='" + controlUsername + '\'' +
                ", message='" + message + '\'' +
                '}';
    }
}

