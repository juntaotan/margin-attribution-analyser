#include "lof_engine.cuh"

#include <array>
#include <cmath>
#include <iomanip>
#include <iostream>

// Verify the seven supplied orders against independently calculated LOF values.
int main() {
    constexpr int order_count = 7;
    constexpr int dimension = 4;
    constexpr int k = 2;

    // A through G are stored in input order, with G as the anomaly.
    const std::array<float, order_count * dimension> orders = {
        7.0f, 2.0f, 1.0f, 1.0f,  // A
        7.1f, 1.8f, 1.1f, 0.9f,  // B
        6.9f, 2.2f, 0.9f, 1.1f,  // C
        0.1f, 2.0f, 1.0f, 1.0f,  // D
        0.2f, 1.8f, 1.1f, 0.9f,  // E
        0.0f, 2.2f, 0.9f, 1.1f,  // F
        0.5f, 0.5f, 0.5f, 0.5f   // G
    };

    // Expected values use reachDistance(A,B) = max(kDistance(B), distance(A,B)).
    const std::array<float, order_count> expected = {
        1.333333333f, 0.875f, 0.875f, 1.333333333f,
        0.875f, 0.875f, 3.552414237f
    };
    const std::array<const char*, order_count> names = {"A", "B", "C", "D", "E", "F", "G"};
    const auto scores = lof_calculator::calculate(orders.data(), order_count, dimension, k);

    bool all_correct = true;
    std::cout << std::fixed << std::setprecision(6);
    std::cout << "Point  LOF       Expected  Result\n";

    // Print and check the LOF value of each order.
    for (int index = 0; index < order_count; ++index) {
        const bool correct = std::fabs(scores[index] - expected[index]) < 1e-4f;
        all_correct &= correct;
        std::cout << names[index] << "      "
                  << scores[index]
                  << "  " << expected[index]
                  << "  " << (correct ? "PASS" : "FAIL") << '\n';
    }

    std::cout << "Overall: " << (all_correct ? "PASS" : "FAIL") << '\n';
    return all_correct ? 0 : 1;
}
