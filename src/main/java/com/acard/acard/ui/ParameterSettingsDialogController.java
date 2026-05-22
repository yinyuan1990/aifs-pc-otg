package com.acard.acard.ui;

import javafx.animation.PauseTransition;
import javafx.fxml.Initializable;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.net.URL;
import java.util.ResourceBundle;

import com.acard.acard.model.StreamPreset;
import com.acard.acard.model.StreamProfile;
import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.storage.ConfigStore;
import com.acard.acard.storage.SlowmoStore;

/**
 * 主界面菜单栏的“参数设定”弹框。
 * UI风格与相机设定一致，目前仅包含一个属性：慢放帧数。
 */
public class ParameterSettingsDialogController implements Initializable {
    private HBox titleBar;
    private Slider slowmoSlider;
    private Label slowmoValue;
    private Label sizeValue;
    private Label secondsValue;
    
    private Popup popup;
    private Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;

    private PauseTransition persistDebounce;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 无FXML模式下不使用
    }

    /**
     * 以模式窗口形式展示（如果后续添加FXML，可沿用此方法）。
     */
    public static void showDialog(Stage owner) {
        ParameterSettingsDialogController c = new ParameterSettingsDialogController();
        c.buildDialogProgrammatically(owner);
    }

    /**
     * 无FXML的直接显示方法（Popup，点击外部自动关闭）。
     */
    public static void showDialogWithoutFXML(Stage owner) {
        ParameterSettingsDialogController c = new ParameterSettingsDialogController();
        c.buildDialogProgrammatically(owner);
    }

    private void buildDialogProgrammatically(Stage owner) {
        // 顶部标题栏
        HBox topBar = new HBox();
        topBar.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-background-radius: 12 12 0 0;");
        Label title = new Label("参数设定");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;");
        topBar.getChildren().add(title);
        this.titleBar = topBar;

        // 中心区域
        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(12); grid.setPadding(new Insets(12));
        grid.setStyle("-fx-background-color: transparent;");
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHalignment(HPos.RIGHT); c1.setMinWidth(140);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHalignment(HPos.LEFT); c2.setHgrow(Priority.ALWAYS);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setHalignment(HPos.LEFT); c3.setMinWidth(120);
        grid.getColumnConstraints().addAll(c1, c2, c3);

        // 慢放帧数
        Label framesLabel = new Label("慢放帧数 (3000~10000)");
        slowmoSlider = new Slider(SlowmoStore.MIN_FRAMES, SlowmoStore.MAX_FRAMES, SlowmoStore.DEFAULT_FRAMES);
        slowmoSlider.setShowTickLabels(true); slowmoSlider.setShowTickMarks(true);
        slowmoSlider.setMajorTickUnit(1000); slowmoSlider.setMinorTickCount(0); slowmoSlider.setBlockIncrement(SlowmoStore.STEP);
        slowmoValue = new Label(String.valueOf(SlowmoStore.getInstance().getSlowmoFrames()));
        grid.add(framesLabel, 0, 0); grid.add(slowmoSlider, 1, 0); grid.add(slowmoValue, 2, 0);

        // 文件大小估算
        Label sizeLabel = new Label("估算文件大小");
        sizeValue = new Label("-");
        grid.add(sizeLabel, 0, 1); grid.add(sizeValue, 1, 1, 2, 1);

        // 估算时长（总帧数 / FPS）
        Label secondsLabel = new Label("估算时长");
        secondsValue = new Label("-");
        grid.add(secondsLabel, 0, 2); grid.add(secondsValue, 1, 2, 2, 1);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(grid);
        root.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 12; -fx-border-color: #d6d9dc; -fx-border-width: 1; -fx-border-radius: 12; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.20), 16, 0.30, 0, 6);");

        // 初始化控件逻辑
        setupControls();

        // 使用 Popup 展示，点击外部自动关闭
        Popup p = new Popup();
        p.setAutoHide(true);
        p.setHideOnEscape(true);
        p.getContent().add(root);
        attachToPopup(p);
        try {
            double centerX = owner.getX() + Math.max(0, owner.getWidth() - 520) / 2;
            double centerY = owner.getY() + Math.max(0, owner.getHeight() - 220) / 2;
            p.setX(centerX);
            p.setY(centerY);
        } catch (Throwable ignore) {}
        p.show(owner);
    }

    private void setupControls() {
        // 初始化恒定的持久化防抖
        persistDebounce = new PauseTransition(Duration.millis(250));
        persistDebounce.setOnFinished(ev -> {
            int frames = getRoundedFrames(slowmoSlider.getValue());
            SlowmoStore.getInstance().setSlowmoFrames(frames);
        });

        // 初始值采用本地存储
        int initFrames = SlowmoStore.getInstance().getSlowmoFrames();
        slowmoSlider.setValue(initFrames);
        slowmoValue.setText(String.valueOf(initFrames));
        updateSizeEstimate(initFrames);

        // 滑块变化
        slowmoSlider.valueProperty().addListener((obs, oldV, newV) -> {
            int v = getRoundedFrames(newV.doubleValue());
            slowmoSlider.setValue(v);
            slowmoValue.setText(String.valueOf(v));
            updateSizeEstimate(v);
            // 防抖持久化
            persistDebounce.stop();
            persistDebounce.playFromStart();
        });

        // 配置变更时（如档位变化）重新计算估算大小
        ConfigStore.getInstance().addThinConfigListener(cfg -> {
            int currentFrames = getRoundedFrames(slowmoSlider.getValue());
            updateSizeEstimate(currentFrames);
        });
    }

    private void attachToPopup(Popup popup) {
        this.popup = popup;
        if (titleBar != null) {
            titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
            });
            titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
                popup.setX(e.getScreenX() - dragOffsetX);
                popup.setY(e.getScreenY() - dragOffsetY);
            });
        }
    }

    private int getRoundedFrames(double raw) {
        int clamped = (int)Math.max(SlowmoStore.MIN_FRAMES, Math.min(SlowmoStore.MAX_FRAMES, Math.round(raw)));
        int remainder = clamped % SlowmoStore.STEP;
        if (remainder != 0) {
            int down = clamped - remainder;
            int up = down + SlowmoStore.STEP;
            clamped = (clamped - down) < (up - clamped) ? down : up;
        }
        return Math.max(SlowmoStore.MIN_FRAMES, Math.min(SlowmoStore.MAX_FRAMES, clamped));
    }

    private void updateSizeEstimate(int totalFrames) {
        // 基于磁盘PNG存储进行估算：慢放帧以PNG无损保存，且在写盘前会将帧缩放到不超过1280x720（参见 DiskFrameRingBuffer.scaleToMax）
        ThinRemoteConfig cfg = null;
        try { cfg = ConfigStore.getInstance().getThinConfig(); } catch (Throwable ignore) {}
        String type = (cfg != null) ? cfg.getType() : null;
        StreamProfile profile = StreamProfile.fromString(type);
        if (profile == null) profile = StreamProfile.STANDARD;

        // 依据档位推断源分辨率（保存到磁盘前会缩放到 <=1280x720）
        StreamPreset preset = StreamPreset.TABLE.get(profile);
        if (preset == null) {
            sizeValue.setText("-");
            secondsValue.setText("-");
            return;
        }
        int srcW = Math.max(1, preset.getWidth());
        int srcH = Math.max(1, preset.getHeight());
        int maxW = 1280, maxH = 720;
        double scale = Math.min((double) maxW / srcW, (double) maxH / srcH);
        int storeW = scale >= 1.0 ? srcW : Math.max(1, (int) Math.round(srcW * scale));
        int storeH = scale >= 1.0 ? srcH : Math.max(1, (int) Math.round(srcH * scale));
        long pixels = (long) storeW * (long) storeH;

        // 经验估算：PNG对自然视频帧的平均压缩率约为 0.20 字节/像素（Bpp），实际范围 0.15~0.25
        double PNG_EST_BPP = 0.20; // bytes per pixel
        double bytesPerFrame = pixels * PNG_EST_BPP;
        double totalBytes = bytesPerFrame * Math.max(0, totalFrames);

        String human = humanReadableSize(totalBytes);
        sizeValue.setText(human + "  (PNG, " + storeW + "x" + storeH + ", 档位: " + profile.toTypeString() + ")");

        // 时长估算（总帧数 / FPS）
        Integer cfgFps = (cfg != null) ? cfg.getFps() : null;
        int fpsUsed = (cfgFps != null && cfgFps > 0) ? cfgFps : Math.max(1, preset.getFps());
        double seconds = (fpsUsed > 0) ? (Math.max(0, totalFrames) / (double) fpsUsed) : 0.0;
        secondsValue.setText(String.format("≈ %.1f 秒 (FPS: %d)", seconds, fpsUsed));
    }

    private String humanReadableSize(double bytes) {
        final double KB = 1024.0;
        final double MB = KB * 1024.0;
        final double GB = MB * 1024.0;
        if (bytes >= GB) return String.format("%.2f GB", bytes / GB);
        if (bytes >= MB) return String.format("%.2f MB", bytes / MB);
        if (bytes >= KB) return String.format("%.2f KB", bytes / KB);
        return String.format("%.0f B", bytes);
    }
}