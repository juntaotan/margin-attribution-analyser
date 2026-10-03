#include "lof_engine.cuh"

#include <cuda_runtime.h>
#include <array>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <vector>

// Check one GPU result, then time 200 complete engine calls.
int main() {
    constexpr int order_count = 7;
    constexpr int dimension = 4;
    constexpr int k = 2;
    constexpr int stress_calls = 200;

    // Use the same A-G orders as the LOF logic test.
    const std::array<float, order_count * dimension> orders = {
        7.0f, 2.0f, 1.0f, 1.0f,  // A
        7.1f, 1.8f, 1.1f, 0.9f,  // B
        6.9f, 2.2f, 0.9f, 1.1f,  // C
        0.1f, 2.0f, 1.0f, 1.0f,  // D
        0.2f, 1.8f, 1.1f, 0.9f,  // E
        0.0f, 2.2f, 0.9f, 1.1f,  // F
        0.5f, 0.5f, 0.5f, 0.5f   // G
    };

    // These LOF values are calculated independently for k = 2.
    const std::array<float, order_count> expected = {
        1.333333333f, 0.875f, 0.875f, 1.333333333f,
        0.875f, 0.875f, 3.552414237f
    };
    const std::array<const char*, order_count> names = {"A", "B", "C", "D", "E", "F", "G"};

    // A pressure run requires a real CUDA device.
    int device_count = 0;
    const cudaError_t device_error = cudaGetDeviceCount(&device_count);
    if (device_error != cudaSuccess || device_count == 0) {
        std::cerr << "GPU unavailable: "
                  << (device_error == cudaSuccess ? "no CUDA device" : cudaGetErrorString(device_error))
                  << '\n';
        return 2;
    }

    // The first call checks the engine output and initializes CUDA before timing.
    const auto first_scores = lof_calculator::calculate_cuda(orders.data(), order_count, k);
    bool correct = true;
    std::cout << std::fixed << std::setprecision(6);
    std::cout << "One-call correctness:\nPoint  LOF       Expected  Result\n";

    // Verify and print each point's LOF from the first call.
    for (int index = 0; index < order_count; ++index) {
        const bool point_correct = std::fabs(first_scores[index] - expected[index]) < 1e-4f;
        correct &= point_correct;
        std::cout << names[index] << "      " << first_scores[index] << "  "
                  << expected[index] << "  " << (point_correct ? "PASS" : "FAIL") << '\n';
    }
    std::cout << "Correctness: " << (correct ? "PASS" : "FAIL") << '\n';
    if (!correct) return 1;

    // Measure complete calls, including device allocation, transfers, kernels, and result copy.
    std::vector<float> final_scores;
    const auto start = std::chrono::steady_clock::now();

    // Send the same seven-order batch to the engine 200 times.
    for (int call = 0; call < stress_calls; ++call) {
        final_scores = lof_calculator::calculate_cuda(orders.data(), order_count, k);
    }

    const auto stop = std::chrono::steady_clock::now();
    const double seconds = std::chrono::duration<double>(stop - start).count();

    // Confirm the last timed call still returned all seven expected scores.
    bool final_correct = true;
    for (int index = 0; index < order_count; ++index) {
        final_correct &= std::fabs(final_scores[index] - expected[index]) < 1e-4f;
    }
    std::cout << "Stress calls: " << stress_calls << '\n'
              << "Total time: " << seconds << " s\n"
              << "Average: " << seconds * 1000.0 / stress_calls << " ms/call\n"
              << "Throughput: " << stress_calls / seconds << " calls/s\n"
              << "Final result: " << (final_correct ? "PASS" : "FAIL") << '\n';
    return final_correct ? 0 : 1;
}
