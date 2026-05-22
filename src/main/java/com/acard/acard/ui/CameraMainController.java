package com.acard.acard.ui;

import com.acard.acard.*;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.net.NetworkConfig;
import com.acard.acard.net.NetworkManager;
import com.acard.acard.net.StompWebSocketClient;
import com.acard.acard.net.LoginResponse;
import com.acard.acard.net.HttpClientManager;
import com.acard.acard.net.ApiResponse;
import com.acard.acard.net.QRCodeBindingResponse;
import com.acard.acard.net.PendingBindingResponse;
import com.acard.acard.net.BindingInfo;
import com.acard.acard.net.BindingVerifyRequest;
import com.acard.acard.net.BindingVerifyResponse;
import com.acard.acard.net.ManualBindResponse;
import com.acard.acard.storage.AuthStore;
import com.acard.acard.storage.LocalStorage;
import com.acard.acard.store.GridStore;
import com.acard.acard.store.CaptureStore;
import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import com.acard.acard.ui.dialog.QRCodeDialog;
import com.acard.acard.ui.dialog.DeviceBindingDialog;
import com.acard.acard.utils.DirectoryUtils;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ResourceBundle;

import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Circle;
import javafx.scene.Group;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import com.acard.acard.storage.ConfigStore;
import com.acard.acard.model.ThinRemoteConfig;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.acard.acard.SimpleWebRTCPlayer;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * 相机主界面控制器
 * 负责处理菜单项点击、窗口控制等用户交互
 */
public class CameraMainController implements Initializable {

    // FXML注入的UI组件
    @FXML private MenuButton windowMenuButton;
    @FXML private MenuItem fullScreenMenuItem;
    @FXML private MenuItem realTimeWindowMenuItem;
    @FXML private MenuItem slowMotionWindowMenuItem;
    @FXML private Button deviceBindButton;
    @FXML private Button cameraSettingsButton;
    @FXML private Button shortcutKeysButton;
    @FXML private Button aiFunctionButton;  // ⭐ 新增 AI功能按钮

    @FXML private Button arrangementModeButton;  // ⭐ 横向排列按钮（替代CheckBox）
    @FXML private Label arrangementModeLabel;    // ⭐ 横向排列文字标签
    
    @FXML private Button btnFullScreenMode;  // 🔥 全屏模式开关按钮
    @FXML private Label fullScreenModeLabel; // ⭐ 全屏模式文字标签
    
    // ⭐ 新增行列前后下拉菜单
    @FXML private MenuButton rowMenuButton;
    @FXML private MenuButton colMenuButton;
    @FXML private MenuButton frontMenuButton;
    @FXML private MenuButton backMenuButton;
    @FXML private MenuButton offsetMenuButton;  // ⭐ 偏移下拉
    @FXML private Label rowLabel;
    @FXML private Label colLabel;
    @FXML private Label frontLabel;
    @FXML private Label backLabel;
    @FXML private Label offsetLabel;  // ⭐ 偏移标签
    
    // ⭐ 横向排列状态
    private boolean isHorizontalArrangement = true;


    
    @FXML private Button avatarButton;
    @FXML private Button minimizeButton;
    //@FXML private Button maximizeButton;
    @FXML private Button closeButton;
    
    @FXML private javafx.scene.layout.HBox titleBar;
    @FXML private Region dragRegion;  // 添加拖动区域的引用
    @FXML private SplitPane mainContentArea;

    
    // ✅ sessionId → itemIndex 映射（用于事件驱动的后续帧追加）
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> sessionIdToItemIndex = new java.util.concurrent.ConcurrentHashMap<>();
    
    // 设备状态栏标签
    @FXML private Label monitorDeviceValue;
    @FXML private Label monitorLinkStatusValue;

    @FXML private Label tsfpsValue;
    @FXML private Label kbpsValue;  // ✅ 新增 kb/s 显示

    @FXML private Label batteryValue;
    @FXML private Label networkTypeValue;
    @FXML private Label networkQualityValue;  // ✅ 新增 网络质量显示
    @FXML private Label controlDeviceValue;
    @FXML private Label controlLinkStatusValue;
    @FXML private Label bandwidthValue;
    @FXML private Label controlNetworkQualityValue;  // ✅ 新增 Windows端网络质量显示
    @FXML private Label scrollFrameLabel;  // ✅ 滚轮帧数显示
    @FXML private Label videoZoomLabel;  // ✅ 视频局部放大倍数显示
    @FXML private Button startSlowmoButton;  // ✅ 开启慢放按钮
    @FXML private Button clearSlowmoButton;  // ✅ 清空慢放按钮
    @FXML private Circle controlLinkStatusDot;
    @FXML private Label pushStatusValue;
    
    // 主内容区域的元素
    @FXML private StackPane element1;
    @FXML private SplitPane element2;  // 原 GridPane，现改为可拖动分割的 SplitPane
    @FXML private StackPane element2_1;
    @FXML private StackPane element2_2;
    @FXML private Label element1Label;
    @FXML private Label element2_1Label;
    @FXML private Label element2_2Label;

    private String fpsText="";
    private String tsfpsText="";
    // 视图模型
    private CameraMainViewModel viewModel;
    
    // 播放器视图（元素2-1）
    private GpuView element2_1Player;
    private SimpleWebRTCPlayer corePlayer;
    // 延迟启动标记，确保只启动一次
    private boolean corePlayerStarted = false;
    // ✅ 记录是否已经因推流而启动播放器（解决先进入后推流导致画面不出来的问题）
    private volatile boolean playTriggeredByPushStatus = false;
    private volatile int lastPublishStatus = 0;  // 记录上一次的推流状态
    private volatile boolean isPlayerPlaying = false;  // 记录播放器是否正在播放
    private int currentPublishStatus = 0;  // 当前推流状态（从消息解析）


    // 慢放视图（元素2-2）
    //private SlowMoView element2_2SlowView;  // 旧PNG播放（已废弃）
    //private PlayerControl slowPlayer;        // 旧PNG播放（已废弃）
    //private SlowMoPaneController slowPaneController;
    // ✅ 新增：GPU加速慢放播放器（完全独立，不影响实时流）

    // ⭐ 新增：JPEG 序列播放器控制器
    private Element2_2JpegController element2_2JpegController;


