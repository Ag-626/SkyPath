package com.skypath.backend.dto;

public record AirportSummaryDto(
    String code,
    String city,
    String name,
    String country
) {}