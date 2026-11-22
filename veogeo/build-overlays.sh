#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
veogeo_dir="$repo_root/veogeo"
revision="${REVISION:-3.1.0-SNAPSHOT}"

build_module() {
  local module_path="$1"
  local artifact_id="$2"
  local image_context_name="$3"
  local image_tag="$4"
  local dockerfile="$5"
  local base_image="$6"
  local include_artifacts="$7"
  local library_dir="${8:-${image_context_name}-libs}"
  local output_dir="$veogeo_dir/target/image-contexts/$image_context_name"

  rm -rf "$output_dir"
  mkdir -p "$output_dir"
  mkdir -p "$output_dir/$library_dir"
  "$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module_path" -Drevision="$revision" -DskipTests clean package
  "$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module_path" -Drevision="$revision" dependency:copy-dependencies -DincludeScope=runtime -DincludeArtifactIds="$include_artifacts" -DoutputDirectory="$output_dir/$library_dir"
  cp "$veogeo_dir/$module_path/target/$artifact_id-$revision.jar" "$output_dir/$library_dir/"
  cp "$veogeo_dir/images/$dockerfile" "$output_dir/Dockerfile"
  docker build -f "$output_dir/Dockerfile" \
    --build-arg "BASE_IMAGE=$base_image" \
    -t "$image_tag" "$output_dir"
}

OIDC_WEBUI_ARTIFACTS="gs-sec-oidc-core,gs-sec-oidc-web,spring-security-oauth2-core,spring-security-oauth2-client,spring-security-oauth2-resource-server,spring-security-oauth2-jose,oauth2-oidc-sdk,jcip-annotations,content-type,lang-tag,nimbus-jose-jwt,json-path,json-smart,accessors-smart,asm,httpclient5,httpcore5,httpcore5-h2"
OIDC_SERVICE_ARTIFACTS="gs-sec-oidc-core,spring-security-oauth2-core,spring-security-oauth2-client,spring-security-oauth2-resource-server,spring-security-oauth2-jose,oauth2-oidc-sdk,jcip-annotations,content-type,lang-tag,nimbus-jose-jwt,json-path,json-smart,accessors-smart,asm,httpclient5,httpcore5,httpcore5-h2"

build_module security/oidc-login veogeo-geoserver-cloud-oidc-login webui \
  "${WEBUI_OVERLAY_IMAGE:-geoserver-cloud-webui-oidc:local}" webui.Dockerfile \
  "${WEBUI_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-webui:latest}" \
  "$OIDC_WEBUI_ARTIFACTS" webui-libs

# The WebUI installs the interactive login filter. Other GeoServer apps only
# need the OIDC core provider so they can read that shared filter config.
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-rest \
  "${REST_OVERLAY_IMAGE:-geoserver-cloud-rest-oidc:local}" oidc-app.Dockerfile \
  "${REST_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-rest:latest}" "$OIDC_SERVICE_ARTIFACTS" oidc-libs
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-wfs \
  "${WFS_OVERLAY_IMAGE:-geoserver-cloud-wfs-oidc:local}" oidc-app.Dockerfile \
  "${WFS_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-wfs:latest}" "$OIDC_SERVICE_ARTIFACTS" oidc-libs
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-wms \
  "${WMS_OVERLAY_IMAGE:-geoserver-cloud-wms-oidc:local}" oidc-app.Dockerfile \
  "${WMS_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-wms:latest}" "$OIDC_SERVICE_ARTIFACTS" oidc-libs
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-wcs \
  "${WCS_OVERLAY_IMAGE:-geoserver-cloud-wcs-oidc:local}" oidc-app.Dockerfile \
  "${WCS_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-wcs:latest}" "$OIDC_SERVICE_ARTIFACTS" oidc-libs
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-wps \
  "${WPS_OVERLAY_IMAGE:-geoserver-cloud-wps-oidc:local}" oidc-app.Dockerfile \
  "${WPS_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-wps:latest}" "$OIDC_SERVICE_ARTIFACTS" oidc-libs

GWC_BASE="${GWC_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-gwc:latest}"
GWC_MULTIDIM_IMAGE="${GWC_MULTIDIM_IMAGE:-geoserver-cloud-gwc-multidimensional:local}"
build_module gwc/multidimensional veogeo-geoserver-cloud-gwc-multidimensional gwc \
  "$GWC_MULTIDIM_IMAGE" gwc.Dockerfile \
  "$GWC_BASE" \
  "gs-wmts-multi-dimensional"
build_module security/oidc-login veogeo-geoserver-cloud-oidc-login oidc-gwc \
  "${GWC_OVERLAY_IMAGE:-geoserver-cloud-gwc-oidc:local}" oidc-app.Dockerfile \
  "$GWC_MULTIDIM_IMAGE" "$OIDC_SERVICE_ARTIFACTS" oidc-libs
