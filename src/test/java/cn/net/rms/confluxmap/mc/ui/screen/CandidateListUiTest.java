package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CandidateListUiTest {
    @Test
    void candidatesUseOneLineWithOneWaypointAction() {
        final CandidateListUi ui = new CandidateListUi(240, 20, 230, 10, 0);

        assertEquals(4, ui.visibleRows());
        assertEquals(24, ui.rowHeight());
        assertEquals(150, ui.textWidth());
        assertEquals(174, ui.actionX());
        assertEquals(76, ui.actionWidth());
        assertEquals(114, ui.waypointButtonY(0));
        assertEquals(135, ui.dividerY(0));
    }

    @Test
    void onlyTheNonButtonPartOfAVisibleCandidateLocatesIt() {
        final CandidateListUi ui = new CandidateListUi(240, 20, 230, 10, 2);

        assertEquals(2, ui.candidateAt(20, 112));
        assertEquals(5, ui.candidateAt(173, 207));
        assertEquals(-1, ui.candidateAt(174, 112));
        assertEquals(-1, ui.candidateAt(19, 112));
        assertEquals(-1, ui.candidateAt(20, 208));
        assertEquals(-1, new CandidateListUi(240, 20, 230, 1, 0).candidateAt(20, 136));
    }

    @Test
    void scrollOffsetIsClampedToTheSharedVisibleRowCount() {
        final CandidateListUi ui = new CandidateListUi(240, 20, 230, 10, 20);

        assertEquals(6, ui.scrollOffset());
        assertEquals(112, ui.rowY(6));
    }

    @Test
    void separatorsOnlyCoverRenderedCandidates() {
        assertEquals(1, new CandidateListUi(240, 20, 230, 1, 0).renderedRows());
        assertEquals(4, new CandidateListUi(240, 20, 230, 10, 0).renderedRows());
    }

    @Test
    void coordinateAndDistanceFormattingIsShared() {
        assertEquals("-120, 34", CandidateListUi.coordinateText(-120, 34));
        assertEquals(125, CandidateListUi.distanceInBlocks(-120, 34, 0, 0));
    }
}
