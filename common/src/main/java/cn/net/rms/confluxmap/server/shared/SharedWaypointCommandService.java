package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointGroup;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Platform-neutral command use cases for server-owned shared waypoints. */
public final class SharedWaypointCommandService {
    public static final int PAGE_SIZE = 6;
    public static final int DEFAULT_COLOR_ARGB = 0xFF3498DB;
    /** Group-assignment argument that clears group membership instead of naming a group. */
    public static final String UNGROUPED = "none";

    private static final int MIN_ID_PREFIX_LENGTH = 8;
    private static final Pattern COMPACT_ID = Pattern.compile("[0-9a-f]{1,32}");

    public enum Status {
        APPLIED,
        REJECTED,
        INVALID_ID,
        UNKNOWN_ID,
        AMBIGUOUS_ID,
        FORBIDDEN
    }

    public record Position(DimensionId dimensionId, double x, double y, double z) {
        public Position {
            Objects.requireNonNull(dimensionId, "dimensionId");
        }
    }

    public record Entry(String idPrefix, SharedWaypoint waypoint, String xaeroMessage) {
        public Entry {
            Objects.requireNonNull(idPrefix, "idPrefix");
            Objects.requireNonNull(waypoint, "waypoint");
            Objects.requireNonNull(xaeroMessage, "xaeroMessage");
        }
    }

    public record GroupEntry(String idPrefix, SharedWaypointGroup group) {
        public GroupEntry {
            Objects.requireNonNull(idPrefix, "idPrefix");
            Objects.requireNonNull(group, "group");
        }
    }

