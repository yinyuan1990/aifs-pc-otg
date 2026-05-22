package com.acard.acard.pipeline;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import com.acard.acard.webrtc.WebRTCConnectionManager;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayList;
import java.util.List;

/**
 * GStreamer管道构建器
 * 负责构建和管理GStreamer pipeline及其元素
 */
public class GStreamerPipelineBuilder {
    
    private final Pipeline pipeline;
    private final List<Element> elements = new ArrayList<>();
    
    // 主要元素
    private Element jitterBuffer;
    private Element depayloader;
    private Element parser;
    private Element tee;
    private Element decoder;
    private Element converter;
    private Element videoflip;  // 新增：视频旋转元素
    private Element videoRaw;
    private Element tee2;
    private AppSink displaySink;
    private AppSink captureSink;
    private AppSink slowSink;
    
    // 控制元素
    private Element captureValve;
    private Element slowValveDisk;
    private Element slowPreValveDisk;
    private Element slowPreValveMem;
    
    public GStreamerPipelineBuilder(String pipelineName) {
        this.pipeline = new Pipeline(pipelineName);
        System.err.println("🔧 创建GStreamer管道: " + pipelineName);
    }
    
    /**
     * 构建完整的WebRTC接收管道
     */
    public void buildWebRTCReceivePipeline(WebRTCConnectionManager webrtcManager) {
        try {
            System.err.println("🔧 开始构建WebRTC接收管道...");
            
            // 添加WebRTC bin到管道
            pipeline.add(webrtcManager.getWebRTCBin());
            
            // 创建显示sink
            createDisplaySink();
            
            // 添加显示sink到管道
            pipeline.add(displaySink);
            
            System.err.println("✅ WebRTC接收管道构建完成");
            
        } catch (Exception e) {
            System.err.println("❌ 构建WebRTC接收管道失败: " + e.getMessage());
            throw new RuntimeException("构建WebRTC接收管道失败", e);
        }
    }
    
    /**
     * 处理新的pad连接
     */
    public void processPadAdded(Pad newPad, String caps) {
        try {
            System.err.println("🔗 处理新pad: " + newPad.getName() + ", caps: " + caps);
            
            String capsLower = caps.toLowerCase();
            
            if (capsLower.contains("video") && capsLower.contains("h264")) {
                System.err.println("📹 检测到H.264视频流，构建视频处理链");
                buildVideoProcessingChain(newPad);
            } else {
                System.err.println("⚠️ 未知的媒体类型，跳过: " + caps);
            }
            
        } catch (Exception e) {
            System.err.println("❌ 处理pad失败: " + e.getMessage());
            throw new RuntimeException("处理pad失败", e);
        }
    }
    

    /**
     * 创建jitter buffer
     */
    private void createJitterBuffer() {
        try {
            jitterBuffer = ElementFactory.make("rtpjitterbuffer", "jb");
            if (jitterBuffer == null) {
                throw new RuntimeException("无法创建rtpjitterbuffer元素");
            }
            
            // 配置jitter buffer
            jitterBuffer.set("latency", 100);
            jitterBuffer.set("drop-on-latency", true);
            
            elements.add(jitterBuffer);
            System.err.println("✅ JitterBuffer创建成功");
        } catch (Exception e) {
            System.err.println("❌ 创建JitterBuffer失败: " + e.getMessage());
            throw new RuntimeException("创建JitterBuffer失败", e);
        }
    }
    
    /**
     * 创建depayloader
     */
    private void createDepayloader() {
        try {
            depayloader = ElementFactory.make("rtph264depay", "depay");
            if (depayloader == null) {
                throw new RuntimeException("无法创建rtph264depay元素");
            }
            
            elements.add(depayloader);
            System.err.println("✅ Depayloader创建成功");
        } catch (Exception e) {
            System.err.println("❌ 创建Depayloader失败: " + e.getMessage());
            throw new RuntimeException("创建Depayloader失败", e);
        }
    }
    
    /**
     * 创建parser
     */
    private void createParser() {
        try {
            parser = ElementFactory.make("h264parse", "parse");
            if (parser == null) {
                throw new RuntimeException("无法创建h264parse元素");
            }
            
            // 配置parser
            parser.set("config-interval", -1);
            
            elements.add(parser);
            System.err.println("✅ Parser创建成功");
        } catch (Exception e) {
            System.err.println("❌ 创建Parser失败: " + e.getMessage());
            throw new RuntimeException("创建Parser失败", e);
        }
    }
    
