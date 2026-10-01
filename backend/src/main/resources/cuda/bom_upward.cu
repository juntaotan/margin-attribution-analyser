#include <cstdint>

// Define a special value to represent an undiscovered node in the graph.
#define UNDISCOVERED 0xFFFFFFFFu

// Process one upward frontier, releasing parents only after all children arrive.
__global__ void process_frontier(
    const uint32_t *offsets,            // CSR Graph from products to materials
    const uint32_t *reverse_offsets,    // Reversed CSR Graph from materials to products
    const uint32_t *reverse_successors, // Reversed CSR Graph from materials to products
    const uint32_t *current_frontier,   // Current frontier of nodes to process (first round is terminal nodes)
    uint32_t *next_frontier,            // Next frontier of nodes to process (prepare for next round)
    uint32_t *next_frontier_count,      // Count of Next frontier nodes in the next frontier
    uint32_t *remaining_nodes_count,
    const uint64_t threshold,       // Threshold for stopping condition
    const int64_t *node_values,     // Values associated with each node
    const uint8_t *node_comparable, // Whether this node appears in two DAGs (1 if it does, 0 otherwise)
    const uint32_t work_item_count,
    uint32_t *result_nodes,
    uint32_t *result_count)
{
    // Get the current work item index
    const uint32_t work_item = blockIdx.x * blockDim.x + threadIdx.x;
    if (work_item >= work_item_count)
    {
        return;
    }
    // Get the successors of this node, which represent its semi-products or
    // products.
    const uint32_t current_item = current_frontier[work_item];
    const uint32_t begin_idx = reverse_offsets[current_item];
    const uint32_t end_idx = reverse_offsets[current_item + 1u];

    for (uint32_t edge = begin_idx; edge < end_idx; ++edge)
    {

        // Get the successor node.
        const uint32_t parent_item = reverse_successors[edge];

        // Get the amount of child nodes for this successor node.
        const uint32_t begin_idx_child = offsets[parent_item];
        const uint32_t end_idx_child = offsets[parent_item + 1u];
        const uint32_t child_count = end_idx_child - begin_idx_child;

        // Store the amount of child nodes for this successor node, using CAS to
        // ensure that only one work item writes to it.
        atomicCAS(
            &remaining_nodes_count[parent_item],
            UNDISCOVERED,
            child_count);

        // The current child has arrived, so decrease the remaining count by one.
        const uint32_t old_remaining = atomicSub(
            &remaining_nodes_count[parent_item], 1u);

        // prune the propagation path once the stopping condition is satisfied
        if (old_remaining == 1u)
        {

            // Only exists in one DAG: cannot compare yet, continue propagation.
            if (node_comparable[parent_item] == 0u)
            {
                const uint32_t pos = atomicAdd(next_frontier_count, 1u);
                next_frontier[pos] = parent_item;
                continue;
            }

            // Unsigned magnitude also represents INT64_MIN without signed overflow.
            const int64_t value = node_values[parent_item];
            const uint64_t magnitude = value < 0
                ? uint64_t{0} - static_cast<uint64_t>(value)
                : static_cast<uint64_t>(value);
            if (magnitude >= threshold)
            {
                const uint32_t pos = atomicAdd(result_count, 1u);
                result_nodes[pos] = parent_item;
                continue;
            }

            // Below threshold: continue propagation.
            const uint32_t pos = atomicAdd(next_frontier_count, 1u);
            next_frontier[pos] = parent_item;
        }
    }
}
