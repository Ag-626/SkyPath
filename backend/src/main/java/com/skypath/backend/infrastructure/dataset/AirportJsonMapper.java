package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.skypath.backend.domain.Airport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AirportJsonMapper {

  private static final Logger log = LoggerFactory.getLogger(AirportJsonMapper.class);

  private enum AirportJsonField {
    CODE("code"),
    NAME("name"),
    CITY("city"),
    COUNTRY("country"),
    TIMEZONE("timezone");

    private final String jsonKey;

    AirportJsonField(String jsonKey) {
      this.jsonKey = jsonKey;
    }

    public String jsonKey() {
      return jsonKey;
    }
  }

  /**
   * Convert a single JSON node into an Airport, or empty if invalid.
   * Used by dataset loaders to keep parsing logic reusable/testable.
   */
  public Optional<Airport> toAirport(JsonNode node) {
    // 1. Extract all raw fields into an EnumMap (easy to extend later)
    Map<AirportJsonField, String> raw = new EnumMap<>(AirportJsonField.class);
    for (AirportJsonField field : AirportJsonField.values()) {
      raw.put(field, textOrNull(node, field.jsonKey()));
    }

    // 2. Compute missing fields in a generic way
    List<AirportJsonField> missingFields = raw.entrySet().stream()
        .filter(e -> e.getValue() == null || e.getValue().isBlank())
        .map(Map.Entry::getKey)
        .toList();

    if (!missingFields.isEmpty()) {
      log.warn(
          "Skipping airport due to missing fields {}. Raw values: {}",
          missingFields, raw
      );
      return Optional.empty();
    }

    String code = raw.get(AirportJsonField.CODE);
    String name = raw.get(AirportJsonField.NAME);
    String city = raw.get(AirportJsonField.CITY);
    String country = raw.get(AirportJsonField.COUNTRY);
    String timezoneId = raw.get(AirportJsonField.TIMEZONE);

    Optional<ZoneId> zoneIdOpt = parseZoneId(code, timezoneId);
    if (zoneIdOpt.isEmpty()) {
      return Optional.empty();
    }
    ZoneId zoneId = zoneIdOpt.get();

    Airport airport = new Airport(code, name, city, country, zoneId);
    return Optional.of(airport);
  }

  private Optional<ZoneId> parseZoneId(String code, String timezoneId) {
    try {
      return Optional.of(ZoneId.of(timezoneId));
    } catch (DateTimeException e) {
      log.warn(
          "Skipping airport with invalid timezone. code={}, timezone={}",
          code, timezoneId
      );
      return Optional.empty();
    }
  }

  private String textOrNull(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    return value != null && !value.isNull() ? value.asText() : null;
  }
}