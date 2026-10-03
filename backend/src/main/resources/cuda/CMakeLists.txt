cmake_minimum_required(VERSION 3.24)

project(margin_attribution_analyser LANGUAGES CXX CUDA)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CUDA_STANDARD 17)

find_package(CUDAToolkit REQUIRED)

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

    include(FetchContent)
    FetchContent_Declare(googletest
        URL https://github.com/google/googletest/archive/refs/tags/v1.18.0.zip
    )
    FetchContent_MakeAvailable(googletest)

    add_executable(bom_upward_engine_test test/bom_upward_engine_test.cu)
    target_link_libraries(bom_upward_engine_test PRIVATE cuda_backend GTest::gtest_main)
    include(GoogleTest)
    gtest_discover_tests(bom_upward_engine_test PROPERTIES TIMEOUT 60)
endif()
