package org.dcsa.ovs.notifications.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcsa.core.events.model.EquipmentEvent;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.OperationsEvent;
import org.dcsa.core.events.model.TransportEvent;
import org.dcsa.core.events.model.enums.SignatureMethod;
import org.dcsa.core.events.service.OperationsEventService;
import org.dcsa.core.events.service.TransportCallTOService;
import org.dcsa.core.events.service.TransportEventService;
import org.dcsa.core.events.service.impl.MessageSignatureHandler;
import org.dcsa.core.exception.CreateException;
import org.dcsa.core.service.impl.ExtendedBaseServiceImpl;
import org.dcsa.ovs.notifications.model.NotificationEndpoint;
import org.dcsa.ovs.notifications.repository.NotificationEndpointRepository;
import org.dcsa.ovs.notifications.service.NotificationEndpointService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RequiredArgsConstructor
@Service
@Slf4j
public class NotificationEndpointServiceImpl extends ExtendedBaseServiceImpl<NotificationEndpointRepository, NotificationEndpoint, UUID> implements NotificationEndpointService {

    private final MessageSignatureHandler messageSignatureHandler;
    private final NotificationEndpointRepository notificationEndpointRepository;
    private final OperationsEventService operationsEventService;
    private final TransportEventService transportEventService;
    private final TransportCallTOService transportCallTOService;

    @Override
    protected Mono<NotificationEndpoint> preSaveHook(NotificationEndpoint notificationEndpoint) {
        SignatureMethod method = SignatureMethod.HMAC_SHA256;
        byte[] secret = notificationEndpoint.getSecret();
        if (secret == null) {
            return Mono.error(new CreateException("Missing mandatory secret field"));
        }
        if (secret.length < method.getMinKeyLength()) {
            return Mono.error(new CreateException("length of the secret should be minimum " + method.getMinKeyLength()
                    + " bytes long (was: " + secret.length + ")"));
        }
        if (method.getMaxKeyLength() < secret.length) {
            return Mono.error(new CreateException("length of the secret should be maximum " + method.getMinKeyLength()
                    + " bytes long (was: " + secret.length + ")"));
        }
        return super.preSaveHook(notificationEndpoint);
    }

    @Override
    protected Mono<NotificationEndpoint> preUpdateHook(NotificationEndpoint original, NotificationEndpoint update) {
        if (update.getSecret() == null) {
            update.setSecret(original.getSecret());
        }
        return super.preUpdateHook(original, update);
    }

    @Override
    public NotificationEndpointRepository getRepository() {
        return notificationEndpointRepository;
    }

    @Override
    public Mono<Void> receiveNotification(ServerHttpRequest request, UUID endpointID) {
        return findById(endpointID)
                .flatMap(notificationEndpoint -> {
                    String subscriptionID = notificationEndpoint.getSubscriptionID();
                    if (request.getMethod() == HttpMethod.HEAD) {
                        // verify request - we are happy at this point. (note that we forgive missing subscriptionIDs
                        // as the endpoint can be verified before we know the Subscription ID)
                        return Mono.empty();
                    }
                    if (subscriptionID == null) {
                        // We do not have a subscription ID yet. Assume that it is not a match
                        // Ideally, we would include a "Retry-After" header as well.
                        return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
                    }
                    return messageSignatureHandler.verifyRequest(request,
                            notificationEndpoint.getSubscriptionID(),
                            notificationEndpoint.getSecret(),
                            Event[].class);
                }).flatMap(signatureResult -> {
                    if (!signatureResult.isValid()) {
                        // The unconditional usage of UNAUTHORIZED is deliberate. We are not interested in letting
                        // the caller know why we are rejecting - just that we are not happy.  Telling more might
                        // inform them of a bug or enable them to guess part of the secret.
                        log.debug("Rejecting message because: " + signatureResult.getResult());
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    }

                    // TODO: Fails with "returned non unique result" when querying findById for OperationsEvent
                    // TODO: Dependent on TransportEventTOService creating Vessel, Voyage, and Service
                    Mono<? extends Event> result = Mono.empty();
                    for (Event event : signatureResult.getParsed()) {
                        event.setNewRecord(true);
                        if (event instanceof OperationsEvent) {
                            OperationsEvent operationsEvent = (OperationsEvent) event;
                            result = result.then(transportCallTOService
                                    .findById(operationsEvent.getTransportCall().getTransportCallID())
                                    .switchIfEmpty(transportCallTOService.create(operationsEvent.getTransportCall()))
                                    .flatMap(ignored -> operationsEventService.findById(operationsEvent.getEventID())
                                            .switchIfEmpty(operationsEventService.create((OperationsEvent) setTransportCallID(operationsEvent)))));
                        } else if (event instanceof TransportEvent) {
                            TransportEvent transportEvent = (TransportEvent) event;
                            result = result.then(transportCallTOService
                                    .findById(transportEvent.getTransportCall().getTransportCallID())
                                    .switchIfEmpty(transportCallTOService.create(transportEvent.getTransportCall()))
                                    .flatMap(ignored -> transportEventService.findById(transportEvent.getEventID())
                                            .switchIfEmpty(transportEventService.create((TransportEvent) setTransportCallID(transportEvent)))));
                        }
                    }
                    return result.then();
                });
    }

    private Event setTransportCallID(Event event) {
        if (event instanceof OperationsEvent) {
            ((OperationsEvent) event).setTransportCallID(((OperationsEvent) event).getTransportCall().getTransportCallID());
            return event;
        }
        if (event instanceof TransportEvent) {
            ((TransportEvent) event).setTransportCallID(((TransportEvent) event).getTransportCall().getTransportCallID());
            return event;
        }
        if (event instanceof EquipmentEvent) {
            ((EquipmentEvent) event).setTransportCallID(((EquipmentEvent) event).getTransportCall().getTransportCallID());
            return event;
        }
        return event;
    }

}
