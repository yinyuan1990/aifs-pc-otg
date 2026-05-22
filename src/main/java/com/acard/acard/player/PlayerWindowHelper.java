package com.acard.acard.player;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 播放器窗口助手
 * 集成SimpleGpuPlayer，提供GPU加速的图片序列播放功能
 */
public class PlayerWindowHelper {
    
    /**
     * 打开播放器窗口
     */
    public static void openPlayerWindow() {
        try {
            // 创建新窗口
            Stage playerStage = new Stage();
            playerStage.setTitle("GPU加速图片序列播放器");
            
            // 创建播放器UI
            BorderPane root = new BorderPane();
            
            // 图片显示区域
            ImageView imageView = new ImageView();
            imageView.setFitWidth(1280);
            imageView.setFitHeight(720);
            imageView.setPreserveRatio(true);
            imageView.setStyle("-fx-background-color: black;");
            root.setCenter(imageView);
            
            // 控制面板
            VBox controlPanel = createControlPanel(imageView, playerStage);
            root.setBottom(controlPanel);
            
            // 状态栏
            Label statusLabel = new Label("GPU播放器准备就绪 - 分辨率: 1280x720");
            statusLabel.setStyle("-fx-padding: 5px; -fx-background-color: #f0f0f0;");
            root.setTop(statusLabel);
            
            // 创建场景
            Scene scene = new Scene(root, 1400, 900);
            
            // 加载样式
            try {
                String cssPath = PlayerWindowHelper.class.getResource("/styles/player.css").toExternalForm();
                scene.getStylesheets().add(cssPath);
            } catch (Exception e) {
                System.out.println("未找到样式文件，使用默认样式");
            }
            
            playerStage.setScene(scene);
            playerStage.show();
            
            // 窗口关闭事件
            playerStage.setOnCloseRequest(e -> {
                System.out.println("GPU播放器窗口关闭");
            });
            
        } catch (Exception e) {
            e.printStackTrace();
            showError("打开GPU播放器失败: " + e.getMessage());
        }
    }
    
    /**
     * 创建控制面板
     */
    private static VBox createControlPanel(ImageView imageView, Stage stage) {
        VBox controlPanel = new VBox(10);
        controlPanel.setPadding(new Insets(10));
        controlPanel.setAlignment(Pos.CENTER);
        
        // GPU播放器实例
        SimpleGpuPlayer[] gpuPlayer = {null};
        
        // 进度滑块
        Slider progressSlider = new Slider();
        progressSlider.setMin(0);
        progressSlider.setMax(100);
        progressSlider.setValue(0);
        progressSlider.setShowTickLabels(true);
        progressSlider.setShowTickMarks(true);
        
        // 播放控制按钮
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        
        Button playPauseBtn = new Button("播放");
        Button stopBtn = new Button("停止");
        
        // 速度控制
        ComboBox<String> speedBox = new ComboBox<>();
        speedBox.getItems().addAll("0.25x", "0.5x", "1.0x", "2.0x", "4.0x");
        speedBox.setValue("1.0x");
        
        // 循环播放
        CheckBox loopBox = new CheckBox("循环播放");
        loopBox.setSelected(true);
        
        buttonBox.getChildren().addAll(playPauseBtn, stopBtn, new Label("速度:"), speedBox, loopBox);
        
        // 文件信息标签
        Label fileInfoLabel = new Label("请选择图片目录 (GPU加速模式)");
        
        // 目录选择按钮
        Button selectDirBtn = new Button("选择图片目录");
        selectDirBtn.setOnAction(e -> {
            javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
            dirChooser.setTitle("选择图片目录");
            File selectedDir = dirChooser.showDialog(stage);
            
            if (selectedDir != null) {
                try {
                    // 释放之前的播放器
                    if (gpuPlayer[0] != null) {
                        gpuPlayer[0].dispose();
                    }
                    
                    // 创建新的GPU播放器
                    gpuPlayer[0] = new SimpleGpuPlayer(selectedDir.getAbsolutePath());
                    
                    // 设置帧回调
                    gpuPlayer[0].setFrameCallback(new SimpleGpuPlayer.FrameCallback() {
                        @Override
                        public void onFrame(byte[] frameData, int frameIndex, int totalFrames) {
                            // 将GPU帧数据转换为JavaFX图像
                            try {
                                // 创建WritableImage (1280x720, BGRA格式)
                                WritableImage writableImage = new WritableImage(1280, 720);
                                
                                // 将字节数据转换为像素缓冲区
                                ByteBuffer buffer = ByteBuffer.wrap(frameData);
                                writableImage.getPixelWriter().setPixels(0, 0, 1280, 720, 
                                    PixelFormat.getByteBgraInstance(), buffer, 1280 * 4);
                                
                                // 更新ImageView
                                Platform.runLater(() -> {
                                    imageView.setImage(writableImage);
                                    progressSlider.setValue(frameIndex);
                                });
                                
                            } catch (Exception ex) {
                                System.err.println("GPU帧转换失败: " + ex.getMessage());
                            }
                        }
                    });
                    
                    int totalFrames = gpuPlayer[0].getTotalFrames();
                    fileInfoLabel.setText("GPU模式已加载 " + totalFrames + " 张图片 (1280x720)");
                    progressSlider.setMax(totalFrames - 1);
                    
                    // 加载第一帧
                    gpuPlayer[0].loadFrame(0);
                    
                } catch (Exception ex) {
                    showError("创建GPU播放器失败: " + ex.getMessage());
                }
            }
        });
        
        // 播放/暂停按钮事件
        playPauseBtn.setOnAction(e -> {
            if (gpuPlayer[0] == null) {
                showError("请先选择图片目录");
                return;
            }
            
            if (gpuPlayer[0].isPlaying()) {
                // 暂停
                gpuPlayer[0].pause();
                playPauseBtn.setText("播放");
            } else {
                // 播放
                gpuPlayer[0].play();
                playPauseBtn.setText("暂停");
            }
        });
        
        // 停止按钮事件
        stopBtn.setOnAction(e -> {
            if (gpuPlayer[0] != null) {
                gpuPlayer[0].stop();
                playPauseBtn.setText("播放");
                progressSlider.setValue(0);
            }
        });
        
        // 进度滑块拖动事件
        progressSlider.setOnMouseReleased(e -> {
            if (gpuPlayer[0] != null) {
                int newIndex = (int) progressSlider.getValue();
                gpuPlayer[0].seekToFrame(newIndex);
            }
        });
        
        // 速度变化事件
        speedBox.setOnAction(e -> {
            if (gpuPlayer[0] != null) {
                String speedStr = speedBox.getValue();
                double speed = Double.parseDouble(speedStr.replace("x", ""));
                gpuPlayer[0].setPlaybackSpeed(speed);
            }
        });
        
        controlPanel.getChildren().addAll(
            selectDirBtn,
            fileInfoLabel,
            progressSlider,
            buttonBox
        );
        
        return controlPanel;
    }
    

    
    /**
     * 显示错误信息
     */
    private static void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("错误");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}