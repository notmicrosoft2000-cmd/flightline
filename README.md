# Flight Line

Flight Line is a Fabric mod for Minecraft 26.3 that turns players and mobs into aircraft without adding plane entities. Fly server-authoritative airliners, manage engines and fuel, work through emergencies, and let ATC sequence both AI and player traffic.

## Website

Official site: <https://notmicrosoft2000-cmd.github.io/flightline/>

The site lives in [`docs/`](docs/) and deploys to GitHub Pages through [`.github/workflows/pages.yml`](.github/workflows/pages.yml). It includes the home page, aircraft comparison, control reference, emergency procedures, ATC guide, live release changelog, and downloads. The shared header, animated flight display, favicon and social preview are generated from `docs/site.js`, `docs/style.css` and the pixel-art assets in `docs/`.

The in-game mod icon is [`src/main/resources/assets/flightline/icon.png`](src/main/resources/assets/flightline/icon.png) and is referenced by `fabric.mod.json`.

## Downloads

Official builds are published on [GitHub Releases](https://github.com/notmicrosoft2000-cmd/flightline/releases). The download page reads those releases through the GitHub API, so a new release appears automatically after it is published.

## Requirements

- Minecraft 26.3
- Fabric Loader
- Fabric API
- Java 25

## Building

```bash
./gradlew build
```

The distributable mod jar is written to `build/libs/`.
