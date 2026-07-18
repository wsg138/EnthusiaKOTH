package com.enthusia.koth.domain.event;

import com.enthusia.koth.domain.ArenaDefinition;
import com.enthusia.koth.domain.CaptureLeaveBehavior;
import com.enthusia.koth.domain.CaptureZone;
import com.enthusia.koth.domain.EventKind;
import com.enthusia.koth.domain.EventState;
import com.enthusia.koth.domain.KothFamily;
import com.enthusia.koth.domain.KothRegion;
import com.enthusia.koth.domain.Position;
import com.enthusia.koth.domain.PrivateTestAccess;
import com.enthusia.koth.domain.StartSource;
import com.enthusia.koth.domain.TeamMode;
import com.enthusia.koth.domain.rules.ItemRuleSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ActiveEventPrivateTest {
    @Test
    void privateEventAcceptsExplicitParticipantsUntilItEnds() {
        UUID owner = UUID.randomUUID();
        UUID tester = UUID.randomUUID();
        ActiveEvent event = event(EventKind.PRIVATE_TEST, owner);

        assertTrue(event.isParticipant(owner));
        assertFalse(event.isParticipant(tester));
        assertTrue(event.join(tester));
        assertTrue(event.isParticipant(tester));
        event.state(EventState.ACTIVE);
        assertTrue(event.join(UUID.randomUUID()));
        event.state(EventState.ENDING);
        assertFalse(event.join(UUID.randomUUID()));
    }

    @Test
    void standardEventAcceptsEveryPlayerAsEligible() {
        assertTrue(event(EventKind.STANDARD, null).isParticipant(UUID.randomUUID()));
    }

    private ActiveEvent event(EventKind kind, UUID owner) {
        Instant now = Instant.now();
        EventRequest request = new EventRequest(UUID.randomUUID(), KothFamily.CAPTURE, TeamMode.SOLO,
                StartSource.PRIVATE_TEST, owner, now, ItemRuleSet.permissive(), false, kind,
                PrivateTestAccess.PERMISSION_JOIN, true);
        ArenaDefinition arena = new ArenaDefinition("capture", KothFamily.CAPTURE,
                new KothRegion("protected", new Position("world", -32, -64, -32), new Position("world", 32, 320, 32)),
                new CaptureZone("zone", new Position("world", 0.5, 80, 0.5), 5),
                120, 15, CaptureLeaveBehavior.RESET, 1, 20, 1);
        return new ActiveEvent(UUID.randomUUID(), request, arena, now, now.plusSeconds(120));
    }
}
