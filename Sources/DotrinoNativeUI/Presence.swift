import Foundation
import LocalAuthentication

/// «Is it you?», asked right before an action that weighs: approving a request, showing a
/// secret, changing who can do what. The phone's own prompt — Face ID or Touch ID, or the
/// passcode when there is no biometry — so an unlocked phone in someone else's hand cannot
/// do it.
///
/// ONE piece for every native app (same role as `Presence.kt`). The app declares
/// `NSFaceIDUsageDescription` in its Info.plist.
///
/// WHAT THIS IS NOT: it does not bind the key. The key in the Secure Enclave still signs
/// without it; this is a check in the app, before the call. It closes «the phone was left
/// unlocked», not «the app was tampered with».
///
/// NO WAY AROUND IT. A phone with no passcode cannot confirm anyone, so the answer is
/// `.unavailable` and the action does not happen — it is never «nothing to ask, go on».
public enum Presence {
    public enum Outcome: Equatable {
        case confirmed
        /// The person closed the prompt. Nothing to say on screen: they know.
        case cancelled
        /// This phone has no passcode: there is nothing to confirm with.
        case unavailable
        /// The system refused (locked out after too many tries…), in its own words.
        case failed(String)
    }

    /// Whether this phone can confirm at all (it has a passcode).
    public static func available() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Shows the prompt; `reason` says what is about to happen.
    public static func confirm(_ reason: String) async -> Outcome {
        let ctx = LAContext()
        var why: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &why) else {
            if let why, why.code != LAError.passcodeNotSet.rawValue { return .failed(why.localizedDescription) }
            return .unavailable
        }
        do {
            return try await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) ? .confirmed : .failed("not-confirmed")
        } catch let e as LAError {
            switch e.code {
            case .userCancel, .appCancel, .systemCancel: return .cancelled
            case .passcodeNotSet: return .unavailable
            default: return .failed(e.localizedDescription)
            }
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    /// What to show for an outcome that did not confirm; nil when there is nothing to say.
    public static func message(_ o: Outcome) -> String? {
        switch o {
        case .confirmed, .cancelled: return nil
        case .unavailable: return DotrinoLang.shared.text("dotrino_presence_unavailable", in: .module)
        case .failed(let m): return m
        }
    }
}
