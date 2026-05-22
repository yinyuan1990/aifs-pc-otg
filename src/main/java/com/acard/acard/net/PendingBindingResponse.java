package com.acard.acard.net;

import java.util.List;

/**
 * 待验证绑定响应
 */
public class PendingBindingResponse {
    private List<BindingInfo> bindings;
    private int count;
    
    public PendingBindingResponse() {
    }
    
    public PendingBindingResponse(List<BindingInfo> bindings, int count) {
        this.bindings = bindings;
        this.count = count;
    }
    
    public List<BindingInfo> getBindings() {
        return bindings;
    }
    
    public void setBindings(List<BindingInfo> bindings) {
        this.bindings = bindings;
    }
    
    public int getCount() {
        return count;
    }
    
    public void setCount(int count) {
        this.count = count;
    }
    
    @Override
    public String toString() {
        return "PendingBindingResponse{" +
                "bindings=" + bindings +
                ", count=" + count +
                '}';
    }
}
