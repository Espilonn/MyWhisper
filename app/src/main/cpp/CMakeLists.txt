cmake_minimum_required(VERSION 3.18)
project(voxkey C CXX)

set(CMAKE_CXX_STANDARD 17)

# Vulkan: passer à ON après avoir mesuré la référence CPU.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS   OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS      OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL)

add_library(voxkey SHARED whisper_jni.cpp)
target_link_libraries(voxkey whisper log android)
