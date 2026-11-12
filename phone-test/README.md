# Tapsys Tap2Pay test kit (Xiaomi and any Android phone with HCE)

A phone app that plays the customer's phone for a real Tap2Pay POS: the **Urovo i9100** (FBL app) or the
**Sunmi P3** (Mindcrafts app). You pick a case, the POS pushes its QR, and the app shows every APDU it
received and sent, what the QR contains and a result for that tap.

The default case (*Generic QR*) answers exactly like the NBP app (`com.paysys.nbpdigital`, `Tap2PayHceService` +
`PushInvoiceAssembler`), so a pass here predicts what the real bank app does. The four QR-kind cases answer the same
way and also check the QR kind; *Invalid QR* makes the phone refuse the QR, to check how the
POS handles a failure.

## Screen (v0.4, Tapsys theme)

All cases are in one list, *Test cases*. Every case is a collapsed row showing only its name and its **result**
badge (NOT RUN, WAITING FOR TAP, IN PROGRESS, PASS, FAIL, CHECK POS). Tapping a row picks that case for the next
tap and opens it (only one is open at a time; tapping an open row closes it). A picked case with no tap yet shows
WAITING FOR TAP and "Waiting for a tap: hold this phone on the POS reader."; once tapped, its latest tap decides
the badge. An open row shows what the case simulates, the result explanation, the tap count and a *Details*
button. *Details* shows the rest: what the phone answers, what the POS should show, the expected QR kind, and the
log of every tap of that case with every APDU in hex (*Copy log*).
CHECK POS means the phone did its part (it refused the QR); whether the POS handled it is read on the POS.
The status card at the top shows NFC routing, the case the next tap will use and a PASS / FAIL count.
*Clear results* and *Copy all taps* are at the end of the screen, after the last case. Colours, wordmark and the
launcher icon come from the Tapsys merchant app (`Merchant_App/tapsys-merchant-instant-payment-app/mobile`).

## Header forms

| Form | Sent by | PUSH header |
|---|---|---|
| spec | Urovo FBL app (`Tap2PayApdu`) | `80 CA 03 P2`: P2 `00` = one APDU, else `01`, `02`, … with the last one `\| 0x80` |
| index | Sunmi, Mindcrafts v2.7 | `80 CA P1 P2`: P1 `00` = more follows, `01` = last; P2 = 0-based chunk index |

Both are accepted. The result says which one each tap used.

## Cases

| Case | Phone answers | Result on the phone | What to check on the POS |
|---|---|---|---|
| Generic QR | FCI `9000`, each chunk `9000`, complete valid QR `9000`; bad CRC or not a Raast QR `6A80` | PASS when a complete valid QR arrived and got `9000` | "Sent to the customer's phone" |
| SQRC without amount | same as Generic QR | PASS only for `01` = `11` and no tag `54`; else FAIL "accepted, but expected …, got …" | "Sent to the customer's phone" |
| SQRC with amount | same as Generic QR | PASS only for `01` = `11` and tag `54` > 0 | amount on POS matches the phone |
| DQRC with amount | same as Generic QR | PASS only for `01` = `12` and tag `54` > 0 (the normal RaastQR sale) | amount on POS matches the phone |
| DQRC without amount | same as Generic QR | PASS only for `01` = `12` and no tag `54` | "Sent to the customer's phone" |
| Invalid QR | complete QR `6A80` | CHECK POS; FAIL if the POS broke the transfer | Failure, not success; retry or fallback |

The four QR-kind cases answer exactly like the NBP app (a wrong kind still gets `9000`); only the result on the
phone says the POS sent the wrong kind. A tag `54` that is present but `0` or not a number fails both the
with- and without-amount cases.

The Urovo FBL app makes only **DQRC with amount** (RaastQR) and **SQRC without amount** (Static QR tile). To try
the other two kinds, use the POC **pos-reader** (Tap2Pay POS Test) on the Urovo: its buttons *SQRC, no amount*,
*SQRC + amount*, *DQRC + amount* and *DQRC, no amount* push a made-up Raast QR (`core` `SampleQr`, merchant
"Tap2Pay Test") in the Urovo's spec header form, with the amount from its amount box.

## Scenario matrix (phone side)

Every row has a unit test in `core` (`PhoneResponderTest`, `PushAssemblerTest`, `RaastQrTest`, `SampleQrTest`, `CaseResultTest`)
unless it says device.

