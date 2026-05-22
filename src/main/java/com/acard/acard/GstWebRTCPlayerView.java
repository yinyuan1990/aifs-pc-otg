package com.acard.acard;

import com.acard.acard.storage.SlowmoStore;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.lowlevel.GValueAPI;
import org.freedesktop.gstreamer.lowlevel.GstBufferAPI;
import org.freedesktop.gstreamer.lowlevel.GstEventAPI;
import org.freedesktop.gstreamer.lowlevel.GstStructureAPI;
import org.freedesktop.gstreamer.lowlevel.GstVideoAPI;
import org.freedesktop.gstreamer.lowlevel.GType;
import org.freedesktop.gstreamer.event.Event;
import org.freedesktop.gstreamer.event.EventType;
import org.freedesktop.gstreamer.webrtc.*;
import org.freedesktop.gstreamer.glib.Natives;

import com.sun.jna.Pointer;


import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.List;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Timer;
import java.util.TimerTask;
import com.acard.acard.capture.SnapshotWindowCollector;

import static org.freedesktop.gstreamer.lowlevel.GstEventAPI.GSTEVENT_API;

/** WebRTC 播放 SRS /rtc/v1/play/ ，appsink 拿每一帧（BGRx） */
public class GstWebRTCPlayerView extends StackPane {

    private final Canvas canvas = new Canvas();
    // 慢放覆盖层画布，避免与实时画面互相覆盖
    private final Canvas slowCanvas = new Canvas();
    private final Pipeline pipe = new Pipeline("webrtc-pipe");
    private final WebRTCBin webrtc;
    private final AppSink sink;
    // 独立的慢放捕获 appsink（从转换后分支获取 BGRx 帧）
    private AppSink slowSink;
    private Element slowPreValveMem;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    
    // 帧捕获相关
    private FrameCapturer frameCapturer;

    private AppSink captureSink;
    // 最小化显示管线标志（默认启用）
    private boolean minimalDisplayOnly = true;
    // 录制分支控制阀与分段写盘sink
    private Element capValve;
    private Element slowValveDisk;
    private Element slowPreValveDisk;
    private final FrameSaver frameSaver;
    private final String outputDir = "D:\\zhen";
    
    // 性能监控相关
    private final ScheduledExecutorService performanceMonitor = Executors.newScheduledThreadPool(1);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private volatile long lastNetworkCheckTime = System.currentTimeMillis();
    private volatile int frameProcessingErrors = 0;

    // Cache of last rendered FX Image frame to draw during layout/resize
    private volatile Image lastFrameFx;
    private volatile Image lastSlowFx;

