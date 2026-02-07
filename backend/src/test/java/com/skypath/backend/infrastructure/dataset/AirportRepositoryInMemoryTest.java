package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.NonNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AirportRepositoryInMemory}.
 * These tests do not use any mocking framework. Instead, they rely on a small
 * test-specific FlightsDatasetLoader implementation that returns an InputStream
 * backed by an in-memory JSON string.
 * The goal is to verify that:
 *  - valid airports are loaded correctly,
 *  - invalid airports are skipped,
 *  - missing or unusable datasets cause a fail-fast IllegalStateException,
 *  - the exposed collections are unmodifiable.
 */
class AirportRepositoryInMemoryTest {

  /**
   * Happy-path test:
   * Verifies that a dataset with a single valid airport is loaded correctly,
   * and that the repository lookup methods behave as expected.
   */
  @Test
  void constructor_shouldLoadValidAirports() {
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
            }
          ]
        }
        """;

    // when
    AirportRepositoryInMemory repository = createRepositoryFromJson(json);

    // then
    Optional<Airport> maybeJfk = repository.findByCode("JFK");
    assertTrue(maybeJfk.isPresent(), "Expected JFK airport to be present");

    Airport jfk = maybeJfk.get();
    assertEquals("JFK", jfk.getCode());
    assertEquals("John F. Kennedy International Airport", jfk.getName());
    assertEquals("New York", jfk.getCity());
    assertEquals("US", jfk.getCountry());
    assertEquals(ZoneId.of("America/New_York"), jfk.getTimezone());

    Collection<Airport> all = repository.findAll();
    assertEquals(1, all.size(), "Expected exactly one airport in repository");

    assertTrue(repository.findByCode("UNKNOWN").isEmpty(),
        "Unknown airport codes should return Optional.empty");
  }

  /**
   * Edge case:
   * When the dataset contains both valid and invalid airport entries,
   * the repository should load only the valid ones and skip invalid ones.
   */
  @Test
  void constructor_shouldSkipInvalidAirportsAndKeepValidOnes() {
    // given: one airport missing timezone, one valid
    String json = """
        {
          "airports": [
            {
              "code": "BAD",
              "name": "Broken Airport",
              "city": "Nowhere",
              "country": "XX"
            },
            {
              "code": "LHR",
              "name": "Heathrow Airport",
              "city": "London",
              "country": "UK",
              "timezone": "Europe/London"
            }
          ]
        }
        """;

    // when
    AirportRepositoryInMemory repository = createRepositoryFromJson(json);

    // then
    assertTrue(repository.findByCode("BAD").isEmpty(),
        "Airport with missing required fields should be skipped");

    Optional<Airport> maybeLhr = repository.findByCode("LHR");
    assertTrue(maybeLhr.isPresent(), "Expected LHR to be present");

    Collection<Airport> all = repository.findAll();
    assertEquals(1, all.size(), "Only the valid airport should be loaded");
  }

  /**
   * Failure case:
   * When the top-level JSON object does not contain an "airports" array,
   * the repository should fail fast with an IllegalStateException.
   */
  @Test
  void constructor_shouldFailWhenAirportsArrayMissing() {
    // given: no "airports" field at all
    String json = """
        {
          "someOtherField": []
        }
        """;

    // when / then
    assertThrows(IllegalStateException.class,
        () -> createRepositoryFromJson(json),
        "Missing 'airports' array should cause IllegalStateException");
  }

  /**
   * Failure case:
   * When the dataset defines an "airports" array but all entries are invalid
   * (e.g. missing required fields or invalid timezone), the repository should
   * again fail fast with an IllegalStateException indicating that no valid
   * airports were found.
   */
  @Test
  void constructor_shouldFailWhenNoValidAirports() {
    // given: every airport entry is invalid
    String json = """
        {
          "airports": [
            {
              "code": "XXX",
              "name": "",
              "city": "City",
              "country": "Country",
              "timezone": "America/New_York"
            },
            {
              "name": "No Code Airport",
              "city": "City",
              "country": "Country",
              "timezone": "Europe/London"
            },
            {
              "code": "BADTZ",
              "name": "Bad Timezone Airport",
              "city": "City",
              "country": "Country",
              "timezone": "Invalid/Timezone"
            }
          ]
        }
        """;

    // when / then
    assertThrows(IllegalStateException.class,
        () -> createRepositoryFromJson(json),
        "Dataset with no valid airports should cause IllegalStateException");
  }

  /**
   * API contract test:
   * Verifies that the map returned by asMap() is unmodifiable and that callers
   * cannot accidentally change the internal state of the repository.
   */
  @Test
  void asMap_shouldReturnUnmodifiableMap() {
    // given
    String json = """
        {
          "airports": [
            {
              "code": "SFO",
              "name": "San Francisco International Airport",
              "city": "San Francisco",
              "country": "US",
              "timezone": "America/Los_Angeles"
            }
          ]
        }
        """;

    AirportRepositoryInMemory repository = createRepositoryFromJson(json);

    Map<String, Airport> mapView = repository.asMap();

    // when / then
    assertThrows(UnsupportedOperationException.class, () ->
        mapView.put("NEW", new Airport("NEW", "New Airport", "City", "CT",
            ZoneId.of("America/New_York")))
    );
  }

  // --------------------------------------------------------------------------
  // Helper methods
  // --------------------------------------------------------------------------

  /**
   * Helper to build an AirportRepositoryInMemory from a given dataset JSON string.
   * Centralizes the common wiring of loader, object mapper and mapper so that
   * individual tests stay focused on assertions.
   */
  private AirportRepositoryInMemory createRepositoryFromJson(String json) {
    FlightsDatasetLoader loader = new StringFlightsDatasetLoader(json);
    AirportJsonMapper airportJsonMapper = new AirportJsonMapper();
    ObjectMapper objectMapper = new ObjectMapper();

    return new AirportRepositoryInMemory(loader, objectMapper, airportJsonMapper);
  }

  // --------------------------------------------------------------------------
  // Test helper: a simple FlightsDatasetLoader that reads from an in-memory string
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
    public InputStream openDatasetStream() {
      return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Minimal ResourceLoader implementation that always returns a ByteArrayResource
   * and reports that it exists, so the FlightsDatasetLoader constructor can
   * complete successfully in tests.
   */
  private static class DummyResourceLoader implements ResourceLoader {

    private final Resource resource = new ByteArrayResource(new byte[0]);

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