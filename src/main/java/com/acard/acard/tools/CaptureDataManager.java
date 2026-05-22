package com.acard.acard.tools;


import com.acard.acard.model.CaptureData;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 捕获数据管理器（线程安全，区间管理）
 * 维护事件ID与CaptureData的映射，并计算有效的连续区间列表
 */
public class CaptureDataManager {


    public static boolean isActive(){

        if(slowPly!=null&&slowPly.length()>0){

            return true;
        }
        return false;
    }
    public static String slowPly="";
    private static CaptureDataManager instance= null;

    public static CaptureDataManager getInstance(){
         if(instance==null){
              instance = new CaptureDataManager();
         }
         return instance;
    }

    private final Map<String, CaptureData> captureDataMap;
    private final ReadWriteLock lock;

    // 缓存的有效区间列表
    private volatile List<IndexRange> cachedRanges = null;
    private volatile boolean needRecalculate = true;

    private CaptureDataManager() {
        this.captureDataMap = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * 添加或更新捕获数据
     */
    public void put(String eventId, CaptureData data) {
        lock.writeLock().lock();
        try {
            captureDataMap.put(eventId, data);
            needRecalculate = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 批量添加
     */
    public void putAll(Map<String, CaptureData> dataMap) {
        lock.writeLock().lock();
        try {
            captureDataMap.putAll(dataMap);
            needRecalculate = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 删除指定事件
     */
    public CaptureData remove(String eventId) {
        lock.writeLock().lock();
        try {
            CaptureData removed = captureDataMap.remove(eventId);
            if (removed != null) {
                needRecalculate = true;
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清空所有数据
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            captureDataMap.clear();
            cachedRanges = null;
            needRecalculate = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取指定事件的数据
     */
    public CaptureData get(String eventId) {
        lock.readLock().lock();
        try {
            return captureDataMap.get(eventId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取数据数量
     */
    public int size() {
        lock.readLock().lock();
        try {
            return captureDataMap.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 判断是否为空
     */
    public boolean isEmpty() {
        lock.readLock().lock();
        try {
            return captureDataMap.isEmpty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 获取所有有效的连续区间列表⭐
     *
     * 例如：
     * - 事件1: 10-50
     * - 事件2: 20-60  → 合并为 [10-60]
     *
     * - 事件1: 10-50
     * - 事件2: 60-100 → 返回 [10-50], [60-100]（中间51-59是空的）
     *
     * @return 有效区间列表（按起始位置排序）
     */
    public List<IndexRange> getValidRanges() {
        lock.readLock().lock();
        try {
            if (captureDataMap.isEmpty()) {
                return Collections.emptyList();
            }

            if (!needRecalculate && cachedRanges != null) {
                return new ArrayList<>(cachedRanges);
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            if (captureDataMap.isEmpty()) {
                return Collections.emptyList();
            }

            if (!needRecalculate && cachedRanges != null) {
                return new ArrayList<>(cachedRanges);
            }

            recalculateRanges();
            return new ArrayList<>(cachedRanges);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 判断指定索引是否在有效区间内⭐
     */
    public boolean isIndexValid(int index) {
        List<IndexRange> ranges = getValidRanges();
        for (IndexRange range : ranges) {
            if (index >= range.getStart() && index <= range.getEnd()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取总的有效帧数
     */
    public int getTotalValidFrames() {
        List<IndexRange> ranges = getValidRanges();
        int total = 0;
        for (IndexRange range : ranges) {
            total += range.getLength();
        }
        return total;
    }

    /**
     * 重新计算有效区间（必须在写锁中调用）⭐
     */
    private void recalculateRanges() {
        if (captureDataMap.isEmpty()) {
            cachedRanges = Collections.emptyList();
            needRecalculate = false;
            return;
        }

        // 1. 收集所有区间
        List<IndexRange> ranges = new ArrayList<>();
        for (CaptureData data : captureDataMap.values()) {
            if (data != null) {
                ranges.add(new IndexRange(data.getStartIndex(), data.getEndIndex()));
            }
        }

        if (ranges.isEmpty()) {
            cachedRanges = Collections.emptyList();
            needRecalculate = false;
            return;
        }

        // 2. 按起始位置排序
        ranges.sort(Comparator.comparingInt(IndexRange::getStart));

        // 3. 合并重叠或相邻的区间
        List<IndexRange> merged = new ArrayList<>();
        IndexRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            IndexRange next = ranges.get(i);

            // 如果重叠或相邻（相邻指 current.end + 1 == next.start）
            if (next.getStart() <= current.getEnd() + 1) {
                // 合并：扩展当前区间的结束位置
                current = new IndexRange(
                        current.getStart(),
                        Math.max(current.getEnd(), next.getEnd())
                );
            } else {
                // 不重叠，保存当前区间，开始新区间
                merged.add(current);
                current = next;
            }
        }

        // 添加最后一个区间
        merged.add(current);

        cachedRanges = Collections.unmodifiableList(merged);
        needRecalculate = false;
    }

    /**
     * 获取所有数据的快照
     */
    public Map<String, CaptureData> snapshot() {
        lock.readLock().lock();
        try {
            return new HashMap<>(captureDataMap);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 强制重新计算
     */
    public void forceRecalculate() {
        lock.writeLock().lock();
        try {
            needRecalculate = true;
            recalculateRanges();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取可删除的索引范围（所有有效区间的并集）⭐
     */
    public Set<Integer> getDeletableIndices() {
        Set<Integer> indices = new HashSet<>();
        List<IndexRange> ranges = getValidRanges();

        for (IndexRange range : ranges) {
            for (int i = range.getStart(); i <= range.getEnd(); i++) {
                indices.add(i);
            }
        }

        return indices;
    }

    /**
     * 索引区间类
     */
    public static class IndexRange {
        private final int start;
        private final int end;

        public IndexRange(int start, int end) {
            if (start > end) {
                throw new IllegalArgumentException("start必须小于等于end");
            }
            this.start = start;
            this.end = end;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public int getLength() {
            return end - start + 1;
        }

        public boolean contains(int index) {
            return index >= start && index <= end;
        }

        @Override
        public String toString() {
            return "[" + start + "-" + end + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IndexRange that = (IndexRange) o;
            return start == that.start && end == that.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    }
}
