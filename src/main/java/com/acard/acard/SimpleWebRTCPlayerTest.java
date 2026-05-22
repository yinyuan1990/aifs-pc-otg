package com.acard.acard;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.Timer;
import java.util.TimerTask;

/**
 * SimpleWebRTCPlayer测试应用
 * 用于测试简化的WebRTC播放器功能
 */
public class SimpleWebRTCPlayerTest extends Application {
    
    private SimpleWebRTCPlayer player;
    private Label statusLabel;
    private Timer statusTimer;
    
    @Override
    public void start(Stage primaryStage) {
        System.out.println("🚀 启动SimpleWebRTCPlayer测试应用");
        
        // 创建播放器实例
        player = new SimpleWebRTCPlayer(
            "171.80.4.72", 
            1985, 
            "tenantA", 
            "VID_1A191D98F454E3E4BAE32DBF50C7"
        );
        
        // 创建UI
        BorderPane root = new BorderPane();
        
        // 顶部控制面板
        HBox controlPanel = createControlPanel();
        root.setTop(controlPanel);
        
        // 中央视频显示区域
        root.setCenter(player.getImageView());
        
        // 底部状态显示
        VBox statusPanel = createStatusPanel();
        root.setBottom(statusPanel);
        
        // 创建场景
        Scene scene = new Scene(root, 800, 900);
        primaryStage.setTitle("SimpleWebRTC播放器测试 - NAL单元监控");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // 启动状态更新定时器
        startStatusTimer();
        
        // 程序关闭时清理资源
        primaryStage.setOnCloseRequest(event -> {
            cleanup();
        });
    }
    
    /**
     * 创建控制面板
     */
    private HBox createControlPanel() {
        HBox panel = new HBox(10);
        panel.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0;");
        
        Button startButton = new Button("▶️ 开始播放");
        startButton.setOnAction(e -> {
            System.out.println("🎬 用户点击开始播放");
            player.start();
        });
        
        Button stopButton = new Button("⏹️ 停止播放");
        stopButton.setOnAction(e -> {
            System.out.println("🛑 用户点击停止播放");
            player.stop();
        });
        
        panel.getChildren().addAll(startButton, stopButton);
        return panel;
    }
    
    /**
     * 创建状态面板
     */
    private VBox createStatusPanel() {
        VBox panel = new VBox(5);
        panel.setStyle("-fx-padding: 10; -fx-background-color: #e8e8e8;");
        
        Label titleLabel = new Label("📊 NAL单元接收状态监控");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        statusLabel = new Label("等待开始...");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        
        Label infoLabel = new Label(
            "目标: 确保接收到 SPS(7) + PPS(8) + IDR(5) 才能正常解码\n" +
            "监控: P-slice(1) 和 FU-A(28) 的接收状态\n" +
            "机制: PLI请求获取缺失的关键帧"
        );
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        
        panel.getChildren().addAll(titleLabel, statusLabel, infoLabel);
        return panel;
    }
    
    /**
     * 启动状态更新定时器
     */
    private void startStatusTimer() {
        statusTimer = new Timer("Status-Timer", true);
        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                javafx.application.Platform.runLater(() -> {
                    if (player != null) {
                        statusLabel.setText(player.getNalStatus());
                    }
                });
            }
        }, 0, 500); // 每500ms更新一次状态
    }
    
    /**
     * 清理资源
     */
    private void cleanup() {
        System.out.println("🧹 清理资源...");
        
        if (statusTimer != null) {
            statusTimer.cancel();
        }
        
        if (player != null) {
            player.stop();
        }
        
        System.out.println("✅ 资源清理完成");
    }
    
    public static void main(String[] args) {
        System.out.println("🎯 SimpleWebRTCPlayer测试程序");
        System.out.println("📋 测试目标:");
        System.out.println("  1. 检测NAL单元类型: SPS(7), PPS(8), IDR(5), P-slice(1), FU-A(28)");
        System.out.println("  2. 验证PLI请求机制");
        System.out.println("  3. 确认视频解码和显示");
        System.out.println();
        
        launch(args);
    }
}