package com.enthusia.koth.domain.event;

import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.PrivateTestAccess;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.rules.ItemRuleSet;

import java.time.Instant;
import java.util.UUID;

public record EventRequest(
        UUID requestId,
        KothFamily family,
        TeamMode teamMode,
        StartSource source,
        UUID requestedBy,
        Instant startAt,
        ItemRuleSet rules,
        boolean queueIfBusy,
        EventKind kind,
        PrivateTestAccess privateTestAccess,
        boolean quickTiming
) {
    public boolean isPrivateTest() {
        return kind == EventKind.PRIVATE_TEST;
    }
}
