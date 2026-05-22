package com.acard.acard.ui;

import com.acard.acard.tools.FileToos;
import com.acard.acard.tools.LogTools;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ⚡ 抓拍对比弹框
 * 
 * 功能：
 * - 左边：实时流截图（抓拍瞬间）
 * - 右边：JPEG 文件（同一时刻）
 * - 直观对比延迟差距
 */
public class RealtimeCaptureTestDialog extends Stage {
    
    private final GpuView gpuView;
    
    // UI组件
    private Label statusLabel;
    private Label delayLabel;
    private ImageView screenshotView;  // 实时流截图
    private ImageView jpegView;        // JPEG 图片
    private Label screenshotLabel;
    private Label jpegLabel;
    private Spinner<Integer> offsetSpinner;  // JPEG 偏移调整
    
    // 抓拍数据
    private Image screenshotImage;      // 截图
    private long captureGpuIndex;       // 抓拍时 GPU 索引
    private long captureJpegIndex;      // 抓拍时 JPEG 索引
    private long measuredDelay;         // 测量延迟
    private int currentJpegOffset = 0;  // JPEG 偏移
    
    // 等待器
    private ScheduledExecutorService waitExecutor;
    
    public RealtimeCaptureTestDialog(Stage owner, GpuView gpuView) {
        this.gpuView = gpuView;
        
        initOwner(owner);
        initModality(Modality.NONE);
        initStyle(StageStyle.DECORATED);
        setTitle("⚡ 抓拍对比 - 实时流 vs JPEG");
        setWidth(900);
        setHeight(580);
        
        // 立即截图
        takeScreenshot();
        
        initUI();
        
        // 等待 JPEG 并加载
        loadJpegImage();
        
        setOnCloseRequest(e -> cleanup());
    }
    
    /**
     * ⚡ 截取实时流画面
     */
    private void takeScreenshot() {
        // 记录索引
        captureGpuIndex = FileToos.GpuIndex;
        captureJpegIndex = FileToos.jpegIndex;
        measuredDelay = captureGpuIndex - captureJpegIndex;
        
        // 截图 GpuView
        if (gpuView != null) {
            try {
                WritableImage snapshot = gpuView.snapshot(new SnapshotParameters(), null);
                screenshotImage = snapshot;
                LogTools.getInstance().logRecord5("⚡ 截图成功: GpuIndex=" + captureGpuIndex + 
                    ", jpegIndex=" + captureJpegIndex + ", 延迟=" + measuredDelay);
            } catch (Exception e) {
                LogTools.getInstance().logRecord2("❌ 截图失败: " + e.getMessage());
            }
        }
    }
    
    private void initUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #1a1a2e;");
        
        // ========== 状态栏 ==========
        HBox statusBar = new HBox(20);
        statusBar.setAlignment(Pos.CENTER);
        statusBar.setStyle("-fx-background-color: #16213e; -fx-background-radius: 8; -fx-padding: 10;");
        
        statusLabel = new Label("⏳ 加载中...");
        statusLabel.setStyle("-fx-text-fill: #ffa502; -fx-font-size: 13px;");
        
        delayLabel = new Label("延迟: " + measuredDelay + " 帧 (Gpu=" + captureGpuIndex + ", Jpeg=" + captureJpegIndex + ")");
        delayLabel.setStyle("-fx-text-fill: #00d4ff; -fx-font-size: 13px; -fx-font-weight: bold;");
        
        statusBar.getChildren().addAll(statusLabel, delayLabel);
        
        // ========== 对比区域 ==========
        HBox compareBox = new HBox(20);
        compareBox.setAlignment(Pos.CENTER);
        VBox.setVgrow(compareBox, Priority.ALWAYS);
        
        // 左边：实时流截图
        VBox leftBox = new VBox(5);
        leftBox.setAlignment(Pos.CENTER);
        leftBox.setStyle("-fx-background-color: #0f0f23; -fx-background-radius: 8; -fx-padding: 10;");
        
