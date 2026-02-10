package com.pdfinverter.service;

import com.pdfinverter.model.PDFProcessRequest;
import com.pdfinverter.model.PDFProcessRequest.InversionMode;
import com.pdfinverter.model.PDFProcessRequest.RangeType;
import com.pdfinverter.util.ContentStreamColorInverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Slf4j
@Service
public class PDFProcessingService {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/pdf-inverter/";
    
    public PDFProcessingService() {
        try {
            Files.createDirectories(Path.of(TEMP_DIR));
        } catch (IOException e) {
            log.error("Failed to create temp directory", e);
        }
    }

    public File processPDF(MultipartFile file, PDFProcessRequest request) throws IOException {
        log.info("Starting PDF processing: {} with mode: {}", 
                file.getOriginalFilename(), 
                request != null && request.getMode() != null ? request.getMode() : "FULL");
        
        // Load the original PDF – modifying in-place preserves metadata, fonts,
        // bookmarks, selectable text, vector graphics, and print quality.
        PDDocument document = Loader.loadPDF(file.getInputStream().readAllBytes());
        
        try {
            InversionMode mode = (request != null && request.getMode() != null) 
                    ? request.getMode() 
                    : InversionMode.FULL;

            boolean compress = request != null && Boolean.TRUE.equals(request.getCompress());
            int outputDpi = (request != null && request.getOutputDpi() != null)
                    ? request.getOutputDpi() : 300;

            List<Integer> pageIndexes = resolvePageIndexes(document, request);

            // True PDF manipulation: rewrite colour operators & re-embed inverted images
            for (int pageIndex : pageIndexes) {
                try {
                    log.debug("Processing page {}/{}", pageIndex + 1, document.getNumberOfPages());
                    PDPage page = document.getPage(pageIndex);
                    ContentStreamColorInverter.invertPage(document, page, mode, compress, outputDpi);
                } catch (Exception e) {
                    log.warn("Failed to invert page {}/{}: {}",
                            pageIndex + 1, document.getNumberOfPages(), e.getMessage());
                    // Continue with remaining pages instead of aborting the entire document
                }
            }

            // Remove non-selected pages so the output contains only the requested range.
            // Iterate in reverse to preserve indices while removing.
            RangeType rangeType = (request != null && request.getRangeType() != null)
                    ? request.getRangeType() : RangeType.ALL;
            if (rangeType != RangeType.ALL) {
                Set<Integer> selectedSet = new HashSet<>(pageIndexes);
                for (int i = document.getNumberOfPages() - 1; i >= 0; i--) {
                    if (!selectedSet.contains(i)) {
                        document.removePage(i);
                    }
                }
                log.debug("Trimmed document to {} pages (range: {})", document.getNumberOfPages(), rangeType);
            }
            
            // Save the modified document (original metadata is preserved automatically)
            String outputFileName = generateOutputFileName(file.getOriginalFilename());
            File outputFile = new File(TEMP_DIR + outputFileName);
            document.save(outputFile);
            
            log.info("PDF processed successfully: {}", outputFileName);
            return outputFile;
            
        } finally {
            document.close();
        }
    }

