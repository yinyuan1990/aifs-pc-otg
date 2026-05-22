package com.acard.acard.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.stage.Stage;

/**
 * 设备绑定提示弹窗
 * - 使用 Popup 实现，与相机设定弹窗风格一致
 * - 点击外部自动关闭
 * - 可拖动
 */
public class DeviceBindingPromptDialog {
    
    private static Popup currentPopup = null;
    private Popup popup;
    private HBox titleBar;
    private double dragOffsetX;
    private double dragOffsetY;
    
    /**
     * 显示设备绑定提示弹窗
     * @param owner 父窗口
     */
    public static void showDialog(Stage owner) {
        // 如果 Popup 已经打开，则关闭它
        if (currentPopup != null && currentPopup.isShowing()) {
            currentPopup.hide();
            currentPopup = null;
            return;
        }
        
        // 创建新的弹窗
        DeviceBindingPromptDialog dialog = new DeviceBindingPromptDialog();
        dialog.buildAndShow(owner);
    }
    
    private void buildAndShow(Stage owner) {
        // 创建 Popup
        popup = new Popup();
        popup.setAutoHide(true);  // 点击外部自动关闭
        
        // 主容器
        VBox mainContainer = new VBox();
        mainContainer.setStyle(
            "-fx-background-color: #2b2b2b; " +
            "-fx-background-radius: 12; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 15, 0, 0, 5);"
        );
        mainContainer.setPrefWidth(400);
        
        // 顶部标题栏
        titleBar = new HBox();
        titleBar.setStyle(
            "-fx-background-color: #2b2b2b; " +
            "-fx-padding: 12; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        titleBar.setSpacing(10);
        
        Label titleLabel = new Label("⚠️ 提示");
        titleLabel.setStyle(
            "-fx-text-fill: #fbbf24; " +  // 黄色警告色
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
                "-fx-padding: 0 5 5;"
            )
        );
        closeButton.setOnAction(e -> popup.hide());
        
        titleBar.getChildren().addAll(titleLabel, spacer, closeButton);
        
        // 内容区域
        VBox contentBox = new VBox();
        contentBox.setStyle(
            "-fx-background-color: #1f1f1f; " +
            "-fx-padding: 20; " +
            "-fx-spacing: 15;"
        );
        
        // 警告图标和文字
        Label iconLabel = new Label("⚠");
        iconLabel.setStyle(
            "-fx-text-fill: #fbbf24; " +
            "-fx-font-size: 48; " +
            "-fx-alignment: center;"
        );
        
        Label messageLabel = new Label("还未绑定监控设备");
        messageLabel.setStyle(
            "-fx-text-fill: white; " +
            "-fx-font-size: 18; " +
            "-fx-font-weight: bold; " +
            "-fx-alignment: center;"
        );
        messageLabel.setWrapText(true);
        
        Label hintLabel = new Label("请在菜单栏「设置」→「设备绑定」中进行设备绑定操作");
        hintLabel.setStyle(
            "-fx-text-fill: #9ca3af; " +
            "-fx-font-size: 14; " +
            "-fx-alignment: center;"
        );
        hintLabel.setWrapText(true);
        
        // 按钮区域
        HBox buttonBox = new HBox();
        buttonBox.setStyle("-fx-alignment: center; -fx-spacing: 15; -fx-padding: 10 0 0 0;");
        
        Button goBindButton = new Button("去绑定");
        goBindButton.setStyle(
            "-fx-background-color: #607AFB; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 8 20; " +
            "-fx-background-radius: 6;"
        );
        goBindButton.setOnMouseEntered(e -> 
            goBindButton.setStyle(
                "-fx-background-color: #4f5fd9; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14; " +
                "-fx-font-weight: bold; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 6;"
            )
        );
        goBindButton.setOnMouseExited(e -> 
            goBindButton.setStyle(
                "-fx-background-color: #607AFB; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14; " +
                "-fx-font-weight: bold; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 6;"
            )
        );
        goBindButton.setOnAction(e -> {
            popup.hide();
            // TODO: 调用 CameraMainController 的设备绑定方法
            // 可以通过事件或回调机制实现
        });
        
        Button laterButton = new Button("稍后再说");
        laterButton.setStyle(
            "-fx-background-color: #374151; " +
            "-fx-text-fill: white; " +
            "-fx-font-size: 14; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 8 20; " +
            "-fx-background-radius: 6;"
        );
        laterButton.setOnMouseEntered(e -> 
            laterButton.setStyle(
                "-fx-background-color: #4b5563; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 6;"
            )
        );
        laterButton.setOnMouseExited(e -> 
            laterButton.setStyle(
                "-fx-background-color: #374151; " +
                "-fx-text-fill: white; " +
                "-fx-font-size: 14; " +
                "-fx-cursor: hand; " +
                "-fx-padding: 8 20; " +
                "-fx-background-radius: 6;"
            )
        );
        laterButton.setOnAction(e -> popup.hide());
        
        buttonBox.getChildren().addAll(goBindButton, laterButton);
        
        // 组装内容
        contentBox.getChildren().addAll(iconLabel, messageLabel, hintLabel, buttonBox);
        
        // 底部圆角
        Region bottomRadius = new Region();
        bottomRadius.setStyle(
            "-fx-background-color: #1f1f1f; " +
            "-fx-background-radius: 0 0 12 12;"
        );
        bottomRadius.setPrefHeight(12);
        
        // 组装主容器
        mainContainer.getChildren().addAll(titleBar, contentBox);
        
        // 设置拖动功能
        setupDragging();
        
        // 添加到 Popup
        popup.getContent().add(mainContainer);
        
        // 显示在屏幕中央
        if (owner != null) {
            double centerX = owner.getX() + owner.getWidth() / 2 - 200;  // 200 = 400/2
            double centerY = owner.getY() + owner.getHeight() / 2 - 150;
            popup.show(owner, centerX, centerY);
            
            // ⭐ 监听主窗口最小化/失去焦点，自动关闭弹框
            owner.iconifiedProperty().addListener((obs, wasIconified, isIconified) -> {
                if (isIconified && popup.isShowing()) {
                    popup.hide();
                }
            });
            owner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused && popup.isShowing()) {
                    javafx.application.Platform.runLater(() -> {
                        if (!owner.isFocused() && popup.isShowing()) {
                            popup.hide();
                        }
                    });
                }
            });
        } else {
            popup.show(owner);
        }
        
        // 保存当前 Popup 引用
        currentPopup = popup;
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
            
            // 鼠标悬停时显示可拖动的提示（改变鼠标样式）
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
}

