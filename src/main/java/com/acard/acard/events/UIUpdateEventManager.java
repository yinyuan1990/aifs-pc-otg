package com.acard.acard.events;

import javafx.application.Platform;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * UI更新事件管理器
 * 提供事件注册、注销和发送功能，支持异步事件处理
 * 
 * @author AI Assistant
 * @date 2025-01-22
 */
public class UIUpdateEventManager {
    
    /**
     * 事件监听器接口
     */
    @FunctionalInterface
    public interface UIUpdateEventListener {
        void onUIUpdateEvent(UIUpdateEvent event);
    }
    
    // 单例实例
    private static volatile UIUpdateEventManager instance;
    
    // 事件监听器映射：事件类型 -> 监听器列表
    private final ConcurrentHashMap<UIUpdateEvent.EventType, CopyOnWriteArrayList<ListenerWrapper>> listeners;
    
    // 全局监听器（监听所有事件类型）
    private final CopyOnWriteArrayList<ListenerWrapper> globalListeners;
    
    // 是否启用调试日志
    private volatile boolean debugEnabled = false;
    
    /**
     * 监听器包装类，包含监听器和标识信息
     */
    private static class ListenerWrapper {
        final UIUpdateEventListener listener;
        final String listenerId;
        final boolean runOnFxThread;
        final long registeredTime;
        