    public File processBatch(List<MultipartFile> files, PDFProcessRequest request) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("No files provided for batch processing");
        }

        String batchFileName = "pdf_inverter_batch_" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
        File zipFile = new File(TEMP_DIR + batchFileName);

        Set<String> usedNames = new HashSet<>();

        try (ZipArchiveOutputStream zipStream = new ZipArchiveOutputStream(zipFile)) {
            zipStream.setEncoding("UTF-8");

            for (MultipartFile file : files) {
                File processedFile = processPDF(file, request);
                String entryName = buildZipEntryName(file.getOriginalFilename(), usedNames);

                ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                entry.setSize(processedFile.length());
                zipStream.putArchiveEntry(entry);
                Files.copy(processedFile.toPath(), zipStream);
                zipStream.closeArchiveEntry();

                if (!processedFile.delete()) {
                    log.debug("Unable to delete temp file: {}", processedFile.getAbsolutePath());
                }
            }
        }

        return zipFile;
    }

    private List<Integer> resolvePageIndexes(PDDocument document, PDFProcessRequest request) {
        int totalPages = document.getNumberOfPages();
        RangeType rangeType = request != null && request.getRangeType() != null
                ? request.getRangeType()
                : RangeType.ALL;

        List<Integer> pages = new ArrayList<>();

        switch (rangeType) {
            case ODD:
                for (int i = 0; i < totalPages; i++) {
                    if ((i + 1) % 2 == 1) {
                        pages.add(i);
                    }
                }
                break;
            case EVEN:
                for (int i = 0; i < totalPages; i++) {
                    if ((i + 1) % 2 == 0) {
                        pages.add(i);
                    }
                }
                break;
            case CUSTOM:
                String customRange = request != null ? request.getCustomRange() : null;
                pages.addAll(parseCustomRange(customRange, totalPages));
                break;
            case ALL:
            default:
                for (int i = 0; i < totalPages; i++) {
                    pages.add(i);
                }
                break;
        }

        if (pages.isEmpty()) {
            for (int i = 0; i < totalPages; i++) {
                pages.add(i);
            }
        }

        return pages;
    }

    private List<Integer> parseCustomRange(String customRange, int totalPages) {
        if (customRange == null || customRange.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String normalized = customRange.replaceAll("\\s+", "");
        String[] parts = normalized.split(",");
        Set<Integer> pages = new TreeSet<>();

        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }

            if (part.contains("-")) {
                String[] bounds = part.split("-");
                if (bounds.length != 2) {
                    continue;
                }

                Integer start = parsePositiveInt(bounds[0]);
                Integer end = parsePositiveInt(bounds[1]);
                if (start == null || end == null) {
                    continue;
                }

                if (start > end) {
                    int temp = start;
                    start = end;
                    end = temp;
                }

                for (int page = start; page <= end; page++) {
                    if (page >= 1 && page <= totalPages) {
                        pages.add(page - 1);
                    }
                }
            } else {
                Integer page = parsePositiveInt(part);
                if (page != null && page >= 1 && page <= totalPages) {
                    pages.add(page - 1);
                }
            }
        }

        return new ArrayList<>(pages);
    }

    private Integer parsePositiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String buildZipEntryName(String originalFilename, Set<String> usedNames) {
        String baseName = originalFilename == null ? "file" : originalFilename.replaceFirst("[.][^.]+$", "");
        String entryName = baseName + "_inverted.pdf";

        if (!usedNames.contains(entryName)) {
            usedNames.add(entryName);
            return entryName;
        }

        int counter = 2;
        while (usedNames.contains(baseName + "_inverted_" + counter + ".pdf")) {
            counter++;
        }

        entryName = baseName + "_inverted_" + counter + ".pdf";
        usedNames.add(entryName);
        return entryName;
    }

    private String generateOutputFileName(String originalFilename) {
        String baseName = originalFilename.replaceFirst("[.][^.]+$", "");
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return baseName + "_inverted_" + uniqueId + ".pdf";
    }

    /**
     * Scheduled cleanup task that runs every 30 minutes.
     * Deletes files in the temp directory that are older than 1 hour
     * to prevent disk space exhaustion from single-file processing results.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000) // every 30 minutes
    public void cleanupTempFiles() {
        File tempDir = new File(TEMP_DIR);
        if (!tempDir.exists() || !tempDir.isDirectory()) {
            return;
        }

        File[] files = tempDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        int deletedCount = 0;
        long freedBytes = 0;

        for (File file : files) {
            if (file.isFile()) {
                Instant lastModified = Instant.ofEpochMilli(file.lastModified());
                if (lastModified.isBefore(cutoff)) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        deletedCount++;
                        freedBytes += fileSize;
                    } else {
                        log.warn("Failed to delete expired temp file: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        if (deletedCount > 0) {
            String freedFormatted = humanReadableByteCount(freedBytes);
            log.info("Temp cleanup: deleted {} file(s), freed {}", deletedCount, freedFormatted);
        }
    }

    private String humanReadableByteCount(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
}
