package dev.flightline.flight;

/** Authoritative lateral navigation source used by the player's autopilot. */
public enum NavMode {
    HDG,
    LNAV,
    ILS;

    public static NavMode fromId(int id) {
        NavMode[] values = values();
        return id >= 0 && id < values.length ? values[id] : HDG;
    }

    public int id() {
        return ordinal();
    }
}
