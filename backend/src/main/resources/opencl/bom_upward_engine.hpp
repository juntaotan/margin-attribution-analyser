#pragma once

#include <CL/opencl.hpp>
#include <vector>

struct BomUpwardGraph {
    // Product -> materials
    std::vector<cl_uint> offsets;

    // Material -> products
    std::vector<cl_uint> reverse_offsets;
    std::vector<cl_uint> reverse_successors;

    // Initial frontier
    std::vector<cl_uint> terminal_nodes;

    // Delta value of each node
    std::vector<cl_long> node_values;

    // 1 = exists in both DAGs, 0 = only one DAG
    std::vector<cl_uchar> node_comparable;
};

class BomUpwardEngine {
public:
    BomUpwardEngine(
        const cl::Context& context,
        const cl::CommandQueue& queue,
        const cl::Program& program
    );

    std::vector<cl_uint> run(
        const BomUpwardGraph& graph,
        cl_ulong threshold
    );

private:
    cl::Context context_;
    cl::CommandQueue queue_;
    cl::Kernel kernel_;
};