        screenshotLabel = new Label("📺 实时流截图 (GpuIndex=" + captureGpuIndex + ")");
        screenshotLabel.setStyle("-fx-text-fill: #00ff88; -fx-font-size: 12px; -fx-font-weight: bold;");
        
        screenshotView = new ImageView();
        screenshotView.setFitWidth(400);
        screenshotView.setFitHeight(300);
        screenshotView.setPreserveRatio(true);
        screenshotView.setStyle("-fx-effect: dropshadow(gaussian, #00ff88, 8, 0, 0, 0);");
        
        if (screenshotImage != null) {
            screenshotView.setImage(screenshotImage);
        }
        
        leftBox.getChildren().addAll(screenshotLabel, screenshotView);
        
        // 右边：JPEG 图片
        VBox rightBox = new VBox(5);
        rightBox.setAlignment(Pos.CENTER);
        rightBox.setStyle("-fx-background-color: #0f0f23; -fx-background-radius: 8; -fx-padding: 10;");
        
        jpegLabel = new Label("📁 JPEG 文件 (等待加载...)");
        jpegLabel.setStyle("-fx-text-fill: #607afb; -fx-font-size: 12px; -fx-font-weight: bold;");
        
        jpegView = new ImageView();
        jpegView.setFitWidth(400);
        jpegView.setFitHeight(300);
        jpegView.setPreserveRatio(true);
        jpegView.setStyle("-fx-effect: dropshadow(gaussian, #607afb, 8, 0, 0, 0);");
        
        rightBox.getChildren().addAll(jpegLabel, jpegView);
        
        compareBox.getChildren().addAll(leftBox, rightBox);
        
        // ========== 控制栏 ==========
        HBox controlBar = new HBox(15);
        controlBar.setAlignment(Pos.CENTER);
        controlBar.setStyle("-fx-background-color: #16213e; -fx-background-radius: 8; -fx-padding: 10;");
        
        Label offsetLbl = new Label("JPEG 偏移:");
        offsetLbl.setStyle("-fx-text-fill: white;");
        
        offsetSpinner = new Spinner<>(-60, 60, 0);
        offsetSpinner.setPrefWidth(80);
        offsetSpinner.setEditable(true);
        offsetSpinner.valueProperty().addListener((obs, old, val) -> {
            currentJpegOffset = val;
            reloadJpeg();
        });
        
        Button prevBtn = new Button("◀ -1");
        prevBtn.setStyle("-fx-background-color: #607afb; -fx-text-fill: white;");
        prevBtn.setOnAction(e -> {
            offsetSpinner.getValueFactory().decrement(1);
        });
        
        Button nextBtn = new Button("+1 ▶");
        nextBtn.setStyle("-fx-background-color: #607afb; -fx-text-fill: white;");
        nextBtn.setOnAction(e -> {
            offsetSpinner.getValueFactory().increment(1);
        });
        
        Button syncBtn = new Button("🎯 自动同步 (+" + measuredDelay + ")");
        syncBtn.setStyle("-fx-background-color: #00ff88; -fx-text-fill: #1a1a2e; -fx-font-weight: bold;");
        syncBtn.setOnAction(e -> {
            offsetSpinner.getValueFactory().setValue((int) measuredDelay);
        });
        
        Button refreshBtn = new Button("🔄 重新抓拍");
        refreshBtn.setStyle("-fx-background-color: #ffa502; -fx-text-fill: white;");
        refreshBtn.setOnAction(e -> {
            takeScreenshot();
            screenshotView.setImage(screenshotImage);
            screenshotLabel.setText("📺 实时流截图 (GpuIndex=" + captureGpuIndex + ")");
            delayLabel.setText("延迟: " + measuredDelay + " 帧 (Gpu=" + captureGpuIndex + ", Jpeg=" + captureJpegIndex + ")");
            syncBtn.setText("🎯 自动同步 (+" + measuredDelay + ")");
            loadJpegImage();
        });
        
        controlBar.getChildren().addAll(offsetLbl, prevBtn, offsetSpinner, nextBtn, syncBtn, refreshBtn);
        
