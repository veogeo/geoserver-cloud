ARG BASE_IMAGE=ghcr.io/veogeo/geoserver-cloud-gwc:latest
FROM ${BASE_IMAGE}

# Runtime-loaded Veogeo JARs stay outside the upstream application archive.
COPY gwc-libs/ /opt/additional_libs/
ENV AOT_MODE=off
