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
  local output_dir="$veogeo_dir/target/image-contexts/$image_context_name"
  local library_dir="${image_context_name}-libs"

  rm -rf "$output_dir"
  mkdir -p "$output_dir"
  mkdir -p "$output_dir/$library_dir"
  "$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module_path" -Drevision="$revision" clean package
  "$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module_path" -Drevision="$revision" dependency:copy-dependencies -DincludeScope=runtime -DincludeArtifactIds="$include_artifacts" -DoutputDirectory="$output_dir/$library_dir"
  cp "$veogeo_dir/$module_path/target/$artifact_id-$revision.jar" "$output_dir/$library_dir/"
  cp "$veogeo_dir/images/$dockerfile" "$output_dir/Dockerfile"
  docker build -f "$output_dir/Dockerfile" \
    --build-arg "BASE_IMAGE=$base_image" \
    -t "$image_tag" "$output_dir"
}

build_module security/oidc-login veogeo-geoserver-cloud-oidc-login webui \
  "${WEBUI_OVERLAY_IMAGE:-geoserver-cloud-webui-oidc:local}" webui.Dockerfile \
  "${WEBUI_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-webui:latest}" \
  "gs-sec-oidc-core,gs-sec-oidc-web,spring-security-oauth2-core,spring-security-oauth2-client,spring-security-oauth2-resource-server,spring-security-oauth2-jose,oauth2-oidc-sdk,jcip-annotations,content-type,lang-tag,nimbus-jose-jwt,json-path,json-smart,accessors-smart,asm,httpclient5,httpcore5,httpcore5-h2"
build_module gwc/multidimensional veogeo-geoserver-cloud-gwc-multidimensional gwc \
  "${GWC_OVERLAY_IMAGE:-geoserver-cloud-gwc-multidimensional:local}" gwc.Dockerfile \
  "${GWC_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-gwc:latest}" \
  "gs-wmts-multi-dimensional"
build_module security/gateway-bearer-auth veogeo-geoserver-cloud-gateway-bearer-auth gateway \
  "${GATEWAY_OVERLAY_IMAGE:-geoserver-cloud-gateway-bearer:local}" gateway.Dockerfile \
  "${GATEWAY_BASE_IMAGE:-ghcr.io/veogeo/geoserver-cloud-gateway:latest}" \
  "spring-security-config,spring-security-core,spring-security-crypto,spring-security-web,spring-security-oauth2-core,spring-security-oauth2-resource-server,spring-security-oauth2-jose,nimbus-jose-jwt,oauth2-oidc-sdk,jcip-annotations,content-type,lang-tag,json-smart,accessors-smart,asm"
