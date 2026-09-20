#include "tree_builder/tree_builder.cuh"

#include <thrust/sort.h>
#include <thrust/unique.h>
#include <thrust/scan.h>
#include <thrust/distance.h>
#include <thrust/execution_policy.h>
#include <algorithm>

namespace margintrace::cuda {

GpuCsrGraph CudaTreeBuilder::buildTree(
    uint32_t total_nodes,
    const thrust::device_vector<uint32_t>& d_targets,
    const UsageRawData& d_usages,
    const ProductionRawData& /* d_productions */,
    cudaStream_t stream
) {
    GpuCsrGraph result;
    result.num_nodes = total_nodes;

    if (total_nodes == 0 || d_targets.empty()) {
        result.offset.assign(1, 0);
        return result;
    }

    // 1. Allocate device memory for BFS frontiers and visited bitmask
    thrust::device_vector<uint32_t> frontier_a(total_nodes);
    thrust::device_vector<uint32_t> frontier_b(total_nodes);
    uint32_t mask_words = (total_nodes + 31) / 32;
    thrust::device_vector<uint32_t> visited_mask(mask_words, 0);

    // Candidate edges buffer sized by raw usages count
    thrust::device_vector<Edge> candidate_edges(d_usages.size);
    thrust::device_vector<uint32_t> d_edge_count(1, 0);
    thrust::device_vector<uint32_t> d_next_frontier_count(1, 0);

    // Initialize node attributes
    result.node_quantities.assign(total_nodes, 0.0);
    result.node_costs.assign(total_nodes, 0.0);
    thrust::device_vector<uint32_t> node_flags(total_nodes, 0);

    GpuNodeAttributes node_attrs{
        thrust::raw_pointer_cast(result.node_quantities.data()),
        thrust::raw_pointer_cast(result.node_costs.data()),
        thrust::raw_pointer_cast(node_flags.data())
    };

    // 2. Initialize target products frontier
    uint32_t num_targets = static_cast<uint32_t>(d_targets.size());
    const int block_size = 256;
    int grid_size_targets = (num_targets + block_size - 1) / block_size;

    init_targets_kernel<<<grid_size_targets, block_size, 0, stream>>>(
        thrust::raw_pointer_cast(d_targets.data()),
        num_targets,
        thrust::raw_pointer_cast(frontier_a.data()),
        thrust::raw_pointer_cast(visited_mask.data()),
        node_attrs
    );

    // 3. Multi-level BFS dependency expansion
    uint32_t* cur_frontier = thrust::raw_pointer_cast(frontier_a.data());
    uint32_t* next_frontier = thrust::raw_pointer_cast(frontier_b.data());
    uint32_t current_frontier_size = num_targets;

    int grid_size_usages = (d_usages.size + block_size - 1) / block_size;

    while (current_frontier_size > 0 && grid_size_usages > 0) {
        d_next_frontier_count[0] = 0;

        expand_frontier_kernel<<<grid_size_usages, block_size, 0, stream>>>(
            cur_frontier,
            current_frontier_size,
            d_usages,
            thrust::raw_pointer_cast(visited_mask.data()),
            next_frontier,
            thrust::raw_pointer_cast(d_next_frontier_count.data()),
            thrust::raw_pointer_cast(candidate_edges.data()),
            thrust::raw_pointer_cast(d_edge_count.data()),
            node_attrs
        );

        // Fetch count of newly discovered upstream materials for next level
        cudaMemcpyAsync(
            &current_frontier_size,
            thrust::raw_pointer_cast(d_next_frontier_count.data()),
            sizeof(uint32_t),
            cudaMemcpyDeviceToHost,
            stream
        );
        cudaStreamSynchronize(stream);

        // Swap double-buffered frontier pointers
        std::swap(cur_frontier, next_frontier);
    }

    // 4. Compact and deduplicate generated edges
    uint32_t total_edges = d_edge_count[0];
    if (total_edges > candidate_edges.size()) {
        total_edges = static_cast<uint32_t>(candidate_edges.size());
    }
    candidate_edges.resize(total_edges);

    if (total_edges > 0) {
        auto policy = thrust::cuda::par.on(stream);
        thrust::sort(policy, candidate_edges.begin(), candidate_edges.end());
        auto new_end = thrust::unique(policy, candidate_edges.begin(), candidate_edges.end());
        total_edges = static_cast<uint32_t>(new_end - candidate_edges.begin());
        candidate_edges.resize(total_edges);
    }
    result.num_edges = total_edges;

    // 5. Construct CSR representation (offset and successors)
    thrust::device_vector<int> outdegrees(total_nodes, 0);
    if (total_edges > 0) {
        int grid_size_edges = (total_edges + block_size - 1) / block_size;
        count_outdegrees_kernel<<<grid_size_edges, block_size, 0, stream>>>(
            thrust::raw_pointer_cast(candidate_edges.data()),
            total_edges,
            thrust::raw_pointer_cast(outdegrees.data())
        );
    }

    result.offset.resize(total_nodes + 1);
    thrust::exclusive_scan(
        thrust::cuda::par.on(stream),
        outdegrees.begin(),
        outdegrees.end(),
        result.offset.begin(),
        0
    );
    result.offset[total_nodes] = static_cast<int>(total_edges);

    result.successors.resize(total_edges);
    if (total_edges > 0) {
        thrust::device_vector<int> edge_cursors(total_nodes, 0);
        int grid_size_edges = (total_edges + block_size - 1) / block_size;
        fill_successors_kernel<<<grid_size_edges, block_size, 0, stream>>>(
            thrust::raw_pointer_cast(candidate_edges.data()),
            total_edges,
            thrust::raw_pointer_cast(result.offset.data()),
            thrust::raw_pointer_cast(edge_cursors.data()),
            thrust::raw_pointer_cast(result.successors.data())
        );
    }

    return result;
}

} // namespace margintrace::cuda
