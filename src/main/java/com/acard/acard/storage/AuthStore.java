package com.acard.acard.storage;

import com.acard.acard.net.LoginResponse;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证数据缓存封装
 * - 缓存登录成功后的后端返回数据（token、用户信息等）
 * - 提供读取/清理接口，便于后续页面直接读取
 */
public class AuthStore {

    private static final String KEY_LOGIN_RESPONSE = "auth.login_response";
    private static final String KEY_EXTRA_DATA = "auth.extra_data"; // 用于缓存后续接口涉及的其他数据

    private final LocalStorage storage = LocalStorage.getInstance();

    private static volatile AuthStore instance;

    private AuthStore() {}

    public static AuthStore getInstance() {
        if (instance == null) {
            synchronized (AuthStore.class) {
                if (instance == null) instance = new AuthStore();
            }
        }
        return instance;
    }

    /** 保存登录响应并记录最近账号 */
    public void saveLoginResponse(LoginResponse resp) {
        if (resp == null) return;
        storage.putObject(KEY_LOGIN_RESPONSE, resp);
        storage.setAuthToken(resp.getToken());
        storage.rememberAccount(resp.getUsername());
    }

    /** 读取已缓存的登录响应 */
    public LoginResponse getLoginResponse() {
        return storage.getObject(KEY_LOGIN_RESPONSE, LoginResponse.class);
    }

    /** 读取永久令牌（permanentToken） */
    public String getPermanentToken() {
        LoginResponse resp = getLoginResponse();
        return resp != null ? resp.getPermanentToken() : null;
    }

    /** 读取安全令牌（JWT token） */
    public String getToken() {
        // 优先从缓存的响应读取；如为空可从 LocalStorage 的 AUTH_TOKEN 键补取
        LoginResponse resp = getLoginResponse();
        String t = resp != null ? resp.getToken() : null;
        if (t == null) {
            t = storage.getAuthToken();
        }
        return t;
    }

    /** 清除登录相关缓存 */
    public void clearLogin() {
        storage.putObject(KEY_LOGIN_RESPONSE, null);
        storage.setAuthToken(null);
        // 清理附加数据
        storage.putObject(KEY_EXTRA_DATA, null);
    }

    /** 写入附加数据（键值对） */
    public void putExtra(String key, String value) {
        Map<String, String> map = getExtraAll();
        map.put(key, value);
        storage.putObject(KEY_EXTRA_DATA, map);
    }

    /** 读取所有附加数据 */
    public Map<String, String> getExtraAll() {
        Type type = new TypeToken<Map<String, String>>(){}.getType();
        Map<String, String> map = storage.getObject(KEY_EXTRA_DATA, type);
        return map != null ? map : new HashMap<>();
    }

    /** 读取单个附加字段 */
    public String getExtra(String key) {
        Map<String, String> map = getExtraAll();
        return map.get(key);
    }
}