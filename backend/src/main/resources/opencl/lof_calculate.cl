// Compute Euclidean distance between two four-feature orders.
float order_distance(__global const float* orders, int target, int candidate) {
    float squared_distance = 0.0f;

    // Compare corresponding features of the two orders.
    for (int feature = 0; feature < 4; ++feature) {
        const float difference = orders[target * 4 + feature] - orders[candidate * 4 + feature];
        squared_distance += difference * difference;
    }

    return sqrt(squared_distance);
}

// Store one order's distances in each work item's matrix row.
__kernel void distance_kernel(__global const float* orders, __global float* distances, int count) {
    const int target = (int)get_global_id(0);
    if (target >= count) return;

    // The diagonal is excluded from neighbor searches.
    for (int candidate = 0; candidate < count; ++candidate) {
        distances[target * count + candidate] = candidate == target
            ? INFINITY
            : order_distance(orders, target, candidate);
    }
}

// Select one order's kth distance without changing the shared distance matrix.
__kernel void k_distance_kernel(__global const float* distances,
                                __global float* k_distances, int count, int k) {
    const int target = (int)get_global_id(0);
    if (target >= count) return;

    // The last selected distance and index distinguish equal-distance neighbors.
    float previous_distance = -1.0f;
    int previous_index = -1;

    // Advance through the sorted (distance, index) order k times.
    for (int rank = 0; rank < k; ++rank) {
        float best_distance = INFINITY;
        int best_index = -1;

        // Find the nearest candidate after the previously selected pair.
        for (int candidate = 0; candidate < count; ++candidate) {
            if (candidate == target) continue;
            const float candidate_distance = distances[target * count + candidate];
            if (candidate_distance < previous_distance ||
                (candidate_distance == previous_distance && candidate <= previous_index)) continue;
            if (candidate_distance < best_distance ||
                (candidate_distance == best_distance && (best_index < 0 || candidate < best_index))) {
                best_distance = candidate_distance;
                best_index = candidate;
            }
        }

        previous_distance = best_distance;
        previous_index = best_index;
    }

    k_distances[target] = previous_distance;
}

// Compute one order's local reachability density from its neighbors.
__kernel void lrd_kernel(__global const float* distances,
                         __global const float* k_distances,
                         __global float* lrd, int count) {
    const int target = (int)get_global_id(0);
    if (target >= count) return;

    float reach_distance_sum = 0.0f;
    int neighbor_count = 0;

    // Include every neighbor at or inside the kth distance, including ties.
    for (int neighbor = 0; neighbor < count; ++neighbor) {
        const float actual_distance = distances[target * count + neighbor];
        if (neighbor == target || actual_distance > k_distances[target]) continue;
        reach_distance_sum += fmax(actual_distance, k_distances[neighbor]);
        ++neighbor_count;
    }

    lrd[target] = reach_distance_sum == 0.0f
        ? INFINITY
        : (float)neighbor_count / reach_distance_sum;
}

// Average one order's neighbor-to-target density ratios into its LOF.
__kernel void lof_kernel(__global const float* distances,
                         __global const float* k_distances,
                         __global const float* lrd,
                         __global float* scores, int count) {
    const int target = (int)get_global_id(0);
    if (target >= count) return;

    float ratio_sum = 0.0f;
    int neighbor_count = 0;

    // Use the same tie-inclusive neighbor set as the LRD kernel.
    for (int neighbor = 0; neighbor < count; ++neighbor) {
        if (neighbor == target || distances[target * count + neighbor] > k_distances[target]) continue;
        const float ratio = isinf(lrd[target]) && isinf(lrd[neighbor])
            ? 1.0f
            : lrd[neighbor] / lrd[target];
        ratio_sum += ratio;
        ++neighbor_count;
    }

    scores[target] = ratio_sum / (float)neighbor_count;
}
