package com.skypath.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * API response model for an itinerary.
 */
public record ItineraryResponse(
    List<FlightSegmentResponse> segments,
    List<LayoverResponse> layovers,
    long totalDurationMinutes,
    BigDecimal totalPrice
) {
}