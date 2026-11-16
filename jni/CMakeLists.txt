cmake_minimum_required(VERSION 3.16)

project(vlc-kmp-jni CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The JNI bridge does not link libVLC: it resolves the shared library at
# runtime (and fails with a descriptive UnsatisfiedLinkError if it is absent).
# Headers come from the pinned libVLC 3.0.24 submodule.
if(NOT DEFINED VLC_SOURCE_DIR)
    set(VLC_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../vlc")
endif()

add_library(vlc_jni SHARED vlc_jni.cpp)
set_target_properties(vlc_jni PROPERTIES OUTPUT_NAME vlc_jni)
target_include_directories(vlc_jni PRIVATE
    ${VLC_SOURCE_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if(ANDROID_JNI)
    # The NDK sysroot ships jni.h on the default include path.
elseif(JNI_INCLUDE_DIR)
    target_include_directories(vlc_jni PRIVATE ${JNI_INCLUDE_DIR})
else()
    find_package(JNI REQUIRED)
    target_include_directories(vlc_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if(JNI_INCLUDE_DIR_PLATFORM)
    target_include_directories(vlc_jni PRIVATE ${JNI_INCLUDE_DIR_PLATFORM})
endif()

if(UNIX AND NOT APPLE AND NOT ANDROID)
    target_link_libraries(vlc_jni PRIVATE ${CMAKE_DL_LIBS})
elseif(ANDROID)
    target_link_libraries(vlc_jni PRIVATE dl)
    # Android 15+ devices can use 16 KiB pages; ELF segments must be aligned
    # accordingly or the loader rejects the library.
    target_link_options(vlc_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
        "-s"
    )
endif()

if(WIN32)
    set_target_properties(vlc_jni PROPERTIES PREFIX "")
endif()
