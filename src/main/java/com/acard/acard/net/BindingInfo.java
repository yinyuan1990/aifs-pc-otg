package com.acard.acard.net;

/**
 * 绑定信息
 */
public class BindingInfo {
    private Long bindingId;
    private String deviceUsername;
    private String deviceId;
    private boolean deviceVerified;
    private boolean controlVerified;
    private String createdAt;
    private String deviceVerifyTime;
    
    public BindingInfo() {
    }
    
    public Long getBindingId() {
        return bindingId;
    }
    
    public void setBindingId(Long bindingId) {
        this.bindingId = bindingId;
    }
    
    public String getDeviceUsername() {
        return deviceUsername;
    }
    
    public void setDeviceUsername(String deviceUsername) {
        this.deviceUsername = deviceUsername;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
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
    
    public String getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
    
    public String getDeviceVerifyTime() {
        return deviceVerifyTime;
    }
    
    public void setDeviceVerifyTime(String deviceVerifyTime) {
        this.deviceVerifyTime = deviceVerifyTime;
    }
    
    @Override
    public String toString() {
        return "BindingInfo{" +
                "bindingId=" + bindingId +
                ", deviceUsername='" + deviceUsername + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", deviceVerified=" + deviceVerified +
                ", controlVerified=" + controlVerified +
                ", createdAt='" + createdAt + '\'' +
                ", deviceVerifyTime='" + deviceVerifyTime + '\'' +
                '}';
    }
}
