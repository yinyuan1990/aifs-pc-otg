package com.acard.acard.slowmotion;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JPEG 序列播放器 UI
 * 
 * 功能：
 * - 播放/暂停
 * - 速度调节（0.25x - 4x）
 * - 进度条拖动 seek
 * - 帧数显示
 * - 零内存模式（直接磁盘读取）
 */
public class DiskJpegPlayerUI {
    
    private Stage stage;
    private DiskJpegPlayer player;
    
    // UI 组件
    private ImageView imageView;
    private Button playPauseButton;
    private Button seekLatestButton;    // ⭐ 跳到最新按钮
    private Slider progressSlider;
    private Label frameLabel;
    private Label speedLabel;
    private Label modeLabel;  // ⭐ 模式显示
    private ComboBox<String> speedComboBox;
    private CheckBox autoFollowCheckBox;  // ⭐ 自动追赶复选框
    
    private volatile boolean isDragging = false;
    
    // ⭐ 异步复制线程池
    private final ExecutorService copyExecutor = Executors.newSingleThreadExecutor();
    
    // ⭐ 复制计数器
    private volatile int copiedFrameCount = 0;
    
    // ⭐ 实时流文件夹和回放文件夹
    private String realtimeDirectory;  // runtime/captures/ssl
    private String playbackDirectory;  // runtime/captures/slow


    private int maxFrames;
    
    /**
     * 显示播放器窗口
     * 
     * @param realtimeDir 实时流目录路径（用于记录，实际不用于读取）
     * @param filePattern 文件名模式（如 "s_%09d.jpeg"）
     * @param startFrame  起始帧
     * @param endFrame    结束帧
     */
    public void show(String realtimeDir, String filePattern, int startFrame, int endFrame,int maxFrame) {
        this.realtimeDirectory = realtimeDir;  // 保存用于日志
        this.playbackDirectory = "runtime/captures/slow";  // 固定回放目录
        this.maxFrames=maxFrame;
        
        Platform.runLater(() -> {
            // ⭐ 创建播放器（使用回放目录）
            player = new DiskJpegPlayer(playbackDirectory, filePattern, startFrame, endFrame,maxFrame);
            
            // 设置帧回调
            player.setFrameCallback(this::onFrameUpdate);
            
            // 创建 Stage
            stage = new Stage();
            stage.setTitle("JPEG 序列播放器（零内存模式）- 总帧数: " + player.getTotalFrames());
            
            // 创建 UI
            VBox root = createUI();
            
            Scene scene = new Scene(root, 1280, 800);
            stage.setScene(scene);
            
            // 关闭时停止播放并清理资源
            stage.setOnCloseRequest(e -> {
                if (player != null) {
                    player.stop();
                }
                // 关闭复制线程池
                copyExecutor.shutdown();
            });
            
            stage.show();
            
            // ⭐ 已默认为实时流模式（在 DiskJpegPlayer 构造时）
            updateModeLabel();
            
            // 不加载第一帧（等待推送）
            // showFirstFrame();
        });
    }
    
