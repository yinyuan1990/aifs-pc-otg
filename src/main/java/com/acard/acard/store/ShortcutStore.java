package com.acard.acard.store;

import javafx.scene.input.KeyCode;
import java.util.prefs.Preferences;

/**
 * 快捷键存储类
 */
public final class ShortcutStore {
    private static final String PREF_NODE = "com.acard.acard.shortcuts";
    private static final String KEY_ROW_ADD_ADJUST = "row_adjust_key";
    private static final String KEY_COL_ADD_ADJUST = "col_adjust_key";

    private static final String KEY_ROW_SUB_ADJUST = "row_sub_adjust_key";  // ⭐ 行减少
    private static final String KEY_COL_SUB_ADJUST = "col_sub_adjust_key";  // ⭐ 列减少

    private static final String KEY_CAMERA_SWITCH = "camera_switch_key";
    private static final String KEY_SETTINGS = "settings_key";
    
    // 新增快捷键存储键名
    private static final String KEY_SLOW_MOTION = "slow_motion_key"; // 慢放
    private static final String KEY_CAPTURE = "capture_key"; // 抓拍
    private static final String KEY_CLEAR = "clear_key"; // 清空
    private static final String KEY_DELETE_LAST = "delete_last_key"; // 删除最后一项
    
    // ⭐ 新增快捷键存储键名
    private static final String KEY_CAPTURE_CLEAR = "capture_clear_key"; // 抓拍清空
    
    // ⭐ 滚轮帧率设置（非快捷键，纯数值）
    private static final String KEY_SCROLL_FRAME_RATE = "scroll_frame_rate";
    public static final int DEFAULT_SCROLL_FRAME_RATE = 0;
    public static final int MIN_SCROLL_FRAME_RATE = 0;
    public static final int MAX_SCROLL_FRAME_RATE = 10;
    
    // 窗口控制快捷键存储键名
    private static final String KEY_FULLSCREEN = "fullscreen_key"; // 全屏
    private static final String KEY_REALTIME_WINDOW = "realtime_window_key"; // 实时窗口切换
    private static final String KEY_SLOWMO_WINDOW = "slowmo_window_key"; // 慢放窗口切换
    private static final String KEY_FULLSCREEN_VIEWER = "fullscreen_viewer_key"; // ⭐ 全屏查看/取消
    
    // 画质快捷键存储键名
    private static final String KEY_QUALITY_0 = "quality_0_key"; // 4K
    private static final String KEY_QUALITY_1 = "quality_1_key"; // 超清
    private static final String KEY_QUALITY_2 = "quality_2_key"; // 高清
    private static final String KEY_QUALITY_3 = "quality_3_key"; // 标清
    
    // 默认快捷键值
    public static final KeyCode DEFAULT_ROW_ADD_ADJUST_KEY = KeyCode.F1; // 行
    public static final KeyCode DEFAULT_ROW_SUB_ADJUST_KEY = KeyCode.F2; // 行
    public static final KeyCode DEFAULT_COL_ADD_ADJUST_KEY = KeyCode.F3; // 列
    public static final KeyCode DEFAULT_COL_SUB_ADJUST_KEY = KeyCode.F4; // 列

    public static final KeyCode DEFAULT_CAMERA_SWITCH_KEY = KeyCode.T; // 前后镜头切换
    public static final KeyCode DEFAULT_SETTINGS_KEY = KeyCode.R; // 设置

    // 新增快捷键默认值
    public static final KeyCode DEFAULT_SLOW_MOTION_KEY = KeyCode.W; // 慢放
    public static final KeyCode DEFAULT_CAPTURE_KEY = KeyCode.SPACE; // 抓拍
    public static final KeyCode DEFAULT_CLEAR_KEY = KeyCode.E; // 清空
    public static final KeyCode DEFAULT_DELETE_LAST_KEY = KeyCode.D; // 删除最后一项
    
    // ⭐ 新增快捷键默认值
    public static final KeyCode DEFAULT_CAPTURE_CLEAR_KEY = KeyCode.C; // 抓拍清空

    // 窗口控制快捷键默认值
    public static final KeyCode DEFAULT_FULLSCREEN_KEY = KeyCode.F; // 全屏
    public static final KeyCode DEFAULT_REALTIME_WINDOW_KEY = KeyCode.G; // 实时窗口切换
    public static final KeyCode DEFAULT_SLOWMO_WINDOW_KEY = KeyCode.H; // 慢放窗口切换
    public static final KeyCode DEFAULT_FULLSCREEN_VIEWER_KEY = KeyCode.A; // ⭐ 全屏查看/取消

