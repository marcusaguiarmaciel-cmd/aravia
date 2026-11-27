import CommonCrypto
import CoreImage.CIFilterBuiltins
import CryptoKit
import SwiftUI

/// Hands this band's auth key to Aravia on another phone (Android) as a QR code, without any
/// server: the key is sealed with AES-256-GCM under a key stretched (PBKDF2-SHA256) from a
/// six-digit code shown next to the QR. The format is in docs/KEY-TRANSFER.md.
enum KeyTransfer {
    static let prefix = "ARAVIA-KEY1:"
    static let iterations: UInt32 = 150_000

    struct Payload: Codable {
        var v = 1
        let name: String
        let mac: String?
        let model: String
        let keys: [String]
    }

    static func newCode() -> String {
        String(format: "%06d", Int.random(in: 0...999_999))
    }

    static func seal(_ payload: Payload, code: String) throws -> String {
        var salt = Data(count: 16)
        _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        let key = try stretch(code, salt: salt)
        let plain = try JSONEncoder().encode(payload)
        let box = try AES.GCM.seal(plain, using: key, authenticating: Data(prefix.utf8))
        var blob = salt
        blob.append(contentsOf: box.nonce)
        blob.append(box.ciphertext)
        blob.append(box.tag)
        return prefix + blob.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func stretch(_ code: String, salt: Data) throws -> SymmetricKey {
        var out = Data(count: 32)
        let password = Array(code.utf8).map { Int8(bitPattern: $0) }
        let status = out.withUnsafeMutableBytes { outBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password, password.count,
                                     saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), iterations,
                                     outBytes.bindMemory(to: UInt8.self).baseAddress, 32)
            }
        }
        guard status == kCCSuccess else { throw CocoaError(.coderInvalidValue) }
        return SymmetricKey(data: out)
    }

    static func qr(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Band › About › Levar para o Android.
struct KeyTransferSheet: View {
    let band: Band
    @Environment(\.dismiss) private var dismiss
    @State private var code = KeyTransfer.newCode()
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    Text(verbatim: "No Aravia do Android, toque em \u{201C}Trazer do iPhone\u{201D} e aponte a câmera para este código.")
                        .font(.subheadline)
                        .foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                    if let image {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 280)
                            .padding(12)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
                    } else if failed {
                        Text(verbatim: "Não deu para montar o código. A chave deste relógio está guardada?")
                            .foregroundStyle(Color.low)
                    } else {
                        ProgressView()
                    }
                    VStack(spacing: 4) {
                        Text(verbatim: "Código de 6 dígitos").font(.caption).foregroundStyle(Color.textSecondary)
                        Text(verbatim: "\(code.prefix(3)) \(code.suffix(3))")
                            .font(.system(size: 40, weight: .bold, design: .monospaced))
                            .foregroundStyle(Color.textPrimary)
                    }
                    Text(verbatim: "O QR leva a chave do relógio cifrada; sem esse código ela não abre. Nada passa pelo servidor. Mostre só para o seu outro celular.")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                    Button { code = KeyTransfer.newCode(); Task { await build() } } label: { Text(verbatim: "Gerar outro código") }
                        .tint(Color.action)
                }
                .padding(24)
            }
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: "Levar para o Android"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button { dismiss() } label: { Text(verbatim: "OK") } } }
            .task { await build() }
        }
    }

    private func build() async {
        image = nil
        let keys = Vault.bandKeys(band.id)
        guard !keys.isEmpty else { failed = true; return }
        let payload = KeyTransfer.Payload(name: band.name, mac: band.mac, model: band.model, keys: keys)
        let code = self.code
        let text = await Task.detached { try? KeyTransfer.seal(payload, code: code) }.value
        guard let text, let qr = KeyTransfer.qr(text) else { failed = true; return }
        image = qr
    }
}
