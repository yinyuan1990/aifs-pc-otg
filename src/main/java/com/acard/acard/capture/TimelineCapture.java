package com.acard.acard.capture;

import javafx.scene.image.Image;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 时间轴抓拍管理器
 * 
 * 功能：
 * 1. 点击抓拍时，以当前帧为起点
 * 2. 从缓存获取前N帧
 * 3. 当前帧
 * 4. 通过事件收集后N帧
 * 5. 组合成完整的抓拍序列
 * 
 * 流程：
 * 1. 用户点击抓拍按钮
 * 2. 立即从缓存获取前N帧（已经在缓冲区了）
 * 3. 获取当前帧
 * 4. 设置标志，开始收集后N帧
 * 5. 每次新帧到达时，如果标志开启，收集帧
 * 6. 收集完成后，调用回调函数
 */
public class TimelineCapture {
    
    /**
     * 抓拍会话（使用压缩存储，避免内存暴涨）
     */
    public static class CaptureSession {
        public final int preCount;                                    // 前抓拍数
        public final int postCount;                                   // 后抓拍数
        public final List<LightweightFrameBuffer.FrameItem> preFrames; // 前N帧（压缩存储）
        public volatile LightweightFrameBuffer.FrameItem currentFrame; // 当前帧（压缩存储）
        public final List<LightweightFrameBuffer.FrameItem> postFrames;// 后N帧（压缩存储）
        public final long startTime;                                  // 开始时间
        public final float jpegQuality;                               // JPEG压缩质量
        
        private final AtomicInteger collectedPost = new AtomicInteger(0);
        private volatile boolean completed = false;
        private volatile boolean needCurrentFrame; // 是否需要从流中获取当前帧
        private Consumer<CaptureSession> onComplete;
        
        public CaptureSession(int preCount, int postCount, 
                              List<LightweightFrameBuffer.FrameItem> preFrames, 
                              LightweightFrameBuffer.FrameItem currentFrame,
                              float jpegQuality,
                              Consumer<CaptureSession> onComplete) {
            this.preCount = preCount;
            this.postCount = postCount;
            this.preFrames = preFrames != null ? preFrames : new ArrayList<>();
            this.currentFrame = currentFrame;
            this.needCurrentFrame = (currentFrame == null); // 如果当前帧为null，需要从流中获取
            this.postFrames = new ArrayList<>(postCount);
            this.startTime = System.currentTimeMillis();
            this.jpegQuality = jpegQuality;
            this.onComplete = onComplete;
            
            if (needCurrentFrame) {
                System.out.println("⏳ 等待从实时流获取当前帧...");
            }
        }
        
        /**
         * 收集后续帧（立即压缩，避免内存暴涨）
         * 
         * @param frame 新帧
         * @return true if 收集完成
         */
        public synchronized boolean collectPostFrame(Image frame) {
            if (completed || frame == null) return false;
            
            // ✨ 如果还需要当前帧，先将这一帧作为当前帧
            if (needCurrentFrame) {
                // 立即压缩为JPEG（避免内存暴涨）
                this.currentFrame = compressImageToFrameItem(frame, jpegQuality);
                needCurrentFrame = false;
                System.out.println("✅ 已从实时流获取当前帧（已压缩）");
                
                // 如果不需要后续帧，直接完成
                if (postCount == 0) {
                    complete();
                    return true;
                }
                return false; // 继续收集后续帧
            }
            
            // 收集后续帧（立即压缩）
            if (collectedPost.get() < postCount) {
                LightweightFrameBuffer.FrameItem compressedFrame = compressImageToFrameItem(frame, jpegQuality);
                if (compressedFrame != null) {
                    postFrames.add(compressedFrame);
                    int current = collectedPost.incrementAndGet();
                    
                    System.out.println("📥 收集后续帧: " + current + "/" + postCount + " (已压缩)");
                    
                    if (current >= postCount) {
                        complete();
                        return true;
                    }
                }
            }
            
            return false;
        }
        
