package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.PaymentJournalEntry
import net.badgersmc.ek.application.PaymentJournalStatus
import net.badgersmc.ek.application.StartSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class FilePaymentJournalTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `payment transaction states survive restart`() {
        val file = temp.resolve("payments.dat").toFile()
        val logs = mutableListOf<String>()
        val id = UUID.randomUUID()
        val payer = UUID.randomUUID()
        val first = FilePaymentJournal(file) { message, _ -> logs += message }

        assertTrue(
            first.record(
                PaymentJournalEntry(
                    id,
                    payer,
                    25.0,
                    StartSource.GUI,
                    PaymentJournalStatus.PREPARED,
                    Instant.parse("2026-08-06T12:00:00Z"),
                ),
            ),
        )
        assertTrue(first.update(id, PaymentJournalStatus.CHARGED))
        assertTrue(first.update(id, PaymentJournalStatus.REFUNDING))

        val restarted = FilePaymentJournal(file) { message, _ -> logs += message }
        val record = restarted.entries().single()
        assertEquals(id, record.transactionId)
        assertEquals(payer, record.payerId)
        assertEquals(25.0, record.amount)
        assertEquals(PaymentJournalStatus.REFUNDING, record.status)
        assertTrue(logs.isEmpty())
    }
}
