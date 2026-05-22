package com.acard.acard.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

public class NewCameraSettingsDialogLauncher {
    
    /**
     * 显示新的相机设置对话框
     * @param owner 父窗口
     * @return 对话框控制器实例
     */
    public static NewCameraSettingsDialogController showDialog(Stage owner) {
        try {
            // 加载FXML文件
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(NewCameraSettingsDialogLauncher.class.getResource("/com/acard/acard/ui/NewCameraSettingsDialog.fxml"));
            Parent root = loader.load();
            
            // 获取控制器
            NewCameraSettingsDialogController controller = loader.getController();
            
            // 创建对话框舞台
            Stage dialogStage = new Stage();
            dialogStage.setTitle("相机设置");
            dialogStage.initModality(Modality.WINDOW_MODAL);
            dialogStage.initOwner(owner);
            dialogStage.initStyle(StageStyle.UNDECORATED); // 无边框样式，使用自定义标题栏
            
            // 设置场景
            Scene scene = new Scene(root);
            dialogStage.setScene(scene);
            
            // 设置对话框舞台到控制器
            controller.setDialogStage(dialogStage);
            
            // 使对话框可拖拽
            makeDraggable(root, dialogStage);
            
            // 显示对话框并等待
            dialogStage.showAndWait();
            
            return controller;
            
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 使对话框可拖拽
     * @param root 根节点
     * @param stage 舞台
     */
    private static void makeDraggable(Parent root, Stage stage) {
        final double[] xOffset = {0};
        final double[] yOffset = {0};
        
        root.setOnMousePressed(event -> {
            xOffset[0] = event.getSceneX();
            yOffset[0] = event.getSceneY();
        });
        
        root.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset[0]);
            stage.setY(event.getScreenY() - yOffset[0]);
        });
    }
    
    /**
     * 简单的测试方法
     * @param primaryStage 主舞台
     */
    public static void testDialog(Stage primaryStage) {
        NewCameraSettingsDialogController controller = showDialog(primaryStage);
        
        if (controller != null && controller.isOkClicked()) {
            System.out.println("用户点击了确定按钮");
            System.out.println("曝光值: " + controller.getExposureValue());
            System.out.println("焦距值: " + controller.getFocusValue());
            System.out.println("帧率: " + controller.getFpsValue());
            System.out.println("码率: " + controller.getBitrateValue());
            System.out.println("对焦距离: " + controller.getFocusDistanceValue());
            System.out.println("亮度: " + controller.getBrightnessValue());
            System.out.println("饱和度: " + controller.getSaturationValue());
            System.out.println("对比度: " + controller.getContrastValue());
            System.out.println("分辨率: " + controller.getClarityValue());
            System.out.println("是否后置摄像头: " + controller.isBackCamera());
        } else {
            System.out.println("用户取消了对话框");
        }
    }
}