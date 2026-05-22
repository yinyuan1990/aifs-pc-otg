package com.acard.acard.test;

import com.acard.acard.tools.LogTools;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.message.MessageType;

import java.io.File;

/**
 * GStreamer GPU JPEG播放器测试（按照成功的命令行配置）⭐
 */
public class GpuJpegPlayerTest extends Application {

    private Pipeline pipeline;
    private Element filesrc;
    private Element jpegdec;
    private Element imagefreeze;
    private Element videoconvert;
    private Element videosink;

    private Label statusLabel;
    private Label pathLabel;
    private Label infoLabel;

    private int frameCount = 0;
    private String lastPath = null;

    @Override
    public void start(Stage primaryStage) {
        // 初始化GStreamer
        LogTools.getInstance().logRecord("========== GStreamer初始化 ==========");
        //Gst.init("GpuJpegPlayerTest");

        Version version = Gst.getVersion();
        LogTools.getInstance().logRecord("GStreamer版本: " + version.getMajor() + "." +
                version.getMinor() + "." + version.getMicro());
        LogTools.getInstance().logRecord("=====================================\n");

        // 创建UI
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #2b2b2b;");

        VBox controlBox = new VBox(15);
        controlBox.setAlignment(Pos.CENTER);
        controlBox.setPadding(new Insets(30));

        Label titleLabel = new Label("🚀 GStreamer GPU JPEG 播放器");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 28px; -fx-font-weight: bold;");

        infoLabel = new Label("GPU: 检测中...");
        infoLabel.setStyle("-fx-text-fill: #00ff88; -fx-font-size: 14px;");

        Button selectButton = new Button("📁 选择JPEG图片");
        selectButton.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-padding: 15 30; -fx-background-radius: 5;");
        selectButton.setPrefWidth(300);
        selectButton.setOnAction(e -> selectAndShowImage());

        Button nextButton = new Button("▶ 下一张");
        nextButton.setStyle("-fx-background-color: #107c10; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-padding: 15 30; -fx-background-radius: 5;");
        nextButton.setPrefWidth(300);
        nextButton.setOnAction(e -> showNextImage());

        Button prevButton = new Button("◀ 上一张");
        prevButton.setStyle("-fx-background-color: #107c10; -fx-text-fill: white; " +
                "-fx-font-size: 16px; -fx-padding: 15 30; -fx-background-radius: 5;");
        prevButton.setPrefWidth(300);
        prevButton.setOnAction(e -> showPrevImage());

        statusLabel = new Label("状态：等待选择图片");
        statusLabel.setStyle("-fx-text-fill: #00ff00; -fx-font-size: 14px;");

        pathLabel = new Label("提示：图片将在独立的GStreamer窗口中显示");
        pathLabel.setStyle("-fx-text-fill: #ffaa00; -fx-font-size: 12px;");
        pathLabel.setWrapText(true);
        pathLabel.setMaxWidth(500);

        Label tipLabel = new Label("✨ Pipeline: filesrc → jpegdec → imagefreeze → videoconvert → d3d11videosink");
        tipLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 11px; -fx-font-family: monospace;");

        controlBox.getChildren().addAll(
                titleLabel, infoLabel, selectButton, nextButton, prevButton,
                statusLabel, pathLabel, tipLabel
        );

        root.setCenter(controlBox);

        Scene scene = new Scene(root, 700, 550);
        primaryStage.setScene(scene);
        primaryStage.setTitle("GStreamer GPU JPEG播放器测试");
        primaryStage.show();

        // 初始化Pipeline
        initPipeline();

        primaryStage.setOnCloseRequest(e -> {
            cleanup();
            Platform.exit();
        });
    }

