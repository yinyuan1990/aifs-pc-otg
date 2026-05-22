package com.acard.acard.net;

import java.util.List;

/**
 * 批量查询在线状态响应模型
 * 对应 POST /api/binding/online-status 接口返回
 */
public class OnlineStatusResponse {
    private List<OnlineStatusItem> list;
    private Integer count;

    public List<OnlineStatusItem> getList() { return list; }
    public void setList(List<OnlineStatusItem> list) { this.list = list; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
    
    /**
     * 在线状态项
     */
    public static class OnlineStatusItem {
        private String controlUsername;   // 控制端账号
        private String deviceUsername;    // 设备端账号（未绑定时为 null）
        private String deviceNickname;    // 设备端昵称
        private String remark;            // 备注（与 deviceNickname 同级）
        private String deviceId;          // 设备ID（未绑定时为 null）
        private Boolean online;           // 是否在线
        private Boolean bound;            // 是否已绑定
        private Long bindingId;           // 绑定记录ID（未绑定时无此字段）
        private String message;           // 状态描述

        public String getControlUsername() { return controlUsername; }
        public void setControlUsername(String controlUsername) { this.controlUsername = controlUsername; }

        public String getDeviceUsername() { return deviceUsername; }
        public void setDeviceUsername(String deviceUsername) { this.deviceUsername = deviceUsername; }

        public String getDeviceNickname() { return deviceNickname; }
        public void setDeviceNickname(String deviceNickname) { this.deviceNickname = deviceNickname; }

        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        
        /**
         * 获取显示文本
         * 格式：deviceNickname 或 deviceNickname(remark)（如果有备注）
         */
        public String getDisplayText() {
            String name = deviceNickname != null && !deviceNickname.isEmpty() 
                ? deviceNickname 
                : deviceUsername;
            if (name == null) name = "未知设备";
            // 如果有 remark，显示格式为 deviceNickname(remark)
            if (remark != null && !remark.isEmpty()) {
                return name + "(" + remark + ")";
            }
            return name;
        }

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

        public Boolean getOnline() { return online; }
        public void setOnline(Boolean online) { this.online = online; }
        
        public boolean isOnline() { return online != null && online; }

        public Boolean getBound() { return bound; }
        public void setBound(Boolean bound) { this.bound = bound; }
        
        public boolean isBound() { return bound != null && bound; }

        public Long getBindingId() { return bindingId; }
        public void setBindingId(Long bindingId) { this.bindingId = bindingId; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}

