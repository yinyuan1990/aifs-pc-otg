package com.acard.acard.events;



/**
 * 录制文件就绪事件（可以开始播放）
 */
public class RecordingFileReadyEvent extends RecordingEvent {
    private final long fileSize;
    private final long recordingDuration;

    public RecordingFileReadyEvent(String filename, long fileSize, long recordingDuration) {
        super(filename);
        this.fileSize = fileSize;
        this.recordingDuration = recordingDuration;
    }

    public long getFileSize() {
        return fileSize;
    }

    public long getRecordingDuration() {
        return recordingDuration;
    }

    @Override
    public String toString() {
        return "RecordingFileReadyEvent{" +
                "filename='" + getFilename() + '\'' +
                ", fileSize=" + fileSize +
                ", recordingDuration=" + recordingDuration +
                '}';
    }
}
