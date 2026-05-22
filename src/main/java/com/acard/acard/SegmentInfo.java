package com.acard.acard;

import java.util.ArrayList;
import java.util.List;

public class SegmentInfo {

    String filePath;
    long startPts;
    long endPts;
    long durationNs;
    int index;
    List<Long> keyFramePts = new ArrayList<>();  // 关键帧的 PTS 列表
    public SegmentInfo(){

    }

    public SegmentInfo(String filePath, long startPts, long endPts, long durationNs, int index) {
        this.filePath = filePath;
        this.startPts = startPts;
        this.endPts = endPts;
        this.durationNs = durationNs;
        this.index = index;
    }

    public SegmentInfo(String filePath, long startPts, long endPts, long durationNs, int index, List<Long> keyFramePts) {
        this.filePath = filePath;
        this.startPts = startPts;
        this.endPts = endPts;
        this.durationNs = durationNs;
        this.index = index;
        this.keyFramePts = keyFramePts;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getStartPts() {
        return startPts;
    }

    public void setStartPts(long startPts) {
        this.startPts = startPts;
    }

    public long getEndPts() {
        return endPts;
    }

    public void setEndPts(long endPts) {
        this.endPts = endPts;
    }

    public long getDurationNs() {
        return durationNs;
    }

    public void setDurationNs(long durationNs) {
        this.durationNs = durationNs;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}
