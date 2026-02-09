package com.pdfinverter.util;

import com.pdfinverter.model.PDFProcessRequest.InversionMode;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;

@Slf4j
public class ColorInverter {

    private static final Color DARK_MODE_BACKGROUND = new Color(42, 42, 42);
    private static final Color DARK_MODE_TEXT = new Color(232, 232, 232);

    public static float[] invertRGB(float r, float g, float b) {
        return new float[] {1.0f - r, 1.0f - g, 1.0f - b};
    }

    public static float[] invertCMYK(float c, float m, float y, float k) {
        return new float[] {1.0f - c, 1.0f - m, 1.0f - y, 1.0f - k};
    }

    public static float invertGrayscale(float gray) {
        return 1.0f - gray;
    }

    public static float rgbToGrayscale(float r, float g, float b) {
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    public static BufferedImage invertImage(BufferedImage image, InversionMode mode) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        boolean hasAlpha = image.getColorModel().hasAlpha();
        int imgType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

        // Normalise to a standard type so that getRGB() works consistently
        // across all colour models (CMYK, Indexed, Gray, custom, etc.)
        BufferedImage source;
        if (image.getType() != imgType) {
            source = new BufferedImage(width, height, imgType);
            Graphics2D g2d = source.createGraphics();
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();
        } else {
            source = image;
        }

        BufferedImage invertedImage = new BufferedImage(width, height, imgType);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = source.getRGB(x, y);
                
                int a = (rgb >> 24) & 0xFF;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                
                // Skip fully transparent pixels – preserve transparency as-is
                if (hasAlpha && a == 0) {
                    invertedImage.setRGB(x, y, 0);
                    continue;
                }
                
                int nr, ng, nb;
                
                switch (mode) {
                    case FULL:
                        nr = 255 - r;
                        ng = 255 - g;
                        nb = 255 - b;
                        break;
                    case GRAYSCALE:
                        int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                        int invertedGray = 255 - gray;
                        nr = invertedGray;
                        ng = invertedGray;
                        nb = invertedGray;
                        break;
                    case TEXT_ONLY:
                        int brightness = (r + g + b) / 3;
                        if (brightness < 128) {
                            nr = 255 - r;
                            ng = 255 - g;
                            nb = 255 - b;
                        } else {
                            nr = r;
                            ng = g;
                            nb = b;
                        }
                        break;
                    case CUSTOM:
                        int avgBrightness = (r + g + b) / 3;
                        if (avgBrightness > 200) {
                            nr = DARK_MODE_BACKGROUND.getRed();
                            ng = DARK_MODE_BACKGROUND.getGreen();
                            nb = DARK_MODE_BACKGROUND.getBlue();
                        } else if (avgBrightness < 55) {
                            nr = DARK_MODE_TEXT.getRed();
                            ng = DARK_MODE_TEXT.getGreen();
                            nb = DARK_MODE_TEXT.getBlue();
                        } else {
                            nr = Math.max(0, Math.min(255, 255 - r + 30));
                            ng = Math.max(0, Math.min(255, 255 - g + 30));
                            nb = Math.max(0, Math.min(255, 255 - b + 30));
                        }
                        break;
                    default:
                        nr = 255 - r;
                        ng = 255 - g;
                        nb = 255 - b;
                }
                
                int newRGB = (a << 24) | (nr << 16) | (ng << 8) | nb;
                invertedImage.setRGB(x, y, newRGB);
            }
        }
        
        return invertedImage;
    }

    public static boolean isDarkColor(float r, float g, float b) {
        float luminosity = rgbToGrayscale(r, g, b);
        return luminosity < 0.5f;
    }
}
