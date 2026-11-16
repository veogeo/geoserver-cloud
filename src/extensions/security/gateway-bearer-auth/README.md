# GeoServer Cloud OIDC JWT Bearer Authentication Extension

Validates signed OIDC **access-token JWTs** independently in each GeoServer service. The gateway can forward the original bearer token unchanged; each service verifies its signature, issuer, audience, and expiry before using the configured username and roles. Opaque tokens and OIDC ID tokens are rejected.

## Configuration

This extension is disabled by default in every service. Enable it and configure the active issuer, the GeoServer audience, token claims, and explicit role mappings:

```yaml
geoserver:
  extension:
    security:
      bearer-auth:
        enabled: true
        issuer-uri: https://identity.example/realms/geonode
        # Optional internal JWKS endpoint if the issuer's advertised URL is unreachable from the gateway.
        jwk-set-uri: https://identity.example/realms/geonode/protocol/openid-connect/certs
        audience: geoserver-cloud
        username-claim: preferred_username
        roles-claim: resource_access.geoserver-cloud.roles
        role-prefix: ROLE_
        role-mappings:
          data-reader: PRIVATE_READER
          data-editor: PRIVATE_EDITOR
```

When `jwk-set-uri` is omitted, the extension uses OIDC discovery from `issuer-uri` to find the signing keys. The configured issuer is still checked against the token's `iss` claim. Configure one active issuer per deployment; GeoNode can be the current issuer and an external OIDC provider can be selected later by changing deployment settings.

Only roles listed in `role-mappings` are accepted. Map them to GeoServer roles used by `security/layers.properties`. The audience must identify GeoServer Cloud, and the identity provider must issue a signed JWT **access token** for that audience. OIDC does not require access tokens to be JWTs; an opaque access token will be rejected. Never use an OIDC ID token as an OGC access token.

Requests without a Bearer header retain the configured GeoServer public-resource and WebUI session behavior. The gateway does not need to authenticate the Bearer token for backend services to authorize it.
