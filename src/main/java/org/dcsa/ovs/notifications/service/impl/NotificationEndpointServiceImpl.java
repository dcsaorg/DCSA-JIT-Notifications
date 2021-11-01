package org.dcsa.ovs.notifications.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.OperationsEvent;
import org.dcsa.core.events.model.TransportCallBasedEvent;
import org.dcsa.core.events.model.enums.SignatureMethod;
import org.dcsa.core.events.model.transferobjects.TransportCallTO;
import org.dcsa.core.events.service.GenericEventService;
import org.dcsa.core.events.service.TimestampDefinitionService;
import org.dcsa.core.events.service.TransportCallTOService;
import org.dcsa.core.events.service.impl.MessageSignatureHandler;
import org.dcsa.core.exception.CreateException;
import org.dcsa.core.service.impl.ExtendedBaseServiceImpl;
import org.dcsa.ovs.notifications.model.NotificationEndpoint;
import org.dcsa.ovs.notifications.model.Subscription;
import org.dcsa.ovs.notifications.model.SubscriptionsConfiguration;
import org.dcsa.ovs.notifications.repository.NotificationEndpointRepository;
import org.dcsa.ovs.notifications.service.NotificationEndpointService;
import org.dcsa.ovs.notifications.service.TimestampNotificationMailService;
import org.dcsa.ovs.notifications.util.SubscriberFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Service
@Slf4j
public class NotificationEndpointServiceImpl extends ExtendedBaseServiceImpl<NotificationEndpointRepository, NotificationEndpoint, UUID> implements NotificationEndpointService {

    private static final TypeReference<List<Event>> EVENT_TYPE_REFERENCE = new TypeReference<>() {};

    private final GenericEventService genericEventService;
    private final MessageSignatureHandler messageSignatureHandler;
    private final NotificationEndpointRepository notificationEndpointRepository;
    private final TransportCallTOService transportCallTOService;
    private final ObjectMapper objectMapper;
    private final TimestampNotificationMailService timestampNotificationMailService;
    private final SubscriptionsConfiguration subscriptionsConfiguration;
    private final SignatureMethod signatureMethod = SignatureMethod.HMAC_SHA256;
    private final Set<String> checkedSubscriptions = new HashSet<>();

    @Autowired
    @Lazy
    @Qualifier("dcsaclient")
    private WebClient dcsaWebClient;

    @Value("${dcsa.notificationBaseUrl}")
    private String notificationUrl;

    @Value("${spring.security.oauth2.client.registration.dcsaclient.client-id:}")
    private String clientID;

    @Value("${spring.security.oauth2.client.provider.dcsaclient.token-uri:}")
    private String tokenUri;

    private final TimestampDefinitionService timestampDefinitionService;

    @Override
    protected Mono<NotificationEndpoint> preSaveHook(NotificationEndpoint notificationEndpoint) {
        byte[] secret = notificationEndpoint.getSecret();
        if (secret == null) {
            return Mono.error(new CreateException("Missing mandatory secret field"));
        }
        if (secret.length < signatureMethod.getMinKeyLength()) {
            return Mono.error(new CreateException("length of the secret should be minimum " + signatureMethod.getMinKeyLength()
                    + " bytes long (was: " + secret.length + ")"));
        }
        if (signatureMethod.getMaxKeyLength() < secret.length) {
            return Mono.error(new CreateException("length of the secret should be maximum " + signatureMethod.getMaxKeyLength()
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

    @Transactional
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
                            eventConverter());
                }).flatMap(signatureResult -> {
                    if (!signatureResult.isValid()) {
                        // The unconditional usage of UNAUTHORIZED is deliberate. We are not interested in letting
                        // the caller know why we are rejecting - just that we are not happy.  Telling more might
                        // inform them of a bug or enable them to guess part of the secret.
                        log.debug("Rejecting message because: " + signatureResult.getResult());
                        return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
                    }

                    Mono<? extends Event> result = Mono.empty();
                    for (Event event : signatureResult.getParsed()) {
                        event.setNewRecord(true);
                        if (event instanceof TransportCallBasedEvent) {
                            TransportCallBasedEvent tcbe = (TransportCallBasedEvent)event;
                            TransportCallTO transportCallTO = tcbe.getTransportCall();

                            result = result.then(transportCallTOService.findById(transportCallTO.getTransportCallID()))
                                    .switchIfEmpty(transportCallTOService.create(transportCallTO))
                                    .doOnNext(((TransportCallBasedEvent) event)::setTransportCall)
                                    .doOnNext(tc -> ((TransportCallBasedEvent) event).setTransportCallID(tc.getTransportCallID()))
                                    .flatMap(ignored -> genericEventService.findByEventTypeAndEventID(event.getEventType(), event.getEventID()))
                                    .switchIfEmpty(
                                            genericEventService.create(event)
                                            .flatMap(savedEvent -> timestampNotificationMailService.sendEmailNotificationsForEvent(event)
                                                    .then(Mono.just(event))
                                            )
                                    )
                                    .flatMap(ignored ->{
                                            if(event instanceof OperationsEvent){
                                                try {
                                                    ((OperationsEvent) event).ensurePhaseTypeIsDefined();
                                                } catch (IllegalStateException e) {
                                                    return Mono.error(new CreateException("Cannot derive portCallPhaseTypeCode automatically from this timestamp. Please define it explicitly"));
                                                }
                                                if(((OperationsEvent) event).getPortCallPhaseTypeCode() == null){
                                                        return Mono.error(new CreateException("Cannot derive portCallPhaseTypeCode automatically from this timestamp. Please define it explicitly"));
                                                }
                                                return Mono.just(timestampDefinitionService.markOperationsEventAsTimestamp((OperationsEvent) event));
                                            }else {
                                             return Mono.just(event);
                                            }
                                    })
                                    .thenReturn(event);
                        }
                    }
                    return result.then();
                });
    }

