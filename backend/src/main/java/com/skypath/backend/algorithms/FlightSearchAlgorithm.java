package com.skypath.backend.algorithms;

import com.skypath.backend.domain.Flight;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.infrastructure.dataset.FlightRepositoryInMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Pure search algorithm for finding itineraries between two airports,
 * given a UTC departure window and access to the flight repository.
 *
 * This class is intentionally free of web/controller concerns and
 * assumes that:
 *  - origin/destination codes are valid,
 *  - the UTC time window has already been computed.
 *
 * FlightSearchService is responsible for validation and orchestration;
 * this class is responsible for the actual search strategy.
 */
@Component
public class FlightSearchAlgorithm {

  private static final Logger log = LoggerFactory.getLogger(FlightSearchAlgorithm.class);

  // ---------------------------------------------------------------------------
  // Configurable settings (injected from application.properties)
  // ---------------------------------------------------------------------------

  /**
   * Maximum number of stops allowed between origin and destination.
   * Example: 2 stops => up to 3 legs.
   */
  private final int maxStops;

  /**
   * Minimum layover for domestic connections (both legs domestic),
   * e.g. 45 minutes.
   */
  private final Duration minDomesticLayover;

  /**
   * Minimum layover when at least one leg is international,
   * e.g. 90 minutes.
   */
  private final Duration minInternationalLayover;

  /**
   * Maximum layover for any connection, e.g. 6 hours.
   */
  private final Duration maxLayover;

  private final FlightRepositoryInMemory flightRepository;

  public FlightSearchAlgorithm(
      FlightRepositoryInMemory flightRepository,
      @Value("${skypath.search.max-stops:2}") int maxStops,
      @Value("${skypath.search.min-domestic-layover:45m}") Duration minDomesticLayover,
      @Value("${skypath.search.min-international-layover:90m}") Duration minInternationalLayover,
      @Value("${skypath.search.max-layover:6h}") Duration maxLayover
  ) {
    this.flightRepository = flightRepository;
    this.maxStops = maxStops;
    this.minDomesticLayover = minDomesticLayover;
    this.minInternationalLayover = minInternationalLayover;
    this.maxLayover = maxLayover;
  }

  /**
   * Core search entry point.
   *
   * @param originCode       IATA code of origin airport
   * @param destinationCode  IATA code of destination airport
   * @param startOfDayUtc    inclusive lower bound of first-leg departure, in UTC
   * @param endOfDayUtc      exclusive upper bound of first-leg departure, in UTC
   */
  public List<Itinerary> search(String originCode,
      String destinationCode,
      Instant startOfDayUtc,
      Instant endOfDayUtc) {

    Map<String, Set<String>> adjacency = flightRepository.getRouteAdjacency();

    // 1. Find airport paths up to configured maxStops (0, 1, 2, ... stops).
    List<List<String>> airportPaths =
        findAirportPaths(originCode, destinationCode, maxStops+1, adjacency);

    if (airportPaths.isEmpty()) {
      log.info("No airport paths found from {} to {} within {} stops",
          originCode, destinationCode, maxStops);
      return List.of();
    }

    // 2. For each airport path, construct time-feasible itineraries.
    List<Itinerary> itineraries = new ArrayList<>();

    for (List<String> path : airportPaths) {
      itineraries.addAll(buildItinerariesForPath(path, startOfDayUtc, endOfDayUtc));
    }

    if (itineraries.isEmpty()) {
      log.info("No time-feasible itineraries found from {} to {} in given departure window",
          originCode, destinationCode);
      return List.of();
    }

    // 3. Sort itineraries to present "best" ones first:
    //    fewer stops, shorter total duration, cheaper.
    itineraries.sort(
        Comparator.comparingInt(Itinerary::getNumberOfStops)
            .thenComparing(Itinerary::getTotalDuration)
            .thenComparing(Itinerary::getTotalPrice)
    );

    return List.copyOf(itineraries);
  }

  // ---------------------------------------------------------------------------
  // Step 1: Find airport paths (0..maxStops) using adjacency
  // ---------------------------------------------------------------------------

  private List<List<String>> findAirportPaths(String originCode,
      String destinationCode,
      int maxLegs,
      Map<String, Set<String>> adjacency) {
    List<List<String>> result = new ArrayList<>();
    Deque<String> currentPath = new ArrayDeque<>();
    currentPath.add(originCode);

    dfsAirportPaths(originCode, destinationCode, maxLegs, adjacency, currentPath, result);
    return result;
  }

