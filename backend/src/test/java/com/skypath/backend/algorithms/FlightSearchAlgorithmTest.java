package com.skypath.backend.algorithms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.infrastructure.dataset.AirportJsonMapper;
import com.skypath.backend.infrastructure.dataset.AirportRepositoryInMemory;
import com.skypath.backend.infrastructure.dataset.FlightJsonMapper;
import com.skypath.backend.infrastructure.dataset.FlightRepositoryInMemory;
import com.skypath.backend.infrastructure.dataset.FlightsDatasetLoader;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightSearchAlgorithm}.
 *
 * These tests use a small in-memory test double of FlightRepositoryInMemory
 * (no mocks) to control the graph and flights:
 *
 *  - We override getRouteAdjacency() and findByRoute(...) to use
 *    test-provided data structures.
 *  - The superclass is constructed with a real dataset loader so that
 *    AirportRepositoryInMemory can initialize successfully, but the
 *    algorithm only sees our synthetic flights.
 */
class FlightSearchAlgorithmTest {

  private static final ZoneId UTC = ZoneOffset.UTC;

  // ---------------------------------------------------------------------------
  // Core scenarios
  // ---------------------------------------------------------------------------

  /**
   * If there is no path in the adjacency graph between origin and destination,
   * search(...) should return an empty list.
   */
  @Test
  void search_shouldReturnEmptyWhenNoPathExists() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");

