package org.dcsa.ovs.notifications.util;

import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;

public interface SubscriberFunction<S, U> {

    URI getSubscriptionBaseURI();
    Mono<String> subscribe(S s);
    Mono<Void> updateSubscription(U u);
    Mono<Void> updateSecret(byte[] newSecret);

    static <S, U> Subscriber<S, U> of(URI publisherSubscriptionEndpoint, String subscriptionID, WebClient webClient) {
        return new Subscriber<>(publisherSubscriptionEndpoint, subscriptionID, webClient);
    }

    class SubscriptionEndpointNotFoundException extends RuntimeException {
        SubscriptionEndpointNotFoundException(String msg) {
            super(msg);
        }
    }
}
