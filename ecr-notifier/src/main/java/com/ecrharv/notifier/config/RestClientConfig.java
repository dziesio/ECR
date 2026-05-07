package com.ecrharv.notifier.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("ecrApiRestClient")
    RestClient ecrApiRestClient(@Value("${ecr.api.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean("barkRestClient")
    RestClient barkRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.day.app")
                .build();
    }
}
