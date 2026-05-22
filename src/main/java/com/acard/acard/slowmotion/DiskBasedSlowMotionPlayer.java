package com.acard.acard.slowmotion;

import com.acard.acard.capture.DiskCaptureCache;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.image.Image;
import javafx.util.Duration;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 基于磁盘缓存的边录边播慢动作播放器
 * 
 * 功能：
 * - 直接从DiskCaptureCache读取帧
 * - 实时跟随录制进度播放
 * - 支持1-10倍慢放
 * - 支持进度跳转
 */
public class DiskBasedSlowMotionPlayer {
    
    private final DiskCaptureCache captureCache;
    private Timeline playbackTimeline;
    private volatile boolean playing = false;
    private AtomicInteger currentFrameIndex = new AtomicInteger(0);
    private AtomicInteger lastDisplayedFrameIndex = new AtomicInteger(-1); // 最后显示的帧索引
    private volatile String lastDisplayedFramePath = null; // ✅ 最后显示的帧文件路径（唯一标识）
    
    // 慢放倍数（1-10）
    private double slowMotionFactor = 1.0;
    
    // 实时流帧率（从录制器获取）
    private int baseFps = 30;
    
    // ✅ 动态延迟检测（低端机自适应）
    private volatile long lastFrameTimestamp = 0;  // 上一帧显示时间
    private volatile long accumulatedDelay = 0;    // 累积延迟(ms)
    private final AtomicInteger slowFrameCount = new AtomicInteger(0);  // 慢帧计数
    private volatile double currentFrameInterval = 66.7;  // 当前帧间隔(ms)，初始15fps
    
    // 帧回调（显示到UI）
    private Consumer<Image> frameCallback;
    
    // 进度回调（更新进度条）
    private Consumer<Double> progressCallback;
    
    // 帧信息回调（显示文件名等信息）
    private java.util.function.BiConsumer<Integer, String> frameInfoCallback;
    
    // 循环播放
    private boolean loop = false;
    
    // ✅ 定期清理计数器：每100帧清理一次缓存，防止内存积压和GC峰值
    private final java.util.concurrent.atomic.AtomicInteger cleanupCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    
    // 🔍 帧计数器（用于诊断日志）
    private final java.util.concurrent.atomic.AtomicInteger frameCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    
    // 🔍 性能日志（慢放链路追踪）
    private com.acard.acard.utils.PerformanceLogger perfLogger;
    
