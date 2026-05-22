package com.acard.acard.config;

import javafx.scene.input.KeyCode;
import com.acard.acard.store.ShortcutStore;

/**
 * 快捷键配置管理类
 */
public class ShortcutConfig {
    private final ShortcutStore store;
    
    public ShortcutConfig() {
        this.store = ShortcutStore.getInstance();
    }
    
    /**
     * 获取行数调整快捷键
     */
    public KeyCode getRowAdjustKey() {
        return store.getRowAdjustKey();
    }
    
    /**
     * 设置行数调整快捷键
     */
    public void setRowAdjustKey(KeyCode key) {
        store.setRowAdjustKey(key);
    }
    
    /**
     * 获取列数调整快捷键
     */
    public KeyCode getColAdjustKey() {
        return store.getColAdjustKey();
    }
    
    /**
     * 设置列数调整快捷键
     */
    public void setColAdjustKey(KeyCode key) {
        store.setColAdjustKey(key);
    }
    
    /**
     * 重置为默认快捷键
     */
    public void resetToDefaults() {
        store.resetToDefaults();
    }
    
    /**
     * 检查指定键是否为行数调整键
     */
    public boolean isRowAdjustKey(KeyCode key) {
        return getRowAdjustKey().equals(key);
    }
    
    /**
     * 检查指定键是否为列数调整键
     */
    public boolean isColAdjustKey(KeyCode key) {
        return getColAdjustKey().equals(key);
    }
    
    /**
     * 获取快捷键的显示名称
     */
    public String getRowAdjustKeyDisplayName() {
        return getRowAdjustKey().getName();
    }

    public String getColAdjustKeyDisplayName() {
        return getColAdjustKey().getName();
    }

    @Override
    public String toString() {
        return String.format("ShortcutConfig{rowAdjustKey=%s, colAdjustKey=%s}",
                           getRowAdjustKey().getName(), getColAdjustKey().getName());
    }

    // 在ShortcutConfig.java中添加：
    public KeyCode getRotationKey(int index) {
        return store.getRotationKey(index);
    }

    public void setRotationKey(int index, KeyCode key) {
        store.setRotationKey(index, key);
    }

