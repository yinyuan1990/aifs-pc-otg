package com.acard.acard;

import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.webrtc.WebRTCConnectionManager;
import com.acard.acard.pipeline.GStreamerPipelineBuilder;
import com.acard.acard.decoder.VideoDecoderManager;
import com.acard.acard.probe.ProbeManager;
import com.acard.acard.monitor.PerformanceMonitor;
import com.acard.acard.capture.SnapshotWindowCollector;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.webrtc.*;
import org.freedesktop.gstreamer.lowlevel.GstBufferAPI;
import org.freedesktop.gstreamer.event.Event;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 重构后的WebRTC播放器视图
 * 使用模块化组件管理WebRTC连接、GStreamer管道、解码器、探针和性能监控
 */
public class GstWebRTCPlayerViewRefactored extends StackPane {

    // UI组件
    private final Canvas canvas = new Canvas();
    private final Canvas slowCanvas = new Canvas();
    
    // 模块化组件
    private final WebRTCConnectionManager webrtcManager;
    private final GStreamerPipelineBuilder pipelineBuilder;
    private final VideoDecoderManager decoderManager;
    private final ProbeManager probeManager;
    private final PerformanceMonitor performanceMonitor;
    
    // 连接参数
    private final String host;
    private final int apiPort;
    private final String app;
    private final String stream;
    
    // 状态管理
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    
    // 帧缓存
    private volatile Image lastFrameFx;
    private volatile Image lastSlowFx;
    
    // 慢放相关
    private volatile boolean slowMoCapturing = false;
    private volatile boolean slowMoPlaying = false;
    private final FrameRingBuffer slowBuffer = new FrameRingBuffer(
        SlowmoStore.getInstance().getSlowmoFrames(), 1920, 1080, 1.0f
    );
    private final FrameRingBuffer realtimeBuffer = new FrameRingBuffer(120, 1280, 720, 1.0f);
    private SlowMoPlayer slowPlayer = null;
    
    // 帧保存器
    private final FrameSaver frameSaver;
    private final String outputDir = "D:\\zhen";
    
    // 定时器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    // 流活动监控
    private volatile long lastFrameTimeMs = 0L;
    private volatile boolean streamActive = false;
    private java.util.function.Consumer<Boolean> onStreamActiveChanged;
    private Runnable onSlowMoStreamStopped;

    @Override
    protected double computeMinWidth(double height) { return 0; }
    @Override
    protected double computeMinHeight(double width) { return 0; }
    @Override
    protected double computePrefWidth(double height) { return 0; }
    @Override
    protected double computePrefHeight(double width) { return 0; }
    @Override
    protected double computeMaxWidth(double height) { return Double.MAX_VALUE; }
    @Override
    protected double computeMaxHeight(double width) { return Double.MAX_VALUE; }

    public GstWebRTCPlayerViewRefactored(String host, int apiPort, String app, String stream) {
        this.host = host;
        this.apiPort = apiPort;
        this.app = app;
        this.stream = stream;

        System.err.println("🚀 初始化重构后的WebRTC播放器视图");
        
        // 初始化UI
        initializeUI();
        
        // 初始化模块化组件
        this.webrtcManager = new WebRTCConnectionManager(host, apiPort, app, stream);
        this.pipelineBuilder = new GStreamerPipelineBuilder("webrtc-pipeline");
        this.decoderManager = new VideoDecoderManager();
        this.probeManager = new ProbeManager();
        this.performanceMonitor = new PerformanceMonitor();
        
        // 初始化帧保存器
        this.frameSaver = new FrameSaver(outputDir, 20);
        this.frameSaver.start();
        
        // 设置组件回调
        setupComponentCallbacks();
        
        // 启动初始化流程
        initializeComponents();
        
        System.err.println("✅ WebRTC播放器视图初始化完成");
    }
    
    /**
     * 初始化UI组件
     */
    private void initializeUI() {
        // 底层为实时画面，顶层为慢放覆盖层
        getChildren().addAll(canvas, slowCanvas);
        setMinSize(0, 0);
        
        System.err.println("🎨 UI组件初始化完成");
    }
    
