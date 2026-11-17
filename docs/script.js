(function () {
    "use strict";

    var OWNER = "notmicrosoft2000-cmd";
    var REPO = "flightline";
    var RELEASES_URL = "https://github.com/" + OWNER + "/" + REPO + "/releases";
    var API_URL = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=20";

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = String(text);
        return node;
    }

    function linkButton(label, href, className) {
        var link = element("a", "btn " + (className || ""), label);
        link.href = href;
        link.target = "_blank";
        link.rel = "noreferrer";
        return link;
    }

    function formatDate(value) {
        if (!value) return "UNKNOWN DATE";
        var date = new Date(value);
        if (Number.isNaN(date.getTime())) return String(value);
        return new Intl.DateTimeFormat(undefined, {
            year: "numeric",
            month: "short",
            day: "numeric"
        }).format(date);
    }

    function releaseUrl(release) {
        if (release.html_url) return release.html_url;
        return release.tag_name
            ? RELEASES_URL + "/tag/" + encodeURIComponent(release.tag_name)
            : RELEASES_URL;
    }

    function jarAssets(release) {
        if (!release.assets) return [];
        return release.assets.filter(function (asset) {
            return /\.jar$/i.test(asset.name || "");
        });
    }

    function primaryJar(release) {
        var jars = jarAssets(release);
        var gameJar = jars.find(function (asset) {
            return !/(sources|javadoc|dev|api)/i.test(asset.name || "");
        });
        return gameJar || jars[0] || null;
    }

    function shortNotes(body, limit) {
        var text = (body || "").trim();
        if (!text) return "No release notes were added.";
        if (text.length <= limit) return text;
        return text.slice(0, limit).trim() + "...";
    }

    function officialReleases(releases) {
        return releases.filter(function (release) {
            return release && !release.draft && release.tag_name;
        });
    }

    function fetchOfficialReleases() {
        return fetch(API_URL, {
            headers: { "Accept": "application/vnd.github+json" }
        }).then(function (response) {
            if (!response.ok) throw new Error("GitHub API returned " + response.status);
            return response.json();
        }).then(function (releases) {
            if (!Array.isArray(releases)) throw new Error("Unexpected release response");
            return officialReleases(releases);
        });
    }

    function updateHomeBadge(releases) {
        var badges = document.querySelectorAll("[data-latest-version]");
        if (!badges.length || !releases.length) return;
        var stable = releases.find(function (release) { return !release.prerelease; }) || releases[0];
        var text = "LATEST OFFICIAL: " + (stable.name || stable.tag_name);
        for (var i = 0; i < badges.length; i++) badges[i].textContent = text;
    }

    function renderLatest(releases) {
        var container = document.getElementById("latest-release");
        if (!container) return;
        container.textContent = "";

        if (!releases.length) {
            container.appendChild(element("p", "release-status", "NO OFFICIAL RELEASE YET"));
            container.appendChild(element("h2", "release-title", "RELEASES ARE NOT POSTED"));
            container.appendChild(element("p", "release-meta", "When an official build is published, it will appear here automatically."));
            container.appendChild(linkButton("OPEN GITHUB RELEASES", RELEASES_URL, "primary"));
            return;
        }

        var stable = releases.find(function (release) { return !release.prerelease; }) || releases[0];
        var heading = element("div", "release-heading");
        heading.appendChild(element("h2", "release-title", stable.name || stable.tag_name));
        if (stable.prerelease) heading.appendChild(element("span", "tag prerelease", "PRE-RELEASE"));
        else heading.appendChild(element("span", "tag", "STABLE"));
        container.appendChild(element("p", "release-status", "LATEST OFFICIAL BUILD"));
        container.appendChild(heading);
        container.appendChild(element("p", "release-meta",
            "TAG " + stable.tag_name + " · PUBLISHED " + formatDate(stable.published_at)));

        var notes = element("p", "release-notes", shortNotes(stable.body, 500));
        container.appendChild(notes);

        var actions = element("div", "release-actions");
        var jar = primaryJar(stable);
        if (jar) {
            actions.appendChild(linkButton("DOWNLOAD " + jar.name, jar.browser_download_url, "primary"));
        }
        actions.appendChild(linkButton("FULL RELEASE NOTES", releaseUrl(stable)));
        container.appendChild(actions);
    }

    function appendAssetButtons(parent, release) {
        var jars = jarAssets(release);
        if (!jars.length) return;
        var actions = element("div", "release-actions");
        jars.forEach(function (asset) {
            var label = /(sources|javadoc)/i.test(asset.name || "") ? "SOURCE JAR" : "JAR";
            actions.appendChild(linkButton(label, asset.browser_download_url));
        });
        parent.appendChild(actions);
    }

    function renderList(releases) {
        var container = document.getElementById("release-list");
        if (!container) return;
        container.textContent = "";

        if (!releases.length) return;
        container.appendChild(element("h2", "", "ALL VERSIONS"));

        releases.forEach(function (release) {
            var card = element("article", "release-item pixel-box");
            var top = element("div", "release-item-top");
            var title = element("h3", "", release.name || release.tag_name);
            top.appendChild(title);
            if (release.prerelease) top.appendChild(element("span", "tag prerelease", "PRE-RELEASE"));
            card.appendChild(top);

            card.appendChild(element("p", "release-meta",
                "TAG " + release.tag_name + " · PUBLISHED " + formatDate(stableDate(release))));
            card.appendChild(element("p", "", shortNotes(release.body, 180)));
            appendAssetButtons(card, release);

            var view = linkButton("VIEW RELEASE", releaseUrl(release));
            card.appendChild(view);
            container.appendChild(card);
        });
    }

    function renderChangelog(releases) {
        var container = document.querySelector("[data-changelog]");
        if (!container) return;
        container.textContent = "";

        if (!releases.length) {
            container.appendChild(element("p", "release-meta", "No published releases are available yet."));
            return;
        }

        releases.forEach(function (release, index) {
            var item = element("article", "timeline-item pixel-box");
            var top = element("div", "release-item-top");
            top.appendChild(element("h3", "", (index === 0 ? "LATEST · " : "") + (release.name || release.tag_name)));
            if (release.prerelease) top.appendChild(element("span", "tag prerelease", "PRE-RELEASE"));
            else top.appendChild(element("span", "tag", "PUBLISHED"));
            item.appendChild(top);

            item.appendChild(element("p", "release-meta",
                "TAG " + release.tag_name + " · " + formatDate(stableDate(release))));
            item.appendChild(element("p", "release-notes release-notes-tall", shortNotes(release.body, 1400)));

            var actions = element("div", "release-actions");
            actions.appendChild(linkButton("RELEASE NOTES", releaseUrl(release)));
            var jar = primaryJar(release);
            if (jar) actions.appendChild(linkButton("DOWNLOAD JAR", jar.browser_download_url, "primary"));
            item.appendChild(actions);
            container.appendChild(item);
        });
    }

    function stableDate(release) {
        return release.published_at || release.created_at;
    }

    function renderError(error) {
        console.error("Flight Line release loader:", error);
        var latest = document.getElementById("latest-release");
        if (latest) {
            latest.textContent = "";
            latest.appendChild(element("p", "release-status", "RELEASE LOOKUP FAILED"));
            latest.appendChild(element("h2", "release-title", "CHECK GITHUB RELEASES"));
            latest.appendChild(element("p", "release-meta", "GitHub's API may be rate limited. The official release page still works."));
            latest.appendChild(linkButton("OPEN GITHUB RELEASES", RELEASES_URL, "primary"));
        }
        var list = document.getElementById("release-list");
        if (list) list.textContent = "";
    }

    document.addEventListener("DOMContentLoaded", function () {
        fetchOfficialReleases().then(function (releases) {
            updateHomeBadge(releases);
            renderLatest(releases);
            renderList(releases);
            renderChangelog(releases);
        }).catch(renderError);
    });
}());