    /**
     * 创建tee分流器
     */
    private void createTee() {
        tee = ElementFactory.make("tee", "tee");
        if (tee == null) {
            throw new RuntimeException("无法创建tee");
        }
        
        elements.add(tee);
        System.err.println("✅ 创建tee分流器");
    }
    
    /**
     * 创建解码器
     */
    private void createDecoder() {
        // 优先尝试硬件解码器，其次 NVIDIA NVDEC，最后回退到软件解码器
        Element hwDec = ElementFactory.make("d3d11h264dec", "dec");
        if (hwDec == null) {
            hwDec = ElementFactory.make("nvh264dec", "dec");
        }
        if (hwDec == null) {
            // 回退到软件解码器
            hwDec = ElementFactory.make("avdec_h264", "dec");
            if (hwDec == null) {
                throw new RuntimeException("无法创建任何H.264解码器");
            }
            // 仅在软件解码器时设置线程参数
            hwDec.set("max-threads", 4);
            hwDec.set("output-corrupt", false);
            System.err.println("✅ 创建软件H.264解码器: avdec_h264");
        } else {
            System.err.println("✅ 创建硬件H.264解码器: " + hwDec.getName());
        }
        decoder = hwDec;
        elements.add(decoder);
    }

    /**
     * 创建转换器
     */
    private void createConverter() {
        // 优先使用GPU颜色转换，其次回退到CPU的videoconvert
        Element conv = ElementFactory.make("d3d11convert", "conv");
        if (conv == null) {
            conv = ElementFactory.make("videoconvert", "conv");
            if (conv == null) {
                throw new RuntimeException("无法创建视频转换器");
            }
            System.err.println("✅ 创建CPU视频转换器: videoconvert");
        } else {
            System.err.println("✅ 创建GPU视频转换器: d3d11convert");
        }
        converter = conv;
        elements.add(converter);
    }

    /**
     * 创建videoflip元素
     */
    private void createVideoFlip() {
        videoflip = ElementFactory.make("videoflip", "videoflip");
        if (videoflip == null) {
            throw new RuntimeException("无法创建videoflip");
        }
        
        // 设置默认旋转方法为none（不旋转）
        videoflip.set("method", 0);  // 0 = none, 1 = 90°顺时针, 2 = 180°, 3 = 90°逆时针
        
        elements.add(videoflip);
        System.err.println("✅ 创建videoflip元素");
    }

    /**
     * 创建GPU下载器（仅在使用d3d11convert时需要，将GPU帧下载为system-memory）
     */
    private void createDownloader() {
        // 仅当转换器是d3d11convert时才尝试创建d3d11download
        if (converter != null && "d3d11convert".equals(converter.getFactory().getName())) {
            Element dl = ElementFactory.make("d3d11download", "dl");
            if (dl == null) {
                // 如果下载器不可用，继续走原有路径（可能会由videoconvert完成到system-memory的转换）
                System.err.println("⚠️ d3d11download不可用，跳过GPU下载步骤");

                return;
            }

            System.err.println("✅ 创建GPU下载器: d3d11download");
        } else {
        }
    }

    /**
     * 构建视频处理链
     */
    private void buildVideoProcessingChain(Pad srcPad) {
        try {
            // 创建jitter buffer
            createJitterBuffer();
            
            // 创建depayloader
            createDepayloader();
            
            // 创建parser
            createParser();
            
            // 创建tee分流器
            createTee();
            
            // 创建解码器
            createDecoder();
            
            // 创建转换器
            createConverter();
            
            // 创建videoflip元素
            createVideoFlip();
            
            // 新增：在GPU转换后将帧下载到system-memory（以便Java消费）
            createDownloader();
            
            // 创建video raw tee
            createVideoRawTee();
            
            // 创建capture和慢动作sinks
            createCaptureSinks();
            
            // 添加所有元素到管道
            addElementsToPipeline();
            
            // 链接元素
            linkVideoElements();
            
            // 连接源pad到jitter buffer
            linkPadToJitterBuffer(srcPad);
            
            // 同步状态
            syncElementStates();
            
            System.err.println("✅ 视频处理链构建完成");
            
        } catch (Exception e) {
            System.err.println("❌ 构建视频处理链失败: " + e.getMessage());
            throw new RuntimeException("构建视频处理链失败", e);
        }
    }
    

    
    /**
     * 创建video raw tee
     */
    private void createVideoRawTee() {
        videoRaw = ElementFactory.make("identity", "vraw");
        if (videoRaw == null) {
            throw new RuntimeException("无法创建identity");
        }
        
        tee2 = ElementFactory.make("tee", "tee2");
        if (tee2 == null) {
            throw new RuntimeException("无法创建tee2");
        }
        
        elements.add(videoRaw);
        elements.add(tee2);
        System.err.println("✅ 创建video raw处理元素");
    }
    
