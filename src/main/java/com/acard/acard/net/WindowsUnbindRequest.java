package com.acard.acard.net;

/**
 * Windows端单方面解绑请求模型
 * 对应 DELETE /api/binding/windows-unbind/{bindingId} 接口请求体
 */
public class WindowsUnbindRequest {
    private String password;  // Windows账号密码

    public WindowsUnbindRequest() {}
    
    public WindowsUnbindRequest(String password) {
        this.password = password;
    }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    @Override
    public String toString() {
        return "WindowsUnbindRequest{password='***'}";
    }
}

