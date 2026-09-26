import Foundation

/// A pending request, as the vault lists it. [ctx] is already opened with my key, when it came sealed.
public struct Approval: Equatable, Sendable, Identifiable {
    public let id: String
    public let ns: String
    public let kind: String
    public let deviceId: String
    public let label: String
    public let exp: Int64
    public let ctx: JSON?
    /// Why the context could not be read, when it came but did not open (or was not sealed to me).
    public let ctxError: String?
}

public struct Grant: Equatable, Sendable, Identifiable {
    public let id: String
    public let ns: String
    public let deviceId: String
    public let label: String
    public let exp: Int64
    public let uses: Int64
    public let ctx: JSON?
}

public struct VaultError: Error, CustomStringConvertible {
    public let description: String
    public let code: String
    public init(_ d: String, code: String) { description = d; self.code = code }
}

/// Calls go one at a time (see [VaultClient]).
actor AsyncLock {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }
}

/// Talks to ONE account's vault over one [ProxyConnection]. Same messages as `vaultRpc` in
/// `@dotrino/identity/vault/remote.js`: `{ type, data: {…, publickey, ts}, signature, cert }`
/// to the vault's key, and the answer comes back to this connection.
///
/// The vault's answers carry no request id, so calls on one account go ONE AT A TIME (the
/// lock): with two in flight, the first answer would settle whichever asked first.
public final class VaultClient: @unchecked Sendable {
    public static let secrets = "vault.secrets"
    public static let secretsResult = "vault.secrets.result"
    public static let renewType = "vault.renew"
    public static let renewed = "vault.renewed"
    public static let errorType = "vault.error"
    public static let adminEvent = "vault.admin.event"
    public static let scopeApprove = "vault:approve"
    /// The record does not let this key approve (yet): the owner has to give it `+aprueba`.
    public static let noApprove = "no-approve"

    private let keys: DeviceKeys
    private let conn: ProxyConnection
    private let onRenewed: (Account) -> Void
    private let gate = AsyncLock()
    private let stateLock = NSLock()
    private var _account: Account

    public var account: Account { stateLock.lock(); defer { stateLock.unlock() }; return _account }

    /// [onRenewed] is called with the renewed account so the caller persists the new paper.
    public init(account: Account, keys: DeviceKeys, conn: ProxyConnection, onRenewed: @escaping (Account) -> Void = { _ in }) {
        _account = account; self.keys = keys; self.conn = conn; self.onRenewed = onRenewed
    }

    private func rpc(_ sendType: String, _ okType: String, _ data: [String: JSON], timeout: TimeInterval = 15) async throws -> JSON {
        await gate.lock()
        do {
            let r = try await rpcUnlocked(sendType, okType, data, timeout: timeout)
            await gate.unlock()
            return r
        } catch {
            await gate.unlock()
            throw error
        }
    }

    private func rpcUnlocked(_ sendType: String, _ okType: String, _ data: [String: JSON], timeout: TimeInterval) async throws -> JSON {
        var d = data
        d["publickey"] = .string(keys.publickey)
        d["ts"] = .int(nowMs())
        let signed = JSON.object(d)
        let answer = OneShot<JSON>()
        let off = conn.onMessage { m in
            switch m.payload["type"]?.string {
            case okType: answer.finish(.success(m.payload))
            case VaultClient.errorType:
                let msg = m.payload["error"]?.string ?? "vault error"
                answer.finish(.failure(VaultError(msg, code: VaultClient.codeOf(msg))))
            default: break
            }
        }
        defer { off() }
        try conn.sendByPubkey(account.vault, ["type": .string(sendType), "data": signed,
                                              "signature": .string(keys.sign(Canonical.stringify(signed))), "cert": account.cert])
        return try await answer.wait(timeout: timeout, onTimeout: VaultError("the vault did not reply (is it running?)", code: "vault-no-reply"))
    }

