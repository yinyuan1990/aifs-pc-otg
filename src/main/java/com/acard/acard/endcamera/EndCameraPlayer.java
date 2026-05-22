package com.acard.acard.endcamera;

import com.acard.acard.webrtc.WebRTCConnectionManager;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.ByteBuffer;

/**
 * 轻量化的终端摄像视频播放类：只负责将 webrtcbin 的视频流解码并渲染到 ImageView。
 * 将复杂的信令与连接管理交给 WebRTCConnectionManager，达到解耦目的。
 */
public class EndCameraPlayer {
    private final WebRTCConnectionManager conn;
    private final WebRTCBin webrtcbin;

    private Pipeline pipeline;
    private Element rtph264depay;
    private Element h264parse;
    private Element decoder;
    private Element queueDepay;
    private Element queueDecode;
    private Element converter;
    private Element capsfilter;
    private AppSink appsink;

    private final ImageView imageView;
    private WritableImage sharedImage;
    private int sharedImageWidth = -1;
    private int sharedImageHeight = -1;

    public EndCameraPlayer(WebRTCConnectionManager conn) {
        this.conn = conn;
        this.webrtcbin = conn.getWebRTCBin();
        this.imageView = new ImageView();
        this.imageView.setPreserveRatio(true);
        this.imageView.setFitWidth(720);
        this.imageView.setFitHeight(1280);
    }

    public ImageView getImageView() {
        return imageView;
    }

    /**
     * 开始播放：创建并启动解码渲染链，触发协商。
     */
    public void play() {
        buildPipeline();
        setupPadAddedLinking();
        setupAppSinkCallback();
        pipeline.setState(State.PLAYING);
        // 让连接管理器添加视频收发器，触发 ON_NEGOTIATION_NEEDED → Offer → SRS 协商
        try { conn.addVideoTransceiver(); } catch (Throwable ignore) {}
    }

    /** 停止播放并释放资源 */
    public void stop() {
        try {
            if (pipeline != null) {
                pipeline.setState(State.NULL);
            }
        } catch (Throwable ignore) {}
    }

