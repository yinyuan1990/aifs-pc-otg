package com.acard.acard.store;

import com.acard.acard.tools.LogTools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * 抓拍参数本地持久化（前后张数、偏移）。
 * 范围 10..240，步进 10，默认 10。
 * 偏移范围 0..10，默认 0。
 */
public final class CaptureStore {
    private static final String PREF_NODE = "com.acard.acard.capture";
    private static final String KEY_PRE = "capture_pre_count";
    private static final String KEY_POST = "capture_post_count";
    private static final String KEY_OFFSET = "capture_offset";  // ⭐ 偏移

    public static final int DEFAULT_COUNT = 10;
    public static final int MIN_COUNT = 10;
    public static final int MAX_COUNT = 240;
    public static final int STEP = 10;
    
    // ⭐ 偏移参数
    public static final int DEFAULT_OFFSET = 0;
    public static final int MIN_OFFSET = 0;
    public static final int MAX_OFFSET = 9;

    private static final CaptureStore INSTANCE = new CaptureStore();
    private final Preferences prefs;
    
    // ⭐ 偏移变化监听器列表
    private final List<Consumer<Integer>> offsetListeners = new CopyOnWriteArrayList<>();

    private CaptureStore() {
        prefs = Preferences.userRoot().node(PREF_NODE);
    }

    public static CaptureStore getInstance() {
        return INSTANCE;
    }

    public int getPreCaptureCount() {
        return clampToStep(prefs.getInt(KEY_PRE, DEFAULT_COUNT));
    }

    public int getPostCaptureCount() {
        return clampToStep(prefs.getInt(KEY_POST, DEFAULT_COUNT));
    }

    public void setPreCaptureCount(int count) {
        prefs.putInt(KEY_PRE, clampToStep(count));
    }

    public void setPostCaptureCount(int count) {
        prefs.putInt(KEY_POST, clampToStep(count));
    }
    
    /**
     * ⭐ 获取偏移值（0-10）
     */
    public int getOffset() {
        LogTools.getInstance().logRecord3("偏移值 获取: "+prefs.getInt(KEY_OFFSET, DEFAULT_OFFSET));
        return clampOffset(prefs.getInt(KEY_OFFSET, DEFAULT_OFFSET));
    }
    
    /**
     * ⭐ 设置偏移值（0-9）
     */
    public void setOffset(int offset) {
        int clampedOffset = clampOffset(offset);
        LogTools.getInstance().logRecord6("🔧 [CaptureStore.setOffset] 设置偏移值: " + offset + " → " + clampedOffset);
        LogTools.getInstance().logRecord3("偏移值: " + clampedOffset);
        prefs.putInt(KEY_OFFSET, clampedOffset);
        
        // ⭐ 通知所有监听器
        LogTools.getInstance().logRecord6("🔧 [CaptureStore.setOffset] 监听器数量: " + offsetListeners.size());
        notifyOffsetListeners(clampedOffset);
    }
    
    /**
     * ⭐ 添加偏移变化监听器（用于UI实时更新）
     */
    public void addOffsetListener(Consumer<Integer> listener) {
        if (listener != null) {
            offsetListeners.add(listener);
        }
    }
    
    /**
     * ⭐ 移除偏移变化监听器
     */
    public void removeOffsetListener(Consumer<Integer> listener) {
        offsetListeners.remove(listener);
    }
    
    /**
     * ⭐ 通知所有监听器
     */
    private void notifyOffsetListeners(int newOffset) {
        LogTools.getInstance().logRecord6("🔧 [CaptureStore.notifyOffsetListeners] 通知 " + offsetListeners.size() + " 个监听器，偏移=" + newOffset);
        int idx = 0;
        for (Consumer<Integer> listener : offsetListeners) {
            try {
                LogTools.getInstance().logRecord6("🔧 [CaptureStore.notifyOffsetListeners] 调用监听器 #" + idx);
                listener.accept(newOffset);
                LogTools.getInstance().logRecord6("🔧 [CaptureStore.notifyOffsetListeners] 监听器 #" + idx + " 完成");
                idx++;
            } catch (Throwable e) {
                LogTools.getInstance().logRecord6("🔧 [CaptureStore.notifyOffsetListeners] 监听器 #" + idx + " 异常: " + e.getMessage());
                LogTools.getInstance().logRecord3("⚠️ 偏移监听器异常: " + e.getMessage());
            }
        }
    }
    
    /**
     * ⭐ 限制偏移值在有效范围内
     */
    private int clampOffset(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }
    
    /**
     * ⭐ 根据 FPS 计算偏移值
     * 50以下 → 3
     * 50-60 → 4
     * 60-70 → 5
     * 70-80 → 6
     * 80-90 → 7
     * 90-100 → 8
     * 100-120 → 9
     */
    public static int calculateOffsetByFps(int fps) {
        if (fps < 50) return 3;
        if (fps < 60) return 4;
        if (fps < 70) return 5;
        if (fps < 80) return 6;
        if (fps < 90) return 7;
        if (fps < 100) return 8;
        return 8;  // 100-120
    }
    
    /**
     * ⭐ 根据 FPS 自动设置偏移值
     */
    public void setOffsetByFps(int fps) {
        int offset = calculateOffsetByFps(fps);
        LogTools.getInstance().logRecord3("📊 根据FPS自动设置偏移: fps=" + fps + " → offset=" + offset);
        setOffset(offset);
    }

    private int clampToStep(int value) {
        int clamped = Math.max(MIN_COUNT, Math.min(MAX_COUNT, value));
        int remainder = clamped % STEP;
        if (remainder != 0) {
            int down = clamped - remainder;
            int up = down + STEP;
            clamped = (clamped - down) < (up - clamped) ? down : up;
        }
        return Math.max(MIN_COUNT, Math.min(MAX_COUNT, clamped));
    }
}