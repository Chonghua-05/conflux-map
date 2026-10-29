package cn.net.rms.confluxmap.core.waypoint.list;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** In-memory {@link WaypointPersonalPreferences} for tests. */
public final class InMemoryPreferences implements WaypointPersonalPreferences {
    private final Set<UUID> hidden = new HashSet<>();
    private final Set<String> collapsed = new HashSet<>();
    private long version;

    @Override
    public boolean isWaypointHidden(final UUID waypointId) {
        return hidden.contains(waypointId);
    }

    @Override
    public void setWaypointHidden(final UUID waypointId, final boolean value) {
        if (value) {
            hidden.add(waypointId);
        } else {
            hidden.remove(waypointId);
        }
        version++;
    }

    @Override
    public boolean isGroupCollapsed(final String groupKey) {
        return collapsed.contains(groupKey);
    }

    @Override
    public void setGroupCollapsed(final String groupKey, final boolean value) {
        if (value) {
            collapsed.add(groupKey);
        } else {
            collapsed.remove(groupKey);
        }
        version++;
    }

    @Override
    public long version() {
        return version;
    }
}
