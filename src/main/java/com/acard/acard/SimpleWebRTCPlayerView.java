package com.acard.acard;

import com.acard.acard.SimpleWebRTCPlayer;
import com.acard.acard.DiskFrameRingBuffer;

import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.ThinConfigResponse;
import com.acard.acard.storage.AuthStore;
import com.acard.acard.storage.ConfigStore;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.ui.CameraSettingsDialogController;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.AnchorPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.geometry.Insets;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.effect.ColorAdjust;
import javafx.stage.Stage;
import javafx.scene.shape.Rectangle;
import javafx.geometry.Rectangle2D;

import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * SimpleWebRTCPlayer 的 UI 封装组件
 * 提供自适应布局和简洁的播放控制接口
 */
public class SimpleWebRTCPlayerView extends StackPane {
    
    private final SimpleWebRTCPlayer player;
    private final ImageView videoView;
    private final Label statusLabel;
    private final Label metricsLabel;
    
    // 缩放相关成员：仅放大，最大4x
    private double zoomFactor = 1.0;
    private final Rectangle clipRect = new Rectangle();
    private final javafx.scene.transform.Scale zoomScale = new javafx.scene.transform.Scale(1.0, 1.0);
    
    // 自适应缩放属性
    private final DoubleProperty scaleFactorProperty = new SimpleDoubleProperty(1.0);
    
    // 左下角控制按钮（相机切换 / 相机旋转 / 设置）
    private HBox bottomLeftControls;
    private Button btnSwitchCamera, btnRotateCamera, btnQualityToggle, btnFps, btnKbps, btnSettings;
    private boolean hdMode = true;
    private Runnable onSwitchCamera, onRotateCamera, onOpenSettings;
    
