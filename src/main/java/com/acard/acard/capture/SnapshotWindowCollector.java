package com.acard.acard.capture;

import com.acard.acard.DiskFrameRingBuffer;
import com.acard.acard.FrameRingBuffer;
import com.acard.acard.ui.SimpleWebRTCPlayerView;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 抓拍窗口采集器：统一处理两种数据来源
 * - 慢放采集中：使用磁盘环形缓冲（PNG）快照，事件点索引由慢放Pane提供
 * - 实时来源：使用内存环形缓冲（JPEG）快照，事件点为点击时刻附近（需外部提供）
 *
 * 说明：后续播放UI将独立封装；本类只负责窗口数据的收集与结果描述。
 */
public class SnapshotWindowCollector {

    /**
     * 结果对象：包含窗口帧列表与事件点在窗口内的相对索引。
     */
    public static class SnapshotWindowResult<T> {
        private final List<T> frames;
        private final int eventIndexInWindow;
        private final int startIndexInSource;
        private final int endIndexInSource;
        private final boolean timedOut;

        public SnapshotWindowResult(List<T> frames, int eventIndexInWindow, int startIndexInSource, int endIndexInSource, boolean timedOut) {
            this.frames = frames;
            this.eventIndexInWindow = eventIndexInWindow;
            this.startIndexInSource = startIndexInSource;
            this.endIndexInSource = endIndexInSource;
            this.timedOut = timedOut;
        }

        public List<T> getFrames() { return frames; }
        public int getEventIndexInWindow() { return eventIndexInWindow; }
        public int getStartIndexInSource() { return startIndexInSource; }
        public int getEndIndexInSource() { return endIndexInSource; }
        public boolean isTimedOut() { return timedOut; }
    }

    /**
     * 从慢放磁盘快照收集窗口：支持等待后向帧补齐。
     * @param player 播放器视图（提供磁盘快照）
     * @param eventIndex 事件点在源快照中的索引
     * @param preCount 事件点前帧数
     * @param postCount 事件点后帧数
     * @param waitTimeoutMs 不足后向帧时的最大等待时长（毫秒）
     */
    public static SnapshotWindowResult<DiskFrameRingBuffer.FrameItem> collectFromSlowMoDisk(
            SimpleWebRTCPlayerView player,
            int eventIndex,
            int preCount,
            int postCount,
            long waitTimeoutMs
    ) {
        if (player == null) {
            return new SnapshotWindowResult<>(Collections.emptyList(), 0, 0, 0, false);
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);
        List<DiskFrameRingBuffer.FrameItem> snapshot = safeDiskSnapshot(player);
        int needEnd = Math.max(0, eventIndex) + Math.max(0, postCount);
        while ((snapshot == null || snapshot.size() <= needEnd) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(80); } catch (InterruptedException ignore) {}
            snapshot = safeDiskSnapshot(player);
        }

        if (snapshot == null || snapshot.isEmpty()) {
            return new SnapshotWindowResult<>(Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int start = Math.max(0, Math.min(Math.max(0, eventIndex) - Math.max(0, preCount), size - 1));
        int end = Math.max(start, Math.min(Math.max(0, eventIndex) + Math.max(0, postCount), size - 1));
        List<DiskFrameRingBuffer.FrameItem> window = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            window.add(snapshot.get(i));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, Math.max(0, eventIndex) - start));
        boolean timedOut = (size <= needEnd) && (System.currentTimeMillis() >= deadline);
        return new SnapshotWindowResult<>(window, eventRel, start, end, timedOut);
    }

    /**
     * 基于时间戳锚定从慢放磁盘快照收集窗口：
     * - 以锚定时间戳定位事件点（选择时间戳<=anchorTs 的最右侧帧）
     * - 支持等待后向帧补齐（postCount）以保证窗口完整
     */
    public static SnapshotWindowResult<DiskFrameRingBuffer.FrameItem> collectFromSlowMoDiskAnchoredTs(
            SimpleWebRTCPlayerView player,
            long anchorTs,
            int preCount,
            int postCount,
            long waitTimeoutMs
    ) {
        if (player == null) {
            return new SnapshotWindowResult<>(Collections.emptyList(), 0, 0, 0, false);
        }
        long deadline = System.currentTimeMillis() + Math.max(0, waitTimeoutMs);
        List<DiskFrameRingBuffer.FrameItem> snapshot = safeDiskSnapshot(player);
        int anchorIdx = findAnchorIndex(snapshot, anchorTs);
        int needEnd = Math.max(0, anchorIdx) + Math.max(0, postCount);
        while ((snapshot == null || snapshot.size() <= needEnd) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(80); } catch (InterruptedException ignore) {}
            snapshot = safeDiskSnapshot(player);
            anchorIdx = findAnchorIndex(snapshot, anchorTs);
            needEnd = Math.max(0, anchorIdx) + Math.max(0, postCount);
        }

        if (snapshot == null || snapshot.isEmpty()) {
            return new SnapshotWindowResult<>(Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        anchorIdx = Math.max(0, Math.min(anchorIdx, size - 1));
        int start = Math.max(0, anchorIdx - Math.max(0, preCount));
        int end = Math.min(size - 1, anchorIdx + Math.max(0, postCount));
        List<DiskFrameRingBuffer.FrameItem> window = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            window.add(snapshot.get(i));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, anchorIdx - start));
        boolean timedOut = (size <= needEnd) && (System.currentTimeMillis() >= deadline);
        return new SnapshotWindowResult<>(window, eventRel, start, end, timedOut);
    }

    /**
     * 在快照中查找时间戳<=anchorTs 的最右侧索引；若不存在则返回最后一帧索引。
     */
    private static int findAnchorIndex(List<DiskFrameRingBuffer.FrameItem> snapshot, long anchorTs) {
        if (snapshot == null || snapshot.isEmpty()) return 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            DiskFrameRingBuffer.FrameItem it = snapshot.get(i);
            if (it != null && it.timestamp <= anchorTs) return i;
        }
        return snapshot.size() - 1;
    }

    /**
     * 从内存快照收集窗口：用于实时来源（未来从播放器实时滑窗传入）。
     * @param snapshot 内存环形缓冲的快照列表
     * @param eventIndex 事件点在源快照中的索引
     * @param preCount 事件点前帧数
     * @param postCount 事件点后帧数
     */
    public static SnapshotWindowResult<FrameRingBuffer.FrameItem> collectFromMemory(
            List<FrameRingBuffer.FrameItem> snapshot,
            int eventIndex,
            int preCount,
            int postCount
    ) {
        if (snapshot == null || snapshot.isEmpty()) {
            return new SnapshotWindowResult<>(Collections.emptyList(), 0, 0, 0, false);
        }
        int size = snapshot.size();
        int start = Math.max(0, Math.min(Math.max(0, eventIndex) - Math.max(0, preCount), size - 1));
        int end = Math.max(start, Math.min(Math.max(0, eventIndex) + Math.max(0, postCount), size - 1));
        List<FrameRingBuffer.FrameItem> window = new ArrayList<>(end - start + 1);
        for (int i = start; i <= end; i++) {
            window.add(snapshot.get(i));
        }
        int eventRel = Math.max(0, Math.min(window.size() - 1, Math.max(0, eventIndex) - start));
        return new SnapshotWindowResult<>(window, eventRel, start, end, false);
    }

    private static List<DiskFrameRingBuffer.FrameItem> safeDiskSnapshot(SimpleWebRTCPlayerView player) {
        try { return player.getSlowMoDiskSnapshot(); } catch (Throwable ignore) { return Collections.emptyList(); }
    }
}