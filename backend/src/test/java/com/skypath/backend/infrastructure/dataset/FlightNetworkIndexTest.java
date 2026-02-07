package com.skypath.backend.infrastructure.dataset;

import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FlightNetworkIndex}.
 *
 * These tests construct small in-memory Flight and Airport objects
 * to verify that:
 *  - flights are grouped by (origin, destination) as expected,
 *  - flights within a route are sorted by departureTimeUtc ascending,
 *  - the route adjacency graph (origin -> destinations) is built correctly,
 *  - exposed collections are unmodifiable,
 *  - empty datasets are handled gracefully.
 */
class FlightNetworkIndexTest {

  /**
   * Happy-path test:
   * Verifies that multiple flights across different routes are indexed correctly:
   *  - route JFK->LAX has 2 flights sorted by departure time,
   *  - route JFK->SFO has 1 flight,
   *  - route LAX->JFK has 1 flight,
   *  - route adjacency reflects these routes.
   */
  @Test
  void constructor_shouldIndexFlightsByRouteAndBuildAdjacency() {
    // given
    Airport jfk = airport("JFK", "America/New_York");
    Airport lax = airport("LAX", "America/Los_Angeles");
    Airport sfo = airport("SFO", "America/Los_Angeles");

    Flight jfkToLaxEarly = flight(
        "SP100",
        jfk, lax,
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T11:00:00Z"),
        "299.00"
    );

    Flight jfkToLaxLater = flight(
        "SP101",
        jfk, lax,
        Instant.parse("2024-03-15T10:00:00Z"),
        Instant.parse("2024-03-15T13:00:00Z"),
        "319.00"
    );

    Flight jfkToSfo = flight(
        "SP200",
        jfk, sfo,
        Instant.parse("2024-03-15T09:00:00Z"),
        Instant.parse("2024-03-15T12:30:00Z"),
        "349.00"
    );

    Flight laxToJfk = flight(
        "SP300",
        lax, jfk,
        Instant.parse("2024-03-15T14:00:00Z"),
        Instant.parse("2024-03-15T22:00:00Z"),
        "399.00"
    );

    List<Flight> flights = List.of(
        jfkToLaxLater,
        jfkToLaxEarly,
        jfkToSfo,
        laxToJfk
    );

    // when
    FlightNetworkIndex index = new FlightNetworkIndex(flights);

    // then: route JFK->LAX should have 2 flights sorted by departureTimeUtc
    List<Flight> jfkToLax = index.findByRoute("JFK", "LAX");
    assertEquals(2, jfkToLax.size(), "Expected two flights from JFK to LAX");
    assertEquals("SP100", jfkToLax.get(0).getFlightNumber(), "Earlier flight should come first");
    assertEquals("SP101", jfkToLax.get(1).getFlightNumber(), "Later flight should come second");

    // JFK->SFO should have exactly one flight
    List<Flight> jfkToSfoList = index.findByRoute("JFK", "SFO");
    assertEquals(1, jfkToSfoList.size(), "Expected one flight from JFK to SFO");
    assertEquals("SP200", jfkToSfoList.get(0).getFlightNumber());

    // LAX->JFK should have exactly one flight
    List<Flight> laxToJfkList = index.findByRoute("LAX", "JFK");
    assertEquals(1, laxToJfkList.size(), "Expected one flight from LAX to JFK");
    assertEquals("SP300", laxToJfkList.get(0).getFlightNumber());

    // Adjacency should reflect all direct routes
    Map<String, Set<String>> adjacency = index.getRouteAdjacency();
    assertEquals(Set.of("LAX", "SFO"), adjacency.get("JFK"),
        "JFK should connect to LAX and SFO");
    assertEquals(Set.of("JFK"), adjacency.get("LAX"),
        "LAX should connect to JFK");
    assertFalse(adjacency.containsKey("SFO"),
        "SFO has no outbound flights, so it should not appear as an origin");
  }

  /**
   * Edge case:
   * Verifies that requesting flights for a route with no flights
   * returns an empty (but non-null) list.
   */
  @Test
  void findByRoute_shouldReturnEmptyListForUnknownRoute() {
    // given
    Airport jfk = airport("JFK", "America/New_York");
    Airport lax = airport("LAX", "America/Los_Angeles");

    Flight jfkToLax = flight(
        "SP100",
        jfk, lax,
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T11:00:00Z"),
        "299.00"
    );

    FlightNetworkIndex index = new FlightNetworkIndex(List.of(jfkToLax));

    // when
    List<Flight> result = index.findByRoute("JFK", "SFO");

    // then
    assertNotNull(result, "findByRoute should never return null");
    assertTrue(result.isEmpty(), "Expected empty list for a route with no flights");
  }