        /**
         * 收集后续帧（FrameItem，已压缩）- 避免Image转换问题
         * 
         * @param frameItem 已压缩的帧项
         * @return true if 收集完成
         */
        public synchronized boolean collectPostFrameItem(LightweightFrameBuffer.FrameItem frameItem) {
            if (completed || frameItem == null) return false;
            
            // ✨ 如果还需要当前帧，先将这一帧作为当前帧
            if (needCurrentFrame) {
                this.currentFrame = frameItem;
                needCurrentFrame = false;
                System.out.println("✅ 已从实时流获取当前帧（FrameItem，已压缩）");
                
                // 如果不需要后续帧，直接完成
                if (postCount == 0) {
                    complete();
                    return true;
                }
                return false; // 继续收集后续帧
            }
            
            // 收集后续帧
            if (collectedPost.get() < postCount) {
                postFrames.add(frameItem);
                int current = collectedPost.incrementAndGet();
                
                System.out.println("📥 收集后续帧: " + current + "/" + postCount + " (FrameItem)");
                
                if (current >= postCount) {
                    complete();
                    return true;
                }
            }
            
            return false;
        }
        
        /**
         * 收集后续帧（磁盘帧，零内存占用）
         * 
         * @param diskItem 磁盘帧项
         * @return true if 收集完成
         */
        public synchronized boolean collectPostDiskFrame(DiskCaptureCache.DiskFrameItem diskItem) {
            if (completed || diskItem == null) return false;
            
            // ✨ 如果还需要当前帧，先将这一帧作为当前帧
            if (needCurrentFrame) {
                // 将磁盘帧转为FrameItem（读取文件内容）
                try {
                    byte[] fileData = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(diskItem.filePath));
                    this.currentFrame = new LightweightFrameBuffer.FrameItem(
                        fileData, diskItem.timestamp, diskItem.width, diskItem.height, diskItem.frameId
                    );
                    needCurrentFrame = false;
                    System.out.println("✅ 已从实时流获取当前帧（磁盘→FrameItem）");
                    
                    // 如果不需要后续帧，直接完成
                    if (postCount == 0) {
                        complete();
                        return true;
                    }
                    return false; // 继续收集后续帧
                } catch (Throwable e) {
                    System.err.println("⚠️ 读取磁盘帧失败: " + e.getMessage());
                    return false;
                }
            }
            
            // 收集后续帧（磁盘帧→FrameItem）
            if (collectedPost.get() < postCount) {
                try {
                    byte[] fileData = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(diskItem.filePath));
                    LightweightFrameBuffer.FrameItem frameItem = new LightweightFrameBuffer.FrameItem(
                        fileData, diskItem.timestamp, diskItem.width, diskItem.height, diskItem.frameId
                    );
                    postFrames.add(frameItem);
                    int current = collectedPost.incrementAndGet();
                    
                    System.out.println("📥 收集后续帧: " + current + "/" + postCount + " (磁盘→FrameItem)");
                    
                    if (current >= postCount) {
                        complete();
                        return true;
                    }
                } catch (Throwable e) {
                    System.err.println("⚠️ 读取磁盘帧失败: " + e.getMessage());
                }
            }
            
