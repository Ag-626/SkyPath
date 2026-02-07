package com.skypath.backend.infrastructure.dataset;

import com.skypath.backend.domain.Flight;

import java.util.*;

/**
 * Read-side index for flights, built once at startup from the
 * immutable list of Flight entities.
 *
 * Responsibilities:
 *  - Group flights by (origin, destination) route, sorted by departure time.
 *  - Build a simple flight network graph (route adjacency) so we can
 *    search airport paths like O→X→Y→D without touching flights first.
 *
 * This keeps FlightRepositoryInMemory focused on loading and exposing
 * data, while this class focuses on indexing and lookup strategies.
 */
public final class FlightNetworkIndex {

  /**
   * (originCode, destinationCode) -> unmodifiable list of flights
   * sorted by departureTimeUtc ascending.
   */
  private final Map<RouteKey, List<Flight>> flightsByRoute;

  /**
   * Flight network graph:
   * origin airport code -> set of destination airport codes that have
   * at least one direct flight in the dataset.
   */
  private final Map<String, Set<String>> routeAdjacency;

  public FlightNetworkIndex(List<Flight> flights) {
    this.flightsByRoute = indexFlightsByRoute(flights);
    this.routeAdjacency = buildRouteAdjacency(flights);
  }

  // ---------------------------------------------------------------------------
  // Route index: (origin, destination) -> sorted flights
  // ---------------------------------------------------------------------------

  private Map<RouteKey, List<Flight>> indexFlightsByRoute(List<Flight> flights) {
    Map<RouteKey, List<Flight>> groupedByRoute = groupFlightsByRoute(flights);
    sortFlightsByDeparture(groupedByRoute);
    return toUnmodifiableListMap(groupedByRoute);
  }

  private Map<RouteKey, List<Flight>> groupFlightsByRoute(List<Flight> flights) {
    Map<RouteKey, List<Flight>> grouped = new HashMap<>();

    for (Flight flight : flights) {
      RouteKey key = RouteKey.of(flight);
      grouped
          .computeIfAbsent(key, k -> new ArrayList<>())
          .add(flight);
    }

    return grouped;
  }

  private void sortFlightsByDeparture(Map<RouteKey, List<Flight>> groupedByRoute) {
    for (List<Flight> routeFlights : groupedByRoute.values()) {
      routeFlights.sort(Comparator.comparing(Flight::getDepartureTimeUtc));
    }
  }

  private Map<RouteKey, List<Flight>> toUnmodifiableListMap(Map<RouteKey, List<Flight>> mutable) {
    Map<RouteKey, List<Flight>> result = new HashMap<>();
    for (Map.Entry<RouteKey, List<Flight>> entry : mutable.entrySet()) {
      result.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Collections.unmodifiableMap(result);
  }

  // ---------------------------------------------------------------------------
  // Route adjacency: origin -> set of destinations
  // ---------------------------------------------------------------------------

  /**
   * Build a simple adjacency list of the flight network:
   * origin airport code -> set of destination codes with at least one direct flight.
   */
  private Map<String, Set<String>> buildRouteAdjacency(List<Flight> flights) {
    Map<String, Set<String>> groupedByOrigin = groupDestinationsByOrigin(flights);
    return toUnmodifiableSetMap(groupedByOrigin);
  }

  private Map<String, Set<String>> groupDestinationsByOrigin(List<Flight> flights) {
    Map<String, Set<String>> grouped = new HashMap<>();

    for (Flight flight : flights) {
      String originCode = flight.getOrigin().getCode();
      String destinationCode = flight.getDestination().getCode();

      grouped
          .computeIfAbsent(originCode, k -> new HashSet<>())
          .add(destinationCode);
    }

    return grouped;
  }

  private Map<String, Set<String>> toUnmodifiableSetMap(Map<String, Set<String>> mutable) {
    Map<String, Set<String>> result = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : mutable.entrySet()) {
      // defensive copy + unmodifiable set
      Set<String> copy = new HashSet<>(entry.getValue());
      result.put(entry.getKey(), Collections.unmodifiableSet(copy));
    }
    return Collections.unmodifiableMap(result);
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Return all flights for the given (origin, destination) route,
   * sorted by departureTimeUtc ascending.
   *
   * If there are no flights on that route, an empty list is returned.
   */
  public List<Flight> findByRoute(String originCode, String destinationCode) {
    RouteKey key = new RouteKey(originCode, destinationCode);
    List<Flight> routeFlights = flightsByRoute.get(key);
    return routeFlights != null ? routeFlights : List.of();
  }

  /**
   * Expose a read-only view of the flight network graph:
   * origin airport code -> set of destination codes that have
   * at least one direct flight in the dataset.
   */
  public Map<String, Set<String>> getRouteAdjacency() {
    return routeAdjacency;
  }

  /**
   * Expose the full route index. The map and nested lists are
   * all unmodifiable snapshots.
   */
  public Map<RouteKey, List<Flight>> getFlightsByRoute() {
    return flightsByRoute;
  }
}

/**
 * Value object representing a direct route between two airports.
 * Used as a key in the flightsByRoute index.
 */
record RouteKey(String originCode, String destinationCode) {

  static RouteKey of(Flight flight) {
    return new RouteKey(
        flight.getOrigin().getCode(),
        flight.getDestination().getCode()
    );
  }
}