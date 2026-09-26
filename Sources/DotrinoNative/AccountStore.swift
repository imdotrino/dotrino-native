import Foundation

/// An account this phone approves for natively: which vault, through which proxy, and the
/// paper (`cert`) the vault gave to this device's key. No secret in here — the keys live in
/// the Secure Enclave under [id].
public struct Account: Equatable, Sendable {
    public let id: String
    public let name: String
    public let profileId: String?
    public let vault: String
    public let proxy: String
    public var cert: JSON
    public let deviceId: String
    public let addedAt: Int64

    public init(id: String, name: String, profileId: String?, vault: String, proxy: String, cert: JSON, deviceId: String, addedAt: Int64 = nowMs()) {
        self.id = id; self.name = name; self.profileId = profileId; self.vault = vault
        self.proxy = proxy; self.cert = cert; self.deviceId = deviceId; self.addedAt = addedAt
    }

    var json: JSON {
        var o: [String: JSON] = ["id": .string(id), "name": .string(name), "vault": .string(vault), "proxy": .string(proxy),
                                 "cert": cert, "deviceId": .string(deviceId), "addedAt": .int(addedAt)]
        if let profileId { o["profileId"] = .string(profileId) }
        return .object(o)
    }

    init(json o: JSON) throws {
        guard let id = o["id"]?.string, let name = o["name"]?.string, let vault = o["vault"]?.string,
              let proxy = o["proxy"]?.string, let cert = o["cert"], cert.object != nil, let deviceId = o["deviceId"]?.string
        else { throw CryptoError("accounts file: an account is missing fields") }
        self.init(id: id, name: name, profileId: o["profileId"]?.string, vault: vault, proxy: proxy, cert: cert,
                  deviceId: deviceId, addedAt: o["addedAt"]?.int ?? 0)
    }
}

/// The accounts this phone approves for, on disk, in a [SealedFile]. Nothing secret is
/// stored (vault key, proxy, paper, name), but the list of vaults a person belongs to is theirs.
public final class AccountStore: @unchecked Sendable {
    public static let shared = AccountStore()
    private let sealed = SealedFile(name: "dotrino-accounts.bin", keyAlias: "dotrino.accounts")

    public init() {}

    public func list() throws -> [Account] {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        return try read()
    }

    private func read() throws -> [Account] {
        guard let plain = try sealed.read() else { return [] }
        guard let arr = try JSON.parse(plain).array else { throw CryptoError("accounts file is not a list") }
        return try arr.map(Account.init(json:))
    }

    private func write(_ accounts: [Account]) throws {
        try sealed.write(Data(JSON.array(accounts.map(\.json)).text.utf8))
    }

    /// Adds or replaces the account with the same [Account.id].
    public func save(_ a: Account) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        try write(read().filter { $0.id != a.id } + [a])
    }

    public func remove(_ id: String) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        try write(read().filter { $0.id != id })
        EnclaveKeys.delete(id)
    }
}
