package com.acard.acard.capture;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.embed.swing.SwingFXUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量级帧缓冲 - 优化内存使用
 * 
 * 优化策略：
 * 1. 动态缓冲大小（根据前抓拍数自动调整）
 * 2. JPEG压缩存储（减少内存90%+）
 * 3. 缩小分辨率（400x225，减少内存75%）
 * 4. 按需启用/禁用
 * 
 * 内存对比：
 * - 原始方案：120帧 × 800×450×4字节 = ~172MB
 * - 优化方案：20帧 × 400×225×4字节 × 10%(JPEG) = ~1.7MB
 * 
 * 内存减少：99%！
 */
public class LightweightFrameBuffer {
    
    // ✨ 新帧监听器（每帧实时触发）
    private volatile java.util.function.Consumer<FrameItem> frameListener;
    
    /**
     * 设置帧监听器（每帧到达时触发）
     */
    public void setFrameListener(java.util.function.Consumer<FrameItem> listener) {
        this.frameListener = listener;
        System.out.println("🎯 LightweightFrameBuffer.setFrameListener() 被调用(@" + System.identityHashCode(this) + "): listener=" + (listener != null ? "已设置" : "null"));
    }
    
    /**
     * 检查是否已设置帧监听器
     */
    public boolean hasFrameListener() {
        return frameListener != null;
    }
    
    /**
     * 帧项：存储压缩后的JPEG数据
     */
    public static class FrameItem {
        public final byte[] jpegData;      // JPEG压缩数据
        public final long timestamp;        // 时间戳（ms）
        public final int width;             // 原始宽度
        public final int height;            // 原始高度
        public final long frameId;          // ✨ 全局帧ID（用于事件驱动分发）
        
        public FrameItem(byte[] jpegData, long timestamp, int width, int height, long frameId) {
            this.jpegData = jpegData;
            this.timestamp = timestamp;
            this.width = width;
            this.height = height;
            this.frameId = frameId;
        }
        