  private void dfsAirportPaths(String current,
      String target,
      int remainingLegs,
      Map<String, Set<String>> adjacency,
      Deque<String> currentPath,
      List<List<String>> result) {

    if (current.equals(target)) {
      // currentPath already contains a valid sequence from origin to target.
      result.add(new ArrayList<>(currentPath));
      return;
    }

    if (remainingLegs == 0) {
      // Cannot add more edges; stop exploring.
      return;
    }

    Set<String> neighbors = adjacency.getOrDefault(current, Set.of());
    if (neighbors.isEmpty()) {
      return;
    }

    for (String next : neighbors) {
      if (currentPath.contains(next)) {
        // Avoid cycles like JFK -> LHR -> JFK -> ...
        continue;
      }
      currentPath.addLast(next);
      dfsAirportPaths(next, target, remainingLegs - 1, adjacency, currentPath, result);
      currentPath.removeLast();
    }
  }

  // ---------------------------------------------------------------------------
  // Step 2: Build itineraries for a given airport path
  // ---------------------------------------------------------------------------

  private List<Itinerary> buildItinerariesForPath(List<String> airportPath,
      Instant startOfDayUtc,
      Instant endOfDayUtc) {
    int legs = airportPath.size() - 1;

    return PathPattern.fromLegCount(legs)
        .map(pattern -> pattern.build(this, airportPath, startOfDayUtc, endOfDayUtc))
        .orElseGet(() -> {
          // We only support up to maxStops (maxStops + 1 legs).
          log.warn("Ignoring airport path with {} legs (more than {} stops): {}",
              legs, maxStops, airportPath);
          return List.of();
        });
  }

  // ----- Direct (0-stop) -----------------------------------------------------

  List<Itinerary> buildDirectItineraries(List<String> airportPath,
      Instant startOfDayUtc,
      Instant endOfDayUtc) {
    String origin = airportPath.get(0);
    String destination = airportPath.get(1);

    List<Flight> flightsOnRoute = flightRepository.findByRoute(origin, destination);
    if (flightsOnRoute.isEmpty()) {
      return List.of();
    }

    List<Itinerary> result = new ArrayList<>();

    for (Flight flight : flightsOnRoute) {
      Instant departureUtc = flight.getDepartureTimeUtc();
      if (!isWithinDepartureWindow(departureUtc, startOfDayUtc, endOfDayUtc)) {
        continue;
      }
      result.add(Itinerary.ofSingleLeg(flight));
    }

    return result;
  }

  // ----- One-stop ------------------------------------------------------------

  List<Itinerary> buildOneStopItineraries(List<String> airportPath,
      Instant startOfDayUtc,
      Instant endOfDayUtc) {
    String origin = airportPath.get(0);
    String via = airportPath.get(1);
    String destination = airportPath.get(2);

    List<Flight> firstLegFlights = flightRepository.findByRoute(origin, via);
    List<Flight> secondLegFlights = flightRepository.findByRoute(via, destination);

    if (firstLegFlights.isEmpty() || secondLegFlights.isEmpty()) {
      return List.of();
    }

    List<Itinerary> result = new ArrayList<>();

    // First leg must depart on the requested day (in UTC window).
    for (Flight firstLeg : firstLegFlights) {
      if (!isWithinDepartureWindow(firstLeg.getDepartureTimeUtc(), startOfDayUtc, endOfDayUtc)) {
        continue;
      }

      // Use optimized connection builder: binary search + layover window.
      addValidConnections(firstLeg, secondLegFlights, result);
    }

    return result;
  }

  // ----- Two-stop ------------------------------------------------------------

