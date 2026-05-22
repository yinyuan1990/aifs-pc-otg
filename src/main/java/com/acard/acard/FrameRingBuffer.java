package com.acard.acard;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定容量环形缓冲，保存最近 N 帧的压缩图像数据（WebP）。
 * - 支持可选缩放到最大尺寸（maxW/maxH）以控制内存占用
 * - 支持设置压缩质量（0.1f ~ 1.0f）
 * - 若 WebP 编码器不可用或失败，回退到 JPEG（不回退到 PNG）
 */
public class FrameRingBuffer {

    public static class FrameItem {
        public final byte[] jpegBytes;
        public final long timestamp;
        public final long frameId;  // ✨ 全局帧ID（用于事件驱动分发）
        
        public FrameItem(byte[] jpegBytes, long timestamp, long frameId) {
            this.jpegBytes = jpegBytes;
            this.timestamp = timestamp;
            this.frameId = frameId;
        }
        
        // 兼容构造函数（用于没有frameId的旧代码）
        public FrameItem(byte[] jpegBytes, long timestamp) {
            this(jpegBytes, timestamp, 0);
        }
    }

    private final int capacity;
    private final int maxW;
    private final int maxH;
    private final float quality;
    private final ArrayDeque<FrameItem> deque;
    private final ReentrantLock lock = new ReentrantLock();

    public FrameRingBuffer(int capacity, int maxW, int maxH, float quality) {
        this.capacity = Math.max(1, capacity);
        this.maxW = Math.max(1, maxW);
        this.maxH = Math.max(1, maxH);
        this.quality = Math.min(1.0f, Math.max(0.1f, quality));
        this.deque = new ArrayDeque<>(capacity);
    }

    public void clear() {
        lock.lock();
        try {
            deque.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 推入一帧，自动缩放并按 WebP 压缩存储。超过容量则丢弃最旧帧。
     * 若 WebP 编码失败，则回退到 JPEG（避免 PNG 带来的 CPU/体积开销）。
     */
    public void push(BufferedImage src) {
        BufferedImage scaled = scaleIfNeeded(src);
        byte[] bytes = toWebpBytes(scaled, quality);
        if (bytes == null || bytes.length == 0) {
            bytes = toJpegBytes(scaled, Math.min(1.0f, Math.max(0.1f, quality)));
        }
        FrameItem item = new FrameItem(bytes, System.currentTimeMillis());
        lock.lock();
        try {
            if (deque.size() >= capacity) {
                deque.pollFirst();
            }
            deque.addLast(item);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取当前缓冲的快照（时间顺序）。
     */
    public List<FrameItem> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(deque);
        } finally {
            lock.unlock();
        }
    }

    /** 当前缓冲帧数（不复制数据） */
    public int size() {
        lock.lock();
        try {
            return deque.size();
        } finally {
            lock.unlock();
        }
    }

    private BufferedImage scaleIfNeeded(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min((double) maxW / w, (double) maxH / h);
        if (scale >= 1.0) return src;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static byte[] toJpegBytes(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, null), param);
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            // 保持无PNG回退策略：JPEG失败则返回空字节，由上层选择其他策略
            return new byte[0];
        }
    }

    /**
     * 将图像编码为 WebP（有损），质量范围 0.0~1.0。失败返回空字节数组。
     */
    private static byte[] toWebpBytes(BufferedImage img, float quality) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").hasNext()
                    ? ImageIO.getImageWritersByMIMEType("image/webp").next()
                    : ImageIO.getImageWritersByFormatName("webp").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.min(1.0f, Math.max(0.0f, quality)));
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}