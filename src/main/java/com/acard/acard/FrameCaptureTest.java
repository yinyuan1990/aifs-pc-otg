package com.acard.acard;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * 帧捕获测试类
 * 演示如何使用GstWebRTCPlayerView的帧捕获功能
 */
public class FrameCaptureTest extends Application {
    
    private GstWebRTCPlayerView playerView;
    private Label statusLabel;
    
    @Override
    public void start(Stage primaryStage) {
        // 创建WebRTC播放器视图
        // 请根据实际情况修改这些参数
        String host = "localhost";  // SRS服务器地址
        int apiPort = 1985;         // SRS API端口
        String app = "live";        // 应用名
        String stream = "test";     // 流名
        
        playerView = new GstWebRTCPlayerView(host, apiPort, app, stream);
        
        // 创建控制界面
        statusLabel = new Label("状态: 准备就绪");
        
        Button startCaptureBtn = new Button("开始捕获帧 (1秒)");
        startCaptureBtn.setOnAction(e -> startFrameCapture());
        
        Button stopCaptureBtn = new Button("停止捕获");
        stopCaptureBtn.setOnAction(e -> stopFrameCapture());
        
        Button statusBtn = new Button("查看状态");
        statusBtn.setOnAction(e -> updateStatus());
        
        VBox controls = new VBox(10);
        controls.getChildren().addAll(
            statusLabel,
            startCaptureBtn,
            stopCaptureBtn,
            statusBtn
        );
        
        VBox root = new VBox(10);
        root.getChildren().addAll(playerView, controls);
        
        Scene scene = new Scene(root, 1300, 800);
        primaryStage.setTitle("WebRTC 帧捕获测试");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // 程序关闭时清理资源
        primaryStage.setOnCloseRequest(e -> {
            if (playerView.isCapturingFrames()) {
                playerView.stopFrameCapture();
            }
            Platform.exit();
        });
    }
    
    private void startFrameCapture() {
        if (playerView.isCapturingFrames()) {
            statusLabel.setText("状态: 已在捕获中，请先停止");
            return;
        }
        
        // 创建带时间戳的输出目录
        String outputDir = FrameCapturer.createTimestampedOutputDir("D:\\zhen");
        
        // 开始捕获1秒的帧
        playerView.startFrameCapture(outputDir, 1000);
        
        statusLabel.setText("状态: 开始捕获帧到 " + outputDir);
        
        // 1.5秒后自动更新状态
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                Platform.runLater(this::updateStatus);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void stopFrameCapture() {
        playerView.stopFrameCapture();
        updateStatus();
    }
    
    private void updateStatus() {
        if (playerView.isCapturingFrames()) {
            int frameCount = playerView.getCapturedFrameCount();
            statusLabel.setText("状态: 正在捕获... 已捕获 " + frameCount + " 帧");
        } else {
            int frameCount = playerView.getCapturedFrameCount();
            statusLabel.setText("状态: 捕获完成，总共 " + frameCount + " 帧");
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}