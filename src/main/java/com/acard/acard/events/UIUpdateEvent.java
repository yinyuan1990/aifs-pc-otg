package com.acard.acard.events;

/**
 * UI更新事件类
 * 用于通知UI组件进行刷新，支持多种事件类型
 * 
 * @author AI Assistant
 * @date 2025-01-22
 */
public class UIUpdateEvent {
    
    /**
     * 事件类型枚举
     */
    public enum EventType {
        WINDOW_SIZE_CHANGED,    // 窗口大小变化
        WINDOW_MOVED,           // 窗口移动
        WINDOW_MAXIMIZED,       // 窗口最大化
        WINDOW_RESTORED,        // 窗口恢复
        FULLSCREEN_TOGGLED,     // 全屏切换
        LAYOUT_CHANGED,         // 布局变化
        PANE_SWITCHED,          // 面板切换
        FORCE_REFRESH,          // 强制刷新
        CONTAINER_RESIZED,       // 容器尺寸变化

        SPEED_KEY,     // 快捷建设置

        RecordingStartedEvent,  //录制开始

        RecordingFileReadyEvent, //录制就绪
        RecordingProgressEvent, //录制进度
        RecordingStoppedEvent, // 录制停止
        UpdateLuzhiNUmEvent, // 录制帧数

        SendFilePathEvent, //发送最新帧事件

        CavasDataEvent,  //调节画面信息

        DeleteItemEvent, //delete item

        SlowCleanEvent, //delete item

        CleanAllEvent, GpuViewCameraEvent, CameraSettingsDialogEvent, //delete item

        AUTO_FULLSCREEN,  // ⭐ 自动触发全屏切换（抓拍满格或清空后恢复）
        RESOLUTION_CHANGED,

        JiesuanCountEvent, //慢放结束
    }
    
    private final EventType eventType;
    private final String source;        // 事件源标识
    private final Object data;          // 附加数据
    private final long timestamp;       // 事件时间戳
    
    /**
     * 构造函数
     * @param eventType 事件类型
     * @param source 事件源标识
     */
    public UIUpdateEvent(EventType eventType, String source) {
        this(eventType, source, null);
    }
    
    /**
     * 构造函数
     * @param eventType 事件类型
     * @param source 事件源标识
     * @param data 附加数据
     */
    public UIUpdateEvent(EventType eventType, String source, Object data) {
        this.eventType = eventType;
        this.source = source;
        this.data = data;
        this.timestamp = System.currentTimeMillis();


    }
    
    /**
     * 获取事件类型
     */
    public EventType getEventType() {
        return eventType;
    }
    
    /**
     * 获取事件源标识
     */
    public String getSource() {
        return source;
    }
    
    /**
     * 获取附加数据
     */
    public Object getData() {
        return data;
    }



    /**
     * 获取事件时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * 获取指定类型的数据
     */
    @SuppressWarnings("unchecked")
    public <T> T getData(Class<T> type) {
        if (data != null && type.isInstance(data)) {
            return (T) data;
        }
        return null;
    }
    
    @Override
    public String toString() {
        return String.format("UIUpdateEvent{type=%s, source='%s', timestamp=%d}", 
                           eventType, source, timestamp);
    }
    
    /**
     * 窗口尺寸数据类
     */
    public static class WindowSizeData {
        public final double width;
        public final double height;
        public final double x;
        public final double y;
        
        public WindowSizeData(double width, double height, double x, double y) {
            this.width = width;
            this.height = height;
            this.x = x;
            this.y = y;
        }
        
        @Override
        public String toString() {
            return String.format("WindowSize{%.0fx%.0f at (%.0f,%.0f)}", width, height, x, y);
        }
    }
    
    /**
     * 容器尺寸数据类
     */
    public static class ContainerSizeData {
        public final double width;
        public final double height;
        public final String containerId;
        
        public ContainerSizeData(double width, double height, String containerId) {
            this.width = width;
            this.height = height;
            this.containerId = containerId;
        }
        
        @Override
        public String toString() {
            return String.format("ContainerSize{%.0fx%.0f, id='%s'}", width, height, containerId);
        }
    }


    public static class FsFilePathData{

        public String filepath;
        public int frameIndex;

        public FsFilePathData(String filepath, int frameIndex) {
            this.filepath = filepath;
            this.frameIndex = frameIndex;
        }

        public String getFilepath() {
            return filepath;
        }

        public void setFilepath(String filepath) {
            this.filepath = filepath;
        }

        public int getFrameIndex() {
            return frameIndex;
        }

        public void setFrameIndex(int frameIndex) {
            this.frameIndex = frameIndex;
        }
    }

    public static class CavasData{

        public String name;
        public double value;

        public int percent;



        public CavasData(String name, double value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public double getValue() {
            return value;
        }

        public void setValue(double value) {
            this.value = value;
        }


        public int getPercent() {
            return percent;
        }

        public void setPercent(int percent) {
            this.percent = percent;
        }
    }

    /**
     * ⭐ 删除Item数据类
     */
    public static class DeleteItemData {
        public final int itemIndex;        // 被删除的item索引
        public final int remainingCount;   // 删除后剩余的item数量
        
        public DeleteItemData(int itemIndex, int remainingCount) {
            this.itemIndex = itemIndex;
            this.remainingCount = remainingCount;
        }
        
        public int getItemIndex() {
            return itemIndex;
        }
        
        public int getRemainingCount() {
            return remainingCount;
        }
        
        @Override
        public String toString() {
            return String.format("DeleteItemData{itemIndex=%d, remainingCount=%d}", 
                               itemIndex, remainingCount);
        }
    }

}