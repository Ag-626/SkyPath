import type { ItineraryDto } from './types'

/**
 * Call the backend search API.
 * Throws an Error if the HTTP status is not 2xx.
 */
export async function searchFlights(
  origin: string,
  destination: string,
  date: string,
): Promise<ItineraryDto[]> {
  const params = new URLSearchParams({
    origin: origin.trim().toUpperCase(),
    destination: destination.trim().toUpperCase(),
    date: date.trim(),
  })

  const response = await fetch(`/api/flights/search?${params.toString()}`)

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    const message = text || `Request failed with status ${response.status}`
    throw new Error(message)
  }

  return (await response.json()) as ItineraryDto[]
}