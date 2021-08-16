package org.dcsa.notifications.repository;

import org.dcsa.core.repository.ExtendedRepository;
import org.dcsa.notifications.model.NotificationEndpoint;

import java.util.UUID;

public interface NotificationEndpointRepository extends ExtendedRepository<NotificationEndpoint, UUID> {
}
