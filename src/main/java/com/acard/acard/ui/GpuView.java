package com.acard.acard.ui;

import com.acard.acard.RawFrameRingBuffer;
import com.acard.acard.SimpleWebRTCPlayer;
import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.net.LoginResponse;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.StompWebSocketClient;
import com.acard.acard.net.ThinConfigResponse;
import com.acard.acard.storage.AuthStore;
import com.acard.acard.storage.ConfigStore;
import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.input.ScrollEvent;
import javafx.scene.transform.Scale;
import javafx.scene.shape.Rectangle;

import com.acard.acard.FrameRingBuffer;
import com.acard.acard.store.ZstdFrameStore;
import com.sun.management.OperatingSystemMXBean;

import com.acard.acard.capture.SnapshotWindowCollector;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.application.Platform;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * GpuView: 一个可挂载到任意 JavaFX 布局中的占位容器，用于请求将 GPU 视频渲染绑定到其区域，
 * 并承载与 SimpleWebRTCPlayerView 等效的叠加 UI（顶部指标、左下角控制按钮与回调接口）。
 * 使用方式：
 * - 创建 GpuView 并将其添加到 UI 布局树中
 * - 调用 attach(player) 传入 SimpleWebRTCPlayer 实例（必须在 player.play() 之前）
 * - 可配置 strictArea（严格区域绑定）与 preferOverlaySink（优先使用支持 VideoOverlay 的 d3dvideosink）
 */
public class GpuView extends StackPane implements  Closeable {
    private SimpleWebRTCPlayer player;
    private boolean strictArea = true; // 默认启用严格区域绑定
    private boolean preferOverlaySink = false; // 默认不强制 d3dvideosink；必要时可手动开启以使用 VideoOverlay
    
    // ⚡ 软解 JavaFX 渲染支持
    private ImageView softwareRenderView;  // 软解时用于显示视频帧
    private volatile boolean isSoftwareDecoder = false;  // 是否为软解模式



    // 底部快捷按钮引用
    private Button btnShortcutSwitchCamera;
    // rotateFlipComboBox 已替换为 btnRotateFlip 按钮
    //private Button btnShortcutQualityToggle;
    //private Button btnShortcutSettings;
    
    // 镜像下拉框
    private ComboBox<String> mirrorComboBox;

    Button btnZoom;
    Button btnFocus;
    // 画质下拉框
    // 画质下拉框
    private ComboBox<String> qualityComboBox;
    
    // ⭐ 底部快捷按钮容器（用于显示/隐藏）
    private HBox shortcutButtonsContainer;

    // ⭐ 按钮悬浮窗口（使用 Popup 代替 Stage）
    private javafx.stage.Popup buttonOverlayPopup;



    // 缩放状态（不使用 Scale Transform，而是通过调整渲染尺寸实现）
    private double overlayScale = 1.0;
    private static final double MIN_SCALE = 1.0;  // ✅ 最小为1.0，视频铺满容器，不能缩小
    private static final double MAX_SCALE = 3.0;  // ✅ 最大放大到3倍（超过后画质损失明显）
    private static final double SCALE_STEP_UP = 1.01;   // ✅ 放大步进：每次增加1%（极致丝滑）
    private static final double SCALE_STEP_DOWN = 0.99; // ✅ 缩小步进：每次减少1%（极致丝滑）

    // ✅ 缩放中心点（用于计算平移偏移）
    private double zoomPivotX = 0;
    private double zoomPivotY = 0;
    
    // ⚡ 鼠标拖动状态（用于窗口放大模式的平移）
    private boolean isDragging = false;
    private double dragStartX = 0;
    private double dragStartY = 0;

    // ---------------- 优化：禁用实时缓冲以降低 CPU 使用率 ----------------
    // 通过系统属性控制是否启用缓冲（默认禁用以实现 2% CPU）
    private static final boolean ENABLE_REALTIME_BUFFER = Boolean.parseBoolean(System.getProperty("gpuview.buffer.enabled", "false"));

    // 条件初始化：仅在启用时创建缓冲
    private final RawFrameRingBuffer rawRealtimeBuffer = ENABLE_REALTIME_BUFFER ? new RawFrameRingBuffer(120, 800, 450) : null;
    private final ZstdFrameStore zstdStore = ENABLE_REALTIME_BUFFER ?
            new ZstdFrameStore(Paths.get(System.getProperty("java.io.tmpdir"), "acard", "realtime-zstd"), 120) : null;

    // 轻量打点：累计推送计数、最近一次推送时间戳、每N帧打印一次
    private volatile long realtimePushedCount = 0;
    private volatile long realtimeLastPushMs = 0;
    private static final int REALTIME_LOG_EVERY = 30;
    // 记录每一帧：取消节流，确保每一帧都入环
    private static final long MIN_PUSH_INTERVAL_MS = 0;
    private volatile long lastBufferedMs = 0;


    // ⭐ 用于防抖的时间戳和定时器
    private volatile long lastResizeTime = 0;
    private volatile javafx.animation.PauseTransition resizeDebounceTimer;



