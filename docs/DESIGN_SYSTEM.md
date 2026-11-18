# Flight Line web design system

The visual system was extracted with `website-design-systems-mcp` from
<https://www.minecraft.net/en-us> on 2026-11-18, then adapted for the Flight Line
field-manual tone. The goal is a site that reads like a maintained aircraft manual
instead of a generic template: square corners, hard shadows, indexed sections,
tabular data, and a restrained palette.

## Source tokens

| Role | Token | Hex |
| --- | --- | --- |
| Off-black | `--void` | `#171615` |
| Coal | `--coal` | `#262423` |
| Stone | `--stone` | `#3d3938` |
| Slate | `--slate` | `#6b6562` |
| Paper shade | `--paper-3` | `#d0c5c0` |
| Paper shade | `--paper-2` | `#ede5e2` |
| Paper | `--paper` | `#fcf5f1` |
| Green | `--green-5` | `#3c8527` |
| Green | `--green-3` | `#6cc349` |
| Brown | `--brown-5` | `#66412c` |
| Signal amber | `--amber` | `#ffc42b` |
| Signal red | `--red` | `#b83b2e` |

## Typography

- `Noto Sans` for body copy. This follows the source site's default stack.
- `Silkscreen` for labels, headings, buttons and instrument numerals. It keeps the
  blocky/pixel feel without setting long passages in a pixel font.
- `VT323` for live flight data, release metadata and sound-track labels.

## Layout rules

- 4px borders and 6–8px hard offset shadows. No rounded corners or soft glows.
- Sections are numbered or ruled like manual chapters, not floating feature cards.
- The home page uses an asymmetric 7/5 hero: manual copy on paper, live flight
  display in a dark instrument console.
- The manual index is a single ruled list with `01`, `02`, … counters and a right
  arrow, replacing a uniform card grid.
- Tables, checklists and release feeds use strong horizontal rules and square
  status blocks.
- Audio lives in a rack-style console with grouped track strips, a separate
  equalizer, transport controls and the full `PLAY ALL` action.

## Motion

Live flight data, cloud drift, ground scroll, radar sweep, blips and the audio
equalizer remain. All motion is disabled under `prefers-reduced-motion: reduce`.
