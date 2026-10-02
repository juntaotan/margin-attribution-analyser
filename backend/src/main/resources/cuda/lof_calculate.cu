#include "lof_engine.cuh"
#include <cmath>

// Step 1: Calculate the Euclidean distance between two vectors.
float distance_calculator::distance(
    const float* vectorA,
    const float* vectorB,
    int dimension
) {

    float sum = 0.0f;

    for (int i = 0; i < dimension; ++i) {
        float diff = vectorA[i] - vectorB[i];
        sum += diff * diff;
    }
    
    return sqrtf(sum);
}
// Step 2: Find the indices of the K nearest vectors to a target vector.
std::vector<int> find_top_k::tops(
    const float* target_vector,
    const float* all_vectors,
    int num_vectors,
    int dimension,
    int k
) {
    // Store the distance from target_vector to every vector.
    std::vector<float> distances(num_vectors);

    // Calculate all distances once.
    for (int i = 0; i < num_vectors; ++i)
    {
        const float* current_vector =
            &all_vectors[i * dimension];

        distances[i] =
            distance_calculator::distance(
                target_vector,
                current_vector,
                dimension
            );
    }

    // Store the indices of the K nearest vectors.
    std::vector<int> top_k_indices(k);

    // Find the smallest distance K times.
    for (int i = 0; i < k; ++i)
    {
        float smallest_distance = std::numeric_limits<float>::infinity();
        int smallest_index = -1;

        for (int j = 0; j < num_vectors; ++j)
        {
            if (distances[j] < smallest_distance)
            {
                smallest_distance = distances[j];
                smallest_index = j;
            }
        }

        top_k_indices[i] = smallest_index;

        // Remove this vector from the next search.
        distances[smallest_index] =
            std::numeric_limits<float>::infinity();
    }

    return top_k_indices;
}