    /**
     * 创建 UI 布局
     */
    private VBox createUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.CENTER);
        
        // 1. 图像显示区域
        imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setFitWidth(1260);
        imageView.setFitHeight(700);
        
        StackPane imagePane = new StackPane(imageView);
        imagePane.setStyle("-fx-background-color: black;");
        VBox.setVgrow(imagePane, Priority.ALWAYS);
        
        // 2. 控制面板
        HBox controlPanel = createControlPanel();
        
        root.getChildren().addAll(imagePane, controlPanel);
        
        return root;
    }
    
    /**
     * 创建控制面板
     */
    private HBox createControlPanel() {
        HBox controlPanel = new HBox(15);
        controlPanel.setPadding(new Insets(10));
        controlPanel.setAlignment(Pos.CENTER);
        
        // 播放/暂停按钮
        playPauseButton = new Button("▶ 播放");
        playPauseButton.setPrefWidth(100);
        playPauseButton.setOnAction(e -> togglePlayPause());
        
        // ⭐ 跳到最新按钮（切换到实时流模式）
        seekLatestButton = new Button("⏭ 实时流");
        seekLatestButton.setPrefWidth(90);
        seekLatestButton.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
        seekLatestButton.setOnAction(e -> {
            // ⭐ 只有在没超过限制时才能切换到实时流模式
            int maxFrames = com.acard.acard.storage.SlowmoStore.getInstance().getSlowmoFrames();
            if (copiedFrameCount < maxFrames) {
                // 切换到实时流模式
                player.switchToRealtimeMode();
                updateModeLabel();

            } else {
                // 已达到限制，提示用户
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText("无法切换到实时流模式");
                alert.setContentText("已达到最大帧数限制 (" + maxFrames + " 帧)");
                alert.showAndWait();
            }
        });
        
        // 速度选择
        Label speedLabelText = new Label("速度:");
        speedComboBox = new ComboBox<>();
        speedComboBox.getItems().addAll("0.25x", "0.5x", "1x", "2x", "4x");
        speedComboBox.setValue("1x");
        speedComboBox.setPrefWidth(80);
        speedComboBox.setOnAction(e -> {

            onSpeedChanged();
            updateModeLabel();  // ⭐ 调速后更新模式显示
        });
        
        speedLabel = new Label("1.0x");
        speedLabel.setStyle("-fx-font-weight: bold;");
        
        // ⭐ 模式显示
        modeLabel = new Label("🔵 回放");
        modeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        
        // ⭐ 自动追赶复选框
        autoFollowCheckBox = new CheckBox("实时追赶");
        autoFollowCheckBox.setSelected(false);
        autoFollowCheckBox.setOnAction(e -> {
            player.setAutoFollowLatest(autoFollowCheckBox.isSelected());
        });
        
        // 进度条
        progressSlider = new Slider(0, 1, 0);
        progressSlider.setPrefWidth(600);
        progressSlider.setOnMousePressed(e -> isDragging = true);
        progressSlider.setOnMouseReleased(e -> {
            isDragging = false;
            player.seekToProgress(progressSlider.getValue());
            updateModeLabel();  // ⭐ Seek 后更新模式显示
        });
        HBox.setHgrow(progressSlider, Priority.ALWAYS);
        
        // 帧数显示
        frameLabel = new Label("帧: 0 / " + (player != null ? player.getTotalFrames() : 0));
        frameLabel.setPrefWidth(150);
        frameLabel.setStyle("-fx-font-family: monospace;");
        
        controlPanel.getChildren().addAll(
            playPauseButton,
            seekLatestButton,  // ⭐ 实时流按钮
            new Separator(javafx.geometry.Orientation.VERTICAL),
            modeLabel,  // ⭐ 模式显示
            new Separator(javafx.geometry.Orientation.VERTICAL),
            speedLabelText,
            speedComboBox,
            speedLabel,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            autoFollowCheckBox,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            progressSlider,
            new Separator(javafx.geometry.Orientation.VERTICAL),
            frameLabel
        );
        
        return controlPanel;
    }
    
    /**
     * 播放/暂停切换
     */
    private void togglePlayPause() {
        if (player.isPlaying()) {
            player.pause();
            playPauseButton.setText("▶ 播放");
        } else {
            player.play();
            playPauseButton.setText("⏸ 暂停");
        }
    }
    
    /**
     * 速度改变
     */
    private void onSpeedChanged() {


        String speedStr = speedComboBox.getValue();
        double speed = Double.parseDouble(speedStr.replace("x", ""));
        player.setPlaybackSpeed(speed);
        speedLabel.setText(String.format("%.2fx", speed));
    }
    
    /**
     * 帧更新回调
     */
    private void onFrameUpdate(BufferedImage image, int frameIndex, int totalFrames) {
        Platform.runLater(() -> {
            // 更新图像
            imageView.setImage(SwingFXUtils.toFXImage(image, null));
            
            // 更新进度条（如果不在拖动中）
            if (!isDragging) {
                double progress = player.getCurrentProgress();
                progressSlider.setValue(progress);
            }
            
            // 更新帧数显示
            frameLabel.setText(String.format("帧: %d / %d", frameIndex, totalFrames));
        });
    }
    
    /**
     * 显示第一帧
     */
    private void showFirstFrame() {
        new Thread(() -> {
            BufferedImage firstFrame = player.loadFrame(player.getCurrentFrameIndex());
            if (firstFrame != null) {
                onFrameUpdate(firstFrame, player.getCurrentFrameIndex(), player.getTotalFrames());
            }
        }).start();
    }
    
    /**
     * 静态工厂方法（方便调用）
     * 
     * @param realtimeDirectory 实时流目录（如 "runtime/captures/ssl"）
     * @param filePattern       文件格式
     * @param startFrame        起始帧
     * @param endFrame          结束帧
     * @return UI 实例，用于后续推送新帧
     */
    public static DiskJpegPlayerUI open(String realtimeDirectory, String filePattern, int startFrame, int endFrame,int maxFrame) {
        DiskJpegPlayerUI ui = new DiskJpegPlayerUI();
        ui.show(realtimeDirectory, filePattern, startFrame, endFrame,maxFrame);
        return ui;  // ⭐ 返回实例
    }
    
    /**
     * 测试入口（从按钮调用）
     */
    public static void openTestPlayer() {
        // ⭐ 传入实时流目录
        open("runtime/captures/ssl", "s_%09d.jpeg", 1, 1,1);
    }
    
    // ========== 推送播放支持 ==========
    
    /**
     * 更新模式显示
     */


    private void updateModeLabel() {
        if (player != null && modeLabel != null) {
            Platform.runLater(() -> {
                if (player.isRealtimeMode()) {
                    modeLabel.setText("🔴 实时流");
                    modeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: red;");
                    // ⭐ 更新速度选择框为 1x
                    speedComboBox.setValue("1x");

                    /*// ⭐ 禁用播放按钮
                    playPauseButton.setDisable(true);
                    playPauseButton.setText("▶ 播放");

                    // ⭐ 禁用速度控制
                    speedComboBox.setDisable(true);

                    // ⭐ 禁用进度条拖动
                    progressSlider.setDisable(true);*/

                } else {
                    // 回放模式
                    modeLabel.setText("🔵 回放");
                    modeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: blue;");

                    // ⭐ 启用播放按钮
                    playPauseButton.setDisable(false);

                    // ⭐ 同步播放状态到按钮
                    if (player.isPlaying()) {
                        playPauseButton.setText("⏸ 暂停");
                    } else {
                        playPauseButton.setText("▶ 播放");
                    }

                    // ⭐ 启用速度控制
                    speedComboBox.setDisable(false);

                    // ⭐ 启用进度条拖动
                    progressSlider.setDisable(false);
                }
            });
        }
    }
    
    /**
     * 异步复制文件到回放目录（有数量限制）
     * 
     * @param sourcePath 原始文件路径
     * @param frameIndex 帧索引
     * @return true=已复制，false=跳过
     */
    private boolean copyFrameAsync(String sourcePath, int frameIndex) {
        // ⭐ 检查复制数量限制
        int maxFrames = com.acard.acard.storage.SlowmoStore.getInstance().getSlowmoFrames();
        if (copiedFrameCount >= maxFrames) {
            // 超过限制，不复制
            return false;
        }
        
        copyExecutor.submit(() -> {
            try {
                File sourceFile = new File(sourcePath);
                if (!sourceFile.exists()) {
                    return;
                }
                
                // 目标文件路径
                String filename = String.format("s_%09d.jpeg", frameIndex);
                File targetFile = new File(playbackDirectory, filename);
                
                // 确保目录存在
                File parentDir = targetFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                
                // ⭐ 异步复制（不阻塞 UI）
                Files.copy(sourceFile.toPath(), targetFile.toPath(), 
                          StandardCopyOption.REPLACE_EXISTING);
                
                // ⭐ 增加计数器
                copiedFrameCount++;
                
                System.out.println("📋 已复制: " + filename + " (" + copiedFrameCount + "/" + maxFrames + ")");
                
            } catch (IOException e) {
                System.err.println("复制失败: " + e.getMessage());
            }
        });
        
        return true;
    }
    
    /**
     * 推送新帧（外部调用）
     * 
     * @param sourcePath 原始 JPEG 路径（实时流文件夹）
     * @param frameIndex 新帧索引
     */
    public void pushNewFrame(String sourcePath, int frameIndex) {
        if (player == null) {
            return;
        }
        
        // ⭐ 检查是否超过复制限制

        if (copiedFrameCount >= maxFrames) {
            // ⭐ 超过限制，不做任何处理
            return;
        }
        
        // 1. 异步复制到回放目录（有数量限制）
        boolean copied = copyFrameAsync(sourcePath, frameIndex); //一致处理
        
        // 2. 推送给播放器（实时流模式会立即显示）
        if (copied) {


            player.pushRealtimeFrame(sourcePath, frameIndex);
            // 3. 更新模式显示
            updateModeLabel();
        }
    }
    
    /**
     * 启用自动追赶模式
     */
    public void setAutoFollow(boolean enable) {
        if (player != null) {
            player.setAutoFollowLatest(enable);
        }
        if (autoFollowCheckBox != null) {
            autoFollowCheckBox.setSelected(enable);
        }
    }
    
    /**
     * 获取播放器实例（用于外部控制）
     */
    public DiskJpegPlayer getPlayer() {
        return player;
    }
    
    /**
     * 获取已复制帧数
     */
    public int getCopiedFrameCount() {
        return copiedFrameCount;
    }
    
    /**
     * 重置复制计数器（重新开始录制时调用）
     */
    public void resetCopyCount() {
        copiedFrameCount = 0;
        System.out.println("🔄 复制计数器已重置");
    }

    /**
     * 创建可嵌入的 Pane（用于集成到 Element2_2）
     *
     * @param realtimeDir 实时流目录路径
     * @param filePattern 文件名模式
     * @param startFrame  起始帧
     * @param endFrame    结束帧
     * @param maxFrame    最大帧数
     * @return 可嵌入的 Pane
     */
    public Pane createEmbeddedPane(String realtimeDir, String filePattern, int startFrame, int endFrame, int maxFrame) {
        this.realtimeDirectory = realtimeDir;
        this.playbackDirectory = "runtime/captures/slow";
        this.maxFrames = maxFrame;

        // ⭐ 创建播放器（使用回放目录）
        player = new DiskJpegPlayer(playbackDirectory, filePattern, startFrame, endFrame, maxFrame);

        // 设置帧回调
        player.setFrameCallback(this::onFrameUpdate);

        // ⭐ 创建 UI（自适应版本）
        VBox root = createAdaptiveUI();

        // ⭐ 已默认为实时流模式
        updateModeLabel();

        return root;
    }

    /**
     * 创建自适应 UI 布局（用于嵌入）
     */
    private VBox createAdaptiveUI() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setAlignment(Pos.CENTER);

        // 1. 图像显示区域（自适应）
        imageView = new ImageView();
        imageView.setPreserveRatio(true);

        // ⭐ 不设置固定大小，让其自适应父容器
        // imageView.setFitWidth(1260);  // 删除固定宽度
        // imageView.setFitHeight(700);  // 删除固定高度

        StackPane imagePane = new StackPane(imageView);
        imagePane.setStyle("-fx-background-color: black;");
        VBox.setVgrow(imagePane, Priority.ALWAYS);

        // ⭐ 绑定 ImageView 到 StackPane 的大小（保持纵横比）
        imageView.fitWidthProperty().bind(imagePane.widthProperty());
        imageView.fitHeightProperty().bind(imagePane.heightProperty());

        // 2. 控制面板
        HBox controlPanel = createControlPanel();

        root.getChildren().addAll(imagePane, controlPanel);

        return root;
    }
}

