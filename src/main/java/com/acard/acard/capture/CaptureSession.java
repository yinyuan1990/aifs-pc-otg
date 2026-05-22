package com.acard.acard.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 抓拍会话管理器 - 每次抓拍创建独立文件夹
 * 
 * 功能：
 * 1. 每次抓拍创建独立文件夹（例如：capture_20250120_143022/）
 * 2. 从实时流缓存复制图片到独立文件夹
 * 3. 管理抓拍会话的生命周期
 * 
 * 文件结构：
 * runtime/captures/
 *   ├── capture_20250120_143022/   # 第1次抓拍
 *   │   ├── frame_001.jpeg
 *   │   ├── frame_002.jpeg
 *   │   └── ...
 *   ├── capture_20250120_143135/   # 第2次抓拍
 *   │   ├── frame_001.jpeg
 *   │   └── ...
 *   └── ...
 */
public class CaptureSession {
    
    private static final Path BASE_DIR = Paths.get("runtime/captures/zp");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    
    // 📊 全局计数器（用于生成编号）
    private static final java.util.concurrent.atomic.AtomicInteger realtimeCounter = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final java.util.concurrent.atomic.AtomicInteger slowmoCounter = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    
    private final String sessionId;
    private final Path sessionDir;
    private final List<DiskCaptureCache.DiskFrameItem> frames = new ArrayList<>();
    private final long createTime;
    private long eventFrameId = -1;  // 事件帧ID（用于后续帧事件）
    
    /**
     * 创建新的抓拍会话
     * 
     * @param typeDisplayName 类型显示名称（实时流抓拍/慢放抓拍）
     */
    public CaptureSession(String typeDisplayName) throws IOException {
        this.createTime = System.currentTimeMillis();
        
        // 根据类型选择计数器和前缀
        long number;
        String prefix;
        if (typeDisplayName.contains("慢放")) {
            number = System.currentTimeMillis();
            prefix = "慢放";
        } else {
            number = System.currentTimeMillis();
            prefix = "时时流";
        }
        
        // 格式：时时流-001, 慢放-001
        this.sessionId = String.format("%s-%s", prefix, number+"");
        this.sessionDir = BASE_DIR.resolve(sessionId);
        
        // 创建会话目录
        Files.createDirectories(sessionDir);
        
        System.out.println("📁 创建抓拍会话: " + sessionId);
        System.out.println("   类型: " + typeDisplayName);
        System.out.println("   路径: " + sessionDir.toAbsolutePath());
    }
    
    /**
     * 创建新的抓拍会话（默认类型）
     */
    public CaptureSession() throws IOException {
        this("capture");
    }


    public List<DiskCaptureCache.DiskFrameItem> copyFramesFromCache(
            List<DiskCaptureCache.DiskFrameItem> sourceFrames, int eventIndex) throws IOException {

        if (sourceFrames == null || sourceFrames.isEmpty()) {
            throw new IOException("源帧列表为空");
        }

        System.out.println("📋 复制帧到会话目录: " + sourceFrames.size() + "帧");

        int frameNumber = 1;
        for (int i = 0; i < sourceFrames.size(); i++) {
            DiskCaptureCache.DiskFrameItem sourceFrame = sourceFrames.get(i);

            // 检查源文件是否存在
            File sourceFile = new File(sourceFrame.filePath);
            if (!sourceFile.exists()) {
                System.err.println("⚠️ 源文件不存在，跳过: " + sourceFrame.filePath);
                continue;
            }

            // 生成新的文件名（frame_001.jpeg, frame_002.jpeg, ...）
            String newFileName = String.format("frame_%03d.jpeg", frameNumber++);
            Path targetPath = sessionDir.resolve(newFileName);

            try {
                // 复制文件到会话目录
                Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

                // 创建新的DiskFrameItem
                DiskCaptureCache.DiskFrameItem newFrame = new DiskCaptureCache.DiskFrameItem(
                        targetPath.toString(),
                        sourceFrame.timestamp,
                        sourceFrame.width,
                        sourceFrame.height,
                        sourceFrame.format,
                        sourceFrame.frameId
                );

                frames.add(newFrame);

            } catch (IOException e) {
                System.err.println("⚠️ 复制文件失败: " + sourceFile + " -> " + targetPath);
                throw e;
            }
        }

        System.out.println("✅ 复制完成: " + frames.size() + "帧");
        return new ArrayList<>(frames);
    }
    
    /**
     * 从实时流缓存复制帧到会话目录
     * 
     * @param sourceFrames 实时流缓存中的帧
     * @param eventIndex 事件帧索引（相对于sourceFrames）
     * @return 会话目录中的帧列表
     */
    public List<DiskCaptureCache.DiskFrameItem> copyFramesFromCache(
            List<DiskCaptureCache.DiskFrameItem> sourceFrames, int eventIndex,String name) throws IOException {

        if (sourceFrames == null || sourceFrames.isEmpty()) {
            throw new IOException("源帧列表为空");
        }
        
        System.out.println("📋 复制帧到会话目录: " + sourceFrames.size() + "帧");
        
        int frameNumber = 1;
        for (int i = 0; i < sourceFrames.size(); i++) {
            DiskCaptureCache.DiskFrameItem sourceFrame = sourceFrames.get(i);
            
            // 检查源文件是否存在
            File sourceFile = new File(sourceFrame.filePath);
            if (!sourceFile.exists()) {
                System.err.println("⚠️ 源文件不存在，跳过: " + sourceFrame.filePath);
                continue;
            }
            
            // 生成新的文件名（frame_001.jpeg, frame_002.jpeg, ...）
            String newFileName = String.format(name+"frame_%03d.jpeg", frameNumber++);
            Path targetPath = sessionDir.resolve(newFileName);
            
            try {
                // 复制文件到会话目录
                Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                
                // 创建新的DiskFrameItem
                DiskCaptureCache.DiskFrameItem newFrame = new DiskCaptureCache.DiskFrameItem(
                    targetPath.toString(),
                    sourceFrame.timestamp,
                    sourceFrame.width,
                    sourceFrame.height,
                    sourceFrame.format,
                    sourceFrame.frameId
                );

                frames.add(newFrame);
                
            } catch (IOException e) {
                System.err.println("⚠️ 复制文件失败: " + sourceFile + " -> " + targetPath);
                throw e;
            }
        }
        
        System.out.println("✅ 复制完成: " + frames.size() + "帧");
        return new ArrayList<>(frames);
    }




