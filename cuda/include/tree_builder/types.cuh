#pragma once

#include <cuda_runtime.h>
#include <thrust/device_vector.h>
#include <cstdint>

namespace margintrace::cuda {

/**
 * Node attributes stored in Structure-of-Arrays (SoA) layout on device memory.
 */
struct GpuNodeAttributes {
    double* d_quantities;   ///< Aggregated completed/consumed quantity per node
    double* d_costs;        ///< Aggregated material total cost per node
    uint32_t* d_flags;      ///< Bitmask flags (e.g. bit 0: target product, bit 1: consumed material)
};

/**
 * Raw production records in SoA layout.
 */
struct ProductionRawData {
    uint32_t size;
    const uint32_t* d_order_ids;      ///< Encoded production order ID
    const uint32_t* d_product_ids;    ///< Produced product ID
    const double*   d_completed_qtys; ///< Completed quantity
};

/**
 * Raw inventory usage records in SoA layout.
 */
struct UsageRawData {
    uint32_t size;
    const uint32_t* d_order_ids;      ///< Production order ID
    const uint32_t* d_product_ids;    ///< Target product ID
    const uint32_t* d_material_ids;   ///< Consumed material ID (upstream dependency)
    const double*   d_material_qtys;  ///< Consumed material quantity
    const double*   d_material_costs; ///< Consumed material total cost
};

/**
 * Directed edge representing upstream material consumption by downstream product:
 * src: materialId (upstream)
 * dst: productId  (downstream)
 */
struct Edge {
    uint32_t src;
    uint32_t dst;

    __host__ __device__ bool operator<(const Edge& other) const {
        if (src != other.src) return src < other.src;
        return dst < other.dst;
    }

    __host__ __device__ bool operator==(const Edge& other) const {
        return src == other.src && dst == other.dst;
    }
};

/**
 * Output Compressed Sparse Row (CSR) representation of the dependency graph.
 */
struct GpuCsrGraph {
    uint32_t num_nodes{0};
    uint32_t num_edges{0};

    thrust::device_vector<int> offset;             ///< Node row offsets, size = num_nodes + 1
    thrust::device_vector<int> successors;         ///< Successor column indices, size = num_edges
    thrust::device_vector<double> node_quantities; ///< Node quantities indexed by node ID
    thrust::device_vector<double> node_costs;      ///< Node costs indexed by node ID
};

} // namespace margintrace::cuda
