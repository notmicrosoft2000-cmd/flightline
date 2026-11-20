# Flight Line Handoff

Flight Line is a Fabric mod for Minecraft 26.3, built with Gradle 9.8.2 and Java 25. Players use server-authoritative aircraft physics; AI-enabled mobs also fly without adding plane entities. Shared flight logic is under `src/main/java/dev/flightline/flight/`, client input/HUD/audio code is under `src/client/java/dev/flightline/client/`, and custom play payloads are under `src/main/java/dev/flightline/network/`.

Run `./gradlew build` for compilation and automated tests. In-game verification still matters for key events, HUD placement, mixins, and server movement. Asset downloads have previously stalled in this environment.

Current features include runway roll, aircraft pitch/bank and flight physics, engine spool, gear/flaps/spoilers, weather, autopilot, mob aircraft steering and air-to-air avoidance, TCAS, serialized cockpit voice alerts/callouts sourced from `Audio/`, and a compact PFD/ND/EICAS/radio-altimeter HUD.

Camera pitch follow and aircraft attitude:
- Dynamic flight camera pitch follow in `FlightCameraMixin.java`: as the pilot moves the mouse to command pitch, the render camera dynamically tracks and follows the aircraft's aerodynamic pitch response with realistic flight inertia, without fighting raw mouse input.
- Aircraft pitch attitude rendering in `LivingEntityRendererMixin.java`: players and mobs tilt realistically nose-up when climbing and nose-down when diving (`Axis.XP, -pitch`), matching their flight bank roll (`Axis.ZP, bank`).

Airliner Mode Control Panel (MCP) on Flight Deck (`H`):
- Tab 0 (AUTOPILOT) styled like an authentic airliner glareshield Mode Control Panel with 5 distinct horizontal bays:
  - `A/T · SPEED`: digital amber LED window, direct input, `[-]` / `[+]` steps, `SYNC SPD`, and `A/T ARM` green annunciator.
  - `HDG SEL`: digital 3-digit heading display (`090°`), direct input, `[-10]` / `[+10]` steps, `SYNC HDG`, and `HDG SEL` green annunciator.
  - `ALT HOLD`: digital 5-digit altitude display (`10,000 FT`), direct input, `[-500]` / `[+500]` steps, `SYNC ALT`, and `ALT HOLD` green annunciator.
  - `VERT SPEED`: digital signed V/S display (`+1,500 FPM`), direct input, `[-500]` / `[+500]` steps, `LEVEL OFF` (0 FPM), and `VERT SPEED` green annunciator.
  - `A/P CMD`: large master engage button with illuminated green `ENGAGED` / `STANDBY` status.
- Lower flight guidance panel features live current vs target linear deviation tapes and real-time autothrottle / N1 telemetry.
- Tab 1 (RUNWAY / AUTOLAND): Runway threshold X/Z, inbound runway heading, 1–6 degree glide slope adjustment, "USE MY POSITION", and "ARM AUTOLAND". Guidance is dimension- and approach-volume scoped.
- Tab 2 (ALERTS): Active aircraft warnings and cautions with advisory notes.

Emergency Checklist & QRH (`O`):
- Clean, compact 580x370 layout fitting all screen scales cleanly.
- Header with severity banner (Critical, Caution, All Nominal) and resource gauges (Fire bottles, E1/E2 restart attempts, Fuel %).
- Prominent `[T]` quick-trigger card for the recommended emergency procedure.
- Left column: Active alerts with fire suppression window countdown bars and plain-English failure descriptions.
- Right column: Grouped recovery procedures: `ENGINES` (Extinguish E1/E2, Relight E1/E2), `AIRFRAME & HYDRAULICS` (Alternate Gear Extend, Hydraulic Backup), and `EMERGENCY OPERATIONS` (Emergency Descent, Fuel Jettison, Declare/Cancel MAYDAY, Emergency Autoland).

Touchdowns allow a safe landing, a survivable hard landing that can fail the gear, or a fatal crash for gear-up and excessive sink-rate impacts. Aircraft crashes use a custom death message and produce smoke, flame, cloud, and impact particles with radial entity knockback; they no longer create terrain-damaging explosions. Mob enrollment excludes NoAI mobs, passengers, and mobs carrying passengers/vehicles; native AI remains enabled for target selection.

Throttle lever and engine spool rates are tuned for responsive acceleration. Pushback brakes forward taxi motion to a stop before reversing, then ramps to a modest capped speed. The lower-left radio-altimeter panel shows a digital height, color-coded 0–500 ft tape with low-altitude ticks, and vertical-speed trend.

The RUNWAY / AUTOLAND tab arms server-side autoland while airborne inside an active ILS coverage area. It tracks the runway localizer and selected glide slope, targets 130 knots, and configures gear, flaps, and spoilers; a successful touchdown triggers rollout braking. Autoland can be canceled even after airspeed or engine/gear conditions deteriorate, and it disengages if ILS guidance is lost. Set runway threshold X/Z and inbound runway heading on H > RUNWAY / AUTOLAND; threshold Y is inferred from terrain. “USE MY POSITION” is a shortcut while standing at the threshold. Autoland is a button on that page, not a keybind.

Weather simulation samples clear, rain, and thunderstorm conditions into changing wind, gust-driven turbulence, and an estimated visibility range. Thunderstorms can produce recurring windshear events with an aural warning and flight-model impulses. Operators can force a dimension-scoped event with `/flightline weather windshear [durationTicks]` (default `140`) during an active thunderstorm and cancel it with `/flightline weather clear`. Turbulence feeds into aircraft motion and camera shake; visibility is shown on EICAS and scales the ND contact scope. Minecraft's own world rendering still supplies the rain/thunder visuals.

