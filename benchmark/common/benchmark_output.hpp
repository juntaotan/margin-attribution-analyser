#pragma once

#include <cstdint>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

/** Shared timing sample emitted by native benchmark runners. */
struct BenchmarkSample {
    double input_ms{};
    double compute_ms{};
    double output_ms{};
    double total_ms{};
};

/** Order-independent result signature used to compare all three backends. */
struct ResultSignature {
    std::size_t count{};
    uint64_t sum{};
    uint64_t xor_value{};
};

inline uint64_t mix_node(uint64_t value) {
    value += 0x9E3779B97F4A7C15ULL;
    value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9ULL;
    value = (value ^ (value >> 27)) * 0x94D049BB133111EBULL;
    return value ^ (value >> 31);
}

inline ResultSignature signature(const std::vector<uint32_t>& nodes) {
    ResultSignature result{nodes.size(), 0, 0};
    for (uint32_t node : nodes) {
        const uint64_t mixed = mix_node(node);
        result.sum += mixed;
        result.xor_value ^= mixed;
    }
    return result;
}

inline std::string json_escape(const std::string& value) {
    std::ostringstream escaped;
    for (char character : value) {
        if (character == '\\' || character == '"') escaped << '\\';
        escaped << character;
    }
    return escaped.str();
}

inline void print_number_array(const std::vector<BenchmarkSample>& samples,
                               double BenchmarkSample::* field) {
    std::cout << '[';
    for (std::size_t index = 0; index < samples.size(); ++index) {
        if (index != 0) std::cout << ',';
        std::cout << std::fixed << std::setprecision(6) << samples[index].*field;
    }
    std::cout << ']';
}

/** Emits one machine-readable line consumed by the unified report runner. */
inline void print_benchmark_json(const std::string& backend, const std::string& device,
                                 double load_ms, std::size_t nodes, std::size_t edges,
                                 std::size_t terminals, uint64_t threshold,
                                 const std::vector<BenchmarkSample>& samples,
                                 const ResultSignature& result) {
    std::cout << "BENCHMARK_JSON {\"backend\":\"" << json_escape(backend)
              << "\",\"device\":\"" << json_escape(device)
              << "\",\"load_ms\":" << std::fixed << std::setprecision(6) << load_ms
              << ",\"nodes\":" << nodes << ",\"edges\":" << edges
              << ",\"terminals\":" << terminals << ",\"threshold\":" << threshold
              << ",\"input_ms\":";
    print_number_array(samples, &BenchmarkSample::input_ms);
    std::cout << ",\"compute_ms\":";
    print_number_array(samples, &BenchmarkSample::compute_ms);
    std::cout << ",\"output_ms\":";
    print_number_array(samples, &BenchmarkSample::output_ms);
    std::cout << ",\"total_ms\":";
    print_number_array(samples, &BenchmarkSample::total_ms);
    std::cout << ",\"result_count\":" << result.count
              << ",\"result_sum\":\"" << result.sum
              << "\",\"result_xor\":\"" << result.xor_value << "\"}\n";
}
