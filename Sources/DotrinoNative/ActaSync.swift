import Foundation

/// PUT THE PHONE'S RECORD UP TO DATE, from the vault: what the identity does when it lists the
/// vault's devices (`listVaultDevices` → `adoptChain`), for native apps. Same piece as
/// `ActaSync.kt`.
///
/// Without it a native app judged other devices against the record it found in the shared
/// store, and that one only moved when a WebView of the identity ran: the vault changed its
/// record, re-issued the agents' papers with the new `seq`, and the phone answered each of them
/// with `acta-vieja`.
///
/// Nothing is taken on the vault's word: each record of the chain must verify and chain from
/// the one adopted before it ([Acta.canAdopt]). What is adopted is written back to the shared
/// identity store, so every Dotrino app of the phone sees it.
public enum ActaSync {
    public struct ActaSyncError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
        init(_ d: String, code: String) { description = d; self.code = code }
    }

    static let devices = "vault.devices"
    static let devicesResult = "vault.devices.result"
    static let errorType = "vault.error"

    /// Asks the vault for the records after mine and adopts what chains. Returns the profile with
    /// the newest record, or the same one when there was nothing newer. Throws [ActaSyncError]:
    /// `no-vault`, `vault-no-reply`, `vault-error`, `no-record` (the vault sent nothing to adopt),
    /// `not-adopted` (nothing it sent chains from mine: the reason of the newest one).
    ///
    /// [save] keeps the adopted record where the identity reads it (key, JSON text).
    public static func update(_ profile: Profile, _ conn: ProxyConnection, save: (String, String) throws -> Void, timeout: TimeInterval = 15) async throws -> Profile {
        guard let v = profile.vault else { throw ActaSyncError("this profile is not linked to a vault", code: "no-vault") }
        let res = try await requestDevices(profile, v, conn, timeout: timeout)
        let chain = (res["chain"]?.array ?? []).filter { $0.object != nil }
        let candidates = chain.isEmpty ? [res["acta"]].compactMap { $0?.objectValue } : chain
        if candidates.isEmpty { throw ActaSyncError("the vault sent no record", code: "no-record") }
        let current = profile.record
        guard let adopted = Acta.adoptChain(candidates, current) else {
            let newest = candidates.max { ($0["seq"]?.int ?? 0) < ($1["seq"]?.int ?? 0) }!
            let why = Acta.canAdopt(newest, current).reason
            // The same record as mine, or an older one: there is nothing newer, which is not an error.
            if why == "misma-acta" || why == "seq-menor" { return profile }
            throw ActaSyncError("the vault's record does not chain from this phone's: \(why)", code: "not-adopted")
        }
        if let pid = profile.pid { try save(Profile.actaKey(pid), adopted.text) }
        return profile.withActa(adopted)
    }

    /// [update] keeping the record in the shared identity store of this phone.
    public static func catchUp(_ profile: Profile, _ conn: ProxyConnection) async throws -> Profile {
        try await update(profile, conn, save: { try IdentityStore.shared.set($0, $1) })
    }

    /// `requestDevices` of the identity: `{ op: 'devices', sinceSeq }`, signed as this device, with its paper.
    private static func requestDevices(_ profile: Profile, _ v: Profile.VaultLink, _ conn: ProxyConnection, timeout: TimeInterval) async throws -> JSON {
        let data: JSON = ["op": "devices", "sinceSeq": .int(profile.actaSeq ?? 0), "publickey": .string(profile.publickey), "ts": .int(nowMs())]
        let signature = try profile.signAsDevice(data)
        let answer = OneShot<JSON>()
        let off = conn.onMessage { m in
            switch m.payload["type"]?.string {
            case devicesResult: answer.finish(.success(m.payload))
            case errorType: answer.finish(.failure(ActaSyncError(m.payload["error"]?.string ?? "vault error", code: "vault-error")))
            default: break
            }
        }
        defer { off() }
        try conn.sendByPubkey(v.master, ["type": .string(devices), "data": data, "signature": .string(signature), "cert": v.cert])
        return try await answer.wait(timeout: timeout, onTimeout: ActaSyncError("the vault did not reply (is it running?)", code: "vault-no-reply"))
    }
}
