package com.acard.acard.net;

/**
 * Windows端单方面解绑响应模型
 * 对应 DELETE /api/binding/windows-unbind/{bindingId} 接口返回
 */
public class WindowsUnbindResponse {
    private String message;  // 成功提示信息
    private String error;    // 错误信息

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    
    public boolean isSuccess() {
        return message != null && error == null;
    }

    @Override
    public String toString() {
        return "WindowsUnbindResponse{" +
                "message='" + message + '\'' +
                ", error='" + error + '\'' +
                '}';
    }
}

