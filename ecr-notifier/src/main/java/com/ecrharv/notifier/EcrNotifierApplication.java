package com.ecrharv.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EcrNotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(EcrNotifierApplication.class, args);
    }
}
