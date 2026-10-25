package cn.net.rms.confluxmap.mc.ui.screen;

/** Whether the server's entity-radar policy leaves the radar settings interactive. */
enum RadarSettingsAccess {
    ALLOWED(true, null),
    FORBIDDEN_BY_SERVER(false, "confluxmap.screen.config.radar.disabled_by_server");

    private final boolean controlsActive;
    private final String noticeKey;

    RadarSettingsAccess(final boolean controlsActive, final String noticeKey) {
        this.controlsActive = controlsActive;
        this.noticeKey = noticeKey;
    }

    static RadarSettingsAccess from(final boolean serverAllowsRadar) {
        return serverAllowsRadar ? ALLOWED : FORBIDDEN_BY_SERVER;
    }

    boolean controlsActive() {
        return controlsActive;
    }

    String noticeKey() {
        return noticeKey;
    }

    String tooltipKey() {
        return noticeKey;
    }
}
