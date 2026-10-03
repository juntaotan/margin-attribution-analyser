#pragma once

#include <CL/opencl.hpp>
#include <vector>

// Run four ordered OpenCL stages to calculate one LOF score per four-feature order.
class OpenClLofEngine {
public:
    // Keep the caller's context, in-order queue, and prebuilt LOF kernels for repeated calls.
    OpenClLofEngine(const cl::Context& context, const cl::CommandQueue& queue,
                    const cl::Program& program);

    // Accept N contiguous four-feature arrays and return N LOF scores in input order.
    std::vector<float> run(const float* orders, int count, int k);

private:
    cl::Context context_;
    cl::CommandQueue queue_;
    cl::Kernel distance_kernel_;
    cl::Kernel k_distance_kernel_;
    cl::Kernel lrd_kernel_;
    cl::Kernel lof_kernel_;
};
