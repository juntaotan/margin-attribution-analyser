#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bom_vectorisation {

constexpr std::size_t dimensions = 384;
constexpr std::size_t material_blocks = 3;
constexpr std::size_t output_blocks = 2 * material_blocks;
constexpr std::size_t output_width = output_blocks * dimensions;
constexpr std::size_t numeric_width = 5;

// L2-normalise one 384-dimensional block. A zero block remains zero.
__device__ void normalise(float* vector) {
    double squared_norm = 0.0;
    for (std::size_t d = 0; d < dimensions; ++d)
        squared_norm += static_cast<double>(vector[d]) * vector[d];
    if (squared_norm == 0.0) return;
    const double inverse_norm = 1.0 / sqrt(squared_norm);
    for (std::size_t d = 0; d < dimensions; ++d)
        vector[d] = static_cast<float>(vector[d] * inverse_norm);
}

/*
 * Vectorise one product-period row of a product -> material CSR.
 *
 * offsets[P+1] and material_ids[E] describe the CSR. quantity[E] and
 * amount[E] are the actual quantity and monetary amount on each edge.
 * material_vectors[M][3][384] contains the completed material embeddings:
 * self, one-hop and two-hop. All arrays use the same material index space.
 *
 * embedding[P][6][384] receives three amount-weighted blocks followed by
 * three quantity-weighted blocks. Each nonzero block is L2-normalised.
 * numeric[P][5] receives total amount, total quantity, edge count,
 * amount HHI and quantity HHI. Empty rows produce zeros.
 *
 * All pointers refer to device memory. The caller assigns one product row
 * to each invocation and owns kernel launches, memory and synchronisation.
 */
__device__ void vectorise_bom_row(
    uint32_t source,
    uint32_t product_count,
    const uint32_t* offsets,
    const uint32_t* material_ids,
    const double* quantity,
    const double* amount,
    const float* material_vectors,
    float* embedding,
    double* numeric
) {
    if (source >= product_count) return;

    const uint32_t first = offsets[source];
    const uint32_t last = offsets[source + 1];

    double amount_total = 0.0;
    double quantity_total = 0.0;
    double amount_squares = 0.0;
    double quantity_squares = 0.0;

    for (uint32_t edge = first; edge < last; ++edge) {
        const double a = amount[edge];
        const double q = quantity[edge];
        amount_total += a;
        quantity_total += q;
        amount_squares += a * a;
        quantity_squares += q * q;
    }

    float* row = embedding + static_cast<std::size_t>(source) * output_width;

    for (std::size_t block = 0; block < material_blocks; ++block) {
        float* amount_vector = row + block * dimensions;
        float* quantity_vector = row + (material_blocks + block) * dimensions;
        for (std::size_t d = 0; d < dimensions; ++d) {
            double amount_sum = 0.0;
            double quantity_sum = 0.0;
            for (uint32_t edge = first; edge < last; ++edge) {
                const std::size_t material_offset =
                    static_cast<std::size_t>(material_ids[edge]) * material_blocks * dimensions;
                const double value = material_vectors[material_offset + block * dimensions + d];
                amount_sum += amount[edge] * value;
                quantity_sum += quantity[edge] * value;
            }
            amount_vector[d] = static_cast<float>(amount_sum);
            quantity_vector[d] = static_cast<float>(quantity_sum);
        }
        normalise(amount_vector);
        normalise(quantity_vector);
    }

    double* row_numeric = numeric + static_cast<std::size_t>(source) * numeric_width;
    row_numeric[0] = amount_total;
    row_numeric[1] = quantity_total;
    row_numeric[2] = static_cast<double>(last - first);
    row_numeric[3] = amount_total > 0.0
        ? amount_squares / (amount_total * amount_total) : 0.0;
    row_numeric[4] = quantity_total > 0.0
        ? quantity_squares / (quantity_total * quantity_total) : 0.0;
}
} // namespace bom_vectorisation
