package com.acard.acard;

import com.acard.acard.capture.DiskCaptureCache;
import com.acard.acard.capture.LightweightFrameBuffer;
import com.acard.acard.capture.TimelineCapture;
import com.acard.acard.store.CaptureStore;
import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.scene.image.Image;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.webrtc.WebRTCBin;
import org.freedesktop.gstreamer.webrtc.WebRTCSessionDescription;
import org.freedesktop.gstreamer.GstObject;
import com.acard.acard.net.NetworkConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ✅ RecordOnlyPlayer - 纯录制播放器
 * 
 * 关键改动（相比默认配置）：
 * 1. matroskamux: min-cluster-duration=100ms（支持边录边读）
 * 2. filesink: sync=false, async=true（不与时钟强同步）
 * 3. 删除buffer-size=0（使用默认缓冲）
 * 4. 删除per-frame flush（让muxer正常聚合cluster）
 */
public class RecordOnlyPlayer {

    private final String serverHost;
    private final int serverPort;
    private final String tenant;
    private final String streamId;
    
    private Pipeline pipeline;
    private WebRTCBin webrtcbin;
    private Element rtph264depay;
    private Element h264parse;
    private Element mp4mux;  // 实际是matroskamux
    private Element filesink;
    private Element queueDepay;
    private Element queueMux;
    
    private boolean isRecording = false;
    private String recordOutputPath;
    private PrintWriter recordLogger;

    private PrintWriter recordLogger2;
    private int maxRecordFrames = 3000;
    private int recordedFrameCount = 0;
    
    private java.util.function.Consumer<Integer> frameCountCallback;
    private Runnable recordingCompleteCallback;
    private String currentStreamUrl;



    // ========== 抓拍功能相关字段 ==========
    private Element captureTee;           // 分流器：将视频流分为录制分支和抓拍分支
    private Element captureQueue;         // 抓拍队列
    private Element captureDownload;      // GPU数据下载（如果使用GPU解码）

    private Element captureDecoder;       // 抓拍解码器（CPU解码）
    private Element captureConverter;     // 抓拍格式转换器
    private Element captureCapsFilter;    // 抓拍格式过滤器



    // ✨ 全局帧ID计数器（每一帧都会递增，用于事件驱动的帧分发）






    private boolean enableCaptureFeature=true;
    // 替换现有的 captureValve, jpegEncoder, multifilesink
    private Element captureValve;        // 控制阀门
    private Element captureImageQueue;   // JPEG保存队列
    private Element captureImageDecoder; // JPEG分支解码器
    private Element captureImageConvert; // 视频格式转换
    private Element jpegEncoder;         // JPEG编码器
    private Element multifilesink;       // 多文件保存

    // 添加 valve 元素
    Element recordValve;


    public void setFrameCountCallback(java.util.function.Consumer<Integer> callback) {
        this.frameCountCallback = callback;
    }
    
    public void setRecordingCompleteCallback(Runnable callback) {
        this.recordingCompleteCallback = callback;
    }
    
    public void setMaxRecordFrames(int maxFrames) {
        this.maxRecordFrames = maxFrames;
    }
    
    public RecordOnlyPlayer(String serverHost, int serverPort, String tenant, String streamId) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.tenant = tenant;
        this.streamId = streamId;
        
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/slowmo/RecordOnlyPlayer_" + timestamp + ".txt";
            
            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            recordLogger = new PrintWriter(new FileWriter(logFile, true));

            String logPath2 = "runtime/slowmo/ZPai_" + timestamp + ".txt";
            File logFile2 = new File(logPath2);
            File parentDir2 = logFile2.getParentFile();
            if (parentDir2 != null && !parentDir2.exists()) {
                parentDir2.mkdirs();
            }

            recordLogger2  = new PrintWriter(new FileWriter(logFile2, true));