    /// `unauthorized: <reason>` → the reason, which is what can be acted on.
    static func codeOf(_ msg: String) -> String {
        guard let r = msg.range(of: #"^unauthorized: ([\w-]+)"#, options: .regularExpression) else { return "vault-error" }
        return String(msg[r].dropFirst("unauthorized: ".count))
    }

    /// Once, and only for a paper problem: after `+aprueba` the old paper does not carry
    /// `vault:approve`, and after a record change it is behind. A revoked device or one the
    /// record no longer lets approve is NOT retried — renewing cannot fix that, and retrying
    /// would only hide the reason.
    private func withPaper<T>(_ block: () async throws -> T) async throws -> T {
        do { return try await block() } catch let e as VaultError
            where ["scope", "acta-vieja", "untrusted-issuer", "expired", "legacy-cert-retirado", "no-acta"].contains(e.code) {
            _ = try await renew()
            return try await block()
        }
    }

    /// A new paper for my key. Accepted only if it is from the vault I enrolled with, for my key, and lets me approve.
    @discardableResult
    public func renew() async throws -> Account {
        let res = try await rpc(Self.renewType, Self.renewed, ["op": "renew"])
        guard let cert = res["cert"], cert.object != nil else { throw VaultError("the vault did not send a paper", code: "no-cert") }
        let a = account
        if let why = Delegation.check(cert, vault: a.vault, sub: keys.publickey, expectedScope: Self.scopeApprove) {
            // A good paper without `vault:approve` is not a broken paper: the record simply
            // does not let this key approve yet (`+aprueba` is missing). Said as such, so the
            // screen can tell the owner what to do instead of «invalid renewed paper: scope».
            if why == "scope" { throw VaultError("the record does not let this device approve", code: Self.noApprove) }
            throw VaultError("invalid renewed paper: \(why)", code: why)
        }
        var n = a
        n.cert = cert
        stateLock.withLock { _account = n }
        onRenewed(n)
        return n
    }

    private func secretsCall(_ data: [String: JSON]) async throws -> JSON {
        try await withPaper {
            let res = try await rpc(Self.secrets, Self.secretsResult, data)
            guard let body = res["body"], body.object != nil else { throw VaultError("the vault answered without a body", code: "no-body") }
            return body
        }
    }

    public func approvals() async throws -> [Approval] {
        let body = try await secretsCall(["op": "approvals"])
        return (body["items"]?.array ?? []).compactMap(approvalOf)
    }

    public func approve(_ id: String) async throws { try await answer("approve", id) }
    public func deny(_ id: String) async throws { try await answer("deny", id) }

    private func answer(_ op: String, _ id: String) async throws {
        let body = try await secretsCall(["op": .string(op), "id": .string(id)])
        if body["ok"]?.bool != true { throw VaultError("the vault did not confirm the \(op)", code: "not-confirmed") }
    }

    public func grants() async throws -> [Grant] {
        let body = try await secretsCall(["op": "grants"])
        return (body["items"]?.array ?? []).compactMap { o in
            guard let id = o["id"]?.string else { return nil }
            return Grant(id: id, ns: o["ns"]?.string ?? "", deviceId: o["deviceId"]?.string ?? "", label: o["label"]?.string ?? "",
                         exp: o["exp"]?.int ?? 0, uses: o["uses"]?.int ?? 0, ctx: openCtx(o).0)
        }
    }

    public func revokeGrant(_ id: String) async throws {
        let body = try await secretsCall(["op": "grant-revoke", "id": .string(id)])
        if body["ok"]?.bool != true { throw VaultError("that approval was no longer active", code: "not-found") }
    }

    private func approvalOf(_ o: JSON) -> Approval? {
        guard let id = o["id"]?.string else { return nil }
        let (ctx, err) = openCtx(o)
        return Approval(id: id, ns: o["ns"]?.string ?? "", kind: o["kind"]?.string ?? "read", deviceId: o["deviceId"]?.string ?? "",
                        label: o["label"]?.string ?? "", exp: o["exp"]?.int ?? 0, ctx: ctx, ctxError: err)
    }

    /// The context comes sealed to MY encryption key; open it here. Not opening is said, never hidden.
    private func openCtx(_ o: JSON) -> (JSON?, String?) {
        if let c = o["ctx"], c.object != nil { return (c, nil) }
        if let wrap = o["ctxWrap"], wrap.object != nil, let env = o["ctxEnvelope"], env.object != nil {
            do {
                let c = try JSON.parse(Crypto.openSealed(wrap: wrap, envelope: env, keys: keys))
                return c.object != nil ? (c, nil) : (nil, "cannot-open")
            } catch { return (nil, "cannot-open") }
        }
        if o["ctxSealed"]?.bool == false { return (nil, o["ctxReason"]?.string ?? "not-sealed") }
        return (nil, nil)
    }
}
