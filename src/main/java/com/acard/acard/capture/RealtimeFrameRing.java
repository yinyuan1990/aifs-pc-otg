package com.acard.acard.capture;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ⚡ 实时帧内存环（用于实时流抓拍）
 * 
 * 特点：
 * - 环形缓冲区，固定大小（默认60帧：前30+后30）
 * - 使用JPEG压缩存储，减少内存占用（约1/10）
 * - 线程安全
 * - 自动覆盖旧帧
 * 
 * 内存估算（1080p）：
 * - 原始帧：1920*1080*4 = 8MB/帧
 * - JPEG压缩后：约 80-150KB/帧
 * - 60帧总计：约 5-10MB（可接受）
 */
public class RealtimeFrameRing {
    
    // ⚡ 配置
    private static final int DEFAULT_CAPACITY = 60;  // 默认容量：前30+后30
    private static final float JPEG_QUALITY = 0.90f; // JPEG压缩质量（0.0-1.0）
    
    // ⚡ iOS推流端4档分辨率（前后置相同）
    // p4k:      1920x1440 60fps 4500kbps
    // ultra:    1280x960  60fps 3000kbps
    // high:     960x720   60fps 1500kbps
    // standard: 640x480   60fps 1200kbps
    // 不再强制缩放，保持原始分辨率
    
    // ⚡ 帧数据结构
    public static class FrameData {
        public final byte[] jpegData;      // JPEG压缩数据
        public final int width;            // 原始宽度
        public final int height;           // 原始高度
        public final long timestamp;       // 时间戳
        public final long frameIndex;      // 帧索引
        
        public FrameData(byte[] jpegData, int width, int height, long timestamp, long frameIndex) {
            this.jpegData = jpegData;
            this.width = width;
            this.height = height;
            this.timestamp = timestamp;
            this.frameIndex = frameIndex;
        }
        
        /**
         * 解码为JavaFX Image
         */
        public Image toImage() {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(jpegData);
                BufferedImage bi = ImageIO.read(bais);
                if (bi != null) {
                    return SwingFXUtils.toFXImage(bi, null);
                }
            } catch (Exception e) {
                System.err.println("⚠️ 帧解码失败: " + e.getMessage());
            }
            return null;
        }
        
