package org.dcsa.ovs.notifications.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("notification_endpoint")
@Data
public class NotificationEndpoint {

    @Id
    @Column("endpoint_id")
    private UUID endpointID;

    @Column("subscription_id")
    private String subscriptionID;

    @Column("secret")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private byte[] secret;

    @Column("endpoint_reference")
    private String endpointReference;

    @Column("managed_endpoint")
    private Boolean managedEndpoint;

    @Column("subscription_url")
    private String subscriptionURL;
}
