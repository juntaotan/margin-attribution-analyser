#include <algorithm>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

#include "bom_upward_engine.cuh"


namespace {

template <typename T>
std::vector<T> read_binary(
    const std::filesystem::path& path
) {
    if (!std::filesystem::exists(path)) {
        throw std::runtime_error(
            "File not found: " + path.string()
        );
    }

    const auto file_size =
        std::filesystem::file_size(path);

    if (file_size % sizeof(T) != 0) {
        throw std::runtime_error(
            "Invalid binary file size: " + path.string()
        );
    }

    const auto count =
        static_cast<std::size_t>(
            file_size / sizeof(T)
        );

    std::vector<T> values(count);

    std::ifstream file(
        path,
        std::ios::binary
    );

    if (!file) {
        throw std::runtime_error(
            "Failed to open: " + path.string()
        );
    }

    if (file_size > 0) {
        file.read(
            reinterpret_cast<char*>(values.data()),
            static_cast<std::streamsize>(file_size)
        );

        if (!file) {
            throw std::runtime_error(
                "Failed to read: " + path.string()
            );
        }
    }

    return values;
}


BomUpwardGraph load_graph(
    const std::filesystem::path& directory
) {
    BomUpwardGraph graph;

    graph.offsets =
        read_binary<uint32_t>(
            directory / "offsets.bin"
        );

    graph.reverse_offsets =
        read_binary<uint32_t>(
            directory / "reverse_offsets.bin"
        );

    graph.reverse_successors =
        read_binary<uint32_t>(
            directory / "reverse_successors.bin"
        );

    graph.terminal_nodes =
        read_binary<uint32_t>(
            directory / "terminal_nodes.bin"
        );

    graph.node_values =
        read_binary<int64_t>(
            directory / "node_values.bin"
        );

    graph.node_comparable =
        read_binary<uint8_t>(
            directory / "node_comparable.bin"
        );

    if (graph.offsets.empty()) {
        throw std::runtime_error(
            "offsets.bin is empty"
        );
    }

    const std::size_t node_count =
        graph.offsets.size() - 1;

    if (
        graph.reverse_offsets.size()
        != node_count + 1
    ) {
        throw std::runtime_error(
            "reverse_offsets size mismatch"
        );
    }

    if (
        graph.node_values.size()
        != node_count
    ) {
        throw std::runtime_error(
            "node_values size mismatch"
        );
    }

    if (
        graph.node_comparable.size()
        != node_count
    ) {
        throw std::runtime_error(
            "node_comparable size mismatch"
        );
    }

    if (
        graph.reverse_offsets.back()
        != graph.reverse_successors.size()
    ) {
        throw std::runtime_error(
            "Reverse CSR edge count mismatch"
        );
    }

    return graph;
}

} // namespace


int main(
    int argc,
    char* argv[]
) {
    if (argc < 2 || argc > 4) {
        std::cerr
            << "Usage:\n"
            << "  cuda_benchmark "
            << "<dataset_directory> "
            << "[iterations] "
            << "[threshold]\n";

        return 1;
    }

    try {
        const std::filesystem::path dataset =
            argv[1];

        const int iterations =
            argc >= 3
                ? std::stoi(argv[2])
                : 10;

        const uint64_t threshold =
            argc >= 4
                ? std::stoull(argv[3])
                : 100000;

        if (iterations <= 0) {
            throw std::runtime_error(
                "iterations must be > 0"
            );
        }

        // ------------------------------------
        // Dataset loading — not timed
        // ------------------------------------

        auto graph = load_graph(dataset);

        const std::size_t node_count =
            graph.offsets.size() - 1;

        const std::size_t edge_count =
            graph.reverse_successors.size();

        std::cout
            << "CUDA BOM Upward Benchmark\n"
            << "=========================\n"
            << "Dataset:   "
            << dataset << '\n'
            << "Nodes:     "
            << node_count << '\n'
            << "Edges:     "
            << edge_count << '\n'
            << "Terminals: "
            << graph.terminal_nodes.size()
            << '\n'
            << "Threshold: "
            << threshold << '\n'
            << "Iterations:"
            << ' ' << iterations
            << "\n\n";

        CudaBomUpwardEngine engine;

        // ------------------------------------
        // Warm-up
        //
        // Excluded from benchmark because the
        // first CUDA invocation may include
        // context/runtime initialisation.
        // ------------------------------------

        std::cout << "Warm-up...\n";

        auto warmup_result =
            engine.run(
                graph,
                threshold
            );

        std::cout
            << "Warm-up result count: "
            << warmup_result.size()
            << "\n\n";

        // ------------------------------------
        // Benchmark
        // ------------------------------------

        std::vector<double> timings;
        timings.reserve(iterations);

        std::size_t result_count = 0;

        for (int i = 0; i < iterations; ++i) {

            const auto start =
                std::chrono::steady_clock::now();

            auto result =
                engine.run(
                    graph,
                    threshold
                );

            const auto end =
                std::chrono::steady_clock::now();

            const double elapsed_ms =
                std::chrono::duration<
                    double,
                    std::milli
                >(end - start).count();

            timings.push_back(elapsed_ms);
            result_count = result.size();

            std::cout
                << "Run "
                << std::setw(2)
                << (i + 1)
                << ": "
                << std::fixed
                << std::setprecision(3)
                << elapsed_ms
                << " ms\n";
        }

        // ------------------------------------
        // Statistics
        // ------------------------------------

        const double total =
            std::accumulate(
                timings.begin(),
                timings.end(),
                0.0
            );

        const double average =
            total / timings.size();

        const double minimum =
            *std::min_element(
                timings.begin(),
                timings.end()
            );

        const double maximum =
            *std::max_element(
                timings.begin(),
                timings.end()
            );

        std::cout
            << "\nResults\n"
            << "=======\n"
            << "Result count: "
            << result_count
            << '\n'
            << "Average:      "
            << std::fixed
            << std::setprecision(3)
            << average
            << " ms\n"
            << "Minimum:      "
            << minimum
            << " ms\n"
            << "Maximum:      "
            << maximum
            << " ms\n";

        return 0;
    }
    catch (const std::exception& e) {
        std::cerr
            << "Benchmark failed: "
            << e.what()
            << '\n';

        return 1;
    }
}