#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

namespace material_vectorisation {

constexpr std::size_t dimensions = 384;
constexpr std::size_t vector_width = 3 * dimensions;

struct MaterialInfo {
    std::string name, specification, category;
    std::vector<std::string> suppliers, upstreams, downstreams;
};

// All three inputs share one snapshot; materials[i] corresponds to CSR node i.
// Edges point to the products that consume the material.
struct Input {
    std::vector<uint32_t> offsets, successors;
    std::vector<MaterialInfo> materials;
};

// L2-normalise vector[384] in place; norm is its L2 norm. Zero vectors stay zero.
__device__ void normalise(float* vector) {
    double norm = 0;
    for (std::size_t d = 0; d < dimensions; ++d)
        norm += static_cast<double>(vector[d]) * vector[d];
    if (norm == 0) return;
    norm = sqrt(norm);
    for (std::size_t d = 0; d < dimensions; ++d)
        vector[d] = static_cast<float>(vector[d] / norm);
}

// Combine material's six property groups into text.
// field/list preserve field and list boundaries without deduplication.
std::string describe(const MaterialInfo& material) {
    const auto field = [](const std::string& value) {
        return std::to_string(value.size()) + ":" + value;
    };
    const auto list = [&](const std::vector<std::string>& values) {
        std::string text = "[";
        for (const auto& value : values) text += field(value) + ";";
        return text + "]";
    };
    return "Name: " + field(material.name)
        + "\nSpecification: " + field(material.specification)
        + "\nCategory: " + field(material.category)
        + "\nSuppliers: " + list(material.suppliers)
        + "\nUpstreams: " + list(material.upstreams)
        + "\nDownstreams: " + list(material.downstreams);
}

/**
 * Produce one material's content vector by masked mean pooling and L2 normalisation.
 * External preprocessing uses describe(materials[i]) with all six property groups.
 * External GPU inference uses paraphrase-multilingual-MiniLM-L12-v2 in CSR order.
 * hidden[N][length][384] is its float32 token output; mask[N][length] is int64.
 * source is the material index; n is N; length includes special and padding tokens.
 * content[N][384] receives the result; only the source row is modified.
 * All pointers refer to device memory; inputs and output must not overlap.
 * The caller owns inference, memory lifetime, synchronisation and kernel launches.
 * Run after inference completes and finish all content rows before aggregation.
 * Empty masks and zero-norm results produce zero vectors.
 */
__device__ void encode_materials(
    uint32_t source, 
    uint32_t n, 
    uint32_t length,
    const float* hidden,
    const int64_t* mask,
    float* content
) {
    if (source >= n) return;
    
    const std::size_t token_offset = static_cast<std::size_t>(source) * length;
    float* vector = content + static_cast<std::size_t>(source) * dimensions;
    uint32_t count = 0;
    for (uint32_t t = 0; t < length; ++t)
        if (mask[token_offset + t]) ++count;
    for (std::size_t d = 0; d < dimensions; ++d) {
        double sum = 0;
        for (uint32_t t = 0; t < length; ++t) {
            if (mask[token_offset + t])
                sum += hidden[(token_offset + t) * dimensions + d];
        }
        vector[d] = count ? static_cast<float>(sum / count) : 0.0f;
    }
    normalise(vector);
}

/**
 * Steps 2-4: collect and aggregate two forward neighbourhoods into source's three vector blocks.
 * offsets[N+1]/successors[M] form a valid CSR; content[N][384] is normalised.
 * seen[N] is exclusive scratch space for this call: bit 0 marks one hop, bit 1 marks two hops.
 * output[N][3][384] stores self, one-hop and two-hop vectors; only the source row is modified.
 * Inputs, seen and output must not overlap; the caller owns array placement and lifetime.
 */
__device__ void aggregate_material(
    uint32_t source, uint32_t n, const uint32_t* offsets, const uint32_t* successors,
    const float* content, uint8_t* seen, float* output) {
    if (source >= n) return;
    float* own = output + static_cast<std::size_t>(source) * vector_width;
    float* one = own + dimensions;
    float* two = one + dimensions;
    for (std::size_t d = 0; d < dimensions; ++d) {
        own[d] = content[static_cast<std::size_t>(source) * dimensions + d];
        one[d] = two[d] = 0;
    }
    for (uint32_t node = 0; node < n; ++node) seen[node] = 0;
    uint32_t one_count = 0, two_count = 0;
    for (uint32_t edge = offsets[source]; edge < offsets[source + 1]; ++edge) {
        const uint32_t neighbour = successors[edge];
        if (seen[neighbour] & 1) continue;
        seen[neighbour] |= 1;
        if (neighbour != source) {
            for (std::size_t d = 0; d < dimensions; ++d)
                one[d] += content[static_cast<std::size_t>(neighbour) * dimensions + d];
            ++one_count;
        }
        // Exactly two edges, not shortest-path distance; deduplicate each depth and exclude the source as an endpoint.
        for (uint32_t next = offsets[neighbour]; next < offsets[neighbour + 1]; ++next) {
            const uint32_t endpoint = successors[next];
            if (endpoint == source || (seen[endpoint] & 2)) continue;
            seen[endpoint] |= 2;
            for (std::size_t d = 0; d < dimensions; ++d)
                two[d] += content[static_cast<std::size_t>(endpoint) * dimensions + d];
            ++two_count;
        }
    }
    for (std::size_t d = 0; d < dimensions; ++d) {
        if (one_count) one[d] /= one_count;
        if (two_count) two[d] /= two_count;
    }
    normalise(one);
    normalise(two);
}

} // namespace material_vectorisation
