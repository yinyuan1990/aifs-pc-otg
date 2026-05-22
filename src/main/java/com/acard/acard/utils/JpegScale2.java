package com.acard.acard.utils;

import com.acard.acard.tools.LogTools;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.message.Message;
import org.freedesktop.gstreamer.message.MessageType;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
public class JpegScale2 {

    private static JpegScale2 instance;
    private static boolean gstInitialized = false;

    private final int targetWidth;
    private final int targetHeight;
    private final int jpegQuality;

    // ⭐ 内存模式Pipeline（返回BufferedImage）
    private Pipeline memoryPipeline;
    private Element memoryFilesrc;
    private Element memoryJpegdec;
    private Element memoryVideoscale;
    private Element memoryCapsfilter;
    private Element memoryVideoconvert;
    private AppSink memoryAppsink;

    // ⭐ 文件模式Pipeline（保存到文件）- 持久化
    private Pipeline filePipeline;
    private Element fileFilesrc;
    private Element fileJpegdec;
    private Element fileVideoscale;
    private Element fileCapsfilter;
    private Element fileJpegenc;
    private Element fileFilesink;
    private volatile boolean filePipelineInitialized = false;

    private BufferedImage currentImage = null;
    private final Object imageLock = new Object();

    private JpegScale2(int targetWidth, int targetHeight, int jpegQuality) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.jpegQuality = jpegQuality;
        initMemoryPipeline();
        initFilePipeline(); // ⭐ 启动时就初始化文件Pipeline
    }

    public static synchronized JpegScale2 getInstance() {
        if (instance == null) {
            if (!gstInitialized) {
                Gst.init("JpegScale2");
                gstInitialized = true;
            }

            long memoryGB = getSystemMemoryGB();
            int width, height, quality;

            if (memoryGB >= 32) {
                width = 480;
                height = 320;
                quality = 100;
                System.out.println("🔧 高端机（" + memoryGB + "GB）→ 480x320, quality=100");
            } else if (memoryGB >= 16) {
                width = 480;
                height = 320;
                quality = 75;
                System.out.println("🔧 中端机（" + memoryGB + "GB）→ 480x320, quality=75");
            } else {
                width = 480;
                height = 320;
                quality = 50;
                System.out.println("🔧 低端机（" + memoryGB + "GB）→ 480x320, quality=50");
            }

            instance = new JpegScale2(width, height, quality);
        }
        return instance;
    }

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
            System.err.println("⚠️ 无法获取系统内存，默认按8GB处理");
            return 8;
        }
    }

    private void initMemoryPipeline() {
        try {
            System.out.println("🔧 初始化内存模式Pipeline...");

            memoryPipeline = new Pipeline("jpeg-scaler-memory");
            memoryFilesrc = ElementFactory.make("filesrc", "file_source_mem");

            try {
                memoryJpegdec = ElementFactory.make("nvjpegdec", "nvjpeg_decoder_mem");
                System.out.println("✅ 内存模式使用NVJPEG GPU解码");
            } catch (Exception e) {
                memoryJpegdec = ElementFactory.make("jpegdec", "jpeg_decoder_mem");
                System.out.println("✅ 内存模式使用CPU JPEG解码");
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

            Element.linkMany(memoryFilesrc, memoryJpegdec, memoryVideoscale,
                    memoryCapsfilter, memoryVideoconvert, memoryAppsink);

            System.out.println("✅ 内存模式Pipeline初始化完成");

        } catch (Exception e) {
            System.err.println("❌ 内存模式Pipeline初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 初始化文件模式Pipeline（持久化，启动时创建一次）⭐
     */
    private void initFilePipeline() {
        try {
            System.out.println("🔧 初始化文件模式Pipeline（持久化）...");

            long initStart = System.nanoTime();

            filePipeline = new Pipeline("jpeg-scaler-file");

            fileFilesrc = ElementFactory.make("filesrc", "file_source_file");

            try {
                fileJpegdec = ElementFactory.make("nvjpegdec", "nvjpeg_decoder_file");
                System.out.println("✅ 文件模式使用NVJPEG GPU解码");
            } catch (Exception e) {
                fileJpegdec = ElementFactory.make("jpegdec", "jpeg_decoder_file");
                System.out.println("✅ 文件模式使用CPU JPEG解码");
            }

            fileVideoscale = ElementFactory.make("videoscale", "scaler_file");
            fileVideoscale.set("method", 0);

            fileCapsfilter = ElementFactory.make("capsfilter", "caps_file");
            String capsStr = String.format("video/x-raw,width=%d,height=%d", targetWidth, targetHeight);
            Caps caps = Caps.fromString(capsStr);
            fileCapsfilter.set("caps", caps);

            fileJpegenc = ElementFactory.make("jpegenc", "jpeg_encoder_file");
            fileJpegenc.set("quality", jpegQuality);

            fileFilesink = ElementFactory.make("filesink", "file_sink_file");

            filePipeline.addMany(fileFilesrc, fileJpegdec, fileVideoscale, fileCapsfilter, fileJpegenc, fileFilesink);
            Element.linkMany(fileFilesrc, fileJpegdec, fileVideoscale, fileCapsfilter, fileJpegenc, fileFilesink);

            long initTime = (System.nanoTime() - initStart) / 1_000_000;

            filePipelineInitialized = true;

            System.out.println("✅ 文件模式Pipeline初始化完成（耗时: " + initTime + "ms，后续复用）");
            System.out.println("   输出分辨率: " + targetWidth + "x" + targetHeight);
            System.out.println("   JPEG质量: " + jpegQuality);

        } catch (Exception e) {
            System.err.println("❌ 文件模式Pipeline初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

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
            System.err.println("❌ 处理Sample失败: " + e.getMessage());
        }
    }

    public BufferedImage scale(String jpegPath) {
        if (memoryPipeline == null) {
            System.err.println("❌ 内存Pipeline未初始化");
            return null;
        }

        File file = new File(jpegPath);
        if (!file.exists()) {
            System.err.println("❌ 文件不存在: " + jpegPath);
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

            return currentImage;

        } catch (Exception e) {
            System.err.println("❌ 缩放失败: " + jpegPath + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 缩放JPEG并保存到文件（复用持久Pipeline）⭐
     */

    /**
     * 缩放JPEG并保存到文件（复用Pipeline，无监听器版本）⭐
     */
    public String scaleAndSave(String sourcePath, String targetPath) {
        if (!filePipelineInitialized || filePipeline == null) {
            LogTools.getInstance().logRecord("❌ 文件模式Pipeline未初始化");
            return null;
        }

        File sourceFile = new File(sourcePath);
        if (!sourceFile.exists()) {
            LogTools.getInstance().logRecord("❌ 源文件不存在: " + sourcePath);
            return null;
        }

        long totalStartTime = System.nanoTime();

        try {
            String normalizedSource = sourcePath.replace("\\", "/");
            String normalizedTarget = targetPath.replace("\\", "/");

            // 确保目标目录存在
            File targetFile = new File(targetPath);
            File parentDir = targetFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // ⭐ 1. 停止Pipeline（清空状态）
            long updateStart = System.nanoTime();

            filePipeline.stop();
            filePipeline.setState(State.NULL);

            // ⭐ 等待完全停止
            State ret1 = filePipeline.getState(1000000000L); // 1秒超时

            LogTools.getInstance().logRecord("⚠️ Pipeline停止失败 "+ret1.name());


            // ⭐ 2. 更新路径
            fileFilesrc.set("location", normalizedSource);
            fileFilesink.set("location", normalizedTarget);

            long updateTime = (System.nanoTime() - updateStart) / 1_000_000;

            // ⭐ 3. 启动Pipeline
            long executeStart = System.nanoTime();

            StateChangeReturn ret2 = filePipeline.setState(State.PLAYING);

            if (ret2 == StateChangeReturn.FAILURE) {
                long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
                LogTools.getInstance().logRecord("❌ Pipeline启动失败 (总耗时: " + totalTime + "ms)");
                return null;
            }

            // ⭐ 4. 等待处理完成（通过检查文件存在+Pipeline状态）
            boolean success = false;
            long deadline = System.currentTimeMillis() + 5000; // 5秒超时
            long lastFileSize = 0;
            int stableCount = 0;

            while (System.currentTimeMillis() < deadline) {
                // 检查文件是否存在且大小稳定
                if (targetFile.exists()) {
                    long currentSize = targetFile.length();
                    if (currentSize > 0) {
                        if (currentSize == lastFileSize) {
                            stableCount++;
                            // 文件大小连续3次不变，认为写入完成
                            if (stableCount >= 3) {
                                success = true;
                                break;
                            }
                        } else {
                            stableCount = 0;
                            lastFileSize = currentSize;
                        }
                    }
                }

                // 检查Pipeline状态
                State currentState = filePipeline.getState();
                if (currentState == State.NULL) {
                    // Pipeline已经自动停止（可能是EOS）
                    if (targetFile.exists() && targetFile.length() > 0) {
                        success = true;
                        break;
                    }
                }

                try {
                    Thread.sleep(10); // 短暂等待
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }

            long executeTime = (System.nanoTime() - executeStart) / 1_000_000;

            // ⭐ 5. 停止Pipeline（准备下次复用）
            filePipeline.setState(State.NULL);

            if (!success) {
                long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
                LogTools.getInstance().logRecord("⚠️ 处理超时或失败: " + targetPath + " (总耗时: " + totalTime + "ms)");
                return null;
            }

            // 验证文件
            if (targetFile.exists() && targetFile.length() > 0) {
                long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
                long fileSize = targetFile.length() / 1024;

                System.out.println(String.format(
                        "✅ 缩放完成: %s → %s | 总耗时: %dms (更新:%dms, 执行:%dms) | 文件大小: %dKB",
                        new File(sourcePath).getName(),
                        new File(targetPath).getName(),
                        totalTime,
                        updateTime,
                        executeTime,
                        fileSize
                ));

                return targetPath;
            } else {
                LogTools.getInstance().logRecord("❌ 文件未生成或为空: " + targetPath);
                return null;
            }

        } catch (Exception e) {
            long totalTime = (System.nanoTime() - totalStartTime) / 1_000_000;
            LogTools.getInstance().logRecord("❌ 失败: " + e.getMessage() + " (总耗时: " + totalTime + "ms)");
            e.printStackTrace();
            return null;
        }
    }
    public String getConfigInfo() {
        return String.format("%dx%d, quality=%d", targetWidth, targetHeight, jpegQuality);
    }

    public int getTargetWidth() {
        return targetWidth;
    }

    public int getTargetHeight() {
        return targetHeight;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public void cleanup() {
        if (memoryPipeline != null) {
            memoryPipeline.setState(State.NULL);
            memoryPipeline.dispose();
            memoryPipeline = null;
        }

        if (filePipeline != null) {
            filePipeline.setState(State.NULL);
            filePipeline.dispose();
            filePipeline = null;
        }

        synchronized (imageLock) {
            if (currentImage != null) {
                currentImage.flush();
                currentImage = null;
            }
        }
    }
}