    // 条件初始化：仅在启用缓冲时创建线程池
    private final ThreadPoolExecutor realtimePushExecutor = ENABLE_REALTIME_BUFFER ? new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "GpuView-RealtimePush"); t.setDaemon(true); return t; }
    ) : null;


    // ⭐ 成员变量：追踪当前按下的键
    private final Set<KeyCode> pressedKeys = new HashSet<>();

    // ⭐ 在构造函数或初始化方法中设置键盘监听
    private void setupKeyboardTracking() {
        // 监听键盘按下
        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.setOnKeyPressed(e -> {
                    pressedKeys.add(e.getCode());
                });

                newScene.setOnKeyReleased(e -> {
                    pressedKeys.remove(e.getCode());
                });
            }
        });
    }

    // ⭐ 检查某个键是否被按下
    private boolean isKeyPressed(KeyCode keyCode) {
        return pressedKeys.contains(keyCode);
    }
    
    // ⭐ 设置尺寸变化监听器，实时刷新 VideoOverlay
    private void setupResizeListener() {
        // 创建防抖定时器（16ms约等于60fps，既快速又避免过度刷新）
        resizeDebounceTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(16));
        resizeDebounceTimer.setOnFinished(e -> {
            if (player != null) {
                try {
                    player.refreshOverlayRectangle();
                } catch (Exception ex) {
                    // 忽略刷新异常，避免影响主流程
                }
            }
        });
        
        // 监听宽度变化
        widthProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1.0) {
                requestOverlayRefresh();
            }
        });
        
        // 监听高度变化
        heightProperty().addListener((obs, oldVal, newVal) -> {
            if (Math.abs(newVal.doubleValue() - oldVal.doubleValue()) > 1.0) {
                requestOverlayRefresh();
            }
        });
    }
    
    // ⭐ 请求刷新 Overlay（带防抖）
    private void requestOverlayRefresh() {
        if (resizeDebounceTimer != null) {
            resizeDebounceTimer.stop();
            resizeDebounceTimer.playFromStart();
        }
    }


    // 或者提供手动清理方法
    public void cleanup() {
        if (player != null) {
            player.stop();
            player = null;
        }
        if (resizeDebounceTimer != null) {
            resizeDebounceTimer.stop();
        }
        // ⭐ 清理倒计时
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
        }
    }

    public GpuView() {

        setupKeyboardTracking();
        // ✅ 先设置为透明，让父容器的黑色背景透过，避免灰色闪现
        setStyle("-fx-background-color: transparent;");

        // 在 JavaFX 中，Region 默认大小为 0x0；可根据需要设置最小尺寸以便可见
        setMinWidth(50);  // ⭐ 提高最小尺寸到50，防止拖动时尺寸过小
        setMinHeight(50);
        initializeUI();

        // ✅ 设置裁剪区域，防止缩放后超出父节点边界
        Rectangle clipRect = new Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        // ✅ 禁用缓存，确保 clip 正确工作
        setCache(false);
        
        // ⭐ 监听尺寸变化，实时刷新 VideoOverlay
        setupResizeListener();
        
        // ⭐ 移除鼠标进入/离开事件监听器（按钮始终显示在底部）
        // setupButtonVisibilityControl();

        // 添加 Ctrl+滚轮缩放事件
        /*addEventFilter(ScrollEvent.SCROLL, e -> {
            // 检查是否按住Ctrl键
            if (!e.isControlDown()) {
                return;
            }

            // 根据滚轮方向确定缩放因子
            double factor;
            if (e.getDeltaY() > 0) {
                // 向上滚轮：放大
                factor = SCALE_STEP_UP;  // 1.01
            } else {
                // 向下滚轮：缩小
                factor = SCALE_STEP_DOWN;  // 0.99
            }

            double newScale = overlayScale * factor;
            // 限制缩放范围：最小1.0（不能缩小），最大4.0
            newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

            // 如果缩放值没有变化，直接返回
            if (Math.abs(newScale - overlayScale) < 1e-3) {
                e.consume();
                return;
            }

            overlayScale = newScale;

            // 记录缩放中心点（鼠标位置）
            zoomPivotX = e.getX();
            zoomPivotY = e.getY();

            // 通知播放器刷新 Overlay 子窗口位置与大小（应用缩放）
            if (player != null) {
                player.setVideoScale((float) overlayScale);
            }

            e.consume();
        });*/
        addEventFilter(ScrollEvent.SCROLL, e -> {
            // ⭐ 关键修复：排除底部按钮区域（60像素高度），避免在按钮上触发缩放
            double mouseY = e.getY();
            double containerHeight2 = this.getHeight();
            double bottomButtonHeight = FileToos.botoomHight;  // 底部按钮高度（60px）
            // 如果鼠标在底部按钮区域，不处理滚轮事件
            if (mouseY > (containerHeight2 - bottomButtonHeight)) {
                // 让事件继续传递给底部按钮
                return;
            }
            if (player == null) return;


            if (e.isControlDown()) {

                zoomScrollEvent(e);

            }else {
                // ⚡ 窗口放大模式：使用容器坐标
                double deltaY = e.getDeltaY();
                if (deltaY == 0) return;
                
                // ⭐ 局部放大步进：0.15（约1.5圈滚轮完成 1.0x → 3.0x）
                double delta = deltaY > 0 ? 0.15 : -0.15;
                
                // ⚡ 直接传递鼠标在容器中的坐标（窗口放大模式）
                int zoomMouseX = (int) e.getX();
                int zoomMouseY = (int) mouseY;  // 复用已有的 mouseY
                
                // ⭐ 调用播放器的缩放方法（窗口放大模式）
                player.adjustZoom(delta, zoomMouseX, zoomMouseY);
            }
            e.consume();
        });
        
        // ⚡ 双击重置缩放
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getClickCount() == 2 && player != null) {
                player.resetVideoZoom();
                e.consume();
            }
        });
        
        // ⚡ 拖动平移（放大后才生效）
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (player == null) return;
            
            // ⭐ 关键修复：排除底部按钮区域，避免拦截按钮点击
            double mouseY = e.getY();
            double containerHeight = this.getHeight();
            double bottomButtonHeight = FileToos.botoomHight;  // 底部按钮高度
            if (mouseY > containerHeight - bottomButtonHeight) {
                return;  // 在底部按钮区域，不处理拖动事件
            }
            
            // 只有放大状态下才启用拖动
            if (player.getCurrentZoom() > 1.0) {
                isDragging = true;
                dragStartX = e.getX();
                dragStartY = e.getY();
                e.consume();
            }
        });
        
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!isDragging || player == null) return;
            
            // ⭐ 关键修复：排除底部按钮区域
            double mouseY = e.getY();
            double containerHeight = this.getHeight();
            double bottomButtonHeight = FileToos.botoomHight;
            if (mouseY > containerHeight - bottomButtonHeight) {
                isDragging = false;  // 进入按钮区域时停止拖动
                return;
            }
            
            double deltaX = e.getX() - dragStartX;
            double deltaY = e.getY() - dragStartY;
            
            // 调用播放器的平移方法
            player.panZoom(deltaX, deltaY);
            
            // 更新起始点（增量平移）
            dragStartX = e.getX();
            dragStartY = e.getY();
            e.consume();
        });
        
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (isDragging) {
                isDragging = false;
                e.consume();
            }
        });
        
        initializeEventListeners();


    }


    //对焦距离
    private void pushFocusDistanceUpdate(float value) {
        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新对焦距离：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[CameraSettings] 无法更新对焦距离：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setFocus(value);
            payload.setPtype("focus");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.CameraType);
            updateConfigState();
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                //ConfigStore.getInstance().setThinConfig(body.getData());

                                System.out.println("[CameraSettings] 对焦距离已更新为 " + value);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[CameraSettings] 更新对焦距离失败: " + msg);
                            }
                        } else {
                            System.err.println("[CameraSettings] 更新对焦距离失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[CameraSettings] 更新对焦距离异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[CameraSettings] 更新对焦距离异常: " + e.getMessage());
        }
    }


    private void pushZoomUpdate(double value) {
        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                LogTools.getInstance().logRecord("[CameraSettings] 无法更新焦距：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                LogTools.getInstance().logRecord("[CameraSettings] 无法更新焦距：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setZoom(value);
            payload.setPtype("zoom");
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.CameraType);
            updateConfigState();
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                //ConfigStore.getInstance().setThinConfig(body.getData());

                                LogTools.getInstance().logRecord("[CameraSettings] 焦距已更新为 " + value);
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                LogTools.getInstance().logRecord("[CameraSettings] 更新焦距失败: " + msg);
                            }
                        } else {
                            LogTools.getInstance().logRecord("[CameraSettings] 更新焦距失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        LogTools.getInstance().logRecord("[CameraSettings] 更新焦距异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LogTools.getInstance().logRecord("[CameraSettings] 更新焦距异常: " + e.getMessage());
        }
    }

    /**
     * 获取当前缩放比例（供 SimpleWebRTCPlayer 使用）
     */
    public double getOverlayScale() {
        return overlayScale;
    }

    /**
     * 绑定到指定的播放器，使其将视频渲染绑定到本区域。
     */
    public void attach(SimpleWebRTCPlayer player) {
        this.player = player;
        try { System.out.println("GPUView: 📎 attach called, binding overlay & frame callback"); } catch (Throwable ignore) {}

        // ✨ 启用轻量级抓拍功能（内存<3MB，CPU<0.5%）
        System.setProperty("capture.enabled", "true");
        System.out.println("GPUView: ✅ 已启用抓拍功能（capture.enabled=true）");

        // 通过系统属性与现有 SimpleWebRTCPlayer 逻辑对接，触发严格区域绑定与 sink 选择
        System.setProperty("video.strictArea", Boolean.toString(strictArea));
        if (preferOverlaySink) {
            // 新增属性：优先使用支持 VideoOverlay 的 d3dvideosink
            System.setProperty("video.forceD3DVideoSink", "true");
        }
        // 将当前 GpuView 作为 overlayTarget 传递给播放器
        player.setOverlayTarget(this);
        
        // ⚡ 设置帧回调：软解时渲染到 ImageView，硬解时推入缓冲
        player.setFrameCallback(frame -> {
            // 推入实时缓冲（抓拍用）
            pushFrameToRealtimeBuffer(frame);
            
            // ⚡ 软解时渲染到 ImageView
            if (isSoftwareDecoder && softwareRenderView != null && frame != null) {
                Platform.runLater(() -> {
                    try {
                        softwareRenderView.setImage(frame);
                    } catch (Throwable ignored) {}
                });
            }
        });

        // ⭐ 延迟创建悬浮窗口（等待布局完成）

        // ⭐ 绑定到 Scene 生命周期（组件卸载时自动停止）
        // ⚠️ 关键修复：延迟检查，避免在容器交换时误判为移除
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null && this.player != null) {
                // ⭐ 延迟 500ms 后再检查，确认是真的被移除，而不是容器交换中的临时状态
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
                delay.setOnFinished(e -> {
                    // 再次检查：如果500ms后还是没有Scene，说明真的被移除了
                    if (this.getScene() == null && this.player != null) {
                        System.out.println("🔴 GpuView 已从 Scene 移除（确认），自动停止播放器");
                        try {
                            this.player.stop();
                            this.player = null;
                        } catch (Exception ex) {
                            System.err.println("❌ 停止播放器失败: " + ex.getMessage());
                        }
                    } else if (this.getScene() != null) {
                        System.out.println("✅ GpuView 只是容器交换，未真正移除，保持播放");
                    }
                });
                delay.play();
            }
        });

        // ⭐ 绑定到 Window 关闭事件（窗口关闭时自动停止）
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                Window window = newScene.getWindow();
                if (window != null) {
                    window.addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, e -> {
                        System.out.println("🔴 窗口关闭，自动停止播放器");
                        if (this.player != null) {
                            try {
                                this.player.stop();
                                this.player = null;
                            } catch (Exception ex) {
                                System.err.println("❌ 停止播放器失败: " + ex.getMessage());
                            }
                        }
                    });
                }
            }
        });


    }

    /**
     * 解除绑定（不销毁播放器），仅移除本视图与播放器的关联。
     */
    public void detach() {
        this.player = null;
        // 恢复属性为默认（不强制），避免影响其他视图
        System.clearProperty("video.forceD3DVideoSink");
        // 清理未处理的推送任务，避免后台队列持续堆积造成CPU抖动
        try {
            var q = realtimePushExecutor.getQueue();
            if (q != null) q.clear();
            realtimeLastPushMs = 0; // 重置活跃打点
        } catch (Throwable ignore) {}
        // 可按需保留 strictArea，全局严格区域下一次 attach 仍可用
    }

    /** 是否启用严格区域绑定（VideoOverlay 绑定仅在 sink 支持时生效） */
    public boolean isStrictArea() { return strictArea; }
    public void setStrictArea(boolean strictArea) { this.strictArea = strictArea; }

    /** 是否优先使用支持 VideoOverlay 的 d3dvideosink（可能与 D3D11 链路不完全兼容） */
    public boolean isPreferOverlaySink() { return preferOverlaySink; }
    public void setPreferOverlaySink(boolean preferOverlaySink) { this.preferOverlaySink = preferOverlaySink; }

    /** 获取当前绑定的播放器实例（可能为 null） */
    public SimpleWebRTCPlayer getPlayer() { return player; }
    
    /**
     * ⚡ 设置软解模式（播放器启动后调用）
     * @param isSoftware true=软解，false=硬解
     */
    public void setSoftwareDecoderMode(boolean isSoftware) {
        this.isSoftwareDecoder = isSoftware;
        Platform.runLater(() -> {
            if (softwareRenderView != null) {
                softwareRenderView.setVisible(isSoftware);
                // ⚡ 软解时将 ImageView 提升到最上层，确保不被遮挡
                if (isSoftware) {
                    softwareRenderView.toFront();
                }
                LogTools.getInstance().logRecord("🎬 GpuView 渲染模式: " + (isSoftware ? "软解 (JavaFX ImageView)" : "硬解 (GPU 直显)"));
            }
            
            // ⚡ 关键：切换时隐藏/显示 VideoOverlay 子窗口
            if (player != null) {
                player.setOverlayWindowVisible(!isSoftware);
            }
        });
    }
    
    /**
     * ⚡ 检测并自动设置软解模式（播放器启动后调用）
     */
    public void detectAndSetDecoderMode() {
        if (player != null) {
            boolean isSoftware = !player.isHardwareDecoder();
            setSoftwareDecoderMode(isSoftware);
        }
    }

    // 初始化叠加 UI：顶部指标 + 左下角按钮
    private void initializeUI() {
        // ⚡ 创建软解渲染 ImageView（默认隐藏，软解时显示）
        softwareRenderView = new ImageView();
        softwareRenderView.setPreserveRatio(false);  // ⚡ 不保持宽高比，平铺整个区域
        softwareRenderView.setSmooth(true);
        softwareRenderView.setVisible(false);  // 默认隐藏
        // ⚡ 绑定尺寸：完全平铺（底部留 50px 给按钮）
        softwareRenderView.fitWidthProperty().bind(widthProperty());
        softwareRenderView.fitHeightProperty().bind(heightProperty().subtract(FileToos.botoomHight));
        // ⚡ 确保左上角对齐，平铺整个可用区域
        StackPane.setAlignment(softwareRenderView, Pos.TOP_LEFT);
        getChildren().add(softwareRenderView);
        
        // 右上角性能指标标签

        // ⭐ 添加状态标签（暂无视频/休眠中）
        statusLabel = new Label("暂无视频");
        statusLabel.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 24px; -fx-font-style: italic;");
        StackPane.setAlignment(statusLabel, Pos.CENTER);
        getChildren().add(0, statusLabel);  // 添加到最底层
        
        // ⭐ 倒计时直接复用 statusLabel，不需要单独的标签

        // ✅ 新增：底部快捷按钮容器（直接显示在底部）
        createBottomShortcutButtons();
        updateConfigState();
        updateShortcutButtonStates();


    }

    public void updateConfigState(){

        Platform.runLater(() -> {
            ThinRemoteConfig cfg = null;
            try {
                cfg = ConfigStore.getInstance().getThinConfig();
            } catch (Throwable ignore) {}
            if (cfg == null) {
                LogTools.getInstance().logRecord("cfg----> instance null");
                return;
            }
            LogTools.getInstance().logRecord("init; "+"焦距:"+cfg.getFocus()+" "+"缩放:"+cfg.getZoom());
            btnFocus.setText(String.format("%.2f", cfg.getFocus()));
            btnZoom.setText(String.format("%.1f", cfg.getZoom()) + "X");

            updateQualityComboBoxDisplay();

        });




    }

    /**
     * ✅ 创建底部快捷按钮容器（使用预留的50px空间）
     * 参考Element2_3的按钮样式设计，从ThinRemoteConfig读取配置设置按钮文字
     */

    public int buttonWidth = 32;  // 新设计按钮宽度
    private Button btnRotateFlip;  // 旋转镜像按钮
    private Button btnRefresh;     // 刷新按钮
    private Button btnSleep;       // 休眠按钮
    private boolean isSleeping = false;  // 休眠状态
    private Label statusLabel;     // ⭐ 中间状态文字标签
    
    // ⭐ 工作倒计时相关（复用 statusLabel 显示）
    private javafx.animation.Timeline countdownTimeline;  // 倒计时定时器
    private int countdownSeconds = 10;  // 倒计时秒数（10秒）
    
    /**
     * ✅ 设置按钮为"休眠"状态（设备正在推流时调用）
     * publishState = 1 时调用 → 状态文字不变（有视频流会覆盖）
     */
    public void setSleepButtonToSleep() {
        isSleeping = false;
        if (btnSleep != null) {
            javafx.application.Platform.runLater(() -> {
                btnSleep.setText("休眠");
                LogTools.getInstance().logRecord3("🔄 按钮已设置为: 休眠");
            });
        }
    }
    
    /**
     * ✅ 设置按钮为"工作"状态（设备休眠/离线时调用）
     * publishState = 0 或断线时调用
     * 如果是用户主动休眠 → 显示"休眠中"
     * 如果是掉线 → 显示"暂无视频"
     */
    public void setSleepButtonToWork() {
        // ⭐ 只有当不是用户主动休眠时，才设置 isSleeping = true
        // 如果已经是休眠状态（用户主动点击），保持状态
        final boolean wasUserSleeping = isSleeping;
        isSleeping = true;
        if (btnSleep != null) {
            javafx.application.Platform.runLater(() -> {
                btnSleep.setText("工作");
                // ⭐ 如果是用户主动休眠状态，显示"休眠中"；否则显示"暂无视频"
                if (wasUserSleeping) {
                    setStatusSleeping();  // 用户主动休眠，显示"休眠中"
                    LogTools.getInstance().logRecord3("🔄 按钮已设置为: 工作，状态: 休眠中（用户主动）");
                } else {
                    setStatusNoVideo();  // 掉线，显示"暂无视频"
                    LogTools.getInstance().logRecord3("🔄 按钮已设置为: 工作，状态: 暂无视频（掉线）");
                }
            });
        }
    }
    
    /**
     * ✅ 重置休眠按钮状态（兼容旧调用）
     */
    public void resetSleepButton() {
        setSleepButtonToSleep();
    }
    
    /**
     * ⭐ 设置中间状态文字为"休眠中，点击刷新工作"
     */
    public void setStatusSleeping() {
        if (statusLabel != null) {
            javafx.application.Platform.runLater(() -> {
                statusLabel.setText("休眠中，点击刷新工作");
                statusLabel.setStyle("-fx-text-fill: #F59E0B; -fx-font-size: 24px; -fx-font-style: italic;");
                statusLabel.setVisible(true);  // ⭐ 确保可见
                LogTools.getInstance().logRecord3("📺 状态文字: 休眠中，点击刷新工作");
            });
        }
    }
    
    /**
     * ⭐ 设置中间状态文字为"暂无视频"（掉线/默认状态）
     */
    public void setStatusNoVideo() {
        if (statusLabel != null) {
            javafx.application.Platform.runLater(() -> {
                statusLabel.setText("暂无视频");
                statusLabel.setStyle("-fx-text-fill: #6b7280; -fx-font-size: 24px; -fx-font-style: italic;");
                statusLabel.setVisible(true);
                LogTools.getInstance().logRecord3("📺 状态文字: 暂无视频");
            });
        }
    }
    
    /**
     * ⭐ 设置中间状态文字为"连接中 X"（倒计时状态）
     */
    private void setStatusConnecting(int seconds) {
        if (statusLabel != null) {
            statusLabel.setText("连接中 " + seconds);
            // ⭐ 使用醒目的蓝色，较大字体
            statusLabel.setStyle("-fx-text-fill: #3B82F6; -fx-font-size: 28px; -fx-font-weight: bold;");
            statusLabel.setVisible(true);
            LogTools.getInstance().logRecord3("📺 状态文字: 连接中 " + seconds);
        }
    }
    
    /**
     * ⭐ 获取当前休眠状态
     */
    public boolean isSleeping() {
        return isSleeping;
    }
    
    /**
     * ⭐ iOS 掉线时调用：强制重置状态并显示"暂无视频"
     * 不管之前是否是用户主动休眠，都重置为非休眠状态
     */
    public void onIosDisconnected() {
        isSleeping = false;  // 重置休眠状态
        if (btnSleep != null) {
            javafx.application.Platform.runLater(() -> {
                btnSleep.setText("休眠");
                setStatusNoVideo();  // 显示"暂无视频"
                LogTools.getInstance().logRecord3("📺 iOS掉线，状态重置: 暂无视频");
            });
        }
    }
    
    private void createBottomShortcutButtons() {
        // ⭐ 新深色主题按钮样式
        String darkButtonStyle =
                "-fx-background-color: #292929; " +
                "-fx-text-fill: #FAFAFA; " +
                "-fx-padding: 0 12; " +
                "-fx-background-radius: 8; " +
                "-fx-font-size: 12px; " +
                "-fx-font-weight: 400;";
        
        String iconOnlyStyle =
                "-fx-background-color: #292929; " +
                "-fx-padding: 0; " +
                "-fx-background-radius: 8;";
        
        // 创建底部快捷按钮容器
        shortcutButtonsContainer = new HBox(10);
        shortcutButtonsContainer.setAlignment(Pos.CENTER_LEFT);
        shortcutButtonsContainer.setPadding(new Insets(12));
        shortcutButtonsContainer.setStyle("-fx-background-color: #1F1F1F;");

        // ⭐ 1. 前后切换 - 只有图标
        Button btnShortcutSwitchCamera = new Button();
        btnShortcutSwitchCamera.setStyle(iconOnlyStyle);
        btnShortcutSwitchCamera.setPrefSize(32, 32);
        btnShortcutSwitchCamera.setMinSize(32, 32);
        btnShortcutSwitchCamera.setMaxSize(32, 32);
        try {
            ImageView icon = new ImageView(new Image(getClass().getResourceAsStream("/com/acard/design/qianhou.png")));
            icon.setFitWidth(30);
            icon.setFitHeight(30);
            icon.setPreserveRatio(false);  // 不保持比例，强制14x14
            btnShortcutSwitchCamera.setGraphic(icon);
        } catch (Exception e) {
            btnShortcutSwitchCamera.setText("⟳");
        }
        btnShortcutSwitchCamera.setTooltip(createFastTooltip("前后切换"));
        btnShortcutSwitchCamera.setOnAction(e -> onShortcutSwitchCamera());

        // ⭐ 2. 旋转按钮 - 只显示图标（点击循环旋转角度）
        btnRotateFlip = new Button();
        btnRotateFlip.setStyle(iconOnlyStyle);
        btnRotateFlip.setPrefSize(32, 32);
        btnRotateFlip.setMinSize(32, 32);
        btnRotateFlip.setMaxSize(32, 32);
        try {
            ImageView rotateIcon = new ImageView(new Image(getClass().getResourceAsStream("/com/acard/design/xuanzhuan.png")));
            rotateIcon.setFitWidth(20);
            rotateIcon.setFitHeight(20);
            rotateIcon.setPreserveRatio(false);
            btnRotateFlip.setGraphic(rotateIcon);
        } catch (Exception ignored) {
            btnRotateFlip.setText("⟳");
        }
        btnRotateFlip.setOnAction(e -> cycleRotateOnly());
        btnRotateFlip.setTooltip(createFastTooltip("点击切换旋转角度"));
        // 旋转按钮支持滚轮切换
        btnRotateFlip.setOnScroll(e -> {
            if (e.getDeltaY() > 0) {
                cycleRotateOnly();  // 向上滚轮
            } else if (e.getDeltaY() < 0) {
                cycleRotateOnlyReverse();  // 向下滚轮
            }
            e.consume();
        });
        
        // ⭐ 3. 镜像下拉框 - 显示"镜像"二字，可下拉选择，可滚轮操作
        mirrorComboBox = createMirrorComboBox();
        mirrorComboBox.setStyle(darkButtonStyle + "-fx-cursor: hand;");
        mirrorComboBox.setPrefHeight(32);
        mirrorComboBox.setMinHeight(32);
        mirrorComboBox.setMaxHeight(32);

        // ⭐ 4. 高清切换 - 下拉框（新样式）
        ComboBox<String> qualityComboBox = createQualityComboBox();
        qualityComboBox.setStyle(darkButtonStyle + "-fx-cursor: hand;");
        qualityComboBox.setPrefHeight(32);
        qualityComboBox.setMinHeight(32);
        qualityComboBox.setMaxHeight(32);

        // ⭐ 5. 镜头变倍 - 只显示数字
        btnZoom = new Button("1.0X");
        btnZoom.setStyle(darkButtonStyle + "-fx-cursor: hand;");
        btnZoom.setPrefHeight(32);
        btnZoom.setMinHeight(32);
        btnZoom.setMaxHeight(32);
        btnZoom.setTooltip(createFastTooltip("镜头变倍"));
        btnZoom.setOnAction(e -> onZoom());
        btnZoom.setOnScroll(e -> {
            zoomScrollEvent(e);
            e.consume();
        });

        // ⭐ 6. 清晰度（焦距）- 只显示数字
        btnFocus = new Button("0.68");
        btnFocus.setStyle(darkButtonStyle + "-fx-cursor: hand;");
        btnFocus.setPrefHeight(32);
        btnFocus.setMinHeight(32);
        btnFocus.setMaxHeight(32);
        btnFocus.setTooltip(createFastTooltip("清晰度"));
        btnFocus.setOnAction(e -> onFocus());
        btnFocus.setOnScroll(e -> {
            focousScrollEvent(e);
            e.consume();
        });

        // ⭐ 7. 刷新 - 文字按钮（突出颜色）
        btnRefresh = new Button("刷新");
        btnRefresh.setStyle(
                "-fx-background-color: #292929; " +
                "-fx-text-fill: #00D4FF; " +  // ⭐ 突出的青色
                "-fx-padding: 0 12; " +
                "-fx-background-radius: 8; " +
                "-fx-border-radius: 8; " +
                "-fx-font-size: 14px; " +
                "-fx-font-weight: bold;"  // ⭐ 加粗
        );
        btnRefresh.setPrefHeight(32);
        btnRefresh.setMinHeight(32);
        btnRefresh.setMaxHeight(32);
        btnRefresh.setOnAction(e -> onRefresh());
        btnRefresh.setTooltip(createFastTooltip("刷新推流状态"));

        // ⭐ 8. 休眠 - 文字按钮
        btnSleep = new Button("休眠");
        btnSleep.setStyle(darkButtonStyle);
        btnSleep.setPrefHeight(32);
        btnSleep.setMinHeight(32);
        btnSleep.setMaxHeight(32);
        btnSleep.setOnAction(e -> toggleSleep());
        btnSleep.setTooltip(createFastTooltip("切换休眠/工作状态"));

        // 保存引用
        this.btnShortcutSwitchCamera = btnShortcutSwitchCamera;
        this.qualityComboBox = qualityComboBox;

        // ⭐ 左侧按钮组
        HBox leftButtons = new HBox(10);
        leftButtons.setAlignment(Pos.CENTER_LEFT);
        leftButtons.getChildren().addAll(
            btnShortcutSwitchCamera,
            btnRotateFlip,
            mirrorComboBox,
            qualityComboBox,
            btnZoom,
            btnFocus
        );
        
        // ⭐ 中间弹性空间
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        // ⭐ 右侧按钮组（刷新 + 休眠）
        HBox rightButtons = new HBox(10);
        rightButtons.setAlignment(Pos.CENTER_RIGHT);
        rightButtons.getChildren().addAll(btnRefresh, btnSleep);

        // 添加到容器（左侧按钮 + 弹性空间 + 右侧按钮）
        shortcutButtonsContainer.getChildren().addAll(
            leftButtons,
            spacer,
            rightButtons
        );

        // 设置容器高度为预留的50px空间
        shortcutButtonsContainer.setPrefHeight(50);
        shortcutButtonsContainer.setMaxHeight(50);
        shortcutButtonsContainer.setMinHeight(50);

        // ⭐ 添加到GpuView底部（直接添加，不使用Popup）
        getChildren().add(shortcutButtonsContainer);
        StackPane.setAlignment(shortcutButtonsContainer, Pos.BOTTOM_CENTER);
        shortcutButtonsContainer.setTranslateY(-2); // 距离底部2px

        // ⭐ 初始状态：显示按钮容器
        shortcutButtonsContainer.setVisible(true);
        shortcutButtonsContainer.setManaged(true);  // 占用布局空间
        updateConfigState();

    }


    public void zoomScrollEvent(ScrollEvent e){
        // 获取当前的 zoom 值
        ThinRemoteConfig config = ConfigStore.getInstance().getThinConfig();
        if (config == null) {
            LogTools.getInstance().logRecord("⚠️ 配置为空，无法调整缩放");
            return;
        }

        Double currentZoom = config.getZoom();
        if(currentZoom.doubleValue() < 1.0){
            config.setZoom(1.0);
        }
        currentZoom = config.getZoom();
        if (currentZoom == null) {
            currentZoom = 1.0; // 默认值
        }


        // ⭐ 根据滚轮方向调整缩放值（步进 0.01）
        double delta = e.getDeltaY() > 0 ? FileToos.zoomScale : -FileToos.zoomScale;
        double newZoom = currentZoom + delta;

        // ⭐ 限制范围 [1.0, 3.0]
        newZoom = Math.max(1.0, Math.min(3.0, newZoom));

        LogTools.getInstance().logRecord("🔍 滚轮调整缩放: " + currentZoom + " -> " + newZoom);

        // ⭐ 推送更新到后端
        pushZoomUpdate(newZoom);
    }


    public void focousScrollEvent(ScrollEvent e){
        // 获取当前的 zoom 值
        ThinRemoteConfig config = ConfigStore.getInstance().getThinConfig();
        if (config == null) {
            LogTools.getInstance().logRecord("⚠️ 配置为空，无法调整缩放");
            return;
        }

        Float currentFoucs = config.getFocus();
        if (currentFoucs == null) {
            currentFoucs = 0.5f; // 默认值
        }

        // ⭐ 根据滚轮方向调整缩放值（步进 0.01）
        float delta = (float) (e.getDeltaY() > 0 ? 0.01 : -0.01);
        float newZoom = currentFoucs + delta;

        // ⭐ 限制范围 [0.0, 1.0]
        newZoom = Math.max(0.0f, Math.min(1.0f, newZoom));

        LogTools.getInstance().logRecord("🔍 滚轮调整缩放: " + currentFoucs + " -> " + newZoom);

        // ⭐ 推送更新到后端
        pushFocusDistanceUpdate(newZoom);
    }


    public void setButtonHeight( Button button){
        button.setPrefHeight(45);
        button.setMinHeight(45);
        button.setMaxHeight(45);
    }

    /**
     * ⭐ 缩放功能
     */
    private void onZoom() {
        System.out.println("🔍 缩放按钮被点击");
        // TODO: 实现缩放逻辑
        // 可以弹出缩放控制面板或直接调整缩放级别
    }

    /**
     * ⭐ 对焦功能
     */
    private void onFocus() {
        System.out.println("🎯 对焦按钮被点击");
        // TODO: 实现对焦逻辑
        // 可以触发自动对焦或手动对焦调整
    }

    /**
     * ⭐ 刷新功能（用户点击刷新按钮时调用）
     * 取消旧倒计时 + 发送指令 + 启动新倒计时
     */
    private void onRefresh() {
        System.out.println("🔄 刷新按钮被点击");
        
        // ⭐ 重置视频流状态，启动倒计时
        FileToos.videoStreamStatus = 0;
        startWakeCountdown();  // 会先取消旧的倒计时
        
        // 发送刷新指令
        sendRefreshCommand();
    }
    
    /**
     * ⭐ 内部刷新方法（只发送指令，不启动倒计时）
     * 倒计时结束时自动调用
     */
    private void sendRefreshCommand() {
        try {
            // 调用 StompWebSocketClient 的 sendResetPublish 方法
            StompWebSocketClient.getInstance().sendResetPublish();
            LogTools.getInstance().logRecord3("✅ 刷新指令已发送");
        } catch (Exception e) {
            System.err.println("❌ 发送刷新指令失败: " + e.getMessage());
            LogTools.getInstance().logRecord3("❌ 发送刷新指令失败: " + e.getMessage());
        }
    }

    /**
     * ⭐ 休眠功能
     */
    private void onSleep() {
        System.out.println("💤 休眠按钮被点击");
        try {
            // 调用 StompWebSocketClient 的 sendRESET_SHENGDIANG 方法
            StompWebSocketClient.getInstance().sendRESET_SHENGDIANG();
            LogTools.getInstance().logRecord("✅ 休眠指令已发送");
        } catch (Exception e) {
            System.err.println("❌ 发送休眠指令失败: " + e.getMessage());
            LogTools.getInstance().logRecord("❌ 发送休眠指令失败: " + e.getMessage());
        }
    }

    /**
     * ⭐ 创建按钮悬浮窗口（独立Stage，覆盖在HWND之上）
     */
    /**
     * ⭐ 创建按钮悬浮 Popup（覆盖在 HWND 之上）
     */
    private void createButtonOverlayPopup() {
        if (buttonOverlayPopup != null) {
            return;
        }
        
        if (shortcutButtonsContainer == null) {
            System.out.println("⚠️ shortcutButtonsContainer 为 null，无法创建 Popup");
            return;
        }

        // ⭐ 创建 Popup
        buttonOverlayPopup = new javafx.stage.Popup();
        buttonOverlayPopup.setAutoHide(false);  // 不自动隐藏
        
        // ⭐ 确保按钮容器可见（重要！）
        shortcutButtonsContainer.setVisible(true);
        shortcutButtonsContainer.setManaged(true);
        shortcutButtonsContainer.setStyle(
                "-fx-background-color: #DDE5ED; " +  // ⭐ 改为这个颜色
                        "-fx-background-radius: 0;"
        );
        
        // ⭐ 设置容器的最小宽度（确保按钮能显示）
        shortcutButtonsContainer.setMinWidth(300);


        // ⭐ 创建包装容器（Popup 需要一个根节点）
        StackPane popupRoot = new StackPane();

        // ⭐⭐⭐ 绑定宽度到 GpuView 的宽度
        popupRoot.prefWidthProperty().bind(this.widthProperty());
        popupRoot.minWidthProperty().bind(this.widthProperty());
        popupRoot.maxWidthProperty().bind(this.widthProperty());
        popupRoot.setStyle("-fx-background-color: transparent;");
        popupRoot.getChildren().add(shortcutButtonsContainer);
        StackPane.setAlignment(shortcutButtonsContainer, Pos.CENTER);
// ⭐⭐⭐ 关键：Popup 的鼠标事件也触发显示（防止被隐藏）



        popupRoot.setOnMouseExited(e -> {
            if (!isMouseInExtendedArea()) {
                hideButtonOverlay();
                System.out.println("🖱️ 鼠标离开 Popup 且不在扩展区域，隐藏按钮");
            }
        });
        // ⭐ 设置内容（使用包装容器）
        buttonOverlayPopup.getContent().add(popupRoot);

        System.out.println("✅ 按钮 Popup 已创建，按钮容器子元素数量: " + shortcutButtonsContainer.getChildren().size());
    }

    /**
     * ⭐ 设置按钮的显示/隐藏控制（鼠标进入/离开时触发）
     */
    private void setupButtonVisibilityControl() {
        // ⭐ 鼠标进入 GpuView 时显示按钮
        this.setOnMouseEntered(e -> {
            showButtonOverlay();
            updateButtonOverlayPosition();
        });

        // ⭐ 鼠标离开 GpuView 时检查是否真的离开了扩展区域
        this.setOnMouseExited(e -> {
            // ⭐ 检查鼠标是否在 GpuView + 50px（Popup高度）范围内
            if (!isMouseInExtendedArea()) {
                hideButtonOverlay();
                System.out.println("🖱️ 鼠标离开扩展区域，隐藏按钮");
            } else {
                System.out.println("🖱️ 鼠标仍在扩展区域内（包括Popup），保持显示");
            }
        });

        // ⭐ 监听位置和大小变化
        this.layoutBoundsProperty().addListener((obs, oldVal, newVal) -> {
            updateButtonOverlayPosition();
        });

        // ⭐ 监听主窗口移动
        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.windowProperty().addListener((obs2, oldWindow, newWindow) -> {
                    if (newWindow != null) {
                        newWindow.xProperty().addListener((obs3, oldX, newX) -> {
                            updateButtonOverlayPosition();
                        });
                        newWindow.yProperty().addListener((obs3, oldY, newY) -> {
                            updateButtonOverlayPosition();
                        });
                    }
                });
            }
        });
    }

    /**
     * ⭐ 检查鼠标是否在 GpuView + Popup高度（50px）的扩展区域内
     */
    private boolean isMouseInExtendedArea() {
        try {
            // ⭐ 获取当前鼠标屏幕坐标
            java.awt.Point mouseScreenPos = java.awt.MouseInfo.getPointerInfo().getLocation();
            javafx.geometry.Point2D mousePos = new javafx.geometry.Point2D(
                    mouseScreenPos.x,
                    mouseScreenPos.y
            );

            // ⭐ 获取 GpuView 的屏幕范围
            javafx.geometry.Bounds gpuBounds = this.localToScreen(this.getBoundsInLocal());

            if (gpuBounds == null) {
                return false;
            }

            // ⭐ 扩展区域：GpuView 底部向下延伸 50px（Popup 高度）
            double minX = gpuBounds.getMinX();
            double maxX =  gpuBounds.getMaxX();
            double minY = gpuBounds.getMinY()+ 50;
            double maxY = gpuBounds.getMaxY() + 50;  // ⭐ 向下扩展 50px

            // ⭐ 判断鼠标是否在扩展区域内
            boolean inExtendedArea = mousePos.getX() >= minX &&
                    mousePos.getX() <= maxX &&
                    mousePos.getY() >= minY &&
                    mousePos.getY() <= maxY;

            return inExtendedArea;

        } catch (Exception e) {
            // 如果检测失败，返回 false（允许隐藏）
            return false;
        }
    }

    /**
     * ⭐ 显示按钮 Popup
     */
    private void showButtonOverlay() {
        if (shortcutButtonsContainer == null) {
            return;
        }

        Platform.runLater(() -> {
            // 确保 Popup 已创建并显示
            if (buttonOverlayPopup == null) {
                createButtonOverlayPopup();
            }

            // ⭐ 首次显示 Popup（固定位置，之后不再 hide）
            if (buttonOverlayPopup != null && !buttonOverlayPopup.isShowing()) {
                javafx.geometry.Bounds bounds = this.localToScreen(this.getBoundsInLocal());
                if (bounds != null && bounds.getWidth() > 0) {
                    double x = bounds.getMinX();
                    double y = bounds.getMaxY() ;  // 底部 - 50px

                    Window ownerWindow = this.getScene().getWindow();
                    buttonOverlayPopup.show(ownerWindow, x, y);
                    System.out.println("🖱️ 首次显示 Popup");
                }
            }

            // ⭐ 设置透明度为 1.0（可见）
            //shortcutButtonsContainer.setOpacity(1.0);
            shortcutButtonsContainer.setVisible(true);
            shortcutButtonsContainer.setManaged(true);  // 不占用布局空间
        });
    }

    /**
     * ⭐ 隐藏按钮 Popup
     */
    private void hideButtonOverlay() {
        if (shortcutButtonsContainer != null) {
            Platform.runLater(() -> {
                // ⭐ 只改透明度，不隐藏 Popup
                //shortcutButtonsContainer.setOpacity(0.0);
                shortcutButtonsContainer.setVisible(false);
                shortcutButtonsContainer.setManaged(false);  // 不占用布局空间
            });
        }
    }

    /**
     * ⭐ 更新按钮 Popup 的位置
     */
    private void updateButtonOverlayPosition() {
        if (buttonOverlayPopup == null || !buttonOverlayPopup.isShowing()) {
            return;
        }

        Platform.runLater(() -> {
            try {
                javafx.geometry.Bounds bounds = this.localToScreen(this.getBoundsInLocal());
                if (bounds != null) {
                    double x = bounds.getMinX();
                    double y = bounds.getMinY() + bounds.getHeight();
                    double width = bounds.getWidth();

                    // ⭐ 更新位置
                    buttonOverlayPopup.setX(x);
                    buttonOverlayPopup.setY(y);
                    
                    // ⭐ 更新宽度（与 GpuView 同步）
                    if (shortcutButtonsContainer != null) {
                        shortcutButtonsContainer.setPrefWidth(width);
                        shortcutButtonsContainer.setMaxWidth(width);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 更新 Popup 位置失败: " + e.getMessage());
            }
        });
    }

    /**
     * ⭐ 创建旋转和镜像下拉框（合并功能）
     * 点击时循环切换旋转选项（不包括镜像），滚轮可切换所有选项
     */
    private ComboBox<String> createRotateFlipComboBox() {
        ComboBox<String> comboBox = new ComboBox<>();
        
        // ⭐ 添加选项（旋转 + 镜像）
        comboBox.getItems().addAll(
            "还原",     // 0: 0度，不翻转
            "90度",         // 1: 顺时针90度
            "180度",        // 2: 旋转180度
            "270度",        // 3: 顺时针270度
            "水平",         // 4: 水平翻转
            "垂直"          // 5: 垂直翻转
        );
        
        // 设置默认值
        comboBox.setValue("还原");
        
        // ⭐ 点击时只循环旋转选项（索引0-3），不弹出下拉菜单
        comboBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            // 获取当前选中的索引
            int currentIndex = comboBox.getSelectionModel().getSelectedIndex();
            if (currentIndex < 0) currentIndex = 0;
            
            // 只在前4个旋转选项中循环（索引 0-3），跳过镜像
            int nextIndex = (currentIndex + 1) % 4;
            
            // 切换到下一个选项
            comboBox.getSelectionModel().select(nextIndex);
            
            // 阻止默认的下拉行为
            event.consume();
        });
        
        // ⭐ 拦截 MOUSE_RELEASED 事件，防止下拉框弹出
        comboBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, event -> {
            event.consume();
        });
        
        // ⭐ 拦截 MOUSE_CLICKED 事件，防止下拉框弹出
        comboBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            event.consume();
        });
        
        // ⭐ 设置样式（确保文字为白色）
        comboBox.setStyle(
            "-fx-background-color: #3b82f6;" +
            "-fx-text-fill: white;" +  // ComboBox 显示文字颜色
            "-fx-font-size: 10px;" +
            "-fx-font-weight: bold;" +
            "-fx-padding: 4px 8px;" +
            "-fx-border-radius: 6px;" +
            "-fx-background-radius: 6px;" +
            "-fx-control-inner-background: #3b82f6;" +  // 下拉框背景
            "-fx-prompt-text-fill: white;"  // 提示文字颜色
        );
        
        // ⭐ 设置按钮区域（箭头）的样式
        comboBox.setButtonCell(new javafx.scene.control.ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: white;");  // ⭐ 确保按钮文字为白色
                }
            }
        });
        
        // ⭐ 设置下拉列表中每个项的样式（白色文字）
        comboBox.setCellFactory(lv -> new javafx.scene.control.ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    // 设置每个选项的样式
                    setStyle(
                        "-fx-background-color: rgba(30, 58, 138, 0.9);" +
                        "-fx-text-fill: white;" +  // ⭐ 白色文字
                        "-fx-font-size: 12px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 6px 8px;"
                    );
                }
            }
            
            @Override
            public void updateSelected(boolean selected) {
                super.updateSelected(selected);
                if (selected) {
                    // 选中项的高亮样式
                    setStyle(
                        "-fx-background-color: rgba(74, 144, 226, 0.9);" +  // 高亮蓝色
                        "-fx-text-fill: white;" +
                        "-fx-font-size: 12px;" +
                        "-fx-font-weight: bold;" +
                        "-fx-padding: 6px 8px;"
                    );
                }
            }
        });
        
        // ⭐ 选择事件（处理旋转和镜像）
        comboBox.setOnAction(e -> {
            String selected = comboBox.getValue();
            if (selected != null && player != null) {
                switch (selected) {
                    case "还原":
                        player.setVideoRotation(0);  // 旋转归零
                        player.setVideoFlip(0);      // 镜像归零（不翻转）
                        LogTools.getInstance().logRecord("🔄 画面还原（旋转0度 + 移除镜像）");
                        break;
                    case "90度":
                        player.setVideoRotation(90);
                        LogTools.getInstance().logRecord("🔄 90度");
                        break;
                    case "180度":
                        player.setVideoRotation(180);
                        LogTools.getInstance().logRecord("🔄 180度");
                        break;
                    case "270度":
                        player.setVideoRotation(270);
                        LogTools.getInstance().logRecord("🔄 270度");
                        break;
                    case "水平":
                        player.setVideoFlip(1);
                        LogTools.getInstance().logRecord("🔄 水平");
                        break;
                    case "垂直":
                        player.setVideoFlip(2);
                        LogTools.getInstance().logRecord("🔄 垂直");
                        break;
                }
            }
        });
        
        // ⭐ 鼠标滚轮事件（在下拉框上滚动切换选项）
        comboBox.setOnScroll(event -> {
            int currentIndex = comboBox.getSelectionModel().getSelectedIndex();
            int itemCount = comboBox.getItems().size();
            
            if (event.getDeltaY() > 0) {
                // 向上滚动，选择上一个
                int newIndex = (currentIndex - 1 + itemCount) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            } else if (event.getDeltaY() < 0) {
                // 向下滚动，选择下一个
                int newIndex = (currentIndex + 1) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            }
            
            event.consume();  // 阻止事件传播
        });
        
        return comboBox;
    }
    
    /**
     * 循环切换旋转角度（只有旋转，不含镜像）
     */
    private int rotateIndex = 0;
    private final int[] rotateAngles = {0, 90, 180, 270};
    
    private void cycleRotateOnly() {
        rotateIndex = (rotateIndex + 1) % rotateAngles.length;
        int angle = rotateAngles[rotateIndex];
        
        if (player != null) {
            player.setVideoRotation(angle);
        }
        LogTools.getInstance().logRecord("🔄 旋转切换: " + angle + "度");
    }
    
    /**
     * 反向循环切换旋转角度（滚轮向下）
     */
    private void cycleRotateOnlyReverse() {
        rotateIndex = (rotateIndex - 1 + rotateAngles.length) % rotateAngles.length;
        int angle = rotateAngles[rotateIndex];
        
        if (player != null) {
            player.setVideoRotation(angle);
        }
        LogTools.getInstance().logRecord("🔄 旋转切换: " + angle + "度");
    }
    
    /**
     * 创建镜像下拉框（显示"镜像"二字，可下拉选择，可滚轮操作）
     */
    private ComboBox<String> createMirrorComboBox() {
        ComboBox<String> comboBox = new ComboBox<>();
        
        // 添加选项
        comboBox.getItems().addAll("镜像", "水平", "垂直", "还原");
        
        // 默认值
        comboBox.setValue("镜像");
        
        // 深色主题样式
        String darkComboStyle =
                "-fx-background-color: #292929; " +
                "-fx-padding: 0 12; " +
                "-fx-background-radius: 8; " +
                "-fx-font-size: 12px; " +
                "-fx-cursor: hand;";
        comboBox.setStyle(darkComboStyle);
        comboBox.getStyleClass().add("dark-combo-box");
        
        // 设置 ButtonCell 确保选中值显示区域背景色一致
        comboBox.setButtonCell(new javafx.scene.control.ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(item);
                setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-font-size: 12px;");
            }
        });
        
        // 选择事件处理
        comboBox.setOnAction(e -> {
            String selected = comboBox.getValue();
            if (selected != null && player != null) {
                switch (selected) {
                    case "镜像":
                        // "镜像"选项不做任何操作，只是占位显示
                        break;
                    case "水平":
                        player.setVideoFlip(1);
                        LogTools.getInstance().logRecord("🔄 镜像切换: 水平");
                        break;
                    case "垂直":
                        player.setVideoFlip(2);
                        LogTools.getInstance().logRecord("🔄 镜像切换: 垂直");
                        break;
                    case "还原":
                        player.setVideoFlip(0);
                        LogTools.getInstance().logRecord("🔄 镜像切换: 还原");
                        // 还原后重置显示为"镜像"
                        Platform.runLater(() -> comboBox.setValue("镜像"));
                        break;
                }
            }
        });
        
        // 滚轮支持：滚动切换镜像选项
        comboBox.setOnScroll(e -> {
            int currentIndex = comboBox.getSelectionModel().getSelectedIndex();
            int itemCount = comboBox.getItems().size();
            
            if (e.getDeltaY() > 0) {
                // 向上滚动，选择上一个
                int newIndex = (currentIndex - 1 + itemCount) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            } else if (e.getDeltaY() < 0) {
                // 向下滚动，选择下一个
                int newIndex = (currentIndex + 1) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            }
            e.consume();
        });
        
        comboBox.setTooltip(createFastTooltip("镜像切换（滚轮切换）"));
        
        return comboBox;
    }
    
    /**
     * 切换休眠/工作状态
     */
    private void toggleSleep() {
        // ✅ 只有当前是"休眠"按钮时（要进入休眠），才需要检查设备是否在线
        // 当前是"工作"按钮时（要恢复工作），直接发送，无需判断
        if (!isSleeping) {
            // 当前状态：工作中，要进入休眠 → 需要检查设备在线
            if (!CameraMainController.isDeviceOnline()) {
                LogTools.getInstance().logRecord("⚠️ 设备未在线，无法执行休眠操作");
                // ⭐ 使用深色主题弹框
                com.acard.acard.utils.AlertUtil.error("提示", "设备未在线，无法执行休眠操作");
                return;
            }
        }
        
        // ⭐ 记录是否从休眠恢复工作（点击"工作"按钮）
        final boolean isWakingUp = isSleeping;  // 当前是休眠状态，要恢复工作
        
        isSleeping = !isSleeping;
        
        // ✅ 确保在 UI 线程更新按钮文字和状态文字
        final boolean sleeping = isSleeping;
        javafx.application.Platform.runLater(() -> {
            if (btnSleep != null) {
                btnSleep.setText(sleeping ? "工作" : "休眠");
                LogTools.getInstance().logRecord3("💤 按钮文字已更新为: " + (sleeping ? "工作" : "休眠"));
            }
            
            // ⭐ 如果是从休眠恢复工作，启动倒计时（不设置"暂无视频"，直接显示倒计时）
            if (isWakingUp) {
                FileToos.videoStreamStatus = 0;  // 重置视频流状态
                startWakeCountdown();  // 直接显示"连接中 10"
            } else if (sleeping) {
                // ⭐ 进入休眠，先取消倒计时，再显示"休眠中"
                cancelWakeCountdown();
                setStatusSleeping();
            }
        });
        
        onSleep();  // 发送指令
        LogTools.getInstance().logRecord3("💤 休眠状态: " + (isSleeping ? "休眠中" : "工作中"));
    }
    
    /**
     * ⭐ 启动工作恢复倒计时（10秒）
     * 如果 10 秒内视频流到达，倒计时取消
     * 如果 10 秒后视频流未到达，自动触发刷新并重新开始倒计时
     */
    private void startWakeCountdown() {
        // 先取消之前的倒计时
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
        }
        
        countdownSeconds = 10;
        
        // ⭐ 显示倒计时（使用醒目样式）
        setStatusConnecting(countdownSeconds);
        
        LogTools.getInstance().logRecord3("⏱️ 开始工作恢复倒计时: " + countdownSeconds + "秒");
        
        // 创建倒计时定时器
        countdownTimeline = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.seconds(1),
                event -> {
                    // ⭐ 检查视频流是否已到达
                    if (FileToos.videoStreamStatus == 1) {
                        // 视频流已到达，取消倒计时，隐藏状态标签
                        if (statusLabel != null) {
                            statusLabel.setVisible(false);
                        }
                        LogTools.getInstance().logRecord3("⏱️ 视频流已到达，取消倒计时");
                        if (countdownTimeline != null) {
                            countdownTimeline.stop();
                            countdownTimeline = null;
                        }
                        return;
                    }
                    
                    countdownSeconds--;
                    if (countdownSeconds > 0) {
                        // ⭐ 更新倒计时显示
                        setStatusConnecting(countdownSeconds);
                    } else {
                        // 倒计时结束，触发刷新并重新开始倒计时
                        LogTools.getInstance().logRecord3("⏱️ 倒计时结束，视频流未到达，自动触发刷新并重新倒计时");
                        sendRefreshCommand();  // ⭐ 只发送指令，不重新启动倒计时
                        // ⭐ 重新开始倒计时
                        countdownSeconds = 10;
                        setStatusConnecting(countdownSeconds);
                    }
                }
            )
        );
        countdownTimeline.setCycleCount(javafx.animation.Animation.INDEFINITE);  // ⭐ 无限循环，直到视频流到达
        countdownTimeline.play();
    }
    
    /**
     * ⭐ 取消工作恢复倒计时（视频流到达时调用）
     */
    public void cancelWakeCountdown() {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
            LogTools.getInstance().logRecord3("⏱️ 倒计时已取消（视频流已到达）");
        }
        // ⭐ 隐藏状态标签（视频流到达后会被视频覆盖）
        if (statusLabel != null) {
            javafx.application.Platform.runLater(() -> {
                statusLabel.setVisible(false);
            });
        }
    }
    
    private ComboBox<String> createQualityComboBox() {
        ComboBox<String> comboBox = new ComboBox<>();

        // 添加简化的选项（只显示画质名称）
        comboBox.getItems().addAll("4K", "超清", "高清", "标清");

        // 设置当前选中项
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}

        if (cfg != null) {
            String type = cfg.getType();
            String displayText = "";

            switch (type.toLowerCase()) {
                case "4k": 
                case "p4k": displayText = "4K"; break;
                case "ultra": displayText = "超清"; break;
                case "high": displayText = "高清"; break;
                case "standard": displayText = "标清"; break;
                default: displayText = "高清";
            }

            comboBox.setValue(displayText);
        } else {
            // 默认选中高清
            comboBox.setValue("高清");
        }

        // ⭐ 深色主题样式
        comboBox.setStyle(
                "-fx-background-color: #292929; " +
                "-fx-padding: 0 12; " +
                "-fx-background-radius: 8; " +
                "-fx-font-size: 12px; " +
                "-fx-cursor: hand;"
        );
        comboBox.getStyleClass().add("dark-combo-box");
        
        // ⭐ 设置 ButtonCell 确保选中值显示区域背景色一致
        comboBox.setButtonCell(new javafx.scene.control.ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(item);
                setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-font-size: 12px;");
            }
        });

        // 设置事件处理
        comboBox.setOnAction(e -> onQualityComboBoxChanged(comboBox.getValue()));
        
        // ⭐ 添加滚轮支持：滚动切换画质选项
        comboBox.setOnScroll(e -> {
            int currentIndex = comboBox.getSelectionModel().getSelectedIndex();
            int itemCount = comboBox.getItems().size();
            
            if (e.getDeltaY() > 0) {
                // 向上滚动，选择上一个
                int newIndex = (currentIndex - 1 + itemCount) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            } else if (e.getDeltaY() < 0) {
                // 向下滚动，选择下一个
                int newIndex = (currentIndex + 1) % itemCount;
                comboBox.getSelectionModel().select(newIndex);
            }
            e.consume();
        });

        comboBox.setPrefHeight(32);
        comboBox.setMinHeight(32);
        comboBox.setMaxHeight(32);
        // 设置提示
        comboBox.setTooltip(createFastTooltip("选择画质（滚轮切换）"));

        updateQualityComboBoxDisplay();

        return comboBox;
    }



    private void onQualityComboBoxChanged(String selectedQuality) {
        System.out.println("画质下拉框选择: " + selectedQuality);

        // ✅ 检查是否收到过 CONFIG_STATE 消息
        if (CameraMainController.lastConfigStateTime == 0) {
            System.err.println("⚠️ 尚未收到 CONFIG_STATE 消息，无法切换画质");
            updateQualityComboBoxDisplay();  // 恢复显示
            return;
        }

        // ✅ 检查画质权限
        if (!isQualityAccessible(selectedQuality)) {
            System.err.println("⚠️ 当前会员等级不支持该画质: " + selectedQuality);
            updateQualityComboBoxDisplay();  // 恢复显示
            // 可选：弹出提示
            showQualityAccessDeniedTip(selectedQuality);
            return;
        }

        // 根据选择的画质设置对应的type值
        String newType = "";
        switch (selectedQuality) {
            case "4K":
                newType = "p4k";
                break;
            case "超清":
                newType = "ultra";
                break;
            case "高清":
                newType = "high";
                break;
            case "标清":
                newType = "standard";
                break;
            default:
                newType = "high";
        }

        // 调用现有的画质更新逻辑
        updateQualityType(newType);
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

    /**
     * 通过快捷键设置画质（F1-F4对应不同画质档位）
     * @param qualityIndex 画质索引：0=4K, 1=超清, 2=高清, 3=标清
     */
    public void setQualityByShortcut(int qualityIndex) {
        if (qualityComboBox == null) {
            System.err.println("画质下拉框未初始化");
            return;
        }

        // ✅ 检查是否收到过 CONFIG_STATE 消息
        if (CameraMainController.lastConfigStateTime == 0) {
            System.err.println("⚠️ 尚未收到 CONFIG_STATE 消息，无法切换画质");
            return;
        }

        // 前后置摄像头都支持4个档位
        String selectedQuality = "";
        switch (qualityIndex) {
            case 0: selectedQuality = "4K"; break;
            case 1: selectedQuality = "超清"; break;
            case 2: selectedQuality = "高清"; break;
            case 3: selectedQuality = "标清"; break;
            default: selectedQuality = "高清";
        }

        // ✅ 检查画质权限
        if (!isQualityAccessible(selectedQuality)) {
            System.err.println("⚠️ 当前会员等级不支持该画质: " + selectedQuality);
            showQualityAccessDeniedTip(selectedQuality);
            return;
        }

        // 设置下拉框选中项并触发事件
        qualityComboBox.setValue(selectedQuality);
        onQualityComboBoxChanged(selectedQuality);
        System.out.println("快捷键设置画质: F" + (qualityIndex + 1) + " -> " + selectedQuality);
    }


    private void updateQualityType(String newType) {
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[GpuView] 无法更新画质：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            ThinRemoteConfig payload = ConfigStore.getInstance().getThinConfig();
            if (payload == null) {
                System.err.println("[GpuView] 无法更新画质：配置为空");
                return;
            }
            payload.setDeviceId(deviceId);
            payload.setType(newType);
            payload.setPtype("type");
            String finalType = newType;
            ConfigStore.getInstance().setThinConfig(payload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.CameraType);
            updateQualityComboBoxDisplay();
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                               // com.acard.acard.storage.ConfigStore.getInstance().setThinConfig(body.getData());
                                System.out.println("[GpuView] 画质已更新为 " + finalType);
                                // 更新下拉框显示
                                //updateQualityComboBoxDisplay();
                            } else {
                                String msg = body != null ? body.getMessage() : ("HTTP错误: " + apiResp.getCode() + " " + apiResp.getMessage());
                                System.err.println("[GpuView] 更新画质失败: " + msg);
                            }
                        } else {
                            System.err.println("[GpuView] 更新画质失败: " + (apiResp != null ? apiResp.getMessage() : "响应为空"));
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[GpuView] 更新画质异常: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[GpuView] 更新画质异常: " + e.getMessage());
        }
    }

    private void updateQualityComboBoxDisplay() {
        if (qualityComboBox == null) return;

        Platform.runLater(() -> {
            try {
                ThinRemoteConfig cfg = ConfigStore.getInstance().getThinConfig();
                if (cfg != null) {
                    String type = cfg.getType();
                    String displayText = "";

                    switch (type.toLowerCase()) {
                        case "4k":
                        case "p4k": displayText = "4K"; break;
                        case "ultra": displayText = "超清"; break;
                        case "high": displayText = "高清"; break;
                        case "standard": displayText = "标清"; break;
                        default: displayText = "高清";
                    }
                    qualityComboBox.setValue(displayText);

                }
            } catch (Throwable e) {
                System.err.println("更新画质下拉框显示失败: " + e.getMessage());
            }
        });
    }


    private void pushDirectionUpdate() {
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        Integer dir = Integer.valueOf(cfg.getDirection());
        int direction = (dir == -1 ? 1 : -1);

        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            String updatedBy = resp != null && resp.getUsername() != null ? resp.getUsername() : "unknown";
            if (deviceId == null || deviceId.isBlank()) {
                System.err.println("[CameraSettings] 无法更新相机方向：deviceId 为空");
                return;
            }
            String endpoint = "/api/thin-config/" + deviceId + "?updatedBy=" + URLEncoder.encode(updatedBy, StandardCharsets.UTF_8);
            
            // ✅ 第一步：先发送方向切换
            ThinRemoteConfig dirPayload = ConfigStore.getInstance().getThinConfig();
            if (dirPayload == null) {
                System.err.println("[CameraSettings] 无法更新相机方向：配置为空");
                return;
            }
            dirPayload.setDeviceId(deviceId);
            dirPayload.setDirection(String.valueOf(direction));
            dirPayload.setPtype("direction");
            ConfigStore.getInstance().setThinConfig(dirPayload);
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(dirPayload, FileToos.CameraType);
            updateConfigState();

            NetworkManager.getInstance()
                    .put(endpoint, dirPayload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {
                            ThinConfigResponse body = apiResp.getData();
                            if (body != null && body.isSuccess() && body.getData() != null) {
                                System.out.println("[CameraSettings] 相机方向已切换为 " + direction);
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

    private void rotateLocalCanvas() {

        if( player!=null){

            player.setVideoRotation(FileToos.derection);
            if(FileToos.derection==0){
                FileToos.derection=3;
            }
            FileToos.derection=FileToos.derection+1;
            if(FileToos.derection>7){
                FileToos.derection=0;
            }
        }
    }

    /**
     * 切换画质（高清/标清）
     */
    private void pushTypeUpdate() {
        ThinRemoteConfig cfg = null;
        try {
            cfg = ConfigStore.getInstance().getThinConfig();
        } catch (Throwable ignore) {}
        if (cfg == null) return;

        String type1 = cfg.getType();
        String type = "";
        if (type1.equals("high")) {
            type = "standard";
        } else {
            type = "high";
        }

        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
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
            StompWebSocketClient.getInstance().notifyDeviceConfigUpdate(payload,FileToos.CameraType);
            updateConfigState();
            String finalType = type;
            NetworkManager.getInstance()
                    .put(endpoint, payload, ThinConfigResponse.class)
                    .thenAccept(apiResp -> {
                        if (apiResp != null && apiResp.isSuccess()) {

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

    private void updateRealtimeButtonStates() {
        Platform.runLater(() -> {
            try {
                ThinRemoteConfig cfg = ConfigStore.getInstance().getThinConfig();
                if (cfg == null) {
                    System.out.println("⚠️ 配置为空，无法更新按钮状态");
                    return;
                }

                // ⭐ 图标按钮不需要更新文本，只需要更新画质下拉框
                updateQualityComboBoxDisplay();

                System.out.println("✅ 实时流按钮状态更新完成");
            } catch (Throwable e) {
                System.err.println("⚠️ 更新按钮状态失败: " + e.getMessage());
            }
        });
    }


    public void updateSpeed(){
        updateRealtimeButtonStates();
    }


    // 1. 在类的字段声明区域添加（大约在第 73 行附近）
    private UIUpdateEventManager eventManager;
    private String listenerId;
    // 2. 在 initialize 方法的末尾添加事件注册初始化
    private void initializeEventListeners() {
        eventManager = UIUpdateEventManager.getInstance();
        this.listenerId = "GpuView_Speed" + System.currentTimeMillis();
        registerUIUpdateEvents();
    }
    // 4. 添加事件处理方法
    private void handleUIUpdateEvent(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {
                switch (event.getEventType()) {
                    case SPEED_KEY:
                        updateSpeed();
                        break;
                    default:
                        System.out.println("Element2_3Controller: 收到未处理的事件类型: " + event.getEventType());
                        break;
                }
            } catch (Exception e) {
                System.err.println("Element2_3Controller: 处理UI更新事件时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    // 3. 添加事件注册方法
    private void registerUIUpdateEvents() {
        if (eventManager != null) {
            // 注册 SPEED_KEY 事件监听器
            eventManager.registerListener(UIUpdateEvent.EventType.SPEED_KEY,
                    this::handleUIUpdateEvent, listenerId + "_speed");
            
            // ✅ 注册 GpuViewCameraEvent 事件监听器
            eventManager.registerListener(UIUpdateEvent.EventType.GpuViewCameraEvent,
                    this::handleGpuViewCameraEvent, listenerId + "_camera");
            
            System.out.println("GpuView: UI更新事件监听器已注册 (SPEED_KEY + GpuViewCameraEvent)");
        }
    }

    // 6. 添加事件注销方法（在类的末尾）
    private void unregisterUIUpdateEvents() {
        if (eventManager != null) {
            eventManager.unregisterListener(UIUpdateEvent.EventType.SPEED_KEY, listenerId + "_speed");
            eventManager.unregisterListener(UIUpdateEvent.EventType.GpuViewCameraEvent, listenerId + "_camera");
            System.out.println("GpuView: UI更新事件监听器已注销 (SPEED_KEY + GpuViewCameraEvent)");
        }
    }
    
    /**
     * ✅ 处理 GpuViewCameraEvent 事件
     * 当相机配置更新时，调用 updateConfigState() 刷新 UI
     */
    private void handleGpuViewCameraEvent(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {
                System.out.println("📡 GpuView 收到 GpuViewCameraEvent 事件，刷新配置状态");
                updateConfigState();
            } catch (Exception e) {
                System.err.println("❌ GpuView 处理 GpuViewCameraEvent 事件失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }


    /**
     * ⭐ 创建快速显示的 Tooltip（解决默认 1 秒延迟问题）
     */
    private Tooltip createFastTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(javafx.util.Duration.millis(10));    // 10ms 后显示（默认 1000ms）
        tooltip.setShowDuration(javafx.util.Duration.seconds(10)); // 显示 10 秒
        tooltip.setHideDelay(javafx.util.Duration.millis(50));     // 50ms 后隐藏
        return tooltip;
    }

    /**
     * 创建带样式的按钮
     */
    private Button createStyledButton(String text, String style, String tooltipText) {
        Button button = new Button(text);
        button.setStyle(style);
        button.setTooltip(createFastTooltip(tooltipText));
        button.setFocusTraversable(false);
        return button;
    }

    /**
     * 创建带图标的按钮（使用本地图片）
     */
    private Button createIconButton(String iconPath, String style, String tooltipText, int iconSize) {
        Button button = new Button();
        
        try {
            // 从resources加载图标
            java.io.InputStream iconStream = getClass().getClassLoader().getResourceAsStream(iconPath);
            if (iconStream != null) {
                // ⭐ 强制指定图标加载尺寸
                Image icon = new Image(
                    iconStream, 
                    iconSize, iconSize, // 请求的宽高
                    true,  // preserveRatio
                    true   // smooth
                );
                javafx.scene.image.ImageView iconView = new javafx.scene.image.ImageView(icon);
                
                // ⭐ 再次强制设置显示尺寸（确保一致）
                iconView.setFitWidth(iconSize);
                iconView.setFitHeight(iconSize);
                iconView.setPreserveRatio(false); // ⭐ 关闭比例保持，强制为正方形
                iconView.setSmooth(true); // 平滑缩放
                
                // ⭐ 调试输出
                System.out.println("图标加载: " + iconPath + " -> " + iconSize + "x" + iconSize + 
                    " (原始: " + icon.getWidth() + "x" + icon.getHeight() + ")");
                
                button.setGraphic(iconView);
            } else {
                // 图标加载失败，使用文字备用
                System.err.println("⚠️ 无法加载图标: " + iconPath);
                button.setText("?");
            }
        } catch (Exception e) {
            System.err.println("⚠️ 加载图标异常: " + iconPath + ", " + e.getMessage());
            button.setText("?");
        }
        
        button.setStyle(style);
        button.setTooltip(createFastTooltip(tooltipText));
        button.setFocusTraversable(false);
        return button;
    }

    // ========== 快捷按钮事件处理方法（留空实现） ==========

    /**
     * 快捷按钮：切换相机
     */
    public void onShortcutSwitchCamera() {
        // TODO: 实现切换相机功能
        System.out.println("快捷按钮：切换相机");
        pushDirectionUpdate();
    }

    /**
     * 快捷按钮：旋转相机
     */
    // ⭐ 已删除 onShortcutRotateCamera()，功能已合并到 createRotateFlipComboBox()

    /**
     * 快捷按钮：画质切换
     */
    private void onShortcutQualityToggle() {
        // TODO: 实现画质切换功能
        System.out.println("快捷按钮：画质切换");
        toggleQuality();
    }

    /**
     * 快捷按钮：设置
     */
    private void onShortcutSettings() {
        // TODO: 实现设置功能
        System.out.println("快捷按钮：设置");
        openCameraSettings();
    }

    /**
     * 更新快捷按钮的状态显示（参考Element2_3Controller的updateRealtimeButtonStates方法）
     */
    public void updateShortcutButtonStates() {
        Platform.runLater(() -> {
            try {
                ThinRemoteConfig cfg = ConfigStore.getInstance().getThinConfig();
                if (cfg == null) {
                    System.out.println("⚠️ 配置为空，无法更新快捷按钮状态");
                    return;
                }

                System.out.println("🔄 开始更新快捷按钮状态:");
                System.out.println("   - 当前配置: 方向=" + cfg.getDirection() + ", 角度=" + cfg.getAngle() + ", 画质=" + cfg.getType());

                // ⭐ 图标按钮不需要更新文本，只需要更新画质下拉框
                updateQualityComboBoxDisplay();

                System.out.println("✅ 快捷按钮状态更新完成");
            } catch (Throwable e) {
                System.err.println("⚠️ 更新快捷按钮状态失败: " + e.getMessage());
            }
        });
    }

    private Button makeTextButton(String text, String tooltipText) {
        Button b = new Button(text);
        b.setTooltip(createFastTooltip(tooltipText));
        b.setFocusTraversable(false);
        return b;
    }


    public void openCameraSettings() {
        try {
            Platform.runLater(() -> {
                Stage stage = null;
                try {
                    // 尝试从按钮获取 Stage
                   /* if (btnShortcutSettings != null && btnShortcutSettings.getScene() != null && btnShortcutSettings.getScene().getWindow() instanceof javafx.stage.Stage) {
                        stage = (javafx.stage.Stage) btnShortcutSettings.getScene().getWindow();
                    }*/
                    stage = (Stage) GpuView.this.getScene().getWindow();
                    if(stage!=null) {
                        CameraSettingsDialogController.showDialogWithoutFXML(stage);
                    }
                } catch (Throwable t) {
                    System.err.println("打开相机设定窗口失败: " + t.getMessage());
                    t.printStackTrace();
                }
            });
        } catch (Throwable ignore) {}
    }

    private void toggleQuality() {
        // 与具体画质切换策略对接由上层控制器实现，这里仅提供占位按钮行为。
        // 可通过 onOpenSettings 或外部绑定的控制器来执行高清/标清切换。
        pushTypeUpdate();

    }

    private void updateMetricsVisibility() {

    }

    /** 更新顶部指标文本；是否显示由 FPS/Kbps 按钮状态控制 */
    public void setMetricsText(String text) {


    }

    /** 设置指标显隐（覆盖按钮状态） */


    private void pushFrameToRealtimeBuffer(Image image) {
        // 优化：如果缓冲未启用，直接返回，避免任何处理
        if (!ENABLE_REALTIME_BUFFER || image == null || realtimePushExecutor == null) {
            return;
        }
        
        long now = System.currentTimeMillis();
        if (now - lastBufferedMs < MIN_PUSH_INTERVAL_MS) { /* 不节流 */ }
        lastBufferedMs = now;
        
        realtimePushExecutor.execute(() -> {
            try {
                BufferedImage bi = SwingFXUtils.fromFXImage(image, null);
                if (bi != null) {
                    // 推入内存环形缓冲，保留最近帧用于实时/慢放窗口
                    if (rawRealtimeBuffer != null) {
                        try { rawRealtimeBuffer.push(bi); } catch (Throwable ignore) {}
                    }
                    // 同步落盘（Zstd 压缩文件环），兼容慢放窗口与磁盘快照
                    if (zstdStore != null) {
                        try { zstdStore.appendFrame(bi, System.currentTimeMillis()); } catch (Throwable ignore) {}
                    }
                    // 轻量打点：累计与节流日志
                    long cnt = ++realtimePushedCount;
                    realtimeLastPushMs = System.currentTimeMillis();
                    if (cnt % REALTIME_LOG_EVERY == 0) {
                        int qlen = 0;
                        try { qlen = realtimePushExecutor.getQueue() != null ? realtimePushExecutor.getQueue().size() : 0; } catch (Throwable ignore) {}
                        System.out.println("GPUView: 📈 realtime pushed=" + cnt + ", queue.pending=" + qlen + ", lastMs=" + realtimeLastPushMs);
                    }
                }
            } catch (Throwable t) {
                // 忽略单帧异常，保持流畅
            }
        });
    }

    /** 获取实时缓冲的快照（内存：按需编码为JPEG以兼容JavaFX） */
    public List<FrameRingBuffer.FrameItem> getRealtimeSnapshot() {
        if (!ENABLE_REALTIME_BUFFER || rawRealtimeBuffer == null) {
            return java.util.Collections.emptyList();
        }
        List<RawFrameRingBuffer.FrameItem> raw = rawRealtimeBuffer.snapshot();
        java.util.ArrayList<FrameRingBuffer.FrameItem> out = new java.util.ArrayList<>(raw.size());
        for (RawFrameRingBuffer.FrameItem r : raw) {
            byte[] bytes = encodeToJpeg(r.image, 0.50f);
            out.add(new FrameRingBuffer.FrameItem(bytes, r.timestamp));
        }
        return out;
    }

    /** 获取实时缓冲的磁盘快照（时间顺序，返回磁盘文件路径与时间戳）。
     * 兼容旧接口：从 Chronicle+Zstd 实时存储中提取最近少量帧，写入临时 PNG 并返回列表。
     */
    public List<DiskFrameRingBuffer.FrameItem> getRealtimeDiskSnapshot() {
        if (!ENABLE_REALTIME_BUFFER || zstdStore == null) {
            return java.util.Collections.emptyList();
        }
        try {
            // 从 Zstd 压缩文件环读取最近少量帧，落盘为 PNG 快照并返回
            List<BufferedImage> imgs = zstdStore.getLastNImages(3);
            if (imgs == null || imgs.isEmpty()) return java.util.Collections.emptyList();
            java.nio.file.Path outDir = Paths.get(System.getProperty("java.io.tmpdir"), "acard", "realtime-snapshot");
            try { java.nio.file.Files.createDirectories(outDir); } catch (Throwable ignore) {}
            java.util.ArrayList<DiskFrameRingBuffer.FrameItem> list = new java.util.ArrayList<>(imgs.size());
            int seq = 0;
            long baseTs = System.currentTimeMillis();
            for (BufferedImage img : imgs) {
                if (img == null) continue;
                String name = String.format("realtime_%d_%02d.png", baseTs, seq++);
                java.nio.file.Path file = outDir.resolve(name);
                try {
                    javax.imageio.ImageIO.write(img, "PNG", file.toFile());
                    list.add(new DiskFrameRingBuffer.FrameItem(file, baseTs));
                } catch (Throwable ignore) {
                    // 单帧落盘失败则跳过
                }
            }
            return list;
        } catch (Throwable ignore) {
            return java.util.Collections.emptyList();
        }
    }

    // 打印当前存储帧序号与 CPU/内存状态（每帧调用） - 优化：禁用日志以降低 CPU
    private static void logStoreStats(long frameIndex) {
        // 优化：完全禁用日志，避免 CPU 开销
        if (!Boolean.parseBoolean(System.getProperty("gpuview.log.stats", "false"))) {
            return;
        }
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double procLoad = os.getProcessCpuLoad(); // 0.0~1.0，-1 表示不可用
            double sysLoad = os.getSystemCpuLoad();   // 0.0~1.0
            long totalPhys = os.getTotalPhysicalMemorySize();
            long freePhys = os.getFreePhysicalMemorySize();
            long usedPhys = Math.max(0, totalPhys - freePhys);
            Runtime rt = Runtime.getRuntime();
            long heapUsed = rt.totalMemory() - rt.freeMemory();
            long heapTotal = rt.totalMemory();
            String msg = String.format(
                    "GPUView: 💾 stored frame=%d | CPU(process=%.1f%%, system=%.1f%%) | Mem(phys=%d/%d MB, heap=%d/%d MB)",
                    frameIndex,
                    procLoad < 0 ? 0.0 : procLoad * 100.0,
                    sysLoad < 0 ? 0.0 : sysLoad * 100.0,
                    usedPhys / (1024 * 1024), totalPhys / (1024 * 1024),
                    heapUsed / (1024 * 1024), heapTotal / (1024 * 1024)
            );
            System.out.println(msg);
            appendLogLine(msg);
        } catch (Throwable ignore) {
            // 指标不可用时忽略
        }
    }

    // 将日志追加到 runtime/logs/realtime-store.log（同时打印到控制台）
    private static void appendLogLine(String line) {
        try {
            java.nio.file.Path logDir = Paths.get(System.getProperty("user.dir"), "runtime", "logs");
            java.nio.file.Files.createDirectories(logDir);
            java.nio.file.Path logFile = logDir.resolve("realtime-store.log");
            byte[] data = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            java.nio.file.Files.write(logFile, data,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignore) {
            // 文件不可写时忽略（仍有控制台输出）
        }
    }

    /**
     * 抓取实时滑窗窗口：以当前时刻为事件点，等待后向帧补齐到 postCount 或超时。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindow(int preCount, int postCount, long waitTimeoutMs) {
        if (!ENABLE_REALTIME_BUFFER || rawRealtimeBuffer == null) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);

        // 初始快照与事件点：当前最后一帧
        List<RawFrameRingBuffer.FrameItem> rawSnap = rawRealtimeBuffer.snapshot();
        int eventIndex = Math.max(0, rawSnap.size() - 1);
        int needEnd = eventIndex + safePost;

        // 等待后向帧到位（加入简单退避与活跃检测，减少无效轮询）
        while (System.currentTimeMillis() < deadline) {
            if (rawSnap.size() > needEnd) break;
            long missing = needEnd - rawSnap.size();
            // 缩短等待睡眠，加快窗口补齐速度
            long sleepMs = missing > 3 ? 40L : 30L;
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignore) {}
            List<RawFrameRingBuffer.FrameItem> newSnap = rawRealtimeBuffer.snapshot();
            if (newSnap.size() == rawSnap.size()) {
                // 若没有增长且流不再活跃，则提前结束等待
                if (!isRealtimeActive(800)) { rawSnap = newSnap; break; }
            }
            rawSnap = newSnap;
        }

        if (rawSnap.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = rawSnap.size();
        int start = Math.max(0, Math.min(eventIndex - safePre, size - 1));
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            RawFrameRingBuffer.FrameItem r = rawSnap.get(i);
            byte[] bytes = encodeToJpeg(r.image, 0.50f);
            window.add(new FrameRingBuffer.FrameItem(bytes, r.timestamp));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        boolean timedOut = rawSnap.size() <= needEnd && (System.currentTimeMillis() >= deadline || !isRealtimeActive(800));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, timedOut);
    }

    /** 抓取实时滑窗窗口（磁盘）：以当前时刻为事件点，等待后向帧补齐到 postCount 或超时。 */
    public SnapshotWindowCollector.SnapshotWindowResult<DiskFrameRingBuffer.FrameItem> collectRealtimeDiskWindow(int preCount, int postCount, long waitTimeoutMs) {
        if (!ENABLE_REALTIME_BUFFER) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);

        List<DiskFrameRingBuffer.FrameItem> snap = getRealtimeDiskSnapshot();
        int eventIndex = Math.max(0, snap.size() - 1);
        int needEnd = eventIndex + safePost;
        while (System.currentTimeMillis() < deadline) {
            if (snap.size() > needEnd) break;
            long missing = needEnd - snap.size();
            long sleepMs = missing > 3 ? 40L : 30L;
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignore) {}
            List<DiskFrameRingBuffer.FrameItem> newSnap = getRealtimeDiskSnapshot();
            if (newSnap.size() == snap.size()) {
                if (!isRealtimeActive(800)) { snap = newSnap; break; }
            }
            snap = newSnap;
        }
        if (snap.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snap.size();
        int start = Math.max(0, Math.min(eventIndex - safePre, size - 1));
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        List<DiskFrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) { window.add(snap.get(i)); }
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        boolean timedOut = snap.size() <= needEnd && (System.currentTimeMillis() >= deadline || !isRealtimeActive(800));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, timedOut);
    }

    /**
     * 抓取实时滑窗窗口（锚定事件索引）：以指定的绝对事件索引为中心，返回 [event-pre, event+post] 范围内的窗口。
     * 不等待，仅基于当前 buffer 快照构造窗口；用于后台增量刷新。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindowAnchored(int eventAbsIndex, int preCount, int postCount) {
        if (!ENABLE_REALTIME_BUFFER || rawRealtimeBuffer == null) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<RawFrameRingBuffer.FrameItem> snapshot = rawRealtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex = Math.max(0, Math.min(eventAbsIndex, size - 1));
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            RawFrameRingBuffer.FrameItem r = snapshot.get(i);
            byte[] bytes = encodeToJpeg(r.image, 0.50f);
            window.add(new FrameRingBuffer.FrameItem(bytes, r.timestamp));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    /**
     * 抓取实时滑窗（按时间戳锚定）：将事件点固定为 anchorTs 对应的帧，窗口为 [event-pre, event+post]。
     * 在快照中查找 <= anchorTs 的最后一帧实现锚定，避免事件点随"最新帧"漂移。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindowAnchoredTs(long anchorTs, int preCount, int postCount) {
        if (!ENABLE_REALTIME_BUFFER || rawRealtimeBuffer == null) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<RawFrameRingBuffer.FrameItem> snapshot = rawRealtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex;
        if (anchorTs <= snapshot.get(0).timestamp) {
            eventIndex = 0;
        } else if (anchorTs >= snapshot.get(size - 1).timestamp) {
            eventIndex = size - 1;
        } else {
            int idx = 0;
            for (int i = size - 1; i >= 0; i--) {
                if (snapshot.get(i).timestamp <= anchorTs) { idx = i; break; }
            }
            eventIndex = idx;
        }
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            RawFrameRingBuffer.FrameItem r = snapshot.get(i);
            byte[] bytes = encodeToJpeg(r.image, 0.50f);
            window.add(new FrameRingBuffer.FrameItem(bytes, r.timestamp));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    /** 抓取实时滑窗（磁盘，按时间戳锚定） */
    public SnapshotWindowCollector.SnapshotWindowResult<DiskFrameRingBuffer.FrameItem> collectRealtimeDiskWindowAnchoredTs(long anchorTs, int preCount, int postCount) {
        if (!ENABLE_REALTIME_BUFFER) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<DiskFrameRingBuffer.FrameItem> snapshot = getRealtimeDiskSnapshot();
        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex;
        if (anchorTs <= snapshot.get(0).timestamp) {
            eventIndex = 0;
        } else if (anchorTs >= snapshot.get(size - 1).timestamp) {
            eventIndex = size - 1;
        } else {
            int idx = 0;
            for (int i = size - 1; i >= 0; i--) {
                if (snapshot.get(i).timestamp <= anchorTs) { idx = i; break; }
            }
            eventIndex = idx;
        }
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<DiskFrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) { window.add(snapshot.get(i)); }
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    /**
     * 判断实时流是否活跃：最近一次帧推送时间在阈值内且缓冲中至少有一帧。
     */
    public boolean isRealtimeActive() {
        return isRealtimeActive(2000);
    }

    /**
     * 判断实时流是否活跃（可配置阈值，毫秒）。
     */
    public boolean isRealtimeActive(long recentMsThreshold) {
        if (!ENABLE_REALTIME_BUFFER || rawRealtimeBuffer == null) {
            return false;
        }
        long threshold = (recentMsThreshold <= 0) ? 2000 : recentMsThreshold;
        long last = realtimeLastPushMs;
        int size = 0;
        try { size = rawRealtimeBuffer.size(); } catch (Throwable ignore) {}
        long now = System.currentTimeMillis();
        return size > 0 && (now - last) < threshold;
    }

    // ---------------- 编码工具：按需将原始帧编码为 JPEG（JavaFX 可直接解码） ----------------
    private static byte[] encodeToJpeg(BufferedImage img, float quality) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64 * 1024);
            javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.min(1.0f, Math.max(0.1f, quality)));
            try (javax.imageio.stream.MemoryCacheImageOutputStream out = new javax.imageio.stream.MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(out);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
    
    /**
     * ⚡ 编码 JPEG 并应用缩放裁剪（抓拍用）
     * 如果当前处于放大状态，则只截取放大区域
     */
    private byte[] encodeToJpegWithZoom(BufferedImage img, float quality) {
        if (img == null) return new byte[0];
        
        // ⚡ 应用缩放裁剪（如果有放大）
        BufferedImage processed = img;
        if (player != null && player.getCurrentZoom() > 1.0) {
            try {

                if (processed != null && processed != img) {
                    System.out.println("✅ 抓拍应用缩放裁剪: " + img.getWidth() + "x" + img.getHeight() + 
                        " → " + processed.getWidth() + "x" + processed.getHeight());
                }
            } catch (Exception e) {
                System.err.println("⚠️ 应用缩放裁剪失败: " + e.getMessage());
                processed = img; // 失败时使用原图
            }
        }
        
        return encodeToJpeg(processed != null ? processed : img, quality);
    }

    // 在GpuView.java中添加：
    public void applyRotationFromShortcut(int rotationIndex) {
        try {
            // 直接设置旋转值，不需要递增
            if (player != null) {
                player.setVideoRotation(rotationIndex);
                FileToos.derection=FileToos.derection+1;
                if(FileToos.derection>7){
                    FileToos.derection=0;
                }
            }
            // 更新按钮文字
            updateShortcutButtonStates();

            System.out.println("应用快捷键旋转: " + rotationIndex + " (" +
                    FileToos.getVideoDirectionText(rotationIndex) + ")");
        } catch (Exception e) {
            System.err.println("应用快捷键旋转失败: " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        unregisterUIUpdateEvents();
        // ⭐ 关闭按钮悬浮窗口

    }
}