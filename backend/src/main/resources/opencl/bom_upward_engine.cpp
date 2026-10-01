#include "bom_upward_engine.hpp"

#include <algorithm>
#include <stdexcept>

// Define a constant for undiscovered nodes to confirm that the node has not 
// been processed yet.
namespace {
constexpr cl_uint UNDISCOVERED = 0xFFFFFFFFu;
}

BomUpwardEngine::BomUpwardEngine(
    const cl::Context& context,
    const cl::CommandQueue& queue,
    const cl::Program& program
)
    : context_(context),
      queue_(queue),
      kernel_(program, "process_frontier")
{
}


std::vector<cl_uint> BomUpwardEngine::run(
    const BomUpwardGraph& graph,
    const cl_ulong threshold
) {
    const cl_uint node_count = static_cast<cl_uint>(graph.node_values.size());

    if (node_count == 0 || graph.terminal_nodes.empty()) {
        return {};
    }

    // Basic validation
    if (graph.offsets.size() != node_count + 1 ||
        graph.reverse_offsets.size() != node_count + 1 ||
        graph.node_comparable.size() != node_count) {
        throw std::runtime_error("Invalid BOM graph dimensions.");
    }

    // Static graph buffers
    cl::Buffer offsets_buffer(context_,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        graph.offsets.size() * sizeof(cl_uint),
        const_cast<cl_uint*>(graph.offsets.data())
    );
    cl::Buffer reverse_offsets_buffer(
        context_,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        graph.reverse_offsets.size() * sizeof(cl_uint),
        const_cast<cl_uint*>(graph.reverse_offsets.data())
    );
    cl::Buffer reverse_successors_buffer(
        context_,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        graph.reverse_successors.size() * sizeof(cl_uint),
        const_cast<cl_uint*>(graph.reverse_successors.data())
    );
    cl::Buffer node_values_buffer(
        context_,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        graph.node_values.size() * sizeof(cl_long),
        const_cast<cl_long*>(graph.node_values.data())
    );
    cl::Buffer node_comparable_buffer(
        context_,
        CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
        graph.node_comparable.size() * sizeof(cl_uchar),
        const_cast<cl_uchar*>(graph.node_comparable.data())
    );

    // Dynamic propagation state
    cl::Buffer remaining_buffer(context_,CL_MEM_READ_WRITE,node_count * sizeof(cl_uint));
    cl::Buffer frontier_a(context_, CL_MEM_READ_WRITE, node_count * sizeof(cl_uint));
    cl::Buffer frontier_b(context_,CL_MEM_READ_WRITE,node_count * sizeof(cl_uint));
    cl::Buffer next_frontier_count_buffer(context_,CL_MEM_READ_WRITE,sizeof(cl_uint));
    cl::Buffer result_nodes_buffer(context_,CL_MEM_READ_WRITE,node_count * sizeof(cl_uint));
    cl::Buffer result_count_buffer(context_,CL_MEM_READ_WRITE,sizeof(cl_uint));

    // Initialise state
    // Initialise remaining_buffer
    queue_.enqueueFillBuffer(remaining_buffer,UNDISCOVERED,0,node_count * sizeof(cl_uint));
    const cl_uint zero = 0;
    // Initialise result_count_buffer
    queue_.enqueueFillBuffer(result_count_buffer,zero,0,sizeof(cl_uint));

    // First frontier is all terminal nodes
    queue_.enqueueWriteBuffer(
        frontier_a,
        CL_TRUE,
        0,
        graph.terminal_nodes.size() * sizeof(cl_uint),
        graph.terminal_nodes.data()
    );

    cl::Buffer current_frontier = frontier_a;
    cl::Buffer next_frontier = frontier_b;

    // Start to propagate values from terminal nodes to their ancestors.
    cl_uint current_count = static_cast<cl_uint>(graph.terminal_nodes.size());
    while (current_count > 0) {

        // next_frontier is empty at the beginning of every round.
        queue_.enqueueFillBuffer(next_frontier_count_buffer,zero,0,sizeof(cl_uint));

        // Kernel arguments: Must match process_frontier(...) exactly
        kernel_.setArg(0, offsets_buffer);
        kernel_.setArg(1, reverse_offsets_buffer);
        kernel_.setArg(2, reverse_successors_buffer);
        kernel_.setArg(3, current_frontier);
        kernel_.setArg(4, next_frontier);
        kernel_.setArg(5, next_frontier_count_buffer);
        kernel_.setArg(6, remaining_buffer);
        kernel_.setArg(7, threshold);
        kernel_.setArg(8, node_values_buffer);
        kernel_.setArg(9, node_comparable_buffer);
        kernel_.setArg(10, current_count);
        kernel_.setArg(11, result_nodes_buffer);
        kernel_.setArg(12, result_count_buffer);

        queue_.enqueueNDRangeKernel(kernel_,cl::NullRange,cl::NDRange(current_count),cl::NullRange);
        cl_uint next_count = 0;
        queue_.enqueueReadBuffer(next_frontier_count_buffer,CL_TRUE,0,sizeof(cl_uint),&next_count);

        current_count = next_count;

        std::swap(current_frontier, next_frontier);
    }

    // Read final results
    cl_uint result_count = 0;
    queue_.enqueueReadBuffer(result_count_buffer,CL_TRUE,0,sizeof(cl_uint),&result_count);
    std::vector<cl_uint> results(result_count);

    if (result_count > 0) {
        queue_.enqueueReadBuffer(result_nodes_buffer,CL_TRUE,0,result_count * sizeof(cl_uint),results.data());
    }

    return results;
}