cmake_minimum_required(VERSION 3.24)

project(
    margin_attribution_benchmark
    LANGUAGES CXX CUDA
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)

add_executable(
    cuda_benchmark

    cuda/cuda_benchmark.cu
    ../backend/src/main/resources/cuda/bom_upward_engine.cu
)

target_include_directories(
    cuda_benchmark
    PRIVATE
        ../backend/src/main/resources/cuda
)

set_target_properties(
    cuda_benchmark
    PROPERTIES
        CUDA_SEPARABLE_COMPILATION ON
)