package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ConfigScreenPredictionPolicyTest {
    @Test
    void withheldSeedDisablesSeedDependentControlsButNotServerMapSync() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(false, false, true, null, true);

        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(PredictionControl.UNDERLAY)
        );
        assertNull(access.disabledReasonKey(PredictionControl.NETWORK_SYNC));
        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(PredictionControl.STRUCTURES)
        );
    }

    @Test
    void seedIndependentFlatUnderlayStaysUsableWithoutEnablingStructureControls() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(false, true, true, null, true);

        assertNull(access.disabledReasonKey(PredictionControl.UNDERLAY));
        assertEquals(
            "confluxmap.screen.config.prediction.seed_disabled_by_server",
            access.disabledReasonKey(PredictionControl.STRUCTURES)
        );
    }

    @Test
    void clientSeedFallbackReenablesSeedDependentControlsWhenServerWithholdsSeed() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(false, false, true, true, null, true);

        assertNull(access.disabledReasonKey(PredictionControl.UNDERLAY));
        assertNull(access.disabledReasonKey(PredictionControl.STRUCTURES));
    }

    @Test
    void localSeedOverrideKeepsSeedControlsAvailableWhenServerSharesSeed() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(false, false, false, true, null, true);

        assertNull(access.disabledReasonKey(PredictionControl.UNDERLAY));
        assertNull(access.disabledReasonKey(PredictionControl.STRUCTURES));
    }

    @Test
    void directServerPoliciesExplainCorrectionAndStructureControls() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(
                false,
                false,
                false,
                "confluxmap.screen.config.prediction.sync_disabled_by_server",
                false
            );

        assertNull(access.disabledReasonKey(PredictionControl.UNDERLAY));
        assertEquals(
            "confluxmap.screen.config.prediction.sync_disabled_by_server",
            access.disabledReasonKey(PredictionControl.NETWORK_SYNC)
        );
        assertEquals(
            "confluxmap.map.structure_search.disabled_by_server",
            access.disabledReasonKey(PredictionControl.STRUCTURES)
        );
    }

    @Test
    void singleplayerNeverTreatsSeedSharingAsARestriction() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(true, false, true, null, true);

        assertNull(access.disabledReasonKey(PredictionControl.UNDERLAY));
        assertNull(access.disabledReasonKey(PredictionControl.STRUCTURES));
    }

    @Test
    void incompatibleServerUsesItsSpecificMapSyncExplanation() {
        final PredictionSettingsAccess access =
            PredictionSettingsAccess.from(
                false,
                false,
                false,
                "confluxmap.screen.config.prediction.sync_incompatible_server",
                true
            );

        assertEquals(
            "confluxmap.screen.config.prediction.sync_incompatible_server",
            access.disabledReasonKey(PredictionControl.NETWORK_SYNC)
        );
    }
}
