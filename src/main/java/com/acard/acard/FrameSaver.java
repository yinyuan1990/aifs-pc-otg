package com.acard.acard;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * 固定内存的帧保存器
 * 使用阻塞队列控制内存占用，避免内存溢出
 */
public class FrameSaver {
    
    private static class FrameData {
        final BufferedImage image;
        final int frameNumber;
        final long receiveTimestamp;  // 帧接收时间
        final long queueTimestamp;    // 加入队列时间
        
        FrameData(BufferedImage image, int frameNumber, long receiveTimestamp) {
            this.image = image;
            this.frameNumber = frameNumber;
            this.receiveTimestamp = receiveTimestamp;
            this.queueTimestamp = System.currentTimeMillis();
        }
    }
    
    private final BlockingQueue<FrameData> frameQueue;
    private final ExecutorService saveExecutor;
    private final AtomicInteger frameCounter = new AtomicInteger(0);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final String outputDir;
    private final int maxQueueSize;
    
    // 延迟统计
    private volatile long lastSaveCompletedTime = 0;
    private volatile long lastFrameReceiveTime = 0;  // 最新帧接收时间
    private volatile long totalDelayMs = 0;
    private volatile int savedFrameCount = 0;
    private volatile long maxDelayMs = 0;
    private volatile long minDelayMs = Long.MAX_VALUE;
    
    // 帧率统计
    private volatile long frameRateStartTime = 0;
    private volatile int frameRateCounter = 0;
    private volatile double currentFPS = 0.0;
    
