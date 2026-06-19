package com.enthusia.koth.infrastructure.gui;

import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.TeamMode;

public final class StartFlowSession {
    private KothFamily family = KothFamily.CAPTURE;
    private TeamMode teamMode = TeamMode.SOLO;
    private boolean advanced;

    public KothFamily family() {
        return family;
    }

    public void family(KothFamily family) {
        this.family = family;
    }

    public TeamMode teamMode() {
        return teamMode;
    }

    public void teamMode(TeamMode teamMode) {
        this.teamMode = teamMode;
    }

    public boolean advanced() {
        return advanced;
    }

    public void advanced(boolean advanced) {
        this.advanced = advanced;
    }
}
