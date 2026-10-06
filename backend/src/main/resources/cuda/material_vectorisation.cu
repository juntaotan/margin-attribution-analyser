#include <onnxruntime_cxx_api.h>
#include <ortx_tokenizer.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef MATERIAL_MINILM_DIRECTORY
#define MATERIAL_MINILM_DIRECTORY "models/paraphrase-multilingual-MiniLM-L12-v2"
#endif

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
__host__ __device__ void normalise(float* vector) {
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

// Convert tokenizer errors into exceptions; status is the library's return code.
void check_tokenizer(extError_t status) {
    if (status != kOrtxOK) throw std::runtime_error(OrtxGetLastErrorMessage());
}

/**
 * Step 1: encode each current material description in CSR order, then normalise.
 * input contains the three input arrays; returns content[N][384], without
 * description deduplication or caching across analyses.
 * The fixed model is paraphrase-multilingual-MiniLM-L12-v2; local assets are supplied externally.
 * ids/mask/types are model inputs; hidden contains token embeddings.
 * Masked mean pooling produces the material vectors.
 * Processes data only: no CUDA memory management, thread configuration or kernel launches.
 */
std::vector<float> encode_materials(const Input& input) {
    const std::size_t n = input.materials.size();
    if (n > std::numeric_limits<uint32_t>::max() ||
        input.successors.size() > std::numeric_limits<uint32_t>::max() ||
        input.offsets.size() != n + 1 || input.offsets.front() != 0 ||
        input.offsets.back() != input.successors.size() ||
        !std::is_sorted(input.offsets.begin(), input.offsets.end()))
        throw std::invalid_argument("Invalid material CSR offsets");
    for (uint32_t node : input.successors)
        if (node >= n) throw std::invalid_argument("Invalid material CSR successor");
    if (n == 0) return {};

    std::vector<std::string> descriptions;
    for (const auto& material : input.materials) descriptions.push_back(describe(material));

    // Use the fixed local model with its original Unigram vocabulary, Unicode rules and special tokens.
    const std::string directory = MATERIAL_MINILM_DIRECTORY;
    std::ifstream file(directory + "/tokenizer.json", std::ios::binary);
    if (!file) throw std::runtime_error("Cannot open MiniLM tokenizer.json");
    const std::string vocabulary{std::istreambuf_iterator<char>(file), {}};
    const std::string config = R"({"tokenizer_class":"XLMRobertaTokenizer","bos_token":"<s>","eos_token":"</s>","unk_token":"<unk>","pad_token":"<pad>","add_bos_token":true,"add_eos_token":true,"model_max_length":128})";
    const OrtxTokenizerBlob blob(config, vocabulary);
    using TokenizerHandle = std::unique_ptr<OrtxObject, decltype(&OrtxDisposeOnly)>;
    OrtxTokenizer* raw_tokenizer = nullptr;
    auto status = OrtxCreateTokenizerFromBlob(&raw_tokenizer, &blob);
    TokenizerHandle tokenizer(raw_tokenizer, OrtxDisposeOnly);
    check_tokenizer(status);
    Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "material_minilm");
    Ort::Session model(environment, (directory + "/model.onnx").c_str(), Ort::SessionOptions{});

    constexpr std::size_t length = 128;
    const auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    std::vector<float> content(n * dimensions, 0.0f);
    for (std::size_t begin = 0; begin < n; begin += 32) {
        const std::size_t batch = std::min<std::size_t>(32, n - begin);
        std::vector<const char*> texts;
        for (std::size_t row = 0; row < batch; ++row) {
            if (descriptions[begin + row].find('\0') != std::string::npos)
                throw std::invalid_argument("Material text contains a null byte");
            texts.push_back(descriptions[begin + row].c_str());
        }
        OrtxTokenId2DArray* raw_tokens = nullptr;
        status = OrtxTokenize(tokenizer.get(), texts.data(), batch, &raw_tokens);
        TokenizerHandle tokens(raw_tokens, OrtxDisposeOnly);
        check_tokenizer(status);

        // Limit to 128 tokens, preserving boundary special tokens; padding=1 and mask excludes padding.
        std::vector<int64_t> ids(batch * length, 1), mask(ids.size(), 0), types(ids.size(), 0);
        for (std::size_t row = 0; row < batch; ++row) {
            const extTokenId_t* words = nullptr;
            std::size_t count = 0;
            check_tokenizer(OrtxTokenId2DArrayGetItem(tokens.get(), row, &words, &count));
            if (count < 2 || words[0] != 0 || words[count - 1] != 2)
                throw std::runtime_error("Unexpected MiniLM special tokens");
            count = std::min(count, length);
            for (std::size_t t = 0; t < count; ++t) {
                ids[row * length + t] = words[t];
                mask[row * length + t] = 1;
            }
            ids[row * length + count - 1] = 2;
        }
        const std::array<int64_t, 2> shape{static_cast<int64_t>(batch), length};
        std::array<Ort::Value, 3> values{
            Ort::Value::CreateTensor<int64_t>(memory, ids.data(), ids.size(), shape.data(), 2),
            Ort::Value::CreateTensor<int64_t>(memory, mask.data(), mask.size(), shape.data(), 2),
            Ort::Value::CreateTensor<int64_t>(memory, types.data(), types.size(), shape.data(), 2)};
        const char* names[] = {"input_ids", "attention_mask", "token_type_ids"};
        const char* output_names[] = {"last_hidden_state"};
        auto output = model.Run(Ort::RunOptions{nullptr}, names, values.data(), 3, output_names, 1);
        if (output[0].GetTensorTypeAndShapeInfo().GetShape() !=
            std::vector<int64_t>{static_cast<int64_t>(batch), length, dimensions})
            throw std::runtime_error("Unexpected MiniLM output shape");
        const float* hidden = output[0].GetTensorData<float>();
        for (std::size_t row = 0; row < batch; ++row) {
            std::array<double, dimensions> sum{};
            std::size_t count = 0;
            for (std::size_t t = 0; t < length; ++t) {
                if (!mask[row * length + t]) continue;
                ++count;
                for (std::size_t d = 0; d < dimensions; ++d)
                    sum[d] += hidden[(row * length + t) * dimensions + d];
            }
            float* vector = content.data() + (begin + row) * dimensions;
            for (std::size_t d = 0; d < dimensions; ++d) {
                vector[d] = static_cast<float>(sum[d] / count);
                if (!std::isfinite(vector[d])) throw std::runtime_error("Non-finite MiniLM output");
            }
            normalise(vector);
        }
    }
    return content;
}

/**
 * Steps 2-4: collect and aggregate two forward neighbourhoods into source's three vector blocks.
 * offsets[N+1]/successors[M] form a valid CSR; content[N][384] is normalised.
 * seen[N] is exclusive scratch space for this call: bit 0 marks one hop, bit 1 marks two hops.
 * output[N][3][384] stores self, one-hop and two-hop vectors; only the source row is modified.
 * Inputs, seen and output must not overlap; the caller owns array placement and lifetime.
 */
__host__ __device__ void aggregate_material(
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
