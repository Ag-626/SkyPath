package com.skypath.backend.dto;

import java.math.BigDecimal;

public record FlightSegmentResponse(
    String flightNumber,
    String airline,
    String originCode,
    String originCity,
    String destinationCode,
    String destinationCity,
    String departureTimeLocal,   // ISO-8601 with offset, in origin timezone
    String arrivalTimeLocal,     // ISO-8601 with offset, in destination timezone
    String aircraft,
    BigDecimal price
) {
}