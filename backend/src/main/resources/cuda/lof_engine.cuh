#pragma once

#include <vector>

// Keep each order's neighbors and the distance to its kth neighbor together.
struct Neighbourhoods {
    std::vector<std::vector<int>> indices;
    std::vector<float> k_distances;
};

class distance_calculator {
public:
    // Return the Euclidean distance between two feature vectors.
    static float distance(const float* vector_a, const float* vector_b, int dimension);
};

class find_top_k {
public:
    // Find every order's k nearest neighbors, including ties at the kth distance.
    static Neighbourhoods tops(const float* all_vectors, int num_vectors, int dimension, int k);
};

class lof_calculator {
public:
    // Return one LOF score per order in the same order as the input vectors.
    static std::vector<float> calculate(const float* all_vectors, int num_vectors, int dimension, int k);
};
