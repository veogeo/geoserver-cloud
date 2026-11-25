# Catalog event bus

Sends the GeoServer events of a service instance to the other instances through
[Spring Cloud Bus](https://docs.spring.io/spring-cloud-bus/reference/), and publishes the events received from them to
the local `ApplicationContext`.

The event model lives in the [events](../events/README.md) module, without a dependency on Spring Cloud Bus. Modules
listening to the events depend on that module only.

## How it works

- `RemoteGeoServerEventBridge` wraps each local `GeoServerEvent` in a `RemoteGeoServerEvent` and publishes it to the
  bus. Spring Cloud Bus encodes it as JSON with the `gs-jackson-bindings` module.
- The secrets of stores, such as database passwords, are encrypted in the events sent and decrypted in the events
  received (`RemoteEventSecrets`), with the password encrypter of the security settings and the keys of the keystore
  shared by the services.
- The bridge unwraps the incoming `RemoteGeoServerEvent`s and publishes their `GeoServerEvent` locally, marked as remote
  (`GeoServerEvent.isRemote()`). The events sent by the instance itself are ignored.
- With RabbitMQ as transport, `RemoteEventsResumedPublisher` publishes a local `RemoteEventsResumed` event when the
  consumer subscribes to its queue, at startup or after a lost connection. Listeners holding state derived from the
  events, such as caches, empty or rebuild it.
- With Pub/Sub as transport, `StreamRemoteEventsResumedPublisher` publishes the same event when the Spring Cloud Bus
  input binding is active. Pub/Sub subscriptions stay anonymous and per instance, so each instance receives its own
  copy of bus events; the binder removes those subscriptions when it shuts down.

## Selecting a transport

The application includes both Spring Cloud Stream binders, but a profile selects exactly one:

- `bus-rabbitmq` selects RabbitMQ. It remains the transport used by the existing `standalone` and `config-first`
  profile groups, including VM deployments.
- `bus-pubsub` selects Google Cloud Pub/Sub. The `kubernetes` profile group uses it by default; Cloud Run deployments
  should include it explicitly in their active profiles.

The Pub/Sub binder uses Application Default Credentials and discovers the project from the runtime when possible.
Its default resource creation can create the shared `geoserver` topic and each instance's anonymous subscription. Grant
the workload identity permission to publish, subscribe, create, and remove those resources, or provision the topic and
adjust resource-creation settings for the deployment.

For local Pub/Sub development, run the Google Pub/Sub emulator, set `spring.cloud.gcp.pubsub.emulator-host` to its
address, and set `spring.cloud.gcp.project-id` to the emulator project (for example `test-project`). Activate
`local,bus-pubsub`; the emulator does not need production credentials.

## Configuration

The integration is active when Spring Cloud Bus is enabled (`spring.cloud.bus.enabled`, `true` by default) and
`geoserver.bus.enabled` is `true` (the default). The transport is configured as usual for Spring Cloud Bus, for
example:

```yaml
spring:
  rabbitmq:
    host: rabbitmq
    port: 5672
    username: guest
    password: guest
geoserver:
  bus:
    enabled: true
```
