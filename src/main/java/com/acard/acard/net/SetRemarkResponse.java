package com.acard.acard.net;

/**
 * 设置备注响应模型
 * 对应 POST /api/binding/set-remark 接口返回
 */
public class SetRemarkResponse {
    private Boolean success;         // 是否成功
    private Long bindingId;          // 绑定记录ID
    private String controlUsername;  // Windows控制端账号
    private String deviceUsername;   // iOS设备端账号
    private String remark;           // 设置的备注内容
    private String message;          // 提示信息
    private String error;            // 错误信息

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    
    public boolean isSuccess() { return success != null && success; }

    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }

    public String getControlUsername() { return controlUsername; }
    public void setControlUsername(String controlUsername) { this.controlUsername = controlUsername; }

    public String getDeviceUsername() { return deviceUsername; }
    public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}

