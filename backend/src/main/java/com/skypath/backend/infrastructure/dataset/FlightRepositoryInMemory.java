package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Component
public class FlightRepositoryInMemory {

  private static final Logger log = LoggerFactory.getLogger(FlightRepositoryInMemory.class);

  /**
   * Immutable list of all valid flights loaded from the dataset.
   */
  private final List<Flight> flights;

  /**
   * Read-side index for efficient route-based lookups and
   * airport graph queries.
   */
  private final FlightNetworkIndex index;

  public FlightRepositoryInMemory(FlightsDatasetLoader datasetLoader,
      ObjectMapper objectMapper,
      AirportRepositoryInMemory airportRepository,
      FlightJsonMapper flightJsonMapper) {

    Map<String, Airport> airportsByCode = airportRepository.asMap();

    this.flights = loadFlights(datasetLoader, objectMapper, flightJsonMapper, airportsByCode);
    this.index = new FlightNetworkIndex(flights);

    log.info(
        "Loaded {} flights from dataset across {} routes and {} origin airports",
        flights.size(),
        index.getFlightsByRoute().size(),
        index.getRouteAdjacency().size()
    );
  }

  /**
   * Load and parse all flights from the dataset JSON.
   * Delegates JSON → Flight conversion to the FlightJsonMapper,
   * and fails fast if the JSON is structurally invalid or no
   * valid flights can be parsed.
   */
  private List<Flight> loadFlights(FlightsDatasetLoader loader,
      ObjectMapper objectMapper,
      FlightJsonMapper flightJsonMapper,
      Map<String, Airport> airportsByCode) {
    try (InputStream is = loader.openDatasetStream()) {
      JsonNode root = objectMapper.readTree(is);
      JsonNode flightsNode = root.get("flights");

      if (flightsNode == null || !flightsNode.isArray()) {
        log.error("Dataset missing 'flights' array in flights.json");
        throw new IllegalStateException("Dataset missing 'flights' array");
      }

      List<Flight> result = new java.util.ArrayList<>();

      for (JsonNode node : flightsNode) {
        flightJsonMapper.toFlight(node, airportsByCode).ifPresent(result::add);
      }

      if (result.isEmpty()) {
        log.error("No valid flights could be loaded from flights.json");
        throw new IllegalStateException("No valid flights found in dataset");
      }

      // Immutable snapshot
      return List.copyOf(result);

    } catch (IOException e) {
      throw new IllegalStateException("Failed to load flights from flights.json", e);
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /**
   * Return an immutable snapshot of all flights.
   */
  public List<Flight> findAll() {
    return flights;
  }

  /**
   * Return all flights for the given (origin, destination) route,
   * sorted by departureTimeUtc ascending.
   *
   * If there are no flights on that route, an empty list is returned.
   */
  public List<Flight> findByRoute(String originCode, String destinationCode) {
    return index.findByRoute(originCode, destinationCode);
  }

  /**
   * Expose a read-only view of the flight network graph:
   * for each origin airport code, the set of destination codes
   * that currently have at least one direct flight in the dataset.
   *
   * This is what we'll use to search paths like O→X→Y→D.
   */
  public Map<String, java.util.Set<String>> getRouteAdjacency() {
    return index.getRouteAdjacency();
  }

  /**
   * Expose the route index if more advanced algorithms need it.
   * The returned map and nested lists are all unmodifiable.
   */
  public Map<RouteKey, List<Flight>> getFlightsByRoute() {
    return index.getFlightsByRoute();
  }
}