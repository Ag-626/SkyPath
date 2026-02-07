package com.skypath.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.algorithms.FlightSearchAlgorithm;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.infrastructure.dataset.AirportJsonMapper;
import com.skypath.backend.infrastructure.dataset.AirportRepositoryInMemory;
import com.skypath.backend.infrastructure.dataset.FlightJsonMapper;
import com.skypath.backend.infrastructure.dataset.FlightRepositoryInMemory;
import com.skypath.backend.infrastructure.dataset.FlightsDatasetLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightSearchService}.
 *
 * These tests deliberately avoid using mocking frameworks:
 *  - For validation paths (nulls, unknown airports, same origin/destination),
 *    we rely only on a tiny in-memory AirportRepository stub and a no-op algorithm.
 *  - For the “happy path”, we wire a recording algorithm that captures the
 *    computed UTC window and verifies that the service delegates correctly.
 */
class FlightSearchServiceTest {

  private static final String ORIGIN_CODE = "JFK";
  private static final String DEST_CODE = "LAX";

  private RecordingAirportRepository airportRepository;
  private RecordingFlightSearchAlgorithm algorithm;
  private FlightSearchService service;

  @BeforeEach
  void setUp() {
    // Build a minimal in-memory airport repository with two airports.
    airportRepository = new RecordingAirportRepository();

    Airport jfk = new Airport(
        ORIGIN_CODE,
        "John F. Kennedy International Airport",
        "New York",
        "United States",
        ZoneId.of("America/New_York")
    );

    Airport lax = new Airport(
        DEST_CODE,
        "Los Angeles International Airport",
        "Los Angeles",
        "United States",
        ZoneId.of("America/Los_Angeles")
    );

    airportRepository.addAirport(jfk);
    airportRepository.addAirport(lax);

    // Build a dummy FlightRepository + algorithm that only records inputs.
    FlightRepositoryInMemory flightRepository = createDummyFlightRepository(airportRepository);
    algorithm = new RecordingFlightSearchAlgorithm(flightRepository);

    service = new FlightSearchService(airportRepository, algorithm);
  }

  // ---------------------------------------------------------------------------
  // Validation tests
  // ---------------------------------------------------------------------------

  /**
   * search(...) should throw NullPointerException when originCode is null.
   */
  @Test
  void search_shouldThrowWhenOriginCodeIsNull() {
    LocalDate date = LocalDate.of(2024, 3, 15);

    assertThrows(NullPointerException.class,
        () -> service.search(null, DEST_CODE, date));
  }

  /**
   * search(...) should throw NullPointerException when destinationCode is null.
   */
  @Test
  void search_shouldThrowWhenDestinationCodeIsNull() {
    LocalDate date = LocalDate.of(2024, 3, 15);

    assertThrows(NullPointerException.class,
        () -> service.search(ORIGIN_CODE, null, date));
  }

  /**
   * search(...) should throw NullPointerException when travelDate is null.
   */
  @Test
  void search_shouldThrowWhenTravelDateIsNull() {
    assertThrows(NullPointerException.class,
        () -> service.search(ORIGIN_CODE, DEST_CODE, null));
  }

  /**
   * search(...) should throw IllegalArgumentException when origin airport
   * cannot be resolved from the repository.
   */
  @Test
  void search_shouldThrowWhenOriginAirportUnknown() {
    LocalDate date = LocalDate.of(2024, 3, 15);

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.search("XXX", DEST_CODE, date)
    );

