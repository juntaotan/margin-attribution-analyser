#include "benchmark_graph_loader.hpp"
#include "benchmark_output.hpp"
#include "bom_upward_engine.cuh"

#include <cuda_runtime.h>

#include <chrono>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

BomUpwardGraph cuda_graph(BenchmarkGraph graph) {
    return {std::move(graph.offsets), std::move(graph.reverse_offsets),
            std::move(graph.reverse_successors), std::move(graph.terminal_nodes),
            std::move(graph.node_values), std::move(graph.node_comparable)};
}

std::string device_name() {
    cudaDeviceProp properties{};
    if (const auto error = cudaGetDeviceProperties(&properties, 0); error != cudaSuccess) {
        throw std::runtime_error(cudaGetErrorString(error));
    }
    return properties.name;
}

} // namespace

/** Runs the CUDA backend repeatedly and emits one JSON result line. */
int main(int argc, char* argv[]) {
    if (argc != 5) {
        std::cerr << "Usage: cuda_benchmark <dataset> <iterations> <threshold> <warmups>\n";
        return 1;
    }

    try {
        const std::filesystem::path dataset = argv[1];
        const int iterations = std::stoi(argv[2]);
        const uint64_t threshold = std::stoull(argv[3]);
        const int warmups = std::stoi(argv[4]);

        const auto load_start = std::chrono::steady_clock::now();
        BomUpwardGraph graph = cuda_graph(BenchmarkGraphLoader::load(dataset));
        const auto load_end = std::chrono::steady_clock::now();
        const double load_ms = std::chrono::duration<double, std::milli>(
                load_end - load_start).count();

        CudaBomUpwardEngine engine;
        for (int warmup = 0; warmup < warmups; ++warmup) {
            engine.run(graph, threshold);
        }

        std::vector<BenchmarkSample> samples;
        samples.reserve(iterations);
        std::vector<uint32_t> result;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            CudaBomUpwardTimings timing;
            result = engine.run(graph, threshold, &timing);
            samples.push_back({timing.input_ms, timing.compute_ms,
                    timing.output_ms, timing.total_ms});
        }

        print_benchmark_json("CUDA", device_name(), load_ms,
                graph.node_values.size(), graph.reverse_successors.size(),
                graph.terminal_nodes.size(), threshold, samples, signature(result));
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "CUDA benchmark failed: " << exception.what() << '\n';
        return 1;
    }
}
