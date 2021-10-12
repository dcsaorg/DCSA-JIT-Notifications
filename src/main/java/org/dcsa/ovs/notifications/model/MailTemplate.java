package org.dcsa.ovs.notifications.model;

import lombok.Data;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.enums.EventClassifierCode;
import org.dcsa.core.events.model.enums.EventType;
import org.springframework.boot.context.properties.ConstructorBinding;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import java.util.Objects;
import java.util.Set;

@ConstructorBinding
@Data
public class MailTemplate {

    public MailTemplate(String to, String subject, String body, Set<EventClassifierCode> onlyForEventClassifierCode, Set<EventType> onlyForEventType) {
        this.to = Objects.requireNonNullElse(to, "NOT_SPECIFIED");
        this.subject = subject;
        this.body = body;
        this.onlyForEventClassifierCode = onlyForEventClassifierCode;
        this.onlyForEventType = onlyForEventType;
    }

    @Email
    private final String to;

    @NotBlank
    private final String subject;

    @NotBlank
    private final String body;

    private final Set<EventClassifierCode> onlyForEventClassifierCode;

    private final Set<EventType> onlyForEventType;

    public boolean appliesToEvent(Event event) {
        if (!onlyForEventType.isEmpty() && !onlyForEventType.contains(event.getEventType())) {
            return false;
        }
        if (!onlyForEventClassifierCode.isEmpty() && !onlyForEventClassifierCode.contains(event.getEventClassifierCode())) {
            return false;
        }
        return true;
    }

}
