package com.pdfinverter.util;

import com.pdfinverter.model.PDFProcessRequest.RangeType;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class PageRangeParser {

    private static final Pattern RANGE_PATTERN = Pattern.compile("(\\d+)(?:-(\\d+))?");

    public static Set<Integer> parse(RangeType rangeType, String customRange, int totalPages) {
        Set<Integer> pages = new LinkedHashSet<>();

        if (rangeType == null) {
            rangeType = RangeType.ALL;
        }

        switch (rangeType) {
            case ALL:
                for (int i = 1; i <= totalPages; i++) {
                    pages.add(i);
                }
                break;
            case ODD:
                for (int i = 1; i <= totalPages; i += 2) {
                    pages.add(i);
                }
                break;
            case EVEN:
                for (int i = 2; i <= totalPages; i += 2) {
                    pages.add(i);
                }
                break;
            case CUSTOM:
                pages = parseCustomRange(customRange, totalPages);
                break;
        }

        return pages;
    }

    private static Set<Integer> parseCustomRange(String rangeString, int totalPages) {
        Set<Integer> pages = new LinkedHashSet<>();

        if (rangeString == null || rangeString.trim().isEmpty()) {
            return pages;
        }

        String[] parts = rangeString.split(",");

        for (String part : parts) {
            part = part.trim();
            Matcher matcher = RANGE_PATTERN.matcher(part);

            if (matcher.matches()) {
                int start = Integer.parseInt(matcher.group(1));
                String endStr = matcher.group(2);

                if (endStr != null) {
                    int end = Integer.parseInt(endStr);
                    for (int i = Math.max(1, start); i <= Math.min(totalPages, end); i++) {
                        pages.add(i);
                    }
                } else {
                    if (start >= 1 && start <= totalPages) {
                        pages.add(start);
                    }
                }
            }
        }

        return pages;
    }
}