    /**
     * 初始化GStreamer Pipeline（按照成功的命令行配置）⭐
     */
    private void initPipeline() {
        try {
            LogTools.getInstance().logRecord("🔧 开始初始化GStreamer Pipeline...");

            pipeline = new Pipeline("gpu-jpeg-test");

            // 1. filesrc
            filesrc = ElementFactory.make("filesrc", "file_source");
            LogTools.getInstance().logRecord("✅ 创建 filesrc");
            String normalizedPath = "D:/javafx/Acard/runtime/runtimes_000000001.jpeg";
            filesrc.set("location", normalizedPath);
            // 2. jpegdec（先尝试GPU，失败则用CPU）
            try {
                jpegdec = ElementFactory.make("nvjpegdec", "nvjpeg_decoder");
                LogTools.getInstance().logRecord("✅ 创建 nvjpegdec (GPU硬件解码)");
                updateStatus("使用NVJPEG GPU硬件解码");
                Platform.runLater(() -> infoLabel.setText("GPU: NVIDIA 硬件JPEG解码"));
            } catch (Exception e) {
                jpegdec = ElementFactory.make("jpegdec", "jpeg_decoder");
                LogTools.getInstance().logRecord("⚠️ 创建 jpegdec (CPU解码)");
                updateStatus("使用CPU JPEG解码");
                Platform.runLater(() -> infoLabel.setText("解码: CPU jpegdec"));
            }

            // 3. imagefreeze（关键！将单帧转换为视频流）⭐
            imagefreeze = ElementFactory.make("imagefreeze", "freeze");
            LogTools.getInstance().logRecord("✅ 创建 imagefreeze (冻结帧)");

            // 4. videoconvert
            videoconvert = ElementFactory.make("videoconvert", "converter");
            LogTools.getInstance().logRecord("✅ 创建 videoconvert");

            // 5. d3d11videosink
            try {
                videosink = ElementFactory.make("d3d11videosink", "d3d11_sink");
                videosink.set("sync", false);  // ⭐ 按照命令行配置
                LogTools.getInstance().logRecord("✅ 创建 d3d11videosink (DirectX GPU显示)");
            } catch (Exception e) {
                videosink = ElementFactory.make("autovideosink", "auto_sink");
                videosink.set("sync", false);
                LogTools.getInstance().logRecord("⚠️ 创建 autovideosink (自动选择)");
            }

            // ⭐ 添加所有元素到Pipeline
            pipeline.addMany(filesrc, jpegdec, imagefreeze, videoconvert, videosink);
            LogTools.getInstance().logRecord("✅ 所有元素已添加到Pipeline");

            // ⭐ 连接元素（按照命令行顺序）
            boolean linked = Element.linkMany(filesrc, jpegdec, imagefreeze, videoconvert, videosink);
            LogTools.getInstance().logRecord("🔗 Pipeline连接: " + (linked ? "成功 ✅" : "失败 ❌"));

            if (!linked) {
                System.err.println("❌ Pipeline连接失败！");
                updateStatus("Pipeline连接失败");
                return;
            }

            // 监听Bus消息（获取GPU信息）
            Bus bus = pipeline.getBus();
            bus.connect((Bus.MESSAGE) (bus1, message) -> {
                if (message.getType() == MessageType.ELEMENT) {
                    Structure structure = message.getStructure();
                    if (structure != null && structure.hasName("prepare-window-handle")) {
                        LogTools.getInstance().logRecord("📺 窗口句柄准备就绪");
                    }
                }
            });

            LogTools.getInstance().logRecord("✅ GStreamer Pipeline初始化完成！");
            LogTools.getInstance().logRecord("📋 Pipeline结构: filesrc → jpegdec → imagefreeze → videoconvert → d3d11videosink");
            updateStatus("初始化完成，请选择图片");

        } catch (Exception e) {
            System.err.println("❌ Pipeline初始化失败: " + e.getMessage());
            e.printStackTrace();
            updateStatus("初始化失败: " + e.getMessage());
        }
    }

