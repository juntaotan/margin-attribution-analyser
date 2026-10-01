
// Define a special value to represent an undiscovered node in the graph.
#define UNDISCOVERED 0xFFFFFFFFu

__kernel void process_frontier(
    __global const uint *offsets,            // CSR Graph from products to materials
    __global const uint *reverse_offsets,    // Reversed CSR Graph from materials to products
    __global const uint *reverse_successors, // Reversed CSR Graph from materials to products
    __global const uint *current_frontier,   // Current frontier of nodes to process (first round is terminal nodes)
    __global uint *next_frontier,            // Next frontier of nodes to process (prepare for next round)
    __global uint *next_frontier_count,      // Count of Next frontier nodes in the next frontier
    __global uint *remaining_nodes_count,
    const ulong threshold,                   // Threshold for stopping condition
    __global const long *node_values,        // Values associated with each node
    __global const uchar *node_comparable,   // Whether this node appears in two DAGs (1 if it does, 0 otherwise)
    const uint work_item_count, 
    __global uint *result_nodes,
    __global uint *result_count
) {
  // Get the current work item index
  const uint work_item = (uint)get_global_id(0);
  if (work_item >= work_item_count) {
    return;
  }

  // Get the successors of this node, which represent its semi-products or
  // products.
  const uint current_item = current_frontier[work_item];
  const uint begin_idx = reverse_offsets[current_item];
  const uint end_idx = reverse_offsets[current_item + 1u];

  for (uint edge = begin_idx; edge < end_idx; ++edge) {

    // Get the successor node.
    const uint parent_item = reverse_successors[edge];

    // Get the amount of child nodes for this successor node.
    const uint begin_idx_child = offsets[parent_item];
    const uint end_idx_child = offsets[parent_item + 1u];
    const uint child_count = end_idx_child - begin_idx_child;

    // Store the amount of child nodes for this successor node, using CAS to
    // ensure that only one work item writes to it.
    atomic_cmpxchg(
        (volatile __global uint *)&remaining_nodes_count[parent_item],
        UNDISCOVERED, 
        child_count
    );

    // The current child has arrived, so decrease the remaining count by one.
    const uint old_remaining = atomic_dec(
        (volatile __global uint *)&remaining_nodes_count[parent_item]);

    // prune the propagation path once the stopping condition is satisfied
    if (old_remaining == 1u) {

      // Only exists in one DAG: cannot compare yet, continue propagation.
      if (node_comparable[parent_item] == 0u) {
        const uint pos = atomic_inc((volatile __global uint *)next_frontier_count);
        next_frontier[pos] = parent_item;
        continue;
      }

      // Exists in both DAGs: check significance.
      if (abs(node_values[parent_item]) >= threshold) {
        const uint pos = atomic_inc((volatile __global uint *)result_count);
        result_nodes[pos] = parent_item;
        continue;
      }

      // Below threshold: continue propagation.
      const uint pos = atomic_inc((volatile __global uint *)next_frontier_count);
      next_frontier[pos] = parent_item;
    }
  }
}