# OpenID Connect WebUI login overlay

This auto-configuration is provider-agnostic. It reads the discovery document and applies optional endpoint overrides. Configuration uses the prefix `geoserver.extension.security.oidc-login` (environment form: `GEOSERVER_EXTENSION_SECURITY_OIDC_LOGIN_*`).

Required values when enabled:

- `enabled=true`
- `client-id`
- `client-secret`
- `issuer-uri`

Optional values include `discovery-uri`, `authorization-uri`, `token-uri`, `user-info-uri`, `jwks-uri`, `logout-uri`, `filter-name`, `scopes`, `username-claim`, `roles-claim`, `role-source`, `role-converter`, `allow-admin-login`, `use-pkce`, `public-url`, and `post-logout-redirect-uri`.

The standard discovery URL is derived as `{issuer-uri}/.well-known/openid-configuration`. Endpoint overrides support providers whose published URLs are not reachable from the WebUI container. Browser login and service-side Bearer token validation remain separate features.

## Cold identity-provider startup

When enabled, the WebUI starts OIDC discovery in a daemon background worker. Its first GET to the configured discovery URI also acts as a best-effort wake-up for a cold identity provider. GeoServer does not wait for that request and discovery timeouts do not fail application startup. The login filter is installed after valid provider metadata is available; if discovery remains unavailable after the bounded retries, GeoServer continues serving and logs that browser login is not ready. A later process start retries discovery; no periodic requests keep a stopped identity provider warm.

Discovery retries use bounded exponential backoff. The defaults are six attempts, a 5-second connection timeout, a 30-second read timeout, a 5-second initial retry delay, and a 60-second maximum delay. Override them with `discovery-max-attempts`, `discovery-connect-timeout-seconds`, `discovery-read-timeout-seconds`, `discovery-retry-delay-seconds`, and `discovery-max-retry-delay-seconds` under `geoserver.extension.security.oidc-login`. The provider remains generic: the initializer uses the configured `discovery-uri`, or derives the standard URL from `issuer-uri`.
