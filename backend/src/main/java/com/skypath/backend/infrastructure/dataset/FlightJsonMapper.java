package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class FlightJsonMapper {

  private static final Logger log = LoggerFactory.getLogger(FlightJsonMapper.class);

  private enum FlightJsonField {
    FLIGHT_NUMBER("flightNumber"),
    AIRLINE("airline"),
    ORIGIN("origin"),
    DESTINATION("destination"),
    DEPARTURE_TIME("departureTime"),
    ARRIVAL_TIME("arrivalTime"),
    PRICE("price"),
    AIRCRAFT("aircraft");

    private final String jsonKey;

    FlightJsonField(String jsonKey) {
      this.jsonKey = jsonKey;
    }

    public String jsonKey() {
      return jsonKey;
    }
  }

  // Small helper types to keep code readable
  private record AirportPair(Airport origin, Airport destination) {}
  private record TimeBounds(Instant departureUtc, Instant arrivalUtc) {}

  /**
   * Convert a single JSON node into a Flight, or empty if invalid.
   *
   * @param node            flight JSON object
   * @param airportsByCode  map of airport code -> Airport
   */
  public Optional<Flight> toFlight(JsonNode node, Map<String, Airport> airportsByCode) {
    // 1. Extract all raw fields as strings (numeric price is handled via asText())
    Map<FlightJsonField, String> raw = new EnumMap<>(FlightJsonField.class);
    for (FlightJsonField field : FlightJsonField.values()) {
      raw.put(field, textOrNull(node, field.jsonKey()));
    }

    // 2. Generic missing-field check
    List<FlightJsonField> missingFields = raw.entrySet().stream()
        .filter(e -> e.getValue() == null || e.getValue().isBlank())
        .map(Map.Entry::getKey)
        .toList();

    if (!missingFields.isEmpty()) {
      log.warn(
          "Skipping flight due to missing fields {}. Raw values: {}",
          missingFields, raw
      );
      return Optional.empty();
    }

    String flightNumber = raw.get(FlightJsonField.FLIGHT_NUMBER);
    String airline = raw.get(FlightJsonField.AIRLINE);
    String originCode = raw.get(FlightJsonField.ORIGIN);
    String destinationCode = raw.get(FlightJsonField.DESTINATION);
    String departureText = raw.get(FlightJsonField.DEPARTURE_TIME);
    String arrivalText = raw.get(FlightJsonField.ARRIVAL_TIME);
    String priceText = raw.get(FlightJsonField.PRICE);
    String aircraft = raw.get(FlightJsonField.AIRCRAFT);

    // 3. Resolve airports
    Optional<AirportPair> airportPairOpt =
        resolveAirports(flightNumber, originCode, destinationCode, airportsByCode);
    if (airportPairOpt.isEmpty()) {
      return Optional.empty();
    }
    AirportPair airports = airportPairOpt.get();

    // 4. Parse and validate times
    Optional<TimeBounds> timeBoundsOpt =
        parseAndValidateTimes(flightNumber, departureText, arrivalText, airports.origin(), airports.destination());
    if (timeBoundsOpt.isEmpty()) {
      return Optional.empty();
    }
    TimeBounds timeBounds = timeBoundsOpt.get();

    // 5. Parse price
    Optional<BigDecimal> priceOpt = parsePrice(flightNumber, priceText);
    if (priceOpt.isEmpty()) {
      return Optional.empty();
    }
    BigDecimal price = priceOpt.get();

    Flight flight = new Flight(
        flightNumber,
        airline,
        airports.origin(),
        airports.destination(),
        timeBounds.departureUtc(),
        timeBounds.arrivalUtc(),
        price,
        aircraft
    );

    return Optional.of(flight);
  }

  private Optional<AirportPair> resolveAirports(String flightNumber,
      String originCode,
      String destinationCode,
      Map<String, Airport> airportsByCode) {
    Airport origin = airportsByCode.get(originCode);
    if (origin == null) {
      log.warn(
          "Skipping flight {} due to unknown origin airport code: {}",
          flightNumber, originCode
      );
      return Optional.empty();
    }

    Airport destination = airportsByCode.get(destinationCode);
    if (destination == null) {
      log.warn(
          "Skipping flight {} due to unknown destination airport code: {}",
          flightNumber, destinationCode
      );
      return Optional.empty();
    }

    return Optional.of(new AirportPair(origin, destination));
  }

  private Optional<TimeBounds> parseAndValidateTimes(String flightNumber,
      String departureText,
      String arrivalText,
      Airport origin,
      Airport destination) {
    try {
      LocalDateTime departureLocal = LocalDateTime.parse(departureText);
      LocalDateTime arrivalLocal = LocalDateTime.parse(arrivalText);

      ZonedDateTime departureZoned = departureLocal.atZone(origin.getTimezone());
      ZonedDateTime arrivalZoned = arrivalLocal.atZone(destination.getTimezone());

      Instant departureUtc = departureZoned.toInstant();
      Instant arrivalUtc = arrivalZoned.toInstant();

      if (!arrivalUtc.isAfter(departureUtc)) {
        log.warn(
            "Skipping flight {} because arrival time is not after departure. departureUtc={}, arrivalUtc={}",
            flightNumber, departureUtc, arrivalUtc
        );
        return Optional.empty();
      }

      return Optional.of(new TimeBounds(departureUtc, arrivalUtc));
    } catch (DateTimeParseException e) {
      log.warn(
          "Skipping flight {} due to invalid datetime. departure='{}', arrival='{}'",
          flightNumber, departureText, arrivalText
      );
      return Optional.empty();
    }
  }

  private Optional<BigDecimal> parsePrice(String flightNumber, String priceText) {
    try {
      return Optional.of(new BigDecimal(priceText));
    } catch (NumberFormatException e) {
      log.warn(
          "Skipping flight {} due to invalid price '{}'",
          flightNumber, priceText
      );
      return Optional.empty();
    }
  }

  private String textOrNull(JsonNode node, String fieldName) {
    JsonNode value = node.get(fieldName);
    return value != null && !value.isNull() ? value.asText() : null;
  }
}