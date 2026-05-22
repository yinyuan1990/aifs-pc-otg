package com.acard.acard;

import com.sun.jna.NativeLibrary;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Version;

import java.nio.file.*;
import java.util.Map;

public final class GstBootstrap {
    private GstBootstrap() {}

    public static void init(boolean useSystemInstall) {
        // 1) 选择使用系统安装目录，还是项目 runtime 目录
        Path gstHome = useSystemInstall
                ? Paths.get("C:", "Program Files", "gstreamer", "1.0", "msvc_x86_64")
                : Paths.get("runtime","gstreamer","win64").toAbsolutePath();

        Path bin      = gstHome.resolve("bin");
        Path lib      = gstHome.resolve("lib");
        Path plugins  = lib.resolve("gstreamer-1.0");
        Path libexec  = gstHome.resolve("libexec").resolve("gstreamer-1.0");
        Path scanner  = libexec.resolve("gst-plugin-scanner.exe");
        Path registry = Paths.get(System.getProperty("user.home"), ".gst-registry-1.0.bin");

        // 2) 把 bin/lib 追加到【当前进程】PATH（GStreamer/依赖DLL能被找到）
        addToEnvPath(bin.toString());
        addToEnvPath(lib.toString());

        // 3) 告诉 GStreamer 插件和扫描器在哪里（必须在 Gst.init 之前）
        setEnv("GST_PLUGIN_PATH", plugins.toString());
        setEnv("GST_PLUGIN_SYSTEM_PATH", plugins.toString());
        setEnv("GST_PLUGIN_SCANNER", scanner.toString());
        setEnv("GST_REGISTRY", registry.toString());

        // 4) 告诉 JNA 去哪里找“gstreamer”原生库
        String sep = System.getProperty("path.separator", ";");
        System.setProperty("jna.library.path", bin.toString() + sep + lib.toString());
        // 再显式追加搜索路径（双保险）
        try {
            NativeLibrary.addSearchPath("gstreamer", bin.toString());
            NativeLibrary.addSearchPath("gstreamer-1.0-0", bin.toString());
        } catch (Throwable ignore) {}

        // 5) 打点输出（定位常见问题）
        System.out.println("GST HOME    = " + gstHome);
        System.out.println("bin exists  = " + Files.exists(bin) + "  -> " + bin);
        System.out.println("lib exists  = " + Files.exists(lib) + "  -> " + lib);
        System.out.println("plugins ok  = " + Files.exists(plugins) + " -> " + plugins);
        System.out.println("scanner ok  = " + Files.exists(scanner) + " -> " + scanner);
        System.out.println("dll present = " + Files.exists(bin.resolve("gstreamer-1.0-0.dll")));
        System.out.println("PATH head   = " + System.getenv("PATH"));

        // 6) 初始化 GStreamer（这一步才去加载 gstreamer-1.0-0.dll）
        Gst.init(Version.of(1, 14), "acard-desktop", new String[]{});
        System.out.println("✅ GStreamer initialized");
    }

    // 追加到当前进程 PATH（只影响本进程，安全）
    private static void addToEnvPath(String dir) {
        String sep = System.getProperty("path.separator", ";");
        try {
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            var theEnv = pe.getDeclaredField("theEnvironment");
            theEnv.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String,String> env = (Map<String,String>) theEnv.get(null);
            env.put("PATH", dir + sep + env.getOrDefault("PATH",""));

            var theCIEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment");
            theCIEnv.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String,String> cienv = (Map<String,String>) theCIEnv.get(null);
            cienv.put("PATH", dir + sep + cienv.getOrDefault("PATH",""));
        } catch (Throwable ignored) {}
    }
    private static void setEnv(String k, String v) {
        try {
            Class<?> pe = Class.forName("java.lang.ProcessEnvironment");
            var theEnv = pe.getDeclaredField("theEnvironment");
            theEnv.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String,String> env = (Map<String,String>) theEnv.get(null);
            env.put(k, v);
            var theCIEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment");
            theCIEnv.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String,String> cienv = (Map<String,String>) theCIEnv.get(null);
            cienv.put(k, v);
        } catch (Throwable ignored) {}
    }
}
