package com.acard.acard;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 固定容量环形缓冲，保存最近 N 帧的原始图像（BufferedImage）。
 * - 推入时按最大尺寸缩放（maxW/maxH），降低内存占用
 * - 不做任何编码；按需由调用方进行编码
 */
public class RawFrameRingBuffer {

    public static class FrameItem {
        public final BufferedImage image;
        public final long timestamp;
        public FrameItem(BufferedImage image, long timestamp) {
            this.image = image;
            this.timestamp = timestamp;
        }
    }

    private final int capacity;
    private final int maxW;
    private final int maxH;
    private final ArrayDeque<FrameItem> deque;
    private final ReentrantLock lock = new ReentrantLock();

    public RawFrameRingBuffer(int capacity, int maxW, int maxH) {
        this.capacity = Math.max(1, capacity);
        this.maxW = Math.max(1, maxW);
        this.maxH = Math.max(1, maxH);
        this.deque = new ArrayDeque<>(capacity);
    }

    /** 清空缓冲 */
    public void clear() {
        lock.lock();
        try {
            deque.clear();
        } finally {
            lock.unlock();
        }
    }

    /** 推入一帧：按最大尺寸缩放后存储原始 BufferedImage；超过容量则丢弃最旧帧。 */
    public void push(BufferedImage src) {
        if (src == null) return;
        BufferedImage scaled = scaleIfNeeded(src, maxW, maxH);
        FrameItem item = new FrameItem(scaled, System.currentTimeMillis());
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

    /** 获取当前缓冲的快照（时间顺序）。 */
    public List<FrameItem> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(deque);
        } finally {
            lock.unlock();
        }
    }

    /** 当前缓冲帧数 */
    public int size() {
        lock.lock();
        try {
            return deque.size();
        } finally {
            lock.unlock();
        }
    }

    /** 若超出最大尺寸则按等比缩放，否则返回深拷贝，避免外部修改影响缓存。 */
    private static BufferedImage scaleIfNeeded(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        double sx = w > maxW ? (maxW / (double) w) : 1.0;
        double sy = h > maxH ? (maxH / (double) h) : 1.0;
        double s = Math.min(sx, sy);
        int tw = (int) Math.round(w * s);
        int th = (int) Math.round(h * s);
        if (tw <= 0 || th <= 0) {
            tw = Math.max(1, Math.min(w, maxW));
            th = Math.max(1, Math.min(h, maxH));
        }
        BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return dst;
    }
}