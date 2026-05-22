module com.acard.acard {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    // GStreamer Java 绑定
    requires org.freedesktop.gstreamer;
    requires com.sun.jna.platform;
    requires com.sun.jna;

    requires java.desktop;
    requires javafx.swing;
    requires java.net.http;
    requires java.management;
    requires jdk.management;
    requires okhttp3.logging;
    requires com.google.gson;
    requires org.java_websocket;     // ✅ 使用 SwingFXUtils 需要
    requires kotlin.stdlib;           // ✅ OkHttp 4.x 依赖 Kotlin 运行时
    // Chronicle Queue 运行在 classpath，无需 JPMS 模块声明
    // Zstd 无损压缩 JNI 模块
    requires com.github.luben.zstd_jni;
    // 已移除 LMDB 依赖

    // 📱 二维码生成库
    requires com.google.zxing;
    requires com.google.zxing.javase;
    requires java.prefs;
    requires static webp.imageio;


    opens com.acard.acard to javafx.fxml, com.sun.jna;
    opens com.acard.acard.controller to javafx.fxml;
    opens com.acard.acard.model to javafx.fxml, com.google.gson;
    opens com.acard.acard.viewmodel to javafx.fxml;
    opens com.acard.acard.ui to javafx.fxml;
    // 允许 Gson 反射访问以进行 JSON 反序列化
    opens com.acard.acard.net to com.google.gson;
    
    exports com.acard.acard;
    exports com.acard.acard.controller;
    exports com.acard.acard.model;
    exports com.acard.acard.viewmodel;
    exports com.acard.acard.ui;
    exports com.acard.acard.slowmotion;
}