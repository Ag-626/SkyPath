package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Flight;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.lang.NonNull;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightRepositoryInMemory}.
 * These tests avoid any mocking framework. They use a small in-memory
 * FlightsDatasetLoader implementation that returns JSON content from a string,
 * and real mappers/repositories to verify behavior:
 *  - valid flights are loaded and grouped by origin,
 *  - missing 'flights' array causes a fail-fast IllegalStateException,
 *  - datasets with no valid flights cause a fail-fast IllegalStateException,
 *  - search methods behave as expected for known/unknown origins/destinations,
 *  - exposed collections are unmodifiable.
 */
class FlightRepositoryInMemoryTest {

  /**
   * Happy-path test:
   * Verifies that a dataset with valid airports and flights is loaded correctly,
   * that flights are grouped by origin, and that search methods return the
   * expected results.
   */
  @Test
  void constructor_shouldLoadValidFlightsAndSupportSearchByOriginAndDestination() {
    // given
    String json = """
        {
          "airports": [
            {
              "code": "JFK",
              "name": "John F. Kennedy International Airport",
              "city": "New York",
              "country": "US",
              "timezone": "America/New_York"
            },
            {
              "code": "LAX",
              "name": "Los Angeles International Airport",
              "city": "Los Angeles",
              "country": "US",
              "timezone": "America/Los_Angeles"
            },
            {
              "code": "SFO",
              "name": "San Francisco International Airport",
              "city": "San Francisco",
              "country": "US",
              "timezone": "America/Los_Angeles"
            }
          ],
          "flights": [
            {
              "flightNumber": "SP100",
              "airline": "SkyPath Airways",
              "origin": "JFK",
              "destination": "LAX",
              "departureTime": "2024-03-15T08:00:00",
              "arrivalTime": "2024-03-15T11:00:00",
              "price": "299.00",
              "aircraft": "Boeing 737"
            },
            {
              "flightNumber": "SP101",
              "airline": "SkyPath Airways",
              "origin": "JFK",
              "destination": "SFO",
              "departureTime": "2024-03-15T09:00:00",
              "arrivalTime": "2024-03-15T12:00:00",
              "price": "349.00",
              "aircraft": "Airbus A320"
            },
            {
              "flightNumber": "SP200",
              "airline": "SkyPath Airways",
              "origin": "LAX",
              "destination": "JFK",
              "departureTime": "2024-03-15T13:00:00",
              "arrivalTime": "2024-03-15T21:00:00",
              "price": "399.00",
              "aircraft": "Boeing 777"
            }
          ]
        }
        """;

    // when
    FlightRepositoryInMemory flightRepository = createRepositoryFromJson(json);

    // then
    List<Flight> allFlights = flightRepository.findAll();
    assertEquals(3, allFlights.size(), "Expected three flights to be loaded");

    List<Flight> fromJfk = flightRepository.findByOrigin("JFK");
    assertEquals(2, fromJfk.size(), "Expected two flights originating from JFK");

    List<Flight> fromLax = flightRepository.findByOrigin("LAX");
    assertEquals(1, fromLax.size(), "Expected one flight originating from LAX");

    List<Flight> jfkToLax = flightRepository.findByOriginAndDestination("JFK", "LAX");
    assertEquals(1, jfkToLax.size(), "Expected one flight from JFK to LAX");
    assertEquals("SP100", jfkToLax.get(0).getFlightNumber());
  }

  /**
   * Failure case:
   * When the dataset JSON does not contain a "flights" array at all,
   * the FlightRepositoryInMemory constructor should fail fast with an
   * IllegalStateException.
   */
  @Test
  void constructor_shouldFailWhenFlightsArrayMissing() {
    // given: airports array present, flights array missing
    String json = """
        {
          "airports": [
            {
              "code": "JFK",
              "name": "John F. Kennedy International Airport",
              "city": "New York",
              "country": "US",
              "timezone": "America/New_York"
            }
          ]
        }
        """;

    // when / then
    assertThrows(IllegalStateException.class,
        () -> createRepositoryFromJson(json),
        "Missing 'flights' array should cause IllegalStateException");
  }

  /**
   * Failure case:
   * When the "flights" array is present but every entry is invalid,
   * the repository should fail fast with an IllegalStateException indicating
   * that no valid flights were found.
   */
  @Test
  void constructor_shouldFailWhenNoValidFlights() {
    // given: airports array is valid, but all flights are invalid
    String json = """
        {
          "airports": [
            {
              "code": "JFK",
              "name": "John F. Kennedy International Airport",
              "city": "New York",
              "country": "US",
              "timezone": "America/New_York"
            }
          ],
          "flights": [
            {
              "flightNumber": "SP_BAD_1",
              "airline": "SkyPath Airways",
              "origin": "UNKNOWN",
              "destination": "JFK",
              "departureTime": "2024-03-15T08:00:00",
              "arrivalTime": "2024-03-15T12:00:00",
              "price": "199.00",
              "aircraft": "Boeing 737"
            },
            {
              "flightNumber": "SP_BAD_2",
              "airline": "SkyPath Airways",
              "origin": "JFK",
              "destination": "JFK",
              "departureTime": "2024-03-15T08:00:00",
              "arrivalTime": "not-a-datetime",
              "price": "199.00",
              "aircraft": "Boeing 737"
            }
          ]
        }
        """;

    // when / then
    assertThrows(IllegalStateException.class,
        () -> createRepositoryFromJson(json),
        "Dataset with no valid flights should cause IllegalStateException");
  }

