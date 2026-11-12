package pk.paysys.tap2pay.phonetest;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.PhoneResponder;

/**
 * HCE for the Tap2Pay AID. Every APDU goes to the shared {@link PhoneResponder}; a held answer (slow case) is
 * sent later with {@link #sendResponseApdu}, and dropped if the link goes first. Android calls this on main.
 */
public class PhoneTestService extends HostApduService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        cancelPending();
        try {
            PhoneResponder.Reply reply = TestStore.get(this).responder.onApdu(apdu);
            if (reply.delayMs == 0) return reply.response;
            if (reply.delayMs == PhoneResponder.Reply.NEVER) return null;
            final byte[] response = reply.response;
            pending = () -> {
                pending = null;
                sendResponseApdu(response);
                TestStore.get(this).responder.onDelayedSent(response);
            };
            main.postDelayed(pending, reply.delayMs);
            return null;
        } catch (RuntimeException e) {
            Log.e(TestStore.TAG, "APDU handling failed", e);
            return Apdu.sw(Apdu.SW_UNKNOWN);
        }
    }

    @Override
    public void onDeactivated(int reason) {
        cancelPending();
        TestStore.get(this).responder.onDeactivated(reason == DEACTIVATION_LINK_LOSS);
    }

    @Override
    public void onDestroy() {
        cancelPending();
        super.onDestroy();
    }

    private void cancelPending() {
        if (pending != null) {
            main.removeCallbacks(pending);
            pending = null;
        }
    }
}
