package com.skypath.backend.controller;

import com.skypath.backend.domain.Airport;
import com.skypath.backend.domain.Flight;
import com.skypath.backend.domain.Itinerary;
import com.skypath.backend.service.FlightSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Standalone MVC test for FlightSearchController.
 *
 * This test:
 *  - builds a MockMvc instance using MockMvcBuilders.standaloneSetup(...)
 *  - uses a plain Mockito mock for FlightSearchService (no @MockBean)
 *  - verifies request mapping, parameter binding, and JSON response shape.
 */
class FlightSearchControllerTest {

  private MockMvc mockMvc;
  private FlightSearchService flightSearchService;

  @BeforeEach
  void setUp() {
    // Pure Mockito mock – no Spring @MockBean, so no deprecation warnings
    this.flightSearchService = Mockito.mock(FlightSearchService.class);

    // Wire the controller manually with the mocked service
    FlightSearchController controller = new FlightSearchController(flightSearchService);

    // Build a standalone MockMvc instance around this controller
    this.mockMvc = MockMvcBuilders.standaloneSetup(controller)
        .build();
  }

  // ---------------------------------------------------------------------------
  // Happy path: valid query parameters -> 200 + well-structured JSON
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/flights/search with valid params should return itineraries JSON")
  void searchFlights_shouldReturnItinerariesJson() throws Exception {
    // Given
    String originCode = "AAA";
    String destinationCode = "BBB";
    LocalDate travelDate = LocalDate.of(2024, 3, 15);

    // Use UTC for both airports so local times are easy/predictable
    Airport originAirport = new Airport(
        originCode,
        "Origin Airport",
        "Origin City",
        "Origin Country",
        ZoneId.of("UTC")
    );

    Airport destinationAirport = new Airport(
        destinationCode,
        "Destination Airport",
        "Destination City",
        "Destination Country",
        ZoneId.of("UTC")
    );

    Instant departureUtc = Instant.parse("2024-03-15T08:00:00Z");
    Instant arrivalUtc = Instant.parse("2024-03-15T12:00:00Z");

    // Adapted to your Flight constructor ordering:
    // (flightNumber, airline, origin, destination, departureUtc, arrivalUtc, price, aircraft)
    Flight flight = new Flight(
        "SP001",
        "SkyPath Airways",
        originAirport,
        destinationAirport,
        departureUtc,
        arrivalUtc,
        new BigDecimal("199.00"),
        "A320"
    );

    Itinerary itinerary = Itinerary.ofSingleLeg(flight);

    Mockito.when(flightSearchService.search(originCode, destinationCode, travelDate))
        .thenReturn(List.of(itinerary));

    // When / Then
    mockMvc.perform(get("/api/flights/search")
            .param("origin", originCode)
            .param("destination", destinationCode)
            .param("date", "2024-03-15"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        // top-level: array of itineraries
        .andExpect(jsonPath("$.length()").value(1))
        // first itinerary basic fields
        .andExpect(jsonPath("$[0].segments.length()").value(1))
        .andExpect(jsonPath("$[0].layovers").isArray())
        .andExpect(jsonPath("$[0].totalDurationMinutes").isNumber())
        .andExpect(jsonPath("$[0].totalPrice").isNumber())
        // segment mapping checks
        .andExpect(jsonPath("$[0].segments[0].flightNumber").value("SP001"))
        .andExpect(jsonPath("$[0].segments[0].airline").value("SkyPath Airways"))
        .andExpect(jsonPath("$[0].segments[0].originCode").value(originCode))
        .andExpect(jsonPath("$[0].segments[0].destinationCode").value(destinationCode))
        .andExpect(jsonPath("$[0].segments[0].aircraft").value("A320"));

    // Verify service interaction
    verify(flightSearchService).search(eq(originCode), eq(destinationCode), eq(travelDate));
    verifyNoMoreInteractions(flightSearchService);
  }

  // ---------------------------------------------------------------------------
  // Validation: missing required parameters -> 400 Bad Request
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("GET /api/flights/search without destination should return 400")
  void searchFlights_missingDestination_shouldReturnBadRequest() throws Exception {
    mockMvc.perform(get("/api/flights/search")
            .param("origin", "JFK")
            // .param("destination", "LAX") // missing on purpose
            .param("date", "2024-03-15"))
        .andExpect(status().isBadRequest());

    verifyNoMoreInteractions(flightSearchService);
  }

  @Test
  @DisplayName("GET /api/flights/search with invalid date should return 400")
  void searchFlights_invalidDateFormat_shouldReturnBadRequest() throws Exception {
    mockMvc.perform(get("/api/flights/search")
            .param("origin", "JFK")
            .param("destination", "LAX")
            .param("date", "15-03-2024")) // invalid ISO date
        .andExpect(status().isBadRequest());

    verifyNoMoreInteractions(flightSearchService);
  }
}