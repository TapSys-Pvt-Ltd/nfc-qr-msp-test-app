package pk.paysys.tap2pay.posreader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import pk.paysys.tap2pay.core.Invoice;
import pk.paysys.tap2pay.core.SampleQr;

/** Buttons for each tap scenario plus a timestamped log (also in logcat, tag Tap2PayPOS). */
public class MainActivity extends Activity {
    private static final String TAG = "Tap2PayPOS";
    private final StringBuilder lines = new StringBuilder();
    private TextView logView;
    private ScrollView scroll;
    private EditText amount;
    private TapSession session;
    private String lastPayload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        amount = new EditText(this);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText("1.00");
        amount.setHint("Amount (PKR)");
        root.addView(amount);

        // Raast QRs of each kind, for the phone test app's QR-kind cases (SQRC/DQRC, with/without amount)
        root.addView(note("Raast QR push (phone test app: pick the matching case). Amount from the box above."));
        LinearLayout qrRow1 = new LinearLayout(this);
        qrRow1.addView(weighted(button("SQRC, no amount", () -> pushSample(false, false))));
        qrRow1.addView(weighted(button("SQRC + amount", () -> pushSample(false, true))));
        root.addView(qrRow1);
        LinearLayout qrRow2 = new LinearLayout(this);
        qrRow2.addView(weighted(button("DQRC + amount", () -> pushSample(true, true))));
        qrRow2.addView(weighted(button("DQRC, no amount", () -> pushSample(true, false))));
        root.addView(qrRow2);
        root.addView(note("Old spec invoice format (Track A):"));

        root.addView(button("Tap: fresh invoice (1 APDU)", () -> {
            lastPayload = Invoice.fresh(amountText()).toPayload();
            session.start("fresh", lastPayload, 0);
        }));
        root.addView(button("Tap: fresh invoice (2 chunks)", () -> {
            lastPayload = Invoice.fresh(amountText()).toPayload();
            session.start("fresh chunked", lastPayload, 172);
        }));
        root.addView(button("Tap: replay last invoice (expect 6985)", () -> {
            if (lastPayload == null) {
                log("Send a fresh invoice first.");
                return;
            }
            session.start("replay", lastPayload, 0);
        }));
        root.addView(button("Tap: spec canonical, stale (expect 6985)", () ->
                session.start("canonical", Invoice.CANONICAL, 0)));
        root.addView(button("Probe: Apple VAS (SELECT OSE.VAS.01)", () -> session.startVasProbe()));
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Stop", () -> session.stop()));
        row.addView(button("Clear log", () -> {
            lines.setLength(0);
            logView.setText("");
        }));
        root.addView(row);

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        session = new TapSession(this::log);
        log("Ready. Open the Tap2Pay Wallet Test app on the phone, then press a Tap button.");
        // Not on recreation: the restored intent would still carry the extra and start a second probe.
        if (savedInstanceState == null) runFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        runFromIntent(intent);
    }

    /** adb shell am start -n pk.paysys.tap2pay.posreadertest/pk.paysys.tap2pay.posreader.MainActivity --es run vas */
    private void runFromIntent(Intent intent) {
        if (intent != null && "vas".equals(intent.getStringExtra("run"))) {
            intent.removeExtra("run");
            session.startVasProbe();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session.isSearching()) session.stop();
    }

    private String amountText() {
        String a = amount.getText().toString().trim();
        try {
            return String.format(Locale.US, "%.2f", Double.parseDouble(a));
        } catch (NumberFormatException e) {
            return "1.00";
        }
    }

    /** Pushes a made-up Raast QR in the spec header form (as the Urovo FBL app sends it). */
    private void pushSample(boolean dynamic, boolean withAmount) {
        String ref = "T" + new SimpleDateFormat("yyMMddHHmmssSSS", Locale.US).format(new Date());
        String qr;
        try {
            qr = SampleQr.build(dynamic, withAmount ? amountText() : null, ref);
        } catch (IllegalArgumentException e) {
            log("Not sent: " + e.getMessage());
            return;
        }
        String kind = (dynamic ? "DQRC" : "SQRC") + (withAmount ? " PKR " + amountText() : " no amount");
        log("Raast QR " + kind + ", " + qr.length() + " chars: " + qr);
        session.start(kind, qr, 0);
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static View weighted(View v) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return v;
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
