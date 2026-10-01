#include "benchmark_graph_loader.hpp"
#include "benchmark_output.hpp"
#include "bom_upward_engine.hpp"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

BomUpwardGraph opencl_graph(BenchmarkGraph graph) {
    return {std::move(graph.offsets), std::move(graph.reverse_offsets),
            std::move(graph.reverse_successors), std::move(graph.terminal_nodes),
            std::move(graph.node_values), std::move(graph.node_comparable)};
}

std::string kernel_source() {
    std::ifstream file(BOM_UPWARD_KERNEL_PATH);
    if (!file) throw std::runtime_error("Failed to open OpenCL kernel");
    std::ostringstream source;
    source << file.rdbuf();
    return source.str();
}

cl::Device select_device() {
    std::vector<cl::Platform> platforms;
    cl::Platform::get(&platforms);
    for (const auto& platform : platforms) {
        std::vector<cl::Device> devices;
        if (platform.getDevices(CL_DEVICE_TYPE_GPU, &devices) == CL_SUCCESS && !devices.empty()) {
            return devices.front();
        }
    }
    for (const auto& platform : platforms) {
        std::vector<cl::Device> devices;
        if (platform.getDevices(CL_DEVICE_TYPE_ALL, &devices) == CL_SUCCESS && !devices.empty()) {
            return devices.front();
        }
    }
    throw std::runtime_error("No OpenCL device found");
}

} // namespace

/** Runs the OpenCL backend repeatedly and emits one JSON result line. */
int main(int argc, char* argv[]) {
    if (argc != 5) {
        std::cerr << "Usage: opencl_benchmark <dataset> <iterations> <threshold> <warmups>\n";
        return 1;
    }

    try {
        const std::filesystem::path dataset = argv[1];
        const int iterations = std::stoi(argv[2]);
        const cl_ulong threshold = std::stoull(argv[3]);
        const int warmups = std::stoi(argv[4]);

        const cl::Device device = select_device();
        const cl::Context context({device});
        const cl::CommandQueue queue(context, device);
        cl::Program program(context, kernel_source());
        if (program.build({device}) != CL_SUCCESS) {
            throw std::runtime_error(program.getBuildInfo<CL_PROGRAM_BUILD_LOG>(device));
        }

        const auto load_start = std::chrono::steady_clock::now();
        BomUpwardGraph graph = opencl_graph(BenchmarkGraphLoader::load(dataset));
        const auto load_end = std::chrono::steady_clock::now();
        const double load_ms = std::chrono::duration<double, std::milli>(
                load_end - load_start).count();

        BomUpwardEngine engine(context, queue, program);
        for (int warmup = 0; warmup < warmups; ++warmup) {
            engine.run(graph, threshold);
        }

        std::vector<BenchmarkSample> samples;
        samples.reserve(iterations);
        std::vector<cl_uint> result;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            BomUpwardTimings timing;
            result = engine.run(graph, threshold, &timing);
            samples.push_back({timing.input_ms, timing.compute_ms,
                    timing.output_ms, timing.total_ms});
        }

        print_benchmark_json("OpenCL", device.getInfo<CL_DEVICE_NAME>(), load_ms,
                graph.node_values.size(), graph.reverse_successors.size(),
                graph.terminal_nodes.size(), threshold, samples, signature(result));
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "OpenCL benchmark failed: " << exception.what() << '\n';
        return 1;
    }
}