    // 画质快捷键默认值
    public static final KeyCode DEFAULT_QUALITY_0_KEY = KeyCode.NUMPAD4; // 4K
    public static final KeyCode DEFAULT_QUALITY_1_KEY = KeyCode.NUMPAD3; // 超清
    public static final KeyCode DEFAULT_QUALITY_2_KEY = KeyCode.NUMPAD2; // 高清
    public static final KeyCode DEFAULT_QUALITY_3_KEY = KeyCode.NUMPAD1; // 标清

    // 旋转快捷键存储键名
    private static final String KEY_ROTATION_0 = "rotation_0_key";
    private static final String KEY_ROTATION_1 = "rotation_1_key";
    private static final String KEY_ROTATION_2 = "rotation_2_key";
    private static final String KEY_ROTATION_3 = "rotation_3_key";
    private static final String KEY_ROTATION_4 = "rotation_4_key";
    private static final String KEY_ROTATION_5 = "rotation_5_key";
    private static final String KEY_ROTATION_6 = "rotation_6_key";
    private static final String KEY_ROTATION_7 = "rotation_7_key";

    // 旋转快捷键默认值（改为小键盘，避免与滚轮帧数冲突）
    public static final KeyCode DEFAULT_ROTATION_0_KEY = KeyCode.NUMPAD0;
    public static final KeyCode DEFAULT_ROTATION_1_KEY = KeyCode.NUMPAD1;
    public static final KeyCode DEFAULT_ROTATION_2_KEY = KeyCode.NUMPAD2;
    public static final KeyCode DEFAULT_ROTATION_3_KEY = KeyCode.NUMPAD3;
    public static final KeyCode DEFAULT_ROTATION_4_KEY = KeyCode.NUMPAD4;
    public static final KeyCode DEFAULT_ROTATION_5_KEY = KeyCode.NUMPAD5;
    public static final KeyCode DEFAULT_ROTATION_6_KEY = KeyCode.NUMPAD6;
    public static final KeyCode DEFAULT_ROTATION_7_KEY = KeyCode.NUMPAD7;

    // 单例实例
    private static final ShortcutStore INSTANCE = new ShortcutStore();
    private final Preferences prefs;

    private ShortcutStore() {
        prefs = Preferences.userRoot().node(PREF_NODE);
    }

    public static ShortcutStore getInstance() {
        return INSTANCE;
    }

    // 行调整快捷键
    public KeyCode getRowAdjustKey() {
        String keyName = prefs.get(KEY_ROW_ADD_ADJUST, DEFAULT_ROW_ADD_ADJUST_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_ROW_ADD_ADJUST_KEY;
        }
    }

