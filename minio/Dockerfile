FROM golang:1.24.8-bookworm AS build
WORKDIR /src
# Build a fixed upstream release without relying on withdrawn registry images.
RUN git clone --depth 1 --branch RELEASE.2025-10-15T17-29-55Z https://github.com/minio/minio.git .
RUN CGO_ENABLED=0 go build -trimpath -o /out/minio .

FROM debian:bookworm-slim
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /src/NOTICE /usr/share/doc/minio/
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
