package com.acard.acard.ui;

import com.acard.acard.config.ShortcutConfig;
import com.acard.acard.store.GridStore;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Node;
import javafx.application.Platform;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * 快捷键管理器
 * 负责处理全局快捷键事件监听和分发
 */
public class ShortcutManager {
   // 快捷键配置
    private final ShortcutConfig config;
    
    // 当前按下的键
    private final Set<KeyCode> pressedKeys;
    
    // Scene引用
    private Scene scene;
    
    // 控制器引用
    private Element2_3Controller element2_3Controller;
    private Element1Controller element1Controller;

    // 在ShortcutManager.java中添加：
    private GpuView gpuView;


    private CameraMainController cameraMainController;


    public void setCameraMainController(CameraMainController controller) {
        this.cameraMainController = controller;
    }

    public void setGpuView(GpuView gpuView) {
        this.gpuView = gpuView;
    }
    
    public ShortcutManager() {
        this.config = new ShortcutConfig();
        this.pressedKeys = new HashSet<>();

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/event_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            recordLogger = new PrintWriter(new FileWriter(logFile, true));


        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查指定按键是否被按下（不区分大小写）
     * 在JavaFX中，字母键的KeyCode本身就不区分大小写
     * 例如：无论按下q还是Q，都是KeyCode.Q
     */
    private boolean isKeyPressed(KeyCode targetKey) {
        return pressedKeys.contains(targetKey);
    }
    
    /**
     * 初始化快捷键管理器并绑定到Scene
     */
    public void initialize(Scene scene) {
        this.scene = scene;
        setupGlobalKeyListeners();
        setupGlobalScrollListener();
    }
    
    /**
     * 绑定到Scene（与initialize方法功能相同，提供兼容性）
     */
    public void bindToScene(Scene scene) {
        initialize(scene);
    }
    
    /**
     * 设置Element2_3Controller引用，用于访问行列下拉列表
     */
    public void setElement2_3Controller(Element2_3Controller controller) {
        this.element2_3Controller = controller;
    }
    
    /**
     * 设置Element1Controller引用，用于检测鼠标位置
     */
    public void setElement1Controller(Element1Controller controller) {
        this.element1Controller = controller;
    }
    
    /**
     * 设置全局键盘事件监听器
     */
    private void setupGlobalKeyListeners() {
        if (scene == null) return;
        
        // 键盘按下事件
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            pressedKeys.add(event.getCode());
        });
        
