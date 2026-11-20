# GeoServer Cloud Veogeo overlays

This directory contains Veogeo-owned extensions for GeoServer Cloud. Java sources and tests stay under each module's own `src/main` and `src/test` directories. The service Bearer module stays in this overlay. A build-only patch adds it to the GeoServer Maven reactor and each GeoServer application during image builds; the upstream POM files remain untouched in the repository.

## Included overlays

- `security/oidc-login`: generic OpenID Connect browser login for the GeoServer WebUI. It reads the provider's discovery document and allows discovery endpoints, endpoint overrides, claims, scopes, filter name, redirect URL, and administrator mapping to be configured through `geoserver.extension.security.oidc-login.*` properties.
- `security/service-bearer-auth`: optional Bearer JWT validation executed inside each GeoServer application. It has no provider-specific default role-claim path; `groups` is the default and claim mappings are configurable.
- `gwc/multidimensional`: registers the WMTS multidimensional operations when GWC is enabled.

Each module declares its own Spring Boot `AutoConfiguration.imports` resource. The Bearer validator is a normal Maven dependency of WFS, WMS, WCS, WPS, GWC, REST and WebUI; it is packaged inside each executable service JAR. It does not rely on a gateway, proxy, or runtime-added JAR.

## Build and deploy

The VeoGeo modules inherit dependency versions from `../src/pom.xml`. The `manual-cn-vg-ghcr` workflow applies `patches/service-bearer-auth-apps.patch` to its temporary checkout before `make`; Maven then builds the validator as part of the service reactor and packages it into every app. The patch is not committed into the upstream POM files. The corresponding GeoServer Cloud and GeoServer artifacts for the selected revision must already be available in the local Maven repository. Build the upstream base once, then run:

```sh
./veogeo/build-overlays.sh
```

The script builds two thin images over the configured GHCR images: `geoserver-cloud-webui-oidc:local` and `geoserver-cloud-gwc-multidimensional:local`. It does not build a gateway Bearer image.

The script stages dependency JARs and Docker build contexts under `veogeo/target/image-contexts/`; it does not write generated JARs beside the maintained source or Dockerfiles. To edit an overlay, change its files under `veogeo/<area>/<name>/src/`; to rebuild the WebUI and GWC overlays, run the script above. The service Bearer code is included whenever service applications are built.

For a different identity provider, configure the generic OIDC properties. At minimum set `GEOSERVER_EXTENSION_SECURITY_OIDC_LOGIN_ENABLED=true`, `..._CLIENT_ID`, `..._CLIENT_SECRET`, and `..._ISSUER_URI`; set `..._DISCOVERY_URI` only when the provider does not use the standard discovery path derived from its issuer. Configure endpoint overrides only when provider metadata advertises URLs that are unreachable from the service network.

## Upstream maintenance boundary

All product-specific Java, XML, Maven, image, and test files in this directory can be reviewed and released independently. Changes to upstream GeoServer Cloud versions are consumed through the inherited parent and dependency versions; no upstream source files need project-specific edits. The ACL compatibility patch, when needed for a particular dependency revision, belongs under `patches/` and is applied only by the overlay build process.
