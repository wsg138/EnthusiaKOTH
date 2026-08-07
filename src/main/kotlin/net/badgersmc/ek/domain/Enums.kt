package net.badgersmc.ek.domain

enum class EventKind {
    PLAYER_COMMAND,
    GUI,
    FLARE,
    ADMIN,
    SCHEDULED,
    PRIVATE_TEST,
}

enum class EventState { SCHEDULED, QUEUED, STARTING, ACTIVE, ENDING, COMPLETED, CANCELLED }

enum class CaptureLeaveBehavior { RESET, DECAY, PAUSE }

enum class TeamMode { SOLO, GUILD }

enum class LockState(val allows: (kind: EventKind) -> Boolean) {
    UNLOCKED({ true }),
    MANUAL_LOCKED({ it == EventKind.SCHEDULED || it == EventKind.PRIVATE_TEST || it == EventKind.ADMIN }),
    ALL_LOCKED({ false }),
}
