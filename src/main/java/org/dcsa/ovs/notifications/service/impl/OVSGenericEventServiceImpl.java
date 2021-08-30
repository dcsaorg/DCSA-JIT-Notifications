package org.dcsa.ovs.notifications.service.impl;

import org.dcsa.core.events.repository.EventRepository;
import org.dcsa.core.events.service.*;
import org.dcsa.core.events.service.impl.GenericEventServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class OVSGenericEventServiceImpl extends GenericEventServiceImpl implements GenericEventService {
    public OVSGenericEventServiceImpl(ShipmentEventService shipmentEventService, TransportEventService transportEventService, EquipmentEventService equipmentEventService, OperationsEventService operationsEventService, EventRepository eventRepository) {
        super(shipmentEventService, transportEventService, equipmentEventService, operationsEventService, eventRepository);
    }
}
