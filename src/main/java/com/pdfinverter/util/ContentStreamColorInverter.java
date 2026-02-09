package com.pdfinverter.util;

import com.pdfinverter.model.PDFProcessRequest.InversionMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * True PDF color inversion via content-stream manipulation.
 * <p>
 * Parses PDF content streams and rewrites color operators (rg/RG, g/G, k/K,
 * sc/SC, scn/SCN) so that selectable text, vector graphics, and print quality
 * are fully preserved.  Embedded raster images (XObjects) are extracted,
 * pixel-inverted, and re-embedded.  Form XObjects are handled recursively.
 *
 * @author PDF Inverter Team
 * @version 2.0.0
 */
@Slf4j
public class ContentStreamColorInverter {

    /* Custom dark-mode palette (matches the raster CUSTOM mode) */
    private static final float CUSTOM_BG_R = 42f / 255f;
    private static final float CUSTOM_BG_G = 42f / 255f;
    private static final float CUSTOM_BG_B = 42f / 255f;
    private static final float CUSTOM_FG_R = 232f / 255f;
    private static final float CUSTOM_FG_G = 232f / 255f;
    private static final float CUSTOM_FG_B = 232f / 255f;

    private ContentStreamColorInverter() { /* utility class */ }

    // ------------------------------------------------------------------ public

    /**
     * Invert all visible colours on the given page.
     *
     * @param document  the owning document (needed for image factories)
     * @param page      the page to process
     * @param mode      inversion mode
     * @param compress  when {@code true} re-embedded images use JPEG compression
     */
    public static void invertPage(PDDocument document, PDPage page,
                                  InversionMode mode, boolean compress, int outputDpi) throws IOException {

        // 1. Rewrite colour operators in the page content stream
        invertContentStream(document, page, mode);

        // 2. Invert raster images (skip for TEXT_ONLY – text-only preserves images)
        boolean invertImages = (mode != InversionMode.TEXT_ONLY);
        Set<String> visited = new HashSet<>();

        if (invertImages) {
            invertResourceImages(document, page.getResources(), mode, compress, outputDpi, visited);
        }

        // 3. Recurse into Form XObjects
        invertFormXObjects(document, page.getResources(), mode, compress, outputDpi, visited);

        // 4. Invert annotation appearances (form fields, stamps, links, etc.)
        invertAnnotations(document, page, mode, compress, outputDpi, visited);
    }

    // ------------------------------------------------------- content stream ops

    private static void invertContentStream(PDDocument document, PDPage page,
                                            InversionMode mode) throws IOException {
        InputStream is = page.getContents();
        if (is == null) return;

        byte[] contentBytes;
        try { contentBytes = is.readAllBytes(); } finally { is.close(); }
        if (contentBytes.length == 0) return;

        List<Object> tokens     = parseTokens(contentBytes);
        List<Object> processed  = processTokens(tokens, mode);

        // Prepend an opaque background rectangle (inverted white → black / dark-gray)
        PDRectangle box = page.getMediaBox();
        processed = prependBackground(processed, box, mode);

        // Write back with FlateDecode compression
        PDStream newStream = new PDStream(document);
        try (OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(out);
            writer.writeTokens(processed);
        }
        page.setContents(newStream);
    }

    // ------------------------------------------------------------ token parsing

    private static List<Object> parseTokens(byte[] contentBytes) throws IOException {
        List<Object> tokens = new ArrayList<>();
        PDFStreamParser parser = new PDFStreamParser(contentBytes);
        Object token;
        while ((token = parser.parseNextToken()) != null) {
            tokens.add(token);
        }
        return tokens;
    }

    // --------------------------------------------------------- token processing

    static List<Object> processTokens(List<Object> tokens, InversionMode mode) {
        List<Object>  result   = new ArrayList<>();
        List<COSBase> operands = new ArrayList<>();

        for (Object token : tokens) {
            if (token instanceof Operator op) {
                String name = op.getName();

                if (isColorOperator(name)) {
                    invertOperands(operands, name, mode);
                }

                result.addAll(operands);
                result.add(op);
                operands.clear();
            } else if (token instanceof COSBase base) {
                operands.add(base);
            }
        }
        result.addAll(operands);          // trailing operands (shouldn't happen)
        return result;
    }

