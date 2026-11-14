cmake_minimum_required(VERSION 3.22)
project(voxkey_bench C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Backend switch. CPU is the baseline; flip to ON (or pass
# -DVOXKEY_VULKAN=ON / edit app/build.gradle.kts arguments) to build ggml
# with the Vulkan backend. Nothing else has to change.
# ---------------------------------------------------------------------------
option(VOXKEY_VULKAN "Build ggml with the Vulkan backend" OFF)
set(GGML_VULKAN ${VOXKEY_VULKAN} CACHE BOOL "" FORCE)

# whisper.cpp is fetched by scripts/fetch-whisper.sh into third_party/.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "third_party/whisper.cpp missing - run scripts/fetch-whisper.sh")
endif()

# Keep the build small: library only, no examples/tests, no OpenMP.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

add_library(voxkey SHARED jni.cpp)

target_compile_definitions(voxkey PRIVATE
    $<$<BOOL:${VOXKEY_VULKAN}>:GGML_USE_VULKAN>
)

target_link_libraries(voxkey PRIVATE whisper parakeet)
