package com.ecrharv.harvester;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcrHarvesterApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcrHarvesterApplication.class, args);
    }
}
