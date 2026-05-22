package com.acard.acard.events;



/**
 * 录制开始事件
 */
public class RecordingStartedEvent extends RecordingEvent {

    public RecordingStartedEvent(String filename) {
        super(filename);
    }

    @Override
    public String toString() {
        return "RecordingStartedEvent{filename='" + getFilename() + "'}";
    }
}