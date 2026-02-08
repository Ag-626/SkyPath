# SkyPath
SkyPath: Flight Connection Search Engine

## Overview

SkyPath is a small end-to-end flight search system built around a fixed dataset (`flights.json`).  
Given an origin airport, destination airport, and a travel date, it finds **valid itineraries** with:

- Direct flights
- 1-stop connections
- 2-stop connections (maximum)

The backend is a Spring Boot service that:

- Loads the provided `flights.json` dataset into in-memory domain objects on startup
- Applies **connection rules** (min/max layovers, no airport changes)
- Handles **time zones** correctly by converting all internal times to UTC while still exposing local airport times in the API
- Exposes a simple REST API for flight search and airport lookup

The frontend is a React (TypeScript) single-page app that:

- Lets the user search by origin, destination, and date
- Shows the resulting itineraries (segments, layovers, total duration, total price)
- Handles loading, validation, empty results, and API errors in a user-friendly way

SkyPath is intentionally focused on **correctness**, **readable architecture**, and **clear trade-offs** rather than on production-grade scale or completeness. It is meant as a take-home exercise to demonstrate backend design, time-zone handling, search algorithms, and basic frontend UX.


## Tech Stack

**Backend**

- Java 17
- Spring Boot 3.5.x
- Maven
- Jackson (JSON parsing)
- SLF4J + Logback (logging)
- Spring Boot Actuator (basic health/metrics endpoints)

**Frontend**

- React 18
- TypeScript
- Vite (build/dev server)
- CSS (no heavy UI framework, just lightweight custom styling)

**Infrastructure / Tooling**

- Docker (multi-stage images for backend & frontend)
- Nginx (serving built frontend and proxying to backend in container)
- Docker Compose (orchestrates backend + frontend)
- JUnit 5 + Spring Boot Test (unit + integration tests)


## Project Structure

At the top level:

```text
SkyPath/
├── backend/                # Spring Boot application
│   ├── src/
│   │   ├── main/java/com/skypath/backend/
│   │   │   ├── domain/            # Core domain models (Airport, Flight, Itinerary, etc.)
│   │   │   ├── infrastructure/    # Dataset loading, JSON mappers, in-memory repositories, Flights Network
│   │   │   ├── algorithms/        # FlightSearchAlgorithm (core search logic)
│   │   │   ├── service/           # Orchestration services (FlightSearchService, etc.)
│   │   │   ├── controller/        # REST controllers
│   │   │   └── dto/               # API-facing DTOs
│   │   └── main/resources/
│   │       ├── application.properties
│   │       └── static/flights.json   # Provided dataset (loaded on startup)
│   ├── Dockerfile
│   └── pom.xml
│
├── frontend/               # React + TypeScript SPA
│   ├── src/
│   │   ├── App.tsx         # Main UI / search form / results rendering
│   │   ├── api.ts          # Typed API client for backend
│   │   ├── types.ts        # Shared DTO types
│   │   └── App.css         # Styling
│   ├── nginx.conf          # Nginx config used in Docker image
│   ├── Dockerfile
│   └── vite.config.ts
│
├── docker-compose.yml      # Runs backend + frontend together
└── README.md               # You are here 
```


## How to Run the Application

### Prerequisites

- Docker + Docker Compose (Docker Desktop is fine)
---
### Run Everything with Docker Compose (recommended)

From a terminal:

```bash
# 1) Clone the repository
git clone https://github.com/Ag-626/SkyPath.git

# 2) Go into the project root
cd SkyPath

# 3) Build and start both services
docker-compose up --build
```
Once both containers are up, open the frontend in your browser at: http://localhost:3000

To stop everything:
```bash
docker-compose down
```


## API Contract (request/response examples)

### 1. Search itineraries

**Endpoint**

