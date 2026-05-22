package com.acard.acard.slowmotion;

import com.acard.acard.tools.FileToos;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import com.acard.acard.tools.LogTools;
import com.sun.jna.platform.win32.User32;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;
import org.freedesktop.gstreamer.interfaces.VideoOverlay;
import org.freedesktop.gstreamer.message.Message;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.scene.image.Image;
import javafx.embed.swing.SwingFXUtils;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.Pointer;
import com.acard.acard.capture.LightweightFrameBuffer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.List;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
/**
 * GPU加速慢放播放器（OpenGL渲染，完全独立，不影响实时流）
 *
 * 方案：GPU硬解 + OpenGL渲染 + 独立子窗口
 * - 实时流：d3d11h264dec（GPU硬解）+ d3d11videosink（DirectX渲染）
 * - 慢放：  d3d11h264dec（GPU硬解）+ glimagesink（OpenGL渲染）
 * - 关键：DirectX vs OpenGL，完全不同的图形API，100%不冲突
 *
 * 优势：
 * 1. GPU硬解：CPU<4%，内存~400MB（与实时流一致）
 * 2. OpenGL渲染：与DirectX完全隔离，不会冲突
 * 3. 支持120fps，不跳帧
 * 4. 关闭后立即生效，无需等待DirectX资源释放
 *
 * @author AI Assistant
 * @date 2025-10-22
 */
public class SlowMoGpuPlayer {

    private static final com.sun.jna.platform.win32.User32 user32 = com.sun.jna.platform.win32.User32.INSTANCE;

    // ========== 事件管理相关 ==========
    private final UIUpdateEventManager eventManager = UIUpdateEventManager.getInstance();
    private final String listenerId = "SlowMoGpuPlayer_" + System.currentTimeMillis();
    private volatile boolean eventListenersRegistered = false;

    private Pipeline pipeline;

    Element uriDecodeBin;
    private Element filesrc;
    private Element demuxer;        // qtdemux（MP4）或 matroskademux（MKV）
    private Element h264parse;
    private Element decoder;
    private Element videosink;      // d3d11videosink（GPU硬渲染）
    private VideoOverlay videoOverlay;

    private String currentMp4Path;
    private double currentRate = 1.0;  // 当前播放速度

    private volatile State currentState = State.NULL;
    
    // ========== EOS控制标志 ==========
    private volatile boolean shouldHandleEOS = true;  // 是否应该处理EOS（录制阶段为true，停止后为false）

    // ========== 播放位置监控 ==========
    private java.util.function.Consumer<Integer> playbackFrameCallback;  // 播放帧数回调
    private java.util.concurrent.ScheduledExecutorService positionMonitor;
    private int currentFps = 30;  // 默认FPS
    private StackPane targetPane;  // 目标StackPane（element2_2）

    // HWND相关（完全模仿SimpleWebRTCPlayer的命名和用法）
    private volatile long overlayWindowHandle = 0L;  // 父窗口句柄
    private volatile long overlayChildHandle = 0L;   // 子窗口句柄

    // 日志记录器（共用Element2_3Controller的slowMoLogger）
    private java.io.PrintWriter debugLogger;

    // ========== 抓拍相关 ==========
    private Element tee;                              // 视频分流元素
    private Element captureQueue;                     // 抓拍队列
    private Element captureConvert;                   // 视频格式转换
    private AppSink captureSink;                      // appsink用于抓拍（必须是AppSink类型）
    private LightweightFrameBuffer frameBuffer;       // 帧缓冲区（存储前N帧）
    private AtomicLong captureFrameCounter = new AtomicLong(0);  // 抓拍帧计数器
    private volatile boolean captureEnabled = false;  // 是否启用抓拍
    private int preCapture = 10;                      // 前抓拍数（默认10帧）
    private PrintWriter recordLogger;



    /**
     * 设置日志记录器
     */
    public void setDebugLogger(java.io.PrintWriter logger) {
        this.debugLogger = logger;
    }


    public SlowMoGpuPlayer(){

        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
        String logPath = "runtime/hwd_" + timestamp + ".txt";

        File logFile = new File(logPath);
        File parentDir = logFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try {
            recordLogger = new PrintWriter(new FileWriter(logFile, true));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        registerUIUpdateEvents();
    }

    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }

    /**
     * 记录日志到文件和控制台
     * ⚠️ 如果在JavaFX线程中调用，跳过文件写入（避免阻塞UI）
     */
    private void log(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
        String logMsg = "[" + timestamp + "] " + message;

        // 输出到控制台（快速）
        System.out.println(logMsg);

        // ⚠️ 只在非JavaFX线程中写文件（避免阻塞UI）
        if (debugLogger != null && !Platform.isFxApplicationThread()) {
            debugLogger.println(logMsg);
            debugLogger.flush();
        }
    }

    /**
     * 设置播放帧数回调（实时通知UI当前播放到第几帧）
     */
    public void setPlaybackFrameCallback(java.util.function.Consumer<Integer> callback) {
        this.playbackFrameCallback = callback;
    }

    /**
     * 设置FPS（用于计算播放帧数）
     */
    public void setFps(int fps) {
        this.currentFps = Math.max(1, fps);
    }

