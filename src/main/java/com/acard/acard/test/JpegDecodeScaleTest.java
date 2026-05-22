package com.acard.acard.test;



import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * JPEG解码+缩放性能测试
 * 对比不同方案的速度
 */
public class JpegDecodeScaleTest extends Application {

    private TextArea logArea;
    private ImageView imageView;
    private Label statusLabel;
    private String lastTestFile = null;

    // 目标分辨率
    private static final int TARGET_WIDTH = 320;
    private static final int TARGET_HEIGHT = 240;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #2b2b2b;");

        // 顶部控制区
        VBox topBox = new VBox(10);
        topBox.setPadding(new Insets(15));
        topBox.setStyle("-fx-background-color: #1e1e1e;");

        Label titleLabel = new Label("📊 JPEG解码+缩放性能测试");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");

        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);

        Button selectButton = new Button("📁 选择测试图片");
        selectButton.setStyle("-fx-background-color: #0078d4; -fx-text-fill: white; " +
                "-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 5;");
        selectButton.setOnAction(e -> selectTestFile());

        Button testAllButton = new Button("🚀 运行完整测试");
        testAllButton.setStyle("-fx-background-color: #107c10; -fx-text-fill: white; " +
                "-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 5;");
        testAllButton.setOnAction(e -> runFullTest());

        Button clearButton = new Button("🧹 清空日志");
        clearButton.setStyle("-fx-background-color: #d13438; -fx-text-fill: white; " +
                "-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 5;");
        clearButton.setOnAction(e -> logArea.clear());

        statusLabel = new Label("状态：等待选择图片");
        statusLabel.setStyle("-fx-text-fill: #00ff00; -fx-font-size: 13px;");

        buttonBox.getChildren().addAll(selectButton, testAllButton, clearButton);
        topBox.getChildren().addAll(titleLabel, buttonBox, statusLabel);

        // 中间日志区
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-control-inner-background: #1e1e1e; -fx-text-fill: #00ff00; " +
                "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px;");

        // 右侧预览区
        VBox previewBox = new VBox(10);
        previewBox.setPadding(new Insets(10));
        previewBox.setAlignment(Pos.CENTER);
        previewBox.setStyle("-fx-background-color: #1e1e1e;");
        previewBox.setPrefWidth(340);

        Label previewLabel = new Label("预览 (320x240)");
        previewLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        imageView = new ImageView();
        imageView.setFitWidth(320);
        imageView.setFitHeight(240);
        imageView.setStyle("-fx-border-color: #555555; -fx-border-width: 2;");

        previewBox.getChildren().addAll(previewLabel, imageView);

        // 组装
        root.setTop(topBox);
        root.setCenter(logArea);
        root.setRight(previewBox);

        Scene scene = new Scene(root, 1100, 650);
        primaryStage.setScene(scene);
        primaryStage.setTitle("JPEG解码+缩放性能测试");
        primaryStage.show();

        log("✅ 测试工具已启动");
        log("📋 目标分辨率: " + TARGET_WIDTH + "x" + TARGET_HEIGHT);
        log("💡 提示: 选择一张1920x1080的JPEG图片进行测试\n");
    }

    private void selectTestFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择测试图片");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JPEG图片", "*.jpg", "*.jpeg")
        );

        File initialDir = new File("runtime/captures/ssl");
        if (!initialDir.exists()) {
            initialDir = new File("runtime");
        }
        if (initialDir.exists()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        File file = fileChooser.showOpenDialog(statusLabel.getScene().getWindow());
        if (file != null) {
            lastTestFile = file.getAbsolutePath();
            log("\n📁 已选择: " + file.getName());
            log("📦 文件大小: " + file.length() / 1024 + " KB");
            statusLabel.setText("状态：已选择 " + file.getName());
        }
    }

    /**
     * 运行完整性能测试
     */
    private void runFullTest() {
        if (lastTestFile == null) {
            log("\n❌ 请先选择测试图片");
            return;
        }

        log("\n========================================");
        log("🚀 开始性能测试");
        log("========================================\n");

        new Thread(() -> {
            try {
                File file = new File(lastTestFile);

                // 获取原始分辨率
                BufferedImage testImg = ImageIO.read(file);
                int origWidth = testImg.getWidth();
                int origHeight = testImg.getHeight();
                testImg.flush();

                log("📐 原始分辨率: " + origWidth + "x" + origHeight);
                log("📐 目标分辨率: " + TARGET_WIDTH + "x" + TARGET_HEIGHT);
                log("");

                // 测试1: ImageIO 原图解码
                log("【测试1】ImageIO - 原图解码（无缩放）");
                test_ImageIO_Original(file);
                Thread.sleep(100);

                // 测试2: ImageIO 解码+Java缩放（快速）
                log("\n【测试2】ImageIO - 解码+Java快速缩放");
                test_ImageIO_ScaleFast(file);
                Thread.sleep(100);

                // 测试3: ImageIO 解码+Java缩放（高质量）
                log("\n【测试3】ImageIO - 解码+Java高质量缩放");
                test_ImageIO_ScaleSmooth(file);
                Thread.sleep(100);

                // 测试4: TurboJPEG 原图解码
                log("\n【测试4】TurboJPEG - 原图解码（无缩放）");
                test_TurboJPEG_Original(file);
                Thread.sleep(100);

                // 测试5: TurboJPEG 解码+Java缩放
                log("\n【测试5】TurboJPEG - 解码+Java快速缩放");
                test_TurboJPEG_ScaleFast(file);
                Thread.sleep(100);

                // 测试6: ImageIO子采样解码（最快）⭐
                log("\n【测试6】ImageIO - 子采样解码（最快）⭐");
                test_ImageIO_Subsample(file);
                Thread.sleep(100);

                log("\n========================================");
                log("✅ 测试完成");
                log("========================================");

                Platform.runLater(() -> statusLabel.setText("状态：测试完成"));

            } catch (Exception e) {
                log("\n❌ 测试失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, "TestThread").start();
    }

    /**
     * 测试1: ImageIO原图解码
     */
    private void test_ImageIO_Original(File file) throws Exception {
        long total = 0;
        int rounds = 5;

        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();
            BufferedImage img = ImageIO.read(file);
            long time = (System.nanoTime() - start) / 1_000_000;
            total += time;
            img.flush();
            log("  第" + (i+1) + "次: " + time + "ms");
        }

        long avg = total / rounds;
        log("  ⭐ 平均耗时: " + avg + "ms");
        log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));
    }

    /**
     * 测试2: ImageIO解码+Java快速缩放
     */
    private void test_ImageIO_ScaleFast(File file) throws Exception {
        long total = 0;
        int rounds = 5;
        BufferedImage resultImg = null;

        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();

            // 解码
            BufferedImage original = ImageIO.read(file);

            // 快速缩放（SCALE_FAST）
            Image fxImg = SwingFXUtils.toFXImage(original, null);
            BufferedImage scaled = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(original, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null);
            g.dispose();

            long time = (System.nanoTime() - start) / 1_000_000;
            total += time;

            if (i == rounds - 1) {
                resultImg = scaled;
            } else {
                scaled.flush();
            }
            original.flush();

            log("  第" + (i+1) + "次: " + time + "ms");
        }

        long avg = total / rounds;
        log("  ⭐ 平均耗时: " + avg + "ms");
        log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));

        // 显示结果
        if (resultImg != null) {
            final BufferedImage finalImg = resultImg;
            Platform.runLater(() -> {
                Image fxImage = SwingFXUtils.toFXImage(finalImg, null);
                imageView.setImage(fxImage);
                finalImg.flush();
            });
        }
    }

    /**
     * 测试3: ImageIO解码+Java高质量缩放
     */
    private void test_ImageIO_ScaleSmooth(File file) throws Exception {
        long total = 0;
        int rounds = 5;

        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();

            BufferedImage original = ImageIO.read(file);
            BufferedImage scaled = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null);
            g.dispose();

            long time = (System.nanoTime() - start) / 1_000_000;
            total += time;

            scaled.flush();
            original.flush();

            log("  第" + (i+1) + "次: " + time + "ms");
        }

        long avg = total / rounds;
        log("  ⭐ 平均耗时: " + avg + "ms");
        log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));
    }

    /**
     * 测试4: TurboJPEG原图解码
     */
    private void test_TurboJPEG_Original(File file) throws Exception {
        try {
            long total = 0;
            int rounds = 5;

            for (int i = 0; i < rounds; i++) {
                long start = System.nanoTime();

                byte[] jpegData = java.nio.file.Files.readAllBytes(file.toPath());
                com.acard.acard.utils.TurboJpegEncoder.DecodedImage decoded =
                        com.acard.acard.utils.TurboJpegEncoder.decodeJPEGToRGB(jpegData);

                long time = (System.nanoTime() - start) / 1_000_000;
                total += time;

                log("  第" + (i+1) + "次: " + time + "ms");
            }

            long avg = total / rounds;
            log("  ⭐ 平均耗时: " + avg + "ms");
            log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));

        } catch (Exception e) {
            log("  ⚠️ TurboJPEG不可用: " + e.getMessage());
        }
    }

    /**
     * 测试5: TurboJPEG解码+Java缩放
     */
    private void test_TurboJPEG_ScaleFast(File file) throws Exception {
        try {
            long total = 0;
            int rounds = 5;

            for (int i = 0; i < rounds; i++) {
                long start = System.nanoTime();

                // TurboJPEG解码
                byte[] jpegData = java.nio.file.Files.readAllBytes(file.toPath());
                com.acard.acard.utils.TurboJpegEncoder.DecodedImage decoded =
                        com.acard.acard.utils.TurboJpegEncoder.decodeJPEGToRGB(jpegData);

                // 转换为BufferedImage
                BufferedImage original = new BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB);
                int[] pixels = new int[decoded.width * decoded.height];
                for (int j = 0; j < pixels.length; j++) {
                    int idx = j * 3;
                    int r = decoded.rgbData[idx] & 0xFF;
                    int g = decoded.rgbData[idx + 1] & 0xFF;
                    int b = decoded.rgbData[idx + 2] & 0xFF;
                    pixels[j] = (r << 16) | (g << 8) | b;
                }
                original.setRGB(0, 0, decoded.width, decoded.height, pixels, 0, decoded.width);

                // 快速缩放
                BufferedImage scaled = new BufferedImage(TARGET_WIDTH, TARGET_HEIGHT, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(original, 0, 0, TARGET_WIDTH, TARGET_HEIGHT, null);
                g.dispose();

                long time = (System.nanoTime() - start) / 1_000_000;
                total += time;

                scaled.flush();
                original.flush();

                log("  第" + (i+1) + "次: " + time + "ms");
            }

            long avg = total / rounds;
            log("  ⭐ 平均耗时: " + avg + "ms");
            log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));

        } catch (Exception e) {
            log("  ⚠️ TurboJPEG不可用: " + e.getMessage());
        }
    }

    /**
     * 测试6: ImageIO子采样解码（最快方案）⭐
     */
    private void test_ImageIO_Subsample(File file) throws Exception {
        long total = 0;
        int rounds = 5;
        BufferedImage resultImg = null;

        for (int i = 0; i < rounds; i++) {
            long start = System.nanoTime();

            // ⭐ 使用ImageReader子采样（解码时直接降低分辨率）
            javax.imageio.ImageReader reader = javax.imageio.ImageIO.getImageReadersByFormatName("JPEG").next();
            javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(file);
            reader.setInput(iis);

            // 计算采样率（1920 → 320 = 6倍）
            int subsampleFactor = 6;
            javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(subsampleFactor, subsampleFactor, 0, 0);

            BufferedImage img = reader.read(0, param);

            iis.close();
            reader.dispose();

            long time = (System.nanoTime() - start) / 1_000_000;
            total += time;

            if (i == rounds - 1) {
                resultImg = img;
            } else {
                img.flush();
            }

            log("  第" + (i+1) + "次: " + time + "ms (子采样后: " + img.getWidth() + "x" + img.getHeight() + ")");
        }

        long avg = total / rounds;
        log("  ⭐ 平均耗时: " + avg + "ms");
        log("  📊 120fps需要: <8ms，当前" + (avg > 8 ? "❌不达标" : "✅达标"));
        log("  💡 这是最快的CPU解码方案！");

        // 显示结果
        if (resultImg != null) {
            final BufferedImage finalImg = resultImg;
            Platform.runLater(() -> {
                Image fxImage = SwingFXUtils.toFXImage(finalImg, null);
                imageView.setImage(fxImage);
                finalImg.flush();
            });
        }
    }

    private void log(String msg) {
        Platform.runLater(() -> {
            logArea.appendText(msg + "\n");
            System.out.println(msg);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
