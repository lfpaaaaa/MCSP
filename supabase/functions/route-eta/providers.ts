// The routing services behind route-eta: where to send a trip and how to read the answer.

export type Mode = "WALK" | "TRANSIT" | "DRIVE";

export interface Point {
  lat: number;
  lng: number;
}

export interface Route {
  durationSeconds: number;
  distanceMeters: number;
}

/** What a routing service said about one trip. */
export type RoutingResult =
  | { kind: "found"; route: Route }
  | { kind: "no_route" }
  | { kind: "failed"; detail: string };

export interface RoutingProvider {
  /** The row in private.route_providers that spaces out calls to this service. */
  name: "osrm" | "transitous";
  url(origin: Point, destination: Point): string;
  read(status: number, body: unknown): RoutingResult;
}

// OSRM run by FOSSGIS for OpenStreetMap. Usage policy: at most one request per second, a valid
// User-Agent, and visible attribution with a "fix the map" link. https://routing.openstreetmap.de/about.html
const OSRM_BASE_URL = "https://routing.openstreetmap.de";

// Transitous plans journeys with open timetable data, including Transport Victoria's. It serves
// open-source, non-commercial projects that link to https://transitous.org/sources/.
// https://transitous.org/api/
const TRANSITOUS_PLAN_URL = "https://api.transitous.org/api/v6/plan";

const EARTH_RADIUS_METERS = 6_371_008.8;

export function providerFor(mode: Mode): RoutingProvider {
  switch (mode) {
    case "WALK":
      return osrm("routed-foot");
    case "DRIVE":
      return osrm("routed-car");
    case "TRANSIT":
      return transitous;
  }
}

function osrm(profile: string): RoutingProvider {
  return {
    name: "osrm",
    // OSRM expects longitude first. The server's profile is chosen by the path prefix.
    url: (origin, destination) =>
      `${OSRM_BASE_URL}/${profile}/route/v1/driving/` +
      `${origin.lng},${origin.lat};${destination.lng},${destination.lat}` +
      "?overview=false&alternatives=false&steps=false",
    read: readOsrmAnswer,
  };
}

const transitous: RoutingProvider = {
  name: "transitous",
  url: (origin, destination) => {
    const query = new URLSearchParams({
      fromPlace: `${origin.lat},${origin.lng}`,
      toPlace: `${destination.lat},${destination.lng}`,
      // Earliest arrival from now, without leg geometry: the lightest request that gives the time.
      timetableView: "false",
      directModes: "WALK",
      detailedLegs: "false",
      detailedTransfers: "false",
    });
    return `${TRANSITOUS_PLAN_URL}?${query}`;
  },
  read: readTransitousAnswer,
};

/** Reads an OSRM route answer (https://project-osrm.org/docs/v5.24.0/api/#route-service). */
export function readOsrmAnswer(status: number, body: unknown): RoutingResult {
  const answer = asRecord(body);
  const code = answer?.code;
  // NoRoute: the points are not connected. NoSegment: a point is too far from any road or path.
  if (code === "NoRoute" || code === "NoSegment") {
    return { kind: "no_route" };
  }
  const route = asRecord(asArray(answer?.routes)[0]);
  if (status !== 200 || code !== "Ok" || !route) {
    return { kind: "failed", detail: `OSRM answered ${status} ${String(code ?? "")}`.trim() };
  }
  return found(route.duration, route.distance);
}

/**
 * Reads a Transitous (MOTIS) plan answer. The quickest option wins, which is walking the whole way
 * (`direct`) when that beats every public transport connection.
 */
export function readTransitousAnswer(status: number, body: unknown): RoutingResult {
  const answer = asRecord(body);
  if (status !== 200 || !answer) {
    return { kind: "failed", detail: `Transitous answered ${status}` };
  }
  const options = [...asArray(answer.itineraries), ...asArray(answer.direct)]
    .map(asRecord)
    .filter((option): option is Record<string, unknown> => option !== null && isNonNegativeNumber(option.duration));
  if (options.length === 0) {
    return { kind: "no_route" };
  }
  const quickest = options.reduce((best, option) => (Number(option.duration) < Number(best.duration) ? option : best));
  return found(quickest.duration, journeyDistance(quickest));
}

function found(duration: unknown, distance: unknown): RoutingResult {
  if (!isNonNegativeNumber(duration) || !isNonNegativeNumber(distance)) {
    return { kind: "failed", detail: "The answer had no usable duration or distance" };
  }
  return { kind: "found", route: { durationSeconds: Math.ceil(duration), distanceMeters: Math.round(distance) } };
}

/**
 * Length of a journey in metres. Walking legs report their distance; public transport legs count
 * the straight line between the stops where they start and end.
 */
function journeyDistance(itinerary: Record<string, unknown>): number {
  return asArray(itinerary.legs).reduce<number>((total, value) => {
    const leg = asRecord(value);
    if (!leg) {
      return total;
    }
    return total + (isNonNegativeNumber(leg.distance) ? leg.distance : straightLineMeters(leg.from, leg.to));
  }, 0);
}

function straightLineMeters(from: unknown, to: unknown): number {
  const a = coordinates(from);
  const b = coordinates(to);
  if (!a || !b) {
    return 0;
  }
  const radians = (degrees: number) => (degrees * Math.PI) / 180;
  const h = Math.sin(radians(b.lat - a.lat) / 2) ** 2 +
    Math.cos(radians(a.lat)) * Math.cos(radians(b.lat)) * Math.sin(radians(b.lon - a.lon) / 2) ** 2;
  return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
}

function coordinates(place: unknown): { lat: number; lon: number } | null {
  const record = asRecord(place);
  const lat = record?.lat;
  const lon = record?.lon;
  return typeof lat === "number" && typeof lon === "number" ? { lat, lon } : null;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function isNonNegativeNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}
