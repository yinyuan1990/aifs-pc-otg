package com.acard.acard;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 磁盘环形缓冲（无损PNG）：固定容量保存最近 N 帧，超出则删除最旧文件。
 */
public class DiskFrameRingBuffer {

    public static class FrameItem {
        public final Path path;
        public final long timestamp;
        public FrameItem(Path path, long timestamp) {
            this.path = path;
            this.timestamp = timestamp;
        }
    }

    private final int capacity;
    private final Path dir;
    private final ArrayDeque<FrameItem> deque;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger seq = new AtomicInteger(0);

    public DiskFrameRingBuffer(int capacity, Path dir) {
        this.capacity = Math.max(1, capacity);
        this.dir = dir;
        this.deque = new ArrayDeque<>(capacity);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("创建慢放缓冲目录失败: " + dir + ", " + e.getMessage(), e);
        }
    }

    /** 清空缓冲并删除磁盘文件 */
    public void clear() {
        lock.lock();
        try {
            for (FrameItem item : deque) {
                try { Files.deleteIfExists(item.path); } catch (IOException ignore) {}
            }
            deque.clear();
        } finally {
            lock.unlock();
        }
    }

    /** 推入一帧：无损PNG，1:1 原始分辨率；超过容量则删除最旧文件。 */
    public void push(BufferedImage src) {
        int id = seq.incrementAndGet();
        Path file;
        FrameItem item;
        try {
            String pngName = String.format("frame_%06d.png", id);
            file = dir.resolve(pngName);
            // 保持 1:1 原始分辨率与像素数据，无缩放、无损压缩
            ImageIO.write(src, "PNG", file.toFile());
            item = new FrameItem(file, System.currentTimeMillis());
        } catch (IOException ex) {
            // 写盘失败直接返回，不影响实时播放
            return;
        }
        lock.lock();
        try {
            if (deque.size() >= capacity) {
                FrameItem old = deque.pollFirst();
                if (old != null) {
                    try { Files.deleteIfExists(old.path); } catch (IOException ignore) {}
                }
            }
            deque.addLast(item);
        } finally {
            lock.unlock();
        }
    }

    /** 将图像缩放到不超过 maxW x maxH，保持比例。 */
    private static BufferedImage scaleToMax(BufferedImage src, int maxW, int maxH) {
        try {
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min((double) maxW / Math.max(1, w), (double) maxH / Math.max(1, h));
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
        } catch (Throwable ignore) {
            return src;
        }
    }

    /** 获取当前缓冲快照（时间顺序） */
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

    /** 外部推入已存在的文件路径，维持容量并删除最旧项 */
    public void pushPath(Path file) {
        if (file == null) return;
        FrameItem item = new FrameItem(file, System.currentTimeMillis());
        lock.lock();
        try {
            if (deque.size() >= capacity) {
                FrameItem old = deque.pollFirst();
                if (old != null) {
                    try { Files.deleteIfExists(old.path); } catch (IOException ignore) {}
                }
            }
            deque.addLast(item);
        } finally {
            lock.unlock();
        }
    }
}