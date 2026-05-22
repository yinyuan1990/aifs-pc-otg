package com.acard.acard;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

public class GstTestView extends StackPane {
    private final Canvas canvas = new Canvas(640, 360);
    private final Pipeline pipe;

    public GstTestView() {
        getChildren().add(canvas);

        pipe = new Pipeline("test-pipe");
        Element src  = ElementFactory.make("videotestsrc", "src");
        Element conv = ElementFactory.make("videoconvert", "conv");
        AppSink sink = (AppSink) ElementFactory.make("appsink", "sink");

        sink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
        sink.set("emit-signals", true);
        sink.set("max-buffers", 1);
        sink.set("drop", true);

        sink.connect((AppSink.NEW_SAMPLE) elem -> {
            Sample sample = sink.pullSample();
            if (sample == null) {
                return FlowReturn.OK;
            }
            Buffer buffer = sample.getBuffer();
            if (buffer == null) {
                sample.dispose();
                return FlowReturn.OK;
            }
            Caps caps = sample.getCaps();
            if (caps == null) {
                sample.dispose();
                return FlowReturn.OK;
            }
            Structure s = null;
            try {
                s = caps.getStructure(0);
            } catch (Throwable t) {
                // 忽略异常，避免触发原生断言
            }
            if (s == null) {
                sample.dispose();
                return FlowReturn.OK;
            }
            int w = s.hasField("width") ? s.getInteger("width") : 0;
            int h = s.hasField("height") ? s.getInteger("height") : 0;
            if (w <= 0 || h <= 0) {
                sample.dispose();
                return FlowReturn.OK;
            }

            // ✅ 这版 API：map() 直接给 ByteBuffer
            ByteBuffer bb = buffer.map(false); // false = 只读
            if (bb == null) {
                sample.dispose();
                return FlowReturn.OK;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] dst = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int stride = w * 4; // B,G,R,x

            for (int y = 0; y < h; y++) {
                int rowStart = y * stride;
                for (int x = 0; x < w; x++) {
                    int b = bb.get(rowStart + x * 4) & 0xFF;
                    int g = bb.get(rowStart + x * 4 + 1) & 0xFF;
                    int r = bb.get(rowStart + x * 4 + 2) & 0xFF;
                    dst[y * w + x] = (r << 16) | (g << 8) | b;
                }
            }
            buffer.unmap(); // ✅ 别忘了

            Image fxImg = SwingFXUtils.toFXImage(img, null);
            Platform.runLater(() -> {
                var gc = canvas.getGraphicsContext2D();
                gc.drawImage(fxImg, 0, 0, canvas.getWidth(), canvas.getHeight());
            });
            sample.dispose();
            return FlowReturn.OK;
        });


        pipe.addMany(src, conv, sink);
        Element.linkMany(src, conv, sink);
        pipe.play();
    }
}
