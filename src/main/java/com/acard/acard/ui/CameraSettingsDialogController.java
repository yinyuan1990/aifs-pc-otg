package com.acard.acard.ui;

import com.acard.acard.SimpleWebRTCPlayer;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.net.StompWebSocketClient;
import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Popup;

import java.net.URL;
import java.util.ResourceBundle;
import java.util.Arrays;

import com.acard.acard.storage.AuthStore;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.ThinConfigResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.acard.acard.storage.ConfigStore;
import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.util.CameraSettingsStorage;

import static com.acard.acard.SimpleWebRTCPlayer.*;

public class CameraSettingsDialogController implements Initializable {

    // ⭐ 静态变量：跟踪当前打开的 Popup 实例（改为 Popup）
    private static Popup currentPopup = null;

    @FXML private HBox titleBar;
    @FXML private Slider exposureSlider;
    @FXML private Label exposureValue;
    @FXML private Slider focusSlider;
    @FXML private Label focusValue;
    @FXML private ComboBox<String> clarityCombo;
    @FXML private Slider fpsSlider;
    @FXML private Label fpsValue;


    private Slider bitrateSlider;
    private Label bitrateValueLabel;




    @FXML private ComboBox<Integer> angleCombo;
    @FXML private Button okButton;
    @FXML private Button cancelButton;
    // 删除按钮移除
    // private Button deleteButton;
    
    // ✅ 新增：对焦距离、亮度、饱和度、对比度控件
    @FXML private Slider focusDistanceSlider;
    @FXML private Label focusDistanceValue;
    @FXML private Slider brightnessSlider;
    @FXML private Label brightnessValue;
    @FXML private Slider saturationSlider;
    @FXML private Label saturationValue;
    @FXML private Slider contrastSlider;
    @FXML private Label contrastValue;

    // ✅ 图像闪烁 相关控件（0-100，步数1）
    private Slider cjfpsSlider;
    private Label cjfpsValue;

    private Stage stage;
    private Popup popup;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean updatingUI = false;
    
    // ✅ 事件管理器
    private com.acard.acard.events.UIUpdateEventManager eventManager;
    private String listenerId;


    private CheckBox frontCameraCheckBox;  // 前置摄像头复选框


    private Slider hueSlider;
    private Label hueValue;
    private Slider gammaSlider;
    private Label gammaValue;
    private Label exposureMappingDebugLabel;  // 曝光映射调试显示



    public void attachToStage(Stage stage) {
        this.stage = stage;
        // ❌ 已移除：订阅全局配置更新事件
        // 原因：用户操作滑块时，WebSocket推送的配置会覆盖用户操作，导致滑块跳回
        // 现在：只推送到服务器，不接收WebSocket反馈更新UI
        /*
        ConfigStore.getInstance().addThinConfigListener(cfg -> {
            updatingUI = true;
            try {
                applyThinConfigDefaults();
            } finally {
                updatingUI = false;
            }
        });
        */
        // 可拖动：使用顶部 titleBar 实现拖动
        titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
    }

