package pk.paysys.tap2pay.postag;

import android.content.Context;
import android.content.SharedPreferences;

import pk.paysys.tap2pay.core.TagResponder;

/**
 * The QR the POS is currently offering. Kept in prefs too, so a service started in a fresh process (app killed
 * after the invoice was created) still serves it instead of answering "no tag".
 */
final class TagState {
    static final TagResponder RESPONDER = new TagResponder();
    private static final String PREFS = "tag";
    private static final String KEY_QR = "qr";
    private static boolean loaded;

    private TagState() {}

    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        RESPONDER.setQr(prefs(c).getString(KEY_QR, null));
    }

    /** null = idle (no tag). Throws IllegalArgumentException if the QR is too long; nothing is changed then. */
    static synchronized void set(Context c, String qr) {
        RESPONDER.setQr(qr);
        loaded = true;
        prefs(c).edit().putString(KEY_QR, RESPONDER.qr()).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