    private static boolean isColorOperator(String n) {
        return switch (n) {
            case "g", "G", "rg", "RG", "k", "K",
                 "sc", "SC", "scn", "SCN" -> true;
            default -> false;
        };
    }

    // --------------------------------------------------------- operand inverters

    private static void invertOperands(List<COSBase> ops, String opName,
                                       InversionMode mode) {
        switch (opName) {
            case "g", "G"     -> invertGray(ops, mode);
            case "rg", "RG"   -> invertRGB(ops, mode);
            case "k", "K"     -> invertCMYK(ops, mode);
            case "sc", "SC",
                 "scn", "SCN" -> invertSC(ops, mode);
        }
    }

    /* g / G  –  one gray operand */
    private static void invertGray(List<COSBase> ops, InversionMode mode) {
        if (ops.isEmpty()) return;
        int idx = ops.size() - 1;
        if (!(ops.get(idx) instanceof COSNumber num)) return;

        float gray = num.floatValue();

        switch (mode) {
            case FULL, GRAYSCALE, TEXT_ONLY ->
                ops.set(idx, new COSFloat(clamp(1f - gray)));
            case CUSTOM -> {
                float[] mapped = customMapGray(gray);
                float cg = luminance(mapped[0], mapped[1], mapped[2]);
                ops.set(idx, new COSFloat(clamp(cg)));
            }
        }
    }

    /* rg / RG  –  three RGB operands */
    private static void invertRGB(List<COSBase> ops, InversionMode mode) {
        if (ops.size() < 3) return;
        int base = ops.size() - 3;

        float r = floatAt(ops, base);
        float g = floatAt(ops, base + 1);
        float b = floatAt(ops, base + 2);

        float[] out = switch (mode) {
            case FULL, TEXT_ONLY -> new float[]{1f - r, 1f - g, 1f - b};
            case GRAYSCALE -> {
                float inv = 1f - luminance(r, g, b);
                yield new float[]{inv, inv, inv};
            }
            case CUSTOM -> customMapRGB(r, g, b);
        };

        ops.set(base,     new COSFloat(clamp(out[0])));
        ops.set(base + 1, new COSFloat(clamp(out[1])));
        ops.set(base + 2, new COSFloat(clamp(out[2])));
    }

    /* k / K  –  four CMYK operands */
    private static void invertCMYK(List<COSBase> ops, InversionMode mode) {
        if (ops.size() < 4) return;
        int base = ops.size() - 4;

        float c = floatAt(ops, base);
        float m = floatAt(ops, base + 1);
        float y = floatAt(ops, base + 2);
        float k = floatAt(ops, base + 3);

        // First convert CMYK → RGB for correct visual inversion
        float rr = (1f - c) * (1f - k);
        float gg = (1f - m) * (1f - k);
        float bb = (1f - y) * (1f - k);

        float[] rgbOut;

        switch (mode) {
            case FULL, TEXT_ONLY -> {
                rgbOut = new float[]{1f - rr, 1f - gg, 1f - bb};
            }
            case GRAYSCALE -> {
                float invGray = 1f - luminance(rr, gg, bb);
                rgbOut = new float[]{invGray, invGray, invGray};
            }
            case CUSTOM -> {
                rgbOut = customMapRGB(rr, gg, bb);
            }
            default -> {
                rgbOut = new float[]{1f - rr, 1f - gg, 1f - bb};
            }
        }

        // Convert RGB back to CMYK
        float nk = 1f - Math.max(rgbOut[0], Math.max(rgbOut[1], rgbOut[2]));
        if (nk >= 1f) {
            ops.set(base,     new COSFloat(0f));
            ops.set(base + 1, new COSFloat(0f));
            ops.set(base + 2, new COSFloat(0f));
            ops.set(base + 3, new COSFloat(1f));
        } else {
            ops.set(base,     new COSFloat(clamp((1f - rgbOut[0] - nk) / (1f - nk))));
            ops.set(base + 1, new COSFloat(clamp((1f - rgbOut[1] - nk) / (1f - nk))));
            ops.set(base + 2, new COSFloat(clamp((1f - rgbOut[2] - nk) / (1f - nk))));
            ops.set(base + 3, new COSFloat(clamp(nk)));
        }
    }

