package dev.flightline.network;

import dev.flightline.FlightLineIds;
import dev.flightline.flight.EmergencyStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Authoritative emergency state used by the checklist, HUD and one-key resolve prompt. */
public record EmergencyStatusPayload(int conditions, int available, int recommendedAction,
                                     int recommendedTarget, int fireTicks1, int fireTicks2,
                                     int hotTicks1, int hotTicks2, int extinguisherBottles,
                                     int restartAttempts1, int restartAttempts2, int fuelDumpTicks)
        implements CustomPacketPayload {
    public static final Type<EmergencyStatusPayload> TYPE = new Type<>(FlightLineIds.id("emergency_status"));

    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, EmergencyStatusPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::conditions,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::available,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::recommendedAction,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::recommendedTarget,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::fireTicks1,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::fireTicks2,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::hotTicks1,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::hotTicks2,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::extinguisherBottles,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::restartAttempts1,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::restartAttempts2,
            ByteBufCodecs.VAR_INT, EmergencyStatusPayload::fuelDumpTicks,
            EmergencyStatusPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static EmergencyStatusPayload inactive() {
        return new EmergencyStatusPayload(0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean hasCondition(int condition) {
        return (conditions & condition) != 0;
    }

    public boolean can(int procedure) {
        return (available & procedure) != 0;
    }

    public boolean active() {
        return conditions != 0 || fuelDumpTicks > 0;
    }

    /** Comparison key for throttled updates: timers tick constantly, procedures do not. */
    public EmergencyStatusPayload statusOnly() {
        return new EmergencyStatusPayload(conditions, available, recommendedAction,
                recommendedTarget, 0, 0, 0, 0, extinguisherBottles,
                restartAttempts1, restartAttempts2, 0);
    }

    public EmergencyStatus.Recommendation recommendation() {
        return new EmergencyStatus.Recommendation(recommendedAction, recommendedTarget);
    }
}
