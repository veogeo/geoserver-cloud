ARG BASE_IMAGE=ghcr.io/veogeo/geoserver-cloud-gateway:latest
FROM ${BASE_IMAGE}

# Runtime-loaded Veogeo JARs stay outside the upstream application archive.
COPY gateway-libs/ /opt/additional_libs/
ENV AOT_MODE=off
