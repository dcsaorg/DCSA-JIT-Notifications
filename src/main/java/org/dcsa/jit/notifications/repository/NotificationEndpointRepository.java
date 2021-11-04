package org.dcsa.jit.notifications.repository;

import org.dcsa.core.repository.ExtendedRepository;
import org.dcsa.jit.notifications.model.NotificationEndpoint;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface NotificationEndpointRepository extends ExtendedRepository<NotificationEndpoint, UUID> {
    Mono<NotificationEndpoint> findByEndpointReference(String endpointReference);

    @Query("DELETE FROM notification_endpoint WHERE managed_endpoint AND subscription_url = ''")
    Mono<Void> removeIncompletelySetupEndpoints();
}
