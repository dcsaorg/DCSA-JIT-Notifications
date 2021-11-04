package org.dcsa.jit.notifications.repository;

import org.dcsa.core.repository.ExtendedRepository;
import org.dcsa.jit.notifications.model.PendingEmailNotification;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface PendingEmailNotificationRepository extends ExtendedRepository<PendingEmailNotification, UUID> {

    // PostgreSQL specific (due to "FOR UPDATE SKIP LOCKED")
    @Query("DELETE FROM pending_email_notification WHERE event_id = ("
            + "  SELECT event_id FROM pending_email_notification FOR UPDATE SKIP LOCKED LIMIT 1"
            + ") RETURNING *")
    Mono<PendingEmailNotification> pollPendingEmail();
}
