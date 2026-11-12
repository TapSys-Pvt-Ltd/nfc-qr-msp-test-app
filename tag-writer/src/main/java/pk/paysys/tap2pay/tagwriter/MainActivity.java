package pk.paysys.tap2pay.tagwriter;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pk.paysys.tap2pay.core.Hex;
import pk.paysys.tap2pay.core.Invoice;
import pk.paysys.tap2pay.core.TagResponder;
import pk.paysys.tap2pay.core.TagWritePlan;

/**
 * Writes the Tap2Pay sticker: one MIME record {@link TagResponder#MIME_TYPE} holding a static Raast QR, the same
 * record the POS tag route serves, so the iPhone T2P Reader ("NDEF tag") reads it. Every tap first reports what
 * is on the tag; it is written only while armed, and disarmed after a verified read-back. Never locks the tag.
 * adb shell am start -n pk.paysys.tap2pay.tagwritertest/pk.paysys.tap2pay.tagwriter.MainActivity --es arm static
 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    private static final String TAG = "Tap2PayWriter";
    private final StringBuilder lines = new StringBuilder();
    private TextView status;
    private TextView logView;
    private ScrollView scroll;
    private NfcAdapter adapter;
    /** QR to write on the next tap; null = inspect only. Touched on the main and NFC binder threads. */
    private volatile String armed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        status = new TextView(this);
        root.addView(status);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Arm: static QR", () -> arm(Invoice.staticQr())));
        row.addView(button("Disarm", () -> arm(null)));
        row.addView(button("Clear log", () -> {
            lines.setLength(0);
            logView.setText("");
        }));
        root.addView(row);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = NfcAdapter.getDefaultAdapter(this);
        log(adapter == null ? "This phone has no NFC." : "Ready. Hold the card on the back of the phone to inspect it.");
        // Not on recreation: the restored intent still carries the extra and would re-arm after a verified write
        if (savedInstanceState == null) armFromIntent(getIntent());
        showStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        armFromIntent(intent);
    }

    private void armFromIntent(Intent intent) {
        if (intent == null) return;
        String mode = intent.getStringExtra("arm");
        intent.removeExtra("arm");
        if ("static".equals(mode)) arm(Invoice.staticQr());
        else if ("off".equals(mode)) arm(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reader mode only while visible: the system tag dispatcher would otherwise open another app on a tap
        if (adapter != null && adapter.isEnabled()) {
            adapter.enableReaderMode(this, this, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                    | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V, null);
        }
        showStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (adapter != null) adapter.disableReaderMode(this);
    }

    private void arm(String qr) {
        armed = qr;
        log(qr == null ? "Disarmed: taps only inspect." : "ARMED: next tap writes a " + qr.length() + "-char static QR.");
        showStatus();
    }

    private void showStatus() {
        runOnUiThread(() -> status.setText("NFC: " + (adapter == null ? "none" : adapter.isEnabled() ? "on" : "OFF (turn it on, then reopen)")
                + "\nMode: " + (armed == null ? "inspect only" : "ARMED - next tap writes")));
    }

    /** NFC binder thread. */
    @Override
    public void onTagDiscovered(Tag tag) {
        String qr = armed;
        log("-- Tag " + Hex.encode(tag.getId()) + " techs " + techs(tag));
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul != null) log("MIFARE Ultralight family, type " + (ul.getType() == MifareUltralight.TYPE_ULTRALIGHT_C ? "C"
                : ul.getType() == MifareUltralight.TYPE_ULTRALIGHT ? "EV1/NTAG" : "unknown"));
        Ndef ndef = Ndef.get(tag);
        NdefFormatable formatable = NdefFormatable.get(tag);
        NdefMessage message = qr == null ? null : new NdefMessage(NdefRecord.createMime(TagResponder.MIME_TYPE,
                qr.getBytes(StandardCharsets.UTF_8)));
        try {
            boolean writable = false;
            int max = 0;
            if (ndef != null) {
                ndef.connect();
                writable = ndef.isWritable();
                max = ndef.getMaxSize();
                log("NDEF " + ndef.getType() + ": capacity " + max + " B, " + (writable ? "writable" : "READ-ONLY"));
                describe(ndef.getCachedNdefMessage(), "Currently on it");
            } else {
                log(formatable != null ? "Blank: not NDEF-formatted yet (can be formatted)." : "Not an NDEF tag.");
            }
            int size = message == null ? 0 : message.getByteArrayLength();
            TagWritePlan.Action action = TagWritePlan.decide(qr != null, ndef != null, writable, max, formatable != null, size);
            switch (action) {
                case INSPECT_ONLY:
                    log("Inspect only (not armed). Nothing written.");
                    break;
                case TOO_SMALL:
                    log("NOT WRITTEN: message is " + size + " B, tag holds " + max + " B.");
                    break;
                case READ_ONLY:
                    log("NOT WRITTEN: tag is locked read-only.");
                    break;
                case NOT_WRITABLE:
                    log("NOT WRITTEN: this card cannot hold NDEF on this phone (bank card, or MIFARE Classic"
                            + " without NXP support).");
                    break;
                case WRITE:
                    ndef.writeNdefMessage(message);
                    verify(qr, ndef.getNdefMessage());
                    break;
                case FORMAT_AND_WRITE:
                    formatable.connect();
                    try {
                        formatable.format(message);
                    } finally {
                        formatable.close();
                    }
                    log("Formatted and written. Tap again to read it back.");
                    break;
            }
        } catch (Exception e) {
            log("FAILED: " + e + (qr != null ? " - still armed; hold the card still and tap again." : ""));
        } finally {
            try {
                if (ndef != null) ndef.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void verify(String qr, NdefMessage readBack) {
        byte[] got = readBack == null || readBack.getRecords().length == 0 ? null : readBack.getRecords()[0].getPayload();
        if (TagWritePlan.verified(qr.getBytes(StandardCharsets.UTF_8), got)) {
            // Only disarm if nothing re-armed meanwhile
            if (armed == qr) armed = null;
            log("WRITTEN AND VERIFIED: " + readBack.getByteArrayLength() + " B. Disarmed.");
            showStatus();
        } else {
            log("WRITE NOT VERIFIED: read-back differs - still armed, tap again.");
        }
    }

    private void describe(NdefMessage m, String label) {
        if (m == null || m.getRecords().length == 0) {
            log(label + ": empty");
            return;
        }
        log(label + ": " + m.getRecords().length + " record(s), " + m.getByteArrayLength() + " B");
        for (NdefRecord r : m.getRecords()) {
            String type = new String(r.getType(), StandardCharsets.US_ASCII);
            boolean ours = r.getTnf() == NdefRecord.TNF_MIME_MEDIA && TagResponder.MIME_TYPE.equals(type);
            log("  TNF " + r.getTnf() + " type " + type + ", " + r.getPayload().length + " B" + (ours ? " (Tap2Pay QR)" : ""));
        }
    }

    private static String techs(Tag tag) {
        StringBuilder sb = new StringBuilder();
        for (String t : tag.getTechList()) sb.append(sb.length() == 0 ? "" : ",").append(t.substring(t.lastIndexOf('.') + 1));
        return sb.toString();
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void log(String s) {
        Log.i(TAG, s);
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s + "\n";
        runOnUiThread(() -> {
            lines.append(line);
            logView.setText(lines);
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
