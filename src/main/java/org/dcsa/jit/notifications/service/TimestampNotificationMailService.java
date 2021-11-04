package org.dcsa.jit.notifications.service;

import org.dcsa.core.events.model.Event;
import org.dcsa.jit.notifications.model.PendingEmailNotification;
import reactor.core.publisher.Flux;

public interface TimestampNotificationMailService {


    Flux<PendingEmailNotification> sendEmailNotificationsForEvent(Event event);
}
