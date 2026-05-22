package com.acard.acard.utils;

import com.acard.acard.tools.LogTools;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.message.Message;
import org.freedesktop.gstreamer.message.MessageType;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/**
 * GStreamer JPEG实时缩放器（根据系统内存自动配置）
 * 支持两种模式：
 * 1. scale() - 返回BufferedImage（内存）
 * 2. scaleAndSave() - 保存到文件并返回路径（磁盘）
 */
public class GStreamerJpegScaler {

    private static GStreamerJpegScaler instance;
    private static boolean gstInitialized = false;

    // 动态配置
    private final int targetWidth;
    private final int targetHeight;
    private final int jpegQuality;

    // Pipeline元素（内存模式 - 返回BufferedImage）
    private Pipeline memoryPipeline;
    private Element memoryFilesrc;
    private Element memoryJpegdec;
    private Element memoryVideoscale;
    private Element memoryCapsfilter;
    private Element memoryVideoconvert;
    private AppSink memoryAppsink;

    // 当前处理的图片（内存模式）
    private BufferedImage currentImage = null;
    private final Object imageLock = new Object();

    private GStreamerJpegScaler(int targetWidth, int targetHeight, int jpegQuality) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.jpegQuality = jpegQuality;
        initMemoryPipeline();
    }

    /**
     * 获取实例（根据系统内存自动配置）⭐
     */
    public static synchronized GStreamerJpegScaler getInstance() {
        if (instance == null) {
            if (!gstInitialized) {
                Gst.init("GStreamerJpegScaler");
                gstInitialized = true;
            }

            // ⭐ 根据内存自动配置
            long memoryGB = getSystemMemoryGB();
            int width, height, quality;

            if (memoryGB >= 32) {
                width = 480;
                height = 320;
                quality = 100;
                LogTools.getInstance().logRecord("🔧 高端机（" + memoryGB + "GB）→ 480x320, quality=100");
            } else if (memoryGB >= 16) {
                width = 480;
                height = 320;
                quality = 100;
                LogTools.getInstance().logRecord("🔧 中端机（" + memoryGB + "GB）→ 480x320, quality=75");
            } else {
                width = 480;
                height = 320;
                quality = 100;
                LogTools.getInstance().logRecord("🔧 低端机（" + memoryGB + "GB）→ 480x320, quality=50");
            }

            instance = new GStreamerJpegScaler(width, height, quality);
        }
        return instance;
    }

    /**
     * 获取系统物理内存（GB）
     */
    private static long getSystemMemoryGB() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long totalMemoryBytes = osBean.getTotalPhysicalMemorySize();
            long totalMemoryGB = totalMemoryBytes / (1024 * 1024 * 1024);
            System.out.println("💾 系统物理内存: " + totalMemoryGB + "GB");
            return totalMemoryGB;
        } catch (Exception e) {
            LogTools.getInstance().logRecord("⚠️ 无法获取系统内存，默认按8GB处理");
            return 8;
        }
    }

    /**
     * 初始化内存模式Pipeline（返回BufferedImage）
     */
    private void initMemoryPipeline() {
        try {
            System.out.println("🔧 初始化GStreamer JPEG缩放Pipeline（内存模式）...");

            memoryPipeline = new Pipeline("jpeg-scaler-memory");

            memoryFilesrc = ElementFactory.make("filesrc", "file_source_mem");

            try {
                memoryJpegdec = ElementFactory.make("nvjpegdec", "nvjpeg_decoder_mem");
                System.out.println("✅ 使用NVJPEG GPU解码");
            } catch (Exception e) {
                memoryJpegdec = ElementFactory.make("jpegdec", "jpeg_decoder_mem");
                System.out.println("✅ 使用CPU JPEG解码");
            }

            memoryVideoscale = ElementFactory.make("videoscale", "scaler_mem");
            memoryVideoscale.set("method", 0);

            memoryCapsfilter = ElementFactory.make("capsfilter", "caps_mem");
            String capsStr = String.format("video/x-raw,width=%d,height=%d", targetWidth, targetHeight);
            Caps caps = Caps.fromString(capsStr);
            memoryCapsfilter.set("caps", caps);

            memoryVideoconvert = ElementFactory.make("videoconvert", "converter_mem");

            memoryAppsink = (AppSink) ElementFactory.make("appsink", "app_sink_mem");
            memoryAppsink.set("emit-signals", true);
            memoryAppsink.set("sync", false);

            Caps sinkCaps = Caps.fromString("video/x-raw,format=RGB");
            memoryAppsink.setCaps(sinkCaps);

            memoryAppsink.connect(new AppSink.NEW_SAMPLE() {
                @Override
                public FlowReturn newSample(AppSink elem) {
                    Sample sample = elem.pullSample();
                    if (sample != null) {
                        handleNewSample(sample);
                        sample.dispose();
                    }
                    return FlowReturn.OK;
                }
            });

            memoryPipeline.addMany(memoryFilesrc, memoryJpegdec, memoryVideoscale,
                    memoryCapsfilter, memoryVideoconvert, memoryAppsink);

            boolean linked = Element.linkMany(memoryFilesrc, memoryJpegdec, memoryVideoscale,
                    memoryCapsfilter, memoryVideoconvert, memoryAppsink);

            if (!linked) {
                LogTools.getInstance().logRecord("❌ 内存模式Pipeline连接失败！");
                return;
            }

            System.out.println("✅ GStreamer JPEG缩放Pipeline初始化完成（内存模式）");
            System.out.println("   输出分辨率: " + targetWidth + "x" + targetHeight);
            System.out.println("   JPEG质量配置: " + jpegQuality);

        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ 内存模式Pipeline初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 处理新的Sample
     */
    private void handleNewSample(Sample sample) {
        try {
            Buffer buffer = sample.getBuffer();
            ByteBuffer byteBuffer = buffer.map(false);

            if (byteBuffer != null) {
                Structure caps = sample.getCaps().getStructure(0);
                int width = caps.getInteger("width");
                int height = caps.getInteger("height");

                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

                byte[] data = new byte[byteBuffer.remaining()];
                byteBuffer.get(data);

                int[] pixels = new int[width * height];
                for (int i = 0; i < pixels.length; i++) {
                    int idx = i * 3;
                    int r = data[idx] & 0xFF;
                    int g = data[idx + 1] & 0xFF;
                    int b = data[idx + 2] & 0xFF;
                    pixels[i] = (r << 16) | (g << 8) | b;
                }
                image.setRGB(0, 0, width, height, pixels, 0, width);

                synchronized (imageLock) {
                    if (currentImage != null) {
                        currentImage.flush();
                    }
                    currentImage = image;
                    imageLock.notifyAll();
                }

                buffer.unmap();
            }

        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ 处理Sample失败: " + e.getMessage());
        }
    }

    /**
     * 缩放JPEG（返回BufferedImage）⭐
     * @param jpegPath 源JPEG文件路径
     * @return 缩放后的BufferedImage
     */
    public BufferedImage scale(String jpegPath) {
        if (memoryPipeline == null) {
            LogTools.getInstance().logRecord("❌ 内存模式Pipeline未初始化");
            return null;
        }

        File file = new File(jpegPath);
        if (!file.exists()) {
            LogTools.getInstance().logRecord("❌ 文件不存在: " + jpegPath);
            return null;
        }

        try {
            synchronized (imageLock) {
                currentImage = null;
            }

            memoryPipeline.setState(State.NULL);
            Thread.sleep(5);

            String normalizedPath = jpegPath.replace("\\", "/");
            memoryFilesrc.set("location", normalizedPath);

            memoryPipeline.setState(State.PLAYING);

            synchronized (imageLock) {
                long timeout = System.currentTimeMillis() + 1000;
                while (currentImage == null && System.currentTimeMillis() < timeout) {
                    imageLock.wait(100);
                }
            }

            if (currentImage == null) {
                LogTools.getInstance().logRecord("⚠️ 缩放超时: " + jpegPath);
                return null;
            }

            return currentImage;

        } catch (Exception e) {
            LogTools.getInstance().logRecord("❌ 缩放失败: " + jpegPath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 缩放JPEG并保存到文件（返回文件路径）⭐
     * @param sourcePath 源JPEG文件路径
     * @param targetPath 目标文件路径（缩放后保存）
     * @return 目标文件路径（成功）或 null（失败）
     */
    /**
     * 缩放JPEG并保存到文件（同步等待）⭐
     */
    /**
     * 缩放JPEG并保存到文件（返回文件路径）⭐
     */
    /**
     * 缩放JPEG并保存到文件（返回文件路径）⭐
     */
    public String scaleAndSave(String sourcePath, String targetPath) {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            LogTools.getInstance().logRecord("❌ 源文件不存在: " + sourcePath);
            return null;
        }

        // ⭐ 总计时开始
        long totalStartTime = System.nanoTime();

        Pipeline filePipeline = null;
        try {
            String normalizedSource = sourcePath.replace("\\", "/");
            String normalizedTarget = targetPath.replace("\\", "/");

            // ⭐ Pipeline创建计时
            long pipelineCreateStart = System.nanoTime();

            String pipelineStr = String.format(
                    "filesrc location=\"%s\" ! jpegdec ! videoscale method=0 ! video/x-raw,width=%d,height=%d ! jpegenc quality=%d ! filesink location=\"%s\"",
                    normalizedSource, targetWidth, targetHeight, jpegQuality, normalizedTarget
            );

            filePipeline = (Pipeline) Gst.parseLaunch(pipelineStr);

            long pipelineCreateTime = (System.nanoTime() - pipelineCreateStart) / 1_000_000;

            final boolean[] isDone = {false};
            final boolean[] hasError = {false};
            final String[] errorMsg = {null};
            final Object lock = new Object();

            Bus bus = filePipeline.getBus();

            bus.connect(new Bus.MESSAGE() {
                @Override
                public void busMessage(Bus bus, Message message) {
                    MessageType type = message.getType();

                    if (type == MessageType.EOS) {
                        synchronized (lock) {
                            isDone[0] = true;
                            lock.notifyAll();
                        }
                    } else if (type == MessageType.ERROR) {
                        synchronized (lock) {
                            hasError[0] = true;
                            try {
                                errorMsg[0] = "未知错误";
                            } catch (Exception e) {
                                errorMsg[0] = "未知错误";
                            }
                            lock.notifyAll();
                        }
                    }
                }
            });

            // ⭐ Pipeline执行计时
            long pipelineExecuteStart = System.nanoTime();

            filePipeline.setState(State.PLAYING);

            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!isDone[0] && !hasError[0]) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
                        LogTools.getInstance().logRecord("⚠️ 超时: " + targetPath + " (总耗时: " + totalTime + "ms)");
                        return null;
                    }
                    try {
                        lock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }

            long pipelineExecuteTime = (System.nanoTime() - pipelineExecuteStart) / 1_000_000;

            if (hasError[0]) {
                long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
                LogTools.getInstance().logRecord("❌ 错误: " + errorMsg[0] + " (总耗时: " + totalTime + "ms)");
                return null;
            }

            // ⭐ 文件验证计时
            long verifyStart = System.nanoTime();

            File targetFile = new File(targetPath);
            boolean fileExists = targetFile.exists() && targetFile.length() > 0;

            long verifyTime = (System.nanoTime() - verifyStart) / 1_000_000;

            // ⭐ 总耗时
            long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;

            if (fileExists) {
                long fileSize = targetFile.length() / 1024; // KB

                // ⭐ 详细计时日志
                LogTools.getInstance().logRecord(String.format(
                        "✅ 缩放完成: %s → %s | 总耗时: %dms (Pipeline创建:%dms, 执行:%dms, 验证:%dms) | 文件大小: %dKB",
                        new File(sourcePath).getName(),
                        new File(targetPath).getName(),
                        totalTime,
                        0,
                        0,
                        verifyTime,
                        fileSize
                ));
                return targetPath;
            } else {
                LogTools.getInstance().logRecord("❌ 文件未生成: " + targetPath + " (总耗时: " + totalTime + "ms)");
                return null;
            }

        } catch (Exception e) {
            long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
            LogTools.getInstance().logRecord("❌ 失败: " + e.getMessage() + " (总耗时: " + totalTime + "ms)");
            e.printStackTrace();
            return null;
        } finally {
            if (filePipeline != null) {
                filePipeline.setState(State.NULL);
                filePipeline.dispose();
            }
        }
    }






    /**
     * 获取配置信息
     */
    public String getConfigInfo() {
        return String.format("%dx%d, quality=%d", targetWidth, targetHeight, jpegQuality);
    }

    /**
     * 获取目标分辨率
     */
    public int getTargetWidth() {
        return targetWidth;
    }

    public int getTargetHeight() {
        return targetHeight;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        if (memoryPipeline != null) {
            memoryPipeline.setState(State.NULL);
            memoryPipeline.dispose();
            memoryPipeline = null;
        }

        synchronized (imageLock) {
            if (currentImage != null) {
                currentImage.flush();
                currentImage = null;
            }
        }
    }
}