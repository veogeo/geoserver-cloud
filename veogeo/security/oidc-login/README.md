# OpenID Connect WebUI login overlay

This auto-configuration is provider-agnostic. It reads the discovery document and applies optional endpoint overrides. Configuration uses the prefix `geoserver.extension.security.oidc-login` (environment form: `GEOSERVER_EXTENSION_SECURITY_OIDC_LOGIN_*`).

Required values when enabled:

- `enabled=true`
- `client-id`
- `client-secret`
- `issuer-uri`

Optional values include `discovery-uri`, `authorization-uri`, `token-uri`, `user-info-uri`, `jwks-uri`, `logout-uri`, `filter-name`, `scopes`, `username-claim`, `roles-claim`, `role-source`, `role-converter`, `allow-admin-login`, `use-pkce`, `public-url`, and `post-logout-redirect-uri`.

The standard discovery URL is derived as `{issuer-uri}/.well-known/openid-configuration`. Endpoint overrides support providers whose published URLs are not reachable from the WebUI container. Browser login and service-side Bearer token validation remain separate features.
