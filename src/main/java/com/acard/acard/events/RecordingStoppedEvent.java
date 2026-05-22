package com.acard.acard.events;



/**
 * 录制停止事件
 */
public class RecordingStoppedEvent extends RecordingEvent {
    private final long finalSize;
    private final long totalDuration;

    public RecordingStoppedEvent(String filename, long finalSize, long totalDuration) {
        super(filename);
        this.finalSize = finalSize;
        this.totalDuration = totalDuration;
    }

    public long getFinalSize() {
        return finalSize;
    }

    public long getTotalDuration() {
        return totalDuration;
    }

    @Override
    public String toString() {
        return "RecordingStoppedEvent{" +
                "filename='" + getFilename() + '\'' +
                ", finalSize=" + finalSize +
                ", totalDuration=" + totalDuration +
                '}';
    }
}
