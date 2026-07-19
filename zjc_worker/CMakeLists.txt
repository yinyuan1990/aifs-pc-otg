# ============================================================================
# zjc_worker —— 独立子进程守护程序（流量整形 / 带宽突发）
#
# 从 Phoenix 主工程分离出来单独编译（用户要求：zjc_worker 不放主进程目录，
# 由服务器下发按需下载安装）。纯 Win32 + WinDivert，零 Qt 依赖。
#
# 构建（在本目录）：
#   cmake -S . -B build -G "Ninja" -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
# 产物（build/ 下，打包上传服务器 {uploadPath}/zjc/）：
#   zjc_worker.exe  WinDivert.dll  WinDivert64.sys
# 发布后在总后台把 system_config 的 zjc.worker.version 改成本工程 ZJC_WORKER_VERSION，
# Phoenix 端会据此判断需要重新下载安装。
#
# 源文件仍复用主工程目录里的 ../zjc_worker.cpp + ../winshaper/*（不物理移动，
# 避免破坏 winshaper 相对引用）；本工程只是独立的构建入口。
# ============================================================================
cmake_minimum_required(VERSION 3.16)

project(zjc_worker VERSION 1.0.0 LANGUAGES C CXX RC)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT WIN32)
    message(FATAL_ERROR "zjc_worker 仅支持 Windows")
endif()

# 主工程根目录（本工程是它的子目录）
set(AIFS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")

# ⭐ 版本资源(.rc)：让 UAC 提权弹窗显示程序名「金凤凰」（否则显示文件名 zjc_worker）
add_executable(zjc_worker WIN32 "${AIFS_ROOT}/zjc_worker.cpp" "${CMAKE_CURRENT_SOURCE_DIR}/zjc_worker.rc")

# WinDivert SDK（存在则把 winshaper 静态链进 exe，单文件即可整形）
set(ZJC_WINDIVERT_DIR "${AIFS_ROOT}/winshaper/third_party/WinDivert")
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
        "${AIFS_ROOT}/winshaper/winshaper_runner.c"
        "${AIFS_ROOT}/winshaper/flowmap.c"
        "${AIFS_ROOT}/winshaper/rules.c"
        "${AIFS_ROOT}/winshaper/shape.c"
        "${AIFS_ROOT}/winshaper/procutil.c"
    )
    set_source_files_properties(
        "${AIFS_ROOT}/winshaper/winshaper_runner.c"
        "${AIFS_ROOT}/winshaper/flowmap.c"
        "${AIFS_ROOT}/winshaper/rules.c"
        "${AIFS_ROOT}/winshaper/shape.c"
        "${AIFS_ROOT}/winshaper/procutil.c"
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

target_link_libraries(zjc_worker PRIVATE ws2_32 winhttp bcrypt iphlpapi)

# 复制 WinDivert 运行时到输出目录（打包上传时和 exe 一起放服务器）
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