    private void buildPipeline() {
        // 创建元素
        pipeline = new Pipeline("endcamera-pipeline");
        rtph264depay = ElementFactory.make("rtph264depay", "depay");
        h264parse = ElementFactory.make("h264parse", "parse");
        decoder = ElementFactory.make("avdec_h264", "decoder");
        queueDepay = ElementFactory.make("queue", "q_depay");
        queueDecode = ElementFactory.make("queue", "q_decode");
        converter = ElementFactory.make("videoconvert", "convert");
        capsfilter = ElementFactory.make("capsfilter", "cf_bgrx");
        appsink = (AppSink) ElementFactory.make("appsink", "appsink");

        // 元素配置：实时优先，稳定协商
        try {
            queueDepay.set("leaky", 1); // downstream
            queueDepay.set("max-size-buffers", 50);
            queueDepay.set("max-size-bytes", 0);
            queueDepay.set("max-size-time", 0L);
        } catch (Throwable ignore) {}
        try {
            queueDecode.set("leaky", 1);
            queueDecode.set("max-size-buffers", 50);
            queueDecode.set("max-size-bytes", 0);
            queueDecode.set("max-size-time", 0L);
        } catch (Throwable ignore) {}
        try { h264parse.set("config-interval", 1); } catch (Throwable ignore) {}
        try { h264parse.set("output-format", "byte-stream"); } catch (Throwable ignore) {}
        try { h264parse.set("disable-passthrough", true); } catch (Throwable ignore) {}
        try { decoder.set("threads", 1); } catch (Throwable ignore) {}
        try { decoder.set("skip-frame", 0); } catch (Throwable ignore) {}
        try { decoder.set("discard-corrupted-frames", false); } catch (Throwable ignore) {}
        try { decoder.set("output-corrupt", false); } catch (Throwable ignore) {}
        try { capsfilter.set("caps", Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full")); } catch (Throwable ignore) {}
        try {
            appsink.set("emit-signals", true);
            appsink.set("sync", false);
            appsink.set("qos", true);
            appsink.set("max-buffers", Integer.getInteger("diag.sink.maxBuffers", 4));
            appsink.set("drop", Boolean.parseBoolean(System.getProperty("diag.sink.drop", "true")));
            appsink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
        } catch (Throwable ignore) {}
        try { rtph264depay.set("request-keyframe", true); } catch (Throwable ignore) {}
        try { rtph264depay.set("wait-for-keyframe", false); } catch (Throwable ignore) {}

        // 组装到管道并链接
        pipeline.addMany(webrtcbin, rtph264depay, h264parse, queueDepay, decoder, queueDecode, converter, capsfilter, appsink);
        Element.linkMany(rtph264depay, h264parse, queueDepay, decoder, queueDecode, converter, capsfilter, appsink);
    }

    /**
     * 在 webrtcbin 的 pad-added 中，将视频RTP pad 连接到 depay.sink。
     */
    private void setupPadAddedLinking() {
        webrtcbin.connect(new Element.PAD_ADDED() {
            @Override
            public void padAdded(Element element, Pad pad) {
                Caps capsObj = pad.getCurrentCaps();
                if (capsObj == null) capsObj = pad.queryCaps(null);
                int size = -1;
                try { size = (capsObj != null) ? capsObj.size() : -1; } catch (Throwable ignore) {}
                if (capsObj == null || size <= 0) return;
                String capsStr = capsObj.toString().toLowerCase();
                boolean isRtp = capsStr.contains("application/x-rtp");
                boolean isVideo = capsStr.contains("media=(string)video") || capsStr.contains("media=video");
                boolean isH264 = capsStr.contains("h264") || capsStr.contains("encoding-name=(string)h264");
                boolean isFecRedRtx = capsStr.contains("rtx") || capsStr.contains("red") || capsStr.contains("ulpfec");
                if (!(isRtp && isVideo && isH264) || isFecRedRtx) return;
                Pad sinkPad = rtph264depay.getStaticPad("sink");
                if (sinkPad != null && !sinkPad.isLinked()) {
                    try { pad.link(sinkPad); } catch (PadLinkException ignore) {}
                }
            }
        });
    }

    /**
     * 设置 AppSink 回调，安全地映射、转换并写入 JavaFX 图像。
     */
    private void setupAppSinkCallback() {
        if (appsink == null) return;
        appsink.connect(new AppSink.NEW_SAMPLE() {
            @Override
            public FlowReturn newSample(AppSink elem) {
                Sample sample = elem.pullSample();
                if (sample != null) {
                    processVideoFrame(sample);
                    sample.dispose();
                }
                return FlowReturn.OK;
            }
        });
    }

    private void processVideoFrame(Sample sample) {
        try {
            Buffer buffer = sample.getBuffer();
            Caps caps = sample.getCaps();
            if (caps == null) return;
            int capsSize;
            try { capsSize = caps.size(); } catch (Throwable t) { return; }
            if (capsSize <= 0) return;
            Structure s;
            try { s = caps.getStructure(0); } catch (Throwable t) { return; }
            if (s == null) return;
            int width = s.getInteger("width");
            int height = s.getInteger("height");
            String format = null;
            try { format = s.getString("format"); } catch (Throwable ignore) {}

            ByteBuffer bb;
            try { bb = buffer != null ? buffer.map(false) : null; } catch (Throwable t) { return; }
            if (bb == null) return;

            boolean isBGRx = "BGRx".equalsIgnoreCase(format) || "BGRA".equalsIgnoreCase(format) || "ARGB".equalsIgnoreCase(format);
            int bpp = isBGRx ? 4 : 3;
            int expected = width * height * bpp;
            int actual = bb.remaining();
            if (actual < expected) { try { buffer.unmap(); } catch (Throwable ignore) {} return; }

            byte[] data = new byte[Math.min(expected, actual)];
            try { bb.get(data, 0, data.length); } catch (Throwable t) { try { buffer.unmap(); } catch (Throwable ignore) {} return; }

            Platform.runLater(() -> {
                try {
                    if (sharedImage == null || sharedImageWidth != width || sharedImageHeight != height) {
                        sharedImage = new WritableImage(width, height);
                        sharedImageWidth = width;
                        sharedImageHeight = height;
                        imageView.setImage(sharedImage);
                    }
                    if (isBGRx) {
                        int srcRowStride = Math.max(width * 4, data.length / Math.max(1, height));
                        sharedImage.getPixelWriter().setPixels(0, 0, width, height,
                                PixelFormat.getByteBgraPreInstance(), data, 0, srcRowStride);
                    } else {
                        int rowStride = Math.max(width * 3, data.length / Math.max(1, height));
                        sharedImage.getPixelWriter().setPixels(0, 0, width, height,
                                PixelFormat.getByteRgbInstance(), data, 0, rowStride);
                    }
                } catch (Throwable ignore) {}
            });

            try { buffer.unmap(); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
    }
}