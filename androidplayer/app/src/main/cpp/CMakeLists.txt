cmake_minimum_required(VERSION 3.22.1)
project("androidplayer")

# 输出构建类型和ABI，方便调试
message(STATUS "Building for ABI: ${ANDROID_ABI}")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")

# FFmpeg 头文件目录
set(ffmpeg_head_dir ${CMAKE_SOURCE_DIR}/include)
message(STATUS "FFmpeg include directory: ${ffmpeg_head_dir}")
include_directories(${ffmpeg_head_dir})

# FFmpeg 预编译库目录
set(ffmpeg_lib_dir "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
message(STATUS "FFmpeg library directory: ${ffmpeg_lib_dir}")

# 添加 FFmpeg 库 (作为 IMPORTED 库)
add_library(ffmpeg SHARED IMPORTED)
set_target_properties(ffmpeg PROPERTIES IMPORTED_LOCATION "${ffmpeg_lib_dir}/libffmpeg.so")

# 查找 NDK 提供的标准库
find_library(log-lib             # 变量名
        log)                # 要查找的库名 (不带lib前缀和.so后缀)
find_library(android-lib
        android)
find_library(nativewindow-lib
        nativewindow)
find_library(aaudio-lib          # 为 aaudio 使用 find_library
        aaudio)
find_library(opensles-lib
        OpenSLES)
# find_library(atomic-lib atomic) # atomic 通常作为 -latomic 直接链接，但也可以find
# find_library(m-lib m)           # m (math) 通常作为 -lm 直接链接

# 定义你的项目库
add_library(${CMAKE_PROJECT_NAME} SHARED
        AAudioRender.cpp
        ANWRender.cpp
        native-lib.cpp
)

# 链接库到你的项目
target_link_libraries(${CMAKE_PROJECT_NAME}
        # 依赖的第三方库
        ffmpeg                  # 链接 FFmpeg

        # NDK 标准库 (使用 find_library 找到的变量)
        ${log-lib}
        ${android-lib}
        ${nativewindow-lib}
        ${aaudio-lib}           # <--- 使用找到的 aaudio 库变量
        ${opensles-lib}         # <--- 新增：链接 OpenSLES 库

        # 其他标准库，可以直接写名字 (CMake 会自动处理 -l 前缀)
        # 或者像上面那样用 find_library (更推荐)
        atomic                  # 对应 -latomic
        m                       # 对应 -lm
)

# 调试信息：可以查看 NDK 包含路径，但通常不需要手动设置
# message(STATUS "ANDROID_NDK_INCLUDE_DIRS = ${ANDROID_NDK_INCLUDE_DIRS}")