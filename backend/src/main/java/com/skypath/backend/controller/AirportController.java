package com.skypath.backend.controller;

import com.skypath.backend.dto.AirportSummaryDto;
import com.skypath.backend.domain.Airport;
import com.skypath.backend.infrastructure.dataset.AirportRepositoryInMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/airports")
public class AirportController {

  private final AirportRepositoryInMemory airportRepository;

  public AirportController(AirportRepositoryInMemory airportRepository) {
    this.airportRepository = airportRepository;
  }

  @GetMapping
  public List<AirportSummaryDto> listAirports() {
    return airportRepository.findAll().stream()
        .sorted(Comparator.comparing(Airport::getCode))
        .map(a -> new AirportSummaryDto(
            a.getCode(),
            a.getCity(),
            a.getName(),
            a.getCountry()
        ))
        .toList();
  }
}