  /**
   * Edge case:
   * Verifies that an empty list of flights produces an index with
   * no routes and an empty adjacency map, but API methods still behave
   * predictably (no exceptions, empty collections).
   */
  @Test
  void constructor_shouldHandleEmptyFlightList() {
    // given
    List<Flight> flights = List.of();

    // when
    FlightNetworkIndex index = new FlightNetworkIndex(flights);

    // then
    assertTrue(index.getFlightsByRoute().isEmpty(), "Expected no routes for empty flight list");
    assertTrue(index.getRouteAdjacency().isEmpty(), "Expected empty adjacency for empty flight list");

    List<Flight> result = index.findByRoute("JFK", "LAX");
    assertNotNull(result, "findByRoute should never return null");
    assertTrue(result.isEmpty(), "Expected empty list for any route when no flights exist");
  }

  /**
   * API contract test:
   * Verifies that the route index map and its inner lists are unmodifiable.
   */
  @Test
  void getFlightsByRoute_shouldReturnUnmodifiableMapAndLists() {
    // given
    FlightNetworkIndex index = createIndexWithSingleRoute();

    Map<RouteKey, List<Flight>> byRoute = index.getFlightsByRoute();
    assertFalse(byRoute.isEmpty(), "Precondition: route index should not be empty");

    // when / then: outer map must be unmodifiable
    assertThrows(UnsupportedOperationException.class,
        () -> byRoute.clear(),
        "getFlightsByRoute() map should be unmodifiable");

    // inner lists must also be unmodifiable
    Map.Entry<RouteKey, List<Flight>> firstEntry = byRoute.entrySet().iterator().next();
    List<Flight> flightsForRoute = firstEntry.getValue();
    assertThrows(UnsupportedOperationException.class,
        () -> flightsForRoute.add(flightsForRoute.get(0)),
        "Lists in getFlightsByRoute() should be unmodifiable");
  }

  /**
   * API contract test:
   * Verifies that the adjacency map and its inner sets are unmodifiable.
   */
  @Test
  void getRouteAdjacency_shouldReturnUnmodifiableMapAndSets() {
    // given
    FlightNetworkIndex index = createIndexWithSingleRoute();

    Map<String, Set<String>> adjacency = index.getRouteAdjacency();
    assertFalse(adjacency.isEmpty(), "Precondition: adjacency should not be empty");

    // when / then: outer map must be unmodifiable
    assertThrows(UnsupportedOperationException.class,
        () -> adjacency.put("NEW_ORIGIN", Set.of("NEW_DEST")),
        "Adjacency map should be unmodifiable");

    // inner sets must be unmodifiable
    Set<String> destinationsFromOrigin = adjacency.values().iterator().next();
    assertThrows(UnsupportedOperationException.class,
        () -> destinationsFromOrigin.add("ANOTHER_DEST"),
        "Destination sets in adjacency should be unmodifiable");
  }

  // ---------------------------------------------------------------------------
  // Helper methods
  // ---------------------------------------------------------------------------

  /**
   * Helper for creating a minimal Airport instance for tests.
   */
  private Airport airport(String code, String zoneId) {
    return new Airport(
        code,
        code + " Airport",
        "Test City",
        "Test Country",
        ZoneId.of(zoneId)
    );
  }

  /**
   * Helper for creating a minimal Flight instance for tests.
   * Adjust the constructor call if your Flight class has a different signature.
   */
  private Flight flight(String flightNumber,
      Airport origin,
      Airport destination,
      Instant departureUtc,
      Instant arrivalUtc,
      String price) {
    return new Flight(
        flightNumber,
        "Test Airline",
        origin,
        destination,
        departureUtc,
        arrivalUtc,
        new BigDecimal(price),
        "Test Aircraft"
    );
  }

  /**
   * Creates an index with a single route (JFK->LAX) and one flight on it,
   * used for unmodifiable-collection tests.
   */
  private FlightNetworkIndex createIndexWithSingleRoute() {
    Airport jfk = airport("JFK", "America/New_York");
    Airport lax = airport("LAX", "America/Los_Angeles");

    Flight jfkToLax = flight(
        "SP_SINGLE",
        jfk, lax,
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T11:00:00Z"),
        "199.00"
    );

    return new FlightNetworkIndex(List.of(jfkToLax));
  }
}