| # | Situation | Expected |
|---|---|---|
| 1 | Generic QR, spec form, one APDU | `9000`, PASS, form spec |
| 2 | Generic QR, spec form, chunked | `9000` per chunk, PASS, chunk count shown |
| 3 | Generic QR, index form, one APDU (P1 `01`, P2 `00`) | `9000`, PASS, form index |
| 4 | Generic QR, index form, chunked | `9000` per chunk, PASS |
| 5 | QR with a bad CRC, or not a Raast QR | `6A80`, FAIL with the reason |
| 6 | PUSH before SELECT | `6985`, FAIL "push without SELECT" |
| 7 | Chunk out of sequence, or forms mixed in one transfer | `6A86`, buffer dropped, FAIL |
| 8 | Unknown P1 | `6A86` |
| 9 | Wrong CLA / INS | `6E00` / `6D00` |
| 10 | Lc 0 / Lc longer than the data | `6A80` |
| 11 | Extended Lc (`00 hi lo`) | accepted, as the NBP app does |
| 12 | Payload over 4096 bytes | `6A80` |
| 13 | Link lost mid-transfer | tap closes as FAIL "link lost after chunk n" |
| 14 | Second SELECT while a tap is open (POS retry in the same field) | old tap closed as it stood, new tap starts |
| 15 | Same QR as an earlier tap | still answered per case; result notes "same QR as tap #n" |
| 16 | Case changed while a tap is in progress | the case read at SELECT stays for that tap |
| 21 | Static QR (`01` = `11`, no amount) | PASS, type static, amount "entered by payer" |
| 22 | Process killed, app reopened | case kept (preferences); taps kept (log file) — device |
| 23 | App in background or phone locked | routed here only if no other app wins the AID; status shows routing — device |
| 24 | Screen off | most phones turn NFC off; no tap expected — device |
| 25 | NBP app also installed (same AID) | while this app is in front it is the preferred service — device |
| 26 | SQRC without amount case, static QR with no `54` | `9000`, PASS |
| 27 | Any QR-kind case, any of the other three kinds | `9000` (like NBP), FAIL "accepted, but expected X, got Y" |
| 28 | SQRC with amount case, static QR with `54` | `9000`, PASS |
| 29 | DQRC with amount case, dynamic QR with `54` | `9000`, PASS |
| 30 | (covered by 27) DQRC with amount, POS left out the amount | FAIL, "got dynamic QR without amount" |
| 31 | DQRC without amount case, dynamic QR with no `54` | `9000`, PASS |
| 32 | (covered by 27) SQRC without amount, POS sent an amount | FAIL, amount shown |
| 33 | `54` present but `0`, `0.00` or not a number | FAIL in both amount cases, "unusable amount" |
| 34 | QR-kind case, bad CRC | `6A80`, FAIL with the CRC reason (CRC beats kind) |
| 35 | `01` missing or another value | FAIL, "got unknown QR" |
| 36 | Generic QR case with each of the 4 kinds; QR-kind case over the index (Sunmi) header | Generic QR passes all; index form checked the same way |
| 37 | Case changed mid-tap | the case read at SELECT decides the kind check |
| 38 | Every case has a label and descriptions; a removed case saved by v0.3 or older loads as Generic QR | yes (`PhoneCase.parse`) |
| 39 | Rotate the phone | portrait: one column; landscape: status left, cases right; the case is kept — device |
| 40 | Case never tapped | badge NOT RUN, *Details* shows the description and "No tap with this case yet" (`CaseResultTest`) |
| 41 | Latest tap of a case is open / PASS / FAIL / phone refused the QR | badge IN PROGRESS / PASS / FAIL / CHECK POS under that case only |
| 42 | Several taps of one case | the newest decides the badge; "Tap #n · 3 taps: 2 PASS, 1 FAIL"; *Details* lists all, newest first, with every byte |
| 43 | Case changed mid-tap | the tap shows under the case read at SELECT |
| 45 | Tap a closed row / the open row / rotate | opens it and closes the other, and picks it for the next tap / closes it / the open row is kept — device |
| 44 | App process killed or reinstalled | results reset to NOT RUN (kept in memory only); the log file keeps every finished tap — device |
| 46 | Pick a case with no tap yet | badge WAITING FOR TAP, "Waiting for a tap…"; the previously picked case goes back to NOT RUN if it had no tap (`CaseResultTest`) |
| 47 | Pick a case that already has taps | its latest tap decides the badge, never WAITING (`CaseResultTest`) |
| 48 | Clear results | picked case WAITING FOR TAP, every other case NOT RUN (`CaseResultTest`) |
| 49 | Reject case: the POS breaks the transfer or the link is lost before the QR completes | FAIL, not CHECK POS (`PhoneResponderTest`) |

## Build and install

```bash
./gradlew :core:test :phone-test:assembleDebug
adb -s <phone-serial> install -r phone-test/build/outputs/apk/debug/phone-test-debug.apk
```

Pick a case from adb (optional): `adb shell am start -n pk.paysys.tap2pay.phonetest/pk.paysys.tap2pay.phonetest.MainActivity --es case ACCEPT`.
Every tap is also written to `/sdcard/Android/data/pk.paysys.tap2pay.phonetest/files/taps.txt` and logcat tag `Tap2PayPhoneTest`.