            logRecord("========== RecordOnlyPlayer初始化 ==========");
            logRecord("服务器: " + serverHost + ":" + serverPort);
            logRecord("租户: " + tenant);
            logRecord("流ID: " + streamId);
        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
        // ========== 初始化抓拍功能 ==========
        //initializeCaptureFeature();
    }



    
    public void play() throws Exception {
        logRecord("\n🔧 ========== 初始化Pipeline ==========");
        
        try {
            Gst.init();
        } catch (Exception e) {
            logRecord("GStreamer已初始化");
        }
        
        try {
            // 创建Pipeline
            pipeline = new Pipeline("recordonly-pipeline");
            logRecord("✅ Pipeline创建成功");
            
            // 创建元素
            webrtcbin = (WebRTCBin) ElementFactory.make("webrtcbin", "webrtcbin0");
            rtph264depay = ElementFactory.make("rtph264depay", "depay");
            queueDepay = ElementFactory.make("queue", "queue_depay");
            h264parse = ElementFactory.make("h264parse", "h264parse");

            if (rtph264depay != null) {
                // ⭐ 关键配置：防止马赛克
                try {
                    rtph264depay.set("request-keyframe", true);    // 主动请求关键帧（I帧）
                    rtph264depay.set("wait-for-keyframe", true);   // 等待关键帧再开始输出（防止花屏）
                    logRecord("✅ rtph264depay 已配置防马赛克: request-keyframe=true, wait-for-keyframe=true");
                } catch (Exception e) {
                    logRecord("⚠️ rtph264depay 配置失败: " + e.getMessage());
                }
            }
            // ========== 抓拍分支元素 ==========
            if (enableCaptureFeature) {


                // 新增JPEG保存分支元素
                captureValve = ElementFactory.make("valve", "capture_valve");
                captureImageQueue = ElementFactory.make("queue", "capture_image_queue");
                captureImageDecoder = ElementFactory.make("avdec_h264", "capture_image_decoder");  // 添加解码器
                captureImageConvert = ElementFactory.make("videoconvert", "capture_image_convert");
                jpegEncoder = ElementFactory.make("jpegenc", "jpeg_encoder");
                multifilesink = ElementFactory.make("multifilesink", "multi_filesink");

                // 配置valve（默认开启JPEG保存）
                if (captureValve != null) {
                    captureValve.set("drop", false);  // 开启数据流通，允许JPEG保存
                    logRecord("✅ captureValve已配置: drop=false (JPEG保存已启用)");
                }

                // 配置队列


                if (captureImageQueue != null) {
                    captureImageQueue.set("max-size-buffers",60); // 5 → 60
                    captureImageQueue.set("leaky", 2);  // 保持丢弃旧帧
                    captureImageQueue.set("max-size-bytes", 0);
                    captureImageQueue.set("max-size-time", Long.getLong("queue.jpeg.max.time", 200_000_000L)); // 100ms
                    System.err.println("✅ captureImageQueue优化: max-buffers=60, max-time=100ms, leaky=2（支持60fps+）");
                }

                // 配置JPEG编码器
                if (jpegEncoder != null) {
                    jpegEncoder.set("quality", 85);
                    jpegEncoder.set("idct-method", 2); // 快速IDCT算法
                    logRecord("✅ jpegEncoder已配置: quality=100");
                }

                // 配置multifilesink
                if (multifilesink != null) {
                    // 确保目录存在
                    File captureDir = new File("runtime/captures/slow");
                    if (!captureDir.exists()) {
                        captureDir.mkdirs();
                        logRecord("✅ 创建captures目录: " + captureDir.getAbsolutePath());
                    }
                    multifilesink.set("location", "runtime/captures/slow/s_%05d.jpeg");
                    multifilesink.set("post-messages", true);
                    multifilesink.set("max-files", 300);  // 0表示无限制
                    logRecord("✅ multifilesink已配置: runtime/captures/slow/s_%05d.jpeg");
                }


                captureTee = ElementFactory.make("tee", "capture_tee");
                captureQueue = ElementFactory.make("queue", "capture_queue");

                captureDecoder = ElementFactory.make("avdec_h264", "capture_decoder");
                captureConverter = ElementFactory.make("videoconvert", "capture_converter");
                captureCapsFilter = ElementFactory.make("capsfilter", "capture_caps");


                // 配置抓拍队列
                if (captureQueue != null) {
                    captureQueue.set("max-size-buffers", 2);
                    captureQueue.set("leaky", 2); // 丢弃旧数据
                    logRecord2("✅ 抓拍队列配置: max-buffers=2, leaky=drop-old");
                }

                // 配置解码器
                if (captureDecoder != null) {
                    captureDecoder.set("output-corrupt", false);
                    logRecord2("✅ 抓拍解码器配置完成");
                }

                // 配置格式转换器的输出格式
                if (captureCapsFilter != null) {
                    Caps captureCaps = Caps.fromString("video/x-raw,format=BGRA");
                    captureCapsFilter.set("caps", captureCaps);
                    logRecord2("✅ 抓拍格式过滤器配置: BGRA格式");
                }

                logRecord2("✅ 抓拍分支元素创建完成");
            }

            recordValve = ElementFactory.make("valve", "record_valve");
            if (recordValve != null) {
                recordValve.set("drop", false);  // 默认关闭录制
                logRecord("✅ 创建 recordValve");
            }

            queueMux = ElementFactory.make("queue", "queue_mux");
            // ✅ 关键改动1：matroskamux + 最小延迟配置 + 强制flush
            mp4mux = ElementFactory.make("matroskamux", "mux");
            if (mp4mux != null) {
                mp4mux.set("streamable", true);
                mp4mux.set("writing-app", "Acard-SlowMotion");
                
                // ⚠️ 尝试不同的配置来强制实时写入
                try {
                    // 方案1：最小cluster时间
                    mp4mux.set("min-cluster-duration", 100L); // 0 = 尽可能小的cluster
                    logRecord("✅ 设置 min-cluster-duration=0");
                } catch (Exception e) {
                    logRecord("⚠️ 设置min-cluster-duration失败: " + e.getMessage());
                }
                
                try {
                    // 方案2：禁用cluster缓存
                    mp4mux.set("max-cluster-duration", 1000000000L); // 1秒最大cluster
                    logRecord("✅ 设置 max-cluster-duration=1s");
                } catch (Exception e) {
                    logRecord("⚠️ 设置max-cluster-duration失败: " + e.getMessage());
                }
                
                logRecord("✅ matroskamux配置: streamable=true, 实时写入模式");
            }


            queueDepay = ElementFactory.make("queue", "queue_depay");
            if (queueDepay != null) {
                queueDepay.set("max-size-buffers", 120);    // 缓冲30帧（1秒@30fps）
                queueDepay.set("max-size-bytes", 0);       // 不限制字节数
                queueDepay.set("max-size-time", 4000000000L);  // 1秒缓冲
                logRecord("✅ queueDepay 已配置");
            }

            queueMux = ElementFactory.make("queue", "queue_mux");
            if (queueMux != null) {
                queueMux.set("max-size-buffers", 120);
                queueMux.set("max-size-bytes", 0);
                queueMux.set("max-size-time", 4000000000L);
                logRecord("✅ queueMux 已配置");
            }
            filesink = ElementFactory.make("filesink", "sink");
            
            if (webrtcbin == null || rtph264depay == null || h264parse == null || mp4mux == null || filesink == null) {
                logRecord("❌ 创建元素失败:");
                logRecord("   webrtcbin: " + webrtcbin);
                logRecord("   rtph264depay: " + rtph264depay);
                logRecord("   h264parse: " + h264parse);
                logRecord("   matroskamux: " + mp4mux);
                logRecord("   filesink: " + filesink);
                throw new RuntimeException("创建GStreamer元素失败");
            }
            logRecord("✅ 所有元素创建成功");
            
            // 配置WebRTCBin
            webrtcbin.set("bundle-policy", 3);  // max-bundle
            logRecord("✅ WebRTCBin配置完成");
            
            // ⚠️ 注意：filesink的location必须在Pipeline启动前设置，运行时不能修改
            // 因此这里先设置一个占位路径，startRecording时会重新创建Pipeline
            String placeholderPath = "runtime/slowmo/placeholder.mkv";
            File placeholderFile = new File(placeholderPath);
            File placeholderDir = placeholderFile.getParentFile();
            if (placeholderDir != null && !placeholderDir.exists()) {
                placeholderDir.mkdirs();
            }
            filesink.set("location", placeholderPath);
            // ✅ 关键改动2：sync=false, async=true, buffer-mode=unbuffered
            filesink.set("sync", false);
            filesink.set("async", true);
            filesink.set("buffer-mode", 2);  // 2 = unbuffered (实时写入，不缓冲)
            
            // ⚠️ 尝试强制flush
            try {
                filesink.set("buffer-size", 0);  // 0 = 不缓冲，立即写入
                logRecord("✅ 设置 buffer-size=0 (强制立即写入)");
            } catch (Exception e) {
                logRecord("⚠️ 设置buffer-size失败: " + e.getMessage());
            }
            
            try {
                filesink.set("o-sync", true);  // 使用O_SYNC标志，强制同步写入
                logRecord("✅ 设置 o-sync=true (强制同步写入)");
            } catch (Exception e) {
                logRecord("⚠️ 设置o-sync失败: " + e.getMessage());
            }
            

            
            // 添加元素到pipeline

            //原
            //pipeline.addMany(webrtcbin, rtph264depay, queueDepay, h264parse, queueMux, mp4mux, filesink);
            //logRecord("✅ 所有元素已添加到pipeline");
            // 连接已知的静态pad
            //rtph264depay.link(queueDepay);
            //queueDepay.link(h264parse);
            //h264parse.link(queueMux);
            //queueMux.link(mp4mux);
            //mp4mux.link(filesink);
            logRecord("✅ 元素链接完成: rtph264depay → queue → h264parse → queue → matroskamux → filesink");
            //原

// 删除原来的 mp4mux 和 filesink
// Element mp4mux = ElementFactory.make("matroskamux", "mux");
// Element filesink = ElementFactory.make("filesink", "sink");

// ⭐ 改用 splitmuxsink
            Element splitmuxSink = ElementFactory.make("splitmuxsink", "splitmux");
            if (splitmuxSink != null) {
                // 配置片段时长（4秒）
                splitmuxSink.set("max-size-time", 4_000_000_000L);

                // 配置文件名模板
                String segmentPattern = "runtime/xslow/segments/segment_%05d.mkv";
                splitmuxSink.set("location", segmentPattern);

                // 配置 muxer
                splitmuxSink.set("muxer-factory", "matroskamux");

                // ⭐ 关键：设置最大文件数（0 = 无限制）
                try {
                    splitmuxSink.set("max-files", 0);  // 0 = 不限制文件数量
                    logRecord("✅ 设置 max-files=0（无限制）");
                } catch (Exception e) {
                    logRecord("⚠️ max-files 设置失败: " + e.getMessage());
                }

                // 请求关键帧
                splitmuxSink.set("send-keyframe-requests", true);

                // 异步完成
                try {
                    splitmuxSink.set("async-finalize", true);
                } catch (Exception e) {
                    logRecord("⚠️ async-finalize 不支持");
                }

                logRecord("✅ splitmuxsink 已创建");
                logRecord("   片段时长: 4秒");
                logRecord("   最大文件数: 无限制");
                logRecord("   文件模板: " + segmentPattern);
            }
            enableCaptureFeature=false;
            // 简化后的pipeline.addMany
            if (enableCaptureFeature) {
                pipeline.addMany(webrtcbin, rtph264depay, queueDepay, h264parse, queueMux, mp4mux, filesink);
               /* pipeline.addMany(webrtcbin, rtph264depay, queueDepay, h264parse, queueMux,
                        captureTee,
                        captureValve, captureImageQueue, captureImageDecoder, captureImageConvert, jpegEncoder, multifilesink,
                        mp4mux, filesink);*/
            } else {
                pipeline.addMany(webrtcbin, rtph264depay, queueDepay, h264parse, queueMux,splitmuxSink);// mp4mux, filesink);
            }


            logRecord("✅ 所有元素已添加到pipeline");



            // 替换原有的链接逻辑
            if (enableCaptureFeature && captureTee != null) {
                // 带抓拍分支的链接
                rtph264depay.link(queueDepay);
                queueDepay.link(h264parse);
                h264parse.link(captureTee);  // h264parse → tee

                // tee → 录制分支
                Pad teeSrcRecord = captureTee.getRequestPad("src_%u");
                Pad queueMuxSink = queueMux.getStaticPad("sink");
                if (teeSrcRecord != null && queueMuxSink != null) {
                    teeSrcRecord.link(queueMuxSink);
                    queueMux.link(mp4mux);
                    mp4mux.link(filesink);
                    logRecord2("✅ 录制分支已连接: tee → queueMux → matroskamux → filesink");
                }

               // tee → JPEG保存分支（新增）
                /*Pad teeSrcJpeg = captureTee.getRequestPad("src_%u");
                Pad captureValveSink = captureValve.getStaticPad("sink");
                if (teeSrcJpeg != null && captureValveSink != null) {
                    teeSrcJpeg.link(captureValveSink);
                    boolean queueToSinkLinked = Element.linkMany(captureValve, captureImageQueue, captureImageDecoder, captureImageConvert, jpegEncoder, multifilesink);
                    if(queueToSinkLinked){
                        logRecord2("✅ JPEG分支链接成功: captureValve → captureImageQueue → captureImageDecoder → captureImageConvert → jpegEncoder → multifilesink");
                    }else{
                        logRecord2("❌ jpeg分支链接失败（queue→sink失败）");
                    }
                }*/



                logRecord2("✅ 录制管道已链接（带抓拍分支）");
            } else {
                // 原有的直连逻辑
                rtph264depay.link(queueDepay);
                queueDepay.link(h264parse);
                h264parse.link(queueMux);

                queueMux.link(splitmuxSink);
                //queueMux.link(mp4mux);
                //mp4mux.link(filesink);
                logRecord("✅ 录制管道已链接: rtph264depay → queue → h264parse → queue → matroskamux → filesink");
            }
            //改

            // 在 queueMux 的输出端添加 probe
            Pad queueMuxSrc = queueMux.getStaticPad("src");
            if (queueMuxSrc != null) {
                long[] lastKeyFrameTime = {0};  // 记录上一个关键帧时间

                queueMuxSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    Buffer buffer = info.getBuffer();
                    if (buffer != null) {
                        long pts = buffer.getPresentationTimestamp();
                        boolean isKeyFrame = !buffer.getFlags().contains(BufferFlags.DELTA_UNIT);

                        if (isKeyFrame) {
                            long ptsMs = pts / 1_000_000;
                            long intervalMs = (pts - lastKeyFrameTime[0]) / 1_000_000;

                            // ⭐ 打印关键帧间隔
                            logRecord("🔑 关键帧: PTS=" + ptsMs + "ms, 距上一帧=" + intervalMs + "ms");

                            lastKeyFrameTime[0] = pts;


                        }
                    }
                    return PadProbeReturn.OK;
                });
                logRecord("✅ 关键帧监听已添加");
            }



            
            // ⚠️ 添加filesink输入端的probe，检查数据是否到达
            Pad filesinkSink = filesink.getStaticPad("sink");
            if (filesinkSink != null) {
                filesinkSink.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    if (isRecording) {
                        // 只记录前10个buffer
                        if (recordedFrameCount <= 10) {
                            logRecord("🔍 filesink收到数据: buffer #" + recordedFrameCount);
                        }
                    }
                    return PadProbeReturn.OK;
                });
                logRecord("✅ filesink probe已添加（用于诊断）");
            } else {
                logRecord("⚠️ 无法获取filesink的sink pad");
            }
            
            // 添加帧数统计probe（h264parse输出）
            Pad h264parseSrc = h264parse.getStaticPad("src");
            if (h264parseSrc != null) {


                h264parseSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    Caps caps = pad.getCurrentCaps();
                    if (caps != null && caps.size() > 0) {
                        Structure structure = caps.getStructure(0);
                        if (structure != null && structure.hasField("width") && structure.hasField("height")) {
                            FileToos.slowWidth = structure.getInteger("width");
                            FileToos.slowHight = structure.getInteger("height");

                        }
                    }
                    return PadProbeReturn.OK;
                });

                h264parseSrc.addProbe(PadProbeType.BUFFER, (pad, info) -> {
                    if (isRecording) {
                        recordedFrameCount++;

                        // 实时更新UI
                        if (frameCountCallback != null) {
                            frameCountCallback.accept(recordedFrameCount);
                        }
                        
                        // 每10帧检查一次文件大小（前100帧），之后每100帧检查一次
                        if ((recordedFrameCount <= 100 && recordedFrameCount % 10 == 0) || 
                            (recordedFrameCount > 100 && recordedFrameCount % 100 == 0)) {
                            long fileSize = 0;
                            if (recordOutputPath != null) {
                                File f = new File(recordOutputPath);
                                if (f.exists()) {
                                    fileSize = f.length();
                                }
                            }
                            logRecord("📊 已录制: " + recordedFrameCount + " / " + maxRecordFrames + " 帧，文件大小: " + (fileSize / 1024) + " KB");
                        }
                        
                        // 达到最大帧数，自动停止
                        if (recordedFrameCount >= maxRecordFrames) {
                            logRecord("\n⏹️ 已达到最大录制帧数: " + maxRecordFrames);
                            logRecord("🛑 自动停止录制...");
                            
                            new Thread(() -> {
                                try {
                                    Thread.sleep(100);
                                    stopRecording();
                                    logRecord("✅ 录制已自动停止");
                                    
                                    if (recordingCompleteCallback != null) {
                                        recordingCompleteCallback.run();
                                    }
                                } catch (Exception e) {
                                    logRecord("❌ 自动停止失败: " + e.getMessage());
                                }
                            }, "AutoStopRecording").start();
                        }
                    }
                    return PadProbeReturn.OK;
                });
                logRecord("✅ 帧数统计probe已添加");
            }
            
            // WebRTCBin pad-added回调
            webrtcbin.connect(new Element.PAD_ADDED() {
                @Override
                public void padAdded(Element element, Pad pad) {
                    String padName = pad.getName();
                    logRecord("🔍 WebRTCBin新增pad: " + padName);
                    
                    try {
                        Caps caps = pad.getCurrentCaps();
                        if (caps != null) {
                            logRecord("   Caps: " + caps.toString());
                        }
                    } catch (Exception e) {
                        logRecord("   无法获取Caps");
                    }
                    
                    // 连接到rtph264depay（修改：匹配src_开头的pad）
                    if (padName.startsWith("src_") || padName.startsWith("recv_rtp_src_")) {
                        logRecord("✅ 检测到RTP视频pad: " + padName);
                        Pad depaySink = rtph264depay.getStaticPad("sink");
                        if (depaySink != null && !depaySink.isLinked()) {
                            try {
                                pad.link(depaySink);
                                logRecord("✅ webrtcbin → rtph264depay 已连接");
                            } catch (Exception e) {
                                logRecord("❌ Pad连接异常: " + e.getMessage());
                                if (recordLogger != null) {
                                    e.printStackTrace(recordLogger);
                                }
                            }
                        } else {
                            if (depaySink == null) {
                                logRecord("❌ rtph264depay的sink pad为null");
                            } else {
                                logRecord("⚠️ rtph264depay的sink pad已连接");
                            }
                        }
                    } else {
                        logRecord("⚠️ 跳过非RTP pad: " + padName);
                    }
                }
            });
            logRecord("✅ WebRTCBin PAD_ADDED监听已设置");
            
            // ICE candidate处理
            webrtcbin.connect(new WebRTCBin.ON_ICE_CANDIDATE() {
                @Override
                public void onIceCandidate(int sdpMLineIndex, String candidate) {
                    logRecord("🧊 本地ICE候选者: mline=" + sdpMLineIndex);
                    // SRS使用ice-lite模式，不需要发送本地候选者
                }
            });
            logRecord("✅ ICE候选者监听已设置");
            
            // 连接状态监听（简化版本，避免复杂的GObject API）
            logRecord("✅ 连接状态将通过日志监控");
            
            // Bus消息处理
            Bus bus = pipeline.getBus();
            bus.connect(new Bus.ERROR() {
                @Override
                public void errorMessage(GstObject source, int code, String message) {
                    logRecord("❌ ERROR: " + message);
                }
            });
            bus.connect(new Bus.EOS() {
                @Override
                public void endOfStream(GstObject source) {
                    logRecord("✅ EOS收到");
                }
            });
            logRecord("✅ Pipeline消息监听已设置");
            logRecord("✅ ========== Pipeline初始化完成 ==========\n");
            
        } catch (Exception e) {
            logRecord("❌ 创建pipeline失败: " + e.getMessage());
            e.printStackTrace();
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
            throw e;
        }
    }
    
    public boolean startRecording(String outputPath) {
        if (isRecording) {
            logRecord("⚠️ 已在录制中");
            return false;
        }
        
        logRecord("\n🎬 ========== 开始录制 ==========");
        logRecord("输出文件: " + outputPath);
        
        try {
            this.recordOutputPath = outputPath;
            recordedFrameCount = 0;
            
            // 创建输出目录
            File outputFile = new File(outputPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                outputDir.mkdirs();
                logRecord("✅ 创建输出目录: " + outputDir.getAbsolutePath());
            }
            
            // 检查pipeline状态
            State currentState = pipeline.getState();
            logRecord("📊 当前Pipeline状态: " + currentState);
            
            // ⚠️ 关键：必须在NULL状态下设置filesink的location
            if (currentState != State.NULL) {
                logRecord("🔄 Pipeline非NULL状态（" + currentState + "），先停止到NULL...");
                StateChangeReturn stopRet = pipeline.setState(State.NULL);
                logRecord("   停止结果: " + stopRet);
                
                // 等待状态切换完成
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(100);
                    State newState = pipeline.getState();
                    logRecord("   当前状态: " + newState);
                    if (newState == State.NULL) {
                        logRecord("✅ Pipeline已进入NULL状态");
                        break;
                    }
                }
                
                // 最终验证
                State finalState = pipeline.getState();
                if (finalState != State.NULL) {
                    logRecord("❌ Pipeline未能进入NULL状态，当前: " + finalState);
                    return false;
                }
            } else {
                logRecord("✅ Pipeline已在NULL状态");
            }
            
            // 设置文件输出路径（必须在NULL状态）
            logRecord("🔧 设置filesink路径: " + outputPath);
            filesink.set("location", outputPath);
            filesink.set("sync", false);
            filesink.set("async", true);
            filesink.set("buffer-mode", 2);  // 2 = unbuffered (实时写入，不缓冲)
            
            // ⚠️ 强制flush配置
            try {
                filesink.set("buffer-size", 0);  // 0 = 不缓冲，立即写入
                logRecord("✅ 设置 buffer-size=0");
            } catch (Exception e) {
                logRecord("⚠️ 设置buffer-size失败: " + e.getMessage());
            }
            
            try {
                filesink.set("o-sync", true);  // 使用O_SYNC标志，强制同步写入
                logRecord("✅ 设置 o-sync=true");
            } catch (Exception e) {
                logRecord("⚠️ 设置o-sync失败: " + e.getMessage());
            }
            
            logRecord("✅ filesink配置完成: location=" + outputPath);
            logRecord("   配置: sync=false, async=true, buffer-mode=unbuffered, buffer-size=0, o-sync=true");
            
            // 先启动Pipeline
            logRecord("🚀 启动Pipeline...");
            StateChangeReturn ret = pipeline.play();
            logRecord("✅ Pipeline启动结果: " + ret);
            
            // 不等待，立即启动WebRTC信令（异步）
            logRecord("🌐 开始WebRTC信令（异步）...");
            startWebRTCSignaling();
            
            if (ret == StateChangeReturn.FAILURE) {
                logRecord("❌ Pipeline启动失败");
                return false;
            }
            
            isRecording = true;
            logRecord("✅ ========== 录制已启动 ==========\n");
            return true;
            
        } catch (Throwable e) {
            logRecord("❌ 启动录制失败: " + e.getMessage());
            e.printStackTrace();
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
            return false;
        }
    }
    
    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        
        logRecord("\n🛑 ========== 停止录制 ==========");
        
        try {
            // 发送EOS到pipeline
            if (pipeline != null) {
                pipeline.sendEvent(new org.freedesktop.gstreamer.event.EOSEvent());
                logRecord("✅ 已发送EOS");
            }
            
            // 等待EOS处理
            //Thread.sleep(1000);
            
            isRecording = false;
            logRecord("✅ 录制已停止");
            logRecord("   输出文件: " + recordOutputPath);
            
            // 检查文件大小
            File outputFile = new File(recordOutputPath);
            if (outputFile.exists()) {
                long fileSize = outputFile.length();
                logRecord("   文件大小: " + (fileSize / 1024) + " KB");
                
                if (fileSize == 0) {
                    logRecord("⚠️ 警告：文件大小为0，录制可能失败");
                }
            } else {
                logRecord("⚠️ 警告：文件未生成");
            }
            
        } catch (Exception e) {
            logRecord("❌ 停止录制失败: " + e.getMessage());
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
        }
    }
   /* public boolean startRecording(String outputPath) {
        logRecord("\n📹 ========== 开始录制 ==========");

        if (isRecording) {
            logRecord("⚠️ 已在录制中");
            return false;
        }

        try {
            File outputFile = new File(outputPath);
            File outputDir = outputFile.getParentFile();
            if (outputDir != null && !outputDir.exists()) {
                outputDir.mkdirs();
            }

            // ⭐ 只在第一次录制时设置 location（或者停止后切换文件时）
            if (recordOutputPath == null || !recordOutputPath.equals(outputPath)) {
                // 需要切换文件：关闭 valve，发送 EOS，等待完成，再设置新路径
                if (filesink != null) {
                    recordValve.set("drop", true);
                    Thread.sleep(100);

                    // 发送 EOS 完成当前文件
                    Pad valveSrc = recordValve.getStaticPad("src");
                    if (valveSrc != null) {
                        valveSrc.sendEvent(new org.freedesktop.gstreamer.event.EOSEvent());
                        Thread.sleep(500);
                    }

                    // 切换文件（需要短暂 NULL 状态）
                    filesink.setState(State.NULL);
                    filesink.set("location", outputPath);
                    filesink.syncStateWithParent();
                }
            }

            recordOutputPath = outputPath;

            // ⭐ 打开 valve 开始录制
            recordValve.set("drop", false);

            isRecording = true;
            recordedFrames = 0;

            logRecord("✅ 录制已启动（valve 已打开）");
            logRecord("   输出文件: " + outputPath);

            return true;

        } catch (Exception e) {
            logRecord("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        logRecord("\n🛑 ========== 停止录制 ==========");

        try {
            // ⭐ 关闭 valve
            recordValve.set("drop", true);

            // 发送 EOS 到 valve 的 src pad（完成 MKV 文件）
            Pad valveSrc = recordValve.getStaticPad("src");
            if (valveSrc != null) {
                valveSrc.sendEvent(new org.freedesktop.gstreamer.event.EOSEvent());
                logRecord("✅ 已发送 EOS");
            }

            // 等待 EOS 处理
            Thread.sleep(800);

            isRecording = false;
            logRecord("✅ 录制已停止（valve 已关闭）");
            logRecord("   输出文件: " + recordOutputPath);

            // 检查文件大小
            File outputFile = new File(recordOutputPath);
            if (outputFile.exists()) {
                long fileSize = outputFile.length();
                logRecord("   文件大小: " + (fileSize / 1024) + " KB");
            }

        } catch (Exception e) {
            logRecord("❌ 停止失败: " + e.getMessage());
            e.printStackTrace();
        }
    }*/


    
    public void stop() {
        logRecord("\n🔌 ========== 停止Player ==========");
        
        try {
            if (isRecording) {
                stopRecording();
            }
            
            if (pipeline != null) {
                pipeline.setState(State.NULL);
                logRecord("✅ Pipeline已停止");
            }
            
            if (recordLogger != null) {
                recordLogger.close();
                recordLogger = null;
            }
            
            logRecord("✅ Player已停止");
        } catch (Exception e) {
            logRecord("❌ 停止失败: " + e.getMessage());
        }
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    private void startWebRTCSignaling() {
        logRecord("\n📡 ========== 开始WebRTC信令 ==========");
        
        try {
            // 构建流URL（与SimpleWebRTCPlayer一致）
            String streamUrl = "webrtc://" + serverHost + "/" + tenant + "/" + streamId;
            logRecord("🎯 流URL: " + streamUrl);
            currentStreamUrl = streamUrl;
            
            // 添加recvonly H264视频transceiver（触发ON_NEGOTIATION_NEEDED）
            try {
                Caps videoCaps = Caps.fromString("application/x-rtp,media=video,payload=109,encoding-name=H264,clock-rate=90000,profile-level-id=42e01f,packetization-mode=1,level-asymmetry-allowed=1");
                GstObject transceiver = webrtcbin.emit(GstObject.class, "add-transceiver", 3, videoCaps);
                logRecord("✅ 已添加recvonly H264视频transceiver: " + transceiver);
            } catch (Exception e) {
                logRecord("❌ 添加transceiver失败: " + e.getMessage());
            }
            // 创建Offer（客户端主动）
            createOffer(streamUrl);
            
        } catch (Exception e) {
            logRecord("❌ WebRTC信令失败: " + e.getMessage());
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
        }
    }
    
    private void createOffer(String streamUrl) {
        logRecord("\n🔄 创建WebRTC Offer...");
        
        try {
            logRecord("📋 调用webrtcbin.createOffer()...");
            webrtcbin.createOffer(new WebRTCBin.CREATE_OFFER() {
                @Override
                public void onOfferCreated(WebRTCSessionDescription offer) {
                    logRecord("✅ Offer创建成功（回调触发）");
                    
                    try {
                        // 获取SDP文本
                        SDPMessage offerMsg = offer.getSDPMessage();
                        String sdpOffer = offerMsg.toString().replace("\r\n", "\n").replace("\n", "\r\n");
                        
                        logRecord("===== LOCAL OFFER SDP BEGIN =====");
                        logRecord(sdpOffer.substring(0, Math.min(500, sdpOffer.length())));
                        logRecord("===== LOCAL OFFER SDP END =====");
                        
                        // 设置本地描述
                        webrtcbin.setLocalDescription(offer);
                        logRecord("✅ 本地描述已设置");
                        
                        // 发送Offer到服务器
                        sendOfferToServer(streamUrl, sdpOffer);
                        
                    } catch (Exception e) {
                        logRecord("❌ 处理Offer失败: " + e.getMessage());
                        if (recordLogger != null) {
                            e.printStackTrace(recordLogger);
                        }
                    }
                }
            });
            logRecord("✅ createOffer已调用（异步，不等待回调）");
            
        } catch (Exception e) {
            logRecord("❌ 创建Offer失败: " + e.getMessage());
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
        }
    }
    
    private void sendOfferToServer(String streamUrl, String sdpOffer) {
        logRecord("\n📤 发送Offer到服务器...");
        
        try {
            String apiUrl = "http://" + serverHost + ":" + serverPort + "/rtc/v1/play/";
            String sdpCRLF = sdpOffer.replace("\r\n", "\n").replace("\n", "\r\n");
            
            // 构建JSON请求体
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("api", apiUrl);
            obj.addProperty("streamurl", streamUrl);
            obj.addProperty("sdp", sdpCRLF);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
            String json = gson.toJson(obj);
            
            logRecord("📤 信令URL: " + apiUrl);
            logRecord("📋 请求体长度: " + json.length() + " 字符");
            
            // 发送HTTP请求
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(new URI(apiUrl))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, java.nio.charset.StandardCharsets.UTF_8))
                .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            String responseBody = response.body();
            
            logRecord("📥 收到响应: HTTP " + response.statusCode());
            logRecord("📄 响应内容: " + responseBody);
            
            if (response.statusCode() == 200) {
                // 解析Answer SDP
                String answerSdp = parseAnswerSDP(responseBody);
                if (answerSdp != null) {
                    setRemoteAnswer(answerSdp);
                } else {
                    logRecord("⚠️ 未找到Answer SDP");
                }
            } else {
                logRecord("❌ 信令失败: HTTP " + response.statusCode());
            }
            
        } catch (Exception e) {
            logRecord("❌ 发送Offer失败: " + e.getMessage());
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
        }
    }
    
    private String parseAnswerSDP(String jsonResponse) {
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(jsonResponse).getAsJsonObject();
            if (obj.has("sdp")) {
                String sdp = obj.get("sdp").getAsString();
                return sdp.replace("\\r\\n", "\r\n").replace("\\n", "\n");
            }
            return null;
        } catch (Exception e) {
            logRecord("⚠️ 解析Answer SDP失败: " + e.getMessage());
            return null;
        }
    }
    
    private void setRemoteAnswer(String sdp) {
        logRecord("\n🔧 设置远程Answer...");
        
        try {
            // 解析SDP
            SDPMessage sdpMsg = new SDPMessage();
            sdpMsg.parseBuffer(sdp);
            
            // 创建WebRTCSessionDescription
            WebRTCSessionDescription answer = new WebRTCSessionDescription(
                org.freedesktop.gstreamer.webrtc.WebRTCSDPType.ANSWER, sdpMsg);
            
            // 设置远程描述
            webrtcbin.setRemoteDescription(answer);
            
            logRecord("✅ 远程Answer已设置");
            logRecord("✅ WebRTC连接建立成功");
            
        } catch (Exception e) {
            logRecord("❌ 设置远程Answer失败: " + e.getMessage());
            if (recordLogger != null) {
                e.printStackTrace(recordLogger);
            }
        }
    }

    /**
     * 开始JPEG图片保存
     */
    public void startJpegCapture() {
        if (captureValve != null) {
            captureValve.set("drop", false);
            logRecord("✅ JPEG保存已启动");
        } else {
            logRecord("❌ captureValve未初始化，无法启动JPEG保存");
        }
    }

    /**
     * 停止JPEG图片保存
     */
    public void stopJpegCapture() {
        if (captureValve != null) {
            captureValve.set("drop", true);
            logRecord("✅ JPEG保存已停止");
        } else {
            logRecord("❌ captureValve未初始化，无法停止JPEG保存");
        }
    }

    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }

    private void logRecord2(String message) {
        LogTools.getInstance().logRecord3(message);
    }


}
