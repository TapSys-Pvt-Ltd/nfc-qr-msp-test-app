import CoreNFC
import SwiftUI

// Reverse ("tag") route test: the iPhone reads the QR from the POS (pos-tag app) in two ways,
// an NFC Forum NDEF tag and the Tap2Pay AID with GET INVOICE (80 CB, P1P2 = offset).

@main
struct T2PReaderApp: App {
    @StateObject private var reader = TapReader()
    var body: some Scene { WindowGroup { ContentView(reader: reader) } }
}

struct ContentView: View {
    @ObservedObject var reader: TapReader

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button("Tap to Pay: NDEF tag") { reader.readNdef() }
                    Button("Tap to Pay: Tap2Pay AID") { reader.readTap2PayAid() }
                } footer: {
                    Text(NFCTagReaderSession.readingAvailable ? "NFC reading available" : "NFC reading NOT available")
                }
                if let summary = reader.summary {
                    Section("Last QR") {
                        Text(summary).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
                    }
                }
                Section("Log (newest first)") {
                    ForEach(Array(reader.log.enumerated()), id: \.offset) { entry in
                        Text(entry.element).font(.system(.caption2, design: .monospaced))
                    }
                }
            }
            .navigationTitle("T2P Reader")
            .toolbar { Button("Clear") { reader.clear() } }
        }
    }
}

final class TapReader: NSObject, ObservableObject {
    @Published var log: [String] = []
    @Published var summary: String?
    private var ndefSession: NFCNDEFReaderSession?
    private var tagSession: NFCTagReaderSession?
    private var started = Date()
    private static let ndefAid = "D2760000850101"

    func clear() {
        log.removeAll()
        summary = nil
    }

    func readNdef() {
        guard NFCNDEFReaderSession.readingAvailable else { return add("NFC reading not available") }
        started = Date()
        let s = NFCNDEFReaderSession(delegate: self, queue: nil, invalidateAfterFirstRead: false)
        s.alertMessage = "Hold your iPhone near the POS"
        ndefSession = s
        add("NDEF session: begin")
        s.begin()
    }

    func readTap2PayAid() {
        guard NFCTagReaderSession.readingAvailable else { return add("NFC reading not available") }
        started = Date()
        guard let s = NFCTagReaderSession(pollingOption: [.iso14443], delegate: self, queue: nil) else {
            return add("could not create tag session")
        }
        s.alertMessage = "Hold your iPhone near the POS"
        tagSession = s
        add("Tag session (ISO 14443): begin")
        s.begin()
    }

    fileprivate func add(_ line: String) {
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        DispatchQueue.main.async { self.log.insert("+\(ms)ms \(line)", at: 0) }
    }

    fileprivate func finish(_ session: NFCReaderSession, qr: String, via: String) {
        let ms = Int(Date().timeIntervalSince(started) * 1000)
        add("READ OK via \(via): \(qr.utf8.count) bytes")
        let text = "via \(via), \(ms) ms after the button\n" + QrSummary.describe(qr) + "\n\n" + qr
        DispatchQueue.main.async { self.summary = text }
        session.alertMessage = "QR received"
        session.invalidate()
    }

    fileprivate func fail(_ session: NFCReaderSession, _ why: String) {
        add("FAILED: \(why)")
        session.invalidate(errorMessage: why)
    }

    fileprivate func readMessage(_ session: NFCReaderSession, _ tag: NFCNDEFTag, via: String) {
        tag.queryNDEFStatus { status, capacity, error in
            if let error { return self.fail(session, "NDEF status: \(error.localizedDescription)") }
            self.add("NDEF status \(status.rawValue) (1 none, 2 rw, 3 ro), capacity \(capacity)")
            tag.readNDEF { message, error in
                if let error { return self.fail(session, "NDEF read: \(error.localizedDescription)") }
                guard let record = message?.records.first else { return self.fail(session, "empty NDEF message") }
                let type = String(data: record.type, encoding: .ascii) ?? record.type.hex
                self.add("record TNF \(record.typeNameFormat.rawValue) type \(type), \(record.payload.count) B")
                guard let qr = String(data: record.payload, encoding: .utf8) else {
                    return self.fail(session, "payload is not UTF-8")
                }
                self.finish(session, qr: qr, via: via)
            }
        }
    }

