package pk.paysys.tap2pay.postag;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.SystemClock;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.Hex;

/** POS as the card: serves the current QR as an NDEF tag and on the Tap2Pay AID (core TagResponder). */
public class TagHceService extends HostApduService {
    private long firstApduAt;

    @Override
    public void onCreate() {
        super.onCreate();
        TagState.load(this);
    }

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        long now = SystemClock.elapsedRealtime();
        if (firstApduAt == 0) firstApduAt = now;
        byte[] resp = TagState.RESPONDER.process(apdu);
        int sw = Apdu.swOf(resp);
        EventLog.add("+" + (now - firstApduAt) + "ms <- " + Hex.encode(apdu, 0, Math.min(apdu.length, 12))
                + (apdu.length > 12 ? " +" + (apdu.length - 12) + "B" : "")
                + "  -> " + (resp.length - 2) + "B " + Apdu.swName(sw));
        return resp;
    }

    @Override
    public void onDeactivated(int reason) {
        EventLog.add("deactivated (" + (reason == DEACTIVATION_LINK_LOSS ? "link loss" : "deselected") + ")");
        TagState.RESPONDER.reset();
        firstApduAt = 0;
    }
}
