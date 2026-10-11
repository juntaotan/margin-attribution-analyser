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

add_library(lof_opencl_engine lof_engine.cpp)
target_include_directories(lof_opencl_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(lof_opencl_engine PUBLIC OpenCL::OpenCL)

# --------------------------------------------------
# Copy OpenCL kernel to build directory
# --------------------------------------------------

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/bom_upward.cl
    ${CMAKE_CURRENT_BINARY_DIR}/bom_upward.cl
    COPYONLY
)

configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/lof_calculate.cl
    ${CMAKE_CURRENT_BINARY_DIR}/lof_calculate.cl
    COPYONLY
)

# Run the 14,000-point batch test explicitly.
add_executable(lof_engine_test test/lof_engine_test.cpp)
target_link_libraries(lof_engine_test PRIVATE lof_opencl_engine)
target_compile_definitions(lof_engine_test PRIVATE
    LOF_KERNEL_PATH="${CMAKE_CURRENT_BINARY_DIR}/lof_calculate.cl"
)

# --------------------------------------------------
# GoogleTest
# --------------------------------------------------

include(CTest)
if(BUILD_TESTING)
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
endif()

# FP64 balanced FGW, independent of CUDA. Device tests never skip.
find_package(Threads REQUIRED)
add_library(fgw_opencl_engine fgw_engine.cpp)
target_include_directories(fgw_opencl_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(fgw_opencl_engine PUBLIC OpenCL::OpenCL Threads::Threads)
configure_file(fgw.cl fgw.cl COPYONLY)
foreach(target fgw_opencl_test fgw_opencl_run)
    if(target STREQUAL "fgw_opencl_test")
        set(source test/fgw/fgw_test.cpp)
    else()
        set(source test/fgw/fgw_run.cpp)
    endif()
    add_executable(${target} ${source})
    target_link_libraries(${target} PRIVATE fgw_opencl_engine)
    target_compile_definitions(${target} PRIVATE FGW_KERNEL_PATH="${CMAKE_CURRENT_BINARY_DIR}/fgw.cl")
endforeach()
if(BUILD_TESTING)
    add_test(NAME fgw_opencl_default COMMAND fgw_opencl_test)
    foreach(mode RANGE 0 2)
        add_test(NAME fgw_opencl_mode_${mode} COMMAND fgw_opencl_test)
        set_tests_properties(fgw_opencl_mode_${mode} PROPERTIES ENVIRONMENT "FGW_SIMPLEX_MODE=${mode}" TIMEOUT 120)
    endforeach()
endif()
