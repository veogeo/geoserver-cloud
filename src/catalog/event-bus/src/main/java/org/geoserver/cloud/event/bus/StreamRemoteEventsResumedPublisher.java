/* (c) 2026 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */

package org.geoserver.cloud.event.bus;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.geoserver.cloud.event.RemoteEventsResumed;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.BindingCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/** Publishes {@link RemoteEventsResumed} when the Spring Cloud Bus input binding is created and running. */
@Slf4j(topic = "org.geoserver.cloud.event.bus")
public class StreamRemoteEventsResumedPublisher {

    private static final String BUS_INPUT_BINDING = "springCloudBusInput";

    private final ApplicationEventPublisher publisher;

    public StreamRemoteEventsResumedPublisher(@NonNull ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener(BindingCreatedEvent.class)
    public void onBindingCreated(BindingCreatedEvent event) {
        if (event.getSource() instanceof Binding<?> binding
                && BUS_INPUT_BINDING.equals(binding.getName())
                && binding.isRunning()) {
            log.info("Spring Cloud Bus input binding is active, remote events resumed");
            publisher.publishEvent(new RemoteEventsResumed(binding.getName()));
        }
    }
}