    private MessageSignatureHandler.Converter<List<Event>> eventConverter() {
        return (payload -> objectMapper.readValue(payload, EVENT_TYPE_REFERENCE));
    }

    @Transactional
    public <S, U> Mono<NotificationEndpoint> setupSubscription(Consumer<NotificationEndpoint> configurator,
                                                        BiFunction<URI, NotificationEndpoint, SubscriberFunction<S, U>> subscriberProvider,
                                                        BiFunction<URI, NotificationEndpoint, S> subscriptionPayloadProvider
                                                        ) {
        NotificationEndpoint notificationEndpoint = new NotificationEndpoint();
        if (notificationEndpoint.getSecret() == null) {
            notificationEndpoint.setSecret(signatureMethod.generateSecret());
        }
        if (configurator != null) {
            configurator.accept(notificationEndpoint);
        }
        if (notificationEndpoint.getSubscriptionID() != null) {
            return Mono.error(new IllegalStateException("setupSubscription: Cannot setup a notification with the subscription known ahead of time!"));
        }
        return this.create(notificationEndpoint)
                .flatMap(ep -> {
                    URI callbackUrl = callbackUrlForEndpoint(ep);
                    return subscribe(ep, subscriberProvider.apply(callbackUrl, ep), subscriptionPayloadProvider.apply(callbackUrl, ep));
                });
    }

