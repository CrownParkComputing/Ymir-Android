cmake_minimum_required(VERSION 3.22.1)
project(ymir_android_host LANGUAGES C CXX)

set(YMIR_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../..")

set(Ymir_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(Ymir_ENABLE_IPO OFF CACHE BOOL "" FORCE)
set(Ymir_ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(Ymir_ENABLE_SANDBOX OFF CACHE BOOL "" FORCE)
set(Ymir_ENABLE_YMDASM OFF CACHE BOOL "" FORCE)
set(Ymir_ENABLE_YMIR_HEADLESS OFF CACHE BOOL "" FORCE)
set(Ymir_ENABLE_YMIR_DBG OFF CACHE BOOL "" FORCE)

add_subdirectory("${YMIR_ROOT}" "${CMAKE_BINARY_DIR}/ymir-core-build")

add_library(ymir_android SHARED
    ymir_android.cpp
)

target_compile_features(ymir_android PRIVATE cxx_std_20)
target_link_libraries(ymir_android
    PRIVATE
        ymir::ymir-core
        EGL
        GLESv3
        vulkan
        aaudio
        android
        log
)
