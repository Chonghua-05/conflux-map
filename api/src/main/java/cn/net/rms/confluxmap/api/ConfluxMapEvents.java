package cn.net.rms.confluxmap.api;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Mod lifecycle events. Every event fires on the client main thread; payloads are
 * immutable snapshots taken at notification time, except the two request payloads
 * ({@link WaypointListOpening} and {@link TeleportLandingResolve}), which are mutable
 * by design so a listener can answer a query or adjust the state the mod is about to
 * apply.
 */
@ApiStatus.Experimental
public interface ConfluxMapEvents {
    /** Fired when the player joins a world (singleplayer or server). */
    Event<SessionEvent> sessionStarted();

    /** Fired when the player leaves the world; also fires before a switch to another world. */
    Event<SessionEvent> sessionEnded();

    /** Fired when the player changes dimension within the same world. */
    Event<SessionEvent> dimensionChanged();

    /** Fired when the fullscreen map screen opens. */
    Event<MapScreenEvent> fullscreenMapOpened();

    /** Fired when the fullscreen map screen closes, whatever close path was used. */
    Event<MapScreenEvent> fullscreenMapClosed();

    /** Fired after any waypoint mutation, carrying the full new waypoint list. */
    Event<WaypointChangeEvent> waypointsChanged();

    /**
     * Fired while the waypoint-list screen is being created, before its initial state
     * is applied. Listeners may call the request methods on the payload to change the
     * tab and dimension filter the screen opens with; later listeners override earlier
     * ones, and requests that would break the screen's invariants (for example a public
     * tab while shared waypoints are unavailable) are ignored. The informational
     * getters describe the open that is happening.
     */
    Event<WaypointListOpening> waypointListOpening();

    /** Fired after the shared-waypoint catalog changes, carrying the full new list. */
    Event<SharedWaypointChange> sharedWaypointsChanged();

    /** Fired after a fullscreen-map location-menu action runs, whatever it did. */
    Event<LocationActionEvent> locationActionPerformed();

    /**
     * Fired when a teleport target has no landing answer of its own: a known void
     * column, or an unknown column browsed from a non-live session. A listener may
     * call {@link TeleportLandingResolve#setLandingY(int)} to supply the landing
     * height; the mod then sends one teleport command directly to that height. The
     * event is not fired when the two-stage policy can still resolve real ground
     * (a live session with an unknown column), so a resolver never overrides a
     * correct landing.
     */
    Event<TeleportLandingResolve> teleportLandingResolve();

    /** Where the player currently is. Ids mirror the mod's own storage identities. */
    record SessionEvent(String serverId, String worldId, String dimensionId) {
    }

    /** The dimension the fullscreen map was showing. */
    record MapScreenEvent(String dimensionId) {
    }

    /** The complete waypoint list after one mutation (the mod does not report the delta). */
    record WaypointChangeEvent(String worldId, List<WaypointApi.ApiWaypoint> waypoints) {
        public WaypointChangeEvent {
            waypoints = List.copyOf(waypoints);
        }
    }

    /** The complete shared-waypoint list after one catalog change. */
    record SharedWaypointChange(List<SharedWaypointApi.ApiSharedWaypoint> waypoints) {
        public SharedWaypointChange {
            waypoints = List.copyOf(waypoints);
        }
    }

    /** One executed location-menu action; {@code heightKnown} mirrors the target's ground answer. */
    record LocationActionEvent(
        LocationAction action,
        int blockX,
        int blockZ,
        String dimensionId,
        boolean heightKnown
    ) {
    }

    /** Location-menu actions the fullscreen map can run. */
    enum LocationAction {
        TELEPORT,
        SET_WAYPOINT,
        EDIT_WAYPOINT,
        DELETE_WAYPOINT,
        SHARE_LOCATION,
        SHARE_WAYPOINT,
        HIGHLIGHT,
        HIGHLIGHT_WAYPOINT,
        CLEAR_HIGHLIGHT,
        HIGHLIGHT_PLAYER,
        CLEAR_PLAYER_HIGHLIGHT
    }

    /** Which tab the waypoint-list screen opens on. */
    enum WaypointListTab { LOCAL, PUBLIC }

    /**
     * Mutable request payload of {@link #waypointListOpening()}. The request methods
     * never throw; the screen applies whatever the last listener left behind.
     */
    final class WaypointListOpening {
        private final boolean openedFromHotkey;
        private final boolean sharedWaypointsEnabled;
        private WaypointListTab requestedTab;
        private boolean requestedAllDimensions;

        public WaypointListOpening(final boolean openedFromHotkey, final boolean sharedWaypointsEnabled) {
            this.openedFromHotkey = openedFromHotkey;
            this.sharedWaypointsEnabled = sharedWaypointsEnabled;
        }

        /** Whether the screen is being opened by the mod's own waypoints hotkey. */
        public boolean openedFromHotkey() {
            return openedFromHotkey;
        }

        /** Whether shared waypoints are currently usable; a public tab needs this. */
        public boolean sharedWaypointsEnabled() {
            return sharedWaypointsEnabled;
        }

        /** The tab requested so far, or {@code null} to keep the screen's default. */
        public WaypointListTab requestedTab() {
            return requestedTab;
        }

        /** Asks the screen to open on this tab; requesting {@code PUBLIC} without shared waypoints keeps {@code LOCAL}. */
        public void requestTab(final WaypointListTab tab) {
            this.requestedTab = Objects.requireNonNull(tab, "tab");
        }

        /** Whether any listener asked for the all-dimensions filter. */
        public boolean requestedAllDimensions() {
            return requestedAllDimensions;
        }

        /** Asks the screen to list waypoints from every dimension instead of the current one. */
        public void requestAllDimensions() {
            this.requestedAllDimensions = true;
        }
    }

    /** Mutable query payload of {@link #teleportLandingResolve()}. */
    final class TeleportLandingResolve {
        private final int blockX;
        private final int blockZ;
        private final String dimensionId;
        private final boolean voidColumn;
        private Integer landingY;

        public TeleportLandingResolve(
            final int blockX,
            final int blockZ,
            final String dimensionId,
            final boolean voidColumn
        ) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
            this.voidColumn = voidColumn;
        }

        public int blockX() {
            return blockX;
        }

        public int blockZ() {
            return blockZ;
        }

        public String dimensionId() {
            return dimensionId;
        }

        /**
         * Whether the column is a captured void (definitely no surface). When false the
         * column is simply unknown - never captured, and no live chunk can resolve it.
         */
        public boolean voidColumn() {
            return voidColumn;
        }

        /** The landing height supplied so far, or empty when no listener answered. */
        public OptionalInt landingY() {
            return landingY == null ? OptionalInt.empty() : OptionalInt.of(landingY);
        }

        /** Supplies the height the player should land at; later listeners override earlier ones. */
        public void setLandingY(final int landingY) {
            this.landingY = landingY;
        }

        /** Withdraws a previously supplied height. */
        public void clearLandingY() {
            this.landingY = null;
        }
    }
}
