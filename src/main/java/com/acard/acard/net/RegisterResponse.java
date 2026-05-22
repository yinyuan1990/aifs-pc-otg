package com.acard.acard.net;

import com.google.gson.annotations.SerializedName;

/**
 * 注册响应
 * 匹配实际的控制端注册接口返回数据
 */
public class RegisterResponse {
    @SerializedName(value = "userId", alternate = {"user_id", "id"})
    private Long userId;
    
    private String username;
    
    @SerializedName(value = "userType", alternate = {"user_type"})
    private String userType;
    
    @SerializedName(value = "deviceId", alternate = {"device_id"})
    private String deviceId;
    
    @SerializedName(value = "membershipType", alternate = {"membership_type"})
    private String membershipType;
    
    private String status;
    
    @SerializedName(value = "trialEndTime", alternate = {"trial_end_time"})
    private String trialEndTime;
    
    private String message;
    
    private String error;  // 错误时才有此字段
    
    public RegisterResponse() {}
    
    public Long getUserId() {
        return userId;
    }
    
    public void setUserId(Long userId) {
        this.userId = userId;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getUserType() {
        return userType;
    }
    
    public void setUserType(String userType) {
        this.userType = userType;
    }
    
    public String getDeviceId() {
        return deviceId;
    }
    
    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }
    
    public String getMembershipType() {
        return membershipType;
    }
    
    public void setMembershipType(String membershipType) {
        this.membershipType = membershipType;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public String getTrialEndTime() {
        return trialEndTime;
    }
    
    public void setTrialEndTime(String trialEndTime) {
        this.trialEndTime = trialEndTime;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    /**
     * 判断注册是否成功
     * 成功的标志：
     * 1. 没有 error 字段（或者 error 为空）
     * 2. 有 username（已注册的用户名）
     * 3. 有 message（成功消息）
     * 
     * 注意：userId 可能因为字段名不匹配为 null，不能作为唯一判断条件
     */
    public boolean isSuccess() {
        // 如果有 error 字段，肯定是失败
        if (error != null && !error.isEmpty()) {
            return false;
        }
        // 如果有 username 和 message，说明注册成功
        return username != null && !username.isEmpty() 
            && message != null && !message.isEmpty();
    }
}

