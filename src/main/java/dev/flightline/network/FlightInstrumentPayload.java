package dev.flightline.network;

import dev.flightline.FlightLineIds;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlightInstrumentPayload(int radioAltitudeFeet, int verticalSpeedFpm, float bankDegrees,
                                      float pitchDegrees, float headingDegrees, int gearState, int systems,
                                      boolean autopilot, int warnings, AirframeStatus airframe,
                                      TrafficGuidance traffic, CockpitGuidance cockpitGuidance)
        implements CustomPacketPayload {
    public static final Type<FlightInstrumentPayload> TYPE = new Type<>(FlightLineIds.id("flight_instruments"));
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
            AirframeStatus.CODEC, payload -> payload.airframe(),
            TrafficGuidance.CODEC, payload -> payload.traffic(),
            CockpitGuidance.CODEC, payload -> payload.cockpitGuidance(),
            FlightInstrumentPayload::new
    );

    public int fuelPercent() {
        return airframe.fuelPercent();
    }

    public boolean airbrakes() {
        return airframe.airbrakes();
    }

    public float trafficBearingDegrees() {
        return traffic.bearingDegrees();
    }

    public int trafficRangeBlocks() {
        return traffic.rangeBlocks();
    }

    public int trafficAltitudeFeet() {
        return traffic.altitudeFeet();
    }

    public int trafficCounts() {
        return traffic.counts();
    }

    public IlsGuidance ilsGuidance() {
        return cockpitGuidance.ilsGuidance();
    }

    public AutopilotGuidance autopilotGuidance() {
        return cockpitGuidance.autopilotGuidance();
    }

    /** Airframe state that is not packed into the systems bitfield. */
    public record AirframeStatus(int fuelPercent, boolean airbrakes) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, AirframeStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, status -> status.fuelPercent(),
                ByteBufCodecs.BOOL, status -> status.airbrakes(),
                AirframeStatus::new
        );
    }

    /** Nearest-traffic summary rendered by the TCAS display. */
    public record TrafficGuidance(float bearingDegrees, int rangeBlocks, int altitudeFeet, int counts) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, TrafficGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, traffic -> traffic.bearingDegrees(),
                ByteBufCodecs.VAR_INT, traffic -> traffic.rangeBlocks(),
                ByteBufCodecs.VAR_INT, traffic -> traffic.altitudeFeet(),
                ByteBufCodecs.VAR_INT, traffic -> traffic.counts(),
                TrafficGuidance::new
        );

        public static TrafficGuidance none() {
            return new TrafficGuidance(0.0f, 0, 0, 0);
        }
    }

    public record CockpitGuidance(IlsGuidance ilsGuidance, AutopilotGuidance autopilotGuidance,
                                  WeatherGuidance weatherGuidance) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, CockpitGuidance> CODEC = StreamCodec.composite(
                IlsGuidance.CODEC, guidance -> guidance.ilsGuidance(),
                AutopilotGuidance.CODEC, guidance -> guidance.autopilotGuidance(),
                WeatherGuidance.CODEC, guidance -> guidance.weatherGuidance(),
                CockpitGuidance::new
        );
    }

    public record WeatherGuidance(int windSpeedKnots, int windDirectionDegrees, int turbulencePercent,
                                  int visibilityBlocks, int condition, int icingPercent, boolean antiIceActive,
                                  boolean antiIceEffective, int gustKnots) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, WeatherGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, guidance -> guidance.windSpeedKnots(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.windDirectionDegrees(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.turbulencePercent(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.visibilityBlocks(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.condition(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.icingPercent(),
                ByteBufCodecs.BOOL, guidance -> guidance.antiIceActive(),
                ByteBufCodecs.BOOL, guidance -> guidance.antiIceEffective(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.gustKnots(),
                WeatherGuidance::new
        );

        public static WeatherGuidance defaults() {
            return new WeatherGuidance(0, 0, 0, 320, 0, 0, false, false, 0);
        }
    }

    public record IlsGuidance(boolean active, float localizerDegrees, float glideslopeDegrees,
                              float referenceGlideSlopeDegrees, float runwayHeadingDegrees,
                              float thresholdDistanceBlocks) {
        public IlsGuidance(boolean active, float localizerDegrees, float glideslopeDegrees,
                           float referenceGlideSlopeDegrees) {
            this(active, localizerDegrees, glideslopeDegrees, referenceGlideSlopeDegrees, 0.0f, 0.0f);
        }

        public IlsGuidance(boolean active, float localizerDegrees, float glideslopeDegrees) {
            this(active, localizerDegrees, glideslopeDegrees, 3.0f);
        }

        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, IlsGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, guidance -> guidance.active(),
                ByteBufCodecs.FLOAT, guidance -> guidance.localizerDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.glideslopeDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.referenceGlideSlopeDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.runwayHeadingDegrees(),
                ByteBufCodecs.FLOAT, guidance -> guidance.thresholdDistanceBlocks(),
                IlsGuidance::new
        );

        public static IlsGuidance inactive() {
            return new IlsGuidance(false, 0.0f, 0.0f, 3.0f, 0.0f, 0.0f);
        }
    }

    public record AutopilotGuidance(float targetHeadingDegrees, int targetAltitudeFeet,
                                    int targetVerticalSpeedFpm, int targetAirspeedKnots,
                                    float autoThrottleDeadbandKnots, boolean autolandArmed,
                                    boolean atcControlled) {
        @SuppressWarnings("null")
        public static final StreamCodec<RegistryFriendlyByteBuf, AutopilotGuidance> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, guidance -> guidance.targetHeadingDegrees(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetAltitudeFeet(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetVerticalSpeedFpm(),
                ByteBufCodecs.VAR_INT, guidance -> guidance.targetAirspeedKnots(),
                ByteBufCodecs.FLOAT, guidance -> guidance.autoThrottleDeadbandKnots(),
                ByteBufCodecs.BOOL, guidance -> guidance.autolandArmed(),
                ByteBufCodecs.BOOL, guidance -> guidance.atcControlled(),
                AutopilotGuidance::new
        );

        public static AutopilotGuidance defaults() {
            return new AutopilotGuidance(0.0f, 2000, 1000, 160, 2.0f, false, false);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