    // No edge from A to B
    Map<String, Set<String>> adjacency = new HashMap<>();
    Map<String, List<Flight>> flightsByRoute = new HashMap<>();

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,
        Duration.ofMinutes(45),
        Duration.ofMinutes(90),
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "B", start, end);

    assertTrue(result.isEmpty(), "Expected no itineraries when no graph path exists");
  }

  /**
   * Direct flight case: single leg within the departure window should produce
   * a one-leg itinerary.
   */
  @Test
  void search_shouldReturnDirectItineraryWithinWindow() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");

    Flight ab = flight(
        "AB100",
        a,
        b,
        "2024-03-15T10:00:00Z",
        "2024-03-15T12:00:00Z",
        100
    );

    Map<String, Set<String>> adjacency = new HashMap<>();
    adjacency.put("A", Set.of("B"));

    Map<String, List<Flight>> flightsByRoute = new HashMap<>();
    flightsByRoute.put(routeKey("A", "B"), List.of(ab));

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,
        Duration.ofMinutes(45),
        Duration.ofMinutes(90),
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "B", start, end);

    assertEquals(1, result.size(), "Expected exactly one direct itinerary");
    Itinerary it = result.get(0);
    assertEquals(1, it.getLegs().size(), "Direct itinerary should have one leg");
    assertEquals("AB100", it.getLegs().get(0).getFlightNumber());
  }

  /**
   * Direct flights departing before the window start should be excluded.
   */
  @Test
  void search_shouldExcludeDirectFlightsBeforeWindow() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");

    // Departs just before the window
    Flight ab = flight(
        "AB101",
        a,
        b,
        "2024-03-14T23:59:00Z",
        "2024-03-15T02:00:00Z",
        100
    );

    Map<String, Set<String>> adjacency = new HashMap<>();
    adjacency.put("A", Set.of("B"));

    Map<String, List<Flight>> flightsByRoute = new HashMap<>();
    flightsByRoute.put(routeKey("A", "B"), List.of(ab));

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,
        Duration.ofMinutes(45),
        Duration.ofMinutes(90),
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "B", start, end);

    assertTrue(result.isEmpty(), "Expected no itineraries when direct flight departs before window");
  }

  /**
   * One-stop domestic connection:
   *  - A -> B
   *  - B -> C
   *  with a layover >= minDomesticLayover should be accepted.
   *  Flights with too-short layover must be rejected.
   */
  @Test
  void search_shouldFindOneStopDomesticItineraryRespectingLayover() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");
    Airport c = airport("C", "CountryX");

    // First leg: A -> B, arrives at 10:00Z
    Flight ab = flight(
        "AB200",
        a,
        b,
        "2024-03-15T08:00:00Z",
        "2024-03-15T10:00:00Z",
        100
    );

    // Second leg candidate 1: too short layover (30 minutes)
    Flight bcTooShort = flight(
        "BC201",
        b,
        c,
        "2024-03-15T10:30:00Z",  // 30 min after 10:00
        "2024-03-15T12:00:00Z",
        100
    );

    // Second leg candidate 2: valid layover (60 minutes)
    Flight bcValid = flight(
        "BC202",
        b,
        c,
        "2024-03-15T11:00:00Z",  // 60 min after 10:00
        "2024-03-15T13:00:00Z",
        120
    );

    Map<String, Set<String>> adjacency = new HashMap<>();
    adjacency.put("A", Set.of("B"));
    adjacency.put("B", Set.of("C"));

    Map<String, List<Flight>> flightsByRoute = new HashMap<>();
    flightsByRoute.put(routeKey("A", "B"), List.of(ab));
    // Note: list is already sorted by departureTimeUtc
    flightsByRoute.put(routeKey("B", "C"), List.of(bcTooShort, bcValid));

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,
        Duration.ofMinutes(45),  // domestic min
        Duration.ofMinutes(90),  // international min
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "C", start, end);

    assertEquals(1, result.size(), "Expected exactly one valid one-stop itinerary");
    Itinerary it = result.get(0);
    assertEquals(2, it.getLegs().size(), "Itinerary should have two legs for one stop");
    assertEquals("AB200", it.getLegs().get(0).getFlightNumber());
    assertEquals("BC202", it.getLegs().get(1).getFlightNumber());
  }

  /**
   * One-stop international connection:
   *  If either leg is international, we enforce minInternationalLayover (e.g. 90 minutes).
   *  Here we create an A->B (domestic) and B->C (international), with 60 min layover.
   *  That should be rejected because 60 < 90.
   */
  @Test
  void search_shouldEnforceInternationalMinLayover() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");
    Airport c = airport("C", "CountryY"); // different country => international

    Flight ab = flight(
        "AB300",
        a,
        b,
        "2024-03-15T08:00:00Z",
        "2024-03-15T10:00:00Z",
        100
    );

    // 60-minute layover, but treated as international => min 90 -> invalid
    Flight bcInternational = flight(
        "BC301",
        b,
        c,
        "2024-03-15T11:00:00Z",
        "2024-03-15T14:00:00Z",
        200
    );

    Map<String, Set<String>> adjacency = new HashMap<>();
    adjacency.put("A", Set.of("B"));
    adjacency.put("B", Set.of("C"));

    Map<String, List<Flight>> flightsByRoute = new HashMap<>();
    flightsByRoute.put(routeKey("A", "B"), List.of(ab));
    flightsByRoute.put(routeKey("B", "C"), List.of(bcInternational));

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,
        Duration.ofMinutes(45),  // domestic min
        Duration.ofMinutes(90),  // international min
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "C", start, end);

    assertTrue(result.isEmpty(), "Expected no itineraries due to too-short international layover");
  }

  /**
   * Two-stop itinerary:
   *  A -> B -> C -> D where all layovers are within [minDomesticLayover, maxLayover]
   *  should produce a 3-leg itinerary.
   */
  @Test
  void search_shouldFindTwoStopItinerary() {
    Airport a = airport("A", "CountryX");
    Airport b = airport("B", "CountryX");
    Airport c = airport("C", "CountryX");
    Airport d = airport("D", "CountryX");

    // A -> B: arrives 08:00
    Flight ab = flight(
        "AB400",
        a,
        b,
        "2024-03-15T06:00:00Z",
        "2024-03-15T08:00:00Z",
        100
    );

    // B -> C: departs 09:00, arrives 11:00  (layover 1h)
    Flight bc = flight(
        "BC401",
        b,
        c,
        "2024-03-15T09:00:00Z",
        "2024-03-15T11:00:00Z",
        110
    );

    // C -> D: departs 12:00, arrives 14:00 (layover 1h)
    Flight cd = flight(
        "CD402",
        c,
        d,
        "2024-03-15T12:00:00Z",
        "2024-03-15T14:00:00Z",
        120
    );

    Map<String, Set<String>> adjacency = new HashMap<>();
    adjacency.put("A", Set.of("B"));
    adjacency.put("B", Set.of("C"));
    adjacency.put("C", Set.of("D"));

    Map<String, List<Flight>> flightsByRoute = new HashMap<>();
    flightsByRoute.put(routeKey("A", "B"), List.of(ab));
    flightsByRoute.put(routeKey("B", "C"), List.of(bc));
    flightsByRoute.put(routeKey("C", "D"), List.of(cd));

    TestFlightRepository repo = new TestFlightRepository(adjacency, flightsByRoute);
    FlightSearchAlgorithm algorithm = new FlightSearchAlgorithm(
        repo,
        2,                        // allow up to 3 legs (2 stops)
        Duration.ofMinutes(45),
        Duration.ofMinutes(90),
        Duration.ofHours(6)
    );

    Instant start = Instant.parse("2024-03-15T00:00:00Z");
    Instant end = Instant.parse("2024-03-16T00:00:00Z");

    List<Itinerary> result = algorithm.search("A", "D", start, end);

    assertEquals(1, result.size(), "Expected exactly one two-stop itinerary");
    Itinerary it = result.get(0);
    assertEquals(3, it.getLegs().size(), "Two-stop itinerary should have three legs");
    assertEquals("AB400", it.getLegs().get(0).getFlightNumber());
    assertEquals("BC401", it.getLegs().get(1).getFlightNumber());
    assertEquals("CD402", it.getLegs().get(2).getFlightNumber());
  }

  // ---------------------------------------------------------------------------
  // Helpers: airports, flights, keys, test repository
  // ---------------------------------------------------------------------------

  private static Airport airport(String code, String country) {
    return new Airport(
        code,
        "Airport " + code,
        "City " + code,
        country,
        UTC
    );
  }

  private static Flight flight(String flightNumber,
      Airport origin,
      Airport destination,
      String departureIsoUtc,
      String arrivalIsoUtc,
      double price) {
    return new Flight(
        flightNumber,
        "TestAir",
        origin,
        destination,
        Instant.parse(departureIsoUtc),
        Instant.parse(arrivalIsoUtc),
        BigDecimal.valueOf(price),
        "A320"
    );
  }

  private static String routeKey(String origin, String destination) {
    return origin + "->" + destination;
  }

  /**
   * Test-double for FlightRepositoryInMemory that ignores the actual flights
   * loaded from the dataset and instead relies on test-provided maps for:
   *
   *  - the adjacency graph, and
   *  - flights per origin/destination pair.
   */
  private static class TestFlightRepository extends FlightRepositoryInMemory {

    private final Map<String, Set<String>> adjacency;
    private final Map<String, List<Flight>> flightsByRoute;

    TestFlightRepository(Map<String, Set<String>> adjacency,
        Map<String, List<Flight>> flightsByRoute) {
      super(
          // Use the real dataset loader so AirportRepositoryInMemory sees valid airports.
          new FlightsDatasetLoader("classpath:static/flights.json", new DefaultResourceLoader()),
          new ObjectMapper(),
          new AirportRepositoryInMemory(
              new FlightsDatasetLoader("classpath:static/flights.json", new DefaultResourceLoader()),
              new ObjectMapper(),
              new AirportJsonMapper()
          ),
          new FlightJsonMapper()
      );
      this.adjacency = adjacency;
      this.flightsByRoute = flightsByRoute;
    }

    @Override
    public Map<String, Set<String>> getRouteAdjacency() {
      return adjacency;
    }

    @Override
    public List<Flight> findByRoute(String originCode, String destinationCode) {
      return flightsByRoute.getOrDefault(routeKey(originCode, destinationCode), List.of());
    }
  }
}