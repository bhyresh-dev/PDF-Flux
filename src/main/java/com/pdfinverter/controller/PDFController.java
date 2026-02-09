package com.pdfinverter.controller;

import com.pdfinverter.model.PDFProcessRequest;
import com.pdfinverter.service.PDFProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;

@Slf4j
@RestController
@RequestMapping("/api/pdf")
@RequiredArgsConstructor
public class PDFController {

    private final PDFProcessingService processingService;

    @PostMapping(value = "/process")
    public ResponseEntity<Resource> processPDF(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "FULL") String mode,
            @RequestParam(value = "rangeType", defaultValue = "ALL") String rangeType,
            @RequestParam(value = "customRange", required = false) String customRange
    ) {
        log.info("Processing PDF: {} with mode: {} and rangeType: {}", 
                file.getOriginalFilename(), mode, rangeType);
        try {
            // Build the request object
            PDFProcessRequest request = PDFProcessRequest.builder()
                    .mode(PDFProcessRequest.InversionMode.valueOf(mode.toUpperCase()))
                    .rangeType(PDFProcessRequest.RangeType.valueOf(rangeType.toUpperCase()))
                    .customRange(customRange)
                    .build();
            
            // Process the PDF
            File processedFile = processingService.processPDF(file, request);
            
            // Return the file as a resource
            Resource resource = new FileSystemResource(processedFile);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", 
                    file.getOriginalFilename().replace(".pdf", "_inverted.pdf"));
            headers.setContentLength(processedFile.length());
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
        } catch (Exception e) {
            log.error("Error processing PDF", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("PDF Inverter API is running");
    }

    @GetMapping("/info")
    public ResponseEntity<?> info() {
        return ResponseEntity.ok(new java.util.HashMap<String, Object>() {{
            put("version", "1.0.0");
            put("service", "PDF Color Inverter");
            put("supportedModes", new String[]{"FULL", "GRAYSCALE", "TEXT_ONLY", "CUSTOM"});
            put("supportedRanges", new String[]{"ALL", "CUSTOM", "ODD", "EVEN"});
        }});
    }
}