    public boolean isRotationKey(KeyCode key) {
        for (int i = 0; i <= 7; i++) {
            if (getRotationKey(i).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public int getRotationIndex(KeyCode key) {
        for (int i = 0; i <= 7; i++) {
            if (getRotationKey(i).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    // 添加前后镜头切换快捷键的配置方法
    public KeyCode getCameraSwitchKey() {
        return store.getCameraSwitchKey();
    }

    public void setCameraSwitchKey(KeyCode key) {
        store.setCameraSwitchKey(key);
    }

    /**
     * 获取画质快捷键
     * @param qualityIndex 画质索引 (0=4K, 1=超清, 2=高清, 3=标清)
     */
    public KeyCode getQualityKey(int qualityIndex) {
        return store.getQualityKey(qualityIndex);
    }

    /**
     * 设置画质快捷键
     * @param qualityIndex 画质索引 (0=4K, 1=超清, 2=高清, 3=标清)
     * @param key 快捷键
     */
    public void setQualityKey(int qualityIndex, KeyCode key) {
        store.setQualityKey(qualityIndex, key);
    }

    /**
     * 检查是否为画质快捷键
     */
    public boolean isQualityKey(KeyCode key) {
        for (int i = 0; i <= 3; i++) {
            if (getQualityKey(i).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取画质快捷键对应的索引
     */
    public int getQualityIndex(KeyCode key) {
        for (int i = 0; i <= 3; i++) {
            if (getQualityKey(i).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取设置快捷键
     */
    public KeyCode getSettingsKey() {
        return store.getSettingsKey();
    }

    /**
     * 设置设置快捷键
     */
    public void setSettingsKey(KeyCode key) {
        store.setSettingsKey(key);
    }

    /**
     * 检查是否为设置快捷键
     */
    public boolean isSettingsKey(KeyCode key) {
        return getSettingsKey().equals(key);
    }
    
    // 新增快捷键的访问方法
    
    /**
     * 获取慢放快捷键
     */
    public KeyCode getSlowMotionKey() {
        return store.getSlowMotionKey();
    }
    
    /**
     * 设置慢放快捷键
     */
    public void setSlowMotionKey(KeyCode key) {
        store.setSlowMotionKey(key);
    }
    
    /**
     * 检查是否为慢放快捷键
     */
    public boolean isSlowMotionKey(KeyCode key) {
        return getSlowMotionKey().equals(key);
    }
    
    /**
     * 获取抓拍快捷键
     */
    public KeyCode getCaptureKey() {
        return store.getCaptureKey();
    }
    
    /**
     * 设置抓拍快捷键
     */
    public void setCaptureKey(KeyCode key) {
        store.setCaptureKey(key);
    }
    
    /**
     * 检查是否为抓拍快捷键
     */
    public boolean isCaptureKey(KeyCode key) {
        return getCaptureKey().equals(key);
    }

    
    /**
     * 获取清空快捷键
     */
    public KeyCode getClearKey() {
        return store.getClearKey();
    }
    
    /**
     * 设置清空快捷键
     */
    public void setClearKey(KeyCode key) {
        store.setClearKey(key);
    }
    
    /**
     * 检查是否为清空快捷键
     */
    public boolean isClearKey(KeyCode key) {
        return getClearKey().equals(key);
    }
    
    /**
     * 获取删除最后一项快捷键
     */
    public KeyCode getDeleteLastKey() {
        return store.getDeleteLastKey();
    }
    
    /**
     * 设置删除最后一项快捷键
     */
    public void setDeleteLastKey(KeyCode key) {
        store.setDeleteLastKey(key);
    }
    
    /**
     * 检查是否为删除最后一项快捷键
     */
    public boolean isDeleteLastKey(KeyCode key) {
        return getDeleteLastKey().equals(key);
    }

    // ⭐ 新增快捷键方法



    /**
     * 获取抓拍清空快捷键
     */
    public KeyCode getCaptureClearKey() {
        return store.getCaptureClearKey();
    }

    /**
     * 设置抓拍清空快捷键
     */
    public void setCaptureClearKey(KeyCode key) {
        store.setCaptureClearKey(key);
    }

    /**
     * 检查是否为抓拍清空快捷键
     */
    public boolean isCaptureClearKey(KeyCode key) {
        return getCaptureClearKey().equals(key);
    }

    /**
     * 获取抓拍清空快捷键显示名称
     */
    public String getCaptureClearKeyDisplayName() {
        return getCaptureClearKey().getName();
    }


    // 窗口控制快捷键方法

    /**
     * 获取全屏快捷键
     */
    public KeyCode getFullscreenKey() {
        return store.getFullscreenKey();
    }

    /**
     * 设置全屏快捷键
     */
    public void setFullscreenKey(KeyCode key) {
        store.setFullscreenKey(key);
    }

    /**
     * 检查是否为全屏快捷键
     */
    public boolean isFullscreenKey(KeyCode key) {
        return getFullscreenKey().equals(key);
    }

    /**
     * 获取实时窗口切换快捷键
     */
    public KeyCode getRealtimeWindowKey() {
        return store.getRealtimeWindowKey();
    }

    /**
     * 设置实时窗口切换快捷键
     */
    public void setRealtimeWindowKey(KeyCode key) {
        store.setRealtimeWindowKey(key);
    }

    /**
     * 检查是否为实时窗口切换快捷键
     */
    public boolean isRealtimeWindowKey(KeyCode key) {
        return getRealtimeWindowKey().equals(key);
    }

    /**
     * 获取慢放窗口切换快捷键
     */
    public KeyCode getSlowmoWindowKey() {
        return store.getSlowmoWindowKey();
    }

    /**
     * 设置慢放窗口切换快捷键
     */
    public void setSlowmoWindowKey(KeyCode key) {
        store.setSlowmoWindowKey(key);
    }

    /**
     * 检查是否为慢放窗口切换快捷键
     */
    public boolean isSlowmoWindowKey(KeyCode key) {
        return getSlowmoWindowKey().equals(key);
    }

    /**
     * 获取全屏查看/取消快捷键
     */
    public KeyCode getFullscreenViewerKey() {
        return store.getFullscreenViewerKey();
    }

    /**
     * 设置全屏查看/取消快捷键
     */
    public void setFullscreenViewerKey(KeyCode key) {
        store.setFullscreenViewerKey(key);
    }

    /**
     * 检查是否为全屏查看/取消快捷键
     */
    public boolean isFullscreenViewerKey(KeyCode key) {
        return getFullscreenViewerKey().equals(key);
    }

    /**
     * 获取全屏查看/取消快捷键显示名称
     */
    public String getFullscreenViewerKeyDisplayName() {
        return getFullscreenViewerKey().getName();
    }

    /**
     * 获取行数减少快捷键
     */
    public KeyCode getRowSubAdjustKey() {
        return store.getRowSubAdjustKey();
    }

    /**
     * 设置行数减少快捷键
     */
    public void setRowSubAdjustKey(KeyCode key) {
        store.setRowSubAdjustKey(key);
    }

    /**
     * 获取列数减少快捷键
     */
    public KeyCode getColSubAdjustKey() {
        return store.getColSubAdjustKey();
    }

    /**
     * 设置列数减少快捷键
     */
    public void setColSubAdjustKey(KeyCode key) {
        store.setColSubAdjustKey(key);
    }

    /**
     * 检查指定键是否为行数减少键
     */
    public boolean isRowSubAdjustKey(KeyCode key) {
        return getRowSubAdjustKey().equals(key);
    }

    /**
     * 检查指定键是否为列数减少键
     */
    public boolean isColSubAdjustKey(KeyCode key) {
        return getColSubAdjustKey().equals(key);
    }

}