  List<Itinerary> buildTwoStopItineraries(List<String> airportPath,
      Instant startOfDayUtc,
      Instant endOfDayUtc) {
    String origin = airportPath.get(0);
    String via1 = airportPath.get(1);
    String via2 = airportPath.get(2);
    String destination = airportPath.get(3);

    List<Flight> firstLegFlights = flightRepository.findByRoute(origin, via1);
    List<Flight> secondLegFlights = flightRepository.findByRoute(via1, via2);
    List<Flight> thirdLegFlights = flightRepository.findByRoute(via2, destination);

    if (firstLegFlights.isEmpty() || secondLegFlights.isEmpty() || thirdLegFlights.isEmpty()) {
      return List.of();
    }

    List<Itinerary> result = new ArrayList<>();

    // First leg must depart on the requested day (in UTC window).
    for (Flight firstLeg : firstLegFlights) {
      if (!isWithinDepartureWindow(firstLeg.getDepartureTimeUtc(), startOfDayUtc, endOfDayUtc)) {
        continue;
      }

      // ----- Optimize first -> second connection using a time window -----

      // Earliest possible second-leg departure, using the smallest min layover
      // as a lower bound. The exact requirement (domestic vs international)
      // is still enforced in isValidConnection().
      Instant earliestSecondDepartureUtc =
          firstLeg.getArrivalTimeUtc().plus(minDomesticLayover);

      int startIndexSecond =
          findFirstDepartureNotBefore(secondLegFlights, earliestSecondDepartureUtc);
      if (startIndexSecond < 0) {
        // No second leg departs late enough after this first leg.
        continue;
      }

      Instant firstArrivalUtc = firstLeg.getArrivalTimeUtc();

      // Only consider second legs within [earliestSecondDeparture, +maxLayover].
      for (int i = startIndexSecond; i < secondLegFlights.size(); i++) {
        Flight secondLeg = secondLegFlights.get(i);

        // Cheap upper-bound check in UTC to know when we can stop.
        Duration layover1Utc =
            Duration.between(firstArrivalUtc, secondLeg.getDepartureTimeUtc());
        if (layover1Utc.compareTo(maxLayover) > 0) {
          // Because list is sorted by departure time, all further flights
          // will have even longer layovers from this first leg.
          break;
        }

        // Precise validation in local time + domestic/international min layover.
        if (!isValidConnection(firstLeg, secondLeg)) {
          continue;
        }

        // Now connect second -> third using the same optimized helper.
        // previousLeg = firstLeg, so we build [firstLeg, secondLeg, thirdLeg].
        addValidConnections(secondLeg, thirdLegFlights, result, firstLeg);
      }
    }

    return result;
  }

  // ---------------------------------------------------------------------------
  // Connection helpers (optimized using sorted candidate flights)
  // ---------------------------------------------------------------------------

  /**
   * Add itineraries where 'firstLeg' connects to some leg in 'candidateNextLegs'
   * (used for one-stop itineraries).
   */
  private void addValidConnections(Flight firstLeg,
      List<Flight> candidateNextLegs,
      List<Itinerary> accumulator) {
    addValidConnections(firstLeg, candidateNextLegs, accumulator, null);
  }

  /**
   * Add itineraries where 'currentLeg' connects to some leg in 'candidateNextLegs'.
   * If 'previousLeg' is non-null, itineraries will be [previousLeg, currentLeg, nextLeg]
   * (two-stop case). Otherwise, [currentLeg, nextLeg] (one-stop case).
   *
   * This method assumes that candidateNextLegs are sorted by departureTimeUtc
   * (which is guaranteed by FlightRepositoryInMemory).
   */
  private void addValidConnections(Flight currentLeg,
      List<Flight> candidateNextLegs,
      List<Itinerary> accumulator,
      Flight previousLeg) {
    if (candidateNextLegs.isEmpty()) {
      return;
    }

    // Earliest possible next-leg departure using the smallest min layover.
    // The actual minimum might be larger (international), enforced by isValidConnection.
    Instant earliestNextDepartureUtc =
        currentLeg.getArrivalTimeUtc().plus(minDomesticLayover);

    int startIndex = findFirstDepartureNotBefore(candidateNextLegs, earliestNextDepartureUtc);
    if (startIndex < 0) {
      return; // no candidates depart late enough
    }

    Instant currentArrivalUtc = currentLeg.getArrivalTimeUtc();

    for (int i = startIndex; i < candidateNextLegs.size(); i++) {
      Flight nextLeg = candidateNextLegs.get(i);

      // Cheap upper bound in UTC: if layover > maxLayover, we can stop.
      Duration layoverUtc =
          Duration.between(currentArrivalUtc, nextLeg.getDepartureTimeUtc());
      if (layoverUtc.compareTo(maxLayover) > 0) {
        // Because list is sorted by departure time, all further flights will
        // have even longer layovers.
        break;
      }

      // Precise validation in local times + domestic/international rules.
      if (!isValidConnection(currentLeg, nextLeg)) {
        continue;
      }

      if (previousLeg == null) {
        // One-stop itinerary: [currentLeg, nextLeg]
        accumulator.add(Itinerary.ofLegs(currentLeg, nextLeg));
      } else {
        // Two-stop itinerary: [previousLeg, currentLeg, nextLeg]
        accumulator.add(Itinerary.ofLegs(previousLeg, currentLeg, nextLeg));
      }
    }
  }

