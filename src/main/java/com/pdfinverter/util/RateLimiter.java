package com.pdfinverter.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory rate limiter using a sliding window approach.
 * Tracks request timestamps per client IP and enforces a maximum
 * number of requests within a configurable time window.
 */
@Component
public class RateLimiter {

    private static final int MAX_REQUESTS = 30;
    private static final long WINDOW_MILLIS = 60_000; // 1 minute

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Long>> requestCounts = new ConcurrentHashMap<>();

    /**
     * Check whether the given client IP is allowed to make a request.
     *
     * @param clientIp the client's IP address
     * @return true if the request is within the rate limit, false otherwise
     */
    public boolean isAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MILLIS;

        CopyOnWriteArrayList<Long> timestamps = requestCounts.computeIfAbsent(clientIp, k -> new CopyOnWriteArrayList<>());

        // Remove expired entries
        timestamps.removeIf(t -> t < windowStart);

        if (timestamps.size() >= MAX_REQUESTS) {
            return false;
        }

        timestamps.add(now);
        return true;
    }

    /**
     * Periodically called to clean up stale entries from clients
     * that haven't made requests recently.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 300_000) // every 5 minutes
    public void cleanup() {
        long windowStart = System.currentTimeMillis() - WINDOW_MILLIS;
        requestCounts.forEach((ip, timestamps) -> {
            timestamps.removeIf(t -> t < windowStart);
            if (timestamps.isEmpty()) {
                requestCounts.remove(ip);
            }
        });
    }
}
