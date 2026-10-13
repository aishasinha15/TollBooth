package com.tollbooth.config;

import com.tollbooth.security.ApiKeyAuthFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the Swagger page: title, and an "Authorize" button
 * where you paste the API key once for all requests.
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME = "apiKey";

    @Bean
    public OpenAPI tollboothOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Tollbooth API")
                        .version("0.5.0")
                        .description("AI gateway for consumer apps' AI assistants"))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyAuthFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }
}
