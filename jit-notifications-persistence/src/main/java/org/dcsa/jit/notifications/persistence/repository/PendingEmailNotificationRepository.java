package org.dcsa.jit.notifications.persistence.repository;

import org.dcsa.jit.notifications.persistence.entity.PendingEmailNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PendingEmailNotificationRepository extends JpaRepository<PendingEmailNotification, UUID> {}
