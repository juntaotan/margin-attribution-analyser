#pragma once

#include <cstdint>
#include <vector>

// Host-side CSR graph and node attributes used by the CUDA upward traversal.
struct BomUpwardGraph {
    std::vector<uint32_t> offsets;
    std::vector<uint32_t> reverse_offsets;
    std::vector<uint32_t> reverse_successors;
    std::vector<uint32_t> terminal_nodes;
    std::vector<int64_t> node_values;
    std::vector<uint8_t> node_comparable;
};

// Schedules bottom-up BOM propagation and collects nodes meeting the threshold.
class CudaBomUpwardEngine {
public:
    // Traverse a database-built graph from its terminals; result order is unspecified.
    std::vector<uint32_t> run(
        const BomUpwardGraph& graph,
        uint64_t threshold
    );
};