        // ========== 说明 ==========
        Label helpLabel = new Label(
            "💡 左边是抓拍瞬间的实时流画面，右边是对应的 JPEG 文件\n" +
            "📊 调整「JPEG 偏移」直到两边画面一致，偏移值 = 实际延迟帧数\n" +
            "🎯 点击「自动同步」使用测量的延迟值: " + measuredDelay + " 帧"
        );
        helpLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 11px;");
        helpLabel.setWrapText(true);
        
        root.getChildren().addAll(statusBar, compareBox, controlBar, helpLabel);
        
        Scene scene = new Scene(root);
        
        // 键盘绑定
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.LEFT) offsetSpinner.getValueFactory().decrement(1);
            else if (e.getCode() == KeyCode.RIGHT) offsetSpinner.getValueFactory().increment(1);
            else if (e.getCode() == KeyCode.ESCAPE) close();
        });
        
        setScene(scene);
    }
    
    /**
     * ⚡ 加载 JPEG 图片
     */
    private void loadJpegImage() {
        // 计算要加载的 JPEG 索引 = jpegIndex + offset
        long targetIndex = captureJpegIndex + currentJpegOffset;
        
        statusLabel.setText("⏳ 等待 JPEG #" + targetIndex + "...");
        
        waitExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JpegWait");
            t.setDaemon(true);
            return t;
        });
        
        waitExecutor.scheduleAtFixedRate(() -> {
            long currentJpeg = FileToos.jpegIndex;
            
            // JPEG 已写入
            if (currentJpeg >= targetIndex) {
                waitExecutor.shutdown();
                Platform.runLater(() -> reloadJpeg());
            } else {
                Platform.runLater(() -> {
                    statusLabel.setText("⏳ 等待 JPEG: " + currentJpeg + " / " + targetIndex);
                });
            }
        }, 0, 30, TimeUnit.MILLISECONDS);
    }
    
    /**
     * ⚡ 重新加载 JPEG
     */
    private void reloadJpeg() {
        long targetIndex = captureJpegIndex + currentJpegOffset;
        String jpegPath = "runtime/captures/ssl/s_" + String.format("%09d", targetIndex) + ".jpeg";
        File jpegFile = new File(jpegPath);
        
        if (jpegFile.exists()) {
            try {
                Image img = new Image(new FileInputStream(jpegFile));
                jpegView.setImage(img);
                jpegLabel.setText("📁 JPEG #" + targetIndex + " (偏移=" + currentJpegOffset + ")");
                statusLabel.setText("✅ 对比就绪");
                statusLabel.setStyle("-fx-text-fill: #00ff88; -fx-font-size: 13px;");
                
                // 判断是否同步
                if (currentJpegOffset == measuredDelay) {
                    jpegLabel.setText("📁 JPEG #" + targetIndex + " 🎯已同步");
                    jpegLabel.setStyle("-fx-text-fill: #00ff88; -fx-font-size: 12px; -fx-font-weight: bold;");
                } else {
                    jpegLabel.setStyle("-fx-text-fill: #607afb; -fx-font-size: 12px; -fx-font-weight: bold;");
                }
            } catch (Exception e) {
                jpegView.setImage(null);
                jpegLabel.setText("❌ 加载失败: " + jpegPath);
                statusLabel.setText("❌ JPEG 加载失败");
                statusLabel.setStyle("-fx-text-fill: #ff6b6b;");
            }
        } else {
            jpegView.setImage(null);
            jpegLabel.setText("❌ 文件不存在: #" + targetIndex);
            statusLabel.setText("⏳ 等待 JPEG 写入...");
            statusLabel.setStyle("-fx-text-fill: #ffa502;");
        }
    }
    
    private void cleanup() {
        if (waitExecutor != null && !waitExecutor.isShutdown()) {
            waitExecutor.shutdown();
        }
    }
    
    public static void show(Stage owner, GpuView gpuView) {
        RealtimeCaptureTestDialog dialog = new RealtimeCaptureTestDialog(owner, gpuView);
        dialog.show();
        dialog.requestFocus();
    }
}
