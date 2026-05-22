package com.acard.acard.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import com.acard.acard.slowmotion.SlowMoGpuPlayer;

import java.io.File;

/**
 * GPU慢放播放器测试对话框
 * 
 * 功能：
 * 1. 选择MP4文件测试
 * 2. 播放/暂停/停止控制
 * 3. 速度调节（0.1x ~ 2.0x）
 * 4. GPU渲染显示
 * 
 * @author AI Assistant
 * @date 2025-10-22
 */
public class SlowMoGpuTestDialog extends Stage {
    
    private SlowMoGpuPlayer gpuPlayer;
    private StackPane videoPane;
    
    private Button btnChooseFile;
    private Button btnPlay;
    private Button btnPause;
    private Button btnStop;
    private Slider speedSlider;
    private Label speedLabel;
    private Label statusLabel;
    private Label fileLabel;
    
    private String currentMp4Path;
    
    public SlowMoGpuTestDialog() {
        initUI();
        setupHandlers();
    }
    
    private void initUI() {
        setTitle("GPU慢放播放器测试");
        setWidth(800);
        setHeight(600);
        initModality(Modality.NONE);  // 非模态，可以同时看实时流
        
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #2b2b2b;");
        
        // 1. 文件选择区域
        HBox fileBox = new HBox(10);
        fileBox.setAlignment(Pos.CENTER_LEFT);
        
        btnChooseFile = new Button("选择MP4文件");
        btnChooseFile.setStyle("-fx-font-size: 13; -fx-padding: 8 20;");
        
        fileLabel = new Label("未选择文件");
        fileLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 12;");
        
        fileBox.getChildren().addAll(btnChooseFile, fileLabel);
        
        // 2. 视频显示区域（GPU渲染）
        videoPane = new StackPane();
        videoPane.setStyle("-fx-background-color: #000000;");
        videoPane.setMinHeight(400);
        videoPane.setMaxHeight(400);
        VBox.setVgrow(videoPane, Priority.ALWAYS);
        
        Label videoPlaceholder = new Label("🎬 GPU视频渲染区域");
        videoPlaceholder.setStyle("-fx-text-fill: #666; -fx-font-size: 16;");
        videoPane.getChildren().add(videoPlaceholder);
        
        // 3. 播放控制区域
        HBox controlBox = new HBox(10);
        controlBox.setAlignment(Pos.CENTER);
        
        btnPlay = new Button("▶️ 播放");
        btnPlay.setStyle("-fx-font-size: 14; -fx-padding: 8 20; -fx-background-color: #4CAF50; -fx-text-fill: white;");
        btnPlay.setDisable(true);
        
        btnPause = new Button("⏸️ 暂停");
        btnPause.setStyle("-fx-font-size: 14; -fx-padding: 8 20; -fx-background-color: #FF9800; -fx-text-fill: white;");
        btnPause.setDisable(true);
        
        btnStop = new Button("⏹️ 停止");
        btnStop.setStyle("-fx-font-size: 14; -fx-padding: 8 20; -fx-background-color: #F44336; -fx-text-fill: white;");
        btnStop.setDisable(true);
        
        controlBox.getChildren().addAll(btnPlay, btnPause, btnStop);
        
        // 4. 速度控制区域
        HBox speedBox = new HBox(10);
        speedBox.setAlignment(Pos.CENTER);
        
        Label speedTitleLabel = new Label("播放速度：");
        speedTitleLabel.setStyle("-fx-text-fill: #fff; -fx-font-size: 13;");
        
        speedSlider = new Slider(0.1, 2.0, 1.0);
        speedSlider.setShowTickLabels(true);
        speedSlider.setShowTickMarks(true);
        speedSlider.setMajorTickUnit(0.5);
        speedSlider.setMinorTickCount(4);
        speedSlider.setPrefWidth(300);
        speedSlider.setDisable(true);
        
        speedLabel = new Label("1.0x");
        speedLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 14; -fx-font-weight: bold; -fx-min-width: 60;");
        
        speedBox.getChildren().addAll(speedTitleLabel, speedSlider, speedLabel);
        
        // 5. 状态信息区域
        statusLabel = new Label("💡 提示：选择MP4文件开始测试");
        statusLabel.setStyle("-fx-text-fill: #888; -fx-font-size: 12; -fx-padding: 5;");
        statusLabel.setWrapText(true);
        
        // 6. 说明区域
        VBox infoBox = new VBox(5);
        infoBox.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-padding: 10; -fx-background-radius: 5;");
        
        Label infoTitle = new Label("📋 测试说明：");
        infoTitle.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 13; -fx-font-weight: bold;");
        
        Label info1 = new Label("• GPU加速：使用d3d11h264dec硬解 + appsink + Canvas渲染");
        Label info2 = new Label("• 完全独立：不依赖HWND，完全隔离实时流（element2_1）");
        Label info3 = new Label("• 实时调速：拖动滑块即可调整速度（0.1x ~ 2.0x）");
        Label info4 = new Label("• 不跳帧：每帧都显示，丝滑播放");
        info1.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        info2.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        info3.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        info4.setStyle("-fx-text-fill: #ccc; -fx-font-size: 11;");
        
        infoBox.getChildren().addAll(infoTitle, info1, info2, info3, info4);
        
        // 组装
        root.getChildren().addAll(
            fileBox,
            videoPane,
            controlBox,
            speedBox,
            statusLabel,
            infoBox
        );
        
        Scene scene = new Scene(root);
        setScene(scene);
        
        // 关闭时清理资源
        setOnCloseRequest(e -> {
            cleanup();
        });
    }
    
