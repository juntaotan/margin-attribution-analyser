cmake_minimum_required(VERSION 3.24)

project(
    margin_attribution_benchmark
    LANGUAGES CXX CUDA
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)

find_package(OpenCL REQUIRED)

add_executable(
    cuda_benchmark

    cuda/cuda_benchmark.cu
    loader/benchmark_graph_loader.cpp
    ../backend/src/main/resources/cuda/bom_upward_engine.cu
)

target_include_directories(
    cuda_benchmark
    PRIVATE
        ../backend/src/main/resources/cuda
        loader
        common
)

set_target_properties(
    cuda_benchmark
    PROPERTIES
        CUDA_SEPARABLE_COMPILATION ON
)

add_executable(
    opencl_benchmark

    opencl/opencl_benchmark.cpp
    loader/benchmark_graph_loader.cpp
    ../backend/src/main/resources/opencl/bom_upward_engine.cpp
)

target_include_directories(
    opencl_benchmark
    PRIVATE
        ../backend/src/main/resources/opencl
        loader
        common
)

target_link_libraries(opencl_benchmark PRIVATE OpenCL::OpenCL)

target_compile_definitions(
    opencl_benchmark
    PRIVATE
        BOM_UPWARD_KERNEL_PATH="${CMAKE_CURRENT_SOURCE_DIR}/../backend/src/main/resources/opencl/bom_upward.cl"
)
