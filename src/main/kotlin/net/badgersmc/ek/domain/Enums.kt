package net.badgersmc.ek.domain

enum class EventKind { STANDARD, SCHEDULED, PRIVATE_TEST }

enum class EventState { SCHEDULED, QUEUED, STARTING, ACTIVE, ENDING, COMPLETED, CANCELLED }

enum class CaptureLeaveBehavior { RESET, DECAY, PAUSE }

enum class TeamMode { SOLO, GUILD }

enum class LockState(val allows: (kind: EventKind) -> Boolean) {
    UNLOCKED({ true }),
    // Manual lock blocks only manually-started KOTHs (commands, flares);
    // scheduled rotations and private tests still run.
    MANUAL_LOCKED({ it == EventKind.SCHEDULED || it == EventKind.PRIVATE_TEST }),
    ALL_LOCKED({ false }),
}
