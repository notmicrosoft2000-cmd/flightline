# Flight Line Code Audit

Follow-up to the static review. `./gradlew build` passes; this includes the automated tests. In-game/client-server checks below remain necessary.

## Addressed

- Ground traffic no longer qualifies for airborne TCAS resolution advisories. Mob avoidance also considers only airborne player aircraft.
- ND AIR/GND counts now come from the server's 128-block traffic scope rather than only client-tracked entities. Radar blips are still drawn from client-tracked entities; the nearest threat/contact remains server-reported.
- The HUD now fits long labels/values and uses a narrow-screen layout; the lower instrument panel and remappable bottom-right key sheet are omitted when there is insufficient room. The PFD adds ILS deviation needles.
- Crash effects use particles and radial knockback instead of an explosion, avoiding terrain and nearby-block damage.
- Flight input is suspended and queued key presses are discarded while a GUI is open, including the inventory and autopilot screens.
- H opens the flight deck before the flight-enabled input gate. Its MCP, NAV/ILS, and key-reference pages allow GUI-based autopilot and ILS configuration; the server validates glide-slope values.
- Recorded cockpit voices replace the placeholder alerts, and alert/callout sounds are queued so clips do not overlap. Continuous TCAS traffic audio plays once until it clears; other warnings retain cooldown repeats. Descent callouts include 2,500 and 1,000 feet.
- Pull-up warnings are restricted to airborne low-altitude descents or projected terrain conflicts; TCAS advisories require both aircraft to be airborne. Ground gear selection is rejected with a one-shot cockpit warning. Steep bank commands are available and sustained bank beyond 65 degrees causes a crash.
- Gear-up and low-flap warnings now require a low-altitude descent, avoiding the persistent “TOO LOW GEAR” alert during level flight.
- Touchdown logic now allows a safe landing, a survivable hard landing with gear damage, and a crash for gear-up or excessive-speed/sink-rate impacts. Aircraft crashes use a custom localized death message.
- The HUD key reference is now a smaller, borderless vertical list at the bottom right; mappings are gray normally and white while pressed. PFD pitch/roll and ILS readouts have separated, width-limited rows. The MCP shows active heading, altitude, vertical-speed, airspeed, and autothrottle deadband targets.
- `/enableflight` reports failure for dead, spectator, or riding players instead of returning a false success.
- Mob enrollment excludes NoAI mobs, passengers, and vehicles; collision checks query nearby players instead of scanning the full player list for each mob.

## Remaining Risks / Verification

1. **Client radar blips remain limited by vanilla entity tracking.** The server scope counts and nearest contact are authoritative, but the client only draws individual blips for entities it knows about. A future contact-list payload would be needed for all server-visible contacts to appear as blips.
2. **Mob physics intentionally overrides eligible mobs' movement while native AI stays enabled.** NoAI mobs, riders, and vehicles are excluded and gravity is restored, but unusual modded mobs may still have incompatible movement assumptions.
3. **Mob processing still scans loaded entities every server tick.** Nearby player collision queries were narrowed, but large loaded-mob populations should be profiled in a stress world.
4. **ILS references are session-only.** Set one in the H > NAV/ILS page at the runway threshold while facing down the runway, or use `/flightline ils set [glideSlope]`; clear it with the GUI or `/flightline ils clear`. Verify the sign and capture behavior in-game.
5. **Inventory event ordering needs a live-client check.** Verify repeated E open/close with engines on and off, no double-open/reopen, and no movement controls while the inventory is visible.
6. **Collision behavior needs a live server check.** Verify knockback, particles, mob/player collision behavior, and that blocks are not damaged.
7. **Landing and crash behavior need a live check.** Verify safe and hard landings, gear-up/excessive-sink crashes, nonlethal hard-landing damage, and the localized death message.
8. **Voice audio needs a live-client check.** Verify each recorded alert is audible, the terrain/pull-up phrase plays in sequence, and active warning/callout clips do not overlap.
9. **HUD and flight-deck placement need live-client checks** at standard and small GUI resolutions, including ILS needles, pitch/roll readouts, ND counts, live key highlighting, MCP targets, and warning placement.

Use `./gradlew runClient` when assets/display support are available; a successful compile alone does not validate mixin injection, key event order, HUD placement, or movement synchronization.