    private void setupHandlers() {
        // 选择文件
        btnChooseFile.setOnAction(e -> chooseFile());
        
        // 播放
        btnPlay.setOnAction(e -> {
            if (currentMp4Path != null) {
                play();
            }
        });
        
        // 暂停
        btnPause.setOnAction(e -> {
            if (gpuPlayer != null) {
                gpuPlayer.pause();
                updateStatus("⏸️ 已暂停");
            }
        });
        
        // 停止
        btnStop.setOnAction(e -> {
            if (gpuPlayer != null) {
                gpuPlayer.stop();
                updateStatus("⏹️ 已停止");
                btnPlay.setDisable(false);
                btnPause.setDisable(true);
                btnStop.setDisable(true);
            }
        });
        
        // 速度调节
        speedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double rate = Math.round(newVal.doubleValue() * 10.0) / 10.0;  // 保留1位小数
            speedLabel.setText(String.format("%.1fx", rate));
            
            if (gpuPlayer != null && gpuPlayer.isPlaying()) {
                gpuPlayer.setRate(rate);
                updateStatus("⚡ 速度已调整: " + String.format("%.1fx", rate));
            }
        });
    }
    
    private void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择MP4文件");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("MP4视频", "*.mp4")
        );
        
        // 默认打开slowmo目录
        File defaultDir = new File("runtime/slowmo");
        if (defaultDir.exists()) {
            fileChooser.setInitialDirectory(defaultDir);
        }
        
        File file = fileChooser.showOpenDialog(this);
        if (file != null) {
            currentMp4Path = file.getAbsolutePath();
            fileLabel.setText(file.getName());
            fileLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 12; -fx-font-weight: bold;");
            
            btnPlay.setDisable(false);
            speedSlider.setDisable(false);
            
            updateStatus("✅ 文件已选择: " + file.getName());
        }
    }
    
    private void play() {
        try {
            // 清理旧播放器
            if (gpuPlayer != null) {
                gpuPlayer.stop();
                gpuPlayer.dispose();
            }
            
            // 创建GPU播放器
            gpuPlayer = new SlowMoGpuPlayer();
            gpuPlayer.loadMp4(currentMp4Path);
            
            // 绑定到videoPane
            gpuPlayer.attachToPane(videoPane);
            
            // 设置速度
            double rate = speedSlider.getValue();
            gpuPlayer.setRate(rate);
            
            // 播放
            gpuPlayer.play();
            
            btnPlay.setDisable(true);
            btnPause.setDisable(false);
            btnStop.setDisable(false);
            
            updateStatus("▶️ 播放中（速度=" + String.format("%.1fx", rate) + "，GPU硬解+Canvas渲染，完全隔离）");
            
            // 清除占位符
            if (!videoPane.getChildren().isEmpty()) {
                videoPane.getChildren().clear();
            }
            
        } catch (Exception ex) {
            updateStatus("❌ 播放失败: " + ex.getMessage());
            ex.printStackTrace();
            
            Alert alert = new Alert(Alert.AlertType.ERROR);
            // ⭐ 设置父窗口，防止全屏时层级错乱
            alert.initOwner(this);
            alert.setOnShowing(e -> this.toFront());
            alert.setOnHidden(e -> javafx.application.Platform.runLater(() -> { this.toFront(); this.requestFocus(); }));
            alert.setTitle("播放失败");
            alert.setHeaderText("GPU播放器启动失败");
            alert.setContentText(ex.getMessage());
            alert.showAndWait();
        }
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
        System.out.println("[SlowMoGpuTestDialog] " + message);
    }
    
    private void cleanup() {
        if (gpuPlayer != null) {
            gpuPlayer.stop();
            gpuPlayer.dispose();
            gpuPlayer = null;
        }
        System.out.println("🗑️ SlowMoGpuTestDialog: 资源已清理");
    }
    
    public void showDialog() {
        show();
        updateStatus("💡 提示：选择MP4文件开始测试");
    }
}

