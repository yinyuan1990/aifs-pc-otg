package com.acard.acard.ui;


import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;

public class NewCameraSettingsDialogController implements Initializable {

    // FXML 组件
    @FXML private HBox titleBar;
    @FXML private Button closeButton;
    
    // 摄像头方向
    @FXML private RadioButton backCameraRadio;
    @FXML private RadioButton frontCameraRadio;
    private ToggleGroup cameraDirectionGroup;
    
    // 分辨率
    @FXML private ComboBox<String> clarityCombo;
    
    // 曝光补偿
    @FXML private Button exposureMinusBtn;
    @FXML private Slider exposureSlider;
    @FXML private Button exposurePlusBtn;
    @FXML private Button exposureResetBtn;
    @FXML private Label exposureValue;
    
    // 焦距
    @FXML private Button focusMinusBtn;
    @FXML private Slider focusSlider;
    @FXML private Button focusPlusBtn;
    @FXML private Button focusResetBtn;
    @FXML private Label focusValue;
    
    // 帧率
    @FXML private Button fpsMinusBtn;
    @FXML private Slider fpsSlider;
    @FXML private Button fpsPlusBtn;
    @FXML private Button fpsResetBtn;
    @FXML private Label fpsValue;
    
    // 码率
    @FXML private Button bitrateMinusBtn;
    @FXML private Slider bitrateSlider;
    @FXML private Button bitratePlusBtn;
    @FXML private Button bitrateResetBtn;
    @FXML private Label bitrateValue;
    
    // 对焦距离
    @FXML private Button focusDistanceMinusBtn;
    @FXML private Slider focusDistanceSlider;
    @FXML private Button focusDistancePlusBtn;
    @FXML private Button focusDistanceResetBtn;
    @FXML private Label focusDistanceValue;
    
    // 亮度
    @FXML private Button brightnessMinusBtn;
    @FXML private Slider brightnessSlider;
    @FXML private Button brightnessPlusBtn;
    @FXML private Button brightnessResetBtn;
    @FXML private Label brightnessValue;
    
    // 饱和度
    @FXML private Button saturationMinusBtn;
    @FXML private Slider saturationSlider;
    @FXML private Button saturationPlusBtn;
    @FXML private Button saturationResetBtn;
    @FXML private Label saturationValue;
    
    // 对比度
    @FXML private Button contrastMinusBtn;
    @FXML private Slider contrastSlider;
    @FXML private Button contrastPlusBtn;
    @FXML private Button contrastResetBtn;
    @FXML private Label contrastValue;
    
    // 底部按钮
    @FXML private Button resetAllButton;
    @FXML private Button okButton;
    @FXML private Button cancelButton;
    
    // 默认值常量
    private static final double DEFAULT_EXPOSURE = 0.0;
    private static final double DEFAULT_FOCUS = 1.0;
    private static final double DEFAULT_FPS = 30.0;
    private static final double DEFAULT_BITRATE = 50.0;
    private static final double DEFAULT_FOCUS_DISTANCE = 0.5;
    private static final double DEFAULT_BRIGHTNESS = 0.0;
    private static final double DEFAULT_SATURATION = 1.0;
    private static final double DEFAULT_CONTRAST = 1.0;
    
    // 步长常量
    private static final double EXPOSURE_STEP = 0.1;
    private static final double FOCUS_STEP = 0.1;
    private static final double FPS_STEP = 1.0;
    private static final double BITRATE_STEP = 1.0;
    private static final double FOCUS_DISTANCE_STEP = 0.05;
    private static final double BRIGHTNESS_STEP = 0.05;
    private static final double SATURATION_STEP = 0.05;
    private static final double CONTRAST_STEP = 0.1;
    
