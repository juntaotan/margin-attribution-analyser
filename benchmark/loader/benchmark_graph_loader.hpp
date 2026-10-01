#pragma once

#include <filesystem>

#include "bom_upward_engine.cuh"

class BenchmarkGraphLoader {
public:
    static BomUpwardGraph load(
        const std::filesystem::path& dataset_directory
    );
};