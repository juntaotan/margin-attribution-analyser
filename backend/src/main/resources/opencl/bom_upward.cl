
// Define a special value to represent an undiscovered node in the graph.
#define UNDISCOVERED 0xFFFFFFFFu

__kernel void process_frontier(
    __global const uint* offsets,            // CSR Graph from products to materials
    __global const uint* successors,         // CSR Graph from products to materials
    __global const uint* reverse_offsets,    // Reversed CSR Graph from materials to products
    __global const uint* reverse_successors, // Reversed CSR Graph from materials to products
    __global const uint* terminal_nodes,
    __global uint* remaining_nodes_count,

    const float comparison_value,
    const float significance_level,

    const uint work_item_count
) {
    // Get the current work item index
    const uint work_item = (uint)get_global_id(0);
    if (work_item >= work_item_count) {
        return;
    }

    // Get the successors of this node, which represent its semi-products or products.
    const uint current_item = terminal_nodes[work_item];
    const uint begin_idx = reverse_offsets[current_item];
    const uint end_idx = reverse_offsets[current_item + 1u];
    for (uint edge = begin_idx; edge < end_idx; ++edge) {
        // Get the successor node.
        const uint parent_item = reverse_successors[edge];
        // Get the amount of child nodes for this successor node.
        const uint begin_idx_child = offsets[parent_item];
        const uint end_idx_child = offsets[parent_item + 1u];
        const uint child_count = end_idx_child - begin_idx_child;
        // Store the amount of child nodes for this successor node, using CAS to ensure that only 
        // one work item writes to it.
        const uint old_value = atomic_cmpxchg(
            (volatile __global uint*)&remaining_nodes_count[parent_item],
            UNDISCOVERED,
            child_count
        );
    }
}