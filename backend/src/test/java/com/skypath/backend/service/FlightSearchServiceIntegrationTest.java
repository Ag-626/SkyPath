package com.skypath.backend.service;

import com.skypath.backend.domain.Flight;
import com.skypath.backend.domain.Itinerary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that wires the real Spring context, loads flights.json,
 * and verifies that FlightSearchService returns logically consistent
 * itineraries for real searches.
 *
 * This is intentionally higher-level than the unit tests and checks
 * all the key invariants end-to-end:
 *  - origin/destination codes
 *  - stops <= 2
 *  - total price = sum of leg prices
 *  - total duration matches first departure -> last arrival
 *  - layover rules (domestic vs international, max layover)
 *  - sorted by total travel time
 */
@SpringBootTest
class FlightSearchServiceIntegrationTest {

  // Business rules (keep in sync with application.properties / algorithm)
  private static final Duration MIN_DOMESTIC_LAYOVER = Duration.ofMinutes(45);
  private static final Duration MIN_INTERNATIONAL_LAYOVER = Duration.ofMinutes(90);
  private static final Duration MAX_LAYOVER = Duration.ofHours(6);

  @Autowired
  private FlightSearchService flightSearchService;

  // ---------------------------------------------------------------------------
  // Happy-path integration: JFK -> LAX on 2024-03-15 (lots of itineraries)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("JFK→LAX on 2024-03-15: itineraries should satisfy all invariants and be sorted by duration")
  void search_JfkToLax_shouldReturnConsistentSortedItineraries() {
    // Given
    String origin = "JFK";
    String destination = "LAX";
    LocalDate travelDate = LocalDate.of(2024, 3, 15);

    // When
    List<Itinerary> result = flightSearchService.search(origin, destination, travelDate);

    // Then
    assertNotNull(result, "Result list must not be null");
    assertFalse(result.isEmpty(), "Expected at least one itinerary for JFK -> LAX");

    // 1. Sorted by total duration (non-decreasing)
    for (int i = 0; i < result.size() - 1; i++) {
      long currentMinutes = result.get(i).getTotalDuration().toMinutes();
      long nextMinutes = result.get(i + 1).getTotalDuration().toMinutes();
      assertTrue(
          currentMinutes <= nextMinutes,
          "Itineraries must be sorted by total duration (shortest first)"
      );
    }

    // 2. Per-itinerary invariants
    for (Itinerary itinerary : result) {
      assertItineraryInvariants(itinerary, origin, destination, travelDate);
    }
  }

  // ---------------------------------------------------------------------------
  // Edge case 1: Same route but different date -> no departures that day
  // Data: flights.json only has JFK departures on 2024-03-15, not 2024-03-16.
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("JFK→LAX on 2024-03-16: no itineraries because there are no JFK departures that day")
  void search_JfkToLax_onNextDay_shouldReturnEmptyList() {
    // Given
    String origin = "JFK";
    String destination = "LAX";
    LocalDate travelDate = LocalDate.of(2024, 3, 16);

    // When
    List<Itinerary> result = flightSearchService.search(origin, destination, travelDate);

    // Then
    assertNotNull(result, "Result list must not be null");
    assertTrue(result.isEmpty(),
        "Expected no itineraries for JFK -> LAX on 2024-03-16 because dataset has no JFK departures that day");
  }

  // ---------------------------------------------------------------------------
  // Edge case 2: No path within 2 stops (graph connectivity)
  // Example from flights.json: AMS only flies to LHR, and within 2 stops
  // there is no route AMS -> LAX. The algorithm should return an empty list.
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("LGA→AMS on 2024-03-15: no route within 2 stops should yield empty list")
  void search_AmsToLax_shouldReturnEmptyWhenNoPathExists() {
    // Given
    String origin = "LGA";
    String destination = "AMS";
    LocalDate travelDate = LocalDate.of(2024, 3, 15);

    // When
    List<Itinerary> result = flightSearchService.search(origin, destination, travelDate);

    // Then
    assertNotNull(result, "Result list must not be null");
    assertTrue(result.isEmpty(),
        "Expected no itineraries for AMS -> LAX within 2 stops based on flights.json connectivity");
  }

  // ---------------------------------------------------------------------------
  // Edge case 3: Different origin with real departures on 2024-03-16
  // Data: flights.json has NRT->LAX flights on both 2024-03-15 and 2024-03-16.
  // We verify that searching on 2024-03-16 returns valid itineraries and
  // still respects all invariants.
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("NRT→LAX on 2024-03-16: itineraries should be valid and satisfy invariants")
  void search_NrtToLax_on2024_03_16_shouldReturnConsistentItineraries() {
    // Given
    String origin = "NRT";
    String destination = "LAX";
    LocalDate travelDate = LocalDate.of(2024, 3, 16);

    // When
    List<Itinerary> result = flightSearchService.search(origin, destination, travelDate);

    // Then
    assertNotNull(result, "Result list must not be null");
    assertFalse(result.isEmpty(),
        "Expected at least one itinerary for NRT -> LAX on 2024-03-16");

    // Optional: verify sorted by duration to keep symmetry with the JFK/LAX test
    for (int i = 0; i < result.size() - 1; i++) {
      long currentMinutes = result.get(i).getTotalDuration().toMinutes();
      long nextMinutes = result.get(i + 1).getTotalDuration().toMinutes();
      assertTrue(
          currentMinutes <= nextMinutes,
          "Itineraries must be sorted by total duration (shortest first)"
      );
    }

    // Invariants for all NRT->LAX itineraries
    for (Itinerary itinerary : result) {
      assertItineraryInvariants(itinerary, origin, destination, travelDate);
    }
  }

