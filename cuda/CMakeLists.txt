cmake_minimum_required(VERSION 3.18)
project(TreeBuilderCuda LANGUAGES CXX CUDA)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)

if(NOT DEFINED CMAKE_CUDA_ARCHITECTURES)
    # Default to common architectures or native detection
    set(CMAKE_CUDA_ARCHITECTURES 75 80 86 89 90)
endif()

# Library definition (Standalone static library without external linkage)
add_library(tree_builder_cuda STATIC
    src/tree_builder_kernels.cu
    src/tree_builder.cu
)

target_include_directories(tree_builder_cuda
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)

set_target_properties(tree_builder_cuda PROPERTIES
    CUDA_SEPARABLE_COMPILATION ON
    POSITION_INDEPENDENT_CODE ON
)
