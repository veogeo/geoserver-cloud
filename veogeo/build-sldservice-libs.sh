#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
veogeo_dir="$repo_root/veogeo"
revision="${REVISION:-3.1.0-SNAPSHOT}"
output_dir="${1:-$veogeo_dir/target/sldservice-libs}"
module="restconfig/sldservice"
artifact="veogeo-geoserver-cloud-restconfig-sldservice"

mkdir -p "$output_dir"
rm -f "$output_dir"/gs-sldservice-*.jar "$output_dir"/"$artifact"-*.jar

"$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module" \
  -Drevision="$revision" -Dmaven.test.skip=true clean package
"$repo_root/mvnw" -f "$veogeo_dir/pom.xml" -pl "$module" \
  -Drevision="$revision" dependency:copy-dependencies \
  -DincludeScope=provided -DincludeArtifactIds=gs-sldservice \
  -DoutputDirectory="$output_dir"

cp "$veogeo_dir/$module/target/$artifact-$revision.jar" "$output_dir/"
printf 'GeoServer Cloud SLDService libraries written to %s\n' "$output_dir"
