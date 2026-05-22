package com.acard.acard.storage;

import com.google.gson.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地存储工具类（单例）
 * - 使用 JSON 文件持久化数据，默认路径：~/.acard/storage.json
 * - 提供通用键值存取与账号信息便捷方法，便于后续复用
 */
public class LocalStorage {

    private static volatile LocalStorage instance;
    private final Gson gson;
    private final Path storagePath;
    private JsonObject store;

    private LocalStorage() {
        // 配置 Gson 以支持私有字段访问
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()  // 序列化 null 值
                .create();
        this.storagePath = Paths.get(System.getProperty("user.home"), ".acard", "storage.json");
        load();
    }

    public static LocalStorage getInstance() {
        if (instance == null) {
            synchronized (LocalStorage.class) {
                if (instance == null) {
                    instance = new LocalStorage();
                }
            }
        }
        return instance;
    }

    private void load() {
        try {
            if (Files.exists(storagePath)) {
                try (Reader reader = Files.newBufferedReader(storagePath)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    this.store = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
                }
            } else {
                this.store = new JsonObject();
            }
        } catch (Exception e) {
            this.store = new JsonObject();
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storagePath.getParent());
            try (Writer writer = Files.newBufferedWriter(storagePath)) {
                gson.toJson(store, writer);
            }
        } catch (IOException e) {
            // 可根据需要添加日志
        }
    }

    // ===== 通用键值存取 =====

    public synchronized void putString(String key, String value) {
        store.addProperty(key, value);
        persist();
    }

    public synchronized String getString(String key, String defaultValue) {
        JsonElement el = store.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : defaultValue;
    }

    public synchronized <T> void putObject(String key, T value) {
        store.add(key, gson.toJsonTree(value));
        persist();
    }

    public synchronized <T> T getObject(String key, Class<T> clazz) {
        JsonElement el = store.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return gson.fromJson(el, clazz);
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    public synchronized <T> T getObject(String key, Type type) {
        JsonElement el = store.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return gson.fromJson(el, type);
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    public synchronized <T> void putList(String key, List<T> list) {
        store.add(key, gson.toJsonTree(list));
        persist();
    }

    public synchronized <T> List<T> getList(String key, Type type) {
        JsonElement el = store.get(key);
        if (el == null || el.isJsonNull()) return Collections.emptyList();
        try {
            List<T> l = gson.fromJson(el, type);
            return l != null ? l : Collections.emptyList();
        } catch (JsonSyntaxException ex) {
            return Collections.emptyList();
        }
    }

    // ===== 账号信息便捷方法 =====

    public static final class Keys {
        public static final String LAST_ACCOUNT = "last_account";
        public static final String RECENT_ACCOUNTS = "recent_accounts";
        public static final String AUTH_TOKEN = "auth_token"; // 若需要持久化令牌，可使用此键
    }

    /**
     * 记住账号（置顶并去重，最多保留10个）
     */
    public synchronized void rememberAccount(String username) {
        if (username == null || username.isBlank()) return;

        JsonArray arr = store.has(Keys.RECENT_ACCOUNTS) && store.get(Keys.RECENT_ACCOUNTS).isJsonArray()
                ? store.getAsJsonArray(Keys.RECENT_ACCOUNTS)
                : new JsonArray();

        List<String> existing = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) existing.add(e.getAsString());
        }

        existing.remove(username);
        existing.add(0, username);

        if (existing.size() > 10) existing = existing.subList(0, 10);

        JsonArray newArr = new JsonArray();
        for (String s : existing) newArr.add(s);
        store.add(Keys.RECENT_ACCOUNTS, newArr);
        store.addProperty(Keys.LAST_ACCOUNT, username);
        persist();
    }

    public synchronized List<String> getRecentAccounts() {
        JsonElement el = store.get(Keys.RECENT_ACCOUNTS);
        if (el == null || !el.isJsonArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            if (e.isJsonPrimitive()) list.add(e.getAsString());
        }
        return list;
    }

    public String getLastAccount() {
        return getString(Keys.LAST_ACCOUNT, "");
    }

    public synchronized void setLastAccount(String username) {
        store.addProperty(Keys.LAST_ACCOUNT, username != null ? username : "");
        persist();
    }

    public synchronized void setAuthToken(String token) {
        store.addProperty(Keys.AUTH_TOKEN, token);
        persist();
    }

    public String getAuthToken() {
        return getString(Keys.AUTH_TOKEN, null);
    }
}