        /**
         * 获取压缩后的字节大小
         */
        public int getByteSize() {
            return jpegData != null ? jpegData.length : 0;
        }
    }
    
    // ⚡ 环形缓冲区
    private final FrameData[] buffer;
    private final int capacity;
    private int head = 0;           // 写入位置
    private int size = 0;           // 当前帧数
    private long totalPushed = 0;   // 总推送帧数
    private final ReentrantLock lock = new ReentrantLock();
    
    // ⚡ 统计
    private long totalBytesUsed = 0;
    private long lastPushTime = 0;
    private int pushCount = 0;
    
    // ⚡ 单例
    private static volatile RealtimeFrameRing instance;
    
    public static RealtimeFrameRing getInstance() {
        if (instance == null) {
            synchronized (RealtimeFrameRing.class) {
                if (instance == null) {
                    instance = new RealtimeFrameRing(DEFAULT_CAPACITY);
                }
            }
        }
        return instance;
    }
    
    public RealtimeFrameRing(int capacity) {
        this.capacity = capacity;
        this.buffer = new FrameData[capacity];
        System.out.println("⚡ RealtimeFrameRing 初始化: 容量=" + capacity + "帧, JPEG质量=" + JPEG_QUALITY);
    }
    
    /**
     * ⚡ 推送帧到环形缓冲区
     * @param image JavaFX Image
     */
    public void push(Image image) {
        if (image == null) return;
        
        long now = System.currentTimeMillis();
        
        // 节流：限制最大推送频率（约60fps）
        if (now - lastPushTime < 16) {
            return;
        }
        lastPushTime = now;
        
        // 异步压缩和存储
        final Image imgCopy = image;
        final long frameIdx = totalPushed;
        
        // 在当前线程直接处理（避免线程切换开销）
        try {
            byte[] jpegData = compressToJpeg(imgCopy);
            if (jpegData != null && jpegData.length > 0) {
                FrameData frame = new FrameData(
                    jpegData,
                    (int) imgCopy.getWidth(),
                    (int) imgCopy.getHeight(),
                    now,
                    frameIdx
                );
                
                lock.lock();
                try {
                    // 计算旧帧占用的内存
                    if (buffer[head] != null) {
                        totalBytesUsed -= buffer[head].getByteSize();
                    }
                    
                    // 写入新帧
                    buffer[head] = frame;
                    totalBytesUsed += frame.getByteSize();
                    
                    // 移动写入位置
                    head = (head + 1) % capacity;
                    if (size < capacity) {
                        size++;
                    }
                    totalPushed++;
                    pushCount++;
                    
                    // 每100帧输出统计
                    if (pushCount % 100 == 0) {
                        System.out.println("📊 RealtimeFrameRing: 帧数=" + size + "/" + capacity + 
                            ", 内存=" + (totalBytesUsed / 1024) + "KB, 总推送=" + totalPushed);
                    }
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            System.err.println("⚠️ 帧推送失败: " + e.getMessage());
        }
    }
    
    /**
     * ⚡ 获取最近N帧的快照（从旧到新）
     * @param count 需要的帧数
     * @return 帧列表（可能少于请求数量）
     */
    public List<FrameData> getRecentFrames(int count) {
        lock.lock();
        try {
            List<FrameData> result = new ArrayList<>();
            int actualCount = Math.min(count, size);
            
            if (actualCount == 0) {
                return result;
            }
            
            // 计算起始位置（从最旧的开始）
            int start = (head - size + capacity) % capacity;
            int offset = Math.max(0, size - actualCount);
            start = (start + offset) % capacity;
            
            for (int i = 0; i < actualCount; i++) {
                int idx = (start + i) % capacity;
                if (buffer[idx] != null) {
                    result.add(buffer[idx]);
                }
            }
            
            return result;
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * ⚡ 获取指定范围的帧（基于当前帧的前后偏移）
     * @param preCount 当前帧之前的帧数
     * @param postCount 当前帧之后的帧数（通常为0，因为是实时）
     * @return 帧列表
     */
    public List<FrameData> getCaptureWindow(int preCount, int postCount) {
        // 实时流只有"前"帧，没有"后"帧
        // postCount 在实时模式下无效
        return getRecentFrames(preCount + 1);  // +1 包含当前帧
    }
    
    /**
     * ⚡ 压缩图像为JPEG（保持原始分辨率）
     * 
     * iOS端4档分辨率：
     * - p4k:      1920x1440 → 约 200-300KB/帧
     * - ultra:    1280x960  → 约 100-150KB/帧
     * - high:     960x720   → 约 60-100KB/帧
     * - standard: 640x480   → 约 30-50KB/帧
     */
    private byte[] compressToJpeg(Image image) {
        try {
            BufferedImage bi = SwingFXUtils.fromFXImage(image, null);
            if (bi == null) return null;
            
            // ⚡ 不缩放，保持原始分辨率（匹配iOS推流端）
            // 记录当前分辨率用于调试
            int w = bi.getWidth();
            int h = bi.getHeight();
            
            // 转换为RGB格式（JPEG不支持alpha）
            BufferedImage rgbImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            rgbImage.createGraphics().drawImage(bi, 0, 0, null);
            
            // JPEG压缩
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (writers.hasNext()) {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                
                MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(rgbImage, null, null), param);
                writer.dispose();
                ios.close();
            } else {
                // 回退到默认压缩
                ImageIO.write(rgbImage, "jpeg", baos);
            }
            
            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("⚠️ JPEG压缩失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * ⚡ 获取当前分辨率档位名称
     */
    public static String getResolutionTier(int width, int height) {
        // 根据iOS端配置判断档位
        if (width >= 1920 && height >= 1440) return "p4k (1920x1440)";
        if (width >= 1280 && height >= 960) return "ultra (1280x960)";
        if (width >= 960 && height >= 720) return "high (960x720)";
        if (width >= 640 && height >= 480) return "standard (640x480)";
        return "unknown (" + width + "x" + height + ")";
    }
    
    /**
     * ⚡ 清空缓冲区
     */
    public void clear() {
        lock.lock();
        try {
            for (int i = 0; i < capacity; i++) {
                buffer[i] = null;
            }
            head = 0;
            size = 0;
            totalBytesUsed = 0;
            System.out.println("🗑️ RealtimeFrameRing 已清空");
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * ⚡ 获取当前状态
     */
    public String getStatus() {
        lock.lock();
        try {
            return String.format("帧数=%d/%d, 内存=%.2fMB, 总推送=%d", 
                size, capacity, totalBytesUsed / 1024.0 / 1024.0, totalPushed);
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * ⚡ 获取当前帧数
     */
    public int getFrameCount() {
        return size;
    }
    
    /**
     * ⚡ 获取总推送帧数
     */
    public long getTotalPushed() {
        return totalPushed;
    }
    
    /**
     * ⚡ 获取内存使用量（字节）
     */
    public long getMemoryUsage() {
        return totalBytesUsed;
    }
}

