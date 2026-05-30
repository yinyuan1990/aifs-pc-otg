cmake_minimum_required(VERSION 3.16)

project(Phoenix VERSION 0.1 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

# ⭐ 大对象格式 — MainPage.qml 经 qmlcache 编译后单 .obj sections 数突破 PE-COFF 上限
#   MinGW (GAS) 用 -Wa,-mbig-obj, MSVC 用 /bigobj
#   报错形式: "too many sections (49470)" / "file too big"
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" OR CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    add_compile_options(-Wa,-mbig-obj)
elseif(CMAKE_CXX_COMPILER_ID STREQUAL "MSVC")
    add_compile_options(/bigobj)
    add_compile_options(/utf-8)   # 统一源文件和执行字符集为 UTF-8，避免中文字符串乱码
endif()

# FFmpeg 路径
set(FFMPEG_ROOT "C:/ffmpeg")
set(FFMPEG_INCLUDE_DIR "${FFMPEG_ROOT}/include")
set(FFMPEG_LIB_DIR "${FFMPEG_ROOT}/lib")
set(FFMPEG_BIN_DIR "${FFMPEG_ROOT}/bin")

# vcpkg 安装路径（仅用于 turbojpeg，不使用 vcpkg 的 GStreamer）
set(VCPKG_ROOT "D:/javafx/Acard/aic/vcpkg/installed/x64-windows")
set(VCPKG_INCLUDE_DIR "${VCPKG_ROOT}/include")
set(VCPKG_LIB_DIR "${VCPKG_ROOT}/lib")
set(VCPKG_BIN_DIR "${VCPKG_ROOT}/bin")

# GStreamer 路径
set(GSTREAMER_ROOT "C:/PROGRA~1/gstreamer/1.0/msvc_x86_64")
set(GSTREAMER_LIB_DIR "${GSTREAMER_ROOT}/lib")
set(GSTREAMER_BIN_DIR "${GSTREAMER_ROOT}/bin")

# 查找 Qt 模块
find_package(Qt6 REQUIRED COMPONENTS Quick Widgets Network QuickControls2 Concurrent Multimedia ShaderTools WebSockets)
find_package(Qt6 COMPONENTS Qt5Compat QUIET)  # 可选：用于 GraphicalEffects

qt_standard_project_setup(REQUIRES 6.8)

# 源文件
qt_add_executable(Phoenix
    main.cpp
    videoplayer.cpp
    videoplayer.h
    # webrtcclient.cpp  # ⭐ 废弃，改用 GstPlayer WebRTCBin
    # webrtcclient.h
    capturemanager.cpp
    capturemanager.h
    slowmotionplayer.cpp
    slowmotionplayer.h
    imageprovider.cpp
    imageprovider.h
    eventbus.cpp
    eventbus.h
    gpupipeline.cpp      # 颜色调整（亮度/对比度/饱和度/伽马）
    gpupipeline.h
    naluframestore.cpp   # H.264 NALU 环形缓冲（替代 JPEG 文件）
    naluframestore.h
    naludecoder.cpp      # H.264 NALU → QImage 解码器（FFmpeg，备用）
    naludecoder.h
    gstcapturedecoder.cpp  # H.264 NALU → QImage（GStreamer 管线，同直播 d3d11h264dec）
    gstcapturedecoder.h
    capturedebuglog.cpp
    capturedebuglog.h
    gstplayer.cpp        # GStreamer 实时流 + NALU 存储
    gstplayer.h
    httpclient.cpp
    httpclient.h
    websocketclient.cpp
    websocketclient.h
    shortcutstore.cpp
    shortcutstore.h
    qrcodegenerator.cpp
    qrcodegenerator.h
    qrcodegen.cpp
    qrcodegen.hpp
    autoupdater.cpp
    autoupdater.h
    app.rc  # Windows 应用图标
)

# Include 路径（注意顺序：GStreamer 在前，vcpkg 在后，避免 glib 冲突）
target_include_directories(Phoenix PRIVATE 
    ${FFMPEG_INCLUDE_DIR}
    # GStreamer（系统安装，优先级高于 vcpkg）
    "${GSTREAMER_ROOT}/include/gstreamer-1.0"
    "${GSTREAMER_ROOT}/include/glib-2.0"
    "${GSTREAMER_ROOT}/lib/glib-2.0/include"
    # vcpkg（仅用于 turbojpeg.h）
    ${VCPKG_INCLUDE_DIR}
)

# ⭐ GStreamer WebRTC API 需要此宏定义
target_compile_definitions(Phoenix PRIVATE GST_USE_UNSTABLE_API)

# 注意：MSVC 不支持 #warning 指令，需要手动修补 GStreamer 头文件
# 已通过 PowerShell 管理员命令修补：
# (Get-Content 'C:\Program Files\gstreamer\1.0\msvc_x86_64\include\gstreamer-1.0\gst\webrtc\webrtc_fwd.h') -replace '#warning', '// #warning' | Set-Content '...'

# Library 路径（GStreamer 在前，避免链接 vcpkg 版本）
target_link_directories(Phoenix PRIVATE 
    ${GSTREAMER_LIB_DIR}
    ${FFMPEG_LIB_DIR}
    ${VCPKG_LIB_DIR}  # 仅用于 turbojpeg
)

# 链接库
target_link_libraries(Phoenix PRIVATE
    Qt6::Quick
    Qt6::Widgets
    Qt6::Network
    Qt6::QuickControls2
    Qt6::Concurrent
    Qt6::Multimedia
    Qt6::WebSockets
    # FFmpeg
    avformat
    avcodec
    avutil
    swscale
    # libdatachannel（已废弃，改用 GStreamer WebRTCBin）
    # datachannel
    # libjpeg-turbo (软件 JPEG 编码/解码，备用)
    turbojpeg
    # GStreamer（核心 + WebRTC）
    gstreamer-1.0
    gstapp-1.0
    gstvideo-1.0
    gstsdp-1.0         # ⭐ SDP 解析（WebRTC 需要）
    gstwebrtc-1.0      # ⭐ WebRTCBin
    gobject-2.0
    glib-2.0
    # Windows 网络库
    ws2_32
    iphlpapi
    # D3D11 硬件加速
    d3d11
    dxgi
    # Shell API（ShellExecuteW 用于更新脚本启动）
    Shell32
)

qt_add_qml_module(Phoenix
    URI Aifs
    QML_FILES
        Main.qml
        LoginPage.qml
        MainPage.qml
    RESOURCES
        images/yql.png
        images/zwtp.png
        images/avatar.png
        images/icon.png
        images/fh.png
)

# MinGW: QML AOT 原生代码会调用 copysign，链接时需 libm；--only-bytecode 避免生成该原生代码
if(MINGW)
    target_link_libraries(Phoenix PRIVATE m)
    set_target_properties(Phoenix PROPERTIES
        QT_QMLCACHEGEN_ARGUMENTS "--only-bytecode"
    )
endif()

# 编译 Shader（GPU 颜色调整）
qt6_add_shaders(Phoenix "shaders"
    PREFIX "/shaders"
    FILES
        shaders/coloradjust.frag
)

set_target_properties(Phoenix PROPERTIES
    MACOSX_BUNDLE_BUNDLE_VERSION ${PROJECT_VERSION}
    MACOSX_BUNDLE_SHORT_VERSION_STRING ${PROJECT_VERSION_MAJOR}.${PROJECT_VERSION_MINOR}
    MACOSX_BUNDLE TRUE
    WIN32_EXECUTABLE TRUE
)

# 复制 DLL 到输出目录
add_custom_command(TARGET Phoenix POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${FFMPEG_BIN_DIR}/avformat-62.dll"
        "${FFMPEG_BIN_DIR}/avcodec-62.dll"
        "${FFMPEG_BIN_DIR}/avutil-60.dll"
        "${FFMPEG_BIN_DIR}/swscale-9.dll"
        "${FFMPEG_BIN_DIR}/swresample-6.dll"
        # libdatachannel（已废弃，改用 GStreamer WebRTCBin）
        # "${VCPKG_BIN_DIR}/datachannel.dll"
        # "${VCPKG_BIN_DIR}/juice.dll"
        # "${VCPKG_BIN_DIR}/libcrypto-3-x64.dll"
        # "${VCPKG_BIN_DIR}/libssl-3-x64.dll"
        # "${VCPKG_BIN_DIR}/srtp2.dll"
        "${VCPKG_BIN_DIR}/turbojpeg.dll"
        # GStreamer 核心 DLL
        "${GSTREAMER_ROOT}/bin/gstreamer-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstapp-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstvideo-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstbase-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstsdp-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstrtp-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gobject-2.0-0.dll"
        "${GSTREAMER_ROOT}/bin/glib-2.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gmodule-2.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gio-2.0-0.dll"
        "${GSTREAMER_ROOT}/bin/intl-8.dll"
        "${GSTREAMER_ROOT}/bin/ffi-7.dll"
        "${GSTREAMER_ROOT}/bin/pcre2-8-0.dll"
        # ⭐ WebRTC 相关 DLL
        "${GSTREAMER_ROOT}/bin/gstwebrtc-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstwebrtcnice-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/nice-10.dll"
        "${GSTREAMER_ROOT}/bin/libcrypto-3-x64.dll"
        "${GSTREAMER_ROOT}/bin/libssl-3-x64.dll"
        # 额外依赖 DLL
        "${GSTREAMER_ROOT}/bin/orc-0.4-0.dll"
        "${GSTREAMER_ROOT}/bin/gstpbutils-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstaudio-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gsttag-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstnet-1.0-0.dll"
        "${GSTREAMER_ROOT}/bin/gstsctp-1.0-0.dll"
        $<TARGET_FILE_DIR:Phoenix>
)

# ⭐ 子进程守护程序（主程序关闭时运行，打开时终止）
add_executable(zjc_worker WIN32 zjc_worker.cpp)

# 若存在 WinDivert SDK，则把 winshaper 链进 zjc_worker，打包只需一个 exe（子进程用 --zjc-shaper 自启）
set(ZJC_WINDIVERT_DIR "${CMAKE_SOURCE_DIR}/winshaper/third_party/WinDivert")
if(EXISTS "${ZJC_WINDIVERT_DIR}/include/windivert.h")
    option(ZJC_EMBED_WINSHAPER "Embed WinDivert shaper into zjc_worker.exe" ON)
else()
    option(ZJC_EMBED_WINSHAPER "Embed WinDivert shaper into zjc_worker.exe" OFF)
endif()

if(ZJC_EMBED_WINSHAPER)
    if(NOT EXISTS "${ZJC_WINDIVERT_DIR}/include/windivert.h")
        message(FATAL_ERROR "ZJC_EMBED_WINSHAPER=ON but missing ${ZJC_WINDIVERT_DIR}/include/windivert.h — see winshaper/third_party/README.txt")
    endif()
    target_sources(zjc_worker PRIVATE
        winshaper/winshaper_runner.c
        winshaper/flowmap.c
        winshaper/rules.c
        winshaper/shape.c
        winshaper/procutil.c
    )
    set_source_files_properties(
        winshaper/winshaper_runner.c
        winshaper/flowmap.c
        winshaper/rules.c
        winshaper/shape.c
        winshaper/procutil.c
        PROPERTIES LANGUAGE C
    )
    target_include_directories(zjc_worker PRIVATE "${ZJC_WINDIVERT_DIR}/include")
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        target_link_directories(zjc_worker PRIVATE "${ZJC_WINDIVERT_DIR}/x64")
    else()
        target_link_directories(zjc_worker PRIVATE "${ZJC_WINDIVERT_DIR}/x86")
    endif()
    target_link_libraries(zjc_worker PRIVATE WinDivert Shell32)
    target_compile_definitions(zjc_worker PRIVATE ZJC_EMBED_WINSHAPER=1)
else()
    target_compile_definitions(zjc_worker PRIVATE ZJC_EMBED_WINSHAPER=0)
endif()

# 链接 Winsock2 + WinHTTP + BCrypt(SHA256) + IpHlpApi(MAC地址)
target_link_libraries(zjc_worker PRIVATE ws2_32 winhttp bcrypt iphlpapi)
# 禁用 AUTOMOC（不依赖 Qt）
set_target_properties(zjc_worker PROPERTIES AUTOMOC OFF)
# 确保 zjc_worker.exe 输出到与 Phoenix.exe 同一目录
set_target_properties(zjc_worker PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}
    RUNTIME_OUTPUT_DIRECTORY_DEBUG ${CMAKE_BINARY_DIR}
    RUNTIME_OUTPUT_DIRECTORY_RELEASE ${CMAKE_BINARY_DIR}
)

# 复制 zjc_worker.exe 到 Phoenix.exe 所在目录（多配置构建器如 MSVC）
add_custom_command(TARGET zjc_worker POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:zjc_worker>
        $<TARGET_FILE_DIR:Phoenix>
)

# 复制 WinDivert 运行时文件到构建输出目录
if(ZJC_EMBED_WINSHAPER)
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        set(_WDRT "${ZJC_WINDIVERT_DIR}/x64")
    else()
        set(_WDRT "${ZJC_WINDIVERT_DIR}/x86")
    endif()
    add_custom_command(TARGET zjc_worker POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${_WDRT}/WinDivert.dll"   $<TARGET_FILE_DIR:zjc_worker>
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${_WDRT}/WinDivert64.sys" $<TARGET_FILE_DIR:zjc_worker>
    )
endif()

include(GNUInstallDirs)
install(TARGETS Phoenix zjc_worker
    BUNDLE DESTINATION .
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)