    // 流活动监控相关字段
    private volatile long lastFrameTimeMs = 0L;
    private volatile boolean streamActive = false;
    private java.util.function.Consumer<Boolean> onStreamActiveChanged;
    private final java.util.concurrent.ScheduledExecutorService streamMonitor = 
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SimpleWebRTCPlayerView-StreamMonitor");
            t.setDaemon(true);
            return t;
        });
    private java.util.concurrent.ScheduledFuture<?> streamMonitorFuture;
    
    // 慢放功能相关字段
    private volatile boolean slowMoCapturing = false;
    private final DiskFrameRingBuffer slowDiskBuffer = new DiskFrameRingBuffer(
        SlowmoStore.getInstance().getSlowmoFrames(),
        java.nio.file.Paths.get("runtime", "slowmo")
    );
    private final ThreadPoolExecutor slowIoExecutor = new ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(128),
        r -> { Thread t = new Thread(r, "SlowMoDiskIO"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private final java.util.concurrent.ScheduledExecutorService slowDiskWatcher = 
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SlowDiskWatcher");
            t.setDaemon(true);
            return t;
        });
    private java.util.concurrent.ScheduledFuture<?> slowDiskWatchFuture;
    private final int slowCaptureIntervalMs = 30; // 默认30fps
    private volatile long lastSlowFrameSaveMs = 0L; // 上次慢放帧保存时间
    private final java.util.concurrent.atomic.AtomicLong slowSeq = new java.util.concurrent.atomic.AtomicLong(0);
    
    // 慢放缓存图像保存参数（专注清晰度与写盘效率）
    private static final boolean SLOW_SAVE_AS_JPEG = true; // 使用JPEG以减小体积提升写盘速度
    private static final float SLOW_JPEG_QUALITY = 0.95f; // 0.0 - 1.0，提高到0.90以提升清晰度
    private static final boolean SLOW_DOWNSCALE_ENABLED = true; // 高清直接降采样，保证播放流畅与写盘速度
    private static final int SLOW_MAX_WIDTH = 768; // 提高到1280，兼顾清晰与性能
    
    // 慢放流停止回调
    private Runnable onSlowMoStreamStopped;
    
    // 实时缓冲区（用于实时窗口抓取）
    private final FrameRingBuffer realtimeBuffer =
        new FrameRingBuffer(120, 640, 360, 0.7f);
    
    // 当前播放帧跟踪（用于实时抓拍定位）
    private volatile int currentPlayingFrameIndex = -1; // 当前正在播放的帧在缓冲区中的索引
    private volatile long currentPlayingFrameTimestamp = 0L; // 当前正在播放的帧的时间戳
    
    // 实时帧推送相关字段
    private final ThreadPoolExecutor realtimePushExecutor =
        new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> new Thread(r, "RealtimePush")
        );
    private volatile boolean realtimePushBusy = false;
    private volatile long lastRealtimePushMs = 0L;
    private volatile long realtimePushSubmitted = 0L;
    private volatile long realtimePushCompleted = 0L;
    private volatile long realtimePushErrors = 0L;
    
    /**
     * 构造函数
     * @param serverHost 服务器地址
     * @param serverPort 服务器端口
     * @param tenant 租户
     * @param streamId 流ID
     */
    public SimpleWebRTCPlayerView(String serverHost, int serverPort, String tenant, String streamId) {
        this.player = new SimpleWebRTCPlayer(serverHost, serverPort, tenant, streamId);
        this.videoView = player.getImageView();
        // 将容器作为GPU渲染目标区域，避免 ImageView 无图时为 0x0 导致无法设置渲染矩形
        // 注：由 GpuView 统一负责 overlay 绑定，避免与此处重复导致句柄竞争
        // player.setOverlayTarget(this);
        
        // 创建状态标签
        this.statusLabel = new Label("准备播放");
        this.statusLabel.setTextFill(Color.WHITE);
        this.statusLabel.setFont(Font.font(14));
        this.statusLabel.setStyle("-fx-background-color: rgba(0,0,0,0.7); -fx-padding: 5px 10px; -fx-background-radius: 5px;");
        
        // 顶右角性能叠加标签（FPS/Kbps）
        this.metricsLabel = new Label("");
        this.metricsLabel.setTextFill(Color.WHITE);
        this.metricsLabel.setFont(Font.font(12));
        this.metricsLabel.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-text-fill: #ffffff; -fx-padding: 4 8; -fx-background-radius: 6; -fx-font-weight: bold;");
        this.metricsLabel.setVisible(false);
        
        // 初始化UI
        initializeUI();

        // 绑定到 Scene 尺寸，确保最大化或窗口变化时本视图填充父窗口
        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                prefWidthProperty().bind(newScene.widthProperty());
                prefHeightProperty().bind(newScene.heightProperty());
                Platform.runLater(this::updateVideoSize);
            }
        });
        
        // 确保容器可以最大化扩展到父窗口
        this.setMinSize(0, 0);
        this.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        // 设置自适应缩放
        setupAutoResize();
        
        // 设置流活动监控
        startStreamActivityMonitor();
        
        // 设置帧回调，用于实时和慢放缓冲推送
        player.setFrameCallback(image -> {
            updateLastFrameTime();
            pushFrameToRealtimeBuffer(image);
            pushFrameToSlowMoBuffer(image);
        });

        // 尊重外部参数（Gradle run 或环境变量）控制 GPU 显示路径，默认不强制覆盖
        // player.start();
    }
    
    /**
     * 初始化UI布局
     */
    private void initializeUI() {
        // 设置背景色
        this.setStyle("-fx-background-color: black;");
        // 设置视频视图属性
        videoView.setPreserveRatio(false);
        videoView.setSmooth(true);
        videoView.setCache(true);
        // 缩放相关状态：仅放大，最大4x（成员变量已定义）
        // 应用缩放变换和裁剪，确保缩放后不越界
        videoView.getTransforms().add(zoomScale);
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);
        // 捕获 Ctrl+滚轮事件：仅放大，围绕鼠标位置（最大4x）
        addEventFilter(ScrollEvent.SCROLL, this::handleZoomScroll);
        videoView.addEventFilter(ScrollEvent.SCROLL, this::handleZoomScroll);
        // 创建左下角控制按钮
        createBottomLeftControls();
        
        // 将视频视图、状态标签、性能叠加和底部控制条添加到容器
        // 使用一个overlay层承载底部控制条，避免受其他对齐影响
        AnchorPane overlay = new AnchorPane(bottomLeftControls);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.35); -fx-background-radius: 10; -fx-padding: 6;");
        AnchorPane.setLeftAnchor(bottomLeftControls, 6.0);
        AnchorPane.setBottomAnchor(bottomLeftControls, 6.0);
        this.getChildren().addAll(videoView, statusLabel, metricsLabel, overlay);
        StackPane.setAlignment(overlay, Pos.BOTTOM_LEFT);
        StackPane.setMargin(overlay, new Insets(0, 0, 0, 0));
        
        // 设置状态标签位置
        StackPane.setAlignment(statusLabel, Pos.TOP_LEFT);
        statusLabel.setTranslateX(0);
        statusLabel.setTranslateY(0);
        
        // 顶右角叠加标签位置
        StackPane.setAlignment(metricsLabel, Pos.TOP_RIGHT);
        metricsLabel.setTranslateX(-8);
        metricsLabel.setTranslateY(8);
        
        // 左下角控制按钮位置由 overlay(AnchorPane) 的锚点控制，无需单独设置
        
        // overlay定位由 AnchorPane 锚点控制，无需 StackPane 对齐
    }
    
    // Ctrl + 鼠标滚轮缩放：只放大到最大4倍，并以鼠标位置为中心
    private void handleZoomScroll(ScrollEvent e) {
        if (!e.isControlDown()) return; // 需要按住Ctrl
        if (e.getDeltaY() <= 0) { // 仅支持放大，不缩小
            e.consume();
            return;
        }
        double old = zoomFactor;
        double step = 1.15; // 每次滚动约放大15%
        double proposed = Math.min(4.0, old * step);
        if (proposed == old) {
            e.consume();
            return;
        }
        // 以鼠标所在位置作为缩放中心（锚点）
        Point2D pivotLocal = videoView.screenToLocal(e.getScreenX(), e.getScreenY());
        if (pivotLocal != null) {
            zoomScale.setPivotX(pivotLocal.getX());
            zoomScale.setPivotY(pivotLocal.getY());
        }
        zoomFactor = proposed;
        zoomScale.setX(zoomFactor);
        zoomScale.setY(zoomFactor);
        e.consume(); // 拦截事件避免父级滚动
    }

    private void updateVideoSize() {
        if (videoView.getImage() == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        double containerWidth = getWidth();
        double containerHeight = getHeight();
        double imageWidth = videoView.getImage().getWidth();
        double imageHeight = videoView.getImage().getHeight();
        
        if (imageWidth <= 0 || imageHeight <= 0) {
            return;
        }
        
        // 计算缩放比例，保持宽高比，使用 Cover（取最大缩放因子）以填充父容器
        double scaleX = containerWidth / imageWidth;
        double scaleY = containerHeight / imageHeight;

        // 直接拉伸：同时设置宽度和高度以填满容器，取消宽高比约束
        videoView.setFitWidth(containerWidth);
        videoView.setFitHeight(containerHeight);
        
        // 居中显示
        StackPane.setAlignment(videoView, Pos.CENTER);
    }

    // 添加自适应缩放监听，确保 Cover 填充效果在窗口和图像变化时正确应用
    private void setupAutoResize() {
        // 容器尺寸变化时重新计算
        widthProperty().addListener((obs, ov, nv) -> updateVideoSize());
        heightProperty().addListener((obs, ov, nv) -> updateVideoSize());
        // 流图像变化时重新计算
        videoView.imageProperty().addListener((obs, ov, nv) -> updateVideoSize());
        // 初始触发一次
        Platform.runLater(this::updateVideoSize);
    }
    
    /**
     * 创建左下角三枚控制按钮
     */
    private void createBottomLeftControls() {
        bottomLeftControls = new HBox(8);
        bottomLeftControls.setAlignment(Pos.CENTER_LEFT);
        bottomLeftControls.setStyle("-fx-background-color: rgba(255,255,255,0.18); -fx-padding: 8 12; -fx-background-radius: 10; -fx-border-color: rgba(0,0,0,0.25); -fx-border-radius: 10;");
        
        btnSwitchCamera = makeIconButton("/images/exchange.png", "相机切换");
        btnRotateCamera = makeIconButton("/images/rotation.png", "相机旋转");
        // 默认高清
        ThinRemoteConfig cfg = null;

        cfg = ConfigStore.getInstance().getThinConfig();
        if(cfg!=null){
            String type = cfg.getType();
            String btnQualityToggleImg = "";
            if (type.equals("high")) {
                btnQualityToggleImg = "/images/hd.png";
                hdMode=true;
            } else {
                hdMode=false;
                btnQualityToggleImg = "/images/sd.png";
            }
            btnQualityToggle = makeIconButton(btnQualityToggleImg, "画质切换(高清/标清)");
        }else{
            btnQualityToggle = makeIconButton("/images/hd.png", "画质切换(高清/标清)");
        }


        // FPS 按钮，插入到设置按钮前
        btnFps = makeIconButton("/images/fps.png", "显示/隐藏 FPS");
        // kbps 按钮，插入到设置按钮前
        btnKbps = makeIconButton("/images/kbps.png", "显示/隐藏 码率(kbps)");
        btnSettings = makeIconButton("/images/set.png", "设置");
        
        btnSwitchCamera.setOnAction(e -> {
             pushDirectionUpdate();
        });


        btnRotateCamera.setOnAction(e -> {
            pushAngleUpdate();
        });
        btnQualityToggle.setOnAction(e -> pushTypeUpdate());

        btnFps.setOnAction(e -> toggleFpsOverlay());
        btnKbps.setOnAction(e -> toggleKbpsOverlay());
        btnSettings.setOnAction(e -> {

            openCamera();

        });
        
        //bottomLeftControls.getChildren().setAll(btnSwitchCamera, btnRotateCamera, btnQualityToggle, btnFps, btnKbps, btnSettings);
        bottomLeftControls.getChildren().setAll(btnSwitchCamera, btnRotateCamera, btnQualityToggle, btnSettings);
    }


    public void openCamera(){
        // 保证在 JavaFX 应用线程中打开弹窗
        try {
            Platform.runLater(() -> {
                Stage stage = null;
                try {
                    if (btnSettings != null && btnSettings.getScene() != null && btnSettings.getScene().getWindow() instanceof Stage) {
                        stage = (Stage) btnSettings.getScene().getWindow();
                    } else if (bottomLeftControls != null && bottomLeftControls.getScene() != null && bottomLeftControls.getScene().getWindow() instanceof Stage) {
                        stage = (Stage) bottomLeftControls.getScene().getWindow();
                    }
                    if (stage == null) return;
                    CameraSettingsDialogController.showDialogWithoutFXML(stage);
                } catch (Throwable t) {
                    System.err.println("打开相机设定窗口失败: " + t.getMessage());
                }
            });
        } catch (Throwable ignore) {}
    }


    //相机切换
    private void pushDirectionUpdate() {

        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;
        Integer dir = Integer.valueOf(cfg.getDirection());
        int direction = (dir == -1 ? 1 : -1);

        try {
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

    //相机旋转
    private void pushAngleUpdate() {


        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;
        int ang = cfg.getAngle();
        int angle;
        if(ang==0){
            angle =90;
        }else if(ang==90){
            angle =180;
        }else if(ang==180){
            angle =270;
        }else if(ang==270){
            angle =0;
        } else {
            angle = ang;
        }

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

    //高清 /标清 设置
    private void pushTypeUpdate() {

        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        String type1 = cfg.getType();
        String type="";
        if(type1.equals("high")){
            type ="standard";
        }else{
            type ="high";
        }

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
            String finalType = type;
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[CameraSettings] 清晰度已更新为 " + finalType);
                                updateType();

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

    public void updateType(){
        // 方案二增强：在 JavaFX 应用线程中更新 UI，避免 OkHttp 回调线程直接操作 UI

        // 在 FX 线程中执行 UI 更新
        Platform.runLater(() -> {

            ThinRemoteConfig cfg = null;
            try {
                cfg = ConfigStore.getInstance().getThinConfig();
            } catch (Throwable ignore) {}
            String iconPath = "/images/hd.png";
            boolean isHd = true;
            if (cfg != null) {
                String type = cfg.getType();
                isHd = "high".equalsIgnoreCase(type);
                iconPath = isHd ? "/images/hd.png" : "/images/sd.png";
            }
            hdMode = isHd;
            if (btnQualityToggle == null) {
                return;
            }
            try (java.io.InputStream in = getClass().getResourceAsStream(iconPath)) {
                javafx.scene.image.Image fxImg = null;
                if (in != null) {
                    BufferedImage bi = javax.imageio.ImageIO.read(in);
                    if (bi != null) {
                        fxImg = SwingFXUtils.toFXImage(bi, null);
                    }
                }
                if (fxImg != null) {
                    ImageView iv = new ImageView(fxImg);
                    iv.setFitWidth(24);
                    iv.setFitHeight(24);
                    iv.setPreserveRatio(true);
                    ColorAdjust adjust = new ColorAdjust();
                    adjust.setBrightness(0.35);
                    adjust.setContrast(0.1);
                    iv.setEffect(adjust);
                    btnQualityToggle.setGraphic(iv);
                    btnQualityToggle.setTooltip(new Tooltip("画质切换(高清/标清)"));
                } else {
                    btnQualityToggle.setGraphic(null);
                    btnQualityToggle.setText(isHd ? "HD" : "SD");
                }
            } catch (Exception ex) {
                btnQualityToggle.setGraphic(null);
                btnQualityToggle.setText(isHd ? "HD" : "SD");
            }
        });
    }
    
    /**
     * 工具方法：创建带图标的透明按钮（支持PNG/WebP资源）
     */
    private Button makeIconButton(String resourcePath, String tooltipText) {
        Button button = new Button();
        button.setStyle("-fx-background-color: transparent; -fx-padding: 4;");
        button.setFocusTraversable(false);
        if (tooltipText != null && !tooltipText.isBlank()) {
            button.setTooltip(new Tooltip(tooltipText));
        }
        try (java.io.InputStream in = getClass().getResourceAsStream(resourcePath)) {
            javafx.scene.image.Image fxImg = null;
            if (in != null) {
                BufferedImage bi = javax.imageio.ImageIO.read(in);
                if (bi != null) {
                    fxImg = SwingFXUtils.toFXImage(bi, null);
                }
            }
            if (fxImg != null) {
                ImageView iv = new ImageView(fxImg);
                iv.setFitWidth(24);
                iv.setFitHeight(24);
                iv.setPreserveRatio(true);
                // 增加亮度与对比度，黑色图标更清晰
                ColorAdjust adjust = new ColorAdjust();
                adjust.setBrightness(0.35);
                adjust.setContrast(0.1);
                iv.setEffect(adjust);
                button.setGraphic(iv);
            } else {
                // 资源缺失时的降级：显示首字提示文本
                button.setText(tooltipText != null ? tooltipText.substring(0, Math.min(2, tooltipText.length())) : "");
            }
        } catch (Exception ex) {
            // 降级处理
            button.setText(tooltipText != null ? tooltipText.substring(0, Math.min(2, tooltipText.length())) : "");
        }
        return button;
    }
    
    // 画质切换：高清/标清
    private void toggleQuality() {
        
        hdMode = !hdMode;
        // 更新按钮图标
        String iconPath = hdMode ? "/images/hd.png" : "/images/sd.png";
        // 重建图标
        Button newBtn = makeIconButton(iconPath, "画质切换(高清/标清)");
        newBtn.setOnAction(e -> toggleQuality());
        // 替换到 HBox 对应位置（在设置按钮前面）
        int idx = bottomLeftControls.getChildren().indexOf(btnQualityToggle);
        if (idx >= 0) {
            bottomLeftControls.getChildren().set(idx, newBtn);
        } else {
            // 如果异常缺失，则插回设置前
            int settingsIdx = bottomLeftControls.getChildren().indexOf(btnSettings);
            if (settingsIdx >= 0) {
                bottomLeftControls.getChildren().add(settingsIdx, newBtn);
            } else {
                bottomLeftControls.getChildren().add(newBtn);
            }
        }
        btnQualityToggle = newBtn;
        
        // 如果播放器支持切换码率/分辨率，可在此处调用具体实现（占位逻辑）
        try {
            if (player != null) {
                //player.setQualityMode(hdMode ? "HD" : "SD");
            }
        } catch (Throwable t) {
            // 忽略：如果当前 SimpleWebRTCPlayer 未提供该方法
        }
    }

    // 显示/隐藏 FPS 叠加
    private boolean fpsVisible = false;
    private void toggleFpsOverlay() {
        fpsVisible = !fpsVisible;
        if (metricsLabel != null) {
            metricsLabel.setVisible(fpsVisible);
        }
    }

    // 显示/隐藏 码率(kbps) 叠加
    private boolean kbpsVisible = false;
    private void toggleKbpsOverlay() {
        kbpsVisible = !kbpsVisible;
        if (metricsLabel != null) {
            metricsLabel.setVisible(kbpsVisible);
        }
    }

    /**
     * 启动流活动监控
     */
    private void startStreamActivityMonitor() {
        if (streamMonitorFuture != null) {
            streamMonitorFuture.cancel(false);
        }
        
        streamMonitorFuture = streamMonitor.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                
                // 从底层播放器同步最后帧时间
                if (player != null) {
                    long playerLastFrameTime = player.getLastFrameTimeMs();
                    if (playerLastFrameTime > lastFrameTimeMs) {
                        lastFrameTimeMs = playerLastFrameTime;
                    }
                }
                
                boolean wasActive = streamActive;
                boolean isActive = (lastFrameTimeMs > 0) && (now - lastFrameTimeMs < 3000); // 3秒内有帧则认为活跃
                
                if (wasActive != isActive) {
                    streamActive = isActive;
                    if (onStreamActiveChanged != null) {
                        Platform.runLater(() -> onStreamActiveChanged.accept(isActive));
                    }
                }
            } catch (Exception e) {
                System.err.println("流活动监控异常: " + e.getMessage());
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 停止流活动监控
     */
    private void stopStreamActivityMonitor() {
        if (streamMonitorFuture != null) {
            streamMonitorFuture.cancel(false);
            streamMonitorFuture = null;
        }
    }
    
    /**
     * 更新最后帧时间
     */
    private void updateLastFrameTime() {
        lastFrameTimeMs = System.currentTimeMillis();
    }
    
    /**
     * 推送帧到实时缓冲区
     */
    private void pushFrameToRealtimeBuffer(javafx.scene.image.Image image) {
        if (image == null) return;
        
        long now = System.currentTimeMillis();
        // 节流：避免过于频繁的推送（限制为约20fps）
        if (realtimePushBusy || (now - lastRealtimePushMs < 50)) {
            return;
        }
        
        lastRealtimePushMs = now;
        realtimePushBusy = true;
        realtimePushSubmitted++;
        
        realtimePushExecutor.submit(() -> {
            try {
                BufferedImage bufferedImage = convertToBufferedImage(image);
                if (bufferedImage != null) {
                    realtimeBuffer.push(bufferedImage);
                    updateCurrentPlayingFrame();
                }
                realtimePushCompleted++;
            } catch (Exception e) {
                realtimePushErrors++;
                System.err.println("实时帧推送失败: " + e.getMessage());
            } finally {
                realtimePushBusy = false;
            }
        });
    }
    
    /**
     * 更新当前播放帧的索引和时间戳
     */
    private void updateCurrentPlayingFrame() {
        try {
            List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
            if (!snapshot.isEmpty()) {
                // 当前播放帧就是最新推入的帧（缓冲区末尾）
                currentPlayingFrameIndex = snapshot.size() - 1;
                currentPlayingFrameTimestamp = snapshot.get(currentPlayingFrameIndex).timestamp;
            }
        } catch (Exception e) {
            System.err.println("更新当前播放帧失败: " + e.getMessage());
        }
    }
    
    /**
     * 将帧推送到慢放缓冲区（保存到磁盘）
     */
    private void pushFrameToSlowMoBuffer(javafx.scene.image.Image fxImage) {
        if (!slowMoCapturing || fxImage == null) return;
        long now = System.currentTimeMillis();
        slowIoExecutor.execute(() -> {
            try {
                BufferedImage bufferedImage = convertToBufferedImage(fxImage);
                if (bufferedImage != null) {
                    // 队列水位自适应：水位越高，越激进地降采样与压缩，避免卡死
                    int qsize = slowIoExecutor.getQueue().size();
                    final int queueCapacity = 128;
                    double load = (double) qsize / (double) queueCapacity;
                    int effectiveMaxDim = SLOW_MAX_WIDTH; // 默认960
                    float effectiveQuality = SLOW_JPEG_QUALITY; // 默认0.90
                    if (load >= 0.90) {
                        effectiveMaxDim = 720;
                        effectiveQuality = 0.85f;
                    } else if (load >= 0.70) {
                        effectiveMaxDim = 960;
                        effectiveQuality = 0.90f;
                    }

                    // 高质量等比降采样（按最长边压缩到 effectiveMaxDim，保持纵横比，不会发生宽高互换）
                    BufferedImage toSave = bufferedImage;
                    int w = toSave.getWidth();
                    int h = toSave.getHeight();
                    if (SLOW_DOWNSCALE_ENABLED) {
                        int maxDim = Math.max(w, h);
                        if (maxDim > effectiveMaxDim) {
                            double scale = (double) effectiveMaxDim / (double) maxDim;
                            int newW = Math.max(1, (int) Math.round(w * scale));
                            int newH = Math.max(1, (int) Math.round(h * scale));
                            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D g2d = scaled.createGraphics();
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_SPEED);
                            g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
                            g2d.drawImage(toSave, 0, 0, newW, newH, null);
                            g2d.dispose();
                            toSave = scaled;
                        }
                    }
                    long seq = slowSeq.incrementAndGet();
                    String ext = "webp"; // 统一使用 WebP 保存慢放帧
                    String filename = String.format("slowmo_%d_%06d.%s", now, seq, ext);
                    java.nio.file.Path filePath = java.nio.file.Paths.get("runtime", "slowmo", filename);
                    java.nio.file.Files.createDirectories(filePath.getParent());
                    // 使用 WebP 写入（依赖 webp-imageio），显式使用 WebPWriteParam 设置压缩类型和质量
                    javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByMIMEType("image/webp").hasNext()
                            ? javax.imageio.ImageIO.getImageWritersByMIMEType("image/webp").next()
                            : javax.imageio.ImageIO.getImageWritersByFormatName("webp").next();
                    com.luciad.imageio.webp.WebPWriteParam writeParam = new com.luciad.imageio.webp.WebPWriteParam(writer.getLocale());
                    writeParam.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                    writeParam.setCompressionType(writeParam.getCompressionTypes()[com.luciad.imageio.webp.WebPWriteParam.LOSSY_COMPRESSION]);
                    writeParam.setCompressionQuality(Math.max(0.1f, Math.min(1.0f, effectiveQuality)));
                    try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(filePath.toFile())) {
                        writer.setOutput(ios);
                        writer.write(null, new javax.imageio.IIOImage(toSave, null, null), writeParam);
                    } finally {
                        writer.dispose();
                    }
                    try { slowDiskBuffer.pushPath(filePath); } catch (Throwable ignore) {}
                    lastSlowFrameSaveMs = now;
                    System.err.println("SLOWMO: 💾 saved frame to " + filename + " [" + toSave.getWidth() + "x" + toSave.getHeight() + "] quality=" + String.format("%.2f", writeParam.getCompressionQuality()));
                }
            } catch (Exception e) {
                System.err.println("SLOWMO: ❌ failed to save frame: " + e.getMessage());
            }
        });
    }
    
    /**
     * 将JavaFX Image转换为BufferedImage
     */
    private BufferedImage convertToBufferedImage(javafx.scene.image.Image fxImage) {
        try {
            int width = (int) fxImage.getWidth();
            int height = (int) fxImage.getHeight();
            if (width <= 0 || height <= 0) return null;
        
            javafx.scene.image.PixelReader pixelReader = fxImage.getPixelReader();
            int[] argb = new int[width * height];
            // 使用非预乘的 IntArgb，避免因预乘导致的颜色偏暗/饱和度降低
            javafx.scene.image.WritablePixelFormat<java.nio.IntBuffer> fmt = javafx.scene.image.PixelFormat.getIntArgbInstance();
            pixelReader.getPixels(0, 0, width, height, fmt, argb, 0, width);
            // 丢弃 alpha，仅保留直通的 RGB 通道以写入 TYPE_INT_RGB
            for (int i = 0; i < argb.length; i++) {
                argb[i] = argb[i] & 0x00FFFFFF;
            }
            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            bufferedImage.setRGB(0, 0, width, height, argb, 0, width);
            return bufferedImage;
        } catch (Exception e) {
            System.err.println("图像转换失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 检查流是否活跃
     */
    public boolean isStreamActive() {
        return streamActive;
    }
    
    /**
     * 设置流状态变化回调
     * @param callback 回调函数，参数为流是否活跃
     */
    public void setOnStreamActiveChanged(java.util.function.Consumer<Boolean> callback) {
        this.onStreamActiveChanged = callback;
    }
    
    /**
     * 开始播放
     */
    public void play() {
        updateStatus("正在连接...");
        try {
            player.start();
            updateStatus("播放中");
        } catch (Exception e) {
            updateStatus("播放失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        try {
            player.stop();
            updateStatus("已停止");
            // 停止流活动监控
            stopStreamActivityMonitor();
        } catch (Exception e) {
            updateStatus("停止失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 更新状态显示
     */
    private void updateStatus(String status) {
        Platform.runLater(() -> statusLabel.setText(status));
    }
    
    /**
     * 获取播放器实例（用于高级操作）
     */
    public SimpleWebRTCPlayer getPlayer() {
        return player;
    }
    
    /**
     * 获取视频视图（用于直接操作）
     */
    public ImageView getVideoView() {
        return videoView;
    }
    
    /**
     * 获取当前缩放因子
     */
    public double getScaleFactor() {
        return scaleFactorProperty.get();
    }
    
    /**
     * 获取缩放因子属性（用于绑定）
     */
    public DoubleProperty scaleFactorProperty() {
        return scaleFactorProperty;
    }
    
    /**
     * 设置是否显示状态标签
     */
    public void setStatusVisible(boolean visible) {
        statusLabel.setVisible(visible);
    }

    /** 顶右角性能叠加文案（例如 "60fps | 3090kbps"） */
    public void setMetricsOverlay(String text) {
        Platform.runLater(() -> {
            boolean show = text != null && !text.isBlank();
            metricsLabel.setText(show ? text : "");
            metricsLabel.setVisible(show);
        });
    }
    
    /**
     * 获取状态标签是否可见
     */
    public boolean isStatusVisible() {
        return statusLabel.isVisible();
    }
    
    /**
     * 获取NAL状态信息
     */
    public String getNalStatus() {
        return player.getNalStatus();
    }
    
    // ---- 慢放功能方法 ----
    
    /**
     * 开始慢放捕获
     */
    public void startSlowMoCapture() {
        System.err.println("SLOWMO: ▶️ start capture");
        slowDiskBuffer.clear();
        slowMoCapturing = true;
        slowSeq.set(0);
        
        // 清空慢放目录，避免旧文件干扰
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("runtime", "slowmo");
            java.nio.file.Files.createDirectories(dir);
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path p : ds) { 
                    try { java.nio.file.Files.deleteIfExists(p); } catch (Throwable ignore) {} 
                }
            }
        } catch (Throwable ignore) {}
        
        // 启动目录扫描器
        startSlowDiskDirWatcher();
    }
    
    /**
     * 停止慢放捕获
     */
    public void stopSlowMoCapture() {
        System.err.println("SLOWMO: ⏹ stop capture");
        slowMoCapturing = false;
        stopSlowDiskDirWatcher();
    }
    
    /**
     * 检查是否正在慢放捕获
     */
    public boolean isSlowMoCapturing() {
        return slowMoCapturing;
    }
    
    /**
     * 获取慢放磁盘快照
     */
    public List<DiskFrameRingBuffer.FrameItem> getSlowMoDiskSnapshot() {
        return slowDiskBuffer.snapshot();
    }
    
    /**
     * 获取慢放磁盘帧数
     */
    public int getSlowMoDiskCount() {
        return slowDiskBuffer.size();
    }
    
    /**
     * 获取慢放采样间隔
     */
    public int getSlowCaptureIntervalMs() {
        return slowCaptureIntervalMs;
    }
    
    /**
     * 清空慢放缓冲区并彻底复位（停止目录监控、清队列、清目录、重置序列与时间戳）
     */
    public void clearSlowMoBuffers() {
        try {
            // 停止目录监控，避免清理过程中被再次扫描
            stopSlowDiskDirWatcher();
            // 清空缓冲区与序列号、时间戳
            slowDiskBuffer.clear();
            slowSeq.set(0);
            lastSlowFrameSaveMs = 0L;
            // 清空慢放IO执行器队列，确保第二次点击不会复用旧任务
            try { slowIoExecutor.getQueue().clear(); } catch (Throwable ignore) {}
            // 清理慢放目录，彻底移除旧文件
            try {
                java.nio.file.Path dir = java.nio.file.Paths.get("runtime", "slowmo");
                java.nio.file.Files.createDirectories(dir);
                try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                    for (java.nio.file.Path p : ds) {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Throwable ignore) {}
                    }
                }
            } catch (Throwable ignore) {}
        } catch (Exception ignore) {}
    }
    
    /**
     * 启动慢放磁盘目录监控
     */
    private void startSlowDiskDirWatcher() {
        stopSlowDiskDirWatcher();
        try {
            slowDiskWatchFuture = slowDiskWatcher.scheduleAtFixedRate(this::scanSlowmoDir, 0, 200, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
    }
    
    /**
     * 停止慢放磁盘目录监控
     */
    private void stopSlowDiskDirWatcher() {
        try {
            if (slowDiskWatchFuture != null) {
                slowDiskWatchFuture.cancel(false);
                slowDiskWatchFuture = null;
            }
        } catch (Throwable ignore) {}
    }
    
    /**
     * 扫描慢放目录，将新文件推入缓冲区
     */
    private void scanSlowmoDir() {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("runtime", "slowmo");
            List<java.nio.file.Path> files = new ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir, "*.{png,jpg,jpeg,webp}")) {
                for (java.nio.file.Path p : ds) { files.add(p); }
            } catch (Throwable ignore) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                    for (java.nio.file.Path p : ds) { files.add(p); }
                } catch (Throwable ignored) {}
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            Set<java.nio.file.Path> existing = new HashSet<>();
            for (DiskFrameRingBuffer.FrameItem fi : slowDiskBuffer.snapshot()) {
                if (fi != null && fi.path != null) existing.add(fi.path);
            }
            for (java.nio.file.Path p : files) {
                if (!existing.contains(p)) {
                    slowDiskBuffer.pushPath(p);
                }
            }
        } catch (Throwable ignore) {}
    }

    public void waitForSlowDiskFlush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        try {
            while (System.currentTimeMillis() < deadline) {
                if (slowIoExecutor.getQueue().isEmpty()) break;
                try { Thread.sleep(10); } catch (InterruptedException ignore) {}
            }
        } catch (Throwable ignore) {}
    }
    
    /**
     * 设置慢放流停止回调
     */
    public void setOnSlowMoStreamStopped(Runnable callback) {
        this.onSlowMoStreamStopped = callback;
    }
    
    /**
     * 获取实时缓冲区大小
     */
    public int getRealtimeBufferSize() {
        try {
            return realtimeBuffer.snapshot().size();
        } catch (Throwable ignore) {
            return 0;
        }
    }
    
    /**
     * 获取最后一帧的时间戳
     */
    public long getLastRealtimeTimestamp() {
        try {
            List<FrameRingBuffer.FrameItem> ss = realtimeBuffer.snapshot();
            if (ss == null || ss.isEmpty()) return 0L;
            return ss.get(ss.size() - 1).timestamp;
        } catch (Throwable ignore) {
            return 0L;
        }
    }
    
    /**
     * 获取当前正在播放的帧时间戳
     */
    public long getCurrentPlayingTimestamp() {
        return currentPlayingFrameTimestamp;
    }
    
    /**
     * 获取当前正在播放的帧索引
     */
    public int getCurrentPlayingFrameIndex() {
        return currentPlayingFrameIndex;
    }
    
    /**
     * 抓取实时滑窗窗口：以当前正在播放的帧为事件点，返回 [event-pre, event+post] 范围内的窗口。
     * 会等待后向帧到位（直到超时）。
     */
    public com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem>
            collectRealtimeWindow(int preCount, int postCount, long waitTimeoutMs) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);

        // 获取当前播放帧的时间戳作为锚点
        long anchorTimestamp = currentPlayingFrameTimestamp;
        if (anchorTimestamp <= 0) {
            // 如果没有当前播放帧，使用最新帧
            List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
            if (snapshot.isEmpty()) {
                return new com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<>(
                    java.util.Collections.emptyList(), 0, 0, 0, false);
            }
            anchorTimestamp = snapshot.get(snapshot.size() - 1).timestamp;
        }

        // 使用时间戳锚定方式抓取窗口
        return collectRealtimeWindowAnchoredTs(anchorTimestamp, preCount, postCount);
    }

    /**
     * 抓取实时滑窗窗口（锚定事件索引）：以指定的绝对事件索引为中心，返回 [event-pre, event+post] 范围内的窗口。
     * 不等待，仅基于当前 buffer 快照构造窗口；用于后台增量刷新。
     */
    public com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem>
            collectRealtimeWindowAnchored(int eventAbsIndex, int preCount, int postCount) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<>(
                java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex = Math.max(0, Math.min(eventAbsIndex, size - 1));
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        ArrayList<FrameRingBuffer.FrameItem> window =
            new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) window.add(snapshot.get(i));
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        // anchored 模式不等待，因此 timedOut 恒为 false（由调用方控制整体超时逻辑）
        return new com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<>(
            window, eventRel, start, end, false);
    }

    /**
     * 抓取实时滑窗（按时间戳锚定）：将事件点固定为 anchorTs 对应的帧，窗口为 [event-pre, event+post]。
     * 通过在快照中查找 <= anchorTs 的最后一帧实现锚定，避免事件点随"最新帧"漂移。
     */
    public com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem>
            collectRealtimeWindowAnchoredTs(long anchorTs, int preCount, int postCount) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<>(
                java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex = 0;
        // 找到最后一个时间戳 <= anchorTs 的帧作为事件点
        if (anchorTs <= snapshot.get(0).timestamp) {
            eventIndex = 0;
        } else if (anchorTs >= snapshot.get(size - 1).timestamp) {
            eventIndex = size - 1;
        } else {
            for (int i = size - 1; i >= 0; i--) {
                if (snapshot.get(i).timestamp <= anchorTs) { 
                    eventIndex = i; 
                    break; 
                }
            }
        }
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        ArrayList<FrameRingBuffer.FrameItem> window =
            new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) window.add(snapshot.get(i));
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        return new com.acard.acard.capture.SnapshotWindowCollector.SnapshotWindowResult<>(
            window, eventRel, start, end, false);
    }

    /**
     * 直接从GStreamer管道抓拍当前帧（优化版本）
     * 避免从缓冲区获取造成的图片断层问题，直接从管道获取原始帧数据
     * @param callback 抓拍完成后的回调，接收抓拍到的图像
     */
    public void captureCurrentFrame(java.util.function.Consumer<javafx.scene.image.Image> callback) {
        if (player == null) {
            System.err.println("❌ 播放器未初始化，无法抓拍");
            return;
        }
        
        // 使用播放器的直接抓拍功能
        player.requestCapture(callback);
    }

    /**
     * 异步抓拍当前帧，避免UI卡顿
     * @param callback 抓拍完成后的回调
     */
    public void captureCurrentFrameAsync(java.util.function.Consumer<javafx.scene.image.Image> callback) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            captureCurrentFrame(image -> {
                // 确保回调在JavaFX线程中执行
                Platform.runLater(() -> callback.accept(image));
            });
        });
    }
    
    /**
     * 关闭播放器，清理所有资源
     */
    public void shutdown() {
        System.err.println("🔄 开始关闭SimpleWebRTCPlayerView...");
        
        try {
            // 停止慢放捕获
            slowMoCapturing = false;
            
            // 停止流活动监控
            stopStreamActivityMonitor();
            
            // 关闭流监控线程池
            try { 
                streamMonitor.shutdownNow(); 
            } catch (Exception ignore) {}
            
            // 停止慢放磁盘监控
            stopSlowDiskDirWatcher();
            
            // 关闭慢放磁盘监控线程池
            try { 
                slowDiskWatcher.shutdownNow(); 
            } catch (Exception ignore) {}
            
            // 关闭慢放IO线程池
            try { 
                slowIoExecutor.shutdownNow(); 
            } catch (Exception ignore) {}
            
            // 关闭实时推送线程池
            try {
                realtimePushExecutor.shutdownNow();
            } catch (Exception ignore) {}
            
            // 停止底层播放器
            if (player != null) {
                player.stop();
            }
            
            // 清理缓冲区
            try {
                slowDiskBuffer.clear();
                realtimeBuffer.clear();
            } catch (Exception ignore) {}
            
            System.err.println("✅ SimpleWebRTCPlayerView已完全关闭");
            
        } catch (Exception e) {
            System.err.println("❌ 关闭SimpleWebRTCPlayerView时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // 供外部设置按钮行为的回调
    public void setOnSwitchCamera(Runnable r) { this.onSwitchCamera = r; }
    public void setOnRotateCamera(Runnable r) { this.onRotateCamera = r; }
    public void setOnOpenSettings(Runnable r) { this.onOpenSettings = r; }
}

