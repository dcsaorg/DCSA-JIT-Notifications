package org.dcsa.ovs.notifications.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("pending_email_notification")
@Data
public class PendingEmailNotification {
    @Id
    private UUID id;

    @Column("event_id")
    private UUID eventID;

    @Column("template_name")
    private String templateName;

    @Column("enqueued_at_date_time")
    private OffsetDateTime enqueuedAt;
}
