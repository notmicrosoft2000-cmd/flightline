(function () {
    "use strict";

    var SOUND_SETS = {
        kasa: {
            label: "KASA A380",
            description: "Standard callouts, GPWS, TCAS and ATC voice guidance.",
            groups: [
                {
                    name: "ENGINES",
                    tracks: [
                        ["engine_loop.ogg", "Engine loop"],
                        ["engine_cruise.ogg", "Cruise loop"]
                    ]
                },
                {
                    name: "TCAS",
                    tracks: [
                        ["tcas_traffic.ogg", "Traffic, traffic"],
                        ["tcas_climb_now.ogg", "Climb now"],
                        ["tcas_climb_crossing.ogg", "Climb crossing"],
                        ["tcas_descend_now.ogg", "Descend now"],
                        ["tcas_descend_crossing.ogg", "Descend crossing"],
                        ["tcas_clear.ogg", "Clear of conflict"],
                        ["tcas_test_pass.ogg", "TCAS test pass"],
                        ["tcas_test_fail.ogg", "TCAS test fail"]
                    ]
                },
                {
                    name: "GPWS & ALARMS",
                    tracks: [
                        ["caution_alarm.ogg", "Caution alarm"],
                        ["gpws_pull_up.ogg", "Pull up"],
                        ["gpws_terrain.ogg", "Terrain"],
                        ["gpws_sink_rate.ogg", "Sink rate"],
                        ["gpws_dont_sink.ogg", "Don't sink"],
                        ["gpws_too_low_gear.ogg", "Too low gear"],
                        ["gpws_too_low_flaps.ogg", "Too low flaps"],
                        ["gpws_too_low_terrain.ogg", "Too low terrain"],
                        ["gpws_bank_angle.ogg", "Bank angle"],
                        ["gpws_glideslope.ogg", "Glideslope"],
                        ["gpws_windshear.ogg", "Windshear"]
                    ]
                },
                {
                    name: "ALTITUDE & CONFIG",
                    tracks: [
                        ["callout_ding.ogg", "Ding"],
                        ["callout_2500.ogg", "2500"],
                        ["callout_1000.ogg", "1000"],
                        ["callout_500.ogg", "500"],
                        ["callout_100.ogg", "100"],
                        ["callout_50.ogg", "50"],
                        ["callout_40.ogg", "40"],
                        ["callout_30.ogg", "30"],
                        ["callout_20.ogg", "20"],
                        ["callout_10.ogg", "10"],
                        ["callout_retard.ogg", "Retard"],
                        ["callout_plus_100.ogg", "Plus 100"],
                        ["callout_flaps.ogg", "Flaps"],
                        ["callout_gear.ogg", "Gear"]
                    ]
                },
                {
                    name: "VOICE GUIDANCE",
                    tracks: [
                        ["voice_approaching_minimums.ogg", "Approaching minimums"],
                        ["voice_minimums.ogg", "Minimums"],
                        ["voice_adjust_vertical_speed.ogg", "Adjust vertical speed"],
                        ["voice_maintain_vertical_speed.ogg", "Maintain vertical speed"],
                        ["voice_monitor_vertical_speed.ogg", "Monitor vertical speed"],
                        ["voice_increase_climb.ogg", "Increase climb"],
                        ["voice_increase_descend.ogg", "Increase descend"],
                        ["voice_reduce_climb.ogg", "Reduce climb"],
                        ["voice_reduce_descend.ogg", "Reduce descend"]
                    ]
                }
            ]
        },
        gina: {
            label: "THE GINA 787",
            description: "The separate Gina voice set, alarms, callouts and TCAS phrases.",
            groups: [
                {
                    name: "ENGINES & CHIME",
                    tracks: [
                        ["engine_loop.ogg", "Engine loop"],
                        ["engine_cruise.ogg", "Cruise loop"],
                        ["callout_ding.ogg", "Neutral chime"]
                    ]
                },
                {
                    name: "ALARMS",
                    tracks: [
                        ["gina_caution_alarm.ogg", "Caution"],
                        ["gina_urgent_alarm.ogg", "Urgent alarm"],
                        ["gina_stall_stall.ogg", "Stall, stall"],
                        ["gina_takeoff_config_warn.ogg", "Takeoff config"]
                    ]
                },
                {
                    name: "GPWS",
                    tracks: [
                        ["gina_gpws_bank_angle.ogg", "Bank angle"],
                        ["gina_gpws_dont_sink.ogg", "Don't sink"],
                        ["gina_gpws_glideslope.ogg", "Glideslope"],
                        ["gina_gpws_pull_up.ogg", "Pull up"],
                        ["gina_gpws_terrain_pullup.ogg", "Terrain, terrain, pull up"],
                        ["gina_gpws_sink_rate.ogg", "Sink rate"],
                        ["gina_gpws_too_low_gear.ogg", "Too low gear"],
                        ["gina_gpws_too_low_terrain.ogg", "Too low terrain"],
                        ["gina_gpws_windshear.ogg", "Windshear"],
                        ["gina_gpws_windshear_ahead.ogg", "Windshear ahead"]
                    ]
                },
                {
                    name: "TCAS",
                    tracks: [
                        ["gina_tcas_clear.ogg", "Clear of conflict"],
                        ["gina_tcas_climb.ogg", "Climb"],
                        ["gina_tcas_climb_now.ogg", "Climb now"],
                        ["gina_tcas_climb_crossing.ogg", "Climb crossing"],
                        ["gina_tcas_descend.ogg", "Descend"],
                        ["gina_tcas_descend_now.ogg", "Descend now"],
                        ["gina_tcas_descend_crossing.ogg", "Descend crossing"],
                        ["gina_tcas_traffic.ogg", "Traffic, traffic"],
                        ["gina_tcas_test_pass.ogg", "TCAS test pass"],
                        ["gina_tcas_test_fail.ogg", "TCAS test fail"]
                    ]
                },
                {
                    name: "ALTITUDE CALLOUTS",
                    tracks: [
                        ["gina_callout_2500.ogg", "2500"],
                        ["gina_callout_1000.ogg", "1000"],
                        ["gina_callout_500.ogg", "500"],
                        ["gina_callout_400.ogg", "400"],
                        ["gina_callout_300.ogg", "300"],
                        ["gina_callout_200.ogg", "200"],
                        ["gina_callout_100.ogg", "100"],
                        ["gina_callout_40.ogg", "40"],
                        ["gina_callout_30.ogg", "30"],
                        ["gina_callout_20.ogg", "20"],
                        ["gina_callout_10.ogg", "10"]
                    ]
                },
                {
                    name: "VOICE GUIDANCE",
                    tracks: [
                        ["gina_adjust_vertical_speed.ogg", "Adjust vertical speed"],
                        ["gina_voice_approaching_minimums.ogg", "Approaching minimums"],
                        ["gina_voice_approaching_decision_height.ogg", "Approaching decision height"],
                        ["gina_voice_minimums.ogg", "Minimums"]
                    ]
                }
            ]
        }
    };

    function initSoundPanel(root) {
        var audio = root.querySelector("[data-audio]");
        if (!audio) return;

        var state = {
            set: "kasa",
            tracks: [],
            index: -1,
            playAll: false
        };

        var els = {
            toggleKasa: root.querySelector('[data-set="kasa"]'),
            toggleGina: root.querySelector('[data-set="gina"]'),
            playAll: root.querySelector("[data-play-all]"),
            stop: root.querySelector("[data-stop]"),
            prev: root.querySelector("[data-prev]"),
            next: root.querySelector("[data-next]"),
            playPause: root.querySelector("[data-play-pause]"),
            list: root.querySelector("[data-track-list]"),
            nowPlaying: root.querySelector("[data-now-playing]"),
            counter: root.querySelector("[data-counter]"),
            status: root.querySelector("[data-sound-status]"),
            setDescription: root.querySelector("[data-set-description]"),
            progress: root.querySelector("[data-progress]"),
            currentTime: root.querySelector("[data-current-time]"),
            duration: root.querySelector("[data-duration]"),
            volume: root.querySelector("[data-volume]"),
            eq: root.querySelector("[data-equalizer]")
        };

        function formatTime(seconds) {
            if (!isFinite(seconds) || seconds < 0) return "0:00";
            var whole = Math.floor(seconds);
            return Math.floor(whole / 60) + ":" + ("0" + (whole % 60)).slice(-2);
        }

        function setStatus(text) {
            if (els.status) els.status.textContent = text;
        }

        function setEqualizer(active) {
            root.classList.toggle("is-sounding", active);
            if (els.eq) els.eq.setAttribute("aria-hidden", active ? "false" : "true");
        }

        function updatePlayPause() {
            if (!els.playPause) return;
            els.playPause.textContent = audio.paused ? "RESUME" : "PAUSE";
        }

        function updateProgress() {
            var pct = audio.duration ? (audio.currentTime / audio.duration) * 100 : 0;
            if (els.progress) els.progress.style.width = Math.max(0, Math.min(100, pct)) + "%";
            if (els.currentTime) els.currentTime.textContent = formatTime(audio.currentTime);
            if (els.duration) els.duration.textContent = formatTime(audio.duration);
        }

        function highlightTrack() {
            var buttons = els.list ? els.list.querySelectorAll("[data-track-index]") : [];
            for (var i = 0; i < buttons.length; i++) {
                buttons[i].classList.toggle("active", Number(buttons[i].getAttribute("data-track-index")) === state.index);
            }
        }

        function buildList() {
            if (!els.list) return;
            els.list.textContent = "";
            state.tracks = [];

            SOUND_SETS[state.set].groups.forEach(function (group) {
                var section = document.createElement("section");
                section.className = "sound-group";

                var heading = document.createElement("h3");
                heading.className = "sound-group-title";
                heading.textContent = group.name + " · " + group.tracks.length;
                section.appendChild(heading);

                var grid = document.createElement("div");
                grid.className = "sound-track-grid";

                group.tracks.forEach(function (track) {
                    var index = state.tracks.length;
                    state.tracks.push({ file: track[0], label: track[1], group: group.name });

                    var button = document.createElement("button");
                    button.type = "button";
                    button.className = "sound-track";
                    button.setAttribute("data-track-index", index);
                    button.textContent = track[1];
                    button.addEventListener("click", function () {
                        playTrack(index, false);
                    });
                    grid.appendChild(button);
                });

                section.appendChild(grid);
                els.list.appendChild(section);
            });

            if (els.counter) els.counter.textContent = state.tracks.length + " CLIPS";
            if (els.setDescription) els.setDescription.textContent = SOUND_SETS[state.set].description;
            if (els.playAll) els.playAll.textContent = "PLAY ALL " + SOUND_SETS[state.set].label;
            setStatus("READY · " + SOUND_SETS[state.set].label);
        }

        function selectSet(name) {
            if (!SOUND_SETS[name]) return;
            audio.pause();
            state.set = name;
            state.index = -1;
            state.playAll = false;
            setEqualizer(false);
            if (els.toggleKasa) els.toggleKasa.classList.toggle("active", name === "kasa");
            if (els.toggleGina) els.toggleGina.classList.toggle("active", name === "gina");
            if (els.nowPlaying) els.nowPlaying.textContent = "NO CLIP SELECTED";
            updateProgress();
            updatePlayPause();
            buildList();
        }

        function playTrack(index, playAll) {
            var track = state.tracks[index];
            if (!track) return;

            state.index = index;
            state.playAll = playAll;
            audio.src = "audio/" + encodeURIComponent(track.file);
            audio.currentTime = 0;
            audio.play().then(function () {
                setEqualizer(true);
                setStatus((playAll ? "PLAYING ALL · " : "NOW PLAYING · ") + track.group);
                updatePlayPause();
            }).catch(function () {
                setEqualizer(false);
                setStatus("PRESS PLAY ALL TO START AUDIO");
            });

            if (els.nowPlaying) els.nowPlaying.textContent = track.label;
            highlightTrack();
            updateProgress();
        }

        function stop() {
            audio.pause();
            audio.currentTime = 0;
            state.playAll = false;
            setEqualizer(false);
            setStatus("STOPPED");
            updatePlayPause();
            updateProgress();
        }

        function step(delta) {
            if (!state.tracks.length) return;
            var next = state.index + delta;
            if (next < 0) next = state.tracks.length - 1;
            if (next >= state.tracks.length) next = 0;
            playTrack(next, state.playAll);
        }

        if (els.toggleKasa) els.toggleKasa.addEventListener("click", function () { selectSet("kasa"); });
        if (els.toggleGina) els.toggleGina.addEventListener("click", function () { selectSet("gina"); });
        if (els.playAll) els.playAll.addEventListener("click", function () { playTrack(0, true); });
        if (els.stop) els.stop.addEventListener("click", stop);
        if (els.prev) els.prev.addEventListener("click", function () { step(-1); });
        if (els.next) els.next.addEventListener("click", function () { step(1); });
        if (els.playPause) els.playPause.addEventListener("click", function () {
            if (state.index < 0) {
                playTrack(0, false);
                return;
            }
            if (audio.paused) audio.play(); else audio.pause();
        });

        audio.addEventListener("play", function () { setEqualizer(true); updatePlayPause(); });
        audio.addEventListener("pause", function () { setEqualizer(false); updatePlayPause(); });
        audio.addEventListener("timeupdate", updateProgress);
        audio.addEventListener("loadedmetadata", updateProgress);
        audio.addEventListener("ended", function () {
            if (state.playAll && state.index + 1 < state.tracks.length) {
                playTrack(state.index + 1, true);
            } else {
                state.playAll = false;
                setEqualizer(false);
                setStatus("FINISHED · " + SOUND_SETS[state.set].label);
                updatePlayPause();
            }
        });

        if (els.volume) {
            var saved = window.localStorage ? window.localStorage.getItem("flightline-volume") : null;
            if (saved !== null) {
                audio.volume = Math.max(0, Math.min(1, Number(saved)));
                els.volume.value = Math.round(audio.volume * 100);
            }
            els.volume.addEventListener("input", function () {
                audio.volume = Math.max(0, Math.min(1, Number(els.volume.value) / 100));
                if (window.localStorage) window.localStorage.setItem("flightline-volume", String(audio.volume));
            });
        }

        selectSet("kasa");
    }

    document.addEventListener("DOMContentLoaded", function () {
        var panels = document.querySelectorAll("[data-sound-panel]");
        Array.prototype.forEach.call(panels, initSoundPanel);
    });
}());
