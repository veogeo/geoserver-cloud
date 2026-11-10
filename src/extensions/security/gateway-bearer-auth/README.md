# GeoServer Cloud Gateway Bearer Authentication Extension

Validates OIDC JWT Bearer tokens or introspects opaque OAuth access tokens at the GeoServer Cloud gateway and passes the verified identity to backend services via the existing `SharedAuth` request headers. The raw token is not forwarded to backend services.

## Configuration

This extension is disabled by default. Enable it in the gateway configuration and provide the token issuer, audience,
username claim, role claim, and explicit role mappings:

```yaml
geoserver:
  extension:
    security:
      gateway-bearer-auth:
        enabled: true
        issuer-uri: https://keycloak.example/realms/geonode
        audience: geoserver-cloud
        username-claim: preferred_username
        roles-claim: resource_access.geoserver-cloud.roles
        role-prefix: ROLE_
        role-mappings:
          data-reader: PRIVATE_READER
          data-editor: PRIVATE_EDITOR
```

In JWT mode, only roles listed in `role-mappings` are accepted. Map them to roles assigned in GeoServer and used by
`security/layers.properties`. In opaque-token mode, an empty mapping preserves roles returned by the trusted introspection endpoint; add an explicit map if you want to allowlist them. Disable the extension by setting `enabled: false`.


## Opaque OAuth tokens from GeoNode

GeoNode's default OAuth access tokens are opaque database-backed values rather than JWTs. For that mode, configure the gateway with:

```yaml
geoserver:
  extension:
    security:
      gateway-bearer-auth:
        enabled: true
        introspection-uri: https://geonode.example/api/o/v4/tokeninfo/
        introspection-api-key: ${GEOSERVER_CLOUD_INTROSPECTION_API_KEY}
        username-claim: preferred_username
        roles-claim: roles
        role-prefix: ROLE_
```

The gateway POSTs the token as a form field and sends `X-GeoNode-Introspection-Key`; GeoNode must enable Cloud mode, protect the tokeninfo endpoint with the same high-entropy secret, and return `active`, `preferred_username`, and `roles`. Role values already prefixed with `ROLE_` are preserved. Configure `introspection-uri` to select opaque-token validation; omit it to retain the JWT validation mode and its issuer/audience checks. Never use an OIDC ID token as an OGC access token.