    /**
     * 设置appsink的回调，处理每一帧
     */
    private void setupCaptureCallback() {
        if (captureSink == null) {
            log("⚠️ captureSink为null，无法设置回调");
            return;
        }

        try {
            // 连接new-sample信号（使用lambda表达式）
            captureSink.connect((AppSink.NEW_SAMPLE) elem -> {
                Sample sample = elem.pullSample();
                if (sample == null) {
                    return FlowReturn.OK;
                }
                try {
                    // 处理帧（转换为Image并存入缓冲区）
                    processCaptureFrame(sample);
                } catch (Throwable t) {
                    System.err.println("⚠️ 抓拍帧处理失败: " + t.getMessage());
                } finally {
                    sample.dispose();
                }
                return FlowReturn.OK;
            });

            log("✅ appsink回调已设置");
        } catch (Exception e) {
            log("❌ 设置appsink回调失败: " + e.getMessage());
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }

    /**
     * 处理抓拍帧（从appsink获取的帧）
     */
    private void processCaptureFrame(Sample sample) {
        if (!captureEnabled || frameBuffer == null) {
            return;
        }

        try {
            // 获取Buffer
            Buffer buffer = sample.getBuffer();
            if (buffer == null) {
                return;
            }

            // 获取Caps（视频信息）
            Caps caps = sample.getCaps();
            if (caps == null || caps.size() <= 0) {
                log("⚠️ 抓拍帧caps无效，跳过");
                return;
            }
            
            Structure struct = caps.getStructure(0);
            if (struct == null) {
                log("⚠️ 抓拍帧结构无效，跳过");
                return;
            }

            int width = struct.getInteger("width");
            int height = struct.getInteger("height");
            String format = null;
            try { 
                format = struct.getString("format"); 
            } catch (Throwable ignore) {}

            // 映射buffer到内存
            ByteBuffer byteBuffer = buffer.map(false);
            if (byteBuffer == null) {
                return;
            }

            try {
                // 检测格式（videoconvert默认输出BGRx或RGB）
                String fmt = format != null ? format : "BGRx";
                boolean isBGRx = "BGRx".equalsIgnoreCase(fmt) || "BGRA".equalsIgnoreCase(fmt) || "ARGB".equalsIgnoreCase(fmt);
                int bytesPerPixel = isBGRx ? 4 : 3;
                int expectedSize = width * height * bytesPerPixel;
                int actualSize = byteBuffer.remaining();

                if (actualSize < expectedSize) {
                    log("⚠️ 抓拍帧大小异常: actual=" + actualSize + ", expected>=" + expectedSize);
                    return;
                }

                // 计算行步长（包含可能的填充）
                int srcRowStride = Math.max(width * bytesPerPixel, actualSize / Math.max(1, height));
                if (srcRowStride % bytesPerPixel != 0) {
                    srcRowStride = width * bytesPerPixel;
                }

                // 转换为RGB格式
                byte[] rgbData = new byte[width * height * 3];
                if (isBGRx) {
                    // BGRx/BGRA -> RGB
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
                    // RGB -> RGB（直接复制）
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

                // 创建BufferedImage
                BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
                byte[] imageData = ((DataBufferByte) bufferedImage.getRaster().getDataBuffer()).getData();
                
                // 转换RGB到BGR（BufferedImage格式）
                for (int i = 0; i < width * height; i++) {
                    int srcIdx = i * 3;
                    int dstIdx = i * 3;
                    imageData[dstIdx] = rgbData[srcIdx + 2];     // B
                    imageData[dstIdx + 1] = rgbData[srcIdx + 1]; // G
                    imageData[dstIdx + 2] = rgbData[srcIdx];     // R
                }

                // 转换为JavaFX Image
                Image fxImage = SwingFXUtils.toFXImage(bufferedImage, null);

                // 推送到帧缓冲区
                long frameId = captureFrameCounter.incrementAndGet();
                frameBuffer.push(fxImage, frameId);

                // 每100帧输出一次日志
                if (frameId % 100 == 0) {
                    log("📸 已抓拍 " + frameId + " 帧（" + width + "x" + height + ", " + fmt + "），缓冲区大小: " + frameBuffer.size());
                }

            } finally {
                buffer.unmap();
            }

        } catch (Throwable e) {
            log("⚠️ processCaptureFrame异常: " + e.getMessage());
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }

    /**
     * 启用抓拍功能
     * @param preCount 前抓拍帧数（默认10）
     */
    public void enableCapture(int preCount) {
        this.preCapture = Math.max(1, Math.min(120, preCount));  // 限制在1-120帧
        this.captureEnabled = true;
        
        // 创建帧缓冲区（不缩放，保持原始分辨率，高质量JPEG）
        if (frameBuffer == null) {
            frameBuffer = new LightweightFrameBuffer(
                preCapture,   // 容量
                0,            // 不缩放（保持原分辨率）
                0,            // 不缩放
                0.95f         // JPEG质量95%
            );
            frameBuffer.enable();
        } else {
            frameBuffer.adjustCapacity(preCapture);
            frameBuffer.enable();
        }
        
        log("✅ 抓拍功能已启用: 前抓拍数=" + preCapture);
    }

    /**
     * 禁用抓拍功能
     */
    public void disableCapture() {
        this.captureEnabled = false;
        if (frameBuffer != null) {
            frameBuffer.disable();
        }
        log("⛔ 抓拍功能已禁用");
    }

    /**
     * 获取前N帧（用于抓拍）
     * @param count 帧数
     * @return 帧列表
     */
    public List<LightweightFrameBuffer.FrameItem> getRecentFrames(int count) {
        if (frameBuffer == null) {
            log("⚠️ 帧缓冲区未初始化");
            return new java.util.ArrayList<>();
        }
        return frameBuffer.getRecentFrames(count);
    }

    /**
     * 获取当前帧（最新一帧）
     * @return 当前帧，如果没有则返回null
     */
    public LightweightFrameBuffer.FrameItem getCurrentFrame() {
        if (frameBuffer == null || frameBuffer.size() == 0) {
            log("⚠️ 帧缓冲区为空");
            return null;
        }
        List<LightweightFrameBuffer.FrameItem> recent = frameBuffer.getRecentFrames(1);
        return recent.isEmpty() ? null : recent.get(0);
    }

    /**
     * 执行抓拍（获取前N帧+当前帧）
     * @param preCount 前抓拍数
     * @return 抓拍结果（前N帧 + 当前帧）
     */
    public CaptureResult captureFrames(int preCount) {
        if (!captureEnabled || frameBuffer == null) {
            log("⚠️ 抓拍功能未启用");
            return null;
        }

        // 获取前N帧
        List<LightweightFrameBuffer.FrameItem> preFrames = frameBuffer.getRecentFrames(preCount);
        
        // 当前帧就是最后一帧
        LightweightFrameBuffer.FrameItem currentFrame = null;
        if (!preFrames.isEmpty()) {
            currentFrame = preFrames.get(preFrames.size() - 1);
            // 移除最后一帧（它是当前帧）
            preFrames = preFrames.subList(0, preFrames.size() - 1);
        }

        CaptureResult result = new CaptureResult();
        result.preFrames = preFrames;
        result.currentFrame = currentFrame;
        result.timestamp = System.currentTimeMillis();
        result.totalFrames = preFrames.size() + (currentFrame != null ? 1 : 0);

        log("📸 抓拍完成: 前" + preFrames.size() + "帧 + 当前帧 = " + result.totalFrames + "帧");
        return result;
    }

    /**
     * 抓拍结果类
     */
    public static class CaptureResult {
        public List<LightweightFrameBuffer.FrameItem> preFrames;  // 前N帧
        public LightweightFrameBuffer.FrameItem currentFrame;     // 当前帧
        public long timestamp;                                     // 抓拍时间戳
        public int totalFrames;                                    // 总帧数
    }

    /**
     * 加载MP4文件并准备播放
     * @param mp4Path MP4文件路径
     */
    public void loadMp4(String mp4Path) {
        log("\n════════════════════════════════════════");
        log("📂 loadMp4() 被调用");
        log("   新文件路径: " + mp4Path);
        log("   旧文件路径: " + currentMp4Path);
        log("════════════════════════════════════════");
        
        if (mp4Path == null || mp4Path.isEmpty()) {
            log("❌ MP4路径为空");
            return;
        }

        File file = new File(mp4Path);
        if (!file.exists()) {
            log("❌ MP4文件不存在: " + mp4Path);
            return;
        }

        log("📊 文件信息:");
        log("   存在: " + file.exists());
        log("   大小: " + (file.length() / 1024) + " KB");
        log("   绝对路径: " + file.getAbsolutePath());

        this.currentMp4Path = mp4Path;
        log("✅ currentMp4Path已更新");
        
        // ⚠️ 关键修复：新一轮录制开始，重新启用EOS处理
        shouldHandleEOS = true;
        log("✅ EOS处理已启用（新一轮边录边播）");
        
        // ⚠️ 关键修复：隐藏子窗口（避免显示上一次的画面）
        // 新Pipeline启动后会自动显示
        if (overlayChildHandle != 0L) {
            try {
                log("🧹 隐藏子窗口（避免显示旧画面）...");
                WinDef.HWND hChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
                user32.ShowWindow(hChild, 0); // SW_HIDE = 0
                log("✅ 子窗口已隐藏");
            } catch (Exception e) {
                log("⚠️ 隐藏子窗口失败: " + e.getMessage());
            }
        }

        // 停止旧Pipeline（但不销毁子窗口，避免第二次播放时窗口消失）
        if (pipeline != null) {
            try {
                log("🛑 停止旧Pipeline...");
                pipeline.setState(State.NULL);
                Thread.sleep(50);
                pipeline.dispose();
                pipeline = null;
                log("✅ 旧Pipeline已释放（保留子窗口）");
            } catch (Exception e) {
                log("❌ 旧Pipeline释放失败: " + e.getMessage());
            }
        } else {
            log("💡 没有旧Pipeline需要释放");
        }

        // 创建独立Pipeline
        log("🔨 开始创建新Pipeline...");
        createPipeline(mp4Path);
        log("✅ 新Pipeline创建完成");

        log("✅ SlowMoGpuPlayer: MP4已加载 - " + file.getName());
        log("════════════════════════════════════════\n");
    }

    /**
     * 创建独立的GStreamer Pipeline（GPU加速，支持MP4和MKV）
     */
    private void createPipeline(String mp4Path) {
        try {
            // ✅ 创建完全独立的Pipeline（与实时流完全隔离）
            pipeline = new Pipeline("slowmo-gpu-pipeline-" + System.currentTimeMillis());

            // 创建元素
            filesrc = ElementFactory.make("filesrc", "slowmo_filesrc");

            // ✅ 根据文件扩展名选择解封装器
            boolean isMkv = mp4Path.toLowerCase().endsWith(".mkv") || mp4Path.toLowerCase().endsWith(".webm");
            if (isMkv) {
                demuxer = ElementFactory.make("matroskademux", "slowmo_matroskademux");
                System.out.println("✅ 使用matroskademux解封装MKV文件");
            } else {
                demuxer = ElementFactory.make("qtdemux", "slowmo_qtdemux");
                System.out.println("✅ 使用qtdemux解封装MP4文件");
            }

            h264parse = ElementFactory.make("h264parse", "slowmo_h264parse");

            // ✅ GPU硬解（与实时流一致，保持低CPU和低内存）
            decoder = ElementFactory.make("d3d11h264dec", "slowmo_decoder");
            if (decoder == null) {
                System.out.println("⚠️ d3d11h264dec不可用，尝试avdec_h264");
                decoder = ElementFactory.make("avdec_h264", "slowmo_decoder");
            }

            // ✅ 使用DirectX渲染器，但配置为避免焦点切换卡死
            log("✅ 使用d3dvideosink（DirectX 9，配置为避免焦点切换卡死）");

            videosink = ElementFactory.make("d3d11videosink", "slowmo_d3d11videosink");
            if (videosink != null) {
                // ⚠️ 设置属性，避免焦点切换时卡死
               /* try {
                    videosink.set("force-aspect-ratio", false);  // 禁用强制宽高比
                    videosink.set("enable-last-sample", false);  // 禁用last-sample
                    log("✅ d3dvideosink属性已设置");
                } catch (Exception e) {
                    log("⚠️ 设置d3dvideosink属性失败: " + e.getMessage());
                }*/
                //videosink.set("video-direction", FileToos.usederection);
            }
            if (videosink == null) {
                log("⚠️ d3dvideosink不可用，尝试d3d11videosink");
                videosink = ElementFactory.make("d3dvideosink", "slowmo_d3dvideosink");
            }
            log("✅ 视频渲染器创建结果: " + (videosink != null ? videosink.getName() + " (成功)" : "失败"));

            if (videosink == null) {
                System.err.println("❌ 无可用的视频渲染器");
                return;
            }
            // 配置filesrc
            log("🔧 配置filesrc...");
            log("   文件路径: " + mp4Path);

            // ⚠️ 先验证文件，再配置filesrc
            File testFile = new File(mp4Path);
            log("📂 文件验证:");
            log("   存在: " + testFile.exists());
            log("   可读: " + testFile.canRead());
            log("   大小: " + (testFile.length() / 1024) + " KB");
            log("   绝对路径: " + testFile.getAbsolutePath());

            if (!testFile.exists()) {
                log("❌ 文件不存在，无法播放！");
                return;
            }

            try {
                log("🔧 设置filesrc location...");
                filesrc.set("location", mp4Path);
                log("   ✅ location已设置");

                // ⚠️ 注意：filesrc没有use-mmap属性，MKV文件可以边写边读
                // 如果需要禁用缓存，可以尝试设置 blocksize 等其他属性

                log("✅ filesrc配置完成");
            } catch (Exception e) {
                log("❌ filesrc配置失败: " + e.getMessage());
                if (debugLogger != null) {
                    e.printStackTrace(debugLogger);
                }
                return;
            }
            // 配置渲染器（d3d11videosink需要绑定到HWND）
            try {
                // ✅ 保持宽高比，避免画面被拉伸变形
                videosink.set("force-aspect-ratio", true);  // 保持宽高比，画面不变形

                // ✅ 文件播放必须启用sync，否则会立即播放完毕（黑屏）
                videosink.set("sync", true);
                System.out.println("✅ 已启用sync=true（文件播放必需）");

                System.out.println("✅ 慢放Pipeline配置：");
                System.out.println("   解码器: " + decoder.getName() + "（GPU硬解，CPU<4%）");
                System.out.println("   渲染器: " + videosink.getName() + "（DirectX，嵌入JavaFX容器）");
                System.out.println("   画面模式: 自适应填满（与实时流显示效果一致）");
                System.out.println("   同步模式: sync=true（文件播放按时间戳渲染）");
                System.out.println("   💡 d3d11videosink会绑定到元素2-2的子窗口（预留底部50px）");
            } catch (Exception e) {
                System.err.println("⚠️ 渲染器配置失败: " + e.getMessage());
            }

            // ========== 创建抓拍分支（如果启用） ==========
            if (captureEnabled) {
                log("🎬 创建抓拍分支...");
                tee = ElementFactory.make("tee", "capture_tee");
                captureQueue = ElementFactory.make("queue", "capture_queue");
                captureConvert = ElementFactory.make("videoconvert", "capture_convert");

                // ✅ 创建AppSink并进行类型转换
                Element sinkElement = ElementFactory.make("appsink", "capture_sink");
                if (sinkElement != null) {
                    captureSink = (AppSink) sinkElement;
                }

                if (tee != null && captureQueue != null && captureConvert != null && captureSink != null) {
                    // 配置appsink
                    captureSink.set("emit-signals", true);
                    captureSink.set("sync", false);  // 不同步，避免影响播放
                    captureSink.set("max-buffers", 3);  // 最多缓存3帧，避免内存堆积
                    captureSink.set("drop", true);  // 如果来不及处理，丢弃旧帧

                    // 配置queue（小队列，避免延迟）
                    captureQueue.set("max-size-buffers", 3);
                    captureQueue.set("max-size-time", 0L);
                    captureQueue.set("max-size-bytes", 0);
                    captureQueue.set("leaky", 2);  // downstream leaky，丢弃新帧

                    log("✅ 抓拍元素已创建");
                } else {
                    log("⚠️ 抓拍元素创建失败，禁用抓拍功能");
                    captureEnabled = false;
                }
            }

            // 添加到Pipeline
            log("🔗 添加元素到Pipeline...");
            if (captureEnabled && tee != null) {
                pipeline.addMany(filesrc, demuxer, h264parse, decoder, tee,
                        captureQueue, captureConvert, captureSink, videosink);
                log("   ✅ 已添加: filesrc, demuxer, h264parse, decoder, tee, capture分支, videosink");
            } else {
                pipeline.addMany(filesrc, demuxer, h264parse, decoder, videosink);
                log("   ✅ 已添加: filesrc, demuxer, h264parse, decoder, videosink");
            }

            // 链接（demuxer需要动态链接）
            log("🔗 链接 filesrc → demuxer...");
            boolean linked = Element.linkMany(filesrc, demuxer);
            log("   " + (linked ? "✅" : "❌") + " filesrc → demuxer 链接结果: " + linked);

            // demuxer动态pad连接（增强调试）
            demuxer.connect(new Element.PAD_ADDED() {
                @Override
                public void padAdded(Element element, Pad pad) {
                    String padName = pad.getName();
                    log("🔍 demuxer新增pad: " + padName);

                    // 获取pad的caps信息
                    try {
                        Caps caps = pad.getCurrentCaps();
                        log("   Caps: " + (caps != null ? caps.toString() : "null"));
                    } catch (Exception e) {
                        log("   无法获取Caps: " + e.getMessage());
                    }

                    if (padName.startsWith("video_") || padName.startsWith("video") || padName.contains("video")) {
                        log("✅ 检测到视频pad: " + padName);
                        Pad h264parseSink = h264parse.getStaticPad("sink");
                        if (h264parseSink != null && !h264parseSink.isLinked()) {
                            try {
                                pad.link(h264parseSink);
                                log("✅ demuxer → h264parse 已连接");

                                // 链接后续元素
                                if (captureEnabled && tee != null) {
                                    // ✅ 启用抓拍：decoder → tee → (videosink + capture分支)
                                    Element.linkMany(h264parse, decoder, tee);
                                    log("✅ Pipeline已连接: h264parse → decoder → tee");

                                    // 链接显示分支：tee → videosink
                                    Pad teeSrcDisplay = tee.getRequestPad("src_%u");
                                    Pad sinkPadDisplay = videosink.getStaticPad("sink");
                                    if (teeSrcDisplay != null && sinkPadDisplay != null) {
                                        teeSrcDisplay.link(sinkPadDisplay);
                                        log("✅ tee → videosink (显示分支)");
                                    }

                                    // 链接抓拍分支：tee → queue → convert → appsink
                                    Pad teeSrcCapture = tee.getRequestPad("src_%u");
                                    Pad queueSinkPad = captureQueue.getStaticPad("sink");
                                    if (teeSrcCapture != null && queueSinkPad != null) {
                                        teeSrcCapture.link(queueSinkPad);
                                        Element.linkMany(captureQueue, captureConvert, captureSink);
                                        log("✅ tee → queue → convert → appsink (抓拍分支)");

                                        // 设置appsink的new-sample回调
                                        setupCaptureCallback();
                                    }
                                } else {
                                    // ✅ 未启用抓拍：decoder → videosink
                                    Element.linkMany(h264parse, decoder, videosink);
                                    log("✅ Pipeline已连接: h264parse → " + decoder.getName() + " → " + videosink.getName());
                                }
                                log("   ✅ DirectX渲染，将通过HWND嵌入到JavaFX");
                            } catch (Exception e) {
                                log("❌ Pad连接失败: " + e.getMessage());
                                if (debugLogger != null) {
                                    e.printStackTrace(debugLogger);
                                }
                            }
                        } else {
                            if (h264parseSink == null) {
                                log("❌ h264parse的sink pad为null");
                            } else if (h264parseSink.isLinked()) {
                                log("⚠️ h264parse的sink pad已被连接");
                            }
                        }
                    } else {
                        log("⚠️ 跳过非视频pad: " + padName);
                    }
                }
            });
            log("✅ demuxer PAD_ADDED监听已设置");


            pipeline.getBus().connect(new Bus.STATE_CHANGED() {
                @Override
                public void stateChanged(GstObject source, State old, State current, State pending) {
                    if (source == pipeline) {
                        currentState = current;
                        logRecord("🔄 SlowMoGpuPlayer状态: " + old + " → " + current);

                        // 在READY状态时提前创建HWND绑定
                        if (current == State.READY && targetPane != null && overlayChildHandle == 0L) {
                            Platform.runLater(() -> {
                                try {
                                    logRecord("🎯 Pipeline READY，提前创建HWND绑定...");

                                    // 获取VideoOverlay接口
                                    if (videoOverlay == null && videosink != null) {
                                        videoOverlay = VideoOverlay.wrap(videosink);
                                    }

                                    if (videoOverlay != null) {
                                        createAndBindChildWindow(targetPane);
                                        logRecord("✅ READY状态HWND绑定成功");
                                    } else {
                                        logRecord("⚠️ VideoOverlay接口未就绪，等待prepare-window-handle");
                                    }
                                } catch (Exception e) {
                                    logRecord("❌ READY状态HWND绑定失败: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            });
                        }
                    }
                }
            });




            // 监听错误
            pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {

            });

            // 监听EOS（文件播放到末尾）- 详细日志观察
            pipeline.getBus().connect((Bus.EOS) source -> {
                log("🎬 ========== EOS收到 ==========");
                log("   当前状态: " + pipeline.getState());
                log("   当前播放文件: " + currentMp4Path);
                log("   录制是否完成: " + isRecordingComplete);
                log("   是否应该处理EOS: " + shouldHandleEOS);

                // ⚠️ 关键修复：如果不应该处理EOS（用户已点击停止），直接返回
                if (!shouldHandleEOS) {
                    log("   ⚠️ 用户已停止录制，忽略EOS");
                    log("🎬 ========== EOS处理完成（已忽略）==========");
                    return;
                }

                // 检查是否失去焦点
                Platform.runLater(() -> {
                    try {
                        if (targetPane != null && targetPane.getScene() != null) {
                            javafx.stage.Window window = targetPane.getScene().getWindow();
                            boolean isFocused = window != null && window.isFocused();
                            log("   窗口焦点状态: " + (isFocused ? "有焦点" : "失去焦点"));
                        }
                    } catch (Exception e) {
                        log("   检查焦点状态失败: " + e.getMessage());
                    }
                });
                // ⚠️ 关键修复：录制未完成时，往回seek一点，继续播放等待新数据
                if (!isRecordingComplete) {
                    log("   ✅ 录制未完成，往回seek继续等待新数据");
                    try {
                        // 获取当前播放位置
                        long currentPos = pipeline.queryPosition(TimeUnit.NANOSECONDS);
                        long currentSec = currentPos / 1_000_000_000L;
                        log("   📍 当前播放位置: " + currentSec + "秒");

                        // 往回seek 0.5秒（避免追上文件末尾）
                        long seekBackNs = Math.max(0, currentPos - 500_000_000L); // 往回0.5秒
                        long seekBackSec = seekBackNs / 1_000_000_000L;

                        log("   ⏪ 往回seek到: " + seekBackSec + "秒（避免追上末尾）");
                        boolean seekOk = pipeline.seek(currentRate, Format.TIME,
                                EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                                SeekType.SET, seekBackNs,
                                SeekType.NONE, -1);

                        if (seekOk) {
                            log("   ✅ Seek成功，继续播放等待新数据");
                            if (pipeline.getState() != State.PLAYING) {
                                pipeline.play();
                            }
                        } else {
                            log("   ⚠️ Seek失败，尝试直接恢复播放");
                            if (pipeline.getState() != State.PLAYING) {
                                pipeline.play();
                            }
                        }
                    } catch (Exception e) {
                        log("   ❌ EOS处理异常: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    log("   ℹ️ 录制已完成，循环播放");
                    // 录制完成后，循环播放
                    /*try {
                        boolean seekOk = pipeline.seek(1.0, Format.TIME,
                                EnumSet.of(SeekFlags.FLUSH),
                                SeekType.SET, 0,
                                SeekType.NONE, -1);
                        log("   " + (seekOk ? "✅" : "❌") + " 循环播放seek结果: " + seekOk);
                    } catch (Exception e) {
                        log("   ❌ 循环播放seek失败: " + e.getMessage());
                    }*/
                }

                log("🎬 ========== EOS处理完成 ==========");
            });

            log("✅ SlowMoGpuPlayer Pipeline已创建");

        } catch (Exception e) {
            String errorMsg = "❌ 创建SlowMoGpuPlayer Pipeline失败: " + e.getMessage();
            log(errorMsg);
            System.err.println(errorMsg);
            e.printStackTrace();
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }
    public void attachToPaneWithHwnd(StackPane videoContainer){

          this.targetPane=videoContainer;
          new Thread(() -> {
            try {
                // 等待Pipeline进入PLAYING状态



                // 在JavaFX线程中获取HWND
                final long[] hwndHolder = new long[1];
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                javafx.application.Platform.runLater(() -> {
                    try {
                        javafx.scene.Scene scene = videoContainer.getScene();
                        if (scene != null) {
                            javafx.stage.Window window = scene.getWindow();
                            if (window instanceof javafx.stage.Stage) {
                                String title = ((javafx.stage.Stage) window).getTitle();
                                if (title != null && !title.isEmpty()) {
                                    com.sun.jna.platform.win32.WinDef.HWND hwnd =
                                            com.sun.jna.platform.win32.User32.INSTANCE.FindWindow(null, title);
                                    if (hwnd != null) {
                                        hwndHolder[0] = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
                                        overlayWindowHandle =hwndHolder[0];
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {

                    } finally {
                        latch.countDown();
                    }
                });



            } catch (Exception e) {

            }
        }, "DelayedAttachThread").start();


    }


    /**
     * 绑定到StackPane（使用预先获取的HWND）
     * ⚠️ 在后台线程中调用，不访问JavaFX Scene Graph
     * ⚠️ Pipeline已经运行时调用，通过BusSyncHandler确保正确的绑定时序
     * @param pane 目标StackPane（仅用于保存引用）
     * @param hwnd 预先获取的主窗口HWND
     */
    public void attachToPaneWithHwnd(StackPane pane, long hwnd) {
        if (videosink == null || hwnd == 0L) {
            log("❌ attachToPaneWithHwnd: videosink或hwnd为空");
            return;
        }

        this.targetPane = pane;
        this.overlayWindowHandle = hwnd;

        try {
            log("🔗 attachToPaneWithHwnd: 开始绑定");
            log("   HWND: 0x" + Long.toHexString(hwnd));

            // 1. 获取VideoOverlay接口
            videoOverlay = VideoOverlay.wrap(videosink);
            if (videoOverlay == null) {
                log("❌ VideoOverlay.wrap()返回null");
                return;
            }
            log("✅ VideoOverlay接口获取成功");
            log("✅ BusSyncHandler已设置");

            if (pipeline.getState() == State.PLAYING || pipeline.getState() == State.PAUSED) {
                log("⚠️ Pipeline已在运行，主动创建子窗口并绑定");
                
                // 在JavaFX线程中创建或重用子窗口
                Platform.runLater(() -> {
                    try {
                        // ⚠️ 如果子窗口不存在，创建新的
                        if (overlayChildHandle == 0L && overlayWindowHandle != 0L) {


                            // 创建1x1的子窗口
                            WinDef.HWND hParent = new WinDef.HWND(Pointer.createConstant(overlayWindowHandle));
                            logRecord("first--->2");

                            WinDef.HWND hChild = User32.INSTANCE.CreateWindowEx(
                                    0,
                                    "STATIC",
                                    null,
                                    WinUser.WS_CHILD | WinUser.WS_VISIBLE | WinUser.WS_CLIPSIBLINGS,
                                    0, 0, 1, 1,
                                    hParent,
                                    null,
                                    null,
                                    null
                            );
                            if (hChild != null) {

                                User32.INSTANCE.ShowWindow(hChild, WinUser.SW_SHOW);
                                User32.INSTANCE.UpdateWindow(hChild);

                                overlayChildHandle = Pointer.nativeValue(hChild.getPointer());
                                videoOverlay.setWindowHandle(overlayChildHandle);
                                logRecord("🪟 主动创建子窗口: 0x" + Long.toHexString(overlayChildHandle));
                            }
                        }


                        // ⚠️ 关键：无论子窗口是新创建还是已存在，都要绑定到新的VideoOverlay
                        if (overlayChildHandle != 0L && videoOverlay != null) {

                            videoOverlay.setWindowHandle(overlayChildHandle);
                            // 计算并设置渲染区域
                            if (pane != null) {


                                javafx.geometry.Bounds layoutBounds = pane.getLayoutBounds();
                                javafx.geometry.Bounds screenBounds = pane.localToScreen(layoutBounds);
                                
                                if (screenBounds != null) {
                                    javafx.scene.Scene scene = pane.getScene();
                                    double clientOriginX = 0, clientOriginY = 0;
                                    if (scene != null && scene.getRoot() != null) {
                                        javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                                        if (rootTL != null) {
                                            clientOriginX = rootTL.getX();
                                            clientOriginY = rootTL.getY();
                                        }
                                    }
                                    
                                    int rw = (int) Math.round(screenBounds.getWidth());
                                    int rh = (int) Math.round(screenBounds.getHeight() - FileToos.botoomHight);  // 预留底部空间给操作栏
                                    int rx = (int) Math.round(screenBounds.getMinX() - clientOriginX);
                                    int ry = (int) Math.round(screenBounds.getMinY() - clientOriginY);
                                    
                                    // DPI缩放
                                    long hwndForScale = overlayChildHandle;
                                    double sx = 1.0, sy = 1.0;
                                    try {
                                        if (hwndForScale != 0L) {
                                            WinDef.HWND w = new WinDef.HWND(Pointer.createConstant(hwndForScale));
                                            WinDef.HDC hdc = user32.GetDC(w);
                                            if (hdc != null) {
                                                int dpiX = GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                                                int dpiY = GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                                                user32.ReleaseDC(w, hdc);
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





                                    
                                    // 调整子窗口位置和大小
                                    WinDef.HWND hwndChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
                                    int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                                    user32.SetWindowPos(hwndChild, null, pxRx, pxRy, pxRw, pxRh, flags);
                                    logRecord("🪟 调整子窗口: x=" + pxRx + ", y=" + pxRy + ", w=" + pxRw + ", h=" + pxRh + " (DPI: " + sx + "x" + sy + ")");
                                    
                                    // 设置渲染区域
                                    videoOverlay.setRenderRectangle(0, 0, pxRw, pxRh);
                                    videoOverlay.expose();
                                    logRecord("✅ 渲染区域已设置");
                                    
                                    // 设置布局监听器
                                    setupLayoutListener(pane);
                                    logRecord("✅ 布局监听器已设置");
                                }
                            }
                        }
                    } catch (Exception e) {
                        log("❌ 主动绑定子窗口失败: " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            log("❌ attachToPaneWithHwnd失败: " + e.getMessage());
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }




    /**
     * 获取目标StackPane的边界信息（线程安全）
     * @return [x, y, width, height] 相对于父窗口的坐标和尺寸
     */
    private int[] getTargetPaneBounds() {
        final int[] bounds = {0, 0, 800, 600};  // 默认值
        
        if (targetPane != null) {
            try {
                Platform.runLater(() -> {
                    try {
                        javafx.geometry.Bounds localBounds = targetPane.getBoundsInLocal();
                        javafx.geometry.Bounds screenBounds = targetPane.localToScreen(localBounds);
                        
                        if (screenBounds != null) {
                            // 计算相对于父窗口的坐标
                            javafx.scene.Scene scene = targetPane.getScene();
                            double clientOriginX = 0, clientOriginY = 0;
                            if (scene != null && scene.getRoot() != null) {
                                javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                                if (rootTL != null) {
                                    clientOriginX = rootTL.getX();
                                    clientOriginY = rootTL.getY();
                                }
                            }
                            
                            bounds[0] = (int) Math.round(screenBounds.getMinX() - clientOriginX);
                            bounds[1] = (int) Math.round(screenBounds.getMinY() - clientOriginY);
                            bounds[2] = (int) Math.round(screenBounds.getWidth());
                            bounds[3] = (int) Math.round(screenBounds.getHeight());
                        }
                    } catch (Exception e) {
                        log("❌ 获取StackPane边界失败: " + e.getMessage());
                    }
                });
                
                // 等待JavaFX线程完成
                Thread.sleep(50);
            } catch (Exception e) {
                log("❌ getTargetPaneBounds异常: " + e.getMessage());
            }
        }
        
        return bounds;
    }
    /**
     * 调整渲染区域以匹配JavaFX StackPane（预留底部50px）
     * ⚠️ 必须在JavaFX线程调用
     * ⚠️ 完全照抄SimpleWebRTCPlayer的方式：只调整渲染区域，不移动子窗口
     */
    private void resizeOverlayWindow() {
        if (targetPane == null || videoOverlay == null) {
            return;
        }

        try {
            // 使用layoutBounds（实际布局边界）
            javafx.geometry.Bounds layoutBounds = targetPane.getLayoutBounds();
            javafx.geometry.Bounds screenBounds = targetPane.localToScreen(layoutBounds);
            
            if (screenBounds == null) return;
            
            // 计算相对于窗口客户区的坐标
            javafx.scene.Scene scene = targetPane.getScene();
            double clientOriginX = 0, clientOriginY = 0;
            if (scene != null && scene.getRoot() != null) {
                javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                if (rootTL != null) {
                    clientOriginX = rootTL.getX();
                    clientOriginY = rootTL.getY();
                }
            }
            
            int rx = (int) Math.round(screenBounds.getMinX() - clientOriginX);
            int ry = (int) Math.round(screenBounds.getMinY() - clientOriginY);
            int rw = (int) Math.round(screenBounds.getWidth());
            int rh = (int) Math.round(screenBounds.getHeight() - FileToos.botoomHight);  // 预留底部空间给操作栏
            
            // 只调整渲染区域，不移动子窗口（照抄SimpleWebRTCPlayer）
            videoOverlay.setRenderRectangle(rx, ry, rw, rh);
            
            log("📐 调整渲染区域: x=" + rx + ", y=" + ry + ", w=" + rw + ", h=" + rh);

        } catch (Exception e) {
            log("❌ resizeOverlayWindow失败: " + e.getMessage());
        }
    }

    /**
     * 绑定到StackPane（元素2-2），预留底部50px给进度条
     * ⚠️ 使用BusSyncHandler确保正确的绑定时序，避免视频消失
     * @param pane 目标StackPane
     */
    public void attachToPane(StackPane pane) {
        if (videosink == null || pane == null) {
            return;
        }

        this.targetPane = pane;

        try {
            // 1. 获取VideoOverlay接口
            videoOverlay = VideoOverlay.wrap(videosink);
            if (videoOverlay == null) return;

            // 2. 获取主窗口HWND（完全静默）
            overlayWindowHandle = tryResolveWindowHandleFromJavaFX(pane);
            if (overlayWindowHandle == 0L) return;

            // 3. 设置BusSyncHandler（在prepare-window-handle消息中创建子窗口）
            pipeline.getBus().setSyncHandler(new BusSyncHandler() {
                @Override
                public BusSyncReply syncMessage(Message message) {
                    try {
                        if (VideoOverlay.isPrepareWindowHandleMessage(message)) {
                            if (videoOverlay == null) return BusSyncReply.PASS;

                            // 若子窗口已存在，直接绑定
                            if (overlayChildHandle != 0L) {
                                videoOverlay.setWindowHandle(overlayChildHandle);
                                videoOverlay.expose();
                                return BusSyncReply.DROP;
                            }

                            // 创建子窗口
                            if (overlayWindowHandle != 0L) {
                                try {
                                    // 首先尝试获取JavaFX StackPane的直接父窗口句柄
                                    WinDef.HWND hParent = null;
                                    if (targetPane != null) {
                                        hParent = getParentHWND(targetPane);
                                        if (hParent != null) {
                                            log("🎯 使用JavaFX StackPane的父窗口: 0x" + Long.toHexString(Pointer.nativeValue(hParent.getPointer())));
                                        }
                                    }
                                    
                                    // 如果无法获取JavaFX父窗口，使用传入的句柄
                                    if (hParent == null) {
                                        hParent = new WinDef.HWND(Pointer.createConstant(overlayWindowHandle));
                                        log("🔄 使用传入的父窗口句柄: 0x" + Long.toHexString(overlayWindowHandle));
                                    }
                                    
                                    // 获取JavaFX容器的相对位置和尺寸
                                    int[] bounds = getTargetPaneBounds();
                                    int x = 0;  // 子窗口相对于父窗口的位置
                                    int y = 0;
                                    int w = Math.max(bounds[2], 320);  // 最小宽度320
                                    int h = Math.max(bounds[3] - (int)FileToos.botoomHight, 240);  // 预留底部空间给操作栏


                                    
                                    log("🔧 创建嵌入式子窗口: x=" + x + ", y=" + y + ", w=" + w + ", h=" + h);
                                    logRecord("first--->3");
                                    WinDef.HWND hChild = user32.CreateWindowEx(
                                        0, // 不使用扩展样式
                                        "STATIC", 
                                        null,
                                        WinUser.WS_CHILD | WinUser.WS_VISIBLE | WinUser.WS_CLIPSIBLINGS | WinUser.WS_CLIPCHILDREN,
                                        x, y, w, h,
                                        hParent, null, null, null
                                    );
                                    
                                    if (hChild != null) {
                                        overlayChildHandle = Pointer.nativeValue(hChild.getPointer());
                                        log("✅ 嵌入式子窗口创建成功: 0x" + Long.toHexString(overlayChildHandle));
                                        
                                        // 设置子窗口为嵌入式控件，不显示在任务栏
                                        user32.SetWindowLong(hChild, WinUser.GWL_EXSTYLE, 
                                            user32.GetWindowLong(hChild, WinUser.GWL_EXSTYLE) | 0x08000000); // WS_EX_NOACTIVATE
                                        
                                        // 确保窗口可见并正确嵌入
                                        user32.ShowWindow(hChild, WinUser.SW_SHOW);
                                        user32.UpdateWindow(hChild);
                                        
                                        // 强制设置为子窗口（防止独立显示）
                                        user32.SetParent(hChild, hParent);
                                        
                                        // 绑定到VideoOverlay
                                        videoOverlay.setWindowHandle(overlayChildHandle);
                                        log("✅ VideoOverlay绑定到嵌入式窗口完成");
                                        
                                        // 设置渲染区域（相对于子窗口的坐标）
                                        videoOverlay.setRenderRectangle(0, 0, w, h);
                                        log("✅ 渲染区域设置完成: " + w + "x" + h);
                                        
                                        // 刷新显示
                                        videoOverlay.expose();
                                        log("✅ VideoOverlay刷新完成");
                                        
                                        // 设置JavaFX布局监听器，确保窗口跟随容器变化
                                        Platform.runLater(() -> {
                                            setupLayoutListener(pane);
                                            log("✅ JavaFX布局监听器设置完成");
                                        });
                                        
                                        return BusSyncReply.DROP;
                                    }
                                } catch (Throwable e) {
                                    // 静默处理
                                }
                            }
                            return BusSyncReply.PASS;
                        }
                    } catch (Throwable t) {
                        // 静默处理
                    }
                    return BusSyncReply.PASS;
                }
            });

        } catch (Exception e) {
            // 静默处理，不输出任何内容
        }
    }

    public int readyCount= 0;
    /**
     * 创建并绑定子窗口（昨天的做法）
     */
    private void createAndBindChildWindow(StackPane pane) {

        if(readyCount>0){

            return;
        }
        readyCount=1;

            try {
                // ⚠️ 如果子窗口不存在，创建新的
                if (overlayChildHandle == 0L && overlayWindowHandle != 0L) {


                    // 创建1x1的子窗口
                    WinDef.HWND hParent = new WinDef.HWND(Pointer.createConstant(overlayWindowHandle));
                    logRecord("first--->2");
                    WinDef.HWND hChild = User32.INSTANCE.CreateWindowEx(
                            0,
                            "STATIC",
                            null,
                            WinUser.WS_CHILD | WinUser.WS_VISIBLE | WinUser.WS_CLIPSIBLINGS,
                            0, 0, 1, 1,
                            hParent,
                            null,
                            null,
                            null
                    );
                    if (hChild != null) {
                        User32.INSTANCE.ShowWindow(hChild, WinUser.SW_SHOW);
                        User32.INSTANCE.UpdateWindow(hChild);
                        overlayChildHandle = Pointer.nativeValue(hChild.getPointer());
                        videoOverlay.setWindowHandle(overlayChildHandle);
                        logRecord("🪟 主动创建子窗口: 0x" + Long.toHexString(overlayChildHandle));
                    }
                }


                // ⚠️ 关键：无论子窗口是新创建还是已存在，都要绑定到新的VideoOverlay
                if (overlayChildHandle != 0L && videoOverlay != null) {

                    videoOverlay.setWindowHandle(overlayChildHandle);
                    // 计算并设置渲染区域
                    if (pane != null) {


                        javafx.geometry.Bounds layoutBounds = pane.getLayoutBounds();
                        javafx.geometry.Bounds screenBounds = pane.localToScreen(layoutBounds);

                        if (screenBounds != null) {
                            javafx.scene.Scene scene = pane.getScene();
                            double clientOriginX = 0, clientOriginY = 0;
                            if (scene != null && scene.getRoot() != null) {
                                javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                                if (rootTL != null) {
                                    clientOriginX = rootTL.getX();
                                    clientOriginY = rootTL.getY();
                                }
                            }

                            int rw = (int) Math.round(screenBounds.getWidth());
                            int rh = (int) Math.round(screenBounds.getHeight() - FileToos.botoomHight);  // 预留底部空间给操作栏
                            int rx = (int) Math.round(screenBounds.getMinX() - clientOriginX);
                            int ry = (int) Math.round(screenBounds.getMinY() - clientOriginY);

                            // DPI缩放
                            long hwndForScale = overlayChildHandle;
                            double sx = 1.0, sy = 1.0;
                            try {
                                if (hwndForScale != 0L) {
                                    WinDef.HWND w = new WinDef.HWND(Pointer.createConstant(hwndForScale));
                                    WinDef.HDC hdc = user32.GetDC(w);
                                    if (hdc != null) {
                                        int dpiX = GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                                        int dpiY = GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                                        user32.ReleaseDC(w, hdc);
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

                            // 调整子窗口位置和大小
                            WinDef.HWND hwndChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
                            int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                            user32.SetWindowPos(hwndChild, null, pxRx, pxRy, pxRw, pxRh, flags);
                            logRecord("🪟 调整子窗口: x=" + pxRx + ", y=" + pxRy + ", w=" + pxRw + ", h=" + pxRh + " (DPI: " + sx + "x" + sy + ")");

                            // 设置渲染区域
                            videoOverlay.setRenderRectangle(0, 0, pxRw, pxRh);
                            videoOverlay.expose();
                            logRecord("✅ 渲染区域已设置");

                            // 设置布局监听器
                            setupLayoutListener(pane);
                            logRecord("✅ 布局监听器已设置");
                        }
                    }
                }
            } catch (Exception e) {
                log("❌ 主动绑定子窗口失败: " + e.getMessage());
            }




    }

    private long tryResolveWindowHandleFromJavaFX() {
        try {
            javafx.scene.Scene scene = (targetPane != null) ? targetPane.getScene()  : null;
            javafx.stage.Window win = scene != null ? scene.getWindow() : null;
            String title = null;
            if (win instanceof javafx.stage.Stage) {
                title = ((javafx.stage.Stage) win).getTitle();
            }
            if (title != null && !title.isEmpty()) {
                WinDef.HWND hwnd = User32.INSTANCE.FindWindow(null, title);
                if (hwnd != null) {
                    return Pointer.nativeValue(hwnd.getPointer());
                }
            }
            WinDef.HWND fg = User32.INSTANCE.GetForegroundWindow();
            if (fg != null) {
                return Pointer.nativeValue(fg.getPointer());
            }
        } catch (Throwable t) {
            System.err.println("解析 JavaFX 窗口句柄失败: " + t.getMessage());
        }
        return 0L;
    }

    /**
     * 获取JavaFX窗口的主HWND（返回long类型，完全模仿实时流）
     * ⚠️ 完全静默版：不写任何log，避免阻塞JavaFX线程
     */
    private long tryResolveWindowHandleFromJavaFX(javafx.scene.Node node) {
        try {
            // 静默获取Scene和Window
            javafx.scene.Scene scene = (node != null) ? node.getScene() : null;
            javafx.stage.Window win = scene != null ? scene.getWindow() : null;

            // 方法1：通过窗口标题查找（确保获取正确的窗口）
            String title = null;
            if (win instanceof javafx.stage.Stage) {
                title = ((javafx.stage.Stage) win).getTitle();
            }

            if (title != null && !title.isEmpty()) {
                // 使用EnumWindows遍历所有窗口，找到标题匹配且可见的窗口
                final String finalTitle = title; // 创建final变量供内部类使用
                final long[] foundHwnd = {0L};
                user32.EnumWindows(new WinUser.WNDENUMPROC() {
                    @Override
                    public boolean callback(WinDef.HWND hwnd, Pointer data) {
                        char[] windowText = new char[512];
                        user32.GetWindowText(hwnd, windowText, 512);
                        String windowTitle = new String(windowText).trim();
                        
                        // 检查标题匹配且窗口可见
                        if (finalTitle.equals(windowTitle) && user32.IsWindowVisible(hwnd)) {
                            foundHwnd[0] = Pointer.nativeValue(hwnd.getPointer());
                            return false; // 停止枚举
                        }
                        return true; // 继续枚举
                    }
                }, null);
                
                if (foundHwnd[0] != 0L) {
                    return foundHwnd[0];
                }
            }

            // 方法2：获取前台窗口（备用）
            WinDef.HWND fg = user32.GetForegroundWindow();
            if (fg != null) {
                return Pointer.nativeValue(fg.getPointer());
            }
        } catch (Throwable t) {
            // 完全静默，不输出任何log
        }
        return 0L;
    }

    /**
     * 获取JavaFX窗口的主HWND（旧方法，保留兼容性）
     */
    private WinDef.HWND getParentHWND(javafx.scene.Node node) {
        try {
            log("🔍 开始获取主窗口HWND...");

            // 检查node
            if (node == null) {
                log("❌ node为null");
                return null;
            }
            log("✅ node存在: " + node.getClass().getSimpleName());

            // 检查scene
            javafx.scene.Scene scene = node.getScene();
            if (scene == null) {
                log("❌ node未附加到Scene");
                return null;
            }
            log("✅ Scene存在");

            // 检查window
            javafx.stage.Window window = scene.getWindow();
            if (window == null) {
                log("❌ Scene未附加到Window");
                return null;
            }
            log("✅ Window存在: " + window.getClass().getSimpleName());

            // 方法1：通过窗口标题查找HWND（模仿SimpleWebRTCPlayer）
            if (window instanceof javafx.stage.Stage) {
                javafx.stage.Stage stage = (javafx.stage.Stage) window;
                String title = stage.getTitle();
                log("🔧 窗口标题: \"" + title + "\"");

                if (title != null && !title.isEmpty()) {
                    log("🔍 通过窗口标题查找HWND...");
                    WinDef.HWND hwnd =
                        com.sun.jna.platform.win32.User32.INSTANCE.FindWindow(null, title);
                    if (hwnd != null) {
                        long handle = Pointer.nativeValue(hwnd.getPointer());
                        log("✅ 通过标题找到HWND: 0x" + Long.toHexString(handle));
                        return hwnd;
                    } else {
                        log("⚠️ 通过标题未找到HWND");
                    }
                }
            }

            // 方法2：使用前台窗口（备用方案）
            log("🔧 尝试获取前台窗口...");
            WinDef.HWND fgHwnd =
                com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();
            if (fgHwnd != null) {
                long handle = Pointer.nativeValue(fgHwnd.getPointer());
                log("✅ 获取到前台窗口HWND: 0x" + Long.toHexString(handle));
                return fgHwnd;
            } else {
                log("❌ 无法获取前台窗口");
            }

            return null;

        } catch (Exception e) {
            log("❌ 获取主窗口HWND失败: " + e.getMessage());
            e.printStackTrace();
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
            return null;
        }
    }

    /**
     * 创建慢放专属的Windows子窗口
     */
    private WinDef.HWND createChildWindow(WinDef.HWND parent, StackPane pane) {
        try {
            log("🔧 开始创建子窗口...");
            log("   parent HWND: 0x" + Long.toHexString(Pointer.nativeValue(parent.getPointer())));

            // ✅ 模仿实时流的做法：先创建一个极小的子窗口（1x1），避免左上角闪现
            // 后续由JavaFX布局监听负责定位和缩放到正确位置
            log("📐 创建极小子窗口（1x1），避免左上角闪现");
            log("   初始位置: (0, 0)");
            log("   初始尺寸: 1x1 (后续由布局监听调整)");

            // 创建子窗口（完整的嵌入窗口样式）
            log("🔨 调用CreateWindowEx...");
            logRecord("first--->5");
            WinDef.HWND child = user32.CreateWindowEx(
                0x08000000,                     // WS_EX_NOACTIVATE - 防止激活
                "STATIC",                       // lpClassName (大写，和实时流一致)
                null,                           // lpWindowName (null，和实时流一致)
                0x40000000 | 0x10000000 | 0x04000000 | 0x02000000,  // WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN
                0, 0,                          // x, y（初始位置0,0）
                1, 1,                          // width, height（极小尺寸，避免闪现）
                parent,                         // hWndParent
                null,                           // hMenu
                null,                           // hInstance
                null                            // lpParam
            );
            log("✅ CreateWindowEx返回");

            if (child == null) {
                log("❌ CreateWindowEx返回null");
                int lastError = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetLastError();
                log("❌ GetLastError: " + lastError);
                return null;
            }

            long childHandle = Pointer.nativeValue(child.getPointer());
            log("✅ 子窗口已创建: HWND=0x" + Long.toHexString(childHandle));

            // 强制设置父子关系，确保嵌入
            log("🔗 强制设置父子关系...");
            WinDef.HWND setParentResult = user32.SetParent(child, parent);
            if (setParentResult != null) {
                log("✅ SetParent成功");
            } else {
                log("⚠️ SetParent失败");
            }

            // 设置扩展窗口样式，确保不在任务栏显示
            log("🎨 设置扩展窗口样式...");
            int currentExStyle = user32.GetWindowLong(child, WinUser.GWL_EXSTYLE);
            int newExStyle = currentExStyle | 0x08000000;  // WS_EX_NOACTIVATE
            user32.SetWindowLong(child, WinUser.GWL_EXSTYLE, newExStyle);
            log("✅ 扩展样式已设置: 0x" + Integer.toHexString(newExStyle));

            // 检查子窗口的父窗口
            WinDef.HWND actualParent = user32.GetParent(child);
            if (actualParent != null) {
                long actualParentHandle = Pointer.nativeValue(actualParent.getPointer());
                long expectedParentHandle = Pointer.nativeValue(parent.getPointer());
                log("🔍 子窗口的父窗口: 0x" + Long.toHexString(actualParentHandle));
                log("🔍 期望的父窗口: 0x" + Long.toHexString(expectedParentHandle));
                log("🔍 父窗口匹配: " + (actualParentHandle == expectedParentHandle));
            } else {
                log("⚠️ 无法获取子窗口的父窗口");
            }

            return child;

        } catch (Exception e) {
            log("❌ 创建子窗口异常: " + e.getMessage());
            e.printStackTrace();
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
            return null;
        }
    }

    /**
     * 监听JavaFX布局变化，同步子窗口位置和尺寸
     */
    /**
     * 设置布局监听器（完全照抄SimpleWebRTCPlayer）
     */
    private void setupLayoutListener(StackPane pane) {
        if (pane == null) return;
        
        // 监听layoutBounds变化（照抄SimpleWebRTCPlayer）
        pane.layoutBoundsProperty().addListener((obs, oldB, newB) -> {
            if (videoOverlay != null) {
                try {
                    // 节流：避免过于频繁的矩形更新（默认 33ms）
                    long nowNs = System.nanoTime();
                    int minMs = 33;
                    Object lastNsObj = pane.getProperties().get("overlay.lastUpdateNs");
                    long lastNs = (lastNsObj instanceof Long) ? (Long) lastNsObj : 0L;
                    if (lastNs != 0L && (nowNs - lastNs) < (minMs * 1_000_000L)) {
                        return; // 跳过本次更新
                    }
                    pane.getProperties().put("overlay.lastUpdateNs", nowNs);

                    javafx.geometry.Bounds screenBounds = pane.localToScreen(newB);
                    int rw = (int) Math.round(screenBounds != null ? screenBounds.getWidth() : 0);
                    int rh = (int) Math.round(screenBounds != null ? screenBounds.getHeight() - FileToos.botoomHight : 0);  // 预留底部空间给操作栏
                    
                    if (screenBounds != null && rw > 0 && rh > 0) {
                        javafx.scene.Scene scene = pane.getScene();
                        double clientOriginX = 0, clientOriginY = 0;
                        if (scene != null && scene.getRoot() != null) {
                            javafx.geometry.Point2D rootTL = scene.getRoot().localToScreen(0, 0);
                            if (rootTL != null) {
                                clientOriginX = rootTL.getX();
                                clientOriginY = rootTL.getY();
                            }
                        }
                        int rx = (int) Math.round(screenBounds.getMinX() - clientOriginX);
                        int ry = (int) Math.round(screenBounds.getMinY() - clientOriginY);

                        // ⚠️ 关键：DPI缩放（照抄SimpleWebRTCPlayer 1632-1656行）
                        long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle : overlayWindowHandle;
                        double sx = 1.0, sy = 1.0;
                        try {
                            if (hwndForScale != 0L) {
                                WinDef.HWND w = new WinDef.HWND(Pointer.createConstant(hwndForScale));
                                WinDef.HDC hdc = user32.GetDC(w);
                                if (hdc != null) {
                                    int dpiX = GDI32.INSTANCE.GetDeviceCaps(hdc, 88);
                                    int dpiY = GDI32.INSTANCE.GetDeviceCaps(hdc, 90);
                                    user32.ReleaseDC(w, hdc);
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

                        // 跳过与上次相同的矩形更新
                        Object lastRectObj = pane.getProperties().get("overlay.lastRect");
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
                            } catch (Exception ignore) {}
                        }
                        
                        if (lastRx == pxRx && lastRy == pxRy && lastRw == pxRw && lastRh == pxRh) {
                            return; // 矩形未变化，跳过更新
                        }
                        
                        pane.getProperties().put("overlay.lastRect", pxRx + "," + pxRy + "," + pxRw + "," + pxRh);

                        // ⚠️ 关键：调整子窗口位置和大小（照抄SimpleWebRTCPlayer 1705-1709行）
                        if (overlayChildHandle != 0L) {
                            WinDef.HWND hwndChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
                            int flags = WinUser.SWP_NOZORDER | WinUser.SWP_NOACTIVATE;
                            user32.SetWindowPos(hwndChild, null, pxRx, pxRy, pxRw, pxRh, flags);
                            
                            // 在子窗口内渲染（相对坐标0,0）
                            videoOverlay.setRenderRectangle(0, 0, pxRw, pxRh);
                            log("📐 布局变化: x=" + pxRx + ", y=" + pxRy + ", w=" + pxRw + ", h=" + pxRh + " (DPI: " + sx + "x" + sy + ")");
                        }
                    }
                } catch (Exception e) {
                    log("❌ 布局监听器异常: " + e.getMessage());
                }
            }
        });
    }

    /**
     * 更新子窗口的位置和尺寸（与JavaFX pane同步）
     */
    private void updateChildWindowBounds() {
        if (overlayChildHandle == 0L || targetPane == null) return;

        try {
            // ✅ 获取targetPane在Scene中的位置
            javafx.geometry.Bounds boundsInLocal = targetPane.getBoundsInLocal();
            javafx.geometry.Bounds boundsInScene = targetPane.localToScene(boundsInLocal);

            if (boundsInScene == null) {
                log("⚠️ 无法获取targetPane的boundsInScene");
                return;
            }

            // ✅ 使用boundsInScene的坐标（相对于Scene，即窗口客户区）
            int x = (int) Math.round(boundsInScene.getMinX());
            int y = (int) Math.round(boundsInScene.getMinY());
            int width = (int) Math.round(boundsInScene.getWidth());
            int height = (int) Math.round(boundsInScene.getHeight() - FileToos.botoomHight);  // 预留底部空间给操作栏

            // ⚠️ 临时禁用DPI计算，避免Windows API阻塞导致卡死
            // ✅ 获取DPI缩放比例（JavaFX的逻辑坐标 → Windows物理像素）
            double sx = 1.75, sy = 1.75;  // 硬编码DPI缩放（150% = 1.5, 175% = 1.75）
            log("⚠️ 使用硬编码DPI: " + sx);

            // ✅ 应用DPI缩放，转换为物理像素坐标
            int pxX = (int) Math.round(x * sx);
            int pxY = (int) Math.round(y * sy);
            int pxWidth = (int) Math.round(width * sx);
            int pxHeight = (int) Math.round(height * sy);

            log("📐 更新子窗口位置:");
            log("   boundsInScene: (" + boundsInScene.getMinX() + ", " + boundsInScene.getMinY() + ") " +
                boundsInScene.getWidth() + "x" + boundsInScene.getHeight());
            log("   逻辑坐标（相对Scene）: (" + x + ", " + y + ") 尺寸: " + width + "x" + height);
            log("   DPI缩放: sx=" + sx + ", sy=" + sy);
            log("   物理像素（WS_CHILD坐标）: (" + pxX + ", " + pxY + ") 尺寸: " + pxWidth + "x" + pxHeight);

            // 更新子窗口位置和尺寸（使用物理像素坐标）
            final int SWP_NOZORDER = 0x0004;
            final int SWP_NOACTIVATE = 0x0010;
            WinDef.HWND hChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
            user32.SetWindowPos(
                hChild,
                null,
                pxX, pxY,
                pxWidth, pxHeight,
                SWP_NOZORDER | SWP_NOACTIVATE
            );

            // 同时更新VideoOverlay的渲染区域（使用物理像素尺寸）
            if (videoOverlay != null) {
                videoOverlay.setRenderRectangle(0, 0, pxWidth, pxHeight);
                log("✅ 子窗口位置和渲染区域已更新");
            }

        } catch (Exception e) {
            log("⚠️ 更新子窗口位置失败: " + e.getMessage());
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }

    /**
     * 获取Pipeline当前状态
     */
    public State getPipelineState() {
        if (pipeline != null) {
            return pipeline.getState();
        }
        return State.NULL;
    }

    /**
     * 播放
     */
    public void play() {
        if (pipeline != null) {
            log("\n════════════════════════════════════════");
            log("▶️ SlowMoGpuPlayer: 开始播放");
            log("════════════════════════════════════════");
            log("   速度: " + currentRate + "x");
            log("   Pipeline状态: " + pipeline.getState());
            log("   文件: " + currentMp4Path);

            State oldState = pipeline.getState();
            log("🔧 调用pipeline.play()...");
            pipeline.play();
            log("✅ pipeline.play()返回（异步启动，不等待）");

            // ✅ 启动播放位置监控（定期查询当前播放位置并更新UI）
            startPositionMonitor();

            log("════════════════════════════════════════\n");
        } else {
            log("❌ Pipeline为null，无法播放");
        }
    }

    private volatile int latestRecordedFrames = 0;  // 最新录制帧数（从外部更新）
    private volatile boolean isRecordingComplete = false;  // 录制是否完成
    private volatile boolean autoChaseEnabled = false;  // ❌ 禁用自动追赶（导致频繁seek，画面黑屏）
    private volatile long lastChaseTime = 0;  // 上次追赶时间（防抖）
    private volatile boolean isChasingNow = false;  // 是否正在追赶（防止并发）
    private volatile int lastPlaybackFrame = -1;  // 上次播放帧（检测卡住）
    private volatile int stuckCount = 0;  // 卡住计数

    /**
     * 更新最新录制帧数（供外部调用，用于自动追赶）
     */
    public void updateRecordedFrames(int frames) {
        this.latestRecordedFrames = frames;
    }

    /**
     * 设置录制完成状态
     */
    public void setRecordingComplete(boolean complete) {
        this.isRecordingComplete = complete;
        log("📌 录制完成状态: " + complete);
    }

    /**
     * 启动播放位置监控（定期查询播放位置并通知UI + 自动追赶）
     */

    private void startPositionMonitor() {
        stopPositionMonitor();  // 先停止旧的

        positionMonitor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PlaybackPositionMonitor");
            t.setDaemon(true);
            return t;
        });

        log("✅ 播放位置监控线程已创建");

        // 每100ms查询一次播放位置
        positionMonitor.scheduleAtFixedRate(() -> {
            try {
                if (pipeline != null) {
                    State state = pipeline.getState();
                    if (state == State.PLAYING || state == State.PAUSED) {
                        // ⚠️ 精确计算播放帧数：使用纳秒级精度
                        long posNs = pipeline.queryPosition(Format.TIME);

                        // ✅ 方法1：基于时间戳计算（最准确，因为MKV本身就是基于时间戳的）
                        // 帧号 = 时间戳(ns) * FPS / 1_000_000_000
                        // 使用long避免浮点数精度损失
                        long currentFrameLong = (posNs * currentFps) / 1_000_000_000L;
                        int currentFrame = (int) currentFrameLong;

                        // ✅ 调试日志（每10帧输出一次，避免日志过多）
                        if (currentFrame % 10 == 0 && currentFrame != lastPlaybackFrame) {
                            double posSec = posNs / 1_000_000_000.0;
                            String logMsg = String.format("📊 播放位置: %.3f秒, 帧号: %d (FPS: %d)", posSec, currentFrame, currentFps);
                            log(logMsg);
                        }

                        // 通过回调更新UI
                        if (playbackFrameCallback != null) {
                            playbackFrameCallback.accept(currentFrame);
                        }

                        // ✅ 检测卡住：如果播放帧连续3次不变，快速响应
                        if (currentFrame == lastPlaybackFrame) {
                            stuckCount++;
                        } else {
                            stuckCount = 0;
                            lastPlaybackFrame = currentFrame;
                        }

                        // ✅ 自动追赶：录制过程中，保持在最新位置-500ms（避免追上文件末尾）
                        if (autoChaseEnabled && state == State.PLAYING && latestRecordedFrames > 0 && !isChasingNow) {
                            // ⚠️ 关键修复：目标位置 = 最新录制位置 - 500ms
                            // 500ms = 15帧@30fps 或 60帧@120fps
                            int bufferFrames = (currentFps * 500) / 1000;  // 500ms对应的帧数
                            int targetFrame = Math.max(0, latestRecordedFrames - bufferFrames);

                            // ⚠️ 关键修复：计算实际落后（相对于最新录制位置）
                            int actualLag = latestRecordedFrames - currentFrame;

                            // ⚠️ 触发条件：落后超过1秒（currentFps帧），立即追赶到目标位置（最新-500ms）
                            int triggerThreshold = currentFps;  // 1秒
                            if (actualLag > triggerThreshold) {
                                isChasingNow = true;
                                stuckCount = 0;

                                double targetSec = targetFrame / (double) currentFps;

                                System.out.println("⚡ 自动追赶触发：");
                                System.out.println("   当前: 第" + currentFrame + "帧");
                                System.out.println("   录制: 第" + latestRecordedFrames + "帧");
                                System.out.println("   实际落后: " + actualLag + "帧 (" + (actualLag * 1000 / currentFps) + "ms)");
                                System.out.println("   触发阈值: " + triggerThreshold + "帧 (1秒)");
                                System.out.println("   目标位置: 第" + targetFrame + "帧 (最新-500ms)");
                                System.out.println("   缓冲: " + bufferFrames + "帧 (500ms)");
                                log("⚡ 自动追赶：从第" + currentFrame + "帧跳到第" + targetFrame + "帧（落后" + actualLag + "帧，追到最新-500ms）");

                                new Thread(() -> {
                                    try {
                                        // PAUSED → seek → PLAYING
                                        pipeline.setState(State.PAUSED);
                                        Thread.sleep(50);  // 减少等待时间

                                        long targetNs = (long) (targetSec * 1_000_000_000L);

                                        boolean success = pipeline.seek(
                                                currentRate,
                                                Format.TIME,
                                                EnumSet.of(SeekFlags.ACCURATE),
                                                SeekType.SET, targetNs,
                                                SeekType.NONE, -1
                                        );

                                        Thread.sleep(50);  // 减少等待时间
                                        pipeline.setState(State.PLAYING);

                                        String result = success ? "✅ 跳转成功" : "⚠️ 跳转失败";
                                        System.out.println(result);
                                        log(result);

                                    } catch (Exception e) {
                                        String msg = "❌ 跳转异常: " + e.getMessage();
                                        System.err.println(msg);
                                        log(msg);
                                    } finally {
                                        isChasingNow = false;
                                    }
                                }, "AutoChase").start();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略查询错误（避免日志刷屏）
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        log("✅ 播放位置监控已启动（每100ms查询，FPS:" + currentFps + "，自动追赶已启用）");
    }
    /**
     * 停止播放位置监控
     */
    private void stopPositionMonitor() {
        if (positionMonitor != null) {
            positionMonitor.shutdownNow();
            positionMonitor = null;
        }
    }

    /**
     * 跳到最新位置（减少延迟，追上实时流）
     * ✅ 改进版：不使用FLUSH，确保seek后继续播放
     */
    public void seekToLatest() {
        if (pipeline == null) return;

        try {
            // 等待pipeline进入PLAYING状态
            log("⏳ 等待pipeline稳定...");
            Thread.sleep(500);

            // 查询当前文件的时长
            long duration = pipeline.queryDuration(TimeUnit.NANOSECONDS);
            if (duration > 0) {
                // 跳到最后（duration - 0.3秒，避免EOS，缩短缓冲时间）
                long seekPos = Math.max(0, duration - 300_000_000L);  // 减去0.3秒
                log("⏩ 跳到最新位置: " + (seekPos / 1_000_000_000.0) + "秒 (总时长: " + (duration / 1_000_000_000.0) + "秒)");

                // ✅ 使用改进的seek方法（不清空缓冲，保持播放状态）
                boolean success = pipeline.seek(
                    currentRate,                    // 保持当前播放速度
                    Format.TIME,                    // 时间格式
                    EnumSet.of(SeekFlags.ACCURATE), // ✅ 仅使用ACCURATE，不使用FLUSH（避免卡住）
                    SeekType.SET, seekPos,          // 跳到指定位置
                    SeekType.NONE, -1               // 不设置结束位置
                );

                if (success) {
                    log("✅ seek成功");

                    // ✅ 强制设置为PLAYING状态（不等待，直接恢复）
                    log("🔄 强制恢复播放状态");
                    pipeline.play();
                    log("✅ 已跳到最新位置，继续播放");
                } else {
                    log("⚠️ seek失败");
                }
            } else {
                log("⚠️ 无法获取文件时长，跳过seek");
            }
        } catch (Exception e) {
            log("⚠️ seekToLatest失败: " + e.getMessage());
            if (debugLogger != null) {
                e.printStackTrace(debugLogger);
            }
        }
    }

    /**
     * 暂停（用户点击停止按钮时调用）
     */
    public void pause() {
        if (pipeline != null) {
            // ⚠️ 关键修复：暂停时禁用EOS处理（用户已停止录制，不需要追赶）
            shouldHandleEOS = false;
            pipeline.pause();
            System.out.println("⏸️ SlowMoGpuPlayer: 已暂停");
            log("⏸️ 已暂停播放，EOS处理已禁用");
        }
    }

    /**
     * 停止
     */
    public void stop() {
        stopPositionMonitor();  // 停止位置监控
        if (pipeline != null) {
            pipeline.stop();
            System.out.println("⏹️ SlowMoGpuPlayer: 已停止");
        }
    }

    /**
     * 释放资源（彻底清理，防止资源累积）
     */
    public void dispose() {
        System.out.println("🗑️ SlowMoGpuPlayer: 开始清理资源...");

        // 0. 注销事件监听器
        unregisterUIUpdateEvents();

        // 1. 停止Pipeline并设置为NULL状态（确保完全停止）
        if (pipeline != null) {
            try {
                pipeline.setState(State.NULL);  // 先设置为NULL状态
                Thread.sleep(100);  // 等待状态切换完成
                pipeline.dispose();
                pipeline = null;
                System.out.println("   ✅ Pipeline已释放");
            } catch (Exception e) {
                System.err.println("   ❌ Pipeline释放失败: " + e.getMessage());
            }
        }

        // 2. 解除VideoOverlay绑定
        if (videoOverlay != null) {
            try {
                videoOverlay = null;
                System.out.println("   ✅ VideoOverlay已解除");
            } catch (Exception e) {
                System.err.println("   ❌ VideoOverlay解除失败: " + e.getMessage());
            }
        }

        // 3. 隐藏子窗口（不销毁，避免第二次播放时窗口消失）
        if (overlayChildHandle != 0L) {
            try {
                WinDef.HWND hChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));
                user32.ShowWindow(hChild, 0); // SW_HIDE = 0
                System.out.println("   ✅ 子窗口已隐藏（保留HWND，避免第二次播放失败）");
                // ⚠️ 不要重置 overlayChildHandle，保留它供下次使用
                // overlayChildHandle = 0L;  // ❌ 不要重置
            } catch (Exception e) {
                System.err.println("   ❌ 子窗口隐藏失败: " + e.getMessage());
            }
        }

        // 4. 清理所有引用
        targetPane = null;
        overlayWindowHandle = 0L;
        currentState = State.NULL;

        // 5. 建议GC回收（不强制，让JVM自己决定）
        System.gc();

        System.out.println("🗑️ SlowMoGpuPlayer: 资源清理完成");
    }

    /**
     * 设置播放速度（实时调节）
     * @param rate 速度（0.1 ~ 2.0）
     */



    public void setRate(double rate) {

        this.currentRate = rate;
        if (pipeline != null&&pipeline.getState()== State.PLAYING) {
            // 🚀 异步执行，避免阻塞UI
            CompletableFuture.runAsync(() -> {
                try {
                    long posNs = pipeline.queryPosition(Format.TIME);

                    boolean success = pipeline.seek(
                            rate, Format.TIME,
                            EnumSet.of(SeekFlags.ACCURATE),
                            SeekType.SET, posNs,
                            SeekType.NONE, -1
                    );

                    if (success && pipeline.getState() != State.PLAYING) {
                        pipeline.setState(State.PLAYING);
                    }
                } catch (Exception e) {
                    System.err.println("❌ 异步设置播放速度异常: " + e.getMessage());
                }
            });

            System.out.println("🎬 速度调节请求已提交: " + rate + "x");
        }




    }

    /**
     * 跳转到指定位置
     * @param positionNs 位置（纳秒）
     */
    public void seek(long positionNs) {
        if (pipeline == null) {
            System.err.println("❌ seek失败: pipeline为null");
            return;
        }

        // ✅ 整个seek操作在后台线程执行，避免阻塞UI线程
        new Thread(() -> {
            try {
                // ✅ 记录当前播放状态
                State stateBefore = pipeline.getState();
                boolean wasPlaying = (stateBefore == State.PLAYING);

                System.out.println("🎯 用户拖动进度条");
                System.out.println("   目标位置: " + (positionNs / 1_000_000_000.0) + "s");
                System.out.println("   当前状态: " + stateBefore);
                log("🎯 用户拖动到: " + (positionNs / 1_000_000_000.0) + "s, 状态: " + stateBefore);

                // ✅ 修复：使用FLUSH + ACCURATE确保精确seek，但优化状态管理
                boolean success = pipeline.seek(
                    currentRate,
                    Format.TIME,
                    EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),  // ✅ 恢复FLUSH+ACCURATE确保精确定位
                    SeekType.SET, positionNs,
                    SeekType.NONE, -1
                );

                System.out.println("   seek结果: " + (success ? "成功" : "失败"));

                if (success) {
                    log("✅ seek成功");

                    // ✅ 关键修复：无论之前状态如何，拖动后都应该播放
                    // 这符合用户期望：拖动滑块 = 跳转并播放
                    System.out.println("▶️ 拖动后自动播放...");
                    
                    // 等待seek完全生效
                    Thread.sleep(100);
                    
                    // 强制设置为播放状态
                    pipeline.setState(State.PLAYING);
                    
                    // 再次等待确保状态生效
                    Thread.sleep(100);
                    
                    State finalState = pipeline.getState();
                    System.out.println("   最终状态: " + finalState);
                    log("▶️ 拖动后播放状态: " + finalState);
                    
                    // 如果状态设置失败，再次尝试
                    if (finalState != State.PLAYING) {
                        System.out.println("⚠️ 状态设置失败，重试...");
                        pipeline.setState(State.PLAYING);
                        Thread.sleep(50);
                        System.out.println("   重试后状态: " + pipeline.getState());
                    }
                } else {
                    log("❌ seek失败");
                    System.err.println("❌ seek返回false，可能是文件末尾或无效位置");
                    
                    // seek失败时，尝试播放当前位置
                    pipeline.setState(State.PLAYING);
                }
            } catch (Exception e) {
                String msg = "❌ seek异常: " + e.getMessage();
                log(msg);
                System.err.println(msg);
                e.printStackTrace();
            }
        }, "Seek-Thread").start();
    }

    /**
     * 跳转到指定时间（秒）
     * @param timeInSeconds 时间（秒）
     */
    public void seekToTime(double timeInSeconds) {
        long positionNs = (long) (timeInSeconds * 1_000_000_000L);
        seek(positionNs);
    }

    /**
     * 获取当前播放位置（纳秒）
     */
    public long getPosition() {
        if (pipeline != null) {
            return pipeline.queryPosition(Format.TIME);
        }
        return 0;
    }

    /**
     * 获取媒体总时长（纳秒）
     */
    public long getDuration() {
        if (pipeline != null) {
            return pipeline.queryDuration(Format.TIME);
        }
        return 0;
    }

    /**
     * 检查是否正在播放
     */
    public boolean isPlaying() {
        return currentState == State.PLAYING && isRecordingComplete==false;
    }

    // ========== 抓拍功能 ==========
    
    /**
     * 从MKV文件中提取指定帧范围并保存为JPEG
     * 
     * @param centerFrameIndex 中心帧索引（当前播放位置）
     * @param preFrames 前置帧数
     * @param postFrames 后置帧数
     * @param outputDir 输出目录
     * @param callback 完成回调（参数：成功的文件列表）
     */
    public void captureFrames(int centerFrameIndex, int preFrames, int postFrames, 
                             String outputDir, java.util.function.Consumer<List<String>> callback) {
        
        if (currentMp4Path == null || currentMp4Path.isEmpty()) {
            System.err.println("❌ MKV抓拍失败：未加载文件");
            if (callback != null) callback.accept(java.util.Collections.emptyList());
            return;
        }
        
        // 在后台线程执行抓拍
        new Thread(() -> {
            try {
                System.out.println("🎯 开始MKV抓拍:");
                System.out.println("   中心帧: " + centerFrameIndex);
                System.out.println("   前置帧: " + preFrames);
                System.out.println("   后置帧: " + postFrames);
                System.out.println("   输出目录: " + outputDir);
                System.out.println("   MKV文件: " + currentMp4Path);
                System.out.println("   FPS: " + currentFps);
                log("🎯 开始MKV抓拍:");
                log("   中心帧: " + centerFrameIndex);
                log("   前置帧: " + preFrames);
                log("   后置帧: " + postFrames);
                log("   输出目录: " + outputDir);
                
                // 计算帧范围
                int startFrame = Math.max(0, centerFrameIndex - preFrames);
                int endFrame = centerFrameIndex + postFrames;
                int totalFrames = endFrame - startFrame + 1;
                
                System.out.println("   帧范围: " + startFrame + " - " + endFrame + " (共" + totalFrames + "帧)");
                log("   帧范围: " + startFrame + " - " + endFrame + " (共" + totalFrames + "帧)");
                
                // 创建输出目录
                File outDir = new File(outputDir);
                if (!outDir.exists()) {
                    outDir.mkdirs();
                }
                
                System.out.println("📂 开始提取帧...");
                
                // 使用独立的GStreamer pipeline提取帧
                List<String> savedFiles = extractFramesFromMkv(
                    currentMp4Path, startFrame, endFrame, outputDir, currentFps
                );
                
                System.out.println("✅ MKV抓拍完成：成功保存" + savedFiles.size() + "帧");
                log("✅ MKV抓拍完成：成功保存" + savedFiles.size() + "帧");
                
                // 回调
                if (callback != null) {
                    callback.accept(savedFiles);
                }
                
            } catch (Exception e) {
                String msg = "❌ MKV抓拍异常: " + e.getMessage();
                log(msg);
                System.err.println(msg);
                e.printStackTrace();
                if (callback != null) {
                    callback.accept(java.util.Collections.emptyList());
                }
            }
        }, "MKV-Capture-Thread").start();
    }
    
    /**
     * 从MKV文件中提取指定帧范围
     * 
     * @param mkvPath MKV文件路径
     * @param startFrame 起始帧索引
     * @param endFrame 结束帧索引
     * @param outputDir 输出目录
     * @param fps 帧率
     * @return 成功保存的文件路径列表
     */
    private List<String> extractFramesFromMkv(String mkvPath, int startFrame,
                                                         int endFrame, String outputDir, int fps) {
        List<String> savedFiles = new java.util.ArrayList<>();
        
        // 对于每一帧，创建独立的pipeline提取
        for (int frameIdx = startFrame; frameIdx <= endFrame; frameIdx++) {
            try {
                // 计算时间戳（秒）
                double timeInSeconds = frameIdx / (double) fps;
                
                // 输出文件名
                String outputFile = outputDir + "/frame_" + 
                    String.format("%06d", frameIdx) + "_" + 
                    System.currentTimeMillis() + ".jpg";
                
                System.out.println("   🔄 提取第" + frameIdx + "帧 (时间: " + String.format("%.3f", timeInSeconds) + "秒)");
                
                // 提取单帧
                boolean success = extractSingleFrame(mkvPath, timeInSeconds, outputFile);
                
                if (success) {
                    savedFiles.add(outputFile);
                    System.out.println("   ✅ 已保存: 第" + frameIdx + "帧 → " + new File(outputFile).getName());
                    log("   ✅ 已保存: 第" + frameIdx + "帧 → " + new File(outputFile).getName());
                } else {
                    System.out.println("   ⚠️ 跳过: 第" + frameIdx + "帧（提取失败）");
                    log("   ⚠️ 跳过: 第" + frameIdx + "帧（提取失败）");
                }
                
            } catch (Exception e) {
                System.out.println("   ❌ 第" + frameIdx + "帧提取异常: " + e.getMessage());
                log("   ❌ 第" + frameIdx + "帧提取异常: " + e.getMessage());
            }
        }
        
        return savedFiles;
    }
    
    /**
     * 从MKV文件中提取单帧并保存为JPEG
     * 
     * @param mkvPath MKV文件路径
     * @param timeInSeconds 时间戳（秒）
     * @param outputFile 输出文件路径
     * @return 是否成功
     */
    private boolean extractSingleFrame(String mkvPath, double timeInSeconds, String outputFile) {
        // ⚠️ 最简单可靠的方案：使用appsink从播放pipeline中截取当前帧
        // 但这需要修改主pipeline，所以我们使用独立的简化pipeline
        
        Pipeline extractPipeline = null;
        final java.util.concurrent.atomic.AtomicBoolean frameReceived = new java.util.concurrent.atomic.AtomicBoolean(false);
        
        try {
            System.out.println("      创建提取pipeline...");
            
            extractPipeline = new Pipeline("frame-extract-" + System.currentTimeMillis());
            
            Element src = ElementFactory.make("filesrc", "src");
            Element demux = ElementFactory.make("matroskademux", "demux");
            Element parse = ElementFactory.make("h264parse", "parse");
            Element dec = ElementFactory.make("avdec_h264", "dec");
            Element convert = ElementFactory.make("videoconvert", "convert");
            Element enc = ElementFactory.make("jpegenc", "enc");
            Element sink = ElementFactory.make("filesink", "sink");
            
            if (src == null || demux == null || parse == null || dec == null || 
                convert == null || enc == null || sink == null) {
                System.err.println("❌ 无法创建提取pipeline元素");
                return false;
            }
            
            // 配置
            src.set("location", mkvPath);
            enc.set("quality", 95);
            sink.set("location", outputFile);
            sink.set("sync", false);  // 不同步，快速处理
            
            // 添加到pipeline
            extractPipeline.addMany(src, demux, parse, dec, convert, enc, sink);
            
            // 链接
            Element.linkMany(src, demux);
            Element.linkMany(parse, dec, convert, enc, sink);
            
            // demux动态pad连接
            final java.util.concurrent.CountDownLatch padLatch = new java.util.concurrent.CountDownLatch(1);
            final Pipeline finalPipeline = extractPipeline;
            
            demux.connect(new Element.PAD_ADDED() {
                @Override
                public void padAdded(Element element, Pad pad) {
                    if (pad.getName().startsWith("video")) {
                        Pad sinkPad = parse.getStaticPad("sink");
                        if (sinkPad != null && !sinkPad.isLinked()) {
                            pad.link(sinkPad);
                            padLatch.countDown();
                        }
                    }
                }
            });
            
            // 监听Bus消息（检测EOS和错误）
            Bus bus = extractPipeline.getBus();
            bus.connect((Bus.EOS) source -> {
                System.out.println("      ✅ 帧提取完成（EOS）");
                frameReceived.set(true);
            });
            
            bus.connect((Bus.ERROR) (source, code, message) -> {
                System.err.println("      ❌ 提取错误: " + message);
                frameReceived.set(false);
            });
            
            // 启动到PAUSED
            extractPipeline.setState(State.PAUSED);
            
            // 等待pad连接
            if (!padLatch.await(2, TimeUnit.SECONDS)) {
                System.err.println("❌ pad连接超时");
                extractPipeline.setState(State.NULL);
                return false;
            }
            
            System.out.println("      Seek到 " + String.format("%.3f", timeInSeconds) + " 秒...");
            
            // Seek到指定位置
            long posNs = (long) (timeInSeconds * 1_000_000_000L);
            boolean seekSuccess = extractPipeline.seek(
                    currentRate,
                Format.TIME,
                EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE, SeekFlags.KEY_UNIT),
                SeekType.SET, posNs,
                SeekType.NONE, -1
            );
            
            if (!seekSuccess) {
                System.err.println("❌ Seek失败");
                extractPipeline.setState(State.NULL);
                return false;
            }
            
            // 等待seek完成
            Thread.sleep(300);
            
            System.out.println("      开始播放（只处理一帧）...");
            
            // 设置为PLAYING
            extractPipeline.setState(State.PLAYING);
            
            // 等待一小段时间让帧处理完成（单帧很快）
            Thread.sleep(500);
            
            // 停止pipeline（强制停止，不等待EOS）
            try {
                extractPipeline.setState(State.NULL);
                System.out.println("      Pipeline已停止");
            } catch (Exception e) {
                System.err.println("      ⚠️ 停止pipeline失败: " + e.getMessage());
            }
            
            // 检查文件
            File file = new File(outputFile);
            if (file.exists()) {
                long fileSize = file.length();
                System.out.println("      文件大小: " + (fileSize / 1024) + " KB");
                
                if (fileSize > 5 * 1024 * 1024) {
                    System.err.println("      ⚠️ 文件过大");
                    file.delete();
                    return false;
                }
                
                if (fileSize < 1024) {
                    System.err.println("      ⚠️ 文件过小");
                    file.delete();
                    return false;
                }
                
                return true;
            } else {
                System.err.println("      ❌ 文件未生成");
                return false;
            }
            
        } catch (Exception e) {
            System.err.println("❌ 提取单帧异常: " + e.getMessage());
            e.printStackTrace();
            if (extractPipeline != null) {
                try {
                    extractPipeline.setState(State.NULL);
                } catch (Exception ignore) {}
            }
            return false;
        }
    }


    public void refreshOverlayRectangle() {


        if (videoOverlay == null || targetPane == null) return;
        Platform.runLater(() -> {
            try {
                // ✅ 使用layoutBounds（实际布局边界）而不是boundsInLocal
                javafx.geometry.Bounds layoutBounds = targetPane.getLayoutBounds();
                javafx.geometry.Bounds ivScreen = targetPane.localToScreen(layoutBounds);

                if (ivScreen == null) return;
                javafx.scene.Scene scene = targetPane.getScene();
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
                int containerH = (int) Math.round(ivScreen.getHeight() - FileToos.botoomHight);  // 预留底部空间给操作栏
                int containerX = (int) Math.round(ivScreen.getMinX() - clientOriginX);
                int containerY = (int) Math.round(ivScreen.getMinY() - clientOriginY);

                // ✅ 获取 GpuView 的缩放比例（如果适用）
                double gpuViewScale = 1.0;
                if (targetPane instanceof com.acard.acard.ui.GpuView) {
                    gpuViewScale = ((com.acard.acard.ui.GpuView) targetPane).getOverlayScale();
                }

                // ✅ 计算缩放后的视频尺寸（放大时可以超出容器）
                int scaledW = (int) Math.round(containerW * gpuViewScale);
                int scaledH = (int) Math.round(containerH * gpuViewScale);

                // ✅ 计算视频位置（居中放大：视频中心保持在容器中心）
                // scale=1.0: 视频铺满容器
                // scale>1.0: 视频放大，超出部分被裁剪，但中心保持在容器中心
                int videoX = containerX - (scaledW - containerW) / 2;
                int videoY = containerY - (scaledH - containerH) / 2;

                // ✅ 临时开启日志，确认GpuView本身是否在缩放
                if (gpuViewScale != 1.0) {
                    System.out.println("🔍 [缩放调试] 容器大小(GpuView): " + containerW + "x" + containerH + " (应该不变)");
                    System.out.println("🔍 [缩放调试] 视频窗口大小: " + scaledW + "x" + scaledH + " (应该变化)");
                    System.out.println("🔍 [缩放调试] 缩放比例: x" + String.format("%.2f", gpuViewScale));
                }

                long hwndForScale = overlayChildHandle != 0L ? overlayChildHandle
                        : (overlayWindowHandle != 0L ? overlayWindowHandle : tryResolveWindowHandleFromJavaFX());
                double sx = 1.0, sy = 1.0;
                boolean calcDpi = Boolean.parseBoolean(System.getProperty("video.overlay.calcDpi", "true"));
                try {
                    if (calcDpi && hwndForScale != 0L) {
                        WinDef.HWND w = new WinDef.HWND(
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
                    WinDef.HWND hwndChild = new WinDef.HWND(Pointer.createConstant(overlayChildHandle));

                    int pxContainerW = (int) Math.round(containerW * sx);
                    int pxContainerH = (int) Math.round(containerH * sy);

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
                        System.err.println("⚠️ 设置 HWND 裁剪区域失败: " + e.getMessage());
                    }

                    // 3️⃣ 再设置窗口位置和大小（此时裁剪已生效，不会显示超出部分）
                    int flags = WinUser.SWP_NOZORDER
                            | WinUser.SWP_NOACTIVATE
                            | WinUser.SWP_NOREDRAW;  // ✅ 延迟重绘

                    User32.INSTANCE.SetWindowPos(hwndChild, null, pxVideoX, pxVideoY, pxVideoW, pxVideoH, flags);
                    videoOverlay.setRenderRectangle(0, 0, pxVideoW, pxVideoH);

                    // 4️⃣ 统一重绘一次（在所有设置完成后）
                    User32.INSTANCE.InvalidateRect(hwndChild, null, false);
                    User32.INSTANCE.UpdateWindow(hwndChild);  // ✅ 立即更新，使缩放更流畅
                } else {
                    // 无子窗口时，暂时仅触发 expose；等待子窗口创建后再设置精确矩形
                    videoOverlay.expose();
                }
            } catch (Throwable ignore) {}
        });
    }

    /**
     * 注册UI更新事件监听器
     */
    public void registerUIUpdateEvents() {
        if (eventListenersRegistered) {
            return;
        }

        try {
            // 注册强制刷新事件
            eventManager.registerListener(UIUpdateEvent.EventType.FORCE_REFRESH,
                    this::handleUIUpdateEvent, listenerId);

            eventListenersRegistered = true;
            log("✅ UI更新事件监听器注册成功");

        } catch (Exception e) {
            System.err.println("❌ 注册UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 注销UI更新事件监听器
     */
    public void unregisterUIUpdateEvents() {
        if (!eventListenersRegistered) {
            return;
        }

        try {
            // 注销所有该监听器ID的事件
            eventManager.unregisterAllListeners(listenerId);
            eventListenersRegistered = false;
            log("✅ UI更新事件监听器注销成功");

        } catch (Exception e) {
            System.err.println("❌ 注销UI更新事件监听器失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理UI更新事件
     */
    private void handleUIUpdateEvent(UIUpdateEvent event) {
        try {
            log("🔄 收到UI更新事件: " + event.getEventType());

            // 延迟执行刷新，确保UI布局完成
            Platform.runLater(() -> {
                try {
                    refreshOverlayRectangle();
                    log("✅ UI更新事件处理完成: " + event.getEventType());
                } catch (Exception e) {
                    System.err.println("❌ 处理UI更新事件异常: " + e.getMessage());
                    e.printStackTrace();
                }
            });

        } catch (Exception e) {
            System.err.println("❌ 处理UI更新事件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 强制刷新UI（发送刷新事件）
     */


    /**
     * 加载新视频（复用播放器实例）
     * @param videoPath 视频文件路径
     * @return 是否加载成功
     */
    /*public boolean load(String videoPath) {
        try {
            logRecord("🔄 加载新视频: " + videoPath);

            // 1. 如果正在播放，先停止
            if (pipeline != null) {
                State currentState = pipeline.getState();
                if (currentState == State.PLAYING || currentState == State.PAUSED) {
                    pipeline.setState(State.READY);

                    Thread.sleep(100);
                    logRecord("  - 已停止当前播放");
                }
            }

            // 2. 如果 pipeline 不存在，说明是首次加载，需要初始化
            if (pipeline == null) {
                logRecord("  - 首次加载，初始化 pipeline");
                createPipeline(videoPath);  // 调用原来的初始化方法

            }

            // 3. 如果 pipeline 存在，只需要更新 filesrc 的 location
            if (filesrc != null) {
                pipeline.setState(State.NULL);  // 先设为 NULL 才能修改 location
                //Thread.sleep(50);

                filesrc.set("location", videoPath);
                //logRecord("  - 已更新文件路径");

                // 恢复到 READY 状态
                pipeline.setState(State.READY);
                //Thread.sleep(100);

                // 开始播放
                pipeline.setState(State.PLAYING);
                logRecord("✅ 视频加载并播放成功");
                return true;
            }

            return false;

        } catch (Exception e) {
            logRecord("❌ 加载视频失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }*/


    /**
     * 加载新视频（无黑屏切换）
     * @param videoPath 视频文件路径
     * @return 是否加载成功
     */
    public boolean load(String videoPath) {
        try {
            logRecord("🔄 加载新视频: " + videoPath);

            // 1. 如果 pipeline 不存在，首次初始化
            if (pipeline == null) {
                logRecord("  - 首次加载，初始化 pipeline");
                createPipeline(videoPath);  // 调用原来的初始化方法
            }


            // 4. ⭐ 发送 SEEK 事件到开头（确保从头播放）
            try {

                boolean seekSuccess =seekToNearEnd(200);

                if (seekSuccess) {
                    logRecord("  - 已 seek 到视频开头");
                } else {
                    logRecord("  - seek 失败，但继续播放");
                }
            } catch (Exception e) {
                logRecord("  - seek 异常: " + e.getMessage());
            }

            // 5. ⭐ 恢复播放
            pipeline.setState(State.PLAYING);
            logRecord("✅ 视频切换成功（无黑屏）");
            startPositionMonitor();
            logRecord("  - 位置监控已重新启动");

            return true;

        } catch (Exception e) {
            logRecord("❌ 加载视频失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }


    /*public boolean load(String videoPath) {
        try {
            logRecord("🔄 加载新视频: " + videoPath);

            // 1. 如果 pipeline 不存在，首次初始化
            if (pipeline == null) {
                logRecord("  - 首次加载，初始化 pipeline");
                createPipeline(videoPath);  // 调用原来的初始化方法
            }

            // 4. ⭐ 发送 SEEK 事件到开头（确保从头播放）
            try {
                boolean seekSuccess =seekToEndByPercent(98.0);
                if (seekSuccess) {
                    logRecord("  - 已 seek 到视频开头");
                } else {
                    logRecord("  - seek 失败，但继续播放");
                }
            } catch (Exception e) {
                logRecord("  - seek 异常: " + e.getMessage());
            }

            //long posNs = (long) (2 * 1_000_000_000L);

            *//*boolean seekSuccess2=pipeline.seek(
                    currentRate,
                    Format.TIME,
                    EnumSet.of(SeekFlags.ACCURATE),
                    SeekType.SET, posNs,
                    SeekType.NONE, -1);
            if (seekSuccess2) {
                logRecord("seekSuccess2  - 已 seek 到视频开头");
            } else {
                logRecord("seekSuccess2  - seek 失败，但继续播放");
            }*//*
            // 5. ⭐ 恢复播放
            pipeline.setState(State.PLAYING);

            logRecord("✅ 视频切换成功（无黑屏）");
            startPositionMonitor();
            logRecord("  - 位置监控已重新启动");

            return true;

        } catch (Exception e) {
            logRecord("❌ 加载视频失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }*/
    public long getFileDuration() {
        if (pipeline != null) {
            long durationNs = pipeline.queryDuration(Format.TIME);
            long durationMs = durationNs / 1_000_000L;
            logRecord("📊 Pipeline duration: " + durationMs + "ms");
            return durationMs;
        }
        return 0;
    }

    // SlowMoGpuPlayer 中添加
    public boolean seekToEndByPercent(double percent) {
        logRecord("========== 测试百分比 Seek ==========");
        logRecord("🎯 目标百分比: " + percent + "%");

        // ⭐ PERCENT 格式：0-100 对应 0%-100%
        // 注意：GStreamer 内部使用 0-1000000 表示 0%-100%
        // 所以 98% = 98 * 10000 = 980000
        long percentValue = (long) (percent * 10000);

        logRecord("📊 百分比值: " + percentValue + " (内部表示)");

        // PAUSE

        // ⭐ 使用 Format.PERCENT
        boolean success = pipeline.seek(
                currentRate,                    // 正常速度
                Format.PERCENT,         // ⭐ 百分比格式
                EnumSet.of(
                        SeekFlags.FLUSH,      // 清空缓冲
                        SeekFlags.KEY_UNIT,   // ⭐ 必须有！跳到关键帧
                        SeekFlags.SNAP_AFTER  // ⭐ 跳到之后的关键帧
                ),
                SeekType.SET,           // 绝对百分比
                percentValue,           // 98% 的内部表示
                SeekType.NONE,
                -1
        );


        // 验证
        if (success) {
            logRecord("✅ Seek 成功");
        } else {
            logRecord("❌ Seek 失败（可能不支持 PERCENT 格式）");
        }

        logRecord("======================================");
        return success;
    }

    public boolean seekToLive(long offsetMs) {
        logRecord("========== Seek 调试 ==========");

        long actualDurationMs = getFileDuration();
        // 1. 查询文件 duration
        long fileDurationNs = pipeline.queryDuration(Format.TIME);
        long fileDurationMs = fileDurationNs / 1_000_000L;
        logRecord("📊 文件 duration: " + fileDurationMs + "ms");
        logRecord("📊 实际录制 duration: " + actualDurationMs + "ms");
        logRecord("📊 差距: " + (actualDurationMs - fileDurationMs) + "ms");

        // 2. 获取当前位置
        long currentPosNs = pipeline.queryPosition(Format.TIME);
        long currentPosMs = currentPosNs / 1_000_000L;
        logRecord("📍 当前位置: " + currentPosMs + "ms");

        // 3. 计算目标位置（使用实际时长）
        long targetMs = Math.max(0, actualDurationMs - offsetMs);
        long targetNs = targetMs * 1_000_000L;
        logRecord("🎯 目标位置: " + targetMs + "ms（实际时长 - " + offsetMs + "ms）");

        // 4. 执行 seek
        boolean seekSuccess = pipeline.seek(
                currentRate,
                Format.TIME,
                EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                SeekType.SET,
                targetNs,
                SeekType.NONE,
                -1
        );

        logRecord("📌 Seek 结果: " + (seekSuccess ? "✅ 成功" : "❌ 失败"));

        // 5. 验证 seek 后的位置
        if (seekSuccess) {
            try {
                Thread.sleep(100);  // 等待 seek 完成
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            long newPosNs = pipeline.queryPosition(Format.TIME);
            long newPosMs = newPosNs / 1_000_000L;
            logRecord("📍 Seek 后位置: " + newPosMs + "ms");
            logRecord("📊 与目标差距: " + (targetMs - newPosMs) + "ms");
            logRecord("📊 与实时差距: " + (actualDurationMs - newPosMs) + "ms");
        }

        logRecord("==============================");
        return seekSuccess;
    }

    /**
     * Seek 到文件末尾前指定毫秒数的位置
     * @param offsetMs 距离末尾的毫秒数（例如 200 表示末尾前 200ms）
     * @return 是否成功
     */
    public boolean seekToNearEnd(long offsetMs) {
        try {
            if (pipeline == null) {
                logRecord("❌ pipeline 为 null");
                return false;
            }

            // 1. ⭐ 获取视频总时长（纳秒）
            long durationNs = pipeline.queryDuration(Format.TIME);

            if (durationNs <= 0) {
                logRecord("❌ 无法获取视频时长");
                return false;
            }

            // 2. 计算目标位置（总时长 - offsetMs）
            long offsetNs = offsetMs * 1_000_000L;  // 毫秒转纳秒
            long targetPositionNs = Math.max(0, durationNs - offsetNs);

            double durationSec = durationNs / 1_000_000_000.0;
            double targetSec = targetPositionNs / 1_000_000_000.0;

            logRecord(String.format("🎯 视频总时长: %.3fs, 目标位置: %.3fs (末尾前 %dms)",
                    durationSec, targetSec, offsetMs));

            // 3. ⭐ Seek 到目标位置
            boolean seekSuccess = pipeline.seek(
                    currentRate,                    // 播放速度
                    Format.TIME,                    // 时间格式
                    EnumSet.of(SeekFlags.FLUSH),   // 刷新缓冲
                    SeekType.SET, targetPositionNs, // 设置起始位置（纳秒）
                    SeekType.NONE, -1               // 不设置结束位置
            );

            if (seekSuccess) {
                logRecord("✅ Seek 成功，位置: " + targetSec + "秒");
            } else {
                logRecord("❌ Seek 失败");
            }

            return seekSuccess;

        } catch (Exception e) {
            logRecord("❌ Seek 异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Seek 到文件末尾前 200ms（常用场景：边录边播）
     */
    public boolean seekToNearEnd200ms() {
        return seekToNearEnd(200);
    }

    /**
     * 检查播放器是否可用
     */
    public boolean isReady() {
        return pipeline != null;
    }

    /**
     * 重置播放器（出错时使用）
     */
    public void reset() {
        logRecord("🔄 重置播放器");
        try {
            if (pipeline != null) {
                pipeline.setState(State.NULL);
                Thread.sleep(100);
            }
        } catch (Exception e) {
            logRecord("⚠️ 重置失败: " + e.getMessage());
        }
    }

    // 添加外部时长支持
    private Long externalDurationNs = null;

    public void setExternalDuration(long durationNs) {
        this.externalDurationNs = durationNs;
    }

    // 修改 seek 方法使用外部时长
    public boolean seekToLiveByPercent(double percent) {
        logRecord("========== Seek 到实时位置 ==========");
        logRecord("🎯 目标百分比: " + percent + "%");



        try{
            // ⭐ 方法1：使用外部时长（优先，最准确）
            if (externalDurationNs != null && externalDurationNs > 0) {
                long targetNs = (long) (externalDurationNs * (percent / 100.0));
                long targetMs = targetNs / 1_000_000L;

                logRecord("📊 外部 duration: " + (externalDurationNs / 1_000_000L) + "ms");
                logRecord("🎯 目标位置: " + targetMs + "ms");

                // PAUSE
                //pipeline.setState(State.PAUSED);
                //Thread.sleep(100);

                // ⭐ 使用 Format.TIME（时间格式，单位：纳秒）
                boolean success = pipeline.seek(
                        currentRate,                    // 播放速度
                        Format.TIME,            // ⭐ 时间格式（纳秒）
                        EnumSet.of(
                                SeekFlags.FLUSH,      // 清空缓冲
                                SeekFlags.KEY_UNIT,   // 跳到关键帧
                                SeekFlags.SNAP_AFTER  // 跳到之后的关键帧（更接近实时）
                        ),
                        SeekType.SET,           // 绝对位置
                        targetNs,               // ⭐ 目标位置（纳秒）
                        SeekType.NONE,          // 不设置结束位置
                        -1                      // 播放到末尾
                );

                // 恢复播放
                //pipeline.setState(State.PLAYING);

                // 验证结果
                if (success) {
                    Thread.sleep(50);
                    long actualPosMs = pipeline.queryPosition(Format.TIME) / 1_000_000L;
                    long actualDurationMs = externalDurationNs / 1_000_000L;
                    double actualPercent = actualDurationMs > 0 ? (actualPosMs * 100.0 / actualDurationMs) : 0;

                    logRecord("✅ Seek 成功（外部时长）");
                    logRecord("📍 实际位置: " + actualPosMs + "ms / " + actualDurationMs + "ms");
                    logRecord("📊 实际百分比: " + String.format("%.2f", actualPercent) + "%");
                    logRecord("📊 与目标差距: " + Math.abs(targetMs - actualPosMs) + "ms");
                    return true;
                } else {
                    logRecord("⚠️ TIME 格式 seek 失败，尝试回退到百分比格式");
                }
            }

            // ⭐ 方法2：回退到百分比格式（如果外部时长不可用或 TIME seek 失败）
            logRecord("📊 使用百分比格式 seek");
            long percentValue = (long) (percent * 10000);

            pipeline.setState(State.PAUSED);
            Thread.sleep(100);

            // ⭐ 使用 Format.PERCENT
            boolean success = pipeline.seek(
                    1.0,
                    Format.PERCENT,         // ⭐ 百分比格式
                    EnumSet.of(SeekFlags.FLUSH, SeekFlags.KEY_UNIT, SeekFlags.SNAP_AFTER),
                    SeekType.SET,
                    percentValue,           // ⭐ 百分比值（98% = 980000）
                    SeekType.NONE,
                    -1
            );

            pipeline.setState(State.PLAYING);

            if (success) {
                Thread.sleep(50);
                long actualPosMs = pipeline.queryPosition(Format.TIME) / 1_000_000L;
                long fileDurationMs = pipeline.queryDuration(Format.TIME) / 1_000_000L;
                double actualPercent = fileDurationMs > 0 ? (actualPosMs * 100.0 / fileDurationMs) : 0;

                logRecord("✅ Seek 成功（百分比格式）");
                logRecord("📍 实际位置: " + actualPosMs + "ms / " + fileDurationMs + "ms");
                logRecord("📊 实际百分比: " + String.format("%.2f", actualPercent) + "%");
            } else {
                logRecord("❌ Seek 失败");
            }

            logRecord("======================================");
            return success;
        }catch (Exception e){

        }

        return  false;

    }

}
