package com.acard.acard.net;

/**
 * 手动绑定设备接口响应模型
 * 对应 POST /api/binding/manual-bind 接口返回
 */
public class ManualBindResponse {
    private Boolean success;           // 是否成功
    private Long bindingId;            // 绑定记录ID
    private String controlUsername;    // 控制端账号
    private String deviceUsername;     // 设备端账号
    private String deviceId;           // 设备ID
    private String status;             // 绑定状态，成功时为 "ACTIVE"
    private String message;            // 提示信息
    private String bindCompleteTime;   // 绑定完成时间

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    
    public boolean isSuccess() { return success != null && success; }

    public Long getBindingId() { return bindingId; }
    public void setBindingId(Long bindingId) { this.bindingId = bindingId; }

    public String getControlUsername() { return controlUsername; }
    public void setControlUsername(String controlUsername) { this.controlUsername = controlUsername; }

    public String getDeviceUsername() { return deviceUsername; }
    public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getBindCompleteTime() { return bindCompleteTime; }
    public void setBindCompleteTime(String bindCompleteTime) { this.bindCompleteTime = bindCompleteTime; }

    @Override
    public String toString() {
        return "ManualBindResponse{" +
                "success=" + success +
                ", bindingId=" + bindingId +
                ", controlUsername='" + controlUsername + '\'' +
                ", deviceUsername='" + deviceUsername + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", status='" + status + '\'' +
                ", message='" + message + '\'' +
                ", bindCompleteTime='" + bindCompleteTime + '\'' +
                '}';
    }
}

