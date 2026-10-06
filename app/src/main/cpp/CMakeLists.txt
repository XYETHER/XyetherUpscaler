cmake_minimum_required(VERSION 3.22.1)
project(xyether_backend CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Qualcomm's official Android SuperResolution sample links this matched
# AppBuilder bridge, which owns QNN HTP context creation inside the app process.
set(QAI_APPBUILDER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/External/QAIAppBuilder)
add_library(qaiappbuilder SHARED IMPORTED)
set_target_properties(qaiappbuilder PROPERTIES
    IMPORTED_LOCATION ${QAI_APPBUILDER_DIR}/libappbuilder.so)

# Portable non-Qualcomm fallback. MNN is built in-process and runs OpenCL when
# available, otherwise its ARM CPU backend. It is never used as a QNN substitute.
set(MNN_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/External/MNN)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_BENCHMARK OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID ON CACHE BOOL "" FORCE)
set(MNN_BUILD_FOR_ANDROID_COMMAND ON CACHE BOOL "" FORCE)
set(MNN_OPENCL ON CACHE BOOL "" FORCE)
set(MNN_VULKAN ON CACHE BOOL "" FORCE)
set(MNN_VULKAN_IMAGE ON CACHE BOOL "" FORCE)
set(MNN_ARM82 ON CACHE BOOL "" FORCE)
set(MNN_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(MNN_LOW_MEMORY ON CACHE BOOL "" FORCE)
set(MNN_USE_LOGCAT ON CACHE BOOL "" FORCE)
add_subdirectory(${MNN_ROOT_DIR} mnn-build EXCLUDE_FROM_ALL)

add_library(${PROJECT_NAME} SHARED
    qnn_upscaler.cpp
    mnn_fallback.cpp)
# Static archives normally discard pure registration objects. These two MNN
# object targets register GPU backends at process load, so link them directly.
target_sources(${PROJECT_NAME} PRIVATE
    $<TARGET_OBJECTS:MNN_CL>
    $<TARGET_OBJECTS:MNN_Vulkan>)
target_include_directories(${PROJECT_NAME} PRIVATE
    ${QAI_APPBUILDER_DIR}/include
    ${MNN_ROOT_DIR}/include)
target_compile_options(${PROJECT_NAME} PRIVATE
    -O3 -Wall -Wextra -fPIC -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(${PROJECT_NAME} PRIVATE -Wl,--gc-sections
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

find_library(android-lib android)
find_library(log-lib log)
find_library(dl-lib dl)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)
target_link_libraries(${PROJECT_NAME} PRIVATE
    qaiappbuilder
    MNN
    ${android-lib}
    ${log-lib}
    ${dl-lib}
    ${egl-lib}
    ${glesv2-lib})
