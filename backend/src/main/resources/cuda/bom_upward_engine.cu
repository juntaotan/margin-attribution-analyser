#include "bom_upward_engine.cuh"

#include <thrust/copy.h>
#include <thrust/device_vector.h>
#include <stdexcept>
#include <utility>

// Compile the existing kernel with its scheduler in a single translation unit.
#include "bom_upward.cu"

// Run each frontier on the default stream, reading its successor count before swapping.
std::vector<uint32_t> CudaBomUpwardEngine::run(
    const BomUpwardGraph& graph,
    const uint64_t threshold
) {
    const auto node_count = graph.node_values.size();
    if (node_count == 0 || graph.terminal_nodes.empty()) {
        return {};
    }
    // Match the OpenCL engine's graph dimension contract.
    if (graph.offsets.size() != node_count + 1 ||
        graph.reverse_offsets.size() != node_count + 1 ||
        graph.node_comparable.size() != node_count) {
        throw std::runtime_error("Invalid BOM graph dimensions.");
    }

    // Upload immutable graph data; device vectors release all allocations on exit.
    thrust::device_vector<uint32_t> offsets(graph.offsets);
    thrust::device_vector<uint32_t> reverse_offsets(graph.reverse_offsets);
    thrust::device_vector<uint32_t> reverse_successors(graph.reverse_successors);
    thrust::device_vector<int64_t> node_values(graph.node_values);
    thrust::device_vector<uint8_t> node_comparable(graph.node_comparable);

    thrust::device_vector<uint32_t> remaining(node_count, UNDISCOVERED);
    thrust::device_vector<uint32_t> frontier_a(node_count), frontier_b(node_count);
    thrust::device_vector<uint32_t> next_count(1, 0), result_count(1, 0);
    thrust::device_vector<uint32_t> result_nodes(node_count);
    thrust::copy(graph.terminal_nodes.begin(), graph.terminal_nodes.end(), frontier_a.begin());

    auto* current_frontier = thrust::raw_pointer_cast(frontier_a.data());
    auto* next_frontier = thrust::raw_pointer_cast(frontier_b.data());
    auto current_count = static_cast<uint32_t>(graph.terminal_nodes.size());
    constexpr uint32_t block_size = 256;

    while (current_count > 0) {
        next_count[0] = 0;
        process_frontier<<<(current_count - 1) / block_size + 1, block_size>>>(
            thrust::raw_pointer_cast(offsets.data()),
            thrust::raw_pointer_cast(reverse_offsets.data()),
            thrust::raw_pointer_cast(reverse_successors.data()),
            current_frontier,
            next_frontier,
            thrust::raw_pointer_cast(next_count.data()),
            thrust::raw_pointer_cast(remaining.data()),
            threshold,
            thrust::raw_pointer_cast(node_values.data()),
            thrust::raw_pointer_cast(node_comparable.data()),
            current_count,
            thrust::raw_pointer_cast(result_nodes.data()),
            thrust::raw_pointer_cast(result_count.data())
        );
        if (const auto error = cudaGetLastError(); error != cudaSuccess) {
            throw std::runtime_error(cudaGetErrorString(error));
        }

        // Reading the device counter waits for this round to finish.
        current_count = next_count[0];
        std::swap(current_frontier, next_frontier);
    }

    const uint32_t count = result_count[0];
    std::vector<uint32_t> results(count);
    thrust::copy_n(result_nodes.begin(), count, results.begin());
    return results;
}
