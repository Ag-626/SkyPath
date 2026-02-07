package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AirportJsonMapper}.
 * These tests intentionally do not use any mocks. They construct real JsonNode
 * instances and verify that the mapper correctly handles valid input and
 * various edge cases (missing fields, invalid timezone, blank values).
 */
class AirportJsonMapperTest {

  private AirportJsonMapper mapper;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    this.mapper = new AirportJsonMapper();
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Happy-path test:
   * Verifies that a fully valid airport JSON object is correctly mapped
   * into an Airport domain object with all fields populated as expected.
   */
  @Test
  void toAirport_shouldMapValidAirport() throws Exception {
    // given
    String json = """
            {
              "code": "JFK",
              "name": "John F. Kennedy International Airport",
              "city": "New York",
              "country": "US",
              "timezone": "America/New_York"
            }
            """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Airport> result = mapper.toAirport(node);

    // then
    assertTrue(result.isPresent(), "Expected a valid Airport to be returned");

    Airport airport = result.get();
    assertEquals("JFK", airport.getCode());
    assertEquals("John F. Kennedy International Airport", airport.getName());
    assertEquals("New York", airport.getCity());
    assertEquals("US", airport.getCountry());
    assertEquals(ZoneId.of("America/New_York"), airport.getTimezone());
  }

  /**
   * Edge case:
   * When one or more required fields are missing from the JSON,
   * the mapper should skip this airport and return Optional.empty().
   */
  @Test
  void toAirport_shouldReturnEmptyWhenRequiredFieldMissing() throws Exception {
    // given (missing "timezone" field)
    String json = """
            {
              "code": "LHR",
              "name": "Heathrow Airport",
              "city": "London",
              "country": "UK"
            }
            """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Airport> result = mapper.toAirport(node);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when required fields are missing");
  }

  /**
   * Edge case:
   * When required fields are present but contain only blank/whitespace values,
   * the mapper should treat them as missing and return Optional.empty().
   */
  @Test
  void toAirport_shouldReturnEmptyWhenRequiredFieldBlank() throws Exception {
    // given (blank name and timezone)
    String json = """
            {
              "code": "CDG",
              "name": "   ",
              "city": "Paris",
              "country": "FR",
              "timezone": "   "
            }
            """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Airport> result = mapper.toAirport(node);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when required fields are blank");
  }

  /**
   * Edge case:
   * When the timezone field is present but invalid (not a known ZoneId),
   * the mapper should log a warning and return Optional.empty().
   */
  @Test
  void toAirport_shouldReturnEmptyWhenTimezoneInvalid() throws Exception {
    // given
    String json = """
            {
              "code": "DEL",
              "name": "Indira Gandhi International Airport",
              "city": "Delhi",
              "country": "IN",
              "timezone": "Invalid/Timezone"
            }
            """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Airport> result = mapper.toAirport(node);

    // then
    assertTrue(result.isEmpty(), "Expected Optional.empty when timezone is invalid");
  }

  /**
   * Defensive test:
   * If the JSON contains extra fields beyond those the mapper cares about,
   * they should simply be ignored and not break mapping of known fields.
   */
  @Test
  void toAirport_shouldIgnoreUnknownFields() throws Exception {
    // given
    String json = """
            {
              "code": "SFO",
              "name": "San Francisco International Airport",
              "city": "San Francisco",
              "country": "US",
              "timezone": "America/Los_Angeles",
              "extraField": "should-be-ignored"
            }
            """;
    JsonNode node = objectMapper.readTree(json);

    // when
    Optional<Airport> result = mapper.toAirport(node);

    // then
    assertTrue(result.isPresent(), "Expected a valid Airport even with extra fields");
    Airport airport = result.get();
    assertEquals("SFO", airport.getCode());
    assertEquals("San Francisco International Airport", airport.getName());
    assertEquals("San Francisco", airport.getCity());
    assertEquals("US", airport.getCountry());
    assertEquals(ZoneId.of("America/Los_Angeles"), airport.getTimezone());
  }
}