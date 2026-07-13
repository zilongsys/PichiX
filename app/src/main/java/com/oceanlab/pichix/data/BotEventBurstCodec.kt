package com.oceanlab.pichix.data

/** Codifica / decodifica entradas de ráfaga agrupadas en [BotEventEntry.message]. */
object BotEventBurstCodec {

    const val GROUP_PREFIX = "@@BURST@@"
    const val LIVE_PREFIX = "@@BURST_LIVE@@"

    fun isBurstBundle(entry: BotEventEntry): Boolean =
        entry.message.startsWith(GROUP_PREFIX) || entry.message.startsWith(LIVE_PREFIX)

    fun isLive(entry: BotEventEntry): Boolean = entry.message.startsWith(LIVE_PREFIX)

    fun burstId(entry: BotEventEntry): Long = entry.timestampMs

    fun summary(entry: BotEventEntry): String {
        val body = entry.message.removePrefix(GROUP_PREFIX).removePrefix(LIVE_PREFIX)
        return body.lineSequence().firstOrNull().orEmpty().ifBlank { "Ráfaga" }
    }

    fun detailLines(entry: BotEventEntry): List<BotEventEntry> {
        val body = entry.message.removePrefix(GROUP_PREFIX).removePrefix(LIVE_PREFIX)
        return body.lineSequence().drop(1).mapNotNull { line -> parseDetailLine(line) }.toList()
    }

    private fun parseDetailLine(line: String): BotEventEntry? {
        val parts = line.split('|', limit = 3)
        val ts = parts.firstOrNull()?.trim()?.toLongOrNull() ?: return null
        return when (parts.size) {
            3 -> BotEventEntry(ts, parts[1].trim(), parts[2].trim())
            2 -> BotEventEntry(ts, BotEventLog.CAT_BURST, parts[1].trim())
            else -> null
        }
    }

    fun expandToIndividualEntries(bundle: BotEventEntry): List<BotEventEntry> {
        val groupId = burstId(bundle)
        val details = detailLines(bundle)
        if (details.isNotEmpty()) {
            return details.map { line ->
                BotEventEntry(
                    line.timestampMs,
                    line.category,
                    line.message,
                    burstGroupId = groupId,
                )
            }
        }
        return listOf(
            BotEventEntry(
                groupId,
                BotEventLog.CAT_BURST,
                summary(bundle),
                burstGroupId = groupId,
            ),
        )
    }
}
