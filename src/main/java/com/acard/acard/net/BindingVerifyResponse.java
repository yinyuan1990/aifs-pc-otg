package com.acard.acard.net;

/**
 * 控制端验证二级密码响应
 */
public class BindingVerifyResponse {
    private boolean success;
    private Long bindingId;
    private String deviceId;
    private String deviceUsername;  // 设备端账号
    private boolean deviceVerified;
    private boolean controlVerified;
    private String status;
    private String message;
    private String bindCompleteTime;
    private String error;  // 错误信息
    
    public BindingVerifyResponse() {
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public Long getBindingId() {
        return bindingId;
    }
    
    public void setBindingId(Long bindingId) {
        this.bindingId = bindingId;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public String getDeviceUsername() {
        return deviceUsername;
    }
    
    public void setDeviceUsername(String deviceUsername) {
        this.deviceUsername = deviceUsername;
    }
    
    public boolean isDeviceVerified() {
        return deviceVerified;
    }
    
    public void setDeviceVerified(boolean deviceVerified) {
        this.deviceVerified = deviceVerified;
    }
    
    public boolean isControlVerified() {
        return controlVerified;
    }
    
    public void setControlVerified(boolean controlVerified) {
        this.controlVerified = controlVerified;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getBindCompleteTime() {
        return bindCompleteTime;
    }
    
    public void setBindCompleteTime(String bindCompleteTime) {
        this.bindCompleteTime = bindCompleteTime;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    @Override
    public String toString() {
        return "BindingVerifyResponse{" +
                "success=" + success +
                ", bindingId=" + bindingId +
                ", deviceId='" + deviceId + '\'' +
                ", deviceUsername='" + deviceUsername + '\'' +
                ", deviceVerified=" + deviceVerified +
                ", controlVerified=" + controlVerified +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", bindCompleteTime='" + bindCompleteTime + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}
