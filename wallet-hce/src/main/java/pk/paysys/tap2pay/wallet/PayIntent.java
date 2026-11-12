package pk.paysys.tap2pay.wallet;

import android.content.Intent;

/**
 * Interim hand-off contract between the Tap2Pay receiver and a payment app (e.g. the NBP beta app).
 * Not defined by the spec: in the target design the bank app hosts the HCE service itself (P-03).
 *
 * A payment app opts in by declaring an activity with:
 *   <intent-filter>
 *     <action android:name="pk.paysys.tap2pay.action.PAY_INVOICE" />
 *     <category android:name="android.intent.category.DEFAULT" />
 *     <data android:mimeType="text/plain" />
 *   </intent-filter>
 * and reads the EMV-QR-style invoice string from EXTRA_QR (also in Intent.EXTRA_TEXT).
 */
final class PayIntent {
    static final String ACTION_PAY = "pk.paysys.tap2pay.action.PAY_INVOICE";
    static final String EXTRA_QR = "pk.paysys.tap2pay.extra.QR";

    private PayIntent() {}

    static Intent build(String qr) {
        return new Intent(ACTION_PAY)
                .setType("text/plain")
                .putExtra(EXTRA_QR, qr)
                .putExtra(Intent.EXTRA_TEXT, qr);
    }
}
