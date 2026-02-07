package com.skypath.backend.service;

import com.skypath.backend.algorithms.FlightSearchAlgorithm;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.infrastructure.dataset.AirportRepositoryInMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Application service responsible for orchestrating a flight search:
 *  - validates input,
 *  - resolves airports and timezones,
 *  - computes the UTC departure window for the requested date,
 *  - delegates the actual search to FlightSearchAlgorithm.
 *
 * If the algorithm changes in the future, only FlightSearchAlgorithm
 * needs to be updated; this service can stay stable.
 */
@Service
public class FlightSearchService {

  private static final Logger log = LoggerFactory.getLogger(FlightSearchService.class);

  private final AirportRepositoryInMemory airportRepository;
  private final FlightSearchAlgorithm flightSearchAlgorithm;

  public FlightSearchService(AirportRepositoryInMemory airportRepository,
      FlightSearchAlgorithm flightSearchAlgorithm) {
    this.airportRepository = airportRepository;
    this.flightSearchAlgorithm = flightSearchAlgorithm;
  }

  /**
   * Find all itineraries from originCode to destinationCode on the given
   * travelDate (interpreted in the origin airport's local timezone).
   *
   * This method does not throw when no flights exist: it returns an empty list.
   */
  public List<Itinerary> search(String originCode,
      String destinationCode,
      LocalDate travelDate) {

    Objects.requireNonNull(originCode, "originCode must not be null");
    Objects.requireNonNull(destinationCode, "destinationCode must not be null");
    Objects.requireNonNull(travelDate, "travelDate must not be null");

    Airport origin = airportRepository.findByCode(originCode)
        .orElseThrow(() -> new IllegalArgumentException("Unknown origin airport: " + originCode));
    Airport destination = airportRepository.findByCode(destinationCode)
        .orElseThrow(() -> new IllegalArgumentException("Unknown destination airport: " + destinationCode));

    if (originCode.equals(destinationCode)) {
      log.info("Origin and destination are the same ({}), returning empty result", originCode);
      return List.of();
    }

    // Compute the UTC window for the given local date at the origin.
    Instant startOfDayUtc = startOfDayUtc(origin, travelDate);
    Instant endOfDayUtc = endOfDayUtc(origin, travelDate);

    // Delegate the heavy lifting to the algorithm component.
    return flightSearchAlgorithm.search(originCode, destinationCode, startOfDayUtc, endOfDayUtc);
  }

  /**
   * Convert the given travelDate (in origin's local timezone) into
   * the UTC instant at the start of that day.
   */
  private Instant startOfDayUtc(Airport origin, LocalDate travelDate) {
    ZonedDateTime startLocal = travelDate.atStartOfDay(origin.getTimezone());
    return startLocal.toInstant();
  }

  /**
   * Convert the given travelDate (in origin's local timezone) into
   * the UTC instant at the start of the next day. We treat the day
   * window as [startOfDay, nextDayStart).
   */
  private Instant endOfDayUtc(Airport origin, LocalDate travelDate) {
    ZonedDateTime nextDayStartLocal = travelDate.plusDays(1).atStartOfDay(origin.getTimezone());
    return nextDayStartLocal.toInstant();
  }
}