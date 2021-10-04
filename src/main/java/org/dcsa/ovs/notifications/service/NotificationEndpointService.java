package org.dcsa.ovs.notifications.service;

import org.dcsa.core.service.ExtendedBaseService;
import org.dcsa.ovs.notifications.model.NotificationEndpoint;
import org.dcsa.ovs.notifications.util.SubscriberFunction;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Consumer;

public interface NotificationEndpointService extends ExtendedBaseService<NotificationEndpoint, UUID> {

    Mono<Void> receiveNotification(ServerHttpRequest request, UUID endpointID);
}
