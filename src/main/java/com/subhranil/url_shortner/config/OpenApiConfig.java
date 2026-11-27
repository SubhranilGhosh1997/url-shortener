package com.subhranil.url_shortner.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("URL Shortener API")
                        .version("0.0.1")
                        .description("Shorten URLs, follow short links and read click analytics. "
                                + "Redirects are served from Redis; clicks are counted asynchronously via Kafka.\n\n"
                                + "**To use it:** call `POST /api/auth/register` (or `/login`), copy `accessToken`, "
                                + "click **Authorize** and paste it."))
                // Every operation needs the token unless it says otherwise (@SecurityRequirements on public ones).
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
