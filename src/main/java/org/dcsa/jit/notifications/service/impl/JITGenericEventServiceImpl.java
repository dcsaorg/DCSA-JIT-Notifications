package org.dcsa.jit.notifications.service.impl;

import org.dcsa.core.events.repository.EventRepository;
import org.dcsa.core.events.repository.PendingEventRepository;
import org.dcsa.core.events.service.*;
import org.dcsa.core.events.service.impl.GenericEventServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class JITGenericEventServiceImpl extends GenericEventServiceImpl implements GenericEventService {
    public JITGenericEventServiceImpl(
            ShipmentEventService shipmentEventService,
            TransportEventService transportEventService,
            EquipmentEventService equipmentEventService,
            OperationsEventService operationsEventService,
            EventRepository eventRepository,
            PendingEventRepository pendingEventRepository
    ) {
        super(shipmentEventService, transportEventService, equipmentEventService, operationsEventService, eventRepository, pendingEventRepository);
    }
}
