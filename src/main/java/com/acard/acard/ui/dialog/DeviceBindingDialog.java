package com.acard.acard.ui.dialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * 设备绑定对话框
 * 同时包含二维码和密码输入框
 */
public class DeviceBindingDialog {
    
    private Popup popup;
    private HBox titleBar;
    private Consumer<String> onConfirm;
    private Runnable onCancel;
    private double dragOffsetX;
    private double dragOffsetY;
    
    /**
     * 显示设备绑定对话框
     * @param owner 父窗口
     * @param qrContent 二维码内容
     * @param onConfirm 确认回调，参数为输入的密码
     * @param onCancel 取消回调
     */
    public static void showDialog(Stage owner, String qrContent, Consumer<String> onConfirm, Runnable onCancel) {
        DeviceBindingDialog dialog = new DeviceBindingDialog();
        dialog.onConfirm = onConfirm;
        dialog.onCancel = onCancel;
        dialog.buildAndShow(owner, qrContent);
    }
    
    private void buildAndShow(Stage owner, String qrContent) {
        // 创建 Popup
        popup = new Popup();
        popup.setAutoHide(false);  // 不自动关闭，需要用户操作
        
        // 主容器
        VBox mainContainer = new VBox();
        mainContainer.setStyle(
            "-fx-background-color: #2b2b2b; " +
            "-fx-background-radius: 12; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 15, 0, 0, 5);"
        );
        mainContainer.setPrefWidth(320);
        
        // 顶部标题栏
        titleBar = new HBox();
        titleBar.setStyle(
            "-fx-background-color: #2b2b2b; " +
            "-fx-padding: 12; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        titleBar.setSpacing(10);
        
        Label titleLabel = new Label("设备绑定");
        titleLabel.setStyle(
            "-fx-text-fill: white; " +
            "-fx-font-size: 16; " +
            "-fx-font-weight: bold;"
        );
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button closeButton = new Button("×");
        closeButton.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 20; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 5 0 5;"
        );
        closeButton.setOnMouseEntered(e -> 
            closeButton.setStyle(
                "-fx-background-color: #ef4444; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 20; " +
                "-fx-font-weight: bold; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 0 5 0 5; " +
                "-fx-background-radius: 4;"
            )
        );
        closeButton.setOnMouseExited(e -> 
            closeButton.setStyle(
                "-fx-background-color: transparent; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 20; " +
                "-fx-font-weight: bold; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 0 5 0 5;"
            )
        );
        closeButton.setOnAction(e -> {
            if (onCancel != null) {
                onCancel.run();
            }
            popup.hide();
        });
        
        titleBar.getChildren().addAll(titleLabel, spacer, closeButton);
        
        // 内容区域
        VBox contentBox = new VBox();
        contentBox.setStyle(
            "-fx-background-color: #1f1f1f; " +
            "-fx-padding: 20 20 40 20; " +  // ⭐ 底部加大20px (上右下左: 20 20 40 20)
            "-fx-spacing: 15;"
        );
        contentBox.setAlignment(Pos.CENTER);
        
        // 二维码提示
        Label qrHintLabel = new Label("请使用iOS设备扫描二维码");
        qrHintLabel.setStyle(
            "-fx-text-fill: #9ca3af; " +
            "-fx-font-size: 12;"
        );
        
        // 二维码区域（使用柔和的浅灰色背景，避免刺眼）
        StackPane qrContainer = new StackPane();
        qrContainer.setStyle(
            "-fx-background-color: #e8e8e8; " +  // ⭐ 柔和浅灰色，不刺眼
            "-fx-background-radius: 8; " +
            "-fx-padding: 10;"
        );
        
        ImageView qrImageView = new ImageView();
        qrImageView.setFitWidth(160);
        qrImageView.setFitHeight(160);
        qrImageView.setPreserveRatio(true);
        
        // 生成二维码
        Image qrImage = generateQRCodeImage(qrContent, 160);
        qrImageView.setImage(qrImage);
        
        qrContainer.getChildren().add(qrImageView);
        
        // ⭐ 底部提示文字
        Label bottomHintLabel = new Label("扫码后在iOS设备上确认绑定");
        bottomHintLabel.setStyle(
            "-fx-text-fill: #6b7280; " +
            "-fx-font-size: 11;"
        );
        
        // ⭐ 组装内容
        contentBox.getChildren().addAll(
            qrHintLabel, 
            qrContainer,
            bottomHintLabel
        );
        
        // 组装主容器
        mainContainer.getChildren().addAll(titleBar, contentBox);
        
        // 设置拖动功能
        setupDragging();
        
        // 添加到 Popup
        popup.getContent().add(mainContainer);
        
        // 显示在屏幕中央
        if (owner != null) {
            double centerX = owner.getX() + owner.getWidth() / 2 - 160;  // 160 = 320/2
            double centerY = owner.getY() + owner.getHeight() / 2 - 200;
            popup.show(owner, centerX, centerY);
            
            // ⭐ 监听主窗口最小化/失去焦点，自动关闭弹框
            owner.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
                if (isIconified && popup.isShowing()) {
                    popup.hide();
                }
            });
            owner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && popup.isShowing()) {
                    // 延迟检查，避免弹框内操作时误关闭
                    Platform.runLater(() -> {
                        if (!owner.isFocused() && popup.isShowing()) {
                            popup.hide();
                        }
                    });
                }
            });
        } else {
            popup.show(owner);
        }
    }
    
    /**
     * 生成二维码图像（使用柔和的浅灰色背景）
     */
    private Image generateQRCodeImage(String content, int size) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, size, size);
            
            // ⭐ 使用柔和的浅灰色背景 #e8e8e8，避免纯白刺眼
            int softGrayBg = new java.awt.Color(232, 232, 232).getRGB();
            
            BufferedImage bufferedImage = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bufferedImage.setRGB(x, y, bitMatrix.get(x, y) ? 
                        java.awt.Color.BLACK.getRGB() : softGrayBg);
                }
            }
            
            return SwingFXUtils.toFXImage(bufferedImage, null);
        } catch (WriterException e) {
            return null;
        }
    }
    
    /**
     * 设置拖动功能
     */
    private void setupDragging() {
        if (titleBar != null) {
            titleBar.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                dragOffsetX = e.getX();
                dragOffsetY = e.getY();
            });
            titleBar.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
                popup.setX(e.getScreenX() - dragOffsetX);
                popup.setY(e.getScreenY() - dragOffsetY);
            });
            
            titleBar.setOnMouseEntered(e -> 
                titleBar.setStyle(
                    "-fx-background-color: #363636; " +
                    "-fx-padding: 12; " +
                    "-fx-background-radius: 12 12 0 0; " +
                    "-fx-cursor: move;"
                )
            );
            titleBar.setOnMouseExited(e -> 
                titleBar.setStyle(
                    "-fx-background-color: #2b2b2b; " +
                    "-fx-padding: 12; " +
                    "-fx-background-radius: 12 12 0 0;"
                )
            );
        }
    }
    
    /**
     * 关闭对话框
     */
    public void close() {
        if (popup != null) {
            popup.hide();
        }
    }
}

