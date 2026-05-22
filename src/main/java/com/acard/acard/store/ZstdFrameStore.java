package com.acard.acard.store;

import com.github.luben.zstd.Zstd;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ZstdFrameStore：基于 Zstd 压缩的文件环形存储，JPMS 友好且无需访问 JDK 内部。
 * - 每帧存储为一个二进制文件：Header(宽/高/时间戳/数据长度) + 压缩像素数据（ARGB32）
 * - 使用固定容量环，超过容量自动删除最旧文件
 */
public final class ZstdFrameStore implements FrameStore, AutoCloseable {

    private final Path dir;
    private final int capacity;
    private final ArrayDeque<Path> ring;
    private volatile int seq = 0;

    public ZstdFrameStore(Path dir, int capacity) {
        this.dir = dir;
        this.capacity = Math.max(1, capacity);
        this.ring = new ArrayDeque<>(this.capacity);
        try { Files.createDirectories(dir); } catch (IOException ignore) {}
    }

    public ZstdFrameStore(String dir, int capacity) { this(Path.of(dir), capacity); }

    @Override
    public void appendFrame(BufferedImage src, long timestamp) {
        if (src == null) return;
        BufferedImage argb = ensureARGB(src);
        int w = argb.getWidth();
        int h = argb.getHeight();
        int[] pix = ((DataBufferInt) argb.getRaster().getDataBuffer()).getData();
        byte[] raw = intsToBytesLE(pix);
        // 压缩等级在 3~7 之间权衡速度与体积
        byte[] zstd = Zstd.compress(raw, 5);

        String name = String.format("frame_%d_%06d.zstbin", timestamp, seq++);
        Path file = dir.resolve(name);
        ByteBuffer header = ByteBuffer.allocate(4 + 4 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(w).putInt(h).putLong(timestamp).putInt(zstd.length).flip();
        try {
            Files.write(file, header.array(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            Files.write(file, zstd, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
            return; // 单帧写盘失败直接返回
        }
        synchronized (ring) {
            ring.addLast(file);
            while (ring.size() > capacity) {
                Path old = ring.pollFirst();
                if (old != null) { try { Files.deleteIfExists(old); } catch (IOException ignore) {} }
            }
        }
    }

    @Override
    public List<BufferedImage> getLastNImages(int n) {
        if (n <= 0) return Collections.emptyList();
        List<Path> paths;
        synchronized (ring) { paths = new ArrayList<>(ring); }
        if (paths.isEmpty()) return Collections.emptyList();
        int take = Math.min(n, paths.size());
        List<Path> tail = paths.subList(Math.max(0, paths.size() - take), paths.size());
        ArrayList<BufferedImage> out = new ArrayList<>(tail.size());
        for (Path p : tail) {
            try {
                byte[] all = Files.readAllBytes(p);
                if (all.length < (4 + 4 + 8 + 4)) continue;
                ByteBuffer hdr = ByteBuffer.wrap(all, 0, 4 + 4 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
                int w = hdr.getInt();
                int h = hdr.getInt();
                long ts = hdr.getLong();
                int len = hdr.getInt();
                if (len <= 0 || all.length < (4 + 4 + 8 + 4 + len)) continue;
                byte[] zstd = new byte[len];
                System.arraycopy(all, 4 + 4 + 8 + 4, zstd, 0, len);
                byte[] raw = Zstd.decompress(zstd, w * h * 4);
                int[] pix = bytesToIntsLE(raw);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                int[] dest = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                System.arraycopy(pix, 0, dest, 0, dest.length);
                out.add(img);
            } catch (Throwable ignore) {
                // 单帧读取失败跳过
            }
        }
        return out;
    }

    @Override
    public void close() {
        // 无持久占用资源，需要时可清理目录
    }

    private static BufferedImage ensureARGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        try { g.drawImage(src, 0, 0, null); } finally { g.dispose(); }
        return dst;
    }

    private static byte[] intsToBytesLE(int[] data) {
        ByteBuffer bb = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) bb.putInt(v);
        return bb.array();
    }

    private static int[] bytesToIntsLE(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
        return out;
    }
}