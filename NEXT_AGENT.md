# Flight Line Handoff

Flight Line is a Fabric mod for Minecraft 26.3, built with Gradle 9.8.2 and Java 25. Players use server-authoritative aircraft physics; AI-enabled mobs also fly without adding plane entities. Shared flight logic is under `src/main/java/dev/flightline/flight/`, client input/HUD/audio code is under `src/client/java/dev/flightline/client/`, and custom play payloads are under `src/main/java/dev/flightline/network/`.

Run `./gradlew build` for compilation and automated tests. In-game verification still matters for key events, HUD placement, mixins, and server movement. Asset downloads have previously stalled in this environment.

Current features include runway roll, aircraft pitch/bank and flight physics, engine spool, gear/flaps/spoilers, weather, autopilot, mob aircraft steering and air-to-air avoidance, TCAS, serialized cockpit voice alerts/callouts sourced from `Audio/`, and a compact PFD/ND/EICAS/radio-altimeter HUD. H opens a cockpit panel with MCP, NAV/ILS, and remappable key-reference pages, even before flight mode is enabled. The MCP reports active heading/altitude/vertical-speed/airspeed targets and the ±2-knot autothrottle deadband. Set an ILS in NAV/ILS while standing at a runway threshold and facing down the runway; adjust the 1–6 degree glide slope there, or use `/flightline ils set [glideSlope]`. `/flightline ils clear` and the GUI both remove it. Guidance is limited to the configured dimension and approach volume. The reference is session-only.

Touchdowns allow a safe landing, a survivable hard landing that can fail the gear, or a fatal crash for gear-up and excessive sink-rate impacts. Aircraft crashes use a custom death message and produce smoke, flame, cloud, and impact particles with radial entity knockback; they no longer create terrain-damaging explosions. Mob enrollment excludes NoAI mobs, passengers, and mobs carrying passengers/vehicles; native AI remains enabled for target selection.

Throttle lever and engine spool rates are tuned for faster response. Pushback brakes forward taxi motion to a stop before reversing, then ramps to a modest capped speed. The lower-left radio-altimeter panel shows a larger digital height, color-coded 0–500 ft tape with low-altitude ticks, and vertical-speed trend.

The NAV/ILS page can arm server-side autoland while airborne inside an active ILS coverage area. It tracks the runway localizer and selected glide slope, targets 130 knots, and configures gear, flaps, and spoilers; a successful touchdown triggers rollout braking. Autoland can be canceled even after airspeed or engine/gear conditions deteriorate, and it disengages if ILS guidance is lost. Set runway threshold X/Z and inbound runway heading on H > NAV/ILS; threshold Y is inferred from terrain. “USE MY POSITION” is a shortcut while standing at the threshold. Autoland is a button on that page, not a keybind.

Weather simulation samples clear, rain, and thunderstorm conditions into changing wind, gust-driven turbulence, and an estimated visibility range. Thunderstorms can produce recurring windshear events with an aural warning and flight-model impulses. Turbulence feeds into aircraft motion and camera shake; visibility is shown on EICAS and the WX page and scales the ND contact scope. H > WX shows current condition, wind, turbulence, and visibility. Minecraft's own world rendering still supplies the rain/thunder visuals.

Cockpit alert audio is serialized through a queue. The Terrain alert speaks “terrain, terrain”; Pull Up queues “terrain, terrain, pull up”; radio-altimeter callouts and RETARD are preceded by the recorded ding. Caution-class voice alerts get the two-whoop caution tone on alert entry, and caution-only system alerts use that tone. The `Audio/gear.ogg` input is only 2.4 ms and there is no standalone `too low.ogg`, so TOO LOW GEAR retains its existing complete runtime clip until valid word clips are supplied.

Default controls: J engines, L shutdown, G gear, C/V flaps, X/Z spoilers, H cockpit panel, K pushback, M brakes, E inventory, WASD steering/throttle. The bottom-right HUD shows a compact, borderless vertical key list with live remappings and pressed-key highlighting. Bank input permits steep turns; holding beyond 65 degrees for a second crashes the aircraft. Pull-up warnings require an airborne low-altitude descent or a predicted terrain strike, and TCAS warnings require both aircraft to be airborne. Gear-up warnings require a low-altitude descent; selecting gear on the ground is rejected and triggers a one-shot warning. Voice clips are serialized to prevent overlapping playback; TCAS traffic audio is one-shot while other warnings retain cooldown repeats. Inventory controls no longer send flight inputs while a screen is open. `/disableflight` and `/enableflight` are operator-only movement overrides.

Next manual checks:
- Open and close inventory repeatedly with engines on/off; confirm no reopen and no flight input while a screen is open.
- Inspect the PFD/ND/EICAS at standard and small GUI resolutions. Verify pitch/roll, ILS centering and deviations, and that panels/text remain within the screen.
- Open H with flight mode on and off; test MCP, NAV/ILS set/clear and glide-slope adjustment, plus the key-reference page at standard and small resolutions.
- Confirm a continuous TCAS traffic warning plays its aural alert once and can alert again after the traffic warning clears and returns.
- Verify recorded GPWS/TCAS/callout voices, hard-landing survivability, crash death messages, and the composed terrain/terrain/pull-up phrase in-game.
- Test faster throttle changes at idle and high speed, verify engine spool response and landing control, and test pushback from rest and while taxiing forward.
- Inspect the enlarged radio-altimeter tape and vertical-speed trend at standard and narrow screen sizes.
- Configure an ILS at a test runway, approach from the inbound side, and verify localizer/glideslope direction and coverage.
- Test autoland from both sides of the ILS capture envelope; verify arming gates, localizer and glide-slope capture, gear/flap deployment, manual cancellation, ILS-loss disengagement, touchdown, and rollout braking.
- Check camera shake during ordinary gusts, windshear, takeoff, steep banking, descent, and landing rollout; verify it resets on disconnect and is comfortable at different frame rates.
- Check H > WX, EICAS weather readouts, and ND range changes in clear, rainy, and thundering conditions.
- Test multiple airborne and ground mobs/players: counts, ground blips, TCAS resolution only for airborne traffic, and collision particles/knockback without block damage.
- Test `/disableflight`, `/enableflight`, and ILS set/clear in a live server; confirm the enable command rejects riding/spectator states.

Keep Minecraft, loader, API, and Java requirements aligned across Gradle files and `fabric.mod.json`. Avoid Yarn mappings for this target. Do not commit generated `build/`, `.gradle/`, or local run data.
