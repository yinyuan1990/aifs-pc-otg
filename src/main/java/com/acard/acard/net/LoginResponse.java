package com.acard.acard.net;

import java.util.List;

/**
 * 登录响应数据模型，对应后端 /api/auth/login/control 返回结构
 */
public class LoginResponse {
    private String token;
    private String username;
    private String userType;
    private String deviceId;  // 兼容旧字段
    private String currentDeviceId;  // 当前选择的设备ID
    private String currentDeviceUsername;  // 当前选择的设备账号
    private List<BindingDevice> bindingList;  // 已绑定的设备列表
    private Integer bindingCount;  // 绑定的设备数量
    private String permanentToken;
    private String membershipType;
    private String status;
    private String message;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }
    
    public String getDeviceId() { 
        // 优先返回 currentDeviceId，兼容旧代码
        return currentDeviceId != null ? currentDeviceId : deviceId; 
    }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getCurrentDeviceId() { return currentDeviceId; }
    public void setCurrentDeviceId(String currentDeviceId) { this.currentDeviceId = currentDeviceId; }
    
    public String getCurrentDeviceUsername() { return currentDeviceUsername; }
    public void setCurrentDeviceUsername(String currentDeviceUsername) { this.currentDeviceUsername = currentDeviceUsername; }
    
    public List<BindingDevice> getBindingList() { return bindingList; }
    public void setBindingList(List<BindingDevice> bindingList) { this.bindingList = bindingList; }
    
    public Integer getBindingCount() { return bindingCount; }
    public void setBindingCount(Integer bindingCount) { this.bindingCount = bindingCount; }
    
    public String getPermanentToken() { return permanentToken; }
    public void setPermanentToken(String permanentToken) { this.permanentToken = permanentToken; }
    public String getMembershipType() { return membershipType; }
    public void setMembershipType(String membershipType) { this.membershipType = membershipType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}