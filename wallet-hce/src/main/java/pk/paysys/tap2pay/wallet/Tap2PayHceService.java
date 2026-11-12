package pk.paysys.tap2pay.wallet;

import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.Hex;
import pk.paysys.tap2pay.core.InvoiceValidator;

/** Phone side of spec Clause 3: SELECT -> FCI, PUSH INVOICE with chunk reassembly (Clause 3.8), validation (Table 10). */
public class Tap2PayHceService extends HostApduService {
    private static final long T_CHUNK_GAP_MS = 2000;
    private static final int MAX_BUFFER = 1024;
    /** Shared across service instances so duplicate nonces are caught across taps. */
    static final InvoiceValidator VALIDATOR = new InvoiceValidator();

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int expectChunk = 1;
    private long lastChunkAt;
    private long selectedAt;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        EventLog.add("<- " + Hex.encode(apdu, 0, Math.min(apdu.length, 5))
                + (apdu.length > 5 ? " +" + (apdu.length - 5) + "B" : ""));
        byte[] resp = handle(apdu);
        EventLog.add("-> " + (resp.length > 2 ? Hex.encode(resp, 0, resp.length - 2) + " " : "")
                + Apdu.swName(Apdu.swOf(resp)));
        return resp;
    }

    private byte[] handle(byte[] apdu) {
        if (apdu.length < 4) return Apdu.sw(Apdu.SW_WRONG_LENGTH);
        int cla = apdu[0] & 0xFF, ins = apdu[1] & 0xFF, p1 = apdu[2] & 0xFF, p2 = apdu[3] & 0xFF;

        if (cla == 0x00 && ins == 0xA4 && p1 == 0x04) {
            if (apdu.length >= 5 + Apdu.AID.length
                    && Arrays.equals(Arrays.copyOfRange(apdu, 5, 5 + Apdu.AID.length), Apdu.AID)) {
                reset();
                selectedAt = SystemClock.elapsedRealtime();
                EventLog.add("SELECT Tap2Pay AID ok");
                return Apdu.withSw(Apdu.FCI, Apdu.SW_OK);
            }
            return Apdu.sw(0x6A82);
        }
        if (cla != Apdu.CLA_PROPRIETARY || ins != Apdu.INS_PUSH_INVOICE) return Apdu.sw(Apdu.SW_INS_NOT_SUPPORTED);

        // Table 10 rule 1: header
        if (p1 != Apdu.P1_PUSH) {
            reset();
            return Apdu.sw(Apdu.SW_WRONG_P1P2);
        }
        if (apdu.length < 6) return Apdu.sw(Apdu.SW_WRONG_LENGTH);
        int lc = apdu[4] & 0xFF;
        if (lc == 0 || apdu.length < 5 + lc) return Apdu.sw(Apdu.SW_WRONG_LENGTH);
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + lc);

        if (p2 == 0x00) {
            if (buffer.size() > 0) {
                reset();
                return Apdu.sw(Apdu.SW_NOT_ALLOWED);
            }
            return validate(data);
        }
        long now = SystemClock.elapsedRealtime();
        int idx = p2 & 0x7F;
        boolean last = (p2 & 0x80) != 0;
        if (idx != expectChunk || (expectChunk > 1 && now - lastChunkAt > T_CHUNK_GAP_MS)) {
            EventLog.add("chunk sequence error: got " + idx + ", expected " + expectChunk);
            reset();
            return Apdu.sw(Apdu.SW_WRONG_P1P2);
        }
        // Rule 2: buffer limit
        if (buffer.size() + data.length > MAX_BUFFER) {
            reset();
            return Apdu.sw(Apdu.SW_NO_MEMORY);
        }
        buffer.write(data, 0, data.length);
        expectChunk++;
        lastChunkAt = now;
        if (!last) return Apdu.sw(Apdu.SW_OK);
        byte[] complete = buffer.toByteArray();
        reset();
        return validate(complete);
    }

    private byte[] validate(byte[] data) {
        String payload = new String(data, StandardCharsets.UTF_8);
        InvoiceValidator.Result r = VALIDATOR.validate(payload, System.currentTimeMillis());
        long ms = selectedAt > 0 ? SystemClock.elapsedRealtime() - selectedAt : -1;
        EventLog.add("invoice " + data.length + "B -> " + Apdu.swName(r.sw) + " (" + r.reason + "), "
                + ms + " ms since SELECT");
        if (r.top != null) {
            EventLog.lastInvoice = describe(r);
            if (r.ok()) showInvoice(payload, EventLog.lastInvoice);
        }
        return Apdu.sw(r.sw);
    }

    private static String describe(InvoiceValidator.Result r) {
        return (r.ok() ? "ACCEPTED" : "REJECTED: " + r.reason) + "\n"
                + "Merchant: " + r.top.get("59") + ", " + r.top.get("60") + "\n"
                + "Amount:   PKR " + r.top.get("54") + "\n"
                + "IBAN:     " + r.account.get("00") + "\n"
                + "Bill:     " + r.additional.get("01") + "   Ref: " + r.additional.get("05") + "\n"
                + "Terminal: " + r.top.get("80") + " / " + r.additional.get("07") + "\n"
                + "Nonce:    " + r.top.get("81") + "\n"
                + "Time:     " + r.top.get("82");
    }

    /** Clause 4.2 DISPLAYED: show the invoice (also over the lock screen) and offer the app chooser. */
    private void showInvoice(String qr, String summary) {
        Intent i = new Intent(this, InvoiceHandoffActivity.class)
                .putExtra(PayIntent.EXTRA_QR, qr)
                .putExtra(InvoiceHandoffActivity.EXTRA_SUMMARY, summary)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            EventLog.add("could not open screen: " + e.getMessage());
        }
    }

    private void reset() {
        buffer.reset();
        expectChunk = 1;
        lastChunkAt = 0;
    }

    @Override
    public void onDeactivated(int reason) {
        EventLog.add("deactivated (" + (reason == DEACTIVATION_LINK_LOSS ? "link loss" : "deselected") + ")");
        reset();
        selectedAt = 0;
    }
}
