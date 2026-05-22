package com.acard.acard.capture;

import com.acard.acard.DiskFrameRingBuffer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 将当前抓拍窗口的磁盘帧复制到独立目录，避免环形缓冲删除导致“前面的帧少”。
 * 复制后返回新的 FrameItem 列表，路径指向冻结目录；时间戳保持原值。
 */
public class SnapshotDiskFreezer {

    /**
     * 将帧复制到目标目录（如 runtime/captures/<timestamp>/），若存在同名文件则覆盖。
     * 返回新的列表，路径指向复制后的文件。
     */
    public static List<DiskFrameRingBuffer.FrameItem> freezeTo(Path targetDir, List<DiskFrameRingBuffer.FrameItem> frames) {
        if (frames == null || frames.isEmpty()) return List.of();
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            return frames; // 目录创建失败则直接返回原列表（降级）
        }
        List<DiskFrameRingBuffer.FrameItem> out = new ArrayList<>(frames.size());
        int idx = 0;
        for (DiskFrameRingBuffer.FrameItem it : frames) {
            if (it == null || it.path == null) continue;
            String name = String.format("%06d_%d.png", idx++, it.timestamp);
            Path dst = targetDir.resolve(name);
            try {
                Files.copy(it.path, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                out.add(new DiskFrameRingBuffer.FrameItem(dst, it.timestamp));
            } catch (IOException ignore) {
                // 复制失败：跳过该帧
            }
        }
        return out.isEmpty() ? frames : out;
    }
}