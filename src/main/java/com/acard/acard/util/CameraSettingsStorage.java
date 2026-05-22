package com.acard.acard.util;

import com.acard.acard.SimpleWebRTCPlayer;

import java.util.prefs.Preferences;

/**
 * 相机设置本地存储工具类
 * 
 * 使用 Java Preferences API 存储以下参数：
 * - 曝光补偿 (exposure): 0 ~ 200 (100=标准)
 * - 亮度 (brightness): -1.0 ~ 1.0
 * - 对比度 (contrast): 0.0 ~ 2.0
 * - 饱和度 (saturation): 0.0 ~ 2.0
 * - 色调 (hue): -1.0 ~ 1.0
 * - 伽马 (gamma): 0.01 ~ 10.0
 */
public class CameraSettingsStorage {
    
    private static final String PREFS_NODE = "com/acard/acard/camera_settings";
    
    // 存储键
    private static final String KEY_EXPOSURE = "exposure";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_CONTRAST = "contrast";
    private static final String KEY_SATURATION = "saturation";
    private static final String KEY_HUE = "hue";
    private static final String KEY_GAMMA = "gamma";
    private static final String KEY_INITIALIZED = "initialized";  // 标记是否已初始化
    
    // 默认曝光值（0-100范围，20对应slider=0.2）
    public static final int DEFAULT_EXPOSURE = 20;
    
    // 单例
    private static CameraSettingsStorage instance;
    private final Preferences prefs;
    
    // 缓存当前值
    private int exposure;
    private double brightness;
    private double contrast;
    private double saturation;
    private double hue;
    private double gamma;
    
    private CameraSettingsStorage() {
        prefs = Preferences.userRoot().node(PREFS_NODE);
        loadSettings();
    }
    
    public static synchronized CameraSettingsStorage getInstance() {
        if (instance == null) {
            instance = new CameraSettingsStorage();
        }
        return instance;
    }
    
    /**
     * 从本地存储加载设置
     */
    private void loadSettings() {
        // 检查是否首次使用（未初始化）
        boolean initialized = prefs.getBoolean(KEY_INITIALIZED, false);
        
        if (!initialized) {
            // 首次使用，使用默认值
            exposure = DEFAULT_EXPOSURE;
            brightness = SimpleWebRTCPlayer.DEFAULT_BRIGHTNESS;
            contrast = SimpleWebRTCPlayer.DEFAULT_CONTRAST;
            saturation = SimpleWebRTCPlayer.DEFAULT_SATURATION;
            hue = SimpleWebRTCPlayer.DEFAULT_HUE;
            gamma = SimpleWebRTCPlayer.DEFAULT_GAMMA;
            
            // 保存默认值
            saveSettings();
            prefs.putBoolean(KEY_INITIALIZED, true);
            
            System.out.println("📷 相机设置：首次使用，初始化为默认值");
        } else {
            // 已初始化，读取保存的值
            exposure = prefs.getInt(KEY_EXPOSURE, DEFAULT_EXPOSURE);
            brightness = prefs.getDouble(KEY_BRIGHTNESS, SimpleWebRTCPlayer.DEFAULT_BRIGHTNESS);
            contrast = prefs.getDouble(KEY_CONTRAST, SimpleWebRTCPlayer.DEFAULT_CONTRAST);
            saturation = prefs.getDouble(KEY_SATURATION, SimpleWebRTCPlayer.DEFAULT_SATURATION);
            hue = prefs.getDouble(KEY_HUE, SimpleWebRTCPlayer.DEFAULT_HUE);
            gamma = prefs.getDouble(KEY_GAMMA, SimpleWebRTCPlayer.DEFAULT_GAMMA);
            
            System.out.println("📷 相机设置已加载：" + this);
        }
    }
    
