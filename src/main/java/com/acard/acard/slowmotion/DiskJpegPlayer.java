package com.acard.acard.slowmotion;

import com.acard.acard.controller.LoginController;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import com.acard.acard.utils.TurboJpegEncoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 零内存 JPEG 序列播放器（直接从磁盘读取）
 * 
 * 特点：
 * - 不使用内存缓存，每帧都从磁盘读取
 * - 依赖 OS 文件缓存优化性能
 * - 支持可变速度播放
 * - 支持随机 seek
 * - 支持双模式：实时流 + 回放
 */
public class DiskJpegPlayer {
    
    /**
     * 播放模式
     */
    public enum PlayMode {
        REALTIME,   // 实时流模式（从推送路径读取）
        PLAYBACK    // 回放模式（从固定目录读取）
    }
    
    private final String playbackDirectory;  // 回放目录（runtime/captures/slow）
    private final String filePattern;
    private  int startFrame;

    public int getCopyCount() {
        return copyCount;
    }

    public void setCopyCount(int copyCount) {
        this.copyCount = copyCount;
    }

    private int copyCount=0;



    private  int endFrame;  // ⭐ 改为 volatile，支持动态更新

    public int getStartFrame() {
        return startFrame;
    }

    public void setStartFrame(int startFrame) {
        this.startFrame = startFrame;
    }

    public void setEndFrame(int endFrame) {
        this.endFrame = endFrame;
    }

    private  int maxFrame;

    public int getMaxFrame() {
        return maxFrame;
    }

    public void setMaxFrame(int maxFrame) {
        this.maxFrame = maxFrame;
    }

    private volatile PlayMode currentMode = PlayMode.REALTIME;  // ⭐ 默认实时流模式
    private volatile String realtimeFramePath = null;  // ⭐ 实时流：最新一帧的路径
    
    private volatile boolean isPlaying = false;
    private volatile int currentFrameIndex;
    private int currentFrameProssIndex;
    private volatile double playbackSpeed = 1.0;  // 1.0 = 正常速度
    private volatile boolean autoFollowLatest = false;  // ⭐ 自动追赶最新帧
    
    private Thread playbackThread;
    private FrameCallback frameCallback;


    private PlayStateCallback playStateCallback;

    private int fps=30;

    private boolean isRealPlaying = false;

    public boolean isRealPlaying() {
        return isRealPlaying;
    }

    public void setRealPlaying(boolean realPlaying) {
        isRealPlaying = realPlaying;
    }

