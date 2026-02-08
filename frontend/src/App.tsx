import React, { useEffect, useMemo, useState } from 'react';
import { searchFlights } from './api';
import type {
  ItineraryDto,
  FlightSegmentDto,
  AirportOption,
} from './types';

import './App.css';

function formatLocalDateTime(iso: string): string {
  if (!iso) return '';

  const [datePart, timeAndOffset] = iso.split('T');
  if (!datePart || !timeAndOffset) return iso;

  const [yearStr, monthStr, dayStr] = datePart.split('-');
  const [hourStr, minuteStr] = timeAndOffset.substring(0, 5).split(':'); // "HH:mm"

  const year = Number(yearStr);
  const month = Number(monthStr);
  const day = Number(dayStr);

  if (!year || !month || !day) return iso;

  const monthNames = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];

  const monthLabel = monthNames[month - 1] ?? monthStr;

  return `${day} ${monthLabel}, ${hourStr}:${minuteStr}`;
}

function formatDurationMinutes(totalMinutes: number): string {
  if (!Number.isFinite(totalMinutes) || totalMinutes < 0) return '-';
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) {
    return `${minutes} min`;
  }
  if (minutes === 0) {
    return `${hours} h`;
  }
  return `${hours} h ${minutes} min`;
}

function formatCurrency(amount: number): string {
  if (!Number.isFinite(amount)) return '-';
  return amount.toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 2,
  });
}

// 0 = direct, 1 = one stop, 2 = two stops
type StopsTab = 0 | 1 | 2;

// Helper to filter airport suggestions
function filterAirportSuggestions(
  airports: AirportOption[],
  input: string,
): AirportOption[] {
  const q = input.trim().toLowerCase();
  if (!q) return [];

  return airports
    .filter((a) => {
      const code = a.code.toLowerCase();
      const city = a.city.toLowerCase();
      const name = a.name.toLowerCase();
      return (
        code.startsWith(q) ||
        city.includes(q) ||
        name.includes(q)
      );
    })
    .slice(0, 6);
}