    private Stage dialogStage;
    private boolean okClicked = false;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        setupCameraDirectionGroup();
        setupClarityCombo();
        setupSliders();
        setupButtons();
        updateAllValueLabels();
    }
    
    private void setupCameraDirectionGroup() {
        cameraDirectionGroup = new ToggleGroup();
        backCameraRadio.setToggleGroup(cameraDirectionGroup);
        frontCameraRadio.setToggleGroup(cameraDirectionGroup);
        backCameraRadio.setSelected(true);
    }
    
    private void setupClarityCombo() {
        clarityCombo.getItems().addAll(
            "1920x1080",
            "1280x720",
            "640x480",
            "320x240"
        );
        clarityCombo.setValue("1920x1080");
    }
    
    private void setupSliders() {
        // 设置滑动条监听器
        exposureSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            exposureValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
        
        focusSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            focusValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
        
        fpsSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            fpsValue.setText(String.valueOf(newVal.intValue()));
        });
        
        bitrateSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            bitrateValue.setText(String.valueOf(newVal.intValue()));
        });
        
        focusDistanceSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            focusDistanceValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
        
        brightnessSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            brightnessValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
        
        saturationSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            saturationValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
        
        contrastSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            contrastValue.setText(String.format("%.2f", newVal.doubleValue()));
        });
    }
    
    private void setupButtons() {
        // 曝光补偿按钮
        exposureMinusBtn.setOnAction(e -> adjustSlider(exposureSlider, -EXPOSURE_STEP));
        exposurePlusBtn.setOnAction(e -> adjustSlider(exposureSlider, EXPOSURE_STEP));
        exposureResetBtn.setOnAction(e -> resetSlider(exposureSlider, DEFAULT_EXPOSURE));
        
        // 焦距按钮
        focusMinusBtn.setOnAction(e -> adjustSlider(focusSlider, -FOCUS_STEP));
        focusPlusBtn.setOnAction(e -> adjustSlider(focusSlider, FOCUS_STEP));
        focusResetBtn.setOnAction(e -> resetSlider(focusSlider, DEFAULT_FOCUS));
        
        // 帧率按钮
        fpsMinusBtn.setOnAction(e -> adjustSlider(fpsSlider, -FPS_STEP));
        fpsPlusBtn.setOnAction(e -> adjustSlider(fpsSlider, FPS_STEP));
        fpsResetBtn.setOnAction(e -> resetSlider(fpsSlider, DEFAULT_FPS));
        
        // 码率按钮
        bitrateMinusBtn.setOnAction(e -> adjustSlider(bitrateSlider, -BITRATE_STEP));
        bitratePlusBtn.setOnAction(e -> adjustSlider(bitrateSlider, BITRATE_STEP));
        bitrateResetBtn.setOnAction(e -> resetSlider(bitrateSlider, DEFAULT_BITRATE));
        
        // 对焦距离按钮
        focusDistanceMinusBtn.setOnAction(e -> adjustSlider(focusDistanceSlider, -FOCUS_DISTANCE_STEP));
        focusDistancePlusBtn.setOnAction(e -> adjustSlider(focusDistanceSlider, FOCUS_DISTANCE_STEP));
        focusDistanceResetBtn.setOnAction(e -> resetSlider(focusDistanceSlider, DEFAULT_FOCUS_DISTANCE));
        
        // 亮度按钮
        brightnessMinusBtn.setOnAction(e -> adjustSlider(brightnessSlider, -BRIGHTNESS_STEP));
        brightnessPlusBtn.setOnAction(e -> adjustSlider(brightnessSlider, BRIGHTNESS_STEP));
        brightnessResetBtn.setOnAction(e -> resetSlider(brightnessSlider, DEFAULT_BRIGHTNESS));
        
        // 饱和度按钮
        saturationMinusBtn.setOnAction(e -> adjustSlider(saturationSlider, -SATURATION_STEP));
        saturationPlusBtn.setOnAction(e -> adjustSlider(saturationSlider, SATURATION_STEP));
        saturationResetBtn.setOnAction(e -> resetSlider(saturationSlider, DEFAULT_SATURATION));
        
        // 对比度按钮
        contrastMinusBtn.setOnAction(e -> adjustSlider(contrastSlider, -CONTRAST_STEP));
        contrastPlusBtn.setOnAction(e -> adjustSlider(contrastSlider, CONTRAST_STEP));
        contrastResetBtn.setOnAction(e -> resetSlider(contrastSlider, DEFAULT_CONTRAST));
    }
    
    private void adjustSlider(Slider slider, double step) {
        double newValue = slider.getValue() + step;
        newValue = Math.max(slider.getMin(), Math.min(slider.getMax(), newValue));
        slider.setValue(newValue);
    }
    
    private void resetSlider(Slider slider, double defaultValue) {
        slider.setValue(defaultValue);
    }
    
    private void updateAllValueLabels() {
        exposureValue.setText(String.format("%.2f", exposureSlider.getValue()));
        focusValue.setText(String.format("%.2f", focusSlider.getValue()));
        fpsValue.setText(String.valueOf((int) fpsSlider.getValue()));
        bitrateValue.setText(String.valueOf((int) bitrateSlider.getValue()));
        focusDistanceValue.setText(String.format("%.2f", focusDistanceSlider.getValue()));
        brightnessValue.setText(String.format("%.2f", brightnessSlider.getValue()));
        saturationValue.setText(String.format("%.2f", saturationSlider.getValue()));
        contrastValue.setText(String.format("%.2f", contrastSlider.getValue()));
    }
    
    @FXML
    private void handleClose() {
        dialogStage.close();
    }
    
    @FXML
    private void handleResetAll() {
        // 重置所有滑动条到默认值
        exposureSlider.setValue(DEFAULT_EXPOSURE);
        focusSlider.setValue(DEFAULT_FOCUS);
        fpsSlider.setValue(DEFAULT_FPS);
        bitrateSlider.setValue(DEFAULT_BITRATE);
        focusDistanceSlider.setValue(DEFAULT_FOCUS_DISTANCE);
        brightnessSlider.setValue(DEFAULT_BRIGHTNESS);
        saturationSlider.setValue(DEFAULT_SATURATION);
        contrastSlider.setValue(DEFAULT_CONTRAST);
        
        // 重置其他控件
        backCameraRadio.setSelected(true);
        clarityCombo.setValue("1920x1080");
    }
    
    @FXML
    private void handleOk() {
        okClicked = true;
        applySettings();
        dialogStage.close();
    }
    
    @FXML
    private void handleCancel() {
        dialogStage.close();
    }
    
    private void applySettings() {
        // 应用曝光设置
        pushExposureUpdate(exposureSlider.getValue());
        // 应用焦距设置
        pushZoomUpdate(focusSlider.getValue());
        // 应用其他设置
        pushCameraParameterUpdate("focus_distance", focusDistanceSlider.getValue());
        pushCameraParameterUpdate("brightness", brightnessSlider.getValue());
        pushCameraParameterUpdate("saturation", saturationSlider.getValue());
        pushCameraParameterUpdate("contrast", contrastSlider.getValue());
        pushCameraParameterUpdate("fps", (int) fpsSlider.getValue());
        pushCameraParameterUpdate("bitrate", (int) bitrateSlider.getValue());
    }
    
    // API 接口方法 - 参考原有的实现
    private void pushExposureUpdate(double exposureValue) {

    }
    
    private void pushZoomUpdate(double zoomValue) {

    }
    
    private void pushCameraParameterUpdate(String parameter, double value) {

    }
    
    // 设置对话框舞台
    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }
    
    // 获取是否点击了确定按钮
    public boolean isOkClicked() {
        return okClicked;
    }
    
    // 获取当前设置值的方法
    public double getExposureValue() {
        return exposureSlider.getValue();
    }
    
    public double getFocusValue() {
        return focusSlider.getValue();
    }
    
    public int getFpsValue() {
        return (int) fpsSlider.getValue();
    }
    
    public int getBitrateValue() {
        return (int) bitrateSlider.getValue();
    }
    
    public double getFocusDistanceValue() {
        return focusDistanceSlider.getValue();
    }
    
    public double getBrightnessValue() {
        return brightnessSlider.getValue();
    }
    
    public double getSaturationValue() {
        return saturationSlider.getValue();
    }
    
    public double getContrastValue() {
        return contrastSlider.getValue();
    }
    
    public String getClarityValue() {
        return clarityCombo.getValue();
    }
    
    public boolean isBackCamera() {
        return backCameraRadio.isSelected();
    }
}