    /**
     * 保存设置到本地存储
     */
    public void saveSettings() {
        try {
            prefs.putInt(KEY_EXPOSURE, exposure);
            prefs.putDouble(KEY_BRIGHTNESS, brightness);
            prefs.putDouble(KEY_CONTRAST, contrast);
            prefs.putDouble(KEY_SATURATION, saturation);
            prefs.putDouble(KEY_HUE, hue);
            prefs.putDouble(KEY_GAMMA, gamma);
            prefs.flush();
            
            System.out.println("💾 相机设置已保存：" + this);
        } catch (Exception e) {
            System.err.println("❌ 保存相机设置失败: " + e.getMessage());
        }
    }
    
    /**
     * 重置为默认值
     */
    public void resetToDefaults() {
        exposure = DEFAULT_EXPOSURE;
        brightness = SimpleWebRTCPlayer.DEFAULT_BRIGHTNESS;
        contrast = SimpleWebRTCPlayer.DEFAULT_CONTRAST;
        saturation = SimpleWebRTCPlayer.DEFAULT_SATURATION;
        hue = SimpleWebRTCPlayer.DEFAULT_HUE;
        gamma = SimpleWebRTCPlayer.DEFAULT_GAMMA;
        saveSettings();
        
        System.out.println("🔄 相机设置已重置为默认值");
    }
    
    // ========== Getters ==========
    
    public int getExposure() {
        return exposure;
    }
    
    public double getBrightness() {
        return brightness;
    }
    
    public double getContrast() {
        return contrast;
    }
    
    public double getSaturation() {
        return saturation;
    }
    
    public double getHue() {
        return hue;
    }
    
    public double getGamma() {
        return gamma;
    }
    
    // ========== Setters (自动保存) ==========
    
    public void setExposure(int exposure) {
        this.exposure = Math.max(0, Math.min(100, exposure));  // 范围 0-100
        saveSettings();
    }
    
    public void setBrightness(double brightness) {
        this.brightness = Math.max(-1.0, Math.min(1.0, brightness));
        saveSettings();
    }
    
    public void setContrast(double contrast) {
        this.contrast = Math.max(0.0, Math.min(2.0, contrast));
        saveSettings();
    }
    
    public void setSaturation(double saturation) {
        this.saturation = Math.max(0.0, Math.min(2.0, saturation));
        saveSettings();
    }
    
    public void setHue(double hue) {
        this.hue = Math.max(-1.0, Math.min(1.0, hue));
        saveSettings();
    }
    
    public void setGamma(double gamma) {
        this.gamma = Math.max(0.01, Math.min(10.0, gamma));
        saveSettings();
    }
    
    /**
     * 批量设置所有参数（只保存一次）
     */
    public void setAll(int exposure, double brightness, double contrast, double saturation, double hue, double gamma) {
        this.exposure = Math.max(0, Math.min(100, exposure));  // 范围 0-100
        this.brightness = Math.max(-1.0, Math.min(1.0, brightness));
        this.contrast = Math.max(0.0, Math.min(2.0, contrast));
        this.saturation = Math.max(0.0, Math.min(2.0, saturation));
        this.hue = Math.max(-1.0, Math.min(1.0, hue));
        this.gamma = Math.max(0.01, Math.min(10.0, gamma));
        saveSettings();
    }
    
    /**
     * 检查是否为默认值
     */
    public boolean isDefault() {
        return exposure == DEFAULT_EXPOSURE
            && brightness == SimpleWebRTCPlayer.DEFAULT_BRIGHTNESS
            && contrast == SimpleWebRTCPlayer.DEFAULT_CONTRAST
            && saturation == SimpleWebRTCPlayer.DEFAULT_SATURATION
            && hue == SimpleWebRTCPlayer.DEFAULT_HUE
            && gamma == SimpleWebRTCPlayer.DEFAULT_GAMMA;
    }
    
    @Override
    public String toString() {
        return String.format(
            "曝光=%d%%, 亮度=%.2f, 对比度=%.2f, 饱和度=%.2f, 色调=%.2f, 伽马=%.2f",
            exposure, brightness, contrast, saturation, hue, gamma
        );
    }
}

