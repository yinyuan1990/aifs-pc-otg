package com.acard.acard.storage;

import java.util.prefs.Preferences;

/**
 * 登录偏好设置存储
 * 用于保存和读取用户的登录账号等信息
 */
public class LoginPreferences {
    
    private static final String PREFS_NODE = "com.acard.acard.login";
    private static final String KEY_LAST_USERNAME = "lastUsername";
    private static final String KEY_REMEMBER_USERNAME = "rememberUsername";
    
    private static LoginPreferences instance;
    private final Preferences prefs;
    
    private LoginPreferences() {
        prefs = Preferences.userRoot().node(PREFS_NODE);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized LoginPreferences getInstance() {
        if (instance == null) {
            instance = new LoginPreferences();
        }
        return instance;
    }
    
    /**
     * 保存上次登录的账号
     * 
     * @param username 用户名
     */
    public void saveLastUsername(String username) {
        if (username != null && !username.trim().isEmpty()) {
            prefs.put(KEY_LAST_USERNAME, username);
            prefs.putBoolean(KEY_REMEMBER_USERNAME, true);
            flush();
            System.out.println("💾 已保存登录账号: " + username);
        }
    }
    
    /**
     * 获取上次登录的账号
     * 
     * @return 上次登录的账号，如果没有则返回空字符串
     */
    public String getLastUsername() {
        boolean remember = prefs.getBoolean(KEY_REMEMBER_USERNAME, false);
        if (remember) {
            String username = prefs.get(KEY_LAST_USERNAME, "");
            System.out.println("📖 读取上次登录账号: " + (username.isEmpty() ? "(无)" : username));
            return username;
        }
        return "";
    }
    
    /**
     * 清除保存的账号信息
     */
    public void clearLastUsername() {
        prefs.remove(KEY_LAST_USERNAME);
        prefs.putBoolean(KEY_REMEMBER_USERNAME, false);
        flush();
        System.out.println("🗑️ 已清除保存的登录账号");
    }
    
    /**
     * 检查是否记住账号
     * 
     * @return true表示记住账号
     */
    public boolean isRememberUsername() {
        return prefs.getBoolean(KEY_REMEMBER_USERNAME, false);
    }
    
    /**
     * 强制刷新到磁盘（确保数据持久化）
     */
    private void flush() {
        try {
            prefs.flush();
        } catch (Exception e) {
            System.err.println("⚠️ 保存登录偏好设置失败: " + e.getMessage());
        }
    }
}

