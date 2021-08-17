package org.dcsa.ovs.notifications;

import org.dcsa.core.extendedrequest.ExtendedParameters;
import org.dcsa.ovs.notifications.controller.NotificationEndpointController;
import org.dcsa.ovs.notifications.model.NotificationEndpoint;
import org.dcsa.ovs.notifications.service.NotificationEndpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.dialect.R2dbcDialect;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@DisplayName("Tests for Notification Endpoint Controller")
@WebFluxTest(controllers = NotificationEndpointController.class)
class NotificationEndpointControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockBean
    NotificationEndpointService eventService;

    @MockBean
    ExtendedParameters extendedParameters;

    @MockBean
    R2dbcDialect r2dbcDialect;

    private NotificationEndpoint notificationEndpoint;

    @TestConfiguration
    @EnableWebFluxSecurity
    static class WebFluxSecurityConfig {
        @Bean
        public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
            return http.csrf().disable().build();
        }
    }

    @BeforeEach
    void init() {
        notificationEndpoint = new NotificationEndpoint();
        notificationEndpoint.setEndpointID(UUID.randomUUID());
        notificationEndpoint.setSubscriptionID(UUID.randomUUID().toString());
        notificationEndpoint.setSecret("1245515151231231231231231231231231231231231231231231231231231231".getBytes(StandardCharsets.UTF_8));
    }

    @Test()
    @DisplayName("Updating a notification event should throw forbidden for any valid request.")
    void eventUpdatingShouldThrowForbiddenForAnyRequest() {
        // test to confirm that the endpoint is disabled.
        webTestClient
                .post()
                .uri("/receive/{id}", notificationEndpoint.getEndpointID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(notificationEndpoint))
                .exchange()
                .expectStatus()
                .is4xxClientError();
    }

    @Test
    @DisplayName("Creation of a notification event should throw forbidden for any valid request.")
    void notificationEndpointCreateShouldThrowForbiddenForAnyRequest() {
        // test to confirm that the endpoint is disabled.
        webTestClient
                .post()
                .uri("/notification-endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(notificationEndpoint))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("Putting a notification event should throw forbidden for any valid request.")
    void notificationEndpointPuttingShouldThrowForbiddenForAnyRequest() {
        // test to confirm that the endpoint is disabled.
        webTestClient
                .put()
                .uri("/notification-endpoints/{id}", notificationEndpoint.getEndpointID())
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(notificationEndpoint))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("Deleting a notification event should throw forbidden for any valid request.")
    void notificationEndpointDeleteByIdShouldThrowForbiddenForAnyRequest() {
        // test to confirm that the endpoint is disabled.
        webTestClient
                .delete()
                .uri("/notification-endpoints/{id}", notificationEndpoint.getEndpointID())
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("Getting a notification event should throw forbidden for any valid request.")
    void notificationEndpointGetShouldThrowForbiddenForAnyRequest() {
        // test to confirm that the endpoint is disabled.
        webTestClient
                .get()
                .uri("/notification-endpoints")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

}
