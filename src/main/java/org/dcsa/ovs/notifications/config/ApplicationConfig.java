package org.dcsa.ovs.notifications.config;

import org.dcsa.core.repository.ExtendedRepositoryImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@ComponentScan("org.dcsa")
@EnableR2dbcRepositories(
    basePackages = {"org.dcsa"},
    repositoryBaseClass = ExtendedRepositoryImpl.class)
@EnableScheduling
public class ApplicationConfig {

  @ConditionalOnExpression(
      "T(org.apache.commons.lang3.StringUtils).isNotEmpty('${spring.security.oauth2.client.registration.dcsaclient.client-id:}')")
  @Bean(name = "dcsaclient")
  WebClient dcsaWebClient(ReactiveClientRegistrationRepository clientRegistrations) {
    InMemoryReactiveOAuth2AuthorizedClientService clientService =
        new InMemoryReactiveOAuth2AuthorizedClientService(clientRegistrations);
    AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager authorizedClientManager =
        new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
            clientRegistrations, clientService);
    ServerOAuth2AuthorizedClientExchangeFilterFunction oauth =
        new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
    oauth.setDefaultClientRegistrationId("dcsaclient");
    oauth.setDefaultOAuth2AuthorizedClient(true);
    return WebClient.builder()
        .defaultHeader("Content-Type", "application/json")
        .filter(oauth)
        .build();
  }
}
