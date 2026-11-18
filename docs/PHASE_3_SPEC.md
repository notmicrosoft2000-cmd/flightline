# Phase 3A — Navigation Database + Flight Planner

## Goal

Give pilots a reason to fly somewhere: filed routes, known fixes, ATC clearance, and a navigation display that guides them there. This is the first release in the **Living Airspace** arc.

Target version: `0.1.0-alpha.12`, published as `Alpha-V12`.

## 1. Shared navigation database

Add `NavDatabase` under `src/main/java/dev/flightline/flight/`.

- Reuse the deterministic `RunwayGenerator` site logic on its 8192-block grid; do not introduce separate worldgen.
- Generate a finite set of airports around spawn: airport ID/name, center, runway IDs.
- Generate runway records: threshold X/Z, heading, reciprocal heading, ILS course, glide slope.
- Generate fixes: waypoint/VOR/NDB records with ID and X/Z.
- Names are derived from IDs so the payload does not carry large strings.

The server owns the database. Clients receive a cached snapshot.

## 2. Flight plan model

Add `FlightPlan`:

- origin airport ID
- destination airport ID
- waypoint ID list
- cruise altitude
- alternate airport ID
- status: `DRAFT`, `FILED`, `CLEARED`, `ACTIVE`, `ARRIVAL`
- active leg index
- assigned departure/arrival runway IDs
- revision number

Validation rules:

- airports and fixes must exist in the database
- at most 16 waypoints
- cruise altitude 100–40,000 ft
- alternate must be a different airport
- no duplicate consecutive fixes
- ATC can reject with a radio message

## 3. Networking

Keep `FlightInstrumentPayload` at 12 top-level fields and `WeatherGuidance` at 9. Add three small payload types:

1. `NavDataPayload` — server → client, once on join/world reload.
   - version, spawn X/Z, airports, runways, fixes.
2. `FileFlightPlanPayload` — client → server.
   - origin, destination, waypoint IDs, cruise altitude, alternate.
3. `FlightPlanPayload` — server → client.
   - revision, status, active leg, assigned runways, plan contents.
   - sent on change plus a low-rate refresh.

No emergency-state authority changes. Operator-only injection stays operator-only.

## 4. Flight deck UI

Add an `FPLN` tab to `AutopilotScreen` and reflow existing tabs.

Controls:

- ORIGIN / DEST cycle buttons
- waypoint list with ADD / REMOVE
- cruise altitude stepper
- alternate selector
- FILE, REQUEST CLR, CANCEL, LNAV, ILS APP, and SYNC ALT buttons
- status line showing plan state and ATC response

Keep all existing keybinds. Open the planner through `H`; no new key required.

## 5. Navigation display

Extend `FlightHud` ND rendering with route overlays:

- route line from ownship through remaining fixes to destination
- active leg highlighted
- waypoint diamonds with ID labels
- distance and bearing to active fix
- cross-track error
- ETE from ground speed

Add a `ROUTE` overlay toggle to `WeatherMapScreen`, reusing the same route polyline renderer.

## 6. Autopilot behavior

Add server-side nav modes: `HDG`, `LNAV`, `ILS`.

- `HDG`: existing behavior.
- `LNAV`: steer toward active fix, auto-sequence on capture radius, advance leg, then hand off at final approach.
- `ILS`: auto-tune threshold/heading/glide slope from the destination runway database entry, then use existing ILS capture and autoland logic.
- `SYNC ALT` loads the filed cruise altitude into the MCP without changing the active nav mode. Full VNAV constraints come later.

No new plane entities. All of this remains server-authoritative.

## 7. ATC integration

- On FILE, ATC validates the plan.
- On clearance, ATC assigns departure and arrival runways.
- Departure runway chosen from `WeatherField` wind at the airport: maximize headwind component.
- Clearance radio text via existing `AtcRadioPayload`.
- Existing `F7` ACK flow acknowledges clearance.

## 8. Persistence

Move ILS and flight-plan state out of session-only memory.

- Add server `SavedData` keyed by player UUID.
- Persist current plan, status, active leg, assigned runways, ILS reference, and autoland armed state.
- This also fixes the audit’s “ILS state is session-only” risk.

## 9. Tests

New tests:

- `NavDatabaseTest`: deterministic generation, unique IDs, reciprocal runway headings, fix bounds.
- `FlightPlanTest`: validation, status transitions, waypoint sequencing.
- `NavGuidanceTest`: bearing/distance/XTT, capture radius, ILS handoff.
- Wind-based runway selection test.
- Payload round-trip tests where practical.

Manual checks:

- file a plan, see route on ND and weather map
- LNAV sequences fixes
- ATC clearance and ACK
- ILS handoff and autoland
- relog/restart persistence
- dedicated-server load

## 10. Explicit non-goals for Phase 3A

- Taxiways, gates, ramp services.
- AI airline schedules.
- Missions/economy.
- Full VNAV with altitude constraints.
- New aircraft or plane entities.
- New keybinds.
