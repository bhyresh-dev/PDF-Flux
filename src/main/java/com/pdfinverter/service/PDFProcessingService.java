package com.pdfinverter.service;

import com.pdfinverter.model.PDFProcessRequest;
import com.pdfinverter.model.PDFProcessRequest.InversionMode;
import com.pdfinverter.util.ColorInverter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.color.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        
        // Load the original PDF
        PDDocument document = Loader.loadPDF(file.getInputStream().readAllBytes());
        
        try {
            // Determine inversion mode
            InversionMode mode = (request != null && request.getMode() != null) 
                    ? request.getMode() 
                    : InversionMode.FULL;
            
            // Create a new document for the inverted pages
            PDDocument invertedDocument = new PDDocument();
            PDFRenderer renderer = new PDFRenderer(document);
            
            // Process each page
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                log.debug("Processing page {}/{}", pageIndex + 1, document.getNumberOfPages());
                
                // Render the page to an image
                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, 300, ImageType.RGB);
                
                // Invert the colors
                BufferedImage invertedImage = ColorInverter.invertImage(pageImage, mode);
                
                // Create a new page in the output document
                PDPage originalPage = document.getPage(pageIndex);
                PDRectangle pageSize = originalPage.getMediaBox();
                PDPage newPage = new PDPage(pageSize);
                invertedDocument.addPage(newPage);
                
                // Convert the inverted image to PDImageXObject
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(invertedImage, "jpg", baos);
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                        invertedDocument,
                        baos.toByteArray(),
                        "inverted_page_" + pageIndex
                );
                
                // Draw the image on the new page
                try (PDPageContentStream contentStream = new PDPageContentStream(
                        invertedDocument, newPage, PDPageContentStream.AppendMode.OVERWRITE, true)) {
                    contentStream.drawImage(pdImage, 0, 0, pageSize.getWidth(), pageSize.getHeight());
                }
            }
            
            // Save the inverted document
            String outputFileName = generateOutputFileName(file.getOriginalFilename());
            File outputFile = new File(TEMP_DIR + outputFileName);
            invertedDocument.save(outputFile);
            invertedDocument.close();
            
            log.info("PDF processed successfully: {}", outputFileName);
            return outputFile;
            
        } finally {
            document.close();
        }
    }

    private String generateOutputFileName(String originalFilename) {
        String baseName = originalFilename.replaceFirst("[.][^.]+$", "");
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        return baseName + "_inverted_" + uniqueId + ".pdf";
    }
}
