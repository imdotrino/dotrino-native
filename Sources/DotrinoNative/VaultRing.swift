import Foundation

/// THE SUBJECT OF A RING, sealed to this phone (vaultd ≥ 0.190.0, websocket-proxy ≥ 1.11.0).
///
/// The ring that reaches APNs carries nothing Apple can read. Next to it the vault now puts a
/// `sealed` subject — who asks for what — encrypted to the approver's key, and the proxy passes it
/// through as is. The phone opens it here, without the network, so the alert says the why even
/// when the vault is slow to answer.
///
/// It is ONLY the subject. Approving is still done from the list (`approvals`), which is signed:
/// a forged subject approves nothing.
public enum VaultRing {
    /// The subject, or `nil` when `sealed` is not for `keys` (another account) or is malformed.
    public static func open(_ sealed: String, keys: DeviceKeys) -> JSON? {
        guard let o = try? JSON.parse(sealed), let wrap = o["wrap"], let envelope = o["envelope"],
              wrap.object != nil, envelope.object != nil,
              let plain = try? Crypto.openSealed(wrap: wrap, envelope: envelope, keys: keys) else { return nil }
        return try? JSON.parse(plain)
    }

    /// An `approval` subject as the request it announces (the list stays the authority).
    public static func approval(_ s: JSON) -> Approval? {
        guard s["ev"]?.string == "approval", let id = s["id"]?.string else { return nil }
        let ctx = s["ctx"].flatMap { $0.object != nil ? $0 : nil }
        return Approval(id: id, ns: s["ns"]?.string ?? "", kind: s["kind"]?.string ?? "read",
                        deviceId: s["deviceId"]?.string ?? "", label: s["label"]?.string ?? "",
                        exp: s["exp"]?.int ?? 0, ctx: ctx, ctxError: nil)
    }

    /// A notice subject (`updated`, `update-needs-root`, `delivered`) as the notice it announces.
    public static func notice(_ s: JSON) -> VaultNotice? {
        guard let ev = s["ev"]?.string, ev != "approval", let id = s["id"]?.string else { return nil }
        return VaultNotice(id: id, ev: ev, version: s["version"]?.string ?? "", from: s["from"]?.string ?? "",
                           ts: s["ts"]?.int ?? 0, product: s["product"]?.string, deviceId: s["deviceId"]?.string,
                           label: s["label"]?.string, ns: s["ns"]?.string, kind: s["kind"]?.string)
    }
}
