#include "lof_engine.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <iterator>
#include <vector>

// Prefer a GPU while accepting any working OpenCL device.
bool select_device(cl::Device& selected) {
    std::vector<cl::Platform> platforms;
    if (cl::Platform::get(&platforms) != CL_SUCCESS) return false;

    // Search GPUs first, then all other OpenCL devices.
    for (const cl_device_type type : std::array<cl_device_type, 2>{CL_DEVICE_TYPE_GPU, CL_DEVICE_TYPE_ALL}) {
        for (const auto& platform : platforms) {
            std::vector<cl::Device> devices;
            if (platform.getDevices(type, &devices) == CL_SUCCESS && !devices.empty()) {
                selected = devices.front();
                return true;
            }
        }
    }
    return false;
}

// Verify seven known scores, then time one global 14,000-order OpenCL call.
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

    // Build one program and an in-order queue before the timed call.
    cl::Device device;
    if (!select_device(device)) {
        std::cerr << "OpenCL unavailable: no device found\n";
        return 2;
    }
    cl_int error = CL_SUCCESS;
    const cl::Context context({device}, nullptr, nullptr, nullptr, &error);
    if (error != CL_SUCCESS) return 2;
    const cl::CommandQueue queue(context, device, 0, &error);
    if (error != CL_SUCCESS) return 2;

    std::ifstream kernel_file(LOF_KERNEL_PATH);
    if (!kernel_file) {
        std::cerr << "Cannot open LOF kernel source\n";
        return 1;
    }
    const std::string source(std::istreambuf_iterator<char>{kernel_file}, {});
    cl::Program program(context, source);
    std::cout << "OpenCL LOF: building kernels..." << std::endl;
    if (program.build({device}) != CL_SUCCESS) {
        std::cerr << program.getBuildInfo<CL_PROGRAM_BUILD_LOG>(device) << '\n';
        return 1;
    }
    OpenClLofEngine engine(context, queue, program);

    // Verify known seven-point values on the same OpenCL path.
    const auto reference_scores = engine.run(base_orders.data(), reference_count, k);
    for (int point = 0; point < reference_count; ++point) {
        if (!std::isfinite(reference_scores[point]) ||
            std::fabs(reference_scores[point] - expected[point]) > 1e-3f) return 1;
    }
    std::cout << "OpenCL LOF seven-point reference: PASS\n";

    // Warm the same global dataset so runtime setup is outside the timing.
    std::cout << "OpenCL LOF: warming up " << order_count << " global points..." << std::endl;
    engine.run(orders.data(), order_count, k);

    // Time one complete call, including buffers, transfers, kernels, and readback.
    std::cout << "OpenCL LOF: processing " << order_count << " global points in one call..." << std::endl;
    const auto start = std::chrono::steady_clock::now();
    const auto scores = engine.run(orders.data(), order_count, k);
    const double seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();

    // Check all scores and compare the sole G with every normal point.
    int failures = 0;
    float max_normal = 0.0f;
    std::cout << std::fixed << std::setprecision(6);
    std::cout << "OpenCL device: " << device.getInfo<CL_DEVICE_NAME>() << '\n';
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

    std::cout << "OpenCL LOF global batch: " << order_count << " points, 1 call, "
              << seconds << " s, " << seconds * 1000.0 << " ms, "
              << order_count / seconds << " points/s\n"
              << "Checked: " << order_count << ", failures: " << failures << '\n';
    return failures == 0 ? 0 : 1;
}