```http
GET /api/flights/search?origin={IATA}&destination={IATA}&date={YYYY-MM-DD}

Example
Request: GET /api/flights/search?origin=JFK&destination=LAX&date=2024-03-15

Response: 200 OK
[
  {
    "segments": [
      {
        "flightNumber": "SP101",
        "airline": "SkyPath Airways",
        "originCode": "JFK",
        "originCity": "New York",
        "destinationCode": "LAX",
        "destinationCity": "Los Angeles",
        "departureTimeLocal": "2024-03-15T08:30:00-04:00",
        "arrivalTimeLocal": "2024-03-15T11:45:00-07:00",
        "aircraft": "A320",
        "price": 299.0
      }
    ],
    "layovers": [],
    "totalDurationMinutes": 375,
    "totalPrice": 299.0
  },
  {
    "segments": [
      {
        "flightNumber": "SP110",
        "airline": "SkyPath Airways",
        "originCode": "JFK",
        "originCity": "New York",
        "destinationCode": "ORD",
        "destinationCity": "Chicago",
        "departureTimeLocal": "2024-03-15T07:00:00-04:00",
        "arrivalTimeLocal": "2024-03-15T08:30:00-05:00",
        "aircraft": "A319",
        "price": 189.0
      },
      {
        "flightNumber": "SP120",
        "airline": "SkyPath Airways",
        "originCode": "ORD",
        "originCity": "Chicago",
        "destinationCode": "LAX",
        "destinationCity": "Los Angeles",
        "departureTimeLocal": "2024-03-15T09:15:00-05:00",
        "arrivalTimeLocal": "2024-03-15T11:30:00-07:00",
        "aircraft": "A320",
        "price": 225.0
      }
    ],
    "layovers": [
      {
        "airportCode": "ORD",
        "durationMinutes": 45
      }
    ],
    "totalDurationMinutes": 450,
    "totalPrice": 414.0
  }
]

Error responses
  • 400 Bad Request
    • Invalid date format (not YYYY-MM-DD)
    • Unknown origin or destination code
    • origin == destination
  • 500 Internal Server Error – unexpected server-side failure
```

### 2. List airports (for autocomplete)
**Endpoint**

```http
GET /api/airports

Example
Request: GET /api/airports

Response: 200 OK

[
  {
    "code": "JFK",
    "name": "John F. Kennedy International Airport",
    "city": "New York",
    "country": "United States"
  },
  {
    "code": "LAX",
    "name": "Los Angeles International Airport",
    "city": "Los Angeles",
    "country": "United States"
  }
  // ...
]
```

## Flight Search Algorithm

At a high level, the backend does **two things**:

1. Prepares an efficient in-memory view of the dataset on startup.
2. Runs a search over that in-memory graph for a given origin, destination, and date.

### 1. Pre-processing on startup

When the application starts:

- `flights.json` is parsed into domain objects:
    - `Airport` (code, city, country, `ZoneId`, etc.)
    - `Flight` (origin, destination, local departure/arrival, UTC timestamps, price, aircraft)
- Invalid records are **skipped with logging**:
    - Unknown airport codes
    - Invalid timezones or timestamps
    - Prices that can’t be parsed into `BigDecimal`
- A `FlightRepositoryInMemory` builds two key structures:
    - **Route index**: `Map<RouteKey, List<Flight>>`
        - `RouteKey` = `(originCode, destinationCode)`
        - Flights on each route are **sorted by departureTimeUtc**.
    - **Adjacency map**: `Map<String, Set<String>>`
        - For each origin airport → set of destination airports with at least one direct flight.
        - This is the “graph” of airports used for path search.

All of this stays in memory for fast read-side queries; there is no database.

### 2. Path search (up to 2 stops)

The core search logic lives in `FlightSearchAlgorithm`:

1. **Compute the UTC time window** for the requested date in the origin’s local timezone  
   (see *Connection Rules & Timezones* below).

2. **Find all airport paths** from origin to destination using the adjacency map:
    - Depth-first search (DFS) limited by `maxStops` (configurable, default **2**).
    - This yields airport sequences like:
        - Direct: `JFK → LAX`
        - One stop: `JFK → ORD → LAX`
        - Two stops: `JFK → ATL → DFW → LAX`
    - Cycles are prevented by tracking visited airports in the current path.

