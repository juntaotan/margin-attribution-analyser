#include "lof_engine.hpp"

#include <stdexcept>
#include <string>
#include <utility>

namespace {

// Surface OpenCL failures instead of returning incomplete scores.
void check(cl_int error) {
    if (error != CL_SUCCESS) {
        throw std::runtime_error("OpenCL error " + std::to_string(error));
    }
}

// Assign a kernel's arguments in declaration order.
template <typename... Args>
void set_args(cl::Kernel& kernel, Args&&... args) {
    cl_uint index = 0;
    (check(kernel.setArg(index++, std::forward<Args>(args))), ...);
}

} // namespace

// Keep the four prebuilt kernels so repeated runs only allocate per-call data.
OpenClLofEngine::OpenClLofEngine(const cl::Context& context, const cl::CommandQueue& queue,
                                 const cl::Program& program)
    : context_(context), queue_(queue) {
    cl_int error = CL_SUCCESS;
    distance_kernel_ = cl::Kernel(program, "distance_kernel", &error);
    check(error);
    k_distance_kernel_ = cl::Kernel(program, "k_distance_kernel", &error);
    check(error);
    lrd_kernel_ = cl::Kernel(program, "lrd_kernel", &error);
    check(error);
    lof_kernel_ = cl::Kernel(program, "lof_kernel", &error);
    check(error);
}

// Upload four-feature orders, run all LOF stages, and download their scores.
std::vector<float> OpenClLofEngine::run(const float* orders, int count, int k) {
    if (k <= 0 || k >= count) {
        throw std::invalid_argument("LOF requires 1 <= k < count");
    }

    // Distance and intermediate arrays stay on the device between stages.
    const size_t order_count = static_cast<size_t>(count);
    cl_int error = CL_SUCCESS;
    cl::Buffer orders_buffer(context_, CL_MEM_READ_ONLY, order_count * 4 * sizeof(float), nullptr, &error);
    check(error);
    cl::Buffer distances_buffer(context_, CL_MEM_READ_WRITE,
                                order_count * order_count * sizeof(float), nullptr, &error);
    check(error);
    cl::Buffer k_distances_buffer(context_, CL_MEM_READ_WRITE, order_count * sizeof(float), nullptr, &error);
    check(error);
    cl::Buffer lrd_buffer(context_, CL_MEM_READ_WRITE, order_count * sizeof(float), nullptr, &error);
    check(error);
    cl::Buffer scores_buffer(context_, CL_MEM_WRITE_ONLY, order_count * sizeof(float), nullptr, &error);
    check(error);

    check(queue_.enqueueWriteBuffer(orders_buffer, CL_TRUE, 0,
                                    order_count * 4 * sizeof(float), orders));

    // An in-order queue makes each kernel wait for the preceding stage.
    const cl::NDRange work_items(order_count);
    set_args(distance_kernel_, orders_buffer, distances_buffer, count);
    check(queue_.enqueueNDRangeKernel(distance_kernel_, cl::NullRange, work_items));
    set_args(k_distance_kernel_, distances_buffer, k_distances_buffer, count, k);
    check(queue_.enqueueNDRangeKernel(k_distance_kernel_, cl::NullRange, work_items));
    set_args(lrd_kernel_, distances_buffer, k_distances_buffer, lrd_buffer, count);
    check(queue_.enqueueNDRangeKernel(lrd_kernel_, cl::NullRange, work_items));
    set_args(lof_kernel_, distances_buffer, k_distances_buffer, lrd_buffer, scores_buffer, count);
    check(queue_.enqueueNDRangeKernel(lof_kernel_, cl::NullRange, work_items));

    // Blocking read waits for all four kernels and preserves the input order.
    std::vector<float> scores(count);
    check(queue_.enqueueReadBuffer(scores_buffer, CL_TRUE, 0,
                                   order_count * sizeof(float), scores.data()));
    return scores;
}
