package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import java.util.Objects;
import java.util.UUID;

/**
 * Config-file backed {@link WaypointPersonalPreferences} for shared
 * waypoints. Every change saves the client config immediately so eye and
 * fold state survive restarts, mirroring how the cross-dimension overrides
 * persist.
 */
public final class ConfigWaypointPersonalPreferences implements WaypointPersonalPreferences {
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private long version;

    public ConfigWaypointPersonalPreferences(final ConfluxConfig config, final ConfigIo configIo) {
        this.config = Objects.requireNonNull(config, "config");
        this.configIo = Objects.requireNonNull(configIo, "configIo");
    }

    @Override
    public boolean isWaypointHidden(final UUID waypointId) {
        return config.isHiddenSharedWaypoint(waypointId);
    }

    @Override
    public void setWaypointHidden(final UUID waypointId, final boolean hidden) {
        config.setHiddenSharedWaypoint(waypointId, hidden);
        save();
    }

    @Override
    public boolean isGroupCollapsed(final String groupKey) {
        return config.isSharedWaypointGroupCollapsed(groupKey);
    }

    @Override
    public void setGroupCollapsed(final String groupKey, final boolean collapsed) {
        config.setSharedWaypointGroupCollapsed(groupKey, collapsed);
        save();
    }

    @Override
    public long version() {
        return version;
    }

    private void save() {
        version++;
        configIo.save(config);
    }
}
