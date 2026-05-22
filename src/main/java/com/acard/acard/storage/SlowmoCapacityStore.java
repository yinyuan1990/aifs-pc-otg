package com.acard.acard.storage;

/**
 * 慢放容量配置存储
 * 
 * 用于存储和管理慢放模式的缓存容量配置
 */
public class SlowmoCapacityStore {
    
    private static SlowmoCapacityStore instance;
    
    // ✅ 默认慢放缓存容量：1000张（磁盘缓存，33秒@30fps）
    // ⭐ 低端机优化：3000→1000，内存占用减少66%
    // 注意：只缓存路径，不加载到内存（Image缓存单独控制）
    private int slowmoCapacity = 1000;
    
    // ✅ 正常模式缓存容量：120张（从60增加到120，支持更长的前抓拍）
    // 120 × 200KB = 24MB磁盘，内存可控
    private static final int NORMAL_CAPACITY = 120;
    
    private SlowmoCapacityStore() {
        // 私有构造函数
    }
    
    public static synchronized SlowmoCapacityStore getInstance() {
        if (instance == null) {
            instance = new SlowmoCapacityStore();
        }
        return instance;
    }
    
    /**
     * 获取慢放缓存容量
     */
    public int getSlowmoCapacity() {
        return slowmoCapacity;
    }
    
    /**
     * 设置慢放缓存容量
     * 
     * @param capacity 容量（最小100，最大10000）
     */
    public void setSlowmoCapacity(int capacity) {
        this.slowmoCapacity = Math.max(100, Math.min(10000, capacity));
        System.out.println("💾 慢放缓存容量已设置为: " + this.slowmoCapacity + " 张");
    }
    
    /**
     * 获取正常模式缓存容量
     */
    public int getNormalCapacity() {
        return NORMAL_CAPACITY;
    }
    
    /**
     * 估算磁盘占用（按平均60KB/帧计算）
     * 
     * @return 估算的磁盘占用（MB）
     */
    public double estimateDiskUsage() {
        return (slowmoCapacity * 60.0) / 1024.0;
    }
}