  /**
   * Edge case:
   * Verifies that requesting flights from an unknown origin code returns
   * an empty (but non-null) list.
   */
  @Test
  void findByOrigin_shouldReturnEmptyListForUnknownOrigin() {
    // given
    FlightRepositoryInMemory repository = createSimpleRepositoryWithOneFlight();

    // when
    List<Flight> result = repository.findByOrigin("UNKNOWN");

    // then
    assertNotNull(result, "Result list should never be null");
    assertTrue(result.isEmpty(), "Expected empty list for unknown origin code");
  }

  /**
   * Edge case:
   * Verifies that findByOriginAndDestination returns an empty list when there
   * are no flights matching the given origin/destination pair.
   */
  @Test
  void findByOriginAndDestination_shouldReturnEmptyWhenNoMatch() {
    // given
    FlightRepositoryInMemory repository = createSimpleRepositoryWithOneFlight();

    // when
    List<Flight> result = repository.findByOriginAndDestination("JFK", "SFO");

    // then
    assertNotNull(result, "Result list should never be null");
    assertTrue(result.isEmpty(), "Expected empty list when no flights match the given route");
  }

  /**
   * API contract test:
   * Verifies that the list returned by findAll() is unmodifiable so callers
   * cannot accidentally mutate the internal state of the repository.
   */
  @Test
  void findAll_shouldReturnUnmodifiableList() {
    // given
    FlightRepositoryInMemory repository = createSimpleRepositoryWithOneFlight();

    List<Flight> all = repository.findAll();
    assertEquals(1, all.size(), "Precondition: expected one flight");

    // when / then
    assertThrows(UnsupportedOperationException.class,
        () -> all.add(all.get(0)),
        "findAll() should return an unmodifiable list");
  }

  /**
   * API contract test:
   * Verifies that the list returned by findByOrigin() is unmodifiable.
   */
  @Test
  void findByOrigin_shouldReturnUnmodifiableList() {
    // given
    FlightRepositoryInMemory repository = createSimpleRepositoryWithOneFlight();

    List<Flight> fromJfk = repository.findByOrigin("JFK");
    assertEquals(1, fromJfk.size(), "Precondition: expected one flight from JFK");

    // when / then
    assertThrows(UnsupportedOperationException.class,
        () -> fromJfk.add(fromJfk.get(0)),
        "findByOrigin() should return an unmodifiable list");
  }

  // --------------------------------------------------------------------------
  // Helper methods
  // --------------------------------------------------------------------------

  /**
   * Helper to build a FlightRepositoryInMemory from a given dataset JSON string.
   * This centralizes the common wiring of loader, object mapper, airport repo
   * and flight mapper so individual tests stay focused on assertions.
   */
  private FlightRepositoryInMemory createRepositoryFromJson(String json) {
    FlightsDatasetLoader loader = new StringFlightsDatasetLoader(json);
    ObjectMapper objectMapper = new ObjectMapper();
    AirportJsonMapper airportJsonMapper = new AirportJsonMapper();
    AirportRepositoryInMemory airportRepository =
        new AirportRepositoryInMemory(loader, objectMapper, airportJsonMapper);
    FlightJsonMapper flightJsonMapper = new FlightJsonMapper();

    return new FlightRepositoryInMemory(loader, objectMapper, airportRepository, flightJsonMapper);
  }

  /**
   * Creates a repository backed by a minimal dataset containing:
   *  - one JFK airport,
   *  - one LAX airport,
   *  - one flight from JFK to LAX.
   */
  private FlightRepositoryInMemory createSimpleRepositoryWithOneFlight() {
    String json = """
        {
          "airports": [
            {
              "code": "JFK",
              "name": "John F. Kennedy International Airport",
              "city": "New York",
              "country": "US",
              "timezone": "America/New_York"
            },
            {
              "code": "LAX",
              "name": "Los Angeles International Airport",
              "city": "Los Angeles",
              "country": "US",
              "timezone": "America/Los_Angeles"
            }
          ],
          "flights": [
            {
              "flightNumber": "SP_SINGLE",
              "airline": "SkyPath Airways",
              "origin": "JFK",
              "destination": "LAX",
              "departureTime": "2024-03-15T08:00:00",
              "arrivalTime": "2024-03-15T11:00:00",
              "price": "199.00",
              "aircraft": "Boeing 737"
            }
          ]
        }
        """;

    return createRepositoryFromJson(json);
  }

  // --------------------------------------------------------------------------
  // Test helpers: simple FlightsDatasetLoader based on an in-memory JSON string
  // --------------------------------------------------------------------------

  /**
   * Simple FlightsDatasetLoader implementation for tests that returns an
   * InputStream backed by an in-memory JSON string.
   * We provide a minimal ResourceLoader that always "exists" so that the
   * FlightsDatasetLoader base class can be constructed without needing a real
   * file on disk or classpath.
   */
  private static class StringFlightsDatasetLoader extends FlightsDatasetLoader {

    private final String json;

    StringFlightsDatasetLoader(String json) {
      super("ignored-location", new DummyResourceLoader());
      this.json = json;
    }

    @Override
    public java.io.InputStream openDatasetStream() {
      return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Minimal ResourceLoader implementation that always returns a Resource which
   * exists, so that the FlightsDatasetLoader constructor can complete successfully
   * in tests without relying on actual files.
   */
  private static class DummyResourceLoader implements ResourceLoader {

    private final Resource resource = new ByteArrayResource(new byte[0]) {
      @Override
      public boolean exists() {
        return true;
      }
    };

    @Override
    @NonNull
    public Resource getResource(@NonNull String location) {
      return resource;
    }

    @Override
    public ClassLoader getClassLoader() {
      return getClass().getClassLoader();
    }
  }
}