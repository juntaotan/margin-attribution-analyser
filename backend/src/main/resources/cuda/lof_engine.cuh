#pragma once
#include <vector>

class distance_calculator {
public:
    static float distance(
        const float* vectorA,
        const float* vectorB,
        int dimension
    );
};

class find_top_k {
public:
    static std::vector<int> tops(
        const float* target_vector,
        const float* all_vectors,
        int num_vectors,
        int dimension,
        int k
    );
};