        // 键盘释放事件
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            pressedKeys.remove(event.getCode());
        });
    }
    
    /**
     * 设置全局鼠标滚轮事件监听器
     */
    private void setupGlobalScrollListener() {
        if (scene == null) return;
        
       /* scene.addEventFilter(ScrollEvent.SCROLL, event -> {
            // 检查是否按下了快捷键（不区分大小写）

            logRecord("join");
            boolean isRowAdjustPressed = pressedKeys.contains(config.getRowAdjustKey());
            boolean isColAdjustPressed = pressedKeys.contains(config.getColAdjustKey());
            
            if (isRowAdjustPressed || isColAdjustPressed) {
                // 组合快捷键优先级最高，无论鼠标在哪里都消费事件并执行
                event.consume();
                
                // 处理快捷键+滚轮事件
                if (isRowAdjustPressed) {
                    handleRowAdjust(event.getDeltaY() > 0);
                } else if (isColAdjustPressed) {
                    handleColAdjust(event.getDeltaY() > 0);
                }
            }
        });*/

        // 在ShortcutManager.java的setupGlobalKeyListeners()方法中添加：
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            pressedKeys.add(event.getCode());

            if(event.getCode()==config.getRowAdjustKey()){  //行增加
                handleRowAdjust(true);
                event.consume(); // 消费事件，防止其他处理
            }

            if(event.getCode()==config.getRowSubAdjustKey()){//行减少
                handleRowAdjust(false);
                event.consume(); // 消费事件，防止其他处理
            }

            if(event.getCode()==config.getColAdjustKey()){//列增加
                handleColAdjust(true);
                event.consume(); // 消费事件，防止其他处理
            }
            if(event.getCode()==config.getColSubAdjustKey()){//列减少
                handleColAdjust(false);
                event.consume(); // 消费事件，防止其他处理
            }

            // ⭐ 旋转快捷键已禁用
            // if (config.isRotationKey(event.getCode())) {
            //     int rotationIndex = config.getRotationIndex(event.getCode());
            //     if (rotationIndex >= 0) {
            //         handleRotationShortcut(rotationIndex);
            //         event.consume();
            //     }
            // }
            // 在键盘事件处理方法中添加（通常在 handleKeyPressed 方法中）
            if (event.getCode() == config.getCameraSwitchKey()) {
                if (gpuView != null) {
                    gpuView.onShortcutSwitchCamera();
                }
                event.consume();
            }

            // ⭐ 画质快捷键已禁用（NUMPAD1-4 改为滚轮帧数）
            // if (gpuView != null && config.isQualityKey(event.getCode())) {
            //     int qualityIndex = config.getQualityIndex(event.getCode());
            //     if (qualityIndex != -1) {
            //         gpuView.setQualityByShortcut(qualityIndex);
            //         event.consume();
            //     }
            // }

            if(gpuView != null && event.getCode() == config.getSettingsKey()){
                gpuView.openCameraSettings();
            }

            if(config.isSlowMotionKey(event.getCode())){ // 开始慢放
                if(element2_3Controller!=null){
                    logRecord("慢放element2_3Controller === yes");
                    element2_3Controller.onStartSlow();
                    event.consume(); // 消费事件，防止冲突
                }else{
                    logRecord("慢放element2_3Controller === null");
                }
            }
            if(config.isCaptureKey(event.getCode())){ // 抓拍
                if(element2_3Controller!=null){
                    try {
                        element2_3Controller.onCapture();
                        event.consume(); // 消费事件，防止冲突
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            if(config.isClearKey(event.getCode())){ // 清空
                if(element2_3Controller!=null){
                    element2_3Controller.onClear();
                    event.consume(); // 消费事件，防止冲突
                }
            }



            if(config.isCaptureClearKey(event.getCode())){ // 抓拍清空
                if(element2_3Controller!=null){
                    try {
                        element2_3Controller.onCaptureClear();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    event.consume(); // 消费事件，防止冲突
                }
            }


            if(config.isFullscreenKey(event.getCode())){ // 全屏
                if(cameraMainController != null){
                    cameraMainController.handleFullScreen();
                    event.consume(); // 消费事件，防止冲突
                }
            }

            if(config.isRealtimeWindowKey(event.getCode())){ // 实时窗口切换
                if(cameraMainController != null){
                    cameraMainController.handleRealTimeWindow();
                    event.consume(); // 消费事件，防止冲突
                }
            }

            if(config.isSlowmoWindowKey(event.getCode())){ // 慢放窗口切换
                if(cameraMainController != null){
                    cameraMainController.handleSlowMotionWindow();
                    event.consume(); // 消费事件，防止冲突
                }
            }
            //
            if(config.isDeleteLastKey(event.getCode())){ //delete

                FileToos.FbDeleteItemEvent();
                event.consume(); // 消费事件，防止冲突
            }

            // ✅ 主键盘数字键设置滚轮帧数 (QWE上方 0-9)
            if (cameraMainController != null) {
                if (cameraMainController.handleNumpadKeyForScrollFrame(event.getCode())) {
                    event.consume(); // 消费事件，防止冲突
                }
            }

        });
    }
    private PrintWriter recordLogger;
    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }
    
    /**
     * 检查鼠标是否在Element1区域内
     */
    private boolean isMouseOverElement1(ScrollEvent event) {
        if (element1Controller == null) return false;
        
        try {
            // 获取Element1的根节点
            Node element1Root = element1Controller.getRoot();
            if (element1Root == null) return false;
            
            // 检查事件源是否在Element1内部
            Node eventSource = (Node) event.getSource();
            while (eventSource != null) {
                if (eventSource == element1Root) {
                    return true;
                }
                eventSource = eventSource.getParent();
            }
            
            return false;
        } catch (Exception e) {
            // 如果检测失败，默认不在Element1内
            return false;
        }
    }
    
    /**
     * 处理行数调整
     */
    private void handleRowAdjust(boolean increase) {
        if (element2_3Controller == null) return;
        
        Platform.runLater(() -> {
            try {
                int currentRows = GridStore.getInstance().getRows();
                int newRows;
                
                if (increase) {
                    newRows = Math.min(10, currentRows + 1); // 最大10行
                } else {
                    newRows = Math.max(1, currentRows - 1);  // 最小1行
                }
                
                if (newRows != currentRows) {
                    // 更新GridStore
                    GridStore.getInstance().setRows(newRows);
                    
                    // 更新UI下拉列表
                    updateRowCombo(newRows);
                    
                    System.out.println("快捷键调整行数: " + currentRows + " -> " + newRows);
                }
            } catch (Exception e) {
                System.err.println("行数调整失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 处理列数调整
     */
    private void handleColAdjust(boolean increase) {
        if (element2_3Controller == null) return;
        
        Platform.runLater(() -> {
            try {
                int currentCols = GridStore.getInstance().getCols();
                int newCols;
                
                if (increase) {
                    newCols = Math.min(10, currentCols + 1); // 最大10列
                } else {
                    newCols = Math.max(1, currentCols - 1);  // 最小1列
                }
                
                if (newCols != currentCols) {
                    // 更新GridStore
                    GridStore.getInstance().setCols(newCols);
                    
                    // 更新UI下拉列表
                    updateColCombo(newCols);
                    
                    System.out.println("快捷键调整列数: " + currentCols + " -> " + newCols);
                }
            } catch (Exception e) {
                System.err.println("列数调整失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 更新行数下拉列表
     */
    private void updateRowCombo(int newRows) {
        try {
            javafx.scene.control.ComboBox<Integer> rowCombo = element2_3Controller.getRowCombo();
            if (rowCombo != null) {
                rowCombo.setValue(newRows);
            }
        } catch (Exception e) {
            System.err.println("更新行数下拉列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新列数下拉列表
     */
    private void updateColCombo(int newCols) {
        try {
            javafx.scene.control.ComboBox<Integer> colCombo = element2_3Controller.getColCombo();
            if (colCombo != null) {
                colCombo.setValue(newCols);
            }
        } catch (Exception e) {
            System.err.println("更新列数下拉列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取当前快捷键配置
     */
    public ShortcutConfig getShortcutConfig() {
        return config;
    }
    
    /**
     * 获取当前按下的键
     */
    public Set<KeyCode> getPressedKeys() {
        return new HashSet<>(pressedKeys);
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {


        pressedKeys.clear();
        scene = null;
        element2_3Controller = null;
        element1Controller = null;
        gpuView = null;

    }




    // 添加旋转快捷键处理方法
    private void handleRotationShortcut(int rotationIndex) {
        Platform.runLater(() -> {
            try {
                // 设置FileToos.usederection值
                FileToos.usederection = rotationIndex;

                // 如果有GpuView实例，触发旋转并更新按钮文字
                // 这里需要添加GpuView的引用
                if (gpuView != null) {
                    gpuView.applyRotationFromShortcut(rotationIndex);
                }
                System.out.println("快捷键设置画面旋转: " + rotationIndex + " (" +
                        FileToos.getVideoDirectionText(rotationIndex) + ")");
            } catch (Exception e) {
                System.err.println("旋转快捷键处理失败: " + e.getMessage());
            }
        });
    }


}