    private com.acard.acard.slowmotion.SlowMoGpuPlayer slowMoGpuPlayer;
    // 开启慢放后自动在达到容量(180张)时停止采集
    private final ScheduledExecutorService slowCaptureScheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "SlowAutoStop"); t.setDaemon(true); return t; });
    private ScheduledFuture<?> slowAutoStopFuture;


    // 元素1控制器（独立抓拍网格） - ⚡ 使用 V2 高效版本
    private Element1ControllerV2 element1Controller;
    
    // 元素2-3控制器
    private Element2_3Controller element2_3Controller;
    
    private boolean element2Collapsed = false;
    private double element2DividerBackup = 0.65;
    // ⭐ 标记是否是自动抓拍满格触发的全屏（用于清除时判断是否需要恢复）
    private boolean isAutoFullScreenByCapture = false;
    
    // 🔥 全屏模式开关（默认关闭）
    private boolean isFullScreenModeEnabled = false;
    
    // 快捷键管理器
    private ShortcutManager shortcutManager;
    
    private boolean element2FullScreenMode = false;
    private final double[] element2RowPercentsBackup = new double[3];
    
    // 窗口状态
    private boolean isMaximized = false;
    private boolean isFullScreen = false;  // 添加全屏状态标记
    private double originalWidth;
    private double originalHeight;
    private double originalX;
    private double originalY;
    private double xOffset = 0;
    private double yOffset = 0;

    // 最大化按钮图标
    private javafx.scene.Node iconMaximize;  // 单个正方形
    private javafx.scene.Node iconRestore;   // 两个重叠正方形

    // ✅ 静态变量：供 GpuView 等其他组件访问
    public static int publishState = 0;  // 推流状态：0=未推流，1=推流中
    public static long lastConfigStateTime = 0;  // 最后收到 CONFIG_STATE 的时间戳
    private static final long CONFIG_STATE_TIMEOUT_MS = 3000;  // 3秒超时判定为离线
    
    // ✅ 画质控制静态变量（从 CONFIG_STATE 消息解析）
    public static boolean trialRequired = false;        // 是否需要试用限制
    public static boolean activated = false;            // 是否已激活
    public static int activationLevel = 0;              // 激活等级 (0=未激活, 1=白银, 2=黄金)
    public static String activationLevelName = "";      // 等级名称
    public static String activationExpireAt = "";       // 激活到期时间
    public static String[] qualityAccess = {};          // 可用画质列表
    public static boolean trialEnded = false;           // 当天试用是否已结束
    public static int currentStage = 0;                 // 当前试用阶段 (1-6)
    public static int totalStages = 6;                  // 总阶段数
    public static int stageSeconds = 0;                 // 当前阶段总秒数
    public static int remainingSeconds = 0;             // 当前阶段剩余秒数
    public static int usedSeconds = 0;                  // 当前阶段已用秒数
    
    public int startFirst = 0;
    
    // ✅ 流量统计相关变量
    private long lastReceivedBytes = 0;      // 上次接收的总字节数
    private long lastBandwidthUpdateTime = 0; // 上次更新时间戳（毫秒）
    private long totalReceivedBytes = 0;      // 总接收字节数
    
    // ✅ Windows端网络质量监控
    private ScheduledExecutorService networkMonitorExecutor;
    private volatile int localRtt = -1;       // 本地RTT（毫秒）
    private volatile double localPacketLoss = -1.0;  // 本地丢包率


    // 事件管理器相关字段
    private UIUpdateEventManager eventManager;
    private String listenerId;
    private boolean eventListenersRegistered = false;


    private String listenerId2;
    
    // ⭐ 弹框管理：主窗口最小化或失去焦点时自动关闭
    private final java.util.List<Stage> openDialogs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        viewModel = new CameraMainViewModel();
        setupBindings();
        setupWindowDragging();
        // 初始化最大化按钮图标
        iconMaximize = createSquareIcon();
        iconRestore = createDoubleSquaresIcon();
        updateMaximizeIcon();
        updateStatusLabel("相机主界面已加载");
        // 设置控制设备为当前系统类型
        if (controlDeviceValue != null) {
            controlDeviceValue.setText(detectControlDevice());
        }
        // ✅ 更新磁盘缓存配置显示
        updateCacheConfigLabel();
        // ✅ 启动Windows端网络质量监控
        startLocalNetworkMonitor();
        // 初始化元素2-1中的播放器
        setupElement2_1Player();
        // 初始化元素2-2中的慢放视图
        setupElement2_2SlowView();
        // 初始化底部状态栏 Tooltip
        setupStatusBarTooltips();
        
        // ✅ 初始化慢放按钮状态
        updateSlowmoButtonsState();
        // ✅ 初始化滚轮帧数显示
        updateScrollFrameLabel();
        // 初始化元素2-3中的元素3-3控制面板
        setupElement2_3Panel();
        // 初始化元素1独立抓拍面板
        setupElement1Panel();
        // 在连接 STOMP 之前，先通过 HTTP 获取并缓存简化配置
        try {
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            // ⭐ 不管有没有设备都建立 STOMP 连接
            if (deviceId == null || deviceId.isBlank()) {
                // 无设备：直接建立 STOMP 连接（订阅绑定频道等待绑定）
                updateStatusLabel("还未绑定监控设备，等待绑定...");
                Platform.runLater(() -> {
                    updateControlLinkStatus(false);
                    // 建立 STOMP 连接
                    initStompConnection();
                    // 使用 Popup 弹窗提示绑定
                    Stage stage = getStage();
                    if (stage != null) {
                        DeviceBindingPromptDialog.showDialog(stage);
                    }
                });
            } else {
                // 有设备：预取配置后建立 STOMP 连接
                ConfigStore.getInstance().prefetchThinConfig(deviceId)
                    .thenAccept(cfg -> {
                        // 预取完成后再建立 STOMP 连接（不阻塞UI）
                        Platform.runLater(this::initStompConnection);
                    })
                    .exceptionally(ex -> {
                        // 预取失败也尝试建立 STOMP 连接
                        Platform.runLater(this::initStompConnection);
                        return null;
                    });
            }
        } catch (Exception e) {
            System.err.println("初始化时预取简化配置失败: " + e.getMessage());
            // 出错也继续尝试建立 STOMP 连接
            //initStompConnection();
        }


        // 从GridStore读取初始排列方式状态
        boolean initialHorizontalLayout = GridStore.getInstance().isHorizontalLayout();
        isHorizontalArrangement = initialHorizontalLayout;
        
        // ⭐ 更新横向排列按钮的文字
        if (arrangementModeLabel != null) {
            arrangementModeLabel.setText(isHorizontalArrangement ? "横向排列" : "纵向排列");
        }
        
        // ⭐ 初始化行列前后下拉菜单
        initRowColMenus();
        
        // ⭐ 初始化标题栏动态缩放（基于1920x1080设计稿）
        initTitleBarScaling();
        
        // 初始化快捷键管理器
        initializeShortcutManager();

        // ✅ 添加SplitPane divider位置变化监听器
        if (mainContentArea != null) {
            // 监听divider位置变化
            mainContentArea.getDividers().get(0).positionProperty().addListener((obs, oldPos, newPos) -> {
                LogTools.getInstance().logRecord2("SplitPane divider位置变化: " + oldPos + " -> " + newPos);
                // 延迟调用以确保布局完成
                Platform.runLater(() -> {
                    if (corePlayer != null) {
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                });
            });
        }

        // ✅ 添加窗口大小变化监听器
        Platform.runLater(() -> {
            Stage stage = getStage();
            if (stage != null) {
                // 监听窗口宽度变化
                stage.widthProperty().addListener((obs, oldWidth, newWidth) -> {
                    LogTools.getInstance().logRecord2("窗口宽度变化: " + oldWidth + " -> " + newWidth);
                    Platform.runLater(() -> {
                        if (corePlayer != null) {
                            corePlayer.refreshOverlayRectangle();
                        }
                        FileToos.updateSlowSize();
                    });
                });
                // 监听窗口高度变化
                stage.heightProperty().addListener((obs, oldHeight, newHeight) -> {
                    LogTools.getInstance().logRecord2("窗口高度变化: " + oldHeight + " -> " + newHeight);
                    Platform.runLater(() -> {
                        if (corePlayer != null) {
                            corePlayer.refreshOverlayRectangle();
                        }
                        FileToos.updateSlowSize();
                    });
                });
            }
        });
        initializeEventManager();
        // 动态设置菜单项文本，显示实际的快捷键
        updateMenuItemTexts();

        setupWindowCloseHandler();

        //⭐延迟设置圆角（等待 Stage 准备好）
        Platform.runLater(() -> {
            setupRoundedWindow();
        });


    }
    /**
     * ⭐ 设置圆角窗口
     */
    private void setupRoundedWindow() {
        Stage stage = getStage();
        if (stage == null) {
            System.err.println("❌ Stage 为 null，无法设置圆角");
            return;
        }

        // 1. 设置透明窗口（必须，否则圆角外会有白色方框）
        stage.initStyle(javafx.stage.StageStyle.TRANSPARENT);

        // 2. 获取根节点
        javafx.scene.Scene scene = stage.getScene();
        if (scene != null && scene.getRoot() != null) {
            Parent root = scene.getRoot();

            // 3. 设置透明背景
            scene.setFill(Color.TRANSPARENT);

            // 4. 如果根节点是 Region，添加圆角样式
            if (root instanceof Region) {
                Region region = (Region) root;

                // 圆角样式
                region.setStyle(
                        "-fx-background-color: white; " +
                                "-fx-background-radius: 15; " +
                                "-fx-border-radius: 15; " +
                                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 2);"
                );

                LogTools.getInstance().logRecord2("✅ 圆角窗口已设置");
            }
        }
    }



    // 在 initialize 方法的最后添加
    private void setupWindowCloseHandler() {
        // 获取当前窗口并设置关闭事件处理
        Platform.runLater(() -> {
            Stage stage = getStage();
            if (stage != null) {
                stage.setOnCloseRequest(event -> {
                    // 调用清理方法
                    cleanup();
                    // 退出应用
                    Platform.exit();
                });
                
                // ⭐ 监听主窗口最小化状态，自动关闭所有弹框
                stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
                    if (isIconified) {
                        closeAllDialogs();
                    }
                });
                
                // ⭐ 监听主窗口失去焦点，自动关闭所有弹框
                stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                    if (!isFocused) {
                        // 延迟一点检查，避免弹框本身获得焦点时误关闭
                        Platform.runLater(() -> {
                            // 检查焦点是否转移到了我们管理的弹框上
                            boolean focusOnOurDialog = openDialogs.stream().anyMatch(Stage::isFocused);
                            if (!focusOnOurDialog && !stage.isFocused()) {
                                closeAllDialogs();
                            }
                        });
                    }
                });
            }
        });
    }
    
    /**
     * ⭐ 注册弹框到管理列表（在弹框显示时调用）
     */
    public void registerDialog(Stage dialog) {
        if (dialog != null && !openDialogs.contains(dialog)) {
            openDialogs.add(dialog);
            // 弹框关闭时自动移除
            dialog.setOnHidden(e -> openDialogs.remove(dialog));
        }
    }
    
    /**
     * ⭐ 关闭所有已注册的弹框
     */
    private void closeAllDialogs() {
        Platform.runLater(() -> {
            // 复制列表避免并发修改
            java.util.List<Stage> dialogsToClose = new java.util.ArrayList<>(openDialogs);
            for (Stage dialog : dialogsToClose) {
                if (dialog != null && dialog.isShowing()) {
                    dialog.close();
                }
            }
            openDialogs.clear();
        });
    }


    /**
     * 初始化事件管理器
     */
    private void initializeEventManager() {
        try {
            this.eventManager = UIUpdateEventManager.getInstance();
            this.listenerId = "CameraMainController_" + System.currentTimeMillis();

            // 注册事件监听器
            registerUIUpdateEvents();

            LogTools.getInstance().logRecord2("✅ CameraMainController 事件管理器初始化成功");
        } catch (Exception e) {
            System.err.println("❌ CameraMainController 事件管理器初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 注册UI更新事件监听器
     */
    public void registerUIUpdateEvents() {
        if (eventListenersRegistered) {
            return;
        }
        try {
            // 注册快捷键设置事件
            eventManager.registerListener(UIUpdateEvent.EventType.SPEED_KEY,
                    this::handleUIUpdateEvent, listenerId + "_speed");
            
            // ⭐ 注册自动全屏切换事件（抓拍满格或清空后触发）
            eventManager.registerListener(UIUpdateEvent.EventType.AUTO_FULLSCREEN,
                    this::handleAutoFullScreenEvent, listenerId + "_autofullscreen");

            eventManager.registerListener(UIUpdateEvent.EventType.RESOLUTION_CHANGED,
                    this::handleRESOLUTION_CHANGED, listenerId + "_RESOLUTION_CHANGED");
            
            eventListenersRegistered = true;
            LogTools.getInstance().logRecord2("✅ CameraMainController UI更新事件监听器注册成功");

        } catch (Exception e) {
            System.err.println("❌ CameraMainController 注册UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }



    // 在 CameraMainController 销毁时调用
    public void cleanup() {
        // 注销事件监听器
        unregisterUIUpdateEvents();

    }

    /**
     * 注销UI更新事件监听器
     */
    public void unregisterUIUpdateEvents() {
        if (!eventListenersRegistered || eventManager == null) {
            return;
        }
        try {
            eventManager.unregisterListener(UIUpdateEvent.EventType.SPEED_KEY, listenerId + "_speed");
            eventManager.unregisterListener(UIUpdateEvent.EventType.AUTO_FULLSCREEN, listenerId + "_autofullscreen");
            eventManager.unregisterListener(UIUpdateEvent.EventType.RESOLUTION_CHANGED, listenerId + "_RESOLUTION_CHANGED");
            eventListenersRegistered = false;
            LogTools.getInstance().logRecord2("✅ CameraMainController UI更新事件监听器注销成功");

        } catch (Exception e) {
            System.err.println("❌ CameraMainController 注销UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleAutoFullScreenEvent(UIUpdateEvent event) {

        if(isFullScreenModeEnabled){
            Platform.runLater(() -> {
                try {
                    String source = event.getSource();
                    LogTools.getInstance().logRecord2("🎯 收到自动全屏切换事件，来源: " + source);
                    LogTools.getInstance().logRecord2("🎯 收到自动全屏切换事件，来源: " + source);

                    // ⭐ 根据事件来源判断操作
                    if (source != null && source.contains("sslAction")) {
                        // 抓拍满格触发全屏
                        if (!element2Collapsed) {
                            // 只有在非全屏状态下才触发
                            handleFullScreen();
                            isAutoFullScreenByCapture = true;  // 设置标记
                            LogTools.getInstance().logRecord2("✅ 抓拍满格，已进入全屏模式（标记已设置）");
                            LogTools.getInstance().logRecord2("✅ 抓拍满格，已进入全屏模式（标记已设置）");
                        } else {
                            LogTools.getInstance().logRecord2("⚠️ 已经处于全屏状态，跳过");
                        }
                    } else if (source != null && source.contains("onCaptureClear")) {
                        // 清除抓拍后恢复
                        if (isAutoFullScreenByCapture && element2Collapsed) {
                            // 只有在自动全屏标记为true且当前处于全屏状态时才恢复
                            handleFullScreen();
                            isAutoFullScreenByCapture = false;  // 清除标记
                            LogTools.getInstance().logRecord2("✅ 清除抓拍，已退出全屏模式（标记已清除）");
                            LogTools.getInstance().logRecord2("✅ 清除抓拍，已退出全屏模式（标记已清除）");
                        } else {
                            LogTools.getInstance().logRecord2("⚠️ 不是自动全屏状态或未处于全屏，跳过恢复（isAutoFullScreen=" +
                                    isAutoFullScreenByCapture + ", element2Collapsed=" + element2Collapsed + ")");
                            LogTools.getInstance().logRecord2("⚠️ 不是自动全屏状态或未处于全屏，跳过恢复");
                        }
                    }

                } catch (Exception e) {
                    System.err.println("❌ 处理自动全屏切换事件失败: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }



    }
    
    /**
     * ⭐ 处理自动全屏切换事件
     * - 抓拍数量达到行×列时，自动触发全屏
     * - 清空抓拍后，再次触发全屏恢复原状态（仅当之前是自动全屏时）
     */
    private void handleRESOLUTION_CHANGED(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {

                new Thread(() -> {
                    try {
                        Thread.sleep(200);
                        corePlayer.sendPLIRequestChange();  // 🔥 或者你的其他请求关键帧方法
                        LogTools.getInstance().logRecord2("✅ 已发送关键帧请求（200ms）");
                    } catch (Exception e) {
                        LogTools.getInstance().logRecord2("⚠️ 请求关键帧失败: " + e.getMessage());
                    }
                }).start();

                // 延迟 400ms（第二次）
                new Thread(() -> {
                    try {
                        Thread.sleep(400);
                        corePlayer.sendPLIRequestChange();
                        LogTools.getInstance().logRecord2("✅ 已发送关键帧请求（400ms）");
                    } catch (Exception ignore) {}
                }).start();

                // 延迟 600ms（第三次）
                new Thread(() -> {
                    try {
                        Thread.sleep(600);
                        corePlayer.sendPLIRequestChange();
                        LogTools.getInstance().logRecord2("✅ 已发送关键帧请求（600ms）");
                    } catch (Exception ignore) {}
                }).start();
                
            } catch (Exception e) {
                System.err.println("❌ 处理自动全屏切换事件失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 处理UI更新事件
     */
    private void handleUIUpdateEvent(UIUpdateEvent event) {
        try {
            LogTools.getInstance().logRecord2("🔄 CameraMainController 收到UI更新事件: " + event.getEventType());
            // 在JavaFX线程中处理UI更新
            Platform.runLater(() -> {
                try {
                    switch (event.getEventType()) {
                        case SPEED_KEY:
                            updateMenuItemTexts();
                            // ✅ 更新底部慢放按钮的快捷键显示
                            updateSlowmoButtonsState();
                            // ✅ 更新滚轮帧数显示
                            updateScrollFrameLabel();
                            break;
                    }
                    LogTools.getInstance().logRecord2("✅ CameraMainController UI更新事件处理完成: " + event.getEventType());
                } catch (Exception e) {
                    System.err.println("❌ CameraMainController 处理UI更新事件异常: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            System.err.println("❌ CameraMainController 处理UI更新事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 更新菜单项文本，显示实际的快捷键
     */
    private void updateMenuItemTexts() {
        Platform.runLater(() -> {
            try {
                // 使用 ShortcutHelper 获取快捷键名称并更新菜单项文本
                if (fullScreenMenuItem != null) {
                    fullScreenMenuItem.setText("全屏(" +
                            FileToos.ShortcutHelper.getFullscreenKeyName() + ")");
                }

                if (realTimeWindowMenuItem != null) {
                    realTimeWindowMenuItem.setText("实时窗口切换(" +
                            FileToos.ShortcutHelper.getRealtimeWindowKeyName() + ")");
                }

                if (slowMotionWindowMenuItem != null) {
                    slowMotionWindowMenuItem.setText("慢放窗口切换(" +
                            FileToos.ShortcutHelper.getSlowmoWindowKeyName() + ")");
                }

                LogTools.getInstance().logRecord2("✅ 菜单项文本已更新为动态快捷键名称");
            } catch (Exception e) {
                System.err.println("❌ 更新菜单项文本时出错: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    // ⭐ 设计稿基准尺寸（1920x1080）
    private static final double DESIGN_WIDTH = 1920.0;
    private static final double DESIGN_HEIGHT = 1080.0;
    private static final double DESIGN_TITLE_HEIGHT = 48.0;  // 设计稿标题栏高度
    private static final double DESIGN_FONT_SIZE = 14.0;     // 设计稿字体大小
    private static final double DESIGN_ICON_SIZE = 16.0;     // 设计稿图标大小
    private static final double DESIGN_BUTTON_HEIGHT = 32.0; // 设计稿按钮高度
    
    /**
     * ⭐ 初始化标题栏动态缩放（基于1920x1080设计稿）
     */
    private void initTitleBarScaling() {
        if (titleBar == null) return;
        
        // 延迟执行，确保Scene已经设置
        Platform.runLater(() -> {
            if (titleBar.getScene() != null && titleBar.getScene().getWindow() != null) {
                // 初次计算缩放
                applyTitleBarScale(titleBar.getScene().getWindow().getWidth());
                
                // 监听窗口宽度变化
                titleBar.getScene().getWindow().widthProperty().addListener((obs, oldVal, newVal) -> {
                    applyTitleBarScale(newVal.doubleValue());
                });
            } else {
                // 如果Scene还没准备好，再延迟尝试
                titleBar.sceneProperty().addListener((obs, oldScene, newScene) -> {
                    if (newScene != null && newScene.getWindow() != null) {
                        applyTitleBarScale(newScene.getWindow().getWidth());
                        newScene.getWindow().widthProperty().addListener((o, oldVal, newVal) -> {
                            applyTitleBarScale(newVal.doubleValue());
                        });
                    }
                });
            }
        });
    }
    
    /**
     * ⭐ 应用标题栏缩放
     * @param windowWidth 当前窗口宽度
     */
    private void applyTitleBarScale(double windowWidth) {
        if (titleBar == null || windowWidth <= 0) return;
        
        // 计算缩放比例（基于宽度）
        double scale = windowWidth / DESIGN_WIDTH;
        
        // 限制缩放范围 0.6 ~ 1.2
        scale = Math.max(0.6, Math.min(1.2, scale));
        
        // 应用缩放到标题栏
        double scaledTitleHeight = DESIGN_TITLE_HEIGHT * scale;
        double scaledFontSize = DESIGN_FONT_SIZE * scale;
        double scaledIconSize = DESIGN_ICON_SIZE * scale;
        double scaledButtonHeight = DESIGN_BUTTON_HEIGHT * scale;
        double scaledSpacing = 16 * scale;
        
        // 更新标题栏高度和间距
        titleBar.setPrefHeight(scaledTitleHeight);
        titleBar.setSpacing(scaledSpacing);
        
        // 更新所有按钮和标签的字体大小
        String scaledStyle = String.format(
            "-fx-font-size: %.1fpx;",
            scaledFontSize
        );
        
        // 遍历标题栏子节点，更新样式
        updateTitleBarChildrenScale(scaledFontSize, scaledIconSize, scaledButtonHeight);
        
        System.out.println(String.format("📐 标题栏缩放: 窗口宽度=%.0f, 缩放比例=%.2f, 字体=%.1fpx", 
            windowWidth, scale, scaledFontSize));
    }
    
    /**
     * ⭐ 更新标题栏子节点的缩放
     */
    private void updateTitleBarChildrenScale(double fontSize, double iconSize, double buttonHeight) {
        if (titleBar == null) return;
        
        for (javafx.scene.Node node : titleBar.getChildren()) {
            if (node instanceof Button) {
                Button btn = (Button) node;
                btn.setPrefHeight(buttonHeight);
                updateNodeFontAndIcon(btn, fontSize, iconSize);
            } else if (node instanceof MenuButton) {
                MenuButton menuBtn = (MenuButton) node;
                menuBtn.setPrefHeight(buttonHeight);
                updateNodeFontAndIcon(menuBtn, fontSize, iconSize);
            }
        }
    }
    
    /**
     * ⭐ 更新节点的字体和图标大小
     */
    private void updateNodeFontAndIcon(javafx.scene.control.ButtonBase btn, double fontSize, double iconSize) {
        // 更新按钮内的Label字体
        if (btn.getGraphic() instanceof javafx.scene.layout.HBox) {
            javafx.scene.layout.HBox hbox = (javafx.scene.layout.HBox) btn.getGraphic();
            for (javafx.scene.Node child : hbox.getChildren()) {
                if (child instanceof Label) {
                    Label label = (Label) child;
                    String currentStyle = label.getStyle();
                    // 替换字体大小
                    String newStyle = currentStyle.replaceAll("-fx-font-size:\\s*[\\d.]+px;?", 
                        String.format("-fx-font-size: %.1fpx;", fontSize));
                    if (!newStyle.contains("-fx-font-size")) {
                        newStyle += String.format(" -fx-font-size: %.1fpx;", fontSize);
                    }
                    label.setStyle(newStyle);
                } else if (child instanceof javafx.scene.image.ImageView) {
                    javafx.scene.image.ImageView iv = (javafx.scene.image.ImageView) child;
                    // 只调整主图标，不调整下拉箭头（下拉箭头保持较小）
                    if (iv.getFitHeight() > 8) {
                        iv.setFitHeight(iconSize);
                        iv.setFitWidth(iconSize);
                    }
                }
            }
        }
    }
    
    /**
     * ⭐ 初始化行列前后下拉菜单（与 Element2_3Controller 保持一致）
     */
    private void initRowColMenus() {
        // 行数选项 (1-10)
        if (rowMenuButton != null) {
            rowMenuButton.getItems().clear();
            for (int i = 1; i <= 10; i++) {
                final int row = i;
                MenuItem item = new MenuItem(String.valueOf(i));
                item.setOnAction(e -> {
                    if (rowLabel != null) rowLabel.setText("行：" + row);
                    GridStore.getInstance().setRows(row);
                    notifyGridChange();
                });
                rowMenuButton.getItems().add(item);
            }
            // ⭐ 添加滚轮支持
            enableMenuButtonScroll(rowMenuButton, rowLabel, "行", 1, 10);
        }
        
        // 列数选项 (1-10)
        if (colMenuButton != null) {
            colMenuButton.getItems().clear();
            for (int i = 1; i <= 10; i++) {
                final int col = i;
                MenuItem item = new MenuItem(String.valueOf(i));
                item.setOnAction(e -> {
                    if (colLabel != null) colLabel.setText("列：" + col);
                    GridStore.getInstance().setCols(col);
                    notifyGridChange();
                });
                colMenuButton.getItems().add(item);
            }
            // ⭐ 添加滚轮支持
            enableMenuButtonScroll(colMenuButton, colLabel, "列", 1, 10);
        }
        
        // 前帧数选项（与 Element2_3Controller 一致：10, 20, 30, ..., 120, 150, ..., 240）
        if (frontMenuButton != null) {
            frontMenuButton.getItems().clear();
            java.util.List<Integer> preValues = new java.util.ArrayList<>();
            // 10-120: 每10张
            for (int i = 10; i <= 120; i += 10) preValues.add(i);
            // 150-240: 每30张
            for (int i = 150; i <= 240; i += 30) preValues.add(i);
            
            for (int val : preValues) {
                final int front = val;
                MenuItem item = new MenuItem(String.valueOf(val));
                item.setOnAction(e -> {
                    if (frontLabel != null) frontLabel.setText("前：" + front);
                    try {
                        CaptureStore.getInstance().setPreCaptureCount(front);
                    } catch (Throwable ignore) {}
                });
                frontMenuButton.getItems().add(item);
            }
            // ⭐ 添加滚轮支持
            enableMenuButtonScrollList(frontMenuButton, frontLabel, "前", preValues);
        }
        
        // 后帧数选项（与 Element2_3Controller 一致：10, 20, 30, ..., 120, 150, ..., 240）
        if (backMenuButton != null) {
            backMenuButton.getItems().clear();
            java.util.List<Integer> postValues = new java.util.ArrayList<>();
            // 10-120: 每10张
            for (int i = 10; i <= 120; i += 10) postValues.add(i);
            // 150-240: 每30张
            for (int i = 150; i <= 240; i += 30) postValues.add(i);
            
            for (int val : postValues) {
                final int back = val;
                MenuItem item = new MenuItem(String.valueOf(val));
                item.setOnAction(e -> {
                    if (backLabel != null) backLabel.setText("后：" + back);
                    try {
                        CaptureStore.getInstance().setPostCaptureCount(back);
                    } catch (Throwable ignore) {}
                });
                backMenuButton.getItems().add(item);
            }
            // ⭐ 添加滚轮支持
            enableMenuButtonScrollList(backMenuButton, backLabel, "后", postValues);
        }
        
        // ⭐ 偏移选项（0-9）
        if (offsetMenuButton != null) {
            offsetMenuButton.getItems().clear();
            java.util.List<Integer> offsetValues = new java.util.ArrayList<>();
            for (int i = 0; i <= 9; i++) offsetValues.add(i);
            
            for (int val : offsetValues) {
                final int offset = val;
                MenuItem item = new MenuItem(String.valueOf(val));
                item.setOnAction(e -> {

                    if (offsetLabel != null) offsetLabel.setText("偏移：" + offset);

                    try {

                        CaptureStore.getInstance().setOffset(offset);
                    } catch (Throwable ignore) {

                    }
                });
                offsetMenuButton.getItems().add(item);
            }
            // ⭐ 添加滚轮支持
            enableMenuButtonScrollList(offsetMenuButton, offsetLabel, "偏移", offsetValues);
        }
        
        // 设置初始值（从 Store 读取）
        int rows = GridStore.getInstance().getRows();
        int cols = GridStore.getInstance().getCols();
        int front = 10;
        int back = 10;
        try {
            front = CaptureStore.getInstance().getPreCaptureCount();
            back = CaptureStore.getInstance().getPostCaptureCount();
        } catch (Throwable ignore) {}
        
        int offset = 0;
        try {
            offset = CaptureStore.getInstance().getOffset();
        } catch (Throwable ignore) {}
        
        if (rowLabel != null) rowLabel.setText("行：" + rows);
        if (colLabel != null) colLabel.setText("列：" + cols);
        if (frontLabel != null) frontLabel.setText("前：" + front);
        if (backLabel != null) backLabel.setText("后：" + back);
        if (offsetLabel != null) offsetLabel.setText("偏移：" + offset);
        
        // ⭐ 注册偏移监听器（相机设置拖动 FPS 时实时更新主页偏移 UI）
        final Label finalOffsetLabel = offsetLabel;
        CaptureStore.getInstance().addOffsetListener(newOffset -> {
            javafx.application.Platform.runLater(() -> {
                if (finalOffsetLabel != null) {
                    finalOffsetLabel.setText("偏移：" + newOffset);
                    com.acard.acard.tools.LogTools.getInstance().logRecord6("🔧 [CameraMain] 主页偏移UI更新: " + newOffset);
                }
            });
        });
    }
    
    /**
     * ⭐ 通知网格变化（更新 Element1 布局）
     */
    private void notifyGridChange() {
        if (element1Controller != null) {
            element1Controller.setGridSize(GridStore.getInstance().getRows(), GridStore.getInstance().getCols());
        }
    }
    
    /**
     * ⭐ 为 MenuButton 添加滚轮支持（连续数值范围）
     */
    private void enableMenuButtonScroll(MenuButton menuButton, Label label, String prefix, int min, int max) {
        if (menuButton == null) return;
        
        menuButton.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            // 解析当前值
            int currentVal = min;
            if (label != null) {
                String text = label.getText().replaceAll("[^0-9]", "");
                try {
                    currentVal = Integer.parseInt(text);
                } catch (NumberFormatException ignore) {}
            }
            
            // 滚轮调整
            if (event.getDeltaY() > 0) {
                currentVal++;
            } else if (event.getDeltaY() < 0) {
                currentVal--;
            }
            currentVal = Math.max(min, Math.min(max, currentVal));
            
            // 更新显示和存储
            if (label != null) label.setText(prefix + "：" + currentVal);
            
            if ("行".equals(prefix)) {
                GridStore.getInstance().setRows(currentVal);
                notifyGridChange();
            } else if ("列".equals(prefix)) {
                GridStore.getInstance().setCols(currentVal);
                notifyGridChange();
            }
            
            event.consume();
        });
    }
    
    /**
     * ⭐ 为 MenuButton 添加滚轮支持（自定义列表值）
     */
    private void enableMenuButtonScrollList(MenuButton menuButton, Label label, String prefix, java.util.List<Integer> values) {
        if (menuButton == null || values == null || values.isEmpty()) return;
        
        menuButton.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            // 解析当前值并找到索引
            int currentVal = values.get(0);
            if (label != null) {
                String text = label.getText().replaceAll("[^0-9]", "");
                try {
                    currentVal = Integer.parseInt(text);
                } catch (NumberFormatException ignore) {}
            }
            
            int index = values.indexOf(currentVal);
            if (index < 0) index = 0;
            
            // 滚轮调整索引
            if (event.getDeltaY() > 0) {
                index++;
            } else if (event.getDeltaY() < 0) {
                index--;
            }
            index = Math.max(0, Math.min(values.size() - 1, index));
            
            int newVal = values.get(index);
            
            // 更新显示和存储
            if (label != null) label.setText(prefix + "：" + newVal);
            
            try {
                if ("前".equals(prefix)) {
                    CaptureStore.getInstance().setPreCaptureCount(newVal);
                } else if ("后".equals(prefix)) {
                    CaptureStore.getInstance().setPostCaptureCount(newVal);
                } else if ("偏移".equals(prefix)) {
                    CaptureStore.getInstance().setOffset(newVal);
                }
            } catch (Throwable ignore) {}
            
            event.consume();
        });
    }
    
    /**
     * 初始化快捷键管理器
     */
    private void initializeShortcutManager() {
        // 延迟初始化，确保Scene已经设置
        Platform.runLater(() -> {
            try {
                if (mainContentArea != null && mainContentArea.getScene() != null) {
                    // 创建快捷键管理器
                    shortcutManager = new ShortcutManager();
                    
                    // 绑定到Scene
                    shortcutManager.bindToScene(mainContentArea.getScene());
                    
                    // 设置控制器引用（延迟设置，确保控制器已创建）
                    Platform.runLater(() -> {
                        if (element2_3Controller != null) {
                            shortcutManager.setElement2_3Controller(element2_3Controller);
                        }
                        if (element1Controller != null) {
                            //shortcutManager.setElement1Controller(element1Controller);
                        }

                        // 添加这行：
                        if (element2_1Player != null) {
                            shortcutManager.setGpuView(element2_1Player);
                        }

                        // 设置CameraMainController引用
                        shortcutManager.setCameraMainController(CameraMainController.this);


                    });
                    
                    LogTools.getInstance().logRecord2("✅ 快捷键管理器初始化成功");
                } else {
                    System.err.println("❌ Scene未准备好，快捷键管理器初始化失败");
                }
            } catch (Exception e) {
                System.err.println("❌ 快捷键管理器初始化失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleArrangementModeChange(boolean isSelected) {
        if (isSelected) {
            LogTools.getInstance().logRecord2("排列方式已启用 - 从左到右");
            //showInfoDialog("排列方式","从左到右排列");
            // 设置为从左到右排列
            setElement1LayoutDirection(true);
        } else {
            LogTools.getInstance().logRecord2("排列方式已禁用 - 从上到下");
            //showInfoDialog("排列方式","从上到下排列");
            // 设置为从上到下排列
            setElement1LayoutDirection(false);
        }
    }


    // 添加这个方法到 CameraMainController
    private void setElement1LayoutDirection(boolean horizontal) {
        if (element1Controller != null) {
            element1Controller.setLayoutDirection(horizontal);
            // 同步保存到GridStore
            GridStore.getInstance().setHorizontalLayout(horizontal);
        }
    }

    // 在 CameraMainController 中添加

    
    /**
     * 设置数据绑定
     */
    private void setupBindings() {
        // 绑定状态标签

    }
    
    /**
     * 设置窗口拖拽功能
     * 只在拖动区域(Region)响应拖动事件，避免与按钮冲突
     */
    private void setupWindowDragging() {
        if (dragRegion != null) {
            dragRegion.setOnMousePressed((MouseEvent event) -> {
                Stage stage = getStage();
                if (stage != null && !stage.isMaximized() && !isFullScreen) {  // ⭐ 使用伪全屏标记检查
                    xOffset = event.getSceneX();
                    yOffset = event.getSceneY();
                }
            });
            
            dragRegion.setOnMouseDragged((MouseEvent event) -> {
                Stage stage = getStage();
                if (stage != null && !stage.isMaximized() && !isFullScreen) {  // ⭐ 使用伪全屏标记检查
                    stage.setX(event.getScreenX() - xOffset);
                    stage.setY(event.getScreenY() - yOffset);
                }
            });
            
            // 鼠标释放时重置偏移，避免下一次拖动异常
            dragRegion.setOnMouseReleased((MouseEvent event) -> {
                xOffset = 0;
                yOffset = 0;
            });
        }
    }
    
    /**
     * 检查事件目标是否是按钮或按钮的子元素
     */
    private boolean isButtonOrButtonChild(Object target) {
        if (target instanceof Button) {
            return true;
        }
        
        // 检查是否是按钮内的文本节点
        if (target instanceof javafx.scene.text.Text) {
            javafx.scene.Node parent = ((javafx.scene.text.Text) target).getParent();
            return parent instanceof Button;
        }
        
        // 检查是否是其他按钮子元素
        if (target instanceof javafx.scene.Node) {
            javafx.scene.Node node = (javafx.scene.Node) target;
            while (node != null) {
                if (node instanceof Button) {
                    return true;
                }
                node = node.getParent();
            }
        }
        
        return false;
    }
    
    // ==================== 菜单项处理方法 ====================
    
    /**
     * 处理全屏切换 - 实现放大/恢复功能
     */
    @FXML
    public void handleFullScreen() {
        // ⭐ 如果抓拍全屏查看器正在显示，先关闭它
        if (CaptureItemView.isFullscreenViewerShowing()) {
            CaptureItemView.closeFullscreenViewer();
            return;  // 关闭抓拍全屏后直接返回，不执行主页全屏切换
        }
        
        if (mainContentArea == null || element2 == null) {
            return;
        }

        if(isRealtimeStreamInElement2()){

            SplitPane splitPane = mainContentArea;
            if (!element2Collapsed) {
                // ⭐ 进入全屏：缩小element2到1x1
                double[] positions = splitPane.getDividerPositions();
                if (positions.length > 0) {
                    element2DividerBackup = positions[0];
                }


                element2.setMinWidth(1);
                element2.setPrefWidth(1);
                element2.setMaxWidth(1);
                splitPane.setDividerPositions(1.0);
                SplitPane.setResizableWithParent(element2, false);
                element2Collapsed = true;

                // ✅ 关键修复：检查时时流是否在element2（右边）
                // 如果时时流在右边，需要将其缩小到1x1并刷新overlay
                if (corePlayer != null) {
                    LogTools.getInstance().logRecord2("🎯 检测到时时流在右边（element2），缩小到1x1...");
                    LogTools.getInstance().logRecord("时时流在右边，缩小到1x1");

                    // ⭐ 多次刷新确保生效（部分PC需要多次刷新）
                    PauseTransition delay1 =
                            new PauseTransition(Duration.millis(50));
                    delay1.setOnFinished(evt -> {
                        try {
                            corePlayer.refreshOverlayRectangle2();
                            LogTools.getInstance().logRecord2("✅ [第1次] 时时流缩小到1x1完成");
                        } catch (Exception e) {
                            System.err.println("⚠️ 刷新overlay失败: " + e.getMessage());
                        }
                    });
                    delay1.play();

                    PauseTransition delay2 =
                            new PauseTransition(Duration.millis(200));
                    delay2.setOnFinished(evt -> {
                        try {
                            corePlayer.refreshOverlayRectangle2();
                            LogTools.getInstance().logRecord2("✅ [第1次] 时时流缩小到1x1完成");
                        } catch (Exception e) {
                            System.err.println("⚠️ 刷新overlay失败: " + e.getMessage());
                        }
                    });
                    delay2.play();


                    PauseTransition delay3 =
                            new PauseTransition(Duration.millis(300));
                    delay3.setOnFinished(evt -> {
                        try {
                            corePlayer.refreshOverlayRectangle2();
                            LogTools.getInstance().logRecord2("✅ [第1次] 时时流缩小到1x1完成");
                        } catch (Exception e) {
                            System.err.println("⚠️ 刷新overlay失败: " + e.getMessage());
                        }
                    });
                    delay3.play();

                }

            } else {
                // ⭐ 退出全屏：恢复element2大小

                element2.setMinWidth(Region.USE_COMPUTED_SIZE);
                element2.setPrefWidth(Region.USE_COMPUTED_SIZE);
                element2.setMaxWidth(Double.MAX_VALUE);
                SplitPane.setResizableWithParent(element2, true);
                // ⭐ 如果退出全屏，清除自动全屏标记（防止误判）
                if (isAutoFullScreenByCapture) {
                    isAutoFullScreenByCapture = false;
                    LogTools.getInstance().logRecord2("🔄 手动退出全屏，已清除自动全屏标记");
                }

                splitPane.setDividerPositions(element2DividerBackup);
                element2Collapsed = false;

                // ✅ 关键修复：检查时时流是否在element2（右边）
                // 如果时时流在右边，需要恢复其大小并刷新overlay
                if (corePlayer != null) {
                    if (isRealtimeStreamInElement2()) {
                        LogTools.getInstance().logRecord2("🎯 检测到时时流在右边（element2），恢复原始大小...");
                        LogTools.getInstance().logRecord("时时流在右边，恢复原始大小");
                    } else {
                        LogTools.getInstance().logRecord("时时流在左边（element1），正常恢复");
                    }

                    // ⭐ 多次刷新确保生效（部分PC需要多次刷新）
                    PauseTransition delay1 =
                            new PauseTransition(Duration.millis(50));
                    delay1.setOnFinished(evt -> {
                        try {
                            corePlayer.refreshOverlayRectangle();
                            LogTools.getInstance().logRecord2("✅ [第1次] 时时流恢复大小完成");
                        } catch (Exception e) {
                            System.err.println("⚠️ 刷新overlay失败: " + e.getMessage());
                        }
                    });
                    delay1.play();



                } else {
                    LogTools.getInstance().logRecord("error change size: corePlayer is null");
                }
            }
        }


    }

    /**
     * ✅ 检测时时流是否在 element2（右边）
     * 
     * 逻辑：
     *   - 如果 element1 包含 Element1Controller.scroll → 抓拍面板在左边，时时流在右边
     *   - 如果 element1 不包含 Element1Controller.scroll → 时时流在左边，抓拍面板在右边
     * 
     * @FXML private StackPane element1; // 左边容器
     * @FXML private GridPane element2;  // 右边容器
     */
    private boolean isRealtimeStreamInElement2() {
        try {
            if (element1 == null) {
                LogTools.getInstance().logRecord2("⚠️ element1 为 null");
                return false;
            }
            
            if (element1Controller == null) {
                LogTools.getInstance().logRecord2("⚠️ element1Controller 为 null");
                return false;
            }
            
            ScrollPane scroll = element1Controller.getScroll();
            if (scroll == null) {
                LogTools.getInstance().logRecord2("⚠️ element1Controller.getScroll() 为 null");
                return false;
            }
            
            // ✅ 核心逻辑：检查 element1 的子节点中是否包含抓拍面板的 scroll
            boolean scrollInElement1 = isNodeInContainer(scroll, element1);
            
            if (scrollInElement1) {
                // scroll 在 element1（左边） → 时时流在右边
                LogTools.getInstance().logRecord2("✅ 抓拍面板在 element1（左边） → 时时流在 element2（右边）");
                return true;
            } else {
                // scroll 不在 element1（左边） → 时时流在左边
                LogTools.getInstance().logRecord2("✅ 抓拍面板不在 element1 → 时时流在 element1（左边）");
                return false;
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ 检测时时流位置失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * ✅ 递归检查节点是否在容器中（包括嵌套子节点）
     * 因为 scroll 可能嵌套在 Pane 中，而 Pane 才是 element1 的直接子节点
     */
    private boolean isNodeInContainer(javafx.scene.Node targetNode, Parent container) {
        if (targetNode == null || container == null) {
            return false;
        }
        
        // 检查直接子节点
        if (container.getChildrenUnmodifiable().contains(targetNode)) {
            return true;
        }
        
        // 递归检查嵌套子节点
        for (javafx.scene.Node child : container.getChildrenUnmodifiable()) {
            if (child instanceof Parent) {
                if (isNodeInContainer(targetNode, (Parent) child)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 处理实时窗口切换
     */
    @FXML
    public void handleRealTimeWindow() {
        updateStatusLabel("切换到实时窗口模式");
        swapElementContent(element1, element2_1, element1Label, element2_1Label);
        // ✅ 切换后刷新HWND位置和大小
        refreshVideoOverlayAfterSwap();
    }
    
    /**
     * 处理慢放窗口切换
     */
    @FXML
    public void handleSlowMotionWindow() {
        updateStatusLabel("切换到慢放窗口模式");
        swapElementContent(element1, element2_2, element1Label, element2_2Label);
        // ✅ 切换后刷新HWND位置和大小
        refreshVideoOverlayAfterSwap();
    }


    
    /**
     * 窗口切换后刷新视频overlay位置
     */
    private void refreshVideoOverlayAfterSwap() {
        // ✅ 多次延迟执行，确保布局完全完成后再刷新HWND
        Platform.runLater(() -> {
            if (element2_1Player != null) {
                element2_1Player.requestLayout();
            }
            // 第一次刷新（50ms后）
            PauseTransition pause1 = new PauseTransition(Duration.millis(50));
            pause1.setOnFinished(e -> {
                try {
                    if (corePlayer != null) {
                        LogTools.getInstance().logRecord2("🔄 [第1次] 窗口切换后刷新视频overlay...");
                        corePlayer.refreshOverlayRectangle();
                    }
                    // 发送UI更新事件给SlowMoGpuPlayer
                    FileToos.updateSlowSize();
                    
                    // ⚡ 刷新抓拍网格布局
                    if (element1Controller != null) {
                        element1Controller.refreshLayoutSizes();
                    }
                    // ⚡ 刷新 AI 网格布局
                    if (element2_2JpegController != null) {
                        element2_2JpegController.refreshAiLayoutSize();
                    }
                } catch (Throwable ex) {
                    System.err.println("⚠️ 刷新overlay失败: " + ex.getMessage());
                }
            });
            pause1.play();
            
            // 第二次刷新（150ms后）
            PauseTransition pause2 = new PauseTransition(Duration.millis(150));
            pause2.setOnFinished(e -> {
                try {
                    if (corePlayer != null) {
                        LogTools.getInstance().logRecord2("🔄 [第2次] 窗口切换后刷新视频overlay...");
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                    
                    // ⚡ 刷新抓拍网格布局
                    if (element1Controller != null) {
                        element1Controller.refreshLayoutSizes();
                    }
                    // ⚡ 刷新 AI 网格布局
                    if (element2_2JpegController != null) {
                        element2_2JpegController.refreshAiLayoutSize();
                    }
                } catch (Throwable ex) {
                    System.err.println("⚠️ 刷新overlay失败: " + ex.getMessage());
                }
            });
            pause2.play();
            
            // 第三次刷新（300ms后）
            PauseTransition pause3 = new PauseTransition(Duration.millis(300));
            pause3.setOnFinished(e -> {
                try {
                    if (corePlayer != null) {
                        LogTools.getInstance().logRecord2("🔄 [第3次] 窗口切换后刷新视频overlay...");
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                    
                    // ⚡ 刷新抓拍网格布局
                    if (element1Controller != null) {
                        element1Controller.refreshLayoutSizes();
                    }
                    // ⚡ 刷新 AI 网格布局
                    if (element2_2JpegController != null) {
                        element2_2JpegController.refreshAiLayoutSize();
                    }
                } catch (Throwable ex) {
                    System.err.println("⚠️ 刷新overlay失败: " + ex.getMessage());
                }
            });
            pause3.play();
        });
    }
    
    /**
     * 交换两个元素的内容，保持容器大小不变
     */
    private void swapElementContent(StackPane pane1, StackPane pane2, Label label1, Label label2) {
        if (pane1 == null || pane2 == null) return;
        // 交换标签文本（仅视觉标识）
        String tempText = label1 != null ? label1.getText() : null;
        if (label1 != null && label2 != null) {
            label1.setText(label2.getText());
            label2.setText(tempText);
        }

        // 实际交换两个容器的子节点内容
        javafx.scene.Node node1 = pane1.getChildren().isEmpty() ? null : pane1.getChildren().get(0);
        javafx.scene.Node node2 = pane2.getChildren().isEmpty() ? null : pane2.getChildren().get(0);
        if (node1 == null && node2 == null) return;
        // 执行交换
        if (node2 != null) {
            pane1.getChildren().setAll(node2);
        } else {
            pane1.getChildren().clear();
        }
        if (node1 != null) {
            pane2.getChildren().setAll(node1);
        } else {
            pane2.getChildren().clear();
        }

        // 自适应：确保新父容器下的视图占满并重新布局（解除旧绑定，绑定到新父容器宽高）
        try {
            if (node1 instanceof Region) {
                Region r1 = (Region) node1;
                // node1 已被放入 pane2
                if (r1.prefWidthProperty().isBound()) r1.prefWidthProperty().unbind();
                if (r1.prefHeightProperty().isBound()) r1.prefHeightProperty().unbind();
                r1.setMinSize(0, 0);
                r1.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                r1.prefWidthProperty().bind(pane2.widthProperty());
                r1.prefHeightProperty().bind(pane2.heightProperty());
                StackPane.setAlignment(r1, javafx.geometry.Pos.CENTER);
                
                // ✅ 如果是GpuView，重新设置margin
                if (r1 instanceof GpuView) {
                    javafx.geometry.Insets margin = new javafx.geometry.Insets(3, 3, 3, 3);
                    StackPane.setMargin(r1, margin);
                    LogTools.getInstance().logRecord2("🔄 GpuView已切换到新父容器并重新设置margin");
                }
            }
            if (node2 instanceof Region) {
                Region r2 = (Region) node2;
                // node2 已被放入 pane1
                if (r2.prefWidthProperty().isBound()) r2.prefWidthProperty().unbind();
                if (r2.prefHeightProperty().isBound()) r2.prefHeightProperty().unbind();
                r2.setMinSize(0, 0);
                r2.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
                r2.prefWidthProperty().bind(pane1.widthProperty());
                r2.prefHeightProperty().bind(pane1.heightProperty());
                StackPane.setAlignment(r2, javafx.geometry.Pos.CENTER);
                
                // ✅ 如果是GpuView，重新设置margin
                if (r2 instanceof GpuView) {
                    javafx.geometry.Insets margin = new javafx.geometry.Insets(3, 3, 3, 3);
                    StackPane.setMargin(r2, margin);
                    LogTools.getInstance().logRecord2("🔄 GpuView已切换到新父容器并重新设置margin");
                }
            }
        } catch (Throwable ignore) {}

        // 针对抓拍网格：锁定最后一个抓拍项视口为当前网格单元尺寸，避免拉伸/留白
        try {
            lockLastCaptureViewport();
        } catch (Throwable ignore) {}

        // 请求布局并尝试将交互焦点交给新视图
        if (pane1.getScene() != null) {
            pane1.requestLayout();
        }
        if (pane2.getScene() != null) {
            pane2.requestLayout();
        }
        
        // ✅ 强制请求被交换节点的布局更新
        if (node1 instanceof Region) {
            ((Region) node1).requestLayout();
        }
        if (node2 instanceof Region) {
            ((Region) node2).requestLayout();
        }
        
        // ✅ 强制父容器和根节点的布局更新
        Platform.runLater(() -> {
            if (pane1.getParent() != null) {
                pane1.getParent().requestLayout();
            }
            if (pane2.getParent() != null) {
                pane2.getParent().requestLayout();
            }
            if (pane1.getScene() != null && pane1.getScene().getRoot() != null) {
                pane1.getScene().getRoot().requestLayout();
            }
        });
        
        if (node2 != null) {
            node2.requestFocus();
        } else if (node1 != null) {
            node1.requestFocus();
        }
    }
    
    /**
     * 处理设备绑定 - 显示绑定方式选择菜单
     */
    @FXML
    private void handleDeviceBind() {
        // 创建绑定方式选择菜单（使用与行下拉相同的样式）
        ContextMenu bindMenu = new ContextMenu();
        bindMenu.getStyleClass().add("dark-menu-button");
        bindMenu.setStyle("-fx-background-color: #292929; -fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8; -fx-border-width: 1; -fx-padding: 4;");
        
        // 扫码绑定选项
        MenuItem scanBindItem = new MenuItem("扫码绑定");
        scanBindItem.setOnAction(e -> handleScanBind());
        
        // 手动绑定选项
        MenuItem manualBindItem = new MenuItem("手动绑定");
        manualBindItem.setOnAction(e -> handleManualBind());
        
        bindMenu.getItems().addAll(scanBindItem, manualBindItem);
        
        // 在绑定按钮下方显示菜单
        if (deviceBindButton != null) {
            bindMenu.show(deviceBindButton, javafx.geometry.Side.BOTTOM, 0, 5);
        } else {
            // 如果没有找到按钮，尝试获取事件源
            Stage stage = getStage();
            if (stage != null && stage.getScene() != null) {
                bindMenu.show(stage);
            }
        }
    }
    
    /**
     * 处理扫码绑定
     */
    private void handleScanBind() {
        updateStatusLabel("正在获取设备绑定二维码...");
        
        // 调用接口获取二维码信息
        HttpClientManager httpClient = HttpClientManager.getInstance();
        httpClient.get("/api/binding/qrcode", QRCodeBindingResponse.class)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        QRCodeBindingResponse data = response.getData();
                        String controlUsername = data.getControlUsername();
                        
                        if (controlUsername != null && !controlUsername.isEmpty()) {
                            updateStatusLabel("显示设备绑定二维码");
                            
                            // 显示组合弹窗：二维码 + 密码输入
                            Stage stage = getStage();
                            DeviceBindingDialog.showDialog(
                                stage,
                                controlUsername,  // 二维码内容
                                password -> {
                                    // 用户确认输入密码，先查询待验证绑定，然后验证
                                    checkAndVerifyBinding(password);
                                },
                                () -> {
                                    // 用户取消
                                    updateStatusLabel("已取消设备绑定验证");
                                }
                            );
                        } else {
                            updateStatusLabel("获取二维码失败：controlUsername 为空");
                            showInfoDialog("设备绑定", "获取设备绑定码失败，请稍后重试");
                        }
                    } else {
                        String errorMsg = response.getMessage() != null ? response.getMessage() : "未知错误";
                        updateStatusLabel("获取二维码失败：" + errorMsg);
                        showInfoDialog("设备绑定", "获取设备绑定码失败：" + errorMsg);
                    }
                });
            })
            .exceptionally(throwable -> {
                Platform.runLater(() -> {
                    updateStatusLabel("获取二维码失败：" + throwable.getMessage());
                    showInfoDialog("设备绑定", "网络请求失败：" + throwable.getMessage());
                });
                return null;
            });
    }
    
    /**
     * 处理手动绑定
     */
    private void handleManualBind() {
        updateStatusLabel("手动绑定设备");
        
        // 创建手动绑定弹窗
        Stage mainStage = getStage();
        Stage dialogStage = new Stage();
        dialogStage.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialogStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        dialogStage.setTitle("手动绑定设备");
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        dialogStage.setOnShowing(e -> {
            if (mainStage != null) mainStage.toFront();
        });
        dialogStage.setOnHidden(e -> {
            if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); });
        });
        
        // 主容器
        javafx.scene.layout.VBox mainContainer = new javafx.scene.layout.VBox(16);
        mainContainer.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 24;");
        mainContainer.setPrefWidth(400);
        
        // 标题
        Label titleLabel = new Label("手动绑定设备");
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 提示信息
        Label hintLabel = new Label("请输入设备端的账号前8位和密码进行绑定");
        hintLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 13px;");
        
        // 设备账号前8位输入
        javafx.scene.layout.VBox deviceNicknameBox = new javafx.scene.layout.VBox(6);
        Label deviceNicknameLabel = new Label("请输入设备端账号前8位");
        deviceNicknameLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 14px;");
        TextField deviceNicknameField = new TextField();
        deviceNicknameField.setPromptText("请输入设备端账号前8位");
        deviceNicknameField.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-prompt-text-fill: #64748b; " +
                                     "-fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8; -fx-padding: 12;");
        deviceNicknameBox.getChildren().addAll(deviceNicknameLabel, deviceNicknameField);
        
        // 设备密码输入
        javafx.scene.layout.VBox passwordBox = new javafx.scene.layout.VBox(6);
        Label passwordLabel = new Label("设备端密码");
        passwordLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 14px;");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("请输入设备端登录密码");
        passwordField.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-prompt-text-fill: #64748b; " +
                               "-fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8; -fx-padding: 12;");
        passwordBox.getChildren().addAll(passwordLabel, passwordField);
        
        // 二级密码输入
        javafx.scene.layout.VBox secondaryPasswordBox = new javafx.scene.layout.VBox(6);
        Label secondaryPasswordLabel = new Label("二级密码");
        secondaryPasswordLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 14px;");
        PasswordField secondaryPasswordField = new PasswordField();
        secondaryPasswordField.setPromptText("请输入设备端二级密码");
        secondaryPasswordField.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-prompt-text-fill: #64748b; " +
                                        "-fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8; -fx-padding: 12;");
        secondaryPasswordBox.getChildren().addAll(secondaryPasswordLabel, secondaryPasswordField);
        
        // 错误提示
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px;");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        
        // 按钮区域
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox(12);
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        buttonBox.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;");
        cancelBtn.setOnAction(e -> dialogStage.close());
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle("-fx-background-color: #374151; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;"));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;"));
        
        Button confirmBtn = new Button("绑定");
        confirmBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;");
        confirmBtn.setOnMouseEntered(e -> confirmBtn.setStyle("-fx-background-color: #4f6af0; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;"));
        confirmBtn.setOnMouseExited(e -> confirmBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 24;"));
        
        confirmBtn.setOnAction(e -> {
            String deviceNickname = deviceNicknameField.getText().trim();
            String password = passwordField.getText();
            String secondaryPassword = secondaryPasswordField.getText();
            
            // 验证输入
            if (deviceNickname.isEmpty()) {
                errorLabel.setText("请输入设备端账号前8位");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }
            if (password.isEmpty()) {
                errorLabel.setText("请输入设备端密码");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }
            if (secondaryPassword.isEmpty()) {
                errorLabel.setText("请输入二级密码");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                return;
            }
            
            // 禁用按钮，显示加载状态
            confirmBtn.setDisable(true);
            confirmBtn.setText("绑定中...");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
            
            // 调用手动绑定接口
            executeManualBind(deviceNickname, password, secondaryPassword, dialogStage, confirmBtn, errorLabel);
        });
        
        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        
        mainContainer.getChildren().addAll(titleLabel, hintLabel, deviceNicknameBox, passwordBox, secondaryPasswordBox, errorLabel, buttonBox);
        
        // 拖动功能
        final double[] dragOffset = new double[2];
        mainContainer.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        mainContainer.setOnMouseDragged(e -> {
            dialogStage.setX(e.getScreenX() - dragOffset[0]);
            dialogStage.setY(e.getScreenY() - dragOffset[1]);
        });
        
        Scene scene = new Scene(mainContainer);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialogStage.setScene(scene);
        
        // 居中显示
        dialogStage.setOnShown(e -> {
            Stage owner = getStage();
            if (owner != null) {
                dialogStage.setX(owner.getX() + (owner.getWidth() - dialogStage.getWidth()) / 2);
                dialogStage.setY(owner.getY() + (owner.getHeight() - dialogStage.getHeight()) / 2);
            }
        });
        
        // ⭐ 直接监听主窗口状态，自动关闭弹框
        if (mainStage != null) {
            javafx.beans.value.ChangeListener<Boolean> iconifiedListener = (obs, wasIconified, isIconified) -> {
                if (isIconified && dialogStage.isShowing()) {
                    dialogStage.close();
                }
            };
            javafx.beans.value.ChangeListener<Boolean> focusedListener = (obs, wasFocused, isFocused) -> {
                if (!isFocused && dialogStage.isShowing() && !dialogStage.isFocused()) {
                    Platform.runLater(() -> {
                        if (!mainStage.isFocused() && !dialogStage.isFocused() && dialogStage.isShowing()) {
                            dialogStage.close();
                        }
                    });
                }
            };
            mainStage.iconifiedProperty().addListener(iconifiedListener);
            mainStage.focusedProperty().addListener(focusedListener);
            dialogStage.setOnHidden(e -> {
                mainStage.iconifiedProperty().removeListener(iconifiedListener);
                mainStage.focusedProperty().removeListener(focusedListener);
            });
        }
        
        dialogStage.showAndWait();
    }
    
    /**
     * 执行手动绑定接口调用
     */
    private void executeManualBind(String deviceNickname, String password, String secondaryPassword, 
                                   Stage dialogStage, Button confirmBtn, Label errorLabel) {
        // 获取当前控制端账号
        LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
        String controlUsername = loginResp != null ? loginResp.getUsername() : null;
        
        if (controlUsername == null || controlUsername.isEmpty()) {
            Platform.runLater(() -> {
                errorLabel.setText("未获取到控制端账号，请重新登录");
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
                confirmBtn.setDisable(false);
                confirmBtn.setText("绑定");
            });
            return;
        }
        
        // 构建请求参数
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("controlUsername", controlUsername);
        params.put("deviceNickname", deviceNickname);
        params.put("password", password);
        params.put("secondaryPassword", secondaryPassword);
        
        // 调用手动绑定接口
        HttpClientManager httpClient = HttpClientManager.getInstance();
        httpClient.post("/api/binding/manual-bind", params, ManualBindResponse.class)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        ManualBindResponse data = response.getData();
                        if (data.isSuccess()) {
                            // 绑定成功，关闭对话框
                            dialogStage.close();
                            
                            // 统一调用绑定成功后的处理（会弹出提示框）
                            refreshLoginAfterBind(data.getDeviceId(), deviceNickname);
                        } else {
                            // 服务器返回失败
                            String msg = data.getMessage() != null ? data.getMessage() : "绑定失败";
                            errorLabel.setText(msg);
                            errorLabel.setVisible(true);
                            errorLabel.setManaged(true);
                            confirmBtn.setDisable(false);
                            confirmBtn.setText("绑定");
                        }
                    } else {
                        // 请求返回错误
                        String errorMsg = response.getMessage() != null ? response.getMessage() : "绑定失败";
                        errorLabel.setText(errorMsg);
                        errorLabel.setVisible(true);
                        errorLabel.setManaged(true);
                        confirmBtn.setDisable(false);
                        confirmBtn.setText("绑定");
                    }
                });
            })
            .exceptionally(throwable -> {
                Platform.runLater(() -> {
                    String errorMsg = throwable.getMessage();
                    if (errorMsg != null && errorMsg.contains("error")) {
                        // 尝试解析错误信息
                        try {
                            int start = errorMsg.indexOf("\"error\":\"") + 9;
                            int end = errorMsg.indexOf("\"", start);
                            if (start > 8 && end > start) {
                                errorMsg = errorMsg.substring(start, end);
                            }
                        } catch (Exception ignore) {}
                    }
                    errorLabel.setText(errorMsg != null ? errorMsg : "网络请求失败");
                    errorLabel.setVisible(true);
                    errorLabel.setManaged(true);
                    confirmBtn.setDisable(false);
                    confirmBtn.setText("绑定");
                });
                return null;
            });
    }
    
    /**
     * 绑定成功后的处理（统一处理扫码绑定和手动绑定）
     */
    private void refreshLoginAfterBind(String newDeviceId, String newDeviceUsername) {
        LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
        if (loginResp == null) return;
        
        // 判断当前是否已有设备ID
        String currentDeviceId = loginResp.getCurrentDeviceId();
        boolean hasExistingDevice = currentDeviceId != null && !currentDeviceId.isEmpty();
        
        if (hasExistingDevice) {
            // 已有设备在使用，只提示绑定成功，不切换设备
            LogTools.getInstance().logRecord3("📱 绑定新设备成功（当前已有设备在使用）: " + newDeviceUsername);
            updateStatusLabel("绑定成功：" + newDeviceUsername);
            showSuccessDialog("设备绑定成功");
        } else {
            // 没有设备，使用新绑定的设备
            loginResp.setCurrentDeviceId(newDeviceId);
            loginResp.setCurrentDeviceUsername(newDeviceUsername);
            AuthStore.getInstance().saveLoginResponse(loginResp);
            
            // 重新初始化连接
            LogTools.getInstance().logRecord3("📱 首次绑定设备，初始化连接: " + newDeviceUsername);
            updateStatusLabel("绑定成功，正在初始化连接...");
            showSuccessDialog("设备绑定成功");
            initAfterDeviceBinding(newDeviceId);
        }
    }
    
    /**
     * 检查并验证绑定
     * 第一步：先查询待验证的绑定
     * 第二步：如果有则验证，如果没有则提示
     */
    private void checkAndVerifyBinding(String secondaryPassword) {
        updateStatusLabel("正在检查待验证绑定...");
        
        HttpClientManager httpClient = HttpClientManager.getInstance();
        
        // 第一步：查询待验证的绑定
        httpClient.get("/api/binding/pending", PendingBindingResponse.class)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        PendingBindingResponse data = response.getData();
                        
                        // 检查是否有待验证的绑定
                        if (data.getBindings() != null && !data.getBindings().isEmpty()) {
                            // 找到第一个待验证的绑定
                            BindingInfo binding = data.getBindings().get(0);
                            
                            // 第二步：直接验证密码
                            verifyBindingPassword(binding.getBindingId(), secondaryPassword);
                        } else {
                            // 没有待验证的绑定，提示iOS端还未确定
                            updateStatusLabel("iOS端还未确定绑定");
                            showInfoDialog("设备绑定", "iOS端还未确定绑定，请先在iOS设备上确认绑定");
                        }
                    } else {
                        String errorMsg = response.getMessage() != null ? response.getMessage() : "未知错误";
                        updateStatusLabel("查询待验证绑定失败：" + errorMsg);
                        showInfoDialog("设备绑定", "查询失败：" + errorMsg);
                    }
                });
            })
            .exceptionally(throwable -> {
                Platform.runLater(() -> {
                    updateStatusLabel("查询待验证绑定失败：" + throwable.getMessage());
                    showInfoDialog("设备绑定", "网络请求失败：" + throwable.getMessage());
                });
                return null;
            });
    }
    
    /**
     * 验证绑定密码
     */
    private void verifyBindingPassword(Long bindingId, String secondaryPassword) {
        updateStatusLabel("正在验证设备绑定...");
        
        HttpClientManager httpClient = HttpClientManager.getInstance();
        BindingVerifyRequest request = new BindingVerifyRequest(bindingId, secondaryPassword);
        
        httpClient.post("/api/binding/verify-control", request, BindingVerifyResponse.class)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        BindingVerifyResponse data = response.getData();
                        
                        if (data.isSuccess()) {
                            // 验证成功，获取iOS设备号
                            String newDeviceId = data.getDeviceId();
                            String newDeviceUsername = data.getDeviceUsername();
                            
                            // 统一调用绑定成功后的处理（会弹出提示框）
                            refreshLoginAfterBind(newDeviceId, newDeviceUsername);
                        } else {
                            String errorMsg = data.getError() != null ? data.getError() : 
                                (data.getMessage() != null ? data.getMessage() : "验证失败");
                            updateStatusLabel("设备绑定验证失败：" + errorMsg);
                            showInfoDialog("设备绑定", "验证失败：" + errorMsg);
                        }
                    } else {
                        String errorMsg = response.getMessage() != null ? response.getMessage() : "未知错误";
                        updateStatusLabel("设备绑定验证失败：" + errorMsg);
                        showInfoDialog("设备绑定", "验证失败：" + errorMsg);
                    }
                });
            })
            .exceptionally(throwable -> {
                Platform.runLater(() -> {
                    updateStatusLabel("设备绑定验证失败：" + throwable.getMessage());
                    showInfoDialog("设备绑定", "网络请求失败：" + throwable.getMessage());
                });
                return null;
            });
    }
    
    /**
     * 设备绑定成功后的初始化流程
     * 预取配置并建立 STOMP 连接
     */
    private void initAfterDeviceBinding(String deviceId) {
        updateStatusLabel("正在初始化设备连接...");
        
        // 预取简化配置
        ConfigStore.getInstance().prefetchThinConfig(deviceId)
            .thenAccept(cfg -> {
                // 预取完成后建立 STOMP 连接
                Platform.runLater(() -> {
                    updateStatusLabel("配置获取成功，正在建立连接...");
                    initStompConnection();
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    System.err.println("设备绑定后预取配置失败: " + ex.getMessage());
                    // 预取失败也尝试建立 STOMP 连接
                    initStompConnection();
                });
                return null;
            });
    }
    
    /**
     * 处理相机设定
     */
    @FXML
    public void handleCameraSettings() {
        updateStatusLabel("打开相机设定");
        try {
            Stage stage = getStage();
            if (stage != null) {
                CameraSettingsDialogController.showDialogWithoutFXML(stage);
            } else {
                showInfoDialog("相机设定", "无法获取窗口引用，稍后重试");
            }
        } catch (Exception e) {
            e.printStackTrace();
            showInfoDialog("相机设定", "打开设定弹窗失败：" + e.getMessage());
        }
    }
    
    /**
     * 处理快捷键说明
     */
    @FXML
    private void handleShortcutKeys(javafx.event.ActionEvent event) {
        updateStatusLabel("显示快捷键说明");
        showShortcutKeysDialog(event);
    }
    
    /**
     * ⭐ 处理AI功能按钮点击 - 切换慢放窗口的 AI 视图
     */
    @FXML
    private void handleAiFunction() {
        if (element2_2JpegController != null) {
            element2_2JpegController.toggleAiView();
            boolean isAiShowing = element2_2JpegController.isAiViewShowing();
            
            // 更新按钮文字（需要更新 graphic 中的 Label，而不是按钮本身的 text）
            if (aiFunctionButton != null && aiFunctionButton.getGraphic() instanceof javafx.scene.layout.HBox) {
                javafx.scene.layout.HBox hbox = (javafx.scene.layout.HBox) aiFunctionButton.getGraphic();
                for (javafx.scene.Node node : hbox.getChildren()) {
                    if (node instanceof Label) {
                        ((Label) node).setText(isAiShowing ? "慢放" : "AI");
                        break;
                    }
                }
            }
            
            // ⚡ 切换到 AI 视图后，延迟刷新布局尺寸
            if (isAiShowing) {
                Platform.runLater(() -> {
                    element2_2JpegController.refreshAiLayoutSize();
                    Platform.runLater(() -> element2_2JpegController.refreshAiLayoutSize());
                });
            }
            
            updateStatusLabel(isAiShowing ? "切换到 AI 视图" : "切换到慢放视图");
        } else {
            updateStatusLabel("AI功能暂不可用");
        }
    }
    
    /**
     * ⭐ 切换横向/纵向排列
     */
    @FXML
    private void toggleArrangementMode() {
        isHorizontalArrangement = !isHorizontalArrangement;
        if (arrangementModeLabel != null) {
            arrangementModeLabel.setText(isHorizontalArrangement ? "横向排列" : "纵向排列");
        }
        updateStatusLabel(isHorizontalArrangement ? "切换到横向排列" : "切换到纵向排列");
        
        // 调用原有的排列切换逻辑
        if (element1Controller != null) {
            element1Controller.setLayoutDirection(isHorizontalArrangement);
        }
    }
    
    // ==================== 窗口控制方法 ====================
    
    /**
     * 处理头像按钮点击 - 显示下拉菜单（使用与行下拉相同的样式）
     */
    @FXML
    private void handleAvatar() {
        // 创建深色主题的 ContextMenu（使用与行下拉相同的样式）
        ContextMenu avatarMenu = new ContextMenu();
        avatarMenu.getStyleClass().add("dark-menu-button");
        avatarMenu.setStyle("-fx-background-color: #292929; -fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8; -fx-border-width: 1; -fx-padding: 4;");
        
        // 切换账号菜单项
        MenuItem switchAccountItem = new MenuItem("切换账号");
        switchAccountItem.setOnAction(e -> showSwitchAccountDialog());
        
        // 退出登录菜单项
        MenuItem logoutItem = new MenuItem("退出登录");
        logoutItem.setOnAction(e -> handleLogout());
        
        avatarMenu.getItems().addAll(switchAccountItem, logoutItem);
        
        // 在按钮下方显示菜单
        avatarMenu.show(avatarButton, javafx.geometry.Side.BOTTOM, 0, 5);
    }
    
    /**
     * 处理退出登录
     */
    private void handleLogout() {
        Stage mainStage = getStage();
        // 确认对话框
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        alert.setTitle("退出登录");
        alert.setHeaderText(null);
        alert.setContentText("确定要退出登录吗？");
        alert.getDialogPane().setStyle("-fx-background-color: #1F1F1F;");
        alert.getDialogPane().lookup(".content").setStyle("-fx-text-fill: #FAFAFA;");
        
        // ⭐ 全屏保护
        alert.setOnShowing(e -> { if (mainStage != null) mainStage.toFront(); });
        alert.setOnHidden(e -> { if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); }); });
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                performLogout();
            }
        });
    }
    
    /**
     * 执行退出登录操作
     */
    private void performLogout() {
        try {
            // 1. 停止实时流播放器
            if (corePlayer != null) {
                corePlayer.stop();
                System.out.println("✅ 实时流播放器已停止");
            }
            
            // 2. 停止慢放播放器
            if (element2_2JpegController != null) {
                element2_2JpegController.cleanup();
                System.out.println("✅ 慢放播放器已清理");
            }
            
            // 3. 断开 WebSocket 连接
            try {
                NetworkManager.getInstance().disconnectWebSocket();
                LogTools.getInstance().logRecord3("✅ WebSocket连接已断开");
            } catch (Exception e) {
                System.err.println("❌ 断开WebSocket失败: " + e.getMessage());
            }
            
            // 4. 清空缓存文件
            clearCacheFiles();
            
            // 5. 清除登录信息
            AuthStore.getInstance().clearLogin();
            System.out.println("✅ 登录信息已清除");
            
            // 6. 返回登录页面
            returnToLoginPage();
            
        } catch (Exception e) {
            System.err.println("❌ 退出登录失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 清空缓存文件（SSL文件夹等）
     */
    private void clearCacheFiles() {
        try {
            // 清空 runtime/captures 目录
            java.nio.file.Path capturesDir = java.nio.file.Paths.get("runtime", "captures");
            if (java.nio.file.Files.exists(capturesDir)) {
                java.nio.file.Files.walk(capturesDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            java.nio.file.Files.deleteIfExists(path);
                        } catch (Exception ignore) {}
                    });
                System.out.println("✅ 缓存文件已清空");
            }
        } catch (Exception e) {
            System.err.println("❌ 清空缓存文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 返回登录页面
     */
    private void returnToLoginPage() {
        try {
            // 加载登录界面
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/acard/acard/new-login-view.fxml"));
            Parent loginRoot = loader.load();
            
            // 获取当前窗口
            Stage currentStage = getStage();
            if (currentStage != null) {
                // 创建新的登录窗口
                Stage loginStage = new Stage();
                loginStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
                
                // 设置窗口大小
                double windowWidth = 520;
                double windowHeight = 780;
                
                Scene scene = new Scene(loginRoot, windowWidth, windowHeight);
                scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
                loginStage.setScene(scene);
                loginStage.setTitle("登录");
                
                // 固定窗口大小
                loginStage.setMinWidth(windowWidth);
                loginStage.setMaxWidth(windowWidth);
                loginStage.setMinHeight(windowHeight);
                loginStage.setMaxHeight(windowHeight);
                loginStage.setResizable(false);
                
                // 居中显示
                javafx.stage.Screen screen = javafx.stage.Screen.getPrimary();
                double screenWidth = screen.getVisualBounds().getWidth();
                double screenHeight = screen.getVisualBounds().getHeight();
                loginStage.setX((screenWidth - windowWidth) / 2);
                loginStage.setY((screenHeight - windowHeight) / 2);
                
                loginStage.show();
                
                // 关闭当前主窗口
                currentStage.close();
            }
        } catch (Exception e) {
            System.err.println("❌ 返回登录页面失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 显示切换账号对话框
     */
    private void showSwitchAccountDialog() {
        // 获取账号列表（与登录窗口一致，使用 AccountListStore）
        java.util.List<com.acard.acard.storage.AccountListStore.Account> accounts = 
            com.acard.acard.storage.AccountListStore.getInstance().getAllAccounts();
        
        if (accounts.isEmpty()) {
            showInfoDialog("切换账号", "没有可切换的账号，请先登录其他账号");
            return;
        }
        
        // 创建深色主题的对话框
        Stage mainStage = getStage();
        Stage dialogStage = new Stage();
        dialogStage.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        dialogStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        dialogStage.setTitle("切换账号");
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        dialogStage.setOnShowing(e -> {
            if (mainStage != null) mainStage.toFront();
        });
        dialogStage.setOnHidden(e -> {
            if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); });
        });
        
        // 主容器
        javafx.scene.layout.VBox mainContainer = new javafx.scene.layout.VBox(0);
        mainContainer.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 16;");
        mainContainer.setPrefWidth(600);
        mainContainer.setPrefHeight(500);
        
        // 标题行
        javafx.scene.layout.HBox titleBar = new javafx.scene.layout.HBox(10);
        titleBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        titleBar.setPadding(new javafx.geometry.Insets(0, 0, 16, 0));
        
        Label titleLabel = new Label("切换账号");
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 提示标签
        Label hintLabel = new Label("正在加载设备信息...");
        hintLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 13px;");
        javafx.scene.layout.HBox.setHgrow(hintLabel, javafx.scene.layout.Priority.ALWAYS);
        
        // 刷新按钮
        Button refreshBtn = new Button("🔄 刷新");
        refreshBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 6; -fx-padding: 6 12; -fx-font-size: 12px; -fx-cursor: hand;");
        refreshBtn.setOnMouseEntered(e -> refreshBtn.setStyle("-fx-background-color: #374151; -fx-text-fill: #FAFAFA; -fx-background-radius: 6; -fx-padding: 6 12; -fx-font-size: 12px; -fx-cursor: hand;"));
        refreshBtn.setOnMouseExited(e -> refreshBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 6; -fx-padding: 6 12; -fx-font-size: 12px; -fx-cursor: hand;"));
        
        titleBar.getChildren().addAll(titleLabel, hintLabel, refreshBtn);
        
        // 账号列表容器（带滚动）
        javafx.scene.layout.VBox accountListContainer = new javafx.scene.layout.VBox(12);
        accountListContainer.setStyle("-fx-padding: 0;");
        
        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(accountListContainer);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        javafx.scene.layout.VBox.setVgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);
        
        // 当前登录的账号
        LoginResponse currentResp = AuthStore.getInstance().getLoginResponse();
        String currentUsername = currentResp != null ? currentResp.getUsername() : "";
        
        // 用于记录选中的账号和设备
        final String[] selectedAccount = {null};
        final String[] selectedDevice = {null};
        
        // 设备映射（控制端账号 -> 设备列表）
        java.util.Map<String, java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem>> deviceMap = 
            new java.util.concurrent.ConcurrentHashMap<>();
        
        // 先渲染本地列表
        renderSwitchAccountList(accountListContainer, accounts, deviceMap, currentUsername, selectedAccount, selectedDevice, dialogStage);
        
        // 批量查询在线状态
        java.util.List<String> usernames = new java.util.ArrayList<>();
        for (com.acard.acard.storage.AccountListStore.Account account : accounts) {
            usernames.add(account.getUsername());
        }
        
        // 封装刷新设备状态的逻辑
        Runnable refreshDeviceStatus = () -> {
            hintLabel.setText("正在加载设备信息...");
            refreshBtn.setDisable(true);
            
            // ========== 调试日志：打印请求参数 ==========
            System.out.println("========== 切换账号 - 获取设备状态 ==========");
            System.out.println("📤 请求接口: POST /api/binding/online-status");
            System.out.println("📤 请求参数 controlUsernames: " + usernames);
            LogTools.getInstance().logRecord3("📤 请求接口: POST /api/binding/online-status");
            LogTools.getInstance().logRecord3("📤 请求参数 controlUsernames: " + usernames);
            
            NetworkManager.getInstance().getOnlineStatus(usernames)
                .thenAccept(resp -> {
                    Platform.runLater(() -> {
                        refreshBtn.setDisable(false);
                        
                        // ========== 调试日志：打印返回结果 ==========
                        System.out.println("📥 响应 success: " + resp.isSuccess());
                        System.out.println("📥 响应 message: " + resp.getMessage());
                        LogTools.getInstance().logRecord3("📥 响应 success: " + resp.isSuccess() + ", message: " + resp.getMessage());
                        
                        if (resp.isSuccess() && resp.getData() != null && resp.getData().getList() != null) {
                            java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> list = resp.getData().getList();
                            System.out.println("📥 返回设备总数: " + list.size());
                            LogTools.getInstance().logRecord3("📥 返回设备总数: " + list.size());
                            
                            // 打印每个设备的详细信息
                            for (int i = 0; i < list.size(); i++) {
                                com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem item = list.get(i);
                                String info = String.format("  [%d] 控制账号=%s, 设备账号=%s, 显示名=%s, 在线=%s, 已绑定=%s",
                                    i, item.getControlUsername(), item.getDeviceUsername(), 
                                    item.getDisplayText(), item.isOnline(), item.isBound());
                                System.out.println(info);
                                LogTools.getInstance().logRecord3(info);
                            }
                            
                            // 清空旧数据，重新分组
                            deviceMap.clear();
                            for (com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem item : list) {
                                String controlUsername = item.getControlUsername();
                                if (!deviceMap.containsKey(controlUsername)) {
                                    deviceMap.put(controlUsername, new java.util.ArrayList<>());
                                }
                                deviceMap.get(controlUsername).add(item);
                            }
                            
                            // 打印分组结果
                            System.out.println("📊 分组结果:");
                            for (String ctrl : deviceMap.keySet()) {
                                System.out.println("  账号[" + ctrl + "] -> " + deviceMap.get(ctrl).size() + "个设备");
                            }
                            
                            // 重新渲染列表
                            renderSwitchAccountList(accountListContainer, accounts, deviceMap, currentUsername, selectedAccount, selectedDevice, dialogStage);
                            hintLabel.setText("共 " + accounts.size() + " 个账号，点击选择");
                        } else {
                            System.out.println("❌ 获取设备状态失败: data=" + resp.getData());
                            hintLabel.setText("获取设备状态失败");
                        }
                        System.out.println("==============================================");
                    });
                })
                .exceptionally(ex -> {
                    System.out.println("❌ 网络错误: " + ex.getMessage());
                    ex.printStackTrace();
                    Platform.runLater(() -> {
                        refreshBtn.setDisable(false);
                        hintLabel.setText("网络错误");
                    });
                    return null;
                });
        };
        
        // 刷新按钮点击事件
        refreshBtn.setOnAction(e -> refreshDeviceStatus.run());
        
        // 初始加载
        refreshDeviceStatus.run();
        
        // 取消按钮
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 30;");
        cancelBtn.setOnAction(e -> dialogStage.close());
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle("-fx-background-color: #374151; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 30;"));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle("-fx-background-color: #292929; -fx-text-fill: #FAFAFA; -fx-background-radius: 8; -fx-padding: 10 30;"));
        
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox();
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        buttonBox.setPadding(new javafx.geometry.Insets(16, 0, 0, 0));
        buttonBox.getChildren().add(cancelBtn);
        
        mainContainer.getChildren().addAll(titleBar, scrollPane, buttonBox);
        
        // 添加拖动功能
        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        titleBar.setOnMouseDragged(e -> {
            dialogStage.setX(e.getScreenX() - dragOffset[0]);
            dialogStage.setY(e.getScreenY() - dragOffset[1]);
        });
        
        Scene scene = new Scene(mainContainer);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialogStage.setScene(scene);
        
        // 居中显示
        dialogStage.setOnShown(e -> {
            Stage owner = getStage();
            if (owner != null) {
                dialogStage.setX(owner.getX() + (owner.getWidth() - dialogStage.getWidth()) / 2);
                dialogStage.setY(owner.getY() + (owner.getHeight() - dialogStage.getHeight()) / 2);
            }
        });
        
        // ⭐ 直接监听主窗口状态，自动关闭弹框（对 showAndWait 更可靠）
        if (mainStage != null) {
            javafx.beans.value.ChangeListener<Boolean> iconifiedListener = (obs, wasIconified, isIconified) -> {
                if (isIconified && dialogStage.isShowing()) {
                    dialogStage.close();
                }
            };
            javafx.beans.value.ChangeListener<Boolean> focusedListener = (obs, wasFocused, isFocused) -> {
                if (!isFocused && dialogStage.isShowing() && !dialogStage.isFocused()) {
                    Platform.runLater(() -> {
                        if (!mainStage.isFocused() && !dialogStage.isFocused() && dialogStage.isShowing()) {
                            dialogStage.close();
                        }
                    });
                }
            };
            mainStage.iconifiedProperty().addListener(iconifiedListener);
            mainStage.focusedProperty().addListener(focusedListener);
            
            // 弹框关闭时移除监听器
            dialogStage.setOnHidden(e -> {
                mainStage.iconifiedProperty().removeListener(iconifiedListener);
                mainStage.focusedProperty().removeListener(focusedListener);
            });
        }
        
        dialogStage.showAndWait();
    }
    
    /**
     * 渲染切换账号列表（带二级设备列表）
     */
    private void renderSwitchAccountList(
            javafx.scene.layout.VBox container,
            java.util.List<com.acard.acard.storage.AccountListStore.Account> accounts,
            java.util.Map<String, java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem>> deviceMap,
            String currentUsername,
            String[] selectedAccount,
            String[] selectedDevice,
            Stage dialogStage) {
        
        container.getChildren().clear();
        
        // 获取当前正在使用的设备账号
        LoginResponse currentResp = AuthStore.getInstance().getLoginResponse();
        String currentDeviceUsername = currentResp != null ? currentResp.getCurrentDeviceUsername() : null;
        
        for (com.acard.acard.storage.AccountListStore.Account accountInfo : accounts) {
            String username = accountInfo.getUsername();
            java.util.List<com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem> devices = deviceMap.get(username);
            boolean isCurrentAccount = username.equals(currentUsername);
            
            // 账号卡片
            javafx.scene.layout.VBox card = new javafx.scene.layout.VBox(0);
            card.setStyle("-fx-background-color: #292929; -fx-background-radius: 12;");
            
            // ========== 一级：控制端账号头部 ==========
            javafx.scene.layout.HBox header = new javafx.scene.layout.HBox(12);
            header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            header.setStyle("-fx-padding: 14 16; -fx-cursor: hand;");
            
            // 头像
            javafx.scene.layout.StackPane avatar = new javafx.scene.layout.StackPane();
            avatar.setPrefSize(44, 44);
            avatar.setMinSize(44, 44);
            avatar.setMaxSize(44, 44);
            avatar.setStyle("-fx-background-color: linear-gradient(to bottom right, #607AFB, #8B5CF6); -fx-background-radius: 22;");
            Label avatarLabel = new Label(username.substring(0, 1).toUpperCase());
            avatarLabel.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");
            avatar.getChildren().add(avatarLabel);
            
            // 文字区域
            javafx.scene.layout.VBox textBox = new javafx.scene.layout.VBox(2);
            javafx.scene.layout.HBox.setHgrow(textBox, javafx.scene.layout.Priority.ALWAYS);
            
            // 用户名行（可能带当前标记）
            javafx.scene.layout.HBox usernameRow = new javafx.scene.layout.HBox(8);
            usernameRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            Label usernameLabel = new Label(username);
            usernameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: 600;");
            usernameRow.getChildren().add(usernameLabel);
            
            if (isCurrentAccount) {
                Label currentTag = new Label("(当前)");
                currentTag.setStyle("-fx-text-fill: #607AFB; -fx-font-size: 12px;");
                usernameRow.getChildren().add(currentTag);
            }
            
            // 设备数量提示
            int deviceCount = devices != null ? devices.size() : 0;
            long onlineCount = devices != null ? devices.stream().filter(d -> d.isOnline()).count() : 0;
            String hint = deviceCount > 0 
                ? deviceCount + " 个设备，" + onlineCount + " 个在线" 
                : "未绑定设备";
            Label hintLabel = new Label(hint);
            hintLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
            
            textBox.getChildren().addAll(usernameRow, hintLabel);
            
            // 展开/折叠箭头
            Label arrowLabel = new Label(deviceCount > 0 ? "▼" : "→");
            arrowLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
            
            header.getChildren().addAll(avatar, textBox, arrowLabel);
            card.getChildren().add(header);
            
            // ========== 二级：设备列表（默认折叠） ==========
            javafx.scene.layout.VBox deviceListBox = new javafx.scene.layout.VBox(8);
            deviceListBox.setStyle("-fx-padding: 0 16 12 76;");
            deviceListBox.setVisible(false);
            deviceListBox.setManaged(false);
            
            if (devices != null && !devices.isEmpty()) {
                for (com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device : devices) {
                    javafx.scene.layout.HBox deviceRow = new javafx.scene.layout.HBox(8);
                    deviceRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    
                    // 设备信息
                    String deviceUsername = device.getDeviceUsername();
                    
                    // 判断是否是当前正在使用的设备
                    boolean isCurrentDevice = isCurrentAccount && 
                        currentDeviceUsername != null && 
                        deviceUsername != null && 
                        currentDeviceUsername.equals(deviceUsername);
                    
                    // 根据是否是当前设备调整样式
                    String rowBaseStyle = isCurrentDevice 
                        ? "-fx-padding: 16 12; -fx-background-color: #2a3a5a; -fx-background-radius: 8; -fx-cursor: hand; -fx-border-color: #607AFB; -fx-border-radius: 8; -fx-border-width: 1;"
                        : "-fx-padding: 16 12; -fx-background-color: #1F1F1F; -fx-background-radius: 8; -fx-cursor: hand;";
                    String rowHoverStyle = isCurrentDevice
                        ? "-fx-padding: 16 12; -fx-background-color: #3a4a6a; -fx-background-radius: 8; -fx-cursor: hand; -fx-border-color: #607AFB; -fx-border-radius: 8; -fx-border-width: 1;"
                        : "-fx-padding: 16 12; -fx-background-color: #3a3a3a; -fx-background-radius: 8; -fx-cursor: hand;";
                    
                    deviceRow.setStyle(rowBaseStyle);
                    
                    // 在线状态点
                    javafx.scene.shape.Circle statusDot = new javafx.scene.shape.Circle(5);
                    statusDot.setFill(device.isOnline() 
                        ? javafx.scene.paint.Color.web("#34C759") 
                        : javafx.scene.paint.Color.web("#666666"));
                    
                    // 设备名称（使用 getDisplayText：昵称 或 昵称(备注)）
                    String displayName = device.getDisplayText();
                    
                    Label nameLabel = new Label(displayName);
                    nameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
                    javafx.scene.layout.HBox.setHgrow(nameLabel, javafx.scene.layout.Priority.ALWAYS);
                    
                    // ⭐ 设置备注按钮
                    Button remarkBtn = new Button("备注");
                    remarkBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;");
                    remarkBtn.setOnMouseEntered(e -> remarkBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #ffffff; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;"));
                    remarkBtn.setOnMouseExited(e -> remarkBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;"));
                    
                    final String finalUsername = username;
                    final String finalDeviceUsername = deviceUsername;
                    final com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem finalDevice = device;
                    remarkBtn.setOnAction(e -> {
                        e.consume();
                        showSetRemarkDialog(finalUsername, finalDeviceUsername, finalDevice.getRemark(), nameLabel, finalDevice);
                    });
                    
                    // ⭐ 解绑按钮（跟备注按钮样式一致）
                    Button unbindBtn = new Button("解绑");
                    unbindBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;");
                    unbindBtn.setOnMouseEntered(e -> unbindBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: #ffffff; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;"));
                    unbindBtn.setOnMouseExited(e -> unbindBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 4; -fx-cursor: hand;"));
                    
                    final boolean finalIsCurrentAccount = isCurrentAccount;
                    final Long bindingId = device.getBindingId();
                    unbindBtn.setOnAction(e -> {
                        e.consume();
                        // 只能解绑当前登录账号的设备
                        if (!finalIsCurrentAccount) {
                            showDarkTipDialog("提示", "请先切换到该账号后再进行解绑操作");
                            return;
                        }
                        if (bindingId == null) {
                            showDarkTipDialog("提示", "无法获取绑定信息，请刷新后重试");
                            return;
                        }
                        // 显示解绑确认弹框（传递昵称用于显示）
                        showUnbindConfirmDialog(bindingId, finalUsername, finalDeviceUsername, finalDevice.getDisplayText(), dialogStage);
                    });
                    
                    // 当前使用标记
                    if (isCurrentDevice) {
                        Label currentTag = new Label("(当前使用)");
                        currentTag.setStyle("-fx-text-fill: #607AFB; -fx-font-size: 12px; -fx-font-weight: bold;");
                        deviceRow.getChildren().addAll(statusDot, nameLabel, currentTag, remarkBtn, unbindBtn);
                    } else {
                        // 状态文字
                        Label statusLabel = new Label(device.isOnline() ? "在线" : "离线");
                        statusLabel.setStyle("-fx-text-fill: " + (device.isOnline() ? "#34C759" : "#666666") + "; -fx-font-size: 12px;");
                        deviceRow.getChildren().addAll(statusDot, nameLabel, statusLabel, remarkBtn, unbindBtn);
                    }
                    
                    // 点击设备行 - 切换到该账号和设备（排除点击备注按钮和解绑按钮）
                    final String finalRowBaseStyle = rowBaseStyle;
                    final String finalRowHoverStyle = rowHoverStyle;
                    deviceRow.setOnMouseClicked(e -> {
                        // 如果点击的是备注按钮或解绑按钮，不处理
                        if (e.getTarget() == remarkBtn || remarkBtn.isHover() || 
                            e.getTarget() == unbindBtn || unbindBtn.isHover()) {
                            return;
                        }
                        e.consume();
                        // 如果不是当前账号或不是当前设备，才允许切换
                        if (!isCurrentAccount || !isCurrentDevice) {
                            dialogStage.close();
                            switchToAccountWithDevice(username, finalDeviceUsername);
                        }
                    });
                    
                    // 悬停效果
                    deviceRow.setOnMouseEntered(e -> deviceRow.setStyle(finalRowHoverStyle));
                    deviceRow.setOnMouseExited(e -> deviceRow.setStyle(finalRowBaseStyle));
                    
                    deviceListBox.getChildren().add(deviceRow);
                }
            }
            
            card.getChildren().add(deviceListBox);
            
            // 点击头部：展开/折叠设备列表，或直接切换（无设备时）
            header.setOnMouseClicked(e -> {
                if (deviceCount > 0) {
                    // 有设备，展开/折叠
                    boolean isExpanded = deviceListBox.isVisible();
                    deviceListBox.setVisible(!isExpanded);
                    deviceListBox.setManaged(!isExpanded);
                    arrowLabel.setText(isExpanded ? "▼" : "▲");
                } else {
                    // 无设备，直接切换账号
                    if (!isCurrentAccount) {
                        dialogStage.close();
                        switchToAccount(username);
                    }
                }
            });
            
            // 悬停效果
            header.setOnMouseEntered(e -> header.setStyle("-fx-padding: 14 16; -fx-cursor: hand; -fx-background-color: #3a3a3a; -fx-background-radius: 12;"));
            header.setOnMouseExited(e -> header.setStyle("-fx-padding: 14 16; -fx-cursor: hand;"));
            
            container.getChildren().add(card);
        }
    }
    
    /**
     * 切换到指定账号和设备
     */
    private void switchToAccountWithDevice(String username, String deviceUsername) {
        LogTools.getInstance().logRecord3("🔄 切换账号: " + username + ", 设备: " + deviceUsername);
        
        // 保存选择的设备到临时存储
        if (deviceUsername != null) {
            LocalStorage.getInstance().putString("switch_device_username", deviceUsername);
        }
        
        // 调用原有的切换账号方法
        switchToAccount(username);
    }
    
    /**
     * ⭐ 显示解绑确认对话框
     * 只能解绑当前登录账号的设备
     * @param displayName 显示名称（昵称或昵称+备注）
     */
    private void showUnbindConfirmDialog(Long bindingId, String username, String deviceUsername, String displayName, Stage parentDialog) {
        // 从缓存获取当前账号的密码
        com.acard.acard.storage.AccountListStore.Account account = 
            com.acard.acard.storage.AccountListStore.getInstance().getAccount(username);
        if (account == null || account.getPassword() == null || account.getPassword().isEmpty()) {
            showInfoDialog("提示", "无法获取账号密码，请重新登录后再试");
            return;
        }
        final String cachedPassword = account.getPassword();
        
        // 创建确认对话框
        Stage mainStage = getStage();
        Stage confirmDialog = new Stage();
        confirmDialog.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        confirmDialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        confirmDialog.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        confirmDialog.setTitle("确认解绑");
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        confirmDialog.setOnShowing(e -> {
            if (mainStage != null) mainStage.toFront();
        });
        confirmDialog.setOnHidden(e -> {
            if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); });
        });
        
        // 主容器
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(16);
        container.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 24; -fx-border-color: #3a3a3a; -fx-border-radius: 12; -fx-border-width: 1;");
        container.setPrefWidth(360);
        container.setAlignment(javafx.geometry.Pos.CENTER);
        
        // 警告图标
        Label iconLabel = new Label("⚠️");
        iconLabel.setStyle("-fx-font-size: 36px;");
        
        // 标题
        Label titleLabel = new Label("确认解绑设备？");
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 设备信息（显示昵称）
        Label deviceInfoLabel = new Label("设备: " + (displayName != null && !displayName.isEmpty() ? displayName : "未知设备"));
        deviceInfoLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px;");
        
        // 提示文字
        Label hintLabel = new Label("解绑后将断开与该iOS设备的连接\n此操作不可恢复！");
        hintLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 13px; -fx-text-alignment: center;");
        hintLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        
        // 状态标签
        Label statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        
        // 按钮区域
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox(16);
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER);
        buttonBox.setPadding(new javafx.geometry.Insets(8, 0, 0, 0));
        
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;");
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;"));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;"));
        cancelBtn.setOnAction(e -> confirmDialog.close());
        
        Button confirmBtn = new Button("确认解绑");
        confirmBtn.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;");
        confirmBtn.setOnMouseEntered(e -> confirmBtn.setStyle("-fx-background-color: #B91C1C; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;"));
        confirmBtn.setOnMouseExited(e -> confirmBtn.setStyle("-fx-background-color: #DC2626; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 32; -fx-background-radius: 8; -fx-cursor: hand;"));
        LogTools.getInstance().logRecord5("========== 解绑接口调试信息--------》》》》》 ==========");
        confirmBtn.setOnAction(e -> {
            // 显示加载状态
            confirmBtn.setDisable(true);
            confirmBtn.setText("解绑中...");
            statusLabel.setText("正在解绑...");
            statusLabel.setStyle("-fx-text-fill: #60A5FA; -fx-font-size: 12px;");
            
            // 调用解绑接口
            com.acard.acard.net.WindowsUnbindRequest request = new com.acard.acard.net.WindowsUnbindRequest(cachedPassword);
            
            // ⭐ 打印详细调试信息
            String endpoint = "/api/binding/windows-unbind/" + bindingId;
            String fullUrl = com.acard.acard.net.NetworkConfig.getInstance().buildApiUrl(endpoint);
            String token = com.acard.acard.net.NetworkConfig.getInstance().getAuthToken();
            LogTools.getInstance().logRecord5("========== 解绑接口调试信息 ==========");
            LogTools.getInstance().logRecord5("📍 完整URL: " + fullUrl);
            LogTools.getInstance().logRecord5("🔑 Token: " + (token != null ? token : "【空】"));
            LogTools.getInstance().logRecord5("📦 请求参数: { password: '" + cachedPassword + "', bindingId: " + bindingId + " }");
            LogTools.getInstance().logRecord5("======================================");
            LogTools.getInstance().logRecord5("🔗 解绑接口: " + fullUrl + ", Token存在: " + (token != null && !token.isEmpty()));
            
            NetworkManager.getInstance()
                .post(endpoint, request, com.acard.acard.net.WindowsUnbindResponse.class)
                .thenAccept(resp -> {
                    Platform.runLater(() -> {
                        if (resp.isSuccess() && resp.getData() != null && resp.getData().isSuccess()) {
                            // 解绑成功
                            LogTools.getInstance().logRecord3("✅ 解绑成功: bindingId=" + bindingId);
                            statusLabel.setText("✅ 解绑成功");
                            statusLabel.setStyle("-fx-text-fill: #34C759; -fx-font-size: 12px;");
                            
                            // ⭐ 检查是否是当前正在推流的设备，如果是则停止播放
                            try {
                                com.acard.acard.net.LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
                                String currentDeviceUsername = loginResp != null ? loginResp.getCurrentDeviceUsername() : null;
                                
                                // 判断解绑的设备是否是当前绑定的设备
                                if (currentDeviceUsername != null && deviceUsername != null && currentDeviceUsername.equals(deviceUsername)) {
                                    LogTools.getInstance().logRecord3("✅ 解绑的是当前设备，停止播放: " + deviceUsername);
                                    
                                    // 1. 停止录制回调
                                    FileToos.isCallBack = false;
                                    FileToos.FbRecordingStoppedEvent();
                                    
                                    // 2. 停止播放器
                                    if (publishState == 1 && corePlayer != null) {
                                        corePlayer.setJpegSaveEnabled(false);
                                        corePlayer.stop();
                                        LogTools.getInstance().logRecord3("✅ 已停止播放器（当前设备已解绑）");
                                    }
                                    publishState = 0;
                                    isPlayerPlaying = false;
                                    FileToos.isIsCallBackFrame = false;
                                    
                                    // 3. 清除设备信息
                                    loginResp.setDeviceId("");
                                    loginResp.setCurrentDeviceId("");
                                    loginResp.setCurrentDeviceUsername("");
                                    AuthStore.getInstance().saveLoginResponse(loginResp);
                                    
                                    // 4. 更新UI状态
                                    if (monitorLinkStatusValue != null) {
                                        monitorLinkStatusValue.setText("已解绑");
                                        monitorLinkStatusValue.setTextFill(javafx.scene.paint.Color.RED);
                                    }
                                    
                                    // 5. 恢复底部状态栏
                                    resetBottomStatusBar();
                                    
                                    // 6. 通知播放器显示"暂无视频"
                                    if (element2_1Player != null) {
                                        element2_1Player.onIosDisconnected();
                                    }
                                    
                                    // 7. 更新控制端连接状态
                                    updateControlLinkStatus(false);
                                }
                            } catch (Exception ex) {
                                System.err.println("⚠️ 停止播放失败: " + ex.getMessage());
                                ex.printStackTrace();
                            }
                            
                            // 延迟关闭对话框
                            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(800));
                            pause.setOnFinished(ev -> {
                                confirmDialog.close();
                                // 关闭父对话框（账号切换对话框），让用户重新打开刷新列表
                                if (parentDialog != null) {
                                    parentDialog.close();
                                }
                                showInfoDialog("解绑成功", "设备已成功解绑，请重新选择设备");
                            });
                            pause.play();
                        } else {
                            // 解绑失败 - 显示详细错误信息
                            int httpCode = resp.getCode();
                            String errorMsg = resp.getData() != null && resp.getData().getError() != null 
                                ? resp.getData().getError() 
                                : (resp.getMessage() != null ? resp.getMessage() : "解绑失败");
                            
                            // ⭐ 打印详细错误日志
                            LogTools.getInstance().logRecord5("❌ 解绑失败详情:");
                            LogTools.getInstance().logRecord5("   HTTP状态码: " + httpCode);
                            LogTools.getInstance().logRecord5("   错误信息: " + errorMsg);
                            LogTools.getInstance().logRecord5("   resp.isSuccess(): " + resp.isSuccess());
                            LogTools.getInstance().logRecord5("   resp.getMessage(): " + resp.getMessage());
                            LogTools.getInstance().logRecord5("   resp.getData(): " + resp.getData());
                            if (resp.getData() != null) {
                                LogTools.getInstance().logRecord5("   resp.getData().getError(): " + resp.getData().getError());
                                LogTools.getInstance().logRecord5("   resp.getData().getMessage(): " + resp.getData().getMessage());
                            }
                            
                            // ⭐ 显示包含状态码的详细错误
                            String displayMsg = httpCode != 200 && httpCode != 0 
                                ? "(" + httpCode + ") " + errorMsg 
                                : errorMsg;
                            statusLabel.setText("❌ " + displayMsg);
                            statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");
                            confirmBtn.setDisable(false);
                            confirmBtn.setText("确认解绑");
                            LogTools.getInstance().logRecord5("❌ 解绑失败: HTTP " + httpCode + " - " + errorMsg);
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        statusLabel.setText("❌ 网络错误: " + ex.getMessage());
                        statusLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");
                        confirmBtn.setDisable(false);
                        confirmBtn.setText("确认解绑");
                        LogTools.getInstance().logRecord3("❌ 解绑异常: " + ex.getMessage());
                    });
                    return null;
                });
        });
        
        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        
        container.getChildren().addAll(iconLabel, titleLabel, deviceInfoLabel, hintLabel, statusLabel, buttonBox);
        
        // 设置场景
        Scene scene = new Scene(container);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        confirmDialog.setScene(scene);
        
        // 窗口拖动
        final double[] dragOffset = new double[2];
        container.setOnMousePressed(ev -> {
            dragOffset[0] = ev.getSceneX();
            dragOffset[1] = ev.getSceneY();
        });
        container.setOnMouseDragged(ev -> {
            confirmDialog.setX(ev.getScreenX() - dragOffset[0]);
            confirmDialog.setY(ev.getScreenY() - dragOffset[1]);
        });
        
        // 居中显示
        confirmDialog.setOnShown(ev -> {
            Stage owner = getStage();
            if (owner != null) {
                confirmDialog.setX(owner.getX() + (owner.getWidth() - confirmDialog.getWidth()) / 2);
                confirmDialog.setY(owner.getY() + (owner.getHeight() - confirmDialog.getHeight()) / 2);
            }
        });
        
        confirmDialog.showAndWait();
    }
    
    /**
     * 显示设置备注对话框
     */
    private void showSetRemarkDialog(String controlUsername, String deviceUsername, String currentRemark, 
            Label nameLabel, com.acard.acard.net.OnlineStatusResponse.OnlineStatusItem device) {
        // 创建对话框
        Stage mainStage = getStage();
        Stage remarkDialog = new Stage();
        remarkDialog.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        remarkDialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        remarkDialog.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        remarkDialog.setTitle("设置备注");
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        remarkDialog.setOnShowing(e -> {
            if (mainStage != null) mainStage.toFront();
        });
        remarkDialog.setOnHidden(e -> {
            if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); });
        });
        
        // 主容器
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(16);
        container.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 24; -fx-border-color: #3a3a3a; -fx-border-radius: 12; -fx-border-width: 1;");
        container.setPrefWidth(320);
        
        // 标题
        Label titleLabel = new Label("设置备注");
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 16px; -fx-font-weight: bold;");
        
        // 设备信息
        String deviceNickname = device.getDeviceNickname();
        String displayInfo = (deviceNickname != null && !deviceNickname.isEmpty()) ? deviceNickname : deviceUsername;
        Label deviceInfoLabel = new Label("设备: " + displayInfo);
        deviceInfoLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px;");
        
        // 备注输入框
        TextField remarkField = new TextField();
        remarkField.setPromptText("请输入备注（留空则清除备注）");
        remarkField.setText(currentRemark != null ? currentRemark : "");
        remarkField.setStyle("-fx-background-color: #292929; -fx-text-fill: white; -fx-prompt-text-fill: #64748b; -fx-font-size: 14px; -fx-padding: 12; -fx-background-radius: 8; -fx-border-color: #3a3a3a; -fx-border-radius: 8;");
        remarkField.setPrefHeight(40);
        
        // 提示文字
        Label hintLabel = new Label("备注将显示在设备名称后面");
        hintLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        
        // 按钮区域
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox(12);
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;");
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle("-fx-background-color: #4a4a4a; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;"));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle("-fx-background-color: #3a3a3a; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;"));
        cancelBtn.setOnAction(e -> remarkDialog.close());
        
        Button confirmBtn = new Button("确定");
        confirmBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;");
        confirmBtn.setOnMouseEntered(e -> confirmBtn.setStyle("-fx-background-color: #7089fc; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;"));
        confirmBtn.setOnMouseExited(e -> confirmBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 24; -fx-background-radius: 8; -fx-cursor: hand;"));
        confirmBtn.setOnAction(e -> {
            String newRemark = remarkField.getText().trim();
            
            // 显示加载状态
            confirmBtn.setDisable(true);
            confirmBtn.setText("保存中...");
            
            // 调用接口设置备注
            NetworkManager.getInstance().setRemark(controlUsername, deviceUsername, newRemark)
                .thenAccept(resp -> {
                    Platform.runLater(() -> {
                        if (resp.isSuccess() && resp.getData() != null && resp.getData().isSuccess()) {
                            // 更新设备的 remark
                            device.setRemark(newRemark);
                            // 更新显示
                            nameLabel.setText(device.getDisplayText());
                            LogTools.getInstance().logRecord3("✅ 设置备注成功: " + newRemark);
                            remarkDialog.close();
                        } else {
                            String errorMsg = resp.getData() != null && resp.getData().getError() != null 
                                ? resp.getData().getError() 
                                : (resp.getMessage() != null ? resp.getMessage() : "设置失败");
                            hintLabel.setText("❌ " + errorMsg);
                            hintLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");
                            confirmBtn.setDisable(false);
                            confirmBtn.setText("确定");
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        hintLabel.setText("❌ 网络错误: " + ex.getMessage());
                        hintLabel.setStyle("-fx-text-fill: #ef4444; -fx-font-size: 12px;");
                        confirmBtn.setDisable(false);
                        confirmBtn.setText("确定");
                    });
                    return null;
                });
        });
        
        buttonBox.getChildren().addAll(cancelBtn, confirmBtn);
        
        container.getChildren().addAll(titleLabel, deviceInfoLabel, remarkField, hintLabel, buttonBox);
        
        // 设置场景
        Scene scene = new Scene(container);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        remarkDialog.setScene(scene);
        
        // 窗口拖动
        final double[] dragOffset = new double[2];
        container.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        container.setOnMouseDragged(e -> {
            remarkDialog.setX(e.getScreenX() - dragOffset[0]);
            remarkDialog.setY(e.getScreenY() - dragOffset[1]);
        });
        
        // 居中显示
        remarkDialog.setOnShown(e -> {
            Stage owner = getStage();
            if (owner != null) {
                remarkDialog.setX(owner.getX() + (owner.getWidth() - remarkDialog.getWidth()) / 2);
                remarkDialog.setY(owner.getY() + (owner.getHeight() - remarkDialog.getHeight()) / 2);
            }
        });
        
        // ⭐ 直接监听主窗口状态，自动关闭弹框
        if (mainStage != null) {
            javafx.beans.value.ChangeListener<Boolean> iconifiedListener = (obs, wasIconified, isIconified) -> {
                if (isIconified && remarkDialog.isShowing()) {
                    remarkDialog.close();
                }
            };
            javafx.beans.value.ChangeListener<Boolean> focusedListener = (obs, wasFocused, isFocused) -> {
                if (!isFocused && remarkDialog.isShowing() && !remarkDialog.isFocused()) {
                    Platform.runLater(() -> {
                        if (!mainStage.isFocused() && !remarkDialog.isFocused() && remarkDialog.isShowing()) {
                            remarkDialog.close();
                        }
                    });
                }
            };
            mainStage.iconifiedProperty().addListener(iconifiedListener);
            mainStage.focusedProperty().addListener(focusedListener);
            remarkDialog.setOnHidden(e -> {
                mainStage.iconifiedProperty().removeListener(iconifiedListener);
                mainStage.focusedProperty().removeListener(focusedListener);
            });
        }
        
        remarkDialog.showAndWait();
    }
    
    /**
     * 切换到指定账号
     */
    private void switchToAccount(String username) {
        // ⭐ 设置切换账号标记，用于登录页面自动登录判断
        FileToos.isSwitchAccountMode = true;
        
        // 先执行退出操作（停止播放器、断开连接、清空缓存）
        try {
            // 1. 停止实时流播放器
            if (corePlayer != null) {
                corePlayer.stop();
            }
            
            // 2. 停止慢放播放器
            if (element2_2JpegController != null) {
                element2_2JpegController.cleanup();
            }
            
            // 3. 断开 WebSocket 连接
            try {
                NetworkManager.getInstance().disconnectWebSocket();
            } catch (Exception e) {
                System.err.println("断开WebSocket失败: " + e.getMessage());
            }
            
            // 4. 清空缓存文件
            clearCacheFiles();
            
            // 5. 清除当前登录信息（但保留账号列表）
            AuthStore.getInstance().clearLogin();
            
            // 6. 返回登录页面，并预填用户名
            returnToLoginPageWithUsername(username);
            
        } catch (Exception e) {
            System.err.println("❌ 切换账号失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 返回登录页面并预填用户名
     */
    private void returnToLoginPageWithUsername(String username) {
        try {
            // 保存要切换的用户名到临时存储
            LocalStorage.getInstance().setLastAccount(username);
            
            // 返回登录页面
            returnToLoginPage();
            
        } catch (Exception e) {
            System.err.println("❌ 返回登录页面失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 处理最小化
     */
    @FXML
    private void handleMinimize() {
        Stage stage = getStage();
        if (stage != null) {
            stage.setIconified(true);
            updateStatusLabel("窗口已最小化");
        }
    }
    
    /**
     * 处理最大化/还原
     */
    @FXML
    private void handleMaximize() {


        /*Stage stage = getStage();
        if (stage != null) {
            boolean currentlyMaximized = stage.isMaximized();
            if (currentlyMaximized) {
                // 先退出最大化，再恢复窗口大小与位置
                stage.setMaximized(false);
                Platform.runLater(() -> {
                    stage.setWidth(originalWidth);
                    stage.setHeight(originalHeight);
                    stage.setX(originalX);
                    stage.setY(originalY);
                    updateMaximizeIcon();
                    updateStatusLabel("窗口已还原");
                });
                isMaximized = false;
                // ✅ 添加视频适配调用
                Platform.runLater(() -> {
                    if (corePlayer != null) {
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                });
            } else {
                // 保存当前窗口状态
                originalWidth = stage.getWidth();
                originalHeight = stage.getHeight();
                originalX = stage.getX();
                originalY = stage.getY();
                
                // 最大化窗口
                stage.setMaximized(true);
                updateMaximizeIcon();
                isMaximized = true;
                updateStatusLabel("窗口已最大化");
                // ✅ 添加视频适配调用
                Platform.runLater(() -> {
                    if (corePlayer != null) {
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                });
            }
        }*/

        Stage stage = getStage();
        if (stage != null) {
            // ⭐ 使用"伪全屏"模式：窗口最大化到屏幕可视区域（留出任务栏），避免弹框层级问题
            LogTools.getInstance().logRecord2("当前伪全屏状态: " + isFullScreen);

            if (isFullScreen) {
                // 退出伪全屏，恢复原来的大小和位置
                stage.setWidth(originalWidth);
                stage.setHeight(originalHeight);
                stage.setX(originalX);
                stage.setY(originalY);
                LogTools.getInstance().logRecord2("窗口大小已恢复: " + originalWidth + "x" + originalHeight);

                // ✅ 添加视频适配调用
                Platform.runLater(() -> {
                    if (corePlayer != null) {
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                });
                isFullScreen = false;
                updateStatusLabel("已恢复原来的窗口大小");

            } else {
                // 保存当前窗口状态
                originalWidth = stage.getWidth();
                originalHeight = stage.getHeight();
                originalX = stage.getX();
                originalY = stage.getY();
                LogTools.getInstance().logRecord2("保存窗口状态: " + originalWidth + "x" + originalHeight + " at (" + originalX + "," + originalY + ")");

                // ⭐ 进入伪全屏：获取屏幕可视区域（排除任务栏）
                javafx.geometry.Rectangle2D visualBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
                stage.setX(visualBounds.getMinX());
                stage.setY(visualBounds.getMinY());
                stage.setWidth(visualBounds.getWidth());
                stage.setHeight(visualBounds.getHeight());
                
                isFullScreen = true;
                updateStatusLabel("已切换到全屏模式");
                LogTools.getInstance().logRecord2("伪全屏: " + visualBounds.getWidth() + "x" + visualBounds.getHeight());
                
                // ✅ 添加视频适配调用
                Platform.runLater(() -> {
                    if (corePlayer != null) {
                        corePlayer.refreshOverlayRectangle();
                    }
                    FileToos.updateSlowSize();
                });
            }
        }


    }
    
    /**
     * 处理关闭应用
     */
    @FXML
    private void handleClose() {
        updateStatusLabel("正在关闭应用...");
        // 优雅关闭播放器相关后台线程
        try {
            // 停止核心播放器
            if (corePlayer != null) {
                corePlayer.stop();
            }
            disposeSlowPane();
        } catch (Exception ignore) {}
        Platform.exit();
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取当前窗口的Stage
     */
    private Stage getStage() {
        // 尝试多个UI元素来获取Stage
        javafx.scene.Node node = null;
        
        if (titleBar != null) {
            node = titleBar;
        } else if (element1 != null) {
            node = element1;
        }
        
        if (node != null && node.getScene() != null) {
            Window window = node.getScene().getWindow();
            if (window instanceof Stage) {
                return (Stage) window;
            }
        }
        return null;
    }
    
    /**
     * 更新状态标签
     */
    private void updateStatusLabel(String message) {
        if (viewModel != null) {
            viewModel.setStatusMessage(message);
        }
    }
    
    /**
     * ✅ 更新磁盘缓存配置显示
     */
    private void updateCacheConfigLabel() {

    }
    
    /**
     * 更新状态栏的解码器信息显示
     */
    private void updateDecoderInfo() {
        if (corePlayer == null) return;
        
        String decoderName = corePlayer.getDecoderName();
        boolean isHardware = corePlayer.isHardwareDecoder();
        
        Platform.runLater(() -> {
            if (decoderName != null) {
                // 简化显示名称（用于日志）
                String displayName;
                if ("d3d11h264dec".equalsIgnoreCase(decoderName)) {
                    displayName = "D3D11";
                } else if ("nvh264dec".equalsIgnoreCase(decoderName)) {
                    displayName = "NVDEC";
                } else if ("msdkh264dec".equalsIgnoreCase(decoderName)) {
                    displayName = "QuickSync";
                } else if ("avdec_h264".equalsIgnoreCase(decoderName)) {
                    displayName = "软解";
                } else {
                    displayName = decoderName;
                }
                LogTools.getInstance().logRecord("🎬 解码器: " + displayName + " (" + (isHardware ? "硬解" : "软解") + ")");
            }
        });
    }
    
    /**
     * ✅ 更新慢放按钮状态（显示快捷键名称）
     * @param isRecording 是否正在录制
     */
    private void updateSlowmoButtonsState(boolean isRecording) {



        Platform.runLater(() -> {
            // 获取快捷键名称
            String slowMotionKey = FileToos.ShortcutHelper.getSlowMotionKeyName();
            String clearKey = FileToos.ShortcutHelper.getClearKeyName();
            
            // 更新按钮文字，根据录制状态显示不同文字
            if (startSlowmoButton != null) {
                if (isRecording) {
                    startSlowmoButton.setText("慢放停止(" + slowMotionKey + ")");
                } else {
                    startSlowmoButton.setText("开启慢放(" + slowMotionKey + ")");
                }
            }
            if (clearSlowmoButton != null) {
                clearSlowmoButton.setText("清空慢放(" + clearKey + ")");
            }

            if(FileToos.isCallBack){
                updateStatusLabel("停止慢放");
            }else{
                updateStatusLabel("开始慢放");
            }
        });
    }
    
    /**
     * ✅ 更新慢放按钮状态（无参数版本，自动检测状态）
     */
    private void updateSlowmoButtonsState() {
        // 检测当前是否在录制
        boolean isRecording = false;
        if (element2_3Controller != null) {
            isRecording = element2_3Controller.isSlowmoRecording();
        }
        updateSlowmoButtonsState(isRecording);
    }
    
    /**
     * ✅ 更新滚轮帧数显示
     */
    public void updateScrollFrameLabel() {
        if (scrollFrameLabel != null) {
            int frameRate = ShortcutStore.getInstance().getScrollFrameRate();
            Platform.runLater(() -> {
                scrollFrameLabel.setText("滚轮帧数: " + frameRate);
            });
        }
    }
    
    /**
     * ✅ 更新视频局部放大倍数显示
     * @param zoom 当前缩放倍数
     */
    public void updateVideoZoomLabel(double zoom) {
        if (videoZoomLabel != null) {
            Platform.runLater(() -> {
                String zoomText = String.format("%.1fX", zoom);
                videoZoomLabel.setText(zoomText);
                
                // ⭐ 根据缩放倍数动态改变颜色
                if (zoom <= 1.0) {
                    // 1.0x（正常）- 绿色
                    videoZoomLabel.setStyle("-fx-background-color: #2D5A2D; -fx-text-fill: #90EE90; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6 12; -fx-font-weight: bold;");
                } else if (zoom < 2.0) {
                    // 1.0x ~ 2.0x - 黄色
                    videoZoomLabel.setStyle("-fx-background-color: #5A5A2D; -fx-text-fill: #FFD700; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6 12; -fx-font-weight: bold;");
                } else {
                    // >= 2.0x - 红色
                    videoZoomLabel.setStyle("-fx-background-color: #5A2D2D; -fx-text-fill: #FF6B6B; -fx-font-size: 12px; -fx-background-radius: 8; -fx-padding: 6 12; -fx-font-weight: bold;");
                }
            });
        }
    }
    
    /**
     * ✅ 设置滚轮帧数并更新显示
     * @param value 帧数值 (0-10)
     */
    public void setScrollFrameRate(int value) {
        int clamped = Math.max(0, Math.min(10, value));
        ShortcutStore.getInstance().setScrollFrameRate(clamped);
        updateScrollFrameLabel();
    }
    
    /**
     * ✅ 处理数字键设置滚轮帧数（主键盘 0-9 和 小键盘 NUMPAD 0-9）
     * @param keyCode 按键码
     * @return 是否处理了该按键
     */
    public boolean handleNumpadKeyForScrollFrame(KeyCode keyCode) {
        int value = -1;
        switch (keyCode) {
            // 主键盘数字键 (Q W E 上面那排)
            case DIGIT0: value = 0; break;
            case DIGIT1: value = 1; break;
            case DIGIT2: value = 2; break;
            case DIGIT3: value = 3; break;
            case DIGIT4: value = 4; break;
            case DIGIT5: value = 5; break;
            case DIGIT6: value = 6; break;
            case DIGIT7: value = 7; break;
            case DIGIT8: value = 8; break;
            case DIGIT9: value = 9; break;
            // ⭐ 小键盘数字键 (NUMPAD 0-9)
            case NUMPAD0: value = 0; break;
            case NUMPAD1: value = 1; break;
            case NUMPAD2: value = 2; break;
            case NUMPAD3: value = 3; break;
            case NUMPAD4: value = 4; break;
            case NUMPAD5: value = 5; break;
            case NUMPAD6: value = 6; break;
            case NUMPAD7: value = 7; break;
            case NUMPAD8: value = 8; break;
            case NUMPAD9: value = 9; break;
            default: return false;
        }
        if (value >= 0 && value <= 10) {
            setScrollFrameRate(value);
            return true;
        }
        return false;
    }
    
    /**
     * ✅ 开启慢放按钮点击事件（状态切换：开启慢放 ↔ 慢放停止）
     */
    @FXML
    private void onStartSlowmoClick() {
        if (element2_3Controller != null) {
            // 调用 Element2_3Controller 的 onStartSlow 方法（内部会切换状态）
            element2_3Controller.onStartSlow();
            
            // 更新按钮状态
            //boolean isRecording = element2_3Controller.isSlowmoRecording();
            //updateSlowmoButtonsState(isRecording);
            
            // 更新状态栏
            //updateStatusLabel(isRecording ? "开始慢放" : "停止慢放");

            if(FileToos.isCallBack){
                updateStatusLabel("停止慢放");
            }else{
                updateStatusLabel("开始慢放");
            }
        } else {
            updateStatusLabel("慢放控制器未初始化");
        }
    }
    
    /**
     * ✅ 清空慢放按钮点击事件
     */
    @FXML
    private void onClearSlowmoClick() {
        updateStatusLabel("清空慢放");
        if(element2_3Controller!=null) {
            element2_3Controller.onClear();
            // 清空后更新按钮状态
            updateSlowmoButtonsState(false);
        }
    }
    
    /**
     * ✅ 慢放状态变化回调（由 Element2_3Controller 调用）
     * @param isRecording 是否正在录制
     */
    public void onSlowmoStateChanged(boolean isRecording) {
        updateSlowmoButtonsState(isRecording);
    }
    
    /**
     * 显示信息对话框
     */
    private void showInfoDialog(String title, String message) {
        Stage mainStage = getStage();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        // ⭐ 全屏保护
        alert.setOnShowing(e -> { if (mainStage != null) mainStage.toFront(); });
        alert.setOnHidden(e -> { if (mainStage != null) Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); }); });
        alert.showAndWait();
    }
    
    /**
     * ⭐ 显示深色主题提示弹框（与解绑确认风格一致）
     */
    private void showDarkTipDialog(String title, String message) {
        Stage mainStage = getStage();
        
        Stage tipDialog = new Stage();
        tipDialog.initOwner(mainStage);  // ⭐ 设置父窗口，防止全屏时层级错乱
        tipDialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        tipDialog.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        tipDialog.setTitle(title);
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        tipDialog.setOnShowing(e -> {
            if (mainStage != null) {
                mainStage.toFront();
            }
        });
        tipDialog.setOnHidden(e -> {
            if (mainStage != null) {
                Platform.runLater(() -> {
                    mainStage.toFront();
                    mainStage.requestFocus();
                });
            }
        });
        
        // 主容器
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(16);
        container.setStyle("-fx-background-color: #1F1F1F; -fx-background-radius: 12; -fx-padding: 24; -fx-border-color: #3a3a3a; -fx-border-radius: 12; -fx-border-width: 1;");
        container.setPrefWidth(340);
        container.setAlignment(javafx.geometry.Pos.CENTER);
        
        // 提示图标
        Label iconLabel = new Label("💡");
        iconLabel.setStyle("-fx-font-size: 32px;");
        
        // 标题
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: #FAFAFA; -fx-font-size: 18px; -fx-font-weight: bold;");
        
        // 消息内容
        Label messageLabel = new Label(message);
        messageLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-text-alignment: center;");
        messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        messageLabel.setWrapText(true);
        
        // 确定按钮
        Button okBtn = new Button("知道了");
        okBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;");
        okBtn.setOnMouseEntered(e -> okBtn.setStyle("-fx-background-color: #7089fc; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;"));
        okBtn.setOnMouseExited(e -> okBtn.setStyle("-fx-background-color: #607AFB; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 40; -fx-background-radius: 8; -fx-cursor: hand;"));
        okBtn.setOnAction(e -> tipDialog.close());
        
        container.getChildren().addAll(iconLabel, titleLabel, messageLabel, okBtn);
        
        // 设置场景
        Scene scene = new Scene(container);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        tipDialog.setScene(scene);
        
        // 窗口拖动
        final double[] dragOffset = new double[2];
        container.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        container.setOnMouseDragged(e -> {
            tipDialog.setX(e.getScreenX() - dragOffset[0]);
            tipDialog.setY(e.getScreenY() - dragOffset[1]);
        });
        
        // 居中显示
        tipDialog.setOnShown(e -> {
            Stage owner = getStage();
            if (owner != null) {
                tipDialog.setX(owner.getX() + (owner.getWidth() - tipDialog.getWidth()) / 2);
                tipDialog.setY(owner.getY() + (owner.getHeight() - tipDialog.getHeight()) / 2);
            }
        });
        
        tipDialog.showAndWait();
    }
    
    /**
     * 显示成功弹框（深色主题，无标题栏，带勾号图标）
     */
    private void showSuccessDialog(String message) {
        // 确保在 UI 线程执行
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showSuccessDialogImpl(message));
        } else {
            showSuccessDialogImpl(message);
        }
    }
    
    /**
     * 实际显示成功弹框的实现
     */
    private void showSuccessDialogImpl(String message) {
        // 使用自定义 Stage 替代 Alert，避免卡死问题
        Stage dialogStage = new Stage();
        
        // 设置父窗口（必须在其他init之前）
        Stage owner = getStage();
        if (owner != null) {
            dialogStage.initOwner(owner);
        }
        
        dialogStage.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        dialogStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        
        // ⭐ 全屏保护：弹框显示时确保主窗口在前
        dialogStage.setOnShowing(e -> {
            if (owner != null) {
                owner.toFront();
            }
        });
        dialogStage.setOnHidden(e -> {
            if (owner != null) {
                Platform.runLater(() -> {
                    owner.toFront();
                    owner.requestFocus();
                });
            }
        });
        
        // 创建深色圆角容器
        javafx.scene.layout.VBox contentBox = new javafx.scene.layout.VBox(16);
        contentBox.setAlignment(javafx.geometry.Pos.CENTER);
        contentBox.setMinWidth(360);
        contentBox.setStyle(
            "-fx-background-color: #1F1F1F; " +
            "-fx-background-radius: 20; " +
            "-fx-padding: 32 40; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.3, 0, 4);"
        );
        
        // ✓ 勾号图标
        Label iconLabel = new Label("✓");
        iconLabel.setStyle(
            "-fx-font-size: 48px; " +
            "-fx-text-fill: #16A34A;"
        );
        iconLabel.setAlignment(javafx.geometry.Pos.CENTER);
        
        // 消息
        Label messageLabel = new Label(message);
        messageLabel.setStyle(
            "-fx-font-size: 14px; " +
            "-fx-text-fill: #CCCCCC; " +
            "-fx-text-alignment: center; " +
            "-fx-alignment: center;"
        );
        messageLabel.setWrapText(true);
        messageLabel.setMaxWidth(320);
        messageLabel.setAlignment(javafx.geometry.Pos.CENTER);
        messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        
        // 确定按钮
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox();
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER);
        buttonBox.setPadding(new javafx.geometry.Insets(12, 0, 0, 0));
        
        Button okButton = new Button("确定");
        okButton.setStyle(
            "-fx-background-color: #16A34A; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 10 36; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        );
        okButton.setOnMouseEntered(e -> okButton.setStyle(
            "-fx-background-color: #15803D; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 10 36; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        okButton.setOnMouseExited(e -> okButton.setStyle(
            "-fx-background-color: #16A34A; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14px; " +
            "-fx-padding: 10 36; " +
            "-fx-background-radius: 8; " +
            "-fx-cursor: hand;"
        ));
        okButton.setOnAction(e -> dialogStage.close());
        
        buttonBox.getChildren().add(okButton);
        contentBox.getChildren().addAll(iconLabel, messageLabel, buttonBox);
        
        // 创建透明场景
        javafx.scene.Scene scene = new javafx.scene.Scene(contentBox);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        dialogStage.setScene(scene);
        
        // 居中显示
        dialogStage.setOnShown(e -> {
            if (owner != null) {
                dialogStage.setX(owner.getX() + (owner.getWidth() - dialogStage.getWidth()) / 2);
                dialogStage.setY(owner.getY() + (owner.getHeight() - dialogStage.getHeight()) / 2);
            }
        });
        
        // ⭐ 注册弹框，主窗口最小化/失去焦点时自动关闭
        registerDialog(dialogStage);
        
        // 使用 show() 而不是 showAndWait()，避免阻塞
        dialogStage.show();
    }
    
    /**
     * 显示快捷键说明对话框
     */
    private void showShortcutKeysDialog(javafx.event.ActionEvent event) {
        Stage stage = getStage();
        if (stage != null) {
            // 获取触发事件的按钮
            if (event.getSource() instanceof javafx.scene.Node) {
                javafx.scene.Node source = (javafx.scene.Node) event.getSource();
                ShortcutSettingsDialog.showBelowNode(stage, source);
            } else {
                ShortcutSettingsDialog.show(stage);
            }
        }
    }
    
    /**
     * 显示关于对话框（改为 Popup 形式）
     */
    private void showAboutDialog(javafx.event.ActionEvent event) {
        Stage stage = getStage();
        if (stage == null) return;
        
        // ✅ 创建 Popup 内容
        javafx.stage.Popup popup = new javafx.stage.Popup();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        
        // 创建主布局
        javafx.scene.layout.BorderPane mainLayout = new javafx.scene.layout.BorderPane();
        mainLayout.setStyle("-fx-background-color: #ffffff; " +
                           "-fx-background-radius: 12; " +
                           "-fx-border-color: #d6d9dc; " +
                           "-fx-border-width: 1; " +
                           "-fx-border-radius: 12; " +
                           "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.20), 16, 0.30, 0, 6);");
        mainLayout.setPrefWidth(320);
        
        // 标题栏
        javafx.scene.layout.HBox titleBar = new javafx.scene.layout.HBox();
        titleBar.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-background-radius: 12 12 0 0;");
        Label titleLabel = new Label("关于");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;");
        
        Button closeBtn = new Button("×");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 20; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> popup.hide());
        
        Region spacer = new Region();
        javafx.scene.layout.HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);
        
        // 内容区域
        javafx.scene.layout.VBox contentBox = new javafx.scene.layout.VBox(15);
        contentBox.setPadding(new javafx.geometry.Insets(20));
        contentBox.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 0 0 12 12;");
        
        Label appNameLabel = new Label("相机主界面系统");
        appNameLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;");
        
        Label versionLabel = new Label("版本: 1.0.0");
        versionLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        Label teamLabel = new Label("开发: Acard Team");
        teamLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        Label techLabel = new Label("技术: JavaFX + MVVM");
        techLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        Label copyrightLabel = new Label("© 2024 Acard. All rights reserved.");
        copyrightLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999; -fx-padding: 10 0 0 0;");
        
        contentBox.getChildren().addAll(appNameLabel, versionLabel, teamLabel, techLabel, copyrightLabel);
        
        mainLayout.setTop(titleBar);
        mainLayout.setCenter(contentBox);
        
        // 设置拖动
        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(mouseEvent -> {
            dragOffset[0] = mouseEvent.getX();
            dragOffset[1] = mouseEvent.getY();
        });
        titleBar.setOnMouseDragged(mouseEvent -> {
            popup.setX(mouseEvent.getScreenX() - dragOffset[0]);
            popup.setY(mouseEvent.getScreenY() - dragOffset[1]);
        });
        
        popup.getContent().add(mainLayout);
        
        // ✅ 计算位置：显示在按钮下方
        try {
            if (event.getSource() instanceof javafx.scene.Node) {
                javafx.scene.Node source = (javafx.scene.Node) event.getSource();
                javafx.geometry.Bounds bounds = source.localToScreen(source.getBoundsInLocal());
                popup.setX(bounds.getMinX());
                popup.setY(bounds.getMaxY() + 5);
            }
        } catch (Throwable ignore) {}
        
        // ⭐ 监听主窗口最小化/失去焦点，自动关闭弹框
        stage.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
            if (isIconified && popup.isShowing()) {
                popup.hide();
            }
        });
        stage.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused && popup.isShowing()) {
                Platform.runLater(() -> {
                    if (!stage.isFocused() && popup.isShowing()) {
                        popup.hide();
                    }
                });
            }
        });
        
        popup.show(stage);
    }

    /**
     * 主界面发起全局 STOMP 连接
     * ⭐ 不管有没有设备都建立连接，无设备时只订阅绑定消息频道
     */
    private void initStompConnection() {
        try {
            NetworkManager network = NetworkManager.getInstance();
            NetworkConfig cfg = NetworkConfig.getInstance();
            LoginResponse resp = AuthStore.getInstance().getLoginResponse();
            String token = cfg.getAuthToken();
            String deviceId = resp != null ? resp.getDeviceId() : null;
            boolean hasDevice = deviceId != null && !deviceId.isBlank();
            
            // ⭐ 不管有没有设备都建立 STOMP 连接
            String baseWs = cfg.getWebsocketUrl();
            StringBuilder urlBuilder = new StringBuilder(baseWs);
            if (!baseWs.contains("?")) {
                urlBuilder.append("?");
            } else if (!baseWs.endsWith("&") && !baseWs.endsWith("?")) {
                urlBuilder.append("&");
            }
            if (token != null) {
                urlBuilder.append("token=")
                          .append(URLEncoder.encode(token, StandardCharsets.UTF_8));
            }
            // ⭐ 添加 username 参数，让后端识别当前用户（用于 /user/queue/binding 订阅）
            String username = resp != null ? resp.getUsername() : null;
            if (username != null && !username.isBlank()) {
                if (urlBuilder.charAt(urlBuilder.length()-1) != '?' && urlBuilder.charAt(urlBuilder.length()-1) != '&') {
                    urlBuilder.append('&');
                }
                urlBuilder.append("username=")
                          .append(URLEncoder.encode(username, StandardCharsets.UTF_8));
            }
            // 只有有设备时才在 URL 中带上 deviceId
            if (hasDevice) {
                if (urlBuilder.charAt(urlBuilder.length()-1) != '?' && urlBuilder.charAt(urlBuilder.length()-1) != '&') {
                    urlBuilder.append('&');
                }
                urlBuilder.append("deviceId=")
                          .append(URLEncoder.encode(deviceId, StandardCharsets.UTF_8));
            }
            // 更新全局 WebSocket 地址
            LogTools.getInstance().logRecord4("📡 WebSocket URL: " + urlBuilder.toString());
            network.setWebSocketUrl(urlBuilder.toString());

            updateStatusLabel("正在建立 STOMP 连接...");
            final String finalDeviceId = deviceId;
            final boolean finalHasDevice = hasDevice;
            
            network.connectWebSocket(new StompWebSocketClient.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Platform.runLater(() -> {
                        updateStatusLabel("STOMP 连接成功");
                        updateControlLinkStatus(true);
                        
                        // ⭐ 1. 订阅用户绑定消息频道（不管有没有设备都订阅）
                        subscribeToBindingChannel();
                        
                        // ⭐ 2. 只有有设备时才订阅设备配置频道
                        if (finalHasDevice) {
                            subscribeToDeviceChannel(finalDeviceId);
                        } else {
                            updateStatusLabel("等待绑定设备...");
                            LogTools.getInstance().logRecord2("⚠️ 未绑定设备，等待iOS扫码绑定");
                        }
                    });
                }
                @Override
                public void onDisconnected(String reason) {
                    Platform.runLater(() -> {
                        updateStatusLabel("STOMP 连接断开: " + reason);
                        updateControlLinkStatus(false);

                        // ⭐ WebSocket 断线时，停止播放器并重置状态
                        if (corePlayer != null && isPlayerPlaying) {
                            LogTools.getInstance().logRecord2("🔴 WebSocket 断线，自动停止播放器");
                            try {
                                corePlayer.stop();
                                isPlayerPlaying = false;
                                publishState = 0;
                                lastPublishStatus = -1;
                                LogTools.getInstance().logRecord2("✅ 播放器已停止（WebSocket 断线）");
                            } catch (Exception e) {
                                System.err.println("❌ 停止播放器失败: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    });
                }
                @Override
                public void onError(Exception error) {
                    Platform.runLater(() -> {
                        updateStatusLabel("STOMP 连接失败: " + (error != null ? error.getMessage() : "未知错误"));
                        updateControlLinkStatus(false);
                    });
                }
            });
            
            // 若此刻已连接，则立即订阅
            if (network.isWebSocketConnected()) {
                subscribeToBindingChannel();
                if (hasDevice) {
                    subscribeToDeviceChannel(deviceId);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            updateStatusLabel("初始化 STOMP 连接时发生错误: " + e.getMessage());
            Platform.runLater(() -> updateControlLinkStatus(false));
        }
    }
    
    /**
     * ⭐ 订阅用户绑定消息频道 /user/queue/binding
     * 接收 iOS 设备扫码绑定成功的消息
     */
    private void subscribeToBindingChannel() {
        try {
            String bindingDestination = "/user/queue/binding";
            NetworkManager.getInstance().subscribeWebSocket(bindingDestination, payload -> {
                LogTools.getInstance().logRecord4("========== 收到绑定消息 ==========");
                LogTools.getInstance().logRecord4("订阅地址: " + bindingDestination);
                LogTools.getInstance().logRecord4("消息内容: " + payload);
                LogTools.getInstance().logRecord4("==================================");
                processBindingMessage(payload);
            });
            LogTools.getInstance().logRecord2("✅ 已订阅绑定频道: " + bindingDestination);
        } catch (Exception e) {
            System.err.println("订阅绑定频道失败: " + e.getMessage());
        }
    }
    
    /**
     * ⭐ 订阅设备配置频道
     */
    private void subscribeToDeviceChannel(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return;
        
        try {
            String destination = "/topic/device/" + deviceId + "/config";
            NetworkManager.getInstance().subscribeWebSocket(destination, payload -> {
                processConfigStateMessage(payload);
            });
            LogTools.getInstance().logRecord2("✅ 已订阅设备频道: " + destination);
        } catch (Exception e) {
            System.err.println("订阅设备配置频道失败: " + e.getMessage());
        }
    }
    
    /**
     * ⭐ 处理绑定消息
     * 消息格式：{type, deviceId, iosusername, controlUsername, controlNickname, state, timestamp}
     */
    private void processBindingMessage(String payload) {
        try {
            LogTools.getInstance().logRecord4("📩 开始解析绑定消息...");
            LogTools.getInstance().logRecord4("📩 payload长度: " + (payload != null ? payload.length() : "null"));
            
            // ⭐ trim() 去除首尾空白字符，避免 MalformedJsonException
            String cleanPayload = payload != null ? payload.trim() : "";
            LogTools.getInstance().logRecord4("📩 trim后长度: " + cleanPayload.length());
            
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(cleanPayload).getAsJsonObject();
            LogTools.getInstance().logRecord4("📩 JSON解析成功");
            String type = json.has("type") ? json.get("type").getAsString() : "";
            String newDeviceId = json.has("deviceId") ? json.get("deviceId").getAsString() : null;
            String iosUsername = json.has("iosusername") ? json.get("iosusername").getAsString() : null;
            String controlUsername = json.has("controlUsername") ? json.get("controlUsername").getAsString() : null;
            String controlNickname = json.has("controlNickname") ? json.get("controlNickname").getAsString() : null;
            String state = json.has("state") ? json.get("state").getAsString() : "";
            String timestamp = json.has("timestamp") ? json.get("timestamp").getAsString() : null;
            
            LogTools.getInstance().logRecord4("📩 绑定消息解析结果:");
            LogTools.getInstance().logRecord4("  type: " + type);
            LogTools.getInstance().logRecord4("  deviceId: " + newDeviceId);
            LogTools.getInstance().logRecord4("  iosusername: " + iosUsername);
            LogTools.getInstance().logRecord4("  controlUsername: " + controlUsername);
            LogTools.getInstance().logRecord4("  controlNickname: " + controlNickname);
            LogTools.getInstance().logRecord4("  state: " + state);
            LogTools.getInstance().logRecord4("  timestamp: " + timestamp);
            
            if (!"IOSBD".equals(type)) {
                LogTools.getInstance().logRecord4("⚠️ 非绑定消息类型: " + type + "，忽略");
                return;
            }
            
            if (!"ACTIVE".equals(state)) {
                LogTools.getInstance().logRecord4("⚠️ 绑定状态非 ACTIVE: " + state + "，忽略");
                return;
            }
            
            LogTools.getInstance().logRecord4("📱 iOS 设备绑定成功: deviceId=" + newDeviceId + ", iosUsername=" + iosUsername);
            
            // 检查当前是否有设备
            LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
            String currentDeviceId = loginResp != null ? loginResp.getDeviceId() : null;
            boolean hasExistingDevice = currentDeviceId != null && !currentDeviceId.isBlank();
            
            LogTools.getInstance().logRecord4("📩 hasExistingDevice: " + hasExistingDevice + ", currentDeviceId: " + currentDeviceId);
            LogTools.getInstance().logRecord4("📩 准备调用 Platform.runLater...");
            
            Platform.runLater(() -> {
                LogTools.getInstance().logRecord4("📩 Platform.runLater 已执行");
                if (hasExistingDevice) {
                    // ⭐ 有设备：只弹出绑定成功提示框，不切换设备
                    LogTools.getInstance().logRecord4("📱 已有设备，新设备绑定成功: " + iosUsername);
                    updateStatusLabel("新设备绑定成功：" + iosUsername);
                    showSuccessDialog("设备绑定成功");
                } else {
                    // ⭐ 无设备：重新登录 → 获取设备 → 订阅设备频道 → 拉流
                    LogTools.getInstance().logRecord4("📱 首次绑定设备: " + iosUsername);
                    LogTools.getInstance().logRecord4("📱 调用 reLoginAndInitDevice: deviceId=" + newDeviceId + ", iosUsername=" + iosUsername);
                    updateStatusLabel("设备绑定成功，正在初始化...");
                    
                    // 重新登录获取设备信息
                    reLoginAndInitDevice(newDeviceId, iosUsername);
                }
            });
            
        } catch (Exception e) {
            LogTools.getInstance().logRecord4("❌ 解析绑定消息失败: " + e.getMessage());
            LogTools.getInstance().logRecord4("❌ 异常类型: " + e.getClass().getName());
            e.printStackTrace();
        }
    }
    
    /**
     * ⭐ 解绑后检查账号设备状态
     * 不管有没有其他设备，都保持 STOMP 连接等待绑定
     */
    private void checkDevicesAfterUnbind() {
        LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
        String username = loginResp != null ? loginResp.getUsername() : null;
        
        // 从 AccountListStore 获取保存的密码
        String password = null;
        if (username != null) {
            com.acard.acard.storage.AccountListStore.Account account = 
                com.acard.acard.storage.AccountListStore.getInstance().getAccount(username);
            if (account != null) {
                password = account.getPassword();
            }
        }
        
        if (username == null || password == null || password.isEmpty()) {
            LogTools.getInstance().logRecord2("⚠️ 无法检查设备：用户名或密码为空，等待绑定");
            updateStatusLabel("等待绑定设备...");
            return;
        }
        
        // 重新登录刷新设备状态
        updateStatusLabel("正在刷新设备状态...");
        final String finalPassword = password;
        NetworkManager.getInstance().login(username, finalPassword)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        LoginResponse newLoginResp = response.getData();
                        
                        // 检查是否有设备
                        String deviceId = newLoginResp.getDeviceId();
                        boolean hasDevice = deviceId != null && !deviceId.isBlank();
                        
                        // ⭐ 保存刷新后的登录信息（清空设备ID）
                        newLoginResp.setDeviceId("");
                        newLoginResp.setCurrentDeviceId("");
                        newLoginResp.setCurrentDeviceUsername("");
                        AuthStore.getInstance().saveLoginResponse(newLoginResp);
                        
                        if (hasDevice) {
                            LogTools.getInstance().logRecord2("📱 账号还有其他设备，等待用户操作或绑定");
                        } else {
                            LogTools.getInstance().logRecord2("⚠️ 账号没有设备，等待绑定");
                        }
                        
                        // ⭐ 不管有没有其他设备，都保持 STOMP 连接等待绑定
                        updateStatusLabel("等待绑定设备...");
                        // STOMP 连接保持，/user/queue/binding 订阅已存在
                    } else {
                        LogTools.getInstance().logRecord2("❌ 刷新设备状态失败: " + response.getMessage());
                        updateStatusLabel("等待绑定设备...");
                    }
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    LogTools.getInstance().logRecord2("❌ 刷新设备状态异常: " + ex.getMessage());
                    updateStatusLabel("等待绑定设备...");
                });
                return null;
            });
    }
    
    /**
     * ⭐ 重新登录并初始化设备（无设备首次绑定时调用）
     */
    private void reLoginAndInitDevice(String newDeviceId, String iosUsername) {
        // 更新本地存储的设备信息
        LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
        if (loginResp != null) {
            loginResp.setDeviceId(newDeviceId);
            loginResp.setCurrentDeviceId(newDeviceId);
            loginResp.setCurrentDeviceUsername(iosUsername);
            AuthStore.getInstance().saveLoginResponse(loginResp);
        }
        
        // 重新调用登录接口获取完整设备信息
        String username = loginResp != null ? loginResp.getUsername() : null;
        
        // 从 AccountListStore 获取保存的密码
        String password = null;
        if (username != null) {
            com.acard.acard.storage.AccountListStore.Account account = 
                com.acard.acard.storage.AccountListStore.getInstance().getAccount(username);
            if (account != null) {
                password = account.getPassword();
            }
        }
        
        if (username == null || password == null || password.isEmpty()) {
            LogTools.getInstance().logRecord2("⚠️ 无法重新登录：用户名或密码为空");
            // 直接使用当前信息初始化
            showSuccessDialog("设备绑定成功");
            initAfterDeviceBinding(newDeviceId);
            return;
        }
        
        // 重新登录（传入 iosUsername 以获取指定设备信息）
        updateStatusLabel("正在重新登录获取设备信息...");
        final String finalPassword = password;
        LogTools.getInstance().logRecord4("🔄 重新登录: username=" + username + ", iosUsername=" + iosUsername);
        NetworkManager.getInstance().login(username, finalPassword, iosUsername)
            .thenAccept(response -> {
                Platform.runLater(() -> {
                    if (response.isSuccess() && response.getData() != null) {
                        LoginResponse newLoginResp = response.getData();
                        AuthStore.getInstance().saveLoginResponse(newLoginResp);
                        
                        String deviceId = newLoginResp.getDeviceId();
                        LogTools.getInstance().logRecord2("✅ 重新登录成功，设备ID: " + deviceId);
                        
                        showSuccessDialog("设备绑定成功");
                        
                        // 订阅设备频道
                        subscribeToDeviceChannel(deviceId);
                        
                        // 初始化设备连接（预取配置、启动拉流等）
                        initAfterDeviceBinding(deviceId);
                    } else {
                        LogTools.getInstance().logRecord2("❌ 重新登录失败: " + response.getMessage());
                        showSuccessDialog("设备绑定成功");
                        initAfterDeviceBinding(newDeviceId);
                    }
                });
            })
            .exceptionally(ex -> {
                Platform.runLater(() -> {
                    LogTools.getInstance().logRecord2("❌ 重新登录异常: " + ex.getMessage());
                    showSuccessDialog("设备绑定成功");
                    initAfterDeviceBinding(newDeviceId);
                });
                return null;
            });
    }

    // 创建单个正方形图标
    private javafx.scene.Node createSquareIcon() {
        Rectangle r = new Rectangle(12, 12);
        r.setFill(null);
        r.setStroke(Color.GRAY);
        r.setStrokeWidth(1.5);
        return new Group(r);
    }

    // 创建两个重叠正方形图标（用于最大化后的“还原”提示）
    private javafx.scene.Node createDoubleSquaresIcon() {
        Rectangle r1 = new Rectangle(12, 12);
        r1.setFill(null);
        r1.setStroke(Color.GRAY);
        r1.setStrokeWidth(1.5);

        Rectangle r2 = new Rectangle(12, 12);
        r2.setFill(null);
        r2.setStroke(Color.GRAY);
        r2.setStrokeWidth(1.5);
        r2.setTranslateX(4);
        r2.setTranslateY(-4);

        return new Group(r1, r2);
    }

    // 根据当前窗口最大化状态更新最大化按钮图标
    private void updateMaximizeIcon() {
        Stage stage = getStage();
        boolean maximized = stage != null && stage.isMaximized();
       /* if (maximizeButton != null) {
            maximizeButton.setText(null);
            maximizeButton.setGraphic(maximized ? iconRestore : iconMaximize);
            maximizeButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }*/
    }

    /**
     * 在元素2-1中初始化并嵌入 SimpleWebRTCPlayerView 播放器
     *
     *   SimpleWebRTCPlayerView view = new SimpleWebRTCPlayerView(
     *                 "171.80.4.72", 1985, "tenantA", "VID_1A191D98F454E3E4BAE32DBF50C7"
     *         );
     */
    private void setupElement2_1Player() {
        if (element2_1 == null) {
            return;
        }
        try {
            String stream = AuthStore.getInstance().getPermanentToken();
            // 直接使用 GpuView 作为元素2-1的显示容器
            element2_1Player = new GpuView();
            element2_1Player.setMinSize(0, 0);
            element2_1Player.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            // ✅ 设置GpuView的margin来避开父容器边框
            javafx.geometry.Insets margin = new javafx.geometry.Insets(3, 3, 3, 3);
            StackPane.setMargin(element2_1Player, margin);
            element2_1.getChildren().setAll(element2_1Player);
            // 创建核心播放器并绑定到 GpuView
            // ⭐ 拉流 IP 默认为空，等待 CONFIG_STATE 消息动态获取 streamPushIp
            // 在 processConfigStateMessage() 中会调用 corePlayer.setServerHost() 更新拉流地址
            String initialStreamIp = NetworkConfig.getEffectiveStreamIp();  // 优先使用动态 IP，否则使用默认
            corePlayer = new SimpleWebRTCPlayer(initialStreamIp, NetworkConfig.apiPort, NetworkConfig.app, stream);
            element2_1Player.attach(corePlayer);
            
            // ⭐ 设置缩放变化回调，用于更新底部状态栏的缩放显示
            corePlayer.setZoomChangeCallback(this::updateVideoZoomLabel);



            // 延迟到窗口显示且容器尺寸>0后再启动，避免UI线程阻塞与句柄未就绪
            Runnable tryStart = () -> {
                if (corePlayer != null && !corePlayerStarted) {
                    Stage stage = getStage();
                    double w = element2_1.getWidth();
                    double h = element2_1.getHeight();
                    if (stage != null && stage.isShowing() && w > 0 && h > 0) {
                        corePlayerStarted = true;
                        // ⚠️ 不在这里调用 play()，而是等待 CONFIG_STATE 消息确认推流状态
                        // 原因：如果 Java 先启动但 iOS 还没开始推流，会导致播放器无法正常工作
                        // 解决方案：在 processConfigStateMessage() 中检测到 publishStatus==1 时才调用 play()
                        // Platform.runLater(() -> corePlayer.play());
                    }
                }
            };
            // 监听容器尺寸变化
            element2_1.widthProperty().addListener((obs, ov, nv) -> tryStart.run());
            element2_1.heightProperty().addListener((obs, ov, nv) -> tryStart.run());
            // 监听场景与窗口的显示事件
            element2_1.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    Window win = newScene.getWindow();
                    if (win != null) {
                        if (win.isShowing()) {
                            tryStart.run();
                        } else {
                            win.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> tryStart.run());
                        }
                    } else {
                        Platform.runLater(tryStart);
                    }
                }
            });
            // 若此刻已显示，尝试立即启动（一次性）
            Platform.runLater(tryStart);

            // 初始化完成后，延迟2秒检查实时缓冲是否有增长
            int baseline;
            try {
                baseline = element2_1Player != null ? element2_1Player.getRealtimeSnapshot().size() : -1;
            } catch (Exception ex) {
                baseline = -1;
            }
            final int base = baseline;
            PauseTransition delay = new PauseTransition(Duration.seconds(2));
            delay.setOnFinished(ev -> {
                try {
                    int now = (element2_1Player != null) ? element2_1Player.getRealtimeSnapshot().size() : -1;
                    if (now <= base) {
                        LogTools.getInstance().logRecord2("[Element2-1] 2s后实时缓冲未增长，baseline=" + base + ", now=" + now + "。请检查 Appsink 回调/播放启动状态。");
                    } else {
                        LogTools.getInstance().logRecord2("[Element2-1] 2s后实时缓冲增长正常，baseline=" + base + ", now=" + now + "，增长=" + (now - base));
                    }
                } catch (Exception e) {
                    LogTools.getInstance().logRecord2("[Element2-1] 检查实时缓冲增长时发生异常：" + e.getMessage());
                }
            });
            delay.play();

            updateStatusLabel("元素2-1已加载GPU绑定视图");
        } catch (Exception e) {
            updateStatusLabel("加载播放器失败: " + e.getMessage());
        }
    }



    private PrintWriter recordLogger;

    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }

    /**
     * 在元素2-2中初始化慢放显示视图
     */
    private void setupElement2_2SlowView() {
        if (element2_2 == null) return;

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/ui/slow_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            recordLogger = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }

        LogTools.getInstance().logRecord("🔍 setupElement2_2SlowView() 开始执行");

        if (element2_2 == null) {
            LogTools.getInstance().logRecord2("❌ element2_2 为 null，无法加载");
            return;
        }

        // ⭐ 先移除占位 Label
        if (element2_2Label != null) {
            element2_2.getChildren().remove(element2_2Label);
            LogTools.getInstance().logRecord("✅ 已移除占位 Label");
        }

        // ⭐ 使用 Platform.runLater 确保在 JavaFX 线程执行
        Platform.runLater(() -> {
            try {
                LogTools.getInstance().logRecord("📂 开始加载 FXML: /com/acard/acard/ui/Element2_2Pane.fxml");

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/acard/acard/ui/Element2_2Pane.fxml"));


                if (loader.getLocation() == null) {
                    LogTools.getInstance().logRecord2("❌ FXML 文件未找到！");
                    updateStatusLabel("❌ FXML 文件未找到");
                    return;
                }

                LogTools.getInstance().logRecord("✅ FXML 文件找到: " + loader.getLocation());

                Parent pane = loader.load();
                LogTools.getInstance().logRecord("✅ FXML 加载成功");

                element2_2JpegController = loader.getController();
                LogTools.getInstance().logRecord("✅ 控制器获取成功: " + (element2_2JpegController != null));

                // 清空并添加新内容
                element2_2.getChildren().clear();
                LogTools.getInstance().logRecord("✅ element2_2 子节点已清空");

                element2_2.getChildren().add(pane);
                LogTools.getInstance().logRecord("✅ 新 Pane 已添加到 element2_2");

                // ⚠️ 修复：这些绑定应该在 Pane 本身，而不是 element2_2
                // 因为 pane 已经是 element2_2 的子节点了
                // element2_2 本身已经设置了 vgrow 和 minHeight

                updateStatusLabel("✅ 元素2-2已加载 JPEG 序列播放器");
                LogTools.getInstance().logRecord("🎉 Element2_2JpegPane 加载完成！");

            } catch (Exception e) {
                LogTools.getInstance().logRecord("❌ 加载失败，异常信息: " + e.getMessage());
                e.printStackTrace();
                updateStatusLabel("❌ 加载 JPEG 播放器失败: " + e.getMessage());
            }
        });

    }

    /**
     * ⭐ 推送新的 JPEG 帧到 element2_2 播放器
     *
     * @param sourcePath 源文件路径（runtime/captures/ssl/s_xxxxx.jpeg）
     * @param frameIndex 帧索引
     */
    public void pushJpegFrameToElement2_2(String sourcePath, int frameIndex) {
        if (element2_2JpegController != null) {
            element2_2JpegController.pushNewFrame(sourcePath, frameIndex);
        }
    }

    /**
     * ⭐ 重置 JPEG 复制计数器（录制新会话时调用）
     */
    public void resetJpegCopyCountInElement2_2() {
        if (element2_2JpegController != null) {
            element2_2JpegController.resetCopyCount();
        }
    }

    /**
     * ⭐ 获取 Element2_2JpegController（供 Element2_3Controller 使用）
     */
    public Element2_2JpegController getElement2_2JpegController() {
        return element2_2JpegController;
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
     * 初始化底部状态栏的 Tooltip（鼠标悬停提示）
     */
    private void setupStatusBarTooltips() {
        // 左侧状态组 Tooltip
        if (monitorDeviceValue != null) {
            Tooltip.install(monitorDeviceValue.getParent(), createFastTooltip("监控设备"));
        }
        if (monitorLinkStatusValue != null) {
            Tooltip.install(monitorLinkStatusValue.getParent(), createFastTooltip("链接状态"));
        }
        if (pushStatusValue != null) {
            Tooltip.install(pushStatusValue.getParent(), createFastTooltip("在线状态"));
        }
        if (tsfpsValue != null) {
            Tooltip.install(tsfpsValue.getParent(), createFastTooltip("帧率 (fps)"));
        }
        if (kbpsValue != null) {
            Tooltip.install(kbpsValue.getParent(), createFastTooltip("码率"));
        }
        if (batteryValue != null) {
            Tooltip.install(batteryValue.getParent(), createFastTooltip("电量"));
        }
        if (networkTypeValue != null) {
            Tooltip.install(networkTypeValue.getParent(), createFastTooltip("联网类型"));
        }
        if (networkQualityValue != null) {
            Tooltip.install(networkQualityValue.getParent(), createFastTooltip("网络质量"));
        }
        // 右侧状态组 Tooltip
        if (bandwidthValue != null) {
            Tooltip.install(bandwidthValue.getParent(), createFastTooltip("接收流量"));
        }
        if (controlNetworkQualityValue != null) {
            Tooltip.install(controlNetworkQualityValue.getParent(), createFastTooltip("网络质量"));
        }
        if (controlDeviceValue != null) {
            Tooltip.install(controlDeviceValue.getParent(), createFastTooltip("控制设备"));
        }
    }

    /**
     * 初始化Element2_3Controller（不再显示在UI上，但功能保留）
     */
    private void setupElement2_3Panel() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/acard/acard/ui/Element2_3Pane.fxml"));
            Parent pane = loader.load();
            Element2_3Controller controller = loader.getController();
            controller.setCameraMainController(this);
            
            // 注入 GpuView，使用其实时缓存进行抓拍
            if (element2_1Player != null) {
                controller.setGpuView(element2_1Player);
            }
            // 建立与主控制器的联动，便于控制慢放采集/播放
            controller.setLinkedController(this);
            // 将元素2-3的行列设置联动到元素1
            controller.setOnGridChange((r, c) -> {
                if (element1Controller != null) {
                    element1Controller.setGridSize(r, c);
                }
            });
            
            this.element2_3Controller = controller;
            updateStatusLabel("Element2_3Controller已初始化");

        } catch (Exception e) {
            updateStatusLabel("初始化Element2_3Controller失败: " + e.getMessage());
            System.err.println("初始化Element2_3Controller失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 加载元素1独立抓拍面板
     */
    private void setupElement1Panel() {
        if (element1 == null) return;
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/acard/acard/ui/Element1Pane.fxml"));
            Parent pane = loader.load();
            element1Controller = loader.getController();
            if (pane instanceof Region) {
                ((Region) pane).setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            StackPane.setAlignment(pane, javafx.geometry.Pos.TOP_LEFT);
            element1.getChildren().setAll(pane);
            updateStatusLabel("元素1已加载独立抓拍面板");
        } catch (Exception e) {
            updateStatusLabel("加载元素1面板失败: " + e.getMessage());
            System.err.println("加载元素1面板失败: " + e.getMessage());
            e.printStackTrace();
        }
    }


    
    /** 
     * ✅ 向元素1添加一次磁盘抓拍结果（新格式：DiskCaptureCache.DiskFrameItem，零内存）
     * @deprecated 请使用 updateItemByIndex 方法直接指定索引
     */
    @Deprecated
    public void addDiskCaptureToElement1(List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        if (element1Controller != null && frames != null && !frames.isEmpty()) {
            element1Controller.addDiskCaptureV2(frames, eventIndex);
        }
    }
    
    /** ⚡ 像 Android adapter 一样直接添加新抓拍项到末尾（一步到位，最大100个） */
    public void addDiskCaptureV2(List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        if (element1Controller != null && frames != null && !frames.isEmpty()) {
            element1Controller.addNewCaptureItemDirect(frames, eventIndex);
            
            // ⚡ 将抓拍帧加入上传队列（异步上传，不阻塞）
            // 只上传中间帧和前后各一帧，共3张
            LogTools.getInstance().logRecord3("eventIndex: "+eventIndex);
            int totalFrames = frames.size();
            for (int i = -1; i <= 1; i++) {
                int frameIndexToUpload = eventIndex + i;
                LogTools.getInstance().logRecord3("eventIndex: "+frameIndexToUpload);
                if (frameIndexToUpload >= 0 && frameIndexToUpload < totalFrames) {
                    LogTools.getInstance().logRecord3("eventIndex true: "+frameIndexToUpload);
                    com.acard.acard.capture.DiskCaptureCache.DiskFrameItem frame = frames.get(frameIndexToUpload);
                    if (frame.getFilePath() != null && !frame.getFilePath().isEmpty()) {
                        com.acard.acard.tools.ImageUploadQueue.getInstance().enqueueCapture(frame.getFilePath());
                    }
                }
            }
        }
    }
    
    /** ✅ 通过指定索引更新抓拍项（零内存，线程安全） */
    public void updateCaptureItemByIndex(int itemIndex, List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int eventIndex) {
        if (element1Controller != null && frames != null && !frames.isEmpty()) {
            element1Controller.updateItemByIndex(itemIndex, frames, eventIndex);
        }
    }
    
    /**
     * ✅ 注册抓拍会话（sessionId → itemIndex）
     */
    public void registerCaptureSession(String sessionId, int itemIndex) {
        if (sessionId != null && !sessionId.isEmpty()) {
            sessionIdToItemIndex.put(sessionId, itemIndex);
            LogTools.getInstance().logRecord2("✅ 注册抓拍会话: " + sessionId + " → item[" + itemIndex + "]");
        }
    }
    
    /**
     * ✅ 通过sessionId追加帧到对应的UI item
     */
    public void appendFrameBySessionId(String sessionId, com.acard.acard.capture.DiskCaptureCache.DiskFrameItem newFrame) {
        Integer index = sessionIdToItemIndex.get(sessionId);
        if (index != null && element1Controller != null) {
            element1Controller.appendFrameToItem(index, newFrame);
        } else {
            System.err.println("⚠️ 未找到sessionId对应的item: " + sessionId);
        }
    }
    
    /**
     * ✅ 取消注册抓拍会话（可选，用于清理）
     */
    public void unregisterCaptureSession(String sessionId) {
        sessionIdToItemIndex.remove(sessionId);
    }
    
    /**
     * ✅ 追加单个帧到指定的抓拍项（用于事件驱动的后续帧追加）
     */
    public void appendFrameToCaptureItem(int index, com.acard.acard.capture.DiskCaptureCache.DiskFrameItem newFrame) {
        if (element1Controller != null) {
            element1Controller.appendFrameToItem(index, newFrame);
        }
    }


    /** 
     * ✅ 显式创建一个新的抓拍项（线程安全）
     * @return 新创建的item索引，失败返回-1
     */
    public int createNewCaptureItem() {
        if (element1Controller != null) {
            return element1Controller.addEmptyItem();
        }
        return -1;
    }
    
    /** ✅ 获取当前抓拍项总数（用于多次快速抓拍时确定索引） */
    public int getCaptureItemCount() {
        if (element1Controller != null) {
            return element1Controller.getCaptureItemCount();
        }
        return 0;
    }


    


    /** 清空元素1的抓拍UI */
    public void clearSnapshotUI() {

        if (element1Controller != null) {
            element1Controller.clearAllItems();
        }

    }

    /**
     * 在点击抓拍时，锁定元素1中最后一个抓拍项的视口大小为当前网格单元大小。
     */
    public void lockLastCaptureViewport() {
        if (element1Controller != null) {
            element1Controller.lockLastItemViewport();
        }
    }

    /**
     * 开始慢放采集（采集元素2-1的实时画面）
     */
    public void startSlowMoCapture() {
        if (element2_1Player != null) {
            // 确保慢放UI已加载
            updateStatusLabel("开始慢放采集");
            startAutoStopOnCapacity();
        }
    }

    /**
     * 开始慢放采集并以指定倍数启动元素2-2的live播放（边下载边播）。
     * factor: 1..10，倍数越大显示越慢（固定基准约30fps）。
     */
    public void startSlowMoLiveWithFactor(int factor) {
        if (element2_1Player == null) return;
        // 确保慢放UI已加载
        /*if (slowPaneController == null) {
            setupElement2_2SlowView();
        }*/
        // 清空旧数据并开始采集
        //改
        //element2_1Player.clearSlowMoBuffers();
        //element2_1Player.startSlowMoCapture();
        // UI立即归零显示下载帧数
        /*if (slowPaneController != null) {
            slowPaneController.resetDownloadProgressImmediate();
        }*/
        updateStatusLabel("开始慢放采集（live倍数=" + Math.max(1, Math.min(10, factor)) + ")");
        // 以传入倍数启动live模式：固定基准约30fps，倍数越大播放越慢
       /* if (slowPaneController != null) {
            slowPaneController.startLivePlayback(Math.max(1, Math.min(10, factor)));
        }*/
        // 自动在达到180帧时停止采集
        startAutoStopOnCapacity();
    }

    /**
     * 动态更新慢放倍数：元素2-3滑块变化时调用，使元素2-2正在播放的速度即时变化。
     */
    public void updateSlowMoFactor(int factor) {

    }



    
    /**
     * ✅ 新增：停止GPU慢放播放器
     */
    public void stopSlowMoGpuPlayer() {
        if (slowMoGpuPlayer != null) {
            slowMoGpuPlayer.stop();
            slowMoGpuPlayer.dispose();
            slowMoGpuPlayer = null;
            LogTools.getInstance().logRecord2("✅ GPU慢放播放器已停止");
        }
    }
    
    /**
     * ✅ 新增：调整GPU慢放播放速度（实时）
     * @param rate 播放速度（0.1 ~ 2.0）
     */
    public void setSlowMoGpuRate(double rate) {
        if (slowMoGpuPlayer != null) {
            slowMoGpuPlayer.setRate(rate);
            updateStatusLabel("慢放速度已调整: " + rate + "x");
            LogTools.getInstance().logRecord2("✅ 慢放速度已调整: " + rate + "x");
        }
    }
    
    /**
     * ✅ 新增：暂停/恢复GPU慢放播放
     */
    public void toggleSlowMoGpuPause() {
        if (slowMoGpuPlayer != null) {
            if (slowMoGpuPlayer.isPlaying()) {
                slowMoGpuPlayer.pause();
                updateStatusLabel("慢放已暂停");
            } else {
                slowMoGpuPlayer.play();
                updateStatusLabel("慢放已恢复");
            }
        }
    }





    // 慢放Pane的资源释放
    private void disposeSlowPane() {
        /*if (slowPaneController != null) {
            slowPaneController.dispose();
        }*/
        // ✅ 清理GPU慢放播放器
        stopSlowMoGpuPlayer();
        cancelAutoStopOnCapacity();
    }

    // 在采集过程中，当帧数达到容量(180)时自动停止采集
    private void startAutoStopOnCapacity() {
        cancelAutoStopOnCapacity();
        slowAutoStopFuture = slowCaptureScheduler.scheduleAtFixedRate(() -> {
            try {
                if (element2_1Player == null) return;

            } catch (Throwable ignore) {}
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void cancelAutoStopOnCapacity() {
        if (slowAutoStopFuture != null) {
            slowAutoStopFuture.cancel(false);
            slowAutoStopFuture = null;
        }
    }



    // 控制端链接状态：在线=蓝色圆点+蓝色文字，未连接=黑色圆点+黑色文字
    private void updateControlLinkStatus(boolean online) {
        if (controlLinkStatusValue != null) {
            controlLinkStatusValue.setText(online ? "在线" : "未连接");
            controlLinkStatusValue.setTextFill(online ? Color.web("#22CC77") : Color.BLACK);
        }
        if (controlLinkStatusDot != null) {
            controlLinkStatusDot.setFill(online ? Color.web("#22CC77") : Color.BLACK);
            controlLinkStatusDot.setRadius(7.5);
        }
    }
    
    /**
     * ✅ 恢复底部状态栏到初始状态（iOS设备离线时调用）
     */
    private void resetBottomStatusBar() {
        FileToos.isIsCallBackFrame = false;
        
        // 链接状态：未连接（灰色）
        if (monitorLinkStatusValue != null) {
            monitorLinkStatusValue.setText("未连接");
            monitorLinkStatusValue.setTextFill(Color.web("#CCCCCC"));
        }
        
        // 在线状态：未上线（灰色）
        if (pushStatusValue != null) {
            pushStatusValue.setText("未上线");
            pushStatusValue.setTextFill(Color.web("#CCCCCC"));
        }
        
        // fps：0（灰色）
        if (tsfpsValue != null) {
            tsfpsValue.setText("0");
            tsfpsValue.setTextFill(Color.web("#CCCCCC"));
        }
        
        // 码率：0kb/s（灰色）
        if (kbpsValue != null) {
            kbpsValue.setText("0kb/s");
            kbpsValue.setTextFill(Color.web("#CCCCCC"));
        }
        
        LogTools.getInstance().logRecord("✅ 底部状态栏已恢复初始状态");
    }
    
    /**
     * ✅ 静态方法：判断 iOS 设备是否在线
     * 通过检查 CONFIG_STATE 消息的时间差来判断
     * @return true=在线（推流中），false=离线
     */
    public static boolean isDeviceOnline() {
        // 条件1：publishState 必须为 1（推流中）
        if (publishState != 1) {
            return false;
        }
        // 条件2：最后收到 CONFIG_STATE 的时间不超过 3 秒
        long now = System.currentTimeMillis();
        return (now - lastConfigStateTime) < CONFIG_STATE_TIMEOUT_MS;
    }

    // 通过系统属性判断当前控制设备类型
    private String detectControlDevice() {
        String osName = System.getProperty("os.name", "Unknown").toLowerCase();
        if (osName.contains("win")) return "Windows";
        if (osName.contains("mac")) return "macOS";
        if (osName.contains("nux") || osName.contains("nix") || osName.contains("aix")) return "Linux";
        return "其它";
    }
    // 解析设备频道的监控状态（type=CONFIG_STATE），并刷新状态栏显示
    private void processConfigStateMessage(String payload) {
        try {
            // 使用宽松模式解析，容忍轻微格式问题（例如多余逗号、未转义字符）
            JsonReader reader = new JsonReader(new StringReader(payload));
            reader.setLenient(true);
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : null;

            LogTools.getInstance().logRecord3("type: "+type);
            if ("CONFIG_STATE".equals(type)){


                String msgDeviceId = root.has("deviceId") ? root.get("deviceId").getAsString() : null;
                LoginResponse resp = AuthStore.getInstance().getLoginResponse();
                String expectedDeviceId = resp != null ? resp.getDeviceId() : null;
                if (expectedDeviceId == null || !expectedDeviceId.equals(msgDeviceId)) {
                    // 非本次登录绑定的设备，忽略
                    LogTools.getInstance().logRecord2("===========> error");
                    return;
                }
                LogTools.getInstance().logRecord2("===========> succsss");
                
                // ✅ 更新最后收到 CONFIG_STATE 的时间戳（用于判断设备是否在线）
                lastConfigStateTime = System.currentTimeMillis();
                
                JsonObject state = root.has("state") && root.get("state").isJsonObject() ? root.getAsJsonObject("state") : null;
                if (state == null) return;


                int newPublishStatus = 0;
                String networkType = null;
                Integer battery = null;
                String os = null;
                String model = null;
                String streamKey="";
                String streamPushIp = "";  // ⭐ 拉流服务器 IP（与 streamKey 同级）
                try { if (state.has("publishStatus")) newPublishStatus = state.get("publishStatus").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("networkType")) networkType = state.get("networkType").getAsString(); } catch (Exception ignore) {}
                try { if (state.has("battery")) battery = state.get("battery").getAsInt(); } catch (Exception ignore) {}
                try {
                    if (state.has("deviceType") && state.get("deviceType").isJsonObject()) {
                        JsonObject dt = state.getAsJsonObject("deviceType");
                        if (dt.has("os")) os = dt.get("os").getAsString();
                        if (dt.has("model")) model = dt.get("model").getAsString();
                    }
                } catch (Exception ignore) {}
                try { if (state.has("streamKey")) streamKey = state.get("streamKey").getAsString(); } catch (Exception ignore) {}
                // ⭐ 解析 streamPushIp 字段（拉流服务器 IP）
                try { if (state.has("streamPushIp")) streamPushIp = state.get("streamPushIp").getAsString(); } catch (Exception ignore) {}
                //LogTools.getInstance().logRecord5("📡 从 CONFIG_STATE 获取拉流 IP: " + streamPushIp);
                // ⭐ 更新全局配置中的 streamPushIp
                if (streamPushIp != null && !streamPushIp.isEmpty()) {
                    NetworkConfig.setStreamPushIp(streamPushIp);
                    //LogTools.getInstance().logRecord5("📡 从 CONFIG_STATE 获取拉流 IP: " + streamPushIp);
                }
                
                // ✅ 解析激活/试用相关字段（画质控制）
                try { if (state.has("trialRequired")) trialRequired = state.get("trialRequired").getAsBoolean(); } catch (Exception ignore) {}
                try { if (state.has("activated")) activated = state.get("activated").getAsBoolean(); } catch (Exception ignore) {}
                try { if (state.has("activationLevel")) activationLevel = state.get("activationLevel").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("activationLevelName")) activationLevelName = state.get("activationLevelName").getAsString(); } catch (Exception ignore) {}
                try { if (state.has("activationExpireAt")) activationExpireAt = state.get("activationExpireAt").getAsString(); } catch (Exception ignore) {}
                try { if (state.has("trialEnded")) trialEnded = state.get("trialEnded").getAsBoolean(); } catch (Exception ignore) {}
                try { if (state.has("currentStage")) currentStage = state.get("currentStage").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("totalStages")) totalStages = state.get("totalStages").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("stageSeconds")) stageSeconds = state.get("stageSeconds").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("remainingSeconds")) remainingSeconds = state.get("remainingSeconds").getAsInt(); } catch (Exception ignore) {}
                try { if (state.has("usedSeconds")) usedSeconds = state.get("usedSeconds").getAsInt(); } catch (Exception ignore) {}
                // 解析 qualityAccess 数组
                try {
                    if (state.has("qualityAccess") && state.get("qualityAccess").isJsonArray()) {
                        com.google.gson.JsonArray arr = state.getAsJsonArray("qualityAccess");
                        qualityAccess = new String[arr.size()];
                        for (int i = 0; i < arr.size(); i++) {
                            qualityAccess[i] = arr.get(i).getAsString();
                        }
                    }
                } catch (Exception ignore) {}

                // ⭐ 推流状态处理逻辑
                if(newPublishStatus==1&&streamKey!=null&&streamKey.length()>0){
                    if(publishState==0) {
                        publishState=1;
                        
                        // ✅ publishState = 1 时，按钮显示"休眠"
                        Platform.runLater(() -> {
                            if (element2_1Player != null) {
                                element2_1Player.setSleepButtonToSleep();
                            }
                        });
                        
                        //StompWebSocketClient.getInstance().sendResetPublish();
                        // ⭐ 延迟 1秒后再启动播放器，确保前端推流已稳定
                        PauseTransition delay = new PauseTransition(Duration.seconds(0.1));
                        String finalStreamKey = streamKey;
                        corePlayer.setStreamId(finalStreamKey);
                        
                        // ⭐ 使用动态获取的 streamPushIp 更新播放器的服务器地址
                        String effectiveIp = NetworkConfig.getEffectiveStreamIp();
                        corePlayer.setServerHost(effectiveIp);
                        LogTools.getInstance().logRecord("📡 播放器使用拉流 IP: " + effectiveIp);

                        if(startFirst==0){
                            if (corePlayer != null) {
                                LogTools.getInstance().logRecord("⏰ 延迟1秒后执行 play()（会自动协商） "+finalStreamKey);
                                
                                // ⚠️ 测试软解：取消下面这行注释即可强制使用软解
                                // SimpleWebRTCPlayer.setPreferHardwareDecoder(false);
                                
                                LogTools.getInstance().logRecord("🎛️ 当前解码器模式: " + (SimpleWebRTCPlayer.isPreferHardwareDecoder() ? "优先硬解" : "强制软解"));
                                try {
                                    corePlayer.play();
                                    // ✅ 更新状态栏解码器显示
                                    updateDecoderInfo();
                                    // ⚡ 检测并设置 GpuView 软解模式
                                    if (element2_1Player != null) {
                                        element2_1Player.detectAndSetDecoderMode();
                                    }
                                } catch (Exception e) {
                                    LogTools.getInstance().logRecord("❌ 启动播放器失败: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                            delay.play();
                        }else{
                            startFirst=1;
                            if (corePlayer != null) {
                                LogTools.getInstance().logRecord("⏰ 延迟1秒后执行 play()（会自动协商） "+finalStreamKey);
                                try {
                                    corePlayer.play();
                                    // ✅ 更新状态栏解码器显示
                                    updateDecoderInfo();
                                    // ⚡ 检测并设置 GpuView 软解模式
                                    if (element2_1Player != null) {
                                        element2_1Player.detectAndSetDecoderMode();
                                    }
                                } catch (Exception e) {
                                    LogTools.getInstance().logRecord("❌ 启动播放器失败: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                        }

                    }
                } else {
                    // ⭐ 推流停止，清理标志
                    if(publishState==1){
                        corePlayer.stop();
                    }
                    publishState = 0;
                    FileToos.isIsCallBackFrame = false;
                    
                    // ✅ publishState = 0 时，按钮显示"工作"
                    Platform.runLater(() -> {
                        if (element2_1Player != null) {
                            element2_1Player.setSleepButtonToWork();
                        }
                    });
                }



                final String monitorDeviceText = buildMonitorDeviceText(os, model);
            final String pushText = (newPublishStatus == 1) ? "在线中" : "未上线";
            final boolean pushing = newPublishStatus == 1;
            final int finalPublishStatus = newPublishStatus;
            if(pushText.equals("未上线")){

                new Thread(() -> {
                    try {
                        // 清空所有抓拍文件夹
                        DirectoryUtils.clearDirectory("runtime/captures/slow");
                        // ⭐ 暂时注释：保留 ssl 图片用于 AI 训练
                        // DirectoryUtils.clearDirectory("runtime/captures/ssl");

                    } catch (Throwable e) {

                    }
                }, "Clean").start();  // 在后台线程执行

            }
            
            // ✅ 检查状态是否真正变化
            final boolean statusChanged = (lastPublishStatus != newPublishStatus);
            
            // ✅ 记录推流状态变化

                final String batteryText = (battery != null) ? (battery + "%") : null;
                final String networkText = networkType;
                // 从ConfigStore缓存的设备简化配置中获取FPS（final，避免lambda捕获错误）

                try { if (state.has("fps")) fpsText = state.get("fps").getAsString(); } catch (Exception ignore) {}
                try { if (state.has("sendFps")) tsfpsText = state.get("sendFps").getAsString(); } catch (Exception ignore) {}

                if(tsfpsText!=null&&tsfpsText.length()>0){
                    double mfps = Double.parseDouble(tsfpsText);
                    mfps = mfps * 2;
                    tsfpsText  =""+ (int)mfps;
                }

                // 提取 fps/kbps 字段用于右上角叠加显示和状态栏显示
                Double cfgFps = null; Integer cfgKbps = null;
                try { if (state.has("fps")) cfgFps = state.get("fps").getAsDouble(); } catch (Exception ignore) {}
                try { if (state.has("kbps")) cfgKbps = state.get("kbps").getAsInt(); } catch (Exception ignore) {}
                
                // ✅ 计算接收流量（基于kbps）
                final String bandwidthText = calculateBandwidth(cfgKbps);
                
                // ✅ 提取网络质量相关字段（与 fps 同级）
                String networkQuality = null;
                Double packetLoss = null;
                Integer rtt = null;
                try { if (state.has("networkQuality")) networkQuality = state.get("networkQuality").getAsString(); } catch (Exception ignore) {}
                try { if (state.has("packetLoss")) packetLoss = state.get("packetLoss").getAsDouble(); } catch (Exception ignore) {}
                try { if (state.has("rtt")) rtt = state.get("rtt").getAsInt(); } catch (Exception ignore) {}
                
                // ✅ 格式化 kbps 文本用于状态栏显示
                final String kbpsText;
                if (cfgKbps != null && cfgKbps > 0) {
                    kbpsText = cfgKbps + " kb/s";
                } else {
                    kbpsText = "0 kb/s";
                }
                
                final String overlayText;
                if (cfgFps != null && cfgFps > 0 && cfgKbps != null && cfgKbps > 0) {
                    overlayText = String.format("%.0ffps | %dkbps", cfgFps, cfgKbps);
                } else if (cfgFps != null && cfgFps > 0) {
                    overlayText = String.format("%.0ffps", cfgFps);
                } else if (cfgKbps != null && cfgKbps > 0) {
                    overlayText = String.format("%dkbps", cfgKbps);
                } else {
                    overlayText = null;
                }

                // ✅ 计算并格式化网络质量显示（需要在 Platform.runLater 之前完成计算）
                final String qualityDisplayText;
                final String qualityColor;
                final String qualityBgColor;
                final String qualityTooltip;
                
                if (networkQuality != null) {
                    // 后端已经计算好了质量等级，直接使用
                    switch (networkQuality) {
                        case "excellent":
                            qualityDisplayText = "优秀";
                            qualityColor = "#00bb00";      // 深绿色文字
                            qualityBgColor = "rgba(0, 187, 0, 0.15)";  // 淡绿色背景
                            break;
                        case "good":
                            qualityDisplayText = "良好";
                            qualityColor = "#0066ff";      // 蓝色文字
                            qualityBgColor = "rgba(0, 102, 255, 0.15)";  // 淡蓝色背景
                            break;
                        case "fair":
                            qualityDisplayText = "一般";
                            qualityColor = "#ff8800";      // 橙色文字
                            qualityBgColor = "rgba(255, 136, 0, 0.15)";  // 淡橙色背景
                            break;
                        case "poor":
                            qualityDisplayText = "差";
                            qualityColor = "#dd0000";      // 红色文字
                            qualityBgColor = "rgba(221, 0, 0, 0.15)";  // 淡红色背景
                            break;
                        default:
                            qualityDisplayText = "未知";
                            qualityColor = "#888888";      // 灰色文字
                            qualityBgColor = "rgba(136, 136, 136, 0.15)";  // 淡灰色背景
                            break;
                    }
                } else if (packetLoss != null && rtt != null) {
                    // 后端没有提供质量等级，前端计算
                    double lossPercent = packetLoss * 100;
                    if (lossPercent <= 1.0 && rtt <= 100) {
                        qualityDisplayText = "优秀";
                        qualityColor = "#00bb00";
                        qualityBgColor = "rgba(0, 187, 0, 0.15)";
                    } else if (lossPercent <= 3.0 && rtt <= 200) {
                        qualityDisplayText = "良好";
                        qualityColor = "#0066ff";
                        qualityBgColor = "rgba(0, 102, 255, 0.15)";
                    } else if (lossPercent <= 5.0 && rtt <= 400) {
                        qualityDisplayText = "一般";
                        qualityColor = "#ff8800";
                        qualityBgColor = "rgba(255, 136, 0, 0.15)";
                    } else {
                        qualityDisplayText = "差";
                        qualityColor = "#dd0000";
                        qualityBgColor = "rgba(221, 0, 0, 0.15)";
                    }
                } else {
                    qualityDisplayText = "未知";
                    qualityColor = "#888888";
                    qualityBgColor = "rgba(136, 136, 136, 0.15)";
                }
                
                // 构建 Tooltip
                if (packetLoss != null && rtt != null) {
                    qualityTooltip = String.format("网络质量: %s\n丢包率: %.1f%%\nRTT: %dms", 
                        qualityDisplayText, packetLoss * 100, rtt);
                } else {
                    qualityTooltip = "网络质量: " + qualityDisplayText;
                }
                
                Platform.runLater(() -> {
                    if (pushStatusValue != null) {
                        pushStatusValue.setText(pushText);
                        pushStatusValue.setTextFill(pushing ? Color.web("#22CC77") : Color.BLACK);
                    }
                    if (monitorDeviceValue != null && monitorDeviceText != null) monitorDeviceValue.setText("ios (建议清晰度: 50)");
                    if (networkTypeValue != null && networkText != null) networkTypeValue.setText(networkText);


                   // if (tsfpsValue != null && tsfpsText != null) tsfpsValue.setText( tsfpsText);


                    tsfpsValue.setText(FileToos.receiveFps*2 + " fps");


                    if (kbpsValue != null && kbpsText != null) kbpsValue.setText(kbpsText);  // ✅ 更新状态栏 kb/s 显示
                    if (batteryValue != null && batteryText != null) batteryValue.setText(batteryText);
                    
                    // ✅ 更新网络质量显示（突出显示：加粗、大字号、彩色背景）
                    if (networkQualityValue != null) {
                        networkQualityValue.setText(qualityDisplayText);
                        networkQualityValue.setTextFill(Color.web(qualityColor));
                        // 设置样式：加粗、内边距、圆角背景
                        String style = String.format(
                            "-fx-font-weight: bold; -fx-padding: 2 8 2 8; " +
                            "-fx-background-radius: 3; -fx-background-color: %s;",
                            qualityBgColor
                        );
                        networkQualityValue.setStyle(style);
                        // 设置Tooltip（快速显示）
                        networkQualityValue.setTooltip(createFastTooltip(qualityTooltip));
                    }
                    if (monitorLinkStatusValue != null) {
                        monitorLinkStatusValue.setText("在线");
                        monitorLinkStatusValue.setTextFill(Color.web("#22CC77"));
                    }
                    
                    // ✅ 更新接收流量显示
                    if (bandwidthValue != null && bandwidthText != null) {
                        bandwidthValue.setText(bandwidthText);
                    }
                    
                    // ✅ 更新Windows端网络质量显示
                    updateLocalNetworkQuality();
                    
                    // 更新实时播放器右上角叠加
                    if (element2_1Player != null) {
                        element2_1Player.setMetricsText(overlayText);
                    }
                    
                    // ✅ 根据 publishStatus 控制播放器状态（带状态保护）
                    // publishStatus == 1: 开始推流，启动播放器
                    // publishStatus == -1: 停止推流，停止播放器
                    // 只在状态真正变化时执行操作，避免重复调用




                    if (corePlayer != null && corePlayerStarted && statusChanged) {
                        if (finalPublishStatus == 1 && !isPlayerPlaying) {
                            LogTools.getInstance().logRecord2("🎬 状态变化：推流开始（1），启动播放器");
                            try {

                                isPlayerPlaying = true;
                                lastPublishStatus = finalPublishStatus;
                                LogTools.getInstance().logRecord2("✅ 播放器已启动");
                                
                                // ✅ 延迟2秒后获取解码器信息并显示到状态栏
                                PauseTransition decoderInfoDelay =
                                    new PauseTransition(Duration.seconds(1));
                                decoderInfoDelay.setOnFinished(e -> updateDecoderInfo());
                                decoderInfoDelay.play();
                            } catch (Exception e) {
                                System.err.println("❌ 启动播放器失败: " + e.getMessage());
                                e.printStackTrace();
                                isPlayerPlaying = false;  // 失败时重置状态
                            }
                        } else if (finalPublishStatus == -1 && isPlayerPlaying) {
                            LogTools.getInstance().logRecord2("⏹ 状态变化：停止推流（-1），停止播放器");
                            try {
                                publishState = 0;
                                FileToos.isIsCallBackFrame = false;
                                corePlayer.stop();
                                isPlayerPlaying = false;
                                lastPublishStatus = finalPublishStatus;
                                LogTools.getInstance().logRecord2("✅ 播放器已停止");
                                // 注意：这里不重置休眠按钮，因为可能是用户主动休眠导致的
                            } catch (Exception e) {
                                System.err.println("❌ 停止播放器失败: " + e.getMessage());
                                e.printStackTrace();
                                // stop失败也认为已停止，避免状态错乱
                                isPlayerPlaying = false;
                            }
                        } else if (statusChanged) {
                            // 其他状态变化（如0->1, 1->0等），更新lastPublishStatus
                            lastPublishStatus = finalPublishStatus;
                            LogTools.getInstance().logRecord2("📝 更新状态: " + lastPublishStatus);
                        }
                    } else if (!statusChanged) {
                        // 状态未变化，忽略重复消息
                        LogTools.getInstance().logRecord2("⏭️ publishStatus=" + finalPublishStatus + " 未变化，跳过");
                    } else {
                        LogTools.getInstance().logRecord2("⚠️ 播放器未就绪: corePlayer=" + 
                            (corePlayer != null ? "已创建" : "null") + 
                            ", corePlayerStarted=" + corePlayerStarted);
                    }
                });


            } else if ("CONFIG_UPDATE".equals(type)) {

                String msgDeviceId = root.has("deviceId") ? root.get("deviceId").getAsString() : null;
                LoginResponse resp = AuthStore.getInstance().getLoginResponse();
                String expectedDeviceId = resp != null ? resp.getDeviceId() : null;
                if (expectedDeviceId == null || !expectedDeviceId.equals(msgDeviceId)) {
                    // 非本次登录绑定的设备，忽略
                    return;
                }
                // 解析配置并更新到全局缓存（内部将进行变更检测并延迟2秒通知UI）
                JsonObject cfgObj = root.has("config") && root.get("config").isJsonObject()
                        ? root.getAsJsonObject("config")
                        : null;
                if (cfgObj != null) {
                    try {
                        ThinRemoteConfig incoming = new com.google.gson.Gson().fromJson(cfgObj, ThinRemoteConfig.class);
                        LogTools.getInstance().logRecord2("📩 收到CONFIG_UPDATE消息，更新配置到ConfigStore");
                        LogTools.getInstance().logRecord2("   - 方向(direction): " + incoming.getDirection());
                        LogTools.getInstance().logRecord2("   - 角度(angle): " + incoming.getAngle());
                        LogTools.getInstance().logRecord2("   - 画质(type): " + incoming.getType());
                        ConfigStore.getInstance().setThinConfig(incoming);
                        LogTools.getInstance().logRecord2("✅ 配置已更新，将在2秒后通知所有监听器");
                    } catch (Exception ex) {
                        System.err.println("解析CONFIG_UPDATE失败: " + ex.getMessage());
                    }
                }
            } else if ("CONFIG_ERROR".equals(type)) {

                LogTools.getInstance().logRecord("CONFIG_ERROR: " + root);
                
                // ✅ error 字段实际上是 iOS 设备账号（deviceUsername）
                String iosDeviceUsername = root.has("error") ? root.get("error").getAsString() : null;
                
                LoginResponse resp = AuthStore.getInstance().getLoginResponse();
                // ✅ 获取当前绑定的设备账号
                String currentDeviceUsername = resp != null ? resp.getCurrentDeviceUsername() : null;


                LogTools.getInstance().logRecord3("iosDeviceUsername-> "+iosDeviceUsername+" currentDeviceUsername:-> "+currentDeviceUsername);
                // ✅ 判断是否和当前绑定的设备账号一致
                if (currentDeviceUsername != null && currentDeviceUsername.equals(iosDeviceUsername)) {
                    LogTools.getInstance().logRecord("iOS设备断线，设备账号=" + iosDeviceUsername);

                    if (publishState == 1) {
                        corePlayer.stop();
                    }
                    publishState = 0;
                    FileToos.isIsCallBackFrame = false;

                    Platform.runLater(() -> {
                        // ✅ 恢复底部状态栏到初始状态
                        resetBottomStatusBar();
                        
                        // ✅ iOS掉线时，重置状态并显示"暂无视频"
                        if (element2_1Player != null) {
                            element2_1Player.onIosDisconnected();
                        }
                        
                        // 更新控制端连接状态
                        updateControlLinkStatus(false);
                    });
                }

            }else if("RESET_PUBLISH".equals(type)){

                LogTools.getInstance().logRecord("RESET_PUBLISH: "+root);


            }else if("TryDisconnect".equals(type)){

                LogTools.getInstance().logRecord3("TryDisconnect: "+root);


            }else if("UNBIND".equals(type)||"ACCOUNT_CLEARED".equals(type)){ //ACCOUNT_CLEARED

                String msgDeviceId = root.has("deviceId") ? root.get("deviceId").getAsString() : null;
                String controlUsername  = root.has("controlUsername")? root.get("controlUsername").getAsString() : null;

                LoginResponse loginResp = AuthStore.getInstance().getLoginResponse();
                if (loginResp != null) {

                    if(loginResp.getDeviceId().equals(msgDeviceId)&&loginResp.getUsername().equals(controlUsername)){


                        FileToos.isCallBack=false;
                        FileToos.FbRecordingStoppedEvent();

                        loginResp.setDeviceId("");
                        loginResp.setCurrentDeviceId("");
                        loginResp.setCurrentDeviceUsername("");
                        AuthStore.getInstance().saveLoginResponse(loginResp);

                        if (publishState == 1) {
                            corePlayer.setJpegSaveEnabled(false);
                            corePlayer.stop();
                        }
                        publishState = 0;
                        FileToos.isIsCallBackFrame = false;


                        Platform.runLater(() -> {
                            if (monitorLinkStatusValue != null) {
                                FileToos.isIsCallBackFrame = false;
                                monitorLinkStatusValue.setText("已解绑");
                                monitorLinkStatusValue.setTextFill(Color.RED);
                            }
                            
                            // ✅ 恢复底部状态栏到初始状态（与掉线逻辑一致）
                            resetBottomStatusBar();
                            
                            // ✅ 解绑时，按钮显示"工作"，状态显示"暂无视频"
                            if (element2_1Player != null) {
                                element2_1Player.setSleepButtonToWork();
                            }
                            
                            // 更新控制端连接状态
                            updateControlLinkStatus(false);
                            
                            // 提示用户设备已解绑
                            updateStatusLabel("iOS设备已解除绑定");
                            showSuccessDialog("iOS设备已解除绑定\n\n如需继续使用请重新绑定设备");
                            
                            // ⭐ 解绑后重新登录检查是否还有其他设备
                            checkDevicesAfterUnbind();
                        });

                        // ⭐ 不再关闭 WebSocket 连接，保持连接等待新的绑定消息
                        // 取消订阅旧设备的频道
                        try {
                            String oldDestination = "/topic/device/" + msgDeviceId + "/config";
                            NetworkManager.getInstance().unsubscribeWebSocket(oldDestination);
                            LogTools.getInstance().logRecord2("✅ 已取消订阅旧设备频道: " + oldDestination);
                        } catch (Exception e) {
                            System.err.println("取消订阅旧设备频道失败: " + e.getMessage());
                        }
                    }



                }



            }


        } catch (Exception e) {

            System.err.println("解析监控状态失败:=======================++++++++ " + e.getMessage());
        }
    }

    private String buildMonitorDeviceText(String os, String model) {
        StringBuilder sb = new StringBuilder();
        if (os != null && !os.isBlank()) sb.append(os);
        if (model != null && !model.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(model);
        }
        return sb.length() > 0 ? sb.toString() : "其它";
    }
    
    /**
     * ✅ 重置网速统计（流中断或重新连接时调用）
     */
    private void resetBandwidthStats() {
        lastReceivedBytes = 0;
        lastBandwidthUpdateTime = 0;
        totalReceivedBytes = 0;
        System.out.println("✅ 网速统计已重置");
    }
    
    /**
     * ✅ 计算接收流量（基于kbps）
     * @param kbps 接收的kbps值
     * @return 格式化的流量字符串（如 "1.2MB/s"）
     */
    private String calculateBandwidth(Integer kbps) {
        if (kbps == null || kbps <= 0) {
            // 流中断时重置统计
            if (lastBandwidthUpdateTime > 0) {
                long idleTime = System.currentTimeMillis() - lastBandwidthUpdateTime;
                if (idleTime > 3000) {  // 超过3秒没有数据，重置统计
                    resetBandwidthStats();
                }
            }
            return "0KB/s";
        }
        
        long currentTime = System.currentTimeMillis();
        
        // ⭐ 检查是否需要重置（长时间无数据后重新开始）
        if (lastBandwidthUpdateTime > 0) {
            long idleTime = currentTime - lastBandwidthUpdateTime;
            if (idleTime > 3000) {  // 超过3秒没有数据，重置统计
                resetBandwidthStats();
            }
        }
        
        long currentBytes = (long) kbps * 1024 / 8; // kbps 转为 字节/秒
        totalReceivedBytes += currentBytes;
        
        // 计算实际流量速率
        if (lastBandwidthUpdateTime > 0) {
            long timeDiff = currentTime - lastBandwidthUpdateTime;
            if (timeDiff > 0) {
                long bytesDiff = totalReceivedBytes - lastReceivedBytes;
                double bytesPerSecond = (double) bytesDiff / (timeDiff / 1000.0);
                
                // 更新记录
                lastReceivedBytes = totalReceivedBytes;
                lastBandwidthUpdateTime = currentTime;
                
                // 格式化显示
                if (bytesPerSecond >= 1024 * 1024) {
                    return String.format("%.2fMB/s", bytesPerSecond / (1024 * 1024));
                } else if (bytesPerSecond >= 1024) {
                    return String.format("%.1fKB/s", bytesPerSecond / 1024);
                } else {
                    return String.format("%.0fB/s", bytesPerSecond);
                }
            }
        }
        
        // 首次或更新记录
        lastReceivedBytes = totalReceivedBytes;
        lastBandwidthUpdateTime = currentTime;
        
        // 返回估算值（直接使用 kbps 转换）
        if (kbps >= 8192) {
            return String.format("%.2fMB/s", kbps / 8192.0);
        } else {
            return String.format("%.0fKB/s", kbps / 8.0);
        }
    }
    
    /**
     * ✅ 启动Windows端网络质量监控
     * 定期ping服务器测量RTT和丢包率
     */
    private void startLocalNetworkMonitor() {
        if (networkMonitorExecutor != null) {
            return; // 已启动
        }
        
        networkMonitorExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "NetworkMonitor");
            t.setDaemon(true);
            return t;
        });
        
        // 每5秒监测一次
        networkMonitorExecutor.scheduleAtFixedRate(() -> {
            try {
                // 获取服务器地址（从WebSocket连接中获取）
                String serverHost = getServerHost();
                if (serverHost == null || serverHost.isEmpty()) {
                    return;
                }
                
                // 执行3次ping测试
                int successCount = 0;
                long totalRtt = 0;
                int testCount = 3;
                
                for (int i = 0; i < testCount; i++) {
                    long startTime = System.currentTimeMillis();
                    try {
                        java.net.InetAddress addr = java.net.InetAddress.getByName(serverHost);
                        boolean reachable = addr.isReachable(2000); // 2秒超时
                        long endTime = System.currentTimeMillis();
                        
                        if (reachable) {
                            successCount++;
                            totalRtt += (endTime - startTime);
                        }
                    } catch (Exception e) {
                        // ping失败
                    }
                    
                    if (i < testCount - 1) {
                        Thread.sleep(500); // 间隔500ms
                    }
                }
                
                // 计算RTT和丢包率
                if (successCount > 0) {
                    localRtt = (int) (totalRtt / successCount);
                    localPacketLoss = 1.0 - ((double) successCount / testCount);
                } else {
                    localRtt = -1;
                    localPacketLoss = 1.0; // 100%丢包
                }
                
                // 更新UI
                Platform.runLater(this::updateLocalNetworkQuality);
                
            } catch (Exception e) {
                System.err.println("⚠️ 网络质量监控异常: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    
    /**
     * ✅获取服务器主机地址
     * 从系统属性或默认配置中获取
     */
    private String getServerHost() {
        try {
            // 尝试从系统属性获取
            String serverUrl = System.getProperty("server.url");
            if (serverUrl != null && !serverUrl.isEmpty()) {
                URL url = new URL(serverUrl);
                return url.getHost();
            }
            
            // 尝试从环境变量获取
            serverUrl = System.getenv("SERVER_URL");
            if (serverUrl != null && !serverUrl.isEmpty()) {
                URL url = new URL(serverUrl);
                return url.getHost();
            }
            
            // 默认使用本地服务器（开发环境）
            // 生产环境应通过系统属性或配置文件指定
            return "localhost";
            
        } catch (Exception e) {
            System.err.println("⚠️ 获取服务器主机地址失败: " + e.getMessage());
            return "localhost"; // 返回默认值
        }
    }
    
    /**
     * ✅ 更新Windows端网络质量显示
     */
    private void updateLocalNetworkQuality() {
        if (controlNetworkQualityValue == null) {
            return;
        }
        
        String displayText;
        String color;
        String bgColor;
        String tooltip;
        
        if (localRtt < 0 || localPacketLoss < 0) {
            displayText = "未知";
            color = "#888888";
            bgColor = "rgba(136, 136, 136, 0.15)";
            tooltip = "网络质量: 未知";
        } else {
            double lossPercent = localPacketLoss * 100;
            
            if (lossPercent <= 1.0 && localRtt <= 100) {
                displayText = "优秀";
                color = "#00bb00";
                bgColor = "rgba(0, 187, 0, 0.15)";
            } else if (lossPercent <= 3.0 && localRtt <= 200) {
                displayText = "良好";
                color = "#0066ff";
                bgColor = "rgba(0, 102, 255, 0.15)";
            } else if (lossPercent <= 5.0 && localRtt <= 400) {
                displayText = "一般";
                color = "#ff8800";
                bgColor = "rgba(255, 136, 0, 0.15)";
            } else {
                displayText = "差";
                color = "#dd0000";
                bgColor = "rgba(221, 0, 0, 0.15)";
            }
            
            tooltip = String.format("网络质量: %s\n丢包率: %.1f%%\nRTT: %dms", 
                displayText, lossPercent, localRtt);
        }
        
        final String finalText = displayText;
        final String finalColor = color;
        final String finalBgColor = bgColor;
        final String finalTooltip = tooltip;
        
        Platform.runLater(() -> {
            controlNetworkQualityValue.setText(finalText);
            controlNetworkQualityValue.setTextFill(Color.web(finalColor));
            String style = String.format(
                "-fx-font-weight: bold; -fx-padding: 2 8 2 8; " +
                "-fx-background-radius: 3; -fx-background-color: %s;",
                finalBgColor
            );
            controlNetworkQualityValue.setStyle(style);
            controlNetworkQualityValue.setTooltip(createFastTooltip(finalTooltip));
        });
    }


    // ⭐ 辅助方法：检查播放器是否真正在播放
    private boolean isPlayerActuallyPlaying() {
        if (corePlayer == null) {
            return false;
        }

        // 检查管道状态
        boolean pipelineOk = corePlayer.isPlaying();

        // 检查数据流动
        boolean dataFlowing = corePlayer.isDataFlowing();

        LogTools.getInstance().logRecord("🔍 isPlayerActuallyPlaying: pipeline=" + pipelineOk + ", data=" + dataFlowing);

        return pipelineOk && dataFlowing;
    }


    // 在 CameraMainController 中添加成员变量
    private ScheduledExecutorService playbackMonitor;
    private ScheduledFuture<?> playbackMonitorTask;

    // 在 initialize() 方法中启动监控
    private void startPlaybackMonitor() {
        if (playbackMonitor == null) {
            playbackMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PlaybackMonitor");
                t.setDaemon(true);
                return t;
            });
        }

        // 每5秒检查一次播放状态
        playbackMonitorTask = playbackMonitor.scheduleAtFixedRate(() -> {
            try {
                // 只有在推流状态下才检查
                if (publishState == 1 && corePlayer != null) {
                    boolean isActuallyPlaying = isPlayerActuallyPlaying();

                    if (!isActuallyPlaying) {
                        LogTools.getInstance().logRecord("⚠️ 检测到播放器停滞，尝试重启...");

                        Platform.runLater(() -> {
                            try {
                                // 打印详细状态
                                LogTools.getInstance().logRecord("🔍 播放器详细状态:\n" + corePlayer.getDetailedStatus());

                                // 尝试重启
                                corePlayer.play();
                                LogTools.getInstance().logRecord("✅ 播放器已重启");

                                updateStatusLabel("⚠️ 播放器已自动重启");

                            } catch (Exception e) {
                                LogTools.getInstance().logRecord("❌ 播放器重启失败: " + e.getMessage());
                                updateStatusLabel("❌ 播放器重启失败");
                            }
                        });
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 播放状态监控异常: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);

        LogTools.getInstance().logRecord2("✅ 播放状态监控已启动");
    }

    // 在窗口关闭时停止监控
    private void stopPlaybackMonitor() {
        if (playbackMonitorTask != null) {
            playbackMonitorTask.cancel(true);
        }
        if (playbackMonitor != null) {
            playbackMonitor.shutdownNow();
        }
    }

    // ============ 🔥 全屏模式开关 ============
    
    /**
     * 切换全屏模式开关
     */
    @FXML
    private void toggleFullScreenMode() {
        isFullScreenModeEnabled = !isFullScreenModeEnabled;
        
        String status = isFullScreenModeEnabled ? "已开启" : "已关闭";
        LogTools.getInstance().logRecord2("🖥️ 全屏模式: " + status);
        
        // 🔥 更新按钮样式
        updateFullScreenModeButtonStyle();
    }
    
    /**
     * 更新全屏模式按钮样式
     */
    private void updateFullScreenModeButtonStyle() {
        if (fullScreenModeLabel == null) {
            return;
        }
        
        if (isFullScreenModeEnabled) {
            // 开启状态
            fullScreenModeLabel.setText("全屏（开）");
        } else {
            // 关闭状态
            fullScreenModeLabel.setText("全屏（关）");
        }
    }
    
    /**
     * 获取全屏模式状态
     */
    public boolean isFullScreenModeEnabled() {
        return isFullScreenModeEnabled;
    }
    
    /**
     * 设置全屏模式状态
     */
    public void setFullScreenModeEnabled(boolean enabled) {
        this.isFullScreenModeEnabled = enabled;
        updateFullScreenModeButtonStyle();
        LogTools.getInstance().logRecord2("🖥️ 全屏模式已设置为: " + (enabled ? "开启" : "关闭"));
    }

}

