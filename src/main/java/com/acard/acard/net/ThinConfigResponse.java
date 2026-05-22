package com.acard.acard.net;

import com.acard.acard.model.ThinRemoteConfig;

/**
 * 轻配置HTTP响应包装
 * 对应后端: { success: boolean, data: ThinRemoteConfig, message: string }
 */
public class ThinConfigResponse {
    private boolean success;
    private ThinRemoteConfig data;
    private String message;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public ThinRemoteConfig getData() { return data; }
    public void setData(ThinRemoteConfig data) { this.data = data; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}