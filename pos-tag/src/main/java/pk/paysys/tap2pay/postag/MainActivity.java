package pk.paysys.tap2pay.postag;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import pk.paysys.tap2pay.core.Invoice;

/** Cashier side of the tag route: make an invoice, then the phone reads it. Shows HCE support and an APDU log. */
public class MainActivity extends Activity implements EventLog.Listener {
    private static final String[] AIDS = {"D2760000850101", "F0010203030605"};
    private TextView status;
    private TextView qr;
    private TextView log;
    private EditText amount;
    private CardEmulation cardEmulation;
    private ComponentName service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TagState.load(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        amount = new EditText(this);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText("500.00");
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button("New invoice", () -> offer(Invoice.fresh(amount.getText().toString()).toPayload())));
        buttons.addView(button("Static QR", () -> offer(Invoice.staticQr())));
        buttons.addView(button("Idle", () -> offer(null)));
        buttons.addView(button("Clear log", EventLog::clear));
        qr = new TextView(this);
        qr.setTypeface(Typeface.MONOSPACE);
        qr.setTextSize(11);
        qr.setPadding(0, pad, 0, pad);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(status);
        root.addView(amount);
        root.addView(buttons);
        root.addView(qr);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        service = new ComponentName(this, TagHceService.class);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        // Throws on devices that declare HCE but whose NFC service has no card emulation (seen on the i9100)
        try {
            if (adapter != null) cardEmulation = CardEmulation.getInstance(adapter);
        } catch (UnsupportedOperationException e) {
            EventLog.add("no card emulation service: HCE cannot work on this device");
        }
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void offer(String payload) {
        try {
            TagState.set(this, payload);
            EventLog.add(payload == null ? "idle: no tag offered" : "offering " + payload.length() + "-char QR");
        } catch (IllegalArgumentException e) {
            EventLog.add("not offered: " + e.getMessage());
        }
        onChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        EventLog.setListener(this);
        if (cardEmulation != null) cardEmulation.setPreferredService(this, service);
        onChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cardEmulation != null) cardEmulation.unsetPreferredService(this);
        EventLog.setListener(null);
    }

    @Override
    public void onChanged() {
        PackageManager pm = getPackageManager();
        boolean nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC);
        boolean hce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        StringBuilder s = new StringBuilder();
        s.append("Android NFC: ").append(nfc ? "yes" : "NO").append(" | HCE: ").append(hce ? "yes" : "NO");
        s.append(" | adapter: ").append(adapter == null ? "none" : adapter.isEnabled() ? "on" : "OFF");
        if (adapter != null && cardEmulation == null) s.append("\nCard emulation service MISSING: no HCE here");
        if (cardEmulation != null) {
            for (String aid : AIDS) {
                s.append("\n").append(aid).append(cardEmulation.isDefaultServiceForAid(service, aid)
                        ? " routed here" : " routed elsewhere");
            }
        }
        status.setText(s);
        String current = TagState.RESPONDER.qr();
        qr.setText(current == null ? "Idle: no tag offered." : "Offering (" + current.length() + " chars):\n" + current);
        log.setText(EventLog.text());
    }
}
