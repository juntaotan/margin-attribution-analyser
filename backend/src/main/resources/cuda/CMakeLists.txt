cmake_minimum_required(VERSION 3.24)

option(FGW_CPU_ONLY "Build host mathematical validation without CUDA" OFF)
option(FGW_ONLY "Build only the FGW targets" OFF)

if(FGW_CPU_ONLY)
    project(margin_attribution_analyser LANGUAGES CXX)
else()
    project(margin_attribution_analyser LANGUAGES CXX CUDA)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CUDA_STANDARD 17)

set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)
include(CTest)
find_package(Threads REQUIRED)

# This validates the production host routines; it is not a CPU FGW engine.
add_library(fgw_math_validation STATIC test/fgw/host_math.cpp)
target_include_directories(fgw_math_validation PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
add_executable(fgw_host_test test/fgw/fgw_test.cpp)
target_link_libraries(fgw_host_test PRIVATE fgw_math_validation)
if(BUILD_TESTING)
    add_test(NAME fgw_host_test COMMAND fgw_host_test)
endif()
if(FGW_CPU_ONLY)
    return()
endif()

find_package(CUDAToolkit REQUIRED)
add_library(cuda_fgw STATIC fgw.cu fgw_engine.cu)
target_include_directories(cuda_fgw PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(cuda_fgw PUBLIC CUDA::cudart CUDA::cublas Threads::Threads)
set_target_properties(cuda_fgw PROPERTIES CUDA_SEPARABLE_COMPILATION ON)
add_executable(fgw_cuda_test test/fgw/fgw_test.cpp)
target_compile_definitions(fgw_cuda_test PRIVATE FGW_CUDA_TEST)
target_link_libraries(fgw_cuda_test PRIVATE cuda_fgw)
add_executable(fgw_cuda_run test/fgw/fgw_run.cpp)
target_link_libraries(fgw_cuda_run PRIVATE cuda_fgw)
if(BUILD_TESTING)
    add_test(NAME fgw_cuda_test COMMAND fgw_cuda_test)
    set_tests_properties(fgw_cuda_test PROPERTIES SKIP_RETURN_CODE 77)
endif()
if(FGW_ONLY)
    return()
endif()

add_library(cuda_backend
    bom_upward_engine.cu
    lof_calculate.cu
)

target_include_directories(cuda_backend PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(cuda_backend
    PRIVATE CUDA::cudart
)

add_executable(lof_calculate_test test/lof_calculate_test.cu)
target_link_libraries(lof_calculate_test PRIVATE cuda_backend)

# Keep the 14,000-point batch test as an explicit executable.
add_executable(lof_engine_test test/lof_engine_test.cu)
target_link_libraries(lof_engine_test PRIVATE cuda_backend)

include(CTest)
if(BUILD_TESTING)
    add_test(NAME lof_calculate_test COMMAND lof_calculate_test)

    # Prefer an installed test dependency; retain the existing download fallback.
    find_package(GTest QUIET)
    if(NOT GTest_FOUND)
        include(FetchContent)
        FetchContent_Declare(googletest
            URL https://github.com/google/googletest/archive/refs/tags/v1.18.0.zip
        )
        FetchContent_MakeAvailable(googletest)
    endif()

    add_executable(bom_upward_engine_test test/bom_upward_engine_test.cu)
    target_link_libraries(bom_upward_engine_test PRIVATE cuda_backend GTest::gtest_main)
    include(GoogleTest)
    gtest_discover_tests(bom_upward_engine_test PROPERTIES TIMEOUT 60)
endif()