    /**
     * 创建显示sink
     */
    private void createDisplaySink() {
        try {
            displaySink = (AppSink) ElementFactory.make("appsink", "display-sink");
            if (displaySink == null) {
                throw new RuntimeException("无法创建display appsink元素");
            }
            
            // 配置显示sink
            displaySink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
            displaySink.set("drop", false);
            displaySink.set("max-buffers", 30);
            displaySink.set("qos", true);
            displaySink.set("sync", false);
            displaySink.set("async", false);
            
            elements.add(displaySink);
            System.err.println("✅ DisplaySink创建成功");
        } catch (Exception e) {
            System.err.println("❌ 创建DisplaySink失败: " + e.getMessage());
            throw new RuntimeException("创建DisplaySink失败", e);
        }
    }
    
    /**
     * 创建捕获和慢动作sinks
     */
    private void createCaptureSinks() {
        // 创建捕获sink
        captureSink = (AppSink) ElementFactory.make("appsink", "capture-sink");
        if (captureSink != null) {
            captureSink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
            captureSink.set("drop", true);
            captureSink.set("max-buffers", 10);
            captureSink.set("sync", false);
            captureSink.set("async", false);
            elements.add(captureSink);
        }
        
        // 创建慢动作sink
        slowSink = (AppSink) ElementFactory.make("appsink", "slow-sink");
        if (slowSink != null) {
            slowSink.setCaps(Caps.fromString("video/x-raw,format=BGRA,colorimetry=bt709,color-range=full"));
            slowSink.set("drop", true);
            slowSink.set("max-buffers", 5);
            slowSink.set("sync", false);
            slowSink.set("async", false);
            elements.add(slowSink);
        }
        
        // 创建控制阀门
        captureValve = ElementFactory.make("valve", "cap-valve");
        if (captureValve != null) {
            captureValve.set("drop", true); // 默认关闭
            elements.add(captureValve);
        }
        
        slowPreValveMem = ElementFactory.make("valve", "slow-pre-valve-mem");
        if (slowPreValveMem != null) {
            slowPreValveMem.set("drop", false); // 默认开启
            elements.add(slowPreValveMem);
        }
        
        System.err.println("✅ 创建捕获和慢动作sinks");
    }
    
    /**
     * 添加所有元素到管道
     */
    private void addElementsToPipeline() {
        try {
            for (Element element : elements) {
                pipeline.add(element);
            }
            System.err.println("✅ 所有元素已添加到管道");
        } catch (Exception e) {
            System.err.println("❌ 添加元素到管道失败: " + e.getMessage());
            throw new RuntimeException("添加元素到管道失败", e);
        }
    }
    
    /**
     * 链接视频元素
     */
    private void linkVideoElements() {
        try {
            // 主处理链: jb -> depay -> parse -> tee
            if (!jitterBuffer.link(depayloader)) {
                throw new RuntimeException("链接jitterBuffer到depayloader失败");
            }
            
            if (!depayloader.link(parser)) {
                throw new RuntimeException("链接depayloader到parser失败");
            }
            
            if (!parser.link(tee)) {
                throw new RuntimeException("链接parser到tee失败");
            }
            
            // 解码分支: tee -> dec -> [conv] -> [download] -> vraw -> tee2
            Pad teeSrcPad = tee.getRequestPad("src_%u");
            Pad decSinkPad = decoder.getStaticPad("sink");
            if (teeSrcPad != null && decSinkPad != null) {
                teeSrcPad.link(decSinkPad);
            }

            if (!decoder.link(converter)) {
                throw new RuntimeException("链接decoder到converter失败");
            }

            // 链接converter -> videoflip -> videoRaw
            if (!converter.link(videoflip)) {
                throw new RuntimeException("链接converter到videoflip失败");
            }
            
            if (!videoflip.link(videoRaw)) {
                throw new RuntimeException("链接videoflip到videoRaw失败");
            }
            
            if (!videoRaw.link(tee2)) {
                throw new RuntimeException("链接videoRaw到tee2失败");
            }
            
            // 显示分支: tee2 -> sink
            Pad tee2SrcPad = tee2.getRequestPad("src_%u");
            Pad sinkPad = displaySink.getStaticPad("sink");
            if (tee2SrcPad != null && sinkPad != null) {
                tee2SrcPad.link(sinkPad);
            }
            
            // 捕获分支
            if (captureValve != null && captureSink != null) {
                Pad tee2CapturePad = tee2.getRequestPad("src_%u");
                Pad captureValveSink = captureValve.getStaticPad("sink");
                if (tee2CapturePad != null && captureValveSink != null) {
                    tee2CapturePad.link(captureValveSink);
                    captureValve.link(captureSink);
                }
            }
            
            // 慢动作分支
            if (slowPreValveMem != null && slowSink != null) {
                Pad tee2SlowPad = tee2.getRequestPad("src_%u");
                Pad slowValveSink = slowPreValveMem.getStaticPad("sink");
                if (tee2SlowPad != null && slowValveSink != null) {
                    tee2SlowPad.link(slowValveSink);
                    slowPreValveMem.link(slowSink);
                }
            }
            
            System.err.println("✅ 视频元素链接完成");
            
        } catch (Exception e) {
            System.err.println("❌ 链接视频元素失败: " + e.getMessage());
            throw new RuntimeException("链接视频元素失败", e);
        }
    }
    
