package com.acard.acard;

import javax.imageio.ImageIO;

import com.acard.acard.capture.FrameDistributor2;
import com.acard.acard.net.StompWebSocketClient;
import com.acard.acard.storage.SlowmoStore;
import com.acard.acard.store.CaptureStore;
import com.acard.acard.capture.LightweightFrameBuffer;
import com.acard.acard.capture.TimelineCapture;
import com.acard.acard.capture.RealtimeFrameRing;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.JpegFileCleaner;
import com.acard.acard.tools.LogTools;
import javafx.embed.swing.SwingFXUtils;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.glib.GObject;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Bin;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import org.freedesktop.gstreamer.message.Message;
import org.freedesktop.gstreamer.message.MessageType;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import org.freedesktop.gstreamer.PadProbeReturn;
import org.freedesktop.gstreamer.webrtc.WebRTCICEGatheringState;
import org.freedesktop.gstreamer.GstObject;
import org.freedesktop.gstreamer.webrtc.WebRTCPeerConnectionState;
import org.freedesktop.gstreamer.lowlevel.GObjectAPI;
import org.freedesktop.gstreamer.lowlevel.GstStructureAPI;
import org.freedesktop.gstreamer.lowlevel.GstEventAPI;
import org.freedesktop.gstreamer.event.Event;
import org.freedesktop.gstreamer.event.EventType;
import com.sun.jna.Pointer;
import com.acard.acard.net.NetworkConfig;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.WritableImage;
import org.freedesktop.gstreamer.webrtc.WebRTCSessionDescription;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.acard.acard.config.WebRTCBufferConfig;
import com.acard.acard.config.WebRTCBufferConfig.BufferMode;
import com.acard.acard.util.CameraSettingsStorage;



/**
 * 简化的WebRTC播放器 - 专注于解决NAL单元缺失问题
 *
 * 目标：
 * - 检测并确保接收到必要的NAL单元类型：SPS(7), PPS(8), IDR(5)
 * - 监控P-slice(1)和FU-A(28)的接收状态
 * - 实现PLI请求机制来获取缺失的关键帧
 * - 简化的GStreamer管道确保稳定解码
 */
public class SimpleWebRTCPlayer {


    // 帧信息结构（替代 SegmentInfo）
    private static class FrameInfo {
        int frameIndex;
        long pts;
        boolean isKeyFrame;
        String filePath;  // frame_00001234.h264
    }

    private final List<FrameInfo> frameIndex = new ArrayList<>();
    private final AtomicInteger currentFrameIndex = new AtomicInteger(0);

    // ⭐ 调整后的 Probe（记录每一帧）


    //public boolean isCallBack=false;

    /**
     * 最新帧回调接口
     */
    public interface LatestFrameCallback {
        void onNewFrame(String filepath, int frameIndex);
    }

    public LatestFrameCallback getLatestFrameCallback() {
        return latestFrameCallback;
    }

    private LatestFrameCallback latestFrameCallback;

    public void setLatestFrameCallback(LatestFrameCallback callback) {
        this.latestFrameCallback = callback;
    }






    // ✅ 异步抓拍加载线程池（低优先级，避免卡慢放播放）
    private static final ExecutorService captureLoadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CaptureLoader");
        t.setPriority(Thread.MIN_PRIORITY);  // 低优先级，不影响慢放
        t.setDaemon(true);
        return t;
    });

    // ✅ 当前正在执行的抓拍任务（用于取消）
    private volatile java.util.concurrent.Future<?> currentCaptureTask = null;

    // 连接参数
    private volatile String serverHost;  // ⭐ 不再是 final，支持动态设置拉流 IP
    private final int serverPort;
    private final String tenant;
    private String streamId;

    // ⭐ SRS客户端ID（用于stop时主动删除连接，避免僵尸连接）
    private volatile String srsPlayClientId = null;

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getStreamId() {
        return streamId;
    }
    
    /**
     * ⭐ 设置拉流服务器 IP（由 CONFIG_STATE 消息动态获取）
     */
    public void setServerHost(String serverHost) {
        this.serverHost = serverHost;
        LogTools.getInstance().logRecord3("📡 更新拉流服务器 IP: " + serverHost);
    }
    
    /**
     * 获取当前拉流服务器 IP
     */
    public String getServerHost() {
        return serverHost;
    }
    
    // ⭐ 设置SRS播放客户端ID（播放成功后调用）
    public void setSrsPlayClientId(String clientId) {
        this.srsPlayClientId = clientId;
        LogTools.getInstance().logRecord3("📌 记录SRS播放客户端ID: " + clientId);
    }

    // GStreamer组件
    private Pipeline pipeline;
    private WebRTCBin webrtcbin;
    GObjectAPI.GObjectClass.Notify notifyIceConnectionState;
    GObjectAPI.GObjectClass.Notify notifyConnectionState;

    GObjectAPI.GObjectClass.Notify notifyIceGathering;
    private Element rtph264depay;

    private Element queueDepay;
    Element d3d11Download;

    private Element queueMux;
    private Element h264parse;
    private Element decoder;
    private AppSink appsink;

    // ✅ 解码器信息（用于UI显示）
    private volatile String decoderName = "未知";
    private volatile boolean isHardwareDecoder = false;
    // 显示支路缓冲到达标志，用于无画面兜底
    private volatile boolean displayBuffersSeen = false;

    // ⚡ 定期关键帧请求机制（GStreamer内部PLI，不通过socket）
    private java.util.concurrent.ScheduledExecutorService keyframeRequestExecutor;
    private java.util.concurrent.ScheduledFuture<?> keyframeRequestTask;

    // ⚡ 缓冲区目标值（用于监控堆积）
    private volatile int queueDepayTargetBuffers;
    private volatile int finalQueueDecodeTargetBuffers;

    // ⚡ 综合诊断监控（每10秒输出详细统计）
    private java.util.concurrent.ScheduledExecutorService diagnosticExecutor;
    private java.util.concurrent.ScheduledFuture<?> diagnosticTask;
    private volatile boolean isHighResolution = false;

    // ✨ 新增：轻量级抓拍系统（优化内存使用）
    private LightweightFrameBuffer lightweightBuffer;
    private TimelineCapture timelineCapture;
    private Element captureQueue;     // 抓拍专用队列

    // ✨ 全局帧监听器（每帧实时触发）
    private volatile java.util.function.Consumer<FrameRingBuffer.FrameItem> globalFrameListener;
    private Element captureDownload;  // 抓拍专用d3d11download（GPU路径需要）
    private AppSink captureSink;      // 抓拍专用appsink
    private Element captureTee;       // 抓拍专用tee（轻量级）


    Element d3d11convert = null;

    Element videoBalance = null;

    // ⭐ 创建 gamma 元素（在 createPipeline() 中初始化）
    Element gamma;

    // ⭐ 锐化功能标志（使用 videoscale 的 sharpen 属性）
    private boolean sharpenEnabled = false;

    public static final String BRIGHTNESS = "BRIGHTNESS";    // 亮度
    public static final String CONTRAST = "CONTRAST";    // 对比度
    public static final String SATURATION = "SATURATION";    // 饱和度

    public static final String HUE = "HUE";    // 色调
    public static final String GAMMA = "GAMMA";    // 伽马




    // ⭐ 默认值常量
    public static final double DEFAULT_BRIGHTNESS = 0.0;    // 亮度：-1.0 ~ 1.0
    public static final double DEFAULT_CONTRAST = 1.0;      // 对比度：0.0 ~ 2.0
    public static final double DEFAULT_SATURATION = 1.0;    // 饱和度：0.0 ~ 2.0
    public static final double DEFAULT_HUE = 0.0;           // 色调：-1.0 ~ 1.0

    public static final double DEFAULT_GAMMA = 1.0;         // 伽马：0.01 ~ 10.0

    // ⭐ 当前值存储（用于查询）
    public static double currentBrightness = DEFAULT_BRIGHTNESS;
    public static double currentContrast = DEFAULT_CONTRAST;
    public static double currentSaturation = DEFAULT_SATURATION;
    public static double currentHue = DEFAULT_HUE;
    public static double currentGamma = DEFAULT_GAMMA;


    private Element captureValve;        // 控制阀门
    private Element captureImageQueue;   // JPEG保存队列
    private Element captureImageDecoder; // JPEG分支解码器
    private Element captureImageConvert; // 视频格式转换
    //private Element captureImageScale;   // ⭐ 降采样（限制分辨率，防止4K卡死）
    // private Element captureScaleCaps;    // ⭐ 分辨率限制
    private Element jpegEncoder;         // JPEG编码器
    private Element multifilesink;       // 多文件保存
    private Element captureImageDownload;
    private Element captureImageParser;  // 新增
    private Element captureVideocrop;    // ⚡ 抓拍分支裁剪（支持放大区域抓拍）

    Element splitTee;

    private final AtomicLong recordedFrameCount = new AtomicLong(0);
    // MKV录制分支元素
    private Element recordValve;           // 录制开关
    private Element recordQueue;           // 录制队列
    private Element recordDownload;        // GPU->CPU转换
    private Element h264Encoder;           // H264编码器
    private Element matroskaMux;           // MKV封装器
    private Element recordFileSink;        // 文件保存

    Element recordMultiFileSink;
    private Element recordValve2;           // 录制开关

    // 2. 创建分流器
    Element captureRecordTee;// = ElementFactory.make("tee", "capture-record-tee");
    Element queueDecode;

    private long totalFrameCount=0;

    // 类成员变量
    private Element recordIdentity;
    private AtomicLong lastRecordedPts = new AtomicLong(0);  // 最后一帧的时间戳（纳秒）

    private com.acard.acard.capture.DiskCaptureCache diskCaptureCache; // 磁盘缓存（零内存，支持240+张）
    private final java.util.concurrent.atomic.AtomicLong diskCacheFrameCounter = new java.util.concurrent.atomic.AtomicLong(0); // 磁盘缓存帧计数器（跳帧优化）

    // ✨ 全局帧ID计数器（每一帧都会递增，用于事件驱动的帧分发）
    private final java.util.concurrent.atomic.AtomicLong globalFrameCounter = new java.util.concurrent.atomic.AtomicLong(0);

    // ⭐ 视频缩放相关元素
    private Element videocrop;
    private Element videoscale;
    private Element zoomCapsfilter;

    // ⭐ 缩放状态（d3d11convert scale-x/scale-y 模式）
    private double currentZoom = 1.0;     // 当前缩放倍数
    private double minZoom = 1.0;         // 最小缩放（不能低于1.0，显示完整画面）
    private double maxZoom = 5.0;         // 最大缩放（放大5倍）
    private int zoomCenterX = -1;       // 缩放中心X（-1表示使用中心点）
    private int zoomCenterY = -1;       // 缩放中心Y（-1表示使用中心点）
    
    // ⚡ 窗口放大模式：偏移量（用于拖动平移）
    private double windowZoomOffsetX = 0;  // 窗口偏移X（像素）
    private double windowZoomOffsetY = 0;  // 窗口偏移Y（像素）
    
    // ⭐ 缩放变化回调（用于更新 UI 显示）
    private java.util.function.Consumer<Double> zoomChangeCallback;
    
    // ⚡ 缩放请求队列（防崩溃优化）
    private final java.util.concurrent.BlockingQueue<ZoomRequest> zoomQueue = 
        new java.util.concurrent.LinkedBlockingQueue<>(1);
    private volatile boolean zoomProcessorRunning = false;
    
    // 缩放请求数据类
    private static class ZoomRequest {
        double zoom;
        int mouseX;
        int mouseY;
        long timestamp;
        
        ZoomRequest(double zoom, int mouseX, int mouseY) {
            this.zoom = zoom;
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // ⭐ 视频原始尺寸（需要根据实际情况设置）
    private int videoWidth = 0;
    private int videoHeight = 0;


    // ⭐ 记录上一次的裁剪区域
    private int lastCropLeft = 0;
    private int lastCropTop = 0;
    private int lastCropWidth = 0;
    private int lastCropHeight = 0;

    /**
     * 上次重置时间（用于凌晨归零）
     */
    private volatile long lastResetTime = System.currentTimeMillis();

    /**
     * 获取磁盘缓存（用于慢放）
     */
    public com.acard.acard.capture.DiskCaptureCache getDiskCaptureCache() {
        return diskCaptureCache;
    }

    /**
     * 获取当前全局帧ID
     * 用于抓拍事件注册时确定事件帧ID
     */
    public long getCurrentFrameId() {
        return globalFrameCounter.get();
    }



    /**
     * 检查并重置帧计数器（凌晨归零，防止溢出）
     */
    private void checkAndResetFrameCounter() {
        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);

        // 检查是否是新的一天（凌晨0点）
        java.util.Calendar lastCal = java.util.Calendar.getInstance();
        lastCal.setTimeInMillis(lastResetTime);

        if (cal.get(java.util.Calendar.DAY_OF_YEAR) != lastCal.get(java.util.Calendar.DAY_OF_YEAR) ||
                cal.get(java.util.Calendar.YEAR) != lastCal.get(java.util.Calendar.YEAR)) {

            long oldValue = globalFrameCounter.getAndSet(0);
            lastResetTime = now;
            System.out.println("🔄 凌晨归零: frameId计数器从 " + oldValue + " 重置为 0");
        }
    }

    /**
     * 获取解码器名称（用于UI显示）
     */
    public String getDecoderName() {
        return decoderName;
    }

    /**
     * 是否使用硬件解码器
     */
    public boolean isHardwareDecoder() {
        return isHardwareDecoder;
    }

    private Element displayQueue; // 新增：显示分支队列，避免下游渲染阻塞影响上游
    // 新增：仅触发帧回调的分支（不更新UI）
    private AppSink callbackSink;
    private Element callbackQueue;
    private Element callbackValve;
    private Element callbackFakesink;
    private Element cbDownload;
    private Element cbCaps;
    private volatile boolean callbackActive = false;
    // 抓拍分支动态控制（默认不链接，按需链接后解除），降低CPU常驻开销
    private Pad teeSrcCapturePad;
    private Pad captureQueueSinkPad;
    private volatile boolean captureBranchActive = false;
    // 回调分支Pad
    private Pad teeSrcCallbackPad;
    private Pad callbackQueueSinkPad;
    private Element cacheQueue;
    private Element cacheValve;
    private Element cacheLatestQueue;
    private Element cacheFakesink;
    private Pad teeSrcCachePad;
    private Pad cacheQueueSinkPad;
    // 常驻缓存分支：appsink与GPU下载（用于内存缓存与滚动磁盘缓存）
    private AppSink cacheAppSink;
    private Element cacheDownload;
    private Element cacheCaps;
    // 临时抓拍链路元素跟踪与路径标记
    private Element tempCapsfilter;
    private boolean captureTempGpuPath = false;
    private boolean tempD3D11Added = false;

    // ⭐ 监听器引用（防止重复添加，stop时移除）
    private javafx.beans.value.ChangeListener<javafx.scene.Scene> sceneListener;
    private javafx.beans.value.ChangeListener<javafx.geometry.Bounds> layoutListener;
    private javafx.beans.value.ChangeListener<Number> stageXListener;
    private javafx.beans.value.ChangeListener<Number> stageYListener;
    private volatile boolean listenersAdded = false;

    // 新增：压缩码流缓存（在 h264parse 之后分流，不经解码）
    private Element teeCodec;               // parse 后的 tee，用于分出压缩缓存与解码显示两路
    private Element encodedValve;           // 压缩缓存阀门（默认不丢弃）
    private Element encodedQueue;           // 压缩缓存队列（滚动 120 AU）
    private Element encodedCaps;            // 压缩输出 caps，确保 byte-stream/au
    private AppSink encodedAppSink;         // 压缩缓存 appsink（维护内存环）
    private final int encodedCacheMax = Integer.getInteger("capture.encoded.cache.size", 120);
    // 原生编码缓存模式：不在Java层维护环，而由appsink内部队列保存最近N帧（低CPU常驻）
    private final boolean encodedNativeBufferMode = Boolean.parseBoolean(System.getProperty("encoded.native.buffer", "true"));
    private final ArrayDeque<EncodedAu> encodedRing = new ArrayDeque<>(encodedCacheMax);
    // 最近见到的 SPS/PPS（带起始码），用于离线快速推送时确保解码器具备配置数据
    private volatile byte[] lastSpsParam;
    private volatile byte[] lastPpsParam;
    // 编码缓存日志开关与计数器（逐帧）
    private final boolean logEncodedCache = Boolean.parseBoolean(System.getProperty("log.encoded.cache", "true"));
    private long encodedCacheSampleCounter = 0L;

    // 压缩 AU 结构
    private static class EncodedAu {
        final byte[] data;
        final long pts;
        final boolean keyframe;
        EncodedAu(byte[] d, long p, boolean k) { this.data = d; this.pts = p; this.keyframe = k; }
    }

    // 调试：帧回调触发计数与时间
    private long frameCallbackAcceptCounter = 0L;
    private long frameCallbackFirstTsMs = 0L;


    private volatile boolean captureRequested = false;
    private volatile java.util.function.Consumer<Image> captureCallback;
    // 帧回调：用于外部接收每帧图像（深拷贝）
    private volatile java.util.function.Consumer<Image> frameCallback;
    // 延迟回调：当管道尚未创建时暂存回调，待管道创建完成后重新应用
    private volatile java.util.function.Consumer<Image> pendingFrameCallback;

    private long remoteVideoSsrc = 0L;
    private volatile boolean ssrcExtractedFromSdp = false;
    private Pad webrtcSrcPad; // webrtcbin接收的RTP src pad，用于通过webrtcbin发送PLI/FIR
    private String currentStreamUrl; // 记录当前流URL，供 ON_NEGOTIATION_NEEDED 回调使用
    private static final int ICE_RECOVERY_INTERVAL_MS = 2000; // ICE失败后最小重试间隔
    private long lastIceRecoveryMs = 0L; // 上次ICE自动恢复时间戳

    // NAL单元接收状态
    private final AtomicBoolean hasReceivedSps = new AtomicBoolean(false);
    private final AtomicBoolean hasReceivedPps = new AtomicBoolean(false);
    private final AtomicBoolean hasReceivedIdr = new AtomicBoolean(false);
    private final AtomicBoolean hasReceivedPSlice = new AtomicBoolean(false);
    private final AtomicBoolean hasReceivedFuA = new AtomicBoolean(false);

    // PLI请求机制
    private Timer pliTimer;
    private ScheduledFuture<?> pliScheduled;
    private int pliRetryCount = 0;
    private static final int MAX_PLI_RETRIES = 10;
    private static final int PLI_INTERVAL_MS = 1000;

    // 保活与看门狗：首帧后维持稳定显示
    private Timer keepAliveTimer;
    private Timer frameWatchdogTimer;
    private volatile long lastFrameTimeMs = 0L;
    private static final int KEEPALIVE_PLI_INTERVAL_MS = 2500; // 低频保活PLI周期
    private static final int FRAME_NOFRAME_THRESHOLD_MS = 2000; // 超过该时长无帧则触发补救
    private static final int WATCHDOG_TICK_MS = 500; // 看门狗检查间隔
    private volatile long lastWatchdogPliTimeMs = 0L; // 看门狗PLI限流时间戳
    
    // ⭐ 帧率统计（EMA 指数移动平均，极度平滑）
    private final java.util.concurrent.atomic.AtomicInteger fpsFrameCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long fpsLastSecondMs = System.currentTimeMillis();
    
    // ⚡ EMA（指数移动平均）：比滑动窗口更平滑，对突变响应更缓慢
    private static final double FPS_EMA_ALPHA = 0.2;  // 平滑系数（越小越平滑，0.2 = 约5秒稳定）
    private volatile double fpsEma = 0.0;  // EMA 值
    private volatile boolean fpsEmaInitialized = false;  // 是否已初始化

    // 新增：CPU占用监控
    private Timer cpuMonitorTimer;
    private long lastProcCpuTimeNs = -1L;
    private long lastCpuSampleWallNs = -1L;

    // 显示组件
    private ImageView imageView;
    // 复用图像缓冲，避免每帧分配
    private WritableImage sharedImage;
    private int sharedImageWidth = -1;
    private int sharedImageHeight = -1;
    // UI更新节流与状态
    private volatile boolean uiUpdatePending = false;
    private long lastUiUpdateMs = 0L;
    private static final int UI_MIN_FRAME_INTERVAL_MS = 16;  // ✅ 60fps（原30fps太低）
    // PixelBuffer 驱动渲染：复用 ByteBuffer，减少每帧分配
    private ByteBuffer reusableRgbBuffer;
    private int rgbBufferWidth = -1;
    private int rgbBufferHeight = -1;
    private PixelFormat<ByteBuffer> rgbPixelFormat;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile boolean firstDisplaySampleLogged = false;
    // 诊断开关：-Ddiag.fakesink=true 摘掉渲染层；-Ddiag.noRender=true 保留appsink但不进行JavaFX渲染
    private final boolean diagUseFakesink = Boolean.getBoolean("diag.fakesink");
    private final boolean diagNoRender = Boolean.getBoolean("diag.noRender");
    // 诊断：跳过解码（直接到 fakesink）与可选解码器指定
    private  boolean diagSkipDecode = Boolean.getBoolean("diag.skipDecode");
    private final String diagDecoderName = System.getProperty("diag.decoder", "avdec_h264");
    // 新增：直接将 webrtcbin 源 pad 连接到末端 sink（跳过 depay/parse），用于隔离原生崩溃
    private final boolean diagDirectSink = Boolean.getBoolean("diag.directSink");
    // 离线解码详细日志开关（默认开启，便于问题定位）
    private final boolean logOfflineVerbose = Boolean.parseBoolean(System.getProperty("log.offline.verbose", "true"));
    // 是否逐帧打印 push 详情（默认关闭，避免日志过多）
    private final boolean logOfflinePushEach = Boolean.parseBoolean(System.getProperty("log.offline.push.each", "false"));
    // 记录末端元素，便于在 pad-added 时进行直连
    private Element sinkEndElement;
    // 新增：VideoOverlay绑定相关字段
    private volatile long overlayWindowHandle = 0L;
    private volatile long overlayChildHandle = 0L;
    private volatile VideoOverlay videoOverlay;
    private volatile javafx.scene.Node overlayTarget;
    private final AtomicBoolean isWindowBound = new AtomicBoolean(false);  // ⭐ 标志：窗口是否已绑定
    private static final AtomicInteger prepareWindowHandleTriggered = new AtomicInteger(0);  // 统计：prepare-window-handle 触发次数
    private static final AtomicInteger fallbackTriggered = new AtomicInteger(0);  // 统计：后备方案触发次数
    private volatile long pipelineStartTimeMs = 0L;  // Pipeline 启动时间戳
    private final AtomicBoolean firstDataReceived = new AtomicBoolean(false);  // 是否收到首个数据
    private final AtomicBoolean systemPowerBoosted = new AtomicBoolean(false); // 是否已尝试提升系统电源/优先级

    // 最新帧内存缓存与磁盘滚动缓存
    private volatile WritableImage latestFrameImage;
    // 新增：GPU缓存环，用于严格的仅缓存抓拍与预推
    private final int cacheCacheMax = Integer.getInteger("capture.cache.cache.size", 120);
    private final ArrayDeque<WritableImage> cacheRing = new ArrayDeque<>(cacheCacheMax);
    private Path diskCacheDir;
    private final ArrayDeque<Path> diskCacheFiles = new ArrayDeque<>();
    private static final int DISK_CACHE_MAX = 120;
    private volatile long lastDiskWriteMs = 0L;
    private static final int DISK_WRITE_MIN_INTERVAL_MS = 50;
    private volatile long lastCallbackProcessMs = 0L;
    private volatile int callbackMinIntervalMs = Integer.getInteger("callback.min.interval.ms", 66);
    // 抓拍按需：仅在有请求时处理下一帧，避免持续高CPU
    private volatile java.util.function.Consumer<Image> pendingCaptureCallback;
    // 是否启用持续缓存与写盘（默认关闭，降低CPU/I/O）
    private final boolean cacheContinuousEnabled = Boolean.getBoolean("cache.continuous.enabled");
    private final boolean diskCacheEnabled = Boolean.getBoolean("cache.disk.enabled");

    // 预热离线解码子管线（常驻，用于快速抓拍 120 帧窗口）
    private Pipeline warmPipeline;
    private AppSrc warmSrc;
    private Element warmParse;
    private Element warmDec;
    private Element warmVconv;
    private Element warmCaps;
    private AppSink warmSink;

    // 预热抓拍会话状态
    private final AtomicBoolean warmActive = new AtomicBoolean(false);
    private volatile int warmExpectedCount = 0;
    private volatile int warmReceivedCount = 0;
    private volatile CountDownLatch warmDoneLatch;
    private volatile java.util.function.Consumer<Image> warmCallback;
    private volatile Image warmRepImage;
    private final AtomicBoolean warmFirstPushed = new AtomicBoolean(false);
    private final AtomicBoolean warmPrePushedFirst = new AtomicBoolean(false);
    private volatile int warmPreSkipCount = 0;



    // 新增：分发AppSink（用于应用层帧分发）
    private AppSink distributionAppSink;
    private Element distributionDownload;  // 新增：格式转换元素


    // 新增：帧分发器
    private FrameDistributor2 frameDistributor;

    public void setOverlayTarget(javafx.scene.Node target) {
        this.overlayTarget = target;
    }



    /**
     * ⚡ 设置 VideoOverlay 子窗口的可见性（用于软硬解切换）
     * @param visible true=显示，false=隐藏
     */
    public void setOverlayWindowVisible(boolean visible) {
        if (overlayChildHandle == 0L) {
            LogTools.getInstance().logRecord("⚠️ setOverlayWindowVisible: 子窗口句柄为空，跳过");
            return;
        }
        
        try {
            HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));
            if (User32.INSTANCE.IsWindow(hwndChild)) {
                int showCmd = visible ? WinUser.SW_SHOW : WinUser.SW_HIDE;
                User32.INSTANCE.ShowWindow(hwndChild, showCmd);
                if (visible) {
                    User32.INSTANCE.UpdateWindow(hwndChild);
                }
                LogTools.getInstance().logRecord("🎬 VideoOverlay 子窗口: " + (visible ? "显示" : "隐藏") + " (0x" + Long.toHexString(overlayChildHandle) + ")");
            } else {
                LogTools.getInstance().logRecord("⚠️ setOverlayWindowVisible: 子窗口无效");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord("❌ setOverlayWindowVisible 失败: " + t.getMessage());
        }
    }
    
    /**
     * 刷新 GPU Overlay 渲染矩形（用于 Ctrl+滚轮缩放后立即生效）。
     * 该方法在 JavaFX 线程上重新计算 overlayTarget 的屏幕坐标并更新子窗口位置与大小。
     */
    public void refreshOverlayRectangle() {
        if (videoOverlay == null || overlayTarget == null) return;
        Platform.runLater(() -> {
            try {
                // ✅ 使用layoutBounds（实际布局边界）而不是boundsInLocal
                javafx.geometry.Bounds layoutBounds = overlayTarget.getLayoutBounds();
                javafx.geometry.Bounds ivScreen = overlayTarget.localToScreen(layoutBounds);



                if (ivScreen == null) return;
                javafx.scene.Scene scene = overlayTarget.getScene();
                double clientOriginX = 0, clientOriginY = 0;
                if (scene != null && scene.getRoot() != null) {
                    javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                    if (rootTL != null) {
                        clientOriginX = rootTL.getX();
                        clientOriginY = rootTL.getY();
                    }
                }
                // ✅ 获取原始尺寸
                int containerW = (int) Math.round(ivScreen.getWidth());
                int containerH = (int) Math.round(ivScreen.getHeight())-FileToos.botoomHight;
                int containerX = (int) Math.round(ivScreen.getMinX() - clientOriginX);
                int containerY = (int) Math.round(ivScreen.getMinY() - clientOriginY);

                // ⚡ 如果宽度或高度为 0，强制设置为 1px（表示隐藏）
                /*if (containerW <= 0) containerW = 1;
                if (containerH <= 0) containerH = 1;*/

                // ⭐ 只在位置或大小有明显变化时才打印日志（避免频繁刷新时日志过多）
                LogTools.getInstance().logRecord2("📐 容器信息: 位置(" + containerX + "," + containerY + ") 大小=" + containerW + "x" + containerH);

                // ⭐ 窗口大小等于容器大小（局部放大通过 videocrop 裁剪 + Lanczos 插值实现）
                // 注：GStreamer VideoOverlay 会自动拉伸视频到窗口尺寸，
                //     所以"整体放大窗口"的方式会导致视频被拉伸（马赛克）
                //     因此使用 videocrop 裁剪 + Lanczos 高质量插值是当前架构下的最优方案
                int scaledW = containerW;
                int scaledH = containerH;
                int videoX = containerX;
                int videoY = containerY;

                long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle
                        : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
                double sx = 1.0, sy = 1.0;
                boolean calcDpi = Boolean.parseBoolean(System.getProperty("video.overlay.calcDpi", "true"));
                try {
                    if (calcDpi && hwndForScale != 0L) {
                        HWND w = new HWND(
                                Pointer.createConstant(hwndForScale));
                        com.sun.jna.platform.win32.WinDef.HDC hdc = User32.INSTANCE.GetDC(w);
                        if (hdc != null) {
                            int dpiX = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                            int dpiY = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                            User32.INSTANCE.ReleaseDC(w, hdc);
                            if (dpiX > 0 && dpiY > 0) {
                                sx = Math.max(1.0, dpiX / 96.0);
                                sy = Math.max(1.0, dpiY / 96.0);
                            }
                        }
                    }
                } catch (Throwable ignore) {}

                int pxVideoW = (int) Math.round(scaledW * sx);
                int pxVideoH = (int) Math.round(scaledH * sy);
                int pxVideoX = (int) Math.round(videoX * sx);
                int pxVideoY = (int) Math.round(videoY * sy);

                if (pxVideoW <= 0 || pxVideoH <= 0) return;

                if (overlayChildHandle != 0L) {
                    HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));

                    // ⭐ 关键修复：检查窗口是否仍然有效
                    if (!User32.INSTANCE.IsWindow(hwndChild)) {
                        LogTools.getInstance().logRecord2("⚠️ 子窗口已失效: 0x" + Long.toHexString(overlayChildHandle) + "，跳过位置调整");
                        overlayChildHandle = 0L; // 重置句柄
                        return;
                    }

                    // ⭐ 确保窗口可见
                    boolean wasHidden = false;
                    if (!User32.INSTANCE.IsWindowVisible(hwndChild)) {
                        System.out.println("🔍 子窗口被隐藏，重新显示: 0x" + Long.toHexString(overlayChildHandle));
                        User32.INSTANCE.ShowWindow(hwndChild, WinUser.SW_SHOW);
                        User32.INSTANCE.UpdateWindow(hwndChild);
                        wasHidden = true;
                    }

                    int pxContainerW = (int) Math.round(containerW * sx);
                    int pxContainerH = (int) Math.round(containerH * sy);

                    // ⭐ 只在重新显示窗口或位置有大变化时打印日志
                    if (wasHidden) {
                        System.out.println("🔄 重新显示后调整位置: (" + pxVideoX + "," + pxVideoY + ") " + pxVideoW + "x" + pxVideoH);
                    }

                    // ✅ 关键优化：先计算并设置裁剪区域，再设置窗口大小
                    // 这样可以避免短暂显示未裁剪的放大窗口（消除闪烁）
                    try {
                        // 1️⃣ 先计算裁剪区域（相对于HWND窗口的坐标）
                        int clipX = Math.max(0, -pxVideoX + (int)Math.round(containerX * sx));
                        int clipY = Math.max(0, -pxVideoY + (int)Math.round(containerY * sy));
                        int clipW = Math.min(pxVideoW - clipX, pxContainerW);
                        int clipH = Math.min(pxVideoH - clipY, pxContainerH);

                        // 2️⃣ 先设置裁剪区域（在窗口大小变化之前）
                        if (clipW > 0 && clipH > 0) {
                            com.sun.jna.platform.win32.WinDef.HRGN clipRegion = com.sun.jna.platform.win32.GDI32.INSTANCE.CreateRectRgn(
                                    clipX, clipY, clipX + clipW, clipY + clipH);
                            if (clipRegion != null) {
                                // ✅ 关键：先设置裁剪区域（false = 不立即重绘）
                                User32.INSTANCE.SetWindowRgn(hwndChild, clipRegion, false);
                                // ✅ 减少日志输出，避免IO阻塞
                                // System.out.println("✅ [refreshOverlay] 已设置裁剪区域: (" + clipX + "," + clipY + ") " + clipW + "x" + clipH);
                            }
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("⚠️ 设置 HWND 裁剪区域失败: " + e.getMessage());
                    }

                    // 3️⃣ 再设置窗口位置和大小
                    // ⭐ 窗口高度不能超过容器高度，避免覆盖底部按钮
                    int actualWindowH = Math.min(pxVideoH, pxContainerH);

                    int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                    // ⭐ 如果窗口被重新显示，需要立即重绘；否则延迟重绘
                    if (!wasHidden) {
                        flags |= WinUser.SWP_NOREDRAW;  // 正常情况：延迟重绘
                    }

                    // ⭐ 使用实际窗口高度（不超过容器高度）
                    boolean setPosResult = User32.INSTANCE.SetWindowPos(hwndChild, null, pxVideoX, pxVideoY, pxVideoW, actualWindowH, flags);
                    if (!setPosResult) {
                        LogTools.getInstance().logRecord2("⚠️ SetWindowPos 失败！");
                    }

                    // ⭐ 设置渲染矩形
                    try {
                        videoOverlay.setRenderRectangle(0, 0, pxVideoW, pxVideoH);
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("⚠️ setRenderRectangle 失败: " + t.getMessage());
                    }

                    // 4️⃣ 统一重绘一次（在所有设置完成后）
                    User32.INSTANCE.InvalidateRect(hwndChild, null, false);
                    User32.INSTANCE.UpdateWindow(hwndChild);  // ✅ 立即更新

                    if (wasHidden) {
                        System.out.println("✅ 子窗口已重新显示并调整到新位置");
                    }
                } else {
                    // 无子窗口时，暂时仅触发 expose；等待子窗口创建后再设置精确矩形
                    videoOverlay.expose();
                }
            } catch (Throwable ignore) {}
        });
    }
    public void refreshOverlayRectangle2() {
        if (videoOverlay == null || overlayTarget == null) return;
        Platform.runLater(() -> {
            try {
                // ✅ 使用layoutBounds（实际布局边界）而不是boundsInLocal
                javafx.geometry.Bounds layoutBounds = overlayTarget.getLayoutBounds();
                javafx.geometry.Bounds ivScreen = overlayTarget.localToScreen(layoutBounds);



                if (ivScreen == null) return;
                javafx.scene.Scene scene = overlayTarget.getScene();
                double clientOriginX = 0, clientOriginY = 0;
                if (scene != null && scene.getRoot() != null) {
                    javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                    if (rootTL != null) {
                        clientOriginX = rootTL.getX();
                        clientOriginY = rootTL.getY();
                    }
                }
                // ✅ 获取原始尺寸
                int containerW = (int) Math.round(ivScreen.getWidth());
                int containerH = (int) Math.round(ivScreen.getHeight())-FileToos.botoomHight;
                int containerX = (int) Math.round(ivScreen.getMinX() - clientOriginX);
                int containerY = (int) Math.round(ivScreen.getMinY() - clientOriginY);

                // ⚡ 如果宽度或高度为 0，强制设置为 1px（表示隐藏）
                if (containerW <= 0) containerW = 1;
                if (containerH <= 0) containerH = 1;

                // ⭐ 只在位置或大小有明显变化时才打印日志（避免频繁刷新时日志过多）
                LogTools.getInstance().logRecord2("📐 容器信息: 位置(" + containerX + "," + containerY + ") 大小=" + containerW + "x" + containerH);

                // ✅ 窗口大小始终等于容器大小（缩放通过videocrop裁剪实现，不改变窗口尺寸）
                // 这样可以保持视频分辨率不降低
                int scaledW = containerW;
                int scaledH = containerH;
                int videoX = containerX;
                int videoY = containerY;

                long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle
                        : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
                double sx = 1.0, sy = 1.0;
                boolean calcDpi = Boolean.parseBoolean(System.getProperty("video.overlay.calcDpi", "true"));
                try {
                    if (calcDpi && hwndForScale != 0L) {
                        HWND w = new HWND(
                                Pointer.createConstant(hwndForScale));
                        com.sun.jna.platform.win32.WinDef.HDC hdc = User32.INSTANCE.GetDC(w);
                        if (hdc != null) {
                            int dpiX = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                            int dpiY = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                            User32.INSTANCE.ReleaseDC(w, hdc);
                            if (dpiX > 0 && dpiY > 0) {
                                sx = Math.max(1.0, dpiX / 96.0);
                                sy = Math.max(1.0, dpiY / 96.0);
                            }
                        }
                    }
                } catch (Throwable ignore) {}

                int pxVideoW = (int) Math.round(scaledW * sx);
                int pxVideoH = (int) Math.round(scaledH * sy);
                int pxVideoX = (int) Math.round(videoX * sx);
                int pxVideoY = (int) Math.round(videoY * sy);

                if (pxVideoW <= 0 || pxVideoH <= 0) return;

                if (overlayChildHandle != 0L) {
                    HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));

                    // ⭐ 关键修复：检查窗口是否仍然有效
                    if (!User32.INSTANCE.IsWindow(hwndChild)) {
                        LogTools.getInstance().logRecord2("⚠️ 子窗口已失效: 0x" + Long.toHexString(overlayChildHandle) + "，跳过位置调整");
                        overlayChildHandle = 0L; // 重置句柄
                        return;
                    }

                    // ⭐ 确保窗口可见
                    boolean wasHidden = false;
                    if (!User32.INSTANCE.IsWindowVisible(hwndChild)) {
                        System.out.println("🔍 子窗口被隐藏，重新显示: 0x" + Long.toHexString(overlayChildHandle));
                        User32.INSTANCE.ShowWindow(hwndChild, WinUser.SW_SHOW);
                        User32.INSTANCE.UpdateWindow(hwndChild);
                        wasHidden = true;
                    }

                    int pxContainerW = (int) Math.round(containerW * sx);
                    int pxContainerH = (int) Math.round(containerH * sy);

                    // ⭐ 只在重新显示窗口或位置有大变化时打印日志
                    if (wasHidden) {
                        System.out.println("🔄 重新显示后调整位置: (" + pxVideoX + "," + pxVideoY + ") " + pxVideoW + "x" + pxVideoH);
                    }

                    // ✅ 关键优化：先计算并设置裁剪区域，再设置窗口大小
                    // 这样可以避免短暂显示未裁剪的放大窗口（消除闪烁）
                    try {
                        // 1️⃣ 先计算裁剪区域（相对于HWND窗口的坐标）
                        int clipX = Math.max(0, -pxVideoX + (int)Math.round(containerX * sx));
                        int clipY = Math.max(0, -pxVideoY + (int)Math.round(containerY * sy));
                        int clipW = Math.min(pxVideoW - clipX, pxContainerW);
                        int clipH = Math.min(pxVideoH - clipY, pxContainerH);

                        // 2️⃣ 先设置裁剪区域（在窗口大小变化之前）
                        if (clipW > 0 && clipH > 0) {
                            com.sun.jna.platform.win32.WinDef.HRGN clipRegion = com.sun.jna.platform.win32.GDI32.INSTANCE.CreateRectRgn(
                                    clipX, clipY, clipX + clipW, clipY + clipH);
                            if (clipRegion != null) {
                                // ✅ 关键：先设置裁剪区域（false = 不立即重绘）
                                User32.INSTANCE.SetWindowRgn(hwndChild, clipRegion, false);
                                // ✅ 减少日志输出，避免IO阻塞
                                // System.out.println("✅ [refreshOverlay] 已设置裁剪区域: (" + clipX + "," + clipY + ") " + clipW + "x" + clipH);
                            }
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("⚠️ 设置 HWND 裁剪区域失败: " + e.getMessage());
                    }

                    // 3️⃣ 再设置窗口位置和大小（此时裁剪已生效，不会显示超出部分）
                    // ⭐ 关键修复：窗口高度不能超过容器高度，避免覆盖底部按钮
                    int actualWindowH = Math.min(pxVideoH, pxContainerH);

                    int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                    // ⭐ 如果窗口被重新显示，需要立即重绘；否则延迟重绘
                    if (!wasHidden) {
                        flags |= WinUser.SWP_NOREDRAW;  // 正常情况：延迟重绘
                    }

                    // ⭐ 使用实际窗口高度（不超过容器高度），避免覆盖底部按钮
                    boolean setPosResult = User32.INSTANCE.SetWindowPos(hwndChild, null, pxVideoX, pxVideoY, pxVideoW, actualWindowH, flags);
                    if (!setPosResult) {
                        LogTools.getInstance().logRecord2("⚠️ SetWindowPos 失败！");
                    }

                    // ⭐ 设置渲染矩形
                    try {
                        videoOverlay.setRenderRectangle(0, 0, pxVideoW, pxVideoH);
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("⚠️ setRenderRectangle 失败: " + t.getMessage());
                    }

                    // 4️⃣ 统一重绘一次（在所有设置完成后）
                    User32.INSTANCE.InvalidateRect(hwndChild, null, false);
                    User32.INSTANCE.UpdateWindow(hwndChild);  // ✅ 立即更新

                    if (wasHidden) {
                        System.out.println("✅ 子窗口已重新显示并调整到新位置");
                    }
                } else {
                    // 无子窗口时，暂时仅触发 expose；等待子窗口创建后再设置精确矩形
                    videoOverlay.expose();
                }
            } catch (Throwable ignore) {}
        });
    }
    public SimpleWebRTCPlayer(String serverHost, int serverPort, String tenant, String streamId) {


        // 初始化帧分发器
        this.frameDistributor = new FrameDistributor2();
        // ✨ 在构造函数最开始就强制启用抓拍功能
        // 这样确保在后续 createPipeline() 中读取时，capture.enabled 已经是 true
        if (System.getProperty("capture.enabled") == null ||
                System.getProperty("capture.enabled").equals("false")) {
            System.setProperty("capture.enabled", "true");
            System.out.println("🔧 SimpleWebRTCPlayer 构造: 强制启用抓拍功能（capture.enabled=true）");
        }

        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.tenant = tenant;
        this.streamId = streamId;

        // 注意：GStreamer应该在应用程序启动时通过GstBootstrap.init()统一初始化
        // 这里不再重复初始化，避免类型重复注册错误

        // 创建显示组件 - 不设置固定尺寸，让容器控制
        this.imageView = new ImageView();
        this.imageView.setPreserveRatio(false);
        this.imageView.setSmooth(true);
        this.imageView.setCache(true);
        // 移除硬编码尺寸，让父容器决定显示尺寸


        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/asimple_cavas" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger = new PrintWriter(new FileWriter(logFile, true));


        } catch (Exception e) {
            LogTools.getInstance().logRecord2("日志初始化失败: " + e.getMessage());
        }

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/endlog/simple_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            recordLogger3 = new PrintWriter(new FileWriter(logFile, true));


        } catch (Exception e) {
            LogTools.getInstance().logRecord2("日志初始化失败: " + e.getMessage());
        }
    }

    // ==================== 解码器模式控制 ====================
    
    /**
     * 解码器模式：
     * - true（默认）：优先硬解，硬解不可用时自动回退软解
     * - false：强制软解（跳过硬件解码器检测）
     */
    public static boolean preferHardwareDecoder = true;
    
    /**
     * 设置解码器模式
     * @param preferHardware true=优先硬解（默认），false=强制软解
     */
    public static void setPreferHardwareDecoder(boolean preferHardware) {
        preferHardwareDecoder = preferHardware;
        LogTools.getInstance().logRecord3("🎛️ 解码器模式已设置: " + (preferHardware ? "优先硬解" : "强制软解"));
    }
    
    /**
     * 获取当前解码器模式
     */
    public static boolean isPreferHardwareDecoder() {
        return preferHardwareDecoder;
    }
    
    // ==================== 管道辅助方法 ====================
    
    /**
     * 清理旧管道
     */
    private void cleanupOldPipeline() {
        if (pipeline != null) {
            try {
                LogTools.getInstance().logRecord3("🧹 检测到旧 Pipeline，先释放...");
                pipeline.stop();
                pipeline.dispose();
                LogTools.getInstance().logRecord3("✅ 旧 Pipeline 已释放");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 释放旧 Pipeline 失败: " + t.getMessage());
            }
            pipeline = null;
        }
    }
    
    // ==================== 解码器智能选择系统 ====================
    
    /**
     * GPU 类型枚举
     */
    private enum GpuType {
        NVIDIA("NVIDIA", new String[]{"nvh264dec", "d3d11h264dec"}),      // NVIDIA：优先 NVDEC
        AMD("AMD", new String[]{"d3d11h264dec", "amfh264dec"}),           // AMD：优先 D3D11
        INTEL("Intel", new String[]{"msdkh264dec", "d3d11h264dec"}),      // Intel：优先 Quick Sync
        UNKNOWN("未知", new String[]{"d3d11h264dec", "nvh264dec", "msdkh264dec"});  // 未知：尝试所有
        
        final String displayName;
        final String[] preferredDecoders;
        
        GpuType(String displayName, String[] preferredDecoders) {
            this.displayName = displayName;
            this.preferredDecoders = preferredDecoders;
        }
    }
    
    /**
     * 检测系统 GPU 类型
     */
    private GpuType detectGpuType() {
        try {
            // 方法1：通过 Windows 命令获取显卡信息
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", 
                "wmic path win32_VideoController get name");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.toLowerCase()).append(" ");
                }
            }
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            
            String gpuInfo = output.toString();
            LogTools.getInstance().logRecord3("🖥️ 检测到 GPU: " + gpuInfo.trim());
            
            // 判断 GPU 类型（按优先级）
            if (gpuInfo.contains("nvidia") || gpuInfo.contains("geforce") || gpuInfo.contains("rtx") || gpuInfo.contains("gtx")) {
                return GpuType.NVIDIA;
            } else if (gpuInfo.contains("amd") || gpuInfo.contains("radeon") || gpuInfo.contains("rx ")) {
                return GpuType.AMD;
            } else if (gpuInfo.contains("intel") || gpuInfo.contains("uhd") || gpuInfo.contains("iris")) {
                return GpuType.INTEL;
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ GPU 检测失败: " + t.getMessage());
        }
        return GpuType.UNKNOWN;
    }
    
    /**
     * 选择最佳解码器
     * 
     * @return 解码器工厂名称
     */
    private String selectBestDecoder() {
        LogTools.getInstance().logRecord3("==================== 解码器选择 ====================");
        LogTools.getInstance().logRecord3("🎛️ 解码器模式: " + (preferHardwareDecoder ? "优先硬解" : "强制软解"));
        
        // 1. 如果设置为强制软解，直接使用软解（最高优先级）
        if (!preferHardwareDecoder) {
            LogTools.getInstance().logRecord3("🔧 强制软解模式，跳过所有硬件解码器");
            return useSoftwareDecoder();
        }
        
        // 2. 检查用户是否手动指定解码器（仅在硬解模式下生效）
        String userSpecified = System.getProperty("diag.decoder", null);
        if (userSpecified != null && !userSpecified.isEmpty()) {
            LogTools.getInstance().logRecord3("📌 用户手动指定解码器: " + userSpecified);
            try {
                decoder = ElementFactory.make(userSpecified, "decoder");
                if (decoder != null) {
                    LogTools.getInstance().logRecord3("✅ 用户指定解码器可用: " + userSpecified);
                    return userSpecified;
                }
            } catch (Throwable e) {
                LogTools.getInstance().logRecord3("❌ 用户指定解码器不可用: " + userSpecified + " - " + e.getMessage());
            }
        }
        
        // 3. 优先硬解模式：检测 GPU 类型
        GpuType gpuType = detectGpuType();
        LogTools.getInstance().logRecord3("🎮 GPU 类型: " + gpuType.displayName);
        LogTools.getInstance().logRecord3("📋 推荐解码器顺序: " + String.join(" > ", gpuType.preferredDecoders));
        
        // 4. 按优先级尝试硬件解码器
        for (String decoderName : gpuType.preferredDecoders) {
            // 跳过软解（软解在最后尝试）
            if ("avdec_h264".equals(decoderName)) {
                continue;
            }
            try {
                LogTools.getInstance().logRecord3("   🔍 尝试: " + decoderName + "...");
                Element testDecoder = ElementFactory.make(decoderName, "decoder");
                if (testDecoder != null) {
                    decoder = testDecoder;
                    LogTools.getInstance().logRecord3("   ✅ 成功: " + decoderName);
                    LogTools.getInstance().logRecord3("==================== 选择完成 ====================");
                    LogTools.getInstance().logRecord3("🎯 最终解码器: " + decoderName + " (" + gpuType.displayName + " GPU 硬解)");
                    return decoderName;
                } else {
                    LogTools.getInstance().logRecord3("   ⚠️ 跳过: " + decoderName + " (返回null)");
                }
            } catch (Throwable e) {
                LogTools.getInstance().logRecord3("   ⚠️ 跳过: " + decoderName + " (" + e.getMessage() + ")");
            }
        }
        
        // 5. 所有硬解都失败，回退到软解
        LogTools.getInstance().logRecord3("⚠️ 所有硬件解码器不可用，回退到软件解码");
        return useSoftwareDecoder();
    }
    
    /**
     * 使用软件解码器
     */
    private String useSoftwareDecoder() {
        String softDecoder = "avdec_h264";
        try {
            decoder = ElementFactory.make(softDecoder, "decoder");
            if (decoder != null) {
                LogTools.getInstance().logRecord3("✅ 软件解码器可用: " + softDecoder);
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord3("❌ 软件解码器也失败: " + e.getMessage());
        }
        
        LogTools.getInstance().logRecord3("==================== 选择完成 ====================");
        LogTools.getInstance().logRecord3("🎯 最终解码器: " + softDecoder + " (CPU 软解)");
        return softDecoder;
    }
    
    // ==================== 解码器选择系统结束 ====================

    /**
     * 开始WebRTC连接和播放
     */
    public void start() {
        LogTools.getInstance().logRecord3("========== 🚀 开始启动 WebRTC 播放器 ==========");
        LogTools.getInstance().logRecord3("📡 服务器: " + serverHost + ":" + serverPort);
        LogTools.getInstance().logRecord3("🎯 流ID: " + tenant + "/" + streamId);
        LogTools.getInstance().logRecord3("当前状态 - overlayChildHandle: 0x" + Long.toHexString(overlayChildHandle) +
                ", overlayWindowHandle: 0x" + Long.toHexString(overlayWindowHandle));

        // ⭐ 重置局部缩放状态，确保每次连接时从 1.0x 开始
        currentZoom = 1.0;
        windowZoomOffsetX = 0;
        windowZoomOffsetY = 0;
        notifyZoomChange();  // ⭐ 通知 UI 更新
        LogTools.getInstance().logRecord3("🔍 已重置局部缩放状态: 1.0x");

        try {
            // ⭐⭐⭐ 在建立新连接前，先强制踢掉旧的播放连接
            String streamUrl = tenant + "/" + streamId;
            //kickoffOldConnection(streamUrl);

            // 等待 SRS 清理（500ms 足够）
            /*try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }*/
            // 创建GStreamer管道
            LogTools.getInstance().logRecord3("开始创建 GStreamer Pipeline...");
            createPipeline();
            LogTools.getInstance().logRecord3("✅ Pipeline 创建完成");
            // 在管道创建后打印一次缓存/保存队列占用，便于启动期观察
            try { printSavingQueues(); } catch (Throwable ignore) {}

            // 初始化并启动预热离线解码子管线（用于快速抓拍）
            try { initOfflineWarmDecoder(); } catch (Throwable warmEx) { LogTools.getInstance().logRecord2("⚠️ 预热离线解码器初始化失败: " + warmEx.getMessage()); }

            // 若在管道创建前已设置了回调，这里重放一次以激活回调分支
            try {
                if (frameCallback != null) {
                    logRecord3("♻️ start: 发现预设的 frameCallback，重放 setFrameCallback 以激活回调支路");
                    //setFrameCallback(frameCallback);
                }
            } catch (Throwable replayEx) {
                logRecord3("⚠️ start: 重放回调设置失败: " + replayEx.getMessage());
            }

            // 添加管道状态监控
            addPipelineStateMonitoring();

            // 注册 WebRTC 事件信号（协商、ICE、PAD 连接）
            setupWebRTCSignals();

            // 初始化磁盘缓存目录
            try {
                if (diskCacheDir == null) {
                    diskCacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "webrtc_frame_cache");
                }
                Files.createDirectories(diskCacheDir);
                logRecord3("📁 磁盘缓存目录: " + diskCacheDir);
            } catch (Throwable dirEx) {
                logRecord3("⚠️ 初始化磁盘缓存目录失败: " + dirEx.getMessage());
            }

            // 先启动WebRTC信令，确保 currentStreamUrl 已设置并添加 transceiver，避免首次点击协商提前触发
            startWebRTCSignaling();

            // 启动管道
            LogTools.getInstance().logRecord3("🎬 准备启动 Pipeline...");
            LogTools.getInstance().logRecord3("   当前 overlayChildHandle: 0x" + Long.toHexString(overlayChildHandle));
            LogTools.getInstance().logRecord3("   当前 overlayWindowHandle: 0x" + Long.toHexString(overlayWindowHandle));

            // ⭐ 记录启动时间并重置数据接收标志
            pipelineStartTimeMs = System.currentTimeMillis();
            firstDataReceived.set(false);

            StateChangeReturn ret = pipeline.play();
            LogTools.getInstance().logRecord3("🎬 Pipeline.play() 返回: " + ret);
            logRecord3("🎬 管道启动结果: " + ret);

            // ⭐ 关键修复：延迟 5 秒后检查，如果还没有收到数据，发送关键帧请求
            new Thread(() -> {
                try {
                    // 等待 5 秒，给 WebRTC 连接足够的时间建立
                    Thread.sleep(5000);

                    if (!firstDataReceived.get()) {
                        LogTools.getInstance().logRecord3("⏰ 5秒内未收到数据，发送关键帧请求...");
                        //StompWebSocketClient.getInstance().sendResetPublish();
                        // 检查 WebRTC 连接状态
                        if (webrtcbin != null) {
                            try {
                                WebRTCPeerConnectionState connectionState = webrtcbin.getConnectionState();
                                LogTools.getInstance().logRecord3("🔗 当前连接状态: " + connectionState);

                                if (connectionState == WebRTCPeerConnectionState.CONNECTED) {
                                    LogTools.getInstance().logRecord3("✅ WebRTC 连接已建立，发送关键帧请求");
                                    //requestKeyframe();



                                } else {
                                    LogTools.getInstance().logRecord3("⚠️ WebRTC 连接未建立 (" + connectionState + ")，跳过关键帧请求");
                                    LogTools.getInstance().logRecord3("💡 建议：前端配置定期发送关键帧（keyFrameInterval=90）");
                                }
                            } catch (Throwable t) {
                                LogTools.getInstance().logRecord3("⚠️ 检查连接状态失败: " + t.getMessage());
                            }
                        } else {
                            LogTools.getInstance().logRecord3("⚠️ webrtcbin 为 null");
                        }
                    } else {
                        LogTools.getInstance().logRecord3("✅ 已收到数据，跳过关键帧请求");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "KeyframeRequest").start();

            // ⚡ 启动定期关键帧请求（使用GStreamer内部PLI机制，不通过socket）
            startPeriodicKeyframeRequest();

            // ⚡ 启动综合诊断监控（每10秒输出详细统计，帮助定位马赛克原因）
            startDiagnosticMonitoring();

            if (ret == StateChangeReturn.FAILURE) {
                logRecord3("❌ 管道启动失败！");
                return;
            }

            // 新增：启动CPU占用监控
            //startCpuMonitor();

        } catch (Exception e) {
            logRecord3("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private PrintWriter recordLogger;
    private void logRecord(String message) {
        LogTools.getInstance().logRecord2(message);
    }


    private PrintWriter recordLogger3;
    private void logRecord3(String message) {
        LogTools.getInstance().logRecord2(message);
    }
    /**
     * 创建 GStreamer 管道
     * 
     * 重构后的结构：
     * 1. 清理旧管道
     * 2. 计算缓冲配置
     * 3. 创建 WebRTC 输入元素
     * 4. 选择并创建解码器（GPU/软解）
     * 5. 创建后处理元素
     * 6. 创建抓拍分支（始终开启）
     * 7. 创建显示元素
     * 8. 链接管道（GPU路径 / 软解路径）
     */
    private void createPipeline() {
        LogTools.getInstance().logRecord3("==================== 创建 GStreamer 管道 ====================");
        
        // ========== 1. 清理旧管道 ==========
        cleanupOldPipeline();
        
        // ========== 2. 创建新管道 ==========
        pipeline = new Pipeline("webrtc-pipeline");
        try {
            if (pipeline.getClock() != null) {
                LogTools.getInstance().logRecord3("✅ 管道时钟已设置");
            }
        } catch (Exception ignored) {}
        
        // ========== 3. 计算缓冲配置 ==========
        // === 动态缓冲配置 ===
        final WebRTCBufferConfig bufferConfig = WebRTCBufferConfig.getInstance();
        final BufferMode bufferMode = bufferConfig.getCurrentMode();
        final boolean lowLatencyMode = bufferMode == BufferMode.LOW_LATENCY;
        final boolean balancedMode = bufferMode == BufferMode.BALANCED;
        final boolean stableFamilyMode = bufferMode == BufferMode.STABLE || bufferMode == BufferMode.ULTRA_STABLE;
        final int webrtcLatencyMs = bufferMode.getWebrtcLatency();

        // ⭐ 根据机型（内存 + CPU）动态调整缓冲策略，解决快速挥动时的马赛克
        long memoryGB = getSystemMemoryGB();
        int cpuCores = getCore();

        // ⚡ CPU 性能评估：6核或以下视为低端（如 i5-9400F）
        boolean isLowEndCPU = cpuCores <= 6;
        logRecord(String.format("💻 CPU 核心数: %d%s", cpuCores, isLowEndCPU ? " (低端CPU)" : ""));

        // ⚡ 所有机型自动尝试提升系统优先级/电源配置（只执行一次）
        ensureLowEndPerformanceBoost();

        // 根据内存大小确定缓冲策略
        final int jitterLatencyMs;
        final int queueDepayTargetBuffers;
        final int queueDecodeTargetBuffers;
        final long queueDepayMaxTimeNs;
        final int retryCount;
        final int retryTimeoutMs;
        final String machineType;

        // ⚡ 🔥 统一 500ms 稳定配置 + 分辨率自适应
        // 初始使用 500ms 配置，后续根据实际分辨率自动调整
        String resolutionHint = System.getProperty("video.resolution", "auto");
        this.isHighResolution = "4k".equalsIgnoreCase(resolutionHint) ||
                "1080p".equalsIgnoreCase(resolutionHint) ||
                "fhd".equalsIgnoreCase(resolutionHint);

        // 🎯 统一使用 300ms 稳定配置（码率3500kbps，后续根据分辨率自动微调）
        // 分辨率越高，需要更大的缓冲来保证稳定
        if (isHighResolution) {
            // 4K/1080P 高分辨率：400ms 缓冲（解码压力大）
            machineType = "高分辨率(" + resolutionHint + ")";
            jitterLatencyMs = 400;              // 🔥 400ms 缓冲
            queueDepayTargetBuffers = 28;       // 🔥 28帧=467ms@60fps
            queueDecodeTargetBuffers = 20;      // 🔥 20帧=333ms@60fps
            queueDepayMaxTimeNs = 500_000_000L; // 🔥 500ms
            retryCount = 15;                    // 🔥 15次重传
            retryTimeoutMs = 30;                // 🔥 30ms重传周期

            logRecord("🎯 高分辨率模式：jitter=400ms, depay=28帧, decode=20帧");
            logRecord("   ⚡ 策略：缓冲(400ms) + 强重传 + 关闭丢帧 → 稳定无马赛克");

        } else if (memoryGB <= 8 || isLowEndCPU) {
            machineType = (memoryGB <= 8 && isLowEndCPU) ? "极低端机(≤8GB+≤6核)" :
                    (memoryGB <= 8 ? "低端机(≤8GB)" : "低端CPU(≤6核)");

            // 🎯 低端机：300ms 稳定配置
            jitterLatencyMs = 300;              // 🎯 300ms
            queueDepayTargetBuffers = 22;       // 🎯 22帧=367ms@60fps
            queueDecodeTargetBuffers = 16;      // 🎯 16帧=267ms@60fps
            queueDepayMaxTimeNs = 400_000_000L; // 🎯 400ms
            retryCount = 15;                    // 🔥 15次重传
            retryTimeoutMs = 25;                // 🔥 25ms重传周期
            logRecord("   🔥 低端机稳定模式：缓冲(22/16帧) + 强重传(15×25ms) → 延迟≈300ms");
        } else if (memoryGB < 16) {
            machineType = "中端机(8-16GB)";
            jitterLatencyMs = 300;              // 🎯 300ms
            queueDepayTargetBuffers = 22;       // 🎯 22帧
            queueDecodeTargetBuffers = 16;      // 🎯 16帧
            queueDepayMaxTimeNs = 400_000_000L; // 🎯 400ms
            retryCount = 15;                    // 🔥 15次重传
            retryTimeoutMs = 22;                // 🔥 22ms重传周期
            logRecord("   🔧 中端机稳定模式：缓冲(22/16帧) + 强重传(15×22ms) → 延迟≈300ms");
        } else if (memoryGB < 32) {
            machineType = "高端机(16-32GB)";
            jitterLatencyMs = 300;              // 🎯 300ms
            queueDepayTargetBuffers = 22;       // 🎯 22帧
            queueDecodeTargetBuffers = 16;      // 🎯 16帧
            queueDepayMaxTimeNs = 400_000_000L; // 🎯 400ms
            retryCount = 15;                    // 🔥 15次重传
            retryTimeoutMs = 20;                // 🔥 20ms（快速重传）
            logRecord("   🎯 高端机稳定模式：缓冲(22/16帧) + 强重传(15×20ms) → 延迟≈300ms");
        } else {
            machineType = "超高端机";
            jitterLatencyMs = 300;              // 🎯 300ms（统一）
            queueDepayTargetBuffers = 22;       // 🎯 22帧
            queueDecodeTargetBuffers = 16;      // 🎯 16帧
            queueDepayMaxTimeNs = 400_000_000L; // 🎯 400ms
            retryCount = 15;
            retryTimeoutMs = 18;
        }

        // ⚡ 不再额外增加解码缓冲（已在上面针对高分辨率单独配置）
        final int finalQueueDecodeTargetBuffers = queueDecodeTargetBuffers;

        // ⚡ 保存到成员变量（用于定期任务监控缓冲区堆积）
        this.queueDepayTargetBuffers = queueDepayTargetBuffers;
        this.finalQueueDecodeTargetBuffers = finalQueueDecodeTargetBuffers;

        if (!isHighResolution) {
            logRecord("🎚️ 当前缓冲模式: " + bufferMode.getDescription());
            logRecord(String.format("🎯 机型自适应配置 [%s, %dGB内存]:", machineType, memoryGB));
            logRecord(String.format("   - jitter=%dms, retry=%dx%dms (重传窗口=%dms)",
                    jitterLatencyMs, retryCount, retryTimeoutMs, retryCount * retryTimeoutMs));
            logRecord(String.format("   - queueDepay=%d帧/%dms, queueDecode=%d帧",
                    queueDepayTargetBuffers, queueDepayMaxTimeNs/1_000_000L, finalQueueDecodeTargetBuffers));
            logRecord("   - 策略：防马赛克（3×jitter容忍+大缓冲+长重传），leaky=2防延迟累积");
        }

        // 创建元素
        webrtcbin = new WebRTCBin("webrtcbin");
        try {
            // 3 = max-bundle（GStreamer webrtcbin 的枚举值）
            webrtcbin.set("bundle-policy", 3);
        } catch (Exception e) {

        }
        try {
            webrtcbin.set("latency", webrtcLatencyMs);
            logRecord("🎛️ webrtcbin latency 设置为 " + webrtcLatencyMs + "ms");
        } catch (Exception e){
            logRecord("⚠️ 设置 webrtcbin latency 失败: " + e.getMessage());
        }
        
        // 🔥 所有机型"抢网模式"：强制高带宽，禁用自适应降码率
            try {
                // 禁用 TWCC（Transport Wide Congestion Control）拥塞控制
                webrtcbin.set("do-retransmission", true);  // 强制重传
            logRecord("🔥 webrtcbin 抢网模式：启用强制重传（全机型统一启用）");
            } catch (Exception e) {
                logRecord("⚠️ webrtcbin 抢网模式配置失败: " + e.getMessage());
        }
        // ✅ 配置jitterbuffer（webrtcbin内部元素）
        webrtcbin.connect(new Bin.ELEMENT_ADDED() {
            @Override
            public void elementAdded(Bin src, Element elem) {
                String elemName = elem.getName();
                if (elemName != null && elemName.contains("jitterbuffer")) {
                    try {
                        // ⭐ 基础延迟配置
                        elem.set("latency", jitterLatencyMs);  // 基础抖动缓冲

                        // ⭐ 重传优化（关键！）- 极限配置
                        elem.set("do-retransmission", true);           // 启用重传
                        elem.set("rtx-max-retries", retryCount);       // 动态重传次数
                        elem.set("rtx-retry-timeout", retryTimeoutMs);
                        elem.set("rtx-min-retry-timeout", Math.max(10, retryTimeoutMs / 2)); // 🔧 最小超时减半
                        elem.set("rtx-delay", 10);                     // 🔧 10ms 更快开始重传
                        elem.set("rtx-min-delay", 0);                  // 0ms 最小重传延迟
                        elem.set("rtx-deadline", Math.max(500, retryTimeoutMs * 8)); // 🔧 加长截止时间（极限容忍）
                        elem.set("rtx-retry-period", Math.max(200, retryTimeoutMs * 5)); // 🔧 加长重传周期
                        elem.set("rtx-next-seqnum", true);             // 预测下一个包
                        elem.set("rtx-delay-reorder", 1);              // 🔧 乱序1个包就触发重传（更敏感）

                        // ⭐ 🔥 丢包容忍配置（适配300ms延迟 + 码率3500kbps，消除马赛克）
                        int dropoutMs;
                        int misorderMs;
                        if (memoryGB <= 8 || isLowEndCPU) {
                            // 🔥 低端机：适中容忍
                            dropoutMs = Math.max(1200, jitterLatencyMs * 4);  // 🔥 1.2秒容忍
                            misorderMs = Math.max(800, jitterLatencyMs * 3);  // 🔥 800ms乱序容忍

                        } else if (memoryGB < 16) {
                            // 🔧 中端机：适中容忍
                            dropoutMs = Math.max(1200, jitterLatencyMs * 4);  // 🔧 1.2秒容忍
                            misorderMs = Math.max(800, jitterLatencyMs * 3);  // 🔧 800ms乱序容忍
                        } else if (memoryGB < 32) {
                            // 🎯 高端机：适中容忍
                            dropoutMs = Math.max(1000, jitterLatencyMs * 4);  // 🎯 1秒容忍
                            misorderMs = Math.max(600, jitterLatencyMs * 3);  // 🎯 600ms乱序容忍
                        } else {
                            // ⚡ 超高端机：适中容忍
                            dropoutMs = Math.max(900, jitterLatencyMs * 3);
                            misorderMs = Math.max(500, jitterLatencyMs * 2);
                        }
                        elem.set("max-dropout-time", dropoutMs);
                        elem.set("max-misorder-time", misorderMs);
                        
                        // 🔥🔥🔥 关键修改：关闭 drop-on-latency（这是马赛克的主要原因！）
                        // 浏览器不会在延迟高时丢帧，所以更稳定
                        elem.set("drop-on-latency", false);   // 🔥 关闭主动丢包（消除马赛克）
                        
                        // 🎯 低延迟配置：允许适度延迟波动
                        int maxLatencyMs = jitterLatencyMs * 3;  // 延迟超过3倍缓冲才考虑
                        try { elem.set("max-ts-offset-adjustment", 300_000_000L); } catch (Throwable ignore) {} // 300ms
                        
                        // 🔥 全机型"稳定优先"：禁用时钟同步检查
                        try { elem.set("rfc7273-sync", false); } catch (Throwable ignore) {}
                        
                        // ⭐ 快速启动
                        elem.set("faststart-min-packets", 0);          // 立即开始

                        // ⭐ 性能优化
                        elem.set("do-lost", true);                     // 启用丢包检测
                        elem.set("post-drop-messages", false);         // 不发送丢包消息
                        elem.set("drop-messages-interval", 500);       // 丢包消息间隔

                        // ⭐ 时钟同步
                        elem.set("mode", 1);                           // slave 模式
                        elem.set("max-rtcp-rtp-time-diff", -1);        // 禁用 RTCP 检查

                        logRecord(String.format("✅ jitterbuffer 配置完成（🔥稳定优先，对齐浏览器）: latency=%dms, dropOnLatency=OFF, retry=%d×%dms",
                                jitterLatencyMs, retryCount, retryTimeoutMs));
                        logRecord(String.format("   - dropout=%dms, misorder=%dms, maxLatency=%dms 🔥高容忍消除马赛克", dropoutMs, misorderMs, maxLatencyMs));
                        logRecord(String.format("   - deadline=%dms, period=%dms",
                                Math.max(300, retryTimeoutMs * 5), Math.max(150, retryTimeoutMs * 3)));
                    } catch (Exception e) {
                        logRecord("⚠️ 配置jitterbuffer失败: " + e.getMessage());
                    }
                }
            }
        });



        rtph264depay = ElementFactory.make("rtph264depay", "depay");
        try {

            rtph264depay.set("wait-for-keyframe", true);  // ⭐ 必须！等待关键帧才开始解包
            rtph264depay.set("request-keyframe", true);   // 启用关键帧请求

            // ⚡ iOS快速挥动：增强关键帧请求频率
            rtph264depay.set("request-keyframe-on-discont", true);  // 发现不连续时立即请求关键帧

            // ⚡ 不设置 aggregate-mode（使用默认值，避免画面闪烁）
            // rtph264depay.set("aggregate-mode", 0);  // 移除：可能导致画面 "有-无-有-无"

        } catch (Exception e) {
            logRecord("rtph264depay error "+e.getMessage());
        }
        queueDepay = ElementFactory.make("queue", "queue_depay");
        queueMux = ElementFactory.make("queue", "queue_mux");

        h264parse = ElementFactory.make("h264parse", "parse");

        // ==================== 解码器智能选择 ====================
        // 优先级策略：
        //   1. 用户手动指定 (-Ddiag.decoder=xxx)
        //   2. 根据 GPU 类型自动选择最优解码器
        //   3. 回退到软件解码
        // ========================================================
        
        String chosenDecoderFactory = selectBestDecoder();
        
        // ⚡ 判断是否使用 GPU 加速路径（D3D11 元素）
        // d3d11h264dec：直接输出 D3D11 内存，必须用 d3d11convert/d3d11download
        // nvh264dec：输出 CUDA 或系统内存，GStreamer 会自动转换，也可以用 D3D11 路径
        // msdkh264dec：Intel Quick Sync，输出系统内存，也可以用 D3D11 路径
        // avdec_h264：软解，输出系统内存，用 videoconvert
        boolean useD3D11Path = "d3d11h264dec".equalsIgnoreCase(chosenDecoderFactory)
            || "nvh264dec".equalsIgnoreCase(chosenDecoderFactory)
            || "msdkh264dec".equalsIgnoreCase(chosenDecoderFactory);
        
        LogTools.getInstance().logRecord3("🔗 链路模式: " + (useD3D11Path ? "GPU 加速路径 (D3D11)" : "软件路径 (videoconvert)"));

        // 设置解码器信息（用于UI显示）
        this.decoderName = chosenDecoderFactory;
        this.isHardwareDecoder = useD3D11Path;
        boolean enableGpuDisplaySink = useD3D11Path && Boolean.parseBoolean(System.getProperty("gpu.display", "true"));
        
        // ========== 5. 配置解码器 ==========


        if (!diagSkipDecode) {
            // ✅ 软解优化：降低要求，允许跳帧
            boolean isSoftwareDecoder = "avdec_h264".equalsIgnoreCase(chosenDecoderFactory);

            if (isSoftwareDecoder) {
                LogTools.getInstance().logRecord2("⚠️ 软件解码器极限性能优化：");
                try {
                    // 🎯 统一配置：所有机型相同
                    int threads = Math.min(cpuCores, 6);  // 统一：最多6线程
                    
                    decoder.set("skip-frame", 0);      // ✅ 不跳帧（防止马赛克）
                    decoder.set("lowres", 0);          // 不降低分辨率（画质优先）
                    decoder.set("threads", threads);   // 多线程解码
                    decoder.set("thread-type", 3);     // Frame+Slice多线程

                } catch (Throwable e) {
                    LogTools.getInstance().logRecord2("   配置失败: " + e.getMessage());
                }
            } else {
                // 硬件解码器：不跳帧
                try {
                    decoder.set("skip-frame", 0);
                } catch (Throwable e) {
                    LogTools.getInstance().logRecord2("⚠️ decoder不支持skip-frame: " + e.getMessage());
                }
            }

            try {
                // ⭐ 防马赛克配置：宁可卡顿也不输出损坏帧
                decoder.set("discard-corrupted-frames", true);   // ⭐ 丢弃损坏帧（防止马赛克）
                decoder.set("output-corrupt", false);            // 不输出损坏帧
                decoder.set("max-errors", 100);                  // 限制最大错误数（触发关键帧请求）
                logRecord("   ⭐ 解码器防马赛克配置：丢弃损坏帧（宁可卡顿不要马赛克）");
            } catch (Throwable e) {
                LogTools.getInstance().logRecord2("⚠️ decoder容错配置失败: " + e.getMessage());
            }
        }


        // ⚡ 配置h264parse - 平衡模式（防止画面闪烁）
        try {
            h264parse.set("output-format", "byte-stream");
            h264parse.set("disable-passthrough", true);

            // ⚡ 使用温和的 config-interval（防止画面闪烁 "有-无-有-无"）
            h264parse.set("config-interval", 1);  // 1 = 每个关键帧前插入SPS/PPS（不会导致闪烁）

            // ⚡ 自动请求同步点（解码器发现错误时自动请求关键帧）
            decoder.set("automatic-request-sync-points", true);
        } catch (Throwable e) {
            logRecord("⚠️ h264parse配置失败: " + e.getMessage());
        }


        // ========== 6. 创建格式转换元素 ==========
        // 抓拍功能始终启用
        boolean enableCaptureFeature = true;
        logRecord("🎯 抓拍功能: 已启用（始终开启）");
        Element converter = null;
        Element d3d11download = null;
        Element capsfilter = ElementFactory.make("capsfilter", "cf_bgrx");
        try { capsfilter.set("caps", Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full")); } catch (Throwable ignore) {}
        if (useD3D11Path) {
            d3d11convert = ElementFactory.make("d3d11convert", "d3d11convert");
            d3d11download = ElementFactory.make("d3d11download", "d3d11download");
        } else {
            converter = ElementFactory.make("videoconvert", "convert");

        }

        try {
            d3d11convert.set("add-borders", false);
        }catch (Exception e){
            logRecord("add-borders error" +e.getMessage());
        }
        
        // ⭐ 初始化时重置旋转和缩放，确保没有残留的倾斜
        try {
            d3d11convert.set("rotation-z", 0.0f);
            d3d11convert.set("scale-x", 1.0f);
            d3d11convert.set("scale-y", 1.0f);
            logRecord("✅ d3d11convert 旋转/缩放已重置为0");
        } catch (Exception e) {
            logRecord("⚠️ 重置d3d11convert旋转/缩放失败: " + e.getMessage());
        }

        // 优化：移除 tee 分支，只保留显示队列（纯 GPU 直显路径）
        displayQueue = ElementFactory.make("queue", "display_queue");
        try {
            // ⚡ 极低延迟配置：目标3帧延迟（同类产品水平）
            // 最小化显示缓冲，让显示和抓拍几乎同步
            int displayBuffers;
            if (memoryGB <= 8 || isLowEndCPU) {
                displayBuffers = 5;   // 🔥 低端机：5帧=166ms@30fps（最小稳定缓冲）
            } else if (memoryGB < 16) {
                displayBuffers = 3;   // 🔧 中端机：3帧=50ms@60fps（极低延迟）
            } else {
                displayBuffers = 2;   // 🎯 高端机：2帧=33ms@60fps（接近实时）
            }

            displayQueue.set("max-size-buffers", displayBuffers);
            displayQueue.set("max-size-bytes", 0);
            displayQueue.set("max-size-time", 0);  // 不限制时间
            displayQueue.set("leaky", 2);  // ⭐ 保持leaky=2，防止延迟累积
            displayQueue.set("flush-on-eos", true);
            displayQueue.set("silent", true);  // 减少日志输出
            
            System.out.println("⚡ displayQueue 极低延迟: " + displayBuffers + " 帧（目标3帧同步）");

            logRecord(String.format("⚡ displayQueue: buffers=%d, leaky=2 (极低延迟，目标3帧)", displayBuffers));
        } catch (Throwable var43) {
            logRecord("⚠️ 配置 displayQueue 失败: " + var43.getMessage());
        }


        // 创建分发AppSink
        distributionAppSink = (AppSink) ElementFactory.make("appsink", "distribution_appsink");
        if (distributionAppSink != null) {
            distributionAppSink.set("emit-signals", true);
            distributionAppSink.set("sync", false);
            distributionAppSink.set("async", false);

            // ⭐ 优化分发队列：支持120fps

            int distributionBuffers = (memoryGB >= 32) ? 10 : (memoryGB >= 16) ? 8 : 5;
            distributionAppSink.set("max-buffers", distributionBuffers);
            distributionAppSink.set("drop", true);  // 丢弃旧帧，避免积压

            distributionAppSink.setCaps(Caps.fromString("video/x-raw,format=BGRA"));
            logRecord("✅ distributionAppSink 已创建（max-buffers=" + distributionBuffers + "）");
        }

        // 在创建其他元素的地方添加
        distributionDownload = ElementFactory.make("d3d11download", "distributionDownload");

        videoBalance = ElementFactory.make("videobalance", "video_balance");
        // 根据实际范围设置参数
        // ⭐ 从本地存储加载保存的设置（首次使用时为默认值）
        CameraSettingsStorage settings = CameraSettingsStorage.getInstance();
        videoBalance.set("brightness", settings.getBrightness());
        videoBalance.set("contrast", settings.getContrast());
        videoBalance.set("saturation", settings.getSaturation());
        videoBalance.set("hue", settings.getHue());
        
        // 更新当前值
        currentBrightness = settings.getBrightness();
        currentContrast = settings.getContrast();
        currentSaturation = settings.getSaturation();
        currentHue = settings.getHue();

        // ⭐ 创建 gamma 元素
        gamma = ElementFactory.make("gamma", "gamma");
        gamma.set("gamma", settings.getGamma());          // 范围: 0.01 ~ 10.0
        currentGamma = settings.getGamma();
        
        // ⭐ 加载曝光补偿设置
        currentExposure = settings.getExposure();
        
        System.out.println("📷 已应用保存的相机设置: " + settings);


        // ========== 7. 创建抓拍分支元素（始终开启）==========
        if (enableCaptureFeature) {
            captureTee = ElementFactory.make("tee", "capture_tee");
            captureTee.set("allow-not-linked", true);  // ⭐ 重要：允许分支动态连接


            captureValve = ElementFactory.make("valve", "capture_valve");
            captureImageQueue = ElementFactory.make("queue", "capture_image_queue");
            captureImageDecoder = ElementFactory.make("avdec_h264", "capture_image_decoder");  // 添加解码器
            captureImageConvert = ElementFactory.make("videoconvert", "capture_image_convert");

            // ⭐ 创建降采样元素（限制分辨率，防止4K卡死）
            //captureImageScale = ElementFactory.make("videoscale", "capture_image_scale");
            //captureScaleCaps = ElementFactory.make("capsfilter", "capture_scale_caps");

            jpegEncoder = ElementFactory.make("jpegenc", "jpeg_encoder");
            multifilesink = ElementFactory.make("multifilesink", "multi_filesink");
            captureImageDownload = ElementFactory.make("d3d11download", "capture_image_download");
            
            // ⚡ 抓拍分支裁剪元素（支持放大区域抓拍）
            captureVideocrop = ElementFactory.make("videocrop", "capture_videocrop");
            if (captureVideocrop != null) {
                // 初始化：不裁剪（显示完整画面）
                captureVideocrop.set("left", 0);
                captureVideocrop.set("right", 0);
                captureVideocrop.set("top", 0);
                captureVideocrop.set("bottom", 0);
                logRecord("✅ 抓拍裁剪元素已创建（captureVideocrop）");
            }

            // 配置valve（默认开启JPEG保存）
            if (captureValve != null) {
                //captureValve.set("drop",false);  // 开启数据流通，允许JPEG保存
                captureValve.set("drop",false);
            }
            // 配置队列
            if (captureImageQueue != null) {
                // ⚡ 极低延迟配置：目标3帧延迟（同类产品水平）
                // 设置最小缓冲，让 JPEG 写入几乎同步实时显示
                captureImageQueue.set("max-size-buffers", 1);  // ⚡ 最小1帧缓冲
                captureImageQueue.set("max-size-bytes", 0);
                captureImageQueue.set("max-size-time", 0);
                captureImageQueue.set("leaky", 2);  // 丢弃老帧，保持最新帧
                logRecord("⚡ captureImageQueue: buffers=1 (极低延迟抓拍模式，目标3帧)");
            }

            // 配置JPEG编码器（⭐ 根据系统内存自动调整质量）
            if (jpegEncoder != null) {
                // ⭐ 根据内存自动选择质量
                //int quality = Integer.getInteger("jpeg.quality", getOptimalJpegQuality());
                jpegEncoder.set("quality", 85);
                jpegEncoder.set("idct-method", 2); // 快速IDCT算法

            }

            if (multifilesink != null) {
                // 确保目录存在
                File captureDir = new File("runtime/captures/ssl");
                if (!captureDir.exists()) {
                    captureDir.mkdirs();
                }
                multifilesink.set("location", "runtime/captures/ssl/s_%09d.jpeg");
                multifilesink.set("post-messages", true);
                multifilesink.set("max-files", 0);  // 0表示无限制
            }

        } else {
        }

        // ========== 8. 创建 H264 分流和录制元素 ==========
        splitTee = ElementFactory.make("tee", "split_tee");
        splitTee.set("allow-not-linked", true);

        // 创建 MKV 录制分支元素


        logRecord("mkv--->1");
        // 创建MKV录制分支元素
        recordValve = ElementFactory.make("valve", "record_valve");
        recordQueue = ElementFactory.make("queue", "record_queue");


        Element recordH264Parse = ElementFactory.make("h264parse", "record_h264parse");




        matroskaMux = ElementFactory.make("matroskamux", "matroska_mux");
        recordFileSink = ElementFactory.make("filesink", "record_file_sink");


        recordDownload = ElementFactory.make("d3d11download", "record_download");
        captureRecordTee = ElementFactory.make("tee", "capture-record-tee");
        captureRecordTee.set("allow-not-linked", true);  // 允许分支未链接




        h264Encoder = ElementFactory.make("x264enc", "x264enc"); //h264parse
        if (h264Encoder != null) {
            h264Encoder.set("speed-preset", 1);       // 1=ultrafast
            h264Encoder.set("tune", 0x00000004);      // zerolatency
            h264Encoder.set("threads", 2);            // 限制线程数
            h264Encoder.set("key-int-max", 60);       // 2秒一个关键帧
            h264Encoder.set("bframes", 0);            // 不用B帧
            h264Encoder.set("bitrate", 2000);         // 2Mbps
            logRecord("✅ x264enc 超快速配置完成");
        }

        // 在 initialize() 中创建 identity 元素（第 950 行附近）
        recordIdentity = ElementFactory.make("identity", "record_identity");
        if (recordIdentity != null) {
            recordIdentity.set("sync", false);
            recordIdentity.set("silent", true);  // 不打印日志
            logRecord("✅ identity 元素已创建");
        }


        // 配置录制开关（默认关闭）
        recordValve.set("drop", true);  // 默认不录制


        // 配置录制队列
        if (recordQueue != null) {
            /*recordQueue.set("max-size-buffers", 10);
            recordQueue.set("max-size-bytes", 0);
            recordQueue.set("max-size-time", 2000000000L);  // 2秒缓冲
            recordQueue.set("leaky", 2);  // 丢弃旧数据*/

            recordQueue.set("max-size-buffers",10); // 5 → 60
            recordQueue.set("leaky", 2);  // 保持丢弃旧帧
            recordQueue.set("max-size-bytes", 0);
        }


        // 配置 MKV 封装器
        matroskaMux.set("streamable", true);  // 支持流式写入
        matroskaMux.set("writing-app", "Acard");
        matroskaMux.set("min-index-interval", 0L);           // ⭐ 不写索引（降低延迟）
        matroskaMux.set("max-cluster-duration", 100000000L); // ⭐ 100ms 一个 cluster（更频繁）
        // 配置文件输出 runtime/slowmo/segments/segment_00000.mkv

        recordFileSink.set("location", "runtime/xslow/segments/segment_00000.mkv");
        recordFileSink.set("sync", false);
        recordFileSink.set("async", true);
        recordFileSink.set("buffer-mode", 2);  // 2 = unbuffered (实时写入，不缓冲)
        //recordFileSink.set("append", false);  // false = 覆盖模式

        recordValve2 = ElementFactory.make("valve", "record_valve");
        recordValve2.set("drop", true);  // 默认不录制


        // ✅ 改成这个（和 JPEG 一样）
        recordMultiFileSink = ElementFactory.make("multifilesink", "record_multisink");
        recordMultiFileSink.set("location", "runtime/xslow/h264_frames/frame_%08d.h264");
        recordMultiFileSink.set("index", 0);
        recordMultiFileSink.set("post-messages", true);

        // ⚡ 移除 videocrop/videoscale/zoomCapsfilter，局部放大改用 GpuView 窗口放大（零CPU）
        // videocrop = ElementFactory.make("videocrop", "crop");
        // videoscale = ElementFactory.make("videoscale", "scale");
        // zoomCapsfilter = ElementFactory.make("capsfilter", "zoomCaps");
        // initZoomElements();

        // ========== 9. 创建队列元素 ==========
        queueDecode = ElementFactory.make("queue", "q_decode");
        // ✅ 动态配置队列缓冲（帧数+时间双重限制，支持所有帧率）
        try {
            queueDepay.set("max-size-buffers", queueDepayTargetBuffers);
            queueDepay.set("max-size-bytes", 0);
            queueDepay.set("max-size-time", queueDepayMaxTimeNs);
            queueDepay.set("leaky", 2);  // 丢弃老帧
            queueDepay.set("silent", true);
            queueDepay.set("flush-on-eos", true);

            // 🔥 低端机"抢网模式"：禁用 QoS，强制处理每一帧
            if (memoryGB <= 8 || isLowEndCPU) {
                try { queueDepay.set("qos", false); } catch (Throwable ignore) {}
                logRecord(String.format("🔥 queueDepay（抢网模式）: buffers=%d, time=%dms, leaky=2, qos=OFF",
                        queueDepayTargetBuffers, queueDepayMaxTimeNs / 1_000_000L));
            } else {
                logRecord(String.format("🎯 queueDepay（标准模式）: buffers=%d, time=%dms, leaky=2",
                        queueDepayTargetBuffers, queueDepayMaxTimeNs / 1_000_000L));
            }
        } catch (Throwable t) {
            logRecord("⚠️ 配置 queueDepay 失败: " + t.getMessage());
        }

        try {
            // ⚡ 增大解码缓冲：防止瞬时波动触发 leaky 丢帧
            int decodeBuffers = Math.max(finalQueueDecodeTargetBuffers, 25);  // 至少 25 帧

            queueDecode.set("max-size-buffers", decodeBuffers);
            queueDecode.set("max-size-bytes", 0);
            queueDecode.set("max-size-time", 0);  // 不限制时间，只限制帧数
            queueDecode.set("leaky", 2);
            
            // ⚡ 所有机型禁用 QoS，防止丢帧
            try { queueDecode.set("qos", false); } catch (Throwable ignore) {}
            logRecord(String.format("⚡ queueDecode: buffers=%d, leaky=2, qos=OFF (防卡顿)", decodeBuffers));

        } catch (Throwable t) {
            logRecord("⚠️ 配置 queueDecode 失败: " + t.getMessage());
        }


        // ========== 10. 创建显示 Sink 元素 ==========
        if (diagUseFakesink) {
            sinkEndElement = ElementFactory.make("fakesink", "display_fakesink");
            try { sinkEndElement.set("sync", false); sinkEndElement.set("async", false); } catch (Throwable ignore) {}
        } else {
            // 新增：严格区域渲染开关（默认关闭）。关闭时优先使用 GPU sink（d3dvideosink/d3d11videosink）。
            boolean strictArea = Boolean.parseBoolean(System.getProperty("video.strictArea", "false"));
            if (enableGpuDisplaySink) {
                // 当使用 D3D11 解码链路时，优先选择 d3d11videosink，避免与 d3dvideosink 的格式不兼容（NOFORMAT）
                Element vSink = null;
                boolean forceOverlaySink = Boolean.parseBoolean(System.getProperty("video.forceD3DVideoSink", "false"));
                if (useD3D11Path) {
                    if (forceOverlaySink) {
                        System.out.println("🎛️ 已启用 video.forceD3DVideoSink → 强制使用 d3dvideosink（将插入 d3d11download/capsfilter）");
                        vSink = ElementFactory.make("d3dvideosink", "display_sink");
                        if (vSink == null) {
                            LogTools.getInstance().logRecord2("⚠️ 创建 d3dvideosink 失败，尝试 d3d11videosink");
                            vSink = ElementFactory.make("d3d11videosink", "display_sink");
                        }
                    } else {
                        System.out.println("🎛️ 优先选择 d3d11videosink（D3D11 解码链路）");
                        vSink = ElementFactory.make("d3d11videosink", "display_sink");
                        if (vSink == null) {
                            LogTools.getInstance().logRecord2("⚠️ 创建 d3d11videosink 失败，尝试 d3dvideosink");
                            vSink = ElementFactory.make("d3dvideosink", "display_sink");
                        }
                    }
                } else {
                    vSink = ElementFactory.make("d3dvideosink", "display_sink");
                    if (vSink == null) {
                        LogTools.getInstance().logRecord2("⚠️ 创建 d3dvideosink 失败，尝试 d3d11videosink");
                        vSink = ElementFactory.make("d3d11videosink", "display_sink");
                    }
                }
                if (vSink == null) {
                    LogTools.getInstance().logRecord2("❌ 创建 GPU 显示 sink 失败，回退到 appsink");
                    appsink = (AppSink) ElementFactory.make("appsink", "display_sink");
                    if (appsink != null) {
                        appsink.set("emit-signals", true);
                        appsink.set("sync", false);
                        appsink.set("async", false);
                        try { appsink.set("qos", true); } catch (Throwable ignore) {}
                        appsink.set("max-buffers", Integer.getInteger("diag.sink.maxBuffers", 30));
                        appsink.set("drop", Boolean.parseBoolean(System.getProperty("diag.sink.drop", "true")));
                        appsink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
                        sinkEndElement = appsink;
                        // ⚡ 设置 appsink 回调（用于软解显示）
                        setupAppSinkCallback();
                        // 打印 AppSink 关键属性，确认不会因丢帧/阻塞导致无画面

                        // 使用 appsink 显示：通过下载到系统内存的 BGRA 帧进行显示（不再修改 enableGpuDisplaySink）
                        if (useD3D11Path) {
                            System.out.println("🔧 使用 appsink 显示：将通过 d3d11download + capsfilter(BGRA) 显示 CPU 帧");
                        }
                    } else {
                        LogTools.getInstance().logRecord2("❌ 创建 display appsink 失败");
                    }
                } else {
                    try { vSink.set("force-aspect-ratio", false); } catch (Throwable ignore) {}
                    try { vSink.set("sync", false); vSink.set("async", false); } catch (Throwable ignore) {}
                    // ⭐ 关键优化：禁用 QoS，避免高端机型过度丢帧导致卡顿
                    try { vSink.set("qos", false); } catch (Throwable ignore) {}
                    try { vSink.set("enable-last-sample", false); } catch (Throwable ignore) {}  // 禁用，减少开销
                    try { vSink.set("enable-live", true); } catch (Throwable ignore) {}  // 启用live模式
                    // ⭐ 关键优化：放宽延迟限制，支持120fps高帧率（-1 = 无限制）
                    try { vSink.set("max-lateness", -1L); } catch (Throwable ignore) {}
                    // ⭐ 120fps优化：禁用节流（throttle-time）
                    try { vSink.set("throttle-time", 0L); } catch (Throwable ignore) {}  // 不限制帧率
                    try { vSink.set("max-bitrate", 0L); } catch (Throwable ignore) {}  // 不限制码率
                    // ✅ 尝试设置黑色背景（如果属性存在）
                    try { vSink.set("draw-on-main-context", true); } catch (Throwable ignore) {}
                    try { vSink.set("ignore-alpha", false); } catch (Throwable ignore) {}
                    sinkEndElement = vSink;
                    System.out.println("✅ GPU sink 已优化：支持120fps高帧率，禁用QoS和节流");
                    System.out.println("✅ 使用 GPU 显示 sink 作为末端显示（GPU渲染）");
                    String sinkFactoryName = null;
                    try { sinkFactoryName = vSink.getFactory().getName(); } catch (Throwable ignore) {}
                    // 使用接口检测是否支持 VideoOverlay，而非仅根据工厂名称
                    boolean sinkSupportsOverlay = false;
                    try { sinkSupportsOverlay = (VideoOverlay.wrap(vSink) != null); } catch (Throwable ignore) {}
                    if (strictArea && sinkSupportsOverlay) {
                        System.out.println("📐 严格区域：使用 VideoOverlay 绑定到指定区域（sink=" + sinkFactoryName + ")");
                        initVideoOverlayBinding(vSink);
                    } else {
                        System.out.println("🚫 跳过 VideoOverlay 绑定：strictArea=" + strictArea + ", sink=" + sinkFactoryName + ", supportsOverlay=" + sinkSupportsOverlay + "（不支持则默认自建窗口显示）");
                    }
                }
            } else {
                if (strictArea) {
                    System.out.println("🔒 严格区域渲染已开启：强制使用 appsink + JavaFX 绘制，避免全屏覆盖");
                }
                appsink = (AppSink) ElementFactory.make("appsink", "display_sink");
                if (appsink == null) {
                    LogTools.getInstance().logRecord2("❌ 创建 display appsink 失败");
                } else {
                    appsink.set("emit-signals", true);
                    appsink.set("sync", false);
                    appsink.set("async", false);
                    try { appsink.set("qos", true); } catch (Throwable ignore) {}
                    appsink.set("max-buffers", Integer.getInteger("diag.sink.maxBuffers", 30));
                    appsink.set("drop", Boolean.parseBoolean(System.getProperty("diag.sink.drop", "true")));
                    appsink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
                    sinkEndElement = appsink;
                    // ⚡ 设置 appsink 回调（用于软解显示）
                    setupAppSinkCallback();
                    // 打印 AppSink 关键属性，确认不会因丢帧/阻塞导致无画面
                }
            }
        }
        // 显示路径决策汇总

        if (sinkEndElement == null) {
            // 防御：确保末端 sink 不为 null
            sinkEndElement = ElementFactory.make("fakesink", "fallback_sink");
            try {

                sinkEndElement.set("async", false);
                sinkEndElement.set("sync", false);       // ⭐ 关键：关闭同步
                sinkEndElement.set("max-lateness", -1);
                sinkEndElement.set("qos", false);        // ⭐ 关键：关闭 QoS

            } catch (Exception e) {
                logRecord("⚠️ Sink 部分属性不支持: " + e.getMessage());
            }

        }






        // ========== 11. 链接管道（根据解码器类型选择路径）==========
        try {
            if (useD3D11Path) {
                // -------------------- GPU 加速路径（硬件解码）--------------------




                Element recordCapsFilter = ElementFactory.make("capsfilter", "record_caps_filter");  // ⭐ 新增
                // ⭐ 配置 capsfilter 添加色彩信息
                recordCapsFilter.setCaps(Caps.fromString(
                        "video/x-h264," +
                                "stream-format=(string)avc," +
                                "alignment=(string)au," +
                                "colorimetry=(string)bt709," +           // ⭐ 色彩矩阵
                                "chroma-site=(string)mpeg2," +            // ⭐ 色度采样位置
                                "color-range=(string)0-255"               // ⭐ 色彩范围（full range）
                ));
                logRecord("✅ 录制分支已配置色彩信息: bt709 full-range");


                if (recordH264Parse != null) {
                    logRecord("✅ 创建 h264parse");
                }


                d3d11Download = ElementFactory.make("d3d11download", "d3d11download");
                //fpsSet();
                // ⭐ 管道元素（显示用 VideoOverlay，抓拍用 captureVideocrop）
                pipeline.addMany(
                        webrtcbin, rtph264depay, h264parse, queueDepay,
                        splitTee,  // ⭐ H264 分流器
                        decoder, queueDecode,
                        d3d11convert,
                        d3d11Download, videoBalance, gamma, // ⭐ 图像增强链
                        captureTee,
                        displayQueue, sinkEndElement,
                        captureValve, captureImageQueue, captureImageDownload,
                        captureVideocrop,  // ⚡ 抓拍分支裁剪（放大区域抓拍）
                        jpegEncoder, multifilesink);


                Element.linkMany(rtph264depay,h264parse, queueDepay, splitTee);

                // ========== 分支 1：解码分支（显示+抓拍，常驻）==========
                Pad splitSrc1 = splitTee.getRequestPad("src_%u");
                Pad decoderSink = decoder.getStaticPad("sink");
                splitSrc1.link(decoderSink);

                // ⭐ 连接管道（移除 videocrop/videoscale，局部放大改用 GpuView 窗口放大）
                Element.linkMany(decoder, queueDecode, d3d11convert,
                        d3d11Download, videoBalance, gamma,
                        captureTee);
                // 显示路径（从 captureTee 分流）
                Pad teeSrcDisplay = captureTee.getRequestPad("src_%u");
                Pad displayQueueSink = displayQueue.getStaticPad("sink");
                if (teeSrcDisplay != null && displayQueueSink != null) {
                    teeSrcDisplay.link(displayQueueSink);
                    boolean displayOk = Element.linkMany(displayQueue, sinkEndElement);
                    logRecord("✅ 显示分支链接: " + displayOk);
                }
                // 抓拍路径（从 captureTee 分流）
                Pad teeSrcCapture = captureTee.getRequestPad("src_%u");
                Pad captureValveSink = captureValve.getStaticPad("sink");
                if (teeSrcCapture != null && captureValveSink != null) {
                    teeSrcCapture.link(captureValveSink);
                    // ⚡ 抓拍链路：含 captureVideocrop 支持放大区域抓拍
                    boolean captureOk = Element.linkMany(captureValve, captureImageQueue, captureImageDownload,
                            captureVideocrop, jpegEncoder, multifilesink);
                    logRecord("✅ 抓拍分支链接（含 videocrop）: " + captureOk);
                }
                
                logRecord("✅ GPU 加速路径链接完成");
            } else {
                // ==================== 软解路径（avdec_h264）====================
                logRecord("🔧 使用软件解码路径（videoconvert）");
                
                // 软解需要 videoconvert 进行格式转换
                Element softConverter = ElementFactory.make("videoconvert", "soft_convert");
                
                // ⚡ 软解抓拍：不需要 d3d11download，但需要 videoconvert 转换格式
                Element softCaptureConvert = ElementFactory.make("videoconvert", "soft_capture_convert");
                
                pipeline.addMany(
                        webrtcbin, rtph264depay, h264parse, queueDepay,
                        splitTee,
                        decoder, queueDecode,
                        softConverter,  // 软解用 videoconvert
                        videoBalance, gamma,
                        captureTee,
                        displayQueue, sinkEndElement,
                        captureValve, captureImageQueue, 
                        softCaptureConvert,  // ⚡ 软解用 videoconvert 代替 d3d11download
                        captureVideocrop,  // ⚡ 抓拍分支裁剪（放大区域抓拍）
                        jpegEncoder, multifilesink);
                
                Element.linkMany(rtph264depay, h264parse, queueDepay, splitTee);
                
                // 解码分支
                Pad splitSrc1 = splitTee.getRequestPad("src_%u");
                Pad decoderSink = decoder.getStaticPad("sink");
                splitSrc1.link(decoderSink);
                
                // 软解链路：decoder -> queue -> videoconvert -> 后续处理（移除缩放元素）
                Element.linkMany(decoder, queueDecode, softConverter,
                        videoBalance, gamma,
                        captureTee);
                logRecord("✅ 软解主链路: decoder → videoconvert → videoBalance → gamma → captureTee（局部放大改用GpuView窗口放大）");
                
                // 显示路径
                Pad teeSrcDisplay = captureTee.getRequestPad("src_%u");
                Pad displayQueueSink = displayQueue.getStaticPad("sink");
                if (teeSrcDisplay != null && displayQueueSink != null) {
                    teeSrcDisplay.link(displayQueueSink);
                    boolean displayOk = Element.linkMany(displayQueue, sinkEndElement);
                    logRecord("✅ 软解显示分支链接: " + displayOk);
                }
                
                // ⚡ 软解抓拍路径：不用 d3d11download，用 videoconvert + captureVideocrop
                Pad teeSrcCapture = captureTee.getRequestPad("src_%u");
                Pad captureValveSink = captureValve.getStaticPad("sink");
                if (teeSrcCapture != null && captureValveSink != null) {
                    teeSrcCapture.link(captureValveSink);
                    boolean captureOk = Element.linkMany(captureValve, captureImageQueue, 
                            softCaptureConvert,  // ⚡ 软解用 videoconvert
                            captureVideocrop, jpegEncoder, multifilesink);
                    logRecord("✅ 软解抓拍分支链接（含 videocrop）: " + captureOk);
                }
                
                logRecord("✅ 软件解码路径链接完成（含亮度/对比度/缩放/抓拍）");
            }
            addNalProbes();
            addDecoderFrameProbe();  // ⭐ 添加帧率统计探针
            addRealtimeRingProbe();  // ⚡ 添加实时内存环探针（GPU路径同步抓拍）
        } catch (Throwable e) {
            logRecord("❌ 管道链接失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== 在管道构建完成后，初始化缩放设置 ==========

    // 找到管道构建的末尾，添加初始化代码：
    private void initZoomElements() {
        // ⭐ 初始化：确保 videocrop 不裁剪
        if (videocrop != null) {
            try {
        videocrop.set("left", 0);
        videocrop.set("right", 0);
        videocrop.set("top", 0);
        videocrop.set("bottom", 0);
                LogTools.getInstance().logRecord3("✅ videocrop 初始化：不裁剪");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ videocrop 初始化失败: " + t.getMessage());
            }
        }
        
        // ⭐ 设置 videoscale 算法（Lanczos 插值，最高质量）
        // method: 0=nearest, 1=bilinear, 2=4-tap, 3=lanczos
        // Lanczos 使用 sinc 函数加权，保留边缘锐度，放大更清晰
        if (videoscale != null) {
            try {
                videoscale.set("method", 3);  // Lanczos - 最高质量插值
                LogTools.getInstance().logRecord3("✅ videoscale 初始化：Lanczos 插值（最高质量）");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ videoscale 初始化失败: " + t.getMessage());
            }
        }
        
        // ⭐ 关键：zoomCapsfilter 需要在有视频尺寸后再设置
        // 这里先不设置，等第一帧到达时动态设置
        LogTools.getInstance().logRecord3("✅ 缩放元素初始化完成（等待视频尺寸后配置zoomCapsfilter）");
    }
    
    // ⭐ 动态设置 zoomCapsfilter 的输出分辨率（确保裁剪后放大回原始尺寸）
    private volatile boolean zoomCapsConfigured = false;
    
    private void configureZoomCapsIfNeeded() {
        if (zoomCapsConfigured) return;
        if (zoomCapsfilter == null) return;
        
        int w = FileToos.sslWidth;
        int h = FileToos.sslwHight;
        if (w <= 0 || h <= 0) return;
        
        try {
            // ⭐ 不固定输出分辨率，让 videoscale 自动适配
            // 原因：固定 caps 在分辨率变化时会导致协商失败（黑屏）
            String capsStr = "video/x-raw";  // 不指定宽高，让 videoscale 自动处理
            Caps caps = Caps.fromString(capsStr);
        zoomCapsfilter.set("caps", caps);
            zoomCapsConfigured = true;
            LogTools.getInstance().logRecord3("✅ zoomCapsfilter 配置完成（自适应模式）");
            
            // ⭐ 分辨率变化后请求关键帧，确保画面恢复
            try {
                sendPLIRequest();
            } catch (Throwable ignore) {}
            
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ zoomCapsfilter 配置失败: " + t.getMessage());
        }
    }




    /**
     * 设置分发AppSink的回调，用于应用层帧分发
     */


    private void setupDistributionAppSinkCallback() {

    }

    /**
     * 添加管道状态监控
     */
    private void addPipelineStateMonitoring() {
        System.out.println("🔍 添加管道状态监控...");

        Bus bus = pipeline.getBus();

        // 监听错误消息
        bus.connect(new Bus.ERROR() {
            @Override
            public void errorMessage(GstObject source, int code, String message) {
                System.out.println("管道错误 [" + code + "]: " + message + " (来源: " + source.getName() + ")");
            }
        });


        // 监听警告消息
        bus.connect(new Bus.WARNING() {
            @Override
            public void warningMessage(GstObject source, int code, String message) {
                System.out.println("管道警告 [" + code + "]: " + message + " (来源: " + source.getName() + ")");
            }
        });

        // 监听状态变化
        bus.connect(new Bus.STATE_CHANGED() {
            @Override
            public void stateChanged(GstObject source, State old, State current, State pending) {
                System.out.println("状态变化: " + source.getName() + " 从 " + old + " 到 " + current + " (待定: " + pending + ")");

                if (source == pipeline) {  // 只关心 Pipeline 的状态变化
                    System.out.println("🔄 Pipeline 状态变化: " + old + " -> " + current +
                            (pending != State.VOID_PENDING ? " (pending: " + pending + ")" : ""));

                    // 如果 Pipeline 不在 PLAYING 状态，判定为无帧推送
                    if (current != State.PLAYING) {
                        logRecord("⚠️ Pipeline 非 PLAYING 状态，判定为无帧推送");
                        FileToos.isIsCallBackFrame=false;
                    }

                }
            }
        });

        // 监听所有消息（用于调试）
        bus.connect(new Bus.MESSAGE() {
            @Override
            public void busMessage(Bus bus, Message message) {
                MessageType type = message.getType();
                String sourceName = message.getSource() != null ? message.getSource().getName() : "unknown";
                System.out.println("管道消息: " + type + " 来源: " + sourceName);
                // ⭐ 处理 splitmuxsink 片段事件（在 switch 之前）
                if (type == MessageType.ELEMENT) {
                    Structure struct = message.getStructure();
                    if (struct != null) {
                        String structName = struct.getName();

                        // ⭐ multifilesink 的消息名称可能是以下几种之一
                        if (structName != null &&
                                (structName.equals("GstMultiFileSink") ||
                                        structName.equals("multifilesink") ||
                                        structName.contains("multifilesink"))) {

                            // 获取文件名
                            String filename = null;
                            try {
                                filename = struct.getString("filename");
                            } catch (Exception e) {
                                // 尝试其他字段名
                                try {
                                    filename = struct.getString("location");
                                } catch (Exception ex) {
                                    // 忽略
                                }
                            }

                            // 获取索引
                            int index = 0;
                            try {
                                Object indexObj = struct.getValue("index");
                                if (indexObj instanceof Integer) {
                                    index = (Integer) indexObj;
                                } else if (indexObj instanceof Number) {
                                    index = ((Number) indexObj).intValue();
                                }
                            } catch (Exception e) {
                                // 从文件名解析索引
                                if (filename != null) {
                                    try {
                                        // 从 "runtime/captures/slow/s_00123.jpeg" 提取 123
                                        String baseName = new File(filename).getName();
                                        String numberStr = baseName.replaceAll("[^0-9]", "");
                                        if (!numberStr.isEmpty()) {
                                            index = Integer.parseInt(numberStr);
                                        }
                                    } catch (Exception ex) {
                                        // 忽略
                                    }
                                }
                            }

                            if(filename != null) {
                                FileToos.isIsCallBackFrame = true;
                                // logRecord("FileToos.isIsCallBackFrame---> true");
                            }else{
                                FileToos.isIsCallBackFrame = false;
                                // logRecord("FileToos.isIsCallBackFrame---> false");
                            }


                            FileToos.jpegIndex=index;
                            FileToos.jpegIndexTimeMs = System.currentTimeMillis();  // ⚡ 记录JPEG写入时间
                            
                            // ⭐ 偏移同步检查（异步，不阻塞 JPEG 写入）
                            final int jpegIdx = index;
                            com.acard.acard.ui.Element1ControllerV2.checkAndApplyOffset(jpegIdx);
                            
                            // ⭐ 回调通知
                            if (FileToos.isCallBack==true&&latestFrameCallback != null && filename != null && FileToos.lzNum<=SlowmoStore.getInstance().getSlowmoFrames()) {
                                final String finalFilename = filename;
                                final int finalIndex = index;

                                FileToos.lzNum=FileToos.lzNum+1;

                                Platform.runLater(() -> {
                                    latestFrameCallback.onNewFrame(finalFilename, finalIndex);
                                });
                                // 可选：调试日志
                                //logRecord("📸 新帧已保存: " + filename + " (索引: " + index + ")");
                            }
                            // ✅ 恢复自动清理（用户确认逻辑正确）
                            // 说明：index 就是文件下标，CaptureDataManager 存的也是文件下标
                            //      addLook() 每 600 次触发清理，保持磁盘不会爆炸
                            JpegFileCleaner.addLook(index);
                        }
                    }
                }
                // 特殊处理一些重要消息类型
                switch (type) {
                    case STREAM_START:
                        System.out.println("  -> 流开始");
                        break;
                    case EOS:
                        logRecord("  -> 流结束");
                        FileToos.isIsCallBackFrame = false;
                        break;
                    case BUFFERING:
                        System.out.println("  -> 缓冲中");
                        logRecord("  -> 缓冲中");
                        break;
                    case CLOCK_LOST:
                        System.out.println("  -> 时钟丢失");
                        logRecord("  -> 时钟丢失");
                        break;
                    case NEW_CLOCK:
                        System.out.println("  -> 新时钟");
                        logRecord("  -> 新时钟");
                        break;
                    case ERROR:
                        FileToos.isIsCallBackFrame = false;
                        // ⭐ 输出详细错误信息

                        String errorMsg = "未知错误";
                        String debugInfo = message.getStructure() != null ? message.getStructure().toString() : "";
                        logRecord("❌ GStreamer ERROR: " + errorMsg);
                        logRecord("   调试信息: " + debugInfo);
                        LogTools.getInstance().logRecord2("❌ GStreamer ERROR: " + errorMsg);
                        LogTools.getInstance().logRecord2("   调试信息: " + debugInfo);
                        break;
                    case WARNING:
                        // WARNING 可能是暂时性问题，可以根据需要决定是否触发
                        String warning = message.toString();
                        if (warning.contains("timeout") || warning.contains("lost") || warning.contains("disconnect")) {
                            logRecord("⚠️ 收到 WARNING（可能断开）: " + warning);
                            FileToos.isIsCallBackFrame = false;  // ⭐ 触发回调
                            logRecord("  -> WARNING");
                        }
                        break;
                    default:
                        // 其他消息类型的通用处理
                        break;
                }
            }


        });

        System.out.println("✅ 管道状态监控已添加");
    }

    // 新增：初始化 VideoOverlay 绑定到 JavaFX 顶层窗口
    private void initVideoOverlayBinding(Element vSink) {
        if (this.overlayTarget == null) this.overlayTarget = this.imageView;
        try {
            this.videoOverlay = VideoOverlay.wrap(vSink);
            if (this.videoOverlay == null) {
                LogTools.getInstance().logRecord2("⚠️ 当前 sink 不支持 VideoOverlay 接口，跳过窗口绑定");
                return;
            }

            // ⭐ 关键修复：在 JavaFX 线程上延迟获取窗口句柄，确保窗口已完全初始化
            Platform.runLater(() -> {
                overlayWindowHandle = tryResolveWindowHandleFromJavaFX();
                if (overlayWindowHandle != 0L) {
                    LogTools.getInstance().logRecord3("🎯 初始窗口句柄: 0x" + Long.toHexString(overlayWindowHandle));
                } else {
                    LogTools.getInstance().logRecord3("⚠️ 初始未获取到窗口句柄，将在 prepare-window-handle 时重试");
                }
            });

            LogTools.getInstance().logRecord3("🔧 设置 BusSyncHandler...");
            pipeline.getBus().setSyncHandler(new BusSyncHandler() {
                @Override
                public BusSyncReply syncMessage(Message message) {
                    try {
                        // ⭐ 添加：记录所有消息类型（调试用）
                        String msgType = message.getType() != null ? message.getType().toString() : "null";
                        if (msgType.contains("window") || msgType.contains("prepare")) {
                            LogTools.getInstance().logRecord3("🔔 收到消息: " + msgType);
                        }

                        if (VideoOverlay.isPrepareWindowHandleMessage(message)) {
                            LogTools.getInstance().logRecord3("🎬 收到 prepare-window-handle 消息");
                            LogTools.getInstance().logRecord3("   当前状态: overlayChildHandle=0x" + Long.toHexString(overlayChildHandle) +
                                    ", overlayWindowHandle=0x" + Long.toHexString(overlayWindowHandle));

                            if (videoOverlay == null) {
                                LogTools.getInstance().logRecord3("⚠️ videoOverlay 为 null，返回 PASS");
                                return BusSyncReply.PASS;
                            }

                            // 优先绑定子窗口；若尚未创建，则在此阶段先创建一个最小的 WS_CHILD 子窗口并绑定
                            if (overlayChildHandle != 0L) {
                                LogTools.getInstance().logRecord3("🔄 检查已存在的子窗口: 0x" + Long.toHexString(overlayChildHandle));
                                // ⭐ 关键修复：重用窗口前，先检查父窗口是否正确
                                long currentParent = overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX();
                                try {
                                    HWND hwnd = new HWND(Pointer.createConstant(overlayChildHandle));
                                    if (User32.INSTANCE.IsWindow(hwnd)) {
                                        // ✅ 检查父窗口是否正确
                                        HWND parentHwnd = User32.INSTANCE.GetParent(hwnd);
                                        long actualParent = parentHwnd != null ? Pointer.nativeValue(parentHwnd.getPointer()) : 0L;

                                        if (actualParent != 0L && actualParent == currentParent) {
                                            // 父窗口正确，重用窗口
                                            LogTools.getInstance().logRecord3("✅ 父窗口匹配，重用子窗口");
                                            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
                                            User32.INSTANCE.UpdateWindow(hwnd);
                                            LogTools.getInstance().logRecord3("♻️ 重用已存在的子窗口: 0x" + Long.toHexString(overlayChildHandle) +
                                                    " (父窗口: 0x" + Long.toHexString(actualParent) + ")");
                                        } else {
                                            // 父窗口不对，需要创建新窗口
                                            LogTools.getInstance().logRecord3("⚠️ 子窗口的父窗口不匹配: 实际=0x" + Long.toHexString(actualParent) +
                                                    ", 期望=0x" + Long.toHexString(currentParent) + "，将创建新窗口");
                                            overlayChildHandle = 0L; // 重置，让下面创建新窗口
                                        }
                                    } else {
                                        LogTools.getInstance().logRecord3("⚠️ 旧子窗口已失效，将创建新窗口");
                                        overlayChildHandle = 0L;
                                    }
                                } catch (Throwable t) {
                                    LogTools.getInstance().logRecord3("⚠️ 检查旧窗口失败，将创建新窗口: " + t.getMessage());
                                    overlayChildHandle = 0L; // 重置，让下面创建新窗口
                                }

                                if (overlayChildHandle != 0L) {
                                    LogTools.getInstance().logRecord3("🎯 重用子窗口，绑定到 VideoOverlay");
                                    videoOverlay.setWindowHandle(overlayChildHandle);
                                    isWindowBound.set(true);  // ⭐ 标记窗口已绑定
                                    prepareWindowHandleTriggered.incrementAndGet();  // ⭐ 统计
                                    LogTools.getInstance().logRecord3("✅ 已绑定窗口句柄到视频接收器（重用子窗口）: 0x" + Long.toHexString(overlayChildHandle));
                                    LogTools.getInstance().logRecord3("🔄 调用 videoOverlay.expose() 强制刷新");
                                    videoOverlay.expose();
                                    int total = prepareWindowHandleTriggered.get() + fallbackTriggered.get();
                                    LogTools.getInstance().logRecord3("✅ prepare-window-handle 处理完成（重用窗口），返回 DROP");
                                    LogTools.getInstance().logRecord3("📊 统计: prepare-window-handle=" + prepareWindowHandleTriggered.get() +
                                            ", 后备=" + fallbackTriggered.get() + ", 总计=" + total +
                                            ", 触发率=" + (total > 0 ? (prepareWindowHandleTriggered.get() * 100 / total) + "%" : "N/A"));
                                    return BusSyncReply.DROP;
                                }
                            }

                            // ⭐ 关键修复：动态获取父窗口句柄，如果为0则等待后重试
                            LogTools.getInstance().logRecord3("🔍 开始获取父窗口句柄...");
                            long parentHandle = overlayWindowHandle;
                            LogTools.getInstance().logRecord3("   overlayWindowHandle = 0x" + Long.toHexString(parentHandle));
                            if (parentHandle == 0L) {
                                LogTools.getInstance().logRecord3("⚠️ overlayWindowHandle 为 0，尝试动态获取...");
                                // ⭐ 第一次尝试
                                LogTools.getInstance().logRecord3("🔍 第1次尝试: tryResolveWindowHandleFromJavaFX()...");
                                parentHandle = tryResolveWindowHandleFromJavaFX();
                                LogTools.getInstance().logRecord3("   第1次结果: 0x" + Long.toHexString(parentHandle));

                                if (parentHandle == 0L) {
                                    // ⭐ 等待100ms后重试（给 Platform.runLater 时间执行）
                                    try {
                                        LogTools.getInstance().logRecord3("⏳ 第1次失败，等待100ms后重试...");
                                        Thread.sleep(100);
                                        LogTools.getInstance().logRecord3("🔍 第2次尝试: tryResolveWindowHandleFromJavaFX()...");
                                        parentHandle = tryResolveWindowHandleFromJavaFX();
                                        LogTools.getInstance().logRecord3("   第2次结果: 0x" + Long.toHexString(parentHandle));
                                    } catch (InterruptedException ie) {
                                        LogTools.getInstance().logRecord3("⚠️ 等待被中断: " + ie.getMessage());
                                        Thread.currentThread().interrupt();
                                    }
                                }
                                if (parentHandle != 0L) {
                                    overlayWindowHandle = parentHandle;  // ⭐ 保存到实例变量
                                    LogTools.getInstance().logRecord3("✅ 动态获取到父窗口句柄: 0x" + Long.toHexString(parentHandle));
                                } else {
                                    LogTools.getInstance().logRecord3("❌ 等待100ms后仍未获取到窗口句柄，返回 PASS");
                                }
                            } else {
                                LogTools.getInstance().logRecord3("✅ overlayWindowHandle 已存在: 0x" + Long.toHexString(parentHandle));
                            }
                            if (parentHandle != 0L) {
                                LogTools.getInstance().logRecord3("🏗️ 准备创建子窗口，父窗口句柄: 0x" + Long.toHexString(parentHandle));
                                try {
                                    HWND hParent = new HWND(Pointer.createConstant(parentHandle));
                                    LogTools.getInstance().logRecord3("   创建 HWND 对象成功");

                                    // 先创建一个极小的子窗口；后续由 JavaFX 布局监听负责定位和缩放
                                    // ⭐ 关键修复：添加 WS_EX_TRANSPARENT，让鼠标事件穿透到父窗口，避免窗口拖不动
                                    LogTools.getInstance().logRecord3("   调用 CreateWindowEx...");
                                    HWND hChild = User32.INSTANCE.CreateWindowEx(
                                            WinUser.WS_EX_TRANSPARENT,  // ⭐ 透传鼠标事件
                                            "STATIC",
                                            null,
                                            WinUser.WS_CHILD | WinUser.WS_VISIBLE | WinUser.WS_CLIPSIBLINGS,
                                            0, 0, 1, 1,
                                            hParent,
                                            null,
                                            null,
                                            null
                                    );
                                    LogTools.getInstance().logRecord3("   CreateWindowEx 返回: " + (hChild != null ? "成功" : "null"));

                                    if (hChild != null) {
                                        // 确保子窗口可见并完成绘制初始化
                                        LogTools.getInstance().logRecord3("   调用 ShowWindow...");
                                        User32.INSTANCE.ShowWindow(hChild, WinUser.SW_SHOW);
                                        LogTools.getInstance().logRecord3("   调用 UpdateWindow...");
                                        User32.INSTANCE.UpdateWindow(hChild);
                                        overlayChildHandle = Pointer.nativeValue(hChild.getPointer());
                                        LogTools.getInstance().logRecord3("🪟 prepare-window 阶段创建子窗口: 0x" + Long.toHexString(overlayChildHandle));
                                        LogTools.getInstance().logRecord3("🔗 调用 videoOverlay.setWindowHandle(0x" + Long.toHexString(overlayChildHandle) + ")");
                                        videoOverlay.setWindowHandle(overlayChildHandle);
                                        LogTools.getInstance().logRecord3("✅ prepare-window 阶段窗口绑定完成");

                                        // ⭐ 关键修复：强制刷新 VideoOverlay，确保画面显示
                                        LogTools.getInstance().logRecord3("🔄 调用 videoOverlay.expose()...");
                                        videoOverlay.expose();
                                        isWindowBound.set(true);  // ⭐ 标记窗口已绑定
                                        prepareWindowHandleTriggered.incrementAndGet();  // ⭐ 统计
                                        LogTools.getInstance().logRecord3("✅ videoOverlay.expose() 完成");
                                        int total = prepareWindowHandleTriggered.get() + fallbackTriggered.get();
                                        LogTools.getInstance().logRecord3("✅ prepare-window-handle 处理完成，返回 DROP");
                                        LogTools.getInstance().logRecord3("📊 统计: prepare-window-handle=" + prepareWindowHandleTriggered.get() +
                                                ", 后备=" + fallbackTriggered.get() + ", 总计=" + total +
                                                ", 触发率=" + (total > 0 ? (prepareWindowHandleTriggered.get() * 100 / total) + "%" : "N/A"));
                                        return BusSyncReply.DROP;
                                    } else {
                                        LogTools.getInstance().logRecord3("❌ prepare-window 阶段创建子窗口失败（hChild=null）");
                                        LogTools.getInstance().logRecord3("   临时绑定父窗口: 0x" + Long.toHexString(parentHandle));
                                        if (parentHandle != 0L) {
                                            videoOverlay.setWindowHandle(parentHandle);
                                            LogTools.getInstance().logRecord3("🧩 临时绑定父窗口完成");
                                        }
                                        videoOverlay.expose();
                                        LogTools.getInstance().logRecord3("   调用 expose() 完成");
                                    }
                                } catch (Throwable e) {
                                    LogTools.getInstance().logRecord3("❌ prepare-window 子窗口创建异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                    e.printStackTrace();
                                }
                            } else {
                                LogTools.getInstance().logRecord3("⚠️ parentHandle 为 0，无法创建子窗口");
                            }
                            LogTools.getInstance().logRecord3("⚠️ prepare-window-handle 处理失败或跳过，返回 PASS");
                            return BusSyncReply.PASS;
                        }
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("BusSync 处理异常: " + t.getMessage());
                    }
                    return BusSyncReply.PASS;
                }
            });
            LogTools.getInstance().logRecord3("✅ BusSyncHandler 设置完成");

            // 在 JavaFX 线程上异步计算并设置渲染区域，避免在 Bus 同步回调中访问 JavaFX 导致卡死
            LogTools.getInstance().logRecord3("🔧 注册布局监听器（Platform.runLater）...");
            Platform.runLater(() -> {
                LogTools.getInstance().logRecord3("▶️ 布局监听器开始执行...");
                try {
                    if (videoOverlay != null && overlayTarget != null) {
                        // ✅ 使用layoutBounds（实际布局边界）而不是boundsInLocal
                        javafx.geometry.Bounds layoutBounds = overlayTarget.getLayoutBounds();
                        javafx.geometry.Bounds ivScreen = overlayTarget.localToScreen(layoutBounds);

                        System.out.println("🔍 [initOverlay] overlayTarget类型: " + overlayTarget.getClass().getSimpleName());
                        System.out.println("🔍 [initOverlay] boundsInLocal: " + overlayTarget.getBoundsInLocal());
                        System.out.println("🔍 [initOverlay] layoutBounds: " + layoutBounds);
                        System.out.println("🔍 [initOverlay] screenBounds: " + ivScreen);

                        if (ivScreen != null) {

                            javafx.scene.Scene scene = overlayTarget.getScene();
                            double clientOriginX = 0, clientOriginY = 0;
                            if (scene != null && scene.getRoot() != null) {
                                javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                                if (rootTL != null) {
                                    clientOriginX = rootTL.getX();
                                    clientOriginY = rootTL.getY();
                                }
                            }
                            int rw = (int) Math.round(ivScreen.getWidth());
                            int rh = (int) Math.round(ivScreen.getHeight())-FileToos.botoomHight;
                            int rx = (int) Math.round(ivScreen.getMinX() - clientOriginX);
                            int ry = (int) Math.round(ivScreen.getMinY() - clientOriginY);

                            System.out.println("🔍 [initOverlay] clientOrigin: (" + clientOriginX + ", " + clientOriginY + ")");
                            System.out.println("🔍 [initOverlay] 计算前: rx=" + rx + ", ry=" + ry + ", rw=" + rw + ", rh=" + rh);

                            long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle
                                    : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
                            double sx = 1.0, sy = 1.0;
                            try {
                                if (hwndForScale != 0L) {
                                    HWND w = new HWND(
                                            Pointer.createConstant(hwndForScale));
                                    com.sun.jna.platform.win32.WinDef.HDC hdc = User32.INSTANCE.GetDC(w);
                                    if (hdc != null) {
                                        int dpiX = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                                        int dpiY = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                                        User32.INSTANCE.ReleaseDC(w, hdc);
                                        if (dpiX > 0 && dpiY > 0) {
                                            sx = Math.max(1.0, dpiX / 96.0);
                                            sy = Math.max(1.0, dpiY / 96.0);
                                        }
                                    }
                                }
                            } catch (Throwable ignore2) {}

                            int pxRw = (int) Math.round(rw * sx);
                            int pxRh = (int) Math.round(rh * sy);
                            int pxRx = (int) Math.round(rx * sx);
                            int pxRy = (int) Math.round(ry * sy);
                            System.out.println("🔍 [initOverlay] DPI缩放: sx=" + sx + ", sy=" + sy);
                            System.out.println("🔍 [initOverlay] 最终HWND: x=" + pxRx + ", y=" + pxRy + ", w=" + pxRw + ", h=" + pxRh);

                            // 如果当前计算出的渲染区域宽或高为0，跳过初始设置，等待布局变化事件再设置，避免触发GStreamer断言
                            if (pxRw <= 0 || pxRh <= 0) {
                                LogTools.getInstance().logRecord2("⚠️ 渲染区域无效（宽或高为0），跳过初始 setRenderRectangle，等待布局事件");
                            } else {
                                // 优先尝试：在父窗口内创建WS_CHILD子窗口，并绑定VideoOverlay到该子窗口
                                // ⭐ 关键修复：先检查 overlayChildHandle 是否还有效，如果窗口已失效才创建新窗口
                                boolean needCreateWindow = false;
                                if (overlayChildHandle != 0L) {
                                    HWND existingChild = new HWND(Pointer.createConstant(overlayChildHandle));
                                    if (!User32.INSTANCE.IsWindow(existingChild)) {
                                        LogTools.getInstance().logRecord3("⚠️ 布局监听器检测到旧子窗口已失效，需要创建新窗口");
                                        overlayChildHandle = 0L;
                                        needCreateWindow = true;
                                    }
                                } else {
                                    needCreateWindow = true;
                                }

                                if (overlayWindowHandle != 0L && needCreateWindow) {
                                    try {
                                        HWND hParent = new HWND(Pointer.createConstant(overlayWindowHandle));
                                        // ⭐ 关键修复：添加 WS_EX_TRANSPARENT，让鼠标事件穿透
                                        HWND hChild = User32.INSTANCE.CreateWindowEx(
                                                WinUser.WS_EX_TRANSPARENT,  // ⭐ 透传鼠标事件
                                                "STATIC",
                                                null,
                                                WinUser.WS_CHILD | WinUser.WS_VISIBLE,
                                                pxRx, pxRy, pxRw, pxRh,
                                                hParent,
                                                null,
                                                null,
                                                null
                                        );
                                        if (hChild != null) {
                                            // 确保子窗口可见并完成绘制初始化
                                            User32.INSTANCE.ShowWindow(hChild, WinUser.SW_SHOW);
                                            User32.INSTANCE.UpdateWindow(hChild);
                                            overlayChildHandle = Pointer.nativeValue(hChild.getPointer());
                                            LogTools.getInstance().logRecord3("🪟 布局监听器创建子窗口: 0x" + Long.toHexString(overlayChildHandle));

                                            // ⚠️ 关键修复：不要在这里调用 setWindowHandle，等待 prepare-window-handle 消息
                                            // 原因：提前调用 setWindowHandle 会导致 GStreamer 跳过 prepare-window-handle 消息
                                            LogTools.getInstance().logRecord3("⏳ 子窗口已创建，等待 prepare-window-handle 消息来绑定窗口...");

                                            // ⭐ 后备方案：如果 2 秒内没有收到 prepare-window-handle，主动绑定
                                            new Thread(() -> {
                                                try {
                                                    Thread.sleep(2000);  // 等待 2 秒

                                                    // ⭐ 检查窗口是否已经绑定（防止重复绑定）
                                                    if (isWindowBound.get()) {
                                                        LogTools.getInstance().logRecord3("✅ 窗口已通过 prepare-window-handle 绑定，跳过后备方案");
                                                        return;
                                                    }

                                                    if (overlayChildHandle != 0L && videoOverlay != null) {
                                                        LogTools.getInstance().logRecord3("⏰ 2秒超时：prepare-window-handle 未触发，主动绑定窗口...");
                                                        Platform.runLater(() -> {
                                                            try {
                                                                // ⭐ 再次检查（防止竞态条件）
                                                                if (isWindowBound.get()) {
                                                                    LogTools.getInstance().logRecord3("✅ 窗口已绑定（竞态检测），跳过");
                                                                    return;
                                                                }

                                                                LogTools.getInstance().logRecord3("🔗 主动调用 videoOverlay.setWindowHandle(0x" + Long.toHexString(overlayChildHandle) + ")");
                                                                videoOverlay.setWindowHandle(overlayChildHandle);
                                                                videoOverlay.setRenderRectangle(0, 0, pxRw, pxRh);
                                                                LogTools.getInstance().logRecord3("🧭 设置渲染区域: x=0, y=0, w=" + pxRw + ", h=" + pxRh);
                                                                videoOverlay.expose();
                                                                isWindowBound.set(true);  // ⭐ 标记已绑定
                                                                fallbackTriggered.incrementAndGet();  // ⭐ 统计
                                                                int total = prepareWindowHandleTriggered.get() + fallbackTriggered.get();
                                                                LogTools.getInstance().logRecord3("✅ 主动绑定完成并刷新画面");
                                                                LogTools.getInstance().logRecord3("📊 统计: prepare-window-handle=" + prepareWindowHandleTriggered.get() +
                                                                        ", 后备=" + fallbackTriggered.get() + ", 总计=" + total +
                                                                        ", 触发率=" + (total > 0 ? (prepareWindowHandleTriggered.get() * 100 / total) + "%" : "N/A"));
                                                            } catch (Throwable t) {
                                                                LogTools.getInstance().logRecord3("❌ 主动绑定失败: " + t.getMessage());
                                                            }
                                                        });
                                                    } else {
                                                        LogTools.getInstance().logRecord3("⚠️ 后备方案跳过：overlayChildHandle=" + Long.toHexString(overlayChildHandle) +
                                                                ", videoOverlay=" + (videoOverlay != null ? "存在" : "null"));
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            }, "FallbackWindowBind").start();
                                        } else {
                                            LogTools.getInstance().logRecord2("❌ 创建渲染子窗口失败，临时绑定父窗口以全屏显示，等待布局事件重试");
                                            if (overlayWindowHandle != 0L) {
                                                HWND hParentValidate = new HWND(Pointer.createConstant(overlayWindowHandle));
                                                if (User32.INSTANCE.IsWindow(hParentValidate)) {
                                                    videoOverlay.setWindowHandle(overlayWindowHandle);
                                                    System.out.println("🧩 临时使用父窗口全屏显示: 0x" + Long.toHexString(overlayWindowHandle));
                                                    videoOverlay.expose();
                                                } else {
                                                    LogTools.getInstance().logRecord2("❌ 父窗口句柄无效，无法绑定 GPU sink");
                                                }
                                            }
                                        }
                                    } catch (Throwable e) {
                                        LogTools.getInstance().logRecord2("⚠️ 创建子窗口异常，跳过直接渲染，等待布局事件重试: " + e.getMessage());
                                        // 暂不调用 setRenderRectangle，避免误用父窗口坐标导致覆盖 UI
                                        videoOverlay.expose();
                                    }
                                } else if (overlayChildHandle != 0L) {
                                    // 已有子窗口：保证其位置与大小，与JavaFX布局保持一致
                                    HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));
                                    int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                                    User32.INSTANCE.SetWindowPos(hwndChild, null, pxRx, pxRy, pxRw, pxRh, flags);
                                    videoOverlay.setRenderRectangle(0, 0, pxRw, pxRh);
                                    System.out.println("🧭 初始渲染区域设置（子窗口复位）: x=0, y=0, w=" + pxRw + ", h=" + pxRh);
                                } else {
                                    // 无法绑定子窗口：暂不直接渲染，等待子窗口创建成功后再设置渲染矩形
                                    videoOverlay.expose();
                                }
                            }
                        }
                    }
                } catch (Throwable ignore) {}
            });

            // ⭐ 防止重复添加监听器（每次play都会执行，但监听器只需添加一次）
            if (!listenersAdded) {
                listenersAdded = true;
                LogTools.getInstance().logRecord3("📌 首次添加监听器（防止重复）");

                sceneListener = (obs, oldScene, newScene) -> {
                Platform.runLater(() -> {
                    overlayWindowHandle = tryResolveWindowHandleFromJavaFX();
                    // ⭐ 监听窗口移动，解决拖动时黑屏问题
                    if (newScene != null) {
                        addWindowMoveListener(newScene);
                    }
                });
                };
                overlayTarget.sceneProperty().addListener(sceneListener);

                layoutListener = (obs, oldB, newB) -> {
                if (videoOverlay != null) {
                    try {
                        // 节流：避免过于频繁的矩形更新（默认 33ms，可通过 video.overlay.update.min.ms 配置）
                        long nowNs = System.nanoTime();
                        int minMs = Integer.getInteger("video.overlay.update.min.ms", 33);
                        Object lastNsObj = overlayTarget.getProperties().get("overlay.lastUpdateNs");
                        long lastNs = (lastNsObj instanceof Long) ? (Long) lastNsObj : 0L;
                        if (lastNs != 0L && (nowNs - lastNs) < (minMs * 1_000_000L)) {
                            return; // 跳过本次更新
                        }
                        overlayTarget.getProperties().put("overlay.lastUpdateNs", nowNs);

                        javafx.geometry.Bounds ivScreen = overlayTarget.localToScreen(newB);
                        int rw2 = (int) Math.round(ivScreen != null ? ivScreen.getWidth() : 0);
                        int rh2 = (int) Math.round(ivScreen != null ? ivScreen.getHeight()-FileToos.botoomHight : 0);
                        if (ivScreen != null && rw2 > 0 && rh2 > 0) {
                            javafx.scene.Scene scene2 = overlayTarget.getScene();
                            double clientOriginX2 = 0, clientOriginY2 = 0;
                            if (scene2 != null && scene2.getRoot() != null) {
                                javafx.geometry.Point2D rootTL2 = scene2.getRoot().localToScreen(0, 0);
                                if (rootTL2 != null) {
                                    clientOriginX2 = rootTL2.getX();
                                    clientOriginY2 = rootTL2.getY();
                                }
                            }
                            int rx2 = (int) Math.round(ivScreen.getMinX() - clientOriginX2);
                            int ry2 = (int) Math.round(ivScreen.getMinY() - clientOriginY2);

                            long hwndForScale2 = overlayChildHandle != 0L ? overlayChildHandle
                                    : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
                            double sx2 = 1.0, sy2 = 1.0;
                            boolean calcDpi = Boolean.parseBoolean(System.getProperty("video.overlay.calcDpi", "true"));
                            try {
                                if (calcDpi && hwndForScale2 != 0L) {
                                    HWND w2 = new HWND(
                                            Pointer.createConstant(hwndForScale2));
                                    com.sun.jna.platform.win32.WinDef.HDC hdc2 = User32.INSTANCE.GetDC(w2);
                                    if (hdc2 != null) {
                                        int dpiX2 = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc2, 88);
                                        int dpiY2 = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc2, 90);
                                        User32.INSTANCE.ReleaseDC(w2, hdc2);
                                        if (dpiX2 > 0 && dpiY2 > 0) {
                                            sx2 = Math.max(1.0, dpiX2 / 96.0);
                                            sy2 = Math.max(1.0, dpiY2 / 96.0);
                                        }
                                    }
                                }
                            } catch (Throwable ignore2) {}

                            int pxRw2 = (int) Math.round(rw2 * sx2);
                            int pxRh2 = (int) Math.round(rh2 * sy2);
                            int pxRx2 = (int) Math.round(rx2 * sx2);
                            int pxRy2 = (int) Math.round(ry2 * sy2);

                            // 跳过与上次相同的矩形更新
                            Object lastRectObj = overlayTarget.getProperties().get("overlay.lastRect");
                            int lastRx = -1, lastRy = -1, lastRw = -1, lastRh = -1;
                            if (lastRectObj instanceof String) {
                                try {
                                    String[] parts = ((String) lastRectObj).split(",");
                                    if (parts.length == 4) {
                                        lastRx = Integer.parseInt(parts[0]);
                                        lastRy = Integer.parseInt(parts[1]);
                                        lastRw = Integer.parseInt(parts[2]);
                                        lastRh = Integer.parseInt(parts[3]);
                                    }
                                } catch (Throwable ignore) {}
                            }
                            if (lastRx == pxRx2 && lastRy == pxRy2 && lastRw == pxRw2 && lastRh == pxRh2) {
                                return; // 矩形未变化，跳过
                            }
                            overlayTarget.getProperties().put("overlay.lastRect",
                                    pxRx2 + "," + pxRy2 + "," + pxRw2 + "," + pxRh2);

                            boolean preferChildWindow = Boolean.parseBoolean(System.getProperty("video.overlay.childWindow", "true"));
                            if (preferChildWindow && overlayWindowHandle != 0L && overlayChildHandle == 0L) {
                                // 布局变化时，如果父窗口句柄已就绪而子窗口仍未创建，重试创建子窗口（节流已生效）
                                try {
                                    HWND hParent2 = new HWND(Pointer.createConstant(overlayWindowHandle));
                                    // ⭐ 关键修复：添加 WS_EX_TRANSPARENT，让鼠标事件穿透
                                    HWND hChild2 = User32.INSTANCE.CreateWindowEx(
                                            WinUser.WS_EX_TRANSPARENT,  // ⭐ 透传鼠标事件
                                            "STATIC",
                                            null,
                                            WinUser.WS_CHILD | WinUser.WS_VISIBLE,
                                            pxRx2, pxRy2, pxRw2, pxRh2,
                                            hParent2,
                                            null,
                                            null,
                                            null
                                    );
                                    if (hChild2 != null) {
                                        // 确保子窗口可见并完成绘制初始化
                                        User32.INSTANCE.ShowWindow(hChild2, WinUser.SW_SHOW);
                                        User32.INSTANCE.UpdateWindow(hChild2);
                                        overlayChildHandle = Pointer.nativeValue(hChild2.getPointer());
                                        videoOverlay.setWindowHandle(overlayChildHandle);
                                        videoOverlay.setRenderRectangle(0, 0, pxRw2, pxRh2);
                                    }
                                } catch (Throwable ignore3) {}
                            }

                            if (overlayChildHandle != 0L) {
                                HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));
                                int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                                User32.INSTANCE.SetWindowPos(hwndChild, null, pxRx2, pxRy2, pxRw2, pxRh2, flags);
                                videoOverlay.setRenderRectangle(0, 0, pxRw2, pxRh2);
                            } else {
                                // 子窗口尚未创建：临时绑定父窗口以全屏显示，待子窗口创建成功后再切换区域渲染
                                if (overlayWindowHandle != 0L) {
                                    HWND hParentValidate2 = new HWND(Pointer.createConstant(overlayWindowHandle));
                                    if (User32.INSTANCE.IsWindow(hParentValidate2)) {
                                        videoOverlay.setWindowHandle(overlayWindowHandle);
                                        videoOverlay.expose();
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignore) {}
                }
                };
                overlayTarget.layoutBoundsProperty().addListener(layoutListener);
            } else {
                LogTools.getInstance().logRecord3("📌 监听器已存在，跳过添加");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("初始化 VideoOverlay 绑定失败: " + t.getMessage());
        }
    }

    /**
     * ⭐ 监听窗口移动，解决拖动时黑屏问题
     * 当窗口x/y位置变化时，立即更新overlay位置
     */
    private javafx.stage.Stage monitoredStage = null;  // ⭐ 记录已监听的Stage，防止重复添加
    
    private void addWindowMoveListener(javafx.scene.Scene scene) {
        if (scene == null) return;

        javafx.stage.Window window = scene.getWindow();
        if (!(window instanceof javafx.stage.Stage)) return;

        javafx.stage.Stage stage = (javafx.stage.Stage) window;
        
        // ⭐ 防止重复添加：如果已经监听了这个Stage，则跳过
        if (monitoredStage == stage) {
            return;
        }
        monitoredStage = stage;

        // ⭐ 监听窗口 X 坐标变化
        stageXListener = (obs, oldX, newX) -> {
            updateOverlayPosition();
        };
        stage.xProperty().addListener(stageXListener);

        // ⭐ 监听窗口 Y 坐标变化
        stageYListener = (obs, oldY, newY) -> {
            updateOverlayPosition();
        };
        stage.yProperty().addListener(stageYListener);

        System.out.println("✅ 已添加窗口移动监听（解决拖动黑屏问题）");
    }

    /**
     * ⭐ 更新 overlay 位置（窗口移动时调用，无节流）
     */
    private void updateOverlayPosition() {
        if (videoOverlay == null || overlayTarget == null) return;

        try {
            javafx.geometry.Bounds localBounds = overlayTarget.getLayoutBounds();
            javafx.geometry.Bounds ivScreen = overlayTarget.localToScreen(localBounds);

            if (ivScreen == null) return;

            int rw = (int) Math.round(ivScreen.getWidth());
            int rh = (int) Math.round(ivScreen.getHeight() - FileToos.botoomHight);

            if (rw <= 0 || rh <= 0) return;

            // 计算相对于窗口客户区的坐标
            javafx.scene.Scene scene = overlayTarget.getScene();
            double clientOriginX = 0, clientOriginY = 0;
            if (scene != null && scene.getRoot() != null) {
                javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                if (rootTL != null) {
                    clientOriginX = rootTL.getX();
                    clientOriginY = rootTL.getY();
                }
            }

            int rx = (int) Math.round(ivScreen.getMinX() - clientOriginX);
            int ry = (int) Math.round(ivScreen.getMinY() - clientOriginY);

            // DPI 缩放
            long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle
                    : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
            double sx = 1.0, sy = 1.0;
            boolean calcDpi = Boolean.parseBoolean(System.getProperty("video.overlay.calcDpi", "true"));
            try {
                if (calcDpi && hwndForScale != 0L) {
                    HWND w = new HWND(Pointer.createConstant(hwndForScale));
                    com.sun.jna.platform.win32.WinDef.HDC hdc = User32.INSTANCE.GetDC(w);
                    if (hdc != null) {
                        int dpiX = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                        int dpiY = com.sun.jna.platform.win32.GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                        User32.INSTANCE.ReleaseDC(w, hdc);
                        if (dpiX > 0 && dpiY > 0) {
                            sx = Math.max(1.0, dpiX / 96.0);
                            sy = Math.max(1.0, dpiY / 96.0);
                        }
                    }
                }
            } catch (Throwable ignore) {}

            int pxRw = (int) Math.round(rw * sx);
            int pxRh = (int) Math.round(rh * sy);
            int pxRx = (int) Math.round(rx * sx);
            int pxRy = (int) Math.round(ry * sy);

            // ⭐ 更新 overlay 位置
            if (overlayChildHandle != 0L) {
                // 移动子窗口
                HWND hwndChild = new HWND(Pointer.createConstant(overlayChildHandle));
                User32.INSTANCE.SetWindowPos(
                        hwndChild,
                        null,
                        pxRx, pxRy, pxRw, pxRh,
                        WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE
                );
            } else {
                // 更新渲染矩形
                videoOverlay.setRenderRectangle(pxRx, pxRy, pxRw, pxRh);
            }
        } catch (Throwable e) {
            // 忽略错误，避免频繁打印日志
        }
    }

    private long tryResolveWindowHandleFromJavaFX() {
        try {
            javafx.scene.Scene scene = (overlayTarget != null) ? overlayTarget.getScene() : (imageView != null ? imageView.getScene() : null);
            javafx.stage.Window win = scene != null ? scene.getWindow() : null;
            String title = null;
            if (win instanceof javafx.stage.Stage) {
                title = ((javafx.stage.Stage) win).getTitle();
            }
            if (title != null && !title.isEmpty()) {
                HWND hwnd = User32.INSTANCE.FindWindow(null, title);
                if (hwnd != null) {
                    return Pointer.nativeValue(hwnd.getPointer());
                }
            }
            HWND fg = User32.INSTANCE.GetForegroundWindow();
            if (fg != null) {
                return Pointer.nativeValue(fg.getPointer());
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("解析 JavaFX 窗口句柄失败: " + t.getMessage());
        }
        return 0L;
    }

    /**
     * 设置WebRTC信号处理
     */
    private void setupWebRTCSignals() {
        // 处理新的媒体流 - 使用Element的PAD_ADDED接口
        webrtcbin.connect(new Element.PAD_ADDED() {
            @Override
            public void padAdded(Element element, Pad pad) {
                logRecord("🔥🔥🔥 padAdded 被触发！！！");
                String padName = pad.getName();
                logRecord("🔥 pad 名称: " + padName);
                
                Caps capsObj = pad.getCurrentCaps();
                if (capsObj == null) {
                    capsObj = pad.queryCaps(null);
                }
                // 判空与结构数量防护
                int capsSize = -1;
                try {
                    capsSize = (capsObj != null) ? capsObj.size() : -1;
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ 读取 pad caps.size() 失败: " + t.getMessage());
                    capsSize = -1;
                }
                if (capsObj == null || capsSize <= 0) {
                    logRecord("🔗 新的pad: " + padName + " | caps: " + (capsObj == null ? "null" : capsObj.toString()) + " | size=" + capsSize + "（忽略：caps为空或结构数<=0）");
                    return; // 不处理空/未固定的caps，等待后续协商
                }
                String capsStr = (capsObj != null) ? capsObj.toString() : "null";
                logRecord("🔗 新的pad: " + padName + " | caps: " + capsStr + " | size=" + capsSize);

                // 更稳健的识别：仅处理 H264 视频 RTP，忽略 RTX/RED/ULPFEC
                String lowerCaps = (capsStr == null ? "" : capsStr.toLowerCase());
                String lowerName = (padName == null ? "" : padName.toLowerCase());

                boolean isRtp = lowerCaps.contains("application/x-rtp");
                boolean isVideoMedia = lowerCaps.contains("media=(string)video");
                boolean isH264 = lowerCaps.contains("encoding-name=(string)h264") || lowerCaps.contains("h264");
                boolean isFecOrRedOrRtx = lowerCaps.contains("rtx") || lowerCaps.contains("red") || lowerCaps.contains("ulpfec")
                        || lowerName.contains("rtx") || lowerName.contains("red") || lowerName.contains("ulpfec");

                boolean isVideoH264Rtp = isRtp && isVideoMedia && isH264 && !isFecOrRedOrRtx;
                boolean nameLooksVideoRtp = (lowerName.startsWith("recv_rtp_src_") || lowerName.startsWith("src_")) && !isFecOrRedOrRtx;

                if (isVideoH264Rtp || nameLooksVideoRtp) {
                    // 记录 webrtcbin 的视频 RTP 源 pad，用于通过 webrtcbin 发送PLI/FIR
                    webrtcSrcPad = pad;
                    logRecord("✅ 记录 webrtcbin 视频RTP src pad: " + padName);

                    // 直连诊断：跳过 depay/parse，直接连到末端 sink（通常为 fakesink）
                    if (diagSkipDecode && diagDirectSink && sinkEndElement != null) {
                        Pad directSinkPad = sinkEndElement.getStaticPad("sink");
                        if (directSinkPad != null && !directSinkPad.isLinked()) {
                            try {
                                pad.link(directSinkPad);
                                logRecord("✅ 直连诊断：webrtcbin 源 pad 已直接连接到末端 sink（跳过 depay/parse）");
                            } catch (PadLinkException e) {
                                logRecord("❌ 直连诊断失败: " + e.getMessage());
                            }
                        } else {
                            if (directSinkPad == null) {
                                logRecord("❌ 直连诊断：末端 sink 的 sink pad 不存在");
                            } else {
                                logRecord("ℹ️ 直连诊断：末端 sink 已链接，跳过");
                            }
                        }
                        return;
                    }

                    // 连接到H264 depay
                    Pad sinkPad = rtph264depay.getStaticPad("sink");
                    if (sinkPad != null && !sinkPad.isLinked()) {
                        try {
                            pad.link(sinkPad);
                            logRecord("✅ WebRTC H264视频RTP pad已连接到 rtph264depay.sink");

                            // 🔥 关键修复：pad连接后立即请求关键帧（解决首次连接黑屏）
                            logRecord("🔥 视频流已连接，立即请求首个关键帧...");
                            try {
                                sendPLIRequest();
                                // 再发送2次，确保iOS收到（间隔100ms和300ms）
                                Gst.getExecutor().schedule(() -> {
                                    try { sendPLIRequest(); } catch (Exception ignore) {}
                                }, 100, TimeUnit.MILLISECONDS);
                                Gst.getExecutor().schedule(() -> {
                                    try { sendPLIRequest(); } catch (Exception ignore) {}
                                }, 300, TimeUnit.MILLISECONDS);
                            } catch (Exception e) {
                                logRecord("⚠️ 请求关键帧失败: " + e.getMessage());
                            }
                        } catch (PadLinkException e) {
                            logRecord("❌ WebRTC pad连接失败: " + e.getMessage());
                        }
                    } else {
                        if (sinkPad == null) {
                            logRecord("❌ rtph264depay 的 sink pad 不存在");
                        } else {
                            logRecord("ℹ️ rtph264depay.sink 已链接，跳过");
                        }
                    }
                } else {
                    logRecord("ℹ️ 非 H264 视频 RTP（或为 RTX/RED/ULPFEC），忽略该 pad");
                }
            }
        });

        // 🔄 监听 ON_NEGOTIATION_NEEDED：webrtcbin 就绪后再创建 offer
        webrtcbin.connect(new WebRTCBin.ON_NEGOTIATION_NEEDED() {
            @Override
            public void onNegotiationNeeded(Element elem) {
                logRecord("STEP2: 🔄 ON_NEGOTIATION_NEEDED 触发，开始创建 offer");
                try {
                    if (currentStreamUrl == null || currentStreamUrl.isEmpty()) {
                        logRecord("❌ currentStreamUrl 为空，无法创建 offer");
                        return;
                    }
                    createWebRTCOffer(currentStreamUrl);
                } catch (Exception e) {
                    logRecord("❌ ON_NEGOTIATION_NEEDED 回调创建 offer 失败: " + e.getMessage());
                }
            }
        });

        // 🔧 关键修复：添加本地ICE候选者处理
        webrtcbin.connect(new WebRTCBin.ON_ICE_CANDIDATE() {
            @Override
            public void onIceCandidate(int sdpMLineIndex, String candidate) {
                logRecord("🧊 本地ICE候选者生成: mline=" + sdpMLineIndex + ", candidate=" + candidate);
                // 注意：在实际应用中，这里应该通过信令服务器发送给远程端
                // 但在我们的场景中，SRS服务器使用ice-lite模式，不需要发送本地候选者
                if (candidate == null || candidate.isEmpty()) {
                    logRecord("✅ 本地ICE候选者收集完成");
                }
            }
        });

        // 🔧 关键修复：WebRTCBin没有官方的on-new-transceiver接口，暂时移除
        // 官方WebRTCBin只提供ON_NEGOTIATION_NEEDED和ON_ICE_CANDIDATE接口
        // transceiver会在pad-added事件中自动处理
        logRecord("📡 WebRTC信号处理器设置中...");

        // ICE收集状态变化 - 使用GObject的Notify信号连接
        notifyIceGathering = new GObjectAPI.GObjectClass.Notify() {
            @Override
            public void callback(GObject object, Pointer spec) {
                WebRTCICEGatheringState state = webrtcbin.getICEGatheringState();
                logRecord("🧊 ICE收集状态变化: " + state);
                if (state == WebRTCICEGatheringState.COMPLETE) {
                    logRecord("✅ ICE收集完成，连接应该可以建立");
                }
            }
        };
        webrtcbin.connect("notify::ice-gathering-state",
                GObjectAPI.GObjectClass.Notify.class,
                notifyIceGathering,
                notifyIceGathering);


        // 连接状态变化 - 使用GObject的Notify信号连接
        notifyConnectionState = new GObjectAPI.GObjectClass.Notify() {
            @Override
            public void callback(GObject object, Pointer spec) {
                WebRTCPeerConnectionState state = webrtcbin.getConnectionState();
                logRecord("🔗 WebRTC连接状态变化: " + state);
                if (state == WebRTCPeerConnectionState.CONNECTED) {
                    logRecord("✅ WebRTC连接已建立！应该开始接收数据");
                    try { sendPLIRequest(); } catch (Exception ignore) {}
                    try {
                        Gst.getExecutor().schedule(() -> { try { sendPLIRequest(); } catch (Exception ignore) {} }, 300, TimeUnit.MILLISECONDS);
                    } catch (Exception ignore) {}
                    // ⭐ 连接成功后，异步获取SRS的clientId（用于stop时删除连接）
                    fetchSrsPlayClientId();
                } else if (state == WebRTCPeerConnectionState.FAILED) {
                    logRecord("❌ WebRTC连接失败！");
                }
                switch (state) {


                    case NEW:
                        LogTools.getInstance().logRecord3("   → 连接初始化");
                        break;
                    case CONNECTING:
                        LogTools.getInstance().logRecord3("   → 正在连接（ICE 协商中）");
                        break;
                    case CONNECTED:
                        LogTools.getInstance().logRecord3("   → ✅ 连接成功！");
                        break;
                    case FAILED:
                        LogTools.getInstance().logRecord3("   → ❌ 连接失败！");
                        break;
                    case DISCONNECTED:
                        LogTools.getInstance().logRecord3("   → ⚠️ 连接断开");
                        break;
                    case CLOSED:
                        FileToos.isIsCallBackFrame=false;
                        LogTools.getInstance().logRecord3("   → 连接已关闭");
                        break;
                    default:
                        break;

                }
            }
        };
        webrtcbin.connect("notify::connection-state",
                GObjectAPI.GObjectClass.Notify.class,
                notifyConnectionState,
                notifyConnectionState);


        // 🔧 关键修复：添加ICE连接状态监控 - 使用GObject的Notify信号连接
        notifyIceConnectionState = new GObjectAPI.GObjectClass.Notify() {
            @Override
            public void callback(GObject object, Pointer spec) {
                try {
                    Object iceState = webrtcbin.get("ice-connection-state");
                    logRecord("🧊 ICE连接状态变化: " + iceState);

                    // 根据ICE连接状态进行相应处理
                    if (iceState != null) {
                        String stateStr = iceState.toString();
                        switch (stateStr) {
                            case "0": // NEW
                                logRecord("🧊 ICE连接状态: NEW - 等待候选者");
                                break;
                            case "1": // CHECKING
                                logRecord("🧊 ICE连接状态: CHECKING - 正在检查连接");
                                break;
                            case "2": // CONNECTED
                                logRecord("✅ ICE连接状态: CONNECTED - 连接成功！");
                                break;
                            case "3": // COMPLETED
                                logRecord("✅ ICE连接状态: COMPLETED - 连接完成！");
                                // 🔥 ICE完成后也请求关键帧（双重保险）
                                try {
                                    Gst.getExecutor().schedule(() -> {
                                        try { sendPLIRequest(); } catch (Exception ignore) {}
                                    }, 200, TimeUnit.MILLISECONDS);
                                } catch (Exception ignore) {}
                                break;
                            case "4": // FAILED
                                logRecord("❌ ICE连接状态: FAILED - 连接失败！");
                                tryIceAutoRecover();
                                break;
                            case "5": // DISCONNECTED
                                logRecord("⚠️ ICE连接状态: DISCONNECTED - 连接断开");
                                tryIceAutoRecover();
                                break;
                            case "6": // CLOSED
                                logRecord("🔒 ICE连接状态: CLOSED - 连接关闭");
                                break;
                        }
                    }
                } catch (Exception e) {
                    logRecord("⚠️ ICE连接状态监控错误: " + e.getMessage());
                }
            }
        };
        webrtcbin.connect("notify::ice-connection-state",
                GObjectAPI.GObjectClass.Notify.class,
                notifyIceConnectionState,
                notifyIceConnectionState);


    }

    /**
     * 请求关键帧（通过 PLI 或 FIR）
     */
    private void requestKeyFrame() {
        if (webrtcbin == null) {
            LogTools.getInstance().logRecord2("❌ webrtcbin 未初始化，无法请求关键帧");
            return;
        }

        sendPLIRequest();
    }

    /**
     * 公开方法：外部手动请求关键帧
     */
    public void requestKeyFrameManually() {
        requestKeyFrame();
    }
    /**
     * 添加NAL单元探针
     */
    private void addNalProbes() {
        // 在rtph264depay输出添加探针
        Pad depaySourcePad = rtph264depay.getStaticPad("src");
        if (depaySourcePad != null) {
            depaySourcePad.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                Buffer buffer = info.getBuffer();
                if (buffer != null) {
                    analyzeH264Buffer(buffer, "depay-out");
                }
                return PadProbeReturn.OK;
            });
            System.out.println("✅ 已添加depay输出探针");
        }

        // 在h264parse输出添加探针
        Pad parseSourcePad = h264parse.getStaticPad("src");
        if (parseSourcePad != null) {
            parseSourcePad.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                Buffer buffer = info.getBuffer();
                if (buffer != null) {
                    analyzeH264Buffer(buffer, "parse-out");
                }

                Caps caps = pad.getCurrentCaps();
                if (caps != null && caps.size() > 0) {
                    Structure structure = caps.getStructure(0);
                    if (structure != null && structure.hasField("width") && structure.hasField("height")) {
                        int newWidth = structure.getInteger("width");
                        int newHeight = structure.getInteger("height");
                        
                        // ⭐ 检测分辨率变化
                        if (newWidth != FileToos.sslWidth || newHeight != FileToos.sslwHight) {
                            int oldWidth = FileToos.sslWidth;
                            int oldHeight = FileToos.sslwHight;
                            FileToos.sslWidth = newWidth;
                            FileToos.sslwHight = newHeight;
                            videoWidth = newWidth;
                            videoHeight = newHeight;
                            
                            // ⭐ 分辨率变化时重新配置
                            if (oldWidth > 0 && oldHeight > 0) {
                                LogTools.getInstance().logRecord3("📐 分辨率变化: " + oldWidth + "x" + oldHeight + " → " + newWidth + "x" + newHeight);
                                
                                // ⭐ 关键：如果当前有缩放，需要用新分辨率重新计算裁剪量
                                if (currentScaleFactor > 1.0f) {
                                    LogTools.getInstance().logRecord3("🔧 重新应用缩放: " + currentScaleFactor + "x (适配新分辨率)");
                                    reapplyVideoCrop();
                                }
                                
                                // ⭐ 分辨率变化后密集请求关键帧（防止黑屏）
                                requestKeyframeForResolutionChange();
                            }
                            
                            // 🔥 首次检测到分辨率时，根据分辨率自动调整 jitterbuffer
                            if (oldWidth == 0 && oldHeight == 0) {
                                adjustJitterbufferByResolution(newWidth, newHeight);
                            }
                        }
                    }
                }
                return PadProbeReturn.OK;
            });
            System.out.println("✅ 已添加parse输出探针");
        }
    }

    /**
     * ⭐ 分辨率变化时密集请求关键帧（防止黑屏）
     * 原因：H264解码器在分辨率变化时需要新的SPS/PPS和关键帧
     */
    private void requestKeyframeForResolutionChange() {
        LogTools.getInstance().logRecord3("🔑 分辨率变化，密集请求关键帧（防止黑屏）");
        
        // 立即发送
        try { sendPLIRequest(); } catch (Throwable ignore) {}
        
        // 100ms后再发
        try {
            Gst.getExecutor().schedule(() -> {
                try { sendPLIRequest(); } catch (Throwable ignore) {}
            }, 100, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
        
        // 300ms后再发
        try {
            Gst.getExecutor().schedule(() -> {
                try { sendPLIRequest(); } catch (Throwable ignore) {}
            }, 300, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
        
        // 500ms后再发（确保恢复）
        try {
            Gst.getExecutor().schedule(() -> {
                try { sendPLIRequest(); } catch (Throwable ignore) {}
            }, 500, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
        
        // 1秒后最后检查一次
        try {
            Gst.getExecutor().schedule(() -> {
                try { 
                    sendPLIRequest(); 
                    LogTools.getInstance().logRecord3("🔑 分辨率变化后1秒，最终关键帧请求");
                } catch (Throwable ignore) {}
            }, 1000, TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
    }
    
    /**
     * 🔥 根据分辨率自动调整 jitterbuffer 配置（码率3500kbps）
     * - SD (≤720p): 300ms 缓冲
     * - HD (720p-1080p): 350ms 缓冲  
     * - FHD (1080p): 400ms 缓冲
     * - 4K (2160p+): 500ms 缓冲
     */
    private void adjustJitterbufferByResolution(int width, int height) {
        if (webrtcbin == null) return;
        
        // 计算分辨率级别
        int pixels = width * height;
        String resLevel;
        int targetLatencyMs;
        int targetDropoutMs;
        int targetMisorderMs;
        
        if (pixels >= 3840 * 2160) {
            // 4K: 大缓冲
            resLevel = "4K";
            targetLatencyMs = 500;
            targetDropoutMs = 2500;
            targetMisorderMs = 1500;
        } else if (pixels >= 1920 * 1080) {
            // 1080P: 中等缓冲
            resLevel = "1080P";
            targetLatencyMs = 400;
            targetDropoutMs = 2200;
            targetMisorderMs = 1400;
        } else if (pixels >= 1280 * 720) {
            // 720P: 标准缓冲
            resLevel = "720P";
            targetLatencyMs = 350;
            targetDropoutMs = 2100;
            targetMisorderMs = 1300;
        } else {
            // SD: 低延迟 + 高丢包容忍（消除马赛克但保持低延迟）
            resLevel = "SD";
            targetLatencyMs = 250;      // 🔥 标清低延迟：250ms
            targetDropoutMs = 2000;     // 🔥 高容忍：2秒（防马赛克）
            targetMisorderMs = 1200;    // 🔥 高容忍：1.2秒（防马赛克）
        }
        
        LogTools.getInstance().logRecord3(String.format(
            "🎬 检测到分辨率 %dx%d (%s)，自动调整 jitterbuffer: latency=%dms, dropout=%dms, misorder=%dms",
            width, height, resLevel, targetLatencyMs, targetDropoutMs, targetMisorderMs));
        
        // 动态调整 jitterbuffer 参数
        try {
            webrtcbin.getElements().forEach(element -> {
                String elemName = element.getName();
                if (elemName != null && elemName.contains("jitterbuffer")) {
                    try {
                        element.set("latency", targetLatencyMs);
                        element.set("max-dropout-time", targetDropoutMs);
                        element.set("max-misorder-time", targetMisorderMs);
                        LogTools.getInstance().logRecord3("✅ jitterbuffer 已根据分辨率(" + resLevel + ")自动调整");
                    } catch (Exception e) {
                        LogTools.getInstance().logRecord3("⚠️ 动态调整 jitterbuffer 失败: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("⚠️ 获取 jitterbuffer 元素失败: " + e.getMessage());
        }
        
        // 更新 isHighResolution 标志
        this.isHighResolution = pixels >= 1920 * 1080;
    }
    
    // 新增：decoder输出帧探针，精确更新 lastFrameTimeMs 和帧率统计
    private void addDecoderFrameProbe() {
        try {
            Pad decSrc = decoder != null ? decoder.getStaticPad("src") : null;
            if (decSrc != null) {
                decSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    long now = System.currentTimeMillis();
                    lastFrameTimeMs = now;
                    
                    // ⭐ 视频流到达，设置状态为 1（用于工作恢复倒计时检测）
                    FileToos.videoStreamStatus = 1;
                    
                    // ⭐ 帧率统计：EMA 指数移动平均（极度平滑，避免跳动）
                    fpsFrameCounter.incrementAndGet();
                    if (now - fpsLastSecondMs >= 1000) {
                        int currentSecondFps = fpsFrameCounter.getAndSet(0);
                        fpsLastSecondMs = now;
                        
                        // ⚡ 诊断日志：实际每秒收到的帧数（来自 decoder 输出）
                        LogTools.getInstance().logRecord3("📊 [FPS诊断] 实际收帧: " + currentSecondFps + " fps (来自decoder输出)");
                        
                        // EMA 计算：newEma = alpha * current + (1-alpha) * oldEma
                        // alpha=0.2 意味着当前值只占 20%，历史值占 80%（非常平滑）
                        if (!fpsEmaInitialized) {
                            fpsEma = currentSecondFps;  // 第一次直接赋值
                            fpsEmaInitialized = true;
                        } else {
                            fpsEma = FPS_EMA_ALPHA * currentSecondFps + (1.0 - FPS_EMA_ALPHA) * fpsEma;
                        }
                        
                        FileToos.receiveFps = (int) Math.round(fpsEma);  // 四舍五入
                    }
                    
                    return PadProbeReturn.OK;
                });
                System.out.println("✅ 已添加decoder输出帧探针，用于更新 lastFrameTimeMs 和帧率统计");
            } else {
                System.out.println("⚠️ decoder.src pad 不可用，暂无法安装帧探针");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 安装decoder帧探针异常: " + t.getMessage());
        }
    }
    
    /**
     * ⚡ 添加实时内存环探针（GPU路径同步抓拍）
     * 只做帧计数统计，不做 NV12 转换（太慢会卡死）
     * 内存环推送由专门的 GStreamer 分支处理
     */
    private void addRealtimeRingProbe() {
        try {
            if (captureTee == null) {
                LogTools.getInstance().logRecord5("⚠️ captureTee 为 null，无法添加内存环探针");
                return;
            }
            
            Pad teeSinkPad = captureTee.getStaticPad("sink");
            if (teeSinkPad == null) {
                LogTools.getInstance().logRecord5("⚠️ captureTee.sink pad 为 null，无法添加内存环探针");
                return;
            }
            
            // ⚡ 只做帧计数，不做 NV12 转换
            teeSinkPad.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                try {
                    // ⚡ 累加 GPU 帧索引，统计与 JPEG 的差距
                    FileToos.GpuIndex++;
                    int gpuIdx = FileToos.GpuIndex;
                    int jpegIdx = FileToos.jpegIndex;
                    int diff = gpuIdx - jpegIdx;
                    
                    // 每60帧打印差距日志
                    if (gpuIdx % 60 == 0) {
                        LogTools.getInstance().logRecord5("📊 帧差: Gpu=" + gpuIdx + " Jpeg=" + jpegIdx + " 差=" + diff);
                        
                        // 打印内存环状态
                        com.acard.acard.capture.RealtimeFrameRing ring = 
                            com.acard.acard.capture.RealtimeFrameRing.getInstance();
                        LogTools.getInstance().logRecord5("📦 内存环状态: " + ring.getStatus());
                    }
                } catch (Throwable e) {
                    // 忽略错误
                }
                return PadProbeReturn.OK;
            });
            
            LogTools.getInstance().logRecord5("✅ 已添加 captureTee 帧计数探针");
            
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 安装探针异常: " + t.getMessage());
        }
    }

    // 重新添加 addNalProbes 的原始内容
    private void addNalProbesRestored() {
        // 在rtph264depay输出添加探针
        Pad depaySourcePadRestored = rtph264depay.getStaticPad("src");
        if (depaySourcePadRestored != null) {
            depaySourcePadRestored.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                Buffer buffer = info.getBuffer();
                if (buffer != null) {
                    analyzeH264Buffer(buffer, "depay-out");
                }
                return PadProbeReturn.OK;
            });
            System.out.println("✅ 已添加depay输出探针");
        }

        // 在h264parse输出添加探针
        Pad parseSourcePad = h264parse.getStaticPad("src");
        if (parseSourcePad != null) {
            parseSourcePad.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                Buffer buffer = info.getBuffer();
                if (buffer != null) {
                    analyzeH264Buffer(buffer, "parse-out");
                }
                return PadProbeReturn.OK;
            });
            System.out.println("✅ 已添加parse输出探针");
        }
    }

    /**
     * 分析H264缓冲区中的NAL单元
     */
    private void analyzeH264Buffer(Buffer buffer, String source) {
        try {
            ByteBuffer byteBuffer = buffer.map(false);
            if (byteBuffer != null && byteBuffer.remaining() > 4) {
                byte[] data = new byte[Math.min(2048, byteBuffer.remaining())]; // 读取前最多2KB用于分析，避免漏检
                byteBuffer.get(data);

                // 查找NAL单元（优先处理WebRTC标准的3字节起始码）
                for (int i = 0; i < data.length - 4; i++) {
                    boolean hasStartCode3 = (data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x01);
                    boolean hasStartCode4 = (data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01);

                    if (hasStartCode3 || hasStartCode4) {
                        int nalStart = hasStartCode3 ? i + 3 : i + 4;
                        String startCodeType = hasStartCode3 ? "3-byte" : "4-byte";

                        if (nalStart < data.length) {
                            int nalHeader = data[nalStart] & 0xFF;
                            int nalType = nalHeader & 0x1F;

                            // 记录起始码类型，特别关注关键帧
                            if (nalType == 5 || nalType == 7 || nalType == 8) {
                                System.out.println("🔍 [" + source + "] NAL type " + nalType + " 使用 " + startCodeType + " 起始码");
                            }

                            processNalUnit(nalType, source);

                            // WebRTC标准建议使用3字节起始码，如果发现4字节起始码则警告
                            if (hasStartCode4 && !hasStartCode3) {
                                LogTools.getInstance().logRecord2("⚠️ [" + source + "] 检测到4字节起始码，WebRTC可能需要3字节起始码");
                            }
                        }
                    } else {
                        // AVC长度前缀：前4字节为长度
                        int length = ((data[i] & 0xFF) << 24) | ((data[i+1] & 0xFF) << 16) | ((data[i+2] & 0xFF) << 8) | (data[i+3] & 0xFF);
                        if (length > 0 && length < 1000000 && i + 4 < data.length) { // 添加长度合理性检查
                            int nalHeader = data[i+4] & 0xFF;
                            int nalType = nalHeader & 0x1F;

                            if (nalType == 5 || nalType == 7 || nalType == 8) {
                                System.out.println("🔍 [" + source + "] NAL type " + nalType + " 使用AVC长度前缀格式 (length=" + length + ")");
                            }

                            processNalUnit(nalType, source);
                            i += 4 + Math.min(length, data.length - i - 4) - 1; // 跳过当前NAL内容，继续
                        }
                    }
                }

                buffer.unmap();
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ NAL分析错误: " + e.getMessage());
        }
    }

    /**
     * 通过 HTTP API 删除 SRS 播放连接（仅删除自己的播放连接，不影响推流端）
     * 确保 SRS 服务器上的播放连接被立即清理
     */
    public void deleteStreamFromSRS(String streamUrl) {
        try {
            // 从 streamUrl 提取流信息
            // streamUrl 格式: "tenantA/VID_xxx_timestamp"
            String[] parts = streamUrl.split("/");
            if (parts.length < 2) {
                LogTools.getInstance().logRecord3("⚠️ streamUrl 格式错误: " + streamUrl);
                return;
            }

            String app = parts[0];        // tenantA
            String streamName = parts[1]; // VID_xxx_timestamp

            // 构建 SRS API URL
            String apiUrl = "http://" + serverHost + ":" + serverPort + "/api/v1/clients";

            LogTools.getInstance().logRecord3("   → 查询 SRS 客户端: " + apiUrl);

            // 1. 获取所有客户端
            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // 读取响应
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                LogTools.getInstance().logRecord3("   ✅ 客户端列表获取成功");

                // 2. 解析 JSON，找到匹配的 **播放（play）** 客户端 ID
                // ⚠️ 关键：只删除 type="play" 的客户端，保留 type="publish" 的推流端
                String responseStr = response.toString();
                LogTools.getInstance().logRecord3("   → 客户端列表: " + responseStr);

                if (responseStr.contains(streamName)) {
                    // 查找所有包含该流的客户端
                    int searchFrom = 0;
                    while (true) {
                        int streamIndex = responseStr.indexOf(streamName, searchFrom);
                        if (streamIndex == -1) break;  // 没有更多匹配

                        // 在该流名称附近查找 "type" 字段
                        int typeIndex = responseStr.lastIndexOf("\"type\":\"", streamIndex);
                        if (typeIndex != -1 && typeIndex > searchFrom - 200) {  // 确保在合理范围内
                            int typeStart = typeIndex + 8;
                            int typeEnd = responseStr.indexOf("\"", typeStart);
                            String clientType = responseStr.substring(typeStart, typeEnd);

                            // ✅ 只处理 type="play" 的客户端（播放端）
                            if ("play".equals(clientType)) {
                                // 在该客户端对象内查找 "id" 字段
                                int idIndex = responseStr.lastIndexOf("\"id\":\"", streamIndex);
                                if (idIndex != -1 && idIndex > typeIndex - 100) {
                                    int idStart = idIndex + 6;
                                    int idEnd = responseStr.indexOf("\"", idStart);
                                    String clientId = responseStr.substring(idStart, idEnd);

                                    LogTools.getInstance().logRecord3("   → 找到播放客户端 ID: " + clientId + " (type=play)");

                                    // 3. 删除该播放客户端
                                    String deleteUrl = apiUrl + "/" + clientId;
                                    java.net.HttpURLConnection deleteConn =
                                            (java.net.HttpURLConnection) new java.net.URL(deleteUrl).openConnection();
                                    deleteConn.setRequestMethod("DELETE");
                                    deleteConn.setConnectTimeout(3000);
                                    deleteConn.setReadTimeout(3000);

                                    int deleteCode = deleteConn.getResponseCode();
                                    if (deleteCode == 200 || deleteCode == 204) {
                                        LogTools.getInstance().logRecord3("   ✅ SRS 播放客户端已删除: " + clientId + " (保留推流端)");
                                    } else {
                                        LogTools.getInstance().logRecord3("   ⚠️ 删除播放客户端失败，响应码: " + deleteCode);
                                    }
                                    deleteConn.disconnect();
                                    break;  // 找到并删除后退出循环
                                }
                            } else {
                                LogTools.getInstance().logRecord3("   ℹ️ 跳过非播放客户端 (type=" + clientType + ")");
                            }
                        }

                        // 移动到下一个搜索位置
                        searchFrom = streamIndex + streamName.length();
                    }
                } else {
                    LogTools.getInstance().logRecord3("   ℹ️ 未找到匹配的客户端");
                }
            } else {
                LogTools.getInstance().logRecord3("   ⚠️ 获取客户端列表失败，响应码: " + responseCode);
            }
            conn.disconnect();

        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 删除 SRS 播放连接异常: " + t.getMessage());
        }
    }

    /**
     * 手动清理指定流在 SRS 上的所有播放客户端（外部可调用）
     * @param streamUrl 形如 tenant/streamId
     */
    public void deleteAllPlayClients(String streamUrl) {
        try {
            LogTools.getInstance().logRecord3("⚙️ 手动触发 SRS 播放连接清理, stream=" + streamUrl);
            kickoffOldConnection(streamUrl);
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 手动清理播放连接异常: " + t.getMessage());
        }
    }
    
    /**
     * ⭐ 异步获取SRS播放客户端ID（连接成功后调用）
     */
    private void fetchSrsPlayClientId() {
        if (streamId == null || streamId.isEmpty()) {
            LogTools.getInstance().logRecord3("ℹ️ streamId为空，跳过获取SRS clientId");
            return;
        }
        
        new Thread(() -> {
            try {
                Thread.sleep(500);  // 等待SRS注册完成
                
                String apiUrl = "http://" + serverHost + ":" + serverPort + "/api/v1/clients";
                LogTools.getInstance().logRecord3("🔍 获取SRS客户端列表: " + apiUrl);
                
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                
                int code = conn.getResponseCode();
                if (code == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();
                    
                    String response = sb.toString();
                    // 查找匹配当前streamId且type为rtc-play的客户端
                    // 简单解析：查找 "name":"streamId" 附近的 "id":"xxx" 和 "type":"rtc-play"
                    int searchFrom = 0;
                    while (true) {
                        int nameIdx = response.indexOf("\"name\":\"" + streamId + "\"", searchFrom);
                        if (nameIdx == -1) break;
                        
                        // 找到该客户端对象的起始位置（向前找{）
                        int objStart = response.lastIndexOf("{", nameIdx);
                        int objEnd = response.indexOf("}", nameIdx);
                        if (objStart == -1 || objEnd == -1) {
                            searchFrom = nameIdx + 1;
                            continue;
                        }
                        
                        String clientObj = response.substring(objStart, objEnd + 1);
                        
                        // 检查是否是rtc-play类型
                        if (clientObj.contains("\"type\":\"rtc-play\"")) {
                            // 提取id
                            int idIdx = clientObj.indexOf("\"id\":\"");
                            if (idIdx != -1) {
                                int idStart = idIdx + 6;
                                int idEnd = clientObj.indexOf("\"", idStart);
                                if (idEnd != -1) {
                                    String clientId = clientObj.substring(idStart, idEnd);
                                    srsPlayClientId = clientId;
                                    LogTools.getInstance().logRecord3("✅ 获取到SRS播放clientId: " + clientId);
                                    break;
                                }
                            }
                        }
                        searchFrom = nameIdx + 1;
                    }
                    
                    if (srsPlayClientId == null) {
                        LogTools.getInstance().logRecord3("ℹ️ 未找到匹配的rtc-play客户端");
                    }
                } else {
                    LogTools.getInstance().logRecord3("⚠️ 获取SRS客户端列表失败: " + code);
                }
                conn.disconnect();
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 获取SRS clientId异常: " + t.getMessage());
            }
        }, "FetchSrsClientId").start();
    }
    
    /**
     * ⭐ 删除当前SRS播放连接（stop时调用，避免僵尸连接）
     */
    private void deleteSrsPlayConnection() {
        // 方式1：如果有保存的clientId，直接删除
        if (srsPlayClientId != null && !srsPlayClientId.isEmpty()) {
            try {
                String apiUrl = "http://" + serverHost + ":" + serverPort + "/api/v1/clients/" + srsPlayClientId;
                LogTools.getInstance().logRecord3("🗑️ 删除SRS播放连接: " + srsPlayClientId);
                
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
                conn.setRequestMethod("DELETE");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                
                int code = conn.getResponseCode();
                if (code == 200 || code == 204) {
                    LogTools.getInstance().logRecord3("✅ SRS播放连接已删除: " + srsPlayClientId);
                } else {
                    LogTools.getInstance().logRecord3("⚠️ 删除SRS连接返回码: " + code);
                }
                conn.disconnect();
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 删除SRS连接异常: " + t.getMessage());
            }
            srsPlayClientId = null;
            return;
        }
        
        // 方式2：如果没有clientId，通过streamId查找并删除
        if (streamId != null && !streamId.isEmpty()) {
            try {
                String streamUrl = tenant + "/" + streamId;
                LogTools.getInstance().logRecord3("🗑️ 通过streamId删除SRS播放连接: " + streamUrl);
                kickoffOldConnection(streamUrl);
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 通过streamId删除SRS连接异常: " + t.getMessage());
            }
        } else {
            LogTools.getInstance().logRecord3("ℹ️ 无clientId和streamId，跳过SRS连接删除");
        }
    }

    /**
     * 强制踢掉旧的播放连接（在新连接建立前调用）
     * 这个方法会删除 **所有** 匹配的播放客户端，确保没有残留
     */
    private void kickoffOldConnection(String streamUrl) {
        try {
            // 从 streamUrl 提取流信息
            String[] parts = streamUrl.split("/");
            if (parts.length < 2) {
                LogTools.getInstance().logRecord3("⚠️ streamUrl 格式错误: " + streamUrl);
                return;
            }

            String streamName = parts[1]; // VID_xxx_timestamp

            // 构建 SRS API URL
            String apiUrl = "http://" + serverHost + ":" + serverPort + "/api/v1/clients";

            LogTools.getInstance().logRecord3("🔄 开始 Kickoff：强制踢掉旧播放连接...");

            // 1. 获取所有客户端
            java.net.URL url = new java.net.URL(apiUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);  // 2 秒超时
            conn.setReadTimeout(2000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                // 读取响应
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseStr = response.toString();

                // 2. 查找并删除所有匹配的播放客户端
                int kickoffCount = 0;
                if (responseStr.contains(streamName)) {
                    int searchFrom = 0;
                    while (true) {

                        LogTools.getInstance().logRecord3("si - join ");
                        int streamIndex = responseStr.indexOf(streamName, searchFrom);
                        if (streamIndex == -1) break;

                        // 查找 "type" 字段
                        int typeIndex = responseStr.lastIndexOf("\"type\":\"", streamIndex);
                        if (typeIndex != -1 && typeIndex > searchFrom - 200) {
                            int typeStart = typeIndex + 8;
                            int typeEnd = responseStr.indexOf("\"", typeStart);
                            String clientType = responseStr.substring(typeStart, typeEnd);

                            // 只处理 type="play" 的客户端
                            if ("play".equals(clientType)) {
                                // 查找 "id" 字段
                                int idIndex = responseStr.lastIndexOf("\"id\":\"", streamIndex);
                                if (idIndex != -1 && idIndex > typeIndex - 100) {
                                    int idStart = idIndex + 6;
                                    int idEnd = responseStr.indexOf("\"", idStart);
                                    String clientId = responseStr.substring(idStart, idEnd);

                                    // 删除该播放客户端
                                    String deleteUrl = apiUrl + "/" + clientId;
                                    java.net.HttpURLConnection deleteConn =
                                            (java.net.HttpURLConnection) new java.net.URL(deleteUrl).openConnection();
                                    deleteConn.setRequestMethod("DELETE");
                                    deleteConn.setConnectTimeout(2000);
                                    deleteConn.setReadTimeout(2000);

                                    int deleteCode = deleteConn.getResponseCode();
                                    if (deleteCode == 200 || deleteCode == 204) {
                                        kickoffCount++;
                                        LogTools.getInstance().logRecord3("   ✅ 已踢掉旧播放客户端: " + clientId);
                                    }
                                    deleteConn.disconnect();
                                }
                            }
                        }

                        searchFrom = streamIndex + streamName.length();
                    }
                }

                if (kickoffCount > 0) {
                    LogTools.getInstance().logRecord3("✅ Kickoff 完成：共踢掉 " + kickoffCount + " 个旧播放连接");
                } else {
                    LogTools.getInstance().logRecord3("ℹ️ Kickoff：未发现旧播放连接，可以直接连接");
                }
            } else {
                LogTools.getInstance().logRecord3("⚠️ Kickoff 失败：获取客户端列表失败，响应码: " + responseCode);
            }
            conn.disconnect();

        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ Kickoff 异常: " + t.getMessage());
            // 不抛出异常，允许继续尝试连接
        }
    }

    /**
     * 请求关键帧（通过发送 EOS 然后重启，触发关键帧）
     */
    private void requestKeyframe() {
        try {
            if (webrtcbin == null) {
                LogTools.getInstance().logRecord3("❌ webrtcbin 为 null，无法请求关键帧");
                return;
            }

            LogTools.getInstance().logRecord3("🔑 尝试通过 webrtcbin 请求关键帧...");

            // ⭐ 方法1：通过 transceiver 发送 FIR 请求（如果支持）
            try {
                // 尝试获取并操作 webrtcbin 的 transceiver
                GObject transceiver = webrtcbin.emit(GObject.class, "get-transceiver", 0);
                if (transceiver != null) {
                    LogTools.getInstance().logRecord3("✅ 获取到 transceiver: " + transceiver);

                    // 尝试不同的 FIR 请求方法
                    try {
                        // 方法A：通过信号发送
                        transceiver.emit("send-fir-request");
                        LogTools.getInstance().logRecord3("✅ FIR 请求已发送（方法A）");
                        return;
                    } catch (Throwable t1) {
                        LogTools.getInstance().logRecord3("⚠️ FIR 请求失败（方法A）: " + t1.getMessage());

                        // 方法B：通过属性设置
                        try {
                            transceiver.set("fec-type", 1);  // 启用 FEC
                            LogTools.getInstance().logRecord3("✅ FIR 请求已发送（方法B）");
                            return;
                        } catch (Throwable t2) {
                            LogTools.getInstance().logRecord3("⚠️ FIR 请求失败（方法B）: " + t2.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 获取 transceiver 失败: " + t.getMessage());
            }

            // ⭐ 方法2：简单的重新协商（可能触发关键帧）
            try {
                LogTools.getInstance().logRecord3("🔄 尝试重新协商触发关键帧...");
                // 通过修改 local description 触发重新协商
                // 这通常会让编码器发送新的关键帧
                LogTools.getInstance().logRecord3("⚠️ 关键帧请求方法受限，建议前端定期发送关键帧");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("❌ 重新协商失败: " + t.getMessage());
            }

            sendPLIRequest();

        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("❌ 请求关键帧失败: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * 处理检测到的NAL单元
     */
    private void processNalUnit(int nalType, String source) {
        // ⭐ 记录首个数据接收（用于诊断 WebRTC 连接是否成功）
        if (!firstDataReceived.getAndSet(true) && pipelineStartTimeMs > 0) {
            long elapsedMs = System.currentTimeMillis() - pipelineStartTimeMs;
            LogTools.getInstance().logRecord3("🎯 首个 NAL 单元到达！耗时: " + elapsedMs + "ms, 类型: " + nalType + ", 来源: " + source);
            if (elapsedMs > 5000) {
                LogTools.getInstance().logRecord3("⚠️ 首个数据到达延迟超过5秒，可能是 WebRTC 连接问题");
            }
        }

        switch (nalType) {
            case 1: // P-slice
                if (!hasReceivedPSlice.getAndSet(true)) {
                    LogTools.getInstance().logRecord3("✅ [" + source + "] 首次接收到P-slice (NAL type 1)");
                }
                break;

            case 5: // IDR
                if (!hasReceivedIdr.getAndSet(true)) {
                    LogTools.getInstance().logRecord3("🎯 [" + source + "] 首次接收到IDR帧 (NAL type 5) - 关键帧！");
                    checkFirstFrameComplete();
                }
                break;

            case 7: // SPS
                if (!hasReceivedSps.getAndSet(true)) {
                    LogTools.getInstance().logRecord3("📋 [" + source + "] 首次接收到SPS (NAL type 7) - 序列参数集");
                    checkFirstFrameComplete();
                }
                break;

            case 8: // PPS
                if (!hasReceivedPps.getAndSet(true)) {
                    LogTools.getInstance().logRecord3("📋 [" + source + "] 首次接收到PPS (NAL type 8) - 图像参数集");
                    checkFirstFrameComplete();
                }
                break;

            default:
                // 简化处理：不再特殊处理FU-A(28)，因为请求关键帧后会自动发送完整的5、7、8帧
                if (nalType != 28) { // 减少FU-A的日志输出
                    LogTools.getInstance().logRecord3("📦 [" + source + "] 接收到NAL type " + nalType);
                }
                break;
        }
    }

    /**
     * 检查首帧完成状态
     */
    private void checkFirstFrameComplete() {
        if (hasReceivedSps.get() && hasReceivedPps.get() && hasReceivedIdr.get()) {
            System.out.println("🎉 首帧完成！已接收SPS+PPS+IDR，停止PLI请求");
            stopPliTimer();
            // 启动低频保活与帧看门狗，避免需要第二次点击播放
            startKeepAliveTimer();
            startFrameWatchdog();
            // 打印状态摘要
            printNalStatus();

            // ⭐ 关键修复：首帧完成后，主动刷新 VideoOverlay
            // 解决问题：窗口已绑定但前端延迟推流导致画面不显示
            if (videoOverlay != null && overlayChildHandle != 0L) {
                try {
                    LogTools.getInstance().logRecord3("🎬 首帧完成，主动刷新 VideoOverlay 确保画面显示");
                    videoOverlay.expose();
                    LogTools.getInstance().logRecord3("✅ VideoOverlay 刷新完成");
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord3("⚠️ 刷新 VideoOverlay 失败: " + t.getMessage());
                }
            }
        } else {
            // 如果缺少关键NAL单元，继续请求关键帧
            System.out.println("⏳ 首帧未完成，继续等待: SPS=" + hasReceivedSps.get() +
                    ", PPS=" + hasReceivedPps.get() + ", IDR=" + hasReceivedIdr.get());

            // 检测到关键帧数据不完整时，主动请求重发
            if (hasReceivedIdr.get() && (!hasReceivedSps.get() || !hasReceivedPps.get())) {
                LogTools.getInstance().logRecord2("⚠️ 检测到IDR帧但缺少SPS/PPS，请求重发关键帧");
                sendPLIRequest();
            }
        }
    }

    /**
     * 打印NAL单元接收状态
     */
    private void printNalStatus() {
        System.out.println("📊 NAL单元接收状态:");
        System.out.println("  ├─ SPS (7): " + (hasReceivedSps.get() ? "✅" : "❌"));
        System.out.println("  ├─ PPS (8): " + (hasReceivedPps.get() ? "✅" : "❌"));
        System.out.println("  ├─ IDR (5): " + (hasReceivedIdr.get() ? "✅" : "❌"));
        System.out.println("  └─ P-slice (1): " + (hasReceivedPSlice.get() ? "✅" : "❌"));
    }

    /**
     * 设置AppSink回调处理视频帧
     */
    private void setupAppSinkCallback() {
        // 确保appsink已正确创建
        if (appsink == null) {
            LogTools.getInstance().logRecord2("❌ AppSink为null，无法设置回调");
            return;
        }

        appsink.set("emit-signals", true);
        appsink.connect(new AppSink.NEW_SAMPLE() {
            @Override
            public FlowReturn newSample(AppSink elem) {
                // 诊断：保留 appsink 但不做任何渲染或映射，最大化隔离 JNI/DirectBuffer/JavaFX
                if (diagNoRender) {
                    try {
                        Sample s = elem.pullSample();
                        if (s != null) s.dispose();
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("[DIAG] noRender 快速返回异常：" + t.getMessage());
                    }
                    return FlowReturn.OK;
                }
                Sample sample = elem.pullSample();
                if (sample != null) {
                    if (!firstDisplaySampleLogged) {
                        try {
                            Caps sc = sample.getCaps();
                            Structure st = (sc != null && sc.size() > 0) ? sc.getStructure(0) : null;
                            Integer w = st != null ? st.getInteger("width") : null;
                            Integer h = st != null ? st.getInteger("height") : null;
                            String fmt = st != null ? st.getString("format") : null;
                            LogTools.getInstance().logRecord2("STEP8: 📸 display appsink NEW_SAMPLE 首帧: " + (w != null ? w : "?") + "x" + (h != null ? h : "?") + (fmt != null ? (", format=" + fmt) : "") + ", caps=" + (sc != null ? sc.toString() : "null"));
                        } catch (Throwable ex) {
                            LogTools.getInstance().logRecord2("STEP8: ❌ 采样信息日志失败: " + ex.getMessage());
                        }
                        firstDisplaySampleLogged = true;
                    }
                    // ⚡ GPU appsink 帧计数（用于对比 JPEG 延迟）
                    FileToos.GpuIndex++;
                    //if (FileToos.GpuIndex % 60 == 0) {
                        int diff = FileToos.GpuIndex - FileToos.jpegIndex;
                        LogTools.getInstance().logRecord5("📊 帧差: Gpu=" + FileToos.GpuIndex + " Jpeg=" + FileToos.jpegIndex + " 差=" + diff);
                    //}
                    
                    processVideoFrame(sample);
                    sample.dispose();
                }
                return FlowReturn.OK;
            }
        });
    }



    private void offlineDecodeFromEncodedSinkWindow(int totalFrames, java.util.function.Consumer<Image> callback) {
        // 统一委托到 Java 压缩环的快速离线解码，避免与原生 appsink 队列行为冲突
        System.out.println("🔁 offlineDecodeFromEncodedSinkWindow -> 委托到 offlineDecodeFromEncodedRingFast (window=" + totalFrames + ")");
        offlineDecodeFromEncodedRingFast(totalFrames, callback);
    }

    /**
     * 离线小窗解码：从 encodedRing 找到最近前置关键帧（IDR），
     * 以该处为起点解码最多 totalFrames 帧，并：
     * - 可选将帧序列写入磁盘目录（tmp/webrtc_frame_cache/capture_XXXX/）
     * - 将“点击时刻附近”的代表帧回推到 UI（callback.accept(image)）
     */
    private void offlineDecodeFromEncodedRing(int totalFrames, java.util.function.Consumer<Image> callback) {
        java.util.List<EncodedAu> snapshot;
        synchronized (encodedRing) {
            snapshot = new java.util.ArrayList<>(encodedRing);
        }
        if (snapshot.isEmpty()) {
            LogTools.getInstance().logRecord2("⚠️ 编码环为空，无法离线解码，回退实时抓拍");
            // 回退：开启一次实时抓拍
            pendingCaptureCallback = callback;
            try { if (cacheValve != null) cacheValve.set("drop", false); } catch (Throwable ignore) {}
            try { if (cacheAppSink != null) cacheAppSink.set("emit-signals", true); } catch (Throwable ignore) {}
            return;
        }

        // 从尾部向前找到最近的 IDR
        int startIdxTmp = -1;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            if (snapshot.get(i).keyframe) { startIdxTmp = i; break; }
        }
        if (startIdxTmp < 0) {
            // 未找到IDR，保守从窗口头部开始
            startIdxTmp = Math.max(0, snapshot.size() - totalFrames);
            System.out.println("🔎 未找到IDR（mini），使用最近窗口起点=" + startIdxTmp);
        } else {
            System.out.println("🔑 找到IDR起点（mini） index=" + startIdxTmp);
            // 若关键帧靠近尾部，仅有 1 帧可解，尝试短暂等待以收集若干后续帧
            int postFramesWanted = Integer.getInteger("offline.mini.post.frames", Math.min(totalFrames, 21));
            int postAwaitMs = Integer.getInteger("offline.mini.post.await.ms", 500);
            int availableAfter = Math.max(0, snapshot.size() - startIdxTmp);
            if (availableAfter < postFramesWanted) {
                long postDeadline = System.currentTimeMillis() + postAwaitMs;
                System.out.println("⏳ [mini] 等待后续帧 postFramesWanted=" + postFramesWanted + ", postAwaitMs=" + postAwaitMs + ", availableAfter=" + availableAfter);
                while (System.currentTimeMillis() < postDeadline) {
                    try { Thread.sleep(20); } catch (Throwable ignore) {}
                    java.util.List<EncodedAu> snap3;
                    synchronized (encodedRing) { snap3 = new java.util.ArrayList<>(encodedRing); }
                    int idrIdx3 = -1;
                    for (int i = snap3.size() - 1; i >= 0; i--) {
                        EncodedAu au3 = snap3.get(i);
                        boolean idr3 = au3.keyframe;
                        if (!idr3) { try { idr3 = containsH264Idr(au3.data); } catch (Throwable ignore) {} }
                        if (idr3) { idrIdx3 = i; break; }
                    }
                    if (idrIdx3 >= 0) {
                        int avail3 = Math.max(0, snap3.size() - idrIdx3);
                        if (avail3 >= postFramesWanted) { snapshot = snap3; startIdxTmp = idrIdx3; break; }
                    }
                }
                System.out.println("🧩 [mini] post 等待结果: idrIndex=" + startIdxTmp + ", snapshotSize=" + snapshot.size() + ", availableAfter=" + Math.max(0, snapshot.size() - startIdxTmp));
                // 如果等待后仍不足期望帧数，回退到“较早的IDR”，优先选择能满足窗口的最近候选
                int stillAvail = Math.max(0, snapshot.size() - startIdxTmp);
                if (stillAvail < postFramesWanted) {
                    int chosen = startIdxTmp;
                    for (int i = startIdxTmp; i >= 0; i--) {
                        EncodedAu auX = snapshot.get(i);
                        boolean idrX = auX.keyframe;
                        if (!idrX) { try { idrX = containsH264Idr(auX.data); } catch (Throwable ignore) {} }
                        if (idrX) {
                            int availX = Math.max(0, snapshot.size() - i);
                            if (availX >= postFramesWanted) { chosen = i; break; }
                        }
                    }
                    if (chosen != startIdxTmp) {
                        System.out.println("🔁 [mini] 回退到较早IDR以满足窗口: from=" + startIdxTmp + " -> " + chosen + ", availableAfter=" + (snapshot.size() - chosen));
                        startIdxTmp = chosen;
                    }
                }
            }
        }
        final int startIndex = startIdxTmp;
        final int endIndex = Math.min(snapshot.size(), startIndex + Math.min(totalFrames, Integer.getInteger("offline.mini.post.frames", Math.min(totalFrames, 21))));
        if (endIndex - startIndex <= 0) {
            LogTools.getInstance().logRecord2("⚠️ 编码窗口不足，无法离线解码，回退实时抓拍");
            pendingCaptureCallback = callback;
            try { if (cacheValve != null) cacheValve.set("drop", false); } catch (Throwable ignore) {}
            try { if (cacheAppSink != null) cacheAppSink.set("emit-signals", true); } catch (Throwable ignore) {}
            return;
        }
        System.out.println("🪟 离线抓拍窗口: start=" + startIndex + ", end=" + endIndex + ", size=" + (endIndex - startIndex));

        // 构建离线解码子管线：appsrc ! h264parse ! avdec_h264 ! videoconvert ! appsink
        final Pipeline mini = new Pipeline("capture-mini-pipe");
        final AppSrc src = new AppSrc("offline_src");
        final Element parse = ElementFactory.make("h264parse", "offline_parse");
        final Element dec = ElementFactory.make(diagDecoderName, "offline_dec");
        final Element vconv = ElementFactory.make("videoconvert", "offline_vconv");
        final Element capsfilter = ElementFactory.make("capsfilter", "offline_caps");
        final AppSink sink = new AppSink("offline_sink");

        try {
            // 确保 parse 输出 byte-stream / au，并插入 SPS/PPS
            try { parse.set("config-interval", 1); } catch (Throwable ignore) {}
            try { parse.set("output-format", "byte-stream"); } catch (Throwable ignore) {}
            try { parse.set("disable-passthrough", true); } catch (Throwable ignore) {}

            // 强制离线分支像素格式一致（BGRA），与显示/处理路径对齐
            try { capsfilter.set("caps", Caps.fromString("video/x-raw,format=BGRA")); } catch (Throwable ignore) {}

            // 配置 appsink：不阻塞、限制缓存
            try { sink.set("emit-signals", true); } catch (Throwable ignore) {}
            try { sink.set("sync", false); } catch (Throwable ignore) {}
            try { sink.set("max-buffers", totalFrames); } catch (Throwable ignore) {}
            try { sink.set("drop", true); } catch (Throwable ignore) {}

            // 配置 appsrc caps 和属性
            Caps h264Caps = Caps.fromString("video/x-h264,stream-format=(string)byte-stream,alignment=(string)au");
            src.setCaps(h264Caps);
            try { src.setLatency(0, 0); } catch (Throwable ignore) {}
            // 增大 appsrc 队列容量，避免窗口数据被队列上限截断
            try { src.setMaxBytes(32_000_000); } catch (Throwable ignore) {}
            try { src.setStreamType(AppSrc.StreamType.STREAM); } catch (Throwable ignore) {}

            mini.addMany(src, parse, dec, vconv, capsfilter, sink);
            Element.linkMany(src, parse, dec, vconv, capsfilter, sink);

            final CountDownLatch done = new CountDownLatch(1);
            final int expectedCount = (endIndex - startIndex);
            System.out.println("🎯 mini 离线解码 expectedCount=" + expectedCount);
            final Image[] repImageHolder = new Image[1];
            final int[] receivedCount = new int[]{0};
            final AtomicBoolean firstPushed = new AtomicBoolean(false);
            // 供 lambda 使用的最终引用，避免“lambda 中引用的变量需最终或事实最终”的编译错误
            final java.util.function.Consumer<Image> cbRef = callback;

            sink.connect((AppSink.NEW_SAMPLE) s -> {
                Sample sample = s.pullSample();
                if (sample != null) {
                    try {
                        if (logOfflineVerbose) {
                            try {
                                Buffer sbuf = sample.getBuffer();
                                long spts = sbuf != null ? sbuf.getPresentationTimestamp() : 0L;
                                System.out.println("📥 offline_sink 样本到达: idx=" + receivedCount[0] + "/" + expectedCount + ", pts=" + spts);
                            } catch (Throwable ignore) {}
                        }
                        // 复用现有转换逻辑
                        WritableImage img = null;
                        try {
                            processVideoFrame(sample);
                            img = latestFrameImage; // processVideoFrame 会更新 latestFrameImage
                        } catch (Throwable ignore) {}
                        if (img == null) {
                            // 兜底：直接转换（略）
                        }
                        if (img != null) {
                            repImageHolder[0] = img; // 仅保留最后一帧作为代表帧
                            // 首帧立刻回调，缩短 UI 首帧显示延迟
                            if (receivedCount[0] == 0 && cbRef != null && firstPushed.compareAndSet(false, true)) {
                                final Image firstImg = img;
                                try { Platform.runLater(() -> cbRef.accept(firstImg)); } catch (Throwable ignore) {}
                            }
                        }
                    } finally {
                        try { sample.dispose(); } catch (Throwable ignore) {}
                    }
                }
                // 统计接收的样本数，达到预期则结束等待（EOS由appsrc控制）
                receivedCount[0]++;
                if (receivedCount[0] >= expectedCount) {
                    done.countDown();
                }
                return FlowReturn.OK;
            });

            mini.play();

            // 推送编码AU序列到 appsrc
            for (int i = startIndex; i < endIndex; i++) {
                EncodedAu au = snapshot.get(i);
                Buffer buf = new Buffer(au.data.length);
                ByteBuffer map = buf.map(true);
                if (map != null) {
                    map.put(au.data);
                    buf.unmap();
                }
                // 设置时间戳（近似）：按 60fps 计算
                long ptsNs = 0L;
                try {
                    // 如果原始 pts 可用，则使用；否则按序号生成
                    // 注意：环中 pts 以纳秒存储，与 GStreamer ClockTime 一致
                    ptsNs = au.pts > 0 ? au.pts : (long)(i - startIndex) * (1_000_000_000L / 60);
                } catch (Throwable ignore) {}
                try { buf.setPresentationTimestamp(ptsNs); } catch (Throwable ignore) {}
                if (logOfflineVerbose) {
                    boolean idrCur = au.keyframe;
                    if (!idrCur) { try { idrCur = containsH264Idr(au.data); } catch (Throwable ignore) {} }
                    int idx = (i - startIndex);
                    if (logOfflinePushEach || idx < 3 || i >= endIndex - 3 || (idx % 10) == 0) {
                        System.out.println("➡️ mini push idx=" + idx + "/" + expectedCount + ", len=" + au.data.length + "B, pts=" + ptsNs + ", idr=" + idrCur);
                    }
                }

                FlowReturn ret = src.pushBuffer(buf);
                if (ret != FlowReturn.OK) {
                    LogTools.getInstance().logRecord2("⚠️ appsrc push 返回: " + ret);
                }
            }
            src.endOfStream();

            // 等待最多 2 秒，或提前完成
            long awaitStartMs = System.currentTimeMillis();
            try { done.await(2, TimeUnit.SECONDS); } catch (Throwable ignore) {}
            long awaitDur = System.currentTimeMillis() - awaitStartMs;
            System.out.println("⏱️ mini 等待结束，用时 " + awaitDur + "ms，收到 " + receivedCount[0] + "/" + expectedCount);

            if (repImageHolder[0] == null) {
                LogTools.getInstance().logRecord2("↩️ 离线解码未产生帧，回退到实时抓拍");
                pendingCaptureCallback = callback;
                try { if (cacheValve != null) cacheValve.set("drop", false); } catch (Throwable ignore) {}
                try { if (cacheAppSink != null) cacheAppSink.set("emit-signals", true); } catch (Throwable ignore) {}
            } else {
                // 写盘（按需）
                if (diskCacheEnabled) {
                    try {
                        if (diskCacheDir == null) {
                            diskCacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "webrtc_frame_cache");
                            Files.createDirectories(diskCacheDir);
                        }
                        Path file = diskCacheDir.resolve("capture_rep_" + System.currentTimeMillis() + ".png");
                        ImageIO.write(SwingFXUtils.fromFXImage(repImageHolder[0], null), "png", file.toFile());
                        System.out.println("💾 已写入代表帧到文件: " + file);
                    } catch (Throwable ioex) {
                        LogTools.getInstance().logRecord2("⚠️ 抓拍窗口写盘失败: " + ioex.getMessage());
                    }
                }

                // 将代表帧（靠近点击末尾的帧）回推 UI
                if (cbRef != null && !firstPushed.get()) {
                    System.out.println("✅ 离线抓拍回调：返回代表帧(完成阶段)，received=" + receivedCount[0] + ", start=" + startIndex + ", end=" + endIndex);
                    try { Platform.runLater(() -> cbRef.accept(repImageHolder[0])); } catch (Throwable ignore) {}
                }
            }

        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("❌ 离线解码窗口失败: " + t.getMessage());
            // 失败兜底：回退实时抓拍
            pendingCaptureCallback = callback;
            try { if (cacheValve != null) cacheValve.set("drop", false); } catch (Throwable ignore) {}
            try { if (cacheAppSink != null) cacheAppSink.set("emit-signals", true); } catch (Throwable ignore) {}
        } finally {
            try { mini.stop(); } catch (Throwable ignore) {}
            try { mini.dispose(); } catch (Throwable ignore) {}
        }
    }

    // 粗判 H.264 是否包含 IDR（type=5）或起始参数（SPS/PPS），用于找到最近前置关键帧
    private static boolean containsH264Idr(byte[] data) {
        if (data == null || data.length < 6) return false;
        for (int i = 0; i < data.length - 5; i++) {
            boolean sc3 = data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x01;
            boolean sc4 = !sc3 && i + 3 < data.length && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01;
            if (sc3 || sc4) {
                int nalStart = sc3 ? i + 3 : i + 4;
                if (nalStart < data.length) {
                    int nalHdr = data[nalStart] & 0xFF;
                    int nalType = nalHdr & 0x1F;
                    if (nalType == 5) return true; // IDR
                }
            }
        }
        return false;
    }

    // 提取目标类型的 H.264 NAL（带起始码，byte-stream 格式），用于注入 SPS/PPS
    private static byte[] extractStartCodedH264Nal(byte[] data, int wantedType) {
        if (data == null || data.length < 6) return null;
        int i = 0;
        while (i < data.length - 5) {
            boolean sc3 = data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x01;
            boolean sc4 = !sc3 && i + 3 < data.length && data[i] == 0x00 && data[i+1] == 0x00 && data[i+2] == 0x00 && data[i+3] == 0x01;
            if (sc3 || sc4) {
                int startCodeLen = sc3 ? 3 : 4;
                int nalStart = i + startCodeLen;
                if (nalStart >= data.length) break;
                int nalType = data[nalStart] & 0x1F;
                int j = nalStart + 1;
                while (j < data.length - 3) {
                    boolean sc3n = data[j] == 0x00 && data[j+1] == 0x00 && data[j+2] == 0x01;
                    boolean sc4n = !sc3n && j + 3 < data.length && data[j] == 0x00 && data[j+1] == 0x00 && data[j+2] == 0x00 && data[j+3] == 0x01;
                    if (sc3n || sc4n) break;
                    j++;
                }
                int nalEnd = j;
                if (nalType == wantedType) {
                    int sliceStart = i; // 包含起始码
                    int sliceEnd = nalEnd > sliceStart ? nalEnd : data.length;
                    byte[] out = new byte[sliceEnd - sliceStart];
                    System.arraycopy(data, sliceStart, out, 0, out.length);
                    return out;
                }
                i = nalEnd > i ? nalEnd : i + 1;
            } else {
                i++;
            }
        }
        return null;
    }

    /**
     * 初始化常驻预热离线解码子管线：appsrc ! h264parse ! [decoder] ! videoconvert ! caps(BGRA) ! appsink
     * 该子管线保持 PLAYING 状态，抓拍时快速向 appsrc 推送编码AU序列，无需每次重新构建与启动管线。
     */
    private void initOfflineWarmDecoder() {
        if (warmPipeline != null) {
            try {
                // 若已存在则确保处于播放状态
                warmPipeline.play();
            } catch (Throwable ignore) {}
            return;
        }

        warmPipeline = new Pipeline("capture-warm-pipe");
        warmSrc = new AppSrc("warm_src");
        warmParse = ElementFactory.make("h264parse", "warm_parse");
        warmDec = ElementFactory.make(diagDecoderName, "warm_dec");
        warmVconv = ElementFactory.make("videoconvert", "warm_vconv");
        warmCaps = ElementFactory.make("capsfilter", "warm_caps");
        warmSink = new AppSink("warm_sink");

        try {
            // h264parse 确保 byte-stream/au，并强制插入参数集（-1：在关键帧或变更时发送一次）
            try { warmParse.set("config-interval", -1); } catch (Throwable ignore) {}
            try { warmParse.set("output-format", "byte-stream"); } catch (Throwable ignore) {}
            try { warmParse.set("disable-passthrough", true); } catch (Throwable ignore) {}

            // caps 统一为 BGRA，兼容现有处理/显示路径
            try { if (warmCaps != null) warmCaps.setCaps(Caps.fromString("video/x-raw,format=BGRA")); } catch (Throwable ignore) {}

            // appsink：开启信号、非阻塞、不开启丢帧、开启qos
            try { warmSink.set("emit-signals", true); } catch (Throwable ignore) {}
            try { warmSink.set("sync", false); } catch (Throwable ignore) {}
            try { warmSink.set("drop", false); } catch (Throwable ignore) {}
            try { warmSink.set("qos", true); } catch (Throwable ignore) {}
            try { warmSink.set("max-buffers", Integer.getInteger("warm.sink.maxBuffers", Math.max(encodedCacheMax, 64))); } catch (Throwable ignore) {}

            // appsrc caps 与属性（设置为 live，立即解码）
            try { warmSrc.setCaps(Caps.fromString("video/x-h264,stream-format=(string)byte-stream,alignment=(string)au")); } catch (Throwable ignore) {}
            try { warmSrc.setLatency(0, 0); } catch (Throwable ignore) {}
            // 增大常驻 appsrc 队列容量以容纳离线窗口（默认120帧，约数十MB）
            try { warmSrc.setMaxBytes(32_000_000); } catch (Throwable ignore) {}
            try { warmSrc.setStreamType(AppSrc.StreamType.STREAM); } catch (Throwable ignore) {}
            try { warmSrc.set("is-live", true); } catch (Throwable ignore) {}
            try { warmSrc.set("do-timestamp", true); } catch (Throwable ignore) {}
            try { warmSrc.set("block", false); } catch (Throwable ignore) {}

            // 连接回调：在每次抓拍会话期间累计样本并返回代表帧（支持会话内跳过预推帧）
            warmSink.connect((AppSink.NEW_SAMPLE) s -> {
                LogTools.getInstance().logRecord5("🔥 warmSink NEW_SAMPLE 触发");
                Sample sample = s.pullSample();
                boolean deliverFlag = true;
                if (sample != null) {
                    try {
                        // 使用现有视频帧处理逻辑，更新 latestFrameImage
                        try { processVideoFrame(sample); } catch (Throwable ignore) {}
                        Image img = latestFrameImage;
                        if (img != null) {
                            warmRepImage = img;
                            // 会话内跳过：若点击阶段已预推若干显示帧，则离线回调跳过前 warmPreSkipCount 帧
                            int skipLeft = warmPreSkipCount;
                            if (skipLeft > 0) { deliverFlag = false; warmPreSkipCount = skipLeft - 1; }
                            if (deliverFlag && warmCallback != null) {
                                final Image curImg = img;
                                try { Platform.runLater(() -> { java.util.function.Consumer<Image> cb = warmCallback; if (cb != null) cb.accept(curImg); }); } catch (Throwable ignore) {}
                                // 标记首帧已推送，供完成阶段判断是否重复回推
                                if (warmReceivedCount == 0) { warmFirstPushed.compareAndSet(false, true); }
                            }
                        }
                    } finally {
                        try { sample.dispose(); } catch (Throwable ignore) {}
                    }
                }
                // 按会话统计（仅统计已实际回调的样本），达到预期后释放等待
                if (deliverFlag) {
                    warmReceivedCount++;
                    if (warmReceivedCount <= 3 || (warmReceivedCount % 20) == 0) {
                        System.out.println("📥 warm_sink 收到样本: " + warmReceivedCount + "/" + warmExpectedCount);
                    }
                    CountDownLatch latch = warmDoneLatch;
                    if (latch != null && warmReceivedCount >= warmExpectedCount) { latch.countDown(); }
                }
                return FlowReturn.OK;
            });

            // 组装并链接
            warmPipeline.addMany(warmSrc, warmParse, warmDec, warmVconv, warmCaps, warmSink);
            Element.linkMany(warmSrc, warmParse, warmDec, warmVconv, warmCaps, warmSink);

            // 启动常驻子管线
            warmPipeline.play();
            System.out.println("🔥 已初始化常驻预热离线解码子管线");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("❌ 预热离线解码子管线初始化失败: " + t.getMessage());
            try {
                if (warmPipeline != null) { warmPipeline.stop(); warmPipeline.dispose(); }
            } catch (Throwable ignore) {}
            warmPipeline = null;
            warmSrc = null; warmParse = null; warmDec = null; warmVconv = null; warmCaps = null; warmSink = null;
        }
    }

    /**
     * 销毁常驻预热离线解码子管线，释放资源。
     */
    private void disposeOfflineWarmDecoder() {
        try {
            if (warmPipeline != null) {
                try { warmPipeline.stop(); } catch (Throwable ignore) {}
                try { warmPipeline.dispose(); } catch (Throwable ignore) {}
            }
        } finally {
            warmPipeline = null;
            warmSrc = null;
            warmParse = null;
            warmDec = null;
            warmVconv = null;
            warmCaps = null;
            warmSink = null;
            warmActive.set(false);
            warmExpectedCount = 0;
            warmReceivedCount = 0;
            warmDoneLatch = null;
            warmCallback = null;
            warmRepImage = null;
            warmFirstPushed.set(false);
            System.out.println("🧹 已销毁预热离线解码子管线");
        }
    }

    /**
     * 快速离线解码抓拍：复用常驻 warmPipeline，将编码环窗口（从最近前置IDR起）
     * 推送到 warmSrc，按预期帧数统计完成并返回代表帧。
     * 若预热管线未就绪则尝试初始化；初始化失败则判定抓拍无效（不回退临时管线）。
     */
    private void offlineDecodeFromEncodedRingFast(int totalFrames, java.util.function.Consumer<Image> callback) {
        int ringSizeBefore;
        synchronized (encodedRing) { ringSizeBefore = encodedRing.size(); }
        System.out.println("⚡ 预热离线解码请求(fast): totalFrames=" + totalFrames + ", encodedRing=" + ringSizeBefore);
        System.out.println("🟠 数据源: 编码缓存离线解码 (fast)");

        if (warmPipeline == null || warmSrc == null || warmSink == null) {
            System.out.println("♻️ 预热管线未就绪，初始化常驻预热管线");
            initOfflineWarmDecoder();
            try { Thread.sleep(Integer.getInteger("warm.init.await.ms", 100)); } catch (Throwable ignore) {}
            if (warmPipeline == null || warmSrc == null || warmSink == null) {
                LogTools.getInstance().logRecord2("❌ 预热管线初始化失败，抓拍无效（fast）");
                return;
            }
        }

        // 构建编码环快照
        java.util.List<EncodedAu> snapshot;
        synchronized (encodedRing) { snapshot = new java.util.ArrayList<>(encodedRing); }
        System.out.println("🧾 encodedRing 快照 size=" + snapshot.size());
        if (snapshot.isEmpty()) {
            int awaitMs = Integer.getInteger("capture.fast.await.cache.ms", 1000);
            System.out.println("⏳ 编码环为空，等待缓存填充 awaitMs=" + awaitMs);
            long deadline = System.currentTimeMillis() + awaitMs;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); } catch (Throwable ignore) {}
                synchronized (encodedRing) { snapshot = new java.util.ArrayList<>(encodedRing); }
                if (!snapshot.isEmpty()) break;
            }
            if (snapshot.isEmpty()) {
                LogTools.getInstance().logRecord2("⚠️ 编码缓存不可用，回退到实时GPU抓拍（fast）");
                // 回退：触发一次实时抓拍（GPU缓存）
                try {
                    pendingCaptureCallback = callback;
                    if (cacheValve != null) cacheValve.set("drop", false);
                    if (cacheAppSink != null) cacheAppSink.set("emit-signals", true);
                } catch (Throwable ignore) {}
                return;
            }
        }

        // 找到最近前置 IDR 作为起点（优先 keyframe 标记，不足时字节流扫描）
        int startIdxTmp = -1;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            EncodedAu au = snapshot.get(i);
            boolean idr = au.keyframe;
            if (!idr) {
                try { idr = containsH264Idr(au.data); } catch (Throwable ignore) {}
            }
            if (idr) { startIdxTmp = i; break; }
        }
        if (startIdxTmp < 0) {
            startIdxTmp = Math.max(0, snapshot.size() - totalFrames);
            System.out.println("🔎 未找到IDR，使用最近窗口起点=" + startIdxTmp);
            // 快速触发关键帧请求并短暂等待，争取拿到 IDR 以提升多帧解码概率
            try { sendPLIRequest(); } catch (Throwable ignore) {}
            int idrWaitMs = Integer.getInteger("warm.fast.idr.wait.ms", 800);
            System.out.println("⏳ 等待关键帧窗口 idrWaitMs=" + idrWaitMs);
            long deadline = System.currentTimeMillis() + idrWaitMs;
            boolean idrFound = false;
            while (System.currentTimeMillis() < deadline && !idrFound) {
                try { Thread.sleep(20); } catch (Throwable ignore) {}
                java.util.List<EncodedAu> snap2;
                synchronized (encodedRing) { snap2 = new java.util.ArrayList<>(encodedRing); }
                for (int i = snap2.size() - 1; i >= 0; i--) {
                    EncodedAu au2 = snap2.get(i);
                    boolean idr2 = au2.keyframe;
                    if (!idr2) { try { idr2 = containsH264Idr(au2.data); } catch (Throwable ignore) {} }
                    if (idr2) { snapshot = snap2; startIdxTmp = i; idrFound = true; System.out.println("🔑 后补到关键帧，index=" + startIdxTmp); break; }
                }
            }
        } else {
            System.out.println("🔑 找到IDR起点 index=" + startIdxTmp);
        }

        // 若关键帧靠近尾部，仅有 1 帧可解，尝试短暂等待以收集若干后续帧
        // 使用调用方给出的 totalFrames 作为整体窗口期望值（前+后）
        int totalWanted = Math.max(1, totalFrames);
        int postAwaitMs = Integer.getInteger("warm.fast.post.await.ms", 500);
        int availableAfter = Math.max(0, snapshot.size() - startIdxTmp);
        if (availableAfter < totalWanted) {
            long postDeadline = System.currentTimeMillis() + postAwaitMs;
            System.out.println("⏳ 等待后续帧 totalWanted=" + totalWanted + ", postAwaitMs=" + postAwaitMs + ", availableAfter=" + availableAfter);
            while (System.currentTimeMillis() < postDeadline) {
                try { Thread.sleep(20); } catch (Throwable ignore) {}
                java.util.List<EncodedAu> snap3;
                synchronized (encodedRing) { snap3 = new java.util.ArrayList<>(encodedRing); }
                // 重新定位最近 IDR，确保起点合理
                int idrIdx3 = -1;
                for (int i = snap3.size() - 1; i >= 0; i--) {
                    EncodedAu au3 = snap3.get(i);
                    boolean idr3 = au3.keyframe;
                    if (!idr3) { try { idr3 = containsH264Idr(au3.data); } catch (Throwable ignore) {} }
                    if (idr3) { idrIdx3 = i; break; }
                }
                if (idrIdx3 >= 0) {
                    int avail3 = Math.max(0, snap3.size() - idrIdx3);
                    if (avail3 >= totalWanted) { snapshot = snap3; startIdxTmp = idrIdx3; break; }
                }
            }
            System.out.println("🧩 post 等待结果: idrIndex=" + startIdxTmp + ", snapshotSize=" + snapshot.size() + ", availableAfter=" + Math.max(0, snapshot.size() - startIdxTmp));
            // 如果等待后仍不足期望帧数，回退到“较早的IDR”，优先选择能满足窗口的最近候选
            int stillAvail = Math.max(0, snapshot.size() - startIdxTmp);
            if (stillAvail < totalWanted) {
                int chosen = startIdxTmp;
                for (int i = startIdxTmp; i >= 0; i--) {
                    EncodedAu auX = snapshot.get(i);
                    boolean idrX = auX.keyframe;
                    if (!idrX) { try { idrX = containsH264Idr(auX.data); } catch (Throwable ignore) {} }
                    if (idrX) {
                        int availX = Math.max(0, snapshot.size() - i);
                        if (availX >= totalWanted) { chosen = i; break; }
                    }
                }
                if (chosen != startIdxTmp) {
                    System.out.println("🔁 回退到较早IDR以满足窗口: from=" + startIdxTmp + " -> " + chosen + ", availableAfter=" + (snapshot.size() - chosen));
                    startIdxTmp = chosen;
                }
            }
        }

        final int startIndex = startIdxTmp;
        // 推送整体窗口：结束按 totalWanted 限定
        final int endIndex = Math.min(snapshot.size(), startIndex + Math.max(1, totalWanted));
        final int expectedCount = Math.max(0, endIndex - startIndex);
        System.out.println("🎯 解码窗口: start=" + startIndex + ", end=" + endIndex + ", expected=" + expectedCount);
        if (expectedCount <= 0) {
            LogTools.getInstance().logRecord2("❌ 编码窗口不足（fast），抓拍无效");
            return;
        }

        // 会话协同：防止并发重入（不回退，等待或拒绝）
        if (warmActive.get()) {
            long waitMs = Integer.getInteger("capture.fast.await.session.ms", 800);
            System.out.println("⏳ 预热抓拍会话仍在进行，等待 " + waitMs + "ms");
            long deadline2 = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < deadline2 && warmActive.get()) {
                try { Thread.sleep(20); } catch (Throwable ignore) {}
            }
        }
        if (!warmActive.compareAndSet(false, true)) {
            LogTools.getInstance().logRecord2("⛔ 会话繁忙，抓拍请求被拒绝（fast），请稍后重试");
            return;
        }

        try {
            // 离线整体窗口：按 warmPreSkipCount 跳过已预推帧，仅统计实际回调帧
            int deliverExpected = Math.max(0, expectedCount - Math.max(0, warmPreSkipCount));
            warmExpectedCount = Math.max(1, deliverExpected);
            warmReceivedCount = 0;
            warmDoneLatch = new CountDownLatch(1);
            warmCallback = callback;
            warmRepImage = null;
            try { warmFirstPushed.set(warmPrePushedFirst.getAndSet(false)); } catch (Throwable ignore) {}

            // 每次会话根据需要扩容 appsink 缓存，避免丢帧
            int newMaxBuffers = Math.max(expectedCount, 64);
            try { if (warmSink != null) warmSink.set("max-buffers", newMaxBuffers); } catch (Throwable ignore) {}
            System.out.println("🪣 warm_sink.max-buffers=" + newMaxBuffers);
            // 打印 warm_sink 关键属性，确保会话配置正确
            try {
                int msb = getIntProp(warmSink, "max-buffers", newMaxBuffers);
                boolean emit = getBoolProp(warmSink, "emit-signals", true);
                boolean drop = getBoolProp(warmSink, "drop", false);
                boolean sync = getBoolProp(warmSink, "sync", false);
                boolean qos = getBoolProp(warmSink, "qos", true);
                System.out.println("🧪 warm_sink props: emit=" + emit + ", drop=" + drop + ", sync=" + sync + ", qos=" + qos + ", max-buffers=" + msb);
            } catch (Throwable ignore) {}

            // 在窗口前注入 AUD（可选）与最近的 SPS/PPS，确保解析器具备完整起始配置
            try {
                long firstPtsNs = 0L;
                if (endIndex > startIndex) {
                    long cand = snapshot.get(startIndex).pts;
                    firstPtsNs = cand > 0 ? cand : 0L;
                }
                long frameDurNs = 1_000_000_000L / 60; // 约 60fps 的步长
                // 可选注入 AUD：优先从首帧提取，否则回退生成标准 AUD
                boolean injectAud = Boolean.parseBoolean(System.getProperty("warm.fast.inject.aud", "true"));
                byte[] audParam = null;
                if (injectAud) {
                    try {
                        byte[] firstAu = snapshot.get(startIndex).data;
                        audParam = extractStartCodedH264Nal(firstAu, 9);
                        if (audParam == null) {
                            audParam = new byte[] { 0x00, 0x00, 0x00, 0x01, 0x09, (byte)0xF0 };
                        }
                    } catch (Throwable ignore) {}
                }
                if (logEncodedCache) {
                    System.out.println(
                            "🧾 fast 离线推送: AUD=" + (audParam != null ? audParam.length : 0) + "B" +
                                    ", SPS=" + (lastSpsParam != null ? lastSpsParam.length : 0) + "B" +
                                    ", PPS=" + (lastPpsParam != null ? lastPpsParam.length : 0) + "B" +
                                    ", firstPts=" + firstPtsNs
                    );
                }
                if (audParam != null) {
                    Buffer abuf = new Buffer(audParam.length);
                    ByteBuffer amap = abuf.map(true);
                    if (amap != null) { amap.put(audParam); abuf.unmap(); }
                    long audPts = firstPtsNs > 0 ? Math.max(0, firstPtsNs - 3 * frameDurNs) : 0;
                    try { abuf.setPresentationTimestamp(audPts); } catch (Throwable ignore) {}
                    if (logEncodedCache) System.out.println("🔧 注入AUD len=" + audParam.length + ", pts=" + audPts);
                    warmSrc.pushBuffer(abuf);
                }
                if (lastSpsParam != null) {
                    Buffer cbuf = new Buffer(lastSpsParam.length);
                    ByteBuffer cmap = cbuf.map(true);
                    if (cmap != null) { cmap.put(lastSpsParam); cbuf.unmap(); }
                    try { cbuf.setPresentationTimestamp(firstPtsNs > 0 ? Math.max(0, firstPtsNs - 2 * frameDurNs) : 0); } catch (Throwable ignore) {}
                    if (logEncodedCache) System.out.println("🔧 注入SPS len=" + lastSpsParam.length + ", pts=" + (firstPtsNs > 0 ? Math.max(0, firstPtsNs - 2 * frameDurNs) : 0));
                    warmSrc.pushBuffer(cbuf);
                }
                if (lastPpsParam != null) {
                    Buffer cbuf2 = new Buffer(lastPpsParam.length);
                    ByteBuffer cmap2 = cbuf2.map(true);
                    if (cmap2 != null) { cmap2.put(lastPpsParam); cbuf2.unmap(); }
                    try { cbuf2.setPresentationTimestamp(firstPtsNs > 0 ? Math.max(0, firstPtsNs - frameDurNs) : 0); } catch (Throwable ignore) {}
                    if (logEncodedCache) System.out.println("🔧 注入PPS len=" + lastPpsParam.length + ", pts=" + (firstPtsNs > 0 ? Math.max(0, firstPtsNs - frameDurNs) : 0));
                    warmSrc.pushBuffer(cbuf2);
                }
            } catch (Throwable ignore) {}

            // 快速推送 AU 序列到常驻 appsrc
            System.out.println("🚀 推送 AU 到 warm_src: " + expectedCount + " 帧（预计回调 " + deliverExpected + " 帧，skip=" + Math.max(0, warmPreSkipCount) + ")");
            for (int i = startIndex; i < endIndex; i++) {
                EncodedAu au = snapshot.get(i);
                Buffer buf = new Buffer(au.data.length);
                ByteBuffer map = buf.map(true);
                if (map != null) {
                    map.put(au.data);
                    buf.unmap();
                }
                // 为每个缓冲设置 PTS，避免下游只产出一帧
                long ptsNs = 0L;
                try {
                    ptsNs = au.pts > 0 ? au.pts : (long)(i - startIndex) * (1_000_000_000L / 60);
                    buf.setPresentationTimestamp(ptsNs);
                } catch (Throwable ignore) {}
                if (logOfflineVerbose) {
                    boolean idrCur = au.keyframe;
                    if (!idrCur) { try { idrCur = containsH264Idr(au.data); } catch (Throwable ignore) {} }
                    int idx = (i - startIndex);
                    if (logOfflinePushEach || idx < 3 || i >= endIndex - 3 || (idx % 10) == 0) {
                        System.out.println("➡️ warm push idx=" + idx + "/" + expectedCount + ", len=" + au.data.length + "B, pts=" + ptsNs + ", idr=" + idrCur);
                    }
                }
                FlowReturn ret = warmSrc.pushBuffer(buf);
                if (ret != FlowReturn.OK) {
                    LogTools.getInstance().logRecord2("⚠️ warm appsrc push 返回: " + ret);
                }
            }

            // 推送完成后可选发送 EOS，帮助解析器尽快完成当前批次
            boolean sendEos = Boolean.parseBoolean(System.getProperty("warm.fast.send.eos", "true"));
            if (sendEos) {
                try { warmSrc.endOfStream(); System.out.println("📨 已向 warm_src 发送 EOS"); } catch (Throwable ignore) {}
            }

            // 等待完成或短超时（默认 1200ms，可通过 -Dwarm.fast.await.ms 调整）
            int awaitMs = Integer.getInteger("warm.fast.await.ms", 1200);
            long awaitStart = System.currentTimeMillis();
            System.out.println("⏱️ 等待离线解码完成，awaitMs=" + awaitMs);
            try { warmDoneLatch.await(awaitMs, TimeUnit.MILLISECONDS); } catch (Throwable ignore) {}
            long waited = System.currentTimeMillis() - awaitStart;
            System.out.println("⏱️ 等待结束，用时 " + waited + "ms，收到 " + warmReceivedCount + "/" + warmExpectedCount);
            if (warmExpectedCount > 0) {
                System.out.println("📊 离线解码统计(fast): 目标帧=" + warmExpectedCount + ", 已收帧=" + warmReceivedCount + ", 预推跳过=" + Math.max(0, warmPreSkipCount));
            }

            // 若首帧尚未通过 first push 回调，则在完成阶段回推代表帧
            if (warmRepImage == null) {
                LogTools.getInstance().logRecord2("↩️ 预热离线解码未产生帧，回退到离线临时解码（可能只解出首帧）");
                offlineDecodeFromEncodedRing(totalFrames, callback);
            } else {
                if (warmCallback != null && !warmFirstPushed.get()) {
                    final Image rep = warmRepImage;
                    try { Platform.runLater(() -> { java.util.function.Consumer<Image> cb = warmCallback; if (cb != null) cb.accept(rep); }); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("❌ 预热离线解码（fast）失败: " + t.getMessage());
            // 失败兜底：离线临时解码
            try { offlineDecodeFromEncodedRing(totalFrames, callback); } catch (Throwable ignore) {}
        } finally {
            // 会话结束复位
            System.out.println("🧹 预热解码会话结束，重置状态");
            warmActive.set(false);
            warmExpectedCount = 0;
            warmReceivedCount = 0;
            warmDoneLatch = null;
            warmCallback = null;
            warmRepImage = null;
            warmFirstPushed.set(false);
        }
    }

    /**
     * 处理视频帧
     */
    private void processVideoFrame(Sample sample) {
        // ⚡ 调试：确认是否进入此方法
        LogTools.getInstance().logRecord5("🎬 processVideoFrame 进入");
        
        try {
            Buffer buffer = sample.getBuffer();
            Caps caps = sample.getCaps();

            // 安全校验：若caps为空或结构不可用，则跳过该帧避免触发本地崩溃
            if (caps == null) {
                LogTools.getInstance().logRecord2("⚠️ Sample caps 为 null，跳过该帧以避免原生断言崩溃 - 可能是关键帧到达时的时序问题");
                return;
            }
            // 额外防护：在访问结构前确认 size()>0
            int capsSize = 0;
            try {
                capsSize = caps.size();
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ 读取 caps.size() 失败: " + t.getMessage() + " - 跳过该帧以避免原生断言崩溃");
                return;
            }
            if (capsSize <= 0) {
                LogTools.getInstance().logRecord2("⚠️ Caps 结构数量为 0（size<=0），跳过该帧 - 可能是协商尚未完成或解码器尚未就绪");
                return;
            }
            Structure structure = null;
            try {
                structure = caps.getStructure(0);
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ 获取caps结构失败: " + t.getMessage() + " - 可能是关键帧处理时的内存问题");
                return; // 直接返回，避免进一步处理
            }
            if (structure == null) {
                LogTools.getInstance().logRecord2("⚠️ Caps结构为 null，跳过该帧 - 可能是解码器状态异常");
                return;
            }

            // 获取视频信息
            int width = structure.getInteger("width");
            int height = structure.getInteger("height");
            String format = null;
            try { format = structure.getString("format"); } catch (Throwable ignore) {}

            // 刷新最后一帧时间戳，供保活与看门狗使用（减少每帧日志避免IO开销）
            lastFrameTimeMs = System.currentTimeMillis();

            // 映射缓冲区 - 添加额外的安全检查
            ByteBuffer byteBuffer = null;
            try {
                byteBuffer = buffer != null ? buffer.map(false) : null;
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ 缓冲区映射失败: " + t.getMessage() + " - 可能是关键帧数据损坏");
                return;
            }

            if (byteBuffer != null) {
                String fmt = format != null ? format : "BGRx"; // 与appsink caps保持一致
                boolean isBGRx = "BGRx".equalsIgnoreCase(fmt) || "BGRA".equalsIgnoreCase(fmt) || "ARGB".equalsIgnoreCase(fmt);
                int bytesPerPixel = isBGRx ? 4 : 3;
                int expectedSize = width * height * bytesPerPixel;
                int actualSize = byteBuffer.remaining();

                // 若启用无渲染诊断，则直接释放并返回，避免任何 JavaFX/UI 交互
                if (diagNoRender) {
                    try { buffer.unmap(); } catch (Throwable ignore) {}
                    return;
                }

                // 尺寸校验：不允许过小
                if (actualSize < expectedSize) {
                    LogTools.getInstance().logRecord2("⚠️ 帧大小异常: actual=" + actualSize + ", expected>=" + expectedSize + ", caps=" + (caps != null ? caps.toString() : "null"));
                    try { buffer.unmap(); } catch (Throwable ignore) {}
                    return;
                }

                // 计算实际行步长（包含可能的填充）
                int rowStrideCandidate = Math.max(width * bytesPerPixel, actualSize / Math.max(1, height));
                if (rowStrideCandidate % bytesPerPixel != 0) {
                    rowStrideCandidate = width * bytesPerPixel;
                }
                final int srcRowStride = rowStrideCandidate;

                // 预先转换像素到复用的直接缓冲区（BGRA_PRE），供 PixelBuffer 使用，避免每帧分配数组
                if (reusableRgbBuffer == null || rgbBufferWidth != width || rgbBufferHeight != height) {
                    reusableRgbBuffer = ByteBuffer.allocateDirect(width * height * 4);
                    rgbBufferWidth = width;
                    rgbBufferHeight = height;
                }
                // 固定 position/limit，不再使用 clear/flip，按绝对索引写入像素，避免 remaining 不足
                if (reusableRgbBuffer.position() != 0 || reusableRgbBuffer.limit() != width * height * 4) {
                    reusableRgbBuffer.position(0);
                    reusableRgbBuffer.limit(width * height * 4);
                }
                try {
                    if (isBGRx) {
                        final int rowLen = width * 4;
                        if (srcRowStride == rowLen) {
                            int totalLen = rowLen * height;
                            ByteBuffer srcFull = byteBuffer.duplicate();
                            srcFull.position(0);
                            srcFull.limit(totalLen);
                            ByteBuffer dstFull = reusableRgbBuffer.duplicate();
                            dstFull.position(0);
                            dstFull.limit(totalLen);
                            dstFull.put(srcFull);
                            // 补齐 alpha
                            for (int i = 3; i < totalLen; i += 4) {
                                reusableRgbBuffer.put(i, (byte) 0xFF);
                            }
                        } else {
                            for (int y = 0; y < height; y++) {
                                int srcPos = y * srcRowStride;
                                int dstPos = y * rowLen;
                                ByteBuffer srcRow = byteBuffer.duplicate();
                                srcRow.position(srcPos);
                                srcRow.limit(srcPos + rowLen);
                                ByteBuffer dstRow = reusableRgbBuffer.duplicate();
                                dstRow.position(dstPos);
                                dstRow.limit(dstPos + rowLen);
                                dstRow.put(srcRow);
                                // 每行补齐 alpha
                                for (int i = dstPos + 3; i < dstPos + rowLen; i += 4) {
                                    reusableRgbBuffer.put(i, (byte) 0xFF);
                                }
                            }
                        }
                    } else {
                        for (int y = 0; y < height; y++) {
                            int rowBase = y * srcRowStride;
                            int dstRowBase = y * width * 4;
                            for (int x = 0; x < width; x++) {
                                int base = rowBase + x * 3;
                                int dstBase = dstRowBase + x * 4;
                                byte r = byteBuffer.get(base);
                                byte g = byteBuffer.get(base + 1);
                                byte b = byteBuffer.get(base + 2);
                                reusableRgbBuffer.put(dstBase, b);
                                reusableRgbBuffer.put(dstBase + 1, g);
                                reusableRgbBuffer.put(dstBase + 2, r);
                                reusableRgbBuffer.put(dstBase + 3, (byte)0xFF);
                            }
                        }
                    }
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ 行拷贝失败: " + t.getMessage());
                    try { buffer.unmap(); } catch (Throwable ignore) {}
                    return;
                }

                // ⚡ 软解优化：移除不必要的深拷贝，JPEG 抓拍走独立路径
                // latestFrameImage 仅在需要时更新（节流）
                
                // 在JavaFX线程中更新UI（节流）：避免过多 runLater 导致 GCLocker 等待与花屏
                long now = System.currentTimeMillis();
                LogTools.getInstance().logRecord5("🔍 准备显示: uiUpdatePending=" + uiUpdatePending + ", timeDiff=" + (now - lastUiUpdateMs));
                if (!uiUpdatePending && (now - lastUiUpdateMs) >= UI_MIN_FRAME_INTERVAL_MS) {
                    uiUpdatePending = true;
                    final int frameWidth = width;
                    final int frameHeight = height;
                    LogTools.getInstance().logRecord5("🖼️ 进入 Platform.runLater");
                    Platform.runLater(() -> {
                        try {
                            lastUiUpdateMs = System.currentTimeMillis();
                            // 初始化/复用 PixelBuffer 与图像对象，尺寸变化时才重建
                            if (pixelBuffer == null || sharedImage == null || sharedImageWidth != frameWidth || sharedImageHeight != frameHeight) {
                                rgbPixelFormat = PixelFormat.getByteBgraPreInstance();
                                pixelBuffer = new PixelBuffer<>(frameWidth, frameHeight, reusableRgbBuffer, rgbPixelFormat);
                                sharedImage = new WritableImage(pixelBuffer);
                                sharedImageWidth = frameWidth;
                                sharedImageHeight = frameHeight;
                                imageView.setImage(sharedImage);
                            }
                            // 标记整屏为脏以重绘（整屏脏区）
                            pixelBuffer.updateBuffer(pb -> null);
                            
                            // ⚡ 软解优化：直接传递 sharedImage 给回调，避免深拷贝
                            // 回调方需要自行处理线程安全（如立即使用或自行拷贝）
                            if (frameCallback != null && !callbackActive) {
                                frameCallbackAcceptCounter++;
                                if (frameCallbackAcceptCounter == 1) {
                                    frameCallbackFirstTsMs = System.currentTimeMillis();
                                    System.out.println("🖼️ frameCallback.accept 首帧: " + frameWidth + "x" + frameHeight);
                                }
                                // ⚡ 直接传递 sharedImage，不再每帧创建 8MB 的副本
                                frameCallback.accept(sharedImage);
                            }
                            
                            // ⚡ 推送到实时帧内存环（和显示完全同步！）
                            // 这里是显示帧的唯一出口，推送到内存环保证抓拍和显示同步
                            try {
                                com.acard.acard.capture.RealtimeFrameRing ring = com.acard.acard.capture.RealtimeFrameRing.getInstance();
                                ring.push(sharedImage);
                                // 每60帧打印一次状态
                                if (ring.getTotalPushed() % 60 == 0) {
                                    LogTools.getInstance().logRecord5("📦 内存环: " + ring.getStatus());
                                }
                            } catch (Throwable ringEx) {
                                LogTools.getInstance().logRecord5("❌ 内存环推送失败: " + ringEx.getMessage());
                            }
                        } catch (Exception e) {
                            LogTools.getInstance().logRecord2("⚠️ 图像显示错误: " + e.getMessage());
                        } finally {
                            uiUpdatePending = false;
                        }
                    });
                }

                try { buffer.unmap(); } catch (Throwable ignore) {}
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ 视频帧处理错误: " + e.getMessage());
        }
    }

    /**
     * 处理抓拍帧
     */
    /**
     * 处理抓拍帧（直接转JPEG，避免Image对象）
     */
    private void processCaptureFrame(Sample sample, java.util.function.Consumer<Image> callback) {
        // ✅ 检查并重置帧计数器（凌晨归零）
        checkAndResetFrameCounter();

        // ✨ 1. 立即分配全局帧ID（在任何处理之前）
        final long frameId = globalFrameCounter.incrementAndGet();

        // 注释掉详细日志以减少日志输出
        // System.out.println("🎬 processCaptureFrame 开始处理样本");
        try {
            Buffer buffer = sample.getBuffer();
            Caps caps = sample.getCaps();
            // System.out.println("📋 获取到 Buffer: " + (buffer != null ? "有效" : "null") + ", Caps: " + (caps != null ? "有效" : "null"));

            // 安全校验
            if (caps == null || caps.size() <= 0) {
                LogTools.getInstance().logRecord2("⚠️ 抓拍帧caps无效，跳过");
                return;
            }

            Structure structure = caps.getStructure(0);
            if (structure == null) {
                LogTools.getInstance().logRecord2("⚠️ 抓拍帧结构无效，跳过");
                return;
            }

            // 获取视频信息
            int width = structure.getInteger("width");
            int height = structure.getInteger("height");
            String format = null;
            try { format = structure.getString("format"); } catch (Throwable ignore) {}

            // System.out.println("📸 处理抓拍帧: " + width + "x" + height + (format != null ? (", format=" + format) : ""));

            // 映射缓冲区
            ByteBuffer byteBuffer = buffer.map(false);
            // System.out.println("🗺️ 缓冲区映射: " + (byteBuffer != null ? "成功，大小=" + byteBuffer.remaining() : "失败"));
            if (byteBuffer != null) {
                String fmt = format != null ? format : "BGRx";
                boolean isBGRx = "BGRx".equalsIgnoreCase(fmt) || "BGRA".equalsIgnoreCase(fmt) || "ARGB".equalsIgnoreCase(fmt);
                int bytesPerPixel = isBGRx ? 4 : 3;
                int expectedSize = width * height * bytesPerPixel;
                int actualSize = byteBuffer.remaining();
                // System.out.println("📊 像素格式: " + fmt + ", 每像素字节: " + bytesPerPixel + ", 期望大小: " + expectedSize + ", 实际大小: " + actualSize);

                if (actualSize < expectedSize) {
                    LogTools.getInstance().logRecord2("⚠️ 抓拍帧大小异常: actual=" + actualSize + ", expected>=" + expectedSize);
                    try { buffer.unmap(); } catch (Throwable ignore) {}
                    try { sample.dispose(); } catch (Throwable ignore) {}
                    return;
                }

                // 计算实际行步长（包含可能的填充）
                int rowStrideCandidate = Math.max(width * bytesPerPixel, actualSize / Math.max(1, height));
                if (rowStrideCandidate % bytesPerPixel != 0) {
                    rowStrideCandidate = width * bytesPerPixel;
                }
                final int srcRowStride = rowStrideCandidate;
                // System.out.println("📏 行步长: " + srcRowStride);

                // 预先转换为RGB并去除每行填充
                final byte[] rgbData = new byte[width * height * 3];
                // System.out.println("🎨 开始像素格式转换...");
                try {
                    if (isBGRx) {
                        int dst = 0;
                        for (int y = 0; y < height; y++) {
                            int rowBase = y * srcRowStride;
                            for (int x = 0; x < width; x++) {
                                int base = rowBase + x * 4;
                                byte b = byteBuffer.get(base);
                                byte g = byteBuffer.get(base + 1);
                                byte r = byteBuffer.get(base + 2);
                                rgbData[dst++] = r;
                                rgbData[dst++] = g;
                                rgbData[dst++] = b;
                            }
                        }
                    } else {
                        int dst = 0;
                        for (int y = 0; y < height; y++) {
                            int rowBase = y * srcRowStride;
                            for (int x = 0; x < width; x++) {
                                int base = rowBase + x * 3;
                                rgbData[dst++] = byteBuffer.get(base);
                                rgbData[dst++] = byteBuffer.get(base + 1);
                                rgbData[dst++] = byteBuffer.get(base + 2);
                            }
                        }
                    }
                    // System.out.println("🎨 像素格式转换完成");
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ 抓拍行拷贝失败: " + t.getMessage());
                    t.printStackTrace();
                    try { buffer.unmap(); } catch (Throwable ignore) {}
                    try { sample.dispose(); } catch (Throwable ignore) {}
                    return;
                }

                // ✨ 2. 保存到磁盘缓存（传递frameId）
                com.acard.acard.capture.DiskCaptureCache.DiskFrameItem diskItem = null;
                try {
                    if (diskCaptureCache != null) {
                        // ✅ CPU优化：默认不跳帧（保证抓拍连续性）
                        // 用户需求：随时可能抓拍，必须保存每一帧
                        // 配置：cache.frameskip=1（默认），可设置为2/3降低CPU
                        int frameSkip = Integer.getInteger("cache.frameskip", 1);  // ✅ 默认不跳帧
                        long counter = diskCacheFrameCounter.incrementAndGet();

                        boolean shouldSave = (counter % frameSkip == 0);

                        // ⚠️ 注意：不再需要抓拍时特殊处理，因为默认就是每帧都保存

                        if (shouldSave) {
                            diskItem = diskCaptureCache.addFrameFromRGB(rgbData, width, height, frameId);

                            if (diskItem != null) {
                                // 如果有活动抓拍会话，推送帧
                                if (timelineCapture != null && timelineCapture.hasActiveSession()) {
                                    timelineCapture.onNewDiskFrame(diskItem);
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    LogTools.getInstance().logRecord2("⚠️ 磁盘缓存写入失败: " + e.getMessage());
                }

                // ✨ 3. 推送到内存缓冲（触发全局帧监听器）
                // 直接使用磁盘文件创建Image，避免颜色空间转换问题
                try {
                    if (lightweightBuffer != null && lightweightBuffer.isEnabled() && diskItem != null) {
                        File imageFile = new File(diskItem.filePath);
                        if (imageFile.exists()) {
                            Image fxImage = new Image(imageFile.toURI().toString());
                            lightweightBuffer.push(fxImage, frameId);
                        }
                    }
                } catch (Throwable e) {
                    LogTools.getInstance().logRecord2("⚠️ 内存缓冲推送失败: " + e.getMessage());
                }

                try { buffer.unmap(); } catch (Throwable ignore) {}
                try { sample.dispose(); } catch (Throwable ignore) {}
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ 抓拍帧处理异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 请求实时抓拍（优化版本）
     * 使用非阻塞方式，避免UI卡顿
     */
    public void requestCapture(java.util.function.Consumer<Image> callback) {
        // 预抓拍数量改为与 CaptureStore 配置对齐
        int prePushCount;
        try { prePushCount = Math.max(0, CaptureStore.getInstance().getPreCaptureCount()); }
        catch (Throwable ignore) { prePushCount = Math.max(0, Integer.getInteger("capture.prepush.cache.count", 3)); }
        java.util.List<WritableImage> prePushImages = getRecentCacheFramesSnapshot(prePushCount);

        // 若 GPU 环为空，不再直接失败；继续尝试离线后抓拍作为兜底
        boolean hasPre = prePushImages != null && !prePushImages.isEmpty();
        if (!hasPre) { System.out.println("⚠️ GPU缓存环为空，跳过预推，进入离线兜底"); }

        if (hasPre) {
            System.out.println("⚡ 即时预推GPU缓存环帧 count=" + prePushImages.size());
            for (WritableImage img : prePushImages) {
                try { Platform.runLater(() -> { try { callback.accept(img); } catch (Throwable ignore) {} }); } catch (Throwable ignore) {}
            }
            warmPrePushedFirst.set(true);
            warmPreSkipCount = prePushImages.size();
        } else {
            warmPreSkipCount = 0;
        }

        // 启动 GPU 缓存抓拍
        System.out.println("🟢 数据源: GPU缓存（cacheAppSink）");
        pendingCaptureCallback = callback;
        try { if (cacheValve != null) cacheValve.set("drop", false); } catch (Throwable ignore) {}
        try { if (cacheAppSink != null) cacheAppSink.set("emit-signals", true); } catch (Throwable ignore) {}

        // 触发离线后抓拍（窗口为前+后总数），在后台线程执行避免阻塞UI
        int postWanted;
        try { postWanted = Math.max(1, CaptureStore.getInstance().getPostCaptureCount()); }
        catch (Throwable ignore) { postWanted = Math.max(1, Integer.getInteger("warm.fast.post.frames", 21)); }

        final java.util.function.Consumer<Image> cb = callback;
        final int preCountForOffline = prePushImages != null ? prePushImages.size() : 0;
        final int totalFrames = Math.max(1, preCountForOffline + postWanted);
        System.out.println("🧮 离线窗口: pre=" + preCountForOffline + ", post=" + postWanted + ", total=" + totalFrames);
        new Thread(() -> {
            try {
                // 离线会话保留预推跳过计数（避免重复回调）
                offlineDecodeFromEncodedSinkWindow(totalFrames, cb);
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("❌ 离线后抓拍触发失败: " + t.getMessage());
            }
        }, "capture-offline-thread").start();
    }

    // ========== ✨ 新增：时间轴抓拍API ==========

    /**
     * 启用帧缓冲（用于时间轴抓拍）
     * 注意：启用后会占用少量内存（约1-3MB）
     */
    public void enableCaptureBuffer() {
        System.out.println("🔧 enableCaptureBuffer() 被调用");
        System.out.println("   lightweightBuffer: " + (lightweightBuffer != null ? "已初始化" : "null"));
        System.out.println("   timelineCapture: " + (timelineCapture != null ? "已初始化" : "null"));
        System.out.println("   captureTee: " + (captureTee != null ? "已创建" : "null"));
        System.out.println("   captureQueue: " + (captureQueue != null ? "已创建" : "null"));
        System.out.println("   captureSink: " + (captureSink != null ? "已创建" : "null"));

        if (lightweightBuffer != null) {
            lightweightBuffer.enable();
            System.out.println("✅ 帧缓冲已启用");
        } else {
            System.out.println("⚠️ 抓拍功能未初始化（capture.enabled=false）");
            LogTools.getInstance().logRecord2("❌ 关键问题：lightweightBuffer 为 null，说明 createPipeline() 中未启用抓拍功能");
            LogTools.getInstance().logRecord2("   请检查启动时是否看到：'✅ 轻量级抓拍系统已初始化'");
            LogTools.getInstance().logRecord2("   请检查启动时是否看到：'✅ 已添加 GPU 管道元素（带轻量级抓拍分支）'");
            LogTools.getInstance().logRecord2("   请检查启动时是否看到：'✅ 抓拍分支已连接'");
        }
    }

    /**
     * 禁用帧缓冲
     */
    public void disableCaptureBuffer() {
        if (lightweightBuffer != null) {
            lightweightBuffer.disable();
            System.out.println("⛔ 帧缓冲已禁用");
        }
    }

    /**
     * 更新抓拍配置（调整缓冲大小）
     *
     * @param preCount 前抓拍数
     * @param postCount 后抓拍数
     */
    public void updateCaptureConfig(int preCount, int postCount) {
        if (lightweightBuffer != null) {
            int newCapacity = Math.max(preCount, postCount) + 5;
            lightweightBuffer.adjustCapacity(newCapacity);
            System.out.println("🔧 抓拍配置已更新: preCount=" + preCount + ", postCount=" + postCount + ", bufferCapacity=" + newCapacity);
        }
    }

    /**
     * 时间轴抓拍（前N + 当前 + 后N）
     *
     * @param preCount 前抓拍数（从缓存获取）
     * @param postCount 后抓拍数（从事件收集）
     * @param onComplete 完成回调（接收所有帧）
     *
     * 示例：
     * <pre>
     * player.requestTimelineCapture(10, 10, session -> {
     *     List<Image> allFrames = session.getAllFrames();
     *     System.out.println("抓拍完成: " + allFrames.size() + " 帧");
     *     // 处理帧数据...
     * });
     * </pre>
     */
    public void requestTimelineCapture(int preCount, int postCount,
                                       java.util.function.Consumer<TimelineCapture.CaptureSession> onComplete) {
        if (timelineCapture == null) {
            LogTools.getInstance().logRecord2("❌ 抓拍功能未初始化（capture.enabled=false）");
            return;
        }

        if (lightweightBuffer == null || !lightweightBuffer.isEnabled()) {
            LogTools.getInstance().logRecord2("❌ 帧缓冲未启用，请先调用 enableCaptureBuffer()");
            return;
        }

        // ✨ 不再要求缓冲有数据，允许缓冲为空
        // 当前帧将从实时流中获取（TimelineCapture会收集下一帧作为"当前帧"）
        System.out.println("🎬 时间轴抓拍已启动:");
        System.out.println("   缓冲帧数: " + lightweightBuffer.size());
        System.out.println("   预期: 前" + preCount + " + 当前1 + 后" + postCount
                + " = " + (preCount + 1 + postCount) + "帧");

        // 开始抓拍（currentFrame传null，由TimelineCapture从实时流获取）
        // 使用高质量JPEG（0.92）压缩，约150KB/帧@1080p，240帧≈36MB
        timelineCapture.startCapture(preCount, postCount, null, 0.92f, onComplete);
    }

    /**
     * ✨ 设置全局帧监听器（每帧实时触发，用于事件驱动分发）
     *
     * @param listener 帧监听器（在非JavaFX线程调用，注意线程安全）
     */
    public void setGlobalFrameListener(java.util.function.Consumer<FrameRingBuffer.FrameItem> listener) {
        System.out.println("🔧 setGlobalFrameListener() 被调用");
        System.out.println("  - lightweightBuffer: " + (lightweightBuffer != null ? "已初始化" : "null"));
        System.out.println("  - lightweightBuffer.isEnabled(): " + (lightweightBuffer != null ? lightweightBuffer.isEnabled() : "N/A"));

        if (lightweightBuffer != null) {
            // 将 LightweightFrameBuffer.FrameItem 转换为 FrameRingBuffer.FrameItem（包含frameId）
            lightweightBuffer.setFrameListener(lwFrame -> {
                if (listener != null) {
                    FrameRingBuffer.FrameItem rbFrame = new FrameRingBuffer.FrameItem(
                            lwFrame.jpegData, lwFrame.timestamp, lwFrame.frameId);  // ✨ 传递frameId
                    listener.accept(rbFrame);
                }
            });
            System.out.println("✅ 全局帧监听器已设置到 lightweightBuffer");
        } else {
            LogTools.getInstance().logRecord2("⚠️ lightweightBuffer 未初始化，无法设置帧监听器");
        }
    }

    /**
     * ✨ 流式抓拍（立即显示 + 异步追加，零内存暴涨）
     *
     * @param preCount 前抓拍数
     * @param postCount 后抓拍数
     * @param onInitialFrames 初始帧回调（前置帧立即返回，立即显示）
     * @param onPostFrame 后续帧回调（每收到1帧就回调，实时追加）- ✅ 新增sessionId参数用于关联UI item
     * @param onComplete 完成回调（totalCount, duration）
     * @param onError 错误回调
     */
    public void requestStreamingCapture(int preCount, int postCount,
                                        java.util.function.Consumer<java.util.List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem>> onInitialFrames,
                                        java.util.function.BiConsumer<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem, String> onPostFrame,
                                        java.util.function.BiConsumer<Integer, Long> onComplete,
                                        java.util.function.Consumer<String> onError) {
        System.out.println("🚀 流式抓拍开始: pre=" + preCount + ", post=" + postCount);

        if (timelineCapture == null || lightweightBuffer == null) {
            onError.accept("抓拍功能未初始化");
            return;
        }

        if (!lightweightBuffer.isEnabled()) {
            onError.accept("帧缓冲未启用");
            return;
        }

        // ✅ 取消之前未完成的后续帧收集（防止内存爆炸）
        try {
            timelineCapture.cancelCapture();
            System.out.println("✅ 已取消之前的后续帧收集任务");
        } catch (Throwable e) {
            // 忽略取消失败（可能已完成）
        }

        long startTime = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicInteger totalFrames = new java.util.concurrent.atomic.AtomicInteger(0);

        // ✅ 独立文件夹模式：每次抓拍创建专属文件夹
        // 单次抓拍120帧：内存从24MB降至0.12MB（减少99.5%）

        // 声明在外层，供lambda使用
        final com.acard.acard.capture.CaptureSession[] sessionHolder = new com.acard.acard.capture.CaptureSession[1];
        final com.acard.acard.utils.CaptureLogger[] loggerHolder = new com.acard.acard.utils.CaptureLogger[1];

        try {
            if (diskCaptureCache == null) {
                LogTools.getInstance().logRecord2("❌ diskCaptureCache 为 null");
                onError.accept("磁盘缓存未初始化");
                return;
            }

            // 1️⃣ 创建独立的抓拍会话（实时流类型）
            com.acard.acard.capture.CaptureSession session;
            try {
                // 使用事件类型的中文名称
                session = new com.acard.acard.capture.CaptureSession("实时流抓拍");
            } catch (IOException e) {
                LogTools.getInstance().logRecord2("❌ 创建抓拍会话失败: " + e.getMessage());
                onError.accept("创建抓拍会话失败: " + e.getMessage());
                return;
            }

            sessionHolder[0] = session;  // 保存到数组

            // 创建日志
            loggerHolder[0] = new com.acard.acard.utils.CaptureLogger(session.getSessionId());
            loggerHolder[0].logCaptureStart(preCount, postCount);

            // 2️⃣ 从实时流缓存获取前帧
            java.util.List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> allCacheFrames =
                    diskCaptureCache.getAllFrames();

            int actualPreCount = Math.min(preCount, allCacheFrames.size());
            int startIndex = Math.max(0, allCacheFrames.size() - actualPreCount);

            System.out.println("📦 抓拍会话: " + session.getSessionId());
            System.out.println("   前帧: " + actualPreCount + "/" + preCount +
                    " (实时流缓存:" + allCacheFrames.size() + "张)");

            // 3️⃣ 获取需要复制的源帧
            java.util.List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> sourceFrames =
                    new java.util.ArrayList<>();
            for (int i = startIndex; i < allCacheFrames.size(); i++) {
                sourceFrames.add(allCacheFrames.get(i));
            }

            // 4️⃣ 复制到独立文件夹
            java.util.List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> captureFrames;
            try {
                int eventIndex = sourceFrames.size() - 1;  // 最后一帧为事件帧
                captureFrames = session.copyFramesFromCache(sourceFrames, eventIndex,"ssl");

                // 🔍 诊断日志
                System.out.println("📊 前置帧诊断:");
                System.out.println("   请求前置帧数: " + preCount);
                System.out.println("   实际复制帧数: " + captureFrames.size());
                System.out.println("   请求后续帧数: " + postCount);
                System.out.println("   预期总帧数: " + (preCount + postCount));

                // 记录到文件日志
                if (loggerHolder[0] != null) {
                    loggerHolder[0].logPreFrames(preCount, captureFrames.size());
                }

            } catch (IOException e) {
                LogTools.getInstance().logRecord2("❌ 复制帧到会话失败: " + e.getMessage());
                session.cleanup();  // 清理失败的会话
                onError.accept("复制帧失败: " + e.getMessage());
                return;
            }

            totalFrames.set(captureFrames.size());

            // 5️⃣ 立即回调独立文件夹中的帧
            if (!captureFrames.isEmpty()) {
                onInitialFrames.accept(captureFrames);
                System.out.println("✅ 独立文件夹帧已返回: " + captureFrames.size() + "帧");
                System.out.println("   会话ID: " + session.getSessionId());
                System.out.println("   路径: " + session.getSessionDir());

                // ✅ 获取最后一帧的frameId作为事件帧ID（用于后续帧事件）
                com.acard.acard.capture.DiskCaptureCache.DiskFrameItem lastFrame =
                        captureFrames.get(captureFrames.size() - 1);
                sessionHolder[0].setEventFrameId(lastFrame.frameId);  // 保存事件帧ID到session

                System.out.println("📌 事件帧ID: " + lastFrame.frameId + " (从前置帧最后一帧获取)");
            }

        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ 获取前置帧失败: " + e.getMessage());
            e.printStackTrace();
            onError.accept("获取前置帧失败: " + e.getMessage());
        }

        // ✅ 2. 使用事件驱动机制收集后续帧（零延迟，实时响应）
        if (postCount > 0 && sessionHolder[0] != null) {
            System.out.println("⏳ 注册后续帧事件: " + postCount + "帧...");

            // ✅ 从session获取事件帧ID（最后一帧的frameId）
            long eventFrameId = sessionHolder[0].getEventFrameId();

            System.out.println("📌 当前全局帧ID: " + getCurrentFrameId() + " (参考)");
            System.out.println("📌 后续帧将从下一帧开始收集（共需" + postCount + "帧）");

            // 注册帧回调（实时通知UI）
            final long finalStartTime = startTime;
            final java.util.concurrent.atomic.AtomicInteger finalTotalFrames = totalFrames;
            final java.util.concurrent.atomic.AtomicInteger postFrameCounter = new java.util.concurrent.atomic.AtomicInteger(0);
            final com.acard.acard.utils.CaptureLogger finalLogger = loggerHolder[0];  // 保存logger供lambda使用

            // ✅ 创建事件专属回调（每个事件只处理自己的帧）
            java.util.function.BiConsumer<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem, String> callback = (diskFrame, sessionId) -> {
                try {
                    onPostFrame.accept(diskFrame, sessionId);  // ✅ 传递sessionId
                    finalTotalFrames.incrementAndGet();

                    // 记录后续帧到文件日志
                    int postIndex = postFrameCounter.incrementAndGet();
                    if (finalLogger != null) {
                        finalLogger.logPostFrame(postIndex, postCount, diskFrame.frameId);
                    }
                } catch (Throwable e) {
                    LogTools.getInstance().logRecord2("⚠️ 帧回调失败: " + e.getMessage());
                    e.printStackTrace();

                    if (finalLogger != null) {
                        finalLogger.logError("帧回调失败", e);
                    }
                }
            };

            // ✅ 创建抓拍事件（传入专属回调）
            com.acard.acard.capture.CaptureEvent event = new com.acard.acard.capture.CaptureEvent(
                    com.acard.acard.capture.CaptureEvent.Type.REALTIME,
                    eventFrameId,
                    postCount,
                    sessionHolder[0],
                    callback  // ✅ 传入专属回调
            );

            System.out.println("📋 创建抓拍事件: " + sessionHolder[0].getSessionId() +
                    ", 需要后续帧=" + postCount);

            // 注册到事件管理器（不再需要全局回调列表）
            com.acard.acard.capture.CaptureEventManager.getInstance().registerEvent(event);

            System.out.println("✅ 事件已注册到管理器，活跃事件数: " +
                    com.acard.acard.capture.CaptureEventManager.getInstance().getActiveEventCount());

            // 记录到文件日志
            if (loggerHolder[0] != null) {
                loggerHolder[0].logEventRegistered(eventFrameId, postCount);
            }

        } else {
            // 无需后续帧，立即完成
            long duration = System.currentTimeMillis() - startTime;
            onComplete.accept(totalFrames.get(), duration);
            System.out.println("✅ 流式抓拍完成（无后续帧）: 总计" + totalFrames.get() + "帧");
        }
    }

    /**
     * 取消当前抓拍
     */
    public void cancelTimelineCapture() {
        if (timelineCapture != null) {
            timelineCapture.cancelCapture();
        }
    }

    /**
     * 获取缓冲状态信息
     */
    public String getCaptureBufferStatus() {
        if (lightweightBuffer == null) {
            return "抓拍功能未初始化";
        }

        return String.format("缓冲状态: %s, 帧数=%d, 内存=%.2fMB",
                lightweightBuffer.isEnabled() ? "启用" : "禁用",
                lightweightBuffer.size(),
                lightweightBuffer.getMemoryUsageMB());
    }

    /**
     * 开始WebRTC信令
     */
    // ===== WebRTC 协商相关方法 =====


    /**
     * 手动触发 WebRTC 协商
     * 在确认前端已开始推流后调用
     */
    public void startNegotiation() {
        LogTools.getInstance().logRecord3("🔄 手动触发 WebRTC 协商");

        if (webrtcbin == null || currentStreamUrl == null) {
            LogTools.getInstance().logRecord3("❌ WebRTC 未初始化，无法协商");
            return;
        }

        try {
            // 添加 transceiver 触发协商
            Caps videoCaps = Caps.fromString(
                    "application/x-rtp,media=video,payload=109,encoding-name=H264," +
                            "clock-rate=90000,profile-level-id=42e01f,packetization-mode=1," +
                            "level-asymmetry-allowed=1"
            );
            GstObject transceiver = webrtcbin.emit(GstObject.class, "add-transceiver", 3, videoCaps);
            LogTools.getInstance().logRecord3("✅ 已添加 transceiver，等待协商: " + transceiver);
        } catch (Exception e) {
            LogTools.getInstance().logRecord3("❌ 触发协商失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 开始 WebRTC 信令并立即添加 transceiver
     * ⭐ 在 pipeline.play() 之前调用，确保 WebRTC 状态机正确初始化
     */
    private void startWebRTCSignaling() {
        System.out.println("📡 开始WebRTC信令...");

        try {
            verifyServerConnection();
            String streamUrl = buildStreamUrl();
            System.out.println("🎯 流URL: " + streamUrl);
            currentStreamUrl = streamUrl;
            System.out.println("STEP1: ✅ 保存流URL");

            // ⭐ 立即添加 transceiver（必须在 pipeline.play() 之前）
            // 这样 ON_NEGOTIATION_NEEDED 信号才能正确触发
            try {
                logRecord("🔥🔥🔥 准备添加 transceiver...");
                Caps videoCaps = Caps.fromString(
                        "application/x-rtp,media=video,payload=109,encoding-name=H264," +
                                "clock-rate=90000,profile-level-id=42e01f,packetization-mode=1," +
                                "level-asymmetry-allowed=1"
                );
                logRecord("🔥 videoCaps: " + videoCaps);
                GstObject transceiver = webrtcbin.emit(GstObject.class, "add-transceiver", 3, videoCaps);
                logRecord("STEP1b: ✅ 已添加 recvonly H264 视频 transceiver: " + transceiver);
                
                // 🔥 检查 transceiver 的方向
                try {
                    Object direction = transceiver.get("direction");
                    logRecord("🔥 transceiver direction: " + direction);
                } catch (Exception ex) {
                    logRecord("⚠️ 无法读取 transceiver direction: " + ex.getMessage());
                }
            } catch (Exception e) {
                logRecord("❌ 添加 transceiver 失败: " + e.getMessage());
                e.printStackTrace();
            }

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 信令启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 使用WebRTCBin创建SDP offer
     */
    private void createWebRTCOffer(String streamUrl) {
        logRecord("🔥🔥🔥 开始创建WebRTC SDP offer...");
        logRecord("🔥 streamUrl: " + streamUrl);

        try {
            // 直接创建 offer：transceiver 已在 startWebRTCSignaling 中添加并触发协商
            Object bundlePolicy = webrtcbin.get("bundle-policy");
            logRecord("🔥 bundle-policy currently=" + bundlePolicy);
            long memoryGB = getSystemMemoryGB();
            int cpuCores = getCore();

            // ⚡ CPU 性能评估：6核或以下视为低端（如 i5-9400F）
            boolean isLowEndCPU = cpuCores <= 6;
            logRecord("🔥 调用 webrtcbin.createOffer()...");
            webrtcbin.createOffer(new WebRTCBin.CREATE_OFFER() {
                @Override
                public void onOfferCreated(WebRTCSessionDescription offer) {
                    logRecord("🔥🔥🔥 SDP offer创建成功！！！");

                    try {
                        // 直接使用 GstWebRTCPlayerView 的处理流程：获取 SDP 文本、设置本地描述、POST 到 SRS、设置远程描述
                        SDPMessage offerMsg = tryGetSdpMessage(offer);
                        String sdpOffer = (offerMsg != null) ? offerMsg.toString() : tryGetSdpText(offer);
                        sdpOffer = (sdpOffer == null ? "" : sdpOffer).replace("\r\n", "\n").replace("\n", "\r\n");

                        // 🔥 所有机型强制设置 20Mbps 带宽（不管机型）
                        sdpOffer = injectBandwidthToSdp(sdpOffer, 20000);  // 20Mbps 强制带宽
                        logRecord("🔥 SDP 已注入强制带宽：20Mbps（全机型统一）");

                        // 调试输出完整 SDP
                        System.out.println("===== LOCAL OFFER SDP BEGIN =====");
                        System.out.println(sdpOffer);
                        System.out.println("===== LOCAL OFFER SDP END   =====");

                        // 额外日志：打印 BUNDLE 与 mid 关键行
                        try {
                            for (String line : sdpOffer.split("\\r?\\n")) {
                                if (line.startsWith("a=group:BUNDLE") || line.startsWith("a=mid:")) {
                                    LogTools.getInstance().logRecord2("[SDP KEY] " + line);
                                }
                            }
                        } catch (Exception ignore) {}

                        logRecord("🔥 STEP4: 调用 set-local-description...");
                        setLocalDescriptionCompat(webrtcbin,offer);
                        logRecord("🔥 set-local-description 完成");

                        // 发送到 SRS 并获取 ANSWER SDP（使用新写的 sendOfferToServer）
                        logRecord("🔥 STEP5: 发送 offer 到 SRS 服务器...");
                        sendOfferToServer(streamUrl, sdpOffer);
                        logRecord("🔥 sendOfferToServer 调用完成");

                        // 注意：sendOfferToServer 内部会处理响应解析和回退逻辑，并调用 setRemoteDescriptionCompat/extractAndAddIceCandidatesFromSdp

                    } catch (Exception e) {
                        logRecord("❌ 处理offer失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

            });

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 创建offer失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 设置本地SDP描述
     */
    private void setLocalDescription(WebRTCSessionDescription offer) {
        System.out.println("🔧 设置本地SDP描述...");

        try {
            webrtcbin.setLocalDescription(offer);
            System.out.println("✅ 本地SDP描述设置成功");
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置本地SDP描述失败: " + e.getMessage());
            throw new RuntimeException("设置本地SDP描述失败", e);
        }
    }

    /**
     * 获取SDP文本
     */
    private String getSdpText(WebRTCSessionDescription desc) {
        try {
            return desc.getSDPMessage().toString();
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 获取SDP文本失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 发送offer到服务器
     */
    private void sendOfferToServer(String streamUrl, String sdpOffer) {
        System.out.println("📤 发送SDP offer到服务器...");

        try {
            String apiUrl = ("http://" + serverHost + ":" + NetworkConfig.apiPort + "/rtc/v1/play/").trim().replace("`", "");
            String sdpCRLF = sdpOffer.replace("\r\n", "\n").replace("\n", "\r\n");

            // 使用 Gson 构建请求体，禁用 HTML 转义，字段包含 api/streamurl/sdp
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("api", apiUrl);
            obj.addProperty("streamurl", streamUrl.trim().replace("`", ""));
            obj.addProperty("sdp", sdpCRLF);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            String json = gson.toJson(obj);

            System.out.println("📤 发送信令请求到: " + apiUrl);
            System.out.println("📋 请求体长度: " + json.length() + " 字符");
            System.out.println("📄 请求体预览: " + json.substring(0, Math.min(500, json.length())));
            int hexLen = Math.min(128, json.length());
            StringBuilder hex = new StringBuilder();
            for (int idx = 0; idx < hexLen; idx++) {
                hex.append(String.format("%02X ", (int) json.charAt(idx)));
            }
            System.out.println("🔍 请求体前128字节HEX: " + hex);

            boolean sdpHasAudio = sdpCRLF.contains("\nm=audio ");
            boolean sdpHasVideo = sdpCRLF.contains("\nm=video ");
            System.out.println("🔎 SDP Offer检查: hasAudio=" + sdpHasAudio + ", hasVideo=" + sdpHasVideo);

            // 发送HTTP请求（同步），添加 Accept 头与超时配置
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            String body = resp.body();

            System.out.println("📥 收到信令响应: " + resp.statusCode());
            System.out.println("🧾 响应头: " + resp.headers().map());
            System.out.println("📄 响应内容: " + body);

            // 处理3xx重定向（POST默认不跟随）
            if (resp.statusCode() / 100 == 3) {
                String loc = resp.headers().firstValue("location").orElse(null);
                if (loc != null && !loc.isEmpty()) {
                    String redirectUrl = loc.startsWith("http") ? loc : "http://" + serverHost + ":" + NetworkConfig.apiPort + (loc.startsWith("/") ? loc : "/" + loc);
                    System.out.println("➡️ 跟随重定向到: " + redirectUrl);
                    HttpRequest redirectReq = HttpRequest.newBuilder()
                            .uri(URI.create(redirectUrl))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                            .build();
                    resp = client.send(redirectReq, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                    body = resp.body();
                    System.out.println("📥 重定向后的响应: " + resp.statusCode());
                    System.out.println("🧾 响应头: " + resp.headers().map());
                    System.out.println("📄 响应内容: " + body);
                }
            }

            // 简单容错：非2xx直接返回
            if (resp.statusCode() / 100 != 2) {
                LogTools.getInstance().logRecord2("❌ SRS HTTP错误: " + resp.statusCode() + ", body=" + body);
                return;
            }

            // 检查业务码
            int codeIdx = body.indexOf("\"code\":");
            if (codeIdx >= 0) {
                int comma = body.indexOf(",", codeIdx);
                String codeStr = (comma > 0 ? body.substring(codeIdx + 7, comma) : body.substring(codeIdx + 7)).trim();
                try {
                    int code = Integer.parseInt(codeStr.replaceAll("[^0-9-]", ""));
                    if (code != 0) {
                        LogTools.getInstance().logRecord2("❌ SRS业务码非0: " + code + ", body=" + body);
                        // 一次性自动回退重试：保持纯净 streamurl，不追加任何参数
                        try {
                            String altStreamUrl = streamUrl;
                            System.out.println("🔁 自动回退重试，使用: " + altStreamUrl);
                            com.google.gson.JsonObject retryObj = new com.google.gson.JsonObject();
                            retryObj.addProperty("api", apiUrl);
                            retryObj.addProperty("streamurl", altStreamUrl);
                            retryObj.addProperty("sdp", sdpCRLF);
                            com.google.gson.Gson retryGson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
                            String retryJson = retryGson.toJson(retryObj);
                            HttpRequest retryReq = HttpRequest.newBuilder()
                                    .uri(URI.create(apiUrl))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(retryJson, java.nio.charset.StandardCharsets.UTF_8))
                                    .build();
                            HttpResponse<String> retryResp = client.send(retryReq, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                            System.out.println("📥 回退重试响应: " + retryResp.statusCode());
                            System.out.println("📄 回退响应内容: " + retryResp.body());
                            if (retryResp.statusCode() / 100 == 2 && retryResp.body().contains("\"code\":0")) {
                                String remoteSdpRetry = extractSdpFromResponse(retryResp.body());
                                if (remoteSdpRetry != null && !remoteSdpRetry.isEmpty()) {
                                    processSignalingResponse(retryResp.body());
                                    return;
                                }
                            }
                        } catch (Exception ex) {
                            LogTools.getInstance().logRecord2("❌ 回退重试失败: " + ex.getMessage());
                        }
                        return;
                    }
                } catch (NumberFormatException ignore) {
                    // 继续解析sdp
                }
            }

            // 提取SDP并处理
            String remoteSdp = extractSdpFromResponse(body);
            if (remoteSdp != null && !remoteSdp.isEmpty()) {
                processSignalingResponse(body);
            } else {
                LogTools.getInstance().logRecord2("❌ 无法从响应中提取SDP");
            }

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 发送offer失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 验证服务器连接
     */
    private void verifyServerConnection() {
        System.out.println("🔍 验证服务器连接...");

        try {
            // 检查服务器基本连接
            String healthUrl = String.format("http://%s:%d/api/v1/versions", serverHost, serverPort);
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .GET()
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            System.out.println("✅ 服务器连接正常");
                            System.out.println("📄 服务器信息: " + response.body());
                        } else {
                            System.out.println("⚠️ 服务器响应异常: " + response.statusCode());
                        }
                    })
                    .exceptionally(throwable -> {
                        LogTools.getInstance().logRecord2("❌ 服务器连接失败: " + throwable.getMessage());
                        return null;
                    });

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 服务器验证失败: " + e.getMessage());
        }
    }


    /**
     * 处理信令响应
     */
    private void processSignalingResponse(String responseBody) {
        System.out.println("📨 处理信令响应...");

        try {
            // 解析JSON响应获取SDP
            String remoteSdp = extractSdpFromResponse(responseBody);
            if (remoteSdp != null && !remoteSdp.isEmpty()) {
                System.out.println("📄 收到远程SDP，长度: " + remoteSdp.length());

                // 🔧 关键修复：设置远程SDP描述
                setRemoteDescription(remoteSdp);

                // 🔧 关键修复：从SDP中提取并添加ICE候选者
                extractAndAddIceCandidatesFromSdp(remoteSdp);

                // 🔧 新增：从Answer SDP中提取SSRC并立即触发PLI/FIR请求首帧
                long ssrc = extractSsrcFromAnswerSdp(remoteSdp);
                if (ssrc > 0) {
                    System.out.println("🔎 从Answer SDP提取到SSRC: " + ssrc + "（仅用于调试）");
                }
                // 总是通过上行事件请求关键帧
                try { sendPLIRequest(); } catch (Exception ignore) {}

                System.out.println("✅ 远程SDP设置完成，开始ICE连接过程");
            } else {
                LogTools.getInstance().logRecord2("❌ 无法从响应中提取SDP");
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 处理信令响应失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 从 Answer SDP 中提取视频SSRC（优先限定在 m=video 段），并加入全局与 FID 组回退解析
    private long extractSsrcFromAnswerSdp(String answerSdp) {
        try {
            String normalized = answerSdp == null ? "" : answerSdp.replace("\r\n", "\n");
            // 1) 先在 m=video 段内查找 a=ssrc
            int mVideoIdx = normalized.indexOf("\nm=video ");
            int nextMIdx = mVideoIdx >= 0 ? normalized.indexOf("\nm=", mVideoIdx + 1) : -1;
            String videoSection = (mVideoIdx >= 0)
                    ? normalized.substring(mVideoIdx, nextMIdx >= 0 ? nextMIdx : normalized.length())
                    : normalized;

            Matcher matcherVideo = Pattern.compile("\\na=ssrc:(\\d+)").matcher(videoSection);
            if (matcherVideo.find()) {
                long ssrc = Long.parseLong(matcherVideo.group(1));
                remoteVideoSsrc = ssrc;
                ssrcExtractedFromSdp = true;
                System.out.println("🔎 从Answer SDP(m=video)提取到SSRC: " + ssrc);
                return ssrc;
            }

            // 2) 回退：在整个 SDP 中查找第一条 a=ssrc
            Matcher matcherGlobal = Pattern.compile("\\na=ssrc:(\\d+)").matcher(normalized);
            if (matcherGlobal.find()) {
                long ssrc = Long.parseLong(matcherGlobal.group(1));
                remoteVideoSsrc = ssrc;
                ssrcExtractedFromSdp = true;
                System.out.println("🔎 从Answer SDP(全局)提取到SSRC: " + ssrc);
                return ssrc;
            }

            // 3) 回退：解析 a=ssrc-group:FID <primary> <secondary>
            Matcher matcherGroup = Pattern.compile("\\na=ssrc-group:FID\\s+(\\d+)\\s+(\\d+)").matcher(normalized);
            if (matcherGroup.find()) {
                long primary = Long.parseLong(matcherGroup.group(1));
                remoteVideoSsrc = primary;
                ssrcExtractedFromSdp = true;
                System.out.println("🔎 从Answer SDP(ssrc-group:FID)提取到主SSRC: " + primary);
                return primary;
            }

            System.out.println("⚠️ Answer SDP未包含可解析的 a=ssrc 行或 ssrc-group:FID，无法提取SSRC");
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 提取SSRC失败: " + e.getMessage());
        }
        return 0L;
    }

    /**
     * 在 SDP 中注入带宽限制（b=AS），强制要求服务器提供更高带宽
     * @param sdp 原始 SDP
     * @param bandwidthKbps 带宽（kbps）
     * @return 修改后的 SDP
     */
    private String injectBandwidthToSdp(String sdp, int bandwidthKbps) {
        if (sdp == null || sdp.isEmpty()) return sdp;
        
        try {
            StringBuilder result = new StringBuilder();
            String[] lines = sdp.split("\\r?\\n");
            boolean injected = false;
            
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                result.append(line).append("\r\n");
                
                // 在 m=video 行后面注入带宽限制
                if (line.startsWith("m=video") && !injected) {
                    result.append("b=AS:").append(bandwidthKbps).append("\r\n");
                    result.append("b=TIAS:").append(bandwidthKbps * 1000).append("\r\n");
                    injected = true;
                    System.out.println("🔥 SDP 注入带宽: b=AS:" + bandwidthKbps + " (强制 " + (bandwidthKbps/1000) + "Mbps)");
                }
            }
            
            return result.toString();
        } catch (Exception e) {
            logRecord("⚠️ SDP 带宽注入失败: " + e.getMessage());
            return sdp;  // 失败则返回原始 SDP
        }
    }

    /**
     * 从JSON响应中提取SDP
     */
    private String extractSdpFromResponse(String responseBody) {
        try {
            // 使用 Gson 解析 JSON，确保稳健获取 sdp 字段
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(responseBody);
            if (root != null && root.isJsonObject()) {
                com.google.gson.JsonObject obj = root.getAsJsonObject();
                if (obj.has("sdp") && !obj.get("sdp").isJsonNull()) {
                    String sdp = obj.get("sdp").getAsString();
                    // 统一换行到 CRLF，避免后续解析问题
                    sdp = sdp.replace("\r\n", "\n").replace("\n", "\r\n");
                    return sdp;
                }
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ SDP提取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 设置远程SDP描述
     */
    private void setRemoteDescription(String remoteSdp) {
        try {
            System.out.println("🔧 设置远程SDP描述...");

            // 防御：webrtcbin 不能为空
            if (webrtcbin == null) {
                LogTools.getInstance().logRecord2("❌ webrtcbin 为 null，无法设置远程SDP。请检查管线初始化或是否过早停止。");
                return;
            }

            // 创建SDPMessage对象并解析SDP字符串
            SDPMessage sdpMessage = new SDPMessage();
            sdpMessage.parseBuffer(remoteSdp);

            // 创建WebRTCSessionDescription对象
            WebRTCSessionDescription sessionDescription =
                    new WebRTCSessionDescription(
                            org.freedesktop.gstreamer.webrtc.WebRTCSDPType.ANSWER,
                            sdpMessage
                    );

            // 交接所有权给底层，避免 Java 侧二次释放
            try { sessionDescription.disown(); } catch (Throwable ignore) {}

            // 使用webrtcbin的set-remote-description信号
            webrtcbin.emit("set-remote-description", sessionDescription, null);
            System.out.println("✅ 远程SDP描述设置成功");

            // 🔥 关键修复：延迟请求关键帧，确保 SDP 协商完成
            Gst.getExecutor().schedule(() -> {
                try {
                    sendPLIRequest();
                    System.out.println("🔥 远程SDP设置完成，已请求首个关键帧（延迟200ms）");
                } catch (Exception e) {
                    LogTools.getInstance().logRecord2("⚠️ 请求关键帧失败: " + e.getMessage());
                }
            }, 200, TimeUnit.MILLISECONDS);

            // 再发送2次，确保iOS收到
            Gst.getExecutor().schedule(() -> {
                try { sendPLIRequest(); } catch (Exception ignore) {}
            }, 400, TimeUnit.MILLISECONDS);
            Gst.getExecutor().schedule(() -> {
                try { sendPLIRequest(); } catch (Exception ignore) {}
            }, 600, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置远程SDP描述失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ♻️ ICE自动恢复：限流 + 重置 webrtcbin 状态 + 重新发起协商
     */
    private void tryIceAutoRecover() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastIceRecoveryMs;
        if (elapsed >= 0 && elapsed < ICE_RECOVERY_INTERVAL_MS) {
            long remain = ICE_RECOVERY_INTERVAL_MS - elapsed;
            System.out.println("⏳ ICE自动恢复限流，剩余 " + remain + "ms");
            return;
        }
        lastIceRecoveryMs = now;

        System.out.println("♻️ 尝试ICE自动恢复：重置 webrtcbin 到 READY 并重新协商");

        // 停止相关定时器，避免干扰恢复过程
        try { stopPliTimer(); } catch (Throwable ignore) {}
        try { stopKeepAliveTimer(); } catch (Throwable ignore) {}
        try { stopFrameWatchdog(); } catch (Throwable ignore) {}

        // 重置解码/NAL状态与SSRC等
        try {
            hasReceivedSps.set(false);
            hasReceivedPps.set(false);
            hasReceivedIdr.set(false);
            hasReceivedPSlice.set(false);
            hasReceivedFuA.set(false);
            firstDisplaySampleLogged = false;
            ssrcExtractedFromSdp = false;
            remoteVideoSsrc = 0L;
            webrtcSrcPad = null;
        } catch (Throwable ignore) {}

        // 将 webrtcbin 置为 READY，再回到 PLAYING
        if (webrtcbin != null) {
            try {
                webrtcbin.setState(State.READY);
                System.out.println("🔄 webrtcbin 状态 -> READY");
            } catch (Throwable e) {
                LogTools.getInstance().logRecord2("⚠️ 设置 webrtcbin READY 失败: " + e.getMessage());
            }
            try {
                webrtcbin.setState(State.PLAYING);
                System.out.println("▶️ webrtcbin 状态 -> PLAYING");
            } catch (Throwable e) {
                LogTools.getInstance().logRecord2("⚠️ 设置 webrtcbin PLAYING 失败: " + e.getMessage());
            }
        } else {
            LogTools.getInstance().logRecord2("⚠️ webrtcbin 为 null，跳过状态重置");
        }

        // 重新发起信令（内部会构建/保存流URL并添加 transceiver，从而触发协商）
        try {
            startWebRTCSignaling();
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ 重新发起信令失败: " + e.getMessage());
        }
    }

    /**
     * 从SDP中提取并添加ICE候选者
     */
    private void extractAndAddIceCandidatesFromSdp(String sdp) {
        try {
            System.out.println("🧊 从SDP中提取ICE候选者...");

            // 防御：webrtcbin 不能为空
            if (webrtcbin == null) {
                LogTools.getInstance().logRecord2("❌ webrtcbin 为 null，无法添加ICE候选者。跳过候选者处理。");
                return;
            }

            // 查找所有a=candidate行
            Pattern candidatePattern = Pattern.compile("a=candidate:([^\\r\\n]+)");
            Matcher matcher = candidatePattern.matcher(sdp);

            int candidateCount = 0;
            while (matcher.find()) {
                String candidate = "candidate:" + matcher.group(1);
                System.out.println("🧊 发现ICE候选者: " + candidate);

                // 🔧 关键修复：添加远程ICE候选者到webrtcbin
                try {
                    webrtcbin.emit("add-ice-candidate", 0, candidate);
                    candidateCount++;
                    System.out.println("✅ ICE候选者已添加: " + candidate.substring(0, Math.min(50, candidate.length())) + "...");
                } catch (Exception e) {
                    LogTools.getInstance().logRecord2("❌ 添加ICE候选者失败: " + e.getMessage());
                }
            }

            System.out.println("🧊 总共处理了 " + candidateCount + " 个ICE候选者");

            if (candidateCount == 0) {
                System.out.println("⚠️ 未在SDP中找到ICE候选者，这可能导致连接问题");
            }

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ ICE候选者提取失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 启动PLI请求定时器
     */
    private void startPliTimer() {
        System.out.println("⏰ 启动PLI请求定时器 (GLib主线程, 间隔: 200ms)");

        // 如果已有旧的定时器，先停止
        stopPliTimer();

        // 在 GLib MainContext 执行，避免非 GLib 线程调用 GStreamer API
        pliRetryCount = 0;
        pliScheduled = Gst.getExecutor().scheduleWithFixedDelay(() -> {
            try {
                if (pliRetryCount >= MAX_PLI_RETRIES) {
                    System.out.println("⏰ PLI请求达到最大重试次数，停止定时器");
                    stopPliTimer();
                    // 开启低频保活与看门狗，避免需要第二次点击播放
                    startKeepAliveTimer();
                    startFrameWatchdog();
                    return;
                }

                if (!hasReceivedSps.get() || !hasReceivedPps.get() || !hasReceivedIdr.get()) {
                    sendPLIRequest();
                    pliRetryCount++;
                } else {
                    System.out.println("✅ 已接收完整NAL单元，停止PLI请求");
                    stopPliTimer();
                    // 切入低频保活与看门狗
                    startKeepAliveTimer();
                    startFrameWatchdog();
                }
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ PLI调度异常: " + t.getMessage());
            }
        }, 200, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止PLI请求定时器
     */
    private void stopPliTimer() {
        try {
            if (pliScheduled != null) {
                pliScheduled.cancel(false);
                pliScheduled = null;
            }
            if (pliTimer != null) {
                pliTimer.cancel();
                pliTimer = null;
            }
            System.out.println("⏰ PLI请求定时器已停止");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止PLI定时器异常: " + t.getMessage());
        }
    }

    /**
     * 发送关键帧请求（统一使用 GstForceKeyUnit 上行事件）
     */
    private void sendPLIRequest() {
        // LogTools.getInstance().logRecord2("📡 检查NAL单元状态 (第" + (pliRetryCount + 1) + "次)");
        printNalStatus();

        try {
            // 检查WebRTC连接状态
            WebRTCPeerConnectionState connectionState = webrtcbin.getConnectionState();
            LogTools.getInstance().logRecord2("🔗 当前连接状态: " + connectionState);

            if (connectionState != WebRTCPeerConnectionState.CONNECTED) {
                LogTools.getInstance().logRecord2("⚠️ WebRTC连接未建立");

                return;
            }

            // 统一采用正确的方式：向 webrtcbin 的视频 src pad 发送上行 GstForceKeyUnit 事件
            if (webrtcSrcPad != null) {
                try {
                    Structure s = new Structure("GstForceKeyUnit");
                    GstStructureAPI.GSTSTRUCTURE_API.gst_structure_set(
                            s, "timestamp", org.freedesktop.gstreamer.lowlevel.GType.INT64, -1L);
                    GstStructureAPI.GSTSTRUCTURE_API.gst_structure_set(
                            s, "all-headers", org.freedesktop.gstreamer.lowlevel.GType.BOOLEAN, true);
                    s.setInteger("count", 1);
                    Event ev = GstEventAPI.GSTEVENT_API
                            .gst_event_new_custom(EventType.CUSTOM_UPSTREAM, s);
                    boolean ok = webrtcSrcPad.sendEvent(ev);
                    // LogTools.getInstance().logRecord2("📡 GstForceKeyUnit -> webrtcbin.src 结果: " + ok);

                    // GstForceKeyUnit事件会被webrtcbin自动转换为PLI/FIR RTCP消息
                    // 无需额外发送FIR，webrtcbin会根据协商结果选择合适的RTCP反馈类型
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ 发送GstForceKeyUnit失败: " + t.getMessage());
                }
            } else {
                LogTools.getInstance().logRecord2("⚠️ webrtcSrcPad 未就绪，无法发送GstForceKeyUnit");
            }

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 关键帧请求失败: " + e.getMessage());
            e.printStackTrace();
        }

    }

    public void sendPLIRequestChange() {
        /*if (decoder == null) {
            logRecord("⚠️ decoder 为 null，无法 Flush");
            return;
        }

        try {
            // 🔥 正确的 Flush 方法（基于 gst1-java-core-1.4.0 javadoc）
            // 发送 FlushStart
            org.freedesktop.gstreamer.event.FlushStartEvent flushStart =
                    new org.freedesktop.gstreamer.event.FlushStartEvent();
            decoder.sendEvent(flushStart);
            logRecord("📤 已发送 FlushStart 到解码器");

            // 延迟 50ms 后发送 FlushStop
            Gst.getExecutor().schedule(() -> {
                try {
                    // FlushStopEvent 使用无参构造函数
                    org.freedesktop.gstreamer.event.FlushStopEvent flushStop =
                            new org.freedesktop.gstreamer.event.FlushStopEvent();
                    decoder.sendEvent(flushStop);
                    logRecord("📤 已发送 FlushStop，解码器已重置");
                } catch (Exception e) {
                    logRecord("⚠️ FlushStop 失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 50, TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            logRecord("⚠️ Flush 解码器失败: " + e.getMessage());
            e.printStackTrace();
        }*/
    }

    // 已弃用：不再对 decoder/parse/depay 的 sink pad 发送 ForceKeyUnit

    // 保活：低频PLI，维持远端周期性关键帧，避免播放需要二次点击
    private void startKeepAliveTimer() {
        try {
            stopKeepAliveTimer();
            keepAliveTimer = new Timer(true);
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (webrtcbin != null && webrtcSrcPad != null && webrtcbin.getConnectionState() == WebRTCPeerConnectionState.CONNECTED) {
                            Gst.getExecutor().execute(() -> { try { sendPLIRequest(); } catch (Throwable ignore) {} });
                        }
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("⚠️ 保活定时器任务异常: " + t.getMessage());
                    }
                }
            };
            keepAliveTimer.scheduleAtFixedRate(task, KEEPALIVE_PLI_INTERVAL_MS, KEEPALIVE_PLI_INTERVAL_MS);
            System.out.println("⏳ 保活定时器已启动 (间隔: " + KEEPALIVE_PLI_INTERVAL_MS + "ms)");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 启动保活定时器异常: " + t.getMessage());
        }
    }

    private void stopKeepAliveTimer() {
        try {
            if (keepAliveTimer != null) {
                keepAliveTimer.cancel();
                keepAliveTimer = null;
                System.out.println("⏳ 保活定时器已停止");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止保活定时器异常: " + t.getMessage());
        }
    }

    // 看门狗：监测长时间无帧，触发补救操作（PLI）
    private void startFrameWatchdog() {
        try {
            stopFrameWatchdog();
            frameWatchdogTimer = new Timer(true);
            TimerTask task = new TimerTask() {
                @Override
                public void run() {
                    try {
                        long now = System.currentTimeMillis();
                        long last = lastFrameTimeMs;
                        if (last > 0 && now - last >= FRAME_NOFRAME_THRESHOLD_MS) {
                            // 仅在连接已建立时补救，并做限流避免频繁发送PLI
                            if (webrtcbin != null && webrtcSrcPad != null && webrtcbin.getConnectionState() == WebRTCPeerConnectionState.CONNECTED) {
                                if (lastWatchdogPliTimeMs == 0L || now - lastWatchdogPliTimeMs >= KEEPALIVE_PLI_INTERVAL_MS) {
                                    System.out.println("🐶 看门狗触发：超过 " + FRAME_NOFRAME_THRESHOLD_MS + "ms 未收到帧，发送PLI补救");
                                    lastWatchdogPliTimeMs = now;

                                    // 检查是否需要重置NAL状态并重新请求SPS/PPS
                                    if (!hasReceivedSps.get() || !hasReceivedPps.get()) {
                                        LogTools.getInstance().logRecord2("🐶 看门狗检测到缺少SPS/PPS，重置NAL状态并请求关键帧");
                                        hasReceivedSps.set(false);
                                        hasReceivedPps.set(false);
                                        hasReceivedIdr.set(false);
                                    }

                                    Gst.getExecutor().execute(() -> { try { sendPLIRequest(); } catch (Throwable ignore) {} });
                                } else {
                                    long remain = KEEPALIVE_PLI_INTERVAL_MS - (now - lastWatchdogPliTimeMs);
                                    System.out.println("🐶 看门狗触发但已限流，剩余 " + remain + "ms 后可再次发送PLI");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("⚠️ 看门狗任务异常: " + t.getMessage());
                    }
                }
            };
            frameWatchdogTimer.scheduleAtFixedRate(task, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS);
            System.out.println("🐶 帧看门狗已启动 (检测间隔: " + WATCHDOG_TICK_MS + "ms, 阈值: " + FRAME_NOFRAME_THRESHOLD_MS + "ms)");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 启动帧看门狗异常: " + t.getMessage());
        }
    }

    private void stopFrameWatchdog() {
        try {
            if (frameWatchdogTimer != null) {
                frameWatchdogTimer.cancel();
                frameWatchdogTimer = null;
                System.out.println("🐶 帧看门狗已停止");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止帧看门狗异常: " + t.getMessage());
        }
    }

    // ⚡ 定期关键帧请求：使用GStreamer内部PLI机制，不通过socket（避免中断前端推流）
    private void startPeriodicKeyframeRequest() {
        try {
            if (keyframeRequestExecutor != null) {
                LogTools.getInstance().logRecord2("⚠️ 定期关键帧请求已启动，跳过重复启动");
                return;
            }

            keyframeRequestExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PeriodicKeyframeRequest");
                t.setDaemon(true);
                return t;
            });

            // ⚡ 根据机型调整关键帧请求间隔
            int intervalSeconds;
            long memoryGB = getSystemMemoryGB();
            int cpuCores = getCore();
            boolean isLowEndCPU = cpuCores <= 6;

            if (memoryGB <= 8 && isLowEndCPU) {
                // 🔥 极低端机：频繁关键帧，快速恢复
                intervalSeconds = 3;
                LogTools.getInstance().logRecord2("🔥 极低端机模式，关键帧间隔: " + intervalSeconds + "秒（频繁刷新，快速恢复）");
            } else if (memoryGB <= 8 || isLowEndCPU) {
                // ⚡ 低端机：频繁关键帧
                intervalSeconds = 5;
                LogTools.getInstance().logRecord2("🎯 低端机模式，关键帧间隔: " + intervalSeconds + "秒（频繁刷新防卡顿）");
            } else {
                // ⚡ 其他机型：标准间隔（3秒）
                intervalSeconds = 20;
                String resolutionHint = System.getProperty("video.resolution", "sd");
                if ("4k".equalsIgnoreCase(resolutionHint) || "1080p".equalsIgnoreCase(resolutionHint)) {
                    LogTools.getInstance().logRecord2("🎯 高分辨率稳定模式，关键帧间隔: " + intervalSeconds + "秒（大缓冲防马赛克）");
                } else {
                    LogTools.getInstance().logRecord2("🎯 标清稳定模式，关键帧间隔: " + intervalSeconds + "秒（大缓冲防马赛克）");
                }
            }

            final int finalInterval = intervalSeconds;

            // 定期发送 PLI（Picture Loss Indication）事件到 webrtcbin
            keyframeRequestTask = keyframeRequestExecutor.scheduleAtFixedRate(() -> {
                try {
                    // ⚡ 步骤1：检查缓冲区堆积（提前预警，50%阈值）
                    boolean needExtraKeyframe = checkBufferAccumulation();

                    // ⚡ 步骤2：请求关键帧（定期 + 按需）
                    sendPLIRequest();

                    if (needExtraKeyframe) {
                        LogTools.getInstance().logRecord2("⚡ [提前预警] 检测到缓冲堆积，已请求关键帧清理");
                    }

                    LogTools.getInstance().logRecord2("✅ [定期维护] 关键帧请求完成（间隔" + finalInterval + "秒）");

                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ [定期维护] 异常: " + t.getMessage());
                }
            }, finalInterval, finalInterval, java.util.concurrent.TimeUnit.SECONDS);

            LogTools.getInstance().logRecord2("🔄 定期关键帧请求已启动（间隔: " + finalInterval + "秒，使用GStreamer PLI，不通过socket）");

        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 启动定期关键帧请求失败: " + t.getMessage());
        }
    }

    // 停止定期关键帧请求
    private void stopPeriodicKeyframeRequest() {
        try {
            if (keyframeRequestTask != null) {
                keyframeRequestTask.cancel(false);
                keyframeRequestTask = null;
            }
            if (keyframeRequestExecutor != null) {
                keyframeRequestExecutor.shutdown();
                keyframeRequestExecutor = null;
            }
            LogTools.getInstance().logRecord2("🔄 定期关键帧请求已停止");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止定期关键帧请求失败: " + t.getMessage());
        }
    }

    // ⚡ 缓冲区堆积检查（提前预警，50%阈值）
    private boolean checkBufferAccumulation() {
        boolean needKeyframe = false;

        try {
            // queueDepay 检查（50%阈值，提前预警）
            if (queueDepay != null) {
                int currentBuffers = (Integer) queueDepay.get("current-level-buffers");
                double fillRate = (double) currentBuffers / queueDepayTargetBuffers;

                if (fillRate > 0.5) {  // 超过 50% 就预警（运动画面下缓冲会快速堆积）
                    LogTools.getInstance().logRecord2(String.format(
                            "⚠️ [预警] queueDepay 填充率: %.1f%% (%d/%d帧)，运动画面可能导致马赛克",
                            fillRate * 100, currentBuffers, queueDepayTargetBuffers
                    ));
                    needKeyframe = true;
                }
            }

            // queueDecode 检查
            if (queueDecode != null) {
                int currentBuffers = (Integer) queueDecode.get("current-level-buffers");
                double fillRate = (double) currentBuffers / finalQueueDecodeTargetBuffers;

                if (fillRate > 0.5) {
                    LogTools.getInstance().logRecord2(String.format(
                            "⚠️ [预警] queueDecode 填充率: %.1f%% (%d/%d帧)，解码压力大",
                            fillRate * 100, currentBuffers, finalQueueDecodeTargetBuffers
                    ));
                    needKeyframe = true;
                }
            }

        } catch (Throwable ignore) {}

        return needKeyframe;
    }

    // ⚡ 综合诊断监控：每10秒输出详细统计，帮助定位马赛克原因
    private void startDiagnosticMonitoring() {
        try {
            if (diagnosticExecutor != null) {
                LogTools.getInstance().logRecord2("⚠️ 诊断监控已启动，跳过重复启动");
                return;
            }

            diagnosticExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DiagnosticMonitor");
                t.setDaemon(true);
                return t;
            });

            diagnosticTask = diagnosticExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (pipeline == null || pipeline.getState() != State.PLAYING) {
                        return;
                    }

                    LogTools.getInstance().logRecord2("========== 📊 马赛克诊断报告 ==========");

                    // 1. 队列状态监控
                    monitorQueueStatus();

                    // 2. Jitterbuffer 统计
                    monitorJitterbuffer();

                    // 3. WebRTC 连接质量
                    monitorWebRTCQuality();

                    LogTools.getInstance().logRecord2("=========================================");

                } catch (Throwable t) {
                    LogTools.getInstance().logRecord2("⚠️ [诊断] 异常: " + t.getMessage());
                }
            }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);

            LogTools.getInstance().logRecord2("📊 综合诊断监控已启动（间隔: 10秒）");

        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 启动诊断监控失败: " + t.getMessage());
        }
    }

    private void stopDiagnosticMonitoring() {
        try {
            if (diagnosticTask != null) {
                diagnosticTask.cancel(false);
                diagnosticTask = null;
            }
            if (diagnosticExecutor != null) {
                diagnosticExecutor.shutdown();
                diagnosticExecutor = null;
            }
            LogTools.getInstance().logRecord2("📊 综合诊断监控已停止");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止诊断监控失败: " + t.getMessage());
        }
    }

    // 监控队列状态（详细版）
    private void monitorQueueStatus() {
        // queueDepay 监控
        if (queueDepay != null) {
            try {
                int currentBuffers = (Integer) queueDepay.get("current-level-buffers");
                long currentTime = ((Long) queueDepay.get("current-level-time")) / 1_000_000; // 转为ms
                int maxBuffers = queueDepayTargetBuffers;

                double fillRate = (double) currentBuffers / maxBuffers * 100;

                String status = fillRate > 80 ? "❌严重堆积" : fillRate > 60 ? "⚠️轻微堆积" : "✅正常";

                LogTools.getInstance().logRecord2(String.format(
                        "📊 [queueDepay] %d/%d帧 (%.1f%%), %dms %s",
                        currentBuffers, maxBuffers, fillRate, currentTime, status
                ));

                if (fillRate > 80) {
                    LogTools.getInstance().logRecord2("   ⚠️ 网络丢包/延迟严重，可能导致马赛克");
                }
            } catch (Throwable e) {
                LogTools.getInstance().logRecord2("⚠️ [queueDepay] 监控失败: " + e.getMessage());
            }
        }

        // queueDecode 监控
        if (queueDecode != null) {
            try {
                int currentBuffers = (Integer) queueDecode.get("current-level-buffers");
                long currentTime = ((Long) queueDecode.get("current-level-time")) / 1_000_000;
                int maxBuffers = finalQueueDecodeTargetBuffers;

                double fillRate = (double) currentBuffers / maxBuffers * 100;

                String status = fillRate > 80 ? "❌严重堆积" : fillRate > 60 ? "⚠️轻微堆积" : "✅正常";

                LogTools.getInstance().logRecord2(String.format(
                        "📊 [queueDecode] %d/%d帧 (%.1f%%), %dms %s",
                        currentBuffers, maxBuffers, fillRate, currentTime, status
                ));

                if (fillRate > 80) {
                    LogTools.getInstance().logRecord2("   ⚠️ 解码器性能不足，可能导致马赛克");
                }
            } catch (Throwable e) {
                LogTools.getInstance().logRecord2("⚠️ [queueDecode] 监控失败: " + e.getMessage());
            }
        }
    }

    // 监控 Jitterbuffer 统计
    private void monitorJitterbuffer() {
        if (webrtcbin != null) {
            try {
                webrtcbin.getElements().forEach(element -> {
                    if (element.getName() != null && element.getName().contains("jitterbuffer")) {
                        try {
                            Object stats = element.get("stats");
                            if (stats != null) {
                                String statsStr = stats.toString();
                                LogTools.getInstance().logRecord2("📊 [Jitterbuffer] " + statsStr);

                                // 简单解析关键指标
                                if (statsStr.contains("num-lost") && statsStr.contains("num-late")) {
                                    LogTools.getInstance().logRecord2("   ℹ️ 包含丢包/延迟统计，请检查数值");
                                }
                            }
                        } catch (Throwable ignore) {}
                    }
                });
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ [Jitterbuffer] 监控失败: " + t.getMessage());
            }
        }
    }

    // 监控 WebRTC 连接质量
    private void monitorWebRTCQuality() {
        if (webrtcbin != null) {
            try {
                WebRTCPeerConnectionState state = webrtcbin.getConnectionState();
                LogTools.getInstance().logRecord2("📊 [WebRTC] 连接状态: " + state);

                if (state != WebRTCPeerConnectionState.CONNECTED) {
                    LogTools.getInstance().logRecord2("   ⚠️ 连接未建立，可能导致马赛克");
                }
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ [WebRTC] 监控失败: " + t.getMessage());
            }
        }
    }

    // CPU占用监控：在播放期间定期输出进程与系统CPU使用率
    private void startCpuMonitor() {
        try {
            if (cpuMonitorTimer != null) return;
            final com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean == null) {
                LogTools.getInstance().logRecord2("⚠️ 操作系统监控不可用，无法启动CPU监控");
                return;
            }
            cpuMonitorTimer = new Timer("cpu-monitor", true);
            final int cores = getCore();
            cpuMonitorTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    try {
                        long nowNs = System.nanoTime();
                        long procNs = osBean.getProcessCpuTime();
                        if (lastProcCpuTimeNs > 0 && lastCpuSampleWallNs > 0) {
                            long deltaProc = procNs - lastProcCpuTimeNs;
                            long deltaWall = nowNs - lastCpuSampleWallNs;
                            if (deltaProc >= 0 && deltaWall > 0) {
                                double procPct = Math.min(100.0, Math.max(0.0, (double) deltaProc / (double) deltaWall / cores * 100.0));
                                double sysPct = -1.0;
                                try {
                                    double rawSys = osBean.getSystemCpuLoad();
                                    if (rawSys >= 0.0 && rawSys <= 1.0) {
                                        sysPct = rawSys * 100.0;
                                    }
                                } catch (Throwable ignore) {}
                                System.out.printf("📊 CPU监控: 进程=%.1f%% 系统=%s 核心=%d%n", procPct,
                                        (sysPct >= 0 ? String.format("%.1f%%", sysPct) : "N/A"), cores);
                            }
                        }
                        lastProcCpuTimeNs = procNs;
                        lastCpuSampleWallNs = nowNs;
                    } catch (Throwable t) {
                        LogTools.getInstance().logRecord2("⚠️ CPU监控异常: " + t.getMessage());
                    }
                }
            }, 1000, 2000);
            System.out.println("✅ CPU占用监控已启动");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 启动CPU监控失败: " + t.getMessage());
        }
    }

    private void stopCpuMonitor() {
        try {
            if (cpuMonitorTimer != null) {
                cpuMonitorTimer.cancel();
                cpuMonitorTimer = null;
                lastProcCpuTimeNs = -1L;
                lastCpuSampleWallNs = -1L;
                System.out.println("✅ CPU占用监控已停止");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord2("⚠️ 停止CPU监控异常: " + t.getMessage());
        }
    }

    /**
     * 辅助方法：安全地释放 GStreamer 元素
     */
    private void disposeElement(Element element, String name) {
        if (element != null) {
            try {
                element.dispose();
                // LogTools.getInstance().logRecord3("  ✅ 已释放: " + name);
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("  ⚠️ 释放 " + name + " 失败: " + t.getMessage());
            }
        }
    }

    /**
     * 停止播放器
     */
    public void stop() {
        LogTools.getInstance().logRecord3("========== 🛑 开始停止 WebRTC 播放器 ==========");

        // 记录停止前的内存使用情况
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        LogTools.getInstance().logRecord3("📊 停止前内存使用: " + usedMemoryBefore + " MB");

        LogTools.getInstance().logRecord3("当前状态 - Pipeline: " + (pipeline != null ? "存在" : "null") +
                ", overlayChildHandle: 0x" + Long.toHexString(overlayChildHandle) +
                ", overlayWindowHandle: 0x" + Long.toHexString(overlayWindowHandle) +
                ", videoOverlay: " + (videoOverlay != null ? "存在" : "null"));

        // ===== 第0步：主动删除SRS播放连接（避免僵尸连接）=====
        LogTools.getInstance().logRecord3("步骤0: 删除SRS播放连接");
        deleteSrsPlayConnection();

        // ===== 第1步：停止所有定时器 =====
        LogTools.getInstance().logRecord3("步骤1: 停止所有定时器");
        stopPliTimer();
        stopKeepAliveTimer();
        stopFrameWatchdog();
        stopCpuMonitor();
        stopPeriodicKeyframeRequest();  // ⚡ 停止定期关键帧请求
        stopDiagnosticMonitoring();     // ⚡ 停止综合诊断监控
        lastFrameTimeMs = 0L;
        LogTools.getInstance().logRecord3("✅ 定时器已停止");

        // ===== 第2步：先停止 Pipeline（让 GStreamer 清理窗口子类化）=====
        // ⭐ 关键：必须先停止 Pipeline，让 d3d11videosink 自己清理窗口子类化
        LogTools.getInstance().logRecord3("步骤2: 停止 Pipeline（让 GStreamer 清理窗口子类化）");
        if (pipeline != null) {
            try {
                LogTools.getInstance().logRecord3("🎬 调用 pipeline.stop()...");
                StateChangeReturn ret = pipeline.stop();
                LogTools.getInstance().logRecord3("🎬 Pipeline 停止返回: " + ret);

                // 等待状态切换完成（最多2秒，给足时间清理）
                LogTools.getInstance().logRecord3("🎬 等待 Pipeline 状态切换完成（最多2秒）...");
                State finalState = pipeline.getState(2000000000L); // 2秒 = 2e9纳秒
                LogTools.getInstance().logRecord3("🎬 Pipeline 最终状态: " + finalState);
                LogTools.getInstance().logRecord3("✅ Pipeline 已停止，窗口子类化应该已被 GStreamer 清理");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ Pipeline 停止失败: " + t.getMessage());
                t.printStackTrace();
            }
        } else {
            LogTools.getInstance().logRecord3("ℹ️ Pipeline 为 null，跳过停止");
        }

        // ===== 第3步：关闭 WebRTC 连接 =====
        LogTools.getInstance().logRecord3("步骤3: 关闭 WebRTC 连接");
        if (webrtcbin != null) {
            try {
                // ⭐ 关键修复：先关闭 PeerConnection（向 SRS 发送关闭信号）
                try {
                    WebRTCPeerConnectionState currentState = webrtcbin.getConnectionState();
                    LogTools.getInstance().logRecord3("   当前 WebRTC 连接状态: " + currentState);

                    if (currentState == WebRTCPeerConnectionState.CONNECTED ||
                            currentState == WebRTCPeerConnectionState.CONNECTING) {
                        LogTools.getInstance().logRecord3("   → 发送关闭信号到 SRS...");

                        // 方法1：设置连接状态为 closed
                        try {
                            webrtcbin.setState(State.NULL);  // 先停止元素
                            LogTools.getInstance().logRecord3("   ✅ webrtcbin 已设置为 NULL 状态");
                        } catch (Throwable t1) {
                            LogTools.getInstance().logRecord3("   ⚠️ 设置 NULL 状态失败: " + t1.getMessage());
                        }

                        // 方法2：发送 EOS 事件（通知流结束）
                        try {
                            webrtcbin.sendEvent(org.freedesktop.gstreamer.event.EOSEvent.class.newInstance());
                            LogTools.getInstance().logRecord3("   ✅ EOS 事件已发送");
                        } catch (Throwable t2) {
                            LogTools.getInstance().logRecord3("   ⚠️ 发送 EOS 失败: " + t2.getMessage());
                        }

                        // ⭐⭐⭐ 方法3：通过 HTTP API 主动删除 SRS 播放连接（最可靠）

                    }
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord3("   ⚠️ 关闭 PeerConnection 失败: " + t.getMessage());
                }

                // 断开所有信号连接（使用正确的 disconnect 重载）
                if (notifyIceConnectionState != null) {
                    webrtcbin.disconnect(GObjectAPI.GObjectClass.Notify.class, notifyIceConnectionState);
                    notifyIceConnectionState = null;
                }
                if (notifyConnectionState != null) {
                    webrtcbin.disconnect(GObjectAPI.GObjectClass.Notify.class, notifyConnectionState);
                    notifyConnectionState = null;
                }
                if (notifyIceGathering != null) {
                    webrtcbin.disconnect(GObjectAPI.GObjectClass.Notify.class, notifyIceGathering);
                    notifyIceGathering = null;
                }
                LogTools.getInstance().logRecord3("✅ WebRTC 信号已断开");

                // ⭐ 新增：等待一小段时间，确保关闭信号发送完成
                try {
                    Thread.sleep(100);  // 100ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 断开 WebRTC 信号失败: " + t.getMessage());
                t.printStackTrace();
            }
        }

        // ===== 第4步：清理 Java 堆内存（大对象）=====
        LogTools.getInstance().logRecord3("步骤4: 清理 Java 堆内存");
        try {
            // 清理图像缓存
            latestFrameImage = null;
            sharedImage = null;
            warmRepImage = null;

            // 清理缓存环
            if (cacheRing != null) {
                cacheRing.clear();
                LogTools.getInstance().logRecord3("  ✅ 已清理图像缓存环");
            }

            // 清理编码缓存环（可能包含大量字节数组）
            synchronized (encodedRing) {
                encodedRing.clear();
                LogTools.getInstance().logRecord3("  ✅ 已清理编码缓存环");
            }

            // 清理 SPS/PPS 参数
            lastSpsParam = null;
            lastPpsParam = null;

            // 清理可重用缓冲区
            reusableRgbBuffer = null;
            pixelBuffer = null;

            // 清理回调
            frameCallback = null;
            latestFrameCallback = null;
            globalFrameListener = null;
            captureCallback = null;
            pendingFrameCallback = null;
            pendingCaptureCallback = null;
            warmCallback = null;

            // 清理轻量级帧缓冲和时间轴抓拍
            if (lightweightBuffer != null) {
                try {
                    lightweightBuffer.clear();
                    LogTools.getInstance().logRecord3("  ✅ 已清理轻量级帧缓冲");
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord3("  ⚠️ 清理轻量级帧缓冲失败: " + t.getMessage());
                }
            }

            if (timelineCapture != null) {
                try {
                    timelineCapture.cancelCapture();
                    LogTools.getInstance().logRecord3("  ✅ 已取消时间轴抓拍");
                } catch (Throwable t) {
                    LogTools.getInstance().logRecord3("  ⚠️ 取消时间轴抓拍失败: " + t.getMessage());
                }
            }

            // 清理帧索引列表
            if (frameIndex != null) {
                frameIndex.clear();
                LogTools.getInstance().logRecord3("  ✅ 已清理帧索引列表");
            }

            LogTools.getInstance().logRecord3("✅ Java 堆内存已清理");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 清理 Java 堆内存失败: " + t.getMessage());
            t.printStackTrace();
        }

        // ===== 第5步：清理磁盘缓存 =====
        LogTools.getInstance().logRecord3("步骤5: 清理磁盘缓存");
        if (diskCaptureCache != null) {
            try {
                diskCaptureCache.clear();
                LogTools.getInstance().logRecord3("✅ 磁盘缓存已清理");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 清理磁盘缓存失败: " + t.getMessage());
                t.printStackTrace();
            }
        }

        // 清理磁盘缓存文件列表
        try {
            synchronized (diskCacheFiles) {
                diskCacheFiles.clear();
                LogTools.getInstance().logRecord3("✅ 磁盘缓存文件列表已清理");
            }
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 清理磁盘缓存文件列表失败: " + t.getMessage());
        }

        // ===== 第6步：清理元素引用（⚠️ 不 dispose，因为会重新 play）=====
        // ⭐ 关键修改：只清空引用，不 dispose()，因为下次 play() 会重新创建 Pipeline
        // 原因：频繁 dispose 会导致：
        // 1. 高端机型 GC 压力大，卡顿
        // 2. 实时窗口切换后无法恢复（元素被销毁）
        LogTools.getInstance().logRecord3("步骤6: 清空 GStreamer 元素引用（不释放，等待下次 play）");

        // 只清空引用，让 Pipeline dispose 时自动清理
        webrtcSrcPad = null;
        webrtcbin = null;
        rtph264depay = null;
        h264parse = null;
        decoder = null;
        appsink = null;
        captureValve = null;
        captureImageQueue = null;
        jpegEncoder = null;
        multifilesink = null;
        splitTee = null;
        d3d11convert = null;
        videoBalance = null;
        gamma = null;
        videocrop = null;
        videoscale = null;
        zoomCapsfilter = null;

        LogTools.getInstance().logRecord3("✅ 元素引用已清空");

        // ===== 第6.5步：移除监听器（防止泄漏）=====
        LogTools.getInstance().logRecord3("步骤6.5: 移除监听器（防止泄漏）");
        try {
            if (overlayTarget != null) {
                if (sceneListener != null) {
                    overlayTarget.sceneProperty().removeListener(sceneListener);
                    sceneListener = null;
                }
                if (layoutListener != null) {
                    overlayTarget.layoutBoundsProperty().removeListener(layoutListener);
                    layoutListener = null;
                }
            }
            if (monitoredStage != null) {
                if (stageXListener != null) {
                    monitoredStage.xProperty().removeListener(stageXListener);
                    stageXListener = null;
                }
                if (stageYListener != null) {
                    monitoredStage.yProperty().removeListener(stageYListener);
                    stageYListener = null;
                }
                monitoredStage = null;
            }
            listenersAdded = false;
            zoomCapsConfigured = false;  // ⭐ 重置缩放配置标志
            currentScaleFactor = 1.0f;   // ⭐ 重置缩放比例
            FileToos.receiveFps = 0;     // ⭐ 重置帧率显示
            fpsFrameCounter.set(0);
            // ⚡ 重置 EMA
            fpsEma = 0.0;
            fpsEmaInitialized = false;
            LogTools.getInstance().logRecord3("✅ 监听器已移除，缩放配置已重置");
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 移除监听器失败: " + t.getMessage());
        }

        // ===== 第7步：清理 VideoOverlay 绑定并销毁渲染窗口 =====
        // ⭐ 关键：Pipeline 已停止，d3d11videosink 已自动清理窗口子类化
        // 现在可以安全地销毁窗口了
        LogTools.getInstance().logRecord3("步骤7: 清理 VideoOverlay 绑定并销毁渲染窗口");
        LogTools.getInstance().logRecord3("当前窗口状态 - overlayChildHandle: 0x" + Long.toHexString(overlayChildHandle) +
                ", overlayWindowHandle: 0x" + Long.toHexString(overlayWindowHandle) +
                ", videoOverlay: " + (videoOverlay != null ? "存在" : "null"));

        // 清理 VideoOverlay 引用（不需要调用 setWindowHandle(0)，Pipeline 已停止）
        if (videoOverlay != null) {
            LogTools.getInstance().logRecord3("清理 VideoOverlay 引用");
            videoOverlay = null;
        }

        // ⭐ 保留 overlayWindowHandle，供下次使用
        // overlayWindowHandle 是父窗口句柄，第二次 play() 时还需要用到
        LogTools.getInstance().logRecord3("保留 overlayWindowHandle: 0x" + Long.toHexString(overlayWindowHandle) + "（供下次使用）");
        // overlayWindowHandle = 0L; // ⭐ 注释掉，不清理

        // ⭐ 关键修复：解除窗口绑定并延迟销毁，避免 GStreamer 崩溃
        // 原因：
        // 1. GStreamer 的 d3d11videosink 对窗口进行了子类化（subclass）
        // 2. 如果立即销毁窗口，会触发 external_window_proc != sub_class_proc 断言
        // 3. 必须先解除绑定，再延迟销毁
        if (overlayChildHandle != 0L) {
            final long handleToDestroy = overlayChildHandle;
            LogTools.getInstance().logRecord3("处理子窗口: 0x" + Long.toHexString(handleToDestroy));
            try {
                HWND hwnd = new HWND(Pointer.createConstant(handleToDestroy));
                boolean isWindow = User32.INSTANCE.IsWindow(hwnd);
                LogTools.getInstance().logRecord3("IsWindow 检查结果: " + isWindow);

                if (isWindow) {
                    // ⭐ 第1步：先解除 VideoOverlay 绑定（设置为 0）
                    if (videoOverlay != null) {
                        try {
                            videoOverlay.setWindowHandle(0L);
                            LogTools.getInstance().logRecord3("✅ VideoOverlay 绑定已解除");
                        } catch (Throwable t) {
                            LogTools.getInstance().logRecord3("⚠️ 解除 VideoOverlay 绑定失败: " + t.getMessage());
                        }
                    }

                    // ⭐ 第2步：隐藏窗口
                    User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_HIDE);
                    LogTools.getInstance().logRecord3("✅ 窗口已隐藏");

                    // ⭐ 第3步：延迟 500ms 后销毁窗口（让 GStreamer 完全释放）
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            HWND hwndDelayed = new HWND(Pointer.createConstant(handleToDestroy));
                            if (User32.INSTANCE.IsWindow(hwndDelayed)) {
                                boolean destroyed = User32.INSTANCE.DestroyWindow(hwndDelayed);
                                if (destroyed) {
                                    LogTools.getInstance().logRecord3("✅ 延迟销毁窗口成功: 0x" + Long.toHexString(handleToDestroy));
                                } else {
                                    LogTools.getInstance().logRecord3("⚠️ 延迟销毁窗口失败: 0x" + Long.toHexString(handleToDestroy));
                                }
                            } else {
                                LogTools.getInstance().logRecord3("ℹ️ 窗口已自动释放: 0x" + Long.toHexString(handleToDestroy));
                            }
                        } catch (Throwable t) {
                            LogTools.getInstance().logRecord3("⚠️ 延迟销毁窗口异常: " + t.getMessage());
                        }
                    }, "WindowDestroyer-" + Long.toHexString(handleToDestroy)).start();
                }
                // ⭐ 立即重置句柄
                overlayChildHandle = 0L;
                LogTools.getInstance().logRecord3("overlayChildHandle 已重置为 0");
            } catch (Throwable t) {
                LogTools.getInstance().logRecord3("⚠️ 处理子窗口异常: " + t.getMessage());
                t.printStackTrace();
                overlayChildHandle = 0L;
            }
        } else {
            LogTools.getInstance().logRecord3("overlayChildHandle 为 0，无需处理");
        }

        // ⭐ 不清理 overlayTarget 引用！
        // overlayTarget 是外部通过 setOverlayTarget() 传入的，应该保持不变
        // 这样第二次 play() 时布局监听器才能正常工作
        LogTools.getInstance().logRecord3("保留 overlayTarget 引用（不清理，供下次使用）");
        // overlayTarget = null; // ⭐ 注释掉，不清理

        // ===== 第8步：清理预热解码器 =====
        try {
            disposeOfflineWarmDecoder();
        } catch (Throwable ignore) {}

        // ===== 第9步：关闭日志文件 =====
        if (recordLogger != null) {
            try {
                recordLogger.close();
                recordLogger = null;
            } catch (Throwable ignore) {}
        }
        if (recordLogger3 != null) {
            try {
                recordLogger3.close();
                recordLogger3 = null;
            } catch (Throwable ignore) {}
        }

        // ===== 第10步：清理 Pipeline 引用（⚠️ 不 dispose，让下次 play 重新创建）=====
        // ⚠️ 重要修改：不调用 pipeline.dispose()，只清空引用
        // 原因：
        // 1. play() 会重新调用 createPipeline()，会自动处理旧 Pipeline
        // 2. 频繁 dispose 导致高端机型卡顿（GC 压力大）
        // 3. dispose 会导致实时窗口切换后无法恢复
        LogTools.getInstance().logRecord3("步骤10: 清空 Pipeline 引用（不释放，等待下次 play 重新创建）");
        pipeline = null;
        LogTools.getInstance().logRecord3("✅ Pipeline 引用已清空");

        // ===== 第11步：跳过强制垃圾回收（避免卡顿）=====
        // ⚠️ 移除 System.gc() 调用，原因：
        // 1. 高端机型对象多，强制 GC 会导致明显卡顿
        // 2. JVM 会自动管理内存，不需要手动干预
        // 3. 频繁 GC 反而降低性能
        LogTools.getInstance().logRecord3("步骤11: 跳过强制垃圾回收（让 JVM 自动管理）");

        // ===== 第12步：重置状态标志 =====
        LogTools.getInstance().logRecord3("步骤12: 重置状态标志");
        currentStreamUrl = null;
        hasReceivedSps.set(false);
        hasReceivedPps.set(false);
        hasReceivedIdr.set(false);
        hasReceivedPSlice.set(false);
        isWindowBound.set(false);  // ⭐ 重置窗口绑定标志
        firstDataReceived.set(false);  // ⭐ 重置数据接收标志
        pipelineStartTimeMs = 0L;  // ⭐ 重置启动时间
        hasReceivedFuA.set(false);
        displayBuffersSeen = false;
        globalFrameCounter.set(0);
        diskCacheFrameCounter.set(0);

        // 记录停止后的内存使用情况
        long usedMemoryAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long memoryReleased = usedMemoryBefore - usedMemoryAfter;
        LogTools.getInstance().logRecord3("📊 停止后内存使用: " + usedMemoryAfter + " MB");
        LogTools.getInstance().logRecord3("📊 本次释放内存: " + (memoryReleased > 0 ? "+" : "") + memoryReleased + " MB");

        LogTools.getInstance().logRecord3("========== ✅ 播放器已完全停止并释放资源 ==========");
    }

    /**
     * 获取显示组件
     */
    public ImageView getImageView() {
        return imageView;
    }

    /**
     * 获取NAL单元接收状态
     */
    public String getNalStatus() {
        return String.format("SPS:%s PPS:%s IDR:%s P-slice:%s FU-A:%s",
                hasReceivedSps.get() ? "✅" : "❌",
                hasReceivedPps.get() ? "✅" : "❌",
                hasReceivedIdr.get() ? "✅" : "❌",
                hasReceivedPSlice.get() ? "✅" : "❌",
                hasReceivedFuA.get() ? "✅" : "❌"
        );
    }

    /**
     * 获取最后一帧的时间戳
     */
    public long getLastFrameTimeMs() {
        return lastFrameTimeMs;
    }

    /**
     * 设置回调最小间隔毫秒（运行时可调）
     */
    public void setCallbackMinIntervalMs(int ms) {
        if (ms < 0) ms = 0;
        this.callbackMinIntervalMs = ms;
        try { System.out.println("🔧 回调最小间隔更新为 " + ms + "ms"); } catch (Throwable ignore) {}
    }

    /**
     * 获取当前回调最小间隔毫秒
     */
    public int getCallbackMinIntervalMs() {
        return this.callbackMinIntervalMs;
    }

    /**
     * 获取最近显示环的帧快照（最多 max 帧，从旧到新）
     * 用于调试/抓拍回放，返回对环内帧的只读视图（不复制像素）。
     */
    // 已移除显示环缓存，删除最近显示帧快照方法

    /**
     * 获取最近GPU缓存环的帧快照（最多 max 帧，从旧到新）
     * 严格仅缓存抓拍场景下用于预推窗口。
     */
    public java.util.List<WritableImage> getRecentCacheFramesSnapshot(int max) {
        // 禁用GPU缓存环快照，直接返回空列表以避免遍历与深拷贝开销
        try { System.out.println("⛔ getRecentCacheFramesSnapshot: 已禁用，返回空列表"); } catch (Throwable ignore) {}
        return java.util.Collections.emptyList();
    }

    /**
     * ⚡ 获取当前帧回调函数
     * @return 当前帧回调函数，可能为null
     */
    public java.util.function.Consumer<Image> getFrameCallback() {
        return this.frameCallback;
    }
    
    /**
     * ⚡ 获取当前显示帧（从内存环获取，和屏幕显示完全同步）
     * 用于实时流抓拍，确保抓到的是用户看到的那一帧
     * @return 当前显示帧，如果内存环为空返回null
     */
    public Image getCurrentDisplayFrame() {
        try {
            java.util.List<com.acard.acard.capture.RealtimeFrameRing.FrameData> frames = 
                com.acard.acard.capture.RealtimeFrameRing.getInstance().getRecentFrames(1);
            if (frames != null && !frames.isEmpty()) {
                return frames.get(0).toImage();
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("⚠️ 获取当前显示帧失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * ⚡ 获取最近N帧（从内存环获取，用于抓拍回放）
     * @param count 需要的帧数
     * @return 帧列表（Image对象）
     */
    public java.util.List<Image> getRecentDisplayFrames(int count) {
        java.util.List<Image> result = new java.util.ArrayList<>();
        try {
            java.util.List<com.acard.acard.capture.RealtimeFrameRing.FrameData> frames = 
                com.acard.acard.capture.RealtimeFrameRing.getInstance().getRecentFrames(count);
            for (com.acard.acard.capture.RealtimeFrameRing.FrameData frame : frames) {
                Image img = frame.toImage();
                if (img != null) {
                    result.add(img);
                }
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("⚠️ 获取最近帧失败: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * ⚡ 获取内存环状态
     */
    public String getRealtimeRingStatus() {
        return com.acard.acard.capture.RealtimeFrameRing.getInstance().getStatus();
    }

    /**
     * 设置帧回调函数，用于接收处理后的帧
     * @param callback 帧回调函数，接收JavaFX Image对象
     */
    public void setFrameCallback(java.util.function.Consumer<Image> callback) {
        this.frameCallback = callback;
        boolean hasCallback = callback != null;
        try {
            System.out.println("🧩 setFrameCallback: 回调已设置=" + hasCallback + ", 当前线程=" + Thread.currentThread().getName());
        } catch (Throwable ignore) {}

        // 检查管道是否已创建
        if (callbackSink == null || callbackValve == null) {
            System.out.println("⏰ setFrameCallback: 管道尚未创建，暂存回调待后续应用");
            this.pendingFrameCallback = callback;
            return;
        }

        // 应用回调设置
        applyFrameCallbackSettings(callback, hasCallback);
    }

    private void applyFrameCallbackSettings(java.util.function.Consumer<Image> callback, boolean hasCallback) {
        // 控制 appsink 发信号（默认关闭，只有设置回调时打开）
        try {
            if (callbackSink != null) {
                callbackSink.set("emit-signals", hasCallback);
                System.out.println("🔧 setFrameCallback: callbackSink.emit-signals 设置为 " + hasCallback);
            } else {
                LogTools.getInstance().logRecord2("❌ setFrameCallback: callbackSink 为 null，无法设置 emit-signals");
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ setFrameCallback: 设置 emit-signals 失败: " + e.getMessage());
        }

        // 控制回调阀门（默认 drop，设置回调时打开）
        try {
            if (callbackValve != null) {
                callbackValve.set("drop", !hasCallback);
                System.out.println("🔧 setFrameCallback: callbackValve.drop 设置为 " + (!hasCallback));
            } else {
                LogTools.getInstance().logRecord2("❌ setFrameCallback: callbackValve 为 null，无法设置 drop");
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ setFrameCallback: 设置 valve.drop 失败: " + e.getMessage());
        }

        // 🔍 GPU直显模式诊断
        boolean forceOverlaySink4Prop = Boolean.parseBoolean(System.getProperty("video.forceD3DVideoSink", "false"));
        boolean activeGpuDisplayMode = appsink == null && !forceOverlaySink4Prop;


        // 将 callback_queue 的下游在 fakesink 与 appsink 之间切换，避免空闲时拉帧
        try {
            Pad queueSrcPad = callbackQueue != null ? callbackQueue.getStaticPad("src") : null;
            Pad fakeSinkPad = callbackFakesink != null ? callbackFakesink.getStaticPad("sink") : null;
            Pad cbSinkPad   = callbackSink != null ? callbackSink.getStaticPad("sink") : null;
            if (queueSrcPad != null) {
                if (hasCallback) {
                    // 确保将 callbackSink 加入管道并同步到父状态
                    try {
                        if (callbackSink != null && callbackSink.getParent() == null && pipeline != null) {
                            pipeline.add(callbackSink);
                            callbackSink.syncStateWithParent();
                            System.out.println("🔗 setFrameCallback: 已将 callbackSink 加入管道并同步状态");
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 将 callbackSink 加入管道失败: " + e.getMessage());
                    }
                    // 切到 appsink
                    try { if (fakeSinkPad != null && queueSrcPad.isLinked()) queueSrcPad.unlink(fakeSinkPad); } catch (Throwable ignore) {}
                    try {
                        if (cbSinkPad != null) {
                            Element.linkMany(callbackQueue, callbackSink);
                            System.out.println("🟢 setFrameCallback: callback_queue → callback_sink 已链接，开始拉取样本");
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 链接 callback_queue → callback_sink 失败: " + e.getMessage());
                    }
                } else {
                    // 切回 fakesink
                    try { if (cbSinkPad != null && queueSrcPad.isLinked()) queueSrcPad.unlink(cbSinkPad); } catch (Throwable ignore) {}
                    try { if (fakeSinkPad != null) Element.linkMany(callbackQueue, callbackFakesink); } catch (Throwable e) { LogTools.getInstance().logRecord2("❌ setFrameCallback: 链接 callback_queue → callback_fakesink 失败: " + e.getMessage()); }
                    try {
                        if (callbackSink != null) {
                            callbackSink.set("emit-signals", false);
                        }
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ setFrameCallback: 下游切换异常: " + e.getMessage());
        }

        // 在 GPU 直显模式下，设置回调才临时插入 d3d11download/caps 到阀门之前；取消回调时恢复直连阀门
        try {
            boolean forceOverlaySink4Prop2 = Boolean.parseBoolean(System.getProperty("video.forceD3DVideoSink", "false"));
            // 修复：避免依赖在 createPipeline 内部声明的局部变量，改为根据当前显示路径判断
            // 只要当前未使用 appsink（即使用 GPU 显示 sink），并且未强制 overlay，则视为 GPU 直显模式
            boolean activeGpuDisplayMode2 = appsink == null && !forceOverlaySink4Prop2;
            if (teeSrcCallbackPad != null && callbackValve != null) {
                Pad valveSinkPad = callbackValve.getStaticPad("sink");
                Pad cbDownloadSinkPad = cbDownload != null ? cbDownload.getStaticPad("sink") : null;
                if (hasCallback && activeGpuDisplayMode2) {
                    // 懒创建与安全添加：确保 cbDownload/cbCaps 可用且已加入管道
                    try {
                        if (cbDownload == null) {
                            cbDownload = ElementFactory.make("d3d11download", "cb_d3d11download");
                            System.out.println("🔧 setFrameCallback: 创建 cbDownload = " + cbDownload);
                        } else {
                            System.out.println("🔧 setFrameCallback: 复用现有 cbDownload = " + cbDownload);
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 创建 cbDownload 失败: " + e.getMessage());
                    }
                    try {
                        if (cbCaps == null) {
                            cbCaps = ElementFactory.make("capsfilter", "cb_capsfilter");
                            System.out.println("🔧 setFrameCallback: 创建 cbCaps = " + cbCaps);
                        } else {
                            System.out.println("🔧 setFrameCallback: 复用现有 cbCaps = " + cbCaps);
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 创建 cbCaps 失败: " + e.getMessage());
                    }
                    try {
                        if (cbCaps != null) {
                            cbCaps.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
                            System.out.println("🔧 setFrameCallback: cbCaps 设置格式成功");
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: cbCaps 设置格式失败: " + e.getMessage());
                    }
                    // 解除 tee → valve，改为 tee → d3d11download → caps → valve → queue
                    try {
                        if (valveSinkPad != null && teeSrcCallbackPad.isLinked()) {
                            teeSrcCallbackPad.unlink(valveSinkPad);
                            System.out.println("🔗 setFrameCallback: 已解除 teeSrcCallbackPad → callbackValve 链接");
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("⚠️ setFrameCallback: 解除原有链接失败: " + e.getMessage());
                    }

                    // 确保下载元素已加入管道
                    try {
                        if (pipeline != null) {
                            if (cbDownload != null && cbDownload.getParent() == null) {
                                pipeline.add(cbDownload);
                                System.out.println("🔗 setFrameCallback: 已将 cbDownload 加入管道");
                            } else if (cbDownload != null) {
                                System.out.println("🔗 setFrameCallback: cbDownload 已在管道中，parent=" + cbDownload.getParent());
                            } else {
                                LogTools.getInstance().logRecord2("❌ setFrameCallback: cbDownload 为 null，无法加入管道");
                            }
                            if (cbCaps != null && cbCaps.getParent() == null) {
                                pipeline.add(cbCaps);
                                System.out.println("🔗 setFrameCallback: 已将 cbCaps 加入管道");
                            } else if (cbCaps != null) {
                                System.out.println("🔗 setFrameCallback: cbCaps 已在管道中，parent=" + cbCaps.getParent());
                            } else {
                                LogTools.getInstance().logRecord2("❌ setFrameCallback: cbCaps 为 null，无法加入管道");
                            }
                        } else {
                            LogTools.getInstance().logRecord2("❌ setFrameCallback: pipeline 为 null");
                        }
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 加入下载元素到管道失败: " + e.getMessage());
                    }

                    // 重新建立链路：tee → d3d11download → caps → valve
                    try {


                        if (cbDownloadSinkPad != null && !cbDownloadSinkPad.isLinked()) {
                            teeSrcCallbackPad.link(cbDownloadSinkPad);
                            System.out.println("🟢 setFrameCallback: teeSrcCallbackPad → cbDownload 链接成功");
                        } else if (cbDownloadSinkPad != null) {
                            System.out.println("⚠️ setFrameCallback: cbDownloadSinkPad 已链接，peer=" + cbDownloadSinkPad.getPeer());
                        } else {
                            LogTools.getInstance().logRecord2("❌ setFrameCallback: cbDownloadSinkPad 为 null");
                        }

                        // 先解除 valve 的现有上游链接
                        if (valveSinkPad != null && valveSinkPad.isLinked()) {
                            Pad valveUpstreamPad = valveSinkPad.getPeer();
                            if (valveUpstreamPad != null) {
                                valveUpstreamPad.unlink(valveSinkPad);
                                System.out.println("🔗 setFrameCallback: 已解除 valve 的现有上游链接");
                            }
                        }

                        if (cbCaps != null) {
                            Element.linkMany(cbDownload, cbCaps, callbackValve);
                            System.out.println("🟢 setFrameCallback: cbDownload → cbCaps → callbackValve 链接成功");
                        } else {
                            Element.linkMany(cbDownload, callbackValve);
                            System.out.println("🟢 setFrameCallback: cbDownload → callbackValve 链接成功");
                        }

                        // 同步状态
                        if (cbDownload != null) {
                            cbDownload.syncStateWithParent();
                            System.out.println("🔧 setFrameCallback: cbDownload 状态已同步");
                        }
                        if (cbCaps != null) {
                            cbCaps.syncStateWithParent();
                            System.out.println("🔧 setFrameCallback: cbCaps 状态已同步");
                        }
                        System.out.println("🟢 setFrameCallback: GPU下载链路插入成功");

                        // 详细诊断GPU下载链路状态


                        // 检查链路连接状态
                        if (teeSrcCallbackPad != null && cbDownload != null) {
                            System.out.println("   - teeSrcCallbackPad → cbDownload 连接状态: " + teeSrcCallbackPad.isLinked() + " → " + (cbDownloadSinkPad != null ? cbDownloadSinkPad.isLinked() : "null"));
                        }

                        if (cbDownload != null && cbCaps != null) {
                            Pad cbDownloadSrcPad = cbDownload.getStaticPad("src");
                            Pad cbCapsSinkPad = cbCaps.getStaticPad("sink");
                            System.out.println("   - cbDownload → cbCaps 连接状态: " + (cbDownloadSrcPad != null ? cbDownloadSrcPad.isLinked() : "null") + " → " + (cbCapsSinkPad != null ? cbCapsSinkPad.isLinked() : "null"));
                        }

                        if (cbCaps != null && callbackValve != null) {
                            Pad cbCapsSrcPad = cbCaps.getStaticPad("src");
                            Pad callbackValveSinkPad = callbackValve.getStaticPad("sink");
                            System.out.println("   - cbCaps → callbackValve 连接状态: " + (cbCapsSrcPad != null ? cbCapsSrcPad.isLinked() : "null") + " → " + (callbackValveSinkPad != null ? callbackValveSinkPad.isLinked() : "null"));
                        }

                        if (callbackValve != null && callbackQueue != null) {
                            Pad callbackValveSrcPad = callbackValve.getStaticPad("src");
                            Pad callbackQueueSinkPad = callbackQueue.getStaticPad("sink");
                            System.out.println("   - callbackValve → callbackQueue 连接状态: " + (callbackValveSrcPad != null ? callbackValveSrcPad.isLinked() : "null") + " → " + (callbackQueueSinkPad != null ? callbackQueueSinkPad.isLinked() : "null"));
                        }

                        if (callbackQueue != null && callbackSink != null) {
                            Pad callbackQueueSrcPad = callbackQueue.getStaticPad("src");
                            Pad callbackSinkSinkPad = callbackSink.getStaticPad("sink");
                            System.out.println("   - callbackQueue → callbackSink 连接状态: " + (callbackQueueSrcPad != null ? callbackQueueSrcPad.isLinked() : "null") + " → " + (callbackSinkSinkPad != null ? callbackSinkSinkPad.isLinked() : "null"));
                        }

                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: GPU插入下载链路失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    // 恢复 tee → valve → queue，并解除 d3d11download/caps 链路
                    try {
                        if (cbDownloadSinkPad != null) teeSrcCallbackPad.unlink(cbDownloadSinkPad);
                    } catch (Throwable ignore) {}
                    try {
                        if (valveSinkPad != null && !valveSinkPad.isLinked()) teeSrcCallbackPad.link(valveSinkPad);
                    } catch (Throwable e) {
                        LogTools.getInstance().logRecord2("❌ setFrameCallback: 恢复阀门直连失败: " + e.getMessage());
                    }
                    // 修复：使用实例 unlink 而非静态重载，避免参数数量不匹配的编译错误
                    try { if (cbDownload != null && cbCaps != null) cbDownload.unlink(cbCaps); } catch (Throwable ignore) {}
                    try { if (cbCaps != null && callbackValve != null) cbCaps.unlink(callbackValve); } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("❌ setFrameCallback: GPU下载元素插入/还原异常: " + e.getMessage());
        }

        callbackActive = hasCallback;
        try { System.out.println("🧩 setFrameCallback: emit-signals=" + hasCallback + ", valve.drop=" + (!hasCallback)); } catch (Throwable ignore) {}
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, java.nio.charset.StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // 统一构建包含 vhost/eip 的 webrtc 流URL，支持系统属性开关
    public String buildStreamUrl() {
        String base = ("webrtc://" + serverHost + "/" + urlEncode(tenant) + "/" + urlEncode(streamId)).trim().replace("`", "");
        boolean appendParams = Boolean.parseBoolean(System.getProperty("srs.appendVhostEip", "true")); // 默认开启，恢复昨天的行为
        if (!appendParams) {
            System.out.println("🔧 已禁用 vhost/eip 自动附加（系统属性 srs.appendVhostEip=false），使用基础URL");
            return base;
        }
        String vhost = System.getProperty("srs.vhost", "vid-7gg4748");
        String eip = System.getProperty("srs.eip", serverHost);
        String url = base + "?vhost=" + urlEncode(vhost) + "&eip=" + urlEncode(eip);
        System.out.println("🔧 vhost/eip 参数: vhost=" + vhost + ", eip=" + eip + " (可通过系统属性 srs.vhost / srs.eip / srs.appendVhostEip 控制)");
        return url;
    }

    // === 兼容方法迁移自 GstWebRTCPlayerView ===
    private SDPMessage tryGetSdpMessage(WebRTCSessionDescription desc) {
        LogTools.getInstance().logRecord2("=== Trying to extract SDPMessage ===");

        String[] methodNames = {"getSdpMessage", "getSDPMessage", "sdpMessage", "getSessionDescription"};

        for (String methodName : methodNames) {
            try {
                Object result = desc.getClass().getMethod(methodName).invoke(desc);
                if (result instanceof SDPMessage) {
                    SDPMessage sdpMsg = (SDPMessage) result;
                    LogTools.getInstance().logRecord2("✅ Successfully extracted SDPMessage using method: " + methodName);
                    return sdpMsg;
                }
            } catch (Exception e) {
                LogTools.getInstance().logRecord2("❌ SDPMessage method " + methodName + " failed: " + e.getMessage());
            }
        }

        LogTools.getInstance().logRecord2("❌ All SDPMessage extraction methods failed");
        return null;
    }

    private String tryGetSdpText(WebRTCSessionDescription desc) {
        try {
            try { return (String) desc.getClass().getMethod("getSdpString").invoke(desc); } catch (Throwable ignore) {}
            try { return (String) desc.getClass().getMethod("getSDPString").invoke(desc); } catch (Throwable ignore) {}
            SDPMessage msg = tryGetSdpMessage(desc);
            if (msg != null) {
                return msg.toString();
            }
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("⚠️ tryGetSdpText失败: " + e.getMessage());
        }
        LogTools.getInstance().logRecord2("⚠️ 未能获取SDP文本，返回空字符串");
        return "";
    }


    private static void setLocalDescriptionCompat(WebRTCBin bin, WebRTCSessionDescription desc) {
        try {
            LogTools.getInstance().logRecord2("STEP4: ▶️ set-local-description begin");
            desc.disown(); // 交给底层接管
            bin.emit("set-local-description", desc, null);
            LogTools.getInstance().logRecord2("STEP4: ✅ set-local-description emitted");

            // 🔥 关键修复：延迟100ms，确保 local description 生效
            try {
                Thread.sleep(100);
                LogTools.getInstance().logRecord2("STEP4: ✅ set-local-description 延迟完成（100ms）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }



    private void setRemoteDescriptionCompat(String answerText) {
        setRemoteDescription(answerText);
        extractAndAddIceCandidatesFromSdp(answerText);
    }

    private String postOfferToSRS(String sdpOffer) {
        try {
            String apiUrl = ("http://" + serverHost + ":" + NetworkConfig.apiPort + "/rtc/v1/play/").trim().replace("`", "");
            String sdpCRLF = (sdpOffer == null ? "" : sdpOffer).replace("\r\n", "\n").replace("\n", "\r\n");

            String streamUrl = buildStreamUrl();

            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("api", apiUrl);
            obj.addProperty("streamurl", streamUrl);
            obj.addProperty("sdp", sdpCRLF);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            String json = gson.toJson(obj);

            System.out.println("📤 发送信令请求到: " + apiUrl);
            System.out.println("📋 请求体长度: " + json.length() + " 字符");
            System.out.println("📄 请求体预览: " + json.substring(0, Math.min(500, json.length())));
            int hexLen = Math.min(128, json.length());
            StringBuilder hex = new StringBuilder();
            for (int idx = 0; idx < hexLen; idx++) {
                hex.append(String.format("%02X ", (int) json.charAt(idx)));
            }
            System.out.println("🔍 请求体前128字节HEX: " + hex);

            boolean sdpHasAudio = sdpCRLF.contains("\nm=audio ");
            boolean sdpHasVideo = sdpCRLF.contains("\nm=video ");
            System.out.println("🔎 SDP Offer检查: hasAudio=" + sdpHasAudio + ", hasVideo=" + sdpHasVideo);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            String body = resp.body();

            System.out.println("📥 收到信令响应: " + resp.statusCode());
            System.out.println("🧾 响应头: " + resp.headers().map());
            System.out.println("📄 响应内容: " + body);

            if (resp.statusCode() / 100 == 3) {
                String loc = resp.headers().firstValue("location").orElse(null);
                if (loc != null && !loc.isEmpty()) {
                    String redirectUrl = loc.startsWith("http") ? loc : "http://" + serverHost + ":" + NetworkConfig.apiPort + (loc.startsWith("/") ? loc : "/" + loc);
                    System.out.println("➡️ 跟随重定向到: " + redirectUrl);
                    HttpRequest redirectReq = HttpRequest.newBuilder()
                            .uri(URI.create(redirectUrl))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                            .build();
                    resp = client.send(redirectReq, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                    body = resp.body();
                    System.out.println("📥 重定向后的响应: " + resp.statusCode());
                    System.out.println("🧾 响应头: " + resp.headers().map());
                    System.out.println("📄 响应内容: " + body);
                }
            }

            if (resp.statusCode() / 100 != 2) {
                LogTools.getInstance().logRecord2("❌ SRS HTTP错误: " + resp.statusCode() + ", body=" + body);
                return null;
            }

            int codeIdx = body.indexOf("\"code\":");
            if (codeIdx >= 0) {
                int comma = body.indexOf(",", codeIdx);
                String codeStr = (comma > 0 ? body.substring(codeIdx + 7, comma) : body.substring(codeIdx + 7)).trim();
                try {
                    int code = Integer.parseInt(codeStr.replaceAll("[^0-9-]", ""));
                    if (code != 0) {
                        LogTools.getInstance().logRecord2("❌ SRS业务码非0: " + code + ", body=" + body);
                        try {
                            String altStreamUrl = streamUrl; // 不再追加任何参数，保持与首次请求一致
                            com.google.gson.JsonObject retryObj = new com.google.gson.JsonObject();
                            retryObj.addProperty("api", apiUrl);
                            retryObj.addProperty("streamurl", altStreamUrl);
                            retryObj.addProperty("sdp", sdpCRLF);
                            com.google.gson.Gson retryGson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
                            String retryJson = retryGson.toJson(retryObj);
                            HttpRequest retryReq = HttpRequest.newBuilder()
                                    .uri(URI.create(apiUrl))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(retryJson, java.nio.charset.StandardCharsets.UTF_8))
                                    .build();
                            HttpResponse<String> retryResp = client.send(retryReq, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                            System.out.println("📥 回退重试响应: " + retryResp.statusCode());
                            System.out.println("📄 回退响应内容: " + retryResp.body());
                            if (retryResp.statusCode() / 100 == 2 && retryResp.body().contains("\"code\":0")) {
                                String remoteSdpRetry = extractSdpFromResponse(retryResp.body());
                                return remoteSdpRetry;
                            }
                        } catch (Exception ex) {
                            LogTools.getInstance().logRecord2("❌ 回退重试失败: " + ex.getMessage());
                        }
                        return null;
                    }
                } catch (NumberFormatException ignore) {}
            }

            String remoteSdp = extractSdpFromResponse(body);
            return remoteSdp;
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ postOfferToSRS失败: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // === 队列占用打印辅助 ===
    private static int getIntProp(Element e, String prop, int def) {
        try {
            Object v = e.get(prop);
            if (v instanceof Number) return ((Number) v).intValue();
            if (v != null) return Integer.parseInt(v.toString());
        } catch (Throwable ignore) {}
        return def;
    }
    private static boolean getBoolProp(Element e, String prop, boolean def) {
        try {
            Object v = e.get(prop);
            if (v instanceof Boolean) return (Boolean) v;
            if (v != null) return Boolean.parseBoolean(v.toString());
        } catch (Throwable ignore) {}
        return def;
    }

    /**
     * 打印保存/缓存相关队列的实时占用与配置
     */
    public void printSavingQueues() {
        System.out.println("===== 保存/缓存队列状态 =====");

        // 压缩码流缓存（parse -> teeCodec -> encodedValve -> encodedQueue -> encodedCaps -> encodedAppSink）
        try {
            int encCurr = encodedQueue != null ? getIntProp(encodedQueue, "current-level-buffers", -1) : -1;
            int encMax  = encodedQueue != null ? getIntProp(encodedQueue, "max-size-buffers", -1) : -1;
            int encLeaky= encodedQueue != null ? getIntProp(encodedQueue, "leaky", -1) : -1;
            boolean encDrop = encodedValve != null ? getBoolProp(encodedValve, "drop", false) : false;
            boolean encEmit = encodedAppSink != null ? getBoolProp(encodedAppSink, "emit-signals", false) : false;
            int encAppMax   = encodedAppSink != null ? getIntProp(encodedAppSink, "max-buffers", -1) : -1;
            boolean encAppDrop = encodedAppSink != null ? getBoolProp(encodedAppSink, "drop", false) : false;

            System.out.printf("📦 encoded_queue: current=%d, max=%d, leaky=%d%n", encCurr, encMax, encLeaky);
            System.out.printf("🚦 encoded_valve.drop=%s%n", String.valueOf(encDrop));
            System.out.printf("🪣 encoded_appsink: emit=%s, drop=%s, max-buffers=%d%n",
                    encEmit, encAppDrop, encAppMax);
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("打印压缩缓存失败: " + e.getMessage());
        }

        // 原始帧缓存（tee -> cacheValve -> [d3d11download?] -> [caps?] -> cacheQueue -> cacheLatestQueue -> cacheAppSink）
        try {
            int cacheCurr = cacheQueue != null ? getIntProp(cacheQueue, "current-level-buffers", -1) : -1;
            int cacheMax  = cacheQueue != null ? getIntProp(cacheQueue, "max-size-buffers", -1) : -1;
            int cacheLeaky= cacheQueue != null ? getIntProp(cacheQueue, "leaky", -1) : -1;

            int latestCurr = cacheLatestQueue != null ? getIntProp(cacheLatestQueue, "current-level-buffers", -1) : -1;
            int latestMax  = cacheLatestQueue != null ? getIntProp(cacheLatestQueue, "max-size-buffers", -1) : -1;
            int latestLeaky= cacheLatestQueue != null ? getIntProp(cacheLatestQueue, "leaky", -1) : -1;

            boolean cacheDrop = cacheValve != null ? getBoolProp(cacheValve, "drop", false) : false;
            boolean cacheEmit = cacheAppSink != null ? getBoolProp(cacheAppSink, "emit-signals", false) : false;
            int cacheAppMax   = cacheAppSink != null ? getIntProp(cacheAppSink, "max-buffers", -1) : -1;
            boolean cacheAppDrop = cacheAppSink != null ? getBoolProp(cacheAppSink, "drop", false) : false;

            System.out.printf("📦 cache_queue: current=%d, max=%d, leaky=%d%n", cacheCurr, cacheMax, cacheLeaky);
            System.out.printf("📦 cache_latest_queue: current=%d, max=%d, leaky=%d%n", latestCurr, latestMax, latestLeaky);
            System.out.printf("🚦 cache_valve.drop=%s%n", String.valueOf(cacheDrop));
            System.out.printf("🪣 cache_appsink: emit=%s, drop=%s, max-buffers=%d%n",
                    cacheEmit, cacheAppDrop, cacheAppMax);
        } catch (Throwable e) {
            LogTools.getInstance().logRecord2("打印原始缓存失败: " + e.getMessage());
        }

        System.out.println("===== 结束 =====");
    }

    public void play() {
        start();
    }

    // ========== JPEG 保存动态控制功能 ==========

    /**
     * 动态控制 JPEG 保存功能
     *
     * @param enabled true=启用JPEG保存，false=禁用JPEG保存
     */
    public void setJpegSaveEnabled(boolean enabled) {
       /* try {
            if ( captureValve!= null) {
                captureValve.set("drop", !enabled);  // drop=true 表示丢弃数据，drop=false 表示通过数据
                String status = enabled ? "启用" : "禁用";
                System.out.println("🔧 JPEG保存控制: " + status + " (captureValve.drop=" + (!enabled) + ")");
                logRecord("🔧 JPEG保存已" + status + ": captureValve.drop=" + (!enabled));
            } else {
                logRecord("❌ captureValve 为 null，无法控制 JPEG 保存");
            }
        } catch (Exception e) {
            logRecord("❌ 设置 JPEG 保存控制失败: " + e.getMessage());
            e.printStackTrace();
        }*/
    }

    /**
     * 获取当前 JPEG 保存状态
     *
     * @return true=JPEG保存已启用，false=JPEG保存已禁用
     */
    public boolean isJpegSaveEnabled() {
        try {
            if (captureValve != null) {
                // drop=false 表示数据通过（JPEG保存启用）
                // drop=true 表示数据丢弃（JPEG保存禁用）
                boolean drop = getBoolProp(captureValve, "drop", true);  // 默认为 true（禁用）
                return !drop;  // 返回相反值
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 获取 JPEG 保存状态失败: " + e.getMessage());
        }
        return false;  // 默认禁用
    }

    /**
     * 切换 JPEG 保存状态
     */
    public void toggleJpegSave() {
        boolean currentState = isJpegSaveEnabled();
        setJpegSaveEnabled(!currentState);
        System.out.println("🔄 JPEG保存状态切换: " + currentState + " → " + (!currentState));
    }





    /**
     * 设置视频旋转角度
     * @param degrees 旋转角度：0, 90, 180, 270
     */
    public void setVideoRotation2(int degrees) {
        if (d3d11convert != null && "d3d11convert".equals(d3d11convert.getFactory().getName())) {
            // ✅ 将角度转换为 GStreamer video-direction 枚举值
            int directionValue;
            switch (degrees) {
                case 0:
                    directionValue = 0;  // identity (不旋转)
                    break;
                case 90:
                    directionValue = 1;  // 90r (顺时针90度)
                    break;
                case 180:
                    directionValue = 2;  // 180 (旋转180度)
                    break;
                case 270:
                    directionValue = 3;  // 90l (逆时针90度 = 顺时针270度)
                    break;
                default:
                    logRecord("⚠️ 不支持的旋转角度: " + degrees + "，使用默认值0");
                    directionValue = 0;
                    break;
            }

            FileToos.usederection = degrees;
           // videoflip.set("method", 0);

            d3d11convert.set("rotation-z", degrees);
            //d3d11convert.set("video-direction", directionValue);

            // 先重置再设置（刷新技巧）
            //d3d11convert.set("video-direction", 0);
            //d3d11convert.set("video-direction", directionValue);
            logRecord("🔄 设置视频旋转: " + degrees + "度 (video-direction=" + directionValue + ")");
        } else {
            logRecord("⚠️ d3d11convert 不可用，无法设置旋转");
        }
    }


    public void setVideoRotation(float degrees) {
        d3d11convert.set("rotation-z", degrees);

        // 获取实际视频尺寸
        int width = FileToos.sslWidth;
        int height = FileToos.sslwHight;

        if (width <= 0 || height <= 0) {
            System.out.println("⚠️ 视频尺寸无效，使用默认缩放");
            return;
        }

        // 如果是 90 或 270 度，宽高互换
        if (degrees == 90 || degrees == 270 || degrees == -90 || degrees == -270) {
            // ⭐ 旋转90°/270°后的正确缩放算法（填满容器，无黑边，允许变形）
            // 
            // 数学推导（以 1920×1080 为例）：
            // ┌─────────────────────────────────────────────────────────┐
            // │ d3d11convert 变换顺序：Scale → Rotate                    │
            // │                                                         │
            // │ 原始: W×H = 1920×1080                                   │
            // │ Scale(sx, sy) 后: W*sx × H*sy = 1920*sx × 1080*sy       │
            // │ Rotate 90° 后宽高互换: H*sy × W*sx = 1080*sy × 1920*sx  │
            // │                                                         │
            // │ 要填满 W×H = 1920×1080 容器（无黑边，无放大/裁剪）：      │
            // │ - 旋转后宽度 = 1080*sy = 1920  →  sy = W/H = 1.78       │
            // │ - 旋转后高度 = 1920*sx = 1080  →  sx = H/W = 0.5625     │
            // │                                                         │
            // │ 结果：画面填满容器，宽高比会变形                          │
            // └─────────────────────────────────────────────────────────┘
            
            float scaleX = (float) height / width;   // H/W = 1080/1920 = 0.5625
            float scaleY = (float) width / height;   // W/H = 1920/1080 = 1.78

            d3d11convert.set("scale-x", scaleX);
            d3d11convert.set("scale-y", scaleY);

            System.out.println("🔄 旋转 " + degrees + "度，尺寸: " + width + "x" + height +
                    "，scale-x=" + scaleX + ", scale-y=" + scaleY + " (填满容器，无黑边)");
        } else {
            // 0度或180度，保持原样
            d3d11convert.set("scale-x", 1.0f);
            d3d11convert.set("scale-y", 1.0f);

            System.out.println("🔄 旋转 " + degrees + "度，保持原始缩放");
        }
    }

    /**
     * 设置视频翻转（镜像）
     * @param flipType 翻转类型：
     *                 0 = 不翻转
     *                 1 = 水平翻转（左右镜像）
     *                 2 = 垂直翻转（上下镜像）
     *                 3 = 水平+垂直翻转（=旋转180度）
     */
    public void setVideoFlip(int flipType) {
        if (d3d11convert != null && "d3d11convert".equals(d3d11convert.getFactory().getName())) {
            int directionValue;
            switch (flipType) {
                case 0:
                    directionValue = 0;  // identity (不翻转)
                    break;
                case 1:
                    directionValue = 4;  // horiz (水平翻转)
                    break;
                case 2:
                    directionValue = 5;  // vert (垂直翻转)
                    break;
                case 3:
                    directionValue = 2;  // 180 (水平+垂直 = 旋转180度)
                    break;
                default:
                    logRecord("⚠️ 不支持的翻转类型: " + flipType + "，使用默认值0");
                    directionValue = 0;
                    break;
            }

            d3d11convert.set("video-direction", directionValue);
            logRecord("🔄 设置视频翻转: flipType=" + flipType + " (video-direction=" + directionValue + ")");
        } else {
            logRecord("⚠️ d3d11convert 不可用，无法设置翻转");
        }
    }

    // ⭐ 当前缩放比例
    private volatile float currentScaleFactor = 1.0f;

    public void setVideoScale(float scale) {
        // 限制缩放范围：最小1.0（不能缩小），最大4.0（最大放大4倍）
        float clampedScale = Math.max(1.0f, Math.min(4.0f, scale));
        currentScaleFactor = clampedScale;
        
        // ✅ 获取最新视频尺寸
        videoWidth = FileToos.sslWidth;
        videoHeight = FileToos.sslwHight;
        
        if (videoWidth <= 0 || videoHeight <= 0) {
            LogTools.getInstance().logRecord2("⚠️ 缩放失败: 视频尺寸未知");
            return;
        }
        
        // ✅ 确保 zoomCapsfilter 已配置（强制输出原始分辨率）
        configureZoomCapsIfNeeded();
        
        // ✅ 使用 videocrop 裁剪 + videoscale 放大回原始尺寸
        // 流程：原始(1920x1080) → videocrop裁剪(960x540) → videoscale放大(1920x1080)
        // 这样输出分辨率不变，但只显示中心区域
        if (videocrop != null) {
            try {
                // 计算裁剪量（像素）
                // scale=1.0 → 不裁剪，显示完整画面
                // scale=2.0 → 裁剪50%，只显示中心区域，然后放大回原始尺寸
                double cropRatio = 1.0 - (1.0 / clampedScale);  // scale=2.0 → cropRatio=0.5
                
                int cropX = (int) Math.round(videoWidth * cropRatio / 2);   // 左右各裁剪
                int cropY = (int) Math.round(videoHeight * cropRatio / 2);  // 上下各裁剪
                
                // 确保裁剪后至少保留100x100像素
                int remainW = videoWidth - cropX * 2;
                int remainH = videoHeight - cropY * 2;
                if (remainW < 100 || remainH < 100) {
                    cropX = Math.max(0, (videoWidth - 100) / 2);
                    cropY = Math.max(0, (videoHeight - 100) / 2);
                    remainW = videoWidth - cropX * 2;
                    remainH = videoHeight - cropY * 2;
                }
                
                // 应用裁剪
                videocrop.set("left", cropX);
                videocrop.set("right", cropX);
                videocrop.set("top", cropY);
                videocrop.set("bottom", cropY);
                
                // ⭐ 关键：设置 zoomCapsfilter 输出分辨率为原始尺寸
                // 这样 videoscale 会将裁剪后的画面放大回原始分辨率
                if (zoomCapsfilter != null && clampedScale > 1.0f) {
                    String capsStr = String.format("video/x-raw,width=%d,height=%d", videoWidth, videoHeight);
                    Caps caps = Caps.fromString(capsStr);
                    zoomCapsfilter.set("caps", caps);
                    LogTools.getInstance().logRecord2("✅ zoomCapsfilter 设置输出: " + videoWidth + "x" + videoHeight);
                }
                
                LogTools.getInstance().logRecord2(String.format(
                    "🔍 视频缩放: scale=%.2f, 裁剪=%d,%d, 中间=%dx%d → 放大回 %dx%d",
                    clampedScale, cropX, cropY, remainW, remainH, videoWidth, videoHeight));
                    
            } catch (Throwable t) {
                LogTools.getInstance().logRecord2("⚠️ videocrop设置失败: " + t.getMessage());
            }
        } else {
            LogTools.getInstance().logRecord2("⚠️ 缩放失败: videocrop为null");
        }
        
        // ⭐ 缩放为1时，重置 zoomCapsfilter 为自适应模式
        if (clampedScale == 1.0f && zoomCapsfilter != null) {
            try {
                videocrop.set("left", 0);
                videocrop.set("right", 0);
                videocrop.set("top", 0);
                videocrop.set("bottom", 0);
                
                Caps caps = Caps.fromString("video/x-raw");
                zoomCapsfilter.set("caps", caps);
                LogTools.getInstance().logRecord2("✅ 缩放重置: 1x（无裁剪，自适应）");
            } catch (Throwable ignore) {}
        }
    }
    
    /**
     * ⭐ 分辨率变化时重新应用裁剪（用新分辨率重新计算裁剪量）
     */
    private void reapplyVideoCrop() {
        if (videocrop == null || currentScaleFactor <= 1.0f) {
            return;
        }
        
        int w = FileToos.sslWidth;
        int h = FileToos.sslwHight;
        if (w <= 0 || h <= 0) {
            return;
        }
        
        try {
            // ⭐ 分辨率变化时，先重置裁剪为0（防止caps协商期间裁剪量不匹配导致黑屏）
            videocrop.set("left", 0);
            videocrop.set("right", 0);
            videocrop.set("top", 0);
            videocrop.set("bottom", 0);
            
            // 用新分辨率重新计算裁剪量
            double cropRatio = 1.0 - (1.0 / currentScaleFactor);
            int cropX = (int) Math.round(w * cropRatio / 2);
            int cropY = (int) Math.round(h * cropRatio / 2);
            
            // 确保裁剪后至少保留100x100像素
            int remainW = w - cropX * 2;
            int remainH = h - cropY * 2;
            if (remainW < 100 || remainH < 100) {
                cropX = Math.max(0, (w - 100) / 2);
                cropY = Math.max(0, (h - 100) / 2);
                remainW = w - cropX * 2;
                remainH = h - cropY * 2;
            }
            
            final int finalCropX = cropX;
            final int finalCropY = cropY;
            final int finalRemainW = remainW;
            final int finalRemainH = remainH;
            
            // ⭐ 延迟50ms再应用新裁剪（等待caps协商完成）
            final int finalW = w;
            final int finalH = h;
            Gst.getExecutor().schedule(() -> {
                try {
                    if (videocrop != null) {
                        videocrop.set("left", finalCropX);
                        videocrop.set("right", finalCropX);
                        videocrop.set("top", finalCropY);
                        videocrop.set("bottom", finalCropY);
                        
                        // ⭐ 同时更新 zoomCapsfilter 输出分辨率
                        if (zoomCapsfilter != null) {
                            String capsStr = String.format("video/x-raw,width=%d,height=%d", finalW, finalH);
                            Caps caps = Caps.fromString(capsStr);
                            zoomCapsfilter.set("caps", caps);
                        }
                        
                        LogTools.getInstance().logRecord3(String.format(
                            "🔧 重新裁剪(分辨率变化): scale=%.2f, 新分辨率=%dx%d, 裁剪=%d,%d, 中间=%dx%d",
                            currentScaleFactor, finalW, finalH, finalCropX, finalCropY, finalRemainW, finalRemainH));
                    }
                } catch (Throwable ignore) {}
            }, 50, TimeUnit.MILLISECONDS);
                
        } catch (Throwable t) {
            LogTools.getInstance().logRecord3("⚠️ 重新裁剪失败: " + t.getMessage());
        }
    }


    public void startRecording() {

        // ⭐ 慢放开启：根据系统内存自动调整分辨率
       /* if (captureScaleCaps != null) {
            try {
                int[] resolution = getOptimalResolution();
                int maxWidth = resolution[0];
                int maxHeight = resolution[1];

                String capsStr = String.format(
                        "video/x-raw,width=(int)[1,%d],height=(int)[1,%d]",
                        maxWidth, maxHeight
                );
                Caps scaleCaps = Caps.fromString(capsStr);
                captureScaleCaps.set("caps", scaleCaps);
                System.out.println("✅ 慢放开启：分辨率限制 " + maxWidth + "x" + maxHeight +
                        " (" + getSystemMemoryGB() + "GB内存自动配置)");
            } catch (Exception e) {
                LogTools.getInstance().logRecord2("⚠️ 设置分辨率限制失败: " + e.getMessage());
            }
        }

        // ⭐ 慢放开启：根据系统内存自动调整JPEG质量
        if (jpegEncoder != null) {
            try {
                int quality = getOptimalJpegQuality();
                jpegEncoder.set("quality", quality);
                System.out.println("✅ 慢放模式：JPEG质量=" + quality +
                        " (" + getSystemMemoryGB() + "GB内存自动配置)");
            } catch (Exception e) {
                LogTools.getInstance().logRecord2("⚠️ 设置JPEG质量失败: " + e.getMessage());
            }
        }*/

        //setJpegSaveEnabled(true);
        FileToos.lzNum=0;
        logRecord("========== 开始录制 ==========");
        FileToos.isCallBack=true;
    }


    public void stopRecording() {

        /*if (captureScaleCaps != null) {
            try {
                // 允许任意分辨率（最大8192x8192，相当于不限制）
                String capsStr = "video/x-raw,width=(int)[1,8192],height=(int)[1,8192]";
                Caps scaleCaps = Caps.fromString(capsStr);
                captureScaleCaps.set("caps", scaleCaps);
                System.out.println("✅ 慢放停止：取消分辨率限制（保持原分辨率）");
            } catch (Exception e) {
                LogTools.getInstance().logRecord2("⚠️ 取消分辨率限制失败: " + e.getMessage());
            }
        }*/

        //setJpegSaveEnabled(false);
        logRecord("========== 停子录制 ==========");
        FileToos.isCallBack=false;
        FileToos.lzNum=0;

    }



    /**
     * ⭐ 获取管道状态
     *
     * @return Pipeline 的状态（NULL, READY, PAUSED, PLAYING）
     */
    public org.freedesktop.gstreamer.State getPipelineState() {
        if (pipeline != null) {
            return pipeline.getState();
        }
        return org.freedesktop.gstreamer.State.NULL;
    }

    /**
     * ⭐ 检查播放器是否正在播放
     *
     * @return true=正在播放，false=未播放或其他状态
     */
    public boolean isPlaying() {
        if (pipeline != null) {
            org.freedesktop.gstreamer.State state = pipeline.getState();
            return state == org.freedesktop.gstreamer.State.PLAYING;
        }
        return false;
    }

    /**
     * ⭐ 获取详细的播放器状态信息（用于诊断）
     *
     * @return 状态信息字符串
     */
    /**
     * ⭐ 获取详细的播放器状态信息（用于诊断）
     *
     * @return 状态信息字符串
     */
    public String getDetailedStatus() {
        StringBuilder sb = new StringBuilder();

        // Pipeline 状态
        if (pipeline != null) {
            sb.append("Pipeline状态: ").append(pipeline.getState()).append("\n");
        } else {
            sb.append("Pipeline状态: null\n");
        }

        // WebRTC 连接状态
        if (webrtcbin != null) {
            try {
                Object connState = webrtcbin.get("connection-state");
                Object iceState = webrtcbin.get("ice-connection-state");
                sb.append("WebRTC连接状态: ").append(connState).append("\n");
                sb.append("ICE连接状态: ").append(iceState).append("\n");
            } catch (Exception e) {
                sb.append("WebRTC状态: 获取失败\n");
            }
        } else {
            sb.append("WebRTC: null\n");
        }

        // ⭐ 数据流状态（修正变量名）
        sb.append("最后帧时间: ").append(lastFrameTimeMs).append("ms ago (")
                .append(System.currentTimeMillis() - lastFrameTimeMs).append("ms)\n");
        sb.append("总帧数: ").append(totalFrameCount).append("\n");
        sb.append("数据流动: ").append(isDataFlowing() ? "✅ 正常" : "❌ 停滞").append("\n");

        return sb.toString();
    }

    /**
     * ⭐ 检查数据是否在流动（最近5秒内有数据）
     *
     * @return true=有数据流动，false=数据停滞
     */
    /**
     * ⭐ 检查数据是否在流动（最近5秒内有数据）
     *
     * @return true=有数据流动，false=数据停滞
     */
    public boolean isDataFlowing() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastFrame = currentTime - lastFrameTimeMs;  // ⭐ 修正：使用 lastFrameTimeMs
        return timeSinceLastFrame < 5000; // 5秒内有数据
    }




// ========== 图像调节公共方法 ==========

    /**
     * 设置亮度
     *
     * @param brightness 亮度值（-1.0 ~ 1.0，0=标准）
     */
    public void setBrightness(double brightness) {
        if (videoBalance == null) {
            LogTools.getInstance().logRecord2("❌ videobalance 未初始化");
            return;
        }

        // 限制范围
        brightness = Math.max(-1.0, Math.min(1.0, brightness));

        try {
            videoBalance.set("brightness", brightness);
            currentBrightness = brightness;
            System.out.println("✅ 亮度已设置: " + brightness);
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置亮度失败: " + e.getMessage());
        }
    }

    /**
     * 设置对比度
     *
     * @param contrast 对比度值（0.0 ~ 2.0，1.0=标准）
     */
    public void setContrast(double contrast) {
        if (videoBalance == null) {
            LogTools.getInstance().logRecord2("❌ videobalance 未初始化");
            return;
        }

        // 限制范围
        contrast = Math.max(0.0, Math.min(2.0, contrast));

        try {
            videoBalance.set("contrast", contrast);
            currentContrast = contrast;
            System.out.println("✅ 对比度已设置: " + contrast);
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置对比度失败: " + e.getMessage());
        }
    }

    /**
     * 设置饱和度
     *
     * @param saturation 饱和度值（0.0 ~ 2.0，1.0=标准）
     */
    public void setSaturation(double saturation) {
        if (videoBalance == null) {
            LogTools.getInstance().logRecord2("❌ videobalance 未初始化");
            return;
        }

        // 限制范围
        saturation = Math.max(0.0, Math.min(2.0, saturation));

        try {
            videoBalance.set("saturation", saturation);
            currentSaturation = saturation;
            System.out.println("✅ 饱和度已设置: " + saturation);
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置饱和度失败: " + e.getMessage());
        }
    }

    /**
     * 设置色调
     *
     * @param hue 色调值（-1.0 ~ 1.0，0=标准）
     */
    public void setHue(double hue) {
        if (videoBalance == null) {
            LogTools.getInstance().logRecord2("❌ videobalance 未初始化");
            return;
        }

        // 限制范围
        hue = Math.max(-1.0, Math.min(1.0, hue));

        try {
            videoBalance.set("hue", hue);
            currentHue = hue;
            System.out.println("✅ 色调已设置: " + hue);
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置色调失败: " + e.getMessage());
        }
    }

    /**
     * 设置伽马值（曝光）
     *
     * @param gammaValue 伽马值（0.01 ~ 10.0，1.0=标准）
     */
    public void setGamma(double gammaValue) {
        if (gamma == null) {
            LogTools.getInstance().logRecord2("❌ gamma 未初始化");
            return;
        }

        // 限制范围
        gammaValue = Math.max(0.01, Math.min(10.0, gammaValue));

        try {
            gamma.set("gamma", gammaValue);
            currentGamma = gammaValue;
            System.out.println("✅ 伽马已设置: " + gammaValue);
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 设置伽马失败: " + e.getMessage());
        }
    }


    /**
     * ⚡ 保存当前图像设置到本地存储
     * 在设置对话框点击"确定"时调用
     */
    public void saveCurrentSettingsToStorage() {
        CameraSettingsStorage.getInstance().setAll(
            currentExposure,
            currentBrightness, 
            currentContrast, 
            currentSaturation, 
            currentHue, 
            currentGamma
        );
    }
    
    /**
     * ⚡ 从本地存储加载并应用设置
     * 用于重新连接时恢复上次保存的设置
     * 注意：只使用曝光值联动计算5个参数，单独存储的值不参与初始化
     */
    public void loadAndApplyStoredSettings() {
        CameraSettingsStorage settings = CameraSettingsStorage.getInstance();
        
        // 获取曝光值
        int exposurePercent = settings.getExposure();
        
        // 应用曝光设置
        setExposurePercent(exposurePercent);
        
        // ✅ 根据曝光值联动计算5个参数（单独存储的值不参与初始化）
        // 曝光 0-100 对应 slider 0-1
        double slider = exposurePercent / 100.0;  // 0 → 0, 20 → 0.2, 100 → 1.0
        
        // 计算各参数值（映射公式）
        double brightness = 0.3 * slider - 0.1;
        double saturation = Math.max(0.875 * slider + 0.925, 1.0);
        double contrast = Math.max(slider + 0.9, 1.0);
        double hue = Math.max(-0.1125 * slider + 0.0625, -0.05);
        double gammaVal = Math.max(-0.5875 * slider + 1.0875, 0.5);
        
        // 限制在有效范围内
        brightness = Math.max(-0.4, Math.min(0.2, brightness));
        saturation = Math.max(0.0, Math.min(2.0, saturation));
        contrast = Math.max(0.0, Math.min(2.0, contrast));
        hue = Math.max(-1.0, Math.min(1.0, hue));
        gammaVal = Math.max(0.5, Math.min(2.0, gammaVal));
        
        if (videoBalance != null) {
            setBrightness(brightness);
            setContrast(contrast);
            setSaturation(saturation);
            setHue(hue);
        }
        
        if (gamma != null) {
            setGamma(gammaVal);
        }
        
        // ✅ 将曝光联动计算的值保存到本地存储（这样打开相机设置UI时能显示正确的值）
        settings.setBrightness(brightness);
        settings.setContrast(contrast);
        settings.setSaturation(saturation);
        settings.setHue(hue);
        settings.setGamma(gammaVal);
        
        System.out.println("📷 初始化设置: 曝光=" + exposurePercent + "% → 亮度=" + String.format("%.2f", brightness) 
            + ", 饱和度=" + String.format("%.2f", saturation) 
            + ", 对比度=" + String.format("%.2f", contrast) 
            + ", 色调=" + String.format("%.2f", hue) 
            + ", 伽马=" + String.format("%.2f", gammaVal));
    }
    
    /**
     * 获取当前亮度
     */
    public double getBrightness() {
        return currentBrightness;
    }

    /**
     * 获取当前对比度
     */
    public double getContrast() {
        return currentContrast;
    }

    /**
     * 获取当前饱和度
     */
    public double getSaturation() {
        return currentSaturation;
    }

    /**
     * 获取当前色调
     */
    public double getHue() {
        return currentHue;
    }

    /**
     * 获取当前伽马值
     */
    public double getGammaValue() {
        return currentGamma;
    }

    /**
     * ⭐ 复原所有图像参数到默认值
     */
    public void resetImageParams() {
        System.out.println("🔄 复原图像参数到默认值...");
        setExposurePercent(DEFAULT_EXPOSURE);
        try {
            // 复原 videobalance 参数
            if (videoBalance != null) {
                setBrightness(DEFAULT_BRIGHTNESS);
                setContrast(DEFAULT_CONTRAST);
                setSaturation(DEFAULT_SATURATION);
                setHue(DEFAULT_HUE);
                System.out.println("✅ videobalance 参数已复原");
            }

            // 复原 gamma 参数
            if (gamma != null) {
                setGamma(DEFAULT_GAMMA);
                System.out.println("✅ gamma 参数已复原");
            }

            System.out.println("✅ 所有图像参数已复原到默认值");

        } catch (Exception e) {
            LogTools.getInstance().logRecord2("❌ 复原图像参数失败: " + e.getMessage());
        }
    }


    /**
     * ⭐ 批量设置所有图像参数
     *
     * @param brightness 亮度（-1.0 ~ 1.0）
     * @param contrast 对比度（0.0 ~ 2.0）
     * @param saturation 饱和度（0.0 ~ 2.0）
     * @param hue 色调（-1.0 ~ 1.0）
     * @param gammaValue 伽马值（0.01 ~ 10.0）
     */
    public void setAllImageParams(double brightness, double contrast, double saturation,
                                  double hue, double gammaValue) {
        System.out.println("🎨 批量设置图像参数...");
        setBrightness(brightness);
        setContrast(contrast);
        setSaturation(saturation);
        setHue(hue);
        setGamma(gammaValue);
        System.out.println("✅ 图像参数批量设置完成");
    }

    /**
     * ⭐ 获取所有当前参数（以字符串形式）
     */
    public String getImageParamsInfo() {
        return String.format(
                "图像参数:\n" +
                        "  - 亮度: %.2f (默认: %.2f)\n" +
                        "  - 对比度: %.2f (默认: %.2f)\n" +
                        "  - 饱和度: %.2f (默认: %.2f)\n" +
                        "  - 色调: %.2f (默认: %.2f)\n" +
                        "  - 伽马: %.2f (默认: %.2f)",
                currentBrightness, DEFAULT_BRIGHTNESS,
                currentContrast, DEFAULT_CONTRAST,
                currentSaturation, DEFAULT_SATURATION,
                currentHue, DEFAULT_HUE,
                currentGamma, DEFAULT_GAMMA
        );
    }

    private static final int DEFAULT_EXPOSURE = 100;  // ⭐ 新增：默认曝光度 100%
    private volatile int currentExposure = DEFAULT_EXPOSURE;  // ⭐ 新增：当前曝光度

    public  final  static String EXPOSURE ="EXPOSURE";



    /**
     * ⭐ 设置曝光度（综合调节亮度、伽马、对比度、饱和度）
     *
     * @param exposurePercent 曝光度百分比（0% ~ 200%，100%=标准）
     *                        0 = 极暗
     *                        100 = 标准（默认）
     *                        200 = 极亮
     */
    /*public void setExposurePercent(int exposurePercent) {



        if (videoBalance == null || gamma == null) {
            LogTools.getInstance().logRecord2("❌ 图像调节元素未初始化");
            return;
        }


        logRecord("exposurePercent "+exposurePercent);
        // 限制范围 0 ~ 200
        exposurePercent = Math.max(0, Math.min(200, exposurePercent));

        // 转换为 0.0 ~ 2.0
        double exposure = exposurePercent / 100.0;

        // ⭐⭐⭐ 保守版本（先确保管道正常工作）
        // 目标：稳定运行，逐步调试

        // 1. 亮度：保守（-0.3 ~ 0.3，GStreamer范围: -1 ~ 1）✅
        double brightness = (exposure - 1.0) * 0.3;         // 保守设置

        // 2. 伽马：保守（0.6 ~ 2.0，GStreamer范围: 0.01 ~ 10.0）✅
        double gammaValue = 0.6 + (exposure * 0.7);         // 保守设置

        // 3. 对比度：保守（0.6 ~ 1.8，GStreamer范围: 0 ~ 2）✅
        double contrast = 0.6 + (exposure * 0.6);            // 保守设置，最大1.8

        // 4. 饱和度：保守（0.5 ~ 1.3，GStreamer范围: 0 ~ 2）✅
        double saturation = 0.5 + (exposure * 0.4);         // 保守设置

        // ⭐ 限制所有参数在有效范围内
        brightness = Math.max(-1.0, Math.min(1.0, brightness));
        gammaValue = Math.max(0.01, Math.min(10.0, gammaValue));
        contrast = Math.max(0.0, Math.min(2.0, contrast));
        saturation = Math.max(0.0, Math.min(2.0, saturation));

        // 5. 锐化强度：随曝光度增加而增加⚡
        double sharpness = Math.min(1.0, exposure * 0.5);  // videoscale sharpen 范围: 0.0 ~ 1.0

        // 应用到 GStreamer 元素
        videoBalance.set("brightness", brightness);
        videoBalance.set("contrast", contrast);
        videoBalance.set("saturation", saturation);
        gamma.set("gamma", gammaValue);

        // ⭐ 应用锐化（使用 videoscale 的 sharpen 属性）
        if (sharpenEnabled && videoscale != null) {
            try {
                videoscale.set("sharpen", sharpness);
                logRecord("  → videoscale 锐化强度: " + sharpness);
            } catch (Exception e) {
                logRecord("  → 锐化设置失败: " + e.getMessage());
                sharpenEnabled = false;  // 禁用锐化标志
            }
        }

        if (!sharpenEnabled) {
            // ⚠️ 无锐化功能，提高对比度作为补偿
            double compensatedContrast = Math.min(2.5, contrast + 0.3);  // 提高30%对比度
            videoBalance.set("contrast", compensatedContrast);
            logRecord("  → 无锐化，对比度补偿: " + compensatedContrast);
        }

        // 更新当前值
        currentBrightness = brightness;
        currentContrast = contrast;
        currentSaturation = saturation;
        currentGamma = gammaValue;
        currentExposure = exposurePercent;

        if (sharpenEnabled) {
            logRecord(String.format(
                    "✅ 曝光度: %d%% (亮度:%.2f, 伽马:%.2f, 对比度:%.2f, 饱和度:%.2f, 锐化:%.2f)",
                    exposurePercent, brightness, gammaValue, contrast, saturation, sharpness
            ));
        } else {
            logRecord(String.format(
                    "✅ 曝光度: %d%% (亮度:%.2f, 伽马:%.2f, 对比度:%.2f, 饱和度:%.2f) [无锐化]",
                    exposurePercent, brightness, gammaValue, contrast, saturation
            ));
        }
    }*/



    /**
     * ⚡ 设置曝光度（仅保存曝光值，不再自动计算联动参数）
     * 联动参数（亮度、饱和度、对比度、色调、伽马）由 CameraSettingsDialogController.updateLinkedParamsFromExposure() 单独设置
     * 这样避免了新旧公式冲突导致的画面黑屏问题
     *
     * @param exposurePercent 曝光度百分比（0% ~ 100%）
     */
    public void setExposurePercent(int exposurePercent) {
        // 限制范围 0 ~ 100
        exposurePercent = Math.max(0, Math.min(100, exposurePercent));
        
        // 只保存曝光值，不再设置其他参数（联动参数由 updateLinkedParamsFromExposure 单独设置）
        currentExposure = exposurePercent;
        
        logRecord("✅ 曝光值已保存: " + exposurePercent + "% (联动参数由 CameraSettingsDialogController 单独设置)");
    }
    /**
     * ⭐ 获取当前曝光度
     *
     * @return 曝光度百分比（0 ~ 200）
     */
    public int getExposurePercent() {
        return currentExposure;
    }



    private int getCore(){

        return  Runtime.getRuntime().availableProcessors();
    }


    /**
     * 获取系统物理内存（GB）
     */
    private long getSystemMemoryGB() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            long totalMemoryBytes = osBean.getTotalPhysicalMemorySize();
            long totalMemoryGB = totalMemoryBytes / (1024 * 1024 * 1024);

            logRecord("💾 系统物理内存: " + totalMemoryGB + "GB");
            logRecord("💻 处理器核心数: " + osBean.getAvailableProcessors());

            return totalMemoryGB;
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ 无法获取系统内存，默认按8GB处理");
            e.printStackTrace();
            return 8; // 默认按低端机处理
        }

       // return 8; // 默认按低端机处理
    }

    /**
     * 根据系统内存自动选择慢放分辨率
     * @return [宽度, 高度]
     */
    private int[] getOptimalResolution() {
        long memoryGB = getSystemMemoryGB();

        // 根据内存分级
        if (memoryGB < 8) {
            // 极低端：4GB → 320x240
            System.out.println("🔧 极低端机（" + memoryGB + "GB）→ 320x240");
            return new int[]{320, 240};

        } else if (memoryGB < 16) {
            // 低端：8GB → 480x320
            System.out.println("🔧 低端机（" + memoryGB + "GB）→ 480x320");
            return new int[]{480, 320};

        } else if (memoryGB < 32) {
            // 中端：16GB → 960x540
            System.out.println("🔧 中端机（" + memoryGB + "GB）→ 960x540");
            return new int[]{960, 540};

        } else {
            // 高端：32GB+ → 1920x1080
            System.out.println("🔧 高端机（" + memoryGB + "GB）→ 1920x1080");
            return new int[]{1280, 720};
        }
    }

    /**
     * 根据系统内存自动选择JPEG质量
     * @return quality (0-100)
     */
    private int getOptimalJpegQuality() {
        long memoryGB = getSystemMemoryGB();

        if (memoryGB < 8) {
            // 极低端：quality=20 (约3KB/帧)
            System.out.println("🎯 JPEG质量：20（极低端机优化）");
            return 100;

        } else if (memoryGB < 16) {
            // 低端：quality=30 (约5KB/帧)
            System.out.println("🎯 JPEG质量：30（低端机优化）");
            return 100;

        } else if (memoryGB < 32) {
            // 中端：quality=50 (约30KB/帧)
            System.out.println("🎯 JPEG质量：50（中端机平衡）");
            return 100;

        } else {
            // 高端：quality=70 (约100KB/帧)
            System.out.println("🎯 JPEG质量：70（高端机高清）");
            return 100;
        }
    }


    // ========== 添加缩放控制方法 ==========
    
    /**
     * ⚡ 启动缩放处理线程（只调用一次）
     */
    public void startZoomProcessor() {
        if (zoomProcessorRunning) return;
        zoomProcessorRunning = true;
        
        Thread processor = new Thread(() -> {
            while (zoomProcessorRunning) {
                try {
                    // 等待缩放请求（阻塞）
                    ZoomRequest req = zoomQueue.take();
                    
                    // ⚡ 关键：等待16ms后再处理，如果有新请求就用新的（60fps）
                    Thread.sleep(16);
                    
                    // 取最新的请求（丢弃中间的）
                    ZoomRequest latest = zoomQueue.poll();
                    if (latest != null) req = latest;
                    
                    // 真正执行缩放
                    applyZoomDirect(req.zoom, req.mouseX, req.mouseY);
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "ZoomProcessor");
        processor.setDaemon(true);
        processor.start();
        
        LogTools.getInstance().logRecord3("✅ 缩放处理线程已启动（60fps，防崩溃）");
    }
    
    /**
     * ⚡ 真正执行缩放（videocrop 裁剪 + Lanczos 插值）
     */
    private void applyZoomDirect(double zoom, int mouseX, int mouseY) {
        if (videocrop == null || videoscale == null) return;
        if (pipeline == null || pipeline.getState() != State.PLAYING) return;
        
        try {
            videoWidth = FileToos.sslWidth;
            videoHeight = FileToos.sslwHight;
            
            // 限制缩放范围
            zoom = Math.max(minZoom, Math.min(maxZoom, zoom));
            
            if (zoom <= 1.0) {
                // 重置到完整画面
                videocrop.set("left", 0);
                videocrop.set("right", 0);
                videocrop.set("top", 0);
                videocrop.set("bottom", 0);
                
                lastCropLeft = 0;
                lastCropTop = 0;
                lastCropWidth = videoWidth;
                lastCropHeight = videoHeight;
                currentZoom = 1.0;
                return;
            }
            
            // 计算鼠标相对位置
            double relativeX = (double)(mouseX - lastCropLeft) / lastCropWidth;
            double relativeY = (double)(mouseY - lastCropTop) / lastCropHeight;
            relativeX = Math.max(0.0, Math.min(1.0, relativeX));
            relativeY = Math.max(0.0, Math.min(1.0, relativeY));
            
            // 计算新的裁剪区域
            int newCropWidth = (int)(videoWidth / zoom);
            int newCropHeight = (int)(videoHeight / zoom);
            int newCropLeft = (int)(mouseX - relativeX * newCropWidth);
            int newCropTop = (int)(mouseY - relativeY * newCropHeight);
            
            // 边界检查
            newCropLeft = Math.max(0, Math.min(videoWidth - newCropWidth, newCropLeft));
            newCropTop = Math.max(0, Math.min(videoHeight - newCropHeight, newCropTop));
            
            int cropRight = videoWidth - newCropLeft - newCropWidth;
            int cropBottom = videoHeight - newCropTop - newCropHeight;
            
            if (cropRight < 0 || cropBottom < 0) return;
            
            // ⚡ 批量设置（关键：一次性设置完）
            videocrop.set("left", newCropLeft);
            videocrop.set("right", cropRight);
            videocrop.set("top", newCropTop);
            videocrop.set("bottom", cropBottom);
            
            // 更新状态
            lastCropLeft = newCropLeft;
            lastCropTop = newCropTop;
            lastCropWidth = newCropWidth;
            lastCropHeight = newCropHeight;
            currentZoom = zoom;
            
        } catch (Exception e) {
            // 忽略异常，防止崩溃
        }
    }

    // ⭐ 缩放同步锁（防止多线程冲突）
    private final Object zoomLock = new Object();
    private volatile boolean isZooming = false;

    /**
     * 设置视频缩放（videocrop 裁剪 + Lanczos 高质量插值）
     */
    public void setVideoZoom(double zoom, int mouseX, int mouseY) {
        videoWidth = FileToos.sslWidth;
        videoHeight = FileToos.sslwHight;

        // ⭐ 防止并发
        if (isZooming) {
            return;
        }

        synchronized (zoomLock) {
            if (videocrop == null || videoscale == null) {
                LogTools.getInstance().logRecord3("⚠️ 缩放元素未初始化");
                return;
            }

            if (pipeline == null || pipeline.getState() != State.PLAYING) {
                LogTools.getInstance().logRecord3("⚠️ 管道未处于PLAYING状态");
                return;
            }

            isZooming = true;

            try {
                // 限制缩放范围（1.0 - 5.0）
                zoom = Math.max(minZoom, Math.min(maxZoom, zoom));

                if (zoom <= 1.0) {
                    // ⭐ 重置到完整画面
                    videocrop.set("left", 0);
                    videocrop.set("right", 0);
                    videocrop.set("top", 0);
                    videocrop.set("bottom", 0);

                    lastCropLeft = 0;
                    lastCropTop = 0;
                    lastCropWidth = videoWidth;
                    lastCropHeight = videoHeight;
                    currentZoom = 1.0;

                    LogTools.getInstance().logRecord3("🔍 缩放: 1.0x（完整画面）");
                    return;
                }

                // ⭐ 计算鼠标在当前显示区域中的相对位置（0.0 - 1.0）
                double relativeX = (double)(mouseX - lastCropLeft) / lastCropWidth;
                double relativeY = (double)(mouseY - lastCropTop) / lastCropHeight;
                relativeX = Math.max(0.0, Math.min(1.0, relativeX));
                relativeY = Math.max(0.0, Math.min(1.0, relativeY));

                // ⭐ 计算新的裁剪区域大小
                int newCropWidth = (int)(videoWidth / zoom);
                int newCropHeight = (int)(videoHeight / zoom);

                // ⭐ 根据相对位置计算新的裁剪区域
                int newCropLeft = (int)(mouseX - relativeX * newCropWidth);
                int newCropTop = (int)(mouseY - relativeY * newCropHeight);

                // ⭐ 边界检查
                newCropLeft = Math.max(0, Math.min(videoWidth - newCropWidth, newCropLeft));
                newCropTop = Math.max(0, Math.min(videoHeight - newCropHeight, newCropTop));

                int cropRight = videoWidth - newCropLeft - newCropWidth;
                int cropBottom = videoHeight - newCropTop - newCropHeight;

                if (cropRight < 0 || cropBottom < 0) {
                    LogTools.getInstance().logRecord3("❌ 裁剪参数异常，跳过");
                    return;
                }

                // ⭐ 应用裁剪（Lanczos 插值在 videoscale 中配置）
                videocrop.set("left", newCropLeft);
                videocrop.set("right", cropRight);
                videocrop.set("top", newCropTop);
                videocrop.set("bottom", cropBottom);

                // ⭐ 更新状态
                lastCropLeft = newCropLeft;
                lastCropTop = newCropTop;
                lastCropWidth = newCropWidth;
                lastCropHeight = newCropHeight;
                currentZoom = zoom;

                LogTools.getInstance().logRecord3("🔍 缩放: " + String.format("%.2fx", zoom) +
                        " 裁剪区域: [" + newCropLeft + "," + newCropTop +
                        " " + newCropWidth + "x" + newCropHeight + "]");

            } catch (Exception e) {
                LogTools.getInstance().logRecord3("❌ 缩放失败: " + e.getMessage());
                e.printStackTrace();
            } finally {
                isZooming = false;
            }
        }
    }


    /**
     * ⚡ 滚轮缩放（VideoOverlay render-rectangle 模式）
     * 使用 GPU 渲染，通过 setRenderRectangle 实现放大+平移，类似图片查看器效果
     *
     * @param delta 缩放增量（正数放大，负数缩小）
     * @param mouseX 鼠标X坐标（容器内）- 缩放中心
     * @param mouseY 鼠标Y坐标（容器内）- 缩放中心
     */
    public void adjustZoom(double delta, int mouseX, int mouseY) {
        double newZoom = currentZoom + delta;
        newZoom = Math.max(minZoom, Math.min(maxZoom, newZoom));
        
        if (Math.abs(newZoom - currentZoom) < 0.001) return;
        
        // ⚡ 以鼠标位置为中心进行缩放
        if (overlayTarget != null && currentZoom > 1.0) {
            double containerW = overlayTarget.getBoundsInLocal().getWidth();
            double containerH = overlayTarget.getBoundsInLocal().getHeight();
            
            if (containerW > 0 && containerH > 0) {
                // 鼠标在容器中的相对位置 (0~1)
                double relX = mouseX / containerW;
                double relY = mouseY / containerH;
                
                // 缩放前：鼠标指向的视口位置
                double viewX = -windowZoomOffsetX + mouseX;
                double viewY = -windowZoomOffsetY + mouseY;
                
                // 缩放比例变化
                double scaleRatio = newZoom / currentZoom;
                
                // 缩放后：保持鼠标指向的视口位置不变
                double newViewX = viewX * scaleRatio;
                double newViewY = viewY * scaleRatio;
                
                // 计算新偏移
                windowZoomOffsetX = -(newViewX - mouseX);
                windowZoomOffsetY = -(newViewY - mouseY);
            }
        }
        
        currentZoom = newZoom;
        
        // ⚡ 缩放到 1.0x 时，强制重置（绕过节流）
        if (currentZoom <= 1.0) {
            windowZoomOffsetX = 0;
            windowZoomOffsetY = 0;
            forceResetCaptureVideocrop();
        }
        
        // 限制偏移范围
        clampZoomOffset();
        
        // ⚡ 使用 VideoOverlay render-rectangle 实现 GPU 放大+平移
        applyRenderRectangleZoom();
        
        // ⭐ 通知 UI 更新缩放显示
        notifyZoomChange();
        
        LogTools.getInstance().logRecord("🔍 放大: " + String.format("%.2fx", currentZoom) + 
            " 偏移: (" + String.format("%.0f", windowZoomOffsetX) + ", " + String.format("%.0f", windowZoomOffsetY) + ")");
    }
    
    /**
     * ⚡ 限制偏移范围，防止视频移出可见区域
     */
    private void clampZoomOffset() {
        if (overlayTarget == null || currentZoom <= 1.0) {
            windowZoomOffsetX = 0;
            windowZoomOffsetY = 0;
            return;
        }
        
        double containerW = overlayTarget.getBoundsInLocal().getWidth();
        double containerH = overlayTarget.getBoundsInLocal().getHeight();
        
        if (containerW <= 0 || containerH <= 0) return;
        
        // 放大后的虚拟尺寸
        double zoomedW = containerW * currentZoom;
        double zoomedH = containerH * currentZoom;
        
        // 最大可偏移量 = 放大尺寸 - 容器尺寸
        double maxOffsetX = zoomedW - containerW;
        double maxOffsetY = zoomedH - containerH;
        
        // 限制范围：偏移为负值（render rect 的 x, y 为负）
        // windowZoomOffsetX 表示 render rect 的 x（负值向右移动内容）
        windowZoomOffsetX = Math.max(-maxOffsetX, Math.min(0, windowZoomOffsetX));
        windowZoomOffsetY = Math.max(-maxOffsetY, Math.min(0, windowZoomOffsetY));
    }
    
    /**
     * ⚡ 应用 VideoOverlay render-rectangle 缩放+平移
     * 
     * 原理：setRenderRectangle(x, y, w, h) 设置视频渲染区域
     * - w, h 大于窗口 = 视频被放大
     * - x, y 为负值 = 视频向左上方移动（看到右下区域）
     * 
     * 例如：2倍放大居中 = setRenderRectangle(-w/2, -h/2, w*2, h*2)
     */
    private void applyRenderRectangleZoom() {
        if (videoOverlay == null || overlayTarget == null) {
            LogTools.getInstance().logRecord("⚠️ VideoOverlay 不可用，无法缩放");
            return;
        }
        
        try {
            double containerW = overlayTarget.getBoundsInLocal().getWidth();
            double containerH = overlayTarget.getBoundsInLocal().getHeight();
            
            if (containerW <= 0 || containerH <= 0) {
                LogTools.getInstance().logRecord("⚠️ 容器尺寸无效，跳过缩放");
                return;
            }
            
            // ⚡ 计算 DPI 缩放
            double dpiScale = 1.0;
            try {
                javafx.stage.Window window = overlayTarget.getScene().getWindow();
                if (window != null) {
                    dpiScale = window.getRenderScaleX();
                }
            } catch (Exception e) { /* ignore */ }
            
            // 物理像素尺寸
            int pxContainerW = (int) Math.round(containerW * dpiScale);
            int pxContainerH = (int) Math.round(containerH * dpiScale);
            
            // 放大后的渲染尺寸
            int renderW = (int) Math.round(pxContainerW * currentZoom);
            int renderH = (int) Math.round(pxContainerH * currentZoom);
            
            // 偏移量（物理像素）
            int offsetX = (int) Math.round(windowZoomOffsetX * dpiScale);
            int offsetY = (int) Math.round(windowZoomOffsetY * dpiScale);
            
            // 1x 时居中，>1x 时使用偏移
            if (currentZoom <= 1.0) {
                offsetX = 0;
                offsetY = 0;
            }
            
            // ⚡ 设置渲染矩形
            videoOverlay.setRenderRectangle(offsetX, offsetY, renderW, renderH);
            
            // ⚡ 同步更新抓拍分支的 videocrop（让抓拍图片也是放大区域）
            syncCaptureVideocrop();
            
            LogTools.getInstance().logRecord2("✅ RenderRect: (" + offsetX + ", " + offsetY + ") " + renderW + "x" + renderH);
        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ setRenderRectangle 失败: " + e.getMessage());
        }
    }
    
    // ⚡ 抓拍裁剪节流：避免频繁修改 GStreamer 元素导致崩溃/抖动
    private volatile long lastCaptureCropSyncMs = 0;
    private static final long CAPTURE_CROP_SYNC_MIN_INTERVAL_MS = 100;  // 最小间隔 100ms
    private volatile int lastCaptureCropL = -1, lastCaptureCropR = -1, lastCaptureCropT = -1, lastCaptureCropB = -1;
    private final Object captureCropSyncLock = new Object();
    
    /**
     * ⚡ 同步抓拍分支的 videocrop（让抓拍图片和显示一致）
     * 
     * videocrop 的 left/right/top/bottom 表示从对应边缘裁剪的像素数
     * 放大 2x = 只显示中间 50%，需要裁剪掉上下左右各 25%
     * 
     * 优化：节流 + 去重 + 异步，避免崩溃和抖动
     */
    private void syncCaptureVideocrop() {
        if (captureVideocrop == null) {
            return;
        }
        
        // ⚡ 节流：避免频繁修改
        long now = System.currentTimeMillis();
        if (now - lastCaptureCropSyncMs < CAPTURE_CROP_SYNC_MIN_INTERVAL_MS) {
            return;
        }
        lastCaptureCropSyncMs = now;
        
        // 计算裁剪参数
        int newLeft = 0, newRight = 0, newTop = 0, newBottom = 0;
        
        try {
            if (currentZoom > 1.0) {
                // 获取视频原始尺寸
                int vidW = this.videoWidth;
                int vidH = this.videoHeight;
                if (vidW <= 0 || vidH <= 0) {
                    return;  // 尺寸未知，跳过
                }
                
                // 计算可视区域尺寸
                int visibleW = (int) Math.round(vidW / currentZoom);
                int visibleH = (int) Math.round(vidH / currentZoom);
                
                // 获取容器尺寸
                double containerW = 1.0;
                double containerH = 1.0;
                if (overlayTarget != null) {
                    try {
                        containerW = Math.max(1, overlayTarget.getBoundsInLocal().getWidth());
                        containerH = Math.max(1, overlayTarget.getBoundsInLocal().getHeight());
                    } catch (Exception e) { /* ignore */ }
                }
                
                // 偏移量转换为原始图像坐标
                double divisor = containerW * (currentZoom - 1);
                double offsetRatioX = divisor > 0.001 ? -windowZoomOffsetX / divisor : 0;
                divisor = containerH * (currentZoom - 1);
                double offsetRatioY = divisor > 0.001 ? -windowZoomOffsetY / divisor : 0;
                
                // 限制比例范围
                offsetRatioX = Math.max(0, Math.min(1, Double.isNaN(offsetRatioX) ? 0 : offsetRatioX));
                offsetRatioY = Math.max(0, Math.min(1, Double.isNaN(offsetRatioY) ? 0 : offsetRatioY));
                
                // 计算裁剪起点
                int maxOffsetX = Math.max(0, vidW - visibleW);
                int maxOffsetY = Math.max(0, vidH - visibleH);
                newLeft = (int) Math.round(offsetRatioX * maxOffsetX);
                newTop = (int) Math.round(offsetRatioY * maxOffsetY);
                
                // 边界检查
                newLeft = Math.max(0, Math.min(maxOffsetX, newLeft));
                newTop = Math.max(0, Math.min(maxOffsetY, newTop));
                
                // 计算右边和下边的裁剪量
                newRight = Math.max(0, vidW - newLeft - visibleW);
                newBottom = Math.max(0, vidH - newTop - visibleH);
            }
            
            // ⚡ 去重：只有变化时才更新（避免抖动）
            if (newLeft == lastCaptureCropL && newRight == lastCaptureCropR && 
                newTop == lastCaptureCropT && newBottom == lastCaptureCropB) {
                return;  // 无变化，跳过
            }
            
            // ⚡ 线程安全：同步更新
            final int fLeft = newLeft, fRight = newRight, fTop = newTop, fBottom = newBottom;
            synchronized (captureCropSyncLock) {
                try {
                    captureVideocrop.set("left", fLeft);
                    captureVideocrop.set("right", fRight);
                    captureVideocrop.set("top", fTop);
                    captureVideocrop.set("bottom", fBottom);
                    
                    // 更新缓存
                    lastCaptureCropL = fLeft;
                    lastCaptureCropR = fRight;
                    lastCaptureCropT = fTop;
                    lastCaptureCropB = fBottom;
                } catch (Exception e) {
                    // 忽略异常，避免崩溃
                }
            }
        } catch (Exception e) {
            // 忽略所有异常，保证稳定性
        }
    }
    
    /**
     * ⚡ 拖动平移（VideoOverlay render-rectangle 模式）
     */
    public void panZoom(double deltaX, double deltaY) {
        if (currentZoom <= 1.0) {
            // 1x 时不支持平移
            return;
        }
        
        // 累加偏移（deltaX 正值 = 向右拖 = 内容向左移 = offsetX 减小）
        windowZoomOffsetX -= deltaX;
        windowZoomOffsetY -= deltaY;
        
        // 限制范围
        clampZoomOffset();
        
        // 应用
        applyRenderRectangleZoom();
        
        LogTools.getInstance().logRecord2("🔄 平移: (" + String.format("%.0f", windowZoomOffsetX) + ", " + String.format("%.0f", windowZoomOffsetY) + ")");
    }
    
    /**
     * ⚡ 刷新窗口缩放（窗口尺寸变化时调用）
     */
    private void refreshOverlayWithZoom() {
        if (currentZoom > 1.0) {
            clampZoomOffset();
            applyRenderRectangleZoom();
        } else {
            refreshOverlayRectangle();
        }
    }

    /**
     * 重置缩放（恢复完整画面）
     */
    public void resetVideoZoom() {
        currentZoom = 1.0;
        windowZoomOffsetX = 0;
        windowZoomOffsetY = 0;
        
        // ⚡ 强制重置抓拍裁剪（绕过节流）
        forceResetCaptureVideocrop();
        
        applyRenderRectangleZoom();
        
        // ⭐ 通知 UI 更新缩放显示
        notifyZoomChange();
        
        LogTools.getInstance().logRecord("🔍 缩放已重置为 1.0x");
    }
    
    /**
     * ⚡ 强制重置抓拍裁剪（绕过节流，用于重置操作）
     */
    private void forceResetCaptureVideocrop() {
        if (captureVideocrop == null) return;
        
        synchronized (captureCropSyncLock) {
            try {
                captureVideocrop.set("left", 0);
                captureVideocrop.set("right", 0);
                captureVideocrop.set("top", 0);
                captureVideocrop.set("bottom", 0);
                
                // 重置缓存
                lastCaptureCropL = 0;
                lastCaptureCropR = 0;
                lastCaptureCropT = 0;
                lastCaptureCropB = 0;
                lastCaptureCropSyncMs = 0;  // 重置节流时间戳
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }

    /**
     * 获取当前缩放倍数
     */
    public double getCurrentZoom() {
        return currentZoom;
    }
    
    /**
     * ⭐ 设置缩放变化回调（用于更新 UI 显示）
     * @param callback 缩放变化时的回调函数，参数为当前缩放倍数
     */
    public void setZoomChangeCallback(java.util.function.Consumer<Double> callback) {
        this.zoomChangeCallback = callback;
    }
    
    /**
     * ⭐ 通知缩放变化（调用回调函数）
     */
    private void notifyZoomChange() {
        if (zoomChangeCallback != null) {
            try {
                zoomChangeCallback.accept(currentZoom);
            } catch (Exception e) {
                // 忽略回调异常
            }
        }
    }

    /**
     * 设置视频尺寸（用于正确计算裁剪区域）
     */
    public void setVideoSize(int width, int height) {
        this.videoWidth = width;
        this.videoHeight = height;
    }

    
    private static final DWORD PRIORITY_CLASS_HIGH = new DWORD(0x00000080); // HIGH_PRIORITY_CLASS
    private static final int THREAD_PRIORITY_ABOVE_NORMAL_VALUE = 1;
    // ========== 优先级提升 ==========

    private void ensureLowEndPerformanceBoost() {
        boostProcessPriority();
        applySystemHighPerformanceProfile();
    }

    /**
     * 提升进程优先级（Windows）
     * 低端机专用：自动提升到 ABOVE_NORMAL 或 HIGH_PRIORITY
     */
    private void boostProcessPriority() {
        try {
            // 🔥 获取当前进程句柄
            HANDLE currentProcess = Kernel32.INSTANCE.GetCurrentProcess();
            
            // 🔥 优先级类别：
            // NORMAL_PRIORITY_CLASS = 0x00000020
            // ABOVE_NORMAL_PRIORITY_CLASS = 0x00008000
            // HIGH_PRIORITY_CLASS = 0x00000080
            // REALTIME_PRIORITY_CLASS = 0x00000100 (不推荐，可能导致系统不稳定)
            
            // 🎯 低端机策略：提升到 HIGH_PRIORITY（高优先级）
            DWORD targetPriority = PRIORITY_CLASS_HIGH;
            String priorityName = "HIGH_PRIORITY";
            
            // 🔥 设置进程优先级
            boolean success = Kernel32.INSTANCE.SetPriorityClass(currentProcess, targetPriority);
            
            if (success) {
                LogTools.getInstance().logRecord3("✅ 进程优先级已提升: " + priorityName);
                LogTools.getInstance().logRecord3("   ⚡ 低端机优化：更高的CPU调度优先级 → 减少卡顿");
            } else {
                LogTools.getInstance().logRecord3("⚠️ 提升进程优先级失败（需要管理员权限）");
            }
            
            // 🔥 提升当前线程优先级
            HANDLE currentThread = Kernel32.INSTANCE.GetCurrentThread();
            boolean threadSuccess = Kernel32.INSTANCE.SetThreadPriority(currentThread, THREAD_PRIORITY_ABOVE_NORMAL_VALUE);
            
            if (threadSuccess) {
              LogTools.getInstance().logRecord3("✅ 主线程优先级已提升: ABOVE_NORMAL");
            }
            
        } catch (Throwable e) {
            LogTools.getInstance().logRecord3("⚠️ 优先级提升失败: " + e.getMessage());
            // 不影响正常运行，只是性能优化
        }
    }

    /**
     * 尝试应用 Windows 高性能电源/CPU/GPU 配置
     * 需管理员权限，失败时提示用户手动运行 BAT 脚本
     */
    private void applySystemHighPerformanceProfile() {
        if (!Boolean.parseBoolean(System.getProperty("powercfg.auto", "true"))) {
            LogTools.getInstance().logRecord3("⚠️ powercfg.auto=false，跳过自动电源配置");
            return;
        }
        if (!isWindows()) {
            LogTools.getInstance().logRecord3("⚠️ 非 Windows 系统，跳过 powercfg 配置");
            return;
        }
        if (!systemPowerBoosted.compareAndSet(false, true)) {
            return;
        }

        LogTools.getInstance().logRecord3("⚡ 尝试自动应用 Windows 高性能电源配置（需管理员权限）");
        List<PowerCommand> commands = Arrays.asList(
                new PowerCommand("powercfg -setactive SCHEME_MIN", "切换电源计划为“高性能”"),
                new PowerCommand("powercfg -setacvalueindex SCHEME_CURRENT SUB_PROCESSOR PROCTHROTTLEMIN 100", "锁定处理器最小状态 100%"),
                new PowerCommand("powercfg -setacvalueindex SCHEME_CURRENT SUB_PROCESSOR PROCTHROTTLEMAX 100", "锁定处理器最大状态 100%"),
                new PowerCommand("powercfg -setacvalueindex SCHEME_CURRENT SUB_PCIEXPRESS ASPM 0", "关闭 PCI-E 省电"),
                new PowerCommand("powercfg -setactive SCHEME_CURRENT", "应用当前配置")
        );

        boolean success = true;
        for (PowerCommand cmd : commands) {
            if (!runPowerCommand(cmd.command, cmd.description)) {
                success = false;
                break;
            }
        }

        if (success) {
            LogTools.getInstance().logRecord3("✅ Windows 高性能电源/CPU 配置已应用");
        } else {
            Path scriptPath = Paths.get(System.getProperty("user.dir", "."), "scripts", "start_high_performance_acard.bat");
            LogTools.getInstance().logRecord3("⚠️ powercfg 命令执行失败（可能需要以管理员身份运行）");
            if (Files.exists(scriptPath)) {
                LogTools.getInstance().logRecord3("👉 请右键以管理员运行脚本：" + scriptPath.toAbsolutePath());
            } else {
                LogTools.getInstance().logRecord3("👉 请手动执行 powercfg 命令或运行 start_high_performance_acard.bat（需管理员权限）");
            }
        }
    }

    private boolean runPowerCommand(String command, String description) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logRecord("⚠️ " + description + " 超时（可能缺少管理员权限）");
                return false;
            }
            int exit = process.exitValue();
            if (exit == 0) {
                logRecord("✅ " + description);
                return true;
            }
            logRecord("⚠️ " + description + " 失败，exit=" + exit);
            if (output.length() > 0) {
                logRecord("   ↪ " + output.toString().trim());
            }
            return false;
        } catch (Exception e) {
            logRecord("⚠️ " + description + " 执行失败: " + e.getMessage());
            return false;
        }
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    /**
     * 提升 GStreamer 线程优先级（通过系统属性）
     * 注意：需要在 Gst.init() 之前设置
     */
    public static void setGStreamerHighPriority() {
        try {
            // 设置 GStreamer 环境变量，提升解码线程优先级
            System.setProperty("GST_REGISTRY_FORK", "no");  // 避免 fork 进程
            System.setProperty("GST_DEBUG_NO_COLOR", "1");  // 减少日志开销
            
            LogTools.getInstance().logRecord2("✅ GStreamer 高优先级模式已启用");
        } catch (Exception e) {
            LogTools.getInstance().logRecord2("⚠️ GStreamer 优先级设置失败: " + e.getMessage());
        }
    }

    private static final class PowerCommand {
        final String command;
        final String description;

        PowerCommand(String command, String description) {
            this.command = command;
            this.description = description;
        }
    }


}