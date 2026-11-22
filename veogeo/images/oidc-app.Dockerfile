ARG BASE_IMAGE=ghcr.io/veogeo/geoserver-cloud-rest:latest
FROM ${BASE_IMAGE}

# Shared GeoServer security configuration may contain an OIDC login filter.
COPY oidc-libs/ /opt/additional_libs/
ENV AOT_MODE=off
