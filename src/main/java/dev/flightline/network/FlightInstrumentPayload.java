package dev.flightline.network;

import dev.flightline.FlightLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightInstrumentPayload(int radioAltitudeFeet, int verticalSpeedFpm, float bankDegrees,
                                      float pitchDegrees, float headingDegrees, int gearState, int systems,
                                      boolean autopilot, int warnings,
                                      float trafficBearingDegrees, int trafficRangeBlocks, int trafficAltitudeFeet,
                                      int trafficCounts, CockpitGuidance cockpitGuidance)
        implements CustomPacketPayload {
    public static final Type<FlightInstrumentPayload> TYPE = new Type<>(FlightLine.id("flight_instruments"));
    @SuppressWarnings("null")
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightInstrumentPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, payload -> payload.radioAltitudeFeet(),
            ByteBufCodecs.VAR_INT, payload -> payload.verticalSpeedFpm(),
            ByteBufCodecs.FLOAT, payload -> payload.bankDegrees(),
            ByteBufCodecs.FLOAT, payload -> payload.pitchDegrees(),
            ByteBufCodecs.FLOAT, payload -> payload.headingDegrees(),
            ByteBufCodecs.VAR_INT, payload -> payload.gearState(),
            ByteBufCodecs.VAR_INT, payload -> payload.systems(),
            ByteBufCodecs.BOOL, payload -> payload.autopilot(),
            ByteBufCodecs.VAR_INT, payload -> payload.warnings(),
            ByteBufCodecs.FLOAT, payload -> payload.trafficBearingDegrees(),
            ByteBufCodecs.VAR_INT, payload -> payload.trafficRangeBlocks(),
            ByteBufCodecs.VAR_INT, payload -> payload.trafficAltitudeFeet(),
            ByteBufCodecs.VAR_INT, payload -> payload.trafficCounts(),
            CockpitGuidance.CODEC, payload -> payload.cockpitGuidance(),
            FlightInstrumentPayload::new
    );

    public IlsGuidance ilsGuidance() {
        return cockpitGuidance.ilsGuidance();
    }

    public AutopilotGuidance autopilotGuidance() {
        return cockpitGuidance.autopilotGuidance();
    }

    public record CockpitGuidance(IlsGuidance ilsGuidance, AutopilotGuidance autopilotGuidance) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, CockpitGuidance> CODEC = StreamCodec.composite(
                IlsGuidance.CODEC, guidance -> guidance.ilsGuidance(),
                AutopilotGuidance.CODEC, guidance -> guidance.autopilotGuidance(),
                CockpitGuidance::new
        );
    }

    public record IlsGuidance(boolean active, float localizerDegrees, float glideslopeDegrees,
                              float referenceGlideSlopeDegrees) {
        public IlsGuidance(boolean active, float localizerDegrees, float glideslopeDegrees) {
            this(active, localizerDegrees, glideslopeDegrees, 3.0f);
        }

        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, IlsGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, guidance -> guidance.active(),
                ByteBufCodecs.FLOAT, guidance -> guidance.localizerDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.glideslopeDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.referenceGlideSlopeDegrees(),
                IlsGuidance::new
        );

        public static IlsGuidance inactive() {
            return new IlsGuidance(false, 0.0f, 0.0f, 3.0f);
        }
    }

    public record AutopilotGuidance(float targetHeadingDegrees, int targetAltitudeFeet,
                                    int targetVerticalSpeedFpm, int targetAirspeedKnots,
                                    float autoThrottleDeadbandKnots, boolean autolandArmed) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, AutopilotGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, guidance -> guidance.targetHeadingDegrees(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetAltitudeFeet(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetVerticalSpeedFpm(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetAirspeedKnots(),
                ByteBufCodecs.FLOAT, guidance -> guidance.autoThrottleDeadbandKnots(),
                ByteBufCodecs.BOOL, guidance -> guidance.autolandArmed(),
                AutopilotGuidance::new
        );

        public static AutopilotGuidance defaults() {
            return new AutopilotGuidance(0.0f, 2000, 1000, 160, 2.0f, false);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
