// Data structures matching the backend JSON response

export interface FlightSegmentDto {
  flightNumber: string
  airline: string
  originCode: string
  originCity: string
  destinationCode: string
  destinationCity: string
  departureTimeLocal: string // ISO string with offset
  arrivalTimeLocal: string   // ISO string with offset
  aircraft: string
  price: number
}

export interface LayoverDto {
  airportCode: string
  durationMinutes: number
}

export interface ItineraryDto {
  segments: FlightSegmentDto[]
  layovers: LayoverDto[]
  totalDurationMinutes: number
  totalPrice: number
}

export interface AirportOption {
  code: string;
  city: string;
  name: string;
  country: string;
}