    public void setRowAdjustKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_ROW_ADD_ADJUST, key.name());
        }
    }

    // 列调整快捷键
    public KeyCode getColAdjustKey() {
        String keyName = prefs.get(KEY_COL_ADD_ADJUST, DEFAULT_COL_ADD_ADJUST_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_COL_ADD_ADJUST_KEY;
        }
    }

    public void setColAdjustKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_COL_ADD_ADJUST, key.name());
        }
    }

    // 行减少快捷键
    public KeyCode getRowSubAdjustKey() {
        String keyName = prefs.get(KEY_ROW_SUB_ADJUST, DEFAULT_ROW_SUB_ADJUST_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_ROW_SUB_ADJUST_KEY;
        }
    }

    public void setRowSubAdjustKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_ROW_SUB_ADJUST, key.name());
        }
    }

    // 列减少快捷键
    public KeyCode getColSubAdjustKey() {
        String keyName = prefs.get(KEY_COL_SUB_ADJUST, DEFAULT_COL_SUB_ADJUST_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_COL_SUB_ADJUST_KEY;
        }
    }

    public void setColSubAdjustKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_COL_SUB_ADJUST, key.name());
        }
    }

    // 相机切换快捷键
    public KeyCode getCameraSwitchKey() {
        String keyName = prefs.get(KEY_CAMERA_SWITCH, DEFAULT_CAMERA_SWITCH_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_CAMERA_SWITCH_KEY;
        }
    }

    public void setCameraSwitchKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_CAMERA_SWITCH, key.name());
        }
    }

    // 设置快捷键
    public KeyCode getSettingsKey() {
        String keyName = prefs.get(KEY_SETTINGS, DEFAULT_SETTINGS_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_SETTINGS_KEY;
        }
    }

    public void setSettingsKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_SETTINGS, key.name());
        }
    }

    // 慢放快捷键
    public KeyCode getSlowMotionKey() {
        String keyName = prefs.get(KEY_SLOW_MOTION, DEFAULT_SLOW_MOTION_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_SLOW_MOTION_KEY;
        }
    }

    public void setSlowMotionKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_SLOW_MOTION, key.name());
        }
    }

    // 抓拍快捷键
    public KeyCode getCaptureKey() {
        String keyName = prefs.get(KEY_CAPTURE, DEFAULT_CAPTURE_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_CAPTURE_KEY;
        }
    }

    public void setCaptureKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_CAPTURE, key.name());
        }
    }


    // 清空快捷键
    public KeyCode getClearKey() {
        String keyName = prefs.get(KEY_CLEAR, DEFAULT_CLEAR_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_CLEAR_KEY;
        }
    }

    public void setClearKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_CLEAR, key.name());
        }
    }

    // 删除最后一项快捷键
    public KeyCode getDeleteLastKey() {
        String keyName = prefs.get(KEY_DELETE_LAST, DEFAULT_DELETE_LAST_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_DELETE_LAST_KEY;
        }
    }

    public void setDeleteLastKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_DELETE_LAST, key.name());
        }
    }

    // ⭐ 抓拍清空快捷键
    public KeyCode getCaptureClearKey() {
        String keyName = prefs.get(KEY_CAPTURE_CLEAR, DEFAULT_CAPTURE_CLEAR_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_CAPTURE_CLEAR_KEY;
        }
    }

    public void setCaptureClearKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_CAPTURE_CLEAR, key.name());
        }
    }

    // 全屏快捷键
    public KeyCode getFullscreenKey() {
        String keyName = prefs.get(KEY_FULLSCREEN, DEFAULT_FULLSCREEN_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_FULLSCREEN_KEY;
        }
    }

    public void setFullscreenKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_FULLSCREEN, key.name());
        }
    }

    // 实时窗口切换快捷键
    public KeyCode getRealtimeWindowKey() {
        String keyName = prefs.get(KEY_REALTIME_WINDOW, DEFAULT_REALTIME_WINDOW_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_REALTIME_WINDOW_KEY;
        }
    }

    public void setRealtimeWindowKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_REALTIME_WINDOW, key.name());
        }
    }

    // 慢放窗口切换快捷键
    public KeyCode getSlowmoWindowKey() {
        String keyName = prefs.get(KEY_SLOWMO_WINDOW, DEFAULT_SLOWMO_WINDOW_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_SLOWMO_WINDOW_KEY;
        }
    }

    public void setSlowmoWindowKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_SLOWMO_WINDOW, key.name());
        }
    }

    // ⭐ 全屏查看/取消快捷键
    public KeyCode getFullscreenViewerKey() {
        String keyName = prefs.get(KEY_FULLSCREEN_VIEWER, DEFAULT_FULLSCREEN_VIEWER_KEY.name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return DEFAULT_FULLSCREEN_VIEWER_KEY;
        }
    }

    public void setFullscreenViewerKey(KeyCode key) {
        if (key != null) {
            prefs.put(KEY_FULLSCREEN_VIEWER, key.name());
        }
    }

    // 画质快捷键
    public KeyCode getQualityKey(int qualityIndex) {
        String keyName = getQualityKeyName(qualityIndex);
        KeyCode defaultKey = getDefaultQualityKey(qualityIndex);
        String storedKeyName = prefs.get(keyName, defaultKey.name());
        try {
            return KeyCode.valueOf(storedKeyName);
        } catch (IllegalArgumentException e) {
            return defaultKey;
        }
    }

    public void setQualityKey(int qualityIndex, KeyCode key) {
        if (key != null) {
            String keyName = getQualityKeyName(qualityIndex);
            prefs.put(keyName, key.name());
        }
    }

    private String getQualityKeyName(int qualityIndex) {
        switch (qualityIndex) {
            case 0: return KEY_QUALITY_0;
            case 1: return KEY_QUALITY_1;
            case 2: return KEY_QUALITY_2;
            case 3: return KEY_QUALITY_3;
            default: throw new IllegalArgumentException("Invalid quality index: " + qualityIndex);
        }
    }

    private KeyCode getDefaultQualityKey(int qualityIndex) {
        switch (qualityIndex) {
            case 0: return DEFAULT_QUALITY_0_KEY;
            case 1: return DEFAULT_QUALITY_1_KEY;
            case 2: return DEFAULT_QUALITY_2_KEY;
            case 3: return DEFAULT_QUALITY_3_KEY;
            default: throw new IllegalArgumentException("Invalid quality index: " + qualityIndex);
        }
    }

    // 旋转快捷键
    public KeyCode getRotationKey(int index) {
        String keyName = prefs.get("rotation_" + index + "_key", getDefaultRotationKey(index).name());
        try {
            return KeyCode.valueOf(keyName);
        } catch (IllegalArgumentException e) {
            return getDefaultRotationKey(index);
        }
    }

    public void setRotationKey(int index, KeyCode key) {
        if (key != null) {
            prefs.put("rotation_" + index + "_key", key.name());
        }
    }

    private KeyCode getDefaultRotationKey(int index) {
        switch (index) {
            case 0: return DEFAULT_ROTATION_0_KEY;
            case 1: return DEFAULT_ROTATION_1_KEY;
            case 2: return DEFAULT_ROTATION_2_KEY;
            case 3: return DEFAULT_ROTATION_3_KEY;
            case 4: return DEFAULT_ROTATION_4_KEY;
            case 5: return DEFAULT_ROTATION_5_KEY;
            case 6: return DEFAULT_ROTATION_6_KEY;
            case 7: return DEFAULT_ROTATION_7_KEY;
            default: return KeyCode.DIGIT0;
        }
    }

    /**
     * 重置所有快捷键为默认值
     */
    public void resetToDefaults() {
        prefs.put(KEY_ROW_ADD_ADJUST, DEFAULT_ROW_ADD_ADJUST_KEY.name());
        prefs.put(KEY_COL_ADD_ADJUST, DEFAULT_COL_ADD_ADJUST_KEY.name());

        prefs.put(KEY_ROW_SUB_ADJUST, DEFAULT_ROW_SUB_ADJUST_KEY.name());
        prefs.put(KEY_COL_SUB_ADJUST, DEFAULT_COL_SUB_ADJUST_KEY.name());

        prefs.put(KEY_CAMERA_SWITCH, DEFAULT_CAMERA_SWITCH_KEY.name());
        prefs.put(KEY_SETTINGS, DEFAULT_SETTINGS_KEY.name());
        
        // 重置新增快捷键
        prefs.put(KEY_SLOW_MOTION, DEFAULT_SLOW_MOTION_KEY.name());
        prefs.put(KEY_CAPTURE, DEFAULT_CAPTURE_KEY.name());
        prefs.put(KEY_CLEAR, DEFAULT_CLEAR_KEY.name());
        prefs.put(KEY_DELETE_LAST, DEFAULT_DELETE_LAST_KEY.name());
        
        // ⭐ 重置新增快捷键
        prefs.put(KEY_CAPTURE_CLEAR, DEFAULT_CAPTURE_CLEAR_KEY.name());
        
        // 重置窗口控制快捷键
        prefs.put(KEY_FULLSCREEN, DEFAULT_FULLSCREEN_KEY.name());
        prefs.put(KEY_REALTIME_WINDOW, DEFAULT_REALTIME_WINDOW_KEY.name());
        prefs.put(KEY_SLOWMO_WINDOW, DEFAULT_SLOWMO_WINDOW_KEY.name());
        prefs.put(KEY_FULLSCREEN_VIEWER, DEFAULT_FULLSCREEN_VIEWER_KEY.name()); // ⭐ 全屏查看/取消
        
        // 重置画质快捷键
        prefs.put(KEY_QUALITY_0, DEFAULT_QUALITY_0_KEY.name());
        prefs.put(KEY_QUALITY_1, DEFAULT_QUALITY_1_KEY.name());
        prefs.put(KEY_QUALITY_2, DEFAULT_QUALITY_2_KEY.name());
        prefs.put(KEY_QUALITY_3, DEFAULT_QUALITY_3_KEY.name());
        
        // 重置旋转快捷键
        for (int i = 0; i <= 7; i++) {
            prefs.put("rotation_" + i + "_key", getDefaultRotationKey(i).name());
        }
    }

    // ⭐ 滚轮帧率设置（非快捷键，纯数值 0-10）
    public int getScrollFrameRate() {
        int value = prefs.getInt(KEY_SCROLL_FRAME_RATE, DEFAULT_SCROLL_FRAME_RATE);
        return Math.max(MIN_SCROLL_FRAME_RATE, Math.min(MAX_SCROLL_FRAME_RATE, value));
    }
    
    public void setScrollFrameRate(int value) {
        int clamped = Math.max(MIN_SCROLL_FRAME_RATE, Math.min(MAX_SCROLL_FRAME_RATE, value));
        prefs.putInt(KEY_SCROLL_FRAME_RATE, clamped);
    }
    
    /**
     * 清空所有快捷键设置
     */
    public void clearAll() {
        try {
            prefs.clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}