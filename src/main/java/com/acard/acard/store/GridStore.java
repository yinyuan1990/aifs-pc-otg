package com.acard.acard.store;

import java.util.prefs.Preferences;

/**
 * 网格行列参数本地持久化（行数和列数）。
 * 范围 1..10，默认 1。
 */
public final class GridStore {
    private static final String PREF_NODE = "com.acard.acard.grid";
    private static final String KEY_ROWS = "grid_rows";
    private static final String KEY_COLS = "grid_cols";
    private static final String KEY_LAYOUT_DIRECTION = "grid_layout_direction";
    private static final String KEY_FRONT_FRAMES = "grid_front_frames";  // ⭐ 新增
    private static final String KEY_BACK_FRAMES = "grid_back_frames";    // ⭐ 新增
    
    // AI 视图专用
    private static final String KEY_AI_ROWS = "ai_grid_rows";
    private static final String KEY_AI_COLS = "ai_grid_cols";
    private static final String KEY_AI_LAYOUT_DIRECTION = "ai_grid_layout_direction";

    public static final int DEFAULT_SIZE = 1;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 10;
    public static final int DEFAULT_FRAMES = 5;  // ⭐ 默认前后帧数
    public static final boolean DEFAULT_HORIZONTAL_LAYOUT = true; // 默认行排（从左到右）

    private static final GridStore INSTANCE = new GridStore();
    private final Preferences prefs;

    private GridStore() {
        prefs = Preferences.userRoot().node(PREF_NODE);
    }

    public static GridStore getInstance() {
        return INSTANCE;
    }

    /**
     * 获取当前网格行数（持久化）。不存在则返回默认值 1。
     */
    public int getRows() {
        return clampToRange(prefs.getInt(KEY_ROWS, DEFAULT_SIZE));
    }

    /**
     * 获取当前网格列数（持久化）。不存在则返回默认值 1。
     */
    public int getCols() {
        return clampToRange(prefs.getInt(KEY_COLS, DEFAULT_SIZE));
    }

    /**
     * 设置并持久化网格行数（自动限制到合法范围 1-10）。
     */
    public void setRows(int rows) {
        prefs.putInt(KEY_ROWS, clampToRange(rows));
    }

    /**
     * 设置并持久化网格列数（自动限制到合法范围 1-10）。
     */
    public void setCols(int cols) {
        prefs.putInt(KEY_COLS, clampToRange(cols));
    }

    /**
     * 同时设置并持久化网格行数和列数。
     */
    public void setGridSize(int rows, int cols) {
        setRows(rows);
        setCols(cols);
    }

    /**
     * 获取当前排列方式（持久化）。true=行排（从左到右），false=竖排（从上到下）。
     */
    public boolean isHorizontalLayout() {
        return prefs.getBoolean(KEY_LAYOUT_DIRECTION, DEFAULT_HORIZONTAL_LAYOUT);
    }

    /**
     * 设置并持久化排列方式。true=行排（从左到右），false=竖排（从上到下）。
     */
    public void setHorizontalLayout(boolean horizontal) {
        prefs.putBoolean(KEY_LAYOUT_DIRECTION, horizontal);
    }

    /**
     * ⭐ 获取前帧数（持久化）
     */
    public int getFrontFrames() {
        return clampFrames(prefs.getInt(KEY_FRONT_FRAMES, DEFAULT_FRAMES));
    }

    /**
     * ⭐ 设置前帧数（持久化）
     */
    public void setFrontFrames(int frames) {
        prefs.putInt(KEY_FRONT_FRAMES, clampFrames(frames));
    }

    /**
     * ⭐ 获取后帧数（持久化）
     */
    public int getBackFrames() {
        return clampFrames(prefs.getInt(KEY_BACK_FRAMES, DEFAULT_FRAMES));
    }

    /**
     * ⭐ 设置后帧数（持久化）
     */
    public void setBackFrames(int frames) {
        prefs.putInt(KEY_BACK_FRAMES, clampFrames(frames));
    }

    // ========== AI 视图专用方法 ==========
    
    /**
     * 获取 AI 视图行数（持久化）。默认 2。
     */
    public int getAiRows() {
        return clampAiRange(prefs.getInt(KEY_AI_ROWS, 2));
    }
    
    /**
     * 获取 AI 视图列数（持久化）。默认 3。
     */
    public int getAiCols() {
        return clampAiRange(prefs.getInt(KEY_AI_COLS, 3));
    }
    
    /**
     * 设置并持久化 AI 视图行数（1-6）。
     */
    public void setAiRows(int rows) {
        prefs.putInt(KEY_AI_ROWS, clampAiRange(rows));
    }
    
    /**
     * 设置并持久化 AI 视图列数（1-6）。
     */
    public void setAiCols(int cols) {
        prefs.putInt(KEY_AI_COLS, clampAiRange(cols));
    }
    
    /**
     * 获取 AI 视图排列方式（持久化）。
     */
    public boolean isAiHorizontalLayout() {
        return prefs.getBoolean(KEY_AI_LAYOUT_DIRECTION, true);
    }
    
    /**
     * 设置并持久化 AI 视图排列方式。
     */
    public void setAiHorizontalLayout(boolean horizontal) {
        prefs.putBoolean(KEY_AI_LAYOUT_DIRECTION, horizontal);
    }
    
    private int clampAiRange(int value) {
        return Math.max(1, Math.min(6, value));
    }

    private int clampToRange(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }
    
    private int clampFrames(int value) {
        return Math.max(0, Math.min(MAX_SIZE, value));
    }
}