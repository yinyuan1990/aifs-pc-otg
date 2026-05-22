package com.acard.acard.ui;


import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.RecordOnlyPlayer;
import com.acard.acard.capture.*;
import com.acard.acard.FrameRingBuffer;
import com.acard.acard.SimpleWebRTCPlayer;
import com.acard.acard.capture.RealtimeFrameRing;
import com.acard.acard.events.RecordingFileReadyEvent;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.model.CaptureData;
import com.acard.acard.model.ThinRemoteConfig;
import com.acard.acard.player.PlayerWindowHelper;
import com.acard.acard.slowmotion.DiskJpegPlayerUI;
import com.acard.acard.slowmotion.SlowMoGpuPlayer;
import com.acard.acard.slowmotion.SlowMotionRecorder;  // 🎬 新增：慢动作录制器
import com.acard.acard.slowmotion.SlowMotionPlayer;    // 🎬 新增：慢动作播放器

import com.acard.acard.storage.ConfigStore;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.store.CaptureStore;
import com.acard.acard.store.GridStore;
import com.acard.acard.test.GpuJpegPlayerTest;
import com.acard.acard.test.JpegDecodeScaleTest;
import com.acard.acard.tools.*;
import com.acard.acard.utils.AlertUtil;
import com.acard.acard.utils.DirectoryUtils;
import com.acard.acard.utils.GStreamerJpegScaler;
import com.acard.acard.utils.JpegScale2;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.input.ScrollEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

import static com.acard.acard.SimpleWebRTCPlayer.*;

