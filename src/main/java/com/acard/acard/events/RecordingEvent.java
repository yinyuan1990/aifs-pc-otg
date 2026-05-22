package com.acard.acard.events;



/**
 * 录制事件基类
 */
public abstract class RecordingEvent {
    private final String filename;
    private final long timestamp;

    public RecordingEvent(String filename) {
        this.filename = filename;
        this.timestamp = System.currentTimeMillis();
    }

    public String getFilename() {
        return filename;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
