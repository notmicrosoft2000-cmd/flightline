package dev.flightline;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Idempotent Minecraft bootstrap for tests that touch payload codecs or registries. */
public final class TestBootstrap {
    private static boolean bootstrapped;

    private TestBootstrap() {
    }

    public static synchronized void ensure() {
        if (bootstrapped) return;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