    public record Page(
        boolean valid,
        int page,
        int totalPages,
        int totalWaypoints,
        List<Entry> entries
    ) {
        public Page {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    public record Result(
        Status status,
        SharedWaypointService.MutationResult mutation,
        SharedWaypoint waypoint,
        SharedWaypointGroup group
    ) {
        public Result {
            Objects.requireNonNull(status, "status");
        }

        public Result(
            final Status status,
            final SharedWaypointService.MutationResult mutation,
            final SharedWaypoint waypoint
        ) {
            this(status, mutation, waypoint, null);
        }

        public boolean applied() {
            return status == Status.APPLIED;
        }
    }

    private record Resolution(Status status, SharedWaypoint waypoint) {
    }

    private record GroupResolution(Status status, SharedWaypointGroup group) {
    }

    private final SharedWaypointService service;
    private final Supplier<UUID> operationIds;
    private final Consumer<SharedWaypointService.MutationResult> appliedMutation;

    public SharedWaypointCommandService(
        final SharedWaypointService service,
        final Supplier<UUID> operationIds,
        final Consumer<SharedWaypointService.MutationResult> appliedMutation
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
        this.appliedMutation = Objects.requireNonNull(appliedMutation, "appliedMutation");
    }

    public Page list(final int requestedPage) {
        final List<SharedWaypoint> waypoints = service.snapshot().waypoints();
        final int totalPages = Math.max(1, (waypoints.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (requestedPage < 1 || requestedPage > totalPages) {
            return new Page(false, requestedPage, totalPages, waypoints.size(), List.of());
        }
        final int from = (requestedPage - 1) * PAGE_SIZE;
        final int to = Math.min(waypoints.size(), from + PAGE_SIZE);
        final List<Entry> entries = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            final SharedWaypoint waypoint = waypoints.get(index);
            entries.add(new Entry(
                uniquePrefix(waypoint.id(), waypoints),
                waypoint,
                WaypointChatCodec.formatXaero(
                    waypoint.name(), waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z(),
                    waypoint.colorArgb()
                )
            ));
        }
        return new Page(true, requestedPage, totalPages, waypoints.size(), entries);
    }

    public Result createHere(
        final SharedWaypointService.Actor actor,
        final Position position,
        final String name
    ) {
        Objects.requireNonNull(position, "position");
        return mutation(service.create(
            actor,
            new SharedWaypointService.CreateRequest(
                operationId(), service.snapshot().revision(), name, position.dimensionId(),
                position.x(), position.y(), position.z(), DEFAULT_COLOR_ARGB, Waypoint.Type.NORMAL
            )
        ));
    }

    public Result rename(
        final SharedWaypointService.Actor actor,
        final String idPrefix,
        final String name
    ) {
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return update(actor, waypoint, name, new Position(
            waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z()
        ));
    }

    public Result moveHere(
        final SharedWaypointService.Actor actor,
        final String idPrefix,
        final Position position
    ) {
        Objects.requireNonNull(position, "position");
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return update(actor, waypoint, waypoint.name(), position);
    }

    public Result delete(final SharedWaypointService.Actor actor, final String idPrefix) {
        Objects.requireNonNull(actor, "actor");
        if (!actor.operator()) {
            return new Result(Status.FORBIDDEN, null, null);
        }
        final Resolution resolution = resolve(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        return mutation(service.delete(
            actor,
            new SharedWaypointService.DeleteRequest(operationId(), waypoint.revision(), waypoint.id())
        ));
    }

    /** All groups with command-friendly unique id prefixes, in server creation order. */
    public List<GroupEntry> listGroups() {
        final List<SharedWaypointGroup> groups = service.groups();
        final List<GroupEntry> entries = new ArrayList<>(groups.size());
        for (final SharedWaypointGroup group : groups) {
            entries.add(new GroupEntry(uniqueGroupPrefix(group.id(), groups), group));
        }
        return entries;
    }

    public Result createGroup(final SharedWaypointService.Actor actor, final String name) {
        Objects.requireNonNull(actor, "actor");
        return groupMutation(service.createGroup(
            actor,
            new SharedWaypointService.GroupCreateRequest(
                operationId(), service.snapshot().revision(), name
            )
        ));
    }

    public Result renameGroup(
        final SharedWaypointService.Actor actor,
        final String idPrefix,
        final String name
    ) {
        Objects.requireNonNull(actor, "actor");
        final GroupResolution resolution = resolveGroup(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null, null);
        }
        final SharedWaypointGroup group = resolution.group();
        return groupMutation(service.renameGroup(
            actor,
            new SharedWaypointService.GroupRenameRequest(
                operationId(), group.revision(), group.id(), name
            )
        ));
    }

    public Result deleteGroup(final SharedWaypointService.Actor actor, final String idPrefix) {
        Objects.requireNonNull(actor, "actor");
        final GroupResolution resolution = resolveGroup(idPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null, null);
        }
        final SharedWaypointGroup group = resolution.group();
        return groupMutation(service.deleteGroup(
            actor,
            new SharedWaypointService.GroupDeleteRequest(operationId(), group.revision(), group.id())
        ));
    }

    /**
     * Moves one shared waypoint into a group. {@code groupIdPrefix} resolving to no group clears
     * the membership; pass {@link #UNGROUPED} to state that explicitly.
     */
    public Result setWaypointGroup(
        final SharedWaypointService.Actor actor,
        final String waypointIdPrefix,
        final String groupIdPrefix
    ) {
        Objects.requireNonNull(actor, "actor");
        final Resolution resolution = resolve(waypointIdPrefix);
        if (resolution.status() != Status.APPLIED) {
            return new Result(resolution.status(), null, null);
        }
        final SharedWaypoint waypoint = resolution.waypoint();
        final UUID groupId;
        if (groupIdPrefix == null || UNGROUPED.equalsIgnoreCase(groupIdPrefix.strip())) {
            groupId = null;
        } else {
            final GroupResolution groupResolution = resolveGroup(groupIdPrefix);
            if (groupResolution.status() != Status.APPLIED) {
                return new Result(groupResolution.status(), null, null, null);
            }
            groupId = groupResolution.group().id();
        }
        return mutation(service.setWaypointGroup(
            actor,
            new SharedWaypointService.SetGroupRequest(
                operationId(), waypoint.revision(), waypoint.id(), groupId
            )
        ));
    }

    private Result update(
        final SharedWaypointService.Actor actor,
        final SharedWaypoint waypoint,
        final String name,
        final Position position
    ) {
        return mutation(service.update(
            actor,
            new SharedWaypointService.UpdateRequest(
                operationId(), waypoint.revision(), waypoint.id(), name, position.dimensionId(),
                position.x(), position.y(), position.z(), waypoint.colorArgb(), waypoint.type()
            )
        ));
    }

    private Result mutation(final SharedWaypointService.MutationResult mutation) {
        if (!mutation.applied()) {
            return new Result(Status.REJECTED, mutation, null);
        }
        if (!mutation.replayed() && mutation.delta().kind() != SharedWaypointStore.DeltaKind.NOOP) {
            appliedMutation.accept(mutation);
        }
        final SharedWaypoint waypoint = mutation.delta().kind() == SharedWaypointStore.DeltaKind.UPSERT
            ? mutation.delta().waypoint()
            : null;
        return new Result(Status.APPLIED, mutation, waypoint);
    }

    private Result groupMutation(final SharedWaypointService.MutationResult mutation) {
        if (!mutation.applied()) {
            return new Result(Status.REJECTED, mutation, null);
        }
        if (!mutation.replayed() && mutation.delta().kind() != SharedWaypointStore.DeltaKind.NOOP) {
            appliedMutation.accept(mutation);
        }
        final SharedWaypointGroup group = mutation.delta().kind() == SharedWaypointStore.DeltaKind.GROUP_UPSERT
            ? mutation.delta().group()
            : null;
        return new Result(Status.APPLIED, mutation, null, group);
    }

    private Resolution resolve(final String input) {
        if (input == null) {
            return new Resolution(Status.INVALID_ID, null);
        }
        final String compact = input.strip().toLowerCase(Locale.ROOT).replace("-", "");
        if (!COMPACT_ID.matcher(compact).matches()) {
            return new Resolution(Status.INVALID_ID, null);
        }
        SharedWaypoint match = null;
        for (final SharedWaypoint waypoint : service.snapshot().waypoints()) {
            if (!compact(waypoint.id()).startsWith(compact)) {
                continue;
            }
            if (match != null) {
                return new Resolution(Status.AMBIGUOUS_ID, null);
            }
            match = waypoint;
        }
        return match == null
            ? new Resolution(Status.UNKNOWN_ID, null)
            : new Resolution(Status.APPLIED, match);
    }

    private GroupResolution resolveGroup(final String input) {
        if (input == null) {
            return new GroupResolution(Status.INVALID_ID, null);
        }
        final String compact = input.strip().toLowerCase(Locale.ROOT).replace("-", "");
        if (!COMPACT_ID.matcher(compact).matches()) {
            return new GroupResolution(Status.INVALID_ID, null);
        }
        SharedWaypointGroup match = null;
        for (final SharedWaypointGroup group : service.groups()) {
            if (!compact(group.id()).startsWith(compact)) {
                continue;
            }
            if (match != null) {
                return new GroupResolution(Status.AMBIGUOUS_ID, null);
            }
            match = group;
        }
        return match == null
            ? new GroupResolution(Status.UNKNOWN_ID, null)
            : new GroupResolution(Status.APPLIED, match);
    }

    private UUID operationId() {
        return Objects.requireNonNull(operationIds.get(), "operation id");
    }

    private static String uniquePrefix(
        final UUID id,
        final List<SharedWaypoint> waypoints
    ) {
        final String compact = compact(id);
        for (int length = MIN_ID_PREFIX_LENGTH; length < compact.length(); length++) {
            final String prefix = compact.substring(0, length);
            boolean unique = true;
            for (final SharedWaypoint waypoint : waypoints) {
                if (!waypoint.id().equals(id) && compact(waypoint.id()).startsWith(prefix)) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                return prefix;
            }
        }
        return compact;
    }

    private static String compact(final UUID id) {
        return id.toString().replace("-", "");
    }

    private static String uniqueGroupPrefix(
        final UUID id,
        final List<SharedWaypointGroup> groups
    ) {
        final String compact = compact(id);
        for (int length = MIN_ID_PREFIX_LENGTH; length < compact.length(); length++) {
            final String prefix = compact.substring(0, length);
            boolean unique = true;
            for (final SharedWaypointGroup group : groups) {
                if (!group.id().equals(id) && compact(group.id()).startsWith(prefix)) {
                    unique = false;
                    break;
                }
            }
            if (unique) {
                return prefix;
            }
        }
        return compact;
    }
}
