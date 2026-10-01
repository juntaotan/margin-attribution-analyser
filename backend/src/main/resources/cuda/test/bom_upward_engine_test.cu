#include "bom_upward_engine.cuh"
#include <gtest/gtest.h>
#include <algorithm>
#include <cstdint>
#include <limits>
#include <numeric>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

using Node = decltype(BomUpwardGraph::offsets)::value_type;
using Value = decltype(BomUpwardGraph::node_values)::value_type;
using Comparable = decltype(BomUpwardGraph::node_comparable)::value_type;

#include <cuda_runtime.h>

// Supplies the CUDA backend for the same graphs and expectations as OpenCL.
class BomUpwardEngineTest : public ::testing::Test {
protected:
    // Require a real device so missing GPU coverage cannot appear as success.
    void SetUp() override {
        int device_count = 0;
        const auto error = cudaGetDeviceCount(&device_count);
        ASSERT_EQ(error, cudaSuccess) << cudaGetErrorString(error);
        ASSERT_GT(device_count, 0) << "A working CUDA device is required.";
    }

    // Supply a fresh engine while each case controls repeated calls.
    CudaBomUpwardEngine make_engine() {
        return CudaBomUpwardEngine{};
    }
};

TEST_F(
    BomUpwardEngineTest,
    PropagatesUntilComparableNodeExceedsThreshold
) {
    /*
     * Graph:
     *
     * T1(0) ─┐
     *        ├── P(2) ──> R(3)
     * T2(1) ─┘
     *
     * P value = 500
     * R value = 1500
     * threshold = 1000
     *
     * Expected:
     * P continues propagation.
     * R becomes a result node.
     */

    BomUpwardGraph graph;

    /*
     * Product -> materials child counts:
     *
     * T1: 0 children
     * T2: 0 children
     * P : 2 children
     * R : 1 child
     */
    graph.offsets = {
        0,  // T1 start
        0,  // T2 start
        0,  // P start
        2,  // R start
        3   // end
    };

    /*
     * Reverse graph:
     *
     * T1 -> P
     * T2 -> P
     * P  -> R
     */
    graph.reverse_offsets = {
        0,  // T1
        1,  // T2
        2,  // P
        3,  // R
        3   // end
    };

    graph.reverse_successors = {
        2,  // T1 -> P
        2,  // T2 -> P
        3   // P  -> R
    };

    // First frontier.
    graph.terminal_nodes = {
        0,  // T1
        1   // T2
    };

    // Delta values.
    graph.node_values = {
        0,      // T1
        0,      // T2
        500,    // P: below threshold
        1500    // R: above threshold
    };

    // All nodes in this test are comparable.
    graph.node_comparable = {
        1,
        1,
        1,
        1
    };

    const uint64_t threshold = 1000;

    auto engine = make_engine();

    const std::vector<Node> results =
        engine.run(graph, threshold);

    ASSERT_EQ(results.size(), 1u);

    EXPECT_EQ(results[0], 3u);
}

namespace {
// Edges point from material to product. Keep the semantic graph separate from
// CSR construction so the CPU oracle does not repeat the kernel's counters.
struct TestGraph {
    std::vector<Value> values;
    std::vector<Comparable> comparable;
    std::vector<std::pair<Node, Node>> edges;

    // Construct the same CSR input for both backends from material-to-product edges.
    BomUpwardGraph csr() const {
        const auto n = values.size();
        BomUpwardGraph graph;
        graph.node_values = values;
        graph.node_comparable = comparable;
        graph.offsets.assign(n + 1, 0);
        graph.reverse_offsets.assign(n + 1, 0);
        std::vector<std::vector<Node>> parents(n);
        for (const auto& [child, parent] : edges) {
            ++graph.offsets.at(parent + 1);
            parents.at(child).push_back(parent);
        }
        for (Node node = 0; node < n; ++node) {
            if (graph.offsets[node + 1] == 0) {
                graph.terminal_nodes.push_back(node);
            }
        }
        std::partial_sum(graph.offsets.begin(), graph.offsets.end(), graph.offsets.begin());
        for (Node node = 0; node < n; ++node) {
            graph.reverse_successors.insert(graph.reverse_successors.end(),
                parents[node].begin(), parents[node].end());
            graph.reverse_offsets[node + 1] = graph.reverse_successors.size();
        }
        return graph;
    }

