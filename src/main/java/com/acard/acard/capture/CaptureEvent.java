package com.acard.acard.capture;

/**
 * 抓拍事件 - 用于事件驱动的后续帧收集
 * 
 * 工作原理：
 * 1. 点击抓拍时，创建一个CaptureEvent并加入活跃事件列表
 * 2. 每当新帧保存到磁盘时，遍历活跃事件列表
 * 3. 检查该帧是否是某个事件的后续帧（通过frameId范围判断）
 * 4. 如果是，复制到该事件的独立文件夹
 * 5. 如果收集完成，从活跃列表移除
 */
public class CaptureEvent {
    
    /**
     * 事件类型
     */
    public enum Type {
        REALTIME("实时流抓拍"),
        SLOWMOTION("慢放抓拍");
        
        private final String displayName;
        
        Type(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    private final String eventId;                    // 事件唯一标识
    private final Type type;                         // 事件类型
    private final long eventFrameId;                 // 事件帧ID（点击抓拍时的当前帧）
    private final int postFrameCount;                // 需要的后续帧数量
    private final CaptureSession session;            // 对应的独立文件夹会话
    private final String sessionId;                  // ✅ 会话ID（如 "时时流-001"）用于UI关联
    private final long createTime;                   // 创建时间
    private final java.util.function.BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback;  // ✅ 事件专属回调
    
    // 运行状态
    private volatile int collectedCount = 0;         // 已收集的后续帧数量
    private volatile long lastFrameId = -1;          // 最后一个目标帧ID
    private volatile boolean completed = false;      // 是否完成
    
    /**
     * 创建抓拍事件
     * 
     * @param type 事件类型
     * @param eventFrameId 事件帧ID（点击抓拍时的当前帧）
     * @param postFrameCount 需要的后续帧数量
     * @param session 对应的独立文件夹会话
     * @param callback 事件专属回调（只处理当前事件的帧）
     */
    public CaptureEvent(Type type, long eventFrameId, int postFrameCount, CaptureSession session,
                       java.util.function.BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback) {
        this.eventId = java.util.UUID.randomUUID().toString();
        this.type = type;
        this.eventFrameId = eventFrameId;
        this.postFrameCount = postFrameCount;
        this.session = session;
        this.sessionId = session != null ? session.getSessionId() : "";  // ✅ 从session获取ID
        this.callback = callback;  // ✅ 保存专属回调
        this.createTime = System.currentTimeMillis();
        this.lastFrameId = eventFrameId + postFrameCount;  // 最后一帧ID
        
        System.out.println("📋 创建抓拍事件: " + this);
    }
    
    /**
     * 检查是否还需要收集后续帧
     * 使用计数器而不是frameId范围，因为frameId可能不连续
     * 
     * @return true=还需要收集，false=已收集够
     */
    public boolean needsPostFrame() {
        // 只要收集的数量还没达到目标，就继续收集
        return collectedCount < postFrameCount && !completed;
    }
    
    /**
     * 添加后续帧到独立文件夹
     * 使用计数器机制，不依赖frameId连续性
     * 
     * @param jpegBytes JPEG数据
     * @param timestamp 时间戳
     * @param width 宽度
     * @param height 高度
     * @param frameId 帧ID（仅用于记录，不用于判断）
     * @return 成功添加的帧，失败返回null
     */
    public synchronized DiskCaptureCache.DiskFrameItem addPostFrame(
            byte[] jpegBytes, long timestamp, int width, int height, long frameId,String name) {
        
        if (completed) {
            return null;  // 已完成，不再接收
        }
        
        if (!needsPostFrame()) {
            return null;  // 已收集够了
        }
        
        try {
            // 添加到独立文件夹
            DiskCaptureCache.DiskFrameItem diskFrame = 
                session.addPostFrame(jpegBytes, timestamp, width, height, frameId,name);
            
            collectedCount++;  // 计数器递增
            
            // 检查是否完成（基于计数器）
            if (collectedCount >= postFrameCount) {
                completed = true;
                long duration = System.currentTimeMillis() - createTime;
                System.out.println("✅ 抓拍事件完成: " + eventId.substring(0, 8) + 
                    " (收集" + collectedCount + "/" + postFrameCount + "帧, 耗时" + duration + "ms)");
            }
            
            return diskFrame;
            
        } catch (Exception e) {
            System.err.println("⚠️ 添加后续帧失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取事件ID
     */
    public String getEventId() {
        return eventId;
    }
    
    /**
     * 获取事件类型
     */
    public Type getType() {
        return type;
    }
    
    /**
     * 获取事件帧ID
     */
    public long getEventFrameId() {
        return eventFrameId;
    }
    
    /**
     * 获取需要的后续帧数量
     */
    public int getPostFrameCount() {
        return postFrameCount;
    }
    
    /**
     * 获取已收集的后续帧数量
     */
    public int getCollectedCount() {
        return collectedCount;
    }
    
    /**
     * 是否完成
     */
    public boolean isCompleted() {
        return completed;
    }
    
    /**
     * 获取对应的会话
     */
    public CaptureSession getSession() {
        return session;
    }
    
    /**
     * ✅ 获取会话ID（用于UI item关联）
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * ✅ 获取事件专属回调
     */
    public java.util.function.BiConsumer<DiskCaptureCache.DiskFrameItem, String> getCallback() {
        return callback;
    }
    
    /**
     * 获取最后一帧ID
     */
    public long getLastFrameId() {
        return lastFrameId;
    }
    
    @Override
    public String toString() {
        return String.format("CaptureEvent[id=%s, type=%s, eventFrameId=%d, need=%d, collected=%d, lastFrameId=%d, completed=%s]",
            eventId.substring(0, 8), type.getDisplayName(), eventFrameId, postFrameCount, collectedCount, lastFrameId, completed);
    }
}

