package com.ecrharv.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ECR-Harvester API")
                        .description("REST API exposing grades, messages and attendance collected from Librus Synergia")
                        .version("1.0.0")
                );
    }
}
