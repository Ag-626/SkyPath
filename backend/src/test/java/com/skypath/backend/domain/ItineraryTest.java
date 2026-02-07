package com.skypath.backend.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Itinerary}.
 *
 * We verify:
 *  - constructor invariants (non-null, non-empty legs),
 *  - number of stops calculation,
 *  - total price calculation,
 *  - total duration calculation,
 *  - factory methods behavior,
 *  - immutability of the legs list.
 */
class ItineraryTest {

  // ---------------- Constructor invariants ----------------

  @Test
  void constructor_shouldThrowWhenLegsIsNull() {
    assertThrows(NullPointerException.class,
        () -> new Itinerary(null),
        "Itinerary should reject null legs list");
  }

  @Test
  void constructor_shouldThrowWhenLegsIsEmpty() {
    assertThrows(IllegalArgumentException.class,
        () -> new Itinerary(List.of()),
        "Itinerary should reject an empty legs list");
  }

  // ---------------- Basic behavior ----------------

  @Test
  void getNumberOfStops_shouldBeLegCountMinusOne() {
    Flight leg1 = flight("F1");
    Flight leg2 = flight("F2");
    Flight leg3 = flight("F3");

    Itinerary direct = new Itinerary(List.of(leg1));
    Itinerary oneStop = new Itinerary(List.of(leg1, leg2));
    Itinerary twoStops = new Itinerary(List.of(leg1, leg2, leg3));

    assertEquals(0, direct.getNumberOfStops(), "1 leg = 0 stops");
    assertEquals(1, oneStop.getNumberOfStops(), "2 legs = 1 stop");
    assertEquals(2, twoStops.getNumberOfStops(), "3 legs = 2 stops");
  }

  @Test
  void getTotalPrice_shouldSumAllLegPrices() {
    Flight leg1 = flight("F1", "100.50");
    Flight leg2 = flight("F2", "200.25");
    Flight leg3 = flight("F3", "50.25");

    Itinerary itinerary = new Itinerary(List.of(leg1, leg2, leg3));

    assertEquals(new BigDecimal("351.00"), itinerary.getTotalPrice());
  }

  @Test
  void getTotalDuration_shouldUseFirstDepartureAndLastArrival() {
    // 08:00Z -> 10:00Z (2h)
    Flight leg1 = flight(
        "F1",
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T10:00:00Z")
    );
    // 12:00Z -> 15:30Z (3h30m)
    Flight leg2 = flight(
        "F2",
        Instant.parse("2024-03-15T12:00:00Z"),
        Instant.parse("2024-03-15T15:30:00Z")
    );

    // Total journey: 08:00Z -> 15:30Z = 7h30m
    Itinerary itinerary = new Itinerary(List.of(leg1, leg2));

    assertEquals(Duration.ofHours(7).plusMinutes(30), itinerary.getTotalDuration());
  }

  // ---------------- Factory methods ----------------

  @Test
  void ofSingleLeg_shouldCreateItineraryWithOneLeg() {
    Flight leg = flight("F1");

    Itinerary itinerary = Itinerary.ofSingleLeg(leg);

    assertEquals(1, itinerary.getLegs().size());
    assertSame(leg, itinerary.getLegs().get(0));
  }

  @Test
  void ofLegs_shouldCreateItineraryWithAllGivenLegsInOrder() {
    Flight leg1 = flight("F1");
    Flight leg2 = flight("F2");
    Flight leg3 = flight("F3");

    Itinerary itinerary = Itinerary.ofLegs(leg1, leg2, leg3);

    assertEquals(List.of(leg1, leg2, leg3), itinerary.getLegs());
  }

  // ---------------- Immutability ----------------

  @Test
  void getLegs_shouldReturnUnmodifiableList() {
    Flight leg = flight("F1");
    Itinerary itinerary = new Itinerary(List.of(leg));

    List<Flight> legs = itinerary.getLegs();

    assertThrows(UnsupportedOperationException.class,
        () -> legs.add(leg),
        "Legs list should be unmodifiable from outside");
  }

  @Test
  void constructor_shouldDefensivelyCopyInputList() {
    Flight leg1 = flight("F1");
    Flight leg2 = flight("F2");

    List<Flight> mutable = new ArrayList<>();
    mutable.add(leg1);

    Itinerary itinerary = new Itinerary(mutable);

    // modify original list after construction
    mutable.add(leg2);

    // itinerary must not see the change
    assertEquals(1, itinerary.getLegs().size());
    assertEquals(leg1, itinerary.getLegs().get(0));
  }

  // ---------------------------------------------------------------------------
  // Helper: build minimal Flight instances for tests
  // ---------------------------------------------------------------------------

  /**
   * Minimal helper for flights where we only care about id/price, and
   * use fixed times.
   */
  private Flight flight(String flightNumber) {
    return flight(
        flightNumber,
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T10:00:00Z"),
        "100.00"
    );
  }

  private Flight flight(String flightNumber, String price) {
    return flight(
        flightNumber,
        Instant.parse("2024-03-15T08:00:00Z"),
        Instant.parse("2024-03-15T10:00:00Z"),
        price
    );
  }

  private Flight flight(String flightNumber,
      Instant departureUtc,
      Instant arrivalUtc) {
    return flight(flightNumber, departureUtc, arrivalUtc, "100.00");
  }

  /**
   * Adjust this helper if your Flight constructor has a different signature.
   */
  private Flight flight(String flightNumber,
      Instant departureUtc,
      Instant arrivalUtc,
      String price) {

    Airport origin = new Airport(
        "ORG",
        "Origin Airport",
        "Origin City",
        "US",
        ZoneId.of("America/New_York")
    );
    Airport destination = new Airport(
        "DST",
        "Destination Airport",
        "Destination City",
        "US",
        ZoneId.of("America/New_York")
    );

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
}