    // ⭐ 新增：单线程执行器（用于推送帧的串行处理）
    private final ExecutorService realtimeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiskJpegPlayer-RealtimeFrame");
        t.setDaemon(true);  // 守护线程，不阻止JVM退出
        return t;
    });

    private final ExecutorService realtimeExecutor2 = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiskJpegPlayer-FilePlath");
        t.setDaemon(true);  // 守护线程，不阻止JVM退出
        return t;
    });
    
    // ⭐ 实时流帧率限制（防止内存暴涨）- 根据系统内存动态调整
    private volatile long lastRealtimeFrameTime = 0;
    private static final long MIN_REALTIME_FRAME_INTERVAL_NS = calculateFrameIntervalByMemory();
    
    /**
     * 根据系统内存大小计算帧间隔
     * 低于8GB: 20fps (50ms)
     * 8-30GB: 30fps (33.33ms) 
     * 30-60GB: 45fps (22.22ms)
     * 高于60GB: 60fps (16.67ms)
     */
    private static long calculateFrameIntervalByMemory() {
        long totalMemoryGB = 16; // 默认16GB
        try {
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long totalBytes = ((com.sun.management.OperatingSystemMXBean) os).getTotalPhysicalMemorySize();
                totalMemoryGB = totalBytes / (1024L * 1024L * 1024L);
            }
        } catch (Exception e) {
            System.out.println("⚠️ 无法获取系统内存，使用默认30fps");
        }
        
        long intervalNs;
        int fps;
        if (totalMemoryGB <= 8) {
            fps = 20;
            intervalNs = 50_000_000L;  // 1000ms/20 = 50ms
        }else if (totalMemoryGB <= 16) {
            fps = 20;
            intervalNs = 50_000_000L;  // 1000ms/30 = 33.33ms
        } else if (totalMemoryGB <= 30) {
            fps = 30;
            intervalNs = 33_333_333L;  // 1000ms/30 = 33.33ms
        } else if (totalMemoryGB <= 60) {
            fps = 45;
            intervalNs = 22_222_222L;  // 1000ms/45 = 22.22ms
        } else {
            fps = 60;
            intervalNs = 16_666_666L;  // 1000ms/60 = 16.67ms
        }
        
        System.out.println("🖥️ 系统内存: " + totalMemoryGB + "GB, 实时流限制: " + fps + "fps");
        return intervalNs;
    }
    /**
     * 帧回调接口
     */
    public interface FrameCallback {
        void onFrame(BufferedImage image, int frameIndex, int totalFrames);

    }


    public interface PlayStateCallback {
        void onStateCallBack();
    }

    public void setPlayStateCallback(PlayStateCallback callback) {
        this.playStateCallback = callback;
    }

    /**
     * 构造函数
     * 
     * @param playbackDirectory 回放目录路径（如 "runtime/captures/slow"）
     * @param filePattern       文件名模式（如 "s_%05d.jpeg"）
     * @param startFrame        起始帧索引
     * @param endFrame          结束帧索引
     */
    public DiskJpegPlayer(String playbackDirectory, String filePattern, int startFrame, int endFrame,int maxFrame) {
        this.playbackDirectory = playbackDirectory;
        this.filePattern = filePattern;
        this.startFrame = startFrame;
        this.maxFrame =maxFrame;
        this.endFrame = this.startFrame+maxFrame-1;

        this.currentFrameIndex = startFrame;

        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/seek/seek_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            recordLogger = new PrintWriter(new FileWriter(logFile, true));

            fps = FileToos.getOptimalJpegQuality();


        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
    }


    public void initData(int startFrame, int endFrame,int maxFrame){

        this.startFrame = startFrame;
        this.maxFrame =maxFrame;
        this.endFrame = this.startFrame+maxFrame-1;
        this.currentFrameIndex = startFrame;

    }


    /**
     * 设置帧回调
     */
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }
    
    /**
     * 从磁盘加载单帧（零内存模式）
     */
    public BufferedImage loadFrame(int frameIndex) {
        String filepath;
        
        // ⭐ 根据模式选择路径
        if (currentMode == PlayMode.REALTIME ) {
            // 实时流模式：直接使用推送的最新一帧路径
            if(realtimeFramePath != null) {
                filepath = realtimeFramePath;
            }else{
                filepath ="";

            }
        } else {
            // 回放模式：从回放目录按索引读取
            String filename = String.format(filePattern, frameIndex);
            filepath = playbackDirectory + File.separator + filename;
        }
        
        try {
            // ⭐ 直接从磁盘读取，不缓存
            File file = new File(filepath);
            if (!file.exists()) {
                System.err.println("文件不存在: " + filepath);
                return null;
            }
            
            // ⭐⭐⭐ GPU渲染测试：同时推送给GPU渲染器 ⭐⭐⭐

            // ⭐ 优先使用 TurboJPEG（7-11ms，快 2-3 倍）
            BufferedImage image = ImageIO.read(file);
            
            // ⭐ 降级到 ImageIO（18-20ms，稳定兼容）
            if (image == null) {
                image = ImageIO.read(file);
            }
            
            return image;
            
        } catch (Exception e) {
            System.err.println("加载失败: " + filepath + " - " + e.getMessage());
            return null;
        }
    }



    
    /**
     * 开始播放
     */
    public void play() {


        LogTools.getInstance().logRecord3("isPlaying: "+isPlaying +" currentFrameIndex: "+currentFrameIndex+" endFrame: "+endFrame+" startFrame: "+startFrame);
        if (isPlaying) {
            return;
        }

        if(currentFrameIndex>=endFrame){
            currentFrameIndex=startFrame;
        }
        
        isPlaying = true;

        playbackThread = new Thread(() -> {
            System.out.println("播放开始: 帧范围 " + startFrame + " - " + endFrame);

            long baseFrameTime = 1_000_000_000L / fps;  // 纳秒/帧

            LogTools.getInstance().logRecord("🎬 播放器 FPS: " + fps + ", 基准帧时间: " + (baseFrameTime / 1_000_000) + "ms");

            long lastFrameTime = System.nanoTime();

            while (isPlaying && currentFrameIndex <= (startFrame+copyCount)) {
                long currentTime = System.nanoTime();

                // ⭐ 根据播放速度调整目标帧时间
                long targetFrameTime = (long)(baseFrameTime / playbackSpeed);

                if (currentTime - lastFrameTime >= targetFrameTime) {
                    final int frameToLoad = currentFrameIndex;
                    FileToos.slowIndex = currentFrameIndex;
                    // 加载帧
                    BufferedImage frame = loadFrame(frameToLoad);
                    if (frame != null && frameCallback != null) {
                        frameCallback.onFrame(frame, frameToLoad-startFrame<0?frameToLoad:frameToLoad-startFrame, endFrame - startFrame + 1);
                    }

                    currentFrameIndex++;
                    lastFrameTime = currentTime;
                }else{

                }

            }
            //currentFrameIndex=startFrame;
            isPlaying =false;

        }, "JpegPlaybackThread");
        
        playbackThread.setDaemon(true);
        playbackThread.start();
    }
    
    /**
     * 暂停播放
     */
    public void pause() {
        isPlaying = false;
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }
    
    /**
     * 停止播放
     */
    public void stop() {
        pause();
        currentFrameIndex = startFrame;
    }
    
    /**
     * Seek 到指定帧
     */
    /**
     * Seek 到指定进度（0.0 - 1.0）
     */
    public void seekToProgress(double progress) {
        progress = Math.max(0.0, Math.min(1.0, progress));
        int targetFrame = startFrame + (int)((endFrame - startFrame) * progress);
        logRecord("pross: "+progress+" ---> "+targetFrame +" NEWFRAME: "+currentFrameProssIndex);

        if(FileToos.isCallBack){
            if (targetFrame > currentFrameProssIndex) {
                if (isRealtimeMode()) {
                    targetFrame = currentFrameProssIndex;
                    if (targetFrame >= startFrame && targetFrame <= endFrame) {
                        // ⭐ Seek 操作自动切换到回放模式

                        // ⭐ 强制设置帧索引
                        currentFrameIndex = startFrame;
                        // ⭐ 立即显示该帧（无论播放还是暂停）
                        if (frameCallback != null) {
                            BufferedImage frame = loadFrame(currentFrameIndex);
                            if (frame != null) {
                                frameCallback.onFrame(frame, currentFrameIndex - startFrame < 0 ? currentFrameIndex : currentFrameIndex - startFrame, endFrame - startFrame + 1);
                            }
                        }
                        // 如果正在播放，播放线程会从新的 currentFrameIndex 继续
                        logRecord("🎯 Seek 到帧1: " + currentFrameIndex);


                    }
                } else {
                    //switchToRealtimeMode();
                    logRecord("🎯 Seek 到帧2: " + currentFrameIndex);
                    stop();
                    switchToRealtimeMode();
                    setRealPlaying(true);
                    seekToFrame(targetFrame);
                }

            } else {
                setRealPlaying(false);
                switchToPlaybackMode();
                stop();
                seekToFrame(targetFrame);
            }
        }else{
            if(targetFrame>=endFrame){
                targetFrame=startFrame;
            }
            seekToFrame(targetFrame);
        }


    }

    /**
     * Seek 到指定帧
     */
    public void seekToFrame(int frameIndex) {
        if (frameIndex >= startFrame && frameIndex <= endFrame) {
            // ⭐ Seek 操作自动切换到回放模式
            //switchToPlaybackMode();
            stop();
            playStateCallback.onStateCallBack();
            // ⭐ 强制设置帧索引
            currentFrameIndex = frameIndex;
            // ⭐ 立即显示该帧（无论播放还是暂停）
            if (frameCallback != null) {
                BufferedImage frame = loadFrame(currentFrameIndex);
                if (frame != null) {
                    frameCallback.onFrame(frame, currentFrameIndex-startFrame<0?currentFrameIndex:currentFrameIndex-startFrame, endFrame - startFrame + 1);
                }
            }
            // 如果正在播放，播放线程会从新的 currentFrameIndex 继续
            logRecord("🎯 Seek 到帧: " + frameIndex);
        }
    }
    


    private PrintWriter recordLogger;
    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }
    /**
     * 设置播放速度
     * @param speed 速度倍数（0.25, 0.5, 1.0, 2.0, 4.0 等）
     */
    public void setPlaybackSpeed(double speed) {
        // ⭐ 调速操作自动切换到回放模式
        if(isRealtimeMode()){
             if(speed>=1.0){
                 return;
             }
        }
        switchToPlaybackMode();
        this.playbackSpeed = Math.max(0.1, Math.min(10.0, speed));
        logRecord("播放速度: " + this.playbackSpeed + "x");
    }
    
    /**
     * 获取当前帧索引
     */
    public int getCurrentFrameIndex() {
        return currentFrameIndex;
    }
    
    /**
     * 获取当前播放进度（0.0 - 1.0）
     */
    public double getCurrentProgress() {
        return (double)(currentFrameIndex - startFrame) / (endFrame - startFrame);
    }
    
    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }
    
    /**
     * 获取总帧数
     */
    public int getTotalFrames() {
        return endFrame - startFrame + 1;
    }
    
    /**
     * 获取播放速度
     */
    public double getPlaybackSpeed() {
        return playbackSpeed;
    }
    
    // ========== 推送播放支持 ==========
    
    /**
     * 动态更新结束帧（用于实时推送新帧）
     * 
     * @param newEndFrame 新的结束帧索引
     */
    public void updateEndFrame(int newEndFrame) {
        if (newEndFrame > endFrame) {
            endFrame = newEndFrame;
            logRecord("📊 结束帧已更新: " + endFrame);
        }
    }
    
    /**
     * 启用/禁用自动追赶最新帧模式
     * 
     * @param enable true=播放到末尾时等待新帧（不循环），false=循环播放
     */
    public void setAutoFollowLatest(boolean enable) {
        this.autoFollowLatest = enable;
        System.out.println("🔄 自动追赶模式: " + (enable ? "开启" : "关闭"));
    }
    
    /**
     * 是否启用了自动追赶模式
     */
    public boolean isAutoFollowLatest() {
        return autoFollowLatest;
    }
    
    /**
     * 推送新帧（外部通知有新帧时调用）
     * 
     * @param frameIndex 新帧的索引
     */
    public void pushNewFrame(int frameIndex) {
        updateEndFrame(frameIndex);
        
        // ⭐ 如果播放器在末尾等待或暂停，立即显示新帧
        if (autoFollowLatest && currentFrameIndex >= endFrame - 1) {
            currentFrameIndex = frameIndex;
            
            // 立即加载并显示新帧
            if (frameCallback != null) {
                new Thread(() -> {
                    BufferedImage newFrame = loadFrame(frameIndex);
                    if (newFrame != null) {
                        frameCallback.onFrame(newFrame, frameIndex, endFrame - startFrame + 1);
                    }
                }).start();
            }
            
            System.out.println("📥 新帧已推送并显示: " + frameIndex);
        } else if (!isPlaying) {
            // ⭐ 如果暂停中，也显示最新帧（可选）
            if (frameCallback != null && frameIndex > currentFrameIndex) {
                currentFrameIndex = frameIndex;
                // ⭐ 使用线程池，不创建新线程
                realtimeExecutor.submit(() -> {
                    BufferedImage frame = loadFrame(frameIndex);
                    if (frame != null) {
                        frameCallback.onFrame(frame, frameIndex, endFrame - startFrame + 1);
                    }
                });
            }
        }
    }
    
    /**
     * 跳转到最新帧
     */
    public void seekToLatest() {
        seekToFrame(endFrame);
    }
    
    /**
     * 获取结束帧索引（动态值）
     */
    public int getEndFrame() {
        return endFrame;
    }
    
    // ========== 双模式支持 ==========
    
    /**
     * 推送实时流帧（原始路径）
     * 
     * @param sourcePath 原始 JPEG 文件路径（实时流文件夹中的最新一帧）
     * @param frameIndex 帧索引
     */
    public void pushRealtimeFrame(String sourcePath, int frameIndex) {
        // ⭐ 保存最新一帧的路径
        this.realtimeFramePath = sourcePath;
        currentFrameProssIndex = frameIndex;
        updateEndFrame(frameIndex);
        
        // ⭐ 仅在实时流模式下立即显示
        if (currentMode == PlayMode.REALTIME && frameCallback != null) {
            // ⭐ 帧率限制：防止 1920x1080 等高分辨率下内存暴涨
            // 限制最大显示帧率为 30fps，跳过中间帧以减少内存分配

            this.currentFrameIndex = frameIndex;
            FileToos.slowIndex = frameIndex;

            // GPU渲染已移到 loadFrame 内部，这里不重复调用
            
            long currentTime = System.nanoTime();
            if (currentTime - lastRealtimeFrameTime < MIN_REALTIME_FRAME_INTERVAL_NS) {
                // 跳过此帧，减少内存压力（文件复制不受影响，只是不显示）
                return;
            }
            lastRealtimeFrameTime = currentTime;

            BufferedImage frame = loadFrame(frameIndex);
            if (frame != null) {
                logRecord("this.realtimeFramePath---> "+this.realtimeFramePath +" =====> true ");
                frameCallback.onFrame(frame, frameIndex-startFrame<0?frameIndex:frameIndex-startFrame, endFrame - startFrame + 1);
            }else{
                logRecord("this.realtimeFramePath---> "+this.realtimeFramePath+ " =======> false");
            }

        }
    }
    
    /**
     * 切换到实时流模式（点击"最新"按钮）
     */
    public void switchToRealtimeMode() {

        isRealPlaying = true;
        if (currentMode != PlayMode.REALTIME) {

            if (isPlaying) {
                pause();  // 停止播放
                logRecord("🔴 已停止回放循环");
            }
            // ⭐ 重置播放速度为 1x
            this.playbackSpeed = 1.0;
            logRecord("🔄 播放速度已重置为 1x");
            currentMode = PlayMode.REALTIME;
            currentFrameIndex = endFrame;  // 跳到最新帧
            logRecord("🔴 切换到实时流模式");
        }
    }
    
    /**
     * 切换到回放模式（seek/调速）
     */
    public void switchToPlaybackMode() {

        isRealPlaying = false;
        if (currentMode != PlayMode.PLAYBACK) {
            currentMode = PlayMode.PLAYBACK;
        }

        logRecord("🔵 切换到回放模式");
        // ⭐ 自动开始播放
        if(currentFrameIndex==endFrame||currentFrameIndex==(endFrame-1)||currentFrameIndex==(endFrame-2)||
                currentFrameIndex==(endFrame-3)||currentFrameIndex==endFrame+1||currentFrameIndex==endFrame+2){
            currentFrameIndex =startFrame;
        }
        if (!isPlaying) {
            play();

            logRecord("▶️ 自动开始播放");
        }else{
            logRecord("正在播放");
        }
    }
    
    /**
     * 获取当前模式
     */
    public PlayMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 是否在实时流模式
     */
    public boolean isRealtimeMode() {
        return currentMode == PlayMode.REALTIME;
    }

    /**
     * 关闭播放器，释放资源
     */
    public void shutdown() {
        pause();  // 停止播放循环

        // ⭐ 关闭实时推送线程池
        if (realtimeExecutor != null && !realtimeExecutor.isShutdown()) {
            realtimeExecutor.shutdown();
            try {
                if (!realtimeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    realtimeExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                realtimeExecutor.shutdownNow();
            }
        }

        System.out.println("🛑 DiskJpegPlayer 已关闭");
    }


    public void previousFrame() {
        // 你的逻辑

        if(isRealtimeMode()){
            setRealPlaying(false);
        }else{
            pause();
        }
        playStateCallback.onStateCallBack();
        currentFrameIndex=currentFrameIndex-1;
        if(currentFrameIndex<startFrame){
            currentFrameIndex = startFrame;
        }

        FileToos.slowIndex = currentFrameIndex;
        BufferedImage frame = loadFrame2(currentFrameIndex);
        if (frame != null) {
            logRecord("this.realtimeFramePath---> "+this.currentFrameIndex +" =====> true ");
            frameCallback.onFrame(frame, currentFrameIndex-startFrame<0?currentFrameIndex:currentFrameIndex-startFrame, endFrame - startFrame + 1);
        }else{
            logRecord("this.realtimeFramePath---> "+this.currentFrameIndex+ " =======> false");
        }

    }

    /**
     * 下一帧
     */
    public void nextFrame() {
        // 你的逻辑
        if(isRealtimeMode()){
            setRealPlaying(false);

        }else{
            pause();
        }
        playStateCallback.onStateCallBack();
        currentFrameIndex=currentFrameIndex+1;
        if(currentFrameIndex>endFrame){
            currentFrameIndex = endFrame;
        }
        FileToos.slowIndex = currentFrameIndex;
        BufferedImage frame = loadFrame2(currentFrameIndex);
        if (frame != null) {
            logRecord("this.realtimeFramePath---> "+this.currentFrameIndex +" =====> true ");
            frameCallback.onFrame(frame, currentFrameIndex-startFrame<0?currentFrameIndex:currentFrameIndex-startFrame, endFrame - startFrame + 1);
        }else{
            logRecord("this.realtimeFramePath---> "+this.currentFrameIndex+ " =======> false");
        }
    }


    public BufferedImage loadFrame2(int frameIndex) {
        String filepath;

        // ⭐ 根据模式选择路径

            // 回放模式：从回放目录按索引读取
            String filename = String.format(filePattern, frameIndex);
            filepath = playbackDirectory + File.separator + filename;


        try {
            // ⭐ 直接从磁盘读取，不缓存
            File file = new File(filepath);
            if (!file.exists()) {
                System.err.println("文件不存在: " + filepath);
                return null;
            }


            // ⭐ 优先使用 TurboJPEG（7-11ms，快 2-3 倍）
            BufferedImage image = ImageIO.read(file);

            // ⭐ 降级到 ImageIO（18-20ms，稳定兼容）
            if (image == null) {
                image = ImageIO.read(file);
            }

            return image;

        } catch (Exception e) {
            System.err.println("加载失败: " + filepath + " - " + e.getMessage());
            return null;
        }
    }


}

