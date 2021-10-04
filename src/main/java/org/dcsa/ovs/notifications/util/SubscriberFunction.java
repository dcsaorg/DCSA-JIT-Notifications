package org.dcsa.ovs.notifications.util;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;

public interface SubscriberFunction<S, U> {

    URI getSubscriptionBaseURI();
    Mono<String> subscribe(S s);
    Mono<Void> updateSubscription(U u);
    Mono<Void> updateSecret(byte[] newSecret);

    static <S, U> Subscriber<S, U> of(URI publisherSubscriptionEndpoint, Consumer<Map<String, Object>> attributesProvider) {
       return new Subscriber<>(publisherSubscriptionEndpoint, attributesProvider);
    }

    static <S, U> Subscriber<S, U> of(URI publisherSubscriptionEndpoint, String subscriptionID, Consumer<Map<String, Object>> attributesProvider) {
        return new Subscriber<>(publisherSubscriptionEndpoint, subscriptionID, attributesProvider);
    }

    class SubscriptionEndpointNotFoundException extends RuntimeException {
        SubscriptionEndpointNotFoundException(String msg) {
            super(msg);
        }
    }
}
