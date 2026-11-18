# GeoServer Cloud Veogeo overlays

This directory contains Veogeo-owned extensions that run on top of GeoServer Cloud. It is deliberately outside `src/` and is not listed by the upstream Maven reactor, so updating GeoServer Cloud does not require merging project code into service POMs or auto-configuration classes. Java sources and tests stay under each overlay's own `src/main` and `src/test` directories, with separate Maven POMs and runtime Dockerfiles.

## Included overlays

- `security/oidc-login`: generic OpenID Connect browser login for the GeoServer WebUI. It reads the provider's discovery document and allows discovery endpoints, endpoint overrides, claims, scopes, filter name, redirect URL, and administrator mapping to be configured through `geoserver.extension.security.oidc-login.*` properties.
- `security/gateway-bearer-auth`: optional Bearer JWT validation for GeoServer services plus the gateway fallback security chain. It has no provider-specific default role-claim path; `groups` is the default and claim mappings are configurable.
- `gwc/multidimensional`: registers the WMTS multidimensional operations when GWC is enabled.

Each JAR declares its own Spring Boot `AutoConfiguration.imports` resource. The GeoServer Cloud base image already supports runtime JARs in `/opt/additional_libs` through `LOADER_PATH`; overlay images copy the JARs there and disable the base image's precomputed AOT cache so it does not omit runtime-loaded configuration.

## Build and deploy

The overlay Maven reactor inherits dependency versions from `../src/pom.xml`, but it does not modify or join that reactor. The corresponding GeoServer Cloud and GeoServer artifacts for the selected revision must already be available in the local Maven repository. Build the upstream base once, then run:

```sh
./veogeo/build-overlays.sh
```

The script builds three thin images over the configured GHCR images. The result tags default to `geoserver-cloud-webui-oidc:local`, `geoserver-cloud-gwc-multidimensional:local`, and `geoserver-cloud-gateway-bearer:local`; base and output tags can be overridden with the environment variables used by the script.

The script stages dependency JARs and Docker build contexts under `veogeo/target/image-contexts/`; it does not write generated JARs beside the maintained source or Dockerfiles. To edit an overlay, change its files under `veogeo/<area>/<name>/src/`; to rebuild its JAR and images, run the script above.

For a different identity provider, configure the generic OIDC properties. At minimum set `GEOSERVER_EXTENSION_SECURITY_OIDC_LOGIN_ENABLED=true`, `..._CLIENT_ID`, `..._CLIENT_SECRET`, and `..._ISSUER_URI`; set `..._DISCOVERY_URI` only when the provider does not use the standard discovery path derived from its issuer. Configure endpoint overrides only when provider metadata advertises URLs that are unreachable from the service network.

## Upstream maintenance boundary

All product-specific Java, XML, Maven, image, and test files in this directory can be reviewed and released independently. Changes to upstream GeoServer Cloud versions are consumed through the inherited parent and dependency versions; no upstream source files need project-specific edits. The ACL compatibility patch, when needed for a particular dependency revision, belongs under `patches/` and is applied only by the overlay build process.