3. **For each airport path**, build time-feasible itineraries:
    - For each leg `(A → B)` in the path, look up flights from the route index:
        - `findByRoute("JFK", "LAX")` → list of flights sorted by departure UTC.
    - The algorithm chooses combinations of flights that:
        - Depart the **first leg** within the computed UTC window.
        - Respect all layover rules between legs (min/max layover, same airport).
    - The code uses the sorted route lists to **scan only the relevant window** of candidate flights, rather than N² over the entire dataset.

4. **Sort itineraries** before returning:
    - Fewer stops first
    - Then shorter total travel time
    - Then lower total price

The result is a set of itineraries that are:

- Reachable within at most 2 stops
- Valid w.r.t. layover rules
- Efficient to compute even as the dataset grows

### Why this algorithm scales (design & complexity)

I designed the search to stay efficient even if the dataset grows significantly, by separating
**graph exploration** (airports) from **time-window scans** (flights on a route).

1. **Graph is tiny and bounded by max stops**

    - Airport graph: `Map<String, Set<String>>` (origin → reachable destinations).
    - Path search uses DFS with a hard cap on stops (default: 2), so we only explore paths of
      length 1, 2, or 3 legs.
    - With `maxStops = 2`, the number of airport paths from A → B is small in practice, and
      does **not** grow with the number of flights per route.
    - This keeps the “combinatorial explosion” under control.

2. **Flights are indexed per route and sorted by departure time**

    - For each `(origin, destination)` pair I build:
      `Map<RouteKey, List<Flight>>`, where each list is sorted by `departureTimeUtc`.
    - When combining legs, I never scan all flights on a route blindly:
        - I binary-search or linearly scan from the first flight whose departure time is
          within the valid layover window.
        - That means for a busy route with thousands of flights, I only touch the subset
          that is actually relevant to the current connection.

3. **Overall complexity (informal)**

    - Let:
        - `P` = number of valid airport paths from origin to destination within `maxStops`
        - `F_r` = number of flights on a given route `(origin, destination)`
    - The cost is roughly:
        - `O(P)` to enumerate airport paths (bounded by small `maxStops`), plus
        - For each path, a time-windowed scan over the relevant route lists, instead of
          `O(F_r^2)` brute-force.
    - Because `P` is small and route lists are sorted, the algorithm behaves well even if
      the dataset per route grows.


## Connection Rules & Timezones

### Connection rules

The algorithm enforces the following rules for every connection between two flights:

| Rule                         | Description                                                                                   |
|------------------------------|-----------------------------------------------------------------------------------------------|
| Same airport                 | The arrival airport of leg *N* must equal the departure airport of leg *N+1*. No JFK → LGA. |
| Time ordering                | Next leg must depart **strictly after** the previous leg arrives (in local time).           |
| Min layover (domestic)       | **45 minutes** minimum when both legs are domestic (country codes match).                   |
| Min layover (international)  | **90 minutes** minimum if either leg is international.                                      |
| Max layover                  | **6 hours** maximum layover for any connection.                                             |

“Domestic” is determined per flight by comparing origin and destination country (case-insensitive).  
For a connection, if **both** legs are domestic, the 45-minute rule applies.  
If **either** leg crosses countries, the stricter 90-minute rule is used.

If a pair of flights violates any of these conditions, that combination is discarded.

### Timezone handling

The dataset stores times **in local airport time without offsets**:

```json
"departureTime" : "2024-03-15T08:30:00"
"arrivalTime":   "2024-03-15T11:45:00"
```
Each airport has a `timezone` field (e.g. `"America/New_York"`). The backend does:

1. **On load**
    - Parse departure/arrival local `LocalDateTime`.
    - Attach the appropriate `ZoneId` to get `ZonedDateTime` for origin/destination.
    - Convert to `Instant` for storage as UTC (`departureTimeUtc`, `arrivalTimeUtc`).

