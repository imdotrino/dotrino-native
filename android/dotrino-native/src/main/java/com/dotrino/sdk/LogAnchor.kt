package com.dotrino.sdk

/**
 * The vault's LOG ANCHOR, seen from the approver (vault ≥ 0.104.0, `lib/src/core/logAnchor.js`).
 *
 * The vault's log is a hash chain, so rewriting or removing an entry in the middle shows; cutting
 * the END does not, because a prefix is still a valid chain. So every few hours the vault seals
 * its log head with the signer and sends it, quietly, to the devices with `approve`, which KEEP
 * the last one. This is the port of `compareAnchor`: what an approver decides with a new anchor
 * against the last one it kept for that vault.
 *
 *   · `first`      none kept yet: keep it.
 *   · `advanced`   the log grew: keep the new one.
 *   · `same`       nothing new.
 *   · `truncated`  lower `logSeq` than the one kept: the end was cut.
 *   · `forked`     same `logSeq`, another head: the end was rewritten.
 *
 * The last two are the alarm, and the new anchor is NOT kept: the good one is the old one.
 */
object LogAnchor {
    const val EV = "log-anchor"
    const val FIRST = "first"
    const val ADVANCED = "advanced"
    const val SAME = "same"
    const val TRUNCATED = "truncated"
    const val FORKED = "forked"

    fun compare(lastSeq: Long?, lastHead: String?, seq: Long, head: String): String = when {
        lastSeq == null || lastHead == null -> FIRST
        seq < lastSeq -> TRUNCATED
        seq == lastSeq -> if (head == lastHead) SAME else FORKED
        else -> ADVANCED
    }

    /** The alarm verdicts: the vault's log lost or changed entries the approver had seen. */
    fun isAlarm(verdict: String) = verdict == TRUNCATED || verdict == FORKED

    /** The newest anchor among a vault's notices, or `null`. */
    fun latest(notices: List<VaultNotice>): VaultNotice? =
        notices.filter { it.ev == EV && it.logSeq != null && it.head != null }.maxByOrNull { it.ts }
}
