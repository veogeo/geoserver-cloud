# GeoServer Cloud en una VM

Despliegue de GeoServer Cloud con Docker Compose, bajo la ruta `/geoserver/`. Esta composición vive en el overlay de VeoGeo; no modifica los Compose del upstream.

English version: [README.md](README.md).

## Requisitos

- Docker Engine y Docker Compose v2.
- OpenSSL para generar secretos.
- Para construir imágenes, el toolchain de desarrollo de este repositorio.

## Inicio rápido

Desde la raíz del checkout:

```bash
cd veogeo/deploy/vm
./deploy.sh
```

Sin opciones, el script descarga el tag `latest` de las imágenes GeoServer Cloud de GHCR, crea PostgreSQL local persistente e inicia el Nginx incluido. Genera `.env` con permisos `600` y contraseñas aleatorias; al terminar las imprime. Guarda las credenciales y conserva `.env` para reinicios. El script no sobrescribe un `.env` existente.

## Elegir el sabor

| Opción | Qué hace |
| --- | --- |
| `--images ghcr --tag TAG` | Descarga imágenes publicadas en GHCR. El tag predeterminado es `latest`. |
| `--images build` | Ejecuta `make deps install build-image` en el checkout y usa las imágenes locales `geoservercloud/*` con la versión declarada por el proyecto. Construye servicios upstream; los módulos adicionales de `veogeo/` usan su flujo de build propio. |
| `--postgres local` | Incluye PostgreSQL/PostGIS en Compose y guarda los datos en el volumen `pgdata`. |
| `--postgres external --pg-host HOST` | Usa una base PostgreSQL existente. La contraseña se solicita sin mostrarse; también puede pasarse por la variable de entorno `PGCONFIG_PASSWORD`. |
| `--nginx included` | Inicia el Nginx de esta composición en el puerto 80. Sirve HTTP; configura TLS antes de exponerlo públicamente. |
| `--nginx external` | No inicia Nginx aquí. Publica el gateway en `127.0.0.1:8080` y lo conecta a una red Docker para un proxy externo. |
| `--nginx none` | Publica el gateway directamente en `0.0.0.0:8080`. |

### Login OIDC opcional

Las imágenes GHCR de VeoGeo ya incluyen la extensión de login OIDC; permanece desactivada salvo que pases `--oidc`. Con `--images build`, el script construye localmente los mismos overlays de imágenes VeoGeo antes de iniciar el stack.

```bash
./deploy.sh \
  --images ghcr \
  --tag latest \
  --oidc \
  --oidc-issuer https://identity.example.org/realms/maps \
  --oidc-client-id geoserver-vm \
  --oidc-public-url https://maps.example.org
```

El script solicita el client secret OIDC sin mostrarlo. También puedes pasarlo mediante `OIDC_CLIENT_SECRET`. `--oidc-public-url` es el origen público sin `/geoserver`; el script establece el destino tras cerrar sesión en `/geoserver/web/`. Configura el cliente OIDC en el proveedor de identidad con las URLs de redirección de login y de cierre de sesión de GeoServer WebUI.

Para construir desde este checkout las mismas imágenes con OIDC, usa `--images build --oidc` con las mismas opciones de issuer, client ID y URL pública. El login de navegador es independiente de la validación de Bearer JWT en solicitudes API WMS/WFS/WCS; `--oidc` configura solamente el login de WebUI.

### Ejemplos

**GHCR, PostgreSQL local y Nginx incluido:**

```bash
./deploy.sh
```

**PostgreSQL externo y Nginx en otro Compose:**

```bash
./deploy.sh \
  --images ghcr \
  --tag latest \
  --postgres external \
  --pg-host db.example.internal \
  --pg-port 5432 \
  --pg-database geoserver \
  --pg-user geoserver \
  --nginx external
```

El script pedirá en modo oculto la contraseña de `geoserver` en la base existente. Esa misma base aloja los esquemas `pgconfig` y `acl`; prepara el usuario, la base y los permisos antes del primer arranque.

Si prefieres proporcionar la contraseña desde un gestor de secretos o el entorno del proceso:

```bash
PGCONFIG_PASSWORD='contraseña-de-la-base' ./deploy.sh \
  --postgres external --pg-host db.example.internal --nginx external
```

**Build local, PostgreSQL local y Nginx incluido:**

```bash
./deploy.sh --images build --postgres local --nginx included
```

## Nginx en otro Compose

Con `--nginx external`, el gateway está disponible como `gateway:8080` dentro de la red `<project>_edge`. El nombre de proyecto predeterminado es `veogeo-geoserver-cloud`, por lo que la red será `veogeo-geoserver-cloud_edge`.

En el Compose del proxy, declara esa red como externa y conecta ahí el servicio Nginx:

```yaml
services:
  nginx:
    image: nginx:1.29-alpine
    ports:
      - "80:80"
      - "443:443"
    networks:
      - geoserver_edge
    volumes:
      - ./geoserver.conf:/etc/nginx/conf.d/geoserver.conf:ro

networks:
  geoserver_edge:
    external: true
    name: veogeo-geoserver-cloud_edge
```

En la configuración del proxy, usa `proxy_pass http://gateway:8080;` y conserva la ruta `/geoserver/`. Reenvía `Host`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port` y `X-Forwarded-For`. La configuración de Nginx incluida en esta carpeta muestra esos encabezados.

No publiques el puerto del gateway en Internet cuando el proxy externo sea el punto de entrada. Si Nginx corre directamente en el host, puede conectarse al gateway publicado en `127.0.0.1:8080`.

## Administración

Ejecuta estos comandos desde `veogeo/deploy/vm`:

```bash
./deploy.sh --status
./deploy.sh --stop
docker compose --env-file .env -f compose.yml up -d
docker compose --env-file .env -f compose.yml logs -f
```

`--stop` conserva los datos. `docker compose down -v` también borra los volúmenes, incluido PostgreSQL local.

## Opciones del script

```bash
./deploy.sh --help
```

También puedes elegir `--project NAME`, `--pg-port PORT`, `--pg-database NAME` y `--pg-user USER`. Para usar PostgreSQL externo, configura previamente su base y credenciales. El directorio ignora `.env` para evitar que las contraseñas se agreguen al repositorio.
