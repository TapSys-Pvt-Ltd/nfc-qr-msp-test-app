# Tap2Pay NFC proof of concept (Track A)

Checks that the Urovo i9100 reader can push a Tap2Pay invoice to an Android phone over HCE,
using the commands of *Tap2Pay via Raast Specification v1.0*, Clause 3.

| Module | Runs on | What it does |
|---|---|---|
| `core` | both (plain Java) | TLV builder/parser, CRC-16/CCITT-FALSE, APDU constants and chunking, Table 10 validation. `new Invoice().toPayload()` reproduces the spec's canonical 234-byte object (CRC `B00A`). |
| `wallet-hce` | phone | `HostApduService` for AID `F0010203030605` (category `other`): SELECT → FCI, PUSH INVOICE with chunk reassembly, validation, shows the invoice and an APDU log. |
| `pos-reader` | i9100 | `RFCardHandlerImpl.searchCard` → `activate("CPU")` → `exchangeApdu` SELECT + PUSH INVOICE, with per-step timings. |

## Build and install

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :pos-reader:assembleDebug :wallet-hce:assembleDebug
adb -s <pos-serial>   install -r pos-reader/build/outputs/apk/debug/pos-reader-debug.apk
adb -s <phone-serial> install -r wallet-hce/build/outputs/apk/debug/wallet-hce-debug.apk
```

## Test steps

1. Phone: NFC on, open **Tap2Pay Wallet Test** (it claims preferred-service while in front).
2. POS: open **Tap2Pay POS Test**, press a Tap button, hold the phone on the contactless area.
3. Compare both logs. Logcat tags: `Tap2PayPOS` (POS) and `Tap2PayWallet` (phone).

| Button | Expected result |
|---|---|
| fresh invoice (1 APDU) | SELECT `9000` with FCI version 02, PUSH `9000`, invoice shown on phone |
| fresh invoice (2 chunks) | P2 `01` then `82`, both `9000` |
| replay last invoice | `6985` (duplicate nonce, Clause 4.3) |
| spec canonical | `6985` (timestamp from 2026-10-08 is outside T_freshness) |

Also worth trying: phone screen locked (should still work, `requireDeviceUnlock=false`), screen off
(should not be detected), and a bank card wallet set as default "Tap & pay" (coexistence, M-1).

## Results, 2026-10-20

Urovo i9100 (Android 13) reader → Samsung Galaxy SM-A266B (Android 16), phone unlocked with the wallet
test app in front. Google Wallet was the phone's default "Tap & pay" app throughout.
Timings come from the phone's log. The POS-side log was not captured in this run.

| Tap | APDUs | Phone result | SELECT → last SW |
|---|---|---|---|
| fresh, 2 chunks (×4) | P2 `01` (172 B) + `82` (61 B) | `9000` accepted | 83–103 ms |
| fresh, 1 APDU | P2 `00` (233 B) | `9000` accepted, invoice displayed | 74 ms |
| replay last | P2 `00` (233 B) | `6985` duplicate nonce | 67 ms |
| spec canonical | P2 `00` (234 B) | `6985` stale timestamp | 63 ms |

- RF field detected → HCE activated: about 125–195 ms. A whole tap is well under 0.5 s, far inside T_wait_sw (5 s) and T_resp (1 s).
- The Urovo SDK's `searchCard` + `activate("CPU")` + `exchangeApdu` works with phone HCE. No PPSE or EMV kernel was needed.
- The Tap2Pay AID (category `other`) reached our service while Google Wallet kept the payment default, with no prompt. That's the M-1 coexistence case on one device.

Not yet tested: phone locked or screen off, background wallet (not in front), other phone brands, and
repeated taps for reliability.

## Spec note

The manifest excerpt in spec Clause 8.3 uses the intent action `android.nfc.cardemulation.HostApduService`.
Android's actual action is `android.nfc.cardemulation.action.HOST_APDU_SERVICE`; with the spec's string
the service is never bound. This PoC uses the Android value.

## Apple VAS probe (pos-reader)

Checks whether the i9100 reader can reach an iPhone's Wallet over Apple VAS without ECP (the i9100 firmware and
Urovo SDKs have no VAS/ECP support, so the pass must be presented manually). **Probe: Apple VAS** sends
`SELECT OSE.VAS.01` (`00A404000A4F53452E5641532E303100`) and decodes the FCI with `core` `VasProbe`
(50 "ApplePay", 9F21 version 01 00, 9F24 nonce, 9F23 capabilities; bit 3 = VAS may be performed, as in
Proxmark3 `hf vas`). It logs the SW, raw bytes, decoded tags and a `RESULT: VAS <verdict>` line. GET VAS DATA is
not sent; it needs an Apple NFC pass certificate. Remote trigger:

```bash
adb -s <pos> shell am start -n pk.paysys.tap2pay.posreadertest/pk.paysys.tap2pay.posreader.MainActivity --es run vas
```

`TapGate` (core) keeps one search or exchange on the reader at a time and ignores card/timeout callbacks from
a search that already ended.

## Reverse ("tag") route for iPhone

iOS apps cannot do HCE without Apple's EEA-only entitlement, so for iPhone the roles flip: the POS is the
card and the phone reads the QR after the customer presses Tap to Pay.

| Module | Runs on | What it does |
|---|---|---|
| `core` `TagResponder` | both | Answers as an NFC Forum Type 4 tag (AID `D2760000850101`, CC `E103`, NDEF `E104` with one MIME record `application/vnd.paysys.raastqr` = the QR) and on the Tap2Pay AID `F0010203030605` (SELECT -> FCI, then GET INVOICE `80 CB` P1P2 = offset; SW `6310` = more, `9000` = last). The QR is snapshotted at SELECT. |
| `pos-tag` | i9100 (or any Android phone as a stand-in) | HCE service for both AIDs. Screen shows Android NFC / HCE support and AID routing, makes a fresh invoice, static QR or idle, and logs every APDU. |
| `ios-reader` | iPhone | Two buttons: **NDEF tag** (`NFCNDEFReaderSession`) and **Tap2Pay AID** (`NFCTagReaderSession`, ISO 7816). Shows the QR, merchant, amount, CRC check and timings. Signed with the PAYSYS LABS team (NFC Tag Reading only). |

```bash
./gradlew :core:test :pos-tag:assembleDebug
adb -s <pos-or-phone> install -r pos-tag/build/outputs/apk/debug/pos-tag-debug.apk
cd ios-reader && xcodebuild -project T2PReader.xcodeproj -scheme T2PReader -destination id=<iphone-udid> -allowProvisioningUpdates build
```

Test: open **Tap2Pay POS Tag Test**, press **New invoice**, then on the iPhone press a Tap to Pay button and
hold it on the POS. Logcat tag `Tap2PayTag`. On the i9100 the POS's own Tap2Pay screen must not be searching
for cards at the same time (reader mode and card mode share the antenna).
