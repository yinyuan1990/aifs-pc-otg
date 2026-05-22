package com.acard.acard.net;

/**
 * 控制端验证二级密码请求
 */
public class BindingVerifyRequest {
    private Long bindingId;
    private String secondaryPassword;
    
    public BindingVerifyRequest() {
    }
    
    public BindingVerifyRequest(Long bindingId, String secondaryPassword) {
        this.bindingId = bindingId;
        this.secondaryPassword = secondaryPassword;
    }
    
    public Long getBindingId() {
        return bindingId;
    }
    
    public void setBindingId(Long bindingId) {
        this.bindingId = bindingId;
    }
    
    public String getSecondaryPassword() {
        return secondaryPassword;
    }
    
    public void setSecondaryPassword(String secondaryPassword) {
        this.secondaryPassword = secondaryPassword;
    }
    
    @Override
    public String toString() {
        return "BindingVerifyRequest{" +
                "bindingId=" + bindingId +
                ", secondaryPassword='***'" +
                '}';
    }
}
