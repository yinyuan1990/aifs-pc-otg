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

public class GstHttpFlvView extends StackPane {
    private final Canvas canvas = new Canvas(1280, 720);
    private final Pipeline pipe = new Pipeline("httpflv-pipe");

    public GstHttpFlvView(String flvUrl) {
        getChildren().add(canvas);

        Element src    = ElementFactory.make("souphttpsrc", "src");
        src.set("location", flvUrl);
        src.set("is-live", true);
        src.set("do-timestamp", true);

        Element demux  = ElementFactory.make("flvdemux", "demux");
        Element vqueue = ElementFactory.make("queue", "vqueue");
        Element hparse = ElementFactory.make("h264parse", "hparse");   // 显式走 H.264，减少犹豫
        Element vdec   = ElementFactory.make("avdec_h264", "vdec");    // 软解，兼容性最好
        Element vconv  = ElementFactory.make("videoconvert", "vconv");
        AppSink sink   = (AppSink) ElementFactory.make("appsink", "sink");

        sink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
        sink.set("emit-signals", true);
        sink.set("max-buffers", 1);
        sink.set("drop", true);

        // flvdemux 的 pad 动态链接到 vqueue
        demux.connect((Element.PAD_ADDED) (elem, pad) -> {
            if (pad.getCurrentCaps().toString().startsWith("video/")) {
                Pad sinkPad = vqueue.getStaticPad("sink");
                if (!sinkPad.isLinked()) pad.link(sinkPad);
            }
        });

        sink.connect((AppSink.NEW_SAMPLE) elem -> {

            System.out.println("join--->");
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

            ByteBuffer bb = buffer.map(false); // 你的绑定：map() 直接给 ByteBuffer
            if (bb == null) {
                sample.dispose();
                return FlowReturn.OK;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] dst = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            int stride = w * 4; // B,G,R,x
            for (int y = 0; y < h; y++) {
                int row = y * stride;
                for (int x = 0; x < w; x++) {
                    int b = bb.get(row + x*4) & 0xFF;
                    int g = bb.get(row + x*4 + 1) & 0xFF;
                    int r = bb.get(row + x*4 + 2) & 0xFF;
                    dst[y*w + x] = (r<<16) | (g<<8) | b;
                }
            }
            buffer.unmap();

            Image fxImg = SwingFXUtils.toFXImage(img, null);
            Platform.runLater(() -> canvas.getGraphicsContext2D()
                    .drawImage(fxImg, 0, 0, canvas.getWidth(), canvas.getHeight()));
            sample.dispose();
            return FlowReturn.OK;
        });

        // 组链：src → demux → vqueue → h264parse → avdec_h264 → videoconvert → appsink
        pipe.addMany(src, demux, vqueue, hparse, vdec, vconv, sink);
        Element.linkMany(src, demux);
        Element.linkMany(vqueue, hparse, vdec, vconv, sink);

        pipe.play();
    }
}

