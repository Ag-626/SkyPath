package com.skypath.backend.dto;

/**
 * Layover at a specific airport between two segments of an itinerary.
 */
public record LayoverResponse(
    String airportCode,
    long durationMinutes
) {
}