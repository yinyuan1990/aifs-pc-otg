package com.acard.acard.update;

import com.acard.acard.tools.LogTools;
import com.google.gson.Gson;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;

/**
 * 简单暴力的更新检查器
 * 1. 对比版本号
 * 2. 不同就下载 jar
 * 3. 直接覆盖
 */
public class UpdateChecker {

    // ⚠️ 修改为你的实际版本号（每次发布新版本需要更新）
    public static final String CURRENT_VERSION = "1.0.9";
    public static final int CURRENT_VERSION_CODE = 109;

    // 版本检查地址
    private static final String VERSION_URL = "http://171.80.4.72:10004/updatesoft/version.json";
    
    // 文件名
    private static final String JAR_NAME = "Acard-1.0-SNAPSHOT.jar";
    private static final String EXE_NAME = "Acard.exe";
    private static final String UPDATE_ZIP = "update.zip";

    // 下载超时（毫秒）
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 300000;  // 5分钟

    private static UpdateChecker instance;
    private boolean isChecking = false;

    public static synchronized UpdateChecker getInstance() {
        if (instance == null) {
            instance = new UpdateChecker();
        }
        return instance;
    }

    /**
     * 检查更新（异步）
     */
    public void checkUpdateAsync(boolean showNoUpdateTip) {
        if (isChecking) {
            LogTools.getInstance().logRecord5("⏳ 正在检查更新中...");
            return;
        }

        new Thread(() -> {
            isChecking = true;
            try {
                checkUpdate(showNoUpdateTip);
            } finally {
                isChecking = false;
            }
        }).start();
    }

