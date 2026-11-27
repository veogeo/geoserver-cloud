#!/usr/bin/env bash
set -Eeuo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"
ENV_FILE="$HERE/.env"
COMPOSE_FILE="$HERE/compose.yml"

usage() {
  cat <<'EOF'
Uso:
  ./deploy.sh [opciones]
  ./deploy.sh --stop
  ./deploy.sh --status

Opciones:
  --images ghcr|build       Descargar imágenes publicadas o construir desde este repo (default: ghcr)
  --tag TAG                 Tag GHCR (default: latest)
  --postgres local|external PostgreSQL en este Compose o existente (default: local)
  --pg-host HOST            Host obligatorio con --postgres external
  --pg-port PORT            Puerto PostgreSQL (default: 5432)
  --pg-database NAME        Base de catálogo y ACL (default: geoserver)
  --pg-user USER            Usuario de PostgreSQL (default: geoserver)
  PGCONFIG_PASSWORD=...     Contraseña de PostgreSQL externo; si falta, se solicita sin mostrarla
  --oidc                    Activar login OIDC en GeoServer WebUI
  --oidc-issuer URL         Issuer del proveedor OIDC (requerido con --oidc)
  --oidc-client-id ID       Client ID (requerido con --oidc)
  OIDC_CLIENT_SECRET=...    Client secret; si falta, se solicita sin mostrarlo
  --oidc-public-url URL     Origen público, p. ej. https://maps.example.org (requerido con --oidc)
  --oidc-discovery-url URL  URL discovery opcional; por defecto issuer/.well-known/openid-configuration
  --nginx included|external|none
                            Nginx en este Compose, en otro Compose o sin Nginx (default: included)
  --project NAME            Nombre del proyecto Compose (default: veogeo-geoserver-cloud)
  -h, --help                Mostrar esta ayuda

Para Nginx externo, el gateway queda publicado en 127.0.0.1:8080 y también
conectado a la red Docker <project>_edge. El otro Compose puede unirse a esa red.
EOF
}

images=ghcr
tag=latest
postgres=local
pg_host=
pg_port=5432
pg_database=geoserver
pg_user=geoserver
nginx=included
project=veogeo-geoserver-cloud
action=deploy
oidc=false
oidc_issuer=
oidc_client_id=
oidc_public_url=
oidc_discovery_url=

while (($#)); do
  case "$1" in
    --images) images="${2:?falta valor para --images}"; shift 2 ;;
    --tag) tag="${2:?falta valor para --tag}"; shift 2 ;;
    --postgres) postgres="${2:?falta valor para --postgres}"; shift 2 ;;
    --pg-host) pg_host="${2:?falta valor para --pg-host}"; shift 2 ;;
    --pg-port) pg_port="${2:?falta valor para --pg-port}"; shift 2 ;;
    --pg-database) pg_database="${2:?falta valor para --pg-database}"; shift 2 ;;
    --pg-user) pg_user="${2:?falta valor para --pg-user}"; shift 2 ;;
    --oidc) oidc=true; shift ;;
    --oidc-issuer) oidc_issuer="${2:?falta valor para --oidc-issuer}"; shift 2 ;;
    --oidc-client-id) oidc_client_id="${2:?falta valor para --oidc-client-id}"; shift 2 ;;
    --oidc-public-url) oidc_public_url="${2:?falta valor para --oidc-public-url}"; shift 2 ;;
    --oidc-discovery-url) oidc_discovery_url="${2:?falta valor para --oidc-discovery-url}"; shift 2 ;;
    --nginx) nginx="${2:?falta valor para --nginx}"; shift 2 ;;
    --project) project="${2:?falta valor para --project}"; shift 2 ;;
    --stop) action=stop; shift ;;
    --status) action=status; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Opción desconocida: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ "$action" != deploy ]]; then
  [[ -f "$ENV_FILE" ]] || { echo "No existe $ENV_FILE; ejecuta primero ./deploy.sh" >&2; exit 1; }
  if [[ "$action" == stop ]]; then
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down
  else
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
  fi
  exit
fi

case "$images" in ghcr|build) ;; *) echo "--images debe ser ghcr o build" >&2; exit 2 ;; esac
case "$postgres" in local|external) ;; *) echo "--postgres debe ser local o external" >&2; exit 2 ;; esac
case "$nginx" in included|external|none) ;; *) echo "--nginx debe ser included, external o none" >&2; exit 2 ;; esac
if [[ "$postgres" == external && -z "$pg_host" ]]; then
  echo "Con --postgres external especifica --pg-host." >&2; exit 2
