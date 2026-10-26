import Foundation

/// The vault's LOG ANCHOR, seen from the approver (vault ≥ 0.104.0, `lib/src/core/logAnchor.js`).
///
/// The vault's log is a hash chain, so rewriting or removing an entry in the middle shows; cutting
/// the END does not, because a prefix is still a valid chain. So every few hours the vault seals
/// its log head with the signer and sends it, quietly, to the devices with `approve`, which KEEP
/// the last one. Same piece as `LogAnchor.kt`: what an approver decides with a new anchor against
/// the last one it kept for that vault. `truncated` and `forked` are the alarm, and then the new
/// anchor is NOT kept: the good one is the old one.
public enum LogAnchor {
    public static let ev = "log-anchor"

    public enum Verdict: String, Sendable {
        case first, advanced, same, truncated, forked
        public var isAlarm: Bool { self == .truncated || self == .forked }
    }

    public static func compare(lastSeq: Int64?, lastHead: String?, seq: Int64, head: String) -> Verdict {
        guard let lastSeq, let lastHead else { return .first }
        if seq < lastSeq { return .truncated }
        if seq == lastSeq { return head == lastHead ? .same : .forked }
        return .advanced
    }

    /// The newest anchor among a vault's notices, or `nil`.
    public static func latest(_ notices: [VaultNotice]) -> VaultNotice? {
        notices.filter { $0.ev == ev && $0.logSeq != nil && $0.head != nil }.max(by: { $0.ts < $1.ts })
    }
}
