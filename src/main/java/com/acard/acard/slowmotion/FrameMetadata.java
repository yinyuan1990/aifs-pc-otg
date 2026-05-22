package com.acard.acard.slowmotion;

/**
 * 帧元数据
 * 用于记录慢动作录制的帧信息
 */
public class FrameMetadata {
    
    public final long frameNumber;      // 帧编号
    public final long timestamp;        // 时间戳（毫秒）
    public final String filePath;       // 文件路径
    public final int width;             // 宽度
    public final int height;            // 高度
    
    public FrameMetadata(long frameNumber, long timestamp, String filePath, int width, int height) {
        this.frameNumber = frameNumber;
        this.timestamp = timestamp;
        this.filePath = filePath;
        this.width = width;
        this.height = height;
    }
    
    @Override
    public String toString() {
        return String.format("Frame#%d @%dms [%dx%d] %s", 
            frameNumber, timestamp, width, height, filePath);
    }
}

