package com.acard.acard;

import com.acard.acard.utils.AlertUtil;
import com.acard.acard.utils.DirectoryUtils;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.freedesktop.gstreamer.Gst;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class HelloApplication extends Application {
    // 提前设置显示相关的 JVM 属性，确保在任何播放器创建或管线构建之前生效
    static {
        // 走 GPU 硬解链路：强制选择 d3d11h264dec
        System.setProperty("diag.decoder", "d3d11h264dec");
        // 保持 GPU 显示开（SimpleWebRTCPlayer 默认已 true，这里显式保证）
        System.setProperty("gpu.display", "true");
        
        // ✨ 启用轻量级抓拍功能（内存<3MB，CPU<0.5%）
        System.setProperty("capture.enabled", "true");
        System.out.println("🔧 应用启动: 已启用抓拍功能（capture.enabled=true）");
        
        // 注意：video.forceD3DVideoSink 由 GpuView.attach(player) 精确控制
    }
    
    private javafx.scene.control.Label statusLabel;
    private Timer statusTimer;
    
    @Override
    public void start(Stage stage) throws IOException {
        SplashScreen splash = SplashScreen.getInstance();
        
        // 使用GstBootstrap正确初始化GStreamer环境
        splash.updateProgress(30, "初始化 GStreamer...");
        try {
            if (!Gst.isInitialized()) {
                // 使用项目内置的 runtime\gstreamer\win64 目录
                GstBootstrap.init(true);
            }
        } catch (Exception e) {
            System.err.println("GStreamer初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
        splash.updateProgress(50, "GStreamer 初始化完成");

        // 加载登录界面并显示
        splash.updateProgress(60, "正在加载界面...");
        final String FXML_PATH ="/com/acard/acard/new-login-view.fxml";// "/com/acard/acard/login-view.fxml";
        FXMLLoader loader = new FXMLLoader(getClass().getResource(FXML_PATH));
        Parent root = loader.load();
        splash.updateProgress(80, "界面加载完成");

        // 登录窗口：透明样式（支持圆角和透明背景）
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("登录 - Secure Vision");
        
        // ⭐ 设置任务栏图标
        try {
            javafx.scene.image.Image icon = new javafx.scene.image.Image(getClass().getResourceAsStream("/images/icon.png"));
            stage.getIcons().add(icon);
        } catch (Exception e) {
            System.err.println("加载登录窗口图标失败: " + e.getMessage());
        }

        // 登录窗口尺寸
        Screen screen = Screen.getPrimary();
        double screenWidth = screen.getVisualBounds().getWidth();
        double screenHeight = screen.getVisualBounds().getHeight();
        
        // 固定窗口尺寸（仅登录表单，无视频）
        double windowWidth = 520;
        double windowHeight = 780;

        Scene scene = new Scene(root, windowWidth, windowHeight);
        // 设置 Scene 透明背景，避免圆角出现白边
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        stage.setScene(scene);

        // 设置固定窗口大小（不可调整）
        stage.setMinWidth(windowWidth);
        stage.setMaxWidth(windowWidth);
        stage.setMinHeight(windowHeight);
        stage.setMaxHeight(windowHeight);
        stage.setResizable(false);
        
        // 居中显示
        stage.setX((screenWidth - windowWidth) / 2);
        stage.setY((screenHeight - windowHeight) / 2);

        splash.updateProgress(90, "正在清理缓存...");
        DirectoryUtils.clearDirectory("runtime/captures/scaleslow");
        DirectoryUtils.clearDirectory("runtime/captures/slow");
        // ⭐ 暂时注释：保留 ssl 图片用于 AI 训练
        DirectoryUtils.clearDirectory("runtime/captures/ssl");
        DirectoryUtils.clearDirectory("runtime/captures/zp");
        DirectoryUtils.clearDirectory("runtime/captures/temp");
        DirectoryUtils.clearDirectory("runtime/slowmo");

        splash.updateProgress(100, "启动完成");
        
        // 显示主窗口并关闭启动画面
        stage.show();
        AlertUtil.setPrimaryStage(stage);
        
        // 延迟关闭启动画面，让用户看到 100% 进度
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {}
            splash.hide();
        }).start();

        // 关闭事件：退出应用
        stage.setOnCloseRequest(event -> javafx.application.Platform.exit());
    }

    // 保留 GPU 测试方法与面板工具（未在登录流程中使用）
    private void csGpu(Stage stage){
        SimpleWebRTCPlayer player = new SimpleWebRTCPlayer(
                "171.80.4.72",
                1985,
                "tenantA",
                "VID_1A191D98F454E3E4BAE32DBF50C7"
        );
        com.acard.acard.ui.GpuView gpuView = new com.acard.acard.ui.GpuView();
        gpuView.setPrefSize(1280, 720);
        gpuView.attach(player);
        javafx.scene.layout.BorderPane root = new javafx.scene.layout.BorderPane();
        root.setCenter(gpuView);
        Scene scene = new Scene(root, 1280, 720);
        stage.setTitle("GPU直显（VideoOverlay嵌入）测试");
        stage.setScene(scene);
        stage.show();
        player.start();
        stage.setOnCloseRequest(evt -> {
            try { player.stop(); } catch (Exception ignored) {}
        });
    }

    private javafx.scene.layout.HBox createControlPanel(SimpleWebRTCPlayer player) {
        javafx.scene.layout.HBox panel = new javafx.scene.layout.HBox(10);
        panel.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0;");
        javafx.scene.control.Button startButton = new javafx.scene.control.Button("▶️ 开始播放");
        startButton.setOnAction(e -> {
            System.out.println("🎬 用户点击开始播放");
            player.start();
        });
        javafx.scene.control.Button stopButton = new javafx.scene.control.Button("⏹️ 停止播放");
        stopButton.setOnAction(e -> {
            System.out.println("🛑 用户点击停止播放");
            player.stop();
        });
        panel.getChildren().addAll(startButton, stopButton);
        return panel;
    }

    private javafx.scene.layout.VBox createStatusPanel(SimpleWebRTCPlayer player) {
        javafx.scene.layout.VBox panel = new javafx.scene.layout.VBox(5);
        panel.setStyle("-fx-padding: 10; -fx-background-color: #e8e8e8;");
        javafx.scene.control.Label titleLabel = new javafx.scene.control.Label("📊 NAL单元接收状态监控");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        statusLabel = new javafx.scene.control.Label("等待开始...");
        statusLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        javafx.scene.control.Label infoLabel = new javafx.scene.control.Label(
            "目标: 确保接收到 SPS(7) + PPS(8) + IDR(5) 才能正常解码\n" +
            "监控: P-slice(1) 和 FU-A(28) 的接收状态\n" +
            "机制: PLI请求获取缺失的关键帧"
        );
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #666666;");
        panel.getChildren().addAll(titleLabel, statusLabel, infoLabel);
        return panel;
    }

    private void startStatusTimer(SimpleWebRTCPlayer player) {
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
        }, 0, 500);
    }

    private void cleanup(SimpleWebRTCPlayer player) {
        System.out.println("🧹 清理资源...");
        if (statusTimer != null) {
            statusTimer.cancel();
        }
        if (player != null) {
            player.stop();
        }
        System.out.println("✅ 资源清理完成");
    }

    /**
     * 应用退出时清理所有资源
     * 确保所有线程池、网络连接、GStreamer pipeline 正确关闭
     */
    @Override
    public void stop() throws Exception {
        System.out.println("==================== 开始清理资源 ====================");
        
        try {
            // 1. 关闭 HTTP 客户端
            com.acard.acard.net.HttpClientManager.getInstance().shutdown();
            System.out.println("✅ HTTP客户端已关闭");
        } catch (Exception e) {
            System.err.println("❌ 关闭HTTP客户端失败: " + e.getMessage());
        }
        
        try {
            // 2. 关闭 WebSocket/STOMP 连接
            com.acard.acard.net.NetworkManager.getInstance().shutdown();
            System.out.println("✅ 网络管理器已关闭");
        } catch (Exception e) {
            System.err.println("❌ 关闭网络管理器失败: " + e.getMessage());
        }
        
        try {
            // 3. 清理 GStreamer
            if (Gst.isInitialized()) {
                Gst.deinit();
                System.out.println("✅ GStreamer已关闭");
            }
        } catch (Exception e) {
            System.err.println("❌ 关闭GStreamer失败: " + e.getMessage());
        }
        
        // 4. 清理定时器
        if (statusTimer != null) {
            statusTimer.cancel();
            System.out.println("✅ 定时器已关闭");
        }
        
        System.out.println("==================== 资源清理完成 ====================");
        
        // 5. 强制退出 JVM（确保所有非守护线程都终止）
        System.exit(0);
    }

    public static void main(String[] args) {
        // ⚡ 立即显示启动画面（Swing 比 JavaFX 启动快很多）
        SplashScreen splash = SplashScreen.getInstance();
        splash.show();
        splash.updateProgress(5, "正在初始化...");
        
        // ✅ 日志重定向到 d:/mm.txt
        try {
            java.nio.file.Path logPath = java.nio.file.Paths.get("d:/mm.txt");
            java.nio.file.Files.createDirectories(logPath.getParent());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(logPath.toFile(), true);
            java.io.PrintStream ps = new java.io.PrintStream(fos, true, "UTF-8");
            System.setOut(ps);
            System.setErr(ps);
            System.out.println("==================== 应用启动 ====================");
            System.out.println("启动时间: " + java.time.LocalDateTime.now());
            System.out.println("日志文件: d:/mm.txt");
        } catch (Exception e) {
            e.printStackTrace();
        }
        splash.updateProgress(10, "日志系统已初始化");
        
        System.out.println("ENV PATH head = " + System.getenv("PATH"));
        System.out.println("ENV GST_PLUGIN_PATH = " + System.getenv("GST_PLUGIN_PATH"));
        System.out.println("ENV GST_PLUGIN_SCANNER = " + System.getenv("GST_PLUGIN_SCANNER"));
        
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            System.err.println("[Uncaught] 线程=" + t.getName() + ", 异常=" + ex);
            ex.printStackTrace(System.err);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("==================== 应用退出 ====================");
            System.out.println("退出时间: " + java.time.LocalDateTime.now());
        }));
        
        splash.updateProgress(15, "正在启动 JavaFX...");
        launch();
    }
}