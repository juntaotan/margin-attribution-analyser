# CUDA TreeBuilder Skeleton

This directory contains a standalone, GPU-accelerated implementation skeleton of the `TreeBuilder` pipeline for the Margin Attribution Analyzer.

It is completely decoupled from the Spring Boot backend and React frontend (no build-system ties, no bindings or linkages configured).

## Overview

The CUDA TreeBuilder ports the recursive BOM/inventory dependency tracing into a massive data-parallel GPU architecture:

1. **Dictionary Encoding (Host-side preparation)**:
   - String identifiers (product numbers, order numbers) are encoded as contiguous 32-bit integers (`uint32_t`).
2. **Structure of Arrays (SoA)**:
   - Production and inventory usage records are arranged contiguously in device memory to maximize memory coalescing and GPU throughput.
3. **Multi-level Reverse BFS Frontier Expansion**:
   - Starting from sold target products, a double-buffered frontier and atomic bitmask iteratively discover upstream material dependencies layer-by-layer.
   - Material quantities and total costs are accumulated in device memory using hardware-accelerated `atomicAdd`.
4. **Parallel CSR Construction (via Thrust/CUB)**:
   - Dependency edges (`material -> product`) are sorted and deduplicated on the device.
   - Out-degree counting and exclusive prefix scan (`thrust::exclusive_scan`) construct the standard Compressed Sparse Row (CSR) arrays (`offset` and `successors`), exactly matching the Java `CsrGraph` topology.

## Directory Layout

```
cuda/
├── CMakeLists.txt              # Standalone CMake build definition
├── README.md                   # Architecture & documentation
├── include/
│   └── tree_builder/
│       ├── types.cuh           # SoA records, Edge, and GpuCsrGraph definitions
│       └── tree_builder.cuh    # CudaTreeBuilder host interface and kernel signatures
└── src/
    ├── tree_builder.cu         # Host orchestrator and Thrust primitives
    └── tree_builder_kernels.cu # Device CUDA kernels (expansion, aggregation, CSR index)
```

## Building the Library

### Prerequisites

- NVIDIA CUDA Toolkit (>= 11.0, tested with CUDA 12/13)
- CMake (>= 3.18)
- C++17 compliant host compiler (`gcc` / `g++` >= 9.0)

### Compilation Steps

```bash
cd cuda
mkdir build && cd build
cmake ..
cmake --build .
```

This produces `libtree_builder_cuda.a` as a standalone static library.

## Future Integration Pathways

When you are ready to link this module with the application:
- **JNI (Java Native Interface)**: Export C-compatible wrapper functions (`extern "C"`) calling `CudaTreeBuilder`.
- **Project Panama (Foreign Function & Memory API, Java 22+)**: Call native entry points directly with zero-copy off-heap memory buffers (`MemorySegment`).
- **Python / Microservice Sidecar**: Expose via Pybind11 or gRPC/C++ microservice.
