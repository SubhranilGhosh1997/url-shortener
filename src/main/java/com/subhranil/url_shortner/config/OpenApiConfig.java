package com.subhranil.url_shortner.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("URL Shortener API")
                .version("0.0.1")
                .description("Shorten URLs, follow short links and read click analytics. "
                        + "Redirects are served from Redis; clicks are counted asynchronously via Kafka."));
    }
}
