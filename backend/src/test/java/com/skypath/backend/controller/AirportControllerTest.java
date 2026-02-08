package com.skypath.backend.controller;

import com.skypath.backend.domain.Airport;
import com.skypath.backend.infrastructure.dataset.AirportRepositoryInMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.ZoneId;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link AirportController}.
 *
 * This test:
 *  - Uses MockMvc in standalone mode (no full Spring context).
 *  - Mocks AirportRepositoryInMemory to control the returned airports.
 *  - Verifies sorting by code and basic field mapping in the JSON response.
 */
class AirportControllerTest {

  private AirportRepositoryInMemory airportRepository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    // Create a Mockito mock for the repository dependency.
    airportRepository = Mockito.mock(AirportRepositoryInMemory.class);

    // Wire the controller with the mock and build MockMvc around it.
    AirportController controller = new AirportController(airportRepository);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  /**
   * Happy-path test:
   *  - repository returns three airports in unsorted order,
   *  - controller should return them sorted by code (AMS, JFK, LAX),
   *  - JSON fields should match the mapped AirportSummaryDto.
   */
  @Test
  @DisplayName("GET /api/airports should return sorted airport summaries")
  void listAirports_shouldReturnSortedAirportSummaries() throws Exception {
    Airport lax = new Airport("LAX", "Los Angeles Intl", "Los Angeles", "USA", ZoneId.of("America/Los_Angeles"));
    Airport ams = new Airport("AMS", "Schiphol", "Amsterdam", "Netherlands", ZoneId.of("Europe/Amsterdam"));
    Airport jfk = new Airport("JFK", "John F. Kennedy Intl", "New York", "USA", ZoneId.of("America/New_York"));

    // Intentionally unsorted
    when(airportRepository.findAll()).thenReturn(List.of(lax, ams, jfk));

    mockMvc.perform(get("/api/airports"))
        .andExpect(status().isOk())
        // array size
        .andExpect(jsonPath("$.length()").value(3))
        // sorted by code: AMS, JFK, LAX
        .andExpect(jsonPath("$[0].code").value("AMS"))
        .andExpect(jsonPath("$[1].code").value("JFK"))
        .andExpect(jsonPath("$[2].code").value("LAX"))
        // verify a couple of other fields for mapping correctness
        .andExpect(jsonPath("$[0].city").value("Amsterdam"))
        .andExpect(jsonPath("$[0].name").value("Schiphol"))
        .andExpect(jsonPath("$[0].country").value("Netherlands"));
  }

  /**
   * Edge case:
   *  - repository returns no airports,
   *  - controller should return an empty JSON array.
   */
  @Test
  @DisplayName("GET /api/airports should return empty list when repository is empty")
  void listAirports_shouldReturnEmptyListWhenNoAirports() throws Exception {
    when(airportRepository.findAll()).thenReturn(List.of());

    mockMvc.perform(get("/api/airports"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}