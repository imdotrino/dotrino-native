import Foundation

/// WHERE THIS DEVICE RUNS, so the vault can tell it apart in its device list. Same shape as
/// `@dotrino/identity/machine`: `kind` (`ios`), `os` (`iOS 18`, `iPadOS 18`) and `name` (the
/// hardware model, `iPhone12,1`). It goes inside what the device signs, so nobody else can write
/// it for it; it decides nothing, it only helps the owner recognise the row.
///
/// Read without UIKit (`UIDevice` is main-actor bound and, since iOS 16, only says «iPhone»).
public enum Machine {
    private static func clip(_ s: String, _ n: Int) -> String {
        String(s.unicodeScalars.filter { $0.value >= 0x20 && $0.value != 0x7f }).trimmingCharacters(in: .whitespaces).prefix(n).description
    }

    /// Pure, for tests: `nil` when there is nothing worth saying.
    public static func of(osMajor: Int, model: String) -> JSON? {
        let name = clip(model, 64)
        let os = osMajor > 0 ? "\(name.hasPrefix("iPad") ? "iPadOS" : "iOS") \(osMajor)" : ""
        if os.isEmpty && name.isEmpty { return nil }
        return .object(["kind": "ios", "os": .string(clip(os, 40)), "name": .string(name)])
    }

    /// This phone. Read once: none of it changes while the app runs.
    public static let current: JSON? = {
        var info = utsname()
        uname(&info)
        var model = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        // On the simulator `uname` says the Mac's CPU; the simulated model is in the environment.
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"], !sim.isEmpty { model = sim }
        return of(osMajor: ProcessInfo.processInfo.operatingSystemVersion.majorVersion, model: model)
    }()
}