    // Compute expected nodes independently by scanning child states on the CPU.
    std::vector<Node> reference(uint64_t threshold) const {
        std::vector<std::vector<Node>> children(values.size());
        for (const auto& [child, parent] : edges) {
            children.at(parent).push_back(child);
        }
        // 0: unresolved, 1: propagates, 2: selected. Repeated scans also allow
        // arbitrary node IDs and children arriving at different depths.
        std::vector<int> state(values.size(), 0);
        for (size_t node = 0; node < values.size(); ++node) {
            if (children[node].empty()) state[node] = 1;
        }
        std::vector<Node> result;
        bool changed;
        do {
            changed = false;
            for (Node node = 0; node < values.size(); ++node) {
                if (state[node] != 0 || !std::all_of(children[node].begin(),
                    children[node].end(), [&](Node child) { return state[child] == 1; })) {
                    continue;
                }
                const Value value = values[node];
                // Avoid signed overflow for LONG_MIN.
                const uint64_t magnitude = value < 0
                    ? static_cast<uint64_t>(-(value + 1)) + 1
                    : static_cast<uint64_t>(value);
                const bool selected = comparable[node] != 0 && magnitude >= threshold;
                state[node] = selected ? 2 : 1;
                if (selected) result.push_back(node);
                changed = true;
            }
        } while (changed);
        return result;
    }
};

// Compare unordered node IDs while retaining duplicates as failures.
void ExpectNodes(std::vector<Node> actual, std::vector<Node> expected) {
    // Do not convert to a set: duplicate results must fail the assertion.
    std::sort(actual.begin(), actual.end());
    std::sort(expected.begin(), expected.end());
    EXPECT_EQ(actual, expected);
}

// Include the complete generated graph in randomized failure diagnostics.
std::string Describe(const TestGraph& graph) {
    std::ostringstream out;
    out << "values:";
    for (auto value : graph.values) out << ' ' << value;
    out << "; comparable:";
    for (auto flag : graph.comparable) out << ' ' << static_cast<unsigned>(flag);
    out << "; edges:";
    for (auto [child, parent] : graph.edges) out << ' ' << child << "->" << parent;
    return out.str();
}
} // namespace

TEST_F(BomUpwardEngineTest, UsesInclusiveAbsoluteThreshold) {
    auto engine = make_engine();
    for (Value value : {999L, 1000L, 1001L, -999L, -1000L, -1001L}) {
        SCOPED_TRACE(value);
        TestGraph graph{{0, value}, {1, 1}, {{0, 1}}};
        ExpectNodes(engine.run(graph.csr(), 1000),
            value == 999 || value == -999 ? std::vector<Node>{} : std::vector<Node>{1});
    }
}

TEST_F(BomUpwardEngineTest, ZeroThresholdSelectsComparableZero) {
    TestGraph graph{{0, 0, 0}, {1, 0, 1}, {{0, 1}, {1, 2}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 0), {2});
}