    public List<DiskCaptureCache.DiskFrameItem> copyFramesFromCache2(
            List<DiskCaptureCache.DiskFrameItem> sourceFrames, int eventIndex,String name) throws IOException {
        List<DiskCaptureCache.DiskFrameItem> newFrames = new ArrayList<>();  // ✅ 新增：专门收集本次复制的帧
        if (sourceFrames == null || sourceFrames.isEmpty()) {
            throw new IOException("源帧列表为空");
        }

        System.out.println("📋 复制帧到会话目录: " + sourceFrames.size() + "帧");

        int frameNumber = 1;
        for (int i = 0; i < sourceFrames.size(); i++) {
            DiskCaptureCache.DiskFrameItem sourceFrame = sourceFrames.get(i);

            // 检查源文件是否存在
            File sourceFile = new File(sourceFrame.filePath);
            if (!sourceFile.exists()) {
                System.err.println("⚠️ 源文件不存在，跳过: " + sourceFrame.filePath);
                continue;
            }

            // 生成新的文件名（frame_001.jpeg, frame_002.jpeg, ...）
            String newFileName = String.format(name+"frame_%03d.jpeg", frameNumber++);
            Path targetPath = sessionDir.resolve(newFileName);

            try {
                // 复制文件到会话目录
                Files.copy(sourceFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);

                // 创建新的DiskFrameItem
                DiskCaptureCache.DiskFrameItem newFrame = new DiskCaptureCache.DiskFrameItem(
                        targetPath.toString(),
                        sourceFrame.timestamp,
                        sourceFrame.width,
                        sourceFrame.height,
                        sourceFrame.format,
                        sourceFrame.frameId
                );
                newFrames.add(newFrame);   // ✅ 添加到本次复制列表


            } catch (IOException e) {
                System.err.println("⚠️ 复制文件失败: " + sourceFile + " -> " + targetPath);
                throw e;
            }
        }

        System.out.println("✅ 复制完成: " + frames.size() + "帧");
        return newFrames;
    }
    
    /**
     * 添加后续帧到会话目录
     * 
     * @param jpegBytes JPEG数据
     * @param timestamp 时间戳
     * @return 新帧
     */
    public DiskCaptureCache.DiskFrameItem addPostFrame(byte[] jpegBytes, long timestamp, 
                                                        int width, int height, long frameId,String name) throws IOException {
        if (jpegBytes == null || jpegBytes.length == 0) {
            throw new IOException("JPEG bytes为空");
        }
        
        // 生成新的文件名
        int frameNumber = frames.size() + 1;
        String newFileName = name+String.format("frame_%03d.jpeg", frameNumber);
        Path targetPath = sessionDir.resolve(newFileName);
        
        // 直接写入bytes
        Files.write(targetPath, jpegBytes);
        
        // 创建新的DiskFrameItem
        DiskCaptureCache.DiskFrameItem newFrame = new DiskCaptureCache.DiskFrameItem(
            targetPath.toString(),
            timestamp,
            width,
            height,
            "jpeg",
            frameId
        );
        
        frames.add(newFrame);
        
        System.out.println("💾 后续帧已添加: " + newFileName + " (" + (jpegBytes.length / 1024) + "KB)");
        
        return newFrame;
    }
    
    /**
     * 获取所有帧
     */
    public List<DiskCaptureCache.DiskFrameItem> getFrames() {
        return new ArrayList<>(frames);
    }
    
    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * 获取会话目录
     */
    public Path getSessionDir() {
        return sessionDir;
    }
    
    /**
     * 获取帧数
     */
    public int getFrameCount() {
        return frames.size();
    }
    
    /**
     * 清理会话（删除所有文件和目录）
     */
    public void cleanup() {
        try {
            if (Files.exists(sessionDir)) {
                // 删除所有文件
                Files.walk(sessionDir)
                    .sorted((a, b) -> b.compareTo(a))  // 先删除文件，再删除目录
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("⚠️ 删除失败: " + path);
                        }
                    });
                
                System.out.println("🗑️ 会话已清理: " + sessionId);
            }
        } catch (IOException e) {
            System.err.println("❌ 清理会话失败: " + sessionId + ", " + e.getMessage());
        }
    }
    
    /**
     * 设置事件帧ID
     */
    public void setEventFrameId(long frameId) {
        this.eventFrameId = frameId;
    }
    
    /**
     * 获取事件帧ID
     */
    public long getEventFrameId() {
        return eventFrameId;
    }
    
    /**
     * 获取会话信息
     */
    public String getInfo() {
        long duration = System.currentTimeMillis() - createTime;
        return String.format("CaptureSession[id=%s, frames=%d, eventFrameId=%d, duration=%dms, dir=%s]",
            sessionId, frames.size(), eventFrameId, duration, sessionDir);
    }
}

