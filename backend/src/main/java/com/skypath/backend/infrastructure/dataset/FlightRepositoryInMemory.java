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
import java.util.*;
import java.util.stream.Collectors;

@Component
public class FlightRepositoryInMemory {

  private static final Logger log = LoggerFactory.getLogger(FlightRepositoryInMemory.class);

  private final List<Flight> flights;
  private final Map<String, List<Flight>> flightsByOrigin; // handy for search later

  public FlightRepositoryInMemory(FlightsDatasetLoader datasetLoader,
      ObjectMapper objectMapper,
      AirportRepositoryInMemory airportRepository,
      FlightJsonMapper flightJsonMapper) {

    List<Flight> loadedFlights = loadFlights(datasetLoader, objectMapper, airportRepository.asMap(), flightJsonMapper);
    if (loadedFlights.isEmpty()) {
      log.error("No valid flights could be loaded from flights.json");
      throw new IllegalStateException("No valid flights found in dataset");
    }

    this.flights = Collections.unmodifiableList(loadedFlights);
    this.flightsByOrigin = Collections.unmodifiableMap(
        this.flights.stream()
            .collect(Collectors.groupingBy(
                f -> f.getOrigin().getCode(),
                Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList)
            ))
    );

    log.info("Loaded {} flights from dataset", this.flights.size());
  }

  private List<Flight> loadFlights(FlightsDatasetLoader loader,
      ObjectMapper objectMapper,
      Map<String, Airport> airportsByCode,
      FlightJsonMapper flightJsonMapper) {

    try (InputStream is = loader.openDatasetStream()) {
      JsonNode root = objectMapper.readTree(is);
      JsonNode flightsNode = root.get("flights");

      if (flightsNode == null || !flightsNode.isArray()) {
        log.error("Dataset missing 'flights' array in flights.json");
        throw new IllegalStateException("Dataset missing 'flights' array");
      }

      List<Flight> result = new ArrayList<>();

      for (JsonNode node : flightsNode) {
        flightJsonMapper.toFlight(node, airportsByCode)
            .ifPresent(result::add);
      }

      return result;

    } catch (IOException e) {
      throw new IllegalStateException("Failed to load flights from flights.json", e);
    }
  }

  // --- Public API we’ll use later ---

  public List<Flight> findAll() {
    return flights;
  }

  public List<Flight> findByOrigin(String originCode) {
    return flightsByOrigin.getOrDefault(originCode, List.of());
  }

  public List<Flight> findByOriginAndDestination(String originCode, String destinationCode) {
    return flightsByOrigin.getOrDefault(originCode, List.of()).stream()
        .filter(f -> f.getDestination().getCode().equals(destinationCode))
        .toList();
  }
}