package com.acard.acard.events;



/**
 * 录制进度事件
 */
public class RecordingProgressEvent extends RecordingEvent {
    private final long fileSize;
    private final long duration;

    public RecordingProgressEvent(String filename, long fileSize, long duration) {
        super(filename);
        this.fileSize = fileSize;
        this.duration = duration;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getDuration() {
        return duration;
    }

    @Override
    public String toString() {
        return "RecordingProgressEvent{" +
                "filename='" + getFilename() + '\'' +
                ", fileSize=" + fileSize +
                ", duration=" + duration +
                '}';
    }
}
