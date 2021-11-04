package org.dcsa.jit.notifications.controller;

import lombok.RequiredArgsConstructor;
import org.dcsa.core.controller.ExtendedBaseController;
import org.dcsa.jit.notifications.model.NotificationEndpoint;
import org.dcsa.jit.notifications.service.NotificationEndpointService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "notification-endpoints", produces = {MediaType.APPLICATION_JSON_VALUE})
public class NotificationEndpointController extends ExtendedBaseController<NotificationEndpointService, NotificationEndpoint, UUID> {

    private final NotificationEndpointService notificationEndpointService;

    @Override
    public NotificationEndpointService getService() {
        return notificationEndpointService;
    }

    @RequestMapping(
            path = "receive/{id}",
            method = {
                    RequestMethod.POST,
                    RequestMethod.HEAD
            }
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> receivePayload(ServerHttpRequest request, @PathVariable("id") UUID endpointID) {
        return notificationEndpointService.receiveNotification(request, endpointID);
    }

    @Override
    @PostMapping
    public Mono<NotificationEndpoint> create(@Valid @RequestBody NotificationEndpoint t) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @Override
    @GetMapping
    public Flux<NotificationEndpoint> findAll(ServerHttpResponse response, ServerHttpRequest request) {
        return Flux.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @Override
    @DeleteMapping
    public Mono<Void> delete(@RequestBody NotificationEndpoint t) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @Override
    @DeleteMapping({"{id}"})
    public Mono<Void> deleteById(@PathVariable UUID id) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @Override
    @PutMapping({"{id}"})
    public Mono<NotificationEndpoint> update(@PathVariable UUID id, @Valid @RequestBody NotificationEndpoint t) {
        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
    }
}
