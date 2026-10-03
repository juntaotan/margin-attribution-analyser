#include "lof_engine.cuh"

#include <math_constants.h>
#include <thrust/device_ptr.h>
#include <thrust/device_vector.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <utility>

// Sum squared feature differences and take the Euclidean norm.
__host__ __device__ float distance_calculator::distance(const float* vector_a, const float* vector_b, int dimension) {
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

namespace {

// Throw when a CUDA operation reports an error.
void check_cuda(cudaError_t error) {
    if (error != cudaSuccess) {
        throw std::runtime_error(cudaGetErrorString(error));
    }
}

// Give each thread one order and store its distances to every order.
__global__ void distance_kernel(const float* vectors, float* distances, int count) {
    const int target = blockIdx.x * blockDim.x + threadIdx.x;
    if (target >= count) return;

    // The target's row is reused by all later LOF stages.
    const int row = target * count;

    // Compare the target with every other four-feature order.
    for (int candidate = 0; candidate < count; ++candidate) {
        distances[row + candidate] = candidate == target
            ? CUDART_INF_F
            : distance_calculator::distance(vectors + target * 4, vectors + candidate * 4, 4);
    }
}

// Give each thread one row and find its kth distance without modifying the matrix.
__global__ void k_distance_kernel(const float* distances, float* k_distances, int count, int k) {
    const int target = blockIdx.x * blockDim.x + threadIdx.x;
    if (target >= count) return;

    // The previous pair identifies the last selected neighbor, including ties.
    float previous_distance = -1.0f;
    int previous_index = -1;

    // Select the next (distance, index) pair k times.
    for (int rank = 0; rank < k; ++rank) {
        float best_distance = CUDART_INF_F;
        int best_index = -1;

        // Scan this order's distances for the next nearest neighbor.
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

// Give each thread one order and compute its local reachability density.
__global__ void lrd_kernel(const float* distances, const float* k_distances, double* lrd, int count) {
    const int target = blockIdx.x * blockDim.x + threadIdx.x;
    if (target >= count) return;

    double reach_distance_sum = 0.0;
    int neighbor_count = 0;

    // Include all neighbors tied at the target's kth distance.
    for (int neighbor = 0; neighbor < count; ++neighbor) {
        const float actual_distance = distances[target * count + neighbor];
        if (neighbor == target || actual_distance > k_distances[target]) continue;
        reach_distance_sum += fmaxf(actual_distance, k_distances[neighbor]);
        ++neighbor_count;
    }

    lrd[target] = reach_distance_sum == 0.0
        ? CUDART_INF
        : neighbor_count / reach_distance_sum;
}

// Give each thread one order and average its neighbors' LRD ratios.
__global__ void lof_kernel(const float* distances, const float* k_distances,
                           const double* lrd, float* scores, int count) {
    const int target = blockIdx.x * blockDim.x + threadIdx.x;
    if (target >= count) return;

    double ratio_sum = 0.0;
    int neighbor_count = 0;

    // Use the same tie-inclusive neighbor set as the LRD stage.
    for (int neighbor = 0; neighbor < count; ++neighbor) {
        if (neighbor == target || distances[target * count + neighbor] > k_distances[target]) continue;
        const double ratio = isinf(lrd[target]) && isinf(lrd[neighbor])
            ? 1.0
            : lrd[neighbor] / lrd[target];
        ratio_sum += ratio;
        ++neighbor_count;
    }

    scores[target] = static_cast<float>(ratio_sum / neighbor_count);
}

} // namespace

// Upload four-feature orders, run the four dependent kernels, and download LOF scores.
std::vector<float> lof_calculator::calculate_cuda(const float* all_vectors, int num_vectors, int k) {
    if (k <= 0 || k >= num_vectors) {
        throw std::invalid_argument("LOF requires 1 <= k < num_vectors");
    }

    // All intermediate values remain in device memory between kernel launches.
    const size_t count = static_cast<size_t>(num_vectors);
    thrust::device_vector<float> vectors(all_vectors, all_vectors + count * 4);
    thrust::device_vector<float> distances(count * count);
    thrust::device_vector<float> k_distances(count);
    thrust::device_vector<double> lrd(count);
    thrust::device_vector<float> device_scores(count);

    const float* vector_ptr = thrust::raw_pointer_cast(vectors.data());
    float* distance_ptr = thrust::raw_pointer_cast(distances.data());
    float* k_distance_ptr = thrust::raw_pointer_cast(k_distances.data());
    double* lrd_ptr = thrust::raw_pointer_cast(lrd.data());
    float* score_ptr = thrust::raw_pointer_cast(device_scores.data());

    // Kernel order provides the cross-order synchronization needed by LOF.
    constexpr int block_size = 256;
    const int grid_size = (num_vectors + block_size - 1) / block_size;
    distance_kernel<<<grid_size, block_size>>>(vector_ptr, distance_ptr, num_vectors);
    check_cuda(cudaGetLastError());
    k_distance_kernel<<<grid_size, block_size>>>(distance_ptr, k_distance_ptr, num_vectors, k);
    check_cuda(cudaGetLastError());
    lrd_kernel<<<grid_size, block_size>>>(distance_ptr, k_distance_ptr, lrd_ptr, num_vectors);
    check_cuda(cudaGetLastError());
    lof_kernel<<<grid_size, block_size>>>(distance_ptr, k_distance_ptr, lrd_ptr, score_ptr, num_vectors);
    check_cuda(cudaGetLastError());

    // This copy waits for the default stream and returns scores in input order.
    std::vector<float> scores(num_vectors);
    check_cuda(cudaMemcpy(scores.data(), score_ptr, count * sizeof(float), cudaMemcpyDeviceToHost));
    return scores;
}
