package org.dcsa.ovs.notifications.model;

import lombok.Data;
import org.dcsa.core.events.model.enums.EventType;
import org.dcsa.ovs.notifications.util.SubscriberFunction;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Data
public class Subscription {

    private static final Consumer<Map<String, Object>> DEFAULT_ATTRIBUTES = (x) -> {};

    private URI publisherBaseURI;
    private String springOIDCClient = "NONE";
    private Set<EventType> eventTypes = Set.of(EventType.OPERATIONS);
    private String vesselIMONumber;

    public Map<String, Object> asSubscription() {
        Map<String, Object> subscriptionPayload = new HashMap<>();
        addIfNotNull(subscriptionPayload, "vesselIMONumber", vesselIMONumber);
        subscriptionPayload.put("eventType", eventTypes.stream().map(EventType::name).collect(Collectors.toList()));
        return subscriptionPayload;
    }

    public Consumer<Map<String, Object>> getAttributeProvider() {
        if (springOIDCClient.equals("NONE")) {
            return DEFAULT_ATTRIBUTES;
        }
        return ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(springOIDCClient);
    }

    private static void addIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
