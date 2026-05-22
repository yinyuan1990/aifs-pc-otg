package com.acard.acard.capture;

import com.acard.acard.tools.LogTools;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSrc;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class FrameDistributor2 {

    private volatile boolean recordingEnabled = false;

    private volatile boolean pipelineCreated = false;

    // 独立的录制管道
    private Pipeline recordPipeline;  // 🎯 关键：独立管道
    private Element recordAppSrc;
    private Element recordQueue;
    private Element recordH264Encoder;
    private Element recordMux;
    private Element recordFileSink;



    private PrintWriter recordLogger;

    public FrameDistributor2(){
        initializeLogger();
    }

    /**
     * 初始化日志系统
     */
    private void initializeLogger() {
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            String logPath = "runtime/logs/frame_distributor_" + timestamp + ".txt";

            File logFile = new File(logPath);
            File parentDir = logFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            recordLogger = new PrintWriter(new FileWriter(logFile, true));
            logRecord("✅ FrameDistributor2 初始化完成");

        } catch (Exception e) {
            System.err.println("❌ 日志初始化失败: " + e.getMessage());
        }
    }

    /**
     * 创建独立的录制管道
     */
    /**
     * 延迟创建录制管道（在需要时才创建）
     */
    private synchronized boolean ensureRecordPipeline() {
        if (pipelineCreated) {
            return true;
        }

        try {
            logRecord("🔄 开始创建录制管道...");

            // 创建独立管道
            recordPipeline = new Pipeline("record-pipeline");

            // 创建元素
            recordAppSrc = ElementFactory.make("appsrc", "record_appsrc");
            recordQueue = ElementFactory.make("queue", "record_queue");
            recordH264Encoder = ElementFactory.make("x264enc", "record_h264enc");
            recordMux = ElementFactory.make("matroskamux", "record_mux");
            recordFileSink = ElementFactory.make("filesink", "record_filesink");

            // 检查元素创建是否成功
            if (recordAppSrc == null || recordQueue == null || recordH264Encoder == null ||
                    recordMux == null || recordFileSink == null) {
                logRecord("❌ 录制元素创建失败");
                return false;
            }

            // 配置AppSrc

            // 在 ensureRecordPipeline() 方法中配置AppSrc时添加：
            recordAppSrc.set("emit-signals", true);
            recordAppSrc.set("is-live", true);
            recordAppSrc.set("format", 3); // GST_FORMAT_TIME
            recordAppSrc.set("block", false); // 🎯 关键：设置为非阻塞模式
            //recordAppSrc.set("max-bytes", 1024 * 1024); // 限制缓冲区大小
            recordAppSrc.set("max-bytes", 0);
            // 设置caps - 简化版本，避免复杂的格式转换
            Caps caps = Caps.fromString("video/x-raw,format=BGRA");
            recordAppSrc.setCaps(caps);

            // 配置队列 - 减少缓冲区大小
            recordQueue.set("max-size-buffers", 5);
            recordQueue.set("leaky", 2); // 丢弃旧帧

            // 配置编码器 - 使用最快设置
            recordH264Encoder.set("speed-preset", 1); // 1 = ultrafast
            recordH264Encoder.set("tune", 4); // 4 = zerolatency
            recordH264Encoder.set("threads", 1); // 单线程避免竞争

            // 配置文件输出
            recordFileSink.set("location", "runtime/slowmo/distributed_record.mkv");
            recordFileSink.set("sync", false);
            recordFileSink.set("async", false);



            // 添加元素到管道
            recordPipeline.addMany(recordAppSrc, recordQueue, recordH264Encoder, recordMux, recordFileSink);

            // 连接元素
            boolean linkResult = Element.linkMany(recordAppSrc, recordQueue, recordH264Encoder, recordMux, recordFileSink);
            if (!linkResult) {
                logRecord("❌ 录制管道元素连接失败");
                return false;
            }

            // ✅ 关键：设置管道为READY状态，但不启动
            StateChangeReturn stateResult = recordPipeline.setState(State.READY);
            if (stateResult == StateChangeReturn.FAILURE) {
                logRecord("❌ 录制管道状态设置失败");
                return false;
            }

            pipelineCreated = true;
            logRecord("✅ 录制管道创建成功");
            return true;

        } catch (Exception e) {
            logRecord("❌ 创建录制管道异常: " + e.getMessage());
            e.printStackTrace();

            // 清理失败的管道
            if (recordPipeline != null) {
                recordPipeline.setState(State.NULL);
                recordPipeline = null;
            }
            return false;
        }
    }

    /**
     * 启用录制
     */
    /**
     * 启用录制
     */
    public void enableRecording() {
        if (recordingEnabled) {
            logRecord("⚠️ 录制已经启用");
            logRecord("✅ 录制已经启用");
            return;
        }

        // 确保管道已创建
        if (!ensureRecordPipeline()) {
            logRecord("❌ 录制管道创建失败，无法启用录制");
            logRecord("❌ 录制管道创建失败，无法启用录制");
            return;
        }

        try {
            // 启动管道
            StateChangeReturn result = recordPipeline.setState(State.PLAYING);
            if (result == StateChangeReturn.FAILURE) {
                logRecord("❌ 录制管道启动失败");
                return;
            }

            recordingEnabled = true;
            logRecord("✅ 录制已启用");

        } catch (Exception e) {
            logRecord("❌ 启用录制异常: " + e.getMessage());
        }
    }

    /**
     * 停止录制
     */
    /**
     * 禁用录制
     */
    public void disableRecording() {
        if (!recordingEnabled) {
            logRecord("⚠️ 录制未启用");
            return;
        }

        try {
            recordingEnabled = false;

            if (recordPipeline != null) {
                // 发送EOS
                if (recordAppSrc != null) {
                    ((AppSrc)recordAppSrc).endOfStream();
                }

                // 等待EOS处理
                Thread.sleep(100);

                // 停止管道
                recordPipeline.setState(State.READY);
            }

            logRecord("✅ 录制已禁用");

        } catch (Exception e) {
            logRecord("❌ 禁用录制异常: " + e.getMessage());
        }
    }

    /**
     * 分发给录制分支
     */


    public void distributeToRecord(Sample sample) {
        if (!recordingEnabled || recordAppSrc == null) return;

        try {
            Buffer buffer = sample.getBuffer();
            if (buffer == null) return;

            // 非阻塞推送
            FlowReturn result = ((AppSrc) recordAppSrc).pushBuffer(buffer);

            if (result == FlowReturn.OK) {
                // 成功
            } else if (result == FlowReturn.FLUSHING) {
                // 管道正在刷新，忽略
            } else {
                logRecord("⚠️ 录制推送失败: " + result + "，丢弃帧");
            }

        } catch (Exception e) {
            logRecord("❌ 录制分发异常: " + e.getMessage());
        }
    }
    public void distributeToRecord(Buffer buffer) {
        if (!recordingEnabled || recordAppSrc == null) return;

        try {
            if (buffer == null) return;

            // 非阻塞推送
            FlowReturn result = ((AppSrc) recordAppSrc).pushBuffer(buffer);

            if (result == FlowReturn.OK) {
                // 成功
                logRecord("❌ 录制分发异常: 成功" );
            } else if (result == FlowReturn.FLUSHING) {
                // 管道正在刷新，忽略
                logRecord("❌ 录制分发异常: FLUSHING" );
            } else {
                logRecord("⚠️ 录制推送失败: " + result + "，丢弃帧");
            }

        } catch (Exception e) {
            logRecord("❌ 录制分发异常: " + e.getMessage());
        }
    }

    private void logRecord(String message) {
        LogTools.getInstance().logRecord3(message);
    }

    // 添加状态检查方法
    public boolean isRecordingEnabled() {
        return recordingEnabled && recordAppSrc != null;
    }

    // 直接推送Buffer的方法
    public FlowReturn pushBufferDirectly(Buffer buffer) {
        if (!recordingEnabled || recordAppSrc == null) {
            return FlowReturn.OK;
        }

        try {
            // 🎯 关键：直接推送，不做任何处理
            return ((AppSrc) recordAppSrc).pushBuffer(buffer);
        } catch (Exception e) {
            logRecord("❌ 直接推送异常: " + e.getMessage());
            return FlowReturn.ERROR;
        }
    }
}