    private URI callbackUrlForEndpoint(NotificationEndpoint ep) {
        try {
            return new URI(this.notificationUrl + "/" + ep.getEndpointID());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private <S, U> Mono<NotificationEndpoint> subscribe(NotificationEndpoint ep, SubscriberFunction<S, U> subscriberFunction, S body) {
        return subscriberFunction.subscribe(body)
                .doOnNext(ep::setSubscriptionID)
                .thenReturn(ep)
                .doOnNext(endpoint -> {
                    if (endpoint.getManagedEndpoint() == Boolean.TRUE && "".equals(endpoint.getSubscriptionURL())) {
                        URI uri = subscriberFunction.getSubscriptionBaseURI();
                        StringBuilder subscriberUrl;
                        try {
                            subscriberUrl = new StringBuilder(uri.toURL().toString());
                        } catch (MalformedURLException e) {
                            // The URI was valid enough for calling an endpoint.  Seems reasonable to assume it is
                            // also a valid URL.
                            throw new AssertionError(e);
                        }
                        if (subscriberUrl.charAt(subscriberUrl.length() - 1) != '/') {
                            subscriberUrl.append('/');
                        }
                        subscriberUrl.append(ep.getSubscriptionID());
                        endpoint.setSubscriptionURL(subscriberUrl.toString());
                    }
                })
                .flatMap(this::save);
    }

    @EventListener(ApplicationStartedEvent.class)
    void initialize() {
        log.info("Using " + this.notificationUrl + " as base URL for generated subscriptions.  Change via dcsa.baseUrl OR dcsa.notificationBaseUrl");
        try {
            new URI(this.notificationUrl);
        } catch (URISyntaxException e) {
            log.error("The notification url is not a valid URL", e);
            throw new RuntimeException("Notification URl is invalid.  Please correct it via dcsa.baseUrl OR dcsa.notificationBaseUrl", e);
        }
    }

  @Scheduled(
      // We wait a while during start up before triggering this to allow other participants to come
      // online first
      // (in the DCSA clusters, all nodes are deployed at the same time).  Additionally, we
      // periodically recheck
      // the subscriptions to verify that they are correct.  This should ensure self-healing if the
      // initial round
      // fails provided that the setup can recover from the error.
      initialDelayString = "PT1M",
      fixedDelayString = "PT1H")
  void checkSubscriptions() {
    if (StringUtils.isNotEmpty(clientID) && StringUtils.isNotEmpty(tokenUri)) {
      log.info("Checking that all managed subscriptions are up to date");
      // TODO: We ought to remove "unknown" managed subscriptions.
      notificationEndpointRepository
          .removeIncompletelySetupEndpoints()
          .thenMany(Flux.fromIterable(subscriptionsConfiguration.getSubscriptions().entrySet()))
          .filter(entry -> !checkedSubscriptions.contains(entry.getKey()))
          .concatMap(
              entry -> {
                String reference = entry.getKey();
                Subscription subscription = entry.getValue();
                BiFunction<
                        URI,
                        NotificationEndpoint,
                        SubscriberFunction<Map<String, Object>, Map<String, Object>>>
                    subscriberFunctionProvider =
                        (callbackUrl, notificationEndpoint) -> {
                          assert notificationEndpoint.getSubscriptionID() == null
                              || notificationEndpoint.getSubscriptionURL().equals("");

                          return SubscriberFunction.of(
                              subscription.getPublisherBaseURI(),
                              notificationEndpoint.getSubscriptionID(), dcsaWebClient);
                        };

                return notificationEndpointRepository
                    .findByEndpointReference(reference)
                    .flatMap(
                        ep -> {
                          URI callbackUrl = callbackUrlForEndpoint(ep);
                          SubscriberFunction<Map<String, Object>, Map<String, Object>>
                              subscriberFunction =
                                  subscriberFunctionProvider.apply(callbackUrl, ep);
                          Map<String, Object> eventSubscription = subscription.asSubscription();
                          eventSubscription.put("subscriptionID", ep.getSubscriptionID());
                          eventSubscription.put("callbackUrl", callbackUrl);
                          return subscriberFunction
                              .updateSubscription(eventSubscription)
                              .thenReturn(ep)
                              .onErrorResume(
                                  SubscriberFunction.SubscriptionEndpointNotFoundException.class,
                                  e -> {
                                    if (ep.getSubscriptionID() != null) {
                                      log.info(
                                          "Found endpoint "
                                              + ep.getEndpointID()
                                              + " with subscription ID "
                                              + ep.getEndpointID()
                                              + ", but remote server does not recognise that ID.  Discarding it.");
                                      return notificationEndpointRepository
                                          .delete(ep)
                                          .then(Mono.empty());
                                    }
                                    // This should not happen, but lets not pretend we can fix it.
                                    return Mono.error(e);
                                  })
                              .flatMap(
                                  e -> {
                                    if (e.getSubscriptionID() == null) {
                                      return Mono.empty();
                                    }
                                    return subscriberFunction
                                        .updateSecret(e.getSecret())
                                        .thenReturn(ep)
                                        .doOnSuccess(
                                            es ->
                                                log.info(
                                                    "Successfully updated existing subscription "
                                                        + reference));
                                  });
                        })
                    .switchIfEmpty(
                        setupSubscription(
                                (notificationEndpoint) -> {
                                  notificationEndpoint.setEndpointReference(reference);
                                  notificationEndpoint.setManagedEndpoint(true);
                                  // dummy value for now; will be replaced when setup is done
                                  // (needed due to constraints)
                                  notificationEndpoint.setSubscriptionURL("");
                                },
                                subscriberFunctionProvider,
                                (callbackUrl, notificationEndpoint) -> {
                                  Map<String, Object> eventSubscription =
                                      subscription.asSubscription();
                                  byte[] secret = notificationEndpoint.getSecret();
                                  assert secret != null && secret.length > 0;
                                  eventSubscription.put("callbackUrl", callbackUrl);
                                  eventSubscription.put("secret", secret);
                                  return eventSubscription;
                                })
                            .doOnSuccess(
                                es -> log.info("Successfully setup subscription " + reference)))
                    .doOnNext(es -> checkedSubscriptions.add(es.getEndpointReference()))
                    .doOnNext(
                        es -> {
                          if (checkedSubscriptions.size()
                              == subscriptionsConfiguration.getSubscriptions().size()) {
                            log.info("All configured/managed subscriptions has been handled");
                          }
                        });
              })
          .then()
          .block();
    } else {
      log.info(
          "Auto subscriptions disabled as both client id and token uri are required to be set.");
    }
  }
}
