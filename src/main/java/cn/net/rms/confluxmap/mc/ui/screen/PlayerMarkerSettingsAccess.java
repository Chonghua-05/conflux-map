package cn.net.rms.confluxmap.mc.ui.screen;

/** Whether a resource pack currently overrides the player marker icon style. */
record PlayerMarkerSettingsAccess(boolean resourceOverride) {
    static PlayerMarkerSettingsAccess from(final boolean resourceOverride) {
        return new PlayerMarkerSettingsAccess(resourceOverride);
    }

    boolean controlsActive() {
        return !resourceOverride;
    }

    String tooltipKey() {
        return resourceOverride
            ? "confluxmap.config.player_marker.disabled_by_resource_pack"
            : null;
    }
}
