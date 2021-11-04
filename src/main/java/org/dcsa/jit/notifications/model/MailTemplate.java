package org.dcsa.jit.notifications.model;

import lombok.Data;
import org.dcsa.core.events.model.Event;
import org.dcsa.core.events.model.TimestampDefinition;
import org.dcsa.core.events.model.enums.EventClassifierCode;
import org.dcsa.core.events.model.enums.EventType;
import org.dcsa.core.events.model.enums.PartyFunction;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import java.util.Set;

@Data
public class MailTemplate {

    @Email
    private String to = "NOT_SPECIFIED";

    @NotBlank
    private String subject;

    @NotBlank
    private String body;

    private Set<EventClassifierCode> onlyForEventClassifierCode;

    private Set<EventType> onlyForEventType;

    private Set<PartyFunction> onlyWhenPrimaryReceiverIs;

    public boolean appliesToEvent(Event event, TimestampDefinition timestampDefinition) {
        if (!onlyForEventType.isEmpty() && !onlyForEventType.contains(event.getEventType())) {
            return false;
        }
        if (!onlyForEventClassifierCode.isEmpty() && !onlyForEventClassifierCode.contains(event.getEventClassifierCode())) {
            return false;
        }
        if(!onlyWhenPrimaryReceiverIs.isEmpty() && (timestampDefinition == null || !onlyWhenPrimaryReceiverIs.contains(timestampDefinition.getPrimaryReceiver()))) {
            return false;
        }
        return true;
    }

}
