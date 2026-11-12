package pk.paysys.tap2pay.wallet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/** Stand-in for a bank app (NBP beta) on the PayIntent contract: shows what a payment app would receive. */
public class DemoBankActivity extends Activity {
    private TextView text;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        text = new TextView(this);
        text.setTypeface(Typeface.MONOSPACE);
        text.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        String qr = intent.getStringExtra(PayIntent.EXTRA_QR);
        EventLog.add("demo bank app received QR (" + (qr == null ? 0 : qr.length()) + " chars)");
        text.setText("Demo bank app\n\nA real payment app would now show the invoice, "
                + "ask for biometric/PIN (R-05) and send the Raast payment.\n\nReceived QR string:\n\n" + qr);
    }
}