    assertTrue(ex.getMessage().contains("Unknown origin airport"));
  }

  /**
   * search(...) should throw IllegalArgumentException when destination airport
   * cannot be resolved from the repository.
   */
  @Test
  void search_shouldThrowWhenDestinationAirportUnknown() {
    LocalDate date = LocalDate.of(2024, 3, 15);

    IllegalArgumentException ex = assertThrows(
        IllegalArgumentException.class,
        () -> service.search(ORIGIN_CODE, "YYY", date)
    );

    assertTrue(ex.getMessage().contains("Unknown destination airport"));
  }

  /**
   * When origin and destination codes are the same, the service should return
   * an empty list and never call the underlying algorithm.
   */
  @Test
  void search_shouldReturnEmptyListWhenOriginEqualsDestination() {
    LocalDate date = LocalDate.of(2024, 3, 15);

    List<Itinerary> result = service.search(ORIGIN_CODE, ORIGIN_CODE, date);

    assertTrue(result.isEmpty(), "Expected empty result when origin == destination");
    assertFalse(algorithm.wasCalled(), "Algorithm should not be called when origin == destination");
  }

  // ---------------------------------------------------------------------------
  // Happy-path test: verify delegation & UTC window computation
  // ---------------------------------------------------------------------------

  /**
   * For valid origin/destination/date, the service should:
   *  - resolve airports,
   *  - compute the UTC window based on origin's timezone,
   *  - delegate to FlightSearchAlgorithm with that window,
   *  - return whatever the algorithm returns.
   */
  @Test
  void search_shouldDelegateToAlgorithmWithComputedUtcWindow() {
    LocalDate travelDate = LocalDate.of(2024, 3, 15);

    // We preconfigure the algorithm to return a synthetic list of itineraries.
    List<Itinerary> expectedItineraries = List.of(TestDataFixtures.createDummyItinerary());
    algorithm.setItinerariesToReturn(expectedItineraries);

    List<Itinerary> result = service.search(ORIGIN_CODE, DEST_CODE, travelDate);

    // 1. Service should return exactly what algorithm returned.
    assertEquals(expectedItineraries, result, "Service should return algorithm's result");

    // 2. Algorithm should have been called exactly once with the right codes.
    assertTrue(algorithm.wasCalled(), "Algorithm should have been invoked");
    assertEquals(ORIGIN_CODE, algorithm.getLastOriginCode());
    assertEquals(DEST_CODE, algorithm.getLastDestinationCode());

    // 3. Verify that the UTC window corresponds to the origin's local day.
    //    For America/New_York on 2024-03-15, startOfDayLocal = 2024-03-15T00:00-04:00
    //    (depending on DST rules). We assert purely via the origin ZoneId to avoid
    //    hardcoding offsets.
    ZoneId originZone = airportRepository
        .findByCode(ORIGIN_CODE)
        .orElseThrow()
        .getTimezone();

    Instant expectedStart = travelDate.atStartOfDay(originZone).toInstant();
    Instant expectedEnd = travelDate.plusDays(1).atStartOfDay(originZone).toInstant();

    assertEquals(expectedStart, algorithm.getLastStartOfDayUtc(),
        "Start-of-day UTC should match origin local midnight converted to UTC");
    assertEquals(expectedEnd, algorithm.getLastEndOfDayUtc(),
        "End-of-day UTC should match next day's local midnight converted to UTC");
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  /**
   * Create a dummy FlightRepositoryInMemory. The underlying flights.json
   * is irrelevant for this service-level test; the search algorithm is
   * overridden to not use it. We only need a valid instance to satisfy
   * FlightSearchAlgorithm's constructor.
   */
  private static FlightRepositoryInMemory createDummyFlightRepository(AirportRepositoryInMemory airportRepository) {
    ResourceLoader resourceLoader = new DefaultResourceLoader();
    // Reuse the real dataset location; it's available on the test classpath.
    FlightsDatasetLoader datasetLoader =
        new FlightsDatasetLoader("classpath:static/flights.json", resourceLoader);

    FlightJsonMapper mapper = new FlightJsonMapper();
    ObjectMapper objectMapper = new ObjectMapper();

    return new FlightRepositoryInMemory(datasetLoader, objectMapper, airportRepository, mapper);
  }

  /**
   * Simple in-memory implementation of AirportRepositoryInMemory for tests.
   * We extend the production class but ignore its internal map and instead
   * manage our own small map of airports.
   */
  private static class RecordingAirportRepository extends AirportRepositoryInMemory {

    private final java.util.Map<String, Airport> airports = new java.util.HashMap<>();

    RecordingAirportRepository() {
      // Provide a minimal but valid loader/objectMapper; the superclass
      // will attempt to load, but we don't rely on its internal data.
      super(
          new FlightsDatasetLoader("classpath:static/flights.json", new DefaultResourceLoader()),
          new ObjectMapper(),
          new AirportJsonMapper()
      );
    }

    void addAirport(Airport airport) {
      airports.put(airport.getCode(), airport);
    }

    @Override
    public Optional<Airport> findByCode(String code) {
      return Optional.ofNullable(airports.get(code));
    }

    @Override
    public java.util.Collection<Airport> findAll() {
      return airports.values();
    }

    @Override
    public java.util.Map<String, Airport> asMap() {
      return java.util.Collections.unmodifiableMap(airports);
    }
  }

  /**
   * Recording test double for FlightSearchAlgorithm.
   * We subclass the production algorithm but override search(...) so that:
   *  - It does not perform any real search.
   *  - It records the parameters passed by FlightSearchService.
   *  - It returns a preconfigured list of itineraries.
   */
  private static class RecordingFlightSearchAlgorithm extends FlightSearchAlgorithm {

    private boolean called = false;
    private String lastOriginCode;
    private String lastDestinationCode;
    private Instant lastStartOfDayUtc;
    private Instant lastEndOfDayUtc;
    private List<Itinerary> itinerariesToReturn = List.of();

    RecordingFlightSearchAlgorithm(FlightRepositoryInMemory flightRepository) {
      // Values here should match your application defaults; adjust if your
      // constructor signature differs.
      super(
          flightRepository,
          2,                          // maxStops
          Duration.ofMinutes(45),     // minDomesticLayover
          Duration.ofMinutes(90),     // minInternationalLayover
          Duration.ofHours(6)         // maxLayover
      );
    }

    void setItinerariesToReturn(List<Itinerary> itineraries) {
      this.itinerariesToReturn = itineraries;
    }

    boolean wasCalled() {
      return called;
    }

    String getLastOriginCode() {
      return lastOriginCode;
    }

    String getLastDestinationCode() {
      return lastDestinationCode;
    }

    Instant getLastStartOfDayUtc() {
      return lastStartOfDayUtc;
    }

    Instant getLastEndOfDayUtc() {
      return lastEndOfDayUtc;
    }

    @Override
    public List<Itinerary> search(String originCode,
        String destinationCode,
        Instant startOfDayUtc,
        Instant endOfDayUtc) {
      this.called = true;
      this.lastOriginCode = originCode;
      this.lastDestinationCode = destinationCode;
      this.lastStartOfDayUtc = startOfDayUtc;
      this.lastEndOfDayUtc = endOfDayUtc;

      return itinerariesToReturn;
    }
  }

  /**
   * Small fixture holder for building dummy itineraries.
   * Keeps the main test body focused on behavior instead of object setup.
   */
  private static final class TestDataFixtures {

    private TestDataFixtures() {
      // utility class
    }

    static Itinerary createDummyItinerary() {
      // For the service test we don't care about real times/prices;
      // we only need a non-empty itinerary instance.
      Airport dummyOrigin = new Airport(
          "DUM",
          "Dummy Origin",
          "Dummy City",
          "Dummy Country",
          ZoneId.of("UTC")
      );

      Airport dummyDestination = new Airport(
          "DUM2",
          "Dummy Destination",
          "Dummy City 2",
          "Dummy Country",
          ZoneId.of("UTC")
      );

      com.skypath.backend.domain.Flight dummyFlight =
          new com.skypath.backend.domain.Flight(
              "SP000",
              "Test Airline",
              dummyOrigin,
              dummyDestination,
              Instant.parse("2024-03-15T10:00:00Z"),
              Instant.parse("2024-03-15T12:00:00Z"),
              java.math.BigDecimal.valueOf(100),
              "A320"
          );

      return new Itinerary(List.of(dummyFlight));
    }
  }
}