        ListenerWrapper(UIUpdateEventListener listener, String listenerId, boolean runOnFxThread) {
            this.listener = listener;
            this.listenerId = listenerId;
            this.runOnFxThread = runOnFxThread;
            this.registeredTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 私有构造函数
     */
    private UIUpdateEventManager() {
        this.listeners = new ConcurrentHashMap<>();
        this.globalListeners = new CopyOnWriteArrayList<>();
    }
    
    /**
     * 获取单例实例
     */
    public static UIUpdateEventManager getInstance() {
        if (instance == null) {
            synchronized (UIUpdateEventManager.class) {
                if (instance == null) {
                    instance = new UIUpdateEventManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 注册事件监听器（监听特定事件类型）
     * @param eventType 事件类型
     * @param listener 监听器
     * @param listenerId 监听器标识
     * @return 是否注册成功
     */
    public boolean registerListener(UIUpdateEvent.EventType eventType, UIUpdateEventListener listener, String listenerId) {
        return registerListener(eventType, listener, listenerId, true);
    }
    
    /**
     * 注册事件监听器（监听特定事件类型）
     * @param eventType 事件类型
     * @param listener 监听器
     * @param listenerId 监听器标识
     * @param runOnFxThread 是否在JavaFX线程中执行
     * @return 是否注册成功
     */
    public boolean registerListener(UIUpdateEvent.EventType eventType, UIUpdateEventListener listener, 
                                  String listenerId, boolean runOnFxThread) {
        if (eventType == null || listener == null || listenerId == null) {
            return false;
        }
        
        try {
            CopyOnWriteArrayList<ListenerWrapper> eventListeners = listeners.computeIfAbsent(
                eventType, k -> new CopyOnWriteArrayList<>());
            
            // 检查是否已存在相同ID的监听器
            for (ListenerWrapper wrapper : eventListeners) {
                if (listenerId.equals(wrapper.listenerId)) {
                    if (debugEnabled) {
                        System.out.println("⚠️ [UIEventManager] 监听器ID已存在，将替换: " + listenerId);
                    }
                    eventListeners.remove(wrapper);
                    break;
                }
            }
            
            ListenerWrapper wrapper = new ListenerWrapper(listener, listenerId, runOnFxThread);
            eventListeners.add(wrapper);
            
            if (debugEnabled) {
                System.out.println("✅ [UIEventManager] 注册监听器: " + eventType + " -> " + listenerId);
            }
            return true;
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 注册监听器失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 注册全局事件监听器（监听所有事件类型）
     * @param listener 监听器
     * @param listenerId 监听器标识
     * @return 是否注册成功
     */
    public boolean registerGlobalListener(UIUpdateEventListener listener, String listenerId) {
        return registerGlobalListener(listener, listenerId, true);
    }
    
    /**
     * 注册全局事件监听器（监听所有事件类型）
     * @param listener 监听器
     * @param listenerId 监听器标识
     * @param runOnFxThread 是否在JavaFX线程中执行
     * @return 是否注册成功
     */
    public boolean registerGlobalListener(UIUpdateEventListener listener, String listenerId, boolean runOnFxThread) {
        if (listener == null || listenerId == null) {
            return false;
        }
        
        try {
            // 检查是否已存在相同ID的监听器
            for (ListenerWrapper wrapper : globalListeners) {
                if (listenerId.equals(wrapper.listenerId)) {
                    if (debugEnabled) {
                        System.out.println("⚠️ [UIEventManager] 全局监听器ID已存在，将替换: " + listenerId);
                    }
                    globalListeners.remove(wrapper);
                    break;
                }
            }
            
            ListenerWrapper wrapper = new ListenerWrapper(listener, listenerId, runOnFxThread);
            globalListeners.add(wrapper);
            
            if (debugEnabled) {
                System.out.println("✅ [UIEventManager] 注册全局监听器: " + listenerId);
            }
            return true;
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 注册全局监听器失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 注销事件监听器
     * @param eventType 事件类型
     * @param listenerId 监听器标识
     * @return 是否注销成功
     */
    public boolean unregisterListener(UIUpdateEvent.EventType eventType, String listenerId) {
        if (eventType == null || listenerId == null) {
            return false;
        }
        
        try {
            CopyOnWriteArrayList<ListenerWrapper> eventListeners = listeners.get(eventType);
            if (eventListeners != null) {
                boolean removed = eventListeners.removeIf(wrapper -> listenerId.equals(wrapper.listenerId));
                if (removed && debugEnabled) {
                    System.out.println("✅ [UIEventManager] 注销监听器: " + eventType + " -> " + listenerId);
                }
                return removed;
            }
            return false;
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 注销监听器失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 注销全局事件监听器
     * @param listenerId 监听器标识
     * @return 是否注销成功
     */
    public boolean unregisterGlobalListener(String listenerId) {
        if (listenerId == null) {
            return false;
        }
        
        try {
            boolean removed = globalListeners.removeIf(wrapper -> listenerId.equals(wrapper.listenerId));
            if (removed && debugEnabled) {
                System.out.println("✅ [UIEventManager] 注销全局监听器: " + listenerId);
            }
            return removed;
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 注销全局监听器失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 注销指定监听器ID的所有监听器
     * @param listenerId 监听器标识
     * @return 注销的监听器数量
     */
    public int unregisterAllListeners(String listenerId) {
        if (listenerId == null) {
            return 0;
        }
        
        int count = 0;
        
        try {
            // 注销特定事件类型的监听器
            for (CopyOnWriteArrayList<ListenerWrapper> eventListeners : listeners.values()) {
                count += eventListeners.removeIf(wrapper -> listenerId.equals(wrapper.listenerId)) ? 1 : 0;
            }
            
            // 注销全局监听器
            count += globalListeners.removeIf(wrapper -> listenerId.equals(wrapper.listenerId)) ? 1 : 0;
            
            if (count > 0 && debugEnabled) {
                System.out.println("✅ [UIEventManager] 注销所有监听器: " + listenerId + " (共" + count + "个)");
            }
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 注销所有监听器失败: " + e.getMessage());
        }
        
        return count;
    }
    
    /**
     * 发送事件
     * @param event 事件对象
     */
    public void fireEvent(UIUpdateEvent event) {
        if (event == null) {
            return;
        }
        
        if (debugEnabled) {
            System.out.println("🔔 [UIEventManager] 发送事件: " + event);
        }
        
        // 通知特定事件类型的监听器
        CopyOnWriteArrayList<ListenerWrapper> eventListeners = listeners.get(event.getEventType());
        if (eventListeners != null) {
            for (ListenerWrapper wrapper : eventListeners) {
                notifyListener(wrapper, event);
            }
        }
        
        // 通知全局监听器
        for (ListenerWrapper wrapper : globalListeners) {
            notifyListener(wrapper, event);
        }
    }
    
    /**
     * 发送事件（便捷方法）
     * @param eventType 事件类型
     * @param source 事件源
     */
    public void fireEvent(UIUpdateEvent.EventType eventType, String source) {
        fireEvent(new UIUpdateEvent(eventType, source));
    }
    
    /**
     * 发送事件（便捷方法）
     * @param eventType 事件类型
     * @param source 事件源
     * @param data 附加数据
     */
    public void fireEvent(UIUpdateEvent.EventType eventType, String source, Object data) {
        fireEvent(new UIUpdateEvent(eventType, source, data));
    }
    
    /**
     * 通知单个监听器
     */
    private void notifyListener(ListenerWrapper wrapper, UIUpdateEvent event) {
        try {
            if (wrapper.runOnFxThread && !Platform.isFxApplicationThread()) {
                Platform.runLater(() -> {
                    try {
                        wrapper.listener.onUIUpdateEvent(event);
                    } catch (Exception e) {
                        System.err.println("❌ [UIEventManager] 监听器执行失败: " + wrapper.listenerId + " - " + e.getMessage());
                    }
                });
            } else {
                wrapper.listener.onUIUpdateEvent(event);
            }
        } catch (Exception e) {
            System.err.println("❌ [UIEventManager] 通知监听器失败: " + wrapper.listenerId + " - " + e.getMessage());
        }
    }
    
    /**
     * 设置调试模式
     * @param enabled 是否启用调试日志
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        if (enabled) {
            System.out.println("🔍 [UIEventManager] 调试模式已启用");
        }
    }
    
    /**
     * 获取监听器统计信息
     */
    public String getListenerStats() {
        int totalSpecific = listeners.values().stream().mapToInt(list -> list.size()).sum();
        int totalGlobal = globalListeners.size();
        return String.format("监听器统计: 特定事件=%d, 全局=%d, 总计=%d", 
                           totalSpecific, totalGlobal, totalSpecific + totalGlobal);
    }
    
    /**
     * 清空所有监听器
     */
    public void clearAllListeners() {
        listeners.clear();
        globalListeners.clear();
        if (debugEnabled) {
            System.out.println("🧹 [UIEventManager] 已清空所有监听器");
        }
    }
}