package com.pdfinverter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF Processing Request Model
 * 
 * Represents the configuration for PDF color inversion processing.
 * 
 * @author PDF Inverter Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PDFProcessRequest {

    /**
     * Inversion modes supported by the application
     */
    public enum InversionMode {
        FULL("Full color inversion"),
        GRAYSCALE("Grayscale inversion"),
        TEXT_ONLY("Text-only inversion"),
        CUSTOM("Custom inversion");

        private final String description;

        InversionMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Page range types
     */
    public enum RangeType {
        ALL("All pages"),
        CUSTOM("Custom range"),
        ODD("Odd pages"),
        EVEN("Even pages");

        private final String description;

        RangeType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Inversion mode to apply
     */
    private InversionMode mode;

    /**
     * Page range type
     */
    private RangeType rangeType;

    /**
     * Custom range string (e.g., "1,3,5-10")
     */
    private String customRange;

    /**
     * Output DPI (default 300)
     */
    @Builder.Default
    private Integer outputDpi = 300;

    /**
     * Whether to compress output
     */
    @Builder.Default
    private Boolean compress = false;

    /**
     * Whether to maintain text selectability
     */
    @Builder.Default
    private Boolean preserveText = true;

    /**
     * Custom inversion percentage (0-100)
     */
    @Builder.Default
    private Integer inversionPercentage = 100;

    /**
     * Whether to enable OCR
     */
    @Builder.Default
    private Boolean enableOCR = false;
}