    /** GET INVOICE loop: 6310 = more data, 9000 = last chunk. */
    fileprivate func pull(_ session: NFCTagReaderSession, _ tag: NFCISO7816Tag, offset: Int, acc: Data) {
        let apdu = NFCISO7816APDU(instructionClass: 0x80, instructionCode: 0xCB,
                                  p1Parameter: UInt8(offset >> 8), p2Parameter: UInt8(offset & 0xFF),
                                  data: Data(), expectedResponseLength: 256)
        tag.sendCommand(apdu: apdu) { data, sw1, sw2, error in
            if let error { return self.fail(session, "GET INVOICE: \(error.localizedDescription)") }
            let sw = String(format: "%02X%02X", sw1, sw2)
            self.add("GET INVOICE @\(offset): \(data.count) B, SW \(sw)")
            let all = acc + data
            if sw == "9000" {
                guard let qr = String(data: all, encoding: .utf8) else { return self.fail(session, "QR is not UTF-8") }
                self.finish(session, qr: qr, via: "Tap2Pay AID")
            } else if sw == "6310", !data.isEmpty, all.count < 4096 {
                self.pull(session, tag, offset: offset + data.count, acc: all)
            } else {
                self.fail(session, "POS answered \(sw)")
            }
        }
    }
}

extension TapReader: NFCNDEFReaderSessionDelegate {
    func readerSessionDidBecomeActive(_ session: NFCNDEFReaderSession) {
        add("NDEF session active, polling")
    }

    func readerSession(_ session: NFCNDEFReaderSession, didInvalidateWithError error: Error) {
        add("NDEF session ended: \(error.localizedDescription)")
    }

    // Required by the protocol; not called because didDetect tags is implemented.
    func readerSession(_ session: NFCNDEFReaderSession, didDetectNDEFs messages: [NFCNDEFMessage]) {}

    func readerSession(_ session: NFCNDEFReaderSession, didDetect tags: [NFCNDEFTag]) {
        add("detected \(tags.count) NDEF tag(s)")
        guard let tag = tags.first else { return }
        session.connect(to: tag) { error in
            if let error { return self.fail(session, "connect: \(error.localizedDescription)") }
            self.readMessage(session, tag, via: "NDEF session")
        }
    }
}

extension TapReader: NFCTagReaderSessionDelegate {
    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        add("Tag session active, polling")
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        add("Tag session ended: \(error.localizedDescription)")
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let tag = tags.first else { return }
        guard case let .iso7816(iso) = tag else {
            add("detected a non-ISO7816 tag: \(tag)")
            return fail(session, "Not a Tap2Pay POS")
        }
        session.connect(to: tag) { error in
            if let error { return self.fail(session, "connect: \(error.localizedDescription)") }
            self.add("ISO7816 tag, selected AID \(iso.initialSelectedAID), UID \(iso.identifier.hex)")
            if iso.initialSelectedAID.uppercased() == Self.ndefAid {
                self.readMessage(session, iso, via: "Tag session NDEF")
            } else {
                self.pull(session, iso, offset: 0, acc: Data())
            }
        }
    }
}

/** Top-level EMVCo TLV fields and the CRC (tag 63, CRC-16/CCITT-FALSE over everything before its value). */
enum QrSummary {
    static func describe(_ qr: String) -> String {
        let chars = Array(qr)
        var fields: [String: String] = [:]
        var i = 0
        while i + 4 <= chars.count, let len = Int(String(chars[i + 2 ..< i + 4])), i + 4 + len <= chars.count {
            fields[String(chars[i ..< i + 2])] = String(chars[i + 4 ..< i + 4 + len])
            i += 4 + len
        }
        let parsedAll = i == chars.count
        var crcOk = false
        if let crc = fields["63"], chars.count >= 4 {
            let body = String(chars[0 ..< chars.count - 4])
            crcOk = String(format: "%04X", crc16(Array(body.utf8))) == crc.uppercased()
        }
        let poi = fields["01"] == "12" ? "dynamic (12)" : fields["01"] == "11" ? "static (11)" : fields["01"] ?? "-"
        return """
        Merchant: \(fields["59"] ?? "-"), \(fields["60"] ?? "-")
        Amount:   \(fields["54"].map { "PKR \($0)" } ?? "none (customer enters it)")
        Type:     \(poi)
        CRC:      \(fields["63"] == nil ? "missing" : crcOk ? "OK" : "MISMATCH")\(parsedAll ? "" : "  (TLV did not parse to the end)")
        """
    }

    static func crc16(_ bytes: [UInt8]) -> UInt16 {
        var crc: UInt16 = 0xFFFF
        for b in bytes {
            crc ^= UInt16(b) << 8
            for _ in 0 ..< 8 { crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
        }
        return crc
    }
}

extension Data {
    var hex: String { map { String(format: "%02X", $0) }.joined() }
}
