package com.acard.acard.storage;

import com.acard.acard.net.BindingDevice;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 设备列表本地存储
 * 按控制端账号缓存绑定的设备列表
 */
public class DeviceListStore {
    private static final String STORE_FILE = "device_list.json";
    private static volatile DeviceListStore instance;
    private final Gson gson = new Gson();
    private final Path storePath;
    
    // 内存缓存：controlUsername -> 设备列表
    private Map<String, List<BindingDevice>> deviceCache = new HashMap<>();

    private DeviceListStore() {
        // 存储在用户目录下的 .acard 文件夹
        String userHome = System.getProperty("user.home");
        Path appDir = Paths.get(userHome, ".acard");
        try {
            Files.createDirectories(appDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.storePath = appDir.resolve(STORE_FILE);
        loadFromFile();
    }

    public static DeviceListStore getInstance() {
        if (instance == null) {
            synchronized (DeviceListStore.class) {
                if (instance == null) {
                    instance = new DeviceListStore();
                }
            }
        }
        return instance;
    }

    /**
     * 从文件加载缓存
     */
    private void loadFromFile() {
        if (!Files.exists(storePath)) {
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(storePath.toFile()), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, List<BindingDevice>>>(){}.getType();
            Map<String, List<BindingDevice>> loaded = gson.fromJson(reader, type);
            if (loaded != null) {
                deviceCache = loaded;
            }
        } catch (Exception e) {
            System.err.println("⚠️ 加载设备列表缓存失败: " + e.getMessage());
        }
    }

    /**
     * 保存到文件
     */
    private void saveToFile() {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(storePath.toFile()), StandardCharsets.UTF_8)) {
            gson.toJson(deviceCache, writer);
        } catch (Exception e) {
            System.err.println("⚠️ 保存设备列表缓存失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定用户的设备列表（从缓存）
     */
    public List<BindingDevice> getDevices(String controlUsername) {
        if (controlUsername == null || controlUsername.isEmpty()) {
            return new ArrayList<>();
        }
        List<BindingDevice> devices = deviceCache.get(controlUsername);
        return devices != null ? new ArrayList<>(devices) : new ArrayList<>();
    }

    /**
     * 保存设备列表（覆盖）
     */
    public void saveDevices(String controlUsername, List<BindingDevice> devices) {
        if (controlUsername == null || controlUsername.isEmpty() || devices == null) {
            return;
        }
        deviceCache.put(controlUsername, new ArrayList<>(devices));
        saveToFile();
        System.out.println("✅ 已缓存设备列表: " + controlUsername + " -> " + devices.size() + " 台设备");
    }

    /**
     * 更新设备列表（直接覆盖缓存）
     * - 后端返回什么就保存什么
     * - 如果列表为空则清空该用户的缓存
     */
    public void updateDevices(String controlUsername, List<BindingDevice> newDevices) {
        if (controlUsername == null || controlUsername.isEmpty()) {
            return;
        }
        
        if (newDevices == null || newDevices.isEmpty()) {
            // 列表为空，清空该用户的缓存
            deviceCache.remove(controlUsername);
            saveToFile();
            System.out.println("✅ 设备列表为空，已清空缓存: " + controlUsername);
        } else {
            // 直接覆盖缓存
            deviceCache.put(controlUsername, new ArrayList<>(newDevices));
            saveToFile();
            System.out.println("✅ 已覆盖设备列表缓存: " + controlUsername + " -> " + newDevices.size() + " 台设备");
        }
    }

    /**
     * 清除指定用户的设备缓存
     */
    public void clearDevices(String controlUsername) {
        if (controlUsername != null) {
            deviceCache.remove(controlUsername);
            saveToFile();
        }
    }

    /**
     * 清除所有缓存
     */
    public void clearAll() {
        deviceCache.clear();
        saveToFile();
    }
}

