package com.acard.acard.net;

import java.util.List;

/**
 * 查询绑定设备列表的响应模型
 * 对应 GET /api/binding/devices 接口返回
 */
public class BindingDeviceListResponse {
    private List<BindingDevice> devices;
    private Integer count;

    public List<BindingDevice> getDevices() { return devices; }
    public void setDevices(List<BindingDevice> devices) { this.devices = devices; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
}