    /* sc / SC / scn / SCN  –  variable-length; heuristic by operand count */
    private static void invertSC(List<COSBase> ops, InversionMode mode) {
        int numericCount = 0;
        for (COSBase op : ops) {
            if (op instanceof COSNumber) numericCount++;
        }

        switch (numericCount) {
            case 1 -> invertGray(ops, mode);
            case 3 -> invertSCN_RGB(ops, mode);
            case 4 -> invertSCN_CMYK(ops, mode);
            default -> {
                // Unknown colour space – invert all numeric values as a best-effort
                for (int i = 0; i < ops.size(); i++) {
                    if (ops.get(i) instanceof COSNumber num) {
                        ops.set(i, new COSFloat(clamp(1f - num.floatValue())));
                    }
                }
            }
        }
    }

    private static void invertSCN_RGB(List<COSBase> ops, InversionMode mode) {
        List<Integer> idx = numericIndices(ops);
        if (idx.size() < 3) return;

        float r = ((COSNumber) ops.get(idx.get(0))).floatValue();
        float g = ((COSNumber) ops.get(idx.get(1))).floatValue();
        float b = ((COSNumber) ops.get(idx.get(2))).floatValue();

        float[] out = switch (mode) {
            case GRAYSCALE -> { float v = 1f - luminance(r, g, b); yield new float[]{v, v, v}; }
            case CUSTOM    -> customMapRGB(r, g, b);
            default        -> new float[]{1f - r, 1f - g, 1f - b};
        };

        ops.set(idx.get(0), new COSFloat(clamp(out[0])));
        ops.set(idx.get(1), new COSFloat(clamp(out[1])));
        ops.set(idx.get(2), new COSFloat(clamp(out[2])));
    }

    private static void invertSCN_CMYK(List<COSBase> ops, InversionMode mode) {
        List<Integer> idx = numericIndices(ops);
        if (idx.size() < 4) return;

        float c = ((COSNumber) ops.get(idx.get(0))).floatValue();
        float m = ((COSNumber) ops.get(idx.get(1))).floatValue();
        float y = ((COSNumber) ops.get(idx.get(2))).floatValue();
        float k = ((COSNumber) ops.get(idx.get(3))).floatValue();

        // Convert CMYK → RGB for correct visual inversion
        float rr = (1f - c) * (1f - k);
        float gg = (1f - m) * (1f - k);
        float bb = (1f - y) * (1f - k);

        float[] rgbOut;

        switch (mode) {
            case GRAYSCALE -> {
                float invGray = 1f - luminance(rr, gg, bb);
                rgbOut = new float[]{invGray, invGray, invGray};
            }
            case CUSTOM -> rgbOut = customMapRGB(rr, gg, bb);
            default -> rgbOut = new float[]{1f - rr, 1f - gg, 1f - bb};
        }

        // Convert RGB back to CMYK
        float nk = 1f - Math.max(rgbOut[0], Math.max(rgbOut[1], rgbOut[2]));
        if (nk >= 1f) {
            ops.set(idx.get(0), new COSFloat(0f));
            ops.set(idx.get(1), new COSFloat(0f));
            ops.set(idx.get(2), new COSFloat(0f));
            ops.set(idx.get(3), new COSFloat(1f));
        } else {
            ops.set(idx.get(0), new COSFloat(clamp((1f - rgbOut[0] - nk) / (1f - nk))));
            ops.set(idx.get(1), new COSFloat(clamp((1f - rgbOut[1] - nk) / (1f - nk))));
            ops.set(idx.get(2), new COSFloat(clamp((1f - rgbOut[2] - nk) / (1f - nk))));
            ops.set(idx.get(3), new COSFloat(clamp(nk)));
        }
    }

