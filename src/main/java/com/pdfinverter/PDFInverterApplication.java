package com.pdfinverter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Main application class for PDF Inverter Backend
 * 
 * Production-ready Spring Boot application with:
 * - CORS configuration for frontend integration
 * - Async processing support
 * - Professional error handling
 * 
 * @author PDF Inverter Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableAsync
public class PDFInverterApplication {

    public static void main(String[] args) {
        SpringApplication.run(PDFInverterApplication.class, args);
    }

    /**
     * Configure CORS for local development and production
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(
                            "http://localhost:3000",
                            "http://localhost:8080",
                            "https://your-production-domain.com"
                        )
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
