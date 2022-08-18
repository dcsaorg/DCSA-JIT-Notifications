package org.dcsa.jit.notifications.controller;

import lombok.RequiredArgsConstructor;
import org.dcsa.jit.notifications.service.TimestampNotificationMailService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
public class NotificationEndpointController {
  private final TimestampNotificationMailService timestampNotificationMailService;

  // TODO
}
