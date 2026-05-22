package com.acard.acard.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * 用户数据模型类
 * 使用JavaFX属性绑定支持MVVM模式
 */
public class User {
    private final StringProperty username;
    private final StringProperty password;
    private final StringProperty accountNumber;
    private final StringProperty status;
    
    public User() {
        this.username = new SimpleStringProperty("");
        this.password = new SimpleStringProperty("");
        this.accountNumber = new SimpleStringProperty("");
        this.status = new SimpleStringProperty("未登录");
    }
    
    public User(String username, String password) {
        this();
        setUsername(username);
        setPassword(password);
    }
    
    // Username属性
    public StringProperty usernameProperty() {
        return username;
    }
    
    public String getUsername() {
        return username.get();
    }
    
    public void setUsername(String username) {
        this.username.set(username);
    }
    
    // Password属性
    public StringProperty passwordProperty() {
        return password;
    }
    
    public String getPassword() {
        return password.get();
    }
    
    public void setPassword(String password) {
        this.password.set(password);
    }
    
    // AccountNumber属性
    public StringProperty accountNumberProperty() {
        return accountNumber;
    }
    
    public String getAccountNumber() {
        return accountNumber.get();
    }
    
    public void setAccountNumber(String accountNumber) {
        this.accountNumber.set(accountNumber);
    }
    
    // Status属性
    public StringProperty statusProperty() {
        return status;
    }
    
    public String getStatus() {
        return status.get();
    }
    
    public void setStatus(String status) {
        this.status.set(status);
    }
    
    @Override
    public String toString() {
        return "User{" +
                "username='" + getUsername() + '\'' +
                ", accountNumber='" + getAccountNumber() + '\'' +
                ", status='" + getStatus() + '\'' +
                '}';
    }
}