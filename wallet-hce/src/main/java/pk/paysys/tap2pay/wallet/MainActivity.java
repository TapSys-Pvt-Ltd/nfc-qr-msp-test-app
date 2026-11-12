package pk.paysys.tap2pay.wallet;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Typeface;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shows NFC/HCE status, the last invoice and the APDU log. Claims preferred-service while in front (P-04). */
public class MainActivity extends Activity implements EventLog.Listener {
    private TextView status;
    private TextView invoice;
    private TextView log;
    private CardEmulation cardEmulation;
    private ComponentName service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        invoice = new TextView(this);
        invoice.setTypeface(Typeface.MONOSPACE);
        invoice.setPadding(0, pad, 0, pad);
        Button clear = new Button(this);
        clear.setText("Clear log");
        clear.setOnClickListener(v -> EventLog.clear());
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);

        root.addView(status);
        root.addView(invoice);
        root.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        service = new ComponentName(this, Tap2PayHceService.class);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter != null) cardEmulation = CardEmulation.getInstance(adapter);
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        onChanged();
    }

    @Override
    public void onChanged() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        String nfc = adapter == null ? "NO NFC hardware" : adapter.isEnabled() ? "NFC on" : "NFC OFF - turn it on";
        String route = "";
        if (cardEmulation != null) {
            boolean ours = cardEmulation.isDefaultServiceForAid(service, "F0010203030605");
            route = ours ? " | AID routed here" : " | AID routed elsewhere or chooser";
        }
        status.setText(nfc + route + "\nHold the phone on the POS reader (screen on).");
        invoice.setText(EventLog.lastInvoice);
        log.setText(EventLog.text());
    }
}
