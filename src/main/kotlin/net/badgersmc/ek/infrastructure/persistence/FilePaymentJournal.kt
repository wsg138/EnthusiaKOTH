package net.badgersmc.ek.infrastructure.persistence

import net.badgersmc.ek.application.PaymentJournal
import net.badgersmc.ek.application.PaymentJournalEntry
import net.badgersmc.ek.application.PaymentJournalStatus
import net.badgersmc.ek.application.StartSource
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * Small atomic journal for paid KOTH starts.
 *
 * Terminal records are deliberately retained. They are the evidence that
 * prevents a stale CHARGED/REFUNDING record from being interpreted as money
 * that should be moved again after a crash.
 */
class FilePaymentJournal(
    private val file: File,
    private val logger: (String, Throwable?) -> Unit,
) : PaymentJournal {
    private val records = linkedMapOf<UUID, PaymentJournalEntry>()

    init {
        load()
    }

    @Synchronized
    override fun record(entry: PaymentJournalEntry): Boolean {
        if (records.containsKey(entry.transactionId)) return false
        records[entry.transactionId] = entry
        if (persist()) return true
        records.remove(entry.transactionId)
        return false
    }

    @Synchronized
    override fun update(transactionId: UUID, status: PaymentJournalStatus): Boolean {
        val previous = records[transactionId] ?: return false
        records[transactionId] = previous.copy(status = status, updatedAt = Instant.now())
        if (persist()) return true
        records[transactionId] = previous
        return false
    }

    @Synchronized
    override fun entries(): List<PaymentJournalEntry> = records.values.toList()

    private fun load() {
        if (!file.isFile) return
        runCatching {
            file.readLines().forEach { line ->
                val parts = line.split('|')
                if (parts.size != 8 || parts[0] != "v1") return@forEach
                val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return@forEach
                val payer = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return@forEach
                val amount = parts[3].toDoubleOrNull() ?: return@forEach
                val source = runCatching { StartSource.valueOf(parts[4]) }.getOrNull() ?: return@forEach
                val status = runCatching { PaymentJournalStatus.valueOf(parts[5]) }.getOrNull() ?: return@forEach
                val created = parts[6].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@forEach
                val updated = parts[7].toLongOrNull()?.let(Instant::ofEpochMilli) ?: return@forEach
                records[id] = PaymentJournalEntry(id, payer, amount, source, status, created, updated)
            }
        }.onFailure { logger("Failed to read KOTH payment journal; paid-start recovery requires operator review", it) }
    }

    private fun persist(): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val content = records.values.joinToString("\n", postfix = if (records.isEmpty()) "" else "\n") { entry ->
            listOf(
                "v1",
                entry.transactionId,
                entry.payerId,
                entry.amount,
                entry.source.name,
                entry.status.name,
                entry.createdAt.toEpochMilli(),
                entry.updatedAt.toEpochMilli(),
            ).joinToString("|")
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        Files.writeString(temporary.toPath(), content, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        true
    }.getOrElse {
        logger("Failed to persist KOTH payment journal; economy transaction state was not advanced", it)
        false
    }
}
