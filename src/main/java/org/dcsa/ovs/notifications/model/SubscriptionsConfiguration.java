package org.dcsa.ovs.notifications.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.validation.Valid;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "dcsa")
@Component
public class SubscriptionsConfiguration {
    @Valid
    private Map<String, Subscription> subscriptions;

    public Subscription getSubscription(String endpointReference) {
        Subscription subscription = subscriptions.get(endpointReference);
        if (subscription == null) {
            throw new IllegalArgumentException("Unknown endpoint reference: " + endpointReference);
        }
        return subscription;
    }
}
