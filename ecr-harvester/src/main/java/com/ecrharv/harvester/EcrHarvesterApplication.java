package com.ecrharv.harvester;

import org.conscrypt.Conscrypt;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.Security;

@SpringBootApplication
@EnableScheduling
public class EcrHarvesterApplication {

    public static void main(String[] args) {
        // Register Conscrypt (BoringSSL) as the first TLS provider so Java's
        // JSSE fingerprint is not used — sites like Librus block the JSSE JA3 hash.
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        SpringApplication.run(EcrHarvesterApplication.class, args);
    }
}
