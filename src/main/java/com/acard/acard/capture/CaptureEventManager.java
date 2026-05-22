package com.acard.acard.capture;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 抓拍事件管理器 - 管理活跃的抓拍事件列表
 * 
 * 核心机制：
 * 1. 维护活跃事件列表（CopyOnWriteArrayList，线程安全）
 * 2. 每当新帧保存到磁盘时，调用onFrameSaved()
 * 3. 遍历活跃事件，检查该帧是否是某个事件的后续帧
 * 4. 如果是，复制到该事件的独立文件夹
 * 5. 如果事件完成，自动从列表移除
 * 
 * 使用示例：
 * ```java
 * // 1. 注册事件
 * CaptureEvent event = new CaptureEvent(Type.REALTIME, eventFrameId, 60, session);
 * manager.registerEvent(event);
 * 
 * // 2. 每帧保存后调用（在DiskCaptureCache.addFrame中）
 * manager.onFrameSaved(jpegBytes, timestamp, width, height, frameId);
 * 
 * // 3. 自动完成和清理
 * ```
 */
public class CaptureEventManager {
    
    private static final CaptureEventManager INSTANCE = new CaptureEventManager();
    
    // 活跃事件列表（线程安全）
    private final List<CaptureEvent> activeEvents = new CopyOnWriteArrayList<>();
    
    // 帧回调（可选，用于通知UI更新）
    private final List<BiConsumer<DiskCaptureCache.DiskFrameItem, String>> frameCallbacks = new CopyOnWriteArrayList<>();
    
    private CaptureEventManager() {
    }
    
    public static CaptureEventManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 注册抓拍事件
     * 
     * @param event 抓拍事件
     */
    public void registerEvent(CaptureEvent event) {
        if (event == null) {
            return;
        }
        
        activeEvents.add(event);
        System.out.println("📌 注册抓拍事件: " + event.getEventId().substring(0, 8) + 
            ", 活跃事件数: " + activeEvents.size());
    }
    
    /**
     * 当新帧保存到磁盘时调用
     * 遍历活跃事件，检查是否需要复制到事件的独立文件夹
     * 
     * @param jpegBytes JPEG数据
     * @param timestamp 时间戳
     * @param width 宽度
     * @param height 高度
     * @param frameId 帧ID
     */
    public void onFrameSaved(byte[] jpegBytes, long timestamp, int width, int height, long frameId,String name) {
        if (activeEvents.isEmpty()) {
            return;  // 没有活跃事件，直接返回
        }
        
        // 🔍 每帧都输出日志（用于诊断慢放抓拍问题）
        boolean hasSlowMotionEvent = activeEvents.stream().anyMatch(e -> e.getType() == CaptureEvent.Type.SLOWMOTION);
        if (hasSlowMotionEvent) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("🔍 [CaptureEventManager] 帧保存触发");
            System.out.println("   frameId: " + frameId);
            System.out.println("   活跃事件数: " + activeEvents.size());
            for (CaptureEvent event : activeEvents) {
                System.out.println("   → 事件: " + event.getEventId().substring(0, 8) + 
                    ", 类型=" + event.getType().getDisplayName() + 
                    ", eventFrameId=" + event.getEventFrameId() +
                    ", 需要=" + event.getPostFrameCount() + 
                    ", 已收集=" + event.getCollectedCount() +
                    ", needsMore=" + event.needsPostFrame());
            }
        }
        
        // ✅ 遍历活跃事件（使用计数器，不依赖frameId连续性）
        Iterator<CaptureEvent> iterator = activeEvents.iterator();
        while (iterator.hasNext()) {
            CaptureEvent event = iterator.next();
            
            // ✅ 检查是否还需要收集后续帧（基于计数器）
            if (event.needsPostFrame()) {
                System.out.println("✅ [收集后续帧] frameId=" + frameId + " → " + 
                    event.getSession().getSessionId() + 
                    " [" + (event.getCollectedCount() + 1) + "/" + event.getPostFrameCount() + "]");
                
                // 添加到该事件的独立文件夹
                DiskCaptureCache.DiskFrameItem diskFrame = 
                    event.addPostFrame(jpegBytes, timestamp, width, height, frameId,name);
                
                if (diskFrame != null) {
                    System.out.println("   ✅ 帧已保存到: " + diskFrame.filePath);
                    
                    // ✅ 只调用该事件的专属回调（不再遍历全局回调列表）
                    BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback = event.getCallback();
                    if (callback != null) {
                        try {
                            System.out.println("   📞 调用专属回调...");
                            callback.accept(diskFrame, event.getSessionId());
                        } catch (Throwable e) {
                            System.err.println("   ❌ 回调失败: " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        System.err.println("   ⚠️ 回调为null！");
                    }
                } else {
                    System.err.println("   ⚠️ addPostFrame返回null");
                }
                
                // ✅ 检查是否完成（基于计数器）
                if (event.isCompleted()) {
                    iterator.remove();  // 安全移除
                    System.out.println("🎉 事件完成: " + event.getSession().getSessionId() + 
                        " (收集" + event.getCollectedCount() + "/" + event.getPostFrameCount() + "帧)" +
                        ", 剩余活跃事件数: " + activeEvents.size());
                }
            } else {
                if (hasSlowMotionEvent) {
                    System.out.println("   ⏭️ 跳过事件 " + event.getEventId().substring(0, 8) + 
                        "（不需要更多帧）");
                }
            }
        }
        
        if (hasSlowMotionEvent) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }
    
    /**
     * ✅ 添加帧回调（用于通知UI更新）- 新增sessionId参数用于关联UI item
     * 
     * @param callback 帧回调 (diskFrame, sessionId)
     */
    public void addFrameCallback(BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback) {
        if (callback != null) {
            frameCallbacks.add(callback);
        }
    }
    
    /**
     * 移除帧回调
     * 
     * @param callback 帧回调
     */
    public void removeFrameCallback(BiConsumer<DiskCaptureCache.DiskFrameItem, String> callback) {
        frameCallbacks.remove(callback);
    }
    
    /**
     * 获取活跃事件数量
     */
    public int getActiveEventCount() {
        return activeEvents.size();
    }
    
    /**
     * 获取活跃事件列表（只读）
     */
    public List<CaptureEvent> getActiveEvents() {
        return new java.util.ArrayList<>(activeEvents);
    }
    
    /**
     * 取消指定事件
     * 
     * @param eventId 事件ID
     */
    public void cancelEvent(String eventId) {
        activeEvents.removeIf(event -> event.getEventId().equals(eventId));
        System.out.println("❌ 取消事件: " + eventId.substring(0, 8));
    }
    
    /**
     * 清空所有活跃事件
     */
    public void clearAllEvents() {
        int count = activeEvents.size();
        activeEvents.clear();
        System.out.println("🗑️ 清空所有活跃事件: " + count + "个");
    }
    
    /**
     * 获取统计信息
     */
    public String getStatistics() {
        if (activeEvents.isEmpty()) {
            return "无活跃事件";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("活跃事件数: ").append(activeEvents.size()).append("\n");
        for (CaptureEvent event : activeEvents) {
            sb.append("  - ").append(event).append("\n");
        }
        return sb.toString();
    }
}

