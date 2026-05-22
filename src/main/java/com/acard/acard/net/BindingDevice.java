package com.acard.acard.net;

/**
 * 绑定设备信息模型
 * 对应登录接口返回的 bindingList 数组元素
 */
public class BindingDevice {
    private Long bindingId;        // 绑定记录ID
    private String deviceUsername; // 设备端账号
    private String deviceNickname; // 设备端昵称
    private String remark;         // 备注（与 deviceNickname 同级）
    private String deviceId;       // 设备ID
    private Boolean online;        // 是否在线（true=推流中，false=离线）
    private String bindTime;       // 绑定时间

    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }

    public String getDeviceUsername() { return deviceUsername; }
    public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }

    public String getDeviceNickname() { return deviceNickname; }
    public void setDeviceNickname(String deviceNickname) { this.deviceNickname = deviceNickname; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public Boolean getOnline() { return online; }
    public void setOnline(Boolean online) { this.online = online; }
    
    public boolean isOnline() { return online != null && online; }

    public String getBindTime() { return bindTime; }
    public void setBindTime(String bindTime) { this.bindTime = bindTime; }
    
    /**
     * 获取显示文本
     * 格式：deviceNickname 或 前3位**(remark)（如果有备注）
     */
    public String getDisplayText() {
        String name = deviceNickname != null && !deviceNickname.isEmpty() 
            ? deviceNickname 
            : deviceUsername;
        // 如果有 remark，昵称超过3位则显示前3位加**，3位及以下直接显示
        if (remark != null && !remark.isEmpty()) {
            String maskedName = name.length() > 3 ? name.substring(0, 3) + "**" : name;
            return maskedName + "(" + remark + ")";
        }
        return name;
    }
    
    /**
     * 获取显示文本（带在线状态）
     */
    public String getDisplayTextWithStatus() {
        String name = getDisplayText();
        String status = isOnline() ? "🟢 在线" : "⚫ 离线";
        return name + " (" + status + ")";
    }
    
    @Override
    public String toString() {
        return "BindingDevice{" +
                "bindingId=" + bindingId +
                ", deviceUsername='" + deviceUsername + '\'' +
                ", deviceNickname='" + deviceNickname + '\'' +
                ", remark='" + remark + '\'' +
                ", online=" + online +
                '}';
    }
}

