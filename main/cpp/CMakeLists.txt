#CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project("androidplayer")

message(STATUS "CMAKE_BUILD_TYPE = ${CMAKE_BUILD_TYPE}")

set(ffmpeg_head_dir ${CMAKE_SOURCE_DIR}/include)

set(ffmpeg_lib_dir "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
message(STATUS "ffmpeg_lib_dir = ${ffmpeg_lib_dir}")
message(STATUS "ffmpeg_head_dir = ${ffmpeg_head_dir}")

include_directories(${ffmpeg_head_dir})

find_library(log-lib log)

add_library(ffmpeg SHARED IMPORTED)
set_target_properties(ffmpeg PROPERTIES IMPORTED_LOCATION "${ffmpeg_lib_dir}/libffmpeg.so")

add_library(${CMAKE_PROJECT_NAME} SHARED
        AAudioRender.cpp
        ANWRender.cpp
        native-lib.cpp # 包含stringFromJNI方法的源文件
)

find_library(android_lib android)
find_library(nativewindow_lib nativewindow)

target_link_libraries(${CMAKE_PROJECT_NAME}
        ffmpeg  # 确保 androidplayer 依赖于 ffmpeg
        aaudio
        ${log-lib}
        ${android_lib}
        ${nativewindow_lib} # 链接 nativewindow 库
)
message(STATUS "ANDROID_NDK_INCLUDE_DIRS = ${ANDROID_NDK_INCLUDE_DIRS}")