package org.dcsa.ovs.notifications.service;

import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.OperationsEvent;
import org.dcsa.ovs.notifications.model.PendingEmailNotification;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TimestampNotificationMailService {


    Flux<PendingEmailNotification> sendEmailNotificationsForEvent(Event event);
}