    // 新增：Popup 版本的拖动支持
    private void attachToPopup(Popup popup) {
        this.popup = popup;
        // ❌ 已移除：订阅全局配置更新事件
        // 原因：用户操作滑块时，WebSocket推送的配置会覆盖用户操作，导致滑块跳回
        // 现在：只推送到服务器，不接收WebSocket反馈更新UI
        /*
        ConfigStore.getInstance().addThinConfigListener(cfg -> {
            updatingUI = true;
            try {
                applyThinConfigDefaults();
            } finally {
                updatingUI = false;
            }
        });
        */
        if (titleBar != null) {
            titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
            });
            titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
                // 使用屏幕坐标移动弹窗位置
                popup.setX(e.getScreenX() - dragOffsetX);
                popup.setY(e.getScreenY() - dragOffsetY);
            });
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupControls();
    }

    private void setupControls() {
        if (exposureSlider != null && exposureValue != null) {
            exposureSlider.setMin(0);
            exposureSlider.setMax(100);  // 范围 0-100
            exposureSlider.setValue(20);  // 默认20，对应slider=0.2


            // 设置刻度
            exposureSlider.setMajorTickUnit(1);         // 步数1
            exposureSlider.setMinorTickCount(0);
            exposureSlider.setShowTickLabels(false);
            exposureSlider.setShowTickMarks(false);
            exposureSlider.setBlockIncrement(1);        // 键盘/滚轮每次调节 1
            exposureSlider.setSnapToTicks(false);
            // ✅ 立即执行，无延迟
            // 监听值变化
            exposureSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                int percent = newVal.intValue();
                exposureSlider.setValue(percent);
                // 更新标签显示
                exposureValue.setText("曝光: " + percent + "%");
                
                // ✅ 只有非UI更新时才推送和联动
                if (!updatingUI) {
                    // 只推送曝光更新，不推送联动参数（曝光会自动触发后端计算）
                    pushExposureUpdate(percent);
                    // 联动更新5个参数的UI显示和值（会同时推送到播放器）
                    updateLinkedParamsFromExposure(percent);
                }
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(exposureSlider, 1);
        }
        if (focusSlider != null && focusValue != null) {
            focusSlider.setMin(1.0);
            focusSlider.setMax(3.0);
            // ✅ 立即执行，无延迟
            focusSlider.valueProperty().addListener((obs, oldV, newV) -> {
                double v = roundToStep(newV.doubleValue(), 0.1, 1, 3.0);
                focusSlider.setValue(v);
                focusValue.setText(String.format("%.2f", v));
                if (updatingUI) return;
                pushZoomUpdate(v);  // 立即执行
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(focusSlider, 0.1);
        }
        if (clarityCombo != null) {
            // ✅ 默认加载后置摄像头的4档清晰度配置
            updateClarityOptions(true);  // true=后置
            
            // 监听选择变化，推送清晰度类型
            clarityCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
                if (updatingUI) return;
                if (sel != null) {
                    // ✅ 检查是否收到过 CONFIG_STATE 消息
                    if (CameraMainController.lastConfigStateTime == 0) {
                        System.err.println("⚠️ 尚未收到 CONFIG_STATE 消息，无法切换画质");
                        // 恢复之前的选择
                        if (old != null) {
                            updatingUI = true;
                            clarityCombo.getSelectionModel().select(old);
                            updatingUI = false;
                        }
                        return;
                    }
                    
                    // ✅ 获取选择的画质名称（用于权限检查）
                    String qualityName = null;
                    String type = null;
                    if (sel.startsWith("4K")) {
                        type = "p4k";
                        qualityName = "4K";
                    } else if (sel.startsWith("超清")) {
                        type = "ultra";
                        qualityName = "超清";
                    } else if (sel.startsWith("高清")) {
                        type = "high";
                        qualityName = "高清";
                    } else if (sel.startsWith("标清")) {
                        type = "standard";
                        qualityName = "标清";
                    }
                    
                    // ✅ 检查画质权限
                    if (qualityName != null && !isQualityAccessible(qualityName)) {
                        System.err.println("⚠️ 当前会员等级不支持该画质: " + qualityName);
                        // 恢复之前的选择
                        if (old != null) {
                            updatingUI = true;
                            clarityCombo.getSelectionModel().select(old);
                            updatingUI = false;
                        }
                        showQualityAccessDeniedTip(qualityName);
                        return;
                    }
                    
                    if (type != null) {
                        pushTypeUpdate(type);
                    }
                }
            });
        }
        // 码率（kbps）——离散取值，使用下拉选择更准确

        if (bitrateSlider != null) {
            // 码率滑动条值变化监听
            bitrateSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
                if (updatingUI) return;

                // 将滑动条值转换为实际码率值（步进1）
                int bitrateValue = (int) Math.round(newVal.doubleValue()) ;
                bitrateValue = Math.max(0, Math.min(100, bitrateValue)); // 限制范围10-100

                // 更新显示文本
                bitrateValueLabel.setText(bitrateValue+"");

                // 推送更新
                pushBitrateUpdate(bitrateValue);
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(bitrateSlider, 1);
        }

        // 相机旋转角度
        if (angleCombo != null) {
            angleCombo.getItems().setAll(Arrays.asList(0, 90, 180, 270));
            angleCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, v) -> {
                if (updatingUI) return;
                if (v != null) pushAngleUpdate(v);
            });
        }

        if (fpsSlider != null && fpsValue != null) {
            fpsSlider.setMin(0);   // ⭐ 从0开始，0实际发送1
            fpsSlider.setMax(120);
            // ✅ 拖动时只更新 UI 显示，不推送
            fpsSlider.valueProperty().addListener((obs, oldV, newV) -> {
                double v = roundToStep(newV.doubleValue(), 1.0, 0.0, 120.0);
                if (Math.abs(fpsSlider.getValue() - v) > 0.01) {
                    fpsSlider.setValue(v);
                }
                fpsValue.setText(String.format("%.0f", v));
            });
            // ⭐ 松开鼠标后才推送更新（避免死循环）
            fpsSlider.setOnMouseReleased(e -> {
                if (updatingUI) return;
                int fps = (int) Math.round(fpsSlider.getValue());
                LogTools.getInstance().logRecord6("🔧 [FPS滑块] 松开鼠标，推送 fps=" + fps);
                pushFpsUpdate(fps);
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(fpsSlider, 1);
        }
        
        // ✅ 镜头清晰度对焦 (0.0 ~ 1.0)
        if (focusDistanceSlider != null && focusDistanceValue != null) {
            focusDistanceSlider.setMin(0.0);
            focusDistanceSlider.setMax(1.0);
            // ✅ 立即执行，无延迟
            focusDistanceSlider.valueProperty().addListener((obs, oldV, newV) -> {
                double v = roundToStep(newV.doubleValue(), 0.01, 0.0, 1.0);
                focusDistanceSlider.setValue(v);
                focusDistanceValue.setText(String.format("%.2f", v));
                if (updatingUI) return;
                pushFocusDistanceUpdate((float) v);  // 立即执行
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(focusDistanceSlider, 0.01);
        }
        
        // ✅ 亮度 (显示 -40 ~ 20，实际 -0.4 ~ 0.2)
        if (brightnessSlider != null && brightnessValue != null) {
            brightnessSlider.setMin(-40);  // 显示范围 *100
            brightnessSlider.setMax(20);
            brightnessSlider.setBlockIncrement(1);
            // ✅ 立即执行，无延迟
            brightnessSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int displayV = (int) Math.round(newV.doubleValue());
                brightnessSlider.setValue(displayV);
                brightnessValue.setText(String.valueOf(displayV));  // 显示整数
                if (updatingUI) return;
                pushBrightnessUpdate((float) (displayV / 100.0));  // 实际值 /100
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(brightnessSlider, 1);
        }
        
        // ✅ 饱和度 (显示 0 ~ 200，实际 0.0 ~ 2.0)
        if (saturationSlider != null && saturationValue != null) {
            saturationSlider.setMin(0);  // 显示范围 *100
            saturationSlider.setMax(200);
            saturationSlider.setBlockIncrement(1);
            // ✅ 立即执行，无延迟
            saturationSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int displayV = (int) Math.round(newV.doubleValue());
                saturationSlider.setValue(displayV);
                saturationValue.setText(String.valueOf(displayV));  // 显示整数
                if (updatingUI) return;
                pushSaturationUpdate((float) (displayV / 100.0));  // 实际值 /100
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(saturationSlider, 1);
        }
        
        // ✅ 对比度 (显示 0 ~ 400，实际 0.0 ~ 4.0)
        if (contrastSlider != null && contrastValue != null) {
            contrastSlider.setMin(0);  // 显示范围 *100
            contrastSlider.setMax(400);
            contrastSlider.setBlockIncrement(1);
            // ✅ 立即执行，无延迟
            contrastSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int displayV = (int) Math.round(newV.doubleValue());
                contrastSlider.setValue(displayV);
                contrastValue.setText(String.valueOf(displayV));  // 显示整数
                if (updatingUI) return;
                pushContrastUpdate((float) (displayV / 100.0));  // 实际值 /100
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(contrastSlider, 1);
        }


        // 前置摄像头复选框事件监听
        if (frontCameraCheckBox != null) {
            frontCameraCheckBox.selectedProperty().addListener((obs, oldV, newV) -> {
                if (updatingUI) return;
                // 更新清晰度选项（true=前置，false=后置）
                updateClarityOptions(!newV);  // 注意取反：勾选=前置，传入false
                pushDirectionUpdate(newV ? 1 : -1);  // 1=前置，-1=后置
            });
        }


        // ✅ 色调 (显示 -100 ~ 100，实际 -1.0 ~ 1.0)
        if (hueSlider != null && hueValue != null) {
            hueSlider.setMin(-100);  // 显示范围 *100
            hueSlider.setMax(100);
            hueSlider.setBlockIncrement(1);
            // ✅ 立即执行，无延迟
            hueSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int displayV = (int) Math.round(newV.doubleValue());
                hueSlider.setValue(displayV);
                hueValue.setText(String.valueOf(displayV));  // 显示整数
                if (updatingUI) return;
                pushHueUpdate((float) (displayV / 100.0));  // 实际值 /100
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(hueSlider, 1);
        }

        // ✅ 伽马 (显示 50 ~ 200，实际 0.5 ~ 2.0)
        if (gammaSlider != null && gammaValue != null) {
            gammaSlider.setMin(50);  // 显示范围 *100
            gammaSlider.setMax(200);
            gammaSlider.setBlockIncrement(1);
            // ✅ 立即执行，无延迟
            gammaSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int displayV = (int) Math.round(newV.doubleValue());
                gammaSlider.setValue(displayV);
                gammaValue.setText(String.valueOf(displayV));  // 显示整数
                if (updatingUI) return;
                pushGammaUpdate((float) (displayV / 100.0));  // 实际值 /100
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(gammaSlider, 1);
        }
        
        // ✅ 图像闪烁 (0~100) - 步数1
        if (cjfpsSlider != null && cjfpsValue != null) {
            cjfpsSlider.setMin(0);
            cjfpsSlider.setMax(100);
            // ✅ 立即执行，无延迟
            cjfpsSlider.valueProperty().addListener((obs, oldV, newV) -> {
                int v = newV.intValue();  // 步数1
                v = Math.max(0, Math.min(100, v));  // 限制范围
                cjfpsSlider.setValue(v);
                cjfpsValue.setText(String.valueOf(v));
                if (updatingUI) return;
                pushCjfpsUpdate(v);  // 立即执行
            });
            // ✅ 添加鼠标滚轮滚动支持
            addScrollSupport(cjfpsSlider, 1);
        }

        // 读取并应用 ThinRemoteConfig 的真实值
        applyThinConfigDefaults();
        
        // ✅ 初始化事件监听器
        initializeEventListeners();
    }
    
    /**
     * ✅ 初始化事件监听器
     */
    private void initializeEventListeners() {
        eventManager = com.acard.acard.events.UIUpdateEventManager.getInstance();
        this.listenerId = "CameraSettings_" + System.currentTimeMillis();
        registerUIUpdateEvents();
    }
    
    /**
     * ✅ 注册 UI 更新事件
     */
    private void registerUIUpdateEvents() {
        if (eventManager != null) {
            // 注册 CameraSettingsDialog 事件监听器
            eventManager.registerListener(UIUpdateEvent.EventType.CameraSettingsDialogEvent,
                    this::handleCameraSettingsEvent, listenerId + "_camera");
            System.out.println("CameraSettingsDialogController: CameraSettingsDialog 事件监听器已注册");
        }
    }
    
    /**
     * ✅ 注销 UI 更新事件
     */
    private void unregisterUIUpdateEvents() {
        if (eventManager != null) {
            eventManager.unregisterListener(UIUpdateEvent.EventType.CameraSettingsDialogEvent, listenerId + "_camera");
            System.out.println("CameraSettingsDialogController: CameraSettingsDialog 事件监听器已注销");
        }
    }
    
    /**
     * ✅ 处理 CameraSettingsDialog 事件
     * 当相机配置更新时，调用 applyThinConfigEvent() 刷新 UI（只更新数值，不触发回调）
     */
    private void handleCameraSettingsEvent(UIUpdateEvent event) {
        javafx.application.Platform.runLater(() -> {
            try {
                System.out.println("📡 CameraSettingsDialogController 收到 CameraSettingsDialog 事件，同步配置状态");
                applyThinConfigEvent();
            } catch (Exception e) {
                System.err.println("❌ CameraSettingsDialogController 处理 CameraSettingsDialog 事件失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private double roundToStep(double value, double step, double min, double max) {
        double clamped = Math.max(min, Math.min(max, value));
        double steps = Math.round(clamped / step);
        return steps * step;
    }
    
    /**
     * ✅ 为滑块添加鼠标滚轮滚动支持
     * @param slider 滑块控件
     * @param step 滚动步进值
     */
    private void addScrollSupport(Slider slider, double step) {
        if (slider == null) return;
        slider.setOnScroll(event -> {
            double delta = event.getDeltaY();
            double currentValue = slider.getValue();
            double newValue;
            if (delta > 0) {
                // 滚轮向上，增加值
                newValue = currentValue + step;
            } else {
                // 滚轮向下，减少值
                newValue = currentValue - step;
            }
            // 限制在滑块范围内
            newValue = Math.max(slider.getMin(), Math.min(slider.getMax(), newValue));
            slider.setValue(newValue);
            event.consume();
        });
    }

    /**
     * ✅ 仅同步配置状态，不触发回调
     * 用于处理来自后端的配置更新事件（CameraSettingsDialog）
     */
    private void applyThinConfigEvent() {
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        // ✅ 关键：设置 updatingUI = true，防止触发网络回调
        updatingUI = true;
        try {


            // 镜头变倍（0.5~3.0，步进0.01）——此处用 zoom 近似映射
            try {
                Double zoom = cfg.getZoom();
                if(zoom.doubleValue()<1){
                    cfg.setZoom(1.0);
                }
                zoom = cfg.getZoom();
                if (zoom != null && focusSlider != null && focusValue != null) {
                    double v = roundToStep(zoom.doubleValue(), 0.1, 1, 3.0);
                    focusSlider.setValue(v);
                    focusValue.setText(String.format("%.2f", v));
                }
            } catch (Throwable ignore) {}


            // 前置摄像头状态初始化
            try {
                String directionStr = cfg.getDirection();
                boolean isFront = false;
                if (directionStr != null) {
                    try {
                        int dir = Integer.parseInt(directionStr);
                        isFront = (dir == 1);  // 1=前置，-1=后置
                    } catch (NumberFormatException e) {
                        // 如果是字符串类型
                        isFront = "front".equalsIgnoreCase(directionStr) || "1".equals(directionStr);
                    }
                }
                if (frontCameraCheckBox != null) {
                    frontCameraCheckBox.setSelected(isFront);
                }
            } catch (Throwable ignore) {}



            // 清晰度（p4k/ultra/high/standard -> 4K/超清/高清/标清）
            // ✅ 先根据摄像头方向更新分辨率选项（-1=后置，1=前置）
            try {
                String directionStr = cfg.getDirection();
                int dir = Integer.parseInt(directionStr);  // 默认后置（-1）
                boolean isBack = (dir == -1);  // -1=后置，1=前置

                // ✅ 先更新选项列表（根据前后摄像头）
                if (clarityCombo != null) {
                    clarityCombo.getItems().clear();
                    if (isBack) {
                        // 后置摄像头
                        clarityCombo.getItems().addAll(
                                "4K (1920x1080 120fps 5000kbps)",
                                "超清 (1920x1080 120fps 4500kbps)",
                                "高清 (1280x720 120fps 3200kbps)",
                                "标清 (1024x768 60fps 1000kbps)"
                        );
                    } else {
                        // 前置摄像头
                        clarityCombo.getItems().addAll(
                                "4K (1920x1080 120fps 3500kbps)",
                                "超清 (1280x720 120fps 3200kbps)",
                                "高清 (1024x768 60fps 2200kbps)",
                                "标清 (1024x768 60fps 800kbps)"
                        );
                    }

                    // ✅ 然后根据type选择对应的档位
                    String type = cfg.getType();
                    if (type != null) {
                        for (String item : clarityCombo.getItems()) {
                            if ((type.equalsIgnoreCase("p4k") && item.startsWith("4K")) ||
                                    (type.equalsIgnoreCase("ultra") && item.startsWith("超清")) ||
                                    (type.equalsIgnoreCase("high") && item.startsWith("高清")) ||
                                    (type.equalsIgnoreCase("standard") && item.startsWith("标清"))) {
                                clarityCombo.getSelectionModel().select(item);
                                System.out.println("✅ 初始化清晰度选中: " + item + " (type=" + type + ", isBack=" + isBack + ")");
                                break;
                            }
                        }
                    } else {
                        // 默认选中第一项
                        clarityCombo.getSelectionModel().selectFirst();
                        System.out.println("⚠️ type为空，默认选中第一项");
                    }
                }
            } catch (Throwable e) {
                System.err.println("❌ 初始化清晰度选项失败: " + e.getMessage());
                e.printStackTrace();
            }

            // 帧率（0~120，步进1，0实际为1）
            try {
                Integer fps = cfg.getFps();
                if (fps != null && fps >= 0 && fpsSlider != null && fpsValue != null) {
                    double v = roundToStep(fps.doubleValue(), 1.0, 0.0, 120.0);
                    fpsSlider.setValue(v);
                    fpsValue.setText(String.format("%.0f", v));
                }
            } catch (Throwable ignore) {}
            
            // ✅ 图像闪烁（0~100，步进1）
            try {
                Integer cjfps = cfg.getCjfps();
                if (cjfps != null && cjfpsSlider != null && cjfpsValue != null) {
                    int v = Math.max(0, Math.min(100, cjfps));
                    cjfpsSlider.setValue(v);
                    cjfpsValue.setText(String.valueOf(v));
                }
            } catch (Throwable ignore) {}

            // 码率滑动条初始化
            if (bitrateSlider != null) {

                Integer bitrate = cfg.getBitrate();
                if (bitrate != null) {
                    // 确保值在范围内且是5的倍数
                    int clampedBitrate = Math.max(0, Math.min(100, bitrate));
                    clampedBitrate = (int) Math.round(clampedBitrate) ;
                    bitrateSlider.setValue(clampedBitrate);
                    bitrateValueLabel.setText(clampedBitrate + "");
                } else {
                    // 默认值50
                    bitrateSlider.setValue(50);
                    bitrateValueLabel.setText("50");
                }
            }

            // ✅ 镜头清晰度对焦（0.0~1.0，步进0.01）
            try {
                Float focusDistance = cfg.getFocus();
                if (focusDistance != null && focusDistanceSlider != null && focusDistanceValue != null) {
                    double v = roundToStep(focusDistance.doubleValue(), 0.01, 0.0, 1.0);
                    focusDistanceSlider.setValue(v);
                    focusDistanceValue.setText(String.format("%.2f", v));
                }
            } catch (Throwable ignore) {}

        } finally {
            updatingUI = false;
        }
    }
    private void applyThinConfigDefaults() {
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        updatingUI = true;
        try {
            // 曝光补偿（-2~2，步进0.1）
            try {
                exposureSlider.setValue(100);
                exposureValue.setText("曝光: " + 100 + "%");
            } catch (Throwable ignore) {}

            // 镜头变倍（0.5~3.0，步进0.01）——此处用 zoom 近似映射
            try {
                Double zoom = cfg.getZoom();
                if(zoom.doubleValue()<1){
                    cfg.setZoom(1.0);
                }
                zoom = cfg.getZoom();
                if (zoom != null && focusSlider != null && focusValue != null) {
                    double v = roundToStep(zoom.doubleValue(), 0.1, 1, 3.0);
                    focusSlider.setValue(v);
                    focusValue.setText(String.format("%.2f", v));
                }
            } catch (Throwable ignore) {}


            // 前置摄像头状态初始化
            try {
                String directionStr = cfg.getDirection();
                boolean isFront = false;
                if (directionStr != null) {
                    try {
                        int dir = Integer.parseInt(directionStr);
                        isFront = (dir == 1);  // 1=前置，-1=后置
                    } catch (NumberFormatException e) {
                        // 如果是字符串类型
                        isFront = "front".equalsIgnoreCase(directionStr) || "1".equals(directionStr);
                    }
                }
                if (frontCameraCheckBox != null) {
                    frontCameraCheckBox.setSelected(isFront);
                }
            } catch (Throwable ignore) {}



            // 清晰度（p4k/ultra/high/standard -> 4K/超清/高清/标清）
            // ✅ 先根据摄像头方向更新分辨率选项（-1=后置，1=前置）
            try {
                String directionStr = cfg.getDirection();
                int dir = Integer.parseInt(directionStr);  // 默认后置（-1）
                boolean isBack = (dir == -1);  // -1=后置，1=前置
                
                // ✅ 先更新选项列表（根据前后摄像头）
                if (clarityCombo != null) {
                    clarityCombo.getItems().clear();
                    if (isBack) {
                        // 后置摄像头
                        clarityCombo.getItems().addAll(
                            "4K (1920x1080 120fps 5000kbps)",
                            "超清 (1920x1080 120fps 4500kbps)",
                            "高清 (1280x720 120fps 3200kbps)",
                            "标清 (1024x768 60fps 1000kbps)"
                        );
                    } else {
                        // 前置摄像头
                        clarityCombo.getItems().addAll(
                            "4K (1920x1080 120fps 3500kbps)",
                            "超清 (1280x720 120fps 3200kbps)",
                            "高清 (1024x768 60fps 2200kbps)",
                            "标清 (1024x768 60fps 800kbps)"
                        );
                    }
                    
                    // ✅ 然后根据type选择对应的档位
                    String type = cfg.getType();
                    if (type != null) {
                        for (String item : clarityCombo.getItems()) {
                            if ((type.equalsIgnoreCase("p4k") && item.startsWith("4K")) ||
                                (type.equalsIgnoreCase("ultra") && item.startsWith("超清")) ||
                                (type.equalsIgnoreCase("high") && item.startsWith("高清")) ||
                                (type.equalsIgnoreCase("standard") && item.startsWith("标清"))) {
                                clarityCombo.getSelectionModel().select(item);
                                System.out.println("✅ 初始化清晰度选中: " + item + " (type=" + type + ", isBack=" + isBack + ")");
                                break;
                            }
                        }
                    } else {
                        // 默认选中第一项
                        clarityCombo.getSelectionModel().selectFirst();
                        System.out.println("⚠️ type为空，默认选中第一项");
                    }
                }
            } catch (Throwable e) {
                System.err.println("❌ 初始化清晰度选项失败: " + e.getMessage());
                e.printStackTrace();
            }

            // 帧率（0~120，步进1，0实际为1）
            try {
                Integer fps = cfg.getFps();
                if (fps != null && fps >= 0 && fpsSlider != null && fpsValue != null) {
                    double v = roundToStep(fps.doubleValue(), 1.0, 0.0, 120.0);
                    fpsSlider.setValue(v);
                    fpsValue.setText(String.format("%.0f", v));
                }
            } catch (Throwable ignore) {}
            
            // ✅ 图像闪烁（0~100，步进1）
            try {
                Integer cjfps = cfg.getCjfps();
                if (cjfps != null && cjfpsSlider != null && cjfpsValue != null) {
                    int v = Math.max(0, Math.min(100, cjfps));
                    cjfpsSlider.setValue(v);
                    cjfpsValue.setText(String.valueOf(v));
                }
            } catch (Throwable ignore) {}

            // 码率滑动条初始化
            if (bitrateSlider != null) {

                    Integer bitrate = cfg.getBitrate();
                    if (bitrate != null) {
                        // 确保值在范围内且是5的倍数
                        int clampedBitrate = Math.max(0, Math.min(100, bitrate));
                        clampedBitrate = (int) Math.round(clampedBitrate) ;
                        bitrateSlider.setValue(clampedBitrate);
                        bitrateValueLabel.setText(clampedBitrate + "");
                    } else {
                        // 默认值50
                        bitrateSlider.setValue(50);
                        bitrateValueLabel.setText("50");
                    }
            }

            // 相机旋转角度
            try {
                int ang = cfg.getAngle();
                if (angleCombo != null) {
                    if (!angleCombo.getItems().contains(ang)) {
                        angleCombo.getItems().add(ang);
                    }
                    angleCombo.getSelectionModel().select(Integer.valueOf(ang));
                }
            } catch (Throwable ignore) {}
            
            // ✅ 镜头清晰度对焦（0.0~1.0，步进0.01）
            try {
                Float focusDistance = cfg.getFocus();
                if (focusDistance != null && focusDistanceSlider != null && focusDistanceValue != null) {
                    double v = roundToStep(focusDistance.doubleValue(), 0.01, 0.0, 1.0);
                    focusDistanceSlider.setValue(v);
                    focusDistanceValue.setText(String.format("%.2f", v));
                }
            } catch (Throwable ignore) {}
            
            // ✅ 亮度（-1.0~1.0，步进0.01）
            try {
                int displayBrightness = (int)(DEFAULT_BRIGHTNESS * 100);
                brightnessSlider.setValue(displayBrightness);
                brightnessValue.setText(String.valueOf(displayBrightness));
            } catch (Throwable ignore) {}
            
            // ✅ 饱和度（显示 0~200）
            try {
                int displaySaturation = (int)(DEFAULT_SATURATION * 100);
                saturationSlider.setValue(displaySaturation);
                saturationValue.setText(String.valueOf(displaySaturation));
            } catch (Throwable ignore) {}
            
            // ✅ 对比度（显示 0~400）
            try {
                int displayContrast = (int)(DEFAULT_CONTRAST * 100);
                contrastSlider.setValue(displayContrast);
                contrastValue.setText(String.valueOf(displayContrast));
            } catch (Throwable ignore) {}

            // ✅ 色调（显示 -100~100）
            try {
                int displayHue = (int)(DEFAULT_HUE * 100);
                hueSlider.setValue(displayHue);
                hueValue.setText(String.valueOf(displayHue));
            } catch (Throwable ignore) {}

            // ✅ 伽马（显示 50~200）
            try {
                int displayGamma = (int)(DEFAULT_GAMMA * 100);
                gammaSlider.setValue(displayGamma);
                gammaValue.setText(String.valueOf(displayGamma));
            } catch (Throwable ignore) {}


        } finally {
            updatingUI = false;
        }
    }

    // 辅助方法：以模式窗口形式显示（FXML路径）。如果 FXML 缺失，将回退到 Popup 构建。
    public static void showDialog(Stage owner) throws Exception {
        javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                CameraSettingsDialogController.class.getResource("/com/acard/acard/ui/CameraSettingsDialog.fxml"));
        CameraSettingsDialogController controller = new CameraSettingsDialogController();
        loader.setController(controller);
        javafx.scene.Parent root;
        try {
            root = loader.load();
        } catch (Exception ex) {
            // FXML 加载失败，退回到 Popup 构建
            controller.buildDialogProgrammatically(owner);
            return;
        }

        Stage dialog = new Stage(StageStyle.UNDECORATED);
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setScene(new Scene(root));
        controller.attachToStage(dialog);
        dialog.showAndWait();
    }

    // 提供无FXML的直接显示方法（改为 Popup，点击外部自动关闭）
    // ⭐ 支持切换：如果已打开则关闭，如果未打开则打开
    public static void showDialogWithoutFXML(Stage owner) {
        // ✅ 如果 Popup 已经打开，则关闭它
        if (currentPopup != null && currentPopup.isShowing()) {
            currentPopup.hide();
            currentPopup = null;
            return;
        }
        
        // 否则，打开新的 Popup
        CameraSettingsDialogController controller = new CameraSettingsDialogController();
        controller.buildDialogProgrammatically(owner);
    }
    
    /**
     * ✅ 获取当前打开的相机设定对话框的 Stage（用于其他对话框设置 owner）
     * @return 如果对话框已打开，返回其 Stage；否则返回 null
     */
    /**
     * ⭐ Popup 版本不需要 getCurrentDialogStage 方法
     * 因为 Popup 不会干扰 Alert 的显示层级
     */
    public static Stage getCurrentDialogStage() {
        // Popup 版本始终返回 null
        return null;
    }

    private void buildDialogProgrammatically(Stage owner) {
        // ✅ 顶部标题栏（深色主题，与快捷键弹框风格一致）
        HBox topBar = new HBox();
        topBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        topBar.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-padding: 16 20; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        
        Label title = new Label("📷 相机设定");
        title.setStyle(
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 18px; " +
            "-fx-font-weight: bold;"
        );
        
        // ✅ 关闭按钮（深色风格，与快捷键弹框一致）
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        );
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
            "-fx-background-color: #DC2626; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8; " +
            "-fx-background-radius: 4;"
        ));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        ));
        
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        topBar.getChildren().addAll(title, spacer, closeBtn);
        this.titleBar = topBar;

        // ✅ 中心区域（深色主题）
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(38); grid.setPadding(new javafx.geometry.Insets(50, 24, 50, 24));
        grid.setStyle("-fx-background-color: #1F1F1F;");
        javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints();
        c1.setHalignment(javafx.geometry.HPos.RIGHT); c1.setMinWidth(140);
        javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints();
        c2.setHalignment(javafx.geometry.HPos.LEFT); c2.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.layout.ColumnConstraints c3 = new javafx.scene.layout.ColumnConstraints();
        c3.setHalignment(javafx.geometry.HPos.LEFT); c3.setMinWidth(80);
        grid.getColumnConstraints().addAll(c1, c2, c3);

        // ✅ 深色主题的标签样式
        String labelStyle = "-fx-text-fill: #CCCCCC; -fx-font-size: 13px;";
        String valueStyle = "-fx-text-fill: #FFFFFF; -fx-font-size: 13px;";
        // ⭐ 重要说明文字样式（对焦、图像闪烁、帧率、清晰度的说明）- 加大2px并加粗，青色突出
        String importantTipStyle = "-fx-text-fill: #65DCE8; -fx-font-size: 12px; -fx-font-weight: bold;";
        String btnStyle = "-fx-background-color: #404040; -fx-text-fill: #FFFFFF; -fx-font-size: 12px; -fx-background-radius: 4; -fx-cursor: hand;";
        String btnHoverStyle = "-fx-background-color: #505050; -fx-text-fill: #FFFFFF; -fx-font-size: 12px; -fx-background-radius: 4; -fx-cursor: hand;";
        String resetBtnStyle = "-fx-background-color: #404040; -fx-text-fill: #FFFFFF; -fx-font-size: 12px; -fx-background-radius: 4; -fx-cursor: hand;";

        // ✅ 第0行：网速控制精调说明（跨3列，左对齐）
        Label networkTipLabel = new Label("网速控制精调 实现超低网速运行");
        networkTipLabel.setStyle("-fx-text-fill: #CCCCCC; -fx-font-size: 13px; -fx-font-weight: bold;");
        networkTipLabel.setMaxWidth(Double.MAX_VALUE);  // 让标签占满整个宽度
        networkTipLabel.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        grid.add(networkTipLabel, 0, 0, 3, 1);  // 跨3列
        GridPane.setHalignment(networkTipLabel, javafx.geometry.HPos.LEFT);  // 强制左对齐

        // ✅ 第1行：对焦 (0.0 ~ 1.0) + 说明（合并为一行，内部使用VBox紧凑布局）
        Label focusDistanceLabel = new Label("对焦 (0.0~1.0)");
        focusDistanceLabel.setStyle(labelStyle);
        
        Button focusMinusBtn = new Button("-");
        focusMinusBtn.setPrefWidth(30);
        focusMinusBtn.setStyle(btnStyle);
        focusMinusBtn.setOnMouseEntered(e -> focusMinusBtn.setStyle(btnHoverStyle));
        focusMinusBtn.setOnMouseExited(e -> focusMinusBtn.setStyle(btnStyle));
        this.focusDistanceSlider = new Slider(0.0, 1.0, 0.5);
        this.focusDistanceSlider.setShowTickLabels(false); 
        this.focusDistanceSlider.setShowTickMarks(false);
        Button focusPlusBtn = new Button("+");
        focusPlusBtn.setPrefWidth(30);
        focusPlusBtn.setStyle(btnStyle);
        focusPlusBtn.setOnMouseEntered(e -> focusPlusBtn.setStyle(btnHoverStyle));
        focusPlusBtn.setOnMouseExited(e -> focusPlusBtn.setStyle(btnStyle));
        this.focusDistanceValue = new Label("0.50");
        this.focusDistanceValue.setStyle(valueStyle);

        Button focusResetBtn = new Button("复位");
        focusResetBtn.setPrefWidth(40);
        focusResetBtn.setStyle(resetBtnStyle);
        
        HBox focusControlBox = new HBox(5, focusMinusBtn, focusDistanceSlider, focusPlusBtn, focusResetBtn);
        focusControlBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(focusDistanceSlider, javafx.scene.layout.Priority.ALWAYS);
        
        // 对焦说明
        Label focusTipLabel = new Label("（调节画面清晰度 0为近景清晰 1为远景清晰 0.6为默认值）");
        focusTipLabel.setStyle(importantTipStyle);  // ⭐ 加大加粗
        
        // 使用VBox将控件和说明紧凑排列
        javafx.scene.layout.VBox focusBox = new javafx.scene.layout.VBox(2);  // 间距2px
        focusBox.getChildren().addAll(focusControlBox, focusTipLabel);

        grid.add(focusDistanceLabel, 0, 1);
        grid.add(focusBox, 1, 1);
        grid.add(focusDistanceValue, 2, 1);

        // ❌ 已移除：镜头变倍（用户要求）
        // ❌ 已移除：分辨率（用户要求）

        // 第2行：曝光补偿 (0-100，步数1)
        Label exposureLabel = new Label("曝光补偿 (0~100)");
        exposureLabel.setStyle(labelStyle);
        this.exposureSlider = new Slider(0, 100, 20);  // 默认20，对应slider=0.2
        this.exposureSlider.setShowTickLabels(false); 
        this.exposureSlider.setShowTickMarks(false);
        this.exposureSlider.setMajorTickUnit(1);
        this.exposureSlider.setBlockIncrement(1);  // 步数1
        this.exposureValue = new Label("20");
        this.exposureValue.setStyle(valueStyle);
        grid.add(exposureLabel, 0, 2); grid.add(exposureSlider, 1, 2); grid.add(exposureValue, 2, 2);

        // 第3行：图像闪烁 (0~100) + 说明（合并为一行，内部使用VBox紧凑布局）
        Label cjfpsLabel = new Label("图像闪烁 (0~100)");
        cjfpsLabel.setStyle(labelStyle);
        this.cjfpsSlider = new Slider(0, 100, 100);  // 默认100
        this.cjfpsSlider.setShowTickLabels(false);
        this.cjfpsSlider.setShowTickMarks(false);
        this.cjfpsSlider.setMajorTickUnit(1);
        this.cjfpsSlider.setMinorTickCount(0);
        this.cjfpsSlider.setBlockIncrement(1);  // 步数1
        this.cjfpsValue = new Label("100");
        this.cjfpsValue.setStyle(valueStyle);
        
        // 图像闪烁说明
        Label cjfpsTipLabel = new Label("（数值越高图像拖影越小闪烁越大 数值越低图像拖影越大闪烁越低）");
        cjfpsTipLabel.setStyle(importantTipStyle);  // ⭐ 加大加粗
        
        // 使用VBox将滑块和说明紧凑排列
        javafx.scene.layout.VBox cjfpsSliderBox = new javafx.scene.layout.VBox(2);  // 间距2px
        cjfpsSliderBox.getChildren().addAll(cjfpsSlider, cjfpsTipLabel);
        
        grid.add(cjfpsLabel, 0, 3); 
        grid.add(cjfpsSliderBox, 1, 3); 
        grid.add(cjfpsValue, 2, 3);

        // 第4行：帧率 + 说明（合并为一行，内部使用VBox紧凑布局）
        Label fpsLabel = new Label("帧率 (0~120)");
        fpsLabel.setStyle(labelStyle);
        this.fpsSlider = new Slider(0, 120, 30);  // ⭐ 范围改为0-120，0实际为1
        this.fpsSlider.setShowTickLabels(false); this.fpsSlider.setShowTickMarks(false);
        this.fpsValue = new Label("30");
        this.fpsValue.setStyle(valueStyle);
        
        // 帧率说明
        Label fpsTipLabel = new Label("（帧率越高对网络要求越高 建议60 网速越差建议下调可实现稳定）");
        fpsTipLabel.setStyle(importantTipStyle);  // ⭐ 加大加粗
        
        // 使用VBox将滑块和说明紧凑排列
        javafx.scene.layout.VBox fpsSliderBox = new javafx.scene.layout.VBox(2);  // 间距2px
        fpsSliderBox.getChildren().addAll(fpsSlider, fpsTipLabel);
        
        grid.add(fpsLabel, 0, 4); 
        grid.add(fpsSliderBox, 1, 4); 
        grid.add(fpsValue, 2, 4);

        // 第5行：清晰度 + 说明（合并为一行，内部使用VBox紧凑布局）
        Label bitrateLabel = new Label("清晰度");
        bitrateLabel.setStyle(labelStyle);
        this.bitrateSlider = new Slider(0, 100, 50);  // 范围10-100，默认值50
        this.bitrateSlider.setShowTickLabels(false);
        this.bitrateSlider.setShowTickMarks(false);
        this.bitrateSlider.setMajorTickUnit(10);
        this.bitrateSlider.setBlockIncrement(1);  // 步进1
        this.bitrateValueLabel = new Label("50");
        this.bitrateValueLabel.setStyle(valueStyle);
        
        // 清晰度说明
        Label bitrateTipLabel = new Label("（清晰度越高对网速要求越高 建议50 远距离使用最佳）");
        bitrateTipLabel.setStyle(importantTipStyle);  // ⭐ 加大加粗
        
        // 使用VBox将滑块和说明紧凑排列
        javafx.scene.layout.VBox bitrateSliderBox = new javafx.scene.layout.VBox(2);  // 间距2px
        bitrateSliderBox.getChildren().addAll(bitrateSlider, bitrateTipLabel);

        grid.add(bitrateLabel, 0, 5);
        grid.add(bitrateSliderBox, 1, 5);
        grid.add(bitrateValueLabel, 2, 5);



        
        // 按钮事件：每次增减0.01
        focusMinusBtn.setOnAction(e -> {
            double newVal = roundToStep(focusDistanceSlider.getValue() - 0.01, 0.01, 0.0, 1.0);
            focusDistanceSlider.setValue(newVal);
        });
        focusPlusBtn.setOnAction(e -> {
            double newVal = roundToStep(focusDistanceSlider.getValue() + 0.01, 0.01, 0.0, 1.0);
            focusDistanceSlider.setValue(newVal);
        });

        // 新增复位按钮事件
        focusResetBtn.setOnAction(e -> {
            focusDistanceSlider.setValue(0.6);  // 恢复默认值0.6
        });
        
        // ✅ 颜色参数精调说明（在亮度上方，左对齐，间距10px）
        Label colorTipLabel = new Label("（颜色参数精调）");
        colorTipLabel.setStyle("-fx-text-fill: #CCCCCC; -fx-font-size: 12px; -fx-font-weight: bold;");
        colorTipLabel.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        javafx.scene.layout.VBox colorTipBox = new javafx.scene.layout.VBox(10);  // 间距10px
        colorTipBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        colorTipBox.getChildren().add(colorTipLabel);
        grid.add(colorTipBox, 0, 6, 3, 1);  // 跨3列
        
        // ✅ 亮度 (-1.0 ~ 1.0) - 步进0.01
        Label brightnessLabel = new Label("亮度 (-40~20)");
        brightnessLabel.setStyle(labelStyle);
        Button brightnessMinusBtn = new Button("-");
        brightnessMinusBtn.setPrefWidth(30);
        brightnessMinusBtn.setStyle(btnStyle);
        brightnessMinusBtn.setOnMouseEntered(e -> brightnessMinusBtn.setStyle(btnHoverStyle));
        brightnessMinusBtn.setOnMouseExited(e -> brightnessMinusBtn.setStyle(btnStyle));
        this.brightnessSlider = new Slider(-40, 20, (int)(DEFAULT_BRIGHTNESS * 100));  // 显示范围 *100
        this.brightnessSlider.setShowTickLabels(false); 
        this.brightnessSlider.setShowTickMarks(false);
        Button brightnessPlusBtn = new Button("+");
        brightnessPlusBtn.setPrefWidth(30);
        brightnessPlusBtn.setStyle(btnStyle);
        brightnessPlusBtn.setOnMouseEntered(e -> brightnessPlusBtn.setStyle(btnHoverStyle));
        brightnessPlusBtn.setOnMouseExited(e -> brightnessPlusBtn.setStyle(btnStyle));
        this.brightnessValue = new Label(String.format("%.2f", DEFAULT_BRIGHTNESS));
        this.brightnessValue.setStyle(valueStyle);

        // 亮度复位按钮
        Button brightnessResetBtn = new Button("复位");
        brightnessResetBtn.setPrefWidth(40);
        brightnessResetBtn.setStyle(resetBtnStyle);


        HBox brightnessBox = new HBox(5, brightnessMinusBtn, brightnessSlider, brightnessPlusBtn,brightnessResetBtn);
        brightnessBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(brightnessSlider, javafx.scene.layout.Priority.ALWAYS);
        
        grid.add(brightnessLabel, 0, 7);
        grid.add(brightnessBox, 1, 7);
        grid.add(brightnessValue, 2, 7);
        
        // 按钮事件：每次增减1（显示值）
        brightnessMinusBtn.setOnAction(e -> {
            int newVal = (int) brightnessSlider.getValue() - 1;
            brightnessSlider.setValue(Math.max(-40, newVal));
        });
        brightnessPlusBtn.setOnAction(e -> {
            int newVal = (int) brightnessSlider.getValue() + 1;
            brightnessSlider.setValue(Math.min(20, newVal));
        });
        // 新增复位按钮事件
        brightnessResetBtn.setOnAction(e -> {
            brightnessSlider.setValue((int)(DEFAULT_BRIGHTNESS * 100));  // 恢复默认值 *100
        });

        
        // ✅ 饱和度 (0.0 ~ 2.0) - 步进0.01
        Label saturationLabel = new Label("饱和度 (0~200)");
        saturationLabel.setStyle(labelStyle);
        Button saturationMinusBtn = new Button("-");
        saturationMinusBtn.setPrefWidth(30);
        saturationMinusBtn.setStyle(btnStyle);
        saturationMinusBtn.setOnMouseEntered(e -> saturationMinusBtn.setStyle(btnHoverStyle));
        saturationMinusBtn.setOnMouseExited(e -> saturationMinusBtn.setStyle(btnStyle));
        this.saturationSlider = new Slider(0, 200, (int)(DEFAULT_SATURATION * 100));  // 显示范围 *100
        this.saturationSlider.setShowTickLabels(false); 
        this.saturationSlider.setShowTickMarks(false);
        Button saturationPlusBtn = new Button("+");
        saturationPlusBtn.setPrefWidth(30);
        saturationPlusBtn.setStyle(btnStyle);
        saturationPlusBtn.setOnMouseEntered(e -> saturationPlusBtn.setStyle(btnHoverStyle));
        saturationPlusBtn.setOnMouseExited(e -> saturationPlusBtn.setStyle(btnStyle));
        this.saturationValue = new Label(String.format("%.2f", DEFAULT_SATURATION));
        this.saturationValue.setStyle(valueStyle);

        Button saturationResetBtn = new Button("复位");
        saturationResetBtn.setPrefWidth(40);
        saturationResetBtn.setStyle(resetBtnStyle);

        
        HBox saturationBox = new HBox(5, saturationMinusBtn, saturationSlider, saturationPlusBtn,saturationResetBtn);
        saturationBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(saturationSlider, javafx.scene.layout.Priority.ALWAYS);
        
        grid.add(saturationLabel, 0, 8);
        grid.add(saturationBox, 1, 8);
        grid.add(saturationValue, 2, 8);
        
        // 按钮事件：每次增减1（显示值）
        saturationMinusBtn.setOnAction(e -> {
            int newVal = (int) saturationSlider.getValue() - 1;
            saturationSlider.setValue(Math.max(0, newVal));
        });
        saturationPlusBtn.setOnAction(e -> {
            int newVal = (int) saturationSlider.getValue() + 1;
            saturationSlider.setValue(Math.min(200, newVal));
        });
        saturationResetBtn.setOnAction(e -> {
            saturationSlider.setValue((int)(DEFAULT_SATURATION * 100));  // 恢复默认值 *100
        });


        // ✅ 对比度 (0.0 ~ 4.0) - 步进0.01
        Label contrastLabel = new Label("对比度 (0~400)");
        contrastLabel.setStyle(labelStyle);
        Button contrastMinusBtn = new Button("-");
        contrastMinusBtn.setPrefWidth(30);
        contrastMinusBtn.setStyle(btnStyle);
        contrastMinusBtn.setOnMouseEntered(e -> contrastMinusBtn.setStyle(btnHoverStyle));
        contrastMinusBtn.setOnMouseExited(e -> contrastMinusBtn.setStyle(btnStyle));
        this.contrastSlider = new Slider(0, 400, (int)(DEFAULT_CONTRAST * 100));  // 显示范围 *100
        this.contrastSlider.setShowTickLabels(false); 
        this.contrastSlider.setShowTickMarks(false);
        Button contrastPlusBtn = new Button("+");
        contrastPlusBtn.setPrefWidth(30);
        contrastPlusBtn.setStyle(btnStyle);
        contrastPlusBtn.setOnMouseEntered(e -> contrastPlusBtn.setStyle(btnHoverStyle));
        contrastPlusBtn.setOnMouseExited(e -> contrastPlusBtn.setStyle(btnStyle));
        this.contrastValue = new Label(String.format("%.2f", DEFAULT_CONTRAST));
        this.contrastValue.setStyle(valueStyle);

        // 对比度复位按钮
        Button contrastResetBtn = new Button("复位");
        contrastResetBtn.setPrefWidth(40);
        contrastResetBtn.setStyle(resetBtnStyle);

        
        HBox contrastBox = new HBox(5, contrastMinusBtn, contrastSlider, contrastPlusBtn,contrastResetBtn);
        contrastBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(contrastSlider, javafx.scene.layout.Priority.ALWAYS);
        
        grid.add(contrastLabel, 0, 9);
        grid.add(contrastBox, 1, 9);
        grid.add(contrastValue, 2, 9);
        
        // 按钮事件：每次增减0.01
        contrastMinusBtn.setOnAction(e -> {
            int newVal = (int) contrastSlider.getValue() - 1;
            contrastSlider.setValue(Math.max(0, newVal));
        });
        contrastPlusBtn.setOnAction(e -> {
            int newVal = (int) contrastSlider.getValue() + 1;
            contrastSlider.setValue(Math.min(400, newVal));
        });

        contrastResetBtn.setOnAction(e -> {
            contrastSlider.setValue((int)(DEFAULT_CONTRAST * 100));  // 恢复默认值 *100
        });
        
        // ✅ 一键还原按钮 - 恢复4个新参数到默认值
       /* Button resetButton = new Button("一键还原");
        resetButton.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-size: 13; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 8 20;");
        resetButton.setOnAction(e -> resetNewParametersToDefault());
        HBox resetBox = new HBox(resetButton);
        resetBox.setAlignment(javafx.geometry.Pos.CENTER);
        grid.add(resetBox, 0, 9, 3, 1);  // 跨3列居中显示*/

        // 底部按钮区域移除（不要删除按钮）
        // 不创建 bottomBar，弹框仅包含标题和内容

        // ✅ 色调 (-1.0 ~ 1.0) - 步进0.01
        Label hueLabel = new Label("色调 (-100~100)");
        hueLabel.setStyle(labelStyle);
        Button hueMinusBtn = new Button("-");
        hueMinusBtn.setPrefWidth(30);
        hueMinusBtn.setStyle(btnStyle);
        hueMinusBtn.setOnMouseEntered(e -> hueMinusBtn.setStyle(btnHoverStyle));
        hueMinusBtn.setOnMouseExited(e -> hueMinusBtn.setStyle(btnStyle));
        this.hueSlider = new Slider(-100, 100, (int)(DEFAULT_HUE * 100));  // 显示范围 *100
        this.hueSlider.setShowTickLabels(false);
        this.hueSlider.setShowTickMarks(false);
        Button huePlusBtn = new Button("+");
        huePlusBtn.setPrefWidth(30);
        huePlusBtn.setStyle(btnStyle);
        huePlusBtn.setOnMouseEntered(e -> huePlusBtn.setStyle(btnHoverStyle));
        huePlusBtn.setOnMouseExited(e -> huePlusBtn.setStyle(btnStyle));
        this.hueValue = new Label(String.format("%.2f", DEFAULT_HUE));
        this.hueValue.setStyle(valueStyle);

        // 色调复位按钮
        Button hueResetBtn = new Button("复位");
        hueResetBtn.setPrefWidth(40);
        hueResetBtn.setStyle(resetBtnStyle);

        HBox hueBox = new HBox(5, hueMinusBtn, hueSlider, huePlusBtn, hueResetBtn);
        hueBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(hueSlider, javafx.scene.layout.Priority.ALWAYS);

        grid.add(hueLabel, 0, 10);
        grid.add(hueBox, 1, 10);
        grid.add(hueValue, 2, 10);

         // 按钮事件：每次增减0.01
        hueMinusBtn.setOnAction(e -> {
            int newVal = (int) hueSlider.getValue() - 1;
            hueSlider.setValue(Math.max(-100, newVal));
        });
        huePlusBtn.setOnAction(e -> {
            int newVal = (int) hueSlider.getValue() + 1;
            hueSlider.setValue(Math.min(100, newVal));
        });
        hueResetBtn.setOnAction(e -> {
            hueSlider.setValue((int)(DEFAULT_HUE * 100));  // 恢复默认值 *100
        });

        // ✅ 伽马 (0.5 ~ 2.0) - 步进0.01
        Label gammaLabel = new Label("伽马 (50~200)");
        gammaLabel.setStyle(labelStyle);
        Button gammaMinusBtn = new Button("-");
        gammaMinusBtn.setPrefWidth(30);
        gammaMinusBtn.setStyle(btnStyle);
        gammaMinusBtn.setOnMouseEntered(e -> gammaMinusBtn.setStyle(btnHoverStyle));
        gammaMinusBtn.setOnMouseExited(e -> gammaMinusBtn.setStyle(btnStyle));
        this.gammaSlider = new Slider(50, 200, (int)(DEFAULT_GAMMA * 100));  // 显示范围 *100
        this.gammaSlider.setShowTickLabels(false);
        this.gammaSlider.setShowTickMarks(false);
        Button gammaPlusBtn = new Button("+");
        gammaPlusBtn.setPrefWidth(30);
        gammaPlusBtn.setStyle(btnStyle);
        gammaPlusBtn.setOnMouseEntered(e -> gammaPlusBtn.setStyle(btnHoverStyle));
        gammaPlusBtn.setOnMouseExited(e -> gammaPlusBtn.setStyle(btnStyle));
        this.gammaValue = new Label(String.format("%.2f", DEFAULT_GAMMA));
        this.gammaValue.setStyle(valueStyle);

        // 伽马复位按钮
        Button gammaResetBtn = new Button("复位");
        gammaResetBtn.setPrefWidth(40);
        gammaResetBtn.setStyle(resetBtnStyle);

        HBox gammaBox = new HBox(5, gammaMinusBtn, gammaSlider, gammaPlusBtn, gammaResetBtn);
        gammaBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(gammaSlider, javafx.scene.layout.Priority.ALWAYS);

        grid.add(gammaLabel, 0, 11);
        grid.add(gammaBox, 1, 11);
        grid.add(gammaValue, 2, 11);

        // 按钮事件：每次增减0.01
        gammaMinusBtn.setOnAction(e -> {
            int newVal = (int) gammaSlider.getValue() - 1;
            gammaSlider.setValue(Math.max(50, newVal));
        });
        gammaPlusBtn.setOnAction(e -> {
            int newVal = (int) gammaSlider.getValue() + 1;
            gammaSlider.setValue(Math.min(200, newVal));
        });
        gammaResetBtn.setOnAction(e -> {
            gammaSlider.setValue((int)(DEFAULT_GAMMA * 100));  // 恢复默认值 *100
        });

        // ✅ 图像闪烁控件已移动到帧率下方（第4-5行）

        // ✅ 曝光映射调试显示（已隐藏）
        // Label mappingDebugLabel = new Label("曝光映射：等待滑动...");
        // mappingDebugLabel.setStyle("-fx-text-fill: #00FF00; -fx-font-size: 11px; -fx-font-family: 'Consolas', monospace;");
        // mappingDebugLabel.setWrapText(true);
        // mappingDebugLabel.setMaxWidth(500);
        // grid.add(mappingDebugLabel, 0, 12, 3, 1);
        // this.exposureMappingDebugLabel = mappingDebugLabel;
        this.exposureMappingDebugLabel = null;  // 隐藏调试显示

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(grid);
        // ✅ 深色主题整体样式（与快捷键弹框一致）
        root.setStyle(
            "-fx-background-color: #1F1F1F; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #333333; " +
            "-fx-border-width: 1; " +
            "-fx-border-radius: 12; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.3, 0, 8);"
        );
        
        // ✅ 深色主题 CSS 样式（滑块、下拉框等）
        root.getStylesheets().add("data:text/css," +
            // 滑块深色主题
            ".slider .track { -fx-background-color: %23404040; } " +
            ".slider .thumb { -fx-background-color: %23909090; } " +
            ".slider .axis { -fx-tick-label-fill: %23888888; } " +
            // 下拉框深色主题
            ".combo-box { -fx-background-color: %23292929; -fx-border-color: %23404040; -fx-border-radius: 6; -fx-background-radius: 6; } " +
            ".combo-box .list-cell { -fx-background-color: %23292929; -fx-text-fill: %23FFFFFF; } " +
            ".combo-box-popup .list-view { -fx-background-color: %23292929; -fx-border-color: %23404040; } " +
            ".combo-box-popup .list-view .list-cell { -fx-background-color: %23292929; -fx-text-fill: %23FFFFFF; } " +
            ".combo-box-popup .list-view .list-cell:hover { -fx-background-color: %23404040; } " +
            ".combo-box-popup .list-view .list-cell:filled:selected { -fx-background-color: %23505050; } " +
            ".combo-box .arrow-button { -fx-background-color: %23292929; } " +
            ".combo-box .arrow { -fx-background-color: %23888888; } " +
            // 复选框深色主题
            ".check-box { -fx-text-fill: %23CCCCCC; } " +
            ".check-box .box { -fx-background-color: %23292929; -fx-border-color: %23404040; } " +
            ".check-box:selected .mark { -fx-background-color: %23FFFFFF; } " +
            // 滚动条深色主题
            ".scroll-bar { -fx-background-color: %23292929; } " +
            ".scroll-bar .thumb { -fx-background-color: %23555555; } " +
            ".scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-background-color: %23292929; }"
        );

        setupControls();
        
        // ⚡ 从本地存储加载保存的图像设置
        loadStoredCameraSettings();

        // ✅ 改用 Popup，解决弹框层级和快捷键问题（参考 ParameterSettingsDialogController）
        Popup popup = new Popup();
        popup.setAutoHide(true);  // 点击外部自动关闭
        popup.setHideOnEscape(true);  // 按 ESC 关闭
        popup.getContent().add(root);
        
        // ✅ 设置关闭按钮事件（现在 Popup 已创建）
        closeBtn.setOnAction(e -> popup.hide());
        
        // 使对话框可拖动
        final double[] dragOffset = new double[2];
        topBar.setOnMousePressed(mouseEvent -> {
            dragOffset[0] = mouseEvent.getX();
            dragOffset[1] = mouseEvent.getY();
        });
        topBar.setOnMouseDragged(mouseEvent -> {
            popup.setX(mouseEvent.getScreenX() - dragOffset[0]);
            popup.setY(mouseEvent.getScreenY() - dragOffset[1]);
        });
        
        this.popup = popup;
        this.stage = owner;  // 保存 owner 引用

        // ⭐ 相对于主窗口居中显示
        try {
            if (owner != null && owner.isShowing()) {
                // 获取主窗口的位置和大小
                double ownerX = owner.getX();
                double ownerY = owner.getY();
                double ownerWidth = owner.getWidth();
                double ownerHeight = owner.getHeight();

                // 对话框尺寸
                double dialogWidth = 560;
                double dialogHeight = 720;

                // 计算居中位置
                double centerX = ownerX + (ownerWidth - dialogWidth) / 2;
                double centerY = ownerY + (ownerHeight - dialogHeight) / 2;

                popup.setX(centerX);
                popup.setY(centerY);
            } else {
                // 如果 owner 不可用，则相对于屏幕居中
                javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                popup.setX((screenBounds.getWidth() - 560) / 2);
                popup.setY((screenBounds.getHeight() - 720) / 2);
            }
        } catch (Throwable ignore) {}
        
        // ⭐ 保存 Popup 实例到静态变量（用于 toggle 功能）
        currentPopup = popup;
        
        // ⭐ 监听 Popup 关闭事件，清理静态引用和注销事件监听器
        popup.setOnHidden(e -> {
            if (currentPopup == popup) {
                currentPopup = null;
            }
            // ✅ 注销事件监听器
            unregisterUIUpdateEvents();
            
            // ⚡ 保存当前图像设置到本地存储
            saveCurrentCameraSettings();
        });
        
        // ⭐ 监听主窗口最小化/失去焦点，自动关闭弹框
        if (owner != null) {
            owner.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
                if (isIconified && popup.isShowing()) {
                    popup.hide();
                }
            });
            owner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && popup.isShowing()) {
                    Platform.runLater(() -> {
                        if (!owner.isFocused() && popup.isShowing()) {
                            popup.hide();
                        }
                    });
                }
            });
        }
        
        // ✅ 显示 Popup
        popup.show(owner);
    }




    private void pushZoomUpdate(double value) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新镜头变倍：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新镜头变倍：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setZoom(value);
            payload.setPtype("zoom");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);


            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {

                                System.out.println("[CameraSettings] 镜头变倍已更新为 " + value);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新镜头变倍失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新镜头变倍失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新镜头变倍异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新镜头变倍异常: " + e.getMessage());
        }
    }
    /**
     * ✅ 根据前置/后置摄像头更新分辨率选项
     */
    private void updateClarityOptions(boolean isBack) {
        if (clarityCombo == null) return;
        
        // 保存当前选中的档位类型
        String currentSelection = clarityCombo.getSelectionModel().getSelectedItem();
        String currentType = null;
        if (currentSelection != null) {
            if (currentSelection.startsWith("4K")) currentType = "p4k";
            else if (currentSelection.startsWith("超清")) currentType = "ultra";
            else if (currentSelection.startsWith("高清")) currentType = "high";
            else if (currentSelection.startsWith("标清")) currentType = "standard";
        }
        
        // 清空现有选项
        clarityCombo.getItems().clear();
        
        if (isBack) {
            // 🎥 后置摄像头：4档固定
            clarityCombo.getItems().addAll(
                    "4K (1920x1080 120fps 5000kbps)",
                    "超清 (1920x1080 120fps 4500kbps)",
                    "高清 (1280x720 120fps 3200kbps)",
                    "标清 (1024x768 60fps 1000kbps)"
            );
        } else {
            // 📱 前置摄像头：4档固定
            clarityCombo.getItems().addAll(
                    "4K (1920x1080 120fps 3500kbps)",
                    "超清 (1280x720 120fps 3200kbps)",
                    "高清 (1024x768 60fps 2200kbps)",
                    "标清 (1024x768 60fps 800kbps)"
            );
        }
        
        // 尝试恢复之前选中的档位类型
        if (currentType != null) {
            for (String item : clarityCombo.getItems()) {
                if ((currentType.equals("p4k") && item.startsWith("4K")) ||
                    (currentType.equals("ultra") && item.startsWith("超清")) ||
                    (currentType.equals("high") && item.startsWith("高清")) ||
                    (currentType.equals("standard") && item.startsWith("标清"))) {
                    clarityCombo.getSelectionModel().select(item);
                    return;
                }
            }
        }
        
        // 默认选择超清
        clarityCombo.getSelectionModel().select(1);  // 第2项（超清）
    }
    
    private void pushDirectionUpdate(int direction) {
        try {

            LogTools.getInstance().logRecord("pushDirectionUpdate-----> pushDirectionUpdate");
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新相机方向：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新相机方向：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setDirection(String.valueOf(direction));
            payload.setPtype("direction");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);


            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 相机方向已更新为 " + direction);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新相机方向失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新相机方向失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新相机方向异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新相机方向异常: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 检查指定画质是否可用
     * 根据 CONFIG_STATE 中的激活/试用信息判断
     */
    private boolean isQualityAccessible(String quality) {
        // 方式一：优先使用 qualityAccess 数组
        String[] accessList = CameraMainController.qualityAccess;
        if (accessList != null && accessList.length > 0) {
            for (String q : accessList) {
                if (q.equals(quality)) {
                    return true;
                }
            }
            return false;
        }
        
        // 方式二：根据 activationLevel 判断
        boolean isActivated = CameraMainController.activated;
        int level = CameraMainController.activationLevel;
        
        if (isActivated) {
            // 已激活用户，根据等级判断
            switch (level) {
                case 1:  // 白银：只能用标清、高清
                    return "标清".equals(quality) || "高清".equals(quality);
                case 2:  // 黄金：全部可用
                    return true;
                default:
                    return true;
            }
        } else {
            // 未激活用户（试用或无限制）：全部可用
            return true;
        }
    }
    
    /**
     * ✅ 显示画质访问被拒绝的提示
     */
    private void showQualityAccessDeniedTip(String quality) {
        String levelName = CameraMainController.activationLevelName;
        if (levelName == null || levelName.isEmpty()) {
            levelName = "当前";
        }
        String message = levelName + "会员不支持" + quality + "画质，请升级会员";
        System.out.println("💡 " + message);
        // 可以在这里添加 Toast 或 Tooltip 提示
    }
    
    private void pushTypeUpdate(String type) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新清晰度：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新清晰度：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setType(type);
            payload.setPtype("type");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);

            FileToos.FbRESOLUTION_CHANGED();
            LogTools.getInstance().logRecord2("📡 已发送分辨率切换事件");


            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                //ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 清晰度已更新为 " + type);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新清晰度失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新清晰度失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新清晰度异常: " + ex.getMessage());
                        return null;
                    });




        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新清晰度异常: " + e.getMessage());
        }
    }
    private void pushFpsUpdate(int fps) {
        LogTools.getInstance().logRecord6("🔧 [pushFpsUpdate] 开始，fps=" + fps);
        
        // ⭐ 0实际为1（用户设置0时，实际发送1）
        int actualFps = (fps <= 0) ? 1 : fps;
        LogTools.getInstance().logRecord6("🔧 [pushFpsUpdate] actualFps=" + actualFps);
        
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新帧率：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新帧率：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setFps(actualFps);  // ⭐ 使用实际帧率
            payload.setPtype("fps");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);

            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                //ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 帧率已更新为 " + actualFps + (fps == 0 ? " (设置0→实际1)" : ""));
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新帧率失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新帧率失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新帧率异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新帧率异常: " + e.getMessage());
        }
    }
    
    // ✅ 新增：更新图像闪烁 (0~100，步数1)
    private void pushCjfpsUpdate(int cjfps) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新图像闪烁：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新图像闪烁：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setCjfps(cjfps);
            payload.setPtype("cjfps");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);

            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                System.out.println("[CameraSettings] 图像闪烁已更新为 " + cjfps);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新图像闪烁失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新图像闪烁失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新图像闪烁异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新图像闪烁异常: " + e.getMessage());
        }
    }

    // 新增：更新码率（kbps）
    private void pushBitrateUpdate(int bitrate) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新码率：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新码率：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setBitrate(bitrate);
            payload.setPtype("bitrate");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);


            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                               // ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 码率已更新为 " + bitrate + " kbps");
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新码率失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新码率失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新码率异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新码率异常: " + e.getMessage());
        }
    }

    // 新增：更新相机旋转角度
    private void pushAngleUpdate(int angle) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新相机旋转角度：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新相机旋转角度：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setAngle(angle);
            payload.setPtype("angle");
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 相机旋转角度已更新为 " + angle + "°");
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新相机旋转角度失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新相机旋转角度失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新相机旋转角度异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新相机旋转角度异常: " + e.getMessage());
        }
    }
    
    // ✅ 新增：更新镜头清晰度对焦 (focus: 0.0 ~ 1.0)
    private void pushFocusDistanceUpdate(float value) {
        try {
            com.acard.acard.net.LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新镜头清晰度对焦：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新镜头清晰度对焦：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setFocus(value);
            payload.setPtype("focus");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.GpuViewType);
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                               // ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 镜头清晰度对焦已更新为 " + value);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新镜头清晰度对焦失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新镜头清晰度对焦失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新镜头清晰度对焦异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新镜头清晰度对焦异常: " + e.getMessage());
        }
    }


    //曝光补偿
    private void pushExposureUpdate(int  percent) {


        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(EXPOSURE,0);
        cavasData.setPercent(percent);
        FileToos.FbCavasDataEvent(cavasData);

    }
    
    /**
     * ✅ 曝光联动更新：根据曝光值计算并更新亮度、饱和度、对比度、色调、伽马
     * 映射公式（曝光滑块 0-100，转换为 slider = percent/100，范围 0-1）：
     * - 亮度 = 0.3 × slider - 0.1
     * - 饱和度 = max(0.875 × slider + 0.925, 1.0)
     * - 对比度 = max(slider + 0.9, 1.0)
     * - 色调 = max(-0.1125 × slider + 0.0625, -0.05)
     * - 伽马 = max(-0.5875 × slider + 1.0875, 0.5)
     * 
     * 对应关系：滑块=0 → slider=0, 滑块=20 → slider=0.2, 滑块=100 → slider=1.0
     */
    private void updateLinkedParamsFromExposure(int percent) {
        // 曝光滑块值转换：0-100 → 0-1
        double slider = percent / 100.0;  // 0 → 0, 20 → 0.2, 100 → 1.0
        
        // 计算各参数值
        double brightness = 0.3 * slider - 0.1;
        double saturation = Math.max(0.875 * slider + 0.925, 1.0);
        double contrast = Math.max(slider + 0.9, 1.0);
        double hue = Math.max(-0.1125 * slider + 0.0625, -0.05);
        double gamma = Math.max(-0.5875 * slider + 1.0875, 0.5);
        
        // 限制在滑块范围内（实际值）
        brightness = Math.max(-0.4, Math.min(0.2, brightness));  // 亮度滑块范围 -40~20 → 实际 -0.4~0.2
        saturation = Math.max(0.0, Math.min(2.0, saturation));   // 饱和度滑块范围 0~200 → 实际 0~2
        contrast = Math.max(0.0, Math.min(4.0, contrast));       // 对比度滑块范围 0~400 → 实际 0~4
        hue = Math.max(-1.0, Math.min(1.0, hue));                // 色调滑块范围 -100~100 → 实际 -1~1
        gamma = Math.max(0.5, Math.min(2.0, gamma));             // 伽马滑块范围 50~200 → 实际 0.5~2
        
        // 使用 updatingUI 标志防止触发单独滑块的监听器
        updatingUI = true;
        try {
            // 更新亮度（显示值 *100）
            if (brightnessSlider != null && brightnessValue != null) {
                int displayBrightness = (int) Math.round(brightness * 100);
                brightnessSlider.setValue(displayBrightness);
                brightnessValue.setText(String.valueOf(displayBrightness));
                pushBrightnessUpdate((float) brightness);
            }
            
            // 更新饱和度（显示值 *100）
            if (saturationSlider != null && saturationValue != null) {
                int displaySaturation = (int) Math.round(saturation * 100);
                saturationSlider.setValue(displaySaturation);
                saturationValue.setText(String.valueOf(displaySaturation));
                pushSaturationUpdate((float) saturation);
            }
            
            // 更新对比度（显示值 *100）
            if (contrastSlider != null && contrastValue != null) {
                int displayContrast = (int) Math.round(contrast * 100);
                contrastSlider.setValue(displayContrast);
                contrastValue.setText(String.valueOf(displayContrast));
                pushContrastUpdate((float) contrast);
            }
            
            // 更新色调（显示值 *100）
            if (hueSlider != null && hueValue != null) {
                int displayHue = (int) Math.round(hue * 100);
                hueSlider.setValue(displayHue);
                hueValue.setText(String.valueOf(displayHue));
                pushHueUpdate((float) hue);
            }
            
            // 更新伽马（显示值 *100）
            if (gammaSlider != null && gammaValue != null) {
                int displayGamma = (int) Math.round(gamma * 100);
                gammaSlider.setValue(displayGamma);
                gammaValue.setText(String.valueOf(displayGamma));
                pushGammaUpdate((float) gamma);
            }
            
            System.out.println("📷 曝光联动更新: 曝光=" + percent + "% → 亮度=" + (int)(brightness * 100) 
                + ", 饱和度=" + (int)(saturation * 100) 
                + ", 对比度=" + (int)(contrast * 100) 
                + ", 色调=" + (int)(hue * 100) 
                + ", 伽马=" + (int)(gamma * 100));
            
            // ✅ 更新调试显示 Label（验证公式）
            if (exposureMappingDebugLabel != null) {
                String debugText = String.format(
                    "曝光=%d (slider=%.2f) → 亮度=%.4f 饱和度=%.4f 对比度=%.4f 色调=%.4f 伽马=%.4f",
                    percent, slider, brightness, saturation, contrast, hue, gamma
                );
                exposureMappingDebugLabel.setText(debugText);
            }
            
            // ✅ 将曝光联动计算的值保存到本地存储
            CameraSettingsStorage storage = CameraSettingsStorage.getInstance();
            storage.setExposure(percent);
            storage.setBrightness(brightness);
            storage.setContrast(contrast);
            storage.setSaturation(saturation);
            storage.setHue(hue);
            storage.setGamma(gamma);
        } finally {
            updatingUI = false;
        }
    }
    
    // ✅ 新增：更新亮度 (brightness: -1.0 ~ 1.0)
    private void pushBrightnessUpdate(float value) {

        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(BRIGHTNESS,value);
        FileToos.FbCavasDataEvent(cavasData);

    }
    
    // ✅ 新增：更新饱和度 (saturation: 0.0 ~ 2.0)
    private void pushSaturationUpdate(float value) {
        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(SATURATION,value);
        FileToos.FbCavasDataEvent(cavasData);
    }
    
    // ✅ 新增：更新对比度 (contrast: 0.0 ~ 2.0)
    private void pushContrastUpdate(float value) {
        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(CONTRAST,value);
        FileToos.FbCavasDataEvent(cavasData);
    }

    // ✅ 新增：更新色调 (hue: -1.0 ~ 1.0)
    private void pushHueUpdate(float value) {
        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(HUE, value);
        FileToos.FbCavasDataEvent(cavasData);
    }

    // ✅ 新增：更新伽马 (gamma: 0.5 ~ 2.0)
    private void pushGammaUpdate(float value) {
        UIUpdateEvent.CavasData cavasData = new UIUpdateEvent.CavasData(GAMMA, value);
        FileToos.FbCavasDataEvent(cavasData);
    }
    
    /**
     * ✅ 一键还原：将4个新参数恢复到默认值
     * - 镜头清晰度对焦: 0.5
     * - 亮度: 0.0
     * - 饱和度: 1.0
     * - 对比度: 1.0
     */
    private void resetNewParametersToDefault() {
        System.out.println("🔄 开始还原4个新参数到默认值...");
        
        updatingUI = true;  // 防止触发网络请求
        try {
            // 1. 镜头清晰度对焦 -> 0.6
            if (focusDistanceSlider != null && focusDistanceValue != null) {
                focusDistanceSlider.setValue(0.6);
                focusDistanceValue.setText("0.60");
            }
            
            // 2. 亮度 -> 0.0
            if (brightnessSlider != null && brightnessValue != null) {
                brightnessSlider.setValue(0.0);
                brightnessValue.setText("0.00");
            }
            
            // 3. 饱和度 -> 1.0
            if (saturationSlider != null && saturationValue != null) {
                saturationSlider.setValue(1.0);
                saturationValue.setText("1.00");
            }
            
            // 4. 对比度 -> 1.0
            if (contrastSlider != null && contrastValue != null) {
                contrastSlider.setValue(1.0);
                contrastValue.setText("1.00");
            }
            
            System.out.println("✅ UI已恢复到默认值");
        } finally {
            updatingUI = false;
        }
        
        // 立即推送到后端（顺序执行，确保稳定）
        try {
            pushFocusDistanceUpdate(0.5f);
            Thread.sleep(1000);  // 短暂延迟，避免并发冲突
            
            pushBrightnessUpdate(0.0f);
            Thread.sleep(1000);
            
            pushSaturationUpdate(1.0f);
            Thread.sleep(1000);
            
            pushContrastUpdate(1.0f);


            // 5. 色调 -> 0.0
            if (hueSlider != null && hueValue != null) {
                hueSlider.setValue(DEFAULT_HUE);  // 0.0
                hueValue.setText(String.format("%.2f", DEFAULT_HUE));
            }

            // 6. 伽马 -> 1.0
            if (gammaSlider != null && gammaValue != null) {
                gammaSlider.setValue(DEFAULT_GAMMA);  // 1.0
                gammaValue.setText(String.format("%.2f", DEFAULT_GAMMA));
            }
            
            System.out.println("✅ 参数已推送到后端");
        } catch (Exception e) {
            System.err.println("❌ 还原参数推送失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * ⚡ 从本地存储加载保存的图像设置（打开对话框时调用）
     * 注意：只加载UI显示，不推送到播放器（避免画面闪烁）
     */
    private void loadStoredCameraSettings() {
        try {
            CameraSettingsStorage storage = CameraSettingsStorage.getInstance();
            updatingUI = true;  // 阻止所有滑块监听器触发推送
            
            // 曝光补偿
            if (exposureSlider != null && exposureValue != null) {
                int exposure = storage.getExposure();
                exposure = (int) Math.max(exposureSlider.getMin(), Math.min(exposureSlider.getMax(), exposure));
                exposureSlider.setValue(exposure);
                exposureValue.setText("曝光: " + exposure + "%");
                
                // ✅ 更新调试显示（不推送，只显示）
                if (exposureMappingDebugLabel != null) {
                    double slider = exposure / 100.0;
                    double brightness = Math.max(-0.4, Math.min(0.2, 0.3 * slider - 0.1));
                    double saturation = Math.max(1.0, Math.min(2.0, 0.875 * slider + 0.925));
                    double contrast = Math.max(1.0, Math.min(2.0, slider + 0.9));
                    double hue = Math.max(-0.05, Math.min(1.0, -0.1125 * slider + 0.0625));
                    double gamma = Math.max(0.5, Math.min(2.0, -0.5875 * slider + 1.0875));
                    String debugText = String.format(
                        "曝光=%d (slider=%.2f) → 亮度=%.4f 饱和度=%.4f 对比度=%.4f 色调=%.4f 伽马=%.4f",
                        exposure, slider, brightness, saturation, contrast, hue, gamma
                    );
                    exposureMappingDebugLabel.setText(debugText);
                }
            }
            
            // 亮度（存储原值 → 显示值 *100）
            if (brightnessSlider != null && brightnessValue != null) {
                double brightness = storage.getBrightness();  // 原值，如 0.1
                int displayBrightness = (int) Math.round(brightness * 100);  // 显示值，如 10
                displayBrightness = (int) Math.max(brightnessSlider.getMin(), Math.min(brightnessSlider.getMax(), displayBrightness));
                brightnessSlider.setValue(displayBrightness);
                brightnessValue.setText(String.valueOf(displayBrightness));
            }
            
            // 对比度（存储原值 → 显示值 *100）
            if (contrastSlider != null && contrastValue != null) {
                double contrast = storage.getContrast();  // 原值，如 1.5
                int displayContrast = (int) Math.round(contrast * 100);  // 显示值，如 150
                displayContrast = (int) Math.max(contrastSlider.getMin(), Math.min(contrastSlider.getMax(), displayContrast));
                contrastSlider.setValue(displayContrast);
                contrastValue.setText(String.valueOf(displayContrast));
            }
            
            // 饱和度（存储原值 → 显示值 *100）
            if (saturationSlider != null && saturationValue != null) {
                double saturation = storage.getSaturation();  // 原值，如 1.2
                int displaySaturation = (int) Math.round(saturation * 100);  // 显示值，如 120
                displaySaturation = (int) Math.max(saturationSlider.getMin(), Math.min(saturationSlider.getMax(), displaySaturation));
                saturationSlider.setValue(displaySaturation);
                saturationValue.setText(String.valueOf(displaySaturation));
            }
            
            // 色调（存储原值 → 显示值 *100）
            if (hueSlider != null && hueValue != null) {
                double hue = storage.getHue();  // 原值，如 -0.05
                int displayHue = (int) Math.round(hue * 100);  // 显示值，如 -5
                displayHue = (int) Math.max(hueSlider.getMin(), Math.min(hueSlider.getMax(), displayHue));
                hueSlider.setValue(displayHue);
                hueValue.setText(String.valueOf(displayHue));
            }
            
            // 伽马（存储原值 → 显示值 *100）
            if (gammaSlider != null && gammaValue != null) {
                double gamma = storage.getGamma();  // 原值，如 1.0
                int displayGamma = (int) Math.round(gamma * 100);  // 显示值，如 100
                displayGamma = (int) Math.max(gammaSlider.getMin(), Math.min(gammaSlider.getMax(), displayGamma));
                gammaSlider.setValue(displayGamma);
                gammaValue.setText(String.valueOf(displayGamma));
            }
            
            System.out.println("📷 相机设置已从本地存储加载（原值→显示值*100）: " + storage);
        } catch (Exception e) {
            System.err.println("❌ 加载相机设置失败: " + e.getMessage());
        } finally {
            updatingUI = false;
        }
    }
    
    /**
     * ⚡ 保存当前图像设置到本地存储（关闭对话框时调用）
     */
    private void saveCurrentCameraSettings() {
        try {
            // 获取当前滑块的值（显示值），转换为实际值（÷100）
            int exposure = exposureSlider != null ? (int) exposureSlider.getValue() : CameraSettingsStorage.DEFAULT_EXPOSURE;
            double brightness = brightnessSlider != null ? brightnessSlider.getValue() / 100.0 : DEFAULT_BRIGHTNESS;  // 显示值÷100
            double contrast = contrastSlider != null ? contrastSlider.getValue() / 100.0 : DEFAULT_CONTRAST;          // 显示值÷100
            double saturation = saturationSlider != null ? saturationSlider.getValue() / 100.0 : DEFAULT_SATURATION;  // 显示值÷100
            double hue = hueSlider != null ? hueSlider.getValue() / 100.0 : DEFAULT_HUE;                              // 显示值÷100
            double gamma = gammaSlider != null ? gammaSlider.getValue() / 100.0 : DEFAULT_GAMMA;                      // 显示值÷100
            
            // 批量保存到本地存储（存储原值）
            CameraSettingsStorage.getInstance().setAll(exposure, brightness, contrast, saturation, hue, gamma);
            
            // 同时保存到 SimpleWebRTCPlayer 的静态变量（原值）
            SimpleWebRTCPlayer.currentBrightness = brightness;
            SimpleWebRTCPlayer.currentContrast = contrast;
            SimpleWebRTCPlayer.currentSaturation = saturation;
            SimpleWebRTCPlayer.currentHue = hue;
            SimpleWebRTCPlayer.currentGamma = gamma;
            
            System.out.println("💾 相机设置已保存到本地存储（原值）: 亮度=" + brightness + ", 对比度=" + contrast);
        } catch (Exception e) {
            System.err.println("❌ 保存相机设置失败: " + e.getMessage());
        }
    }
}