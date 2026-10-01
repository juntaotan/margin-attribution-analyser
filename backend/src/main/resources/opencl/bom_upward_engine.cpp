#define CL_HPP_TARGET_OPENCL_VERSION 200
#define CL_HPP_MINIMUM_OPENCL_VERSION 120
#define CL_HPP_ENABLE_EXCEPTIONS

#include <CL/opencl.hpp>

#include <cstdint>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

// Read cl source file into string
std::string readTextFile(const std::string& path) {
    std::ifstream input(path);

    if (!input) {
        throw std::runtime_error("Cannot open kernel: " + path);
    }

    std::ostringstream content;
    content << input.rdbuf();
    return content.str();
}


cl::Device chooseDevice() {
    std::vector<cl::Platform> platforms;
    cl::Platform::get(&platforms);

    if (platforms.empty()) {
        throw std::runtime_error("No OpenCL platform found");
    }

    for (const auto& platform : platforms) {
        std::vector<cl::Device> devices;
        platform.getDevices(CL_DEVICE_TYPE_ALL, &devices);

        if (!devices.empty()) {
            return devices.front();
        }
    }

    throw std::runtime_error("No OpenCL device found");
}

} // namespace

int main() {
    try {
        // ------------------------------------------------------------
        // 1. 创建 Device、Context、CommandQueue
        // ------------------------------------------------------------
        const cl::Device device = chooseDevice();
        const cl::Context context(device);
        const cl::CommandQueue queue(context, device);

        std::cout
            << "Device: "
            << device.getInfo<CL_DEVICE_NAME>()
            << '\n';

        // ------------------------------------------------------------
        // 2. 读取并编译 OpenCL kernel
        // ------------------------------------------------------------
        const std::string source =
            readTextFile(OPENCL_KERNEL_PATH);

        cl::Program program(context, source);

        try {
            program.build({device});
        } catch (const cl::Error&) {
            std::cerr
                << program.getBuildInfo<CL_PROGRAM_BUILD_LOG>(device)
                << '\n';
            throw;
        }

        cl::Kernel kernel(program, "initialise_nodes");

        // ------------------------------------------------------------
        // 3. Host 端 SoA 测试数据
        //
        // A = 0, B = 1, C = 2, P = 3
        // A/B/C 是 terminal；P 需要等待三个直接下级。
        // ------------------------------------------------------------
        const std::vector<float> nodeValue{
            10.0f, // A
            20.0f, // B
            30.0f, // C
            40.0f  // P
        };

        const std::vector<std::uint32_t> expectedChildren{
            0, // A
            0, // B
            0, // C
            3  // P
        };

        const std::size_t nodeCount = nodeValue.size();

        // ------------------------------------------------------------
        // 4. 创建设备 Buffer
        //
        // 输入 buffer 使用 READ_ONLY。
        // 输出/状态 buffer 使用 READ_WRITE。
        // ------------------------------------------------------------
        cl::Buffer nodeValueBuffer(
            context,
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            sizeof(float) * nodeCount,
            const_cast<float*>(nodeValue.data())
        );

        cl::Buffer expectedBuffer(
            context,
            CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
            sizeof(std::uint32_t) * nodeCount,
            const_cast<std::uint32_t*>(expectedChildren.data())
        );

        cl::Buffer resultBuffer(
            context,
            CL_MEM_READ_WRITE,
            sizeof(float) * nodeCount
        );

        cl::Buffer remainingBuffer(
            context,
            CL_MEM_READ_WRITE,
            sizeof(std::uint32_t) * nodeCount
        );

        cl::Buffer stateBuffer(
            context,
            CL_MEM_READ_WRITE,
            sizeof(std::uint32_t) * nodeCount
        );

        // ------------------------------------------------------------
        // 5. 按照 kernel 参数顺序绑定 buffer
        // ------------------------------------------------------------
        kernel.setArg(0, nodeValueBuffer);
        kernel.setArg(1, expectedBuffer);
        kernel.setArg(2, resultBuffer);
        kernel.setArg(3, remainingBuffer);
        kernel.setArg(4, stateBuffer);
        kernel.setArg(
            5,
            static_cast<cl_uint>(nodeCount)
        );

        // ------------------------------------------------------------
        // 6. 启动 nodeCount 个 work-item
        //
        // gid 0 处理 A
        // gid 1 处理 B
        // gid 2 处理 C
        // gid 3 处理 P
        // ------------------------------------------------------------
        queue.enqueueNDRangeKernel(
            kernel,
            cl::NullRange,
            cl::NDRange(nodeCount),
            cl::NullRange
        );

        // ------------------------------------------------------------
        // 7. 将三个输出 buffer 读回 Host
        // ------------------------------------------------------------
        std::vector<float> result(nodeCount);
        std::vector<std::uint32_t> remaining(nodeCount);
        std::vector<std::uint32_t> state(nodeCount);

        queue.enqueueReadBuffer(
            resultBuffer,
            CL_TRUE,
            0,
            sizeof(float) * nodeCount,
            result.data()
        );

        queue.enqueueReadBuffer(
            remainingBuffer,
            CL_TRUE,
            0,
            sizeof(std::uint32_t) * nodeCount,
            remaining.data()
        );

        queue.enqueueReadBuffer(
            stateBuffer,
            CL_TRUE,
            0,
            sizeof(std::uint32_t) * nodeCount,
            state.data()
        );

        // ------------------------------------------------------------
        // 8. 验证传输结果
        // ------------------------------------------------------------
        for (std::size_t node = 0; node < nodeCount; ++node) {
            std::cout
                << "node=" << node
                << " result=" << result[node]
                << " remaining=" << remaining[node]
                << " state=" << state[node]
                << '\n';
        }

        return 0;
    } catch (const cl::Error& error) {
        std::cerr
            << "OpenCL error: "
            << error.what()
            << " (" << error.err() << ")\n";
        return 1;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}