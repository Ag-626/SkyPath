package com.skypath.backend.controller;

import com.skypath.backend.domain.Flight;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.service.FlightSearchService;
import com.skypath.backend.dto.FlightSegmentResponse;
import com.skypath.backend.dto.ItineraryResponse;
import com.skypath.backend.dto.LayoverResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * REST controller exposing a flight search API.
 *
 * Requirements covered:
 *  - GET /api/flights/search
 *      - origin: 3-letter IATA code
 *      - destination: 3-letter IATA code
 *      - date: ISO-8601 (YYYY-MM-DD)
 *  - Returns itineraries including:
 *      - segments (flight details, times, airports)
 *      - layover durations
 *      - total travel duration
 *      - total price
 *  - Sorted by total travel time (shortest first).
 */
@RestController
@RequestMapping("/api/flights")
public class FlightSearchController {

  private static final Logger log = LoggerFactory.getLogger(FlightSearchController.class);

  private static final DateTimeFormatter ISO_WITH_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  private final FlightSearchService flightSearchService;

  public FlightSearchController(FlightSearchService flightSearchService) {
    this.flightSearchService = flightSearchService;
  }

  /**
   * Search itineraries between two airports on a given date.
   *
   * Example:
   *   GET /api/flights/search?origin=JFK&destination=LAX&date=2024-03-15
   */
  @GetMapping("/search")
  public List<ItineraryResponse> search(
      @RequestParam("origin") String origin,
      @RequestParam("destination") String destination,
      @RequestParam("date")
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
  ) {
    Objects.requireNonNull(origin, "origin must not be null");
    Objects.requireNonNull(destination, "destination must not be null");
    Objects.requireNonNull(date, "date must not be null");

    String originCode = origin.trim().toUpperCase();
    String destinationCode = destination.trim().toUpperCase();

    log.info("Search request: origin={}, destination={}, date={}",
        originCode, destinationCode, date);

    List<Itinerary> itineraries =
        flightSearchService.search(originCode, destinationCode, date);

    // Requirement #5: Sort by total travel time (shortest first).
    // We sort here explicitly (even though the algorithm also sorts),
    // to make the API contract obvious and easy to reason about.
    return itineraries.stream()
        .sorted(Comparator.comparing(Itinerary::getTotalDuration))
        .map(this::toResponse)
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Mapping from domain Itinerary -> API ItineraryResponse
  // ---------------------------------------------------------------------------

  private ItineraryResponse toResponse(Itinerary itinerary) {
    List<Flight> legs = itinerary.getLegs();

    List<FlightSegmentResponse> segments = legs.stream()
        .map(this::toSegmentResponse)
        .toList();

    List<LayoverResponse> layovers = computeLayovers(legs);

    long totalDurationMinutes = itinerary.getTotalDuration().toMinutes();

    return new ItineraryResponse(
        segments,
        layovers,
        totalDurationMinutes,
        itinerary.getTotalPrice()
    );
  }

  private FlightSegmentResponse toSegmentResponse(Flight flight) {
    ZonedDateTime departureLocal = flight.getDepartureAtOriginLocal();
    ZonedDateTime arrivalLocal = flight.getArrivalAtDestinationLocal();

    String departureTimeLocal = departureLocal.format(ISO_WITH_OFFSET);
    String arrivalTimeLocal = arrivalLocal.format(ISO_WITH_OFFSET);

    return new FlightSegmentResponse(
        flight.getFlightNumber(),
        flight.getAirline(),
        flight.getOrigin().getCode(),
        flight.getOrigin().getCity(),
        flight.getDestination().getCode(),
        flight.getDestination().getCity(),
        departureTimeLocal,
        arrivalTimeLocal,
        flight.getAircraft(),
        flight.getPrice()
    );
  }

  /**
   * Compute layover durations at each connection point, in minutes.
   * We measure layovers in the local time of the connection airport.
   */
  private List<LayoverResponse> computeLayovers(List<Flight> legs) {
    if (legs.size() <= 1) {
      return List.of();
    }

    List<LayoverResponse> result = new ArrayList<>();

    for (int i = 0; i < legs.size() - 1; i++) {
      Flight current = legs.get(i);
      Flight next = legs.get(i + 1);

      ZonedDateTime arrivalLocal = current.getArrivalAtDestinationLocal();
      ZonedDateTime departureLocal = next.getDepartureAtOriginLocal();

      long minutes = Duration.between(arrivalLocal, departureLocal).toMinutes();

      result.add(new LayoverResponse(
          current.getDestination().getCode(),
          minutes
      ));
    }

    return result;
  }

  // ---------------------------------------------------------------------------
  // Basic error handling for bad requests (unknown airports, etc.)
  // ---------------------------------------------------------------------------

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<SimpleErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    log.warn("Bad request: {}", ex.getMessage());
    return ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(new SimpleErrorResponse(ex.getMessage()));
  }

  /**
   * Minimal error response body so the frontend gets something structured.
   */
  private record SimpleErrorResponse(String message) {
  }
}