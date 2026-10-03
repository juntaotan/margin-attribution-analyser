#include "lof_engine.cuh"

#include <cuda_runtime.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <vector>

// Verify seven known scores, then time one global 14,000-order CUDA call.
int main() {
    constexpr int reference_count = 7;
    constexpr int dimensions = 4;
    constexpr int order_count = 14000;
    constexpr int normal_count = order_count - 1;
    constexpr int k = 2;

    // A-G provide the fixed seven-point reference case.
    const std::array<float, reference_count * dimensions> base_orders = {
        7.0f, 2.0f, 1.0f, 1.0f,  // A
        7.1f, 1.8f, 1.1f, 0.9f,  // B
        6.9f, 2.2f, 0.9f, 1.1f,  // C
        0.1f, 2.0f, 1.0f, 1.0f,  // D
        0.2f, 1.8f, 1.1f, 0.9f,  // E
        0.0f, 2.2f, 0.9f, 1.1f,  // F
        0.5f, 0.5f, 0.5f, 0.5f   // G
    };
    const std::array<float, reference_count> expected = {
        1.333333333f, 0.875f, 0.875f, 1.333333333f,
        0.875f, 0.875f, 3.552414237f
    };
    const std::array<const char*, reference_count> names = {"A", "B", "C", "D", "E", "F", "G"};

    // Expand A-F into nearby distinct points; include G only once.
    std::vector<float> orders(order_count * dimensions);
    for (int index = 0; index < normal_count; ++index) {
        const int prototype = index % 6;
        const int copy = index / 6;
        for (int feature = 0; feature < dimensions; ++feature) {
            orders[index * dimensions + feature] = base_orders[prototype * dimensions + feature];
        }
        orders[index * dimensions] += copy * 1e-5f;
    }
    for (int feature = 0; feature < dimensions; ++feature) {
        orders[normal_count * dimensions + feature] = base_orders[6 * dimensions + feature];
    }

    // The batch run requires a real CUDA device.
    int device_count = 0;
    const cudaError_t device_error = cudaGetDeviceCount(&device_count);
    if (device_error != cudaSuccess || device_count == 0) {
        std::cerr << "GPU unavailable: "
                  << (device_error == cudaSuccess ? "no CUDA device" : cudaGetErrorString(device_error))
                  << '\n';
        return 2;
    }

    // Verify known seven-point values on the same CUDA path.
    const auto reference_scores = lof_calculator::calculate_cuda(base_orders.data(), reference_count, k);
    for (int point = 0; point < reference_count; ++point) {
        if (!std::isfinite(reference_scores[point]) ||
            std::fabs(reference_scores[point] - expected[point]) > 1e-3f) return 1;
    }
    std::cout << "CUDA LOF seven-point reference: PASS\n";

    // Warm the same global dataset so runtime setup is outside the timing.
    std::cout << "CUDA LOF: warming up " << order_count << " global points..." << std::endl;
    lof_calculator::calculate_cuda(orders.data(), order_count, k);

    // Time one complete call, including buffers, transfers, kernels, and readback.
    std::cout << "CUDA LOF: processing " << order_count << " global points in one call..." << std::endl;
    const auto start = std::chrono::steady_clock::now();
    const auto scores = lof_calculator::calculate_cuda(orders.data(), order_count, k);
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();

    // Check all scores and compare the sole G with every normal point.
    int failures = 0;
    float max_normal = 0.0f;
    std::cout << std::fixed << std::setprecision(6);
    for (int index = 0; index < normal_count; ++index) {
        if (!std::isfinite(scores[index]) || scores[index] <= 0.0f) ++failures;
        max_normal = std::max(max_normal, scores[index]);
    }
    const float anomaly = scores[normal_count];
    if (!std::isfinite(anomaly) || anomaly <= max_normal) ++failures;

    // Print representative A-F scores and the single G score.
    for (int point = 0; point < 6; ++point) {
        std::cout << names[point] << "  LOF=" << scores[point] << '\n';
    }
    std::cout << "G  LOF=" << anomaly << "  max normal=" << max_normal
              << "  " << (failures == 0 ? "PASS" : "FAIL") << '\n';

    std::cout << "CUDA LOF global batch: " << order_count << " points, 1 call, "
              << seconds << " s, " << seconds * 1000.0 << " ms, "
              << order_count / seconds << " points/s\n"
              << "Checked: " << order_count << ", failures: " << failures << '\n';
    return failures == 0 ? 0 : 1;
}
