# CMakeLists.txt
# 自适应帧率控制模块
# 
# 使用方法:
#   mkdir build && cd build
#   cmake ..
#   cmake --build .

cmake_minimum_required(VERSION 3.10)
project(adaptive_fps VERSION 2.0 LANGUAGES CXX)

# C++17 标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 源文件
set(ADAPTIVE_FPS_SOURCES
    network_metrics.cpp
    network_trend_predictor.cpp
    adaptive_fps_decider.cpp
    adaptive_fps_controller.cpp
)

# 头文件
set(ADAPTIVE_FPS_HEADERS
    adaptive_fps_types.h
    network_metrics.h
    network_trend_predictor.h
    adaptive_fps_decider.h
    adaptive_fps_controller.h
)

# 创建静态库
add_library(adaptive_fps STATIC ${ADAPTIVE_FPS_SOURCES} ${ADAPTIVE_FPS_HEADERS})

# 包含目录
target_include_directories(adaptive_fps PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

# 链接线程库
find_package(Threads REQUIRED)
target_link_libraries(adaptive_fps PUBLIC Threads::Threads)

# 示例程序
add_executable(adaptive_fps_example example_usage.cpp)
target_link_libraries(adaptive_fps_example PRIVATE adaptive_fps)

# 安装
install(TARGETS adaptive_fps
    ARCHIVE DESTINATION lib
    LIBRARY DESTINATION lib
)

install(FILES ${ADAPTIVE_FPS_HEADERS}
    DESTINATION include/adaptive_fps
)
