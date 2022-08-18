package org.dcsa.jit.notifications.service;

import lombok.RequiredArgsConstructor;
import org.dcsa.jit.notifications.persistence.repository.PendingEmailNotificationRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TimestampNotificationMailService {
  private final PendingEmailNotificationRepository pendingEmailNotificationRepository;

  // TODO
}
