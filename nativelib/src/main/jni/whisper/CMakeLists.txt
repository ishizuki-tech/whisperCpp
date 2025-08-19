# ============================================================
# Whisper.cpp Android JNI Build - CMakeLists.txt
# ============================================================

# ============================================================
# Project Structure (overview)
#
# whisper_core/
# ├─ src/
# │   └─ whisper.cpp       # Main whisper implementation
# ├─ include/              # Public headers
# └─ ggml/                 # GGML core (math / tensor backend)
#
# JNI Layer:
# └─ jni.c                 # JNI entry point for Android
#
# Build Targets:
# ├─ whisper_v8fp16_va.so  # For ARM64 + FP16 optimized
# ├─ whisper_vfpv4.so      # For ARMv7 + VFPv4 optimized
# └─ whisper.so            # Generic fallback target
# ============================================================

# ---- CMake requirements and project setup ----
cmake_minimum_required(VERSION 3.10)
project(whisper.cpp)

# Use C++17
set(CMAKE_CXX_STANDARD 17)

# Path to whisper.cpp core library
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../whisper_core)

# ---- Detect whisper.cpp version ----
# Extract version from whisper_core/CMakeLists.txt
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")

if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1} PARENT_SCOPE)
else()
    set(WHISPER_VERSION "unknown" PARENT_SCOPE)
endif()

message(STATUS " Whisper version: ${WHISPER_VERSION}")

# ---- Options ----
# Use external GGML if provided; otherwise fall back to local copy
option(GGML_HOME "Path to external GGML source" OFF)

# ---- Source files ----
set(SOURCE_FILES
        ${WHISPER_LIB_DIR}/src/whisper.cpp
        ${CMAKE_SOURCE_DIR}/WhisperLib.c
)

# ---- Android system libraries ----
find_library(LOG_LIB log)

# ---- External dependency management ----
include(FetchContent)

# ============================================================
# Function: build_library
# Builds a whisper shared library for the current ABI target
# ============================================================
function(build_library target_name)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    # Common compile definitions
    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    # ABI-specific optimizations
    if (${target_name} STREQUAL "whisper_v8fp16_va")
        target_compile_options(${target_name} PRIVATE -march=armv8.2-a+fp16)
        set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
    elseif (${target_name} STREQUAL "whisper_vfpv4")
        target_compile_options(${target_name} PRIVATE -mfpu=neon-vfpv4)
        set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
    endif ()

    # Release build optimizations
    if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3)
        target_compile_options(${target_name} PRIVATE
                -fvisibility=hidden
                -fvisibility-inlines-hidden
                -ffunction-sections
                -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
                -Wl,--gc-sections
                -Wl,--exclude-libs,ALL
                -flto
        )
    endif ()

    # ---- GGML setup ----
    if (GGML_HOME)
        # External GGML source
        FetchContent_Declare(ggml SOURCE_DIR ${GGML_HOME})
    else()
        # Local GGML source inside whisper.cpp
        FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
    endif()

    FetchContent_MakeAvailable(ggml)

    # Apply ABI-specific compile flags to GGML as well
    target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})

    # Link libraries
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)
endfunction()

# ============================================================
# Build per ABI
# ============================================================
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va")  # ARM64 + FP16
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4")      # ARMv7 + VFPv4
endif ()

# Default target (generic build)
build_library("whisper")

# ============================================================
# Include directories
# ============================================================
include_directories(
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)