const App: React.FC = () => {
  const [origin, setOrigin] = useState('');
  const [destination, setDestination] = useState('');
  const [dateInput, setDateInput] = useState('15/03/2024'); // dd/MM/yyyy
  const [itineraries, setItineraries] = useState<ItineraryDto[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  const [activeStopsTab, setActiveStopsTab] = useState<StopsTab>(0);

  // Airports + autocomplete state
  const [airports, setAirports] = useState<AirportOption[]>([]);
  const [showOriginSuggestions, setShowOriginSuggestions] = useState(false);
  const [showDestinationSuggestions, setShowDestinationSuggestions] =
    useState(false);

  // Load airports once on mount
  useEffect(() => {
    (async () => {
      try {
        const response = await fetch('/api/airports');
        if (!response.ok) {
          console.warn(
            'Failed to load airports list, status:',
            response.status,
          );
          return;
        }
        const data = (await response.json()) as AirportOption[];
        setAirports(data);
      } catch (e) {
        console.error('Failed to load airports list', e);
      }
    })();
  }, []);

  const originSuggestions = useMemo(
    () => filterAirportSuggestions(airports, origin),
    [airports, origin],
  );

  const destinationSuggestions = useMemo(
    () => filterAirportSuggestions(airports, destination),
    [airports, destination],
  );

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);
    setError(null);

    const trimmedOrigin = origin.trim().toUpperCase();
    const trimmedDestination = destination.trim().toUpperCase();

    // Basic required fields check
    if (!trimmedOrigin || !trimmedDestination || !dateInput.trim()) {
      setError('Please enter origin, destination, and date.');
      setItineraries([]);
      return;
    }

    if (trimmedOrigin.length !== 3 || trimmedDestination.length !== 3) {
      setError(
        'Origin and destination must be 3-letter IATA codes (e.g., JFK, LAX).',
      );
      setItineraries([]);
      return;
    }

    // Prevent same origin & destination
    if (trimmedOrigin === trimmedDestination) {
      setError('Origin and destination must be different airports.');
      setItineraries([]);
      return;
    }

    // Parse dd/MM/yyyy -> yyyy-MM-dd string (what Spring expects)
    const parts = dateInput.split('/');
    if (parts.length !== 3) {
      setError('Please enter date in DD/MM/YYYY format (e.g. 15/03/2024).');
      setItineraries([]);
      return;
    }

    const [ddStr, mmStr, yyyyStr] = parts;

    // Basic shape check
    if (
      ddStr.length !== 2 ||
      mmStr.length !== 2 ||
      yyyyStr.length !== 4
    ) {
      setError('Please enter date in DD/MM/YYYY format (e.g. 15/03/2024).');
      setItineraries([]);
      return;
    }

    const day = Number(ddStr);
    const month = Number(mmStr);
    const year = Number(yyyyStr);

    // Numeric validity check
    if (
      !Number.isInteger(day) ||
      !Number.isInteger(month) ||
      !Number.isInteger(year) ||
      day < 1 ||
      day > 31 ||
      month < 1 ||
      month > 12
    ) {
      setError('Please enter date in DD/MM/YYYY format (e.g. 15/03/2024).');
      setItineraries([]);
      return;
    }

    // Optional: limit to dataset window
    if (year !== 2024 || month !== 3 || (day !== 15 && day !== 16)) {
      setError(
        'This demo only has flights for 15/03/2024 and some arrivals on 16/03/2024.',
      );
      setItineraries([]);
      return;
    }

    const backendDate = `${yyyyStr}-${mmStr}-${ddStr}`;

    setLoading(true);
    try {
      const result = await searchFlights(
        trimmedOrigin,
        trimmedDestination,
        backendDate,
      );
      setItineraries(result);

      if (result.length === 0) {
        setError('No itineraries found for that route and date.');
      } else {
        // Choose default tab: first stops-count that actually has results
        const hasDirect = result.some((it) => it.segments.length === 1);
        const hasOneStop = result.some((it) => it.segments.length === 2);
        const hasTwoStop = result.some((it) => it.segments.length === 3);

        if (hasDirect) {
          setActiveStopsTab(0);
        } else if (hasOneStop) {
          setActiveStopsTab(1);
        } else if (hasTwoStop) {
          setActiveStopsTab(2);
        }
      }
    } catch (err: any) {
      console.error(err);
      setError(
        err?.message ??
          'Something went wrong while searching flights. Please try again.',
      );
      setItineraries([]);
    } finally {
      setLoading(false);
    }
  };

  const showValidationError =
    touched && (!origin.trim() || !destination.trim() || !dateInput.trim());

  // Pre-compute counts by stops and filtered list for active tab
  const countByStops: Record<StopsTab, number> = {
    0: itineraries.filter((it) => it.segments.length === 1).length,
    1: itineraries.filter((it) => it.segments.length === 2).length,
    2: itineraries.filter((it) => it.segments.length === 3).length,
  };

  const filteredItineraries = itineraries.filter(
    (it) => it.segments.length - 1 === activeStopsTab,
  );

  return (
    <div className="app-root">
      <main className="app-main">
        <header className="app-header">
          <h1 className="app-title">SkyPath Flight Search</h1>
          <p className="app-subtitle">
            Search itineraries with up to 2 stops, using the provided flights
            dataset.
          </p>
        </header>

        {/* Search Form */}
        <section className="card">
          <form className="form" onSubmit={handleSearch}>
            <div className="form-row">
              <div className="form-field autocomplete-field">
                <label htmlFor="origin">Origin</label>
                <input
                  id="origin"
                  type="text"
                  maxLength={3}
                  placeholder="e.g. JFK"
                  value={origin}
                  onChange={(e) => {
                    setOrigin(e.target.value.toUpperCase());
                    setShowOriginSuggestions(true);
                  }}
                  onFocus={() => setShowOriginSuggestions(true)}
                  onBlur={() =>
                    setTimeout(
                      () => setShowOriginSuggestions(false),
                      100,
                    )
                  }
                />
                {showOriginSuggestions &&
                  originSuggestions.length > 0 && (
                    <ul className="suggestions-list">
                      {originSuggestions.map((a) => (
                        <li
                          key={a.code}
                          className="suggestion-item"
                          // onMouseDown fires before input blur
                          onMouseDown={() => {
                            setOrigin(a.code.toUpperCase());
                            setShowOriginSuggestions(false);
                          }}
                        >
                          <span className="suggest-code">{a.code}</span>
                          <span className="suggest-main">
                            {a.city} · {a.name}
                          </span>
                          <span className="suggest-country">
                            {a.country}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
              </div>

              <div className="form-field autocomplete-field">
                <label htmlFor="destination">Destination</label>
                <input
                  id="destination"
                  type="text"
                  maxLength={3}
                  placeholder="e.g. LAX"
                  value={destination}
                  onChange={(e) => {
                    setDestination(e.target.value.toUpperCase());
                    setShowDestinationSuggestions(true);
                  }}
                  onFocus={() => setShowDestinationSuggestions(true)}
                  onBlur={() =>
                    setTimeout(
                      () => setShowDestinationSuggestions(false),
                      100,
                    )
                  }
                />
                {showDestinationSuggestions &&
                  destinationSuggestions.length > 0 && (
                    <ul className="suggestions-list">
                      {destinationSuggestions.map((a) => (
                        <li
                          key={a.code}
                          className="suggestion-item"
                          onMouseDown={() => {
                            setDestination(a.code.toUpperCase());
                            setShowDestinationSuggestions(false);
                          }}
                        >
                          <span className="suggest-code">{a.code}</span>
                          <span className="suggest-main">
                            {a.city} · {a.name}
                          </span>
                          <span className="suggest-country">
                            {a.country}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
              </div>
            </div>

            <div className="form-row">
              <div className="form-field">
                <label htmlFor="date">Date (DD/MM/YYYY)</label>
                <input
                  id="date"
                  type="text"
                  placeholder="DD/MM/YYYY"
                  value={dateInput}
                  onChange={(e) => setDateInput(e.target.value)}
                />
              </div>
            </div>

            {showValidationError && (
              <p className="error-text">
                Please fill in all fields before searching.
              </p>
            )}

            {error && <p className="error-text">{error}</p>}

            <button className="primary-button" type="submit" disabled={loading}>
              {loading ? 'Searching…' : 'Search flights'}
            </button>
          </form>
        </section>

        {/* Results */}
        <section className="results-section">
          {loading && <p className="info-text">Loading itineraries…</p>}

          {!loading && itineraries.length === 0 && !error && (
            <p className="info-text">
              Enter an origin, destination and date, then click "Search
              flights".
            </p>
          )}

          {!loading && itineraries.length > 0 && (
            <>
              <p className="info-text">
                All times are shown in the local time of each airport. Total
                duration already accounts for time zones and date changes.
              </p>

              {/* Tabs for Direct / 1 stop / 2 stops */}
              <div className="tabs">
                {( [0, 1, 2] as StopsTab[] ).map((stops) => (
                  <button
                    key={stops}
                    type="button"
                    className={
                      'tab-button' +
                      (activeStopsTab === stops ? ' tab-button-active' : '') +
                      (countByStops[stops] === 0
                        ? ' tab-button-disabled'
                        : '')
                    }
                    disabled={countByStops[stops] === 0}
                    onClick={() => setActiveStopsTab(stops)}
                  >
                    {stops === 0
                      ? 'Direct'
                      : stops === 1
                      ? '1 stop'
                      : '2 stops'}{' '}
                    <span className="tab-count">
                      ({countByStops[stops]})
                    </span>
                  </button>
                ))}
              </div>

              {filteredItineraries.length === 0 && (
                <p className="info-text">
                  No itineraries found for this stop type. Try another tab.
                </p>
              )}
            </>
          )}

          {!loading &&
            filteredItineraries.map((itinerary, index) => (
              <article className="card itinerary-card" key={index}>
                <header className="itinerary-header">
                  <div className="itinerary-title">
                    <span className="itinerary-label">
                      Option {index + 1} ·{' '}
                      {itinerary.segments.length === 1
                        ? 'Direct'
                        : `${itinerary.segments.length - 1} stop${
                            itinerary.segments.length > 2 ? 's' : ''
                          }`}
                    </span>
                    <span className="itinerary-meta">
                      Total duration:{' '}
                      <span className="itinerary-meta-strong">
                        {formatDurationMinutes(
                          itinerary.totalDurationMinutes,
                        )}
                      </span>
                      {' · '}
                      Total price:{' '}
                      <strong className="itinerary-meta-strong">
                        {formatCurrency(itinerary.totalPrice)}
                      </strong>
                    </span>
                  </div>
                </header>

                <div className="itinerary-body">
                  {itinerary.segments.map(
                    (segment: FlightSegmentDto, segIndex: number) => (
                      <div className="segment-row" key={segIndex}>
                        <div className="segment-main">
                          <div className="segment-route">
                            <span className="airport-code">
                              {segment.originCode}
                            </span>
                            <span className="route-arrow">→</span>
                            <span className="airport-code">
                              {segment.destinationCode}
                            </span>
                          </div>
                          <div className="segment-times">
                            {formatLocalDateTime(
                              segment.departureTimeLocal,
                            )}{' '}
                            →{' '}
                            {formatLocalDateTime(
                              segment.arrivalTimeLocal,
                            )}
                          </div>
                          <div className="segment-meta">
                            {segment.airline} · {segment.flightNumber} ·{' '}
                            {segment.aircraft}
                          </div>
                        </div>
                        <div className="segment-price">
                          {formatCurrency(segment.price)}
                        </div>
                      </div>
                    ),
                  )}

                  {itinerary.layovers.length > 0 && (
                    <div className="layovers-row">
                      {itinerary.layovers.map((layover, idx) => (
                        <span className="layover-chip" key={idx}>
                          Layover at {layover.airportCode} ·{' '}
                          {formatDurationMinutes(
                            layover.durationMinutes,
                          )}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </article>
            ))}
        </section>
      </main>
    </div>
  );
};

export default App;