TEST_F(BomUpwardEngineTest, NonComparableNodesPropagateRegardlessOfMagnitude) {
    TestGraph graph{{0, 5000, -5000, 1500}, {1, 0, 0, 1},
        {{0, 1}, {1, 2}, {2, 3}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {3});
}

TEST_F(BomUpwardEngineTest, SelectedNodePrunesItsAncestors) {
    TestGraph graph{{0, 1500, 2500}, {1, 1, 1}, {{0, 1}, {1, 2}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {1});
}

TEST_F(BomUpwardEngineTest, InitialTerminalIsNotEvaluated) {
    TestGraph graph{{5000, 0, 1000}, {1, 1, 1}, {{0, 1}, {1, 2}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {2});
}

TEST_F(BomUpwardEngineTest, SharedMaterialPropagatesToEveryParent) {
    TestGraph graph{{0, 1000, -2000, 3000}, {1, 1, 1, 1},
        {{0, 1}, {0, 2}, {0, 3}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {1, 2, 3});
}

TEST_F(BomUpwardEngineTest, DisconnectedComponentsAreIndependent) {
    TestGraph graph{{0, 1000, 0, 100, 2000, 0}, {1, 1, 1, 1, 1, 1},
        {{0, 1}, {2, 3}, {3, 4}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {1, 4});
}

TEST_F(BomUpwardEngineTest, DiamondSelectsSharedAncestorExactlyOnce) {
    TestGraph graph{{0, 100, 200, 1500}, {1, 1, 1, 1},
        {{0, 1}, {0, 2}, {1, 3}, {2, 3}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {3});
}

TEST_F(BomUpwardEngineTest, WaitsForChildrenAcrossFrontierRounds) {
    // 0 -> 1 -> 2 -> 4 and 3 -> 4. Node 3 arrives two rounds earlier.
    TestGraph graph{{0, 100, 100, 0, 1500}, {1, 1, 1, 1, 1},
        {{0, 1}, {1, 2}, {2, 4}, {3, 4}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {4});
}

TEST_F(BomUpwardEngineTest, PrunedChildBlocksSharedAncestor) {
    // 1 is selected, so 3 never receives the arrival from 1.
    TestGraph graph{{0, 1500, 100, 2500}, {1, 1, 1, 1},
        {{0, 1}, {0, 2}, {1, 3}, {2, 3}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {1});
}

TEST_F(BomUpwardEngineTest, MissingInitialChildDoesNotReleaseParent) {
    TestGraph graph{{0, 0, 1500}, {1, 1, 1}, {{0, 2}, {1, 2}}};
    auto input = graph.csr();
    input.terminal_nodes = {0};
    auto engine = make_engine();
    ExpectNodes(engine.run(input, 1000), {});
}

TEST_F(BomUpwardEngineTest, NoSignificantNodeReturnsEmpty) {
    TestGraph graph{{0, 100, -999}, {1, 1, 1}, {{0, 1}, {1, 2}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {});
}

TEST_F(BomUpwardEngineTest, AllNonComparableNodesReturnEmpty) {
    TestGraph graph{{0, 2000, -3000}, {0, 0, 0}, {{0, 1}, {1, 2}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {});
}

TEST_F(BomUpwardEngineTest, EmptyGraphReturnsEmpty) {
    auto engine = make_engine();
    ExpectNodes(engine.run(BomUpwardGraph{}, 1000), {});
}

TEST_F(BomUpwardEngineTest, EmptyFrontierReturnsEmpty) {
    TestGraph graph{{0, 1500}, {1, 1}, {{0, 1}}};
    auto input = graph.csr();
    input.terminal_nodes.clear();
    auto engine = make_engine();
    ExpectNodes(engine.run(input, 1000), {});
}

TEST_F(BomUpwardEngineTest, RejectsInvalidDimensions) {
    TestGraph graph{{0, 1500}, {1, 1}, {{0, 1}}};
    auto engine = make_engine();
    for (int field = 0; field < 3; ++field) {
        for (bool oversized : {false, true}) {
            SCOPED_TRACE(::testing::Message() << "field=" << field << " oversized=" << oversized);
            auto input = graph.csr();
            auto resize = [oversized](auto& values) {
                values.resize(oversized ? values.size() + 1 : values.size() - 1);
            };
            if (field == 0) resize(input.offsets);
            if (field == 1) resize(input.reverse_offsets);
            if (field == 2) resize(input.node_comparable);
            EXPECT_THROW(engine.run(input, 1000), std::runtime_error);
        }
    }
}

TEST_F(BomUpwardEngineTest, RepeatedCallsResetAllState) {
    TestGraph first{{0, 1500, 2500}, {1, 1, 1}, {{0, 1}, {1, 2}}};
    TestGraph second{{0, 0, -4000, 5000}, {1, 1, 1, 1},
        {{0, 2}, {1, 2}, {2, 3}}};
    auto engine = make_engine();
    ExpectNodes(engine.run(first.csr(), 1000), {1});
    ExpectNodes(engine.run(first.csr(), 2000), {2});
    ExpectNodes(engine.run(second.csr(), 3000), {2});
    ExpectNodes(engine.run(second.csr(), 6000), {});
    ExpectNodes(engine.run(first.csr(), 1000), {1});
}

TEST_F(BomUpwardEngineTest, HandlesFullWidthIntegerMagnitude) {
    const Value minimum = std::numeric_limits<Value>::min();
    const Value maximum = std::numeric_limits<Value>::max();
    const uint64_t high_bit = uint64_t{1} << 63;
    const std::vector<std::pair<Value, uint64_t>> cases = {
        {Value{1} << 40, uint64_t{1} << 40},
        {-(Value{1} << 40), (uint64_t{1} << 40) + 1},
        {maximum, static_cast<uint64_t>(maximum)},
        {maximum, high_bit}, {minimum, high_bit}, {minimum, high_bit + 1},
        {minimum, std::numeric_limits<uint64_t>::max()}
    };
    auto engine = make_engine();
    for (const auto& [value, threshold] : cases) {
        SCOPED_TRACE(::testing::Message() << "value=" << value << " threshold=" << threshold);
        TestGraph graph{{0, value}, {1, 1}, {{0, 1}}};
        ExpectNodes(engine.run(graph.csr(), threshold), graph.reference(threshold));
    }
}

TEST_F(BomUpwardEngineTest, LongChainCompletesAcrossManyRounds) {
    TestGraph graph;
    graph.values.assign(128, 0);
    graph.comparable.assign(128, 1);
    graph.values.back() = 1000;
    for (Node node = 0; node < 127; ++node) graph.edges.emplace_back(node, node + 1);
    auto engine = make_engine();
    ExpectNodes(engine.run(graph.csr(), 1000), {127});
}

TEST_F(BomUpwardEngineTest, ConcurrentFanInAndFanOutDoNotLoseOrDuplicateResults) {
    constexpr Node leaves = 1024;
    constexpr Node parents = 64;
    TestGraph graph;
    graph.values.assign(leaves + parents, 1500);
    graph.comparable.assign(leaves + parents, 1);
    for (Node child = 0; child < leaves; ++child) {
        for (Node parent = leaves; parent < leaves + parents; ++parent) {
            graph.edges.emplace_back(child, parent);
        }
    }
    std::vector<Node> expected(parents);
    std::iota(expected.begin(), expected.end(), leaves);
    auto engine = make_engine();
    auto input = graph.csr();
    std::mt19937 random(20261001);
    for (int repetition = 0; repetition < 20; ++repetition) {
        SCOPED_TRACE(repetition);
        std::shuffle(input.terminal_nodes.begin(), input.terminal_nodes.end(), random);
        ExpectNodes(engine.run(input, 1000), expected);
    }
}

TEST_F(BomUpwardEngineTest, SeededRandomDagsMatchIndependentCpuReference) {
    constexpr unsigned seed = 20261001;
    std::mt19937 random(seed);
    auto engine = make_engine();
    for (int sample = 0; sample < 100; ++sample) {
        const Node n = 2 + random() % 47;
        TestGraph graph;
        std::vector<Node> order(n);
        std::iota(order.begin(), order.end(), 0);
        std::shuffle(order.begin(), order.end(), random);
        for (Node node = 0; node < n; ++node) {
            graph.values.push_back(static_cast<Value>(random() % 4001) - 2000);
            graph.comparable.push_back(random() % 3 != 0);
        }
        // The caller rejects edgeless graphs, so generate at least one edge.
        graph.edges.emplace_back(order[0], order[1]);
        for (Node child = 0; child < n; ++child) {
            for (Node parent = child + 1; parent < n; ++parent) {
                if (child == 0 && parent == 1) continue;
                if (random() % 5 == 0) graph.edges.emplace_back(order[child], order[parent]);
            }
        }
        auto input = graph.csr();
        std::shuffle(input.terminal_nodes.begin(), input.terminal_nodes.end(), random);
        for (uint64_t threshold : {0UL, 1000UL, 2001UL}) {
            SCOPED_TRACE(::testing::Message() << "seed=" << seed << " sample=" << sample
                << " threshold=" << threshold << ' ' << Describe(graph));
            ExpectNodes(engine.run(input, threshold), graph.reference(threshold));
        }
    }
}
