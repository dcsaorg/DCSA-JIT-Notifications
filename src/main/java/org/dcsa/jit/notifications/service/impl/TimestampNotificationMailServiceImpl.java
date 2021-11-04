package org.dcsa.jit.notifications.service.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.OperationsEvent;
import org.dcsa.core.events.model.TimestampDefinition;
import org.dcsa.core.events.model.enums.EventType;
import org.dcsa.core.events.service.OperationsEventService;
import org.dcsa.core.events.repository.TimestampDefinitionRepository;
import org.dcsa.jit.notifications.model.MailConfiguration;
import org.dcsa.jit.notifications.model.MailTemplate;
import org.dcsa.jit.notifications.model.PendingEmailNotification;
import org.dcsa.jit.notifications.repository.PendingEmailNotificationRepository;
import org.dcsa.jit.notifications.service.TimestampNotificationMailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class TimestampNotificationMailServiceImpl implements TimestampNotificationMailService {

    private final Pattern TEMPLATE_PATTERN = Pattern.compile("[{][{]\\s*([a-zA-Z-_./]+)\\s*[}][}]");
    private static final int MAILS_PER_BATCH_JOB = 10;
    // To make easier to do this with Flux which works on lists/arrays
    private static final Object DUMMY_OBJECT = new Object();
    private static final Object[] MAIL_ARRAY;

    static {
        MAIL_ARRAY = new Object[MAILS_PER_BATCH_JOB];
        for (int i = 0; i < MAILS_PER_BATCH_JOB; i++) {
            MAIL_ARRAY[i] = DUMMY_OBJECT;
        }
    }

    private final JavaMailSender emailSender;
    private final MailConfiguration mailConfiguration;
    private final OperationsEventService operationsEventService;
    private final PendingEmailNotificationRepository pendingEmailNotificationRepository;
    private final ReactiveTransactionManager transactionManager;
    private final TimestampDefinitionRepository timestampDefinitionRepository;

    @Value("${dcsa.webui.baseUrl:NOT_SPECIFIED}")
    private String webUIBaseUrl;

    // "Borrow" the parallel from the pending event service
    @Value("${dcsa.pendingEventService.parallel:4}")
    private int parallel;

    private Disposable processPendingEmailQueue;

    // These are substitution variables that have a "constant" definition.  The sendEmail code also has
    // a separate map for "runtime" (configuration derived) variables.
    private static final Map<String, Function<OperationsEvent, Object>> SUBST_VARS = Map.ofEntries(
            Map.entry("TIMESTAMP_TYPE", (oe) -> "TODO: THIS" ), // TODO: Fix as part of DDT-520
            Map.entry("VESSEL_NAME", (oe) -> oe.getTransportCall().getVessel().getVesselName()),
            Map.entry("VESSEL_IMO_NUMBER", (oe) -> oe.getTransportCall().getVessel().getVesselIMONumber())
    );

    // Validate configuration and provide log messages to aid with debugging.
    @EventListener(ApplicationStartedEvent.class)
    void logConfiguration() {
        if ("NOT_SPECIFIED".equals(mailConfiguration.getFrom())) {
            log.info("No from address specified (dcsa.email.from), disabling mail notifications");
            return;
        }
        TimeZone tz;
        if ("NOT_SPECIFIED".equals(mailConfiguration.getTimezone())) {
            tz = TimeZone.getDefault();
            log.info("No timezone specified (dcsa.email.timezone), email notifications will use " + tz.getID() + " - "
                    + tz.getDisplayName());
        } else {
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(mailConfiguration.getTimezone());
            } catch (DateTimeException e) {
                tz = TimeZone.getDefault();
                log.warn("Please check the dcsa.email.timezone value in application.yaml for errors (e.g. typos)");
                log.info("Valid standard examples include: Etc/UTC or " + tz.getID());
                log.info("It is also possible to set it to SYSTEM_TIMEZONE.");
                log.info("For this system, SYSTEM_TIMEZONE resolves to: " + tz.getID() + " (" + tz.getDisplayName() + ")");
                log.info("Note that the SYSTEM_TIMEZONE does not always use the right city name as multiple cities use the same timezone");
                log.info("For more timezones, please have a look at: https://en.wikipedia.org/wiki/List_of_tz_database_time_zones");
                log.error("Invalid or unknown timezone identifier provided: " + mailConfiguration.getTimezone());
                throw e;
            }
            tz = TimeZone.getTimeZone(zoneId);

            log.info("Using timezone " + tz.getID() + " - " + tz.getDisplayName() + " for emails (resolved from dcsa.email.timezone=" + mailConfiguration.getTimezone() + ")");
            log.info("If the timezone is wrong, then please set dcsa.email.timezone to the desired timezone");
        }
        for (Map.Entry<String, MailTemplate> templateEntry : mailConfiguration.getTemplates().entrySet()) {
            String templateKey = templateEntry.getKey();
            MailTemplate template = templateEntry.getValue();
            String toAddress = template.getTo();
            if ("NOT_SPECIFIED".equals(toAddress)) {
                log.info("Disabled email notification for template " + templateKey + " as it has no receiver (dcsa.email.templates." + templateKey + ".to)");
            } else {
                log.info("Will send email notifications to " + toAddress + " for mail template (dcsa.email.templates." + templateKey + ".to)");
            }
        }
    }

    private ZoneId getEmailTimezone() {
        if ("NOT_SPECIFIED".equals(mailConfiguration.getTimezone())) {
            return ZoneId.systemDefault();
        }
        return ZoneId.of(mailConfiguration.getTimezone());
    }

    @Override
    public Flux<PendingEmailNotification> sendEmailNotificationsForEvent(Event event, TimestampDefinition timestampDefinition) {
        return Flux.fromIterable(mailConfiguration.getTemplates().keySet())
                .filter(templateName -> {
                    MailTemplate template = mailConfiguration.getTemplate(templateName);
                    if (!template.appliesToEvent(event,timestampDefinition)) {
                        return false;
                    }
                    // Except for debugging, we discard emails now without a TO address.  Under debugging, the
                    // mail is discarded after the message has been rendered but before sending it.  This is
                    // mostly useful for testing the email service (albeit manually) without a valid email
                    // service.
                    if ("NOT_SPECIFIED".equals(template.getTo()) && !mailConfiguration.isDebugEmail()) {
                        return false;
                    }
                    return true;
                }).flatMap(templateName -> {
                    PendingEmailNotification pendingEmailNotification = new PendingEmailNotification();
                    pendingEmailNotification.setEventID(event.getEventID());
                    pendingEmailNotification.setTemplateName(templateName);
                    return pendingEmailNotificationRepository.save(pendingEmailNotification);
                });
    }

    private Mono<OperationsEvent> sendEmail(String templateName, OperationsEvent operationsEvent, TimestampDefinition timestampDefinition) {
        boolean shouldSendEmail = true;
        if ("NOT_SPECIFIED".equals(mailConfiguration.getFrom())) {
            shouldSendEmail = false;
            if (!mailConfiguration.isDebugEmail()) {
                return Mono.just(operationsEvent);
            }
        }
        MailTemplate template = mailConfiguration.getTemplates().get(templateName);
        if (template == null) {
            // Would happen if we record that we are going to send an email in the db, the app is stopped,
            // the template is removed and the app is started again.  An unlikely scenario, but we should
            // not break the entire mail flow on that either.
            return Mono.just(operationsEvent);
        }
        String toAddress = template.getTo();
        if ("NOT_SPECIFIED".equals(toAddress)) {
            shouldSendEmail = false;
            if (!mailConfiguration.isDebugEmail()) {
                return Mono.just(operationsEvent);
            }
        }

        if (!template.appliesToEvent(operationsEvent,timestampDefinition)) {
            // Should not happen (unless config changes between storing in the DB and sending)
            log.info("Skipping mail notification (" + templateName + "); appliesToEvent rejected message: " + operationsEvent.getEventID());
            return Mono.just(operationsEvent);
        }

        ZoneId zoneId = getEmailTimezone();

        Map<String, Function<OperationsEvent, Object>> customValues = Map.of(
                "WEB_UI_BASE_URI", (oe) -> webUIBaseUrl,
                "TRANSPORT_CALL_ID", (oe) -> oe.getTransportCall().getTransportCallID(),
                "TIMESTAMP_TYPE", (oe) -> timestampDefinition.getTimestampTypeName()
        );
        TemplateSubst<OperationsEvent> subst = TemplateSubst.of(
                operationsEvent,
                zoneId,
                mailConfiguration.getDateFormat(),
                customValues,
                SUBST_VARS
        );

        try {
            String subject = TEMPLATE_PATTERN.matcher(template.getSubject()).replaceAll(subst);
            String body = TEMPLATE_PATTERN.matcher(template.getBody()).replaceAll(subst);

            if (mailConfiguration.isDebugEmail()) {
                log.info("Email template (" + templateName + "), Subject: " + subject);
                log.info("Email template (" + templateName + "), Body: " + body);
            }

            if (!shouldSendEmail) {
                return Mono.just(operationsEvent);
            }

            MimeMessage message = emailSender.createMimeMessage();
            message.setFrom(mailConfiguration.getFrom());
            message.setRecipients(Message.RecipientType.TO, toAddress);
            message.setSubject(subject);
            message.setContent(body, "text/html");

            emailSender.send(message);

            return Mono.just(operationsEvent);
        } catch (MailAuthenticationException e) {
            log.warn("Failed to send mail as login towards the relay failed: " + e.toString(), e);
            log.warn("Please ensure that spring.mail.{host,username,password} + spring.mail.properties.mail.smtp.{port,auth} are set correctly.");
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        } catch (MailException | MessagingException exception) {
            log.error("Fail to send email (" + templateName + ") " + toAddress + ": " + exception.getLocalizedMessage(), exception);
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        } catch (UnknownTemplateKeyException e) {
            log.error("Email template references unknown {{" + e.getMessage() + "}} in dcsa.email.templates." + templateName + ".body");
            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        }
    }

    @Scheduled(
            cron = "${dcsa.email.cronSchedule:15 */1 * * * *}"
    )
    public synchronized void processPendingEmailQueue() {
        if (processPendingEmailQueue != null && !processPendingEmailQueue.isDisposed()) {
            log.info("Skipping processPendingEventQueue task. Previous job is still on-going");
            return;
        }
        processPendingEmailQueue = null;
        Instant start = Instant.now();
        TransactionalOperator transactionalOperator = TransactionalOperator.create(transactionManager);
        log.info("Starting processPendingEventQueue task");

        Mono<OperationsEvent> mapJob = pendingEmailNotificationRepository.pollPendingEmail()
                .flatMap(pendingMessage ->
                        operationsEventService.findById(pendingMessage.getEventID())
                                // These operations should probably happen directly in the operationsEventService
                                .doOnNext(oe -> oe.setEventType(EventType.OPERATIONS))
                                .flatMap( oe ->
                                        Mono.zip(operationsEventService.loadRelatedEntities(oe),
                                                timestampDefinitionRepository.findTimestampDefinitionById(oe.getEventID())
                                ))
                                .flatMap(tuple -> this.sendEmail(pendingMessage.getTemplateName(), tuple.getT1(), tuple.getT2()))
                )
                .doOnSuccess(res -> {
                    if (res != null) {
                        log.info("Successfully sent email notification for OperationsEvent: " + res.getEventID());
                    }
                })
                .onErrorResume(Exception.class, (e) -> {
                    log.warn("Failed to deliver email", e);
                    return Mono.empty();
                });

        Mono<?> parallelJob = Flux.fromArray(MAIL_ARRAY)
                .parallel(parallel)
                .flatMap(ignored -> transactionalOperator.transactional(mapJob))
                .sequential()
                .collectList()
                .doOnSuccess(submissionResults -> {
                    Instant finish = Instant.now();
                    Duration duration = Duration.between(start, finish);
                    if (submissionResults.isEmpty()) {
                        log.info("No email notifications pending at the moment. The processPendingEmailQueue job took "
                                + duration);
                    } else {
                        log.info("The processPendingEmailQueue job took " + duration);
                    }
                });
        processPendingEmailQueue = parallelJob.doFinally((ignored) -> {
            if (processPendingEmailQueue != null) {
                processPendingEmailQueue.dispose();
            }
            processPendingEmailQueue = null;
        }).subscribe();
    }

    @RequiredArgsConstructor(staticName = "of")
    private static class TemplateSubst<T> implements Function<MatchResult, String> {
        private final T operationsEvent;
        private final ZoneId emailTimezone;
        private final String dateTimeFormat;
        private final Map<String, Function<T, Object>> substitutionValues;
        private final Map<String, Function<T, Object>> fallbackValues;

        @Getter(lazy = true)
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateTimeFormat);

        public String apply(MatchResult matchResult) {
            String key = matchResult.group(1);
            Function<T, Object> valueFunction = substitutionValues.get(key);
            if (valueFunction == null) {
                valueFunction = fallbackValues.get(key);
            }
            if (valueFunction == null) {
                throw new UnknownTemplateKeyException(key);
            }
            Object value = valueFunction.apply(operationsEvent);
            if (value instanceof OffsetDateTime) {
                Instant instant = ((OffsetDateTime) value).toInstant();
                ZoneOffset offset = emailTimezone.getRules().getOffset(instant);
                OffsetDateTime timeAtOffset = instant.atOffset(offset);
                value = getFormatter().format(timeAtOffset);
            }
            return String.valueOf(value);
        }
    }

    private static class UnknownTemplateKeyException extends RuntimeException {
        UnknownTemplateKeyException(String key) {
            super(key);
        }
    }
}