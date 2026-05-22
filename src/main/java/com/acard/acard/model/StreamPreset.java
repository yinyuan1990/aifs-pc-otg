package com.acard.acard.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 与 Swift 结构体 StreamPreset 对应的 Java 实现，包含两种预设（standard/high）。
 * 该表为内存常量，可在运行时通过 StreamPreset.TABLE 获取。
 */
public final class StreamPreset {
    private final int width;
    private final int height;
    private final int videoBitrateKbps;
    private final int fps;
    private final int audioBitrate;
    private final int sampleRate;
    private final int keyframeIntervalSec;
    private final String h264ProfileLevel;

    public StreamPreset(int width,
                        int height,
                        int videoBitrateKbps,
                        int fps,
                        int audioBitrate,
                        int sampleRate,
                        int keyframeIntervalSec,
                        String h264ProfileLevel) {
        this.width = width;
        this.height = height;
        this.videoBitrateKbps = videoBitrateKbps;
        this.fps = fps;
        this.audioBitrate = audioBitrate;
        this.sampleRate = sampleRate;
        this.keyframeIntervalSec = keyframeIntervalSec;
        this.h264ProfileLevel = h264ProfileLevel;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getVideoBitrateKbps() { return videoBitrateKbps; }
    public int getFps() { return fps; }
    public int getAudioBitrate() { return audioBitrate; }
    public int getSampleRate() { return sampleRate; }
    public int getKeyframeIntervalSec() { return keyframeIntervalSec; }
    public String getH264ProfileLevel() { return h264ProfileLevel; }

    /**
     * 预设表：在内存中为不可变映射。
     */
    public static final Map<StreamProfile, StreamPreset> TABLE;

    static {
        EnumMap<StreamProfile, StreamPreset> map = new EnumMap<>(StreamProfile.class);
        map.put(StreamProfile.STANDARD, new StreamPreset(
                1280, 720,
                2500,
                60,
                128_000,
                44100,
                1,
                "H264_Baseline_AutoLevel"
        ));
        map.put(StreamProfile.HIGH, new StreamPreset(
                1920, 1080,
                5000,
                60,
                128_000,
                44100,
                1,
                "H264_Baseline_AutoLevel"
        ));
        TABLE = Collections.unmodifiableMap(map);
    }
}