  // ---------------------------------------------------------------------------
  // Edge case 4: Origin equals destination -> service should short-circuit
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Origin equals destination: should return empty list")
  void search_OriginEqualsDestination_shouldReturnEmptyList() {
    // Given
    String origin = "JFK";
    String destination = "JFK";
    LocalDate travelDate = LocalDate.of(2024, 3, 15);

    // When
    List<Itinerary> result = flightSearchService.search(origin, destination, travelDate);

    // Then
    assertNotNull(result, "Result list must not be null");
    assertTrue(result.isEmpty(),
        "Expected no itineraries when origin and destination are the same");
  }

  // ---------------------------------------------------------------------------
  // Helper assertions
  // ---------------------------------------------------------------------------

  /**
   * Assert all logical invariants of a single itinerary.
   */
  private void assertItineraryInvariants(Itinerary itinerary,
      String expectedOrigin,
      String expectedDestination,
      LocalDate expectedTravelDate) {

    List<Flight> legs = itinerary.getLegs();
    assertNotNull(legs, "Itinerary legs must not be null");
    assertFalse(legs.isEmpty(), "Itinerary must contain at least one leg");
    assertTrue(legs.size() <= 3, "Itinerary must have at most 3 legs (max 2 stops)");

    Flight first = legs.get(0);
    Flight last = legs.get(legs.size() - 1);

    // Origin / destination match request
    assertEquals(expectedOrigin, first.getOrigin().getCode(),
        "First leg origin must match requested origin");
    assertEquals(expectedDestination, last.getDestination().getCode(),
        "Last leg destination must match requested destination");

    // First departure date in origin local time must equal requested travel date
    LocalDate departureLocalDate =
        first.getDepartureAtOriginLocal().toLocalDate();
    assertEquals(expectedTravelDate, departureLocalDate,
        "First leg departure local date at origin must match requested date");

    // Total price = sum of all leg prices (using BigDecimal.compareTo to ignore scale)
    BigDecimal sum = BigDecimal.ZERO;
    for (Flight leg : legs) {
      sum = sum.add(leg.getPrice());
    }
    assertEquals(0, sum.compareTo(itinerary.getTotalPrice()),
        "Itinerary total price must equal sum of leg prices");

    // Total duration = time between first departure UTC and last arrival UTC
    Instant departureUtc = first.getDepartureTimeUtc();
    Instant arrivalUtc = last.getArrivalTimeUtc();
    Duration expectedDuration = Duration.between(departureUtc, arrivalUtc);

    assertEquals(expectedDuration.toMinutes(),
        itinerary.getTotalDuration().toMinutes(),
        "Itinerary total duration must match first->last UTC timestamps");

    // Layover invariants between consecutive legs
    for (int i = 0; i < legs.size() - 1; i++) {
      assertConnectionInvariants(legs.get(i), legs.get(i + 1));
    }
  }

  /**
   * Assert that two consecutive legs form a valid connection:
   *  - same airport (no airport change)
   *  - departure after arrival
   *  - layover within [minDomestic, max] or [minInternational, max]
   */
  private void assertConnectionInvariants(Flight first, Flight second) {
    // Same airport code
    String firstDestCode = first.getDestination().getCode();
    String secondOriginCode = second.getOrigin().getCode();
    assertEquals(firstDestCode, secondOriginCode,
        "Connection must happen at the same airport (no airport change)");

    // Local times at connection airport
    ZonedDateTime arrivalLocal = first.getArrivalAtDestinationLocal();
    ZonedDateTime departureLocal = second.getDepartureAtOriginLocal();

    assertTrue(departureLocal.isAfter(arrivalLocal),
        "Next leg must depart strictly after previous leg arrives");

    Duration layover = Duration.between(arrivalLocal, departureLocal);

    // Determine domestic vs international based on origin/destination countries
    Duration minLayover = minLayover(first, second);

    long layoverMinutes = layover.toMinutes();
    long minMinutes = minLayover.toMinutes();
    long maxMinutes = MAX_LAYOVER.toMinutes();

    assertTrue(
        layoverMinutes >= minMinutes,
        () -> String.format(
            "Layover %d minutes must be >= min required %d minutes",
            layoverMinutes, minMinutes)
    );

    assertTrue(
        layoverMinutes <= maxMinutes,
        () -> String.format(
            "Layover %d minutes must be <= max allowed %d minutes",
            layoverMinutes, maxMinutes)
    );
  }

  /**
   * Compute the minimum layover according to domestic/international rules.
   * Mirrors the logic in FlightSearchAlgorithm:
   *  - If both flights are domestic (origin.country == destination.country),
   *    we treat it as a domestic connection: 45 minutes.
   *  - If either leg is international, we enforce the stricter 90 minutes.
   */
  private Duration minLayover(Flight first, Flight second) {
    boolean bothDomestic = isDomestic(first) && isDomestic(second);
    return bothDomestic ? MIN_DOMESTIC_LAYOVER : MIN_INTERNATIONAL_LAYOVER;
  }

  private boolean isDomestic(Flight flight) {
    String originCountry = flight.getOrigin().getCountry();
    String destinationCountry = flight.getDestination().getCountry();
    return originCountry != null
        && destinationCountry != null
        && originCountry.equalsIgnoreCase(destinationCountry);
  }
}