Cockpit alert audio is serialized through a queue. The Terrain alert speaks “terrain, terrain”; Pull Up queues “terrain, terrain, pull up”; Too Low Terrain takes priority over generic Terrain, but Pull Up remains highest among terrain warnings; radio-altimeter callouts and RETARD are preceded by the recorded ding. Speech starts immediately—there is no caution-tone preamble before spoken alerts—and caution-only system alerts still use the two-whoop tone. `SimpleSoundInstance.forUI` takes `(sound, pitch, volume)`, so voices play at pitch `1.0` and volume `1.4` (cues `1.3`) instead of swapped values. Runtime voice/GPWS/TCAS assets were trimmed of silence and normalized.

Control scheme & key handling:
- Key suppression in `VanillaKeybindMixin` while flight mode is active is intentional: quick actions (G), advancements (L), social interactions (P), friends (O), save/load hotbar (C/X), drop (Q), and offhand swap (F) are intercepted to prevent conflicts with flight controls.
- E starts engines while parked/starting; once running, E opens inventory normally. J toggles engine start/shutdown.
- Flight bindings: G gear, C/V flaps, X toggle spoilers, N airbrakes, H flight deck, K pushback, M brakes, R flight radar, U/I/B toggle PFD/EICAS/ND, Y flight data card, L tutorial, P weather map, O emergency checklist, T execute emergency fix, F7 ATC acknowledge, F8 anti-ice, F9 toggle keybind list, F10 controls. W/S throttle, A/D rudder/bank.
- The 12-row vertical keybinds list on the bottom-right HUD is preserved and rendered with a crisp font and translucent backing. The temporary horizontal hotbar chip was removed.
- PFD and EICAS on-screen HUD are sized compactly (`PFD_BASE_WIDTH = 168`, scale capped at 0.70f, EICAS at 0.75f) to avoid obstructing player vision. ScreenScaler enforces a 0.68f readability floor across all UI menus.

Next manual checks:
- Verify E starts the engines on the ground until running, and opens inventory normally once engines are running.
- Verify J toggles engine start / shutdown.
- Confirm vanilla key suppression works as intended during flight: G, L, P, O, C, X, Q, F do not trigger unwanted vanilla hotbar/dialog popups.
- Verify the in-flight HUD is compact (PFD width 168 at 0.70x max, EICAS at 0.75x) without blocking central vision, and vanilla HUD slide operates properly.
- Verify the vertical keybind reference list renders cleanly on the bottom-right and toggles with F9.
- Open H with flight mode on and off; test the 3 tabs (AUTOPILOT, RUNWAY / AUTOLAND, ALERTS), stepper buttons, SYNC, LEVEL, autoland arming, and APPLY & CLOSE.
- Confirm a continuous TCAS traffic warning plays its aural alert once and can alert again after the traffic warning clears and returns.
- Verify recorded GPWS/TCAS/callout voices play at natural speed and louder, with no caution-tone delay before terrain/pull-up speech; confirm a low approach produces a prompt “Too low terrain” and a terrain conflict produces “Terrain, terrain, pull up!”.
- Verify hard-landing survivability, crash death messages, and the composed terrain/terrain/pull-up phrase in-game.
- Test faster throttle changes at idle and high speed, verify engine spool response and landing control, and test pushback from rest and while taxiing forward.
- Inspect the enlarged radio-altimeter tape and vertical-speed trend at standard and narrow screen sizes.
- Configure an ILS at a test runway, approach from the inbound side, and verify localizer/glideslope direction and coverage.
- Test autoland from both sides of the ILS capture envelope; verify arming gates, localizer and glide-slope capture, gear/flap deployment, manual cancellation, ILS-loss disengagement, touchdown, and rollout braking.
- Check camera shake during ordinary gusts, windshear, takeoff, steep banking, descent, and landing rollout; verify it resets on disconnect and is comfortable at different frame rates.
- Check H > WX, EICAS weather readouts, and ND range changes in clear, rainy, and thundering conditions.
- Test multiple airborne and ground mobs/players: counts, ground blips, TCAS resolution only for airborne traffic, and collision particles/knockback without block damage.
- Test `/disableflight`, `/enableflight`, and ILS set/clear in a live server; confirm the enable command rejects riding/spectator states.
- Open H at GUI Scale 1 and in a small window. Verify the 2×2 MCP groups, top engagement strip, footer, and APPLY do not overlap or clip; hover and click every input, stepper, SYNC/LEVEL, AP master, and tab.
- Switch tabs and trigger a nav refresh mid-edit: confirm MCP/ILS/FPLN drafts, carets, and the `UNSENT CHANGES` marker persist; confirm APPLY sends the same values as before.
- Confirm alarm wording appears only on the ALERT tab, the HUD edge flash has the short two-pulse onset, and TCAS-only traffic never triggers the red alarm.
- Check the bottom-right key hints at GUI Scale 1 and in a small window: essentials remain visible, the panel stays clear of the PFD/ND/EICAS, and F9 still hides/restores it.
- Use tutorial `GO TO THIS STEP` in every stage; confirm the first unfinished objective opens the matching MCP, NAV/ILS, radar, gear, flaps, or engines action and that `NEXT` matches progress.
- Build a crowded radar/ND picture and confirm traffic ranking, overlap suppression, and short ALERT advisory rows keep the scope readable.

Keep Minecraft, loader, API, and Java requirements aligned across Gradle files and `fabric.mod.json`. Avoid Yarn mappings for this target. Do not commit generated `build/`, `.gradle/`, or local run data.
