// OpenCL C 1.2-compatible core for bottom-up BOM propagation.
//
// Execution model:
//   1. initialize_query_state is launched once per query.
//   2. process_frontier is launched repeatedly by the C++ host.
//   3. A work-item processes one node from current_frontier.
//   4. The last resolved direct child enqueues its parent in next_frontier.
//
// The scheduling code is complete. The domain-specific comparison is isolated
// in evaluate_node(), so later BOM rules can change without rewriting the
// frontier machinery.

__kernel void bom_upward(
    __global const uint* offsets,
    __global const uint* successors,
    __global const uint* terminal_nodes,
    __global uint* expected_children,
    __global uint* arrived_children,
    const float comparison_value,
    const float significance_level,
    const uint node_count
) {
    // Which node is this work-item responsible for?
    const uint node = (uint)get_global_id(0);
    if (node >= node_count) {
        return;
    }
    // Get the successors of this node, which represent its semi-products or products.
    const uint current_node = terminal_nodes[node];
    const uint begin = offsets[current_node];
    const uint end = offsets[current_node + 1u];

    uint next_node = 0;
    
    for (uint edge = begin; edge < end; ++edge) {
        next_node = successors[edge];
    }

    // Get the amount of children this node has
    const uint child_count = offsets[next_node + 1u] - offsets[next_node];
}