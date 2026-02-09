package com.pdfinverter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * PDF Processing Response Model
 * 
 * Represents the result of a PDF processing operation.
 * 
 * @author PDF Inverter Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PDFProcessResponse {

    /**
     * Unique file ID for download reference
     */
    private String fileId;

    /**
     * Original filename
     */
    private String originalFilename;

    /**
     * Output filename
     */
    private String outputFilename;

    /**
     * Processing status
     */
    private String status;

    /**
     * Processing message
     */
    private String message;

    /**
     * File size in bytes
     */
    private Long fileSize;

    /**
     * Processing time in milliseconds
     */
    private Long processingTimeMs;

    /**
     * Pages processed
     */
    private Integer pagesProcessed;

    /**
     * Processing mode used
     */
    private String mode;

    /**
     * Creation timestamp
     */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Expiration timestamp
     */
    private LocalDateTime expiresAt;

    /**
     * Download URL
     */
    private String downloadUrl;
}