    /**
     * 创建帧保存器
     * @param outputDir 输出目录
     * @param maxQueueSize 最大队列大小（控制内存使用）
     */
    public FrameSaver(String outputDir, int maxQueueSize) {
        this.outputDir = outputDir;
        this.maxQueueSize = maxQueueSize;
        this.frameQueue = new ArrayBlockingQueue<>(maxQueueSize);
        this.saveExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FrameSaver-Thread");
            t.setDaemon(true);
            return t;
        });
        
        // 创建输出目录
        File outputDirectory = new File(outputDir);
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }
    }
    
    /**
     * 启动帧保存服务
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            saveExecutor.submit(this::saveFrameLoop);
            System.out.println("FrameSaver 已启动，队列大小: " + maxQueueSize);
        }
    }
    
    /**
     * 停止帧保存服务
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            saveExecutor.shutdown();
            System.out.println("FrameSaver 已停止");
        }
    }
    
    /**
     * 保存一帧图像（阻塞直到保存）
     * @param image 要保存的图像
     * @throws InterruptedException 如果线程被中断
     */
    public void saveFrame(BufferedImage image) throws InterruptedException {
        if (!isRunning.get()) {
            return;
        }
        
        int frameNumber = frameCounter.incrementAndGet();
        long timestamp = System.currentTimeMillis();
        lastFrameReceiveTime = timestamp;  // 更新最新帧接收时间
        
        // 帧率统计
        updateFrameRate(timestamp);
        
        FrameData frameData = new FrameData(image, frameNumber, timestamp);
        
        // 使用put()方法，如果队列满了会阻塞等待
        frameQueue.put(frameData);
    }
    
    /**
     * 更新帧率统计
     */
    private void updateFrameRate(long currentTime) {
        if (frameRateStartTime == 0) {
            frameRateStartTime = currentTime;
            frameRateCounter = 1;
        } else {
            frameRateCounter++;
            long elapsedTime = currentTime - frameRateStartTime;
            
            // 每秒统计一次帧率
            if (elapsedTime >= 1000) {
                currentFPS = (double) frameRateCounter * 1000.0 / elapsedTime;
                System.out.println(String.format("实际接收帧率: %.1f FPS (统计周期: %d帧, %dms)", 
                    currentFPS, frameRateCounter, elapsedTime));
                
                // 重置统计
                frameRateStartTime = currentTime;
                frameRateCounter = 0;
            }
        }
    }
    
    /**
     * 获取当前队列中待保存的帧数
     */
    public int getQueueSize() {
        return frameQueue.size();
    }
    
    /**
     * 获取已保存的帧总数
     */
    public int getSavedFrameCount() {
        return frameCounter.get();
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * 获取内存使用情况
     */
    public String getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        
        return String.format("内存使用: %.1fMB/%.1fMB, 队列: %d/%d", 
            usedMemory / 1024.0 / 1024.0,
            totalMemory / 1024.0 / 1024.0,
            frameQueue.size(),
            maxQueueSize);
    }
    
    /**
     * 获取帧保存延迟统计信息
     */
    public FrameDelayStats getDelayStats() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastSave = lastSaveCompletedTime > 0 ? currentTime - lastSaveCompletedTime : 0;
        long realtimeGap = (lastFrameReceiveTime > 0 && lastSaveCompletedTime > 0) ? 
            lastFrameReceiveTime - lastSaveCompletedTime : 0;
        double avgDelayMs = savedFrameCount > 0 ? (double) totalDelayMs / savedFrameCount : 0;
        
        return new FrameDelayStats(
            timeSinceLastSave,
            realtimeGap,
            avgDelayMs,
            maxDelayMs,
            minDelayMs == Long.MAX_VALUE ? 0 : minDelayMs,
            frameQueue.size(),
            savedFrameCount,
            currentFPS
        );
    }
    
    /**
     * 帧延迟统计数据类
     */
    public static class FrameDelayStats {
        public final long timeSinceLastSaveMs;  // 距离上次保存完成的时间
        public final long realtimeGapMs;        // 最新帧与最新保存帧的实时差距
        public final double averageDelayMs;     // 平均延迟
        public final long maxDelayMs;           // 最大延迟
        public final long minDelayMs;           // 最小延迟
        public final int queueSize;             // 当前队列大小
        public final int totalSavedFrames;      // 已保存帧总数
        public final double currentFPS;         // 当前实际接收帧率
        
        public FrameDelayStats(long timeSinceLastSaveMs, long realtimeGapMs, double averageDelayMs, 
                              long maxDelayMs, long minDelayMs, int queueSize, int totalSavedFrames, double currentFPS) {
            this.timeSinceLastSaveMs = timeSinceLastSaveMs;
            this.realtimeGapMs = realtimeGapMs;
            this.averageDelayMs = averageDelayMs;
            this.maxDelayMs = maxDelayMs;
            this.minDelayMs = minDelayMs;
            this.queueSize = queueSize;
            this.totalSavedFrames = totalSavedFrames;
            this.currentFPS = currentFPS;
        }
        
        @Override
        public String toString() {
            return String.format("延迟统计: 实时差距%dms, 平均%.1fms, 最大%dms, 最小%dms, 队列%d, 已保存%d帧, 距上次保存%dms, 当前帧率%.1fFPS",
                realtimeGapMs, averageDelayMs, maxDelayMs, minDelayMs, queueSize, totalSavedFrames, timeSinceLastSaveMs, currentFPS);
        }
    }
    
    /**
     * 保存帧的主循环（在后台线程中运行）
     */
    private void saveFrameLoop() {
        System.out.println("帧保存线程已启动");
        
        while (isRunning.get() || !frameQueue.isEmpty()) {
            try {
                FrameData frameData = frameQueue.take(); // 阻塞等待
                saveFrameToDisk(frameData);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("帧保存线程已结束");
    }
    
    /**
     * 将帧数据保存到磁盘
     */
    private void saveFrameToDisk(FrameData frameData) {
        try {
            long saveStartTime = System.currentTimeMillis();
            String filename = String.format("frame_%06d_%d.png", 
                frameData.frameNumber, frameData.receiveTimestamp);
            File outputFile = new File(outputDir, filename);
            
            ImageIO.write(frameData.image, "PNG", outputFile);
            
            // 计算延迟统计
            long saveCompletedTime = System.currentTimeMillis();
            long totalDelay = saveCompletedTime - frameData.receiveTimestamp;
            long queueDelay = saveStartTime - frameData.queueTimestamp;
            
            // 更新统计信息
            synchronized (this) {
                lastSaveCompletedTime = saveCompletedTime;
                totalDelayMs += totalDelay;
                savedFrameCount++;
                maxDelayMs = Math.max(maxDelayMs, totalDelay);
                minDelayMs = Math.min(minDelayMs, totalDelay);
            }
            
            System.out.println(String.format("保存帧: %d -> %s (总延迟: %dms, 队列延迟: %dms)", 
                frameData.frameNumber, filename, totalDelay, queueDelay));
            
            // 每50帧打印一次内存信息
            if (frameData.frameNumber % 50 == 0) {
                System.out.println(getMemoryInfo());
            }
            
        } catch (IOException e) {
            System.err.println("保存第 " + frameData.frameNumber + " 帧失败: " + e.getMessage());
        }
    }
}