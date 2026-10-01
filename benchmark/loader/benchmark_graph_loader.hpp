#pragma once

#include <cstdint>
#include <filesystem>
#include <vector>

/** Backend-neutral arrays loaded once from a benchmark fixture. */
struct BenchmarkGraph {
    std::vector<uint32_t> offsets;
    std::vector<uint32_t> reverse_offsets;
    std::vector<uint32_t> reverse_successors;
    std::vector<uint32_t> terminal_nodes;
    std::vector<int64_t> node_values;
    std::vector<uint8_t> node_comparable;
};

/** Reads and validates the shared little-endian benchmark fixture. */
class BenchmarkGraphLoader {
public:
    static BenchmarkGraph load(
        const std::filesystem::path& dataset_directory
    );
};