    /**
     * 设置组件回调
     */
    private void setupComponentCallbacks() {
        // WebRTC连接管理器回调
        webrtcManager.setCallback(new WebRTCConnectionManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                System.err.println("🔗 WebRTC连接已建立");
                Platform.runLater(() -> performanceMonitor.startMonitoring());
            }
            
            @Override
            public void onDisconnected() {
                System.err.println("🔌 WebRTC连接已关闭");
                Platform.runLater(() -> performanceMonitor.stopMonitoring());
            }
            
            @Override
            public void onError(String error) {
                System.err.println("❌ WebRTC连接错误: " + error);
                handleConnectionError(error);
            }
            
            @Override
            public void onNewTransceiver(Element element, Object transceiver) {
                System.err.println("📡 新的收发器已添加");
                handleNewTransceiver(transceiver);
            }
        });
        
        // 管道构建器已在构造函数中初始化，无需设置回调
        // pipelineBuilder.setPipelineCallback(...) 方法不存在
        
        // 解码器管理器回调
        decoderManager.setCallback(new VideoDecoderManager.DecoderCallback() {
            @Override
            public void onDecodingStarted() {
                System.err.println("🎬 解码开始");
                updateStreamActivity(true);
            }
            
            @Override
            public void onDecodingStopped() {
                System.err.println("⏹️ 解码停止");
                updateStreamActivity(false);
            }
            
            @Override
            public void onDecodingError(String error) {
                System.err.println("❌ 解码错误: " + error);
                handleDecodingError(error);
            }
            
            @Override
            public void onFrameDecoded() {
                lastFrameTimeMs = System.currentTimeMillis();
                updateStreamActivity(true);
            }
            
            @Override
            public void onCapsNegotiated(Caps caps) {
                System.err.println("🔧 Caps协商完成: " + caps.toString());
            }
        });
        
        // 探针管理器回调
        probeManager.setCallback(new ProbeManager.ProbeCallback() {
            @Override
            public void onBufferProbe(String probeName, Buffer buffer, ProbeManager.ProbeStats stats) {
                // 使用GstBufferAPI获取buffer大小
                long size = 0;
                try {
                    size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                } catch (Exception e) {
                    System.err.println("❌ 获取buffer大小失败: " + e.getMessage());
                    return;
                }
                
                performanceMonitor.recordDataTransfer(size);
                
                // 特殊处理某些探针
                if (probeName.contains("decoder_src")) {
                    // 解码器输出探针，表示成功解码
                    lastFrameTimeMs = System.currentTimeMillis();
                    updateStreamActivity(true);
                }
            }
            
            @Override
            public void onEventProbe(String probeName, Event event, ProbeManager.ProbeStats stats) {
                // 处理重要事件
                String eventType = event.getClass().getSimpleName();
                if ("EOSEvent".equals(eventType)) {
                    System.err.println("⏹️ 流结束事件: " + probeName);
                    updateStreamActivity(false);
                }
            }
            
            @Override
            public void onProbeError(String probeName, String error) {
                System.err.println("❌ 探针错误 [" + probeName + "]: " + error);
            }
        });
        
        // 性能监控器回调
        performanceMonitor.setCallback(new PerformanceMonitor.PerformanceCallback() {
            @Override
            public void onPerformanceUpdate(PerformanceMonitor.PerformanceStats stats) {
                // 可以在这里更新UI或记录日志
                if (stats.fps == 0.0 && isPlaying.get()) {
                    // FPS为0但应该在播放，可能有问题
                    System.err.println("⚠️ 性能警告: FPS为0但管道应该在播放");
                }
            }
            
            @Override
            public void onPerformanceAlert(String alertType, String message) {
                System.err.println("⚠️ 性能警告 [" + alertType + "]: " + message);
                
                // 根据警告类型采取行动
                if ("LOW_FPS".equals(alertType)) {
                    // 请求关键帧
                    decoderManager.requestKeyFrame();
                } else if ("HIGH_LATENCY".equals(alertType)) {
                    // 可能需要重置缓冲区
                    resetBuffers();
                }
            }
        });
        
        System.err.println("🔗 组件回调设置完成");
    }
    
    /**
     * 初始化组件
     */
    private void initializeComponents() {
        try {
            // 1. 初始化WebRTC连接管理器
            // webrtcManager已在构造函数中初始化，无需再次调用initialize()
            
            // 2. 构建GStreamer管道
            pipelineBuilder.buildWebRTCReceivePipeline(webrtcManager);
            
            // 3. 配置解码器
            Element decoder = decoderManager.createOptimalDecoder();
            
            // 4. 获取WebRTC bin
            WebRTCBin webrtcBin = webrtcManager.getWebRTCBin();
            
            // 5. 启动管道
            pipelineBuilder.play();
            
            // 6. 建立WebRTC连接
            establishWebRTCConnection();
            
            // 7. 启动流活动监控
            startStreamActivityMonitor();
            
            isInitialized.set(true);
            System.err.println("✅ 所有组件初始化完成");
            
        } catch (Exception e) {
            System.err.println("❌ 组件初始化失败: " + e.getMessage());
            e.printStackTrace();
            handleInitializationError(e);
        }
    }
    
    /**
     * 建立WebRTC连接
     */
    private void establishWebRTCConnection() {
        try {
            System.err.println("🔗 开始建立WebRTC连接...");
            
            // 创建offer并发送到SRS
            webrtcManager.createOffer((offer) -> {
                try {
                    String sdpOffer = offer.getSDPMessage().toString();
                    System.err.println("📤 发送SDP offer到SRS服务器");
                    
                    String sdpAnswer = postOfferToSRS(sdpOffer);
                    System.err.println("📥 收到SDP answer从SRS服务器");
                    
                    webrtcManager.setRemoteDescription(sdpAnswer);
                    System.err.println("✅ WebRTC连接建立完成");
                    
                } catch (Exception e) {
                    System.err.println("❌ WebRTC连接建立失败: " + e.getMessage());
                    handleConnectionError(e.getMessage());
                }
            });
            
        } catch (Exception e) {
            System.err.println("❌ 建立WebRTC连接时出错: " + e.getMessage());
            handleConnectionError(e.getMessage());
        }
    }
    
    /**
     * 设置探针
     */
    private void setupProbes(Pipeline pipeline) {
        try {
            System.err.println("🔍 设置管道探针...");
            
            // 获取关键元素的pad并安装探针
            Element jitterBuffer = pipelineBuilder.getJitterBuffer();
            Element decoder = pipelineBuilder.getDecoder();
            Element sink = pipelineBuilder.getDisplaySink();
            
            if (jitterBuffer != null) {
                Pad jbSrcPad = jitterBuffer.getSrcPads().get(0);
                probeManager.installBufferProbe(jbSrcPad, "jb_src");
            }
            
            if (decoder != null) {
                Pad decoderSrcPad = decoder.getSrcPads().get(0);
                probeManager.installComprehensiveProbe(decoderSrcPad, "decoder_src");
            }
            
            if (sink != null) {
                Pad sinkPad = sink.getSinkPads().get(0);
                probeManager.installBufferProbe(sinkPad, "sink_input");
            }
            
            System.err.println("✅ 探针设置完成");
            
        } catch (Exception e) {
            System.err.println("❌ 设置探针失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理新帧
     */
    private void handleNewFrame(Buffer buffer) {
        try {
            // 更新最后帧时间
            lastFrameTimeMs = System.currentTimeMillis();
            updateStreamActivity(true);
            
            // 转换为JavaFX Image并显示
            BufferedImage bufferedImage = convertBufferToImage(buffer);
            if (bufferedImage != null) {
                Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);
                lastFrameFx = fxImage;
                
                Platform.runLater(() -> {
                    updateCanvas(canvas, fxImage);
                });
                
                // 如果正在捕获慢放，添加到缓冲区
                if (slowMoCapturing) {
                    addToSlowBuffer(bufferedImage);
                }
                
                // 添加到实时缓冲区
                addToRealtimeBuffer(bufferedImage);
            }
            
        } catch (Exception e) {
            System.err.println("❌ 处理新帧失败: " + e.getMessage());
            performanceMonitor.recordDroppedFrame();
        }
    }
    
    /**
     * 将Buffer转换为BufferedImage
     */
    private BufferedImage convertBufferToImage(Buffer buffer) {
        try {
            // 这里需要实现Buffer到BufferedImage的转换
            // 具体实现取决于buffer的格式（BGRx）
            // 这是一个简化的实现框架
            
            // 使用GstBufferAPI获取buffer大小
            long size = 0;
            try {
                size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
            } catch (Exception e) {
                System.err.println("❌ 获取buffer大小失败: " + e.getMessage());
                return null;
            }
            
            if (size <= 0) {
                return null;
            }
            
            // TODO: 实现实际的转换逻辑
            // 这里应该根据caps信息获取宽度、高度等参数
            // 然后将buffer数据转换为BufferedImage
            
            return null; // 临时返回null，需要实际实现
            
        } catch (Exception e) {
            System.err.println("❌ 转换Buffer到Image失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 更新画布
     */
    private void updateCanvas(Canvas canvas, Image image) {
        if (image != null && canvas.getGraphicsContext2D() != null) {
            double canvasWidth = canvas.getWidth();
            double canvasHeight = canvas.getHeight();
            
            if (canvasWidth > 0 && canvasHeight > 0) {
                canvas.getGraphicsContext2D().clearRect(0, 0, canvasWidth, canvasHeight);
                canvas.getGraphicsContext2D().drawImage(image, 0, 0, canvasWidth, canvasHeight);
            }
        }
    }
    
    /**
     * 添加帧到慢放缓冲区
     */
    private void addToSlowBuffer(BufferedImage image) {
        try {
            if (slowBuffer != null) {
                slowBuffer.push(image);
            }
        } catch (Exception e) {
            System.err.println("❌ 添加帧到慢放缓冲区失败: " + e.getMessage());
        }
    }
    
    /**
     * 添加帧到实时缓冲区
     */
    private void addToRealtimeBuffer(BufferedImage image) {
        try {
            if (realtimeBuffer != null) {
                realtimeBuffer.push(image);
            }
        } catch (Exception e) {
            System.err.println("❌ 添加帧到实时缓冲区失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新流活动状态
     */
    private void updateStreamActivity(boolean active) {
        if (streamActive != active) {
            streamActive = active;
            if (onStreamActiveChanged != null) {
                Platform.runLater(() -> onStreamActiveChanged.accept(active));
            }
            System.err.println("📡 流活动状态: " + (active ? "活跃" : "非活跃"));
        }
    }
    
    /**
     * 启动流活动监控
     */
    private void startStreamActivityMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                boolean shouldBeActive = (currentTime - lastFrameTimeMs) < 3000; // 3秒内有帧则认为活跃
                updateStreamActivity(shouldBeActive);
                
                if (!shouldBeActive && slowMoPlaying) {
                    // 流不活跃但慢放在播放，停止慢放
                    stopSlowMoPlayback();
                    if (onSlowMoStreamStopped != null) {
                        Platform.runLater(onSlowMoStreamStopped);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ 流活动监控错误: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
        
        System.err.println("📡 流活动监控已启动");
    }
    
    /**
     * 重置缓冲区
     */
    private void resetBuffers() {
        try {
            if (slowBuffer != null) {
                slowBuffer.clear();
            }
            if (realtimeBuffer != null) {
                realtimeBuffer.clear();
            }
            System.err.println("🔄 缓冲区已重置");
        } catch (Exception e) {
            System.err.println("❌ 重置缓冲区失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送offer到SRS服务器
     */
    private String postOfferToSRS(String sdpOffer) throws Exception {
        // 这里需要实现与SRS服务器的HTTP通信
        // 简化实现，实际需要根据SRS API规范
        
        String url = String.format("http://%s:%d/rtc/v1/play/", host, apiPort);
        String requestBody = String.format(
            "{\"sdp\":\"%s\",\"streamurl\":\"webrtc://%s/%s/%s\"}",
            jsonEscape(sdpOffer), host, app, stream
        );
        
        // TODO: 实现HTTP POST请求
        // 这里返回模拟的SDP answer
        return "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"; // 简化的SDP answer
    }
    
    /**
     * JSON转义
     */
    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
    
    // 错误处理方法
    private void handleConnectionError(String error) {
        System.err.println("🔥 连接错误处理: " + error);
        // 实现错误恢复逻辑
    }
    
    private void handlePipelineError(String error) {
        System.err.println("🔥 管道错误处理: " + error);
        // 实现管道错误恢复逻辑
    }
    
    private void handleDecodingError(String error) {
        System.err.println("🔥 解码错误处理: " + error);
        // 实现解码错误恢复逻辑
        decoderManager.resetDecoder();
    }
    
    private void handleInitializationError(Exception e) {
        System.err.println("🔥 初始化错误处理: " + e.getMessage());
        // 实现初始化错误处理逻辑
    }
    
    private void handleNewTransceiver(Object transceiver) {
        System.err.println("📡 处理新收发器: " + transceiver);
        // 实现收发器处理逻辑
    }
    
    // 慢放相关方法
    public void startSlowMoCapture() {
        slowMoCapturing = true;
        slowBuffer.clear();
        System.err.println("🎬 开始慢放捕获");
    }
    
    public void stopSlowMoCaptureAndPlay(int factor) {
        slowMoCapturing = false;
        startSlowMoPlayback(factor);
        System.err.println("🎬 停止慢放捕获并开始播放，倍率: " + factor);
    }
    
    public void stopSlowMoCapture() {
        slowMoCapturing = false;
        System.err.println("🎬 停止慢放捕获");
    }
    
    public void startSlowMoPlayback(int factor) {
        if (slowPlayer != null) {
            slowPlayer.stop();
        }
        
        slowPlayer = new SlowMoPlayer(slowBuffer.snapshot(), factor, (image) -> {
            // image参数是javafx.scene.image.Image类型，可以直接使用
            lastSlowFx = image;
            Platform.runLater(() -> updateCanvas(slowCanvas, lastSlowFx));
        });
        
        slowPlayer.start();
        slowMoPlaying = true;
        System.err.println("▶️ 开始慢放播放，倍率: " + factor);
    }
    
    public void stopSlowMoPlayback() {
        if (slowPlayer != null) {
            slowPlayer.stop();
            slowPlayer = null;
        }
        slowMoPlaying = false;
        Platform.runLater(() -> slowCanvas.getGraphicsContext2D().clearRect(0, 0, slowCanvas.getWidth(), slowCanvas.getHeight()));
        System.err.println("⏹️ 停止慢放播放");
    }
    
    // 状态查询方法
    public boolean isSlowMoCapturing() { return slowMoCapturing; }
    public boolean isSlowMoPlaying() { return slowMoPlaying; }
    public boolean isStreamActive() { return streamActive; }
    public boolean isInitialized() { return isInitialized.get(); }
    public boolean isPlaying() { return isPlaying.get(); }
    
    // 设置回调
    public void setOnStreamActiveChanged(java.util.function.Consumer<Boolean> callback) {
        this.onStreamActiveChanged = callback;
    }
    
    public void setOnSlowMoStreamStopped(Runnable callback) {
        this.onSlowMoStreamStopped = callback;
    }
    
    // 性能监控相关
    public PerformanceMonitor.PerformanceStats getCurrentPerformanceStats() {
        return performanceMonitor.getCurrentStats();
    }
    
    public void printPerformanceReport() {
        performanceMonitor.printPerformanceReport();
        probeManager.printAllStats();
    }
    
    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        
        double width = getWidth();
        double height = getHeight();
        
        if (width > 0 && height > 0) {
            canvas.setWidth(width);
            canvas.setHeight(height);
            slowCanvas.setWidth(width);
            slowCanvas.setHeight(height);
            
            // 重绘最后一帧
            if (lastFrameFx != null) {
                updateCanvas(canvas, lastFrameFx);
            }
            if (lastSlowFx != null) {
                updateCanvas(slowCanvas, lastSlowFx);
            }
        }
    }
    
    /**
     * 关闭播放器
     */
    public void shutdown() {
        if (isShutdown.getAndSet(true)) {
            return;
        }
        
        System.err.println("🔄 开始关闭WebRTC播放器...");
        
        try {
            // 停止慢放播放
            stopSlowMoPlayback();
            
            // 停止性能监控
            performanceMonitor.shutdown();
            
            // 清理探针
            probeManager.shutdown();
            
            // 关闭解码器管理器
            decoderManager.shutdown();
            
            // 停止管道
            pipelineBuilder.shutdown();
            
            // 关闭WebRTC连接
            webrtcManager.shutdown();
            
            // 停止帧保存器
            frameSaver.stop();
            
            // 关闭定时器
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            
            System.err.println("✅ WebRTC播放器已完全关闭");
            
        } catch (Exception e) {
            System.err.println("❌ 关闭播放器时出错: " + e.getMessage());
        }
    }
}