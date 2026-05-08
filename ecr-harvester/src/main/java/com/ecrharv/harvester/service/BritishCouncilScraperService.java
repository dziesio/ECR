package com.ecrharv.harvester.service;

import com.ecrharv.harvester.scraping.ScrapedData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
public class BritishCouncilScraperService {

    @Value("${bc.enabled:false}")
    private boolean enabled;

    public ScrapedData.BcScrapeResult scrapeMessages(Set<String> knownMessageIds) {
        if (enabled) {
            log.warn("British Council scraping is enabled but not supported — returning empty");
        }
        return ScrapedData.BcScrapeResult.empty();
    }
}