    // ✅ 帧缓存：LRU缓存（用最少内存换取最优性能）
    // ✅ 缓存20帧（优化后），提升缓存命中率，降低IO和解码开销
    // 360x640 × 4字节 × 20帧 ≈ 18MB（0.5倍缩放，默认）✅ 极致性能
    // 432x768 × 4字节 × 20帧 ≈ 27MB（0.6倍缩放）
    // 540x960 × 4字节 × 20帧 ≈ 42MB（0.75倍缩放）
    private final java.util.Map<String, Image> frameCache = 
        new java.util.LinkedHashMap<String, Image>(25, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry eldest) {
            return size() > 20;  // ✅ 20张Image缓存（优化缓存命中率，降低IO开销）
        }
    };
    
    // ✅ 预加载线程池：使用有界队列，防止任务积压导致内存涨
    // 队列大小=5，超过5个任务时拒绝新任务（丢弃），避免内存积压
    private final java.util.concurrent.ThreadPoolExecutor preloadExecutor = 
        new java.util.concurrent.ThreadPoolExecutor(
            1, 1,  // 单线程
            0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(5),  // ✅ 有界队列，最多5个任务
            r -> {
                Thread t = new Thread(r, "SlowMotionPreloader");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()  // ✅ 队列满时丢弃新任务
        );
    
    public DiskBasedSlowMotionPlayer(DiskCaptureCache captureCache) {
        this.captureCache = captureCache;
        System.out.println("🎬 基于磁盘缓存的播放器已创建");
    }
    
    /**
     * 设置基准帧率
     */
    public void setBaseFps(int fps) {
        this.baseFps = fps;
        
        // 如果正在播放，动态调整播放速度
        if (playing && Math.abs(this.baseFps - fps) > 2) {
            updatePlaybackSpeed();
        }
    }
    
    /**
     * 设置慢放倍数（1-10）
     */
    public void setSlowMotionFactor(double factor) {
        double oldFactor = this.slowMotionFactor;
        this.slowMotionFactor = Math.max(1.0, Math.min(10.0, factor));
        
        System.out.println("🎬 设置慢放倍数: " + String.format("%.1fx", this.slowMotionFactor) + 
            " (播放帧率: " + String.format("%.1f", baseFps / this.slowMotionFactor) + " fps)");
        
        // 如果正在播放且倍数变化，重新调整播放速度
        if (playing && Math.abs(oldFactor - this.slowMotionFactor) > 0.01) {
            System.out.println("⚡ 动态调整播放速度...");
            updatePlaybackSpeed();
        }
    }
    
    /**
     * 播放
     */
    public void play() {
        if (playing) {
            return;
        }
        
        // ✅ 创建性能日志
        try {
            perfLogger = new com.acard.acard.utils.PerformanceLogger("slowmo");
            perfLogger.checkpoint("慢放播放开始前");
            perfLogger.logFrameCacheStatus(frameCache.size(), preloadExecutor.getQueue().size());
        } catch (Exception e) {
            System.err.println("⚠️ 创建性能日志失败: " + e.getMessage());
        }
        
        // ✅ 强制GC清理旧对象（降低慢放开始时的内存基线）
        System.gc();
        System.runFinalization();
        try { Thread.sleep(100); } catch (InterruptedException ignore) {}
        
        if (perfLogger != null) {
            perfLogger.checkpoint("GC清理后");
        }
        
        playing = true;
        
        // ✅ 动态播放帧率，自适应原始流帧率（低端机优化）
        // 配置：System Property "slowmo.maxfps"
        // - slowmo.maxfps=15: 默认，平衡流畅和CPU（CPU ~15%）
        // - slowmo.maxfps=20: 高端机
        // - slowmo.maxfps=10: 超低端机
        double configuredMaxFps = Double.parseDouble(System.getProperty("slowmo.maxfps", "15"));
        double maxPlaybackFps = configuredMaxFps;
        double theoreticalFps = baseFps / slowMotionFactor;
        double playbackFps = Math.min(theoreticalFps, maxPlaybackFps);
        
        System.out.println("📺 慢放播放配置: 原始" + baseFps + "fps, 倍速" + slowMotionFactor + 
            "x, 播放" + String.format("%.1f", playbackFps) + "fps");
        currentFrameInterval = 1000.0 / playbackFps;
        
        // ✅ 重置延迟统计
        lastFrameTimestamp = 0;
        accumulatedDelay = 0;
        slowFrameCount.set(0);
        
        playbackTimeline = new Timeline(new KeyFrame(
            Duration.millis(currentFrameInterval),
            event -> displayNextFrame()
        ));
        playbackTimeline.setCycleCount(Timeline.INDEFINITE);
        playbackTimeline.play();
        
        System.out.println("▶️ 开始边录边播: " + String.format("%.1f", playbackFps) + " fps " +
            (playbackFps < theoreticalFps ? "(限速，低端机优化)" : ""));
        System.out.println("💡 低端机优化提示:");
        System.out.println("   - 帧间隔: " + String.format("%.1f", currentFrameInterval) + "ms");
        System.out.println("   - 如果延迟累积，可设置 -Dslowmo.maxfps=10 (100ms间隔)");
        System.out.println("   - 或设置 -Dslowmo.maxfps=5 (200ms间隔，最低配置)");
    }
    
    /**
     * 暂停
     */
    public void pause() {
        if (!playing) {
            return;
        }
        
        playing = false;
        if (playbackTimeline != null) {
            playbackTimeline.stop();
        }
    }
    
    /**
     * 停止
     */
    public void stop() {
        pause();
        currentFrameIndex.set(0);
        lastDisplayedFrameIndex.set(-1);
    }
    
    /**
     * 显示下一帧
     */
    private void displayNextFrame() {
        long frameStartTime = System.currentTimeMillis();
        long loadTime = 0, renderTime = 0, totalTime = 0;
        
        // ✅ 帧计数器递增
        int currentFrameCount = frameCounter.incrementAndGet();
        
        // ✅ 动态延迟检测（低端机自适应）
        if (lastFrameTimestamp > 0) {
            long actualInterval = frameStartTime - lastFrameTimestamp;
            long expectedInterval = (long)currentFrameInterval;
            
            // 计算累积延迟
            if (actualInterval > expectedInterval + 50) {  // 超过50ms认为是延迟
                accumulatedDelay += (actualInterval - expectedInterval);
                slowFrameCount.incrementAndGet();
                
                // ✅ 延迟超过500ms，立即跳帧
                if (accumulatedDelay > 500) {
                    System.out.println("⚠️ 检测到累积延迟: " + accumulatedDelay + "ms，触发跳帧");
                }
            } else {
                // 延迟减少，逐渐恢复
                if (accumulatedDelay > 0) {
                    accumulatedDelay = Math.max(0, accumulatedDelay - 10);
                }
            }
        }
        lastFrameTimestamp = frameStartTime;
        
        // ✅ 每次都重新获取帧列表（实时跟随录制进度）
        List<DiskCaptureCache.DiskFrameItem> frames = captureCache.getFrames();
        int currentIndex = currentFrameIndex.get();
        
        // ✅ 动态跳帧策略（根据累积延迟调整）
        int totalFrames = frames.size();
        int skipThreshold = 15;  // 默认15帧
        
        // 根据累积延迟动态调整跳帧阈值
        if (accumulatedDelay > 2000) {
            skipThreshold = 5;   // 延迟>2s，更激进跳帧
        } else if (accumulatedDelay > 1000) {
            skipThreshold = 10;  // 延迟>1s，激进跳帧
        }
        
        if (totalFrames > 0 && (totalFrames - currentIndex) > skipThreshold) {
            int skipToIndex = totalFrames - 5;  // 跳到最新-5帧，留少量缓冲
            if (skipToIndex > currentIndex) {
                int skippedFrames = skipToIndex - currentIndex;
                System.out.println("⚡ 跳帧: " + currentIndex + " → " + skipToIndex + 
                    " (落后" + (totalFrames - currentIndex) + "帧, 延迟" + accumulatedDelay + "ms, 跳过" + skippedFrames + "帧)");
                currentIndex = skipToIndex;
                currentFrameIndex.set(skipToIndex);
                
                // 跳帧后重置累积延迟
                accumulatedDelay = 0;
                slowFrameCount.set(0);
            }
        }
        
        // 如果当前索引超过已录制帧数，等待新帧或循环
        if (currentIndex >= frames.size()) {
            if (loop && frames.size() > 0) {
                // 循环播放
                currentFrameIndex.set(0);
                currentIndex = 0;
            } else {
                // 等待新帧（边录边播模式）
                return;
            }
        }
        
        // 🔍 步骤1：加载帧
        long loadStart = System.nanoTime();
        Image frame = loadFrameWithCache(currentIndex, frames);
        loadTime = (System.nanoTime() - loadStart) / 1000000;
        
        if (frame != null && frameCallback != null) {
            // 🔍 步骤2：渲染帧（UI更新）
            long renderStart = System.nanoTime();
            frameCallback.accept(frame);
            renderTime = (System.nanoTime() - renderStart) / 1000000;
            
            // ✅ 记录已显示的帧索引和文件路径（唯一标识）
            lastDisplayedFrameIndex.set(currentIndex);
            if (currentIndex >= 0 && currentIndex < frames.size()) {
                lastDisplayedFramePath = frames.get(currentIndex).filePath;
            }
            
            // 🔍 步骤3：预加载后续帧
            long preloadStart = System.nanoTime();
            preloadNextFrames(currentIndex, frames, 3);  // ✅ 预加载3帧（避免线程竞争），配合20帧缓存
            long preloadTime = (System.nanoTime() - preloadStart) / 1000000;
            
            // 🔍 每50帧记录一次完整性能日志
            if (currentFrameCount % 50 == 0 && perfLogger != null) {
                totalTime = System.currentTimeMillis() - frameStartTime;
                perfLogger.logDetail("━━━ 第" + currentIndex + "帧性能分析 ━━━", "");
                perfLogger.logDetail("  加载耗时", loadTime + "ms");
                perfLogger.logDetail("  渲染耗时", renderTime + "ms");
                perfLogger.logDetail("  预加载耗时", preloadTime + "ms");
                perfLogger.logDetail("  总耗时", totalTime + "ms");
                perfLogger.logDetail("  缓存状态", frameCache.size() + "/50帧");
                perfLogger.logDetail("  预加载队列", preloadExecutor.getQueue().size() + "个任务");
                perfLogger.checkpoint("第" + currentFrameCount + "帧显示完成");
            }
        }
        
        // 更新进度
        if (progressCallback != null && frames.size() > 0) {
            double progress = (double) currentIndex / Math.max(1, frames.size());
            progressCallback.accept(progress);
        }
        
        // ✅ 更新帧信息（显示文件名）
        if (frameInfoCallback != null && currentIndex < frames.size()) {
            DiskCaptureCache.DiskFrameItem item = frames.get(currentIndex);
            String fileName = new java.io.File(item.filePath).getName();
            frameInfoCallback.accept(currentIndex + 1, fileName); // 从1开始计数
        }
        
        // ✅ 定期清理缓存：每100帧清理一次，防止内存积压和GC峰值
        int count = cleanupCounter.incrementAndGet();
        if (count >= 100) {
            cleanupCounter.set(0);
            synchronized (frameCache) {
                int oldSize = frameCache.size();
                frameCache.clear();  // 清空所有缓存
                System.out.println("🧹 定期清理frameCache: " + oldSize + "帧 → 0帧");
            }
            System.gc();  // 建议GC回收已释放的Image对象
            
            // ✅ 输出延迟统计（每100帧）
            if (slowFrameCount.get() > 0) {
                System.out.println("📊 延迟统计: 慢帧" + slowFrameCount.get() + "个, 累积延迟" + accumulatedDelay + "ms");
                
                // 如果慢帧过多（>20%），建议降低帧率
                if (slowFrameCount.get() > 20) {
                    System.out.println("💡 建议: 低端机型检测到，可设置 -Dslowmo.maxfps=10 或 -Dslowmo.maxfps=5 降低CPU");
                }
                slowFrameCount.set(0);
            }
        }
        
        currentFrameIndex.incrementAndGet();
        
        // ✅ 记录帧处理时间（用于动态调整）
        long frameEndTime = System.currentTimeMillis();
        long frameProcessTime = frameEndTime - frameStartTime;
        if (frameProcessTime > currentFrameInterval) {
            // 单帧处理时间超过帧间隔，可能导致延迟累积
            // 每10帧输出一次警告（避免日志刷屏）
            if (currentFrameIndex.get() % 10 == 0) {
                System.out.println("⚠️ 帧处理慢: " + frameProcessTime + "ms > " + 
                    String.format("%.1f", currentFrameInterval) + "ms (目标), 延迟累积中...");
            }
        }
    }
    
    /**
     * ✅ 从缓存加载帧（优先缓存，缺失则加载）
     */
    private Image loadFrameWithCache(int index, List<DiskCaptureCache.DiskFrameItem> frames) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        
        DiskCaptureCache.DiskFrameItem item = frames.get(index);
        String filePath = item.filePath;
        
        // ✅ 优先从缓存获取
        synchronized (frameCache) {
            Image cached = frameCache.get(filePath);
            if (cached != null && !cached.isError()) {
                return cached;  // 缓存命中，秒显
            }
        }
        
        // ✅ 缓存未命中，加载并放入缓存
        Image img = loadFrameDirect(filePath);
        if (img != null) {
            synchronized (frameCache) {
                frameCache.put(filePath, img);
            }
        }
        
        return img;
    }
    
    /**
     * ✅ 直接加载帧（同步，阻塞，动态缩放）
     * 支持横竖屏自适应：
     * - 横屏：1280x720 → 960x540（0.75倍）
     * - 竖屏：720x1280 → 540x960（0.75倍）
     * 
     * 优先使用TurboJPEG解码（速度快3-5倍，CPU降低60%）
     */
    private Image loadFrameDirect(String filePath) {
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                System.err.println("⚠️ 文件不存在: " + file.getPath());
                return null;
            }
            
            // ✅ 动态缩放策略（支持横竖屏）
            // 配置：System Property "slowmo.scale"
            // - slowmo.scale=0.5: 默认，极致性能（720x1280→360x640）✅ CPU终极优化
            // - slowmo.scale=0.6: 平衡（720x1280→432x768）
            // - slowmo.scale=0.75: 高画质（720x1280→540x960）
            // 💡 抓拍清晰度由磁盘缓存质量决定（JPEG 0.85），与播放缩放无关
            double scaleFactor = Double.parseDouble(System.getProperty("slowmo.scale", "0.5"));
            
            // ✅ 直接使用JavaFX Image（零临时数组，内存占用低）
            // 810x1440分辨率不大，JavaFX解码足够快
            // ⚠️ TurboJPEG临时数组(RGB+pixels=7MB)导致内存积压，已移除
            return loadFrameWithImageIO(file, scaleFactor);
            
        } catch (Exception e) {
            System.err.println("⚠️ 加载帧异常: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * ✅ 使用TurboJPEG解码（快速路径，CPU降低60%）
     */
    private Image loadFrameWithTurboJPEG(File file, double scaleFactor) {
        long startTime = System.nanoTime();
        long memBefore = 0;
        
        try {
            // 🔍 内存诊断（每50帧一次）
            if (frameCounter.incrementAndGet() % 50 == 0) {
                Runtime rt = Runtime.getRuntime();
                memBefore = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                System.out.println("🔍 [TurboJPEG] 解码前内存: " + memBefore + "MB, 缓存: " + frameCache.size());
            }
            
            // 1. 读取JPEG文件
            byte[] jpegData = java.nio.file.Files.readAllBytes(file.toPath());
            
            // 2. TurboJPEG解码（SIMD优化，速度快3-5倍）
            com.acard.acard.utils.TurboJpegEncoder.DecodedImage decoded = 
                com.acard.acard.utils.TurboJpegEncoder.decodeJPEGToRGB(jpegData);
            
            if (decoded == null) {
                System.err.println("⚠️ TurboJPEG解码失败，降级到ImageIO: " + file.getName());
                return loadFrameWithImageIO(file, scaleFactor);
            }
            
            // 3. 计算缩放后的尺寸
            int targetWidth = (int)(decoded.width * scaleFactor);
            int targetHeight = (int)(decoded.height * scaleFactor);
            
            // 4. 简单缩放RGB数据（最近邻插值，速度快）
            byte[] scaledRGB = scaleRGB(decoded.rgbData, decoded.width, decoded.height, 
                                        targetWidth, targetHeight);
            
            // 5. 转换为JavaFX Image
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(
                targetWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB
            );
            
            int[] pixels = new int[targetWidth * targetHeight];
            int index = 0;
            for (int i = 0; i < pixels.length; i++) {
                int r = scaledRGB[index++] & 0xFF;
                int g = scaledRGB[index++] & 0xFF;
                int b = scaledRGB[index++] & 0xFF;
                pixels[i] = (r << 16) | (g << 8) | b;
            }
            bi.setRGB(0, 0, targetWidth, targetHeight, pixels, 0, targetWidth);
            
            Image result = javafx.embed.swing.SwingFXUtils.toFXImage(bi, null);
            
            // 🔍 性能日志（每50帧一次）
            if (memBefore > 0 && perfLogger != null) {
                Runtime rt = Runtime.getRuntime();
                long memAfter = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
                long elapsedMs = (System.nanoTime() - startTime) / 1000000;
                long rgbSizeMB = scaledRGB.length / 1024 / 1024;
                long pixelsSizeMB = pixels.length * 4 / 1024 / 1024;
                
                perfLogger.logTurboJPEGDecode(file.getName(), elapsedMs, targetWidth, targetHeight,
                    rgbSizeMB, pixelsSizeMB, memBefore, memAfter);
                perfLogger.logFrameCacheStatus(frameCache.size(), preloadExecutor.getQueue().size());
            }
            
            // ✅ 显式释放临时数据，帮助GC（TurboJPEG的native memory）
            scaledRGB = null;
            pixels = null;
            
            return result;
            
        } catch (Throwable e) {
            System.err.println("⚠️ TurboJPEG解码异常: " + e.getMessage());
            return loadFrameWithImageIO(file, scaleFactor);
        }
    }
    
    /**
     * ✅ 使用JavaFX Image解码（兼容路径，ImageIO）
     */
    private Image loadFrameWithImageIO(File file, double scaleFactor) {
        long startTime = System.nanoTime();
        long ioTime = 0, decodeTime = 0, scaleTime = 0;
        
        try {
            // 🔍 步骤1：读取文件元数据
            long step1Start = System.nanoTime();
            Image tempImg = new Image(file.toURI().toString(), false);
            ioTime = (System.nanoTime() - step1Start) / 1000000;
            
            if (tempImg.isError()) {
                Exception error = tempImg.getException();
                System.err.println("❌ 图片加载失败[" + file.getName() + "]: " + 
                    (error != null ? error.getMessage() : "未知错误"));
                return null;
            }
            
            // 计算缩放后的尺寸（保持宽高比）
            double originalWidth = tempImg.getWidth();
            double originalHeight = tempImg.getHeight();
            double targetWidth = originalWidth * scaleFactor;
            double targetHeight = originalHeight * scaleFactor;
            
            // 🔍 步骤2：解码+缩放（关键CPU开销）
            long step2Start = System.nanoTime();
            Image img = new Image(file.toURI().toString(), 
                targetWidth, targetHeight, true, true, false);
            decodeTime = (System.nanoTime() - step2Start) / 1000000;
            
            if (img.isError()) {
                Exception error = img.getException();
                System.err.println("❌ 缩略图加载失败[" + file.getName() + "]: " + 
                    (error != null ? error.getMessage() : "未知错误"));
                return null;
            }
            
            // 🔍 每50帧记录一次性能日志（使用全局帧计数）
            int currentCount = frameCounter.get();
            if (currentCount % 50 == 0 && perfLogger != null) {
                long totalTime = (System.nanoTime() - startTime) / 1000000;
                perfLogger.logDetail("【JavaFX解码】", file.getName());
                perfLogger.logDetail("  IO时间", ioTime + "ms");
                perfLogger.logDetail("  解码+缩放", decodeTime + "ms");
                perfLogger.logDetail("  总耗时", totalTime + "ms");
                perfLogger.logDetail("  原始尺寸", (int)originalWidth + "x" + (int)originalHeight);
                perfLogger.logDetail("  目标尺寸", (int)targetWidth + "x" + (int)targetHeight);
            }
            
            return img;
            
        } catch (Exception e) {
            System.err.println("⚠️ ImageIO加载失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * ✅ 简单RGB缩放（最近邻插值，速度快）
     */
    private byte[] scaleRGB(byte[] srcRGB, int srcWidth, int srcHeight, 
                           int dstWidth, int dstHeight) {
        byte[] dstRGB = new byte[dstWidth * dstHeight * 3];
        
        double xRatio = (double)srcWidth / dstWidth;
        double yRatio = (double)srcHeight / dstHeight;
        
        int dstIndex = 0;
        for (int y = 0; y < dstHeight; y++) {
            int srcY = (int)(y * yRatio);
            for (int x = 0; x < dstWidth; x++) {
                int srcX = (int)(x * xRatio);
                int srcIndex = (srcY * srcWidth + srcX) * 3;
                
                dstRGB[dstIndex++] = srcRGB[srcIndex];     // R
                dstRGB[dstIndex++] = srcRGB[srcIndex + 1]; // G
                dstRGB[dstIndex++] = srcRGB[srcIndex + 2]; // B
            }
        }
        
        return dstRGB;
    }
    
    /**
     * ✅ 预加载后续N帧（后台线程，不阻塞播放）
     * 使用有界队列，队列满时自动丢弃任务，防止内存积压
     */
    private void preloadNextFrames(int currentIndex, List<DiskCaptureCache.DiskFrameItem> frames, int count) {
        // ✅ 检查队列是否快满（诊断日志）
        int queueSize = preloadExecutor.getQueue().size();
        if (queueSize >= 4) {  // 队列容量5，超过4时警告
            // 队列快满了，任务会被丢弃（DiscardPolicy）
            // 这是正常的，避免积压
            return;  // 直接返回，不提交新任务
        }
        
        try {
            preloadExecutor.submit(() -> {
                try {
                    for (int i = 1; i <= count; i++) {
                        int nextIndex = currentIndex + i;
                        if (nextIndex >= frames.size()) {
                            break;  // 超出范围
                        }
                        
                        DiskCaptureCache.DiskFrameItem item = frames.get(nextIndex);
                        String filePath = item.filePath;
                        
                        // 检查缓存是否已存在
                        synchronized (frameCache) {
                            if (frameCache.containsKey(filePath)) {
                                continue;  // 已缓存，跳过
                            }
                        }
                        
                        // 加载并放入缓存
                        Image img = loadFrameDirect(filePath);
                        if (img != null) {
                            synchronized (frameCache) {
                                frameCache.put(filePath, img);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ 预加载失败: " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 队列满，任务被丢弃（正常）
        }
    }
    
    /**
     * 跳转到指定帧
     */
    public void seekToFrame(int frameIndex) {
        List<DiskCaptureCache.DiskFrameItem> frames = captureCache.getFrames();
        
        if (frameIndex >= 0 && frameIndex < frames.size()) {
            currentFrameIndex.set(frameIndex);
            
            // 如果正在播放，立即显示
            if (playing) {
                Image frame = loadFrameWithCache(frameIndex, frames);  // ✅ 使用缓存
                if (frame != null && frameCallback != null) {
                    frameCallback.accept(frame);
                    // ✅ 更新最后显示的帧索引和路径
                    lastDisplayedFrameIndex.set(frameIndex);
                    lastDisplayedFramePath = frames.get(frameIndex).filePath;
                    
                    // ✅ 预加载后续帧（配合10帧缓存）
                    preloadNextFrames(frameIndex, frames, 2);
                }
            }
        }
    }
    
    /**
     * 更新播放速度（动态调整）
     */
    private void updatePlaybackSpeed() {
        System.out.println("🔄 更新播放速度 - 当前状态: playing=" + playing);
        
        if (playbackTimeline != null) {
            playbackTimeline.stop();
            System.out.println("⏸️ 已停止旧Timeline");
        }
        
        if (playing) {
            playing = false;
            play();
            System.out.println("▶️ 已重新启动播放，新速度: " + getPlaybackFps() + " fps");
        }
    }
    
    /**
     * 设置帧回调（显示到UI）
     */
    public void setFrameCallback(Consumer<Image> callback) {
        this.frameCallback = callback;
    }
    
    /**
     * 设置进度回调（更新进度条）
     */
    public void setProgressCallback(Consumer<Double> callback) {
        this.progressCallback = callback;
    }
    
    /**
     * 设置帧信息回调（文件名等）
     */
    public void setFrameInfoCallback(java.util.function.BiConsumer<Integer, String> callback) {
        this.frameInfoCallback = callback;
    }
    
    /**
     * 设置循环播放
     */
    public void setLoop(boolean loop) {
        this.loop = loop;
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return playing;
    }
    
    /**
     * 获取当前帧索引（下一个要播放的帧）
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex.get();
    }
    
    /**
     * 获取最后显示的帧索引（用于抓拍，更准确）
     * ⚠️ 注意：由于环形缓冲，索引可能不准确，优先使用 getLastDisplayedFramePath()
     */
    public int getLastDisplayedFrameIndex() {
        return lastDisplayedFrameIndex.get();
    }
    
    /**
     * 获取最后显示的帧文件路径（唯一标识，用于抓拍）
     */
    public String getLastDisplayedFramePath() {
        return lastDisplayedFramePath;
    }
    
    /**
     * 获取总帧数（已录制）
     */
    public int getTotalFrameCount() {
        return captureCache.getFrames().size();
    }
    
    /**
     * 获取当前进度（0.0-1.0）
     */
    public double getProgress() {
        int total = getTotalFrameCount();
        if (total == 0) {
            return 0.0;
        }
        return (double) currentFrameIndex.get() / total;
    }
    
    /**
     * 获取播放帧率（实际值，考虑低端机限速）
     */
    public double getPlaybackFps() {
        double theoreticalFps = baseFps / slowMotionFactor;
        return Math.min(theoreticalFps, 20.0);  // ✅ 最高20fps
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        stop();
        
        if (perfLogger != null) {
            perfLogger.checkpoint("清理资源前");
        }
        
        // ✅ 清理缓存（50帧缩略图 ≈ 100MB）
        synchronized (frameCache) {
            int cacheSize = frameCache.size();
            frameCache.clear();
            System.out.println("   - 已清理frameCache: " + cacheSize + "帧（~" + (cacheSize * 2) + "MB）");
            
            if (perfLogger != null) {
                perfLogger.logDetail("清理frameCache", cacheSize + "帧");
            }
        }
        
        // ✅ 立即关闭预加载线程池并清空队列（防止任务积压）
        java.util.List<Runnable> droppedTasks = preloadExecutor.shutdownNow();
        System.out.println("   - 已取消预加载任务: " + droppedTasks.size() + "个");
        
        if (perfLogger != null) {
            perfLogger.logDetail("取消预加载任务", droppedTasks.size() + "个");
        }
        
        // ✅ 建议GC清理已释放的Image对象
        System.gc();
        
        System.out.println("🧹 慢放播放器资源已清理");
        
        if (perfLogger != null) {
            perfLogger.complete("慢放播放器资源已清理");
        }
    }
}

