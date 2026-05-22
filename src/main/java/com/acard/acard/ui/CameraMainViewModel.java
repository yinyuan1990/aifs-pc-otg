package com.acard.acard.ui;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 相机主界面视图模型
 * 负责管理界面状态和数据绑定
 */
public class CameraMainViewModel {
    
    // 状态消息属性
    private final StringProperty statusMessage = new SimpleStringProperty("就绪");
    
    // 全屏状态属性
    private final BooleanProperty isFullScreen = new SimpleBooleanProperty(false);
    
    // 窗口最大化状态属性
    private final BooleanProperty isMaximized = new SimpleBooleanProperty(false);
    
    // 当前窗口模式属性 (实时/慢放)
    private final StringProperty currentWindowMode = new SimpleStringProperty("实时");
    
    // 设备连接状态属性
    private final BooleanProperty isDeviceConnected = new SimpleBooleanProperty(false);
    
    // 相机设备列表
    private final ObservableList<String> cameraDevices = FXCollections.observableArrayList();
    
    // 当前选中的相机设备
    private final StringProperty selectedCameraDevice = new SimpleStringProperty();
    
    // 应用程序标题
    private final StringProperty applicationTitle = new SimpleStringProperty("相机主界面系统");
    
    /**
     * 构造函数
     */
    public CameraMainViewModel() {
        initializeDefaultValues();
    }
    
    /**
     * 初始化默认值
     */
    private void initializeDefaultValues() {
        // 初始化相机设备列表（示例数据）
        cameraDevices.addAll(
            "相机设备 1",
            "相机设备 2",
            "相机设备 3"
        );
        
        // 设置默认选中的设备
        if (!cameraDevices.isEmpty()) {
            selectedCameraDevice.set(cameraDevices.get(0));
        }
    }
    
    // ==================== 属性访问方法 ====================
    
    /**
     * 获取状态消息属性
     */
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }
    
    /**
     * 获取状态消息
     */
    public String getStatusMessage() {
        return statusMessage.get();
    }
    
    /**
     * 设置状态消息
     */
    public void setStatusMessage(String message) {
        this.statusMessage.set(message);
    }
    
    /**
     * 获取全屏状态属性
     */
    public BooleanProperty isFullScreenProperty() {
        return isFullScreen;
    }
    
    /**
     * 获取全屏状态
     */
    public boolean isFullScreen() {
        return isFullScreen.get();
    }
    
    /**
     * 设置全屏状态
     */
    public void setFullScreen(boolean fullScreen) {
        this.isFullScreen.set(fullScreen);
        updateStatusMessage(fullScreen ? "已进入全屏模式" : "已退出全屏模式");
    }
    
    /**
     * 获取最大化状态属性
     */
    public BooleanProperty isMaximizedProperty() {
        return isMaximized;
    }
    
    /**
     * 获取最大化状态
     */
    public boolean isMaximized() {
        return isMaximized.get();
    }
    
    /**
     * 设置最大化状态
     */
    public void setMaximized(boolean maximized) {
        this.isMaximized.set(maximized);
        updateStatusMessage(maximized ? "窗口已最大化" : "窗口已还原");
    }
    
    /**
     * 获取当前窗口模式属性
     */
    public StringProperty currentWindowModeProperty() {
        return currentWindowMode;
    }
    
    /**
     * 获取当前窗口模式
     */
    public String getCurrentWindowMode() {
        return currentWindowMode.get();
    }
    
    /**
     * 设置当前窗口模式
     */
    public void setCurrentWindowMode(String mode) {
        this.currentWindowMode.set(mode);
        updateStatusMessage("切换到" + mode + "窗口模式");
    }
    
    /**
     * 获取设备连接状态属性
     */
    public BooleanProperty isDeviceConnectedProperty() {
        return isDeviceConnected;
    }
    
    /**
     * 获取设备连接状态
     */
    public boolean isDeviceConnected() {
        return isDeviceConnected.get();
    }
    
    /**
     * 设置设备连接状态
     */
    public void setDeviceConnected(boolean connected) {
        this.isDeviceConnected.set(connected);
        updateStatusMessage(connected ? "设备已连接" : "设备已断开");
    }
    
    /**
     * 获取相机设备列表
     */
    public ObservableList<String> getCameraDevices() {
        return cameraDevices;
    }
    
    /**
     * 获取选中的相机设备属性
     */
    public StringProperty selectedCameraDeviceProperty() {
        return selectedCameraDevice;
    }
    
    /**
     * 获取选中的相机设备
     */
    public String getSelectedCameraDevice() {
        return selectedCameraDevice.get();
    }
    
    /**
     * 设置选中的相机设备
     */
    public void setSelectedCameraDevice(String device) {
        this.selectedCameraDevice.set(device);
        updateStatusMessage("已选择设备: " + device);
    }
    
    /**
     * 获取应用程序标题属性
     */
    public StringProperty applicationTitleProperty() {
        return applicationTitle;
    }
    
    /**
     * 获取应用程序标题
     */
    public String getApplicationTitle() {
        return applicationTitle.get();
    }
    
    /**
     * 设置应用程序标题
     */
    public void setApplicationTitle(String title) {
        this.applicationTitle.set(title);
    }
    
    // ==================== 业务逻辑方法 ====================
    
    /**
     * 切换全屏模式
     */
    public void toggleFullScreen() {
        setFullScreen(!isFullScreen());
    }
    
    /**
     * 切换最大化状态
     */
    public void toggleMaximized() {
        setMaximized(!isMaximized());
    }
    
    /**
     * 切换到实时窗口模式
     */
    public void switchToRealTimeMode() {
        setCurrentWindowMode("实时");
    }
    
    /**
     * 切换到慢放窗口模式
     */
    public void switchToSlowMotionMode() {
        setCurrentWindowMode("慢放");
    }
    
    /**
     * 添加相机设备
     */
    public void addCameraDevice(String deviceName) {
        if (!cameraDevices.contains(deviceName)) {
            cameraDevices.add(deviceName);
            updateStatusMessage("已添加设备: " + deviceName);
        }
    }
    
    /**
     * 移除相机设备
     */
    public void removeCameraDevice(String deviceName) {
        if (cameraDevices.remove(deviceName)) {
            updateStatusMessage("已移除设备: " + deviceName);
            // 如果移除的是当前选中的设备，则选择第一个可用设备
            if (deviceName.equals(getSelectedCameraDevice()) && !cameraDevices.isEmpty()) {
                setSelectedCameraDevice(cameraDevices.get(0));
            }
        }
    }
    
    /**
     * 刷新设备列表
     */
    public void refreshDeviceList() {
        // TODO: 实现设备扫描逻辑
        updateStatusMessage("正在刷新设备列表...");
        // 模拟刷新延迟
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                javafx.application.Platform.runLater(() -> {
                    updateStatusMessage("设备列表已刷新");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 更新状态消息（内部方法）
     */
    private void updateStatusMessage(String message) {
        setStatusMessage(message);
    }
    
    /**
     * 重置所有状态
     */
    public void resetAllStates() {
        setFullScreen(false);
        setMaximized(false);
        setCurrentWindowMode("实时");
        setDeviceConnected(false);
        setStatusMessage("状态已重置");
    }
}