    // 慢放相关：环形缓冲+播放器
    private volatile boolean slowMoCapturing = false;
    private volatile boolean slowMoPlaying = false;
    // 流活动检测
    private volatile long lastFrameTimeMs = 0L;
    private volatile boolean streamActive = false;
    private java.util.function.Consumer<Boolean> onStreamActiveChanged;
    private final java.util.concurrent.ScheduledExecutorService streamMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "StreamMonitor");
        t.setDaemon(true);
        return t;
    });
    private java.util.concurrent.ScheduledFuture<?> streamMonitorFuture;
    private Runnable onSlowMoStreamStopped;
    
    // 关键帧请求相关的元素引用
    private Element depay;
    private Element jb;
    private Element parse;
    // 慢放内存缓冲：限制最大分辨率，质量保持1.0
    private final FrameRingBuffer slowBuffer = new FrameRingBuffer(SlowmoStore.getInstance().getSlowmoFrames(), 1920, 1080, 1.0f);
    // 实时播放内存滑窗：容量120帧，限制最大分辨率，质量保持1.0
    private final FrameRingBuffer realtimeBuffer = new FrameRingBuffer(120, 1280, 720, 1.0f);
    // 节流与并发保护：避免后台JPEG压缩过载影响实时显示
    private volatile boolean realtimePushBusy = false;
    private volatile long lastRealtimePushMs = 0L;
    private SlowMoPlayer slowPlayer = null;
    private final DiskFrameRingBuffer slowDiskBuffer = new DiskFrameRingBuffer(SlowmoStore.getInstance().getSlowmoFrames(), java.nio.file.Paths.get("runtime", "slowmo"));
    // 慢放磁盘写入采用有界队列的单线程执行器，防止任务堆积影响实时
    // 慢放写盘线程：使用无界队列，保证任何帧都不会被丢弃
    private volatile long slowIoDroppedCount = 0L; // 理论上应始终为0（不丢弃）
    private final java.util.concurrent.ThreadPoolExecutor slowIoExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1,
                    0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(64),
                    r -> { Thread t = new Thread(r, "SlowMoDiskIO"); t.setDaemon(true); return t; },
                    (r, ex) -> { slowIoDroppedCount++; }
            );
    // 实时缓冲推送执行器：与慢放磁盘写入解耦合，避免单线程阻塞导致实时窗口增长缓慢
    private final java.util.concurrent.ThreadPoolExecutor realtimePushExecutor =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 2,
                    0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    r -> { Thread t = new Thread(r, "RealtimePush"); t.setDaemon(true); return t; }
            );
    private volatile long realtimePushSubmitted = 0L;
    private volatile long realtimePushCompleted = 0L;
    private volatile long realtimePushErrors = 0L;
    private volatile long lastSlowDiskWriteMs = 0L;
    private volatile long lastSlowMemPushMs = 0L;
    private volatile int slowCaptureIntervalMs = 33; // 约30FPS的采样间隔，避免过载

    private final String host;
    private final int apiPort;
    private final String app;
    private final String stream;


    // 在类的成员变量区域添加
    private Timer pliTimer;
    private boolean firstKeyFrameReceived = false;
    private static int pliRetryCount = 0;
    private static final int MAX_PLI_RETRIES = 10; // 最多重试10次
    private static final int PLI_INTERVAL_MS = 200; // 每200ms一次




    
    // 保存webrtc的src pad用于发送PLI
    private static Pad webrtcSrcPad = null;
    
    // 静态实例引用，用于在静态方法中访问
    private static GstWebRTCPlayerView currentInstance = null;
    
    // 首屏PLI+NAL探针相关字段
    private java.util.concurrent.ScheduledFuture<?> firstScreenPliScheduledFuture = null;
    private volatile boolean hasReceivedSps = false;
    private volatile boolean hasReceivedPps = false; 
    private volatile boolean hasReceivedIdr = false;
    private volatile boolean firstScreenComplete = false;
    private volatile long remoteVideoSsrc = 0L;
    
    // 获取当前实例的静态方法
    private static GstWebRTCPlayerView getCurrentInstance() {
        return currentInstance;
    }

    interface OnNewTransceiver {
        void onNewTransceiver(Element element, Object transceiver);
    }

    // 在pad-added回调中添加（找到处理接收pad的地方）
    // 在pad-added回调中添加（找到处理接收pad的地方）


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

    public GstWebRTCPlayerView(String host, int apiPort, String app, String stream) {
        this.host = host;
        this.apiPort = apiPort;
        this.app = app;
        this.stream = stream;

        // 设置静态实例引用
        currentInstance = this;

        System.err.println("join---->GstWebRTCPlayerView");
        // 🔧 设置 GST_DEBUG 环境变量获取详细调试信息
        try {
            // 在GStreamer初始化之前设置环境变量
            System.setProperty("GST_DEBUG", "rtph264depay:5,h264parse:4");
            // 同时设置环境变量
            ProcessBuilder pb = new ProcessBuilder();
            pb.environment().put("GST_DEBUG", "rtph264depay:5,h264parse:4");
            System.err.println("🔧 GST_DEBUG 已设置: rtph264depay:5,h264parse:4");
        } catch (Exception e) {
            System.err.println("⚠️ 设置 GST_DEBUG 失败: " + e.getMessage());
        }



        // 底层为实时画面，顶层为慢放覆盖层（默认不启用覆盖层，慢放在元素2-2独立播放）
        getChildren().addAll(canvas, slowCanvas);
        // 允许视图在父容器内自由伸缩，避免测量环路
        setMinSize(0, 0);

        // 初始化帧保存器（队列大小为20，控制内存使用）
         frameSaver = new FrameSaver(outputDir, 20);
         frameSaver.start();
         System.out.println("帧保存器已启动，输出目录: " + outputDir);

         // 启动性能监控（每5秒输出一次统计信息）
         startPerformanceMonitoring();
         System.out.println("性能监控已启动，每5秒输出统计信息");

        // webrtcbin
        this.webrtc = (WebRTCBin) ElementFactory.make("webrtcbin", "webrtc");
        // 适度增加端到端缓冲，满足"不丢帧"并减少宏块马赛克
        webrtc.set("latency", 100); // 降低延迟以减少缓冲压力

        // 🔧 关键修复：bundle-policy配置
        // SRS 仅支持 BUNDLE（需要 SDP 内有 a=group:BUNDLE），因此必须使用 max-bundle
        try {
            webrtc.set("bundle-policy", 3); // 3=max-bundle，确保生成 a=group:BUNDLE
            System.err.println("🔧 Bundle Policy: 设置为max-bundle，满足 SRS 的 BUNDLE 要求");
        } catch (Exception e) {
            System.err.println("❌ Bundle Policy设置失败: " + e.getMessage());
        }

        // 设置STUN服务器以确保ICE候选者生成
        webrtc.set("stun-server", "stun://stun.l.google.com:19302");
        // 添加更多STUN服务器以提高连接成功率
        try {
            // 设置多个STUN服务器以提高ICE连接成功率
            webrtc.set("stun-server", "stun://stun.l.google.com:19302");
            // 可以尝试添加备用STUN服务器
            System.err.println("STUN配置: 使用Google STUN服务器");
        } catch (Exception e) {
            System.err.println("STUN配置: ⚠️ STUN服务器配置失败: " + e.getMessage());
        }

        // 网络优化设置
        try {
            // 设置ICE传输策略为all以允许所有类型的候选者
            webrtc.set("ice-transport-policy", 0); // 0=all, 1=relay
            System.err.println("ICE配置: ice-transport-policy=all");
        } catch (Exception e) {
            System.err.println("ICE配置: ⚠️ ICE传输策略设置失败: " + e.getMessage());
        }

        // 网络优化设置（移除只读属性写入，避免GLib/GObject报错）
        // webrtc.set("ice-connection-state", 0); // 重置ICE连接状态
        // webrtc.set("connection-state", 0); // 重置连接状态
        System.err.println("WebRTCBin优化: bundle-policy=max-bundle, 低延迟配置");

        // 添加ICE连接状态监控 - 使用简化的方式避免编译错误
        try {
            // 使用Element的PAD_ADDED监听器作为替代方案，在pad添加时检查ICE状态
            webrtc.connect(new Element.PAD_ADDED() {
                @Override
                public void padAdded(Element element, Pad pad) {
                    try {
                        // 在pad添加时检查ICE连接状态
                        Object iceState = webrtc.get("ice-connection-state");
                        Object connState = webrtc.get("connection-state");
                        System.err.println("🔄 WebRTC Pad添加 - ICE: " + iceState + ", Connection: " + connState);

                        if (iceState != null && iceState.toString().equals("4")) { // FAILED
                            System.err.println("ICE CONNECTION: ❌ ICE连接失败");
                        } else if (iceState != null && iceState.toString().equals("2")) { // CONNECTED
                            System.err.println("ICE CONNECTION: ✅ ICE连接成功");
                        }
                    } catch (Exception e) {
                        System.err.println("ICE CONNECTION: ⚠️ 状态监控错误: " + e.getMessage());
                    }
                }
            });

            System.err.println("ICE监控: ✅ WebRTC状态监控已安装");
        } catch (Exception e) {
            System.err.println("ICE监控: ⚠️ 状态监控安装失败: " + e.getMessage());
            // 继续执行，不让这个错误阻止WebRTC初始化
        }

        // 末端渲染 appsink（拿每一帧）
        sink = (AppSink) ElementFactory.make("appsink", "sink");
        sink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
        // 强化 appsink 行为，确保不会因为同步或队列丢帧导致无画面
        try { sink.set("emit-signals", true); } catch (Throwable ignore) {}
        try { sink.set("sync", false); } catch (Throwable ignore) {}
        try { sink.set("async", false); } catch (Throwable ignore) {}
        try { sink.set("drop", false); } catch (Throwable ignore) {}
        try { sink.set("qos", true); } catch (Throwable ignore) {}
        try { sink.set("max-buffers", 1); } catch (Throwable ignore) {}
        System.out.println("AppSink配置: drop=false, max-buffers=30, QoS=true, 禁用sync/async");

        sink.connect((AppSink.NEW_SAMPLE) e -> {
            System.err.println("STEP8: 📸 appsink NEW_SAMPLE");
            try {
                Sample sample = sink.pullSample();
                if (sample == null) {
                    System.err.println("STEP8: ⚠️ sample=null");
                    return FlowReturn.OK;
                }

                Buffer buffer = sample.getBuffer();
                if (buffer == null) {
                    System.err.println("STEP8: ⚠️ buffer=null");
                    sample.dispose();
                    return FlowReturn.OK;
                }

                Caps caps = sample.getCaps();
                System.err.println("STEP8: 🔍 caps=" + (caps != null ? caps.toString() : "null"));

                Structure s = null;
                int w = -1, h = -1;
                if (caps != null) {
                    try {
                        s = caps.getStructure(0);
                        if (s != null) {
                            w = s.hasField("width") ? s.getInteger("width") : -1;
                            h = s.hasField("height") ? s.getInteger("height") : -1;
                        }
                    } catch (Exception ex) {
                        System.err.println("STEP8: ⚠️ caps parsing error: " + ex.getMessage());
                    }
                }
                System.err.println("STEP8: ✅ sample caps=" + (caps != null ? caps.toString() : "null") + ", size=" + w + "x" + h);

            ByteBuffer bb = buffer.map(false);
            if (bb == null) {
                System.err.println("STEP8: ⚠️ buffer.map() returned null");
                sample.dispose();
                return FlowReturn.OK;
            }

            int totalBytes = bb.remaining();
            System.err.println("STEP8: 🔍 buffer mapped, totalBytes=" + totalBytes + ", w=" + w + ", h=" + h);

            if (w <= 0 || h <= 0) {
                System.err.println("STEP8: ⚠️ invalid dimensions w=" + w + ", h=" + h);
                buffer.unmap();
                sample.dispose();
                return FlowReturn.OK;
            }

            int computedStride = (h > 0) ? (totalBytes / h) : (w * 4);
            if (computedStride < w * 4 || (computedStride % 4) != 0) {
                // 回退到无填充假设，但保留日志以便定位
                System.err.println("SINK: ⚠️ rowStride fallback used. computedStride=" + computedStride + ", expected≥" + (w*4));
                computedStride = w * 4;
            }
            final int rowStride = computedStride;
            final int effectiveW = Math.min(w, rowStride / 4);
            if (effectiveW != w) {
                System.err.println("SINK: ⚠️ width mismatch, capsW=" + w + ", effW=" + effectiveW + ", rowStride=" + rowStride + ", totalBytes=" + totalBytes);
            }

            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] dst = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                int row = y * rowStride;
                int base = y * w;
                for (int x = 0; x < effectiveW; x++) {
                    int b = bb.get(row + x * 4) & 0xFF;
                    int g = bb.get(row + x * 4 + 1) & 0xFF;
                    int r = bb.get(row + x * 4 + 2) & 0xFF;
                    dst[base + x] = (r << 16) | (g << 8) | b;
                }
            }
            buffer.unmap();

            // 使用帧保存器保存图片

            /*
            try {
                frameSaver.saveFrame(img);
            } catch (InterruptedException em) {
                frameProcessingErrors++; // 增加错误计数
                Thread.currentThread().interrupt();
                System.err.println("保存帧时被中断: " + em.getMessage());
            } catch (Exception ex) {
                frameProcessingErrors++; // 增加错误计数
                System.err.println("帧处理异常: " + ex.getMessage());
                ex.printStackTrace();
            }*/

            // 推入实时滑窗缓冲：改为深拷贝并异步执行，添加统计日志用于诊断
            try {
                // 节流：若上一轮后台压缩仍在进行，或100ms内已提交过一次，则跳过本次推送，优先保证实时渲染
                long now = System.currentTimeMillis();
                if (!realtimePushBusy && (now - lastRealtimePushMs) >= 100) {
                    realtimePushBusy = true;
                    realtimePushSubmitted++;
                    realtimePushExecutor.execute(() -> {
                        try {
                            // 在后台线程进行深拷贝，避免阻塞GStreamer回调线程
                            final BufferedImage pushImg = deepCopyRgb(img);
                            realtimeBuffer.push(pushImg);
                            long done = ++realtimePushCompleted;
                            if ((done % 30) == 0) {
                                System.err.println("REALTIME-PUSH: completed=" + done + ", buffer=" + getRealtimeBufferSize());
                            }
                        } catch (Throwable ex) {
                            realtimePushErrors++;
                        } finally {
                            realtimePushBusy = false;
                            lastRealtimePushMs = System.currentTimeMillis();
                        }
                    });
                }
            } catch (Throwable ex) {
                realtimePushErrors++;
            }
            // 慢放采集仍在 slowSink 回调中进行，显示回调仅负责渲染

            Image fxImg = SwingFXUtils.toFXImage(img, null);
            // 记录最新帧时间，用于流活动判断
            long currentTime = System.currentTimeMillis();
            lastFrameTimeMs = currentTime;
            streamActive = true;
            System.err.println("🎬 帧回调: 时间=" + currentTime + ", streamActive=true");
            System.err.println("STEP8: 🎨 About to call Platform.runLater for UI update");
            Platform.runLater(() -> {
                    // 若正在慢放播放，则由慢放播放器驱动绘制，实时帧不覆盖
                    if (slowMoPlaying) return;
                    // 缓存最新帧，并按当前视图尺寸绘制（不修改 Canvas 尺寸），保持比例不变
                    lastFrameFx = fxImg;
                    double viewW = getWidth();
                    double viewH = getHeight();
                    if (viewW <= 0 || viewH <= 0) return;
                    var gc = canvas.getGraphicsContext2D();
                    gc.setImageSmoothing(false);
                    gc.clearRect(0, 0, viewW, viewH);
                    double imgW = lastFrameFx.getWidth();
                    double imgH = lastFrameFx.getHeight();
                    if (imgW <= 0 || imgH <= 0) return;
                    double scale = Math.min(viewW / imgW, viewH / imgH);
                    double drawW = imgW * scale;
                    double drawH = imgH * scale;
                    double x = (viewW - drawW) / 2.0;
                    double y = (viewH - drawH) / 2.0;
                    gc.drawImage(lastFrameFx, x, y, drawW, drawH);
                    System.err.println("STEP8: ✅ UI updated, canvas size=" + viewW + "x" + viewH + ", image=" + imgW + "x" + imgH);
            });
            sample.dispose();
            return FlowReturn.OK;

            } catch (Exception ex) {
                System.err.println("STEP8: ❌ Exception in NEW_SAMPLE callback: " + ex.getMessage());
                ex.printStackTrace();
                return FlowReturn.ERROR;
            }
        });

        // 管线先放 webrtcbin 与 sink，RTP→解码分支在 pad-added 时再挂
        pipe.addMany(webrtc, sink);

        // 监听 on-new-transceiver 信号来获取创建的 transceiver 对象
        // 使用简化的信号连接方式，避免模块访问问题
        System.err.println("🔧 Setting up on-new-transceiver signal handler...");
        // 注意：由于Java模块系统限制，暂时跳过自定义信号处理
        // 在实际应用中，transceiver会在pad-added事件中可用

        // 关键修复：设置 WebRTCBin 到 READY 状态，然后添加 recvonly transceiver
        System.err.println("🔧 Setting WebRTCBin to READY state...");
        webrtc.setState(State.READY);

        // 🔧 关键修复：在webrtcbin创建后立即获取video src pad
        // 这是发送PLI请求的正确方式
        System.err.println("🔧 获取webrtcbin的video src pad用于PLI发送...");
        try {
            // 方法1: 尝试获取webrtcbin的src pad
            webrtcSrcPad = webrtc.getStaticPad("src_%u");
            if (webrtcSrcPad == null) {
                // 方法2: 尝试获取第一个src pad
                webrtcSrcPad = webrtc.getStaticPad("src_0");
            }
            if (webrtcSrcPad == null) {
                // 方法3: 遍历所有pads查找video src pad
                Iterator<Pad> pads = webrtc.getPads().iterator();
                while (pads.hasNext()) {
                    Pad pad = pads.next();
                    String padName = pad.getName();
                    if (padName != null && padName.startsWith("src_") && pad.getDirection() == PadDirection.SRC) {
                        webrtcSrcPad = pad;
                        System.err.println("🔧 ✅ 找到webrtc src pad: " + padName);
                        break;
                    }
                }
            }
            
            if (webrtcSrcPad != null) {
                System.err.println("🔧 ✅ webrtcSrcPad获取成功: " + webrtcSrcPad.getName());
            } else {
                System.err.println("🔧 ⚠️ webrtcSrcPad获取失败，将在pad-added事件中重试");
            }
        } catch (Exception e) {
            System.err.println("🔧 ❌ webrtcSrcPad获取异常: " + e.getMessage());
        }

        // 远端轨道就绪：application/x-rtp, media=video，兼容 H264/RED/ULPFEC
        webrtc.connect((Element.PAD_ADDED) (elem, newPad) -> {
            String caps = String.valueOf(newPad.getCurrentCaps());
            System.err.println("caps========> "+caps);
            String lc = caps.toLowerCase();

            // 如果之前没有获取到webrtcSrcPad，在这里重试获取
            if (webrtcSrcPad == null) {
                String padName = newPad.getName();
                if (padName != null && padName.startsWith("src_") && newPad.getDirection() == PadDirection.SRC) {
                    webrtcSrcPad = newPad;
                    System.err.println("🔧 在pad-added中获取到webrtcSrcPad: " + padName);
                }
            }

            // 🔧 关键修复：添加WebRTC状态检查
            // 确保webrtcbin处于正确状态再进行pad连接
            State webrtcState = webrtc.getState();
            System.err.println("🔧 WebRTC状态检查: " + webrtcState);
            if (webrtcState == State.NULL || webrtcState == State.VOID_PENDING) {
                System.err.println("❌ WebRTC状态不正确，延迟pad连接");
                // 延迟100ms后重试
                Platform.runLater(() -> {
                    Timer retryTimer = new Timer(true);
                    retryTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            State newState = webrtc.getState();
                            System.err.println("🔧 重试WebRTC状态: " + newState);
                            if (newState != State.NULL && newState != State.VOID_PENDING) {
                                // 状态正常，继续pad连接
                                processPadAdded(elem, newPad, caps, lc);
                                // 启动首屏PLI定时器
                                String retryPadName = newPad.getName();
                                if (retryPadName != null && (retryPadName.contains("recv_rtp_src") || 
                                                           retryPadName.contains("src_") || 
                                                           retryPadName.startsWith("recv") ||
                                                           retryPadName.contains("rtp_src"))) {
                                    Platform.runLater(() -> {
                                        System.err.println("🔑 检测到视频接收pad，启动首屏PLI定时器");

                                    });
                                }

                            } else {
                                System.err.println("❌ WebRTC状态仍然不正确，跳过pad连接");
                            }
                        }
                    }, 100);
                });
                return;
            }

            // 状态正常，直接处理pad连接
            processPadAdded(elem, newPad, caps, lc);
        });

        // === Moved: 提前安装协商与play、添加recvonly transceivers ===
        // 协商：on-negotiation-needed → createOffer → set local → POST → set remote
        webrtc.connect(new WebRTCBin.ON_NEGOTIATION_NEEDED() {
            @Override
            public void onNegotiationNeeded(Element elem) {
                WebRTCBin self = (WebRTCBin) elem;
                System.err.println("STEP2: 🔄 ON_NEGOTIATION_NEEDED triggered");

                try {
                    self.setStunServer("stun://stun.l.google.com:19302");
                    System.err.println("✅ STUN server set successfully");
                } catch (Throwable e) {
                    System.err.println("❌ Failed to set STUN server: " + e.getMessage());
                }
                // 使用带看门狗的创建流程，避免卡在 createOffer
                createOfferWithWatchdog(self, "negotiation-needed");
            }
        });

        System.err.println("STEP0: ▶️ pipeline.play() called");

        // 添加管道状态监控
        try {
            pipe.getBus().connect((Bus.STATE_CHANGED) (source, old, current, pending) -> {
                if (source == pipe) {
                    System.err.println("PIPELINE STATE: " + old + " → " + current + " (pending: " + pending + ")");
                    if (current == State.PLAYING) {
                        System.err.println("PIPELINE: ✅ Successfully reached PLAYING state");
                    } else if (current == State.NULL) {
                        System.err.println("PIPELINE: ❌ Pipeline stopped");
                    }
                }
            });

            pipe.getBus().connect((Bus.ERROR) (source, code, message) -> {
                System.err.println("PIPELINE ERROR: code=" + code + ", message=" + message + ", source=" + source);

                // 特殊处理ICE/网络相关错误
                if (source != null && source.getName() != null) {
                    String sourceName = source.getName();
                    if (sourceName.contains("queue")) {
                        // 抑制非致命的 queue 错误噪音，仅记录不触发恢复流程
                        System.err.println("NETWORK WARN: queue 错误已抑制: " + message + " (" + sourceName + ")");
                        return;
                    }
                    if (sourceName.contains("nicesrc")) {
                        System.err.println("NETWORK ERROR: 检测到ICE/网络数据流错误");
                        System.err.println("NETWORK ERROR: 源元素: " + sourceName);
                        System.err.println("NETWORK ERROR: 错误详情: " + message);

                        // 检查ICE连接状态
                        try {
                            Object iceState = webrtc.get("ice-connection-state");
                            Object connState = webrtc.get("connection-state");
                            System.err.println("NETWORK ERROR: ICE状态=" + iceState + ", 连接状态=" + connState);

                            // 触发自动恢复：ICE断开或失败，或出现内部数据流错误
                            boolean shouldRecover = false;
                            if (iceState != null) {
                                String s = iceState.toString();
                                shouldRecover = ("4".equals(s) || "5".equals(s));
                            }
                            if (!shouldRecover && message != null && message.toLowerCase().contains("internal data stream error")) {
                                shouldRecover = true;
                            }

                            long now = System.currentTimeMillis();
                            // 简单节流：10秒内不重复触发
                            if (shouldRecover && (now - lastNetworkCheckTime) > 10_000L) {
                                lastNetworkCheckTime = now;
                                System.err.println("NETWORK ERROR: ICE连接异常，尝试重新协商并重启ICE...");
                                try {
                                    // 优先请求ICE重启（如果webrtcbin支持）
                                    try {
                                        webrtc.emit("request-ice-restart");
                                        System.err.println("NETWORK RECOVERY: 已请求 request-ice-restart");
                                    } catch (Throwable ignore) {
                                        System.err.println("NETWORK RECOVERY: request-ice-restart 不可用，回退为重新协商");
                                    }
                                    // 回退：重新创建Offer进行协商
                                    createOfferWithWatchdog(webrtc, "auto-recover");
                                } catch (Throwable rex) {
                                    System.err.println("NETWORK RECOVERY: 触发恢复失败: " + rex.getMessage());
                                    rex.printStackTrace();
                                }
                            } else if (shouldRecover) {
                                System.err.println("NETWORK RECOVERY: 已在节流窗口内，跳过重复恢复触发");
                            }
                        } catch (Exception e) {
                            System.err.println("NETWORK ERROR: 无法获取ICE状态: " + e.getMessage());
                        }
                    }
                }
            });

            pipe.getBus().connect((Bus.WARNING) (source, code, message) -> {
                System.err.println("PIPELINE WARNING: code=" + code + ", message=" + message + ", source=" + source);
            });

            System.err.println("STEP0: ✅ Pipeline monitoring installed");
        } catch (Throwable e) {
            System.err.println("STEP0: ⚠️ Pipeline monitoring failed: " + e.getMessage());
        }

        pipe.play();
        System.err.println("STEP0: ✅ Pipeline play() completed");

        // Pipeline启动后添加transceiver
        System.err.println("STEP1: 📡 Pipeline started, adding video transceiver...");
        // 启动流活动监控
        startStreamActivityMonitor();

        // 直接在主线程中添加transceiver，避免Platform.runLater的问题
        try {
            // 音频已禁用 - 仅视频流以减少延迟和带宽消耗
            // Caps audioCaps = Caps.fromString("application/x-rtp,media=audio,payload=111,encoding-name=OPUS,clock-rate=48000");

            // 创建WebRTC视频caps - 必须使用application/x-rtp格式才能正确生成SDP
            // 修改H264配置以支持constrained-high profile，匹配iOS端发送的编码格式
            // profile-level-id=640c1f 对应 constrained-high profile, level 3.1
            // 同时支持多种profile以提高兼容性
            Caps videoCaps = Caps.fromString("application/x-rtp,media=video,payload=109,encoding-name=H264,clock-rate=90000,profile-level-id=640c1f,packetization-mode=1,level-asymmetry-allowed=1");

            // 音频transceiver已禁用 - 仅视频流
            // System.err.println("STEP1: ➕ add audio RECVONLY transceiver with caps: " + audioCaps.toString());
            // webrtc.emit("add-transceiver", 3, audioCaps);
            // System.err.println("STEP1: ✅ audio transceiver added");

            // 添加视频接收transceiver
            System.err.println("STEP1: ➕ add video RECVONLY transceiver with caps: " + videoCaps.toString());
            webrtc.emit("add-transceiver", 3, videoCaps);
            System.err.println("STEP1: ✅ video transceiver added, should trigger negotiation");

            // 延迟一点时间后检查是否需要手动触发
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒
                    System.err.println("STEP2: 🔄 Checking if negotiation was triggered naturally...");
                    // 如果1秒后还没有协商，手动触发
                    try {
                        System.err.println("STEP3: 🚀 Manually creating offer as fallback...");
                        createOfferWithWatchdog(webrtc, "manual-fallback");
                        System.err.println("STEP3: ✅ Fallback createOfferWithWatchdog invoked");
                    } catch (Throwable ex) {
                        System.err.println("STEP3: ❌ Fallback createOfferWithWatchdog threw: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            System.err.println("STEP1: ❌ Failed to add transceiver: " + e.getMessage());
            e.printStackTrace();
        }

    }

    // 🔧 提取pad连接处理逻辑到单独方法
    private void processPadAdded(Element elem, Pad newPad, String caps, String lc) {
        boolean isRtp = lc.contains("application/x-rtp");
        boolean isVideo = lc.contains("media=video") || lc.contains("media=(string)video");
        if (!isRtp || !isVideo) {
            return;
        }
        boolean hasH264 = lc.contains("h264");
        boolean hasRed = lc.contains("encoding-name=red") || lc.contains("encoding-name=(string)red") || caps.contains("RED");
        boolean hasFec = lc.contains("ulpfec") || lc.contains("encoding-name=ulpfec") || lc.contains("encoding-name=(string)ulpfec");
        System.out.println("webrtcbin pad-added: " + caps);
        System.err.println("STEP7: 📥 on-pad-added: elem=" + elem + ", pad=" + newPad + ", hasH264="+hasH264+", hasRED="+hasRed+", hasFEC="+hasFec);

        this.jb = ElementFactory.make("rtpjitterbuffer", "jb");
        // 🔧 优化JitterBuffer配置以避免数据流错误
        jb.set("latency", 100);  // 降低延迟到100ms，减少缓冲压力
        jb.set("mode", 4);      // 4=buffer模式，更稳定的缓冲处理
        jb.set("do-lost", false); // 暂时禁用丢包处理，避免数据流中断
        jb.set("drop-on-latency", false); // 禁用基于延迟的丢帧
        jb.set("max-dropout-time", 1000); // 增加丢包超时到1000ms
        jb.set("max-misorder-time", 100); // 增加乱序时间到100ms
        // 🔧 JitterBuffer 优化配置
        // 🔧 关键修复：使用none模式避免时间戳依赖问题
        jb.set("latency", 50);  // 降低延迟到50ms，平衡响应性和稳定性
        jb.set("mode", 0);      // 0=none模式，只使用RTP时间戳，不依赖系统时钟同步
        jb.set("do-lost", false); // 禁用丢包处理，避免不必要的延迟
        jb.set("drop-on-latency", false); // 禁用基于延迟的丢帧，在none模式下更稳定
        jb.set("max-dropout-time", 1000); // 最大丢包超时1000ms
        jb.set("max-misorder-time", 50); // 最大乱序时间50ms，与延迟匹配
        jb.set("max-rtcp-rtp-time-diff", 1000); // 设置RTCP-RTP时间差容忍度
        jb.set("rfc7273-sync", false); // 禁用RFC7273同步，减少复杂性
        jb.set("faststart-min-packets", 0); // 禁用快速启动，避免初始延迟

        // 🔧 关键修复：确保jitterbuffer正确处理WebRTC的RTP流
        // 添加caps协商支持，确保RTP流能够正确传递给下游元素
        try {
            Pad jbSinkPad = jb.getStaticPad("sink");
            if (jbSinkPad != null) {
                // 让GStreamer自动协商caps，不强制设置特定格式
                System.err.println("🔧 JitterBuffer caps协商: 允许自动协商RTP caps");
            } else {
                System.err.println("❌ JitterBuffer sink pad为null");
            }
        } catch (Exception e) {
            System.err.println("⚠️ JitterBuffer caps设置失败: " + e.getMessage());
        }

        System.out.println("JitterBuffer优化: latency=50ms, mode=none(0), 禁用延迟丢帧, max-misorder-time=50ms");

        // 可选的 FEC 解码器
        Element fecdec = null;
        if (hasFec) {
            try {
                fecdec = ElementFactory.make("rtpulpfecdec", "fecdec");
                System.err.println("STEP7: ➕ 插入 ULPFEC 解码器");
            } catch (Throwable t) {
                System.err.println("STEP7: ⚠️ rtpulpfecdec 不可用: " + t.getMessage());
                fecdec = null;
                }
        }

        // 若为 RED 封装，需先去冗余
        Element reddepay = null;
        if (hasRed) {
            try {
                reddepay = ElementFactory.make("rtpreddepay", "reddepay");
                System.err.println("STEP7: ➕ 检测到 RED，插入 rtpreddepay 解包");
            } catch (Throwable t) {
                System.err.println("STEP7: ⚠️ rtpreddepay 不可用: " + t.getMessage());
                reddepay = null;
            }
        }

        this.depay = ElementFactory.make("rtph264depay", "depay");
        // 优化 rtph264depay 配置，确保正确处理 H264 流
        try {
            depay.set("request-keyframe", true);  // 请求关键帧
            depay.set("wait-for-keyframe", false); // 不等待关键帧，立即开始输出
        } catch (Throwable ignore) {}

        // 移除h264parse - 让rtph264depay直接连接到解码器
        // 🔧 h264parse配置 - 强制插入SPS/PPS解决缺失问题（基于全网搜索最佳实践）
        this.parse = ElementFactory.make("h264parse", "parse");
        
        // 🔍 强制在每个关键帧前插入SPS/PPS参数集
        try {
            parse.set("config-interval", -1);  // 在每个IDR帧前插入SPS/PPS
            System.err.println("STEP7: ✅ h264parse SPS/PPS修复: config-interval=-1 (在每个IDR帧前插入)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264parse config warning: " + e.getMessage());
        }

        // 🔍 强制输出byte-stream格式，确保与rtph264depay兼容
        try {
            parse.set("output-format", "byte-stream");
            System.err.println("STEP7: ✅ h264parse output-format=byte-stream (确保格式兼容)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264parse output-format not supported: " + e.getMessage());
        }

        // 🔧 添加insert-vui参数强化SPS/PPS处理
        try {
            parse.set("insert-vui", true);
            System.err.println("STEP7: ✅ h264parse insert-vui=true (强化VUI插入)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264parse insert-vui not supported: " + e.getMessage());
        }

        // 🔧 禁用passthrough模式强制解析SPS/PPS，但不增加缓存
        try {
            parse.set("disable-passthrough", true);
            System.err.println("STEP7: ✅ h264parse disable-passthrough=true (强制解析SPS/PPS)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264parse disable-passthrough not supported: " + e.getMessage());
        }

        // 创建caps过滤器确保正确的H264格式传递给解码器（强制byte-stream）
        Element h264caps1 = ElementFactory.make("capsfilter", "h264caps1");
        try { 
            // 🔍 基于搜索结果：强制使用byte-stream格式确保SPS/PPS内联传输
            h264caps1.set("caps", Caps.fromString("video/x-h264,stream-format=byte-stream,alignment=au,framerate=30/1")); 
            System.err.println("STEP7: ✅ h264caps1 强制byte-stream格式");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264caps1 配置失败: " + e.getMessage());
        }
        
        Element h264caps2 = ElementFactory.make("capsfilter", "h264caps2");
        try { 
            h264caps2.set("caps", Caps.fromString("video/x-h264,stream-format=byte-stream,alignment=au,framerate=30/1")); 
            System.err.println("STEP7: ✅ h264caps2 强制byte-stream格式");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ h264caps2 配置失败: " + e.getMessage());
        }

        // 创建解码器，使用final变量
        final Element dec = createH264Decoder();

        // 添加解码器输入caps过滤器，确保正确的H264格式
        Element decInputCaps = ElementFactory.make("capsfilter", "dec-input-caps");
        try {
            // 修复：移除强制帧率，让GStreamer自动协商caps
            decInputCaps.set("caps", Caps.fromString("video/x-h264,stream-format=byte-stream,alignment=au"));
            System.err.println("STEP7: ✅ decoder input caps filter configured (byte-stream, auto framerate)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ decoder input caps filter warning: " + e.getMessage());
        }

        System.err.println("STEP7.1: 🔧 Creating converter and caps filter...");
        Element conv  = ElementFactory.make("videoconvert", "conv");
        Element vraw  = ElementFactory.make("capsfilter", "vrawcaps");
        try { vraw.set("caps", Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full")); } catch (Throwable ignore) {}

        System.err.println("STEP7.2: 🔧 Creating tee and queues...");
        System.err.println("STEP7.2.1: 🔧 Creating tee element...");
        Element tee   = ElementFactory.make("tee", "tee");
        System.err.println("STEP7.2.2: 🔧 Creating queue1 element...");
        Element queue1 = ElementFactory.make("queue", "queue1"); // 显示队列
        System.err.println("STEP7.2.3: 🔧 Creating queue2 element...");
        Element queue2 = ElementFactory.make("queue", "queue2"); // 录制队列
        System.err.println("STEP7.2.4: 🔧 Creating tee2 element...");
        Element tee2  = ElementFactory.make("tee", "tee2");
        System.err.println("STEP7.2.5: 🔧 Creating queue3 element...");
        Element queue3 = ElementFactory.make("queue", "queue3"); // 慢放捕获队列
        System.err.println("STEP7.2.6: 🔧 Configuring queue1...");
        try {
            // 🔧 优化queue1配置以避免数据流错误
            queue1.set("leaky", 1); // 改为downstream leaky，避免上游阻塞
            queue1.set("max-size-buffers", 50); // 减少缓冲区大小，降低内存压力
            queue1.set("max-size-bytes", 0);
            queue1.set("max-size-time", 2000000000L); // 设置2秒时间限制
            queue1.set("flush-on-eos", true); // EOS时刷新缓冲区
        } catch (Throwable ignore) {}
        System.err.println("STEP7.2.7: 🔧 Configuring queue2...");
        try {
            // 🔧 优化queue2配置
            queue2.set("leaky", 1); // downstream leaky
            queue2.set("max-size-buffers", 30); // 进一步减少缓冲区
            queue2.set("max-size-time", 1000000000L); // 1秒时间限制
            queue2.set("flush-on-eos", true);
        } catch (Throwable ignore) {}
        System.err.println("STEP7.2.8: 🔧 Configuring queue3...");
        try {
            // 🔧 优化queue3配置
            queue3.set("leaky", 1); // downstream leaky
            queue3.set("max-size-buffers", 50); // 减少缓冲区
            queue3.set("max-size-bytes", 0);
            queue3.set("max-size-time", 2000000000L); // 2秒时间限制
            queue3.set("flush-on-eos", true);
        } catch (Throwable ignore) {}

        System.err.println("STEP7.2.9: 🔧 Creating capValve...");
        capValve = ElementFactory.make("valve", "capValve");
        try { capValve.set("drop", true); } catch (Throwable ignore) {}
        System.err.println("STEP7.2.10: 🔧 Creating splitmux...");
        Element splitmux = ElementFactory.make("splitmuxsink", "splitmux");
        try {
            splitmux.set("location", "runtime/slowmo/segment-%05d.mp4");
            splitmux.set("max-size-time", 5_000_000_000L); // 5s分段
            splitmux.set("muxer-factory", "mp4mux");
        } catch (Throwable ignore) {}

        System.err.println("STEP7.2.11: 🔧 Creating slowSink...");
        slowSink = (AppSink) ElementFactory.make("appsink", "slow-sink");
        System.err.println("STEP7.2.11.1: 🔧 Setting slowSink caps...");
        try {
            // 不设置具体的caps，让GStreamer自动协商
            // slowSink.setCaps(Caps.fromString("video/x-raw"));
            System.err.println("STEP7.2.11.1.SKIP: 🔧 Skipping caps setting, using auto-negotiation...");
        } catch (Exception e) {
            System.err.println("STEP7.2.11.1.ERROR: ❌ Failed to set slowSink caps: " + e.getMessage());
            e.printStackTrace();
        }
        System.err.println("STEP7.2.11.2: 🔧 Configuring slowSink properties...");
        slowSink.set("emit-signals", true);
        slowSink.set("max-buffers", 60);
        slowSink.set("drop", true);
        slowSink.set("qos", true);
        try { slowSink.set("sync", false); } catch (Throwable ignore) {}
        System.err.println("STEP7.2.12: ✅ All elements created successfully");
        System.err.println("STEP7.2.13: 🔧 Connecting slowSink callback...");
        slowSink.connect((AppSink.NEW_SAMPLE) es -> {

            System.err.println("嘻嘻============》");
            Sample ss = slowSink.pullSample();
            if (ss == null) return FlowReturn.OK;
            if (!slowMoCapturing) { ss.dispose(); return FlowReturn.OK; }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastSlowMemPushMs < slowCaptureIntervalMs) { ss.dispose(); return FlowReturn.OK; }
            Buffer sb = ss.getBuffer();
            Structure st = ss.getCaps().getStructure(0);
            int sw = st.getInteger("width");
            int sh = st.getInteger("height");
            ByteBuffer sbb = sb.map(false);
            int totalBytes = sbb.remaining();
            int computedStride = (sh > 0) ? (totalBytes / sh) : (sw * 4);
            if (computedStride < sw * 4 || (computedStride % 4) != 0) {
                computedStride = sw * 4;
            }
            final int rowStride = computedStride;
            final int effectiveW = Math.min(sw, rowStride / 4);
            System.err.println("SLOW-SINK: info w=" + sw + ", h=" + sh + ", totalBytes=" + totalBytes + ", rowStride=" + rowStride + ", effW=" + effectiveW);
            if (effectiveW != sw) {
                System.err.println("SLOW-SINK: ⚠️ width mismatch, capsW=" + sw + ", effW=" + effectiveW + ", rowStride=" + rowStride + ", totalBytes=" + totalBytes);
            }
            BufferedImage img = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            int[] dst = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < sh; y++) {
                int row = y * rowStride;
                int base = y * sw;
                for (int x = 0; x < effectiveW; x++) {
                    int b = sbb.get(row + x * 4) & 0xFF;
                    int g = sbb.get(row + x * 4 + 1) & 0xFF;
                    int r = sbb.get(row + x * 4 + 2) & 0xFF;
                    dst[base + x] = (r << 16) | (g << 8) | b;
                }
            }
            sb.unmap();

                if (slowMoCapturing) {
                    final java.awt.image.BufferedImage memImg = img;
                    slowIoExecutor.execute(() -> {
                        try { slowBuffer.push(memImg); } catch (Exception ignore) {}
                    });
                    lastSlowMemPushMs = nowMs;
                }
                ss.dispose();
                return FlowReturn.OK;
            });

            System.err.println("STEP7.3: 🔧 Creating additional elements...");
            Element queue4 = ElementFactory.make("queue", "slow-disk-queue");
            System.err.println("STEP7.3.1: 🔧 Configuring queue4...");
            try {
                // 🔧 优化queue4配置以避免数据流错误
                queue4.set("leaky", 1); // downstream leaky
                queue4.set("max-size-buffers", 100); // 减少缓冲区大小
                queue4.set("max-size-bytes", 0);
                queue4.set("max-size-time", 3000000000L); // 3秒时间限制
                queue4.set("flush-on-eos", true);
            } catch (Throwable ignore) {}
            System.err.println("STEP7.3.2: 🔧 Creating conv2...");
            Element conv2 = ElementFactory.make("videoconvert", "slow-disk-conv");
            System.err.println("STEP7.3.3: 🔧 Creating jpegenc...");
            Element je = ElementFactory.make("jpegenc", "slow-jpegenc");
            try { je.set("quality", 95); } catch (Throwable ignore) {}
            System.err.println("STEP7.3.4: 🔧 Creating slowValveDisk...");
            slowValveDisk = ElementFactory.make("valve", "slowValveDisk");
            try { slowValveDisk.set("drop", true); } catch (Throwable ignore) {}
            System.err.println("STEP7.3.5: 🔧 Creating multifilesink...");
            Element mfs = ElementFactory.make("multifilesink", "slowmo-sink");
            try {
                mfs.set("location", "runtime/slowmo/frame_%06d.jpg");
                mfs.set("max-files", com.acard.acard.storage.SlowmoStore.getInstance().getSlowmoFrames());
                mfs.set("post-messages", true);
            } catch (Throwable ignore) {}

            // 统一入口：始终通过 rtpjitterbuffer 处理乱序与抖动
            // 不再使用 rtpQueue 直连，避免包乱序导致 depay/parse 不出数据

            // 将元素加入管线（根据 minimalDisplayOnly 进行最小化）
            System.err.println("STEP7.4: 🚩 minimalDisplayOnly=" + minimalDisplayOnly);
            System.err.println("STEP7.4.1: 🔧 Adding elements to pipeline...");
            if (minimalDisplayOnly) {
                // 显示所需的最小元素链 - 恢复parse
                pipe.addMany(depay, parse, h264caps1, decInputCaps, dec, conv, vraw, tee, queue1, tee2, h264caps2);
            } else {
                // 完整元素链，包含慢放与录制分支 - 恢复parse
                pipe.addMany(depay, parse, h264caps1, decInputCaps, dec, conv, vraw, tee, queue1, queue2, capValve, splitmux, tee2, queue3, slowSink, queue4, conv2, je, slowValveDisk, mfs, h264caps2);
            }
            System.err.println("STEP7.4.2: 🔧 Adding jb to pipeline...");
            pipe.add(jb);
            if (fecdec != null) {
                System.err.println("STEP7.4.3: 🔧 Adding fecdec to pipeline...");
                pipe.add(fecdec);
            }
            if (reddepay != null) {
                System.err.println("STEP7.4.4: 🔧 Adding reddepay to pipeline...");
                pipe.add(reddepay);
            }

            // 🔧 修复 rtph264depay 配置 - 恢复正常管道但加强调试
            Element upstream = jb;
            if (fecdec != null) {
                Element.linkMany(upstream, fecdec);
                upstream = fecdec;
            }
            if (reddepay != null) {
                boolean okRed = upstream.link(reddepay);
                System.err.println("STEP7: 🔗 " + upstream.getName() + " -> reddepay linked=" + okRed);
                upstream = reddepay;
            }

        // 🔧 强化 rtph264depay 配置 - 基于GStreamer社区最佳实践和全网搜索结果
        // 🔍 关键修复：正确处理SPS/PPS传输
        try {
            depay.set("wait-for-keyframe", true);     // 等待关键帧，确保SPS/PPS完整性
            System.err.println("STEP7: ✅ rtph264depay wait-for-keyframe=true");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ rtph264depay wait-for-keyframe not supported: " + e.getMessage());
        }
        
        try {
            depay.set("request-keyframe", true);      // 主动请求关键帧，获取SPS/PPS
            System.err.println("STEP7: ✅ rtph264depay request-keyframe=true");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ rtph264depay request-keyframe not supported: " + e.getMessage());
        }
        
        // 🔍 新增：强制输出byte-stream格式，确保SPS/PPS内联传输
        try {
            depay.set("output-format", "byte-stream");
            System.err.println("STEP7: ✅ rtph264depay output-format=byte-stream (强制SPS/PPS内联)");
        } catch (Throwable e) {
            System.err.println("STEP7: ⚠️ rtph264depay output-format not supported: " + e.getMessage());
        }
        
        System.err.println("STEP7: 🔧 rtph264depay 全面修复配置完成");

            // 🔍 移除不支持的属性，专注于核心配置
            System.err.println("STEP7: 🔧 rtph264depay 使用核心配置，跳过不支持的属性");
            // 注意：mode属性在当前GStreamer版本中不存在，已移除
            System.err.println("STEP7: 🔧 rtph264depay 强化配置完成");

            // 🔑 延迟请求关键帧 - 等待管道稳定后发送
            Platform.runLater(() -> {
                Timer keyFrameTimer = new Timer(true);
                keyFrameTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        requestKeyFrameWithRetry();
                    }
                }, 2000); // 延迟2秒发送，确保管道稳定
            });

            // 增强连接错误检查
            try {
                boolean ok1 = upstream.link(depay);
                Pad depaySink = depay.getStaticPad("sink");
                System.err.println("STEP7: 🔗 " + upstream.getName() + " -> depay linked=" + ok1 + ", depay.sink.isLinked=" + (depaySink != null && depaySink.isLinked()));
                if (!ok1) {
                    System.err.println("STEP7: ❌ CRITICAL: upstream -> depay 连接失败！");
                    // 检查元素状态
                    System.err.println("STEP7: upstream状态=" + upstream.getState() + ", depay状态=" + depay.getState());
                }
            } catch (Exception e) {
                System.err.println("STEP7: ❌ upstream -> depay 连接异常: " + e.getMessage());
            }

            try {
                // 恢复标准连接：depay -> parse -> h264caps1
                // 🔍 添加caps调试信息
                System.err.println("STEP7: 🔍 检查连接前的caps状态:");
                Pad depaySrc = depay.getStaticPad("src");
                Pad parseSink = parse.getStaticPad("sink");
                if (depaySrc != null && parseSink != null) {
                    Caps depayCaps = depaySrc.getCurrentCaps();
                    Caps parseCaps = parseSink.getCurrentCaps();
                    System.err.println("STEP7: depay.src caps: " + (depayCaps != null ? depayCaps.toString() : "null"));
                    System.err.println("STEP7: parse.sink caps: " + (parseCaps != null ? parseCaps.toString() : "null"));

                    // 检查caps兼容性
                    Caps parseAllowedCaps = parseSink.getAllowedCaps();
                    System.err.println("STEP7: parse.sink allowed caps: " + (parseAllowedCaps != null ? parseAllowedCaps.toString() : "null"));
                }

                boolean ok2 = depay.link(parse);
                boolean ok3 = parse.link(h264caps1);
                System.err.println("STEP7: 🔗 depay -> parse linked=" + ok2 + ", parse -> h264caps1 linked=" + ok3);

                // 🔧 首屏PLI+NAL探针解决方案 - 在depay->parse链接后插入
                try {
                    // 1. 设置h264parse的config-interval参数（如果尚未设置）
                    parse.set("config-interval", -1);  // 在每个IDR帧前插入SPS/PPS
                    System.err.println("STEP7: ✅ 首屏PLI方案: h264parse config-interval=-1 已设置");

                    // 2. 安装NAL探针到h264parse.src pad
                    attachNalProbeOnParse(parse);
                    System.err.println("STEP7: ✅ 首屏PLI方案: NAL探针已安装到h264parse");

                    // 3. 记录远端视频SSRC（从WebRTC pad获取）
                    if (newPad != null) {
                        try {
                            // 尝试从pad的caps中提取SSRC信息
                            Caps padCaps = newPad.getCurrentCaps();
                            if (padCaps != null) {
                                // 这里可以根据需要解析SSRC，暂时使用默认值
                                remoteVideoSsrc = 12345L; // 占位符，实际应从RTP流中获取
                                System.err.println("STEP7: ✅ 首屏PLI方案: 远端视频SSRC记录为 " + remoteVideoSsrc);
                            }
                        } catch (Exception e) {
                            System.err.println("STEP7: ⚠️ 首屏PLI方案: SSRC获取失败: " + e.getMessage());
                        }
                    }

                    // 4. 启动首屏PLI定时器
                    if (!firstScreenComplete) {
                        startFirstScreenPLITimer();
                        System.err.println("STEP7: ✅ 首屏PLI方案: 首屏PLI定时器已启动");
                    }

                } catch (Exception e) {
                    System.err.println("STEP7: ❌ 首屏PLI方案配置失败: " + e.getMessage());
                }

                // 🔍 连接后再次检查caps
                if (ok2 && depaySrc != null) {
                    Caps negotiatedCaps = depaySrc.getCurrentCaps();
                    System.err.println("STEP7: 协商后的caps: " + (negotiatedCaps != null ? negotiatedCaps.toString() : "null"));
                }

                if (!ok2 || !ok3) {
                    System.err.println("STEP7: ❌ CRITICAL: depay -> parse -> h264caps1 连接失败！");
                    System.err.println("STEP7: depay状态=" + depay.getState() + ", parse状态=" + parse.getState() + ", h264caps1状态=" + h264caps1.getState());
                }
            } catch (Exception e) {
                System.err.println("STEP7: ❌ depay -> parse -> h264caps1 连接异常: " + e.getMessage());
            }

            // Pad probe 观察数据是否到达解包和解码阶段
            try {
                // 🔍 强化 depay.sink 探针 - 观察 RTP 输入
                Pad depaySinkProbe = depay.getStaticPad("sink");
                if (depaySinkProbe != null) {
                    System.err.println("PROBE INIT: depay.sink installed (enhanced)");
                    depaySinkProbe.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps sinkCaps = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        // 🔍 分析 RTP 包内容
                        String rtpInfo = "";
                        if (sinkCaps != null) {
                            Structure struct = sinkCaps.getStructure(0);
                            if (struct != null) {
                                // 安全获取RTP包信息，避免字段不存在异常
                                String payload = "null";
                                String packetMode = "null";
                                String profile = "null";

                                try {
                                    Object payloadObj = struct.getValue("payload");
                                    payload = payloadObj != null ? payloadObj.toString() : "null";
                                } catch (Exception e) { /* 字段不存在 */ }

                                try {
                                    Object packetModeObj = struct.getValue("packetization-mode");
                                    packetMode = packetModeObj != null ? packetModeObj.toString() : "null";
                                } catch (Exception e) { /* 字段不存在 */ }

                                try {
                                    Object profileObj = struct.getValue("profile-level-id");
                                    profile = profileObj != null ? profileObj.toString() : "null";
                                } catch (Exception e) { /* 字段不存在 */ }

                                rtpInfo = String.format(" [payload=%s, pkt-mode=%s, profile=%s]",
                                    payload, packetMode, profile);
                            }
                        }
                        System.err.println("PROBE: rtph264depay ← sink buffer (RTP in)" + rtpInfo +
                            ", buffer=" + (buffer != null ? "present" : "null"));

                        // 🔍 分析 RTP buffer 内容
                        if (buffer != null) {
                            try {
                                long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                System.err.println("  └─ RTP buffer size: " + size + " bytes");

                                // 🔍 尝试读取 RTP payload 的前几个字节来检查 H264 NAL 单元
                                ByteBuffer bb = buffer.map(false);
                                if (bb != null && bb.remaining() >= 12) { // RTP header 至少12字节
                                    // 🔧 从RTP头直接提取SSRC (字节8-11)
                                    byte[] rtpHeader = new byte[12];
                                    bb.get(rtpHeader);
                                    
                                    // 提取SSRC (32位，大端序)
                                    long extractedSsrc = ((rtpHeader[8] & 0xFFL) << 24) |
                                                        ((rtpHeader[9] & 0xFFL) << 16) |
                                                        ((rtpHeader[10] & 0xFFL) << 8) |
                                                        (rtpHeader[11] & 0xFFL);
                                    
                                    // 更新remoteVideoSsrc（只在第一次或值不同时更新）
                                    if (remoteVideoSsrc == 0L || remoteVideoSsrc != extractedSsrc) {
                                        remoteVideoSsrc = extractedSsrc;
                                        System.err.println("🔧 从RTP头提取SSRC: " + extractedSsrc + " (0x" + Long.toHexString(extractedSsrc).toUpperCase() + ")");
                                    }

                                    // 检查是否有RTP扩展
                                    int headerLen = 12;
                                    if ((rtpHeader[0] & 0x10) != 0) { // X bit set
                                        if (bb.remaining() >= 4) {
                                            bb.getShort(); // extension profile
                                            int extLen = bb.getShort() & 0xFFFF;
                                            headerLen += 4 + (extLen * 4);
                                            if (bb.remaining() >= extLen * 4) {
                                                bb.position(headerLen);
                                            }
                                        }
                                    }

                                    // 读取 H264 payload 的前几个字节
                                    if (bb.remaining() >= 1) {
                                        int payloadSize = bb.remaining();
                                        int currentPos = bb.position(); // 记录当前位置
                                        
                                        // 🔧 先读取第一个字节检查NAL类型（使用相对位置）
                                        byte firstByte = bb.get(); // 读取当前位置的字节
                                        bb.position(currentPos); // 恢复位置
                                        int nalType = firstByte & 0x1F;
                                        
                                        byte[] payload;
                                        if (nalType == 24) { // STAP-A包需要完整数据
                                            payload = new byte[Math.min(payloadSize, 1024)]; // 限制最大1KB避免内存问题
                                            bb.get(payload);
                                            System.err.println("  └─ H264 payload (STAP-A): " + payloadSize + " bytes, 读取了 " + payload.length + " bytes");
                                        } else {
                                            payload = new byte[Math.min(8, payloadSize)]; // 其他类型只读前8字节
                                            bb.get(payload);
                                            StringBuilder payloadHex = new StringBuilder();
                                            for (byte b : payload) {
                                                payloadHex.append(String.format("%02X ", b & 0xFF));
                                            }
                                            System.err.println("  └─ H264 payload: " + payloadSize + " bytes, hex=[" + payloadHex.toString().trim() + "]");
                                        }

                                        // 🔍 检查 H264 NAL 单元类型
                                        if (payload.length > 0) {
                                            String nalTypeStr = getNalTypeString(nalType);
                                            System.err.println("  └─ NAL unit type: " + nalType + " (" + nalTypeStr + ")");

                                            // 🔧 特殊处理STAP-A包 (NAL type 24) - 解包其中的SPS/PPS
                                            if (nalType == 24) {
                                                System.err.println("🔍 检测到STAP-A包，开始解包分析...");
                                                analyzeStapAPacket(payload, payload.length); // 使用实际读取的长度
                                            }
                                            
                                            // 检测到关键帧相关NAL units
                                            if (nalType == 5 || nalType == 7 || nalType == 8) {
                                                System.err.println("*** 检测到关键帧相关NAL unit: " + getNalTypeName(nalType) + " ***");

                                                if (nalType == 5 && !firstKeyFrameReceived) {
                                                    firstKeyFrameReceived = true;
                                                    System.err.println("*** 首个IDR帧已接收，停止PLI定时器 ***");
                                                    if (pliTimer != null) {
                                                        pliTimer.cancel();
                                                    }
                                                }
                                                
                                                // 🔧 更新IDR接收状态
                                                if (nalType == 5 && !hasReceivedIdr) {
                                                    hasReceivedIdr = true;
                                                    System.err.println("*** RTP探针检测到首个IDR帧 ***");
                                                    checkFirstScreenComplete();
                                                }
                                            }
                                            
                                            // 🔍 检测其他类型的帧
                                            if (nalType == 1) {
                                                System.err.println("📺 检测到Non-IDR slice (NAL type 1) - P/B帧");
                                            }
                                        }
                                    } else {
                                        System.err.println("  └─ No H264 payload found after RTP header");
                                    }
                                }
                                buffer.unmap();
                            } catch (Exception e) {
                                System.err.println("  └─ RTP payload analysis failed: " + e.getMessage());
                            }
                        }
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: depay.sink pad=null");
                }
            } catch (Throwable ignore) {}
            try {
                // 🔍 强化 depay.src 探针 - 观察 H264 输出
                Pad depaySrc = depay.getStaticPad("src");
                if (depaySrc != null) {
                    System.err.println("PROBE INIT: depay.src installed (enhanced)");
                    depaySrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps dc = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: rtph264depay → src buffer (H264 out), caps=" +
                            (dc != null ? dc.toString() : "null") +
                            ", buffer=" + (buffer != null ? "present" : "null"));
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: depay.src pad=null");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: depay.src probe failed: " + e.getMessage());
            }
            // 恢复h264parse相关的probe代码
            try {
                Pad parseSrc = parse.getStaticPad("src");
                if (parseSrc != null) {
                    System.err.println("PROBE INIT: h264parse.src installed");
                    parseSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps pc = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: h264parse → src buffer (H264 parsed out), caps=" +
                            (pc != null ? pc.toString() : "null") +
                            ", buffer=" + (buffer != null ? "present" : "null"));
                        if (buffer != null) {

                            long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                            System.err.println("  └─ H264 parsed buffer size: " + size + " bytes");
                        }
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: h264parse.src pad=null");
                }
            } catch (Throwable ignore) {}
            try {
                Pad parseSink = parse.getStaticPad("sink");
                if (parseSink != null) {
                    System.err.println("PROBE INIT: h264parse.sink installed");
                    parseSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps ic = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: h264parse ← sink buffer (in), caps=" + (ic != null ? ic.toString() : "null"));
                        if (buffer != null) {
                            long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                            System.err.println("  └─ Input buffer size: " + size + " bytes");
                        }
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: h264parse.sink pad=null");
                }
            } catch (Throwable ignore) {}
            try {
                Pad decSink = dec.getStaticPad("sink");
                if (decSink != null) {
                    decSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps ic = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: 🎬 DECODER ← sink buffer (H264 in), caps=" + (ic != null ? ic.toString() : "null") +
                            ", buffer=" + (buffer != null ? "present" : "null"));
                        if (buffer != null) {
                            try {
                                long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                System.err.println("  └─ Decoder input buffer size: " + size + " bytes");
                            } catch (Exception e) {
                                System.err.println("  └─ Failed to get buffer size: " + e.getMessage());
                            }
                        }
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: ✅ decoder.sink probe installed");
                } else {
                    System.err.println("PROBE INIT: ❌ decoder.sink pad is null!");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: decoder.sink probe failed: " + e.getMessage());
            }

            try {
                Pad decSrc = dec.getStaticPad("src");
                if (decSrc != null) {
                    decSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps cc = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: 🎬 DECODER → src buffer (decoded out), caps=" + (cc != null ? cc.toString() : "null") +
                            ", buffer=" + (buffer != null ? "present" : "null"));
                        if (buffer != null) {
                            try {
                                long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                System.err.println("  └─ Decoder output buffer size: " + size + " bytes");
                            } catch (Exception e) {
                                System.err.println("  └─ Failed to get buffer size: " + e.getMessage());
                            }
                        }
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: ✅ decoder.src probe installed");
                } else {
                    System.err.println("PROBE INIT: ❌ decoder.src pad is null!");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: decoder.src probe failed: " + e.getMessage());
            }

            // 添加解码器状态和错误监控
            try {
                dec.getBus().connect((Bus.STATE_CHANGED) (source, old, current, pending) -> {
                    if (source == dec) {
                        System.err.println("🎬 DECODER state changed: " + old + " → " + current + " (pending: " + pending + ")");
                        if (current == State.PLAYING) {
                            System.err.println("🎬 DECODER is now in PLAYING state - should be ready to decode");
                        }
                    }
                });
                System.err.println("PROBE INIT: ✅ decoder state monitoring installed");
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: decoder state monitoring failed: " + e.getMessage());
            }

            try {
                dec.getBus().connect((Bus.ERROR) (source, code, message) -> {
                    if (source == dec) {
                        System.err.println("🎬 DECODER ERROR: code=" + code + ", message=" + message);
                    }
                });
                System.err.println("PROBE INIT: ✅ decoder error monitoring installed");
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: decoder error monitoring failed: " + e.getMessage());
            }

            // 分流1: tee -> queue1 -> dec -> conv -> vraw -> tee2 -> sink (显示)
            // 注意：tee/tee2 需要通过 request pad 连接到多个下游
            Pad teeSrcDisplay = tee.getRequestPad("src_%u");
            Pad queue1Sink = queue1.getStaticPad("sink");
            try {
                teeSrcDisplay.link(queue1Sink);
                System.err.println("STEP7: 🔗 tee.src(display) -> queue1.sink linked successfully, queue1.sink.isLinked=" + (queue1Sink != null && queue1Sink.isLinked()));
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link tee to queue1: " + e.getMessage());
            }

            try {
                queue1Sink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    Caps qc = pad.getCurrentCaps();
                    System.err.println("PROBE: display-branch ← queue1.sink buffer, caps=" + (qc != null ? qc.toString() : "null"));
                    return PadProbeReturn.OK;
                });
            } catch (Throwable ignore) {}

            // 逐步连接显示分支，增加错误检查
            try {
                queue1.link(decInputCaps);
                System.err.println("STEP7: 🔗 queue1 -> decInputCaps linked successfully");

                // 添加queue1输出探针，监控数据是否从queue1流出
                try {
                    Pad queue1Src = queue1.getStaticPad("src");
                    if (queue1Src != null) {
                        queue1Src.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                            Caps padCaps = pad.getCurrentCaps();
                            Buffer buffer = info.getBuffer();
                            System.err.println("PROBE: 🚀 queue1 → src buffer (to decInputCaps), caps=" + (padCaps != null ? padCaps.toString() : "null") +
                                ", buffer=" + (buffer != null ? "present" : "null"));
                            if (buffer != null) {
                                try {
                                    long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                    System.err.println("  └─ queue1 output buffer size: " + size + " bytes");
                                } catch (Exception e) {
                                    System.err.println("  └─ Failed to get buffer size: " + e.getMessage());
                                }
                            }
                            return PadProbeReturn.OK;
                        });
                        System.err.println("PROBE INIT: ✅ queue1.src probe installed");
                    } else {
                        System.err.println("PROBE INIT: ❌ queue1.src pad is null!");
                    }
                } catch (Throwable e) {
                    System.err.println("PROBE INIT: ❌ Failed to install queue1.src probe: " + e.getMessage());
                }
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link queue1 to decInputCaps: " + e.getMessage());
            }

            try {
                decInputCaps.link(dec);
                System.err.println("STEP7: 🔗 decInputCaps -> dec linked successfully");

                // 添加decInputCaps输出探针
                try {
                    Pad decInputCapsSrc = decInputCaps.getStaticPad("src");
                    if (decInputCapsSrc != null) {
                        decInputCapsSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                            Caps padCaps = pad.getCurrentCaps();
                            Buffer buffer = info.getBuffer();
                            System.err.println("PROBE: 🎯 decInputCaps → src buffer (to decoder), caps=" + (padCaps != null ? padCaps.toString() : "null") +
                                ", buffer=" + (buffer != null ? "present" : "null"));
                            if (buffer != null) {
                                try {
                                    long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                    System.err.println("  └─ decInputCaps output buffer size: " + size + " bytes");
                                } catch (Exception e) {
                                    System.err.println("  └─ Failed to get buffer size: " + e.getMessage());
                                }
                            }
                            return PadProbeReturn.OK;
                        });
                        System.err.println("PROBE INIT: ✅ decInputCaps.src probe installed");
                    } else {
                        System.err.println("PROBE INIT: ❌ decInputCaps.src pad is null!");
                    }
                } catch (Throwable e) {
                    System.err.println("PROBE ERROR: decInputCaps.src probe failed: " + e.getMessage());
                }

                // 添加decoder输入探针
                try {
                    Pad decSink = dec.getStaticPad("sink");
                    if (decSink != null) {
                        decSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                            Caps decCaps = pad.getCurrentCaps();
                            Buffer buffer = info.getBuffer();
                            System.err.println("PROBE: 🎬 DECODER ← sink buffer (H264 in), caps=" + (decCaps != null ? decCaps.toString() : "null") +
                                ", buffer=" + (buffer != null ? "present" : "null"));
                            if (buffer != null) {
                                try {
                                    long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                    System.err.println("  └─ Decoder input buffer size: " + size + " bytes");
                                } catch (Exception e) {
                                    System.err.println("  └─ Failed to get buffer size: " + e.getMessage());
                                }
                            }
                            return PadProbeReturn.OK;
                        });
                        System.err.println("PROBE INIT: ✅ decoder.sink probe installed");
                    } else {
                        System.err.println("PROBE INIT: ❌ decoder.sink pad is null!");
                    }
                } catch (Throwable e) {
                    System.err.println("PROBE ERROR: decoder.sink probe failed: " + e.getMessage());
                }

            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link decInputCaps to decoder: " + e.getMessage());
            }

            try {
                dec.link(conv);
                System.err.println("STEP7: 🔗 dec -> conv linked successfully");
                
                // 添加decoder输出探针
                try {
                    Pad decSrc = dec.getStaticPad("src");
                    if (decSrc != null) {
                        decSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                            Caps decOutputCaps = pad.getCurrentCaps();
                            Buffer buffer = info.getBuffer();
                            System.err.println("PROBE: 🎬 DECODER → src buffer (decoded video out), caps=" + (decOutputCaps != null ? decOutputCaps.toString() : "null") +
                                ", buffer=" + (buffer != null ? "present" : "null"));
                            if (buffer != null) {
                                try {
                                    long size = GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer).longValue();
                                    System.err.println("  └─ ✅ Decoder OUTPUT buffer size: " + size + " bytes (DECODED VIDEO FRAME!)");
                                    
                                    // 检测到解码器输出，停止关键帧重试
                                    if (keyFrameRetryTimer != null) {
                                        stopKeyFrameRetry();
                                        System.err.println("  └─ 🛑 检测到解码器输出，停止关键帧重试定时器");
                                    }
                                } catch (Exception e) {
                                    System.err.println("  └─ Failed to get decoder output buffer size: " + e.getMessage());
                                }
                            }
                            return PadProbeReturn.OK;
                        });
                        System.err.println("PROBE INIT: ✅ decoder.src probe installed");
                    } else {
                        System.err.println("PROBE INIT: ❌ decoder.src pad is null!");
                    }
                } catch (Throwable e) {
                    System.err.println("PROBE ERROR: decoder.src probe failed: " + e.getMessage());
                }
                
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link decoder to converter: " + e.getMessage());
            }

            try {
                conv.link(vraw);
                System.err.println("STEP7: 🔗 conv -> vraw linked successfully");
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link converter to caps filter: " + e.getMessage());
            }

            try {
                vraw.link(tee2);
                System.err.println("STEP7: 🔗 vraw -> tee2 linked successfully");
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link caps filter to tee2: " + e.getMessage());
            }

            // 添加vraw输出监控
            try {
                Pad vrawSrc = vraw.getStaticPad("src");
                if (vrawSrc != null) {
                    vrawSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps vc = pad.getCurrentCaps();
                        System.err.println("PROBE: vraw → src buffer (BGRx), caps=" + (vc != null ? vc.toString() : "null"));
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: vraw.src probe installed");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: vraw probe failed: " + e.getMessage());
            }

            // 添加tee2输入监控
            try {
                Pad tee2Sink = tee2.getStaticPad("sink");
                if (tee2Sink != null) {
                    tee2Sink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps tc = pad.getCurrentCaps();
                        System.err.println("PROBE: tee2 ← sink buffer (BGRx), caps=" + (tc != null ? tc.toString() : "null"));
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: tee2.sink probe installed");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: tee2 sink probe failed: " + e.getMessage());
            }

            System.err.println("STEP7: 🔗 display-branch linking complete");

            // 添加解码器状态监控
            try {
                Pad decSink = dec.getStaticPad("sink");
                if (decSink != null) {
                    System.err.println("PROBE INIT: decoder.sink installed");
                    decSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps dc = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: decoder ← sink buffer, caps=" + (dc != null ? dc.toString() : "null") +
                                         ", buffer_size=" + (buffer != null ? GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer) : "null") +
                                         ", pts=" + (buffer != null ? buffer.getPresentationTimestamp() : "null"));
                        return PadProbeReturn.OK;
                    });

                    // 添加事件监控
                    decSink.addProbe(PadProbeType.EVENT_DOWNSTREAM, (pad, info) -> {
                        Event event = info.getEvent();
                        if (event != null) {
                            GstEventAPI.EventStruct struct = new GstEventAPI.EventStruct(Natives.getPointer(event).getPointer());
                            EventType eventType = (EventType) struct.readField("type");
                            System.err.println("PROBE: decoder ← event: " + eventType);
                        }
                        return PadProbeReturn.OK;
                    });
                }

                Pad decSrc = dec.getStaticPad("src");
                if (decSrc != null) {
                    System.err.println("PROBE INIT: decoder.src installed");
                    decSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps dc = pad.getCurrentCaps();
                        Buffer buffer = info.getBuffer();
                        System.err.println("PROBE: decoder → src buffer (decoded video), caps=" + (dc != null ? dc.toString() : "null") +
                                         ", buffer_size=" + (buffer != null ? GstBufferAPI.GSTBUFFER_API.gst_buffer_get_size(buffer) : "null") +
                                         ", pts=" + (buffer != null ? buffer.getPresentationTimestamp() : "null"));
                        return PadProbeReturn.OK;
                    });

                    // 添加事件监控
                    decSrc.addProbe(PadProbeType.EVENT_DOWNSTREAM, (pad, info) -> {
                        Event event = info.getEvent();
                        if (event != null) {
                            GstEventAPI.EventStruct struct = new GstEventAPI.EventStruct(Natives.getPointer(event).getPointer());
                            EventType eventType = (EventType) struct.readField("type");
                            System.err.println("PROBE: decoder → event: " + eventType);
                        }
                        return PadProbeReturn.OK;
                    });
                }

                // 添加解码器状态监控
                dec.getBus().connect((Bus.STATE_CHANGED) (source, oldState, newState, pending) -> {
                    System.err.println("DECODER STATE: " + oldState + " → " + newState + " (pending: " + pending + ")");
                });

                // 添加解码器错误监控
                dec.getBus().connect((Bus.ERROR) (source, code, message) -> {
                    System.err.println("DECODER ERROR: code=" + code + ", message=" + message);
                });

            } catch (Throwable e) {
                System.err.println("PROBE ERROR: decoder probe failed: " + e.getMessage());
            }
            // 连接到显示sink
            Pad tee2SrcDisplay = tee2.getRequestPad("src_%u");
            Pad sinkSink = sink.getStaticPad("sink");
            try {
                tee2SrcDisplay.link(sinkSink);
                System.err.println("STEP7: 🔗 tee2.src(display) -> sink.sink linked successfully, sink.sink.isLinked=" + (sinkSink != null && sinkSink.isLinked()));
            } catch (Throwable e) {
                System.err.println("STEP7: ❌ Failed to link tee2 to display sink: " + e.getMessage());
            }

            // 添加AppSink的sink pad探针 - 监控数据是否到达AppSink
            try {
                if (sinkSink != null) {
                    sinkSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps sinkCaps = pad.getCurrentCaps();
                        System.err.println("🔍 APPSINK PROBE: Buffer arrived at AppSink.sink! caps=" + (sinkCaps != null ? sinkCaps.toString() : "null"));
                        
                        // 检查AppSink属性
                        try {
                            Object emitSignals = sink.get("emit-signals");
                            Object maxBuffers = sink.get("max-buffers");
                            Object drop = sink.get("drop");
                            Object sync = sink.get("sync");
                            System.err.println("🔍 APPSINK PROPS: emit-signals=" + emitSignals + ", max-buffers=" + maxBuffers + ", drop=" + drop + ", sync=" + sync);
                        } catch (Throwable propEx) {
                            System.err.println("🔍 APPSINK PROPS ERROR: " + propEx.getMessage());
                        }
                        
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: ✅ AppSink.sink probe installed successfully");
                } else {
                    System.err.println("PROBE ERROR: ❌ AppSink.sink pad is null");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: ❌ AppSink sink probe failed: " + e.getMessage());
            }

            // 添加AppSink状态和错误监控
            try {
                sink.getBus().connect((Bus.STATE_CHANGED) (source, oldState, newState, pending) -> {
                    System.err.println("🔍 APPSINK STATE: " + oldState + " → " + newState + " (pending: " + pending + ")");
                });

                sink.getBus().connect((Bus.ERROR) (source, code, message) -> {
                    System.err.println("🔍 APPSINK ERROR: code=" + code + ", message=" + message);
                });
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: ❌ AppSink monitoring failed: " + e.getMessage());
            }

            // 添加tee2输出监控
            try {
                if (tee2SrcDisplay != null) {
                    tee2SrcDisplay.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps tc = pad.getCurrentCaps();
                        System.err.println("PROBE: tee2.src(display) → buffer (BGRx), caps=" + (tc != null ? tc.toString() : "null"));
                        return PadProbeReturn.OK;
                    });
                    System.err.println("PROBE INIT: tee2.src(display) probe installed");
                }
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: tee2 src probe failed: " + e.getMessage());
            }

            // 添加显示sink监控
            try {
                sinkSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    Caps sc = pad.getCurrentCaps();
                    System.err.println("PROBE: display-sink ← buffer, caps=" + (sc != null ? sc.toString() : "null"));
                    return PadProbeReturn.OK;
                });
                System.err.println("PROBE INIT: display-sink probe installed");
            } catch (Throwable e) {
                System.err.println("PROBE ERROR: display-sink probe failed: " + e.getMessage());
            }

            // 转换后分支: tee2 -> 慢放内存/磁盘 与 tee -> 录制分支
            if (!minimalDisplayOnly) {
                // 慢放内存分支
                slowPreValveMem = ElementFactory.make("valve", "slowPreValveMem");
                try { slowPreValveMem.set("drop", true); } catch (Throwable ignore) {}
                pipe.addMany(slowPreValveMem);
                Pad tee2SrcMem = tee2.getRequestPad("src_%u");
                Pad slowMemSink = slowPreValveMem.getStaticPad("sink");
                tee2SrcMem.link(slowMemSink);
                System.err.println("STEP7: 🔗 tee2.src(mem) -> slowPreValveMem.sink linked=" + slowMemSink.isLinked());
                slowPreValveMem.link(queue3);
                queue3.link(slowSink);

                // 慢放磁盘分支
                slowPreValveDisk = ElementFactory.make("valve", "slowPreValveDisk");
                try { slowPreValveDisk.set("drop", true); } catch (Throwable ignore) {}
                pipe.addMany(slowPreValveDisk);
                Pad tee2SrcDisk = tee2.getRequestPad("src_%u");
                Pad slowDiskSink = slowPreValveDisk.getStaticPad("sink");
                tee2SrcDisk.link(slowDiskSink);
                System.err.println("STEP7: 🔗 tee2.src(disk) -> slowPreValveDisk.sink linked=" + slowDiskSink.isLinked());
                slowPreValveDisk.link(queue4);
                Element.linkMany(queue4, conv2, je, slowValveDisk, mfs);

                // 录制分支（直存码流）
                Pad teeSrcRecord = tee.getRequestPad("src_%u");
                Pad queue2Sink = queue2.getStaticPad("sink");
                teeSrcRecord.link(queue2Sink);
                System.err.println("STEP7: 🔗 tee.src(record) -> queue2.sink linked=" + queue2Sink.isLinked());
                queue2.link(capValve);
                capValve.link(splitmux);
            } else {
                System.err.println("STEP7: ⏯️ Minimal mode: skip slow-mo and recording branches");
            }

            // 连接 newPad 到 jb 入口 - 关键的caps协商点
            Pad jbSinkPad = jb.getStaticPad("sink");

            // 🔧 关键修复：在连接前确保caps兼容性
            try {
                // 获取WebRTC pad的当前caps
                Caps webrtcCaps = newPad.getCurrentCaps();
                if (webrtcCaps == null) {
                    webrtcCaps = newPad.queryCaps(null); // 获取可能的caps
                }
                System.err.println("🔧 WebRTC pad caps: " + (webrtcCaps != null ? webrtcCaps.toString() : "null"));

                // 获取jitterbuffer sink pad的caps
                Caps jbCaps = jbSinkPad.queryCaps(null);
                System.err.println("🔧 JitterBuffer sink caps: " + (jbCaps != null ? jbCaps.toString() : "null"));

                // 检查caps兼容性
                if (webrtcCaps != null && jbCaps != null) {
                    Caps intersection = webrtcCaps.intersect(jbCaps);
                    if (intersection != null && !intersection.isEmpty()) {
                        System.err.println("🔧 Caps兼容性检查: ✅ 兼容");
                    } else {
                        System.err.println("🔧 Caps兼容性检查: ⚠️ 可能不兼容，但继续尝试连接");
                    }
                }
            } catch (Exception e) {
                System.err.println("🔧 Caps兼容性检查失败: " + e.getMessage());
            }

            // 执行pad连接
            try {
                newPad.link(jbSinkPad); // link方法抛出异常而不是返回值
                System.err.println("STEP7: 🔗 newPad -> jb.sink linked successfully, jbSinkPad.isLinked=" + jbSinkPad.isLinked());
            } catch (PadLinkException e) {
                System.err.println("❌ WebRTC pad连接到jitterbuffer失败！错误: " + e.getMessage() + " (result=" + e.getLinkResult() + ")");
                // 尝试强制caps协商
                try {
                    Caps webrtcCaps = newPad.getCurrentCaps();
                    if (webrtcCaps != null) {
                        System.err.println("🔧 尝试强制设置jitterbuffer caps: " + webrtcCaps.toString());
                    }
                } catch (Exception e2) {
                    System.err.println("🔧 强制caps设置失败: " + e2.getMessage());
                }
            } catch (Exception e) {
                System.err.println("❌ WebRTC pad连接异常: " + e.getMessage());
            }

            // 安装 jb 的探针，确认 RTP 缓冲是否进入/流出 jitterbuffer
            try {
                Pad jbSink = jb.getStaticPad("sink");
                if (jbSink != null) {
                    System.err.println("PROBE INIT: jb.sink installed");
                    jbSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps c = pad.getCurrentCaps();
                        System.err.println("PROBE: jb ← sink buffer (in), caps=" + (c != null ? c.toString() : "null"));
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: jb.sink pad=null");
                }
            } catch (Throwable ignore) {}
            try {
                Pad jbSrc = jb.getStaticPad("src");
                if (jbSrc != null) {
                    System.err.println("PROBE INIT: jb.src installed");
                    jbSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                        Caps c = pad.getCurrentCaps();
                        System.err.println("PROBE: jb → src buffer (out), caps=" + (c != null ? c.toString() : "null"));
                        return PadProbeReturn.OK;
                    });
                } else {
                    System.err.println("PROBE INIT: jb.src pad=null");
                }
            } catch (Throwable ignore) {}

            // 切到播放态
            jb.syncStateWithParent();
            if (fecdec != null) fecdec.syncStateWithParent();
            if (reddepay != null) reddepay.syncStateWithParent();
            depay.syncStateWithParent();
            parse.syncStateWithParent(); // 恢复parse同步
            h264caps1.syncStateWithParent();
            dec.syncStateWithParent();
            conv.syncStateWithParent();
            vraw.syncStateWithParent();
            tee.syncStateWithParent();
            queue1.syncStateWithParent();
            tee2.syncStateWithParent();
            h264caps2.syncStateWithParent();
            if (!minimalDisplayOnly) {
                queue2.syncStateWithParent();
                capValve.syncStateWithParent();
                splitmux.syncStateWithParent();
                slowPreValveMem.syncStateWithParent();
                queue3.syncStateWithParent();
                slowSink.syncStateWithParent();
                slowPreValveDisk.syncStateWithParent();
                queue4.syncStateWithParent();
                conv2.syncStateWithParent();
                je.syncStateWithParent();
                slowValveDisk.syncStateWithParent();
                mfs.syncStateWithParent();
            }
            try { sink.syncStateWithParent(); } catch (Throwable ignore) {}
            System.err.println("STEP7: 🔌 RTP→decode chain PLAYING (RED="+hasRed+", FEC="+hasFec+")");

        // 协商：on-negotiation-needed → createOffer → set local → POST → set remote
        webrtc.connect(new WebRTCBin.ON_NEGOTIATION_NEEDED() {
            @Override
            public void onNegotiationNeeded(Element elem) {
                WebRTCBin self = (WebRTCBin) elem;
                System.err.println("STEP2: 🔄 ON_NEGOTIATION_NEEDED triggered");

                try {
                    self.setStunServer("stun://stun.l.google.com:19302");
                    System.err.println("✅ STUN server set successfully");
                } catch (Throwable e) {
                    System.err.println("❌ Failed to set STUN server: " + e.getMessage());
                }
                // 使用带看门狗的创建流程，避免卡在 createOffer
                createOfferWithWatchdog(self, "negotiation-needed");
            }
        });



        System.err.println("STEP0: ▶️ pipeline.play() called");

        // 添加管道状态监控
        try {
            pipe.getBus().connect((Bus.STATE_CHANGED) (source, old, current, pending) -> {
                if (source == pipe) {
                    System.err.println("PIPELINE STATE: " + old + " → " + current + " (pending: " + pending + ")");
                    if (current == State.PLAYING) {
                        System.err.println("PIPELINE: ✅ Successfully reached PLAYING state");
                    } else if (current == State.NULL) {
                        System.err.println("PIPELINE: ❌ Pipeline stopped");
                    }
                }
            });

            pipe.getBus().connect((Bus.ERROR) (source, code, message) -> {
                System.err.println("PIPELINE ERROR: code=" + code + ", message=" + message + ", source=" + source);

                // 特殊处理ICE/网络相关错误
                if (source != null && source.getName() != null) {
                    String sourceName = source.getName();
                    if (sourceName.contains("queue")) {
                        // 抑制非致命的 queue 错误噪音，仅记录不触发恢复流程
                        System.err.println("NETWORK WARN: queue 错误已抑制: " + message + " (" + sourceName + ")");
                        return;
                    }
                    if (sourceName.contains("nicesrc")) {
                        System.err.println("NETWORK ERROR: 检测到ICE/网络数据流错误");
                        System.err.println("NETWORK ERROR: 源元素: " + sourceName);
                        System.err.println("NETWORK ERROR: 错误详情: " + message);

                        // 检查ICE连接状态
                        try {
                            Object iceState = webrtc.get("ice-connection-state");
                            Object connState = webrtc.get("connection-state");
                            System.err.println("NETWORK ERROR: ICE状态=" + iceState + ", 连接状态=" + connState);

                            // 触发自动恢复：ICE断开或失败，或出现内部数据流错误
                            boolean shouldRecover = false;
                            if (iceState != null) {
                                String s = iceState.toString();
                                shouldRecover = ("4".equals(s) || "5".equals(s));
                            }
                            if (!shouldRecover && message != null && message.toLowerCase().contains("internal data stream error")) {
                                shouldRecover = true;
                            }

                            long now = System.currentTimeMillis();
                            // 简单节流：10秒内不重复触发
                            if (shouldRecover && (now - lastNetworkCheckTime) > 10_000L) {
                                lastNetworkCheckTime = now;
                                System.err.println("NETWORK ERROR: ICE连接异常，尝试重新协商并重启ICE...");
                                try {
                                    // 优先请求ICE重启（如果webrtcbin支持）
                                    try {
                                        webrtc.emit("request-ice-restart");
                                        System.err.println("NETWORK RECOVERY: 已请求 request-ice-restart");
                                    } catch (Throwable ignore) {
                                        System.err.println("NETWORK RECOVERY: request-ice-restart 不可用，回退为重新协商");
                                    }
                                    // 回退：重新创建Offer进行协商
                                    createOfferWithWatchdog(webrtc, "auto-recover");
                                } catch (Throwable rex) {
                                    System.err.println("NETWORK RECOVERY: 触发恢复失败: " + rex.getMessage());
                                    rex.printStackTrace();
                                }
                            } else if (shouldRecover) {
                                System.err.println("NETWORK RECOVERY: 已在节流窗口内，跳过重复恢复触发");
                            }
                        } catch (Exception e) {
                            System.err.println("NETWORK ERROR: 无法获取ICE状态: " + e.getMessage());
                        }
                    }
                }
            });

            pipe.getBus().connect((Bus.WARNING) (source, code, message) -> {
                System.err.println("PIPELINE WARNING: code=" + code + ", message=" + message + ", source=" + source);
            });

            System.err.println("STEP0: ✅ Pipeline monitoring installed");
        } catch (Throwable e) {
            System.err.println("STEP0: ⚠️ Pipeline monitoring failed: " + e.getMessage());
        }

        pipe.play();
        System.err.println("STEP0: ✅ Pipeline play() completed");

        // Pipeline启动后添加transceiver
        System.err.println("STEP1: 📡 Pipeline started, adding video transceiver...");
        // 启动流活动监控
        startStreamActivityMonitor();

        // 直接在主线程中添加transceiver，避免Platform.runLater的问题
        try {
            // 音频已禁用 - 仅视频流以减少延迟和带宽消耗
            // Caps audioCaps = Caps.fromString("application/x-rtp,media=audio,payload=111,encoding-name=OPUS,clock-rate=48000");

            // 创建WebRTC视频caps - 必须使用application/x-rtp格式才能正确生成SDP
            // 修改H264配置以支持constrained-high profile，匹配iOS端发送的编码格式
            // profile-level-id=640c1f 对应 constrained-high profile, level 3.1
            // 同时支持多种profile以提高兼容性
            Caps videoCaps = Caps.fromString("application/x-rtp,media=video,payload=109,encoding-name=H264,clock-rate=90000,profile-level-id=640c1f,packetization-mode=1,level-asymmetry-allowed=1");

            // 音频transceiver已禁用 - 仅视频流
            // System.err.println("STEP1: ➕ add audio RECVONLY transceiver with caps: " + audioCaps.toString());
            // webrtc.emit("add-transceiver", 3, audioCaps);
            // System.err.println("STEP1: ✅ audio transceiver added");

            // 添加视频接收transceiver
            System.err.println("STEP1: ➕ add video RECVONLY transceiver with caps: " + videoCaps.toString());
            webrtc.emit("add-transceiver", 3, videoCaps);
            System.err.println("STEP1: ✅ video transceiver added, should trigger negotiation");

            // 延迟一点时间后检查是否需要手动触发
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒
                    System.err.println("STEP2: 🔄 Checking if negotiation was triggered naturally...");
                    // 如果1秒后还没有协商，手动触发
                    try {
                        System.err.println("STEP3: 🚀 Manually creating offer as fallback...");
                        createOfferWithWatchdog(webrtc, "manual-fallback");
                        System.err.println("STEP3: ✅ Fallback createOfferWithWatchdog invoked");
                    } catch (Throwable ex) {
                        System.err.println("STEP3: ❌ Fallback createOfferWithWatchdog threw: " + ex.getMessage());
                        ex.printStackTrace();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            System.err.println("STEP1: ❌ Failed to add transceiver: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 使用看门狗保护的 createOffer，若回调未在超时内触发则自动重试
    private void createOfferWithWatchdog(WebRTCBin bin, String context) {
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            System.err.println("STEP3: 📝 Creating SDP offer (" + context + ")...");
            bin.createOffer(new WebRTCBin.CREATE_OFFER() {
                @Override public void onOfferCreated(WebRTCSessionDescription offer) {
                    done.set(true);
                    System.err.println("STEP3: ✅ SDP offer created (" + context + "), processing...");
                    handleOfferAndAnswer(bin, offer);
                }
            });
        } catch (Throwable t) {
            System.err.println("STEP3: ❌ createOffer threw: " + t.getMessage());
        }
        // 选择用于看门狗的调度器：优先使用现有 performanceMonitor，否则使用临时调度器
        java.util.concurrent.ScheduledExecutorService watchdogScheduler = performanceMonitor;
        if (watchdogScheduler == null) {
            try {
                System.err.println("STEP3: ⚙️ performanceMonitor is null, using temporary watchdog scheduler...");
                watchdogScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "CreateOfferWatchdog");
                    t.setDaemon(true);
                    return t;
                });
            } catch (Throwable e) {
                System.err.println("STEP3: ❌ Failed to create temporary watchdog scheduler: " + e.getMessage());
            }
        }
        // 2秒看门狗；如未返回则重试一次
        try {
        watchdogScheduler.schedule(() -> {
            if (!done.get()) {
                System.err.println("STEP3: ⏱ createOffer timed out (" + context + "), retrying...");
                try {
                    bin.createOffer(new WebRTCBin.CREATE_OFFER() {
                        @Override public void onOfferCreated(WebRTCSessionDescription offer) {
                            System.err.println("STEP3: ✅ SDP offer created after retry (" + context + "), processing...");
                            handleOfferAndAnswer(bin, offer);
                        }
                    });
                } catch (Throwable tt) {
                    System.err.println("STEP3: ❌ createOffer retry failed: " + tt.getMessage());
                }
            }
        }, 2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable schedEx) {
            System.err.println("STEP3: ❌ Scheduling watchdog failed: " + schedEx.getMessage());
            schedEx.printStackTrace();
        }
    }

    // ---------------- 慢放控制 API ----------------
    // 是否允许在实时视图上覆盖显示慢放（默认关闭，改由元素2-2独立Pane显示）
    private boolean overlaySlowMoEnabled = false;

    /**
     * 启用/禁用在实时视图上的慢放覆盖显示。
     * 默认禁用：慢放播放仅在元素2-2的独立SlowMoPane中进行，避免影响实时画面。
     */
    public void setOverlaySlowMoEnabled(boolean enabled) {
        this.overlaySlowMoEnabled = enabled;
        if (!enabled) {
            // 关闭时确保覆盖层已清空并停止内存慢放播放器
            try {
                double w = getWidth();
                double h = getHeight();
                var gc = slowCanvas.getGraphicsContext2D();
                gc.clearRect(0, 0, w, h);
            } catch (Throwable ignore) {}
            if (slowPlayer != null) {
                try { slowPlayer.stop(); } catch (Throwable ignore) {}
                slowPlayer = null;
            }
            slowMoPlaying = false;
        }
    }
    public void startSlowMoCapture() {
        System.err.println("SLOWMO: ▶️ start capture");
        slowBuffer.clear();
        try { slowDiskBuffer.clear(); } catch (Throwable ignore) {}
        // 清空慢放目录，避免旧文件干扰
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("runtime", "slowmo");
            java.nio.file.Files.createDirectories(dir);
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                for (java.nio.file.Path p : ds) { try { java.nio.file.Files.deleteIfExists(p); } catch (Throwable ignore) {} }
            }
        } catch (Throwable ignore) {}
        slowMoCapturing = true;
        // 打开慢放磁盘分支阀门
        try { if (slowValveDisk != null) slowValveDisk.set("drop", false); } catch (Throwable ignore) {}
        try { if (slowPreValveDisk != null) slowPreValveDisk.set("drop", false); } catch (Throwable ignore) {}
        try { if (slowPreValveMem != null) slowPreValveMem.set("drop", false); } catch (Throwable ignore) {}
        // 打开录制分支阀门，开始不丢帧分段写盘
        try { if (capValve != null) capValve.set("drop", false); } catch (Throwable ignore) {}
        lastSlowDiskWriteMs = 0L;
        lastSlowMemPushMs = 0L;
        // 启动目录扫描器：将 multifilesink 写入的新文件推入 slowDiskBuffer
        startSlowDiskDirWatcher();
    }

    public void stopSlowMoCaptureAndPlay(int factor) {
        System.err.println("SLOWMO: ⏹ stop capture; request playback, factor=" + factor);
        slowMoCapturing = false;
        // 关闭慢放磁盘分支阀门
        try { if (slowValveDisk != null) slowValveDisk.set("drop", true); } catch (Throwable ignore) {}
        try { if (slowPreValveDisk != null) slowPreValveDisk.set("drop", true); } catch (Throwable ignore) {}
        try { if (slowPreValveMem != null) slowPreValveMem.set("drop", true); } catch (Throwable ignore) {}
        // 关闭录制分支阀门
        try { if (capValve != null) capValve.set("drop", true); } catch (Throwable ignore) {}
        // 停止目录扫描器
        stopSlowDiskDirWatcher();
        // 等待慢放磁盘缓冲区刷新，避免未写完导致播放缺帧
        waitForSlowDiskFlush(3000);
        if (overlaySlowMoEnabled) {
            startSlowMoPlayback(factor);
        } else {
            System.err.println("SLOWMO: overlay playback disabled; use SlowMoPane (element2-2)");
        }
    }

    public void stopSlowMoCapture() {
        System.err.println("SLOWMO: ⏹ stop capture");
        slowMoCapturing = false;
        try { if (slowValveDisk != null) slowValveDisk.set("drop", true); } catch (Throwable ignore) {}
        try { if (slowPreValveDisk != null) slowPreValveDisk.set("drop", true); } catch (Throwable ignore) {}
        try { if (capValve != null) capValve.set("drop", true); } catch (Throwable ignore) {}
        // 停止目录扫描器
        stopSlowDiskDirWatcher();
    }

    /**
     * 等待慢放磁盘写入队列清空，避免刚停止采集时快照不完整。
     * 最长等待 timeoutMs 毫秒，期间每10ms检查一次。
     */
    public void waitForSlowDiskFlush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        try {
            while (System.currentTimeMillis() < deadline) {
                if (slowIoExecutor.getQueue().isEmpty()) break;
                try { Thread.sleep(10); } catch (InterruptedException ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    public boolean isSlowMoCapturing() {
        return slowMoCapturing;
    }

    public java.util.List<FrameRingBuffer.FrameItem> getSlowMoSnapshot() {
        return slowBuffer.snapshot();
    }

    public java.util.List<DiskFrameRingBuffer.FrameItem> getSlowMoDiskSnapshot() {
        return slowDiskBuffer.snapshot();
    }

    /** 慢放采样写盘的时间间隔（毫秒） */
    public int getSlowCaptureIntervalMs() {
        return slowCaptureIntervalMs;
    }

    public int getSlowMoDiskCount() {
        // 元素2-2 独立慢放读取磁盘缓冲，计数返回磁盘帧数
        return slowDiskBuffer.size();
    }

    public void clearSlowMoBuffers() {
        try { slowBuffer.clear(); } catch (Exception ignore) {}
        try { slowDiskBuffer.clear(); } catch (Exception ignore) {}
    }

    public void startSlowMoPlayback(int factor) {
        if (!overlaySlowMoEnabled) {
            System.err.println("SLOWMO: overlay playback disabled; skip drawing on real-time view");
            return;
        }
        List<FrameRingBuffer.FrameItem> frames = slowBuffer.snapshot();
        if (frames.isEmpty()) {
            System.err.println("SLOWMO: ❌ no captured frames, playback aborted");
            return;
        }
        System.err.println("SLOWMO: ▶️ start playback, frames=" + frames.size() + ", factor=" + factor);
        if (slowPlayer != null) {
            slowPlayer.stop();
            slowPlayer = null;
        }
        slowPlayer = new SlowMoPlayer(frames, factor, fxImg -> {
            double viewW = getWidth();
            double viewH = getHeight();
            if (viewW <= 0 || viewH <= 0) return;
            var gc = slowCanvas.getGraphicsContext2D();
            gc.setImageSmoothing(false);
            gc.clearRect(0, 0, viewW, viewH);
            double imgW = fxImg.getWidth();
            double imgH = fxImg.getHeight();
            if (imgW <= 0 || imgH <= 0) return;
            double scale = Math.min(viewW / imgW, viewH / imgH);
            double drawW = imgW * scale;
            double drawH = imgH * scale;
            double x = (viewW - drawW) / 2.0;
            double y = (viewH - drawH) / 2.0;
            gc.drawImage(fxImg, x, y, drawW, drawH);
            lastSlowFx = fxImg;
        });
        slowMoPlaying = true;
        System.err.println("SLOWMO: ✅ playback started");
        slowPlayer.start();
    }

    public void setSlowMoFactor(int factor) {
        if (slowPlayer != null) slowPlayer.setFactor(factor);
    }

    public void stopSlowMoPlayback() {
        slowMoPlaying = false;
        System.err.println("SLOWMO: ⏹ stop playback");
        if (slowPlayer != null) {
            slowPlayer.stop();
            slowPlayer = null;
        }
        // 清空慢放覆盖层
        double w = getWidth();
        double h = getHeight();
        var gc = slowCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        lastSlowFx = null;
    }

    // ---- 慢放磁盘目录监听（扫描新文件并推入缓冲） ----
    private final java.util.concurrent.ScheduledExecutorService slowDiskWatcher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "SlowDiskWatcher"); t.setDaemon(true); return t; });
    private java.util.concurrent.ScheduledFuture<?> slowDiskWatchFuture;

    private void startSlowDiskDirWatcher() {
        stopSlowDiskDirWatcher();
        try {
            slowDiskWatchFuture = slowDiskWatcher.scheduleAtFixedRate(this::scanSlowmoDir, 0, 200, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignore) {}
    }

    private void stopSlowDiskDirWatcher() {
        try {
            if (slowDiskWatchFuture != null) {
                slowDiskWatchFuture.cancel(false);
                slowDiskWatchFuture = null;
            }
        } catch (Throwable ignore) {}
    }

    private void scanSlowmoDir() {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("runtime", "slowmo");
            java.util.List<java.nio.file.Path> files = new java.util.ArrayList<>();
            try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir, "*.{png,jpg,jpeg,webp}")) {
                for (java.nio.file.Path p : ds) { files.add(p); }
            } catch (Throwable ignore) {
                try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
                    for (java.nio.file.Path p : ds) { files.add(p); }
                } catch (Throwable ignored) {}
            }
            files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            java.util.Set<java.nio.file.Path> existing = new java.util.HashSet<>();
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

    // 流活动监控：若慢放采集中且超过阈值未收到新帧，则自动停止慢放并通知
    private void startStreamActivityMonitor() {
        stopStreamActivityMonitor();
        streamMonitorFuture = streamMonitor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long timeSinceLastFrame = now - lastFrameTimeMs;
            boolean active = timeSinceLastFrame < 3000; // 3秒内有新帧视为活跃
            boolean wasActive = streamActive;
            
            // 添加详细的调试日志
            System.err.println("🔍 流活动监控:");
            System.err.println("  - 当前时间: " + now);
            System.err.println("  - 最后帧时间: " + lastFrameTimeMs);
            System.err.println("  - 距离最后帧: " + timeSinceLastFrame + "ms");
            System.err.println("  - 之前状态: " + wasActive);
            System.err.println("  - 计算状态: " + active);
            
            streamActive = active;
            
            if (wasActive && !active && slowMoCapturing) {
                // 断流时自动停止慢放，并回调通知UI
                System.err.println("⚠️ 检测到断流，停止慢放采集");
                stopSlowMoCapture();
                if (onSlowMoStreamStopped != null) {
                    Platform.runLater(onSlowMoStreamStopped);
                }
            }
            if (wasActive != active && onStreamActiveChanged != null) {
                System.err.println("📡 流状态变化: " + wasActive + " -> " + active);
                Platform.runLater(() -> onStreamActiveChanged.accept(active));
            }
        }, 0, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopStreamActivityMonitor() {
        if (streamMonitorFuture != null) {
            streamMonitorFuture.cancel(false);
            streamMonitorFuture = null;
        }
    }

    public boolean isStreamActive() {
        return streamActive;
    }
    public void setOnStreamActiveChanged(java.util.function.Consumer<Boolean> c) {
        this.onStreamActiveChanged = c;
    }
    public void setOnSlowMoStreamStopped(Runnable r) {
        this.onSlowMoStreamStopped = r;
    }


    /**
     * 抓取实时滑窗窗口：以当前时刻为事件点，等待后向帧补齐到 postCount 或超时。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindow(int preCount, int postCount, long waitTimeoutMs) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);

        // 初始快照与事件点：当前最后一帧
        List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
        int eventIndex = Math.max(0, snapshot.size() - 1);
        int needEnd = eventIndex + safePost;

        // 等待后向帧到位
        while (System.currentTimeMillis() < deadline) {
            if (snapshot.size() > needEnd) break;
            try { Thread.sleep(60); } catch (InterruptedException ignore) {}
            snapshot = realtimeBuffer.snapshot();
        }

        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int start = Math.max(0, Math.min(eventIndex - safePre, size - 1));
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) window.add(snapshot.get(i));
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        boolean timedOut = (size <= needEnd) && (System.currentTimeMillis() >= deadline);
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, timedOut);
    }

    /**
     * 抓取实时滑窗窗口（锚定事件索引）：以指定的绝对事件索引为中心，返回 [event-pre, event+post] 范围内的窗口。
     * 不等待，仅基于当前 buffer 快照构造窗口；用于后台增量刷新。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindowAnchored(int eventAbsIndex, int preCount, int postCount) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex = Math.max(0, Math.min(eventAbsIndex, size - 1));
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) window.add(snapshot.get(i));
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        // anchored 模式不等待，因此 timedOut 恒为 false（由调用方控制整体超时逻辑）
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    /**
     * 抓取实时滑窗（按时间戳锚定）：将事件点固定为 anchorTs 对应的帧，窗口为 [event-pre, event+post]。
     * 通过在快照中查找 <= anchorTs 的最后一帧实现锚定，避免事件点随“最新帧”漂移。
     */
    public SnapshotWindowCollector.SnapshotWindowResult<FrameRingBuffer.FrameItem> collectRealtimeWindowAnchoredTs(long anchorTs, int preCount, int postCount) {
        int safePre = Math.max(0, preCount);
        int safePost = Math.max(0, postCount);
        List<FrameRingBuffer.FrameItem> snapshot = realtimeBuffer.snapshot();
        if (snapshot.isEmpty()) {
            return new SnapshotWindowCollector.SnapshotWindowResult<>(java.util.Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int eventIndex = 0;
        // 找到最后一个时间戳 <= anchorTs 的帧作为事件点；若不存在，则退化为最早帧，否则若 anchorTs >= 最新帧则为最后一帧
        if (anchorTs <= snapshot.get(0).timestamp) {
            eventIndex = 0;
        } else if (anchorTs >= snapshot.get(size - 1).timestamp) {
            eventIndex = size - 1;
        } else {
            for (int i = size - 1; i >= 0; i--) {
                if (snapshot.get(i).timestamp <= anchorTs) { eventIndex = i; break; }
            }
        }
        int start = Math.max(0, eventIndex - safePre);
        int end = Math.max(start, Math.min(eventIndex + safePost, size - 1));
        java.util.ArrayList<FrameRingBuffer.FrameItem> window = new java.util.ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) window.add(snapshot.get(i));
        int eventRel = Math.max(0, Math.min(window.size() - 1, eventIndex - start));
        return new SnapshotWindowCollector.SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    /**
     * 当前实时缓冲的帧数（用于计算初次点击时的事件绝对索引）。
     */
    public int getRealtimeBufferSize() {
        try {
            return realtimeBuffer.snapshot().size();
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** 最后一帧的时间戳（若无实时帧则返回 0） */
    public long getLastRealtimeTimestamp() {
        try {
            List<FrameRingBuffer.FrameItem> ss = realtimeBuffer.snapshot();
            if (ss == null || ss.isEmpty()) return 0L;
            return ss.get(ss.size() - 1).timestamp;
        } catch (Throwable ignore) {
            return 0L;
        }
    }
    public long getRealtimePushSubmitted() { return realtimePushSubmitted; }
    public long getRealtimePushCompleted() { return realtimePushCompleted; }
    public long getRealtimePushErrors() { return realtimePushErrors; }

    /**
     * 获取当前实时播放的最后一帧并封装为内存快照项（用于抓拍UI测试）。
     * 若当前没有实时帧则返回 null。
     */
    public FrameRingBuffer.FrameItem getLastRealtimeFrameItem() {
        Image imgFx = lastFrameFx;
        if (imgFx == null) return null;
        int w = (int) imgFx.getWidth();
        int h = (int) imgFx.getHeight();
        if (w <= 0 || h <= 0) return null;
        javafx.scene.image.PixelReader pr = imgFx.getPixelReader();
        int[] argb = new int[w * h];
        javafx.scene.image.WritablePixelFormat<java.nio.IntBuffer> fmt = javafx.scene.image.PixelFormat.getIntArgbPreInstance();
        pr.getPixels(0, 0, w, h, fmt, argb, 0, w);
        for (int i = 0; i < argb.length; i++) {
            argb[i] &= 0x00FFFFFF; // 丢弃 alpha，仅保留 RGB
        }
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64 * 1024);
            javax.imageio.ImageIO.write(bi, "png", baos);
            byte[] bytes = baos.toByteArray();
            return new FrameRingBuffer.FrameItem(bytes, System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对 TYPE_INT_RGB 图像进行深拷贝，确保像素数组不与源图共享。
     */
    private static BufferedImage deepCopyRgb(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] s = ((java.awt.image.DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] d = ((java.awt.image.DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        System.arraycopy(s, 0, d, 0, Math.min(s.length, d.length));
        return dst;
    }

    private void handleOfferAndAnswer(WebRTCBin self, WebRTCSessionDescription offer) {
        try {
            org.freedesktop.gstreamer.SDPMessage offerMsg = tryGetSdpMessage(offer);
            String sdpOffer = (offerMsg != null) ? offerMsg.toString() : tryGetSdpText(offer);
            sdpOffer = sdpOffer.replace("\r\n", "\n").replace("\n", "\r\n");

            // ⬇️ 打印完整 SDP（调试用）
            System.out.println("===== LOCAL OFFER SDP BEGIN =====");
            System.out.println(sdpOffer);
            System.out.println("===== LOCAL OFFER SDP END   =====");

            System.err.println("STEP4: 📤 calling set-local-description...");
            setLocalDescriptionCompat(self, offer);

            String answerText = postOfferToSRS(sdpOffer);

            // 🎯 从服务器Answer SDP中提取SSRC
            extractSsrcFromAnswerSdp(answerText);

            org.freedesktop.gstreamer.SDPMessage answerMsg = new org.freedesktop.gstreamer.SDPMessage();
            answerMsg.parseBuffer(answerText);
            WebRTCSessionDescription remote =
                    new WebRTCSessionDescription(WebRTCSDPType.ANSWER, answerMsg);
            System.err.println("STEP6: 📥 calling set-remote-description...");
            setRemoteDescriptionCompat(self, remote);

            System.out.println("STEP6: WebRTC set remote OK");
        } catch (Exception ex) {
            System.out.println("❌ WebRTC handleOfferAndAnswer error "+ex.getMessage());
            ex.printStackTrace();
        }
    }



    /* ===================== 兼容：不使用 Promise 的 setLocal/Remote ===================== */

    private static void setLocalDescriptionCompat(WebRTCBin bin, WebRTCSessionDescription desc) {
        try {
            System.err.println("STEP4: ▶️ set-local-description begin");
            desc.disown(); // 交给底层接管
            bin.emit("set-local-description", desc, null); // 第三参 GstPromise* = NULL
            System.err.println("STEP4: ✅ set-local-description emitted");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void setRemoteDescriptionCompat(WebRTCBin bin, WebRTCSessionDescription desc) {
        try {
            System.err.println("STEP6: ▶️ set-remote-description begin");
            desc.disown();
            bin.emit("set-remote-description", desc, null);
            System.err.println("STEP6: ✅ set-remote-description emitted");

            // 立即发送PLI请求获取关键帧
            sendPLIRequest(bin);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }



    private static void printNalStatus() {
        System.out.println("📊 NAL单元接收状态:");

    }
    private static boolean sendPLIRequest(WebRTCBin webrtcbin) {
        System.out.println("📡 检查NAL单元状态 (第" + (pliRetryCount + 1) + "次)");
        printNalStatus();

        try {
            // 检查WebRTC连接状态
            WebRTCPeerConnectionState connectionState = webrtcbin.getConnectionState();
            System.out.println("🔗 当前连接状态: " + connectionState);

            if (connectionState != WebRTCPeerConnectionState.CONNECTED) {
                System.out.println("⚠️ WebRTC连接未建立");
                return false;
            }

            // 统一采用正确的方式：向 webrtcbin 的视频 src pad 发送上行 GstForceKeyUnit 事件
            if (webrtcSrcPad != null) {
                try {
                    Structure s = new Structure("GstForceKeyUnit");
                    org.freedesktop.gstreamer.lowlevel.GstStructureAPI.GSTSTRUCTURE_API.gst_structure_set(
                            s, "timestamp", org.freedesktop.gstreamer.lowlevel.GType.INT64, -1L);
                    org.freedesktop.gstreamer.lowlevel.GstStructureAPI.GSTSTRUCTURE_API.gst_structure_set(
                            s, "all-headers", org.freedesktop.gstreamer.lowlevel.GType.BOOLEAN, true);
                    s.setInteger("count", 1);
                    Event ev = org.freedesktop.gstreamer.lowlevel.GstEventAPI.GSTEVENT_API
                            .gst_event_new_custom(EventType.CUSTOM_UPSTREAM, s);
                    boolean ok = webrtcSrcPad.sendEvent(ev);
                    System.out.println("📡 GstForceKeyUnit -> webrtcbin.src 结果: " + ok);
                    return  ok;

                } catch (Throwable t) {
                    System.err.println("⚠️ 发送GstForceKeyUnit失败: " + t.getMessage());
                }
            } else {
                System.out.println("⚠️ webrtcSrcPad 未就绪，无法发送GstForceKeyUnit");
            }

        } catch (Exception e) {
            System.err.println("❌ 关键帧请求失败: " + e.getMessage());
            e.printStackTrace();
        }

        return  false;
    }

    /* ===================== SDP 工具：兼容不同绑定的 getter 命名 ===================== */

    // 你的 SDPMessage 在 org.freedesktop.gstreamer 包里
    private static org.freedesktop.gstreamer.SDPMessage tryGetSdpMessage(WebRTCSessionDescription desc) {
        System.err.println("=== Trying to extract SDPMessage ===");

        String[] methodNames = {"getSdpMessage", "getSDPMessage", "sdpMessage", "getSessionDescription"};

        for (String methodName : methodNames) {
            try {
                Object result = desc.getClass().getMethod(methodName).invoke(desc);
                if (result instanceof org.freedesktop.gstreamer.SDPMessage) {
                    org.freedesktop.gstreamer.SDPMessage sdpMsg = (org.freedesktop.gstreamer.SDPMessage) result;
                    System.err.println("✅ Successfully extracted SDPMessage using method: " + methodName);
                    return sdpMsg;
                }
            } catch (Exception e) {
                System.err.println("❌ SDPMessage method " + methodName + " failed: " + e.getMessage());
            }
        }

        System.err.println("❌ All SDPMessage extraction methods failed");
        return null;
    }

    private static String tryGetSdpText(WebRTCSessionDescription desc) {
        System.err.println("=== Trying to extract SDP text ===");
        System.err.println("WebRTCSessionDescription class: " + desc.getClass().getName());

        // 列出所有可用的方法
        System.err.println("Available methods:");
        for (java.lang.reflect.Method method : desc.getClass().getMethods()) {
            if (method.getParameterCount() == 0) { // 只显示无参数方法
                System.err.println("  - " + method.getName() + "() -> " + method.getReturnType().getSimpleName());
            }
        }

        // 尝试各种可能的方法名
        String[] methodNames = {"getSdp", "getSDP", "sdp", "getSessionDescription", "toString"};

        for (String methodName : methodNames) {
            try {
                Object result = desc.getClass().getMethod(methodName).invoke(desc);
                if (result instanceof String) {
                    String sdpText = (String) result;
                    System.err.println("✅ Successfully extracted SDP using method: " + methodName);
                    System.err.println("SDP length: " + sdpText.length());
                    if (sdpText.length() > 100) {
                        return sdpText;
                    } else {
                        System.err.println("⚠️ SDP too short from method " + methodName + ": " + sdpText);
                    }
                }
            } catch (Exception e) {
                System.err.println("❌ Method " + methodName + " failed: " + e.getMessage());
            }
        }

        // 如果所有方法都失败，尝试通过 SDPMessage 获取
        org.freedesktop.gstreamer.SDPMessage sdpMsg = tryGetSdpMessage(desc);
        if (sdpMsg != null) {
            String sdpText = sdpMsg.toString();
            System.err.println("✅ Extracted SDP via SDPMessage.toString(), length: " + sdpText.length());
            return sdpText;
        }

        System.err.println("❌ All SDP extraction methods failed, using fallback");
        return String.valueOf(desc);
    }

    /* ===================== SRS 交互：POST Offer，拿 Answer 文本 ===================== */


    private String postOfferToSRS(String sdpOffer) throws Exception {
        // 确保 SDP 使用 CRLF 行尾
        String sdpCRLF = sdpOffer.replace("\r\n", "\n").replace("\n", "\r\n");

        String apiUrl = ("http://" + host + ":" + apiPort + "/rtc/v1/play/").trim().replace("`", "");
        // SRS streamurl 格式：webrtc://host:port/app/stream 或 webrtc://host/app/stream
        // 根据 SRS 文档，通常不需要在 streamurl 中包含端口号
        String streamurl = ("webrtc://" + host + "/" + urlEncode(app) + "/" + urlEncode(stream)).trim().replace("`", "");
        // 自动追加 vhost 参数（默认 vid-7gg4748，可通过 -Dsrs.vhost 覆盖）
        String vhost = System.getProperty("srs.vhost", "vid-7gg4748");
        if (vhost != null && !vhost.isEmpty()) {
            streamurl += streamurl.contains("?") ? ("&vhost=" + urlEncode(vhost)) : ("?vhost=" + urlEncode(vhost));
        }
        // 自动追加 eip 参数（默认使用 host，可通过 -Dsrs.eip 覆盖）
        String eip = System.getProperty("srs.eip", host);
        if (eip != null && !eip.isEmpty()) {
            streamurl += streamurl.contains("?") ? ("&eip=" + urlEncode(eip)) : ("?eip=" + urlEncode(eip));
        }

        // 调试输出
        System.err.println("=== SRS WebRTC Play Request ===");
        System.err.println("API URL: " + apiUrl);
        System.err.println("Stream URL: " + streamurl);
        System.err.println("SDP Offer length: " + sdpCRLF.length());
        System.err.println("SDP Offer preview: " + sdpCRLF.substring(0, Math.min(200, sdpCRLF.length())));

        // 验证必要参数
        if (sdpCRLF == null || sdpCRLF.trim().isEmpty()) {
            throw new IllegalArgumentException("SDP offer cannot be null or empty");
        }
        if (!sdpCRLF.contains("v=0")) {
            throw new IllegalArgumentException("Invalid SDP format: missing version line");
        }

        // SRS 期望的 JSON：必须带 api / streamurl / sdp
        // 注意：某些 SRS 版本可能对 JSON 格式要求严格
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("api", apiUrl);
        obj.addProperty("streamurl", streamurl);
        obj.addProperty("sdp", sdpCRLF);
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
        String json = gson.toJson(obj);

        System.err.println("Request JSON length: " + json.length());
        System.err.println("Request JSON preview: " + json.substring(0, Math.min(500, json.length())));
        // 额外字节级校验：打印前 128 字节的十六进制，确认是否存在 0x60（反引号）或异常空格
        int hexLen = Math.min(128, json.length());
        StringBuilder hex = new StringBuilder();
        for (int idx = 0; idx < hexLen; idx++) {
            hex.append(String.format("%02X ", (int) json.charAt(idx)));
        }
        System.err.println("Request JSON head HEX: " + hex);
        // 检查SDP是否包含视频/音频m-line，便于诊断SRS 400
        boolean sdpHasAudio = sdpCRLF.contains("\nm=audio ");
        boolean sdpHasVideo = sdpCRLF.contains("\nm=video ");
        System.err.println("SDP Offer 检查: hasAudio=" + sdpHasAudio + ", hasVideo=" + sdpHasVideo);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(10))  // 增加超时时间
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = resp.body();

        System.err.println("=== SRS Response ===");
        System.err.println("Status Code: " + resp.statusCode());
        System.err.println("Response Headers: " + resp.headers().map());
        System.err.println("Response Body: " + body);

        // 手动处理 3xx 重定向（POST 默认不跟随）
        if (resp.statusCode() / 100 == 3) {
            String loc = resp.headers().firstValue("location").orElse(null);
            if (loc != null && !loc.isEmpty()) {
                String redirectUrl = loc.startsWith("http") ? loc : "http://" + host + ":" + apiPort + (loc.startsWith("/") ? loc : "/" + loc);
                System.err.println("Following redirect to: " + redirectUrl);
                HttpRequest redirectReq = HttpRequest.newBuilder()
                        .uri(URI.create(redirectUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                resp = http.send(redirectReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                body = resp.body();
                System.err.println("=== SRS Response (redirect) ===");
                System.err.println("Status Code: " + resp.statusCode());
                System.err.println("Response Headers: " + resp.headers().map());
                System.err.println("Response Body: " + body);
            }
        }

        // 简单容错：非 2xx 或 code!=0 都报出来方便排查
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("SRS HTTP " + resp.statusCode() + ": " + body);
        }
        // SRS 正常返回形如：{"code":0,"sdp":"...","sessionid":"..."}
        int codeIdx = body.indexOf("\"code\":");
        if (codeIdx >= 0) {
            // 取 code 值
            int comma = body.indexOf(",", codeIdx);
            String codeStr = (comma > 0 ? body.substring(codeIdx + 7, comma) : body.substring(codeIdx + 7)).trim();
            try {
                int code = Integer.parseInt(codeStr.replaceAll("[^0-9-]", ""));
                if (code != 0) {
                    // WHEP 回退已禁用：当业务码非 0 时直接失败，保留原始响应便于定位问题
                    throw new IllegalStateException("SRS /rtc/v1/play business code=" + code + ", body=" + body);
                }
            } catch (NumberFormatException ignore) {
                // 忽略解析失败，继续解析 sdp
            }
        }
        // 抽 sdp
        int i = body.indexOf("\"sdp\":\"");
        if (i < 0) throw new IllegalStateException("answer JSON missing sdp: " + body);
        i += 7;
        int j = body.indexOf("\"", i);
        if (j < 0) throw new IllegalStateException("bad sdp field: " + body);
        String sdpEscaped = body.substring(i, j);
        return sdpEscaped
                .replace("\\r\\n", "\r\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }


    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    /**
     * 开始捕获视频帧
     * @param outputDir 输出目录
     * @param durationMs 捕获持续时间（毫秒）
     */
    public void startFrameCapture(String outputDir, long durationMs) {
        if (frameCapturer != null && frameCapturer.isCapturing()) {
            System.out.println("帧捕获已在进行中，请先停止当前捕获");
            return;
        }

        frameCapturer = new FrameCapturer(outputDir, durationMs);
        frameCapturer.startCapture();

        System.out.println("帧捕获已启动，输出目录: " + outputDir);
    }

    /**
     * 停止捕获视频帧
     */
    public void stopFrameCapture() {
        if (frameCapturer != null) {
            frameCapturer.stopCapture();
            System.out.println("帧捕获已停止");
        }
    }

    /**
     * 获取当前捕获的帧数
     */
    public int getCapturedFrameCount() {
        return frameCapturer != null ? frameCapturer.getFrameCount() : 0;
    }

    /**
     * 检查是否正在捕获帧
     */
    public boolean isCapturingFrames() {
        return frameCapturer != null && frameCapturer.isCapturing();
    }

    /**
     * 获取帧保存器状态
     */
    public String getFrameSaverStatus() {
        if (frameSaver != null) {
            return frameSaver.getMemoryInfo();
        }
        return "帧保存器未启动";
    }

    /**
     * 获取帧保存延迟统计信息
     */
    public FrameSaver.FrameDelayStats getFrameDelayStats() {
        if (frameSaver != null) {
            return frameSaver.getDelayStats();
        }
        return null;
    }

    /**
     * 停止帧保存器
     */
    public void stopFrameSaver() {
        if (frameSaver != null) {
            frameSaver.stop();
            System.out.println("帧保存器已停止");
        }
    }

    private void startPerformanceMonitoring() {
        performanceMonitor.scheduleAtFixedRate(() -> {
            try {
                // 内存使用情况
                long usedMemory = memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024);
                long maxMemory = memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024);

                // CPU负载（如果可用）
                double cpuLoad = -1;
                try {
                    // 尝试使用com.sun.management.OperatingSystemMXBean
                    if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                        cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad() * 100;
                    }
                } catch (Exception e) {
                    // 如果不支持，使用系统CPU负载
                    cpuLoad = osBean.getSystemLoadAverage();
                }

                // 获取帧延迟统计
                FrameSaver.FrameDelayStats delayStats = getFrameDelayStats();

                System.out.printf("[性能监控] 内存: %dMB/%dMB (%.1f%%), CPU: %.1f%%, 帧率: %.1fFPS, 平均延迟: %.1fms, 实时差距: %dms, 处理错误: %d%n",
                    usedMemory, maxMemory, (usedMemory * 100.0 / maxMemory),
                    cpuLoad >= 0 ? cpuLoad : 0.0,
                    delayStats.currentFPS,
                    delayStats.averageDelayMs,
                    delayStats.realtimeGapMs,
                    frameProcessingErrors
                );

                // 重置错误计数
                frameProcessingErrors = 0;

            } catch (Exception e) {
                System.err.println("性能监控异常: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    public void shutdown() {
        // 停止首屏PLI定时器
        if (firstScreenPliScheduledFuture != null) {
            firstScreenPliScheduledFuture.cancel(false);
            firstScreenPliScheduledFuture = null;
        }

        // 重置首屏相关标志位
        hasReceivedSps = false;
        hasReceivedPps = false;
        hasReceivedIdr = false;
        firstScreenComplete = false;
        remoteVideoSsrc = 0L;

        if (performanceMonitor != null && !performanceMonitor.isShutdown()) {
            performanceMonitor.shutdown();
        }
        try { slowIoExecutor.shutdownNow(); } catch (Exception ignore) {}
        try { realtimePushExecutor.shutdownNow(); } catch (Exception ignore) {}
        stopStreamActivityMonitor();
        try { streamMonitor.shutdownNow(); } catch (Exception ignore) {}
        stopFrameSaver();
        stopSlowMoPlayback();
        slowMoCapturing = false;
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            System.err.println("UI: layoutChildren size is zero, w=" + w + ", h=" + h);
            return;
        }
        // 仅在布局周期里同步 Canvas 尺寸，避免形成测量环路
        canvas.setWidth(w);
        canvas.setHeight(h);
        slowCanvas.setWidth(w);
        slowCanvas.setHeight(h);
        // 按当前尺寸重绘一帧（如有），保持视频等比缩放并居中显示，避免拉伸变形
        if (lastFrameFx != null) {
            var gc = canvas.getGraphicsContext2D();
            gc.setImageSmoothing(false);
            gc.clearRect(0, 0, w, h);
            double imgW = lastFrameFx.getWidth();
            double imgH = lastFrameFx.getHeight();
            if (imgW > 0 && imgH > 0) {
                double scale = Math.min(w / imgW, h / imgH);
                double drawW = imgW * scale;
                double drawH = imgH * scale;
                double x = (w - drawW) / 2.0;
                double y = (h - drawH) / 2.0;
                gc.drawImage(lastFrameFx, x, y, drawW, drawH);
            }
        }
        // 慢放覆盖层按当前尺寸重绘（如有）
        if (lastSlowFx != null) {
            var gc2 = slowCanvas.getGraphicsContext2D();
            gc2.setImageSmoothing(false);
            gc2.clearRect(0, 0, w, h);
            double imgW2 = lastSlowFx.getWidth();
            double imgH2 = lastSlowFx.getHeight();
            if (imgW2 > 0 && imgH2 > 0) {
                double scale2 = Math.min(w / imgW2, h / imgH2);
                double drawW2 = imgW2 * scale2;
                double drawH2 = imgH2 * scale2;
                double x2 = (w - drawW2) / 2.0;
                double y2 = (h - drawH2) / 2.0;
                gc2.drawImage(lastSlowFx, x2, y2, drawW2, drawH2);
            }
        }

    }

    // 🔍 辅助方法：获取H264 NAL单元类型描述
    private static String getNalTypeString(int nalType) {
        switch (nalType) {
            case 1: return "P-slice";
            case 2: return "A-slice";
            case 3: return "B-slice";
            case 4: return "C-slice";
            case 5: return "IDR-slice";
            case 6: return "SEI";
            case 7: return "SPS";
            case 8: return "PPS";
            case 9: return "AUD";
            case 10: return "End-of-sequence";
            case 11: return "End-of-stream";
            case 12: return "Filler-data";
            case 24: return "STAP-A";
            case 25: return "STAP-B";
            case 26: return "MTAP16";
            case 27: return "MTAP24";
            case 28: return "FU-A";
            case 29: return "FU-B";
            default: return "Unknown";
        }
    }

    // 🔍 在h264parse.src上安装NAL探针，检测7/8/5类型
    private void attachNalProbeOnParse(Element parseElement) {
        if (parseElement == null) {
            System.err.println("⚠️ attachNalProbeOnParse: parseElement为null");
            return;
        }

        try {
            Pad parseSrcPad = parseElement.getStaticPad("src");
            if (parseSrcPad == null) {
                System.err.println("⚠️ attachNalProbeOnParse: 无法获取parse.src pad");
                return;
            }

            parseSrcPad.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                try {
                    Buffer buffer = info.getBuffer();
                    if (buffer == null) return PadProbeReturn.OK;

                    // 获取当前caps信息
                    Caps currentCaps = pad.getCurrentCaps();
                    if (currentCaps != null) {
                        System.err.println("🔍 NAL探针 - 当前caps: " + currentCaps.toString());
                    }

                    ByteBuffer data = buffer.map(false);
                    if (data == null || data.remaining() < 4) return PadProbeReturn.OK;

                    System.err.println("🔍 NAL探针 - Buffer大小: " + data.remaining() + " bytes");

                    // 查找NAL单元起始码 (0x00000001 或 0x000001)
                    int nalStart = -1;
                    boolean isLongStartCode = false;
                    for (int i = 0; i <= data.remaining() - 4; i++) {
                        if (data.get(i) == 0x00 && data.get(i+1) == 0x00 &&
                            data.get(i+2) == 0x00 && data.get(i+3) == 0x01) {
                            nalStart = i + 4;
                            isLongStartCode = true;
                            break;
                        } else if (i <= data.remaining() - 3 &&
                                   data.get(i) == 0x00 && data.get(i+1) == 0x00 && data.get(i+2) == 0x01) {
                            nalStart = i + 3;
                            isLongStartCode = false;
                            break;
                        }
                    }

                    if (nalStart >= 0 && nalStart < data.remaining()) {
                        int nalHeader = data.get(nalStart) & 0xFF;
                        int nalType = nalHeader & 0x1F;
                        int nalRefIdc = (nalHeader >> 5) & 0x03;
                        int forbiddenZeroBit = (nalHeader >> 7) & 0x01;

                        System.err.println("🔍 NAL探针检测详情:");
                        System.err.println("  ├─ 起始码: " + (isLongStartCode ? "0x00000001 (4字节)" : "0x000001 (3字节)"));
                        System.err.println("  ├─ NAL Header: 0x" + String.format("%02X", nalHeader));
                        System.err.println("  ├─ NAL Type: " + nalType + " (" + getNalTypeString(nalType) + ")");
                        System.err.println("  ├─ NAL Ref IDC: " + nalRefIdc);
                        System.err.println("  ├─ Forbidden Zero Bit: " + forbiddenZeroBit);
                        System.err.println("  └─ 流格式: byte-stream (Annex B)");

                        // 检测关键NAL类型
                        switch (nalType) {
                            case 7: // SPS
                                if (!hasReceivedSps) {
                                    hasReceivedSps = true;
                                    System.err.println("✅ 首次接收到SPS (NAL type 7) - 序列参数集");
                                    // 尝试解析SPS基本信息
                                    if (nalStart + 3 < data.remaining()) {
                                        int profile = data.get(nalStart + 1) & 0xFF;
                                        int constraints = data.get(nalStart + 2) & 0xFF;
                                        int level = data.get(nalStart + 3) & 0xFF;
                                        System.err.println("  └─ SPS信息: Profile=" + profile + ", Constraints=0x" +
                                                         String.format("%02X", constraints) + ", Level=" + level);
                                    }
                                    checkFirstScreenComplete();
                                }
                                break;
                            case 8: // PPS
                                if (!hasReceivedPps) {
                                    hasReceivedPps = true;
                                    System.err.println("✅ 首次接收到PPS (NAL type 8) - 图像参数集");
                                    checkFirstScreenComplete();
                                }
                                break;
                            case 5: // IDR
                                if (!hasReceivedIdr) {
                                    hasReceivedIdr = true;
                                    System.err.println("✅ 首次接收到IDR (NAL type 5) - 即时解码刷新帧");
                                    checkFirstScreenComplete();
                                }
                                System.err.println("🎯 IDR帧检测 - 可以开始解码");
                                break;
                            case 1: // Non-IDR slice
                                System.err.println("📺 Non-IDR slice (NAL type 1) - P/B帧");
                                break;
                            case 6: // SEI
                                System.err.println("📋 SEI (NAL type 6) - 补充增强信息");
                                break;
                            case 9: // Access Unit Delimiter
                                System.err.println("🔄 Access Unit Delimiter (NAL type 9)");
                                break;
                            default:
                                System.err.println("❓ 其他NAL类型: " + nalType);
                                break;
                        }
                    } else {
                        System.err.println("⚠️ NAL探针 - 未找到有效的NAL起始码");
                        // 显示前16字节的十六进制内容用于调试
                        StringBuilder hex = new StringBuilder();
                        for (int i = 0; i < Math.min(16, data.remaining()); i++) {
                            hex.append(String.format("%02X ", data.get(i) & 0xFF));
                        }
                        System.err.println("  └─ 前16字节: " + hex.toString());
                    }

                    buffer.unmap();
                } catch (Exception e) {
                    System.err.println("⚠️ NAL探针异常: " + e.getMessage());
                    e.printStackTrace();
                }
                return PadProbeReturn.OK;
            });

            System.err.println("✅ 增强NAL探针已安装在parse.src pad上");
        } catch (Exception e) {
            System.err.println("❌ 安装NAL探针失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 检查首屏是否完成
    private void checkFirstScreenComplete() {
        System.err.println("🔍 检查首屏完成状态:");
        System.err.println("  ├─ SPS接收状态: " + (hasReceivedSps ? "✅" : "❌"));
        System.err.println("  ├─ PPS接收状态: " + (hasReceivedPps ? "✅" : "❌"));
        System.err.println("  ├─ IDR接收状态: " + (hasReceivedIdr ? "✅" : "❌"));
        System.err.println("  └─ 首屏完成状态: " + (firstScreenComplete ? "✅" : "❌"));

        if (hasReceivedSps && hasReceivedPps && hasReceivedIdr && !firstScreenComplete) {
            firstScreenComplete = true;
            System.err.println("🎉 首屏完成！已接收SPS+PPS+IDR，停止PLI定时器");

            // 停止PLI定时器
            if (firstScreenPliScheduledFuture != null) {
                firstScreenPliScheduledFuture.cancel(false);
                firstScreenPliScheduledFuture = null;
                System.err.println("✅ PLI定时器已停止");
            }

            // 输出解码就绪状态
            System.err.println("🎯 H.264解码器就绪状态:");
            System.err.println("  ├─ 序列参数集(SPS): 已接收");
            System.err.println("  ├─ 图像参数集(PPS): 已接收");
            System.err.println("  ├─ 即时解码刷新帧(IDR): 已接收");
            System.err.println("  └─ 解码器状态: 可以开始解码视频帧");
        } else if (!firstScreenComplete) {
            System.err.println("⏳ 首屏未完成，等待必要的NAL单元...");
            if (!hasReceivedSps) System.err.println("  ⏳ 等待SPS (序列参数集)");
            if (!hasReceivedPps) System.err.println("  ⏳ 等待PPS (图像参数集)");
            if (!hasReceivedIdr) System.err.println("  ⏳ 等待IDR (即时解码刷新帧)");
        }
    }

    /**
     * 通过WebRTC发送RTCP PLI请求
     */
    private boolean sendPliViaWebrtc() {


        return sendPLIRequest(webrtc);

    }




    /**
     * 启动首屏PLI定时器，定期发送PLI请求直到收到完整首屏
     */
    
    private void startFirstScreenPLITimer() {
        if (firstScreenPliScheduledFuture != null) {
            firstScreenPliScheduledFuture.cancel(false);
        }

        firstScreenComplete = false;
        hasReceivedSps = false;
        hasReceivedPps = false;
        hasReceivedIdr = false;

        // 使用 GStreamer 的执行器确保线程安全
        firstScreenPliScheduledFuture = Gst.getExecutor().scheduleAtFixedRate(new Runnable() {
            private int attempts = 0;
            private final int MAX_ATTEMPTS = 10; // 匹配SimpleWebRTCPlayer的重试次数

            @Override
            public void run() {
                if (firstScreenComplete || attempts >= MAX_ATTEMPTS) {
                    if (attempts >= MAX_ATTEMPTS) {
                        System.err.println("⚠️ 首屏PLI请求达到最大尝试次数，停止发送");
                    }
                    if (firstScreenPliScheduledFuture != null) {
                        firstScreenPliScheduledFuture.cancel(false);
                    }
                    return;
                }

                attempts++;
                
                // 🔧 添加严格的连接状态检查（绕过streamActive检查，因为PLI正是为了激活流）
                boolean connectionReady = checkWebRTCConnectionReady(true);
                System.err.println("🔄 发送首屏PLI请求 (第" + attempts + "次) - 连接就绪: " + connectionReady);
                
                if (!connectionReady) {
                    System.err.println("⚠️ WebRTC连接未就绪，跳过此次PLI发送");
                    return;
                }
                
                sendPliViaWebrtc();
            }
        }, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS); // 匹配SimpleWebRTCPlayer的200ms间隔

        System.err.println("⏰ 首屏PLI定时器已启动 - 使用GStreamer线程，200ms间隔");
    }
    
    // 🔧 检查WebRTC连接是否真正就绪
    private boolean checkWebRTCConnectionReady() {
        return checkWebRTCConnectionReady(false);
    }
    
    // 🔧 检查WebRTC连接是否真正就绪（支持绕过streamActive检查）
    private boolean checkWebRTCConnectionReady(boolean bypassStreamActiveCheck) {
        try {
            System.err.println("🔧 开始WebRTC连接状态检查... (绕过streamActive: " + bypassStreamActiveCheck + ")");
            
            // 1. 检查管道状态
            if (pipe == null || pipe.getState() != State.PLAYING) {
                System.err.println("🔧 ❌ 管道未处于PLAYING状态: " + (pipe != null ? pipe.getState() : "null"));
                return false;
            }
            System.err.println("🔧 ✅ 管道状态正常: PLAYING");
            
            // 2. 检查WebRTC元素状态
            if (webrtc == null || webrtc.getState() != State.PLAYING) {
                System.err.println("🔧 ❌ WebRTC元素未处于PLAYING状态: " + (webrtc != null ? webrtc.getState() : "null"));
                return false;
            }
            System.err.println("🔧 ✅ WebRTC元素状态正常: PLAYING");
            
            // 3. 检查关键元素是否存在且处于正确状态
            if (depay == null || jb == null || parse == null) {
                System.err.println("🔧 ❌ 关键元素未创建: depay=" + (depay != null) + ", jb=" + (jb != null) + ", parse=" + (parse != null));
                return false;
            }
            System.err.println("🔧 ✅ 关键元素已创建");
            
            // 4. 检查关键元素状态
            State depayState = depay.getState();
            State jbState = jb.getState();
            State parseState = parse.getState();
            
            if (depayState != State.PLAYING || jbState != State.PLAYING || parseState != State.PLAYING) {
                System.err.println("🔧 ❌ 关键元素状态异常: depay=" + depayState + ", jb=" + jbState + ", parse=" + parseState);
                return false;
            }
            System.err.println("🔧 ✅ 关键元素状态正常: 全部PLAYING");
            
            // 5. 检查媒体流状态（可绕过）
            if (!bypassStreamActiveCheck && !streamActive) {
                System.err.println("🔧 ❌ 媒体流未激活");
                return false;
            }
            if (bypassStreamActiveCheck && !streamActive) {
                System.err.println("🔧 ⚠️ 媒体流未激活，但已绕过检查（用于PLI发送）");
            } else {
                System.err.println("🔧 ✅ 媒体流已激活");
            }
            
            // 6. 检查首屏相关状态
            System.err.println("🔧 📊 首屏状态: SPS=" + hasReceivedSps + ", PPS=" + hasReceivedPps + ", IDR=" + hasReceivedIdr + ", 完成=" + firstScreenComplete);
            
            // 7. 检查webrtcSrcPad是否可用
            if (webrtcSrcPad == null) {
                System.err.println("🔧 ⚠️ webrtcSrcPad为null，尝试动态获取...");
                // 尝试动态获取
                if (webrtc != null) {
                    List<Pad> srcPads = webrtc.getSrcPads();
                    System.err.println("🔧 WebRTC源pad数量: " + srcPads.size());
                    
                    for (Pad pad : srcPads) {
                        String padName = pad.getName();
                        System.err.println("🔧 检查pad: " + padName);
                        
                        if (padName != null && (padName.contains("recv_rtp_src") || padName.contains("src_") || 
                            padName.contains("recv") || padName.contains("rtp_src"))) {
                            webrtcSrcPad = pad;
                            System.err.println("🔧 ✅ 动态获取到webrtcSrcPad: " + padName);
                            
                            // 检查pad的caps
                            Caps padCaps = pad.getCurrentCaps();
                            if (padCaps != null) {
                                System.err.println("🔧 📋 Pad caps: " + padCaps.toString());
                            }
                            break;
                        }
                    }
                }
                
                if (webrtcSrcPad == null) {
                    System.err.println("🔧 ❌ webrtcSrcPad仍为null");
                    return false;
                }
            } else {
                System.err.println("🔧 ✅ webrtcSrcPad已存在: " + webrtcSrcPad.getName());
            }
            
            // 8. 检查SSRC状态
            System.err.println("🔧 📊 远端视频SSRC: " + remoteVideoSsrc);
            
            System.err.println("🔧 ✅ WebRTC连接检查通过 - 所有条件满足");
            return true;
            
        } catch (Exception e) {
            System.err.println("🔧 ❌ 连接状态检查异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // 🔧 实现真正的RTCP PLI消息发送机制
    private boolean sendRtcpPliMessage() {
        if (webrtc == null) {
            System.err.println("❌ RTCP PLI: webrtc元素为null");
            return false;
        }

        return sendPLIRequest(webrtc);
    }
    

    

    
    private volatile int keyFrameRequestCount = 0;
    private volatile long lastKeyFrameRequestTime = 0;
    private Timer keyFrameRetryTimer;
    
    private void requestKeyFrameWithRetry() {
        sendPLIRequest(webrtc);
    }
    
    private void scheduleKeyFrameRetry(long delayMs) {
        if (keyFrameRetryTimer != null) {
            keyFrameRetryTimer.cancel();
        }
        keyFrameRetryTimer = new Timer(true);
        keyFrameRetryTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                requestKeyFrameWithRetry();
            }
        }, delayMs);
    }
    
    // 当收到IDR帧时调用此方法停止重试
    private void stopKeyFrameRetry() {
        keyFrameRequestCount = 0;
        if (keyFrameRetryTimer != null) {
            keyFrameRetryTimer.cancel();
            keyFrameRetryTimer = null;
        }
        System.err.println("🔑 ✅ 已收到关键帧，停止PLI重试");
    }




    private Element createH264Decoder() {
        Element decoder = null;
        try {
            decoder = ElementFactory.make("avdec_h264", "dec");
            System.err.println("STEP7: ✅ avdec_h264 created successfully");

            try {
                // 🔍 配置解码器属性 - 实时性优先
                decoder.set("max-errors", -1);        // 允许错误恢复
                decoder.set("skip-frame", 0);          // 不跳帧，保证流畅
                decoder.set("output-corrupt", true);   // 输出损坏帧，避免等待
                decoder.set("discard-corrupted-frames", false); // 不丢弃损坏帧
                // 注意：wait-for-keyframe 属性不被 avdec_h264 支持，已移除
                System.err.println("STEP7: ✅ avdec_h264 实时配置: 容错解码，不跳帧");
                System.err.println("STEP7: ✅ avdec_h264 configured with error tolerance");
            } catch (Throwable e) {
                System.err.println("STEP7: ⚠️ avdec_h264 config warning: " + e.getMessage());
            }
            System.err.println("STEP7: 🎯 decoder=avdec_h264");
            return decoder;
        } catch (Throwable e) {
            System.err.println("STEP7: ❌ avdec_h264 creation failed: " + e.getMessage());
        }

        // 尝试openh264dec
        try {
            decoder = ElementFactory.make("openh264dec", "dec");
            System.err.println("STEP7: 🎯 decoder=openh264dec (fallback)");
            return decoder;
        } catch (Throwable e) {
            System.err.println("STEP7: ❌ openh264dec creation failed: " + e.getMessage());
        }

        // 尝试d3d11h264dec
        try {
            decoder = ElementFactory.make("d3d11h264dec", "dec");
            System.err.println("STEP7: 🎯 decoder=d3d11h264dec (fallback2)");
            return decoder;
        } catch (Throwable e) {
            System.err.println("STEP7: ❌ d3d11h264dec creation failed: " + e.getMessage());
        }

        // 最后尝试强制创建avdec_h264
        System.err.println("STEP7: ❌ All H264 decoders failed, using software decoder");
        return ElementFactory.make("avdec_h264", "dec");
    }

    // 辅助方法：NAL类型名称
    private String getNalTypeName(int nalType) {
        switch (nalType) {
            case 1: return "P-slice";
            case 5: return "IDR";
            case 7: return "SPS";
            case 8: return "PPS";
            case 28: return "FU-A";
            default: return "Unknown(" + nalType + ")";
        }
    }



    // 标记SSRC是否已从SDP中提取，防止被覆盖
    private volatile boolean ssrcExtractedFromSdp = false;

    /**
     * 🎯 从服务器Answer SDP中提取SSRC
     * 服务器SDP包含类似 "a=ssrc:473648254 cname:m2H8N1A6YOEVRbs6" 的行
     */
    private void extractSsrcFromAnswerSdp(String answerSdp) {
        try {
            System.err.println("🎯 开始从Answer SDP中提取SSRC...");
            System.err.println("Answer SDP内容:\n" + answerSdp);
            
            // 查找 a=ssrc: 行
            String[] lines = answerSdp.split("\\r?\\n");
            for (String line : lines) {
                if (line.startsWith("a=ssrc:")) {
                    try {
                        // 提取SSRC值：a=ssrc:473648254 cname:...
                        String ssrcPart = line.substring(7); // 去掉 "a=ssrc:"
                        int spaceIndex = ssrcPart.indexOf(' ');
                        String ssrcStr = (spaceIndex > 0) ? ssrcPart.substring(0, spaceIndex) : ssrcPart;
                        
                        long extractedSsrc = Long.parseLong(ssrcStr);
                        
                        // 更新remoteVideoSsrc并标记已从SDP提取
                        long oldSsrc = remoteVideoSsrc;
                        remoteVideoSsrc = extractedSsrc;
                        ssrcExtractedFromSdp = true; // 🔒 防止被覆盖
                        
                        System.err.println("🎯 ✅ 成功从SDP提取SSRC: " + extractedSsrc + " (原值: " + oldSsrc + ")");
                        System.err.println("🎯 🔒 已标记SSRC为SDP提取，防止被覆盖");
                        System.err.println("🎯 SDP行: " + line);
                        return;
                        
                    } catch (NumberFormatException e) {
                        System.err.println("🎯 ⚠️ SSRC解析失败: " + line + " - " + e.getMessage());
                    }
                }
            }
            
            System.err.println("🎯 ⚠️ 未在Answer SDP中找到a=ssrc行");
            
        } catch (Exception e) {
            System.err.println("🎯 ❌ 提取SSRC异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🔧 分析STAP-A包，提取其中包含的SPS/PPS
     * STAP-A格式: NAL Header (1字节) + [NAL Size (2字节) + NAL Unit] * N
     */
    private void analyzeStapAPacket(byte[] rtpPayload, int payloadSize) {
        try {
            if (rtpPayload == null || payloadSize < 3) {
                System.err.println("  ❌ STAP-A包数据不足: size=" + payloadSize);
                return;
            }

            System.err.println("  🔍 STAP-A包分析开始 (payload size=" + payloadSize + ")");
            
            int pos = 1; // 跳过STAP-A头部 (第一个字节)
            int nalCount = 0;
            boolean foundSps = false, foundPps = false;
            
            while (pos + 2 < payloadSize) {
                // 🔧 边界检查：确保有足够字节读取NAL长度
                if (pos + 1 >= rtpPayload.length) {
                    System.err.println("    ⚠️ 到达payload边界，停止解析");
                    break;
                }
                
                // 读取NAL单元长度 (2字节，网络字节序)
                int nalLength = ((rtpPayload[pos] & 0xFF) << 8) | (rtpPayload[pos + 1] & 0xFF);
                pos += 2;
                
                // 🔧 严格边界检查
                if (pos + nalLength > payloadSize || pos + nalLength > rtpPayload.length || nalLength <= 0) {
                    System.err.println("    ⚠️ STAP-A包格式错误: nalLength=" + nalLength + 
                                     ", pos=" + pos + ", payloadSize=" + payloadSize + 
                                     ", rtpPayload.length=" + rtpPayload.length);
                    break;
                }
                
                // 🔧 边界检查：确保有足够字节读取NAL头部
                if (pos >= rtpPayload.length) {
                    System.err.println("    ⚠️ NAL头部超出边界");
                    break;
                }
                
                // 读取NAL单元头部
                int nalHeader = rtpPayload[pos] & 0xFF;
                int nalType = nalHeader & 0x1F;
                nalCount++;
                
                System.err.println("    └─ STAP-A内NAL #" + nalCount + ": type=" + nalType + 
                                 " (" + getNalTypeString(nalType) + "), length=" + nalLength + 
                                 ", header=0x" + String.format("%02X", nalHeader) + 
                                 ", pos=" + pos);
                
                // 🎯 关键：检测SPS/PPS
                if (nalType == 7) { // SPS
                    foundSps = true;
                    if (!hasReceivedSps) {
                        hasReceivedSps = true;
                        System.err.println("    ✅ 在STAP-A中发现SPS (NAL type 7)!");
                        
                        // 🔧 边界检查：解析SPS基本信息
                        if (nalLength >= 4 && pos + 3 < rtpPayload.length) {
                            int profile = rtpPayload[pos + 1] & 0xFF;
                            int constraints = rtpPayload[pos + 2] & 0xFF;
                            int level = rtpPayload[pos + 3] & 0xFF;
                            System.err.println("      └─ SPS信息: Profile=" + profile + 
                                             ", Constraints=0x" + String.format("%02X", constraints) + 
                                             ", Level=" + level);
                        } else {
                            System.err.println("      └─ SPS数据不足，无法解析详细信息");
                        }
                    }
                } else if (nalType == 8) { // PPS
                    foundPps = true;
                    if (!hasReceivedPps) {
                        hasReceivedPps = true;
                        System.err.println("    ✅ 在STAP-A中发现PPS (NAL type 8)!");
                    }
                } else if (nalType == 5) { // IDR
                    System.err.println("    🎯 在STAP-A中发现IDR (NAL type 5)!");
                    if (!hasReceivedIdr) {
                        hasReceivedIdr = true;
                        System.err.println("    ✅ 首次从STAP-A中接收到IDR帧!");
                    }
                }
                
                pos += nalLength;
            }
            
            System.err.println("  ✅ STAP-A包分析完成，共发现 " + nalCount + " 个NAL单元");
            System.err.println("    ├─ 包含SPS: " + (foundSps ? "✅" : "❌"));
            System.err.println("    └─ 包含PPS: " + (foundPps ? "✅" : "❌"));
            
            // 检查首屏完成状态
            if (foundSps || foundPps || hasReceivedIdr) {
                checkFirstScreenComplete();
            }
            
        } catch (Exception e) {
            System.err.println("  ❌ STAP-A包分析失败: " + e.getMessage());
            e.printStackTrace();
        }
    }



}


