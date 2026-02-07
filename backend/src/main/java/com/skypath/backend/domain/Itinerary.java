package com.skypath.backend.domain;

import com.skypath.backend.domain.Flight;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object representing a flight itinerary:
 * one or more flight legs, all chained together.
 * Creates an itinerary with at least one flight.
 * To represent "no possible journeys", use an empty List<Itinerary>
 * instead of an Itinerary with zero legs.
 */

public final class Itinerary {

  private final List<Flight> legs;

  public Itinerary(List<Flight> legs) {
    Objects.requireNonNull(legs, "legs must not be null");
    if (legs.isEmpty()) {
      throw new IllegalArgumentException("Itinerary must contain at least one flight leg");
    }
    this.legs = List.copyOf(legs);
  }

  /**
   * Legs in order from origin to final destination.
   */
  public List<Flight> getLegs() {
    return legs;
  }

  /**
   * Number of stops = legs - 1.
   */
  public int getNumberOfStops() {
    return legs.size() - 1;
  }

  /**
   * Total price = sum of all leg prices.
   */
  public BigDecimal getTotalPrice() {
    BigDecimal total = BigDecimal.ZERO;
    for (Flight flight : legs) {
      total = total.add(flight.getPrice());
    }
    return total;
  }

  /**
   * Total travel time from first departure to last arrival.
   */
  public Duration getTotalDuration() {
    Flight first = legs.get(0);
    Flight last = legs.get(legs.size() - 1);

    Instant departure = first.getDepartureTimeUtc();
    Instant arrival = last.getArrivalTimeUtc();

    return Duration.between(departure, arrival);
  }

  /**
   * Convenience factory for single-leg itineraries.
   */
  public static Itinerary ofSingleLeg(Flight flight) {
    return new Itinerary(Collections.singletonList(flight));
  }

  /**
   * Convenience factory for multiple legs.
   */
  public static Itinerary ofLegs(Flight... flights) {
    List<Flight> list = new ArrayList<>(flights.length);
    Collections.addAll(list, flights);
    return new Itinerary(list);
  }
}