#pragma once

#include "types.cuh"
#include <cuda_runtime.h>
#include <thrust/device_vector.h>
#include <cstdint>

namespace margintrace::cuda {

/**
 * Kernel declarations for dependency tree construction.
 */
__global__ void init_targets_kernel(
    const uint32_t* targets,
    uint32_t num_targets,
    uint32_t* frontier,
    uint32_t* visited_mask,
    GpuNodeAttributes nodes
);

__global__ void expand_frontier_kernel(
    const uint32_t* current_frontier,
    uint32_t frontier_size,
    UsageRawData usages,
    uint32_t* visited_mask,
    uint32_t* next_frontier,
    uint32_t* next_frontier_count,
    Edge* out_edges,
    uint32_t* out_edge_count,
    GpuNodeAttributes nodes
);

__global__ void count_outdegrees_kernel(
    const Edge* edges,
    uint32_t num_edges,
    int* outdegrees
);

__global__ void fill_successors_kernel(
    const Edge* edges,
    uint32_t num_edges,
    const int* offsets,
    int* edge_cursors,
    int* successors
);

/**
 * Host orchestrator class for GPU-accelerated dependency tree building.
 */
class CudaTreeBuilder {
public:
    CudaTreeBuilder() = default;
    ~CudaTreeBuilder() = default;

    /**
     * Executes the tree building pipeline on the GPU:
     * 1. Initializes target active frontier.
     * 2. Iteratively expands upstream material dependencies layer-by-layer (reverse BFS).
     * 3. Accumulates quantities and costs per material node.
     * 4. Deduplicates generated edges via parallel sort/unique.
     * 5. Computes degrees and CSR prefix sums to form offset and successors arrays.
     *
     * @param total_nodes Total number of distinct nodes in the catalog
     * @param d_targets Target sold product IDs in device memory
     * @param d_usages Raw inventory usage table in device memory
     * @param d_productions Raw production table in device memory
     * @param stream CUDA stream to run on (defaults to nullptr)
     * @return Constructed GpuCsrGraph in device memory
     */
    GpuCsrGraph buildTree(
        uint32_t total_nodes,
        const thrust::device_vector<uint32_t>& d_targets,
        const UsageRawData& d_usages,
        const ProductionRawData& d_productions,
        cudaStream_t stream = nullptr
    );
};

} // namespace margintrace::cuda