    /**
     * 选择并显示图片
     */
    private void selectAndShowImage() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择JPEG图片");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JPEG图片", "*.jpg", "*.jpeg")
        );

        // 设置初始目录
        File initialDir = new File("runtime/captures/ssl");
        if (!initialDir.exists()) {
            initialDir = new File("runtime");
        }
        if (initialDir.exists()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        File selectedFile = fileChooser.showOpenDialog(statusLabel.getScene().getWindow());
        if (selectedFile != null) {
            lastPath = selectedFile.getAbsolutePath();
            showImage(lastPath);
        }
    }

    /**
     * 显示图片（核心方法）⭐
     */
    /**
     * 显示图片（严格按照命令行状态切换流程）⭐
     */
    private void showImage(String imagePath) {
        if (pipeline == null || filesrc == null) {
            System.err.println("❌ Pipeline未初始化");
            updateStatus("错误：Pipeline未初始化");
            return;
        }

        try {
            File file = new File(imagePath);
            if (!file.exists()) {
                System.err.println("❌ 文件不存在: " + imagePath);
                updateStatus("错误：文件不存在");
                return;
            }

            System.out.println("\n========== 显示图片 ==========");
            System.out.println("📁 文件: " + file.getName());
            System.out.println("📦 大小: " + file.length() / 1024 + " KB");

            long startTime = System.nanoTime();

            // ⭐ 步骤1: Setting pipeline to PAUSED
            System.out.println("Setting pipeline to PAUSED ...");
            //pipeline.setState(State.NULL);  // 先重置
            //Thread.sleep(50);

            // 设置文件路径
            String normalizedPath = imagePath.replace("\\", "/");
            filesrc.set("location", normalizedPath);
            System.out.println("📝 设置路径: " + normalizedPath);

            // ⭐ 步骤2: Pipeline is PREROLLING
            System.out.println("Pipeline is PREROLLING ...");
            StateChangeReturn ret1 = pipeline.setState(State.PAUSED);
            System.out.println("   setState(PAUSED) 返回: " + ret1);

            // ⭐ 步骤3: 等待 Pipeline is PREROLLED
            System.out.println("等待 Pipeline is PREROLLED ...");
            pipeline.getState(5000000000L); // 5秒超时
            State currentState = pipeline.getState();
            System.out.println("   getState() 返回: " );
            System.out.println("   当前状态: " + currentState);

            if (currentState != State.PAUSED) {
                System.err.println("❌ Pipeline未能PREROLL，当前状态: " + currentState);
                updateStatus("错误：Pipeline未能PREROLL");
                return;
            }
            System.out.println("Pipeline is PREROLLED ...");

            // ⭐ 步骤4: Setting pipeline to PLAYING
            System.out.println("Setting pipeline to PLAYING ...");
            StateChangeReturn ret2 = pipeline.setState(State.PLAYING);
            System.out.println("   setState(PLAYING) 返回: " + ret2);

            // ⭐ 步骤5: 等待完全切换到PLAYING状态
            Thread.sleep(100);  // 给窗口时间显示
            State finalState = pipeline.getState();
            System.out.println("Redistribute latency...");
            System.out.println("New clock: GstSystemClock");
            System.out.println("   最终状态: " + finalState);

            long time = (System.nanoTime() - startTime) / 1_000_000;
            frameCount++;

            Platform.runLater(() -> {
                updateStatus(String.format("✅ 显示成功 #%d | 耗时: %dms", frameCount, time));
                pathLabel.setText("路径: " + imagePath);
            });

            System.out.println("✅ 显示完成，耗时: " + time + "ms");
            System.out.println("👀 GStreamer窗口应该已显示（保持PLAYING状态）");
            System.out.println("================================\n");

        } catch (Exception e) {
            System.err.println("❌ 显示失败: " + e.getMessage());
            e.printStackTrace();
            updateStatus("错误: " + e.getMessage());
        }
    }

    /**
     * 显示下一张
     */
    private void showNextImage() {
        if (lastPath == null) {
            updateStatus("请先选择一张图片");
            return;
        }
        navigateImage(1);
    }

    /**
     * 显示上一张
     */
    private void showPrevImage() {
        if (lastPath == null) {
            updateStatus("请先选择一张图片");
            return;
        }
        navigateImage(-1);
    }

    /**
     * 导航图片（+1下一张，-1上一张）
     */
    private void navigateImage(int offset) {
        File currentFile = new File(lastPath);
        String name = currentFile.getName();
        String dir = currentFile.getParent();

        try {
            // 提取数字（支持s_000000001格式）
            String numberStr = name.replaceAll("[^0-9]", "");
            if (numberStr.isEmpty()) {
                updateStatus("无法解析文件名");
                return;
            }

            int number = Integer.parseInt(numberStr);
            int nextNumber = number + offset;

            if (nextNumber < 1) {
                updateStatus("已经是第一张");
                return;
            }

            // 格式化文件名
            String nextName = name.replaceFirst("\\d+", String.format("%09d", nextNumber));
            File nextFile = new File(dir, nextName);

            if (nextFile.exists()) {
                lastPath = nextFile.getAbsolutePath();
                showImage(lastPath);
            } else {
                updateStatus((offset > 0 ? "下一张" : "上一张") + "不存在");
            }
        } catch (Exception e) {
            updateStatus("导航失败: " + e.getMessage());
        }
    }

    /**
     * 更新状态标签
     */
    private void updateStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText("状态：" + msg));
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        LogTools.getInstance().logRecord("🧹 清理资源...");
        if (pipeline != null) {
            pipeline.setState(State.NULL);
            pipeline.dispose();
            pipeline = null;
        }
        Gst.deinit();
        LogTools.getInstance().logRecord("✅ 资源清理完成");
    }

    public static void main(String[] args) {
        launch(args);
    }
}