        /**
         * 解压缩为JavaFX Image
         */
        public Image toImage() {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(jpegData);
                BufferedImage buffered = ImageIO.read(bais);
                if (buffered == null) return null;
                return SwingFXUtils.toFXImage(buffered, null);
            } catch (Throwable e) {
                System.err.println("⚠️ 解压缩帧失败: " + e.getMessage());
                return null;
            }
        }
    }
    
    private final ArrayDeque<FrameItem> ringBuffer;
    private int maxCapacity;
    private final boolean enableScaling;  // 是否启用缩放
    private final int scaleWidth;   // 缩放宽度（0=不缩放）
    private final int scaleHeight;  // 缩放高度（0=不缩放）
    private final float jpegQuality; // JPEG质量（0.0-1.0）
    
    private volatile boolean enabled = false;
    
    /**
     * 构造函数
     * 
     * @param initialCapacity 初始容量（支持1-240，推荐120）
     * @param scaleWidth 缩放宽度（0=保持原始分辨率，推荐0）
     * @param scaleHeight 缩放高度（0=保持原始分辨率，推荐0）
     * @param jpegQuality JPEG质量（0.0-1.0，推荐0.92-0.95）
     */
    public LightweightFrameBuffer(int initialCapacity, int scaleWidth, int scaleHeight, float jpegQuality) {
        this.maxCapacity = Math.max(10, Math.min(240, initialCapacity));  // 支持最大240张
        this.enableScaling = (scaleWidth > 0 && scaleHeight > 0);
        this.scaleWidth = scaleWidth;
        this.scaleHeight = scaleHeight;
        this.jpegQuality = Math.max(0.5f, Math.min(1.0f, jpegQuality));
        this.ringBuffer = new ArrayDeque<>(maxCapacity);
        
        String scaleInfo = enableScaling ? (scaleWidth + "×" + scaleHeight) : "原始分辨率";
        System.out.println("📦 LightweightFrameBuffer 已创建: capacity=" + maxCapacity 
            + ", scale=" + scaleInfo
            + ", quality=" + jpegQuality);
    }
    
    /**
     * 启用缓冲
     */
    public void enable() {
        enabled = true;
        System.out.println("✅ 帧缓冲已启用");
    }
    
    /**
     * 禁用缓冲并清空
     */
    public void disable() {
        enabled = false;
        clear();
        System.out.println("⛔ 帧缓冲已禁用");
    }
    
    /**
     * 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * 动态调整容量
     * 
     * @param newCapacity 新容量（支持1-240）
     */
    public synchronized void adjustCapacity(int newCapacity) {
        int adjustedCapacity = Math.max(10, Math.min(240, newCapacity));  // 最大240张
        if (adjustedCapacity == maxCapacity) return;
        
        System.out.println("🔧 调整缓冲容量: " + maxCapacity + " → " + adjustedCapacity);
        maxCapacity = adjustedCapacity;
        
        // 如果新容量小于当前缓冲大小，移除最旧的帧
        while (ringBuffer.size() > maxCapacity) {
            ringBuffer.pollFirst();
        }
    }
    
    // ✨ 用于调试的静态计数器
    private static final java.util.concurrent.atomic.AtomicInteger pushCounter = 
        new java.util.concurrent.atomic.AtomicInteger(0);
    
    /**
     * 推送新帧（原始分辨率或缩放）
     * 
     * @param image 原始图像
     * @param frameId 全局帧ID
     */
    public synchronized void push(Image image, long frameId) {
        if (!enabled || image == null) return;
        
        int count = pushCounter.incrementAndGet();
        if (count == 1 || count % 100 == 0) {
            System.out.println("🎬 LightweightFrameBuffer.push() 被调用(@" + System.identityHashCode(this) + "): frameId=" + frameId + " (第 " + count + " 帧), frameListener=" + (frameListener != null ? "已设置" : "null"));
        }
        
        try {
            // 1. 转换为BufferedImage
            BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
            if (buffered == null) return;
            
            // 2. 缩放（可选，enableScaling=false则保持原始分辨率）
            BufferedImage toCompress;
            if (enableScaling) {
                toCompress = scaleImage(buffered, scaleWidth, scaleHeight);
            } else {
                toCompress = buffered;  // 保持原始分辨率
            }
            
            // 3. JPEG压缩（高质量，减少内存）
            byte[] jpegData = compressToJPEG(toCompress, jpegQuality);
            
            // 4. 创建帧项（包含frameId）
            FrameItem item = new FrameItem(
                jpegData,
                System.currentTimeMillis(),
                toCompress.getWidth(),
                toCompress.getHeight(),
                frameId  // ✨ 全局帧ID
            );
            
            // 5. 添加到环形缓冲，超出容量则移除最旧帧
            if (ringBuffer.size() >= maxCapacity) {
                ringBuffer.pollFirst();
            }
            ringBuffer.addLast(item);
            
            // 6. ✨ 触发帧监听器（实时分发）
            if (frameListener != null) {
                try {
                    frameListener.accept(item);
                } catch (Throwable listenerEx) {
                    System.err.println("⚠️ 帧监听器异常: " + listenerEx.getMessage());
                }
            }
            
        } catch (Throwable e) {
            System.err.println("⚠️ 推送帧失败: " + e.getMessage());
        }
    }
    
    /**
     * 直接推送FrameItem（已压缩，避免Image转换问题）
     * 
     * @param item 已压缩的帧项
     */
    public synchronized void pushFrameItem(FrameItem item) {
        if (!enabled || item == null) return;
        
        try {
            // 添加到环形缓冲，超出容量则移除最旧帧
            if (ringBuffer.size() >= maxCapacity) {
                ringBuffer.pollFirst();
            }
            ringBuffer.addLast(item);
            
            // ✨ 触发帧监听器（实时分发）
            if (frameListener != null) {
                try {
                    frameListener.accept(item);
                } catch (Throwable listenerEx) {
                    System.err.println("⚠️ 帧监听器异常: " + listenerEx.getMessage());
                }
            }
            
        } catch (Throwable e) {
            System.err.println("⚠️ 推送FrameItem失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取最近N帧（用于前抓拍）
     * 
     * @param count 帧数
     * @return 帧列表（从旧到新）
     */
    public synchronized List<FrameItem> getRecentFrames(int count) {
        List<FrameItem> result = new ArrayList<>();
        if (count <= 0 || ringBuffer.isEmpty()) return result;
        
        // 从后往前取N帧
        int actualCount = Math.min(count, ringBuffer.size());
        FrameItem[] arr = ringBuffer.toArray(new FrameItem[0]);
        int startIndex = Math.max(0, arr.length - actualCount);
        
        for (int i = startIndex; i < arr.length; i++) {
            result.add(arr[i]);
        }
        
        System.out.println("📤 获取最近 " + actualCount + " 帧（请求 " + count + " 帧）");
        return result;
    }
    
    /**
     * 获取当前缓冲大小
     */
    public synchronized int size() {
        return ringBuffer.size();
    }
    
    /**
     * 清空缓冲
     */
    public synchronized void clear() {
        ringBuffer.clear();
        System.out.println("🗑️ 帧缓冲已清空");
    }
    
    /**
     * 获取内存使用估算（MB）
     */
    public synchronized double getMemoryUsageMB() {
        long totalBytes = 0;
        for (FrameItem item : ringBuffer) {
            totalBytes += item.jpegData.length;
        }
        return totalBytes / 1024.0 / 1024.0;
    }
    
    // ========== 私有辅助方法 ==========
    
    /**
     * 缩放图像
     */
    private BufferedImage scaleImage(BufferedImage src, int targetWidth, int targetHeight) {
        if (src.getWidth() == targetWidth && src.getHeight() == targetHeight) {
            return src;
        }
        
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = scaled.createGraphics();
        
        // 使用高质量缩放
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, 
                             java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, 
                             java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, 
                             java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        
        g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();
        
        return scaled;
    }
    
    /**
     * 压缩为JPEG
     */
    private byte[] compressToJPEG(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // 使用ImageIO的JPEG编码器
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        
        if (param.canWriteCompressed()) {
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        
        return baos.toByteArray();
    }
}

