package com.acard.acard;

import org.freedesktop.gstreamer.*;

public final class GstDiag {
    private GstDiag() {}

    /**
     * 检查几个关键元素是否可用
     */
    public static boolean checkElements() {
        try {
            Version v = Gst.getVersion();
            System.out.println("✅ GStreamer ready, version = " +
                    v.getMajor() + "." + v.getMinor() + "." + v.getMicro());
        } catch (Throwable t) {
            System.err.println("❌ GStreamer 尚未初始化: " + t);
            return false;
        }

        boolean ok = true;
        ok &= check("videotestsrc");
        ok &= check("videoconvert");
        ok &= check("appsink");
        ok &= check("webrtcbin");

        System.out.println("SELF CHECK = " + ok);
        return ok;
    }

    /**
     * 单个元素检查：能 create 就算成功
     */
    private static boolean check(String name) {
        try {
            Element e = ElementFactory.make(name, "chk-" + name);
            if (e == null) {
                System.err.println("❌ 无法创建元素: " + name);
                return false;
            } else {
                System.out.println("✅ 元素可用: " + name);
                e.dispose();
                return true;
            }
        } catch (Throwable t) {
            System.err.println("❌ 元素异常: " + name + " → " + t);
            return false;
        }
    }
}