fi
if [[ "$oidc" == true ]]; then
  [[ -n "$oidc_issuer" ]] || { echo "Con --oidc especifica --oidc-issuer." >&2; exit 2; }
  [[ -n "$oidc_client_id" ]] || { echo "Con --oidc especifica --oidc-client-id." >&2; exit 2; }
  [[ -n "$oidc_public_url" ]] || { echo "Con --oidc especifica --oidc-public-url." >&2; exit 2; }
fi
if [[ -e "$ENV_FILE" ]]; then
  echo "$ENV_FILE ya existe. Consérvalo para mantener credenciales; bórralo solo si vas a configurar una instalación nueva." >&2
  exit 1
fi

secret() { openssl rand -hex 24; }
pg_password="${PGCONFIG_PASSWORD:-}"
if [[ "$postgres" == local ]]; then
  pg_password="$(secret)"
elif [[ -z "$pg_password" ]]; then
  read -r -s -p "Contraseña del usuario PostgreSQL $pg_user@$pg_host: " pg_password
  printf '\n'
  [[ -n "$pg_password" ]] || { echo "La contraseña de PostgreSQL no puede estar vacía." >&2; exit 2; }
fi
rabbit_password="$(secret)"
acl_admin_password="$(secret)"
acl_geoserver_password="$(secret)"
oidc_client_secret="${OIDC_CLIENT_SECRET:-}"
if [[ "$oidc" == true && -z "$oidc_client_secret" ]]; then
  read -r -s -p "Client secret del proveedor OIDC: " oidc_client_secret
  printf '\n'
  [[ -n "$oidc_client_secret" ]] || { echo "El client secret OIDC no puede estar vacío." >&2; exit 2; }
fi
uid="$(id -u)"
gid="$(id -g)"
profiles=
[[ "$postgres" == local ]] && profiles=local-postgres
[[ "$nginx" == included ]] && profiles="${profiles:+$profiles,}nginx"
gateway_bind=127.0.0.1
[[ "$nginx" == none ]] && gateway_bind=0.0.0.0

if [[ "$postgres" == local ]]; then pg_host=geoserverdb; fi
if [[ "$images" == ghcr ]]; then
  image_prefix=ghcr.io/veogeo
  image_tag="$tag"
  config_image="$image_prefix/geoserver-cloud-config:$image_tag"
  gateway_image="$image_prefix/geoserver-cloud-gateway:$image_tag"
  webui_image="$image_prefix/geoserver-cloud-webui:$image_tag"
  rest_image="$image_prefix/geoserver-cloud-rest:$image_tag"
  wfs_image="$image_prefix/geoserver-cloud-wfs:$image_tag"
  wms_image="$image_prefix/geoserver-cloud-wms:$image_tag"
  wcs_image="$image_prefix/geoserver-cloud-wcs:$image_tag"
  wps_image="$image_prefix/geoserver-cloud-wps:$image_tag"
  gwc_image="$image_prefix/geoserver-cloud-gwc:$image_tag"
else
  image_prefix=geoservercloud
  image_tag="$(cd "$REPO_ROOT" && ./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)"
  config_image="$image_prefix/geoserver-cloud-config:$image_tag"
  gateway_image="$image_prefix/geoserver-cloud-gateway:$image_tag"
  webui_image="$image_prefix/geoserver-cloud-webui:$image_tag"
  rest_image="$image_prefix/geoserver-cloud-rest:$image_tag"
  wfs_image="$image_prefix/geoserver-cloud-wfs:$image_tag"
  wms_image="$image_prefix/geoserver-cloud-wms:$image_tag"
  wcs_image="$image_prefix/geoserver-cloud-wcs:$image_tag"
  wps_image="$image_prefix/geoserver-cloud-wps:$image_tag"
  gwc_image="$image_prefix/geoserver-cloud-gwc:$image_tag"
  if [[ "$oidc" == true ]]; then
    webui_image=geoserver-cloud-webui-oidc:local
    rest_image=geoserver-cloud-rest-oidc:local
    wfs_image=geoserver-cloud-wfs-oidc:local
    wms_image=geoserver-cloud-wms-oidc:local
    wcs_image=geoserver-cloud-wcs-oidc:local
    wps_image=geoserver-cloud-wps-oidc:local
    gwc_image=geoserver-cloud-gwc-oidc:local
  fi
fi

oidc_enabled=false
oidc_post_logout_redirect_uri=
oidc_effective_discovery_url=
if [[ "$oidc" == true ]]; then
  oidc_enabled=true
  oidc_effective_discovery_url="${oidc_discovery_url:-${oidc_issuer%/}/.well-known/openid-configuration}"
  oidc_post_logout_redirect_uri="${oidc_public_url%/}/geoserver/web/"
fi