    // ------------------------------------------------- background rectangle ops

    static List<Object> prependBackground(List<Object> tokens,
                                          PDRectangle box, InversionMode mode) {
        float[] bg = switch (mode) {
            case CUSTOM -> new float[]{CUSTOM_BG_R, CUSTOM_BG_G, CUSTOM_BG_B};
            default     -> new float[]{0f, 0f, 0f};      // inverted white → black
        };

        // The inverted default colour.  PDF's initial fill & stroke are both
        // black (0,0,0).  Many content streams never emit an explicit colour
        // operator for text because black is the default.  After we paint a
        // black background, such text would be invisible (black-on-black).
        // We therefore set the initial fill & stroke to the inverted default
        // so un-coloured content renders correctly.
        float[] fg = switch (mode) {
            case CUSTOM -> new float[]{CUSTOM_FG_R, CUSTOM_FG_G, CUSTOM_FG_B};
            default     -> new float[]{1f, 1f, 1f};      // inverted black → white
        };

        List<Object> result = new ArrayList<>(tokens.size() + 20);

        // q                      save graphics state
        result.add(Operator.getOperator("q"));
        // r g b rg               set fill colour
        result.add(new COSFloat(bg[0]));
        result.add(new COSFloat(bg[1]));
        result.add(new COSFloat(bg[2]));
        result.add(Operator.getOperator("rg"));
        // x y w h re             full-page rectangle
        result.add(new COSFloat(box.getLowerLeftX()));
        result.add(new COSFloat(box.getLowerLeftY()));
        result.add(new COSFloat(box.getWidth()));
        result.add(new COSFloat(box.getHeight()));
        result.add(Operator.getOperator("re"));
        // f                      fill
        result.add(Operator.getOperator("f"));
        // Q                      restore graphics state
        result.add(Operator.getOperator("Q"));

        // Set inverted-default fill and stroke colours.
        // Any content that never sets an explicit colour will now use these
        // instead of the PDF default (black), making text visible on the
        // inverted background.
        result.add(new COSFloat(fg[0]));
        result.add(new COSFloat(fg[1]));
        result.add(new COSFloat(fg[2]));
        result.add(Operator.getOperator("rg"));    // fill colour
        result.add(new COSFloat(fg[0]));
        result.add(new COSFloat(fg[1]));
        result.add(new COSFloat(fg[2]));
        result.add(Operator.getOperator("RG"));    // stroke colour

        result.addAll(tokens);
        return result;
    }

    // --------------------------------------------- image & form XObject helpers

