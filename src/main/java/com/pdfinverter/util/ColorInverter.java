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
        
        BufferedImage invertedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                
                int newRGB;
                
                switch (mode) {
                    case FULL:
                        newRGB = new Color(255 - r, 255 - g, 255 - b).getRGB();
                        break;
                    case GRAYSCALE:
                        int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                        int invertedGray = 255 - gray;
                        newRGB = new Color(invertedGray, invertedGray, invertedGray).getRGB();
                        break;
                    case TEXT_ONLY:
                        // Invert only dark colors (text), keep light colors (backgrounds)
                        int brightness = (r + g + b) / 3;
                        if (brightness < 128) {
                            // Dark pixel - likely text, invert it
                            newRGB = new Color(255 - r, 255 - g, 255 - b).getRGB();
                        } else {
                            // Light pixel - likely background, keep it
                            newRGB = rgb;
                        }
                        break;
                    case CUSTOM:
                        int avgBrightness = (r + g + b) / 3;
                        if (avgBrightness > 200) {
                            newRGB = DARK_MODE_BACKGROUND.getRGB();
                        } else if (avgBrightness < 55) {
                            newRGB = DARK_MODE_TEXT.getRGB();
                        } else {
                            newRGB = new Color(
                                Math.max(0, Math.min(255, 255 - r + 30)),
                                Math.max(0, Math.min(255, 255 - g + 30)),
                                Math.max(0, Math.min(255, 255 - b + 30))
                            ).getRGB();
                        }
                        break;
                    default:
                        // Default to FULL inversion
                        newRGB = new Color(255 - r, 255 - g, 255 - b).getRGB();
                }
                
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
