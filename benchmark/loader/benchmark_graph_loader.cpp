#include "bom_upward_engine.cuh"

#include <cstdint>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace {

template <typename T>
std::vector<T> read_binary_array(
    const std::filesystem::path& path
) {
    if (!std::filesystem::exists(path)) {
        throw std::runtime_error(
            "Dataset file does not exist: " + path.string()
        );
    }

    const auto file_size =
        std::filesystem::file_size(path);

    if (file_size % sizeof(T) != 0) {
        throw std::runtime_error(
            "Invalid binary file size: " + path.string()
        );
    }

    const auto count =
        static_cast<std::size_t>(
            file_size / sizeof(T)
        );

    std::vector<T> values(count);

    std::ifstream file(path, std::ios::binary);

    if (!file) {
        throw std::runtime_error(
            "Failed to open file: " + path.string()
        );
    }

    if (file_size > 0) {
        file.read(
            reinterpret_cast<char*>(values.data()),
            static_cast<std::streamsize>(file_size)
        );

        if (!file) {
            throw std::runtime_error(
                "Failed to read file: " + path.string()
            );
        }
    }

    return values;
}


void validate_graph(
    const BomUpwardGraph& graph
) {
    if (graph.offsets.empty()) {
        throw std::runtime_error(
            "offsets is empty"
        );
    }

    const std::size_t node_count =
        graph.offsets.size() - 1;

    /*
     * Both forward and reverse CSR describe
     * the same node set.
     */
    if (graph.reverse_offsets.size()
        != node_count + 1) {

        throw std::runtime_error(
            "reverse_offsets has invalid size"
        );
    }

    /*
     * One value and comparable flag per node.
     */
    if (graph.node_values.size() != node_count) {
        throw std::runtime_error(
            "node_values has invalid size"
        );
    }

    if (graph.node_comparable.size()
        != node_count) {

        throw std::runtime_error(
            "node_comparable has invalid size"
        );
    }

    /*
     * reverse_offsets points into
     * reverse_successors.
     */
    if (graph.reverse_offsets.back()
        != graph.reverse_successors.size()) {

        throw std::runtime_error(
            "Reverse CSR edge count mismatch"
        );
    }

    /*
     * Forward and reverse graphs represent
     * the same edge set.
     *
     * We do not store forward successors,
     * but offsets.back() is still the number
     * of forward edges.
     */
    if (graph.offsets.back()
        != graph.reverse_successors.size()) {

        throw std::runtime_error(
            "Forward/reverse edge count mismatch"
        );
    }

    /*
     * CSR offsets must be monotonically increasing.
     */
    for (
        std::size_t i = 1;
        i < graph.offsets.size();
        ++i
    ) {
        if (graph.offsets[i]
            < graph.offsets[i - 1]) {

            throw std::runtime_error(
                "offsets is not monotonically increasing"
            );
        }
    }

    for (
        std::size_t i = 1;
        i < graph.reverse_offsets.size();
        ++i
    ) {
        if (graph.reverse_offsets[i]
            < graph.reverse_offsets[i - 1]) {

            throw std::runtime_error(
                "reverse_offsets is not monotonically increasing"
            );
        }
    }

    /*
     * All node IDs must be valid.
     */
    for (
        const uint32_t node :
        graph.reverse_successors
    ) {
        if (node >= node_count) {
            throw std::runtime_error(
                "Invalid node ID in reverse_successors"
            );
        }
    }

    for (
        const uint32_t node :
        graph.terminal_nodes
    ) {
        if (node >= node_count) {
            throw std::runtime_error(
                "Invalid terminal node ID"
            );
        }
    }
}

} // namespace


BomUpwardGraph BenchmarkGraphLoader::load(
    const std::filesystem::path& dataset_directory
) {
    BomUpwardGraph graph;

    graph.offsets =
        read_binary_array<uint32_t>(
            dataset_directory / "offsets.bin"
        );

    graph.reverse_offsets =
        read_binary_array<uint32_t>(
            dataset_directory / "reverse_offsets.bin"
        );

    graph.reverse_successors =
        read_binary_array<uint32_t>(
            dataset_directory / "reverse_successors.bin"
        );

    graph.terminal_nodes =
        read_binary_array<uint32_t>(
            dataset_directory / "terminal_nodes.bin"
        );

    graph.node_values =
        read_binary_array<int64_t>(
            dataset_directory / "node_values.bin"
        );

    graph.node_comparable =
        read_binary_array<uint8_t>(
            dataset_directory / "node_comparable.bin"
        );

    validate_graph(graph);

    return graph;
}