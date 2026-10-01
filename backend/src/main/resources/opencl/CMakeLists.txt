cmake_minimum_required(VERSION 3.20)

project(BomUpwardEngine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# --------------------------------------------------
# OpenCL
# --------------------------------------------------

find_package(OpenCL REQUIRED)

# --------------------------------------------------
# Main library
# --------------------------------------------------

add_library(
    bom_upward_engine
    bom_upward_engine.cpp
)

target_include_directories(
    bom_upward_engine
    PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    bom_upward_engine
    PUBLIC
    OpenCL::OpenCL
)

# --------------------------------------------------
# Copy OpenCL kernel to build directory
# --------------------------------------------------

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/bom_upward.cl
    ${CMAKE_CURRENT_BINARY_DIR}/bom_upward.cl
    COPYONLY
)

# --------------------------------------------------
# GoogleTest
# --------------------------------------------------

include(FetchContent)

FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/archive/refs/tags/v1.18.0.zip
)

FetchContent_MakeAvailable(googletest)

enable_testing()

# --------------------------------------------------
# Tests
# --------------------------------------------------

add_executable(
    bom_upward_engine_test
    test/bom_upward_engine_test.cpp
)

target_link_libraries(
    bom_upward_engine_test
    PRIVATE
    bom_upward_engine
    GTest::gtest_main
)

target_compile_definitions(bom_upward_engine_test PRIVATE
    BOM_UPWARD_KERNEL_PATH="${CMAKE_CURRENT_BINARY_DIR}/bom_upward.cl"
)

include(GoogleTest)

gtest_discover_tests(
    bom_upward_engine_test
    PROPERTIES TIMEOUT 60
)
