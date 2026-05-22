package com.acard.acard.ui;

import com.acard.acard.GstBootstrap;
import javafx.application.Application;
import javafx.geometry.Insets;
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
 * SimpleWebRTCPlayerView 测试应用
 */
public class SimpleWebRTCPlayerViewTest extends Application {
    
    private SimpleWebRTCPlayerView playerView;
    private Label statusLabel;
    private Label scaleLabel;
    private Timer statusTimer;
    
    @Override
    public void start(Stage primaryStage) {
        // 初始化GStreamer

        
        // 创建播放器视图
        playerView = new SimpleWebRTCPlayerView(
            "192.168.1.100", // 服务器地址
            1985,             // 服务器端口
            "live",           // 租户
            "livestream"      // 流ID
        );
        
        // 创建控制面板
        VBox controlPanel = createControlPanel();
        
        // 创建主布局
        BorderPane root = new BorderPane();
        root.setCenter(playerView);
        root.setBottom(controlPanel);
        
        // 创建场景
        Scene scene = new Scene(root, 1200, 800);
        
        // 设置舞台
        primaryStage.setTitle("SimpleWebRTCPlayer UI 测试");
        primaryStage.setScene(scene);
        primaryStage.show();
        
        // 启动状态更新定时器
        startStatusTimer();
        
        // 窗口关闭时清理资源
        primaryStage.setOnCloseRequest(e -> {
            stopStatusTimer();
            if (playerView != null) {
                playerView.stop();
            }
        });
    }
    
    /**
     * 创建控制面板
     */
    private VBox createControlPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #f0f0f0;");
        
        // 播放控制按钮
        HBox buttonBox = new HBox(10);
        Button playButton = new Button("开始播放");
        Button stopButton = new Button("停止播放");
        Button toggleStatusButton = new Button("切换状态显示");
        
        playButton.setOnAction(e -> playerView.play());
        stopButton.setOnAction(e -> playerView.stop());
        toggleStatusButton.setOnAction(e -> {
            boolean visible = playerView.isStatusVisible();
            playerView.setStatusVisible(!visible);
        });
        
        buttonBox.getChildren().addAll(playButton, stopButton, toggleStatusButton);
        
        // 状态信息标签
        statusLabel = new Label("状态: 未连接");
        scaleLabel = new Label("缩放比例: 1.0");
        
        // 绑定缩放比例显示
        playerView.scaleFactorProperty().addListener((obs, oldVal, newVal) -> {
            scaleLabel.setText(String.format("缩放比例: %.2f", newVal.doubleValue()));
        });
        
        panel.getChildren().addAll(buttonBox, statusLabel, scaleLabel);
        return panel;
    }
    
    /**
     * 启动状态更新定时器
     */
    private void startStatusTimer() {
        statusTimer = new Timer(true);
        statusTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                javafx.application.Platform.runLater(() -> {
                    if (playerView != null) {
                        String nalStatus = playerView.getNalStatus();
                        statusLabel.setText("NAL状态: " + nalStatus);
                    }
                });
            }
        }, 1000, 1000);
    }
    
    /**
     * 停止状态更新定时器
     */
    private void stopStatusTimer() {
        if (statusTimer != null) {
            statusTimer.cancel();
            statusTimer = null;
        }
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}