package com.acard.acard.tools;

import java.io.File;
import java.util.Arrays;

import com.acard.acard.events.RecordingFileReadyEvent;
import com.acard.acard.events.RecordingStartedEvent;
import com.acard.acard.events.UIUpdateEvent;
import com.acard.acard.events.UIUpdateEventManager;
import javafx.scene.input.KeyCode;
import com.acard.acard.store.ShortcutStore;

public class FileToos {

    public static double zoomScale = 0.15;  //镜头变倍

    public static double ImageScale = 0.25;
    
    // ⭐ 切换账号标记：只有从主页点击切换账号时才为 true，用于自动登录判断
    public static volatile boolean isSwitchAccountMode = false;
    
    // ⭐ 视频流状态：0=无视频流，1=有视频流（用于工作恢复倒计时检测）
    public static volatile int videoStreamStatus = 0;

    public static long getSystemMemoryGB() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();

            long totalMemoryBytes = osBean.getTotalPhysicalMemorySize();
            long totalMemoryGB = totalMemoryBytes / (1024 * 1024 * 1024);


            return totalMemoryGB;
        } catch (Exception e) {
            System.err.println("⚠️ 无法获取系统内存，默认按8GB处理");
            e.printStackTrace();
            return 8; // 默认按低端机处理
        }
    }

    public static int getOptimalJpegQuality() {
        long memoryGB = getSystemMemoryGB();

        if (memoryGB < 8) {
            // 极低端：quality=20 (约3KB/帧)
            System.out.println("🎯 JPEG质量：20（极低端机优化）");
            return 30;

        } else if (memoryGB < 16) {
            // 低端：quality=30 (约5KB/帧)
            System.out.println("🎯 JPEG质量：30（低端机优化）");
            return 30;

        } else if (memoryGB < 32) {
            // 中端：quality=50 (约30KB/帧)
            System.out.println("🎯 JPEG质量：50（中端机平衡）");
            return 30;

        } else {
            // 高端：quality=70 (约100KB/帧)
            System.out.println("🎯 JPEG质量：70（高端机高清）");
            return 60;
        }
    }

    public static int getDiuFps() {
        long memoryGB = getSystemMemoryGB();

        if (memoryGB < 8) {

            return 3;

        } else if (memoryGB < 16) {
            // 低端：quality=30 (约5KB/帧)
            System.out.println("🎯 JPEG质量：30（低端机优化）");
            return 2;

        } else if (memoryGB < 32) {
            // 中端：quality=50 (约30KB/帧)
            System.out.println("🎯 JPEG质量：50（中端机平衡）");
            return 1;

        } else {
            // 高端：quality=70 (约100KB/帧)
            System.out.println("🎯 JPEG质量：70（高端机高清）");
            return 1;
        }
    }


    public static int slowAllClear=0;

    public static int slowIndex=0;
    public static boolean isCallBack=false;
    public static int jpegIndex=0;

    public static int GpuIndex=0;


    public static long jpegIndexTimeMs=0;  // ⚡ JPEG写入时的时间戳（用于计算延迟偏移）
    public static int lzNum=0;
    public static int lzZNum=0;
    public static double slowspeed=1.0;

    public static int derection = 0;  // ⭐ 初始化为0，避免视频倾斜
    public static int usederection = 0;  // ⭐ 初始化为0，避免视频倾斜
    public static int slowWidth =0;
    public static int slowHight =0;
    public static int sslFps = 60;  // ⚡ 实时流帧率（用于计算延迟偏移）


    public static int sslWidth =0;
    public static int sslwHight =0;
    
    // ⭐ 实时流帧率统计（每秒更新）
    public static volatile int receiveFps = 0;  // 接收帧率（帧/秒）

    public static int botoomHight=50;



    public static boolean isIsCallBackFrame = false;






    /**
     * 快捷键工具类 - 封装ShortcutStore的访问方法
     */
    public static class ShortcutHelper {
        private static ShortcutStore store = ShortcutStore.getInstance();
        
        /**
         * 获取行增加调整快捷键
         */
        public static KeyCode getRowAdjustKey() {
            return store.getRowAdjustKey();
        }

        /**
         * 获取行减少调整快捷键
         */
        public static KeyCode getRowSubAdjustKey() {
            return store.getRowSubAdjustKey();
        }

        
        /**
         * 获取列增加调整快捷键
         */
        public static KeyCode getColAdjustKey() {
            return store.getColAdjustKey();
        }


        /**
         * 获取列减少调整快捷键
         */
        public static KeyCode getColSubAdjustKey() {
            return store.getColSubAdjustKey();
        }

        
        /**
         * 获取前后镜头切换快捷键
         */
        public static KeyCode getCameraSwitchKey() {
            return store.getCameraSwitchKey();
        }
        
        /**
         * 获取旋转快捷键
         * @param index 旋转索引 (0-7)
         */
        public static KeyCode getRotationKey(int index) {
            return store.getRotationKey(index);
        }
        
        /**
         * 获取画质快捷键
         * @param qualityIndex 画质索引 (0=4K, 1=超清, 2=高清, 3=标清)
         */
        public static KeyCode getQualityKey(int qualityIndex) {
            return store.getQualityKey(qualityIndex);
        }
        
        /**
         * 获取行增加调整快捷键名称
         */
        public static String getRowAdjustKeyName() {
            return store.getRowAdjustKey().getName();
        }
        /**
         * 获取行减少调整快捷键名称
         */
        public static String getRowSubAdjustKeyName() {
            return store.getRowSubAdjustKey().getName();
        }
        
        /**
         * 获取列增加调整快捷键名称
         */
        public static String getColAdjustKeyName() {
            return store.getColAdjustKey().getName();
        }

        /**
         * 获取列减少调整快捷键名称
         */
        public static String getColSubAdjustKeyName() {
            return store.getColSubAdjustKey().getName();
        }

        
        /**
         * 获取前后镜头切换快捷键名称
         */
        public static String getCameraSwitchKeyName() {
            return store.getCameraSwitchKey().getName();
        }
        
        /**
         * 获取旋转快捷键名称
         * @param index 旋转索引 (0-7)
         */
        public static String getRotationKeyName(int index) {
            return store.getRotationKey(index).getName();
        }
        
        /**
         * 获取画质快捷键名称
         * @param qualityIndex 画质索引 (0=4K, 1=超清, 2=高清, 3=标清)
         */
        public static String getQualityKeyName(int qualityIndex) {
            return store.getQualityKey(qualityIndex).getName();
        }
        
        /**
         * 获取设置快捷键名称
         */
        public static String getSettingsKeyName() {
            return store.getSettingsKey().getName();
        }
        
        /**
         * 获取慢放快捷键名称
         */
        public static String getSlowMotionKeyName() {
            return store.getSlowMotionKey().getName();
        }
        
        /**
         * 获取抓拍快捷键名称
         */
        public static String getCaptureKeyName() {
            return store.getCaptureKey().getName();
        }
        

        
        /**
         * 获取清空快捷键名称
         */
        public static String getClearKeyName() {
            return store.getClearKey().getName();
        }


        /**
         * 获取delete 最后一项快捷键名称
         */
        public static String getDeleteLastKeyName() {
            return store.getDeleteLastKey().getName();
        }

        // ⭐ 新增快捷键方法



        /**
         * 获取抓拍清空快捷键名称
         */
        public static String getCaptureClearKeyName() {
            return store.getCaptureClearKey().getName();
        }


        /**
         * 获取画质快捷键名称（根据画质类型字符串）
         * @param qualityType 画质类型 ("4k", "super", "high", "low")
         */
        public static String getQualityKeyNameByType(String qualityType) {
            int index = getQualityIndexByType(qualityType);
            return getQualityKeyName(index);
        }
        
        /**
         * 根据画质类型获取索引
         */
        private static int getQualityIndexByType(String qualityType) {
            if (qualityType == null) return 2; // 默认高清
            switch (qualityType.toLowerCase()) {
                case "4k": return 0;
                case "ultra": return 1;  // 修复：super -> ultra
                case "high": return 2;
                case "standard": return 3;  // 修复：low -> standard
                default: return 2; // 默认高清
            }
        }


        /**
         * 获取全屏快捷键名称
         */
        public static String getFullscreenKeyName() {
            return store.getFullscreenKey().getName();
        }

        /**
         * 获取实时窗口切换快捷键名称
         */
        public static String getRealtimeWindowKeyName() {
            return store.getRealtimeWindowKey().getName();
        }

        /**
         * 获取慢放窗口切换快捷键名称
         */
        public static String getSlowmoWindowKeyName() {
            return store.getSlowmoWindowKey().getName();
        }

        /**
         * 获取全屏查看/取消快捷键名称
         */
        public static String getFullscreenViewerKeyName() {
            return store.getFullscreenViewerKey().getName();
        }


        
        /**
         * 获取所有快捷键信息
         */
        public static String getAllShortcutKeys() {
            StringBuilder sb = new StringBuilder();
            sb.append("行增加调整: ").append(getRowAdjustKeyName()).append("\n");
            sb.append("行减少调整: ").append(getRowSubAdjustKeyName()).append("\n");
            sb.append("列增加调整: ").append(getColAdjustKeyName()).append("\n");
            sb.append("列减少调整: ").append(getRowSubAdjustKeyName()).append("\n");

            sb.append("镜头切换: ").append(getCameraSwitchKeyName()).append("\n");
            sb.append("设置: ").append(getSettingsKeyName()).append("\n");
            sb.append("清LastItem: ").append(getDeleteLastKeyName()).append("\n");
            // 添加新的快捷键
            sb.append("慢放: ").append(getSlowMotionKeyName()).append("\n");
            sb.append("抓拍: ").append(getCaptureKeyName()).append("\n");
            sb.append("清空: ").append(getClearKeyName()).append("\n");
            

            sb.append("抓拍清空: ").append(getCaptureClearKeyName()).append("\n");

            // 添加窗口控制快捷键
            sb.append("全屏: ").append(getFullscreenKeyName()).append("\n");
            sb.append("实时窗口切换: ").append(getRealtimeWindowKeyName()).append("\n");
            sb.append("慢放窗口切换: ").append(getSlowmoWindowKeyName()).append("\n");
            sb.append("全屏查看/取消: ").append(getFullscreenViewerKeyName()).append("\n");

            
            // 添加画质快捷键
            sb.append("画质快捷键:\n");
            sb.append("  4K: ").append(getQualityKeyName(0)).append("\n");
            sb.append("  超清: ").append(getQualityKeyName(1)).append("\n");
            sb.append("  高清: ").append(getQualityKeyName(2)).append("\n");
            sb.append("  标清: ").append(getQualityKeyName(3)).append("\n");
            
            for (int i = 0; i <= 7; i++) {
                sb.append("旋转").append(i).append(": ").append(getRotationKeyName(i)).append("\n");
            }
            
            return sb.toString();
        }
        
        /**
         * 打印所有快捷键信息到控制台
         */
        public static void printAllShortcutKeys() {
            System.out.println("=== 当前快捷键配置 ===");
            System.out.println("行增加调整: " + getRowAdjustKeyName());
            System.out.println("行减少调整: " + getRowSubAdjustKeyName());
            System.out.println("列增加调整: " + getColAdjustKeyName());
            System.out.println("列减少调整: " + getColSubAdjustKeyName());

            System.out.println("镜头切换: " + getCameraSwitchKeyName());
            System.out.println("设置: " + getSettingsKeyName());
            System.out.println("设置: " + getDeleteLastKeyName());
            // 添加新的快捷键
            System.out.println("慢放: " + getSlowMotionKeyName());
            System.out.println("抓拍: " + getCaptureKeyName());
            System.out.println("清空: " + getClearKeyName());
            

            System.out.println("抓拍清空: " + getCaptureClearKeyName());

            // 添加窗口控制快捷键
            System.out.println("全屏: " + getFullscreenKeyName());
            System.out.println("实时窗口切换: " + getRealtimeWindowKeyName());
            System.out.println("慢放窗口切换: " + getSlowmoWindowKeyName());
            System.out.println("全屏查看/取消: " + getFullscreenViewerKeyName());

            
            System.out.println("画质快捷键:");
            System.out.println("  4K: " + getQualityKeyName(0));
            System.out.println("  超清: " + getQualityKeyName(1));
            System.out.println("  高清: " + getQualityKeyName(2));
            System.out.println("  标清: " + getQualityKeyName(3));
            
            System.out.println("旋转快捷键:");
            for (int i = 0; i <= 7; i++) {
                System.out.println("  旋转" + i + ": " + getRotationKeyName(i));
            }
            System.out.println("==================");
        }
    }

    /**
     * 获取video-direction对应的文字描述
     * @param directionValue video-direction的值 (0-7)
     * @return 对应的文字描述
     */
    public static String getVideoDirectionText(int directionValue) {


        directionValue=directionValue+1;
        if(directionValue>7){
            directionValue=0;
        }


        switch (directionValue) {
            case 0: return "正常";          // GST_VIDEO_ORIENTATION_IDENTITY
            case 1: return "右转90°";       // GST_VIDEO_ORIENTATION_90R
            case 2: return "旋转180°";      // GST_VIDEO_ORIENTATION_180
            case 3: return "左转90°";       // GST_VIDEO_ORIENTATION_90L
            case 4: return "水平翻转";       // GST_VIDEO_ORIENTATION_HORIZ
            case 5: return "垂直翻转";       // GST_VIDEO_ORIENTATION_VERT
            case 6: return "左上右下";       // GST_VIDEO_ORIENTATION_UL_LR
            case 7: return "右上左下";       // GST_VIDEO_ORIENTATION_UR_LL
            default: return "正常";         // 默认返回正常
        }
    }

    /**
     * 获取当前旋转按钮应该显示的文字
     * 根据usederection+1的值，实现循环显示（7->0）
     * @return 按钮显示文字
     */
    public static String getCurrentRotationButtonText() {
        int displayValue = (usederection + 1) % 8; // 实现循环：7+1=8, 8%8=0
        return getVideoDirectionText(displayValue);
    }

    public static String getLatestSlowCaptureFile(String captureDir) {
        File dir = new File(captureDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        // 过滤新的s_前缀文件
        File[] files = dir.listFiles((file, name) ->
                name.startsWith("s_") && name.endsWith(".jpeg"));

        if (files == null || files.length == 0) {
            return null;
        }

        // 按文件名中的数字排序，获取最大的
        String latestFile = Arrays.stream(files)
                .map(File::getName)
                .filter(name -> name.matches("s_\\d{9}\\.jpeg"))
                .max((name1, name2) -> {
                    // 提取s_后面的5位数字
                    int num1 = Integer.parseInt(name1.substring(2, 11));
                    int num2 = Integer.parseInt(name2.substring(2, 11));
                    return Integer.compare(num1, num2);
                })
                .orElse(null);

        return latestFile;
    }

    public static String getEarliestSlowCaptureFile(String captureDir) {
        File dir = new File(captureDir);

        if (!dir.exists() || !dir.isDirectory()) {
            return null;
        }

        // 过滤新的s_前缀文件
        File[] files = dir.listFiles((file, name) ->
                name.startsWith("s_") && name.endsWith(".jpeg"));

        if (files == null || files.length == 0) {
            return null;
        }

        // 按文件名中的数字排序，获取最小的 ⭐
        String earliestFile = Arrays.stream(files)
                .map(File::getName)
                .filter(name -> name.matches("s_\\d{9}\\.jpeg"))
                .min((name1, name2) -> {  // ⭐ max 改成 min
                    // 提取s_后面的9位数字
                    int num1 = Integer.parseInt(name1.substring(2, 11));
                    int num2 = Integer.parseInt(name2.substring(2, 11));
                    return Integer.compare(num1, num2);
                })
                .orElse(null);

        return earliestFile;
    }

    /**
     * 从s_前缀的文件名中提取数字
     * @param fileName 文件名，例如 "s_00123.jpeg"
     * @return 提取的数字，例如 123，如果格式不匹配返回 -1
     */
    public static long extractFrameIdFromSlowFile(String fileName) {
        if (fileName == null || !fileName.startsWith("s_") || !fileName.endsWith(".jpeg")) {
            return -1;
        }

        try {
            // 提取s_后面的5位数字部分 s_000001252
            String numberPart = fileName.substring(2,11); // s_00123.jpeg -> 00123
            return Long.parseLong(numberPart);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 从慢动作JPEG文件中读取实际的宽度和高度
     * @param frameId 帧ID
     * @return 包含width和height的数组，[width, height]，失败返回null
     */
    public static int[] getSlowFileImageDimensions(long frameId,File sourceFile) {
       /* String slowDir = "runtime/captures/slow";
        String fileName = String.format("s_%05d.jpeg", frameId);
        String filePath = slowDir + "/" + fileName;
        File sourceFile = new File(filePath);*/

        if (!sourceFile.exists()) {
            return null;
        }

        try {
            // 方法1：使用TurboJPEG解码获取尺寸（推荐，性能更好）
            byte[] jpegData = java.nio.file.Files.readAllBytes(sourceFile.toPath());
            com.acard.acard.utils.TurboJpegEncoder.DecodedImage decoded =
                    com.acard.acard.utils.TurboJpegEncoder.decodeJPEGToRGB(jpegData);

            if (decoded != null) {
                return new int[]{decoded.width, decoded.height};
            }

            // 方法2：降级到ImageIO（兼容性更好）
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(sourceFile);
            if (img != null) {
                return new int[]{img.getWidth(), img.getHeight()};
            }

        } catch (Exception e) {
            //System.err.println("⚠️ 读取图像尺寸失败: " + filePath + ", " + e.getMessage());
        }

        return null;
    }

    /**
     * 创建DiskFrameItem时使用实际图像尺寸
     */
    public static com.acard.acard.capture.DiskCaptureCache.DiskFrameItem createSlowFrameItem(
            String filePath, long timestamp, long frameId) {

        // 从文件名提取frameId来获取实际尺寸
       // int[] dimensions = getSlowFileImageDimensions(frameId,"");

        int width = 1920;   // 默认值
        int height = 1080;  // 默认值

        /*if (dimensions != null) {
            width = dimensions[0];
            height = dimensions[1];
        } else {
            System.out.println("⚠️ 无法读取图像尺寸，使用默认值: " + filePath);
        }*/

        return new com.acard.acard.capture.DiskCaptureCache.DiskFrameItem(
                filePath,
                timestamp,
                width,      // 实际宽度
                height,     // 实际高度
                "jpeg",
                frameId
        );
    }


    //大小触发事件
    public static void updateSlowSize(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.FORCE_REFRESH,
                    "CameraMainController",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }

    public static void updateSpeed(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.SPEED_KEY,
                    "ShortcutSettingsDialog",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static void FbRecordingStartedEvent(String filename){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.RecordingStartedEvent,
                    "SimpleWebRTCPlayer",
                    new RecordingStartedEvent(filename)
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }

    public static void FbRecordingStartedEvent(){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.RecordingStartedEvent,
                    "SimpleWebRTCPlayer",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static void FbRecordingFileReadyEvent(RecordingFileReadyEvent data){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.RecordingFileReadyEvent,
                    "SimpleWebRTCPlayer",
                    data
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static void FbRecordingStoppedEvent(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.RecordingStoppedEvent,
                    "SimpleWebRTCPlayer",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static void FbCavasDataEvent(UIUpdateEvent.CavasData data){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.CavasDataEvent,
                    "FileTools",
                    data
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static void FbDeleteItemEvent(){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.DeleteItemEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }

    public static void FbSlowCleaEvent(){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.SlowCleanEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    //CleanAllEvent
    public static void FbCleanAllEvent(){

        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.CleanAllEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }


    public static String  GpuViewType ="GpuViewType";
    public static String  CameraType ="CameraType";
    public static void FbGpuViewCameraEvent(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.GpuViewCameraEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }



    public static void FbCameraSettingsDialog(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.CameraSettingsDialogEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }

    public static void FbRESOLUTION_CHANGED(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.RESOLUTION_CHANGED,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }

    //JiesuanCountEvent
    public static void FbJiesuanCountEvent(){
        try {
            UIUpdateEvent event = new UIUpdateEvent(
                    UIUpdateEvent.EventType.JiesuanCountEvent,
                    "FileTools",
                    null
            );
            UIUpdateEventManager.getInstance().fireEvent(event);
            System.out.println("🔄 发送慢放窗口切换UI更新事件");
        } catch (Exception e) {
            System.err.println("❌ 发送UI更新事件失败: " + e.getMessage());
        }
    }
}
