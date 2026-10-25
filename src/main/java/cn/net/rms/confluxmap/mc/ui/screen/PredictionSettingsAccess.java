package cn.net.rms.confluxmap.mc.ui.screen;

/**
 * Snapshot of the server/singleplayer policy gating the prediction settings,
 * consulted when the prediction tab's rows are (re)built. Immutable so
 * {@code tick()} can compare snapshots and rebuild only on change.
 */
record PredictionSettingsAccess(
    boolean underlayDisabledByServer,
    String networkSyncDisabledReasonKey,
    boolean structuresDisabledByServer,
    boolean structureSearchAllowed
) {
    static PredictionSettingsAccess from(
        final boolean singleplayer,
        final boolean seedIndependentUnderlay,
        final boolean seedSharingDisabledByServer,
        final String networkSyncDisabledReasonKey,
        final boolean structureSearchAllowed
    ) {
        return from(
            singleplayer,
            seedIndependentUnderlay,
            seedSharingDisabledByServer,
            false,
            networkSyncDisabledReasonKey,
            structureSearchAllowed
        );
    }

    static PredictionSettingsAccess from(
        final boolean singleplayer,
        final boolean seedIndependentUnderlay,
        final boolean seedSharingDisabledByServer,
        final boolean manualSeedAvailable,
        final String networkSyncDisabledReasonKey,
        final boolean structureSearchAllowed
    ) {
        final boolean remoteSeedDisabled = !singleplayer
            && seedSharingDisabledByServer
            && !manualSeedAvailable;
        return new PredictionSettingsAccess(
            remoteSeedDisabled && !seedIndependentUnderlay,
            networkSyncDisabledReasonKey,
            remoteSeedDisabled,
            structureSearchAllowed
        );
    }

    String disabledReasonKey(final PredictionControl control) {
        return switch (control) {
            case UNDERLAY -> underlayDisabledByServer
                ? "confluxmap.screen.config.prediction.seed_disabled_by_server"
                : null;
            case NETWORK_SYNC -> networkSyncDisabledReasonKey;
            case STRUCTURES -> !structureSearchAllowed
                ? "confluxmap.map.structure_search.disabled_by_server"
                : structuresDisabledByServer
                    ? "confluxmap.screen.config.prediction.seed_disabled_by_server"
                    : null;
        };
    }
}
