# Standalone build:
#   cmake -B build -S winshaper -DWINDIVERT_ROOT="C:/path/to/WinDivert-2.2.2-A"
#   cmake --build build --config Release
#
# WINDIVERT_ROOT must contain include/windivert.h and x64/WinDivert.lib (+ WinDivert.dll at runtime).

cmake_minimum_required(VERSION 3.16)
project(winshaper C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT DEFINED WINDIVERT_ROOT)
  set(WINDIVERT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/WinDivert" CACHE PATH "WinDivert SDK root (see third_party/README.txt)")
endif()

set(WINDIVERT_INC "${WINDIVERT_ROOT}/include")
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
  set(WINDIVERT_LIBDIR "${WINDIVERT_ROOT}/x64")
else()
  set(WINDIVERT_LIBDIR "${WINDIVERT_ROOT}/x86")
endif()

if(NOT EXISTS "${WINDIVERT_INC}/windivert.h")
  message(FATAL_ERROR "windivert.h not found under ${WINDIVERT_INC}. Set WINDIVERT_ROOT to your WinDivert SDK.")
endif()

add_executable(winshaper
  main.c
  winshaper_runner.c
  flowmap.c
  rules.c
  shape.c
  procutil.c
)

target_include_directories(winshaper PRIVATE "${WINDIVERT_INC}")
target_link_directories(winshaper PRIVATE "${WINDIVERT_LIBDIR}")
target_link_libraries(winshaper PRIVATE WinDivert ws2_32)

if(MSVC)
  target_compile_definitions(winshaper PRIVATE _CRT_SECURE_NO_WARNINGS)
endif()