    static void invertResourceImages(PDDocument document, PDResources resources,
                                     InversionMode mode, boolean compress, int outputDpi,
                                     Set<String> visited) throws IOException {
        if (resources == null) return;

        // Collect image names from THIS resource dictionary only.
        // Do NOT filter by the shared 'visited' set — resource names like "Im1"
        // are local to each dictionary, so the same name in a different dictionary
        // refers to a completely different image.
        List<COSName> imageNames = new ArrayList<>();
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xo = resources.getXObject(name);
                if (xo instanceof PDImageXObject) imageNames.add(name);
            } catch (IOException e) {
                log.debug("Skipping unreadable XObject: {}", name.getName());
            }
        }

        for (COSName name : imageNames) {
            try {
                PDImageXObject image = (PDImageXObject) resources.getXObject(name);

                // Skip if this exact PDF object was already inverted (shared across
                // multiple resource dictionaries).
                String cosKey = "img_" + System.identityHashCode(image.getCOSObject());
                if (visited.contains(cosKey)) {
                    continue;
                }
                visited.add(cosKey);

                // Skip stencil/mask images – their paint colour comes from the
                // content stream (which is already inverted); pixel-inverting the
                // mask would swap painted/unpainted areas, producing wrong output.
                if (image.isStencil()) {
                    log.debug("Skipping stencil image: {}", name.getName());
                    continue;
                }

                BufferedImage bimg     = image.getImage();
                BufferedImage inverted = ColorInverter.invertImage(bimg, mode);

                // Scale image when lower DPI is requested
                inverted = scaleImageForDpi(inverted, outputDpi);

                // Choose encoding: JPEG for compress (skip if alpha channel present)
                boolean hasAlpha = inverted.getColorModel().hasAlpha();
                PDImageXObject newImage;
                if (compress && !hasAlpha) {
                    float quality = mapDpiToJpegQuality(outputDpi);
                    newImage = JPEGFactory.createFromImage(document, inverted, quality);
                } else {
                    newImage = LosslessFactory.createFromImage(document, inverted);
                }
                resources.put(name, newImage);
                log.debug("Inverted image XObject: {}", name.getName());
            } catch (Exception e) {
                log.warn("Could not invert image {}: {}", name.getName(), e.getMessage());
            }
        }
    }

    static void invertFormXObjects(PDDocument document, PDResources resources,
                                   InversionMode mode, boolean compress, int outputDpi,
                                   Set<String> visited) throws IOException {
        if (resources == null) return;

        List<COSName> formNames = new ArrayList<>();
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xo = resources.getXObject(name);
                if (xo instanceof PDFormXObject) formNames.add(name);
            } catch (IOException e) {
                log.debug("Skipping unreadable XObject: {}", name.getName());
            }
        }

        for (COSName name : formNames) {
            try {
                PDFormXObject form = (PDFormXObject) resources.getXObject(name);

                // Track by COSObject identity, not by resource name.
                // Names like "Fm0" are local to each dictionary; the same name
                // in a different dictionary can point to a completely different form.
                String formKey = "form_" + System.identityHashCode(form.getCOSObject());
                if (visited.contains(formKey)) continue;
                visited.add(formKey);

                // Rewrite colour operators inside the form
                InputStream fis = form.getContents();
                if (fis != null) {
                    byte[] bytes;
                    try { bytes = fis.readAllBytes(); } finally { fis.close(); }
                    if (bytes.length > 0) {
                        List<Object> tokens    = parseTokens(bytes);
                        List<Object> processed = processTokens(tokens, mode);
                        try (OutputStream out =
                                     form.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
                            ContentStreamWriter w = new ContentStreamWriter(out);
                            w.writeTokens(processed);
                        }
                    }
                }

                // Recurse into the form's own resources
                if (mode != InversionMode.TEXT_ONLY) {
                    invertResourceImages(document, form.getResources(), mode, compress, outputDpi, visited);
                }
                invertFormXObjects(document, form.getResources(), mode, compress, outputDpi, visited);

                log.debug("Inverted Form XObject: {}", name.getName());
            } catch (Exception e) {
                log.warn("Could not invert form {}: {}", name.getName(), e.getMessage());
            }
        }
    }

    // ---------------------------------------------------- annotation processing

    private static void invertAnnotations(PDDocument document, PDPage page,
                                          InversionMode mode, boolean compress, int outputDpi,
                                          Set<String> visited) throws IOException {
        List<PDAnnotation> annotations;
        try {
            annotations = page.getAnnotations();
        } catch (Exception e) {
            log.debug("Skipping annotations: {}", e.getMessage());
            return;
        }
        if (annotations == null || annotations.isEmpty()) return;

        for (PDAnnotation annot : annotations) {
            PDAppearanceDictionary appearanceDict = annot.getAppearance();
            if (appearanceDict == null) continue;

            processAppearanceEntry(document, appearanceDict.getNormalAppearance(),
                    mode, compress, outputDpi, visited);
            processAppearanceEntry(document, appearanceDict.getRolloverAppearance(),
                    mode, compress, outputDpi, visited);
            processAppearanceEntry(document, appearanceDict.getDownAppearance(),
                    mode, compress, outputDpi, visited);
        }
    }

    private static void processAppearanceEntry(PDDocument document, PDAppearanceEntry entry,
                                               InversionMode mode, boolean compress, int outputDpi,
                                               Set<String> visited) throws IOException {
        if (entry == null) return;

        if (entry.isStream()) {
            invertAppearanceStream(document, entry.getAppearanceStream(),
                    mode, compress, outputDpi, visited);
        } else if (entry.isSubDictionary()) {
            for (Map.Entry<COSName, PDAppearanceStream> sub
                    : entry.getSubDictionary().entrySet()) {
                invertAppearanceStream(document, sub.getValue(),
                        mode, compress, outputDpi, visited);
            }
        }
    }

    private static void invertAppearanceStream(PDDocument document, PDAppearanceStream stream,
                                               InversionMode mode, boolean compress, int outputDpi,
                                               Set<String> visited) throws IOException {
        if (stream == null) return;

        String key = "annot_" + System.identityHashCode(stream);
        if (visited.contains(key)) return;
        visited.add(key);

        // Rewrite colour operators inside the appearance stream
        InputStream is = stream.getContents();
        if (is != null) {
            byte[] bytes;
            try { bytes = is.readAllBytes(); } finally { is.close(); }
            if (bytes.length > 0) {
                List<Object> tokens    = parseTokens(bytes);
                List<Object> processed = processTokens(tokens, mode);
                try (OutputStream out =
                             stream.getCOSObject().createOutputStream(COSName.FLATE_DECODE)) {
                    ContentStreamWriter w = new ContentStreamWriter(out);
                    w.writeTokens(processed);
                }
            }
        }

        // Invert images and forms inside the appearance
        if (mode != InversionMode.TEXT_ONLY) {
            invertResourceImages(document, stream.getResources(),
                    mode, compress, outputDpi, visited);
        }
        invertFormXObjects(document, stream.getResources(),
                mode, compress, outputDpi, visited);
    }

    // ---------------------------------------------------------- DPI & scaling

    private static float mapDpiToJpegQuality(int outputDpi) {
        if (outputDpi <= 150) return 0.7f;
        if (outputDpi >= 600) return 0.92f;
        return 0.85f;
    }

    private static BufferedImage scaleImageForDpi(BufferedImage img, int outputDpi) {
        if (outputDpi >= 300) return img;

        float scale = outputDpi / 300.0f;
        int newW = Math.max(1, Math.round(img.getWidth() * scale));
        int newH = Math.max(1, Math.round(img.getHeight() * scale));
        if (newW >= img.getWidth() && newH >= img.getHeight()) return img;

        boolean hasAlpha = img.getColorModel().hasAlpha();
        int type = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled = new BufferedImage(newW, newH, type);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    // ----------------------------------------------------------------- helpers

    private static float[] customMapRGB(float r, float g, float b) {
        float brightness = luminance(r, g, b);
        if (brightness > 0.78f) {
            return new float[]{CUSTOM_BG_R, CUSTOM_BG_G, CUSTOM_BG_B};
        } else if (brightness < 0.22f) {
            return new float[]{CUSTOM_FG_R, CUSTOM_FG_G, CUSTOM_FG_B};
        }
        return new float[]{
                clamp(1f - r + 30f / 255f),
                clamp(1f - g + 30f / 255f),
                clamp(1f - b + 30f / 255f)
        };
    }

    private static float[] customMapGray(float gray) {
        return customMapRGB(gray, gray, gray);
    }

    private static List<Integer> numericIndices(List<COSBase> ops) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i) instanceof COSNumber) indices.add(i);
        }
        return indices;
    }

    private static float floatAt(List<COSBase> ops, int i) {
        COSBase item = ops.get(i);
        return (item instanceof COSNumber n) ? n.floatValue() : 0f;
    }

    private static float luminance(float r, float g, float b) {
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
