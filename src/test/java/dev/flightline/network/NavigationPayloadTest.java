package dev.flightline.network;

import dev.flightline.TestBootstrap;
import dev.flightline.flight.NavMode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationPayloadTest {
    @BeforeAll
    static void bootstrapRegistries() {
        TestBootstrap.ensure();
    }

    @Test
    void navDataSnapshotRoundTrips() {
        NavDataPayload payload = new NavDataPayload(NavDataPayload.VERSION, 32, -64,
                List.of(new NavDataPayload.Airport(11, "FL11", 100, 200, 80, List.of(111, 112))),
                List.of(new NavDataPayload.Runway(111, 11, "09", -100, 200, 270.0f, 3.0f),
                        new NavDataPayload.Runway(112, 11, "27", 300, 200, 90.0f, 3.0f)),
                List.of(new NavDataPayload.Fix(115, "F11", 100, 200, NavDataPayload.Fix.WAYPOINT),
                        new NavDataPayload.Fix(116, "V11", 612, 200, NavDataPayload.Fix.VOR)));

        assertEquals(payload, roundTrip(NavDataPayload.CODEC, payload));
    }

    @Test
    void flightPlanActionsRoundTrip() {
        for (FileFlightPlanPayload.Action action : FileFlightPlanPayload.Action.values()) {
            FileFlightPlanPayload payload = new FileFlightPlanPayload(11, 12, List.of(115, 116),
                    14_000, 13, action);
            assertEquals(payload, roundTrip(FileFlightPlanPayload.CODEC, payload));
            assertEquals(action, FileFlightPlanPayload.Action.fromId(action.id()));
        }
        assertEquals(FileFlightPlanPayload.Action.FILE, FileFlightPlanPayload.Action.fromId(99));
    }

    @Test
    void flightStateCarriesAuthoritativeNavMode() {
        FlightStatePayload payload = new FlightStatePayload(true, 2, NavMode.LNAV.id(), 1.5f, 0.75f,
                1.92f, 0.1f, 0.2f, -0.3f);

        assertEquals(payload, roundTrip(FlightStatePayload.CODEC, payload));
        assertEquals(NavMode.LNAV, NavMode.fromId(payload.navMode()));
        assertEquals(NavMode.HDG, NavMode.fromId(99));
    }

    @Test
    void flightPlanStateRoundTrips() {
        FlightPlanPayload payload = new FlightPlanPayload(7, 3, 1, 11, 12, 13, 15_000,
                111, 122, List.of(115, 116));

        assertEquals(payload, roundTrip(FlightPlanPayload.CODEC, payload));
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        ByteBuf raw = Unpooled.buffer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(raw, RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