/**
 * 元素2-3独立控制面板：慢放控制与抓拍参数设置。
 * 与元素2-2交互接口预留：可通过 setLinkedController 传入以联动。
 */
 public class Element2_3Controller implements Initializable, Closeable , LatestFrameCallback {

    // 慢放和抓拍按钮
    @FXML private Button btnStartSlow;
    @FXML private Button btnCapture;
    @FXML private Button btnStop;
    @FXML private Button btnClear;

    //@FXML private Button btnSslCapture;

    @FXML private Button btnCaptureClear;
    // JPEG 保存控制按钮
    @FXML private Button btnJpegToggle;  // 新增：JPEG 保存开关按钮
    @FXML private ComboBox<Integer> rowCombo;
    @FXML private ComboBox<Integer> colCombo;

    @FXML private ComboBox<Integer> preCountCombo;
    @FXML private ComboBox<Integer> postCountCombo;
    @FXML private ComboBox<Integer> offsetCombo;  // ⭐ 偏移下拉




    // ⭐ 新增：对 CameraMainController 的引用
    private CameraMainController cameraMainController;

    /**
     * ⭐ 设置 CameraMainController 引用（用于推送 JPEG 帧）
     */
    public void setCameraMainController(CameraMainController controller) {
        this.cameraMainController = controller;
    }

    /**
     * 获取行数下拉列表（供ShortcutManager使用）
     */
    public ComboBox<Integer> getRowCombo() {
        return rowCombo;
    }
    
    /**
     * 获取列数下拉列表（供ShortcutManager使用）
     */
    public ComboBox<Integer> getColCombo() {
        return colCombo;
    }

    private static final boolean ENABLE_CAPTURE_DEBUG = false;
    private SimpleWebRTCPlayerView playerView; // 与元素2-1播放器交互
    private Object linkedController;        // 预留与元素3-2交互
    private java.util.function.BiConsumer<Integer, Integer> onGridChange;
    
    // JPEG 保存控制状态
    private volatile boolean jpegSaveEnabled = true;  // 默认启用 JPEG 保存
    
    // ✅ 录制专用播放器（用于慢放录制）
    private RecordOnlyPlayer recordOnlyPlayer;
    
    // ✅ 录制帧数计数器定时器
    private java.util.concurrent.ScheduledExecutorService recordFrameScheduler;
    private java.util.concurrent.ScheduledFuture<?> recordFrameCounterTask;
    private volatile int simulatedRecordFrames = 0;
    private volatile int currentFps = 30;  // 当前FPS（从ConfigStore读取）
    
    // 停止按钮样式：激活/未激活
    private static final String STOP_ACTIVE_STYLE = "-fx-background-color: #dc2626; -fx-text-fill: #ffffff; -fx-font-weight: 600; -fx-padding: 6 10; -fx-background-radius: 6; -fx-font-size: 10px;";

    private static final String STOP_INACTIVE_STYLE = "-fx-background-color: #6b7280; -fx-text-fill: #ffffff; -fx-font-weight: 600; -fx-padding: 6 10; -fx-background-radius: 6; -fx-font-size: 10px;";
    
    // 🎬 慢动作系统
    private SlowMotionRecorder slowMotionRecorder;
    private SlowMotionPlayer slowMotionPlayer;
    private String currentSlowMotionSession;
    private SlowMoPaneController slowMoPaneController; // Element2_2的控制器
    
    // ✅ 抓拍任务串行执行器（单线程，防止内存爆炸）
    private static final java.util.concurrent.ExecutorService captureExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CaptureTaskQueue");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
    
    // ✅ 抓拍任务计数器（用于防抖）
    private final java.util.concurrent.atomic.AtomicInteger pendingCaptureTasks = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_PENDING_CAPTURES = 3;  // 最多3个待处理任务（快速点击时拒绝）
    
    // ✅ 抓拍总数计数器（用于触发定期GC）
    private final java.util.concurrent.atomic.AtomicInteger captureCounter = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    
    // ✨ 基于事件的后续帧推送模型
    private final java.util.concurrent.CopyOnWriteArrayList<PostFrameEvent> activePostFrameEvents = 
        new java.util.concurrent.CopyOnWriteArrayList<>();


    private final java.util.concurrent.CopyOnWriteArrayList<PostFrameEvent> activePostFrameEvents2 =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong globalFrameCounter = 
        new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean globalFrameListenerRegistered = false;  // 标志：是否已成功注册
    private volatile boolean globalFrameListenerRegistered2 = false;  // 标志：是否已成功注册


    private DiskJpegPlayerUI jpegPlayerUI;

    @Override
    public void close() throws IOException {
        unregisterUIUpdateEvents();
    }


    UIUpdateEvent.FsFilePathData data = new  UIUpdateEvent.FsFilePathData("",0);

    @Override
    public void onNewFrame(String filepath, int frameIndex) {



        if(FileToos.lzNum>=SlowmoStore.getInstance().getSlowmoFrames()){
            onStop();
        }
        try {
            data.setFilepath(filepath);
            data.setFrameIndex(frameIndex);
            UIUpdateEvent event = new UIUpdateEvent(
                        UIUpdateEvent.EventType.SendFilePathEvent,
                        "Element22",
                        data
                );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }

    }

    /**
     * 后续帧推送事件（记录每个抓拍需要哪些帧）
     */
    private static class PostFrameEvent {
        final int targetIndex;           // UI索引
        final long eventFrameId;         // 事件帧ID
        final long startFrameId;         // 开始帧ID（eventFrameId + 1）
        final long endFrameId;           // 结束帧ID（eventFrameId + postCount）
        final int postCount;             // 需要的后续帧数量
        final CameraMainController controller;
        final java.util.concurrent.atomic.AtomicInteger receivedCount = 
            new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger pushedCount = 
            new java.util.concurrent.atomic.AtomicInteger(0);
        final long createTime;
        
        // ✅ 帧缓冲队列（后续帧先缓存，批量推送）
        final List<FrameRingBuffer.FrameItem> frameBuffer =
            java.util.Collections.synchronizedList(new ArrayList<>());
        
        PostFrameEvent(int targetIndex, long eventFrameId, int postCount, CameraMainController controller) {
            this.targetIndex = targetIndex;
            this.eventFrameId = eventFrameId;
            this.startFrameId = eventFrameId + 1;
            this.endFrameId = eventFrameId + postCount;
            this.postCount = postCount;
            this.controller = controller;
            this.createTime = System.currentTimeMillis();
        }
        
        /**
         * 检查指定帧ID是否属于这个事件的后续帧
         */
        boolean needsFrame(long frameId) {
            return frameId >= startFrameId && frameId <= endFrameId;
        }
        
        /**
         * 添加帧到缓冲区
         */
        void addFrame(FrameRingBuffer.FrameItem frame) {
            frameBuffer.add(frame);
            receivedCount.incrementAndGet();
        }
        
        /**
         * 批量推送缓冲区中的所有帧到UI（必须在JavaFX线程调用）
         */
        int flushFrames() {
            if (frameBuffer.isEmpty()) {
                return 0;
            }
            
            int count = 0;
            synchronized (frameBuffer) {
                for (FrameRingBuffer.FrameItem frame : frameBuffer) {
                    List<FrameRingBuffer.FrameItem> singleFrame =
                        java.util.Collections.singletonList(frame);

                    pushedCount.incrementAndGet();
                    count++;
                }
                frameBuffer.clear();
            }
            return count;
        }
        
        /**
         * 是否已收集完所有帧
         */
        boolean isCollectionComplete() {
            return receivedCount.get() >= postCount;
        }
        
        /**
         * 是否已推送完所有帧
         */
        boolean isPushComplete() {
            return pushedCount.get() >= postCount;
        }
    }


    // 1. 在类的字段声明区域添加（大约在第 73 行附近）
    private UIUpdateEventManager eventManager;
    private String listenerId;
    // 2. 在 initialize 方法的末尾添加事件注册初始化
    private void initializeEventListeners() {
        eventManager = UIUpdateEventManager.getInstance();
        this.listenerId = "Speed_" + System.currentTimeMillis();
        registerUIUpdateEvents();
    }
    // 4. 添加事件处理方法
    private void handleUIUpdateEvent(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {
                switch (event.getEventType()) {
                    case SPEED_KEY:
                        updateSpeedName();
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


    private void handleUIUpdatePlayEvent(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {
                switch (event.getEventType()) {
                    case RecordingFileReadyEvent:
                        updateReady((RecordingFileReadyEvent) event.getData());
                        break;

                }
            } catch (Exception e) {
                System.err.println("Element2_3Controller: 处理UI更新事件时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }


    private void handleStopEvent(UIUpdateEvent event) {
        Platform.runLater(() -> {
            try {
                switch (event.getEventType()) {
                    case RecordingStoppedEvent:
                        stopEvent();
                        break;
                }
            } catch (Exception e) {
                System.err.println("Element2_3Controller: 处理UI更新事件时发生错误: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void gandlecavasEvent(UIUpdateEvent event){

        SimpleWebRTCPlayer player = null;
        if (gpuView != null) {
            try {
                player = gpuView.getPlayer();
                System.out.println("  - player (通过GpuView): " + (player != null ? "✅ 已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - 通过GpuView获取播放器失败: " + e.getMessage());
            }
        }

        if (player == null && playerView != null) {
            try {
                player = playerView.getPlayer();
                System.out.println("  - player (通过playerView备用): " + (player != null ? "已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - player获取异常: " + e.getMessage());
            }
        }

        if (player == null) {
            System.err.println("❌ 播放器未初始化，无法获取实时流帧ID");
            return;
        }
        final SimpleWebRTCPlayer finalPlayer = player;
        UIUpdateEvent.CavasData cavasData = (UIUpdateEvent.CavasData) event.getData();
        switch (cavasData.getName()){
            case BRIGHTNESS:   //亮度
                finalPlayer.setBrightness(cavasData.getValue());
                break;
            case CONTRAST:    // 对比度
                finalPlayer.setContrast(cavasData.getValue());
                break;
            case SATURATION:  //饱和度
                finalPlayer.setSaturation(cavasData.getValue());
                break;
            case HUE:         //色调
                finalPlayer.setHue(cavasData.getValue());
                break;
            case GAMMA:       //伽马
                finalPlayer.setGamma(cavasData.getValue());
                break;
            case EXPOSURE: //曝光
                finalPlayer.setExposurePercent(cavasData.getPercent());
                break;
        }
    }

    // 3. 添加事件注册方法
    private void registerUIUpdateEvents() {
        if (eventManager != null) {
            // 注册 FORCE_REFRESH 事件监听器
            eventManager.registerListener(UIUpdateEvent.EventType.SPEED_KEY,
                    this::handleUIUpdateEvent, listenerId + "_speed");
            System.out.println("Element2_3Controller: UI更新事件监听器已注册");

            eventManager.registerListener(UIUpdateEvent.EventType.RecordingFileReadyEvent,
                    this::handleUIUpdatePlayEvent, listenerId + "_play");
            eventManager.registerListener(UIUpdateEvent.EventType.RecordingStoppedEvent,
                    this::handleStopEvent, listenerId + "_stop");
            eventManager.registerListener(UIUpdateEvent.EventType.CavasDataEvent,
                    this::gandlecavasEvent, listenerId + "_cavasdata");

        }
    }


    // 6. 添加事件注销方法（在类的末尾）
    private void unregisterUIUpdateEvents() {
        if (eventManager != null) {
            eventManager.unregisterListener(UIUpdateEvent.EventType.SPEED_KEY, listenerId + "_speed");
            eventManager.unregisterListener(UIUpdateEvent.EventType.RecordingFileReadyEvent, listenerId + "_play");
            eventManager.unregisterListener(UIUpdateEvent.EventType.RecordingStoppedEvent, listenerId + "_stop");
            eventManager.unregisterListener(UIUpdateEvent.EventType.CavasDataEvent, listenerId + "_cavasdata");
        }
    }


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // ✅ 慢放按钮：启动RecordOnlyPlayer录制
        if (btnStartSlow != null) {
            btnStartSlow.setOnAction(e -> onStartSlow());
        }
        // ✅ JPEG 保存控制按钮：切换 JPEG 保存状态
        if (btnJpegToggle != null) {
            btnJpegToggle.setOnAction(e -> onToggleJpegSave());
            updateJpegToggleButtonText();  // 初始化按钮文本
        }
        // ✅ 订阅配置更新消息，当收到 CONFIG_UPDATE 时自动更新FPS
        ConfigStore.getInstance().addThinConfigListener(cfg -> {
            System.out.println("📩 Element2_3收到配置更新消息");
            
            // ✅ 更新FPS（用于录制帧数计数器）
            if (cfg != null && cfg.getFps() != null) {
                int newFps = cfg.getFps();
                if (newFps != currentFps) {
                    currentFps = newFps;
                    System.out.println("📊 FPS已更新: " + currentFps + " (来自WebSocket配置推送)");
                    
                    // 如果正在录制，重新启动计数器以使用新FPS
                    if (recordOnlyPlayer != null && recordOnlyPlayer.isRecording()) {

                    }
                }
            }
            
            // ⭐ 无条件刷新偏移 UI（相机设置弹框改 FPS 后同步更新）
            if (offsetCombo != null) {
                int latestOffset = CaptureStore.getInstance().getOffset();
                Platform.runLater(() -> {
                    if (offsetCombo.getValue() == null || offsetCombo.getValue() != latestOffset) {
                        offsetCombo.setValue(latestOffset);
                    }
                });
            }
        });
        
        // ✨ 注册全局帧监听器（每帧实时触发，事件驱动分发）
        // 注意：此时 gpuView 和 linkedController 可能还没设置，会在 setGpuView() 中延迟注册
        // registerGlobalFrameListener();
        
        // 下拉列表初始化：行/列均为 1..10，并监听选择变化
        if (rowCombo != null) {
            for (int i = 1; i <= 10; i++) rowCombo.getItems().add(i);
            // 从GridStore加载保存的行数，默认为1
            rowCombo.setValue(GridStore.getInstance().getRows());
            rowCombo.valueProperty().addListener((obs, ov, nv) -> {
                // 保存行数到GridStore
                if (nv != null) {
                    GridStore.getInstance().setRows(nv);
                }
                notifyGridChange();
            });
        }
        if (colCombo != null) {
            for (int i = 1; i <= 10; i++) colCombo.getItems().add(i);
            // 从GridStore加载保存的列数，默认为1
            colCombo.setValue(GridStore.getInstance().getCols());
            colCombo.valueProperty().addListener((obs, ov, nv) -> {
                // 保存列数到GridStore
                if (nv != null) {
                    GridStore.getInstance().setCols(nv);
                }
                notifyGridChange();
            });
        }
        // 前后抓拍张数下拉初始化：10, 20, 30, ..., 120, 150, ..., 240
        if (preCountCombo != null) {
            // 10-120: 每10张
            for (int i = 10; i <= 120; i += 10) preCountCombo.getItems().add(i);
            // 150-240: 每30张
            for (int i = 150; i <= 240; i += 30) preCountCombo.getItems().add(i);
            
            try {
                int preDefault = CaptureStore.getInstance().getPreCaptureCount();
                preCountCombo.setValue(preDefault);
            } catch (Throwable ignore) {
                preCountCombo.setValue(10);
            }
            preCountCombo.valueProperty().addListener((obs, ov, nv) -> {
                try {
                    if (nv != null) {
                        CaptureStore.getInstance().setPreCaptureCount(nv);
                        // ✨ 更新播放器缓冲配置
                        updatePlayerCaptureConfig();
                    }
                } catch (Throwable ignore) {}
            });
        }
        if (postCountCombo != null) {
            // 10-120: 每10张
            for (int i = 10; i <= 120; i += 10) postCountCombo.getItems().add(i);
            // 150-240: 每30张
            for (int i = 150; i <= 240; i += 30) postCountCombo.getItems().add(i);
            
            try {
                int postDefault = CaptureStore.getInstance().getPostCaptureCount();
                postCountCombo.setValue(postDefault);
            } catch (Throwable ignore) {
                postCountCombo.setValue(10);
            }
            postCountCombo.valueProperty().addListener((obs, ov, nv) -> {
                try {
                    if (nv != null) {
                        CaptureStore.getInstance().setPostCaptureCount(nv);
                        // ✨ 更新播放器缓冲配置
                        updatePlayerCaptureConfig();
                    }
                } catch (Throwable ignore) {}
            });
        }

        // ⭐ 偏移下拉初始化（0-9）
        if (offsetCombo != null) {
            for (int i = 0; i <= 9; i++) {
                offsetCombo.getItems().add(i);
            }
            try {
                int offsetDefault = CaptureStore.getInstance().getOffset();
                offsetCombo.setValue(offsetDefault);
            } catch (Throwable ignore) {
                offsetCombo.setValue(0);
            }
            offsetCombo.valueProperty().addListener((obs, ov, nv) -> {
                try {
                    if (nv != null) {
                        CaptureStore.getInstance().setOffset(nv);
                        LogTools.getInstance().logRecord("📐 偏移设置: " + nv);
                    }
                } catch (Throwable ignore) {}
            });
            
        }
        
        enableScrollAdjust(rowCombo);
        enableScrollAdjust(colCombo);
        enableScrollAdjust(preCountCombo);
        enableScrollAdjust(postCountCombo);
        enableScrollAdjust(offsetCombo);  // ⭐ 偏移支持滚轮调整
        // 初始：停止按钮未激活，避免用户误认为可点击
        if (btnStop != null) {
            btnStop.setDisable(true);
            btnStop.setStyle(STOP_INACTIVE_STYLE);
        }
        initializeEventListeners();
        updateSpeedName();
    }

    public void updateSpeedName(){
        btnStartSlow.setText("慢放"+"("+FileToos.ShortcutHelper.getSlowMotionKeyName()+ ")");
        btnCapture.setText("慢放抓拍"+"("+FileToos.ShortcutHelper.getCaptureKeyName()+ ")");
        btnStop.setText("慢放停止"+"("+FileToos.ShortcutHelper.getSlowMotionKeyName()+ ")");
        btnClear.setText("慢放清空"+"("+FileToos.ShortcutHelper.getClearKeyName()+ ")");

        btnCaptureClear.setText("抓拍清空"+"("+FileToos.ShortcutHelper.getCaptureClearKeyName()+ ")");
    }

    // 新增：GpuView 引用，用于实时缓存抓拍
    private GpuView gpuView;

    public void setPlayer(SimpleWebRTCPlayerView player) {
        System.out.println("🔧 Element2_3: setPlayer() 被调用");
        this.playerView = player;
        if (this.playerView != null) {
            System.out.println("  - playerView: 已设置");
            
            // 注册慢放期间断流回调：自动停止慢放并提示
            this.playerView.setOnSlowMoStreamStopped(() -> {
                // 确保在FX线程弹窗
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                // ⭐ 设置父窗口，防止全屏时层级错乱
                if (this.playerView.getScene() != null && this.playerView.getScene().getWindow() != null) {
                    Stage mainStage = (Stage) this.playerView.getScene().getWindow();
                    alert.initOwner(mainStage);
                    // ⭐ 全屏保护
                    alert.setOnShowing(e -> mainStage.toFront());
                    alert.setOnHidden(e -> Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); }));
                }
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("慢放过程中流已停止，已自动停止慢放采集。");
                alert.show();
            });
            
            // 获取实际的播放器实例
            SimpleWebRTCPlayer actualPlayer = null;
            try {
                actualPlayer = this.playerView.getPlayer();
                System.out.println("  - actualPlayer: " + (actualPlayer != null ? "已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - actualPlayer获取失败: " + e.getMessage());
            }
            
            // 自动绑定GpuView，确保实时帧持续推送到120帧缓冲
            try {
                if (this.gpuView != null && actualPlayer != null) {
                    this.gpuView.attach(actualPlayer);
                    System.out.println("Element2_3: ✅ 已将GpuView绑定到SimpleWebRTCPlayer，实现实时缓冲");
                }
            } catch (Throwable e) {
                System.err.println("Element2_3: ⚠️ 绑定GpuView失败: " + e.getMessage());
            }
            
            // ✨ 启用轻量级抓拍系统
            try {
                if (actualPlayer != null) {
                    actualPlayer.enableCaptureBuffer();
                    updatePlayerCaptureConfig();
                    System.out.println("✅ 轻量级抓拍系统已启用: " + actualPlayer.getCaptureBufferStatus());
                } else {
                    System.err.println("⚠️ 无法启用抓拍系统：播放器为 null");
                }
            } catch (Throwable e) {
                System.err.println("⚠️ 启用轻量级抓拍失败: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("  - playerView: null（未设置）");
        }
    }
    
    /**
     * ✨ 更新播放器抓拍配置
     */
    private void updatePlayerCaptureConfig() {
        try {

            int preCount = (preCountCombo != null && preCountCombo.getValue() != null) 
                ? preCountCombo.getValue() : 10;
            int postCount = (postCountCombo != null && postCountCombo.getValue() != null) 
                ? postCountCombo.getValue() : 10;
            

            LogTools.getInstance().logRecord("🔧 已更新抓拍配置: pre=" + preCount + ", post=" + postCount);
        } catch (Throwable e) {
            LogTools.getInstance().logRecord("⚠️ 更新抓拍配置失败: " + e.getMessage());
        }
    }

    /** ✨ 注入 GpuView 作为主要抓拍来源 */
    public void setGpuView(GpuView view) {
        System.out.println("🔧 Element2_3: setGpuView() 被调用");
        this.gpuView = view;
        
        if (this.gpuView != null) {
            System.out.println("  - gpuView: 已设置");
            
            // 获取GpuView中的播放器
            SimpleWebRTCPlayer player = null;
            try {
                player = this.gpuView.getPlayer();
                System.out.println("  - player (来自GpuView): " + (player != null ? "已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - 从GpuView获取播放器失败: " + e.getMessage());
            }
            
            // ✨ 启用轻量级抓拍系统
            if (player != null) {
                try {
                    player.enableCaptureBuffer();
                    updatePlayerCaptureConfig();
                    System.out.println("✅ 轻量级抓拍系统已启用（通过GpuView）: " + player.getCaptureBufferStatus());
                    
                    // ✨ 尝试注册全局帧监听器（在这里更可靠）
                    javafx.animation.PauseTransition pause = 
                        new javafx.animation.PauseTransition(javafx.util.Duration.millis(1000));
                    pause.setOnFinished(e -> {
                        System.out.println("🔧 从 setGpuView 触发全局帧监听器注册...");
                        registerGlobalFrameListener();
                    });
                    pause.play();
        } catch (Throwable e) {
                    System.err.println("⚠️ 启用轻量级抓拍失败: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.err.println("⚠️ 无法启用抓拍系统：GpuView中的播放器为 null");
            }
        } else {
            System.err.println("  - gpuView: null（未设置）");
        }
    }

    public void setLinkedController(Object controller) {
        this.linkedController = controller;
        // 初次联动时使用默认倍数1x（速度控制已移到慢放播放器底部）
        try {
            int f = 1; // 默认1倍速
            if (controller instanceof CameraMainController cmc) {
                cmc.updateSlowMoFactor(f);
                // 获取SlowMoPaneController引用
                try {

                } catch (Throwable e) {
                    System.err.println("⚠️ 获取SlowMoPaneController失败: " + e.getMessage());
                }
            }
        } catch (Throwable ignore) {}
    }

    public void setOnGridChange(java.util.function.BiConsumer<Integer, Integer> consumer) {
        this.onGridChange = consumer;
        notifyGridChange();
    }

    private void notifyGridChange() {
        if (onGridChange != null && rowCombo != null && colCombo != null) {
            try {
                Integer rv = rowCombo.getValue();
                Integer cv = colCombo.getValue();
                int r = Math.max(1, Math.min(10, rv != null ? rv : 1));
                int c = Math.max(1, Math.min(10, cv != null ? cv : 1));
                onGridChange.accept(r, c);
            } catch (Throwable ignore) {}
        }
    }

    private void enableScrollAdjust(ComboBox<Integer> combo) {
        if (combo == null) {
            return;
        }
        combo.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (combo.isDisabled() || combo.getItems().isEmpty()) {
                return;
            }
            int index = combo.getSelectionModel().getSelectedIndex();
            if (index < 0) {
                index = 0;
            }
            if (event.getDeltaY() > 0) {
                index++;
            } else if (event.getDeltaY() < 0) {
                index--;
            }
            index = Math.max(0, Math.min(combo.getItems().size() - 1, index));
            combo.getSelectionModel().select(index);
            event.consume();
        });
    }

    // 下拉列表无需手动提交，选择变化即联动

    // ✅ 全局日志记录器（记录整个慢放过程）
    private PrintWriter slowMoLogger;
    
    /**
     * 记录日志到文件和控制台
     */
    private void logSlowMo(String message) {
        LogTools.getInstance().logRecord2(message);
    }


    public void setJpegSaveEnabled(boolean isOpen){
        SimpleWebRTCPlayer player = null;

        // ✨ 优先方案：通过 GpuView 获取（主要使用方式）
        if (gpuView != null) {
            try {
                player = gpuView.getPlayer();
                System.out.println("  - player (通过GpuView): " + (player != null ? "✅ 已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - 通过GpuView获取播放器失败: " + e.getMessage());
            }
        }
        if (player == null && playerView != null) {
            try {
                player = playerView.getPlayer();
                System.out.println("  - player (通过playerView备用): " + (player != null ? "已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - player获取异常: " + e.getMessage());
            }
        }
        if( player!=null){
            player.setJpegSaveEnabled(isOpen);
        }
        // ⭐ 暂时注释：保留 ssl 图片用于 AI 训练
         DirectoryUtils.clearDirectory("runtime/captures/ssl");

    }

     @FXML
    public void onStartSlow() {



        if(FileToos.isIsCallBackFrame) {


            if(btnStop.isDisabled()) {

                if (CaptureDataManager.slowPly != null && CaptureDataManager.slowPly.length() > 0) {
                    CaptureDataManager.getInstance().remove(CaptureDataManager.slowPly);
                }
                boolean isresult = DirectoryUtils.clearDirectory("runtime/captures/scaleslow");
                if (isresult) {

                    if (gpuView.getPlayer().getLatestFrameCallback() == null) {
                        gpuView.getPlayer().setLatestFrameCallback(this);
                    }
                    FileToos.FbRecordingStartedEvent();
                    FileToos.slowAllClear=1;
                    //发送开始命令
                    gpuView.getPlayer().startRecording();
                    activateStopButton(true);
                    // ✅ 通知 CameraMainController 更新底部按钮状态
                    notifySlowmoStateChanged(true);
                }
            }else{
                onStop();
            }



        }else{
            AlertUtil.error("无画面");
        }
    }

    @FXML
    public void onStop() {

        if(FileToos.isIsCallBackFrame) {
            logSlowMo("\n⏹️ 停止慢放录制（保持播放器运行，可拖动进度条回放）");
            gpuView.getPlayer().stopRecording();
            // 取消激活停止按钮
            activateStopButton(false);
            // ✅ 通知 CameraMainController 更新底部按钮状态
            notifySlowmoStateChanged(false);

            FileToos.FbJiesuanCountEvent();
        }else {
            AlertUtil.error("无画面");
        }
    }


    private void updateReady(RecordingFileReadyEvent event) {



    }







    public void stopEvent(){
        if (slowMoPaneController != null) {
            try {
                // ⚠️ 关键修复：停止录制后，启用滑块（允许拖动回放）
                slowMoPaneController.setRecording(false);
                logSlowMo("✅ 滑块已启用（可拖动回放）");

                SlowMoGpuPlayer mp4Player = slowMoPaneController.getMp4Player();
                if (mp4Player != null) {
                    logSlowMo("⏸️ 暂停MKV播放（保留画面，可拖动进度条回放）...");
                    mp4Player.pause();
                    logSlowMo("✅ MKV播放已暂停，画面保持显示");
                    logSlowMo("💡 再次点击'开启慢放'将清空画面并开始新一轮录制");
                }
            } catch (Throwable e) {
                logSlowMo("⚠️ 暂停MKV播放失败: " + e.getMessage());
                if (slowMoLogger != null) {
                    e.printStackTrace(slowMoLogger);
                }
            }
        }

        // 不关闭日志文件，保留用于回放操作的日志
        if (slowMoLogger != null) {
            logSlowMo("💡 停止录制完成，日志文件保持打开（用于记录回放操作）\n");
        }

        // 取消激活停止按钮
        activateStopButton(false);

        // ⚠️ 关键修复：恢复"开启慢放"按钮状态，允许第二次启动
        Platform.runLater(() -> {
            if (btnStartSlow != null) {
                btnStartSlow.setText("慢放"+"("+FileToos.ShortcutHelper.getSlowMotionKeyName()+ ")");
                btnStartSlow.setDisable(false);
                logSlowMo("✅ '开启慢放'按钮已恢复，可以开始新一轮录制");
            }
            // ✅ 通知 CameraMainController 更新底部按钮状态
            notifySlowmoStateChanged(false);
        });

        logSlowMo("✅ 停止完成：录制已停止，播放器已暂停，可拖动进度条回放");
    }
    
    /**
     * ✅ 通知主控制器慢放状态变化
     */
    private void notifySlowmoStateChanged(boolean isRecording) {
        if (linkedController instanceof CameraMainController) {
            CameraMainController mainController = (CameraMainController) linkedController;
            mainController.onSlowmoStateChanged(isRecording);
        }
    }
    
    /**
     * 启动慢动作播放
     */
    private void startSlowMotionPlayback() {
        System.out.println("🎬🎬🎬 startSlowMotionPlayback() 被调用（旧版慢放系统）");
        
        if (slowMotionRecorder == null) {
            System.err.println("⚠️ 录制器未初始化，无法开始播放");
            return;
        }
        
        if (slowMoPaneController == null) {
            System.err.println("⚠️ SlowMoPaneController未初始化，无法显示慢动作画面");
            return;
        }
        
        // 获取慢放倍数
        // ⚠️ 速度控制已移到慢放播放器底部的下拉框，这里使用默认1倍速
        double factor = 1.0;
        
        // 创建播放器
        slowMotionPlayer = new SlowMotionPlayer(
            currentSlowMotionSession,
            slowMotionRecorder.getFrames()
        );
        
        // 设置基准帧率
        slowMotionPlayer.setBaseFps(slowMotionRecorder.getDetectedFps());
        
        // 设置慢放倍数
        slowMotionPlayer.setSlowMotionFactor(factor);
        
        // ✅ 绑定播放器到SlowMoPaneController（支持进度跳转）
        slowMoPaneController.setSlowMotionPlayer(slowMotionPlayer);
        
        System.out.println("✅✅✅ 旧版慢放播放器已设置，isSlowMotionActive应该返回true");
        
        // ✅ 设置帧显示回调到Element2_2
        slowMotionPlayer.setFrameCallback(image -> {
            Platform.runLater(() -> {
                try {
                    // 获取SlowMoView并显示帧
                    slowMoPaneController.displayFrame(image);
                } catch (Throwable e) {
                    System.err.println("⚠️ 显示慢动作帧失败: " + e.getMessage());
                }
            });
        });
        
        // ✅ 设置进度回调
        slowMotionPlayer.setProgressCallback(progress -> {
            Platform.runLater(() -> {
                try {
                    int currentFrame = slowMotionPlayer.getCurrentFrameIndex();
                    int totalFrames = slowMotionPlayer.getTotalFrameCount();
                    slowMoPaneController.updateProgress(currentFrame, totalFrames);
                } catch (Throwable e) {
                    System.err.println("⚠️ 更新进度失败: " + e.getMessage());
                }
            });
        });
        
        // 开始播放
        slowMotionPlayer.play();
        
        System.out.println("▶️ 慢动作播放已启动 (" + slowMotionRecorder.getFrameCount() + " 帧, " + 
            slowMotionRecorder.getDetectedFps() + " fps, " + 
            String.format("%.1fx", factor) + " 慢放)");
    }

    @FXML
    public void onClear() {
        System.out.println("🗑️ 开始清理...");

        FileToos.isCallBack=false;
        FileToos.lzNum=0;
        FileToos.slowIndex=0;
        FileToos.slowAllClear=0;
        if(CaptureDataManager.slowPly!=null&&CaptureDataManager.slowPly.length()>0) {
            CaptureDataManager.getInstance().remove(CaptureDataManager.slowPly);
        }

        // 4. 取消激活停止按钮
        activateStopButton(false);
        // ✅ 通知 CameraMainController 更新底部按钮状态
        notifySlowmoStateChanged(false);
        
        FileToos.FbJiesuanCountEvent();
        //FileToos. FbSlowCleaEvent();
        delayExecute(0.2, () -> {
            FileToos.FbSlowCleaEvent();

        });

    }


    /**
     * ⭐ 延迟执行方法（用于UI操作）
     * @param delaySeconds 延迟时间（秒）
     * @param action 延迟后执行的操作
     */
    private void delayExecute(double delaySeconds, Runnable action) {
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                javafx.util.Duration.seconds(delaySeconds)
        );
        delay.setOnFinished(event -> action.run());
        delay.play();
    }

    /**
     * 仅清空目录内容，保留根目录：
     * - 遍历根目录下所有文件与子目录并删除
     * - 但不删除根目录本身
     */
    private void clearDirectoryContents(java.nio.file.Path root) {
        try {
            if (root == null || !java.nio.file.Files.exists(root)) return;
            // 先删除子项（包括子目录内容），保留根目录
            java.nio.file.Files.walk(root)
                    .sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); } catch (Throwable ignore) {}
                    });
        } catch (Throwable ignore) {}
    }




    private void handleNewFrame(DiskCaptureCache.DiskFrameItem newFrame, String sessionId) {

        CameraMainController cmc = (linkedController instanceof CameraMainController)
                ? (CameraMainController) linkedController : null;
        System.out.println("  - cmc: " + (cmc != null ? "已链接" : "null"));
        if(cmc!=null) {

            Platform.runLater(() -> {
                try {
                    if (cmc != null) {
                        cmc.appendFrameBySessionId(sessionId, newFrame);
                        if(newFrame.isEnd){
                            cmc.unregisterCaptureSession(sessionId);
                        }
                    }
                    System.out.println("✅ 新帧已添加: " + newFrame.filePath);
                } catch (Exception e) {
                    System.err.println("❌ 处理新帧失败: " + e.getMessage());
                }
            });
        }
    }

    private void slowAction() throws IOException {
        long startTime = System.currentTimeMillis();
        
        CaptureStore store = CaptureStore.getInstance();
        int storePre = store.getPreCaptureCount();
        int storePost = store.getPostCaptureCount();
        
        CameraMainController cmc = (linkedController instanceof CameraMainController)
                ? (CameraMainController) linkedController : null;
        
        if (cmc == null) {
            System.err.println("❌ CameraMainController 未链接，取消抓拍");
            return;
        }
        
        // ⚡ 1. 直接获取当前帧索引（无延迟，实时获取）
        long currentFrameId = FileToos.slowIndex;
        logSlowMo("📐 当前帧索引: " + currentFrameId);

        long startFrameId = Math.max(1, currentFrameId-storePre);
        long endFrameId = currentFrameId + storePost;
        
        logSlowMo("当前currentFrameId: " + currentFrameId + ", 范围: [" + startFrameId + "-" + endFrameId + "]");
        
        // ⚡ 2. 批量构建帧列表（提前分配容量）
        String slowDir = "runtime/captures/ssl";
        int totalFrames = (int)(endFrameId - startFrameId + 1);
        List<DiskCaptureCache.DiskFrameItem> framesToMove = new ArrayList<>(totalFrames);
        
        int width = FileToos.slowWidth;
        int height = FileToos.slowHight;
        
        // ⚡ 优化：使用 StringBuilder 减少字符串拼接
        StringBuilder pathBuilder = new StringBuilder(slowDir.length() + 20);
        for (long frameId = startFrameId; frameId <= endFrameId; frameId++) {
            pathBuilder.setLength(0);
            pathBuilder.append(slowDir)
                      .append("/s_")
                      .append(String.format("%09d", frameId))
                      .append(".jpeg");
            
            DiskCaptureCache.DiskFrameItem frameItem = new DiskCaptureCache.DiskFrameItem(
                pathBuilder.toString(),
                frameId,
                width,
                height,
                "jpeg",
                frameId
            );
            framesToMove.add(frameItem);
        }
        
        if (framesToMove.isEmpty()) {
            System.err.println("❌ 没有找到可用的帧数据");
            return;
        }
        
        // ⚡ 3. 创建会话并注册到 CaptureDataManager
        CaptureSession session = new CaptureSession("慢放");
        String eventId = session.getSessionId() + "";
        
        // 设置所有帧的 eventId
        for (DiskCaptureCache.DiskFrameItem frame : framesToMove) {
            frame.setEventId(eventId);
        }
        
        CaptureData captureData = new CaptureData();
        captureData.setStartIndex((int) startFrameId);
        captureData.setEndIndex((int) endFrameId);
        CaptureDataManager.getInstance().put(eventId, captureData);
        
        // ⚡ 4. 一次性创建UI并填充数据（在FX线程，避免创建空UI再更新）
        final int eventIndex = Math.max(1, storePre);
        Platform.runLater(() -> {
            try {
                // ⚡ 直接创建带数据的UI（跳过创建空UI的步骤）
                cmc.addDiskCaptureV2(framesToMove, eventIndex);
                cmc.lockLastCaptureViewport();
                
                // ⭐ 检查抓拍数量是否等于行×列，如果是则自动触发全屏
                int rows = GridStore.getInstance().getRows();
                int cols = GridStore.getInstance().getCols();
                int expectedCount = rows * cols;
                int currentCount = cmc.getCaptureItemCount();

                LogTools.getInstance().logRecord3("currentCount-> "+currentCount+" rowsXcols-> "+expectedCount);
                if (currentCount == expectedCount-1) {
                    UIUpdateEventManager.getInstance().fireEvent(
                        new UIUpdateEvent(UIUpdateEvent.EventType.AUTO_FULLSCREEN, "Element2_3Controller-slowAction")
                    );
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("⚡ 慢放抓拍完成: " + totalFrames + " 帧, 耗时: " + elapsed + "ms");
            } catch (Exception e) {
                System.err.println("❌ 创建抓拍UI失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    
    private void sslAction() throws IOException {
        long startTime = System.currentTimeMillis();

        CaptureStore store = CaptureStore.getInstance();
        int storePre = store.getPreCaptureCount();
        int storePost = store.getPostCaptureCount();

        CameraMainController cmc = (linkedController instanceof CameraMainController)
                ? (CameraMainController) linkedController : null;
        
        if (cmc == null) {
            System.err.println("❌ CameraMainController 未链接，取消抓拍");
            return;
        }

        // ⚡ 1. 延迟48ms让JPEG链路追上显示

        
        long currentFrameId = FileToos.jpegIndex;
        logSlowMo("📐 当前帧索引: " + currentFrameId);
        
        if(currentFrameId <= 0){
            logSlowMo("⚠️ 帧索引无效，取消抓拍");
            return;
        }



        long startFrameId = Math.max(1, currentFrameId-storePre);
        long endFrameId = currentFrameId + storePost;

        logSlowMo("当前currentFrameId: " + currentFrameId + ", 范围: [" + startFrameId + "-" + endFrameId + "]");

        // ⚡ 2. 批量构建帧列表（在非UI线程，提前分配容量）
        String slowDir = "runtime/captures/ssl";
        int totalFrames = (int)(endFrameId - startFrameId + 1);
        List<DiskCaptureCache.DiskFrameItem> framesToMove = new ArrayList<>(totalFrames);
        
                int width = FileToos.sslWidth;
                int height = FileToos.sslwHight;
        
        // ⚡ 优化：减少字符串拼接，使用 StringBuilder
        StringBuilder pathBuilder = new StringBuilder(slowDir.length() + 20);
        for (long frameId = startFrameId; frameId <= endFrameId; frameId++) {
            pathBuilder.setLength(0);  // 重用 StringBuilder
            pathBuilder.append(slowDir)
                      .append("/s_")
                      .append(String.format("%09d", frameId))
                      .append(".jpeg");
            
                DiskCaptureCache.DiskFrameItem frameItem = new DiskCaptureCache.DiskFrameItem(
                pathBuilder.toString(),
                frameId,
                width,
                height,
                        "jpeg",
                frameId
            );
                framesToMove.add(frameItem);
        }

        if (framesToMove.isEmpty()) {
            System.err.println("❌ 没有找到可用的帧数据");
            return;
        }

        // ⚡ 3. 创建会话并注册到 CaptureDataManager
        CaptureSession session = new CaptureSession("时时流");
        String eventId = session.getSessionId() + "";
        
        // 设置所有帧的 eventId
        for (DiskCaptureCache.DiskFrameItem frame : framesToMove) {
            frame.setEventId(eventId);
        }
        
        CaptureData captureData = new CaptureData();
        captureData.setStartIndex((int) startFrameId);
        captureData.setEndIndex((int) endFrameId);
        CaptureDataManager.getInstance().put(eventId, captureData);

        // ⚡ 4. 一次性创建UI并填充数据（在FX线程，避免创建空UI再更新）
        final int eventIndex = Math.max(1, storePre);
        Platform.runLater(() -> {
            try {
                // ⚡ 直接创建带数据的UI（跳过创建空UI的步骤）
                cmc.addDiskCaptureV2(framesToMove, eventIndex);
                    cmc.lockLastCaptureViewport();
                    
                    // ⭐ 检查抓拍数量是否等于行×列，如果是则自动触发全屏
                    int rows = GridStore.getInstance().getRows();
                    int cols = GridStore.getInstance().getCols();
                    int expectedCount = rows * cols;
                    int currentCount = cmc.getCaptureItemCount();
                    LogTools.getInstance().logRecord3("currentCount-> "+currentCount+" rowsXcols-> "+expectedCount);
                    if (currentCount == expectedCount-1) {
                        UIUpdateEventManager.getInstance().fireEvent(
                            new UIUpdateEvent(UIUpdateEvent.EventType.AUTO_FULLSCREEN, "Element2_3Controller-sslAction")
                        );
            }

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("⚡ 时时流抓拍完成: " + totalFrames + " 帧, 耗时: " + elapsed + "ms");

            } catch (Exception e) {
                System.err.println("❌ 创建抓拍UI失败: " + e.getMessage());
                e.printStackTrace();
        }
        });
    }

    /*@FXML
    public void onSslCapture() throws IOException{
        if(FileToos.isIsCallBackFrame) {
            if(slowMoLogger==null) {

                try {
                    String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                    String logPath = "runtime/endlog/onCapture_" + timestamp + ".txt";
                    File logFile = new File(logPath);
                    File parentDir = logFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    slowMoLogger = new PrintWriter(new FileWriter(logFile, true));

                } catch (Exception e) {
                    System.err.println("日志初始化失败: " + e.getMessage());
                }
            }



            sslAction();

        }else{
            AlertUtil.error("无画面");
        }
    }*/


    @FXML
    public void onCaptureClear() throws IOException {
        // ✅ Bug修复：如果抓拍全屏查看器正在显示，先关闭它（确保确认框在最上层）
        if (CaptureItemView.isFullscreenViewerShowing()) {
            CaptureItemView.closeFullscreenViewer();
        }
        
        // ⭐ 获取主窗口引用
        Stage mainStage = null;
        Scene mainScene = null;
        if (btnCaptureClear != null && btnCaptureClear.getScene() != null) {
            mainScene = btnCaptureClear.getScene();
        } else if (btnClear != null && btnClear.getScene() != null) {
            mainScene = btnClear.getScene();
        }
        if (mainScene != null && mainScene.getWindow() != null) {
            mainStage = (Stage) mainScene.getWindow();
        }
        
        // ⭐ 记录主窗口引用（用于弹框关闭后恢复焦点）
        final Stage finalMainStage = mainStage;
        
        // ⭐ 弹出确认对话框
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        
        // ✅ 必须先设置 initOwner，再设置其他属性
        if (mainStage != null) {
            confirmAlert.initOwner(mainStage);
        }
        
        // ✅ 去掉系统标题栏
        confirmAlert.initStyle(javafx.stage.StageStyle.TRANSPARENT);
        
        // ✅ 应用级模态
        confirmAlert.initModality(Modality.APPLICATION_MODAL);
        
        // ⭐ 关键：弹框显示时确保主窗口在前，弹框关闭后恢复全屏状态
        confirmAlert.setOnShowing(e -> {
            if (finalMainStage != null) {
                finalMainStage.toFront();
            }
        });
        // ✅ 隐藏默认内容
        confirmAlert.setTitle(null);
        confirmAlert.setHeaderText(null);
        confirmAlert.setGraphic(null);
        confirmAlert.setContentText(null);

        // ⭐ 自定义按钮
        ButtonType btnConfirm = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType btnCancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        confirmAlert.getButtonTypes().setAll(btnConfirm, btnCancel);
        
        // ✅ DialogPane 背景透明
        javafx.scene.control.DialogPane dialogPane = confirmAlert.getDialogPane();
        dialogPane.setStyle("-fx-background-color: transparent;");
        
        // ⭐ 创建深色圆角容器
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox(20);
        container.setAlignment(javafx.geometry.Pos.CENTER);
        container.setStyle(
            "-fx-background-color: #1F1F1F; " +
            "-fx-background-radius: 16; " +
            "-fx-padding: 30 40; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.3, 0, 4);"
        );
        container.setMinWidth(360);
        
        // 提示文字
        javafx.scene.control.Label messageLabel = new javafx.scene.control.Label("确定要清空所有抓拍项吗？\n此操作不可恢复！");
        messageLabel.setStyle(
            "-fx-font-size: 14px; " +
            "-fx-text-fill: #CCCCCC; " +
            "-fx-text-alignment: center;"
        );
        messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        messageLabel.setAlignment(javafx.geometry.Pos.CENTER);
        
        // 按钮区域
        javafx.scene.layout.HBox buttonBox = new javafx.scene.layout.HBox(20);
        buttonBox.setAlignment(javafx.geometry.Pos.CENTER);
        buttonBox.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));
        
        javafx.scene.control.Button confirmBtn = new javafx.scene.control.Button("确定");
        confirmBtn.setStyle(
            "-fx-background-color: #DC2626; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        );
        confirmBtn.setOnMouseEntered(e -> confirmBtn.setStyle(
            "-fx-background-color: #B91C1C; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        ));
        confirmBtn.setOnMouseExited(e -> confirmBtn.setStyle(
            "-fx-background-color: #DC2626; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        ));
        confirmBtn.setOnAction(e -> {
            confirmAlert.setResult(btnConfirm);
            confirmAlert.close();
        });
        
        javafx.scene.control.Button cancelBtn = new javafx.scene.control.Button("取消");
        cancelBtn.setStyle(
            "-fx-background-color: #3a3a3a; " +
            "-fx-text-fill: #CCCCCC; " +
            "-fx-font-size: 14px; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        );
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(
            "-fx-background-color: #4a4a4a; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 14px; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        ));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(
            "-fx-background-color: #3a3a3a; " +
            "-fx-text-fill: #CCCCCC; " +
            "-fx-font-size: 14px; " +
            "-fx-background-radius: 8; " +
            "-fx-padding: 10 50; " +
            "-fx-cursor: hand;"
        ));
        cancelBtn.setOnAction(e -> {
            confirmAlert.setResult(btnCancel);
            confirmAlert.close();
        });
        
        buttonBox.getChildren().addAll(confirmBtn, cancelBtn);
        container.getChildren().addAll(messageLabel, buttonBox);
        
        dialogPane.setContent(container);
        
        // 隐藏默认按钮
        dialogPane.getButtonTypes().forEach(bt -> {
            javafx.scene.Node btn = dialogPane.lookupButton(bt);
            if (btn != null) {
                btn.setVisible(false);
                btn.setManaged(false);
            }
        });
        
        // 设置场景透明
        confirmAlert.setOnShowing(e -> {
            Platform.runLater(() -> {
                try {
                    if (confirmAlert.getDialogPane().getScene() != null) {
                        confirmAlert.getDialogPane().getScene().setFill(javafx.scene.paint.Color.TRANSPARENT);
                    }
                } catch (Exception ex) {
                    System.err.println("⚠️ 设置样式失败: " + ex.getMessage());
                }
            });
        });
        
        // ✅ Bug修复：关闭后强制恢复主窗口焦点并置于最前（防止其他软件界面显示到主页）
        final Scene finalMainScene = mainScene;
        confirmAlert.setOnHidden(e -> {
            Platform.runLater(() -> {
                try {
                    if (finalMainStage != null) {
                        // ✅ 强制置于最前并请求焦点
                        finalMainStage.setAlwaysOnTop(true);
                        finalMainStage.toFront();
                        finalMainStage.requestFocus();
                        if (finalMainScene != null && finalMainScene.getRoot() != null) {
                        finalMainScene.getRoot().requestFocus();
                        }
                        // ✅ 延迟恢复正常层级（避免一直置顶）
                        Platform.runLater(() -> {
                            try {
                                Thread.sleep(100);
                                finalMainStage.setAlwaysOnTop(false);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        });
                    }
                } catch (Exception ex) {
                    System.err.println("⚠️ 恢复焦点失败: " + ex.getMessage());
                }
            });
        });

        // ⭐ 等待用户响应
        confirmAlert.showAndWait().ifPresent(response -> {
            if (response == btnConfirm) {
                // 用户点击了"确定"，执行清空操作


                FileToos.FbCleanAllEvent();
                if (linkedController instanceof CameraMainController cmc) {
                    try {
                        System.out.println("   🗑️ 用户确认，开始清空抓拍项...");
                        Platform.runLater(() -> cmc.clearSnapshotUI());
                    } catch (Throwable e) {
                        System.err.println("   ⚠️ 清理抓拍项失败: " + e.getMessage());
                    }
                }
                
                // ⭐ 清空完成后，自动触发全屏切换（恢复到原来的效果）
                System.out.println("🎯 清空抓拍完成，自动触发全屏切换恢复");
                LogTools.getInstance().logRecord2("🎯 清空抓拍完成，自动触发全屏切换恢复");
                
                // 延迟50ms触发，确保UI清空操作完成
                Platform.runLater(() -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    UIUpdateEventManager.getInstance().fireEvent(
                        new UIUpdateEvent(UIUpdateEvent.EventType.AUTO_FULLSCREEN, "Element2_3Controller-onCaptureClear")
                    );
                });
            } else {
                // 用户点击了"取消"
                System.out.println("   ❌ 用户取消了清空操作");
            }
        });
    }

    @FXML
    public void onCapture() throws IOException {

        // ⚡ 直接弹出抓拍对比弹框
       // openCaptureCompareDialog();

        if(FileToos.isIsCallBackFrame) {
            if(slowMoLogger==null) {

                try {
                    String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
                    String logPath = "runtime/endlog/onCapture_" + timestamp + ".txt";
                    File logFile = new File(logPath);
                    File parentDir = logFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    slowMoLogger = new PrintWriter(new FileWriter(logFile, true));

                } catch (Exception e) {
                    System.err.println("日志初始化失败: " + e.getMessage());
                }
            }

            if(FileToos.isCallBack==false&&FileToos.slowAllClear==0){
                  // ⚡ 测试：弹出内存环当前帧，对比JPEG延迟
                  //showRealtimeFrameComparison();
                  sslAction();
            }else{
                if(FileToos.slowIndex!=0){
                    slowAction();
                }
            }

        }else{
            //showRealtimeFrameComparison();
            AlertUtil.error("无画面");
        }


    }

    /**
     * ⚡ 打开抓拍对比弹框（实时流截图 vs JPEG）
     */
    public void openCaptureCompareDialog() {
        Platform.runLater(() -> {
            try {
                Stage owner = null;
                if (btnCapture != null && btnCapture.getScene() != null && btnCapture.getScene().getWindow() != null) {
                    owner = (Stage) btnCapture.getScene().getWindow();
                }
                RealtimeCaptureTestDialog.show(owner, gpuView);
                System.out.println("⚡ 已打开抓拍对比弹框");
            } catch (Exception e) {
                System.err.println("❌ 打开弹框失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * ⚡ 打开内存环抓拍测试弹框
     * 用于测试从 frameCallback 直接获取帧（不经过JPEG磁盘）
     */
    public void openRealtimeCaptureTest() {
        Platform.runLater(() -> {
            try {
                Stage owner = null;
                if (btnCapture != null && btnCapture.getScene() != null && btnCapture.getScene().getWindow() != null) {
                    owner = (Stage) btnCapture.getScene().getWindow();
                }
                RealtimeCaptureTestDialog.show(owner, gpuView);
                System.out.println("⚡ 已打开内存环抓拍测试弹框");
            } catch (Exception e) {
                System.err.println("❌ 打开测试弹框失败: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    /**
     * ⚡ 执行内存环抓拍（测试用）
     * 从 RealtimeFrameRing 获取帧，不经过JPEG磁盘
     */
    public void testRealtimeCapture(int preCount) {
        System.out.println("⚡ 开始内存环抓拍测试: 前帧数=" + preCount);
        
        RealtimeFrameRing ring = RealtimeFrameRing.getInstance();
        List<RealtimeFrameRing.FrameData> frames = ring.getCaptureWindow(preCount, 0);
        
        if (frames.isEmpty()) {
            System.err.println("❌ 内存环为空，无法抓拍");
            Platform.runLater(() -> AlertUtil.error("内存环为空，请确保视频流正常"));
            return;
        }
        
        System.out.println("✅ 获取到 " + frames.size() + " 帧");
        
        // 输出每帧信息
        for (int i = 0; i < frames.size(); i++) {
            RealtimeFrameRing.FrameData frame = frames.get(i);
            System.out.println("   帧" + (i+1) + ": " + frame.width + "x" + frame.height + 
                ", " + (frame.getByteSize()/1024) + "KB, idx=" + frame.frameIndex);
        }
        
        // 最后一帧是当前帧
        RealtimeFrameRing.FrameData currentFrame = frames.get(frames.size() - 1);
        System.out.println("📍 当前帧: " + currentFrame.width + "x" + currentFrame.height + 
            " (" + RealtimeFrameRing.getResolutionTier(currentFrame.width, currentFrame.height) + ")");
    }
    







    /**
     * 🔍 内存诊断日志
     */
    private void logMemoryDiagnostics(String stage) {
        Runtime rt = Runtime.getRuntime();
        long totalMB = rt.totalMemory() / 1024 / 1024;
        long freeMB = rt.freeMemory() / 1024 / 1024;
        long usedMB = totalMB - freeMB;
        long maxMB = rt.maxMemory() / 1024 / 1024;
        
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println("🔍 [" + stage + "] 内存诊断:");
        System.out.println("   已用: " + usedMB + "MB / 总计: " + totalMB + "MB / 最大: " + maxMB + "MB");
        System.out.println("   使用率: " + String.format("%.1f%%", (usedMB * 100.0 / maxMB)));
        System.out.println("   待处理抓拍任务: " + pendingCaptureTasks.get());
        
        // 如果内存超过阈值，强制GC
        if (usedMB > maxMB * 0.8) {
            System.out.println("   ⚠️ 内存使用超过80%，触发GC...");
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            long afterGC_usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            System.out.println("   ✅ GC后内存: " + afterGC_usedMB + "MB（回收了" + (usedMB - afterGC_usedMB) + "MB）");
        }
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * 🎬 执行慢放抓拍（事件驱动模式 + 独立文件夹）
     */
    private void executeSlowMotionCapture() {
        // ✅ 任务限制检查（防止快速点击导致内存爆炸）
        int pending = pendingCaptureTasks.get();
        if (pending >= MAX_PENDING_CAPTURES) {
            System.out.println("⚠️ 抓拍任务队列已满(" + pending + "/" + MAX_PENDING_CAPTURES + ")，拒绝新任务");
            return;
        }
        
        // ✅ 增加待处理计数
        pendingCaptureTasks.incrementAndGet();
        System.out.println("📊 抓拍任务入队，当前待处理: " + pendingCaptureTasks.get());
        
        // ✅ 提交到串行执行器（单线程，防止并发）
        captureExecutor.submit(() -> {
            try {
                executeSlowMotionCaptureImpl();
            } finally {
                // ✅ 任务完成，减少计数
                int remaining = pendingCaptureTasks.decrementAndGet();
                System.out.println("✅ 抓拍任务完成，剩余待处理: " + remaining);
            }
        });
    }
    
    private void executeSlowMotionCaptureImpl() {
        // ✅ 每3次抓拍后强制GC（防止Image对象积压）
        int currentCount = captureCounter.incrementAndGet();
        if (currentCount % 3 == 0) {
            System.out.println("🗑️ 第" + currentCount + "次抓拍，触发GC...");
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignore) {}
        }
        
        // ✅ 创建抓拍性能日志
        com.acard.acard.utils.PerformanceLogger captureLogger = null;
        try {
            captureLogger = new com.acard.acard.utils.PerformanceLogger("capture");
            captureLogger.checkpoint("慢放抓拍开始");
        } catch (Exception e) {
            System.err.println("⚠️ 创建抓拍日志失败: " + e.getMessage());
        }
        final com.acard.acard.utils.PerformanceLogger finalLogger = captureLogger;
        
        System.out.println("🎬 开始慢放抓拍");
        
        // ⚠️ 关键判断：检查是否使用MKV播放器
        SlowMoGpuPlayer mkvPlayer = slowMoPaneController != null ?
            slowMoPaneController.getMkvPlayerRef() : null;
        
        if (mkvPlayer != null) {
            System.out.println("   ✅ 检测到MKV播放器，使用MKV抓拍模式");
            executeMkvCapture(mkvPlayer, finalLogger);
            return;
        }
        
        System.out.println("   ℹ️ 使用旧版磁盘缓存抓拍模式");
        
        try {
            // ✅ 获取播放器实例（用于getCurrentFrameId）
            SimpleWebRTCPlayer player = null;
            if (gpuView != null) {
                try {
                    player = gpuView.getPlayer();
                    System.out.println("  - player (通过GpuView): " + (player != null ? "✅ 已获取" : "null"));
                } catch (Throwable e) {
                    System.err.println("  - 通过GpuView获取播放器失败: " + e.getMessage());
                }
            }
            
            if (player == null && playerView != null) {
                try {
                    player = playerView.getPlayer();
                    System.out.println("  - player (通过playerView备用): " + (player != null ? "已获取" : "null"));
                } catch (Throwable e) {
                    System.err.println("  - player获取异常: " + e.getMessage());
                }
            }
            
            if (player == null) {
                System.err.println("❌ 播放器未初始化，无法获取实时流帧ID");
                return;
            }
            
            final SimpleWebRTCPlayer finalPlayer = player;
            
            // 获取前后抓拍数
            int pre;
            int post;
            try {
                CaptureStore store = CaptureStore.getInstance();
                int storePre = store.getPreCaptureCount();
                int storePost = store.getPostCaptureCount();

                pre =  Math.max(0, storePre) ;
                post = Math.max(0, storePost);
            } catch (Throwable t) {
                pre = 10;
                post = 10;
            }
            
            final int finalPre = pre;
            final int finalPost = post;
            
            System.out.println("   参数: pre=" + finalPre + ", post=" + finalPost);
            
            // ✅ 获取当前显示的帧文件路径（唯一标识）
            String currentFramePath = slowMoPaneController.getCurrentPlayingFramePath();
            System.out.println("   🎯 当前显示帧路径: " + currentFramePath);
            
            if (currentFramePath == null || currentFramePath.isEmpty()) {
                System.err.println("❌ 无法获取当前播放帧");
                return;
            }
            
            // 获取磁盘缓存帧列表
            List<DiskCaptureCache.DiskFrameItem> allFrames =
                slowMoPaneController.getSlowMotionDiskFrames();
            
            if (allFrames == null || allFrames.isEmpty()) {
                System.err.println("❌ 慢放缓存为空");
                return;
            }
            
            // ✅ 根据文件路径在磁盘缓存中定位当前帧
            int currentIndex = -1;
            for (int i = 0; i < allFrames.size(); i++) {
                if (allFrames.get(i).filePath.equals(currentFramePath)) {
                    currentIndex = i;
                    break;
                }
            }
            
            if (currentIndex < 0) {
                System.err.println("❌ 当前播放帧在缓存中未找到");
                return;
            }
            
            final int eventIndex = currentIndex;
            System.out.println("   ✅ 事件帧索引: " + eventIndex);
            
            // ✅ 创建独立的抓拍会话（慢放类型）
            CaptureSession session;
            try {
                session = new CaptureSession("慢放");
            } catch (IOException e) {
                System.err.println("❌ 创建抓拍会话失败: " + e.getMessage());
                return;
            }
            
            // ✅ 一次性收集所有帧（前置+当前+后续），像时时流一样！
            int startIndex = Math.max(0, eventIndex - finalPre);
            int endIndex = Math.min(eventIndex + finalPost, allFrames.size() - 1);
            
            List<DiskCaptureCache.DiskFrameItem> sourceFrames = new ArrayList<>();
            
            // ✅ 1. 收集前置帧
            int actualPreCount = 0;
            for (int i = startIndex; i < eventIndex; i++) {
                DiskCaptureCache.DiskFrameItem diskItem = allFrames.get(i);
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(diskItem.filePath))) {
                    sourceFrames.add(diskItem);
                    actualPreCount++;
                }
            }
            
            // ✅ 2. 收集当前帧
            int eventFrameAdded = 0;
            long slowMoEventFrameId = -1;
            if (eventIndex >= 0 && eventIndex < allFrames.size()) {
                DiskCaptureCache.DiskFrameItem eventFrame = allFrames.get(eventIndex);
                slowMoEventFrameId = eventFrame.frameId;
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(eventFrame.filePath))) {
                    sourceFrames.add(eventFrame);
                    eventFrameAdded = 1;
                }
            }
            
            // ✅ 3. 尝试收集后续帧（尽可能多）
            int actualPostCount = 0;
            for (int i = eventIndex + 1; i <= endIndex && i < allFrames.size(); i++) {
                DiskCaptureCache.DiskFrameItem diskItem = allFrames.get(i);
                if (java.nio.file.Files.exists(java.nio.file.Paths.get(diskItem.filePath))) {
                    sourceFrames.add(diskItem);
                    actualPostCount++;
                } else {
                    break;  // 文件不存在，停止收集
                }
            }
            
            int missingPostCount = finalPost - actualPostCount;
            boolean needsEvent = missingPostCount > 0;  // 是否需要创建事件
            
            System.out.println("📊 慢放抓拍统计: 请求前=" + finalPre + ", 实际前=" + actualPreCount + 
                ", 当前=" + eventFrameAdded + ", 请求后=" + finalPost + ", 实际后=" + actualPostCount + 
                ", 缺失后=" + missingPostCount +
                " | eventIndex=" + eventIndex + ", frameId=" + slowMoEventFrameId + 
                " | 总帧=" + sourceFrames.size() + ", 需要事件=" + needsEvent);
            
            if (slowMoEventFrameId < 0) {
                System.err.println("❌ 无法获取慢放当前帧ID");
                return;
            }
            
            final long finalSlowMoEventFrameId = slowMoEventFrameId;
            final boolean finalNeedsEvent = needsEvent;
            final int finalMissingPostCount = missingPostCount;
            final int finalActualPreCount = actualPreCount;
            final int finalActualPostCount = actualPostCount;
            final int finalEventFrameAdded = eventFrameAdded;
            
            // 复制到会话文件夹
            List<DiskCaptureCache.DiskFrameItem> captureFrames =
                session.copyFramesFromCache(sourceFrames, 0,"slowmo");
            
            // ✅ 性能日志：帧复制后
            if (finalLogger != null) {
                finalLogger.checkpoint("帧复制完成");
                finalLogger.logDetail("复制帧数", captureFrames.size());
                finalLogger.logDetail("前置帧", actualPreCount);
                finalLogger.logDetail("当前帧", eventFrameAdded);
                finalLogger.logDetail("后续帧", actualPostCount);
            }
            
            // 设置事件帧ID
            session.setEventFrameId(finalSlowMoEventFrameId);
            
            final String sessionId = session.getSessionId();
            System.out.println("✅ 创建会话: " + sessionId + ", 复制" + captureFrames.size() + "帧到独立文件夹");
            
            // ✅ 创建文件日志（runtime/logs/capture_慢放-001_xxx.log）
            final com.acard.acard.utils.CaptureLogger logger = new com.acard.acard.utils.CaptureLogger(sessionId);
            logger.logCaptureStart(finalPre, finalPost);
            logger.log("📊 慢放抓拍统计: 请求前=" + finalPre + ", 实际前=" + finalActualPreCount + 
                ", 当前=" + finalEventFrameAdded + ", 请求后=" + finalPost + ", 实际后=" + finalActualPostCount + 
                ", 缺失后=" + finalMissingPostCount);
            logger.log("   eventIndex=" + eventIndex + ", frameId=" + finalSlowMoEventFrameId);
            logger.log("   总帧=" + sourceFrames.size() + ", 需要事件=" + finalNeedsEvent);
            logger.logPreFrames(finalPre, finalActualPreCount);
            
            if (linkedController instanceof CameraMainController finalCmc) {
                // ✅ 预先创建UI item并注册映射
                final int[] captureItemIndexHolder = {-1};
                
                try {
                    if (Platform.isFxApplicationThread()) {
                        captureItemIndexHolder[0] = finalCmc.createNewCaptureItem();
                        finalCmc.registerCaptureSession(sessionId, captureItemIndexHolder[0]);
                    } else {
                        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        Platform.runLater(() -> {
                            captureItemIndexHolder[0] = finalCmc.createNewCaptureItem();
                            finalCmc.registerCaptureSession(sessionId, captureItemIndexHolder[0]);
                            latch.countDown();
                        });
                        latch.await(1, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    System.err.println("❌ 预创建UI项失败: " + e.getMessage());
                }
                
                final int preCreatedIndex = captureItemIndexHolder[0];
                if (preCreatedIndex < 0) {
                    System.err.println("❌ 预创建UI项失败，取消抓拍");
                    return;
                }
                
                System.out.println("✅ 预创建UI项（慢放）: index=" + preCreatedIndex + ", sessionId=" + sessionId);
                
                // ✅ 立即显示已有帧
                if (!captureFrames.isEmpty()) {
                    Platform.runLater(() -> {
                        try {
                            // ✅ 如果有后续帧，定位到最后一帧（触发自动播放）
                            // 如果没有后续帧，定位到当前帧
                            int relativeEventIndex;
                            if (finalActualPostCount > 0) {
                                relativeEventIndex = captureFrames.size() - 1;  // 最后一帧
                                logger.log("✅ 初始定位到最后一帧（有后续帧）: index=" + relativeEventIndex);
                            } else {
                                relativeEventIndex = finalActualPreCount;  // 当前帧
                                logger.log("✅ 初始定位到当前帧（无后续帧）: index=" + relativeEventIndex);
                            }
                            
                            System.out.println("✅ 立即显示（慢放）[索引" + preCreatedIndex + ", sessionId=" + sessionId + "]: " + 
                                captureFrames.size() + "帧, eventIndex=" + relativeEventIndex);
                            
                            finalCmc.updateCaptureItemByIndex(preCreatedIndex, captureFrames, relativeEventIndex);
                            finalCmc.lockLastCaptureViewport();
                            
                            // ✅ 性能日志：UI显示完成
                            if (finalLogger != null) {
                                finalLogger.checkpoint("UI显示完成");
                            }
                        } catch (Throwable e) {
                            System.err.println("❌ 初始帧显示失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }
                
                // ✅ 如果后续帧不足，创建事件等待剩余帧（像时时流一样）
                if (finalNeedsEvent) {
                    logger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    logger.log("🔄 慢放抓拍：后续帧不足，启用事件驱动");
                    logger.log("   缺失后续帧: " + finalMissingPostCount + "帧");
                    logger.log("   慢放缓存frameId: " + finalSlowMoEventFrameId);
                    logger.log("   实时流当前frameId: " + finalPlayer.getCurrentFrameId() + "（参考）");
                    logger.log("   会话ID: " + sessionId);
                    logger.logEventRegistered(finalSlowMoEventFrameId, finalMissingPostCount);
                    
                    // 创建专属回调（追加后续帧，逐个推送）
                    final java.util.concurrent.atomic.AtomicInteger callbackCounter = new java.util.concurrent.atomic.AtomicInteger(0);
                    final long callbackStartTime = System.currentTimeMillis();
                    java.util.function.BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback = (diskFrame, sid) -> {
                        int count = callbackCounter.incrementAndGet();
                        logger.log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        logger.log("📢 [慢放回调-" + count + "/" + finalMissingPostCount + "] 收到后续帧");
                        logger.log("   frameId: " + diskFrame.frameId);
                        logger.log("   filePath: " + diskFrame.filePath);
                        logger.log("   sessionId: " + sid);
                        logger.logPostFrame(count, finalMissingPostCount, diskFrame.frameId);
                        
                        Platform.runLater(() -> {
                            try {
                                finalCmc.appendFrameBySessionId(sid, diskFrame);
                                logger.log("   ✅ 已追加到UI item");
                                
                                // 检查是否完成
                                if (count >= finalMissingPostCount) {
                                    long duration = System.currentTimeMillis() - callbackStartTime;
                                    logger.logCaptureComplete(finalActualPreCount + finalEventFrameAdded + count, duration);
                                    logger.close();
                                    
                                    // ✅ 关闭性能日志
                                    if (finalLogger != null) {
                                        finalLogger.complete("抓拍完成（包含后续帧）");
                                    }
                                }
                            } catch (Throwable e) {
                                logger.logError("追加到UI失败", e);
                            }
                        });
                    };
                    
                    // 创建抓拍事件（只等待缺失的后续帧）
                    CaptureEvent event = new CaptureEvent(
                        CaptureEvent.Type.SLOWMOTION,  // ✅ 事件类型：慢放
                        finalSlowMoEventFrameId,
                        finalMissingPostCount,  // ✅ 只等待缺失的帧
                        session,
                        callback
                    );
                    
                    // 注册到事件管理器（等待实时流推送）
                    CaptureEventManager.getInstance().registerEvent(event);
                    
                    System.out.println("✅ 慢放事件已注册到CaptureEventManager");
                    System.out.println("   事件ID: " + event.getEventId().substring(0, 8));
                    System.out.println("   事件类型: " + event.getType().getDisplayName());
                    System.out.println("   事件帧ID: " + event.getEventFrameId());
                    System.out.println("   需要后续帧: " + event.getPostFrameCount());
                    System.out.println("   当前活跃事件数: " + CaptureEventManager.getInstance().getActiveEventCount());
                    System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                } else {
                    logger.log("✅ 缓存足够，无需创建事件");
                    logger.log("   sessionId: " + sessionId);
                    logger.log("   总帧数: " + captureFrames.size() + "帧（全部来自慢放缓存）");
                    logger.logCaptureComplete(captureFrames.size(), 0);
                    logger.close();
                    
                    // ✅ 关闭性能日志
                    if (finalLogger != null) {
                        finalLogger.complete("抓拍完成（全部来自缓存）");
                    }
                }
            }
        } catch (Throwable e) {
            System.err.println("❌ 慢放抓拍失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // ✨ 慢放后续帧事件（磁盘帧轮询）
    private class SlowMotionPostFrameEvent {
        final int targetIndex;
        final int eventIndex;
        final int startIndex;  // eventIndex + 1
        final int endIndex;    // eventIndex + postCount
        final int postCount;
        final CameraMainController controller;
        final java.util.concurrent.atomic.AtomicInteger collectedCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final long createTime;
        
        SlowMotionPostFrameEvent(int targetIndex, int eventIndex, int postCount, 
                                CameraMainController controller) {
            this.targetIndex = targetIndex;
            this.eventIndex = eventIndex;
            this.startIndex = eventIndex + 1;
            this.endIndex = eventIndex + postCount;
            this.postCount = postCount;
            this.controller = controller;
            this.createTime = System.currentTimeMillis();
        }
        
        // 收集当前已可用的后续帧
        void collectAvailablePostFrames() {

        }
        
        private void retryLater() {
            System.out.println("⏳ 还需要" + (postCount - collectedCount.get()) + "帧，500ms后重试");
            javafx.animation.PauseTransition pause = 
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            pause.setOnFinished(e -> collectAvailablePostFrames());
            pause.play();
        }
    }
    
    // ✨ 活跃的慢放后续帧事件列表
    private final List<SlowMotionPostFrameEvent> activeSlowMotionPostFrameEvents =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    
    /**
     * 🎬 执行MKV抓拍（从MKV文件中提取帧）
     */
    private void executeMkvCapture(SlowMoGpuPlayer mkvPlayer,
                                   com.acard.acard.utils.PerformanceLogger logger) {
        System.out.println("🎯 开始MKV抓拍");
        
        try {
            // 获取前后抓拍数
            int pre, post;
            try {
                CaptureStore store = CaptureStore.getInstance();
                int storePre = store.getPreCaptureCount();
                int storePost = store.getPostCaptureCount();

                pre = Math.max(0, storePre);
                post = Math.max(0, storePost);
            } catch (Throwable t) {
                pre = 10;
                post = 10;
            }
            
            System.out.println("   参数: pre=" + pre + ", post=" + post);
            
            // 获取当前播放帧号
            int currentFrame = slowMoPaneController != null ? 
                slowMoPaneController.getCurrentPlaybackFrameIndex() : 0;
            
            System.out.println("   当前播放帧: " + currentFrame);
            
            if (currentFrame < 0) {
                System.err.println("❌ 无法获取当前播放帧号");
                if (logger != null) logger.complete("抓拍失败：无法获取播放位置");
                return;
            }
            
            // 创建输出目录
            String timestamp = String.valueOf(System.currentTimeMillis());
            String outputDir = "runtime/captures/slowmo/mkv_" + timestamp;
            File outDir = new File(outputDir);
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            
            System.out.println("   输出目录: " + outputDir);
            
            // 调用MKV播放器的抓拍方法
            final CameraMainController finalCmc = (linkedController instanceof CameraMainController) ? 
                (CameraMainController) linkedController : null;
            final int finalPre = pre;  // Lambda中使用，必须是final
            
            mkvPlayer.captureFrames(currentFrame, pre, post, outputDir, (savedFiles) -> {
                // 抓拍完成回调
                System.out.println("✅ MKV抓拍完成: 成功保存" + savedFiles.size() + "帧");
                
                if (logger != null) {
                    logger.complete("MKV抓拍完成：" + savedFiles.size() + "帧");
                }
                
                // 如果有UI控制器，创建抓拍项并显示
                if (finalCmc != null && !savedFiles.isEmpty()) {
                    Platform.runLater(() -> {
                        try {
                            // 创建抓拍项
                            int captureIndex = finalCmc.createNewCaptureItem();
                            
                            // 转换为DiskCaptureCache.DiskFrameItem格式
                            List<DiskCaptureCache.DiskFrameItem> frameItems = new ArrayList<>();
                            for (String filePath : savedFiles) {
                                File file = new File(filePath);
                                if (file.exists()) {
                                    // 创建DiskFrameItem（需要6个参数：filePath, timestamp, width, height, format, frameId）
                                    DiskCaptureCache.DiskFrameItem item =
                                        new DiskCaptureCache.DiskFrameItem(
                                            filePath, 
                                            System.currentTimeMillis(), 
                                            1920,  // width（默认1080p）
                                            1080,  // height
                                            "jpeg", // format
                                            0L     // frameId（MKV抓拍不需要）
                                        );
                                    frameItems.add(item);
                                }
                            }
                            
                            // 更新UI
                            if (!frameItems.isEmpty()) {
                                int eventIndex = finalPre;  // 事件帧在中间
                                finalCmc.updateCaptureItemByIndex(captureIndex, frameItems, eventIndex);
                                finalCmc.lockLastCaptureViewport();
                                System.out.println("✅ MKV抓拍已显示到UI: index=" + captureIndex + ", 帧数=" + frameItems.size());
                            }
                            
                        } catch (Exception e) {
                            System.err.println("❌ MKV抓拍UI更新失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ MKV抓拍失败: " + e.getMessage());
            e.printStackTrace();
            if (logger != null) {
                logger.complete("MKV抓拍失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 调试120帧缓存状态
     */
    private void debugCacheStatus() {
        if (gpuView == null) {
            System.err.println("🔍 缓存调试: gpuView为null");
            return;
        }

        try {
            // 检查GpuView和Player的绑定状态
            SimpleWebRTCPlayer player = gpuView.getPlayer();
            System.err.println("🔍 GPU链路绑定状态:");
            System.err.println("  - gpuView: " + (gpuView != null ? "已创建" : "null"));
            System.err.println("  - player: " + (player != null ? "已绑定" : "null"));
            
            if (player != null) {
                // 检查player的状态
                System.err.println("  - player实例: " + player.getClass().getSimpleName());
                // 尝试获取当前帧回调状态（如果有相关方法）
                System.err.println("  - player状态: 已绑定到GpuView");
            }
            
            // 检查实时活跃状态
            boolean isActive = gpuView.isRealtimeActive();
            boolean isActive800 = gpuView.isRealtimeActive(800);
            boolean isActive2000 = gpuView.isRealtimeActive(2000);
            
            System.err.println("🔍 120帧缓存状态调试:");
            System.err.println("  - 实时活跃(默认2000ms): " + isActive);
            System.err.println("  - 实时活跃(800ms): " + isActive800);
            System.err.println("  - 实时活跃(2000ms): " + isActive2000);

            // 检查内存缓存快照
            var realtimeSnapshot = gpuView.getRealtimeSnapshot();
            System.err.println("  - 内存缓存帧数: " + (realtimeSnapshot != null ? realtimeSnapshot.size() : "null"));
            
            if (realtimeSnapshot != null && !realtimeSnapshot.isEmpty()) {
                var firstFrame = realtimeSnapshot.get(0);
                var lastFrame = realtimeSnapshot.get(realtimeSnapshot.size() - 1);
                System.err.println("  - 第一帧时间戳: " + firstFrame.timestamp);
                System.err.println("  - 最后帧时间戳: " + lastFrame.timestamp);
                System.err.println("  - 时间跨度: " + (lastFrame.timestamp - firstFrame.timestamp) + "ms");
            }

            // 检查磁盘缓存快照
            var diskSnapshot = gpuView.getRealtimeDiskSnapshot();
            System.err.println("  - 磁盘缓存帧数: " + (diskSnapshot != null ? diskSnapshot.size() : "null"));

            // 尝试收集窗口
            var windowResult = gpuView.collectRealtimeWindowAnchored(-1, 2, 2);
            if (windowResult != null) {
                System.err.println("  - 窗口收集结果: " + windowResult.getFrames().size() + "帧");
                System.err.println("  - 事件索引: " + windowResult.getEventIndexInWindow());
                System.err.println("  - 是否超时: " + windowResult.isTimedOut());
            } else {
                System.err.println("  - 窗口收集结果: null");
            }
            
            // 关键诊断：检查帧回调是否被正确设置
            System.err.println("🔍 帧回调诊断:");
            System.err.println("  - GpuView.attach()应该已调用player.setFrameCallback(pushFrameToRealtimeBuffer)");
            System.err.println("  - 如果缓存为空，说明:");
            System.err.println("    1. setFrameCallback没有生效");
            System.err.println("    2. 或者SimpleWebRTCPlayer没有产生帧数据");
            System.err.println("    3. 或者GPU链路中断/未正确建立");

        } catch (Exception e) {
            System.err.println("🔍 缓存调试异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 从临时离线抓拍直接获取最近帧窗口（最多约120帧），持续追加到UI
     */
    private void captureFromGpuChain(int pre, int post, CameraMainController cmc) {
        System.out.println("🎯 切换为临时离线抓拍窗口模式，持续追加最近帧");

        SimpleWebRTCPlayer player = null;
        try { player = (gpuView != null ? gpuView.getPlayer() : null); } catch (Throwable ignore) {}
        if (player == null && playerView != null) {
            try { player = playerView.getPlayer(); } catch (Throwable ignore) {}
        }

        if (player == null) {
            System.out.println("❌ 未找到Player，回退到缓存抓拍");
            fallbackToCache(pre, post, cmc);
            return;
        }

        final java.util.concurrent.atomic.AtomicBoolean created = new java.util.concurrent.atomic.AtomicBoolean(false);
        player.requestCapture(img -> {
            try {
                if (img == null) {
                    System.out.println("Element2_3Controller: 临时抓拍返回空图像");
                    Platform.runLater(() -> {
                        showError("临时抓拍返回空图像");
                        btnCapture.setDisable(false);
                        btnCapture.setText("抓拍");
                    });
                    return;
                }
                byte[] bytes = convertImageToJpegBytes(img);
                if (bytes == null || bytes.length == 0) {
                    Platform.runLater(() -> {
                        showError("临时抓拍图像编码失败");
                        btnCapture.setDisable(false);
                        btnCapture.setText("抓拍");
                    });
                    return;
                }

                final long eventTs = System.currentTimeMillis();
                FrameRingBuffer.FrameItem item = new FrameRingBuffer.FrameItem(bytes, eventTs);
                List<FrameRingBuffer.FrameItem> list = java.util.Collections.singletonList(item);

                if (linkedController instanceof CameraMainController cmcRef) {
                    Platform.runLater(() -> {

                    });
                } else {
                    Platform.runLater(() -> {
                        showInfo("抓拍进行中(临时支路)");
                        btnCapture.setDisable(false);
                        btnCapture.setText("抓拍");
                    });
                }
            } catch (Throwable e) {
                Platform.runLater(() -> {
                    showError("临时抓拍失败: " + e.getMessage());
                    btnCapture.setDisable(false);
                    btnCapture.setText("抓拍");
                });
            }
        });
    }
    
    /**
      * 获取当前的帧回调（如果有的话）
      */
     private java.util.function.Consumer<javafx.scene.image.Image> getCurrentFrameCallback() {
         // 由于pushFrameToRealtimeBuffer是private的，我们返回null让系统自动处理
         return null;
     }
    
    /**
     * 收集额外的前后帧
     */
    private void collectAdditionalFrames(List<FrameRingBuffer.FrameItem> initialFrames, int pre, int post, CameraMainController cmc) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // 尝试从内存缓存获取前帧
                if (pre > 0 && gpuView != null) {
                    var memorySnapshot = gpuView.getRealtimeSnapshot();
                    if (memorySnapshot != null && memorySnapshot.size() > pre) {
                        // 获取最后pre帧作为前帧
                        int startIndex = Math.max(0, memorySnapshot.size() - pre - 1);
                        for (int i = startIndex; i < memorySnapshot.size() - 1; i++) {
                            var frame = memorySnapshot.get(i);
                            if (frame != null) {
                                initialFrames.add(0, frame); // 插入到开头
                            }
                        }
                        System.out.println("✅ 收集到 " + (memorySnapshot.size() - startIndex - 1) + " 个前帧");
                    }
                }
                
                // 等待并收集后帧
                if (post > 0 && gpuView != null) {
                    int collectedPost = 0;
                    long startTime = System.currentTimeMillis();
                    long timeout = 2000; // 2秒超时
                    
                    while (collectedPost < post && (System.currentTimeMillis() - startTime) < timeout) {
                        Thread.sleep(100); // 等待新帧
                        
                        var currentSnapshot = gpuView.getRealtimeSnapshot();
                        if (currentSnapshot != null && currentSnapshot.size() > initialFrames.size()) {
                            // 获取新的帧
                            for (int i = initialFrames.size(); i < Math.min(currentSnapshot.size(), initialFrames.size() + post); i++) {
                                var frame = currentSnapshot.get(i);
                                if (frame != null) {
                                    initialFrames.add(frame);
                                    collectedPost++;
                                }
                            }
                        }
                    }
                    System.out.println("✅ 收集到 " + collectedPost + " 个后帧");
                }
                
                // 更新显示
                Platform.runLater(() -> {
                    if (initialFrames.size() > 1 && cmc != null) {

                    }
                });
                
            } catch (Exception e) {
                System.err.println("❌ 收集额外帧失败: " + e.getMessage());
            }
        });
    }
    
    /**
     * 回退到缓存获取方式
     */
    private void fallbackToCache(int pre, int post, CameraMainController cmc) {

    }

    private boolean isSlowMoMode() {

       /* try {
            if (linkedController instanceof CameraMainController cmc) {
                var slowPane = cmc.getSlowPaneController();
                if (slowPane != null) return slowPane.isSlowMoCapturing();
            }
            return playerView != null && playerView.isSlowMoCapturing();
        } catch (Throwable ignore) {
            return false;
        }*/
        return false;
    }








    /**
     * 根据采集状态激活/禁用停止按钮，并调整样式以呈现视觉变化。
     */
    private void activateStopButton(boolean active) {
        Platform.runLater(() -> {
        if (btnStop == null) return;
        btnStop.setDisable(!active);
        btnStop.setStyle(active ? STOP_ACTIVE_STYLE : STOP_INACTIVE_STYLE);
        });
    }
    
    /**
     * ✅ 判断是否正在慢放录制
     * @return true=正在录制，false=未录制
     */
    public boolean isSlowmoRecording() {
        // btnStop 启用（非禁用）表示正在录制
        return btnStop != null && !btnStop.isDisabled();
    }

    
    /**
     * 停止录制帧数计数器
     */
    private void stopRecordFrameCounter() {
        if (recordFrameCounterTask != null) {
            recordFrameCounterTask.cancel(false);
            recordFrameCounterTask = null;
        }
        simulatedRecordFrames = 0;
        System.out.println("⏹️ 录制帧数计数器已停止");
    }
    
    private void showInfo(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            // ⭐ 设置父窗口，防止全屏时层级错乱
            if (gpuView != null && gpuView.getScene() != null && gpuView.getScene().getWindow() != null) {
                Stage mainStage = (Stage) gpuView.getScene().getWindow();
                alert.initOwner(mainStage);
                // ⭐ 全屏保护
                alert.setOnShowing(e -> mainStage.toFront());
                alert.setOnHidden(e -> Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); }));
            }
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }
    
    /**
     * ✅ 从文件路径中提取sessionId
     * 例如: "runtime/captures/时时流-001/frame_001.jpeg" → "时时流-001"
     */
    private String extractSessionIdFromPath(String filePath) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            if (path.getNameCount() >= 2) {
                return path.getName(path.getNameCount() - 2).toString();
            }
        } catch (Throwable e) {
            System.err.println("⚠️ 提取sessionId失败: " + e.getMessage());
        }
        return "";
    }

    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            // ⭐ 设置父窗口，防止全屏时层级错乱
            if (gpuView != null && gpuView.getScene() != null && gpuView.getScene().getWindow() != null) {
                Stage mainStage = (Stage) gpuView.getScene().getWindow();
                alert.initOwner(mainStage);
                // ⭐ 全屏保护
                alert.setOnShowing(e -> mainStage.toFront());
                alert.setOnHidden(e -> Platform.runLater(() -> { mainStage.toFront(); mainStage.requestFocus(); }));
            }
            alert.setTitle("错误");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }

    /**
     * 将JavaFX Image转换为JPEG字节数组
     */
    private byte[] convertImageToJpegBytes(javafx.scene.image.Image fxImage) {
        try {
            // 将JavaFX Image转换为BufferedImage
            BufferedImage src = SwingFXUtils.fromFXImage(fxImage, null);
            if (src == null) {
                return null;
            }
            
            // 将含透明通道的图像扁平化为RGB，避免JPEG编码失败
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }

            // 尝试写JPEG，失败则回退为PNG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ImageIO.write(rgb, "jpg", baos);
                return baos.toByteArray();
            } catch (Exception jpegEx) {
                try {
                    baos.reset();
                    ImageIO.write(src, "png", baos);
                    return baos.toByteArray();
                } catch (Exception pngEx) {
                    System.err.println("图像编码失败: jpg->" + jpegEx.getMessage() + ", png->" + pngEx.getMessage());
                    return null;
                }
            }
        } catch (Exception e) {
            System.err.println("图像转换为JPEG失败: " + e.getMessage());
            return null;
        }
    }


    
    /**
     * 注册全局帧监听器（每帧实时触发）
     */
    private void registerGlobalFrameListener() {
        // ✅ 检查是否已注册
        if (globalFrameListenerRegistered) {
            System.out.println("ℹ️ 全局帧监听器已注册，跳过重复注册");
            return;
        }
        
        System.out.println("🔧 开始注册全局帧监听器...");
        
        // ✅ 通过 linkedController 获取 CameraMainController
        CameraMainController cmc = (linkedController instanceof CameraMainController) 
            ? (CameraMainController) linkedController : null;
        
        System.out.println("  - linkedController: " + (linkedController != null ? linkedController.getClass().getSimpleName() : "null"));
        System.out.println("  - cmc: " + (cmc != null ? "已获取" : "null"));
        
        if (cmc == null) {
            System.err.println("⚠️ CameraMainController 为空，延迟500ms后重试");
            // 延迟注册（等待父控制器初始化）
            javafx.animation.PauseTransition pause = 
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            pause.setOnFinished(e -> registerGlobalFrameListener());
            pause.play();
            return;
        }
        
        // ✅ 通过 GpuView 获取播放器
        System.out.println("  - gpuView: " + (gpuView != null ? "已设置" : "null"));
        System.out.println("  - playerView: " + (playerView != null ? "已设置（备用）" : "null"));
        
        SimpleWebRTCPlayer player = null;
        if (gpuView != null) {
            try {
                player = gpuView.getPlayer();
                System.out.println("  - player (通过GpuView): " + (player != null ? "✅ 已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - 通过GpuView获取播放器失败: " + e.getMessage());
            }
        }
        
        if (player == null && playerView != null) {
            try {
                player = playerView.getPlayer();
                System.out.println("  - player (通过playerView): " + (player != null ? "已获取" : "null"));
            } catch (Throwable e) {
                System.err.println("  - 通过playerView获取播放器失败: " + e.getMessage());
            }
        }
        
        if (player == null) {
            System.err.println("⚠️ Player 为空，延迟500ms后重试");
            javafx.animation.PauseTransition pause = 
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
            pause.setOnFinished(e -> registerGlobalFrameListener());
            pause.play();
            return;
        }
        
        System.out.println("✅ 播放器已获取，准备设置全局帧监听器");
        
        // ✨ 设置全局帧监听器（在非JavaFX线程调用）
        final java.util.concurrent.atomic.AtomicInteger frameCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        
        player.setGlobalFrameListener(newFrame -> {
            // ✅ 直接使用帧的frameId（由SimpleWebRTCPlayer统一分配）
            long currentFrameId = newFrame.frameId;
            
            int count = frameCounter.incrementAndGet();
            if (count == 1) {
                System.out.println("🎬 全局帧监听器开始接收帧，当前帧ID: " + currentFrameId);
            }
            
            // 检查活跃事件列表
            int activeCount = activePostFrameEvents.size();
            if (activeCount > 0 && count % 30 == 1) {
                System.out.println("🔍 当前帧ID=" + currentFrameId + ", 活跃事件数=" + activeCount);
                for (PostFrameEvent evt : activePostFrameEvents) {
                    System.out.println("   事件[索引" + evt.targetIndex + "]: 需要帧ID " + 
                        evt.startFrameId + "~" + evt.endFrameId + 
                        ", 已收到" + evt.receivedCount.get() + "/" + evt.postCount);
                }
            }
            
            // 遍历活跃事件列表，分发给需要的事件（不卡UI）
            for (PostFrameEvent event : activePostFrameEvents) {
                if (event.needsFrame(currentFrameId)) {
                    // 这一帧属于这个事件的后续帧，缓冲
                    event.addFrame(newFrame);
                    
                    int received = event.receivedCount.get();
                    if (received == 1 || received == event.postCount || received % 5 == 0) {
                        System.out.println("📦 后续帧已缓冲 [索引" + event.targetIndex + 
                            "]: 帧ID=" + currentFrameId + ", 进度" + received + "/" + event.postCount);
                    }
                    
                    // ✅ 如果收集完成，批量推送并移除事件
                    if (event.isCollectionComplete()) {
                        Platform.runLater(() -> {
                            try {
                                int flushed = event.flushFrames();
                                activePostFrameEvents.remove(event);
                                long duration = System.currentTimeMillis() - event.createTime;
                                System.out.println("✅ 后续帧推送完成 [索引" + event.targetIndex + 
                                    "]: " + flushed + "/" + event.postCount + "帧，耗时" + duration + "ms");
                            } catch (Throwable e) {
                                System.err.println("❌ 推送后续帧失败 [索引" + event.targetIndex + "]: " + 
                                    e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    }
                }
            }
        });
        
        // ✅ 标记为已注册
        globalFrameListenerRegistered = true;
        System.out.println("✅ 全局帧监听器已注册，开始实时分发");
    }





    
    /**
     * ✅ 清理RecordOnlyPlayer资源
     * 调用时机：
     * 1. 用户关闭元素2-3窗口
     * 2. 切换到其他视频源
     * 3. 程序退出
     */
    public void cleanupRecordOnlyPlayer() {
        if (recordOnlyPlayer != null) {
            System.out.println("🧹 清理RecordOnlyPlayer资源...");
            try {
                // 停止录制
                if (recordOnlyPlayer.isRecording()) {
                    recordOnlyPlayer.stopRecording();
                }
                // 断开WebRTC连接
                recordOnlyPlayer.stop();
                System.out.println("✅ RecordOnlyPlayer已清理（WebRTC连接已断开）");
            } catch (Throwable e) {
                System.err.println("⚠️ 清理RecordOnlyPlayer失败: " + e.getMessage());
            } finally {
                recordOnlyPlayer = null;
            }
        }
    }

    /**
     * ✅ 切换 JPEG 保存状态
     * 调用时机：用户点击 JPEG 保存开关按钮
     */
    @FXML
    private void onToggleJpegSave() {
        jpegSaveEnabled = !jpegSaveEnabled;
        
        // 更新按钮文本
        updateJpegToggleButtonText();
        
        // 控制播放器的 JPEG 保存
        if (playerView != null && playerView.getPlayer() != null) {
            SimpleWebRTCPlayer player = playerView.getPlayer();

            System.out.println("🎯 JPEG 保存状态已切换: " + (jpegSaveEnabled ? "启用" : "禁用"));
        }
        

    }
    
    /**
     * ✅ 更新 JPEG 切换按钮的文本
     */
    private void updateJpegToggleButtonText() {
        if (btnJpegToggle != null) {
            btnJpegToggle.setText(jpegSaveEnabled ? "JPEG: ON" : "JPEG: OFF");
            
            // 可选：根据状态改变按钮样式
            if (jpegSaveEnabled) {
                btnJpegToggle.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
            } else {
                btnJpegToggle.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
            }
        }
    }
    
    /**
     * ✅ 获取当前 JPEG 保存状态
     * @return true 表示启用，false 表示禁用
     */
    public boolean isJpegSaveEnabled() {
        return jpegSaveEnabled;
    }
    
    /**
     * ✅ 设置 JPEG 保存状态（程序化控制）
     * @param enabled true 启用，false 禁用
     */

}