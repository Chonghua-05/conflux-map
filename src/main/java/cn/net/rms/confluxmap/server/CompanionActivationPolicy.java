package cn.net.rms.confluxmap.server;

/**
 * Detects whether the companion server endpoint should activate.
 * Dedicated servers and singleplayer integrated worlds activate when configured.
 */
final class CompanionActivationPolicy {
    private CompanionActivationPolicy() {
    }

    @SuppressWarnings("unused")
    static boolean shouldActivate(
        final boolean configured,
        final boolean dedicated,
        final int serverPort
    ) {
        return configured;
    }
}