umask 077
cat > "$ENV_FILE" <<EOF
COMPOSE_PROJECT_NAME=$project
COMPOSE_PROFILES=$profiles
GSC_IMAGE_PREFIX=$image_prefix
GSC_TAG=$image_tag
CONFIG_IMAGE=$config_image
GATEWAY_IMAGE=$gateway_image
WEBUI_IMAGE=$webui_image
REST_IMAGE=$rest_image
WFS_IMAGE=$wfs_image
WMS_IMAGE=$wms_image
WCS_IMAGE=$wcs_image
WPS_IMAGE=$wps_image
GWC_IMAGE=$gwc_image
GSC_UID=$uid
GSC_GID=$gid
PGCONFIG_HOST=$pg_host
PGCONFIG_PORT=$pg_port
PGCONFIG_DATABASE=$pg_database
PGCONFIG_USERNAME=$pg_user
PGCONFIG_PASSWORD=$pg_password
RABBITMQ_USER=geoserver
RABBITMQ_PASSWORD=$rabbit_password
ACL_ADMIN_PASSWORD=$acl_admin_password
ACL_GEOSERVER_PASSWORD=$acl_geoserver_password
GATEWAY_BIND=$gateway_bind
GATEWAY_PORT=8080
OIDC_ENABLED=$oidc_enabled
OIDC_ISSUER_URI=$oidc_issuer
OIDC_CLIENT_ID=$oidc_client_id
OIDC_CLIENT_SECRET=$oidc_client_secret
OIDC_DISCOVERY_URI=$oidc_effective_discovery_url
OIDC_PUBLIC_URL=$oidc_public_url
OIDC_POST_LOGOUT_REDIRECT_URI=$oidc_post_logout_redirect_uri
EOF
chmod 600 "$ENV_FILE"

compose=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
if [[ "$images" == ghcr ]]; then
  "${compose[@]}" pull
fi
if [[ "$images" == build ]]; then
  echo "Construyendo imágenes GeoServer Cloud locales (puede tardar bastante)..."
  # Build dependencies and applications without running the test target.
  (cd "$REPO_ROOT" && make deps install build-image TAG="$image_tag")
  if [[ "$oidc" == true ]]; then
    echo "Construyendo las imágenes VeoGeo con soporte OIDC..."
    (
      cd "$REPO_ROOT"
      WEBUI_BASE_IMAGE="geoservercloud/geoserver-cloud-webui:$image_tag" \
      REST_BASE_IMAGE="geoservercloud/geoserver-cloud-rest:$image_tag" \
      WFS_BASE_IMAGE="geoservercloud/geoserver-cloud-wfs:$image_tag" \
      WMS_BASE_IMAGE="geoservercloud/geoserver-cloud-wms:$image_tag" \
      WCS_BASE_IMAGE="geoservercloud/geoserver-cloud-wcs:$image_tag" \
      WPS_BASE_IMAGE="geoservercloud/geoserver-cloud-wps:$image_tag" \
      GWC_BASE_IMAGE="geoservercloud/geoserver-cloud-gwc:$image_tag" \
      ./veogeo/build-overlays.sh
    )
  fi
fi

if [[ "$postgres" == local ]]; then
  "${compose[@]}" up -d geoserverdb
  db_id="$("${compose[@]}" ps -q geoserverdb)"
  for _ in {1..60}; do
    health="$(docker inspect -f '{{.State.Health.Status}}' "$db_id" 2>/dev/null || true)"
    [[ "$health" == healthy ]] && break
    [[ "$health" == unhealthy ]] && { echo "PostgreSQL quedó unhealthy." >&2; exit 1; }
    sleep 2
  done
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$db_id")" == healthy ]] || { echo "Timeout esperando PostgreSQL." >&2; exit 1; }
fi

"${compose[@]}" up -d

case "$nginx" in
  included) public_url='http://<IP-o-dominio-de-la-VM>/geoserver/' ;;
  external) public_url='http://<IP-o-dominio-de-la-VM>/geoserver/ (según el proxy externo)' ;;
  none) public_url='http://<IP-de-la-VM>:8080/geoserver/' ;;
esac

cat <<EOF

GeoServer Cloud quedó desplegado.
URL: $public_url
Red para proxy externo: ${project}_edge

Secretos generados (guárdalos ahora; el archivo .env tiene permisos 600):
  PostgreSQL:             $pg_password
  RabbitMQ:               $rabbit_password
  GeoServer ACL admin:    $acl_admin_password
  GeoServer ACL usuario:  $acl_geoserver_password

Detener sin borrar datos:
  ./deploy.sh --stop
Volver a levantar usando el mismo .env y las mismas contraseñas:
  docker compose --env-file .env -f compose.yml up -d
Estado:
  ./deploy.sh --status
EOF
