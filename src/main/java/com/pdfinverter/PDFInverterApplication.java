package com.pdfinverter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Main application class for PDF Inverter Backend
 * 
 * Production-ready Spring Boot application with:
 * - Frontend served from same origin (no CORS needed for default setup)
 * - Optional CORS support via CORS_ORIGINS environment variable
 * - Async processing support
 * - Professional error handling
 * 
 * @author PDF Inverter Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class PDFInverterApplication {

    public static void main(String[] args) {
        SpringApplication.run(PDFInverterApplication.class, args);
    }

    /**
     * Configure CORS only when CORS_ORIGINS environment variable is set.
     * Since the frontend is served from the same Spring Boot server,
     * CORS is not needed for the default setup. Set CORS_ORIGINS for
     * cross-origin access (e.g., external API consumers).
     *
     * Example: CORS_ORIGINS=https://example.com,https://app.example.com
     */
    @Value("${CORS_ORIGINS:}")
    private String corsOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (corsOrigins != null && !corsOrigins.isBlank()) {
                    registry.addMapping("/api/**")
                            .allowedOrigins(corsOrigins.split(","))
                            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                            .allowedHeaders("*")
                            .allowCredentials(true)
                            .maxAge(3600);
                }
            }
        };
    }
}
