# GeoServer Cloud on a VM

Deploy GeoServer Cloud on a VM with Docker Compose under `/geoserver/`. This composition lives in the VeoGeo overlay and leaves the upstream Compose files untouched.

Versión en español: [README.es.md](README.es.md).

## Requirements

- Docker Engine and Docker Compose v2.
- OpenSSL to generate secrets.
- The repository development toolchain for local image builds.

## Quick start

From the repository checkout:

```bash
cd veogeo/deploy/vm
./deploy.sh
```

With no options, the script pulls the `latest` GeoServer Cloud images from GHCR, starts local persistent PostgreSQL, and enables the included Nginx service. It writes `.env` with `600` permissions, generates random passwords, and prints them when deployment finishes. Save those credentials and keep `.env` for restarts. The script will not overwrite an existing `.env` file.

## Choose a deployment flavor

| Option | What it does |
| --- | --- |
| `--images ghcr --tag TAG` | Pulls published GeoServer Cloud images from GHCR. The default tag is `latest`. |
| `--images build` | Runs `make deps install build-image` from the checkout and uses local `geoservercloud/*` images tagged with the project version. This builds upstream services; additional modules under `veogeo/` use their own build workflow. |
| `--postgres local` | Runs PostgreSQL/PostGIS in this Compose project and stores data in the `pgdata` volume. |
| `--postgres external --pg-host HOST` | Uses an existing PostgreSQL database. The script prompts for its password without echoing it; you can also provide it through the `PGCONFIG_PASSWORD` environment variable. |
| `--nginx included` | Starts the Nginx service in this Compose project on port 80. It serves HTTP; configure TLS before exposing it publicly. |
| `--nginx external` | Does not start Nginx here. Publishes the gateway on `127.0.0.1:8080` and connects it to a Docker network for an external proxy. |
| `--nginx none` | Publishes the gateway directly on `0.0.0.0:8080`. |

### Examples

**GHCR images, local PostgreSQL, and included Nginx:**

```bash
./deploy.sh
```

**External PostgreSQL and Nginx in another Compose project:**

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

The script securely prompts for the password of the `geoserver` PostgreSQL user. The same database hosts the `pgconfig` and `acl` schemas; prepare the database, user, and permissions before the first startup.

To supply the password through a secret manager or the process environment:

```bash
PGCONFIG_PASSWORD='database-password' ./deploy.sh \
  --postgres external --pg-host db.example.internal --nginx external
```

**Build images locally, with local PostgreSQL and included Nginx:**

```bash
./deploy.sh --images build --postgres local --nginx included
```

## Nginx in another Compose project

With `--nginx external`, the gateway is available at `gateway:8080` on the `<project>_edge` Docker network. The default project name is `veogeo-geoserver-cloud`, so the network name is `veogeo-geoserver-cloud_edge`.

In the proxy's Compose file, declare that network as external and connect the Nginx service to it:

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

In the proxy configuration, use `proxy_pass http://gateway:8080;` and preserve the `/geoserver/` path. Forward the `Host`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-Port`, and `X-Forwarded-For` headers. The Nginx configuration included in this directory shows these headers.

Do not expose the gateway port to the Internet when the external proxy is the entry point. If Nginx runs directly on the VM host, it can connect to the gateway at `127.0.0.1:8080`.

## Administration

Run these commands from `veogeo/deploy/vm`:

```bash
./deploy.sh --status
./deploy.sh --stop
docker compose --env-file .env -f compose.yml up -d
docker compose --env-file .env -f compose.yml logs -f
```

`--stop` preserves data. `docker compose down -v` also deletes volumes, including local PostgreSQL data.

## Script options

```bash
./deploy.sh --help
```

You can also set `--project NAME`, `--pg-port PORT`, `--pg-database NAME`, and `--pg-user USER`. For external PostgreSQL, prepare the database and credentials first. This directory ignores `.env` so passwords are not accidentally committed to the repository.
