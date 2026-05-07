package com.ecrharv.notifier.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
public class BarkService {

    @Value("${bark.device-key}")
    private String deviceKey;

    private final RestClient restClient;

    public BarkService(@Qualifier("barkRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    // RestClient encodes URI template variables as path segments automatically —
    // no pre-encoding needed; pre-encoding would cause double-encoding (%20 → %2520).
    public void send(String title, String body) {
        try {
            restClient.get()
                    .uri("/{key}/{title}/{body}", deviceKey, title, body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Bark notification sent — title: '{}'", title);
        } catch (Exception e) {
            log.warn("Bark notification failed — title: '{}', reason: {}", title, e.getMessage());
        }
    }
}
