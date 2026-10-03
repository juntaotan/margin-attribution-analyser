#include "lof_engine.cuh"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>

// Sum squared feature differences and take the Euclidean norm.
float distance_calculator::distance(const float* vector_a, const float* vector_b, int dimension) {
    float squared_distance = 0.0f;

    // Compare the same feature in each order.
    for (int feature = 0; feature < dimension; ++feature) {
        const float difference = vector_a[feature] - vector_b[feature];
        squared_distance += difference * difference;
    }

    return sqrtf(squared_distance);
}

// Build neighbor lists and kth distances for every order.
Neighbourhoods find_top_k::tops(const float* all_vectors, int num_vectors, int dimension, int k) {
    if (dimension <= 0 || k <= 0 || k >= num_vectors) {
        throw std::invalid_argument("LOF requires dimension > 0 and 1 <= k < num_vectors");
    }

    // Each row may contain more than k neighbors when distances tie.
    Neighbourhoods neighborhoods;
    neighborhoods.indices.resize(num_vectors);
    neighborhoods.k_distances.resize(num_vectors);

    // Treat each order as the target once.
    for (int target = 0; target < num_vectors; ++target) {
        // Store candidate distances with their original order indices.
        std::vector<std::pair<float, int>> candidates;
        candidates.reserve(num_vectors - 1);

        // Measure distances to all other orders, excluding the target itself.
        for (int candidate = 0; candidate < num_vectors; ++candidate) {
            if (candidate == target) {
                continue;
            }

            const float candidate_distance = distance_calculator::distance(
                all_vectors + target * dimension,
                all_vectors + candidate * dimension,
                dimension
            );
            candidates.emplace_back(candidate_distance, candidate);
        }

        // Sort by distance, then index, so equal distances have a stable order.
        std::sort(candidates.begin(), candidates.end());

        // The kth distance also determines which tied candidates belong to N_k.
        const float kth_distance = candidates[k - 1].first;
        neighborhoods.k_distances[target] = kth_distance;

        // Include all candidates at or before the kth distance.
        for (const auto& candidate : candidates) {
            if (candidate.first > kth_distance) {
                break;
            }
            neighborhoods.indices[target].push_back(candidate.second);
        }
    }

    return neighborhoods;
}

// Compute LRD for every order, then average neighbor-to-target LRD ratios.
std::vector<float> lof_calculator::calculate(const float* all_vectors, int num_vectors, int dimension, int k) {
    // Neighbor indices and kth distances are shared by the LRD and LOF steps.
    const Neighbourhoods neighborhoods = find_top_k::tops(all_vectors, num_vectors, dimension, k);

    // A zero reach-distance sum gives an infinite local density.
    std::vector<double> lrd(num_vectors);

    // Compute the local reachability density of each order.
    for (int target = 0; target < num_vectors; ++target) {
        double reach_distance_sum = 0.0;

        // Reach distance uses the neighbor's kth distance, not the target's.
        for (const int neighbor : neighborhoods.indices[target]) {
            const float actual_distance = distance_calculator::distance(
                all_vectors + target * dimension,
                all_vectors + neighbor * dimension,
                dimension
            );
            reach_distance_sum += std::max(actual_distance, neighborhoods.k_distances[neighbor]);
        }

        lrd[target] = reach_distance_sum == 0.0
            ? std::numeric_limits<double>::infinity()
            : neighborhoods.indices[target].size() / reach_distance_sum;
    }

    // Scores retain the input order so each result maps to one order.
    std::vector<float> scores(num_vectors);

    // Compare each order's LRD with the LRDs of its neighbors.
    for (int target = 0; target < num_vectors; ++target) {
        double ratio_sum = 0.0;

        // Two infinite densities represent the same zero-reach neighborhood.
        for (const int neighbor : neighborhoods.indices[target]) {
            const double ratio = std::isinf(lrd[target]) && std::isinf(lrd[neighbor])
                ? 1.0
                : lrd[neighbor] / lrd[target];
            ratio_sum += ratio;
        }

        scores[target] = static_cast<float>(ratio_sum / neighborhoods.indices[target].size());
    }

    return scores;
}
