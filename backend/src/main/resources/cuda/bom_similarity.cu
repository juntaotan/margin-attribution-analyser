#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bom_similarity {

constexpr std::size_t dimensions = 384;
constexpr std::size_t embedding_blocks = 6;
constexpr std::size_t embedding_width = embedding_blocks * dimensions;
constexpr std::size_t numeric_width = 5;
constexpr std::size_t detail_width = embedding_blocks + numeric_width;

// Suggested values: layer = {0.5, 0.3, 0.2}, amount_share = 0.5,
// semantic_share = 0.8, numeric = {1, 1, 1, 1, 1}. Weights are supplied by
// the caller so the same scoring policy can be used for both kernels.
struct SimilarityWeights {
    float layer[3];
    float amount_share;
    float semantic_share;
    float numeric[numeric_width];
};

__device__ float clamp01(float value) {
    return fminf(1.0f, fmaxf(0.0f, value));
}

// Returns -1 when both blocks are absent, 0 when only one is absent, and a
// 0..1 mapped cosine similarity otherwise. BOM vector blocks are already L2
// normalised, but their norms are checked to handle empty input blocks.
__device__ float block_similarity(const float* query, const float* candidate) {
    double dot = 0.0;
    double query_norm = 0.0;
    double candidate_norm = 0.0;
    for (std::size_t d = 0; d < dimensions; ++d) {
        const double left = query[d];
        const double right = candidate[d];
        dot += left * right;
        query_norm += left * left;
        candidate_norm += right * right;
    }
    if (query_norm == 0.0 && candidate_norm == 0.0) return -1.0f;
    if (query_norm == 0.0 || candidate_norm == 0.0) return 0.0f;
    const double cosine = dot / sqrt(query_norm * candidate_norm);
    return clamp01(static_cast<float>((cosine + 1.0) * 0.5));
}

__device__ float ratio_similarity(double query, double candidate) {
    if (query == 0.0 && candidate == 0.0) return 1.0f;
    if (query <= 0.0 || candidate <= 0.0) return 0.0f;
    return clamp01(static_cast<float>(fmin(query, candidate) / fmax(query, candidate)));
}

__device__ float concentration_similarity(double query, double candidate) {
    return clamp01(static_cast<float>(1.0 - fabs(query - candidate)));
}

/*
 * Coarse comparison for every candidate BOM vector against one query.
 * embedding[N][6][384] uses blocks 0..2 for amount and 3..5 for quantity.
 * Only the two self blocks (0 and 3) participate in this stage.
 * coarse_scores[N] is in [0, 1], where larger means more similar. Set
 * excluded_candidate to UINT32_MAX when the query is not among candidates;
 * otherwise that candidate receives -1 so it cannot select itself.
 * A block absent on both sides contributes no weight; a block absent on only
 * one side contributes zero similarity. The caller selects the top K scores.
 */
__global__ void compare_bom_coarse(
    uint32_t candidate_count,
    uint32_t excluded_candidate,
    const float* query_embedding,
    const float* embeddings,
    SimilarityWeights weights,
    float* coarse_scores) {
    const std::size_t candidate_index =
        static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (candidate_index >= candidate_count) return;
    if (candidate_index == excluded_candidate) {
        coarse_scores[candidate_index] = -1.0f;
        return;
    }
    const float* candidate = embeddings + candidate_index * embedding_width;

    const float amount_similarity = block_similarity(query_embedding, candidate);
    const float quantity_similarity = block_similarity(
        query_embedding + 3 * dimensions, candidate + 3 * dimensions);
    const float amount_weight = clamp01(weights.amount_share);
    const float quantity_weight = 1.0f - amount_weight;
    float weighted_score = 0.0f;
    float available_weight = 0.0f;
    if (amount_similarity >= 0.0f) {
        weighted_score += amount_weight * amount_similarity;
        available_weight += amount_weight;
    }
    if (quantity_similarity >= 0.0f) {
        weighted_score += quantity_weight * quantity_similarity;
        available_weight += quantity_weight;
    }
    coarse_scores[candidate_index] = available_weight > 0.0f
        ? weighted_score / available_weight : 0.0f;
}

/*
 * Refined comparison for shortlist_indices[K] selected from coarse_scores.
 * query_numeric and numerics[N][5] contain amount, quantity, edge count,
 * amount HHI and quantity HHI in that order.
 *
 * fine_scores[K] is in [0, 1]. details[K][11] receives six block similarities
 * and five numeric similarities; -1 marks a block absent on both sides.
 * This kernel computes reranking scores. The caller sorts K results by score.
 */
__global__ void compare_bom_fine(
    uint32_t shortlist_count,
    const uint32_t* shortlist_indices,
    const float* query_embedding,
    const double* query_numeric,
    const float* embeddings,
    const double* numerics,
    SimilarityWeights weights,
    float* fine_scores,
    float* details) {
    const std::size_t shortlist_position =
        static_cast<std::size_t>(blockIdx.x) * blockDim.x + threadIdx.x;
    if (shortlist_position >= shortlist_count) return;
    const uint32_t candidate_index = shortlist_indices[shortlist_position];
    const float* candidate = embeddings
        + static_cast<std::size_t>(candidate_index) * embedding_width;
    const double* candidate_numeric = numerics
        + static_cast<std::size_t>(candidate_index) * numeric_width;
    float* row_details = details + shortlist_position * detail_width;

    float semantic_sum = 0.0f;
    float semantic_weight_sum = 0.0f;
    const float amount_share = clamp01(weights.amount_share);
    for (std::size_t block = 0; block < embedding_blocks; ++block) {
        const float similarity = block_similarity(
            query_embedding + block * dimensions,
            candidate + block * dimensions);
        row_details[block] = similarity;
        if (similarity < 0.0f) continue;
        const float layer_weight = weights.layer[block % 3];
        const float channel_weight = block < 3 ? amount_share : 1.0f - amount_share;
        const float weight = layer_weight * channel_weight;
        semantic_sum += weight * similarity;
        semantic_weight_sum += weight;
    }
    const float semantic_score = semantic_weight_sum > 0.0f
        ? semantic_sum / semantic_weight_sum : 0.0f;

    row_details[6] = ratio_similarity(query_numeric[0], candidate_numeric[0]);
    row_details[7] = ratio_similarity(query_numeric[1], candidate_numeric[1]);
    row_details[8] = ratio_similarity(query_numeric[2], candidate_numeric[2]);
    row_details[9] = concentration_similarity(query_numeric[3], candidate_numeric[3]);
    row_details[10] = concentration_similarity(query_numeric[4], candidate_numeric[4]);
    float numeric_sum = 0.0f;
    float numeric_weight_sum = 0.0f;
    for (std::size_t feature = 0; feature < numeric_width; ++feature) {
        numeric_sum += weights.numeric[feature] * row_details[embedding_blocks + feature];
        numeric_weight_sum += weights.numeric[feature];
    }
    const float numeric_score = numeric_weight_sum > 0.0f
        ? numeric_sum / numeric_weight_sum : 0.0f;
    const float semantic_share = clamp01(weights.semantic_share);
    fine_scores[shortlist_position] = clamp01(
        semantic_share * semantic_score + (1.0f - semantic_share) * numeric_score);
}

} // namespace bom_similarity
