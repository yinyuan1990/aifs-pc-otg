package com.acard.acard.storage;

import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 账号列表存储管理
 * - 保存多个账号（用户名+密码）
 * - 用于切换账号功能
 * - 注册成功后自动添加到列表
 */
public class AccountListStore {
    
    private static final String KEY_ACCOUNT_LIST = "account_list";
    private static volatile AccountListStore instance;
    
    private final LocalStorage storage = LocalStorage.getInstance();
    
    private AccountListStore() {}
    
    public static AccountListStore getInstance() {
        if (instance == null) {
            synchronized (AccountListStore.class) {
                if (instance == null) {
                    instance = new AccountListStore();
                }
            }
        }
        return instance;
    }
    
    /**
     * 账号信息实体 - 使用简单的 Map 避免反射问题
     */
    public static class Account {
        public String username;
        public String password;
        
        public Account() {}
        
        public Account(String username, String password) {
            this.username = username;
            this.password = password;
        }
        
        // 转换为 Map
        public Map<String, String> toMap() {
            Map<String, String> map = new HashMap<>();
            map.put("username", username);
            map.put("password", password);
            return map;
        }
        
        // 从 Map 创建
        public static Account fromMap(Map<String, String> map) {
            if (map == null) return null;
            return new Account(
                map.get("username"),
                map.get("password")
            );
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getPassword() {
            return password;
        }
        
        public void setPassword(String password) {
            this.password = password;
        }
    }
    
    /**
     * 获取所有保存的账号列表
     */
    public synchronized List<Account> getAllAccounts() {
        try {
            // 使用 List<Map> 避免反射问题
            Type type = new TypeToken<List<Map<String, String>>>(){}.getType();
            List<Map<String, String>> mapList = storage.getObject(KEY_ACCOUNT_LIST, type);
            
            if (mapList == null || mapList.isEmpty()) {
                return new ArrayList<>();
            }
            
            // 转换为 Account 对象
            List<Account> accounts = new ArrayList<>();
            for (Map<String, String> map : mapList) {
                Account account = Account.fromMap(map);
                if (account != null && account.username != null) {
                    accounts.add(account);
                }
            }
            return accounts;
        } catch (Exception e) {
            System.err.println("读取账号列表失败: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
    
    /**
     * 添加或更新账号
     * - 如果用户名已存在，更新密码
     * - 如果不存在，添加到列表开头（最近使用）
     */
    public synchronized void addOrUpdateAccount(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            return;
        }
        
        try {
            List<Account> accounts = getAllAccounts();
            
            // 查找是否已存在
            Optional<Account> existing = accounts.stream()
                .filter(acc -> username.equals(acc.username))
                .findFirst();
            
            if (existing.isPresent()) {
                // 更新密码
                existing.get().password = password;
                // 移到列表开头
                accounts.remove(existing.get());
                accounts.add(0, existing.get());
            } else {
                // 添加新账号到开头
                accounts.add(0, new Account(username, password));
            }
            
            // 限制最多保存 20 个账号
            if (accounts.size() > 20) {
                accounts = accounts.subList(0, 20);
            }
            
            // 转换为 Map 列表保存
            List<Map<String, String>> mapList = new ArrayList<>();
            for (Account account : accounts) {
                mapList.add(account.toMap());
            }
            
            storage.putObject(KEY_ACCOUNT_LIST, mapList);
            System.out.println("💾 已保存账号: " + username);
        } catch (Exception e) {
            System.err.println("保存账号失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 根据用户名查找账号
     */
    public synchronized Account getAccount(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        
        List<Account> accounts = getAllAccounts();
        return accounts.stream()
            .filter(acc -> username.equals(acc.username))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 删除指定账号
     */
    public synchronized void removeAccount(String username) {
        if (username == null || username.trim().isEmpty()) {
            return;
        }
        
        try {
            List<Account> accounts = getAllAccounts();
            accounts.removeIf(acc -> username.equals(acc.username));
            
            // 转换为 Map 列表保存
            List<Map<String, String>> mapList = new ArrayList<>();
            for (Account account : accounts) {
                mapList.add(account.toMap());
            }
            
            storage.putObject(KEY_ACCOUNT_LIST, mapList);
            System.out.println("🗑️ 已删除账号: " + username);
        } catch (Exception e) {
            System.err.println("删除账号失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 清空所有账号
     */
    public synchronized void clearAll() {
        storage.putObject(KEY_ACCOUNT_LIST, new ArrayList<Account>());
        System.out.println("🗑️ 已清空所有账号");
    }
    
    /**
     * 获取账号数量
     */
    public synchronized int getAccountCount() {
        return getAllAccounts().size();
    }
}

