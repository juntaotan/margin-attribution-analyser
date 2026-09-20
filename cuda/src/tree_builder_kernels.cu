#include "tree_builder/tree_builder.cuh"

namespace margintrace::cuda {

__global__ void init_targets_kernel(
    const uint32_t* targets,
    uint32_t num_targets,
    uint32_t* frontier,
    uint32_t* visited_mask,
    GpuNodeAttributes nodes
) {
    uint32_t tid = blockDim.x * blockIdx.x + threadIdx.x;
    if (tid < num_targets) {
        uint32_t target_id = targets[tid];
        frontier[tid] = target_id;

        // Atomically set visited bitmask to avoid re-queuing
        uint32_t word_idx = target_id / 32;
        uint32_t bit_mask = (1u << (target_id % 32));
        atomicOr(&visited_mask[word_idx], bit_mask);

        // Mark node flags as target
        if (nodes.d_flags != nullptr) {
            atomicOr(&nodes.d_flags[target_id], 1u);
        }
    }
}

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
) {
    uint32_t tid = blockDim.x * blockIdx.x + threadIdx.x;
    if (tid >= usages.size) return;

    uint32_t prod_id = usages.d_product_ids[tid];
    uint32_t mat_id = usages.d_material_ids[tid];

    // Filter out self-consumption (aligns with Java: if (materialId.equals(productId)) continue;)
    if (prod_id == mat_id) return;

    // Check whether the product is in the active frontier
    bool active = false;
    for (uint32_t i = 0; i < frontier_size; ++i) {
        if (current_frontier[i] == prod_id) {
            active = true;
            break;
        }
    }
    if (!active) return;

    // 1. Record directed dependency edge (material -> product)
    uint32_t edge_slot = atomicAdd(out_edge_count, 1);
    out_edges[edge_slot] = Edge{mat_id, prod_id};

    // 2. Accumulate material consumption quantity and cost
    if (nodes.d_quantities != nullptr && usages.d_material_qtys != nullptr) {
        atomicAdd(&nodes.d_quantities[mat_id], usages.d_material_qtys[tid]);
    }
    if (nodes.d_costs != nullptr && usages.d_material_costs != nullptr) {
        atomicAdd(&nodes.d_costs[mat_id], usages.d_material_costs[tid]);
    }

    // 3. Check if material has been visited; if not, push to next frontier
    uint32_t word_idx = mat_id / 32;
    uint32_t bit_mask = (1u << (mat_id % 32));
    uint32_t prev_mask = atomicOr(&visited_mask[word_idx], bit_mask);

    if ((prev_mask & bit_mask) == 0) {
        uint32_t next_slot = atomicAdd(next_frontier_count, 1);
        next_frontier[next_slot] = mat_id;
    }
}

__global__ void count_outdegrees_kernel(
    const Edge* edges,
    uint32_t num_edges,
    int* outdegrees
) {
    uint32_t tid = blockDim.x * blockIdx.x + threadIdx.x;
    if (tid < num_edges) {
        atomicAdd(&outdegrees[edges[tid].src], 1);
    }
}

__global__ void fill_successors_kernel(
    const Edge* edges,
    uint32_t num_edges,
    const int* offsets,
    int* edge_cursors,
    int* successors
) {
    uint32_t tid = blockDim.x * blockIdx.x + threadIdx.x;
    if (tid < num_edges) {
        uint32_t src = edges[tid].src;
        uint32_t dst = edges[tid].dst;

        int slot = atomicAdd(&edge_cursors[src], 1);
        successors[offsets[src] + slot] = static_cast<int>(dst);
    }
}

} // namespace margintrace::cuda
