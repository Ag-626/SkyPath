package com.skypath.backend.infrastructure.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skypath.backend.domain.Airport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Component
public class AirportRepositoryInMemory {

  private static final Logger log = LoggerFactory.getLogger(AirportRepositoryInMemory.class);

  private final Map<String, Airport> airportsByCode;

  public AirportRepositoryInMemory(FlightsDatasetLoader datasetLoader,
      ObjectMapper objectMapper,
      AirportJsonMapper airportJsonMapper) {
    this.airportsByCode = loadAirports(datasetLoader, objectMapper, airportJsonMapper);
    log.info("Loaded {} airports from dataset", airportsByCode.size());
  }

  private Map<String, Airport> loadAirports(FlightsDatasetLoader loader,
      ObjectMapper objectMapper,
      AirportJsonMapper airportJsonMapper) {
    try (InputStream is = loader.openDatasetStream()) {
      JsonNode root = objectMapper.readTree(is);
      JsonNode airportsNode = root.get("airports");

      if (airportsNode == null || !airportsNode.isArray()) {
        log.error("Dataset missing 'airports' array in flights.json");
        throw new IllegalStateException("Dataset missing 'airports' array");
      }

      Map<String, Airport> result = new HashMap<>();

      for (JsonNode node : airportsNode) {
        airportJsonMapper.toAirport(node).ifPresent(airport ->
            result.put(airport.getCode(), airport)
        );
      }

      if (result.isEmpty()) {
        log.error("No valid airports could be loaded from flights.json");
        throw new IllegalStateException("No valid airports found in dataset");
      }

      return Collections.unmodifiableMap(result);

    } catch (IOException e) {
      throw new IllegalStateException("Failed to load airports from flights.json", e);
    }
  }

  // --- Public API we’ll use later ---

  public Optional<Airport> findByCode(String code) {
    return Optional.ofNullable(airportsByCode.get(code));
  }

  public Collection<Airport> findAll() {
    return airportsByCode.values();
  }

  public Map<String, Airport> asMap() {
    return airportsByCode;
  }
}