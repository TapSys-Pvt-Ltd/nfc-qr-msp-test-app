package pk.paysys.tap2pay.wallet;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Clause 4.2 DISPLAYED, interim form: shown after an accepted tap (also over the lock screen),
 * then hands the invoice QR string to a payment app picked by the user from the Android chooser.
 * The chosen app owns confirmation and CVM (R-05); this screen never authorizes anything.
 */
public class InvoiceHandoffActivity extends Activity {
    static final String EXTRA_SUMMARY = "summary";

    private String qr;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Tap2Pay invoice received");
        title.setTextSize(20);
        summary = new TextView(this);
        summary.setTypeface(Typeface.MONOSPACE);
        summary.setPadding(0, pad, 0, pad);
        Button pay = new Button(this);
        pay.setText("Pay with…");
        pay.setOnClickListener(v -> unlockThenChoose());
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            EventLog.add("handoff cancelled by user");
            finish();
        });

        root.addView(title);
        root.addView(summary);
        root.addView(pay, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent intent) {
        qr = intent.getStringExtra(PayIntent.EXTRA_QR);
        summary.setText(intent.getStringExtra(EXTRA_SUMMARY));
    }

    /** The chooser cannot be drawn over the keyguard, so ask the user to unlock first. */
    private void unlockThenChoose() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked()) {
            choose();
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            choose();
            return;
        }
        EventLog.add("phone locked: asking user to unlock");
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                choose();
            }

            @Override
            public void onDismissCancelled() {
                EventLog.add("unlock cancelled; invoice not handed off");
            }

            @Override
            public void onDismissError() {
                EventLog.add("unlock error; invoice not handed off");
            }
        });
    }

    private void choose() {
        if (qr == null) {
            finish();
            return;
        }
        Intent pay = PayIntent.build(qr);
        PackageManager pm = getPackageManager();
        Intent target;
        if (!pm.queryIntentActivities(pay, 0).isEmpty()) {
            target = Intent.createChooser(pay, "Pay with");
            EventLog.add("chooser: " + PayIntent.ACTION_PAY + " (" + pm.queryIntentActivities(pay, 0).size()
                    + " app(s))");
        } else {
            // No payment app has adopted the Tap2Pay action yet: share the QR string as plain text.
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, qr);
            target = Intent.createChooser(send, "Send invoice QR to");
            EventLog.add("chooser: no Tap2Pay payment app installed, falling back to share sheet");
        }
        startActivity(target);
        finish();
    }
}
