(function () {
    "use strict";

    var PAGES = [
        { id: "home", label: "HOME", href: "index.html" },
        { id: "aircraft", label: "AIRCRAFT", href: "aircraft.html" },
        { id: "controls", label: "CONTROLS", href: "controls.html" },
        { id: "emergencies", label: "EMERGENCIES", href: "emergencies.html" },
        { id: "audio", label: "SOUNDS", href: "audio.html" },
        { id: "atc", label: "ATC", href: "atc.html" },
        { id: "updates", label: "UPDATES", href: "updates.html" },
        { id: "download", label: "DOWNLOAD", href: "download.html" }
    ];

    var REPO_URL = "https://github.com/notmicrosoft2000-cmd/flightline";
    var RELEASES_URL = REPO_URL + "/releases";

    function currentPage() {
        return document.body.getAttribute("data-page") || "home";
    }

    function navLinks() {
        return PAGES.map(function (page) {
            var active = page.id === currentPage() ? " active" : "";
            return '<a class="nav-link' + active + '" href="' + page.href + '">' + page.label + "</a>";
        }).join("");
    }

    function buildHeader() {
        var mount = document.querySelector("[data-site-header]");
        if (!mount) return;
        mount.innerHTML =
            '<header class="site-header">' +
                '<div class="container header-inner">' +
                    '<a class="brand" href="index.html" aria-label="Flight Line home">' +
                        '<img class="brand-icon" src="favicon.png" width="36" height="36" alt="">' +
                        "<span>" +
                            '<span class="brand-name">FLIGHT LINE</span>' +
                            '<span class="brand-sub">MINECRAFT FABRIC MOD</span>' +
                        "</span>" +
                    "</a>" +
                    '<nav class="nav" aria-label="Main navigation">' +
                        navLinks() +
                        '<a class="nav-link github" href="' + REPO_URL + '" target="_blank" rel="noreferrer">GITHUB</a>' +
                    "</nav>" +
                "</div>" +
            "</header>";
    }

    function planeSvg(mode) {
        return '' +
            '<svg class="' + (mode === "hero" ? "live-plane" : "ticker-plane") + '" viewBox="-32 -32 64 64" aria-hidden="true">' +
                '<g data-plane data-plane-mode="' + mode + '">' +
                    '<line class="contrail" x1="-18" y1="0" x2="-74" y2="0" stroke="#dcecf1" stroke-width="2" stroke-dasharray="8 7" opacity="0.8"/>' +
                    '<g fill="#0b0e10">' +
                        '<rect x="-3" y="-16" width="6" height="32"/>' +
                        '<rect x="-17" y="-4" width="34" height="8"/>' +
                        '<rect x="-10" y="11" width="20" height="6"/>' +
                        '<rect x="-8" y="-5" width="6" height="10"/>' +
                        '<rect x="2" y="-5" width="6" height="10"/>' +
                    "</g>" +
                    '<g fill="#f2f5f5">' +
                        '<rect x="-2" y="-15" width="4" height="30"/>' +
                        '<rect x="-16" y="-3" width="32" height="6"/>' +
                        '<rect x="-9" y="12" width="18" height="4"/>' +
                        '<rect x="-7" y="-4" width="4" height="8" fill="#aeb9be"/>' +
                        '<rect x="3" y="-4" width="4" height="8" fill="#aeb9be"/>' +
                    "</g>" +
                    '<rect x="-16" y="-3" width="4" height="6" fill="#c44b42"/>' +
                    '<rect x="12" y="-3" width="4" height="6" fill="#c44b42"/>' +
                    '<rect x="-2" y="-12" width="4" height="4" fill="#4eb8c8"/>' +
                    '<rect x="-1" y="-11" width="2" height="2" fill="#8fd7e2"/>' +
                "</g>" +
            "</svg>";
    }

    function buildTicker() {
        var mount = document.querySelector("[data-flight-ticker]");
        if (!mount) return;
        mount.innerHTML =
            '<div class="flight-ticker" data-live-flight>' +
                '<div class="container ticker-inner">' +
                    '<span class="ticker-label"><span class="live-dot" aria-hidden="true"></span>LIVE FLIGHT</span>' +
                    planeSvg("ticker") +
                    '<span class="ticker-route">GINA 787 · EGLL → KJFK</span>' +
                    '<span class="ticker-stat">IAS <b data-flight-speed>262</b> KT</span>' +
                    '<span class="ticker-stat">ALT <b data-flight-altitude>34,000</b> FT</span>' +
                    '<span class="ticker-stat ticker-stat-last">HDG <b data-flight-heading>072</b>°</span>' +
                "</div>" +
            "</div>";
    }

    function buildFooter() {
        var mount = document.querySelector("[data-site-footer]");
        if (!mount) return;
        var links = PAGES.map(function (page) {
            return '<a href="' + page.href + '">' + page.label + "</a>";
        }).join(" · ");
        mount.innerHTML =
            '<footer class="site-footer">' +
                '<div class="container">' +
                    "<span>FLIGHT LINE · BUILT FOR FABRIC</span>" +
                    '<span>' + links + ' · <a href="' + RELEASES_URL + '" target="_blank" rel="noreferrer">RELEASES</a></span>' +
                "</div>" +
            "</footer>";
    }

    function setText(root, selector, value) {
        var node = root.querySelector(selector);
        if (node) node.textContent = value;
    }

    function initLiveFlight() {
        var regions = Array.prototype.slice.call(document.querySelectorAll("[data-live-flight]"));
        if (!regions.length) return;

        var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

        function apply(region, t) {
            var speed = Math.round(262 + Math.sin(t * 0.71) * 9 + Math.sin(t * 0.23) * 5);
            var altitude = Math.round((34000 + Math.sin(t * 0.13) * 1600) / 100) * 100;
            var heading = Math.round(72 + Math.sin(t * 0.11) * 8);
            var throttle = Math.round(84 + Math.sin(t * 0.37) * 5);
            var vertical = Math.round(Math.sin(t * 0.19) * 900);
            var wave = Math.sin(t * 0.09);
            var phase = wave > 0.35 ? "CLIMB" : wave < -0.35 ? "DESCENT" : "CRUISE";

            setText(region, "[data-flight-speed]", speed);
            setText(region, "[data-flight-altitude]", altitude.toLocaleString("en-US"));
            setText(region, "[data-flight-heading]", ("00" + heading).slice(-3));
            setText(region, "[data-flight-throttle]", throttle);
            setText(region, "[data-flight-vertical]", (vertical > 0 ? "+" : "") + vertical.toLocaleString("en-US"));
            setText(region, "[data-flight-stage]", phase);

            var plane = region.querySelector("[data-plane]");
            if (!plane) return;

            if (plane.getAttribute("data-plane-mode") === "hero") {
                var width = region.clientWidth || 360;
                var height = region.clientHeight || 220;
                var x = width * 0.52 + Math.sin(t * 0.19) * width * 0.30;
                var y = height * 0.43 + Math.sin(t * 0.31 + 1) * height * 0.15;
                var rotation = Math.sin(t * 0.31 + 1) * 12 + Math.sin(t * 0.19) * 4;
                plane.style.transform =
                    "translate(" + Math.round(x) + "px," + Math.round(y) + "px) " +
                    "translate(-50%,-50%) rotate(" + rotation.toFixed(1) + "deg)";
            } else {
                var slide = Math.round(Math.sin(t * 0.8) * 5);
                var tilt = (Math.sin(t * 0.31 + 1) * 10).toFixed(1);
                plane.style.transform = "translateX(" + slide + "px) rotate(" + tilt + "deg)";
            }
        }

        function tick() {
            var t = (window.performance && window.performance.now ? window.performance.now() : Date.now()) / 1000;
            regions.forEach(function (region) { apply(region, t); });
        }

        tick();
        if (reduced) return;
        window.setInterval(tick, 80);
    }

    document.addEventListener("DOMContentLoaded", function () {
        buildHeader();
        buildTicker();
        buildFooter();
        initLiveFlight();
    });
}());