    /**
     * 将源pad连接到jitter buffer
     */
    private void linkPadToJitterBuffer(Pad srcPad) {
        try {
            if (jitterBuffer != null) {
                Pad sinkPad = jitterBuffer.getStaticPad("sink");
                if (sinkPad != null && !sinkPad.isLinked()) {
                    try {
                        srcPad.link(sinkPad);
                        System.err.println("✅ 源pad成功连接到jitter buffer");
                    } catch (PadLinkException e) {
                        System.err.println("❌ 连接到jitter buffer失败: " + e.getMessage());
                        throw new RuntimeException("连接到jitter buffer失败: " + e.getMessage());
                    }
                } else {
                    System.err.println("⚠️ JitterBuffer sink pad不可用或已连接");
                }
            } else {
                System.err.println("❌ JitterBuffer未创建");
                throw new RuntimeException("JitterBuffer未创建");
            }
        } catch (Exception e) {
            System.err.println("❌ 连接pad到JitterBuffer失败: " + e.getMessage());
            throw new RuntimeException("连接pad到JitterBuffer失败", e);
        }
    }
    
    /**
     * 同步元素状态
     */
    private void syncElementStates() {
        try {
            for (Element element : elements) {
                element.syncStateWithParent();
            }
            System.err.println("✅ 元素状态同步完成");
        } catch (Exception e) {
            System.err.println("❌ 同步元素状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 启动管道
     */
    public void play() {
        try {
            StateChangeReturn result = pipeline.play();
            if (result == StateChangeReturn.FAILURE) {
                throw new RuntimeException("管道启动失败");
            }
            System.err.println("✅ 管道启动成功");
        } catch (Exception e) {
            System.err.println("❌ 启动管道失败: " + e.getMessage());
            throw new RuntimeException("启动管道失败", e);
        }
    }
    
    /**
     * 停止管道
     */
    public void stop() {
        try {
            pipeline.setState(State.NULL);
            System.err.println("✅ 管道已停止");
        } catch (Exception e) {
            System.err.println("❌ 停止管道失败: " + e.getMessage());
        }
    }
    
    // Getters
    public Pipeline getPipeline() { return pipeline; }
    public AppSink getDisplaySink() { return displaySink; }
    public AppSink getCaptureSink() { return captureSink; }
    public AppSink getSlowSink() { return slowSink; }
    public Element getJitterBuffer() { return jitterBuffer; }
    public Element getDepayloader() { return depayloader; }
    public Element getParser() { return parser; }
    public Element getTee() { return tee; }
    public Element getDecoder() { return decoder; }
    public Element getConverter() { return converter; }
    public Element getVideoFlip() { return videoflip; }
    public Element getVideoRaw() { return videoRaw; }
    public Element getTee2() { return tee2; }
    public Element getCaptureValve() { return captureValve; }
    public Element getSlowPreValveMem() { return slowPreValveMem; }
    
    /**
     * 控制捕获阀门
     */
    public void setCaptureEnabled(boolean enabled) {
        if (captureValve != null) {
            captureValve.set("drop", !enabled);
            System.err.println("捕获阀门: " + (enabled ? "开启" : "关闭"));
        }
    }
    
    /**
     * 控制慢动作阀门
     */
    public void setSlowMotionEnabled(boolean enabled) {
        if (slowPreValveMem != null) {
            slowPreValveMem.set("drop", !enabled);
            System.err.println("慢动作阀门: " + (enabled ? "开启" : "关闭"));
        }
    }
    
    /**
     * 关闭管道构建器
     */
    public void shutdown() {
        stop();
        elements.clear();
        System.err.println("GStreamer管道构建器已关闭");
    }
}


    

