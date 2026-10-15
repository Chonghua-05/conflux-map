package cn.net.rms.confluxmap.core.api;

import cn.net.rms.confluxmap.api.ActionApi;
import cn.net.rms.confluxmap.api.ConfluxMapApi;
import cn.net.rms.confluxmap.api.ConfluxMapEvents;
import cn.net.rms.confluxmap.api.Event;
import cn.net.rms.confluxmap.api.MapDataApi;
import cn.net.rms.confluxmap.api.SharedWaypointApi;
import cn.net.rms.confluxmap.api.WaypointApi;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Assembles the public API over the live client services. Constructed (and
 * {@link #install()}ed) by the Fabric composition root once every service is wired; the
 * session tracker drives {@link #onSessionChanged} as the last listener of its chain so
 * plugins observe a session only after the mod's own listeners have applied it.
 */
public final class ConfluxMapApiImpl implements ConfluxMapApi {
    private final String version;
    private final WaypointService waypointService;
    private final WaypointApi waypoints;
    private final SharedWaypointApi sharedWaypoints;
    private final MapDataApi mapData;
    private final CustomMarkerService markers;
    private final ActionApi actions;
    private final EventBus events = new EventBus();
    private SessionGuard.Session lastSeen = SessionGuard.Session.NONE;

    public ConfluxMapApiImpl(
        final String version,
        final GameBridge bridge,
        final WaypointService waypointService,
        final SessionGuard guard,
        final MapWorldService mapWorlds,
        final TileService tiles,
        final PredictionTileService prediction,
        final DaylightModel daylight,
        final CustomMarkerService markers,
        final ActionApi actions,
        final Supplier<List<SharedWaypoint>> sharedWaypointList,
        final Supplier<SharedWaypointAvailability> sharedWaypointAvailability
    ) {
        this.version = version;
        this.waypointService = waypointService;
        this.waypoints = new ApiWaypoints(bridge, waypointService);
        this.sharedWaypoints = new ApiSharedWaypoints(sharedWaypointList, sharedWaypointAvailability);
        this.mapData = new ApiMapData(guard, mapWorlds, tiles, prediction, daylight);
        this.markers = markers;
        this.actions = actions;
    }

    @Override
    public String version() {
        return version;
    }

    @Override
    public ConfluxMapEvents events() {
        return events;
    }

    @Override
    public WaypointApi waypoints() {
        return waypoints;
    }

    @Override
    public SharedWaypointApi sharedWaypoints() {
        return sharedWaypoints;
    }

    @Override
    public MapDataApi mapData() {
        return mapData;
    }

    @Override
    public CustomMarkerService markers() {
        return markers;
    }

    @Override
    public ActionApi actions() {
        return actions;
    }

    public void install() {
        ConfluxMapApi.install(this);
    }

    public void shutdown() {
        ConfluxMapApi.uninstall();
    }

    /** Session-tracker listener (main thread, last in the chain). */
    public void onSessionChanged(final SessionGuard.Session session) {
        final SessionGuard.Session before = lastSeen;
        lastSeen = session;
        if (before.active() && !session.active()) {
            events.fireSessionEnded(before);
        } else if (!before.active() && session.active()) {
            events.fireSessionStarted(session);
        } else if (before.active()) {
            if (!before.world().equals(session.world())) {
                events.fireSessionEnded(before);
                events.fireSessionStarted(session);
            } else if (!before.dimension().equals(session.dimension())) {
                events.dimensionChanged().fire(eventOf(session));
            }
        }
    }

    /** WaypointService change listener (main thread). */
    public void onWaypointsChanged(final List<Waypoint> waypoints) {
        final WaypointStore store = waypointService.current();
        final String worldId = store == null ? "" : store.world().worldId();
        events.waypointsChanged().fire(
            new ConfluxMapEvents.WaypointChangeEvent(worldId, ApiMappers.toApi(waypoints))
        );
    }

    /** Fullscreen map screen lifecycle hooks (render thread). */
    public void fireFullscreenMapOpened(final DimensionId dimension) {
        events.fullscreenMapOpened().fire(
            new ConfluxMapEvents.MapScreenEvent(dimension.toString())
        );
    }

    public void fireFullscreenMapClosed(final DimensionId dimension) {
        events.fullscreenMapClosed().fire(
            new ConfluxMapEvents.MapScreenEvent(dimension.toString())
        );
    }

    /** Shared-waypoint catalog change listener (main thread). */
    public void onSharedWaypointsChanged(final List<SharedWaypoint> waypoints) {
        events.sharedWaypointsChanged().fire(
            new ConfluxMapEvents.SharedWaypointChange(ApiSharedWaypoints.toApi(waypoints))
        );
    }

    /**
     * Fires {@link ConfluxMapEvents#waypointListOpening()} and returns the payload so the
     * opening screen can apply whatever the listeners requested (main thread).
     */
    public ConfluxMapEvents.WaypointListOpening queryWaypointListOpening(
        final boolean openedFromHotkey,
        final boolean sharedWaypointsEnabled
    ) {
        final ConfluxMapEvents.WaypointListOpening opening = new ConfluxMapEvents.WaypointListOpening(
            openedFromHotkey, sharedWaypointsEnabled
        );
        events.waypointListOpening().fire(opening);
        return opening;
    }

    /**
     * Fires {@link ConfluxMapEvents#teleportLandingResolve()} and returns the supplied
     * landing height, if any. Main thread only; the teleport paths consult this only
     * when they have no landing answer of their own.
     */
    public OptionalInt resolveTeleportLanding(
        final int blockX,
        final int blockZ,
        final DimensionId dimension,
        final boolean voidColumn
    ) {
        final ConfluxMapEvents.TeleportLandingResolve resolve = new ConfluxMapEvents.TeleportLandingResolve(
            blockX, blockZ, dimension.toString(), voidColumn
        );
        events.teleportLandingResolve().fire(resolve);
        return resolve.landingY();
    }

    /** Location-menu action report hook (main thread). */
    public void fireLocationActionPerformed(
        final ConfluxMapEvents.LocationAction action,
        final int blockX,
        final int blockZ,
        final DimensionId dimension,
        final boolean heightKnown
    ) {
        events.locationActionPerformed().fire(
            new ConfluxMapEvents.LocationActionEvent(action, blockX, blockZ, dimension.toString(), heightKnown)
        );
    }

    /** Runs {@code body} on the main thread; completes synchronously when already there. */
    public static <T> CompletableFuture<T> callOnMain(final GameBridge bridge, final Supplier<T> body) {
        if (bridge.isOnRenderThread()) {
            try {
                return CompletableFuture.completedFuture(body.get());
            } catch (final Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        bridge.runOnRenderThread(() -> {
            try {
                result.complete(body.get());
            } catch (final Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private static ConfluxMapEvents.SessionEvent eventOf(final SessionGuard.Session session) {
        return new ConfluxMapEvents.SessionEvent(
            session.world().serverId(), session.world().worldId(), session.dimension().toString()
        );
    }

    private static final class EventBus implements ConfluxMapEvents {
        private final Event<SessionEvent> sessionStarted = new Event<>();
        private final Event<SessionEvent> sessionEnded = new Event<>();
        private final Event<SessionEvent> dimensionChanged = new Event<>();
        private final Event<MapScreenEvent> fullscreenMapOpened = new Event<>();
        private final Event<MapScreenEvent> fullscreenMapClosed = new Event<>();
        private final Event<WaypointChangeEvent> waypointsChanged = new Event<>();
        private final Event<WaypointListOpening> waypointListOpening = new Event<>();
        private final Event<SharedWaypointChange> sharedWaypointsChanged = new Event<>();
        private final Event<LocationActionEvent> locationActionPerformed = new Event<>();
        private final Event<TeleportLandingResolve> teleportLandingResolve = new Event<>();

        void fireSessionStarted(final SessionGuard.Session session) {
            sessionStarted.fire(eventOf(session));
        }

        void fireSessionEnded(final SessionGuard.Session session) {
            sessionEnded.fire(eventOf(session));
        }

        @Override
        public Event<SessionEvent> sessionStarted() {
            return sessionStarted;
        }

        @Override
        public Event<SessionEvent> sessionEnded() {
            return sessionEnded;
        }

        @Override
        public Event<SessionEvent> dimensionChanged() {
            return dimensionChanged;
        }

        @Override
        public Event<MapScreenEvent> fullscreenMapOpened() {
            return fullscreenMapOpened;
        }

        @Override
        public Event<MapScreenEvent> fullscreenMapClosed() {
            return fullscreenMapClosed;
        }

        @Override
        public Event<WaypointChangeEvent> waypointsChanged() {
            return waypointsChanged;
        }

        @Override
        public Event<WaypointListOpening> waypointListOpening() {
            return waypointListOpening;
        }

        @Override
        public Event<SharedWaypointChange> sharedWaypointsChanged() {
            return sharedWaypointsChanged;
        }

        @Override
        public Event<LocationActionEvent> locationActionPerformed() {
            return locationActionPerformed;
        }

        @Override
        public Event<TeleportLandingResolve> teleportLandingResolve() {
            return teleportLandingResolve;
        }
    }
}
