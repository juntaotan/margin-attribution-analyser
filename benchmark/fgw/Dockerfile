# Build context: repository root. Both stages use Python 3.12.
ARG CPU_IMAGE=python:3.12-slim-bookworm
ARG CUDA_IMAGE=nvidia/cuda:12.8.1-devel-ubuntu24.04
FROM ${CPU_IMAGE} AS cpu
RUN apt-get update && apt-get install -y --no-install-recommends cmake g++ make \
    && rm -rf /var/lib/apt/lists/*
COPY benchmark/fgw/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY backend/src/main/resources/cuda /src/backend/src/main/resources/cuda
COPY benchmark/fgw /src/benchmark/fgw
RUN cmake -S /src/backend/src/main/resources/cuda -B /build -DFGW_CPU_ONLY=ON -DCMAKE_BUILD_TYPE=Release \
    && cmake --build /build -j 4
WORKDIR /src
CMD ["ctest", "--test-dir", "/build", "--output-on-failure"]

FROM ${CUDA_IMAGE} AS cuda
RUN apt-get update && apt-get install -y --no-install-recommends cmake g++ make python3 python3-venv \
    && rm -rf /var/lib/apt/lists/*
COPY benchmark/fgw/requirements.txt /tmp/requirements.txt
RUN python3 -m venv /venv && /venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt
ENV PATH="/venv/bin:${PATH}"
COPY backend/src/main/resources/cuda /src/backend/src/main/resources/cuda
COPY benchmark/fgw /src/benchmark/fgw
# Configurable real/virtual architectures; the PTX image permits forward JIT.
ARG CUDA_ARCHITECTURES=75
RUN cmake -S /src/backend/src/main/resources/cuda -B /build -DFGW_ONLY=ON -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CUDA_ARCHITECTURES=${CUDA_ARCHITECTURES} && cmake --build /build -j 4
WORKDIR /src
CMD ["ctest", "--test-dir", "/build", "--output-on-failure"]
