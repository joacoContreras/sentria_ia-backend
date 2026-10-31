package iua.edu.sentria.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permite que el frontend Next.js consuma la API REST desde el navegador:
 * local (http://localhost:3000) y producción (https://sentria-ia-frontend.vercel.app).
 * Los orígenes se configuran con sentria.cors.allowed-origins (lista separada por comas)
 * o con la variable de entorno SENTRIA_CORS_ALLOWED_ORIGINS.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${sentria.cors.allowed-origins:http://localhost:3000,https://sentria-ia-frontend.vercel.app}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept", "X-Requested-With")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
    }

}
