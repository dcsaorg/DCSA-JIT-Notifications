package org.dcsa.ovs.notifications.util;

import lombok.*;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Consumer;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class Subscriber<S, U> implements SubscriberFunction<S, U> {

    @NonNull
    @Getter
    private final URI subscriptionBaseURI;
    private String subscriptionID;
    @NonNull
    private final Consumer<Map<String, Object>> attributesProvider;

    public Mono<Void> updateSecret(byte[] secret) {
        if (subscriptionID == null) {
            throw new IllegalStateException("Not subscribed to " + subscriptionBaseURI);
        }
        return invoke(HttpMethod.PUT, subscriptionURI("secret"), Map.of("secret", secret), "Updating secret", true)
                .flatMap(obj -> Mono.error(new AssertionError("Stream should have been empty!")))
                .then();
    }

    @SneakyThrows(URISyntaxException.class)
    private URI subscriptionURI(String suffix) {
        StringBuilder builder = new StringBuilder(subscriptionBaseURI.toString());
        if (subscriptionID != null) {
            builder.append('/').append(subscriptionID);
        }
        if (suffix != null && !suffix.equals("")) {
            if (subscriptionID == null) {
                throw new IllegalStateException("Not subscribed to " + subscriptionBaseURI);
            }
            builder.append('/').append(suffix);
        }
        return new URI(builder.toString());
    }

    private URI subscriptionURI() {
        return subscriptionURI("");
    }

    public Mono<Void> updateSubscription(U update) {
        if (subscriptionID == null) {
            throw new IllegalStateException("Not subscribed to " + subscriptionBaseURI);
        }
        return invoke(HttpMethod.PUT, subscriptionURI(), update, "Updating subscription", false)
                .then();
    }

    @Override
    public Mono<String> subscribe(S subscriptionBody) {
        if (subscriptionID != null) {
            throw new IllegalStateException("Already subscribed to " + subscriptionBaseURI + " (with ID: " + subscriptionID + ")");
        }
        return invoke(HttpMethod.POST, subscriptionBaseURI, subscriptionBody, "Subscription", false)
                .doOnNext(subscriptionID -> this.subscriptionID = subscriptionID);
    }

    private <P> Mono<String> invoke(HttpMethod httpMethod, URI subscriptionURI, P payload, String action, boolean expectedNoContent) {
        WebClient webClient = WebClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .build();

        return webClient.method(httpMethod)
                .uri(subscriptionURI)
                .attributes(this.attributesProvider)
                .bodyValue(payload)
                .exchangeToMono(clientResponse -> {
                    switch (clientResponse.statusCode()) {
                        case UNAUTHORIZED:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                    + "\" failed: Missing or invalid credentials"));
                        case BAD_REQUEST:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                    + "\" failed: Publisher rejected our payload with bad request."));
                        case NOT_FOUND:
                            return Mono.error(new SubscriptionEndpointNotFoundException(action + " to \"" + subscriptionURI
                                    + "\" failed: URL got \"404 Not Found\""));
                        case FORBIDDEN:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                    + "\" failed: Publisher rejected us with Forbidden (might be an IP approval list or insufficient permissions)"));
                        case TOO_MANY_REQUESTS:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                    + "\" failed: Publisher asked us to back off (too many requests)"));
                        case INTERNAL_SERVER_ERROR:
                        case BAD_GATEWAY:
                        case SERVICE_UNAVAILABLE:
                        case GATEWAY_TIMEOUT:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                    + "\" failed: Publisher was down - status code: " + clientResponse.statusCode().value()));
                        case OK:
                        case CREATED:
                        case ACCEPTED:
                            if (expectedNoContent) {
                                return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                        + "\" failed: Publisher responded with " + clientResponse.statusCode().value()
                                        + " but we expected with No Content"));
                            }
                            // OK
                            return clientResponse.bodyToMono(Map.class)
                                    .flatMap(body -> {
                                        Object subscriptionId = body.get("subscriptionID");
                                        if (!(subscriptionId instanceof String)) {
                                            return Mono.error(new IllegalStateException(action + " to \""
                                                    + subscriptionBaseURI
                                                    + "\" appeared to be successful, but it did not return a valid subscription ID (got: \""
                                                    + subscriptionId + "\")"));
                                        }
                                        return Mono.just((String)subscriptionId);
                                    });
                        case NO_CONTENT:
                            if (!expectedNoContent) {
                                return Mono.error(new RuntimeException(action + " to \"" + subscriptionURI
                                        + "\" failed: Publisher responded with 204 No Content, but response should have had content"));
                            }
                            return Mono.empty();
                        default:
                            return Mono.error(new RuntimeException(action + " to \"" + subscriptionBaseURI
                                    + "\" failed: Unhandled reason - status code: " + clientResponse.statusCode().value()));
                    }
                });
    }
}