2. **Searching by date**
   - The client sends `date=YYYY-MM-DD` as a plain calendar date.
   - On the backend, this date is interpreted in the **origin airport’s timezone** only to build a UTC window:
       - `startOfDayUtc = [origin TZ, date at 00:00] → Instant`
       - `endOfDayUtc   = [origin TZ, date at 23:59:59.999...] → Instant`
   - All flights are stored internally with `departureTimeUtc` / `arrivalTimeUtc` (`Instant`).
   - The search filters **only on these UTC instants**:  
     any itinerary whose **first leg’s** `departureTimeUtc` falls within `[startOfDayUtc, endOfDayUtc)` is eligible.

3. **Layovers**
   - For connections, the backend works entirely in UTC as well:
       - `arrivalUtc   = previous.arrivalTimeUtc`
       - `departureUtc = next.departureTimeUtc`
       - `layover = Duration.between(arrivalUtc, departureUtc)`
   - This `Duration` is what is used to enforce:
       - minimum domestic / international layover rules, and
       - maximum layover of 6 hours.
   - Separately, for **presentation**, each leg’s local times are derived by applying the relevant airport `ZoneId` to the UTC instants, so the UI can show:
       - `departureTimeLocal`
       - `arrivalTimeLocal`
         including the correct offset (e.g. `"2024-03-15T08:30:00-04:00"`).

4. **Total duration**
   - Total travel time is also computed purely in UTC:
       - from the first leg’s `departureTimeUtc`
       - to the last leg’s `arrivalTimeUtc`
       - `totalDuration = Duration.between(first.departureTimeUtc, last.arrivalTimeUtc)`
   - This correctly handles:
       - date line crossings,
       - overnight flights,
       - and any situation where local arrival might appear “earlier” than local departure.
   - Local times with offsets are included in the response **only for display**, not for core time calculations.

All responses include **local times with offsets** (e.g. `"2024-03-15T08:30:00-04:00"`), so the frontend can display exactly what the passenger sees on their boarding pass.


## Assumptions & Trade-offs

- **Fixed, in-memory dataset**  
  Dataset is loaded from `static/flights.json` on startup and kept entirely in memory.  
  No persistence or live updates are supported (by design for this exercise).


- **Limited demo dates (2024-03-15 and 2024-03-16)**  
  The sample dataset only contains flights for **15 and 16 March 2024**.  
  For any other date, the backend returns an **empty list**.  
  The frontend surfaces a clear message so users know this demo is only populated for those two dates.


- **Max 2 stops (3 legs)**  
  The search only returns itineraries with at most 2 connections, matching the problem requirements.  
  This also keeps the DFS search space bounded and the responses small and easy to scan.


- **Domestic vs international**  
  A leg is “domestic” if `origin.country == destination.country` (case-insensitive).  
  For a connection:
    - both domestic → 45-minute minimum layover
    - otherwise → 90-minute minimum layover (stricter rule wins)  
      Itineraries are allowed to route through intermediate airports in **other countries**  
      (e.g. domestic origin and final destination, but 1 or 2 stops abroad).  
      Whenever a stop crosses country boundaries, the **90-minute** minimum layover is applied.
  

- **Time is stored in UTC, displayed in local time**  
  All business logic (search window, layovers, total duration) uses UTC instants.  
  Local times with offsets are computed only for display / API responses.

  
- **Simple pricing model**  
  Price is the sum of segment prices; no taxes, currency conversion, or fare rules.  
  Sorting favors fewer stops, shorter duration, then lower total price.


- **Frontend UX scope**  
  UI is intentionally minimal but covers:
    - validation and clear error messages
    - loading / empty states
    - basic autocomplete for airport codes  
      No authentication, caching, or advanced accessibility features are implemented.

## Future Improvements

This implementation focuses on clarity and correctness for the given dataset.  
If I had more time, I’d prioritise the following extensions:

- **Day-of-week–aware, multi-day search**  
  Today, all flights on a route are stored in a single list, and the sample data only covers 15–16 March.  
  In a more realistic world, flights are usually scheduled by weekday (e.g. Mon–Fri, weekends).  
  We could index flights as `Map<DayOfWeek, Map<RouteKey, List<Flight>>>`, so we can:
    - look up only the flights for the **correct day-of-week** of the first leg, and
    - for layovers, consider flights on the **same day-of-week or the next day-of-week** at the connection airport (to model overnight / next-day legs).  
      This makes multi-day and recurring schedules more accurate and keeps lookups efficient.
  

