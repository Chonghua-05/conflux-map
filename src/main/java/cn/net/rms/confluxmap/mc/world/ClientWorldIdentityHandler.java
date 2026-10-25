package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.ConfluxMapMod;
import net.minecraft.text.Text;

/**
 * Static packet-mixin bridge into the client world identity service. Join and respawn are
 * one-shot events and log every fault; the chat hook runs for every message, so its first
 * fault disables the probe instead of storming the log on a chatty server.
 */
public final class ClientWorldIdentityHandler {
    private static volatile ClientMultiworldService service;
    private static volatile boolean chatDisabledForSession;

    private ClientWorldIdentityHandler() {
    }

    public static void bind(final ClientMultiworldService value) {
        service = value;
    }

    public static void gameJoin(final long seedHash) {
        final ClientMultiworldService current = service;
        if (current != null) {
            try {
                current.onGameJoin(seedHash);
            } catch (final Throwable fault) {
                ConfluxMapMod.LOGGER.error("world identity gameJoin failed for seed hash {}", seedHash, fault);
            }
        }
    }

    public static void respawn(final long seedHash) {
        final ClientMultiworldService current = service;
        if (current != null) {
            try {
                current.onRespawn(seedHash);
            } catch (final Throwable fault) {
                ConfluxMapMod.LOGGER.error("world identity respawn failed for seed hash {}", seedHash, fault);
            }
        }
    }

    /** Returns whether a pending world-detection query consumed this rendered chat message. */
    public static boolean chatMessage(final Text message) {
        final ClientMultiworldService current = service;
        if (current == null || chatDisabledForSession) {
            return false;
        }
        try {
            return current.onVelocityServerMessage(
                VelocityServerTextParser.parse(message),
                VelocityServerTextParser.isCurrentServerNotice(message)
            );
        } catch (final Throwable fault) {
            disableChatForSession(fault);
            return false;
        }
    }

    /** Fails open: an unconsumed message renders normally once the probe is disabled. */
    private static synchronized void disableChatForSession(final Throwable fault) {
        if (chatDisabledForSession) {
            return;
        }
        chatDisabledForSession = true;
        ConfluxMapMod.LOGGER.error("velocity probe chat matching disabled after a fault", fault);
    }
}