  /**
   * Binary search helper: find the index of the first flight in 'flights'
   * whose departureTimeUtc is not before 'threshold'. Returns -1 if none.
   */
  private int findFirstDepartureNotBefore(List<Flight> flights, Instant threshold) {
    int lo = 0;
    int hi = flights.size() - 1;
    int result = -1;

    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      Instant departure = flights.get(mid).getDepartureTimeUtc();

      if (departure.isBefore(threshold)) {
        lo = mid + 1;
      } else {
        result = mid;
        hi = mid - 1;
      }
    }

    return result;
  }

  // ---------------------------------------------------------------------------
  // Layover rules & helpers
  // ---------------------------------------------------------------------------

  /**
   * Check whether the departure time is within the [start, end) UTC window.
   */
  private boolean isWithinDepartureWindow(Instant departure,
      Instant startInclusive,
      Instant endExclusive) {
    return !departure.isBefore(startInclusive) && departure.isBefore(endExclusive);
  }

  /**
   * Check if we can validly connect from 'first' to 'second' at the same airport.
   * Applies min/max layover rules and ensures time ordering.
   */
  private boolean isValidConnection(Flight first, Flight second) {
    // Must connect at the same airport.
    if (!first.getDestination().getCode().equals(second.getOrigin().getCode())) {
      return false;
    }

    // Local times at the connection airport.
    ZonedDateTime arrivalLocal = first.getArrivalAtDestinationLocal();
    ZonedDateTime departureLocal = second.getDepartureAtOriginLocal();

    if (!departureLocal.isAfter(arrivalLocal)) {
      // Cannot depart before or exactly at the arrival time.
      return false;
    }

    Duration layover = Duration.between(arrivalLocal, departureLocal);
    Duration minLayover = minLayover(first, second);

    if (layover.compareTo(minLayover) < 0) {
      // Too short.
      return false;
    }

    if (layover.compareTo(maxLayover) > 0) {
      // Too long.
      return false;
    }

    return true;
  }

  /**
   * Minimum layover based on domestic/international legs.
   * Assumption:
   *  - If both flights are domestic (origin.country == destination.country),
   *    we treat it as a domestic connection: use minDomesticLayover.
   *  - If either leg is international, we enforce minInternationalLayover.
   */
  private Duration minLayover(Flight first, Flight second) {
    boolean bothDomestic = isDomestic(first) && isDomestic(second);
    return bothDomestic ? minDomesticLayover : minInternationalLayover;
  }

  private boolean isDomestic(Flight flight) {
    String originCountry = flight.getOrigin().getCountry();
    String destinationCountry = flight.getDestination().getCountry();
    return originCountry != null
        && destinationCountry != null
        && originCountry.equalsIgnoreCase(destinationCountry);
  }

  // ---------------------------------------------------------------------------
  // Internal enum: path pattern strategies (polymorphic)
  // ---------------------------------------------------------------------------

  /**
   * Path pattern strategy based on number of legs.
   * Each enum constant knows how to build itineraries for its shape
   * by delegating to the appropriate helper method on the outer class.
   */
  private enum PathPattern {

    DIRECT(1) {
      @Override
      List<Itinerary> build(FlightSearchAlgorithm algo,
          List<String> airportPath,
          Instant startOfDayUtc,
          Instant endOfDayUtc) {
        return algo.buildDirectItineraries(airportPath, startOfDayUtc, endOfDayUtc);
      }
    },
    ONE_STOP(2) {
      @Override
      List<Itinerary> build(FlightSearchAlgorithm algo,
          List<String> airportPath,
          Instant startOfDayUtc,
          Instant endOfDayUtc) {
        return algo.buildOneStopItineraries(airportPath, startOfDayUtc, endOfDayUtc);
      }
    },
    TWO_STOP(3) {
      @Override
      List<Itinerary> build(FlightSearchAlgorithm algo,
          List<String> airportPath,
          Instant startOfDayUtc,
          Instant endOfDayUtc) {
        return algo.buildTwoStopItineraries(airportPath, startOfDayUtc, endOfDayUtc);
      }
    };

    private final int legs;

    PathPattern(int legs) {
      this.legs = legs;
    }

    abstract List<Itinerary> build(FlightSearchAlgorithm algo,
        List<String> airportPath,
        Instant startOfDayUtc,
        Instant endOfDayUtc);

    static Optional<PathPattern> fromLegCount(int legs) {
      for (PathPattern pattern : values()) {
        if (pattern.legs == legs) {
          return Optional.of(pattern);
        }
      }
      return Optional.empty();
    }
  }
}