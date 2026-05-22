package com.acard.acard.net;

/**
 * 设置备注请求模型
 * 对应 POST /api/binding/set-remark 接口
 */
public class SetRemarkRequest {
    private String controlUsername;  // Windows控制端账号
    private String deviceUsername;   // iOS设备端账号
    private String remark;           // 备注内容（可为空，表示清空备注）

    public SetRemarkRequest() {}

    public SetRemarkRequest(String controlUsername, String deviceUsername, String remark) {
        this.controlUsername = controlUsername;
        this.deviceUsername = deviceUsername;
        this.remark = remark;
    }

    public String getControlUsername() { return controlUsername; }
    public void setControlUsername(String controlUsername) { this.controlUsername = controlUsername; }

    public String getDeviceUsername() { return deviceUsername; }
    public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}