    /**
     * 检查更新
     */
    private void checkUpdate(boolean showNoUpdateTip) {
        LogTools.getInstance().logRecord5("🔍 检查更新... 当前版本: " + CURRENT_VERSION + " (code=" + CURRENT_VERSION_CODE + ")");

        try {
            // 1. 获取服务器版本信息
            VersionInfo serverVersion = fetchVersionInfo();
            if (serverVersion == null) {
                LogTools.getInstance().logRecord5("❌ 获取版本信息失败");
                return;
            }

            LogTools.getInstance().logRecord5("📦 服务器版本: " + serverVersion.getVersion() + " (code=" + serverVersion.getVersionCode() + ")");

            // 2. 对比版本号
            if (serverVersion.getVersionCode() > CURRENT_VERSION_CODE) {
                // 有新版本
                LogTools.getInstance().logRecord5("🆕 发现新版本: " + serverVersion.getVersion());
                showUpdateDialog(serverVersion);
            } else {
                LogTools.getInstance().logRecord5("✅ 当前已是最新版本");
                if (showNoUpdateTip) {
                    Platform.runLater(() -> showInfoPopup("检查更新", "当前已是最新版本 " + CURRENT_VERSION));
                }
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord5("❌ 检查更新失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 获取服务器版本信息（手动解析JSON避免反射问题）
     */
    private VersionInfo fetchVersionInfo() {
        try {
            URL url = new URL(VERSION_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(CONNECT_TIMEOUT);
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                // 手动解析 JSON，避免 Gson 反射问题
                String json = sb.toString();
                VersionInfo info = new VersionInfo();
                
                // 解析 version
                info.version = extractJsonString(json, "version");
                // 解析 versionCode
                info.versionCode = extractJsonInt(json, "versionCode");
                // 解析 downloadUrl
                info.downloadUrl = extractJsonString(json, "downloadUrl");
                // 解析 changelog
                info.changelog = extractJsonString(json, "changelog");
                // 解析 forceUpdate
                info.forceUpdate = extractJsonBoolean(json, "forceUpdate");
                
                return info;
            }
        } catch (Exception e) {
            LogTools.getInstance().logRecord5("❌ 获取版本信息异常: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 从 JSON 中提取字符串值
     */
    private String extractJsonString(String json, String key) {
        try {
            // 查找 "key": "value" 格式
            String searchKey = "\"" + key + "\"";
            int keyIndex = json.indexOf(searchKey);
            if (keyIndex == -1) return null;
            
            // 找到冒号
            int colonIndex = json.indexOf(":", keyIndex + searchKey.length());
            if (colonIndex == -1) return null;
            
            // 找到值的起始引号
            int valueStart = json.indexOf("\"", colonIndex + 1);
            if (valueStart == -1) return null;
            valueStart++; // 跳过引号
            
            // 找到值的结束引号（处理转义）
            int valueEnd = valueStart;
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if (c == '\\' && valueEnd + 1 < json.length()) {
                    valueEnd += 2; // 跳过转义字符
                } else if (c == '"') {
                    break;
                } else {
                    valueEnd++;
                }
            }
            
            String value = json.substring(valueStart, valueEnd);
            // 处理转义字符
            return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 从 JSON 中提取整数值
     */
    private int extractJsonInt(String json, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*";
            int start = json.indexOf("\"" + key + "\"");
            if (start == -1) return 0;
            
            // 找到冒号后的数字
            int colonIndex = json.indexOf(":", start);
            if (colonIndex == -1) return 0;
            
            int valueStart = colonIndex + 1;
            // 跳过空白
            while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
                valueStart++;
            }
            
            // 读取数字
            int valueEnd = valueStart;
            while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
                valueEnd++;
            }
            
            return Integer.parseInt(json.substring(valueStart, valueEnd));
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * 从 JSON 中提取布尔值
     */
    private boolean extractJsonBoolean(String json, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*";
            int start = json.indexOf("\"" + key + "\"");
            if (start == -1) return false;
            
            int colonIndex = json.indexOf(":", start);
            if (colonIndex == -1) return false;
            
            String rest = json.substring(colonIndex + 1).trim();
            return rest.startsWith("true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 显示更新对话框（深色主题风格）
     */
    private void showUpdateDialog(VersionInfo versionInfo) {
        Platform.runLater(() -> {
            Window owner = getActiveWindow();
            if (owner == null) return;
            
            Popup popup = new Popup();
            popup.setAutoHide(false);
            
            // 主容器
            VBox root = new VBox(0);
            root.setStyle(
                "-fx-background-color: #1E1E1E; " +
                "-fx-background-radius: 12; " +
                "-fx-border-color: #3C3C3C; " +
                "-fx-border-radius: 12; " +
                "-fx-border-width: 1; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);"
            );
            root.setPrefWidth(400);
            
            // 顶部标题栏
            HBox topBar = new HBox();
            topBar.setAlignment(Pos.CENTER_LEFT);
            topBar.setStyle(
                "-fx-background-color: #292929; " +
                "-fx-padding: 16 20; " +
                "-fx-background-radius: 12 12 0 0;"
            );
            
            Label title = new Label("🆕 发现新版本");
            title.setStyle(
                "-fx-text-fill: #4ADE80; " +
                "-fx-font-size: 18px; " +
                "-fx-font-weight: bold;"
            );
            
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            
            Button closeBtn = createCloseButton();
            closeBtn.setOnAction(e -> popup.hide());
            
            topBar.getChildren().addAll(title, spacer, closeBtn);
            
            // 内容区域
            VBox content = new VBox(12);
            content.setStyle("-fx-padding: 20;");
            
            Label versionLabel = new Label("新版本: " + versionInfo.getVersion());
            versionLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold;");
            
            Label currentLabel = new Label("当前版本: " + CURRENT_VERSION);
            currentLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 14px;");
            
            content.getChildren().addAll(versionLabel, currentLabel);
            
            // 更新日志
            if (versionInfo.getChangelog() != null && !versionInfo.getChangelog().isEmpty()) {
                Label changelogTitle = new Label("更新内容:");
                changelogTitle.setStyle("-fx-text-fill: #CCCCCC; -fx-font-size: 14px; -fx-padding: 10 0 5 0;");
                
                Label changelogContent = new Label(versionInfo.getChangelog());
                changelogContent.setStyle("-fx-text-fill: #AAAAAA; -fx-font-size: 13px;");
                changelogContent.setWrapText(true);
                
                content.getChildren().addAll(changelogTitle, changelogContent);
            }
            
            // 按钮区域
            HBox buttonBox = new HBox(15);
            buttonBox.setAlignment(Pos.CENTER_RIGHT);
            buttonBox.setStyle("-fx-padding: 15 20 20 20;");
            
            Button laterBtn = new Button("稍后再说");
            laterBtn.setStyle(
                "-fx-background-color: #3C3C3C; " +
                "-fx-text-fill: #CCCCCC; " +
                "-fx-font-size: 14px; " +
                "-fx-padding: 10 25; " +
                "-fx-background-radius: 6; " +
                "-fx-cursor: hand;"
            );
            laterBtn.setOnMouseEntered(e -> laterBtn.setStyle(
                "-fx-background-color: #4C4C4C; -fx-text-fill: #FFFFFF; -fx-font-size: 14px; -fx-padding: 10 25; -fx-background-radius: 6; -fx-cursor: hand;"
            ));
            laterBtn.setOnMouseExited(e -> laterBtn.setStyle(
                "-fx-background-color: #3C3C3C; -fx-text-fill: #CCCCCC; -fx-font-size: 14px; -fx-padding: 10 25; -fx-background-radius: 6; -fx-cursor: hand;"
            ));
            laterBtn.setOnAction(e -> popup.hide());
            
            Button updateBtn = new Button("立即更新");
            updateBtn.setStyle(
                "-fx-background-color: #22C55E; " +
                "-fx-text-fill: #FFFFFF; " +
                "-fx-font-size: 14px; " +
                "-fx-font-weight: bold; " +
                "-fx-padding: 10 25; " +
                "-fx-background-radius: 6; " +
                "-fx-cursor: hand;"
            );
            updateBtn.setOnMouseEntered(e -> updateBtn.setStyle(
                "-fx-background-color: #16A34A; -fx-text-fill: #FFFFFF; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 25; -fx-background-radius: 6; -fx-cursor: hand;"
            ));
            updateBtn.setOnMouseExited(e -> updateBtn.setStyle(
                "-fx-background-color: #22C55E; -fx-text-fill: #FFFFFF; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 25; -fx-background-radius: 6; -fx-cursor: hand;"
            ));
            updateBtn.setOnAction(e -> {
                popup.hide();
                startDownloadUpdate(versionInfo);
            });
            
            buttonBox.getChildren().addAll(laterBtn, updateBtn);
            
            root.getChildren().addAll(topBar, content, buttonBox);
            popup.getContent().add(root);
            
            // 居中显示
            popup.setOnShown(e -> {
                popup.setX(owner.getX() + (owner.getWidth() - root.getWidth()) / 2);
                popup.setY(owner.getY() + (owner.getHeight() - root.getHeight()) / 2);
            });
            
            popup.show(owner);
        });
    }

    // 下载进度 Popup
    private Popup downloadPopup;
    private ProgressBar downloadProgressBar;
    private Label downloadStatusLabel;
    
    /**
     * 开始下载更新（外部 jar 模式：只下载 jar）
     */
    private void startDownloadUpdate(VersionInfo versionInfo) {
        // 显示下载进度弹窗
        Platform.runLater(() -> showDownloadProgress());
        
        new Thread(() -> {
            try {
                // 基础 URL（去掉文件名）
                String baseUrl = versionInfo.getDownloadUrl();
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }
                // 如果 downloadUrl 是完整文件路径，取其目录
                int lastSlash = baseUrl.lastIndexOf("/");
                if (lastSlash > 0) {
                    baseUrl = baseUrl.substring(0, lastSlash);
                }
                
                String jarUrl = baseUrl + "/" + JAR_NAME;
                
                // 下载目录：Acard.exe 同级目录下的 _update 文件夹
                Path appDir = getAppDirectory();
                Path tempDir = appDir.resolve("_update");
                if (!Files.exists(tempDir)) {
                    Files.createDirectories(tempDir);
                }
                LogTools.getInstance().logRecord5("📁 下载目录: " + tempDir);
                
                // 只下载 JAR（外部 jar 模式，exe 只是启动器不需要更新）
                LogTools.getInstance().logRecord5("⬇️ 下载 JAR: " + jarUrl);
                Platform.runLater(() -> {
                    if (downloadStatusLabel != null) {
                        downloadStatusLabel.setText("正在下载 " + JAR_NAME + " ...");
                    }
                });
                Path tempJar = tempDir.resolve(JAR_NAME);
                downloadFileWithProgress(jarUrl, tempJar);
                LogTools.getInstance().logRecord5("✅ JAR 下载完成: " + tempJar);
                
                // 获取目标路径
                Path targetJar = appDir.resolve("lib").resolve(JAR_NAME);
                
                LogTools.getInstance().logRecord5("📂 目标 JAR: " + targetJar);

                // 关闭下载进度弹窗
                Platform.runLater(() -> {
                    if (downloadPopup != null) {
                        downloadPopup.hide();
                    }
                });

                // 创建更新脚本并执行（只覆盖 jar）
                createAndRunUpdateScriptJarOnly(tempJar, targetJar, appDir);

            } catch (Exception e) {
                LogTools.getInstance().logRecord5("❌ 下载更新失败: " + e.getMessage());
                e.printStackTrace();
                Platform.runLater(() -> {
                    if (downloadPopup != null) {
                        downloadPopup.hide();
                    }
                    showErrorPopup("更新失败", "下载更新失败: " + e.getMessage());
                });
            }
        }).start();
    }
    
    /**
     * 创建并运行更新脚本（只覆盖 jar，外部 jar 模式）
     */
    private void createAndRunUpdateScriptJarOnly(Path newJar, Path targetJar, Path appDir) throws IOException {
        // 创建更新脚本（放在 _update 目录下）
        Path tempDir = appDir.resolve("_update");
        Path scriptPath = tempDir.resolve("update_acard.bat");

        String script = "@echo off\r\n" +
                "chcp 65001 > nul\r\n" +
                "echo ========================================\r\n" +
                "echo        Acard 自动更新\r\n" +
                "echo ========================================\r\n" +
                "echo.\r\n" +
                "echo 等待程序退出...\r\n" +
                "timeout /t 3 /nobreak > nul\r\n" +
                "\r\n" +
                "echo 覆盖 JAR 文件...\r\n" +
                "echo   源: " + newJar.toString() + "\r\n" +
                "echo   目标: " + targetJar.toString() + "\r\n" +
                "if not exist \"" + targetJar.getParent().toString() + "\" mkdir \"" + targetJar.getParent().toString() + "\"\r\n" +
                "if exist \"" + newJar.toString() + "\" (\r\n" +
                "    copy /Y \"" + newJar.toString() + "\" \"" + targetJar.toString() + "\"\r\n" +
                "    if errorlevel 1 (\r\n" +
                "        echo   [错误] JAR 复制失败！\r\n" +
                "    ) else (\r\n" +
                "        echo   JAR 更新成功！\r\n" +
                "    )\r\n" +
                ") else (\r\n" +
                "    echo   [跳过] JAR 源文件不存在\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo 清理临时文件...\r\n" +
                "rd /s /q \"" + tempDir.toString() + "\" 2>nul\r\n" +
                "\r\n" +
                "echo 启动程序...\r\n" +
                "cd /d \"" + appDir.toString() + "\"\r\n" +
                "if exist \"" + EXE_NAME + "\" (\r\n" +
                "    start \"\" \"" + EXE_NAME + "\"\r\n" +
                ") else if exist \"启动Acard.bat\" (\r\n" +
                "    start \"\" \"启动Acard.bat\"\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo.\r\n" +
                "echo ========================================\r\n" +
                "echo        更新完成！\r\n" +
                "echo ========================================\r\n" +
                "timeout /t 2 /nobreak > nul\r\n" +
                "del \"%~f0\"\r\n";

        Files.writeString(scriptPath, script);

        LogTools.getInstance().logRecord5("📝 更新脚本已创建: " + scriptPath);

        // 提示用户
        Platform.runLater(() -> {
            showConfirmUpdatePopup(scriptPath);
        });
    }
    
    /**
     * 显示下载进度弹窗
     */
    private void showDownloadProgress() {
        Window owner = getActiveWindow();
        if (owner == null) return;
        
        downloadPopup = new Popup();
        downloadPopup.setAutoHide(false);
        
        VBox root = new VBox(15);
        root.setStyle(
            "-fx-background-color: #1E1E1E; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #3C3C3C; " +
            "-fx-border-radius: 12; " +
            "-fx-border-width: 1; " +
            "-fx-padding: 25; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);"
        );
        root.setPrefWidth(350);
        root.setAlignment(Pos.CENTER);
        
        Label title = new Label("⬇️ 正在下载更新...");
        title.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 16px; -fx-font-weight: bold;");
        
        downloadProgressBar = new ProgressBar(0);
        downloadProgressBar.setPrefWidth(300);
        downloadProgressBar.setStyle("-fx-accent: #22C55E;");
        
        downloadStatusLabel = new Label("准备下载...");
        downloadStatusLabel.setStyle("-fx-text-fill: #888888; -fx-font-size: 13px;");
        
        root.getChildren().addAll(title, downloadProgressBar, downloadStatusLabel);
        downloadPopup.getContent().add(root);
        
        // 居中显示
        downloadPopup.setOnShown(e -> {
            downloadPopup.setX(owner.getX() + (owner.getWidth() - root.getWidth()) / 2);
            downloadPopup.setY(owner.getY() + (owner.getHeight() - root.getHeight()) / 2);
        });
        
        downloadPopup.show(owner);
    }

    /**
     * 下载文件（带进度更新）
     */
    private void downloadFileWithProgress(String urlStr, Path destPath) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(destPath)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long contentLength = conn.getContentLengthLong();

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (contentLength > 0) {
                    double progress = (double) totalRead / contentLength;
                    long finalTotalRead = totalRead;
                    Platform.runLater(() -> {
                        if (downloadProgressBar != null) {
                            downloadProgressBar.setProgress(progress);
                        }
                        if (downloadStatusLabel != null) {
                            downloadStatusLabel.setText(String.format("已下载: %.1f MB / %.1f MB", 
                                finalTotalRead / 1024.0 / 1024.0, 
                                contentLength / 1024.0 / 1024.0));
                        }
                    });
                    System.out.print("\r⬇️ 下载进度: " + (int)(progress * 100) + "% (" + totalRead / 1024 + "KB)");
                }
            }
            LogTools.getInstance().logRecord5("\n✅ 下载完成");
        }
    }

    /**
     * 获取应用目录（exe 所在目录）
     */
    private Path getAppDirectory() {
        // 获取当前 jar 所在目录的父目录（因为 jar 在 lib/ 下）
        try {
            String path = UpdateChecker.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            
            // Windows 路径处理
            if (path.startsWith("/") && path.contains(":")) {
                path = path.substring(1);
            }
            
            File file = new File(path);
            if (file.isFile()) {
                // jar 文件 -> 获取 lib 目录 -> 再获取父目录
                return file.getParentFile().getParentFile().toPath();
            }
            return file.getParentFile().toPath();
        } catch (Exception e) {
            // 回退到当前目录
            return Paths.get(System.getProperty("user.dir"));
        }
    }

    /**
     * 创建并运行更新脚本
     * 直接覆盖 jar 文件，然后重启
     */
    /**
     * 创建并运行更新脚本（覆盖 exe 和 jar）
     */
    private void createAndRunUpdateScript(Path newExe, Path newJar, Path targetExe, Path targetJar, Path appDir) throws IOException {
        // 创建更新脚本（放在 _update 目录下）
        Path tempDir = appDir.resolve("_update");
        Path scriptPath = tempDir.resolve("update_acard.bat");

        String script = "@echo off\r\n" +
                "chcp 65001 > nul\r\n" +
                "echo ========================================\r\n" +
                "echo        Acard 自动更新\r\n" +
                "echo ========================================\r\n" +
                "echo.\r\n" +
                "echo 等待程序退出...\r\n" +
                "timeout /t 3 /nobreak > nul\r\n" +
                "\r\n" +
                "echo 覆盖 EXE 文件...\r\n" +
                "echo   源: " + newExe.toString() + "\r\n" +
                "echo   目标: " + targetExe.toString() + "\r\n" +
                "if exist \"" + newExe.toString() + "\" (\r\n" +
                "    copy /Y \"" + newExe.toString() + "\" \"" + targetExe.toString() + "\"\r\n" +
                "    if errorlevel 1 (\r\n" +
                "        echo   [错误] EXE 复制失败！\r\n" +
                "    ) else (\r\n" +
                "        echo   EXE 更新成功！\r\n" +
                "    )\r\n" +
                ") else (\r\n" +
                "    echo   [跳过] EXE 源文件不存在\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo 覆盖 JAR 文件...\r\n" +
                "echo   源: " + newJar.toString() + "\r\n" +
                "echo   目标: " + targetJar.toString() + "\r\n" +
                "if not exist \"" + targetJar.getParent().toString() + "\" mkdir \"" + targetJar.getParent().toString() + "\"\r\n" +
                "if exist \"" + newJar.toString() + "\" (\r\n" +
                "    copy /Y \"" + newJar.toString() + "\" \"" + targetJar.toString() + "\"\r\n" +
                "    if errorlevel 1 (\r\n" +
                "        echo   [错误] JAR 复制失败！\r\n" +
                "    ) else (\r\n" +
                "        echo   JAR 更新成功！\r\n" +
                "    )\r\n" +
                ") else (\r\n" +
                "    echo   [跳过] JAR 源文件不存在\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo 清理临时文件...\r\n" +
                "rd /s /q \"" + tempDir.toString() + "\" 2>nul\r\n" +
                "\r\n" +
                "echo 启动程序...\r\n" +
                "cd /d \"" + appDir.toString() + "\"\r\n" +
                "if exist \"" + EXE_NAME + "\" (\r\n" +
                "    start \"\" \"" + EXE_NAME + "\"\r\n" +
                ") else if exist \"启动Acard.bat\" (\r\n" +
                "    start \"\" \"启动Acard.bat\"\r\n" +
                ")\r\n" +
                "\r\n" +
                "echo.\r\n" +
                "echo ========================================\r\n" +
                "echo        更新完成！\r\n" +
                "echo ========================================\r\n" +
                "timeout /t 2 /nobreak > nul\r\n" +
                "del \"%~f0\"\r\n";

        Files.writeString(scriptPath, script);

        LogTools.getInstance().logRecord5("📝 更新脚本已创建: " + scriptPath);

        // 提示用户
        Platform.runLater(() -> {
            showConfirmUpdatePopup(scriptPath);
        });
    }
    
    /**
     * 显示确认更新弹窗
     */
    private void showConfirmUpdatePopup(Path scriptPath) {
        Window owner = getActiveWindow();
        if (owner == null) return;
        
        Popup popup = new Popup();
        popup.setAutoHide(false);
        
        VBox root = new VBox(0);
        root.setStyle(
            "-fx-background-color: #1E1E1E; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #3C3C3C; " +
            "-fx-border-radius: 12; " +
            "-fx-border-width: 1; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);"
        );
        root.setPrefWidth(380);
        
        // 顶部标题栏
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-padding: 16 20; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        
        Label title = new Label("✅ 准备更新");
        title.setStyle("-fx-text-fill: #22C55E; -fx-font-size: 18px; -fx-font-weight: bold;");
        topBar.getChildren().add(title);
        
        // 内容
        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 20;");
        
        Label msg1 = new Label("更新包已下载完成！");
        msg1.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 15px;");
        
        Label msg2 = new Label("点击「立即更新」后程序将关闭并自动更新，\n更新完成后会自动重启。");
        msg2.setStyle("-fx-text-fill: #888888; -fx-font-size: 13px;");
        msg2.setWrapText(true);
        
        content.getChildren().addAll(msg1, msg2);
        
        // 按钮
        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.setStyle("-fx-padding: 10 20 20 20;");
        
        Button updateBtn = new Button("立即更新");
        updateBtn.setStyle(
            "-fx-background-color: #22C55E; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 14px; " +
            "-fx-font-weight: bold; " +
            "-fx-padding: 10 30; " +
            "-fx-background-radius: 6; " +
            "-fx-cursor: hand;"
        );
        updateBtn.setOnMouseEntered(e -> updateBtn.setStyle(
            "-fx-background-color: #16A34A; -fx-text-fill: #FFFFFF; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 30; -fx-background-radius: 6; -fx-cursor: hand;"
        ));
        updateBtn.setOnMouseExited(e -> updateBtn.setStyle(
            "-fx-background-color: #22C55E; -fx-text-fill: #FFFFFF; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10 30; -fx-background-radius: 6; -fx-cursor: hand;"
        ));
        updateBtn.setOnAction(e -> {
            popup.hide();
            // 运行更新脚本并退出程序
            try {
                Runtime.getRuntime().exec("cmd /c start /min \"\" \"" + scriptPath.toString() + "\"");
                LogTools.getInstance().logRecord5("🚀 启动更新脚本，程序即将退出...");
                
                // 延迟退出，让脚本启动
                new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        System.exit(0);
                    } catch (InterruptedException ignored) {}
                }).start();
                
            } catch (IOException ex) {
                LogTools.getInstance().logRecord5("❌ 启动更新脚本失败: " + ex.getMessage());
                showErrorPopup("更新失败", "启动更新脚本失败: " + ex.getMessage());
            }
        });
        
        buttonBox.getChildren().add(updateBtn);
        
        root.getChildren().addAll(topBar, content, buttonBox);
        popup.getContent().add(root);
        
        // 居中显示
        popup.setOnShown(e -> {
            popup.setX(owner.getX() + (owner.getWidth() - root.getWidth()) / 2);
            popup.setY(owner.getY() + (owner.getHeight() - root.getHeight()) / 2);
        });
        
        popup.show(owner);
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取当前活动窗口
     */
    private Window getActiveWindow() {
        for (Window window : Stage.getWindows()) {
            if (window.isShowing() && window.isFocused()) {
                return window;
            }
        }
        // 如果没有焦点窗口，返回第一个显示的窗口
        for (Window window : Stage.getWindows()) {
            if (window.isShowing()) {
                return window;
            }
        }
        return null;
    }
    
    /**
     * 创建关闭按钮
     */
    private Button createCloseButton() {
        Button closeBtn = new Button("×");
        closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        );
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
            "-fx-background-color: #DC2626; " +
            "-fx-text-fill: #FFFFFF; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8; " +
            "-fx-background-radius: 4;"
        ));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
            "-fx-background-color: transparent; " +
            "-fx-text-fill: #888888; " +
            "-fx-font-size: 24px; " +
            "-fx-font-weight: bold; " +
            "-fx-cursor: hand; " +
            "-fx-padding: 0 8;"
        ));
        return closeBtn;
    }
    
    /**
     * 显示信息弹窗
     */
    private void showInfoPopup(String titleText, String message) {
        Window owner = getActiveWindow();
        if (owner == null) return;
        
        Popup popup = new Popup();
        popup.setAutoHide(true);
        
        VBox root = new VBox(0);
        root.setStyle(
            "-fx-background-color: #1E1E1E; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #3C3C3C; " +
            "-fx-border-radius: 12; " +
            "-fx-border-width: 1; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);"
        );
        root.setPrefWidth(320);
        
        // 顶部标题栏
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-padding: 16 20; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        
        Label title = new Label("ℹ️ " + titleText);
        title.setStyle("-fx-text-fill: #60A5FA; -fx-font-size: 16px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button closeBtn = createCloseButton();
        closeBtn.setOnAction(e -> popup.hide());
        
        topBar.getChildren().addAll(title, spacer, closeBtn);
        
        // 内容
        Label content = new Label(message);
        content.setStyle("-fx-text-fill: #CCCCCC; -fx-font-size: 14px; -fx-padding: 20;");
        content.setWrapText(true);
        
        root.getChildren().addAll(topBar, content);
        popup.getContent().add(root);
        
        // 居中显示
        popup.setOnShown(e -> {
            popup.setX(owner.getX() + (owner.getWidth() - root.getWidth()) / 2);
            popup.setY(owner.getY() + (owner.getHeight() - root.getHeight()) / 2);
        });
        
        popup.show(owner);
    }
    
    /**
     * 显示错误弹窗
     */
    private void showErrorPopup(String titleText, String message) {
        Window owner = getActiveWindow();
        if (owner == null) return;
        
        Popup popup = new Popup();
        popup.setAutoHide(true);
        
        VBox root = new VBox(0);
        root.setStyle(
            "-fx-background-color: #1E1E1E; " +
            "-fx-background-radius: 12; " +
            "-fx-border-color: #DC2626; " +
            "-fx-border-radius: 12; " +
            "-fx-border-width: 1; " +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0, 0, 5);"
        );
        root.setPrefWidth(350);
        
        // 顶部标题栏
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle(
            "-fx-background-color: #292929; " +
            "-fx-padding: 16 20; " +
            "-fx-background-radius: 12 12 0 0;"
        );
        
        Label title = new Label("❌ " + titleText);
        title.setStyle("-fx-text-fill: #EF4444; -fx-font-size: 16px; -fx-font-weight: bold;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        Button closeBtn = createCloseButton();
        closeBtn.setOnAction(e -> popup.hide());
        
        topBar.getChildren().addAll(title, spacer, closeBtn);
        
        // 内容
        Label content = new Label(message);
        content.setStyle("-fx-text-fill: #CCCCCC; -fx-font-size: 14px; -fx-padding: 20;");
        content.setWrapText(true);
        
        root.getChildren().addAll(topBar, content);
        popup.getContent().add(root);
        
        // 居中显示
        popup.setOnShown(e -> {
            popup.setX(owner.getX() + (owner.getWidth() - root.getWidth()) / 2);
            popup.setY(owner.getY() + (owner.getHeight() - root.getHeight()) / 2);
        });
        
        popup.show(owner);
    }
}