- **Configurable “domestic-only” routing**  
  Today, a journey between two airports in the same country is allowed to route via
  international airports (e.g. US → US via Canada). This is often fine for global
  networks, but some products might want to *forbid* any international legs for
  domestic trips.  
  A natural extension would be a toggle or configuration flag such as
  `allowInternationalHopsOnDomesticJourneys`:
    - `true` (current behaviour): domestic origin/destination may route via other countries
    - `false`: if origin and final destination are in the same country, **all legs**
      in the itinerary must also remain in that country
  

- **Caching hot searches**  
  Cache common queries (e.g. `JFK → LAX on a given date`) in-memory or via Redis with simple TTL-based eviction to avoid recomputing popular routes repeatedly.


- **Richer fare model**  
  Extend pricing beyond a simple sum of leg prices to include currencies, basic taxes/fees, and fare rules (e.g. refundable vs non-refundable), and expose these in sort/filter options.


- **Observability & tuning**  
  Add metrics (search latency, result counts, cache hit ratio), structured logs, and richer health checks (dataset loaded, indexes built).


- **Frontend UX: timezones & controls**  
  Improve UX with clearer visual cues for timezones and date changes (e.g. “+1 day” badges, tooltips explaining local vs UTC behaviour), and add client-side sorting/filtering (by price, duration, number of stops) on top of the API results.


- **Performance regression tests**  
  Introduce load tests on a synthetic large dataset to ensure search latency, memory usage, and throughput remain acceptable as data volume grows.


## Testing

The backend has a mix of unit and integration tests focused on:

- **Dataset loading & mapping**
    - `AirportRepositoryInMemoryTest`
    - `FlightRepositoryInMemoryTest`
    - `FlightJsonMapperTest`  
      These verify that `flights.json` is parsed correctly, invalid rows are skipped with
      warnings, timezones are resolved, and the in-memory indexes are built as expected.

- **Core search logic**
    - `FlightSearchAlgorithmTest`  
      Covers direct, 1-stop and 2-stop itineraries, layover rules (domestic vs international,
      min/max layover), and edge cases (no path, flights outside the search window, etc.).

- **End-to-end behaviour**
    - `FlightSearchServiceIntegrationTest`  
      Boots a Spring context, loads the real dataset, and asserts invariants on the returned
      itineraries (origin/destination, number of stops, total price, total duration,
      layover validity).

- **API layer**
    - `FlightSearchControllerTest`
    - `AirportControllerTest`  
      Ensure the REST endpoints validate input, call the right services, and return the
      expected JSON shapes and status codes.

To run all tests:

```bash
cd backend
./mvnw test
```

---

### 🎮 Sample searches to try

```md
## Sample Searches

A few concrete queries that exercise different parts of the algorithm and dataset:

- **Simple domestic direct + 1-stop options**
  - Origin: `JFK`
  - Destination: `LAX`
  - Date: `2024-03-15`  
  Demonstrates:
  - multiple direct flights,
  - 1-stop options via ORD / DFW / DEN / ATL,
  - sorting by total duration and then price.

- **International + date-line crossing**
  - Origin: `SYD`
  - Destination: `LAX`
  - Date: `2024-03-15`  
  Shows an example where local arrival appears “earlier” than local departure, but
  total duration (computed in UTC) is still correct.

- **Connection with different countries and international layover rule**
  - Origin: `DXB`
  - Destination: `JFK`
  - Date: `2024-03-15`  
  Uses legs like `DXB → LHR → JFK`, demonstrating the 90-minute minimum layover for
  international connections.

- **No results (demo limitation)**
  - Any route on a date other than `2024-03-15` or `2024-03-16`  
  The dataset only contains flights for these two days; the API will return an empty
  list and the UI will surface a clear “no itineraries” message.