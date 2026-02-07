package com.skypath.backend.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;

public class Flight {

  private final String flightNumber;
  private final String airline;
  private final Airport origin;
  private final Airport destination;
  private final Instant departureTimeUtc;
  private final Instant arrivalTimeUtc;
  private final BigDecimal price;
  private final String aircraft;

  public Flight(String flightNumber,
      String airline,
      Airport origin,
      Airport destination,
      Instant departureTimeUtc,
      Instant arrivalTimeUtc,
      BigDecimal price,
      String aircraft) {

    this.flightNumber = Objects.requireNonNull(flightNumber, "flightNumber must not be null");
    this.airline = Objects.requireNonNull(airline, "airline must not be null");
    this.origin = Objects.requireNonNull(origin, "origin must not be null");
    this.destination = Objects.requireNonNull(destination, "destination must not be null");
    this.departureTimeUtc = Objects.requireNonNull(departureTimeUtc, "departureTimeUtc must not be null");
    this.arrivalTimeUtc = Objects.requireNonNull(arrivalTimeUtc, "arrivalTimeUtc must not be null");
    this.price = Objects.requireNonNull(price, "price must not be null");
    this.aircraft = Objects.requireNonNull(aircraft, "aircraft must not be null");
  }

  public String getFlightNumber() {
    return flightNumber;
  }

  public String getAirline() {
    return airline;
  }

  public Airport getOrigin() {
    return origin;
  }

  public Airport getDestination() {
    return destination;
  }

  public Instant getDepartureTimeUtc() {
    return departureTimeUtc;
  }

  public Instant getArrivalTimeUtc() {
    return arrivalTimeUtc;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getAircraft() {
    return aircraft;
  }

  public Duration getDuration() {
    return Duration.between(departureTimeUtc, arrivalTimeUtc);
  }

  /**
   * Departure time in the origin airport's local timezone.
   */
  public ZonedDateTime getDepartureAtOriginLocal() {
    return departureTimeUtc.atZone(origin.getTimezone());
  }

  /**
   * Arrival time in the destination airport's local timezone.
   */
  public ZonedDateTime getArrivalAtDestinationLocal() {
    return arrivalTimeUtc.atZone(destination.getTimezone());
  }

  @Override
  public String toString() {
    return flightNumber + " " + origin.getCode() + "→" + destination.getCode();
  }
}