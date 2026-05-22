package com.acard.acard.storage;

import java.util.prefs.Preferences;

/**
 * 本地持久化慢放参数（慢放帧数、慢放倍数）。
 * ⭐ 低端机优化：默认 1000 帧，最小 500 帧，最大 3000 帧，步进 60 帧。
 */
public final class SlowmoStore {
    private static final String PREF_NODE = "com.acard.acard.slowmo";
    private static final String KEY_FRAMES = "slowmo_frames";
    private static final String KEY_SPEED = "slowmo_speed";  // ✅ 新增：慢放倍数

    public static final int DEFAULT_FRAMES = 10000; // ✅ 默认10000帧
    public static final int MIN_FRAMES = 3000;      // 3000→500
    public static final int MAX_FRAMES = 13000;     // 10000→3000
    public static final int STEP = 60;
    
    public static final String DEFAULT_SPEED = "1.0x";  // ✅ 默认1倍速

    private static final SlowmoStore INSTANCE = new SlowmoStore();
    private final Preferences prefs;

    private SlowmoStore() {
        prefs = Preferences.userRoot().node(PREF_NODE);
    }

    public static SlowmoStore getInstance() {
        return INSTANCE;
    }

    /**
     * 获取当前慢放帧数（持久化）。不存在则返回默认值 3000。
     */
    public int getSlowmoFrames() {
        int v = prefs.getInt(KEY_FRAMES, DEFAULT_FRAMES);
        return clampToStep(v);
    }

    /**
     * 设置并持久化慢放帧数（自动按 60 帧步进对齐到合法范围）。
     */
    public void setSlowmoFrames(int frames) {
        int aligned = clampToStep(frames);
        prefs.putInt(KEY_FRAMES, aligned);
    }

    private int clampToStep(int value) {
        int clamped = Math.max(MIN_FRAMES, Math.min(MAX_FRAMES, value));
        int remainder = clamped % STEP;
        if (remainder != 0) {
            // 四舍五入到最近的步进
            int down = clamped - remainder;
            int up = down + STEP;
            clamped = (clamped - down) < (up - clamped) ? down : up;
        }
        return Math.max(MIN_FRAMES, Math.min(MAX_FRAMES, clamped));
    }
    
    /**
     * ✅ 获取当前慢放倍数（持久化）。不存在则返回默认值 "1.0x"。
     */
    public String getSlowmoSpeed() {
        return prefs.get(KEY_SPEED, DEFAULT_SPEED);
    }
    
    /**
     * ✅ 设置并持久化慢放倍数（如 "1.0x", "2.0x" 等）。
     */
    public void setSlowmoSpeed(String speed) {
        if (speed != null && !speed.isEmpty()) {
            prefs.put(KEY_SPEED, speed);
            System.out.println("✅ [SlowmoStore] 保存慢放倍数: " + speed);
        }
    }
}