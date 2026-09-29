import AVFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

/// THE ECOSYSTEM'S QR in native (`@dotrino/qr`). Same piece as `DotrinoQr.kt` / `QrScanView.kt`.
/// Everything on the device and with the system's own frameworks: what goes in a QR is usually
/// the user's, so it never goes to an image API.
public enum DotrinoQR {
    /// The QR of `text`, sharp at `size` points (nearest-neighbour scaling, no blur).
    public static func image(_ text: String, size: CGFloat, scale: CGFloat = UIScreen.main.scale) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage else { return nil }
        let k = (size * scale) / out.extent.width
        let scaled = out.transformed(by: CGAffineTransform(scaleX: k, y: k))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg, scale: scale, orientation: .up)
    }

    /// Reads a QR from a photo (for when there is no camera or no permission). Nil when none.
    public static func decode(_ photo: UIImage) -> String? {
        guard let ci = CIImage(image: photo) else { return nil }
        let d = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        return d?.features(in: ci).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first
    }
}

/// Shows the QR of `text` (`<dotrino-qr>`), with a white quiet zone so any camera reads it.
public struct DotrinoQRView: View {
    let text: String
    let size: CGFloat
    public init(_ text: String, size: CGFloat = 200) { self.text = text; self.size = size }
    public var body: some View {
        Group {
            if let img = DotrinoQR.image(text, size: size) {
                Image(uiImage: img).interpolation(.none).resizable().frame(width: size, height: size)
            } else { Color.clear.frame(width: size, height: size) }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color.white))
        .accessibilityLabel(Text(text))
    }
}

/// READ A QR WITH THE CAMERA (`<dotrino-qr-scan>`). `onResult` is called ONCE with the first QR
/// read; `onError` with `no-camera-permission`, `no-camera` or `camera-failed`. The app declares
/// `NSCameraUsageDescription` in its Info.plist; the library asks for access when it starts.
public struct DotrinoQRScanner: UIViewControllerRepresentable {
    let onResult: (String) -> Void
    let onError: (String) -> Void
    public init(onResult: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        self.onResult = onResult; self.onError = onError
    }
    public func makeUIViewController(context: Context) -> ScannerController { ScannerController(onResult: onResult, onError: onError) }
    public func updateUIViewController(_ vc: ScannerController, context: Context) {}

    public final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        private let onResult: (String) -> Void
        private let onError: (String) -> Void
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        init(onResult: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
            self.onResult = onResult; self.onError = onError
            super.init(nibName: nil, bundle: nil)
        }
        required init?(coder: NSCoder) { fatalError("not from a storyboard") }

        public override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { ok ? self.configure() : self.onError("no-camera-permission") }
            }
        }

        private func configure() {
            guard let cam = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                  let input = try? AVCaptureDeviceInput(device: cam) else { onError("no-camera"); return }
            let out = AVCaptureMetadataOutput()
            guard session.canAddInput(input), session.canAddOutput(out) else { onError("camera-failed"); return }
            session.addInput(input); session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let p = AVCaptureVideoPreviewLayer(session: session)
            p.videoGravity = .resizeAspectFill
            p.frame = view.bounds
            view.layer.addSublayer(p)
            preview = p
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }

        public override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }

        public override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            if session.isRunning { session.stopRunning() }
        }

        public func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let text = objects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first else { return }
            done = true
            session.stopRunning()
            onResult(text)
        }
    }
}