            return false;
        }
        
        /**
         * 压缩Image为FrameItem（高质量JPEG）
         */
        private LightweightFrameBuffer.FrameItem compressImageToFrameItem(Image image, float quality) {
            try {
                BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
                if (buffered == null) return null;
                
                // 高质量JPEG压缩
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                
                writer.setOutput(new MemoryCacheImageOutputStream(baos));
                writer.write(null, new IIOImage(buffered, null, null), param);
                writer.dispose();
                
                byte[] jpegData = baos.toByteArray();
                return new LightweightFrameBuffer.FrameItem(
                    jpegData,
                    System.currentTimeMillis(),
                    buffered.getWidth(),
                    buffered.getHeight(),
                    0  // frameId未知，使用0（这是旧的内存缓存路径）
                );
            } catch (IOException e) {
                System.err.println("⚠️ 压缩帧失败: " + e.getMessage());
                return null;
            }
        }
        
        /**
         * 完成收集
         */
        private void complete() {
            if (completed) return;
            completed = true;
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("✅ 抓拍完成: 前" + preFrames.size() 
                + " + 当前1 + 后" + postFrames.size() 
                + " = " + getTotalFrameCount() + "帧, 耗时" + duration + "ms");
            
            if (onComplete != null) {
                try {
                    onComplete.accept(this);
                } catch (Throwable e) {
                    System.err.println("⚠️ 抓拍回调异常: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        /**
         * 获取所有帧（前 + 当前 + 后）
         * 解压缩FrameItem为Image（仅在显示时解压）
         */
        public List<Image> getAllFrames() {
            List<Image> all = new ArrayList<>(preFrames.size() + 1 + postFrames.size());
            
            // 解压前置帧
            for (LightweightFrameBuffer.FrameItem item : preFrames) {
                Image img = item.toImage();
                if (img != null) all.add(img);
            }
            
            // 解压当前帧
            if (currentFrame != null) {
                Image img = currentFrame.toImage();
                if (img != null) all.add(img);
            }
            
            // 解压后续帧
            for (LightweightFrameBuffer.FrameItem item : postFrames) {
                Image img = item.toImage();
                if (img != null) all.add(img);
            }
            
            return all;
        }
        
        /**
         * 获取总帧数
         */
        public int getTotalFrameCount() {
            return preFrames.size() + (currentFrame != null ? 1 : 0) + postFrames.size();
        }
        
        /**
         * 是否完成
         */
        public boolean isCompleted() {
            return completed;
        }
        
        /**
         * 强制完成（超时或取消）
         */
        public void forceComplete() {
            if (!completed) {
                System.out.println("⚠️ 强制完成抓拍: 已收集 " + collectedPost.get() + "/" + postCount + " 后续帧");
                complete();
            }
        }
    }
    
    private final LightweightFrameBuffer frameBuffer;
    private final DiskCaptureCache diskCache;
    private volatile CaptureSession currentSession = null;
    private final Object sessionLock = new Object();
    
    /**
     * 构造函数
     * 
     * @param frameBuffer 帧缓冲区（内存模式）
     * @param diskCache 磁盘缓存（零内存模式）
     */
    public TimelineCapture(LightweightFrameBuffer frameBuffer, DiskCaptureCache diskCache) {
        this.frameBuffer = frameBuffer;
        this.diskCache = diskCache;
    }
    
    /**
     * 开始抓拍（所有帧使用压缩存储，避免内存暴涨）
     * 
     * @param preCount 前抓拍数
     * @param postCount 后抓拍数
     * @param currentFrame 当前帧（null=从实时流获取）
     * @param jpegQuality JPEG压缩质量（0.0-1.0）
     * @param onComplete 完成回调
     * @return CaptureSession
     */
    public CaptureSession startCapture(int preCount, int postCount, 
                                        Image currentFrame,
                                        float jpegQuality,
                                        Consumer<CaptureSession> onComplete) {
        synchronized (sessionLock) {
            // 如果有正在进行的会话，强制完成
            if (currentSession != null && !currentSession.isCompleted()) {
                System.out.println("⚠️ 发现未完成的抓拍会话，强制完成");
                currentSession.forceComplete();
            }
            
            // 1. 从缓存获取前N帧（已压缩，直接使用）
            List<LightweightFrameBuffer.FrameItem> preFrames = new ArrayList<>();
            if (frameBuffer != null && frameBuffer.isEnabled()) {
                preFrames = frameBuffer.getRecentFrames(preCount);
                System.out.println("📦 从缓存获取前 " + preFrames.size() + "/" + preCount + " 帧（已压缩）");
            } else {
                System.out.println("⚠️ 帧缓冲未启用，无法获取前置帧");
            }
            
            // 2. 压缩当前帧（如果有）
            LightweightFrameBuffer.FrameItem compressedCurrent = null;
            if (currentFrame != null) {
                compressedCurrent = compressImageToFrameItem(currentFrame, jpegQuality);
            }
            
            // 3. 创建新会话
            currentSession = new CaptureSession(preCount, postCount, preFrames, compressedCurrent, jpegQuality, onComplete);
            
            // 3. 如果不需要后续帧 且 当前帧已存在，立即完成
            if (postCount <= 0 && currentFrame != null) {
                System.out.println("💡 无需收集后续帧，立即完成");
                currentSession.forceComplete();
            }
            
            int expectedFrames = preCount + 1 + postCount;
            System.out.println("🎬 开始抓拍: 前" + preCount + " + 当前1 + 后" + postCount 
                + " = " + expectedFrames + "帧 (当前帧来源: " + (currentFrame != null ? "缓存" : "实时流") + ")");
            
            return currentSession;
        }
    }
    
    /**
     * 推送新帧（供GStreamer回调使用）
     * 
     * @param frame 新帧
     */
    public void onNewFrame(Image frame) {
        // 1. 推送到缓冲区（用于下次抓拍的前置帧）
        if (frameBuffer != null && frameBuffer.isEnabled()) {
            frameBuffer.push(frame, 0);  // frameId未知，使用0（这是旧的回调路径，已不再使用）
        }
        
        // 2. 如果有活动会话，收集后续帧
        CaptureSession session = currentSession;
        if (session != null && !session.isCompleted()) {
            session.collectPostFrame(frame);
        }
    }
    
    /**
     * 推送新帧（FrameItem，已压缩）- 避免Image对象格式转换问题
     * 
     * @param frameItem 已压缩的帧项
     */
    public void onNewFrameItem(LightweightFrameBuffer.FrameItem frameItem) {
        if (frameItem == null) return;
        
        // 1. 推送到缓冲区（用于下次抓拍的前置帧）
        if (frameBuffer != null && frameBuffer.isEnabled()) {
            frameBuffer.pushFrameItem(frameItem);
        }
        
        // 2. 如果有活动会话，收集后续帧
        CaptureSession session = currentSession;
        if (session != null && !session.isCompleted()) {
            session.collectPostFrameItem(frameItem);
        }
    }
    
    /**
     * 推送新帧（磁盘帧，零内存占用）
     * 
     * @param diskItem 磁盘帧项
     */
    public void onNewDiskFrame(DiskCaptureCache.DiskFrameItem diskItem) {
        if (diskItem == null) return;
        
        // 直接推送到磁盘缓存（不占内存）
        // 不需要推送到frameBuffer，frameBuffer仅用于前置帧
        
        // 如果有活动会话，收集后续帧
        CaptureSession session = currentSession;
        if (session != null && !session.isCompleted()) {
            session.collectPostDiskFrame(diskItem);
        }
    }
    
    /**
     * 取消当前抓拍
     */
    public void cancelCapture() {
        synchronized (sessionLock) {
            if (currentSession != null && !currentSession.isCompleted()) {
                System.out.println("❌ 取消抓拍");
                currentSession.forceComplete();
                currentSession = null;
            }
        }
    }
    
    /**
     * 是否有活动会话
     */
    public boolean hasActiveSession() {
        CaptureSession session = currentSession;
        return session != null && !session.isCompleted();
    }
    
    /**
     * 获取当前会话
     */
    public CaptureSession getCurrentSession() {
        return currentSession;
    }
    
    /**
     * 压缩Image为FrameItem（高质量JPEG）
     * 
     * @param image 原始图像
     * @param quality JPEG质量（0.0-1.0）
     * @return 压缩后的FrameItem
     */
    private LightweightFrameBuffer.FrameItem compressImageToFrameItem(Image image, float quality) {
        try {
            BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
            if (buffered == null) return null;
            
            // 高质量JPEG压缩
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            
            writer.setOutput(new MemoryCacheImageOutputStream(baos));
            writer.write(null, new IIOImage(buffered, null, null), param);
            writer.dispose();
            
            byte[] jpegData = baos.toByteArray();
            return new LightweightFrameBuffer.FrameItem(
                jpegData,
                System.currentTimeMillis(),
                buffered.getWidth(),
                buffered.getHeight(),
                0  // frameId未知，使用0（这是旧的内存缓存路径）
            );
        } catch (IOException e) {
            System.err.println("⚠️ 压缩帧失败: " + e.getMessage());
            return null;
        }
    }
}

