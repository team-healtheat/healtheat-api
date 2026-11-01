package com.healtheat.healtheat_api.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("HealthEat API").version("0.1.0")
            .description("Call GET /api/auth/csrf, then send its token in X-XSRF-TOKEN with cookies on state-changing requests."))
            .components(new Components().addSecuritySchemes("accessCookie", new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.COOKIE).name("access_token")
                .description("HttpOnly Secure access token cookie, issued by login.")));
    }
}
