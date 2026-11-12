package pk.paysys.tap2pay.phonetest;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.util.Log;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.PhoneResponder;

/**
 * HCE for the Tap2Pay AID. Every APDU goes to the shared {@link PhoneResponder} and is answered at once.
 * Android calls this on main.
 */
public class PhoneTestService extends HostApduService {
    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        try {
            return TestStore.get(this).responder.onApdu(apdu);
        } catch (RuntimeException e) {
            Log.e(TestStore.TAG, "APDU handling failed", e);
            return Apdu.sw(Apdu.SW_UNKNOWN);
        }
    }

    @Override
    public void onDeactivated(int reason) {
        TestStore.get(this).responder.onDeactivated(reason == DEACTIVATION_LINK_LOSS);
    }
}
