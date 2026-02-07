package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightJsonMapper}.
 * These tests do not use any mocking framework. They construct real JsonNode
 * instances and use real Airport objects to verify that the mapper correctly
 * handles valid input and various edge cases:
 *  - missing fields
 *  - unknown airports
 *  - invalid date times
 *  - arrival before departure
 *  - invalid prices
 */
class FlightJsonMapperTest {

  private FlightJsonMapper mapper;
  private ObjectMapper objectMapper;
  private Airport jfk;
  private Airport lax;
  private Map<String, Airport> airportsByCode;

  @BeforeEach
  void setUp() {
    this.mapper = new FlightJsonMapper();
    this.objectMapper = new ObjectMapper();

    // Set up two real airports with real timezones
    this.jfk = new Airport("JFK", "John F. Kennedy International Airport", "New York", "US",
        ZoneId.of("America/New_York"));
    this.lax = new Airport("LAX", "Los Angeles International Airport", "Los Angeles", "US",
        ZoneId.of("America/Los_Angeles"));

    this.airportsByCode = Map.of(
        "JFK", jfk,
        "LAX", lax
    );
  }

  /**
   * Happy-path test:
   * Verifies that a fully valid flight JSON object is correctly mapped into
   * a Flight domain object, including proper origin/destination, UTC times,
   * and price as BigDecimal.
   */
  @Test
  void toFlight_shouldMapValidFlight() throws Exception {
    // given
    String json = """
        {
          "flightNumber": "SP100",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "299.99",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isPresent(), "Expected a valid Flight to be returned");

    Flight flight = result.get();
    assertEquals("SP100", flight.getFlightNumber());
    assertEquals("SkyPath Airways", flight.getAirline());
    assertEquals(jfk, flight.getOrigin());
    assertEquals(lax, flight.getDestination());
    assertEquals(new BigDecimal("299.99"), flight.getPrice());
    assertEquals("Boeing 737", flight.getAircraft());

    // Check that departure/arrival are converted from local airport time to UTC
    Instant expectedDepartureUtc = LocalDateTime.parse("2024-03-15T08:00:00")
        .atZone(jfk.getTimezone())
        .toInstant();
    Instant expectedArrivalUtc = LocalDateTime.parse("2024-03-15T11:00:00")
        .atZone(lax.getTimezone())
        .toInstant();

    assertEquals(expectedDepartureUtc, flight.getDepartureTimeUtc());
    assertEquals(expectedArrivalUtc, flight.getArrivalTimeUtc());
  }

  /**
   * Edge case:
   * When one or more required fields are missing (e.g., price),
   * the mapper should skip this flight and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenRequiredFieldMissing() throws Exception {
    // given (missing "price")
    String json = """
        {
          "flightNumber": "SP101",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when a required field is missing");
  }

  /**
   * Edge case:
   * When the origin airport code does not exist in the airports map,
   * the mapper should skip this flight and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenOriginUnknown() throws Exception {
    // given
    String json = """
        {
          "flightNumber": "SP102",
          "airline": "SkyPath Airways",
          "origin": "UNKNOWN",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "199.00",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when origin airport code is unknown");
  }

  /**
   * Edge case:
   * When the destination airport code does not exist in the airports map,
   * the mapper should skip this flight and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenDestinationUnknown() throws Exception {
    // given
    String json = """
        {
          "flightNumber": "SP103",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "UNKNOWN",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "199.00",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when destination airport code is unknown");
  }

  /**
   * Edge case:
   * When the departure or arrival datetime has an invalid format,
   * the mapper should skip this flight and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenDatetimeInvalid() throws Exception {
    // given (invalid datetime format)
    String json = """
        {
          "flightNumber": "SP104",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "not-a-datetime",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "199.00",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when departure datetime is invalid");
  }

  /**
   * Edge case:
   * When the arrival time is not strictly after the departure time (i.e., equal
   * or before), the mapper should treat this as invalid and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenArrivalNotAfterDeparture() throws Exception {
    // given (arrival equal to departure)
    String json = """
        {
          "flightNumber": "SP105",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "JFK",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T08:00:00",
          "price": "199.00",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when arrival is not after departure");
  }

  /**
   * Edge case:
   * When the price field is present but cannot be parsed as a BigDecimal,
   * the mapper should skip this flight and return Optional.empty().
   */
  @Test
  void toFlight_shouldReturnEmptyWhenPriceInvalid() throws Exception {
    // given
    String json = """
        {
          "flightNumber": "SP106",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "not-a-number",
          "aircraft": "Boeing 737"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when price is not a valid number");
  }

  /**
   * Defensive test:
   * Verifies that numeric prices (e.g. 199, 199.0) encoded as JSON numbers are
   * correctly handled via asText() + BigDecimal and mapped into the Flight.
   */
  @Test
  void toFlight_shouldHandleNumericPriceValues() throws Exception {
    // given: price as JSON number instead of string
    String json = """
        {
          "flightNumber": "SP107",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": 250.50,
          "aircraft": "Airbus A320"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isPresent(), "Expected a valid Flight when price is numeric");

    Flight flight = result.get();
    assertEquals(0, flight.getPrice().compareTo(new BigDecimal("250.50")));
  }

  /**
   * Defensive test:
   * Verifies that extra fields in the JSON that the mapper does not care about
   * do not break mapping of known fields.
   */
  @Test
  void toFlight_shouldIgnoreUnknownFields() throws Exception {
    // given
    String json = """
        {
          "flightNumber": "SP108",
          "airline": "SkyPath Airways",
          "origin": "JFK",
          "destination": "LAX",
          "departureTime": "2024-03-15T08:00:00",
          "arrivalTime": "2024-03-15T11:00:00",
          "price": "300.00",
          "aircraft": "Boeing 777",
          "extraField": "should-be-ignored"
        }
        """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Flight> result = mapper.toFlight(node, airportsByCode);

    // then
    assertTrue(result.isPresent(), "Expected a valid Flight even with extra JSON fields");
    Flight flight = result.get();
    assertEquals("SP108", flight.getFlightNumber());
    assertEquals(new BigDecimal("300.00"), flight.getPrice());
  }
}