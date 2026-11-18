ARG BASE_IMAGE=ghcr.io/veogeo/geoserver-cloud-webui:latest
FROM ${BASE_IMAGE}

# Runtime-loaded Veogeo JARs stay outside the upstream application archive.
COPY webui-libs/ /opt/additional_libs/
ENV AOT_MODE=off
