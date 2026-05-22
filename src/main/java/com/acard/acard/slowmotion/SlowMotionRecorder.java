package com.acard.acard.slowmotion;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.FileImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢动作录制器
 * 
 * 功能：
 * - 持续录制实时流到磁盘
 * - 自动检测帧率（30/60fps）
 * - 500MB磁盘限制（环形缓冲）
 * - 支持边录制边播放
 */
public class SlowMotionRecorder {
    
    private final String sessionId;
    private final Path recordDir;
    private final List<FrameMetadata> frames;
    private final AtomicLong frameCounter;
    private volatile boolean recording = false;
    
    // 磁盘限制：500MB
    private static final long MAX_DISK_SIZE = 500 * 1024 * 1024; // 500MB
    
    // JPEG质量（85%，平衡质量与大小）
    private static final float JPEG_QUALITY = 0.85f;
    
    // 自动检测的实时流帧率
    private volatile int detectedFps = 30;
    
    // 帧率检测窗口（帧数）
    private static final int FPS_DETECTION_WINDOW = 60;
    
    public SlowMotionRecorder(String sessionId) {
        this.sessionId = sessionId;
        this.recordDir = Paths.get("runtime", "slowmo", sessionId);
        this.frames = Collections.synchronizedList(new ArrayList<>());
        this.frameCounter = new AtomicLong(0);
        
        try {
            Files.createDirectories(recordDir);
            System.out.println("📁 慢动作录制目录已创建: " + recordDir);
        } catch (IOException e) {
            System.err.println("❌ 创建录制目录失败: " + e.getMessage());
        }
    }
    
    /**
     * 开始录制
     */
    public void startRecording() {
        recording = true;
        System.out.println("🔴 开始慢动作录制 (Session: " + sessionId + ")");
    }
    
    /**
     * 停止录制
     */
    public void stopRecording() {
        recording = false;
        System.out.println("⏹️ 停止慢动作录制 (总帧数: " + frames.size() + ", 检测帧率: " + detectedFps + " fps)");
    }
    
    /**
     * 是否正在录制
     */
    public boolean isRecording() {
        return recording;
    }
    
    /**
     * 保存帧（从实时流回调）
     * 
     * @param image 原始图像
     * @return 是否保存成功
     */
    public boolean saveFrame(BufferedImage image) {
        if (!recording) {
            return false;
        }
        
        try {
            long frameNum = frameCounter.incrementAndGet();
            String fileName = String.format("frame_%08d.jpg", frameNum);
            Path filePath = recordDir.resolve(fileName);
            
            // 保存JPEG
            boolean success = saveAsJPEG(image, filePath.toFile(), JPEG_QUALITY);
            
            if (success) {
                // 记录元数据
                FrameMetadata metadata = new FrameMetadata(
                    frameNum,
                    System.currentTimeMillis(),
                    filePath.toString(),
                    image.getWidth(),
                    image.getHeight()
                );
                
                synchronized (frames) {
                    frames.add(metadata);
                    
                    // 环形缓冲：超过500MB删除最旧帧
                    cleanupOldFrames();
                }
                
                // 每60帧检测一次帧率
                if (frameNum % FPS_DETECTION_WINDOW == 0) {
                    detectFrameRate();
                }
                
                // 每100帧打印一次状态
                if (frameNum % 100 == 0) {
                    long diskSize = calculateTotalSize();
                    System.out.println(String.format("📊 录制进度: %d 帧, %.1f MB / 500 MB, FPS=%d", 
                        frames.size(), diskSize / 1024.0 / 1024.0, detectedFps));
                }
            }
            
            return success;
            
        } catch (Exception e) {
            System.err.println("⚠️ 保存慢动作帧失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存为JPEG
     */
    private boolean saveAsJPEG(BufferedImage image, File file, float quality) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            JPEGImageWriteParam param = new JPEGImageWriteParam(null);
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            
            try (FileImageOutputStream output = new FileImageOutputStream(file)) {
                writer.setOutput(output);
                writer.write(null, new IIOImage(image, null, null), param);
                output.flush();
            }
            writer.dispose();
            
            return file.exists() && file.length() > 0;
            
        } catch (IOException e) {
            System.err.println("⚠️ JPEG保存失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 清理旧帧（环形缓冲）
     */
    private void cleanupOldFrames() {
        long totalSize = calculateTotalSize();
        
        while (totalSize > MAX_DISK_SIZE && !frames.isEmpty()) {
            FrameMetadata oldest = frames.remove(0);
            File file = new File(oldest.filePath);
            
            if (file.exists()) {
                long fileSize = file.length();
                file.delete();
                totalSize -= fileSize;
                
                if (frameCounter.get() % 100 == 0) {
                    System.out.println("🗑️ 删除旧帧: " + oldest.frameNumber + " (释放 " + (fileSize/1024) + " KB)");
                }
            }
        }
    }
    
    /**
     * 计算总磁盘占用
     */
    private long calculateTotalSize() {
        long total = 0;
        synchronized (frames) {
            for (FrameMetadata metadata : frames) {
                File file = new File(metadata.filePath);
                if (file.exists()) {
                    total += file.length();
                }
            }
        }
        return total;
    }
    
    /**
     * 自动检测实时流帧率
     */
    private void detectFrameRate() {
        synchronized (frames) {
            int size = frames.size();
            if (size < FPS_DETECTION_WINDOW) {
                return;
            }
            
            // 取最近60帧的时间跨度
            FrameMetadata first = frames.get(size - FPS_DETECTION_WINDOW);
            FrameMetadata last = frames.get(size - 1);
            
            long duration = last.timestamp - first.timestamp;
            
            if (duration > 0) {
                // 计算FPS
                double fps = (FPS_DETECTION_WINDOW - 1) * 1000.0 / duration;
                int roundedFps = (int) Math.round(fps);
                
                // 验证范围（25-70fps）
                if (roundedFps >= 25 && roundedFps <= 70) {
                    if (Math.abs(roundedFps - detectedFps) > 2) {
                        detectedFps = roundedFps;
                        System.out.println("📊 检测到实时流帧率: " + detectedFps + " fps");
                    }
                }
            }
        }
    }
    
    /**
     * 获取帧数
     */
    public int getFrameCount() {
        return frames.size();
    }
    
    /**
     * 获取磁盘占用
     */
    public long getDiskSize() {
        return calculateTotalSize();
    }
    
    /**
     * 获取检测到的帧率
     */
    public int getDetectedFps() {
        return detectedFps;
    }
    
    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * 获取所有帧元数据（只读副本）
     */
    public List<FrameMetadata> getFrames() {
        synchronized (frames) {
            return new ArrayList<>(frames);
        }
    }
    
    /**
     * 清理所有文件
     */
    public void cleanup() {
        stopRecording();
        
        synchronized (frames) {
            for (FrameMetadata metadata : frames) {
                new File(metadata.filePath).delete();
            }
            frames.clear();
        }
        
        try {
            Files.deleteIfExists(recordDir);
            System.out.println("🗑️ 已清理录制目录: " + recordDir);
        } catch (IOException e) {
            System.err.println("⚠️ 清理录制目录失败: " + e.getMessage());
        }
    }
}

