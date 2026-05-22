package com.acard.acard.ui;

import com.acard.acard.store.ShortcutStore;
import com.acard.acard.tools.FileToos;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 抓拍项全屏查看器（零内存磁盘加载）
 * - 鼠标滚轮：切换图片
 * - Ctrl + 鼠标滚轮：放大缩小
 */
public class CaptureItemViewer extends StackPane {

    private final ImageView imageView;
    private final Label frameLabel;
    private final Button closeButton;

    private List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames;
    private int currentFrameIndex = 0;

    // 缩放相关
    private double currentScale = 1.0;
    private static final double MIN_SCALE = 1.0;   // ⭐ 最小 100%（不能缩小到1.0x以下）
    private static final double MAX_SCALE = 5.0;   // 最大 500%
    private static final double SCALE_DELTA = FileToos.ImageScale; // 每次缩放 10%
    
    // ⭐ 复用WritableImage，避免内存泄漏
    private javafx.scene.image.WritableImage reusableImage = null;

    public CaptureItemViewer() {
        // ⭐ 设置半透明黑色背景（全屏遮罩）
        this.setStyle("-fx-background-color: rgba(0, 0, 0, 0.95);");
        //this.setStyle("-fx-background-color: #253229;");
        this.setOpacity(1.0);  // ⭐ 确保容器不透明
        this.setVisible(false);  // 默认隐藏

        // ⭐ 创建图像显示区域
        imageView = new ImageView();
        //imageView.setPreserveRatio(true);
        imageView.setPreserveRatio(false);  // ⭐ 不保持宽高比，拉伸铺满
        imageView.setSmooth(true);

        // 绑定自适应大小（初始状态）
        imageView.fitWidthProperty().bind(this.widthProperty().multiply(0.99));
        imageView.fitHeightProperty().bind(this.heightProperty().multiply(0.99));



        // ⭐ 创建顶部控制面板（只有关闭按钮和帧数）
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.BOTTOM_CENTER);
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7); " +
                "-fx-padding: 10px 10px; " +
                "-fx-background-radius: 10px;");
        // ⭐⭐⭐ 添加这三行：固定高度 50px
        topBar.setMinHeight(50);
        topBar.setPrefHeight(50);
        topBar.setMaxHeight(50);
        // 帧数标签
        frameLabel = new Label("0 / 0");
        frameLabel.setStyle("-fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold;");

        // 缩放提示标签
        Label scaleLabel = new Label("100%");
        scaleLabel.setStyle("-fx-text-fill: #90ee90; -fx-font-size: 14px;");

        // 关闭按钮
        closeButton = new Button("✖ 关闭 (ESC)");
        closeButton.setStyle("-fx-background-color: #dc3545; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 8px 16px; " +
                "-fx-background-radius: 5px; " +
                "-fx-cursor: hand;");
        closeButton.setOnAction(e -> hide());

        // 操作提示
        Label hintLabel = new Label("滚轮/鼠标左右键/方向键←→: 切换图片 | Ctrl+滚轮: 缩放");
        hintLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        topBar.getChildren().addAll(frameLabel, new Label("|") {{
            setStyle("-fx-text-fill: #666666;");
        }}, scaleLabel, hintLabel, spacer, closeButton);

        // ⭐ 布局：图像在中心，控制面板在顶部
        StackPane.setAlignment(imageView, Pos.CENTER);
        StackPane.setAlignment(topBar, Pos.BOTTOM_CENTER);
        StackPane.setMargin(topBar, new javafx.geometry.Insets(10, 0, 0, 0));

        this.getChildren().addAll(imageView, topBar);

        // ⭐⭐⭐ 鼠标滚轮事件（核心功能）
        // ⭐⭐⭐ 鼠标滚轮事件（以鼠标位置为中心缩放）
        this.setOnScroll((ScrollEvent event) -> {
            if (event.isControlDown()) {
                // ⭐ Ctrl + 滚轮：缩放
                double delta = event.getDeltaY() > 0 ? SCALE_DELTA : -SCALE_DELTA;
                double oldScale = currentScale;
                double newScale = currentScale + delta;

                // 限制缩放范围
                newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, newScale));

                if (newScale != oldScale) {
                    // ⭐ 获取鼠标在 ImageView 中的相对位置
                    double mouseX = event.getX();
                    double mouseY = event.getY();

                    // ⭐ 计算缩放中心点（相对于 ImageView 的中心）
                    double pivotX = (mouseX - imageView.getLayoutX() - imageView.getFitWidth() / 2);
                    double pivotY = (mouseY - imageView.getLayoutY() - imageView.getFitHeight() / 2);

                    // ⭐ 应用缩放
                    imageView.setScaleX(newScale);
                    imageView.setScaleY(newScale);

                    // ⭐ 调整位置，使鼠标点保持不变
                    double scaleDelta = newScale / oldScale - 1;
                    imageView.setTranslateX(imageView.getTranslateX() - pivotX * scaleDelta);
                    imageView.setTranslateY(imageView.getTranslateY() - pivotY * scaleDelta);

                    currentScale = newScale;
                    scaleLabel.setText(String.format("%.0f%%", newScale * 100));
                }

                event.consume();
            } else {
                // 普通滚轮：切换图片
                if (event.getDeltaY() > 0) {
                    showNextFrame();
                } else if (event.getDeltaY() < 0) {
                    showPreviousFrame();
                }
                event.consume();
            }
        });

        // ⭐ 键盘快捷键支持
        this.setOnKeyPressed(event -> {
            // ESC 关闭查看器
            if(event.getCode() == ShortcutStore.getInstance().getFullscreenViewerKey()){
                hide();
                event.consume();
            }
            // ⭐ 左方向键：上一帧
            else if (event.getCode() == javafx.scene.input.KeyCode.LEFT) {
                showPreviousFrame();
                event.consume();
            }
            // ⭐ 右方向键：下一帧
            else if (event.getCode() == javafx.scene.input.KeyCode.RIGHT) {
                showNextFrame();
                event.consume();
            }
        });

        // ⭐ 鼠标点击事件：左键上一帧，右键下一帧
        this.setOnMouseClicked(event -> {
            // 如果点击的是背景区域（不是图片），关闭查看器
            if (event.getTarget() == this) {
                hide();
                return;
            }
            
            // ⭐ 左键 = 上一帧
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                showPreviousFrame();
                event.consume();
            }
            // ⭐ 右键 = 下一帧
            else if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                showNextFrame();
                event.consume();
            }
        });
    }

    /**
     * 应用缩放
     */


    /**
     * 应用缩放（以鼠标位置为中心）
     */
    private void applyScale(double scale, double mouseX, double mouseY) {
        // ⭐⭐⭐ 直接使用 scale 属性，不改变 fitWidth/fitHeight
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);

        // ⭐ 计算缩放后的偏移量，使鼠标位置保持不变
        // 这部分可选，如果只需要以中心缩放，可以省略
    }

    /**
     * 显示抓拍项（零内存磁盘加载）
     */
    public void show(List<com.acard.acard.capture.DiskCaptureCache.DiskFrameItem> frames, int startFrameIndex) {
        if (frames == null || frames.isEmpty()) {
            System.err.println("⚠️ 无帧可显示");
            return;
        }

        this.frames = frames;
        this.currentFrameIndex = startFrameIndex;
        this.currentScale = 1.0;  // 重置缩放

        // 重新绑定自适应大小
        imageView.fitWidthProperty().bind(this.widthProperty().multiply(0.99));
        imageView.fitHeightProperty().bind(this.heightProperty().multiply(0.99));
        imageView.setPreserveRatio(false);  // ⭐ 不保持宽高比，拉伸铺满
        imageView.setSmooth(true);
        // 显示查看器
        this.setVisible(true);
        this.toFront();
        this.requestFocus();  // 获取焦点，支持滚轮和键盘操作

        // 加载第一帧
        loadAndDisplayFrame(currentFrameIndex);

        System.out.println("✅ 全屏查看器已显示，共 " + frames.size() + " 帧");
    }

    /**
     * 隐藏查看器
     */
    public void hide() {

        this.setVisible(false);
        // ⭐ 清理图像缓存，释放内存
        reusableImage = null;
        imageView.setImage(null);
        // ⭐ 建议GC回收
        System.gc();

    }

    /**
     * ⭐ 零内存磁盘加载帧
     */
    private void loadAndDisplayFrame(int index) {
        if (frames == null || index < 0 || index >= frames.size()) {
            return;
        }

        currentFrameIndex = index;

        // 在后台线程加载图像（零内存）
        new Thread(() -> {
            try {
                com.acard.acard.capture.DiskCaptureCache.DiskFrameItem frameItem = frames.get(index);
                String filepath = frameItem.getFilePath();

                // ⭐ 直接从磁盘加载（不缓存）
                File file = new File(filepath);
                if (!file.exists()) {
                    System.err.println("⚠️ 帧文件不存在: " + filepath);
                    return;
                }

                BufferedImage bufferedImage = ImageIO.read(file);
                if (bufferedImage == null) {
                    System.err.println("⚠️ 无法读取帧: " + filepath);
                    return;
                }

                // ⭐ 获取图像尺寸（在后台线程完成）
                final int width = bufferedImage.getWidth();
                final int height = bufferedImage.getHeight();

                // 在 FX 线程更新 UI
                Platform.runLater(() -> {
                    try {
                        // ⭐ 复用WritableImage对象（只在尺寸变化时重建）
                        if (reusableImage == null || 
                            (int)reusableImage.getWidth() != width || 
                            (int)reusableImage.getHeight() != height) {
                            reusableImage = new javafx.scene.image.WritableImage(width, height);
                            System.out.println("🔄 CaptureItemViewer创建新的WritableImage: " + width + "x" + height);
                        }
                        
                        // ⭐ 转换图像（复用目标对象）
                        javafx.scene.image.WritableImage fxImage = 
                            javafx.embed.swing.SwingFXUtils.toFXImage(bufferedImage, reusableImage);
                        
                        imageView.setImage(fxImage);
                        frameLabel.setText((index + 1) + " / " + frames.size());
                        
                    } finally {
                        // ⭐ 立即释放BufferedImage
                        bufferedImage.flush();
                    }
                });

            } catch (Exception e) {
                System.err.println("❌ 加载帧失败: " + e.getMessage());
                e.printStackTrace();
            }
        }, "FrameLoader-" + index).start();
    }

    /**
     * 上一帧（配合滚轮帧率设置）
     */
    private void showPreviousFrame() {
        if (currentFrameIndex > 0) {
            // ⭐ 获取滚轮帧率设置（0表示跳1帧，n表示跳n帧）
            int scrollFrameRate = ShortcutStore.getInstance().getScrollFrameRate();
            int step = scrollFrameRate == 0 ? 1 : scrollFrameRate;
            int newIndex = Math.max(0, currentFrameIndex - step);
            loadAndDisplayFrame(newIndex);
            
            // ⚡ 将查看的帧图片路径加入上传队列
            enqueueFrameForUpload(newIndex);
        } else {
            System.out.println("⚠️ 已经是第一帧");
        }
    }

    /**
     * 下一帧（配合滚轮帧率设置）
     */
    private void showNextFrame() {
        if (currentFrameIndex < frames.size() - 1) {
            // ⭐ 获取滚轮帧率设置（0表示跳1帧，n表示跳n帧）
            int scrollFrameRate = ShortcutStore.getInstance().getScrollFrameRate();
            int step = scrollFrameRate == 0 ? 1 : scrollFrameRate;
            int newIndex = Math.min(frames.size() - 1, currentFrameIndex + step);
            loadAndDisplayFrame(newIndex);
            
            // ⚡ 将查看的帧图片路径加入上传队列
            enqueueFrameForUpload(newIndex);
        } else {
            System.out.println("⚠️ 已经是最后一帧");
        }
    }
    
    /**
     * ⚡ 将指定帧的图片路径加入上传队列（查看上下帧时调用）
     */
    private void enqueueFrameForUpload(int frameIndex) {
        if (frames != null && frameIndex >= 0 && frameIndex < frames.size()) {
            com.acard.acard.capture.DiskCaptureCache.DiskFrameItem frame = frames.get(frameIndex);
            if (frame != null && frame.getFilePath() != null && !frame.getFilePath().isEmpty()) {
                com.acard.acard.tools.ImageUploadQueue.getInstance().enqueueFrameView(frame.getFilePath());
            }
        }
    }
}
