package pk.paysys.tap2pay.posreader;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.urovo.sdk.rfcard.RFCardHandlerImpl;
import com.urovo.sdk.rfcard.listener.RFSearchListener;
import com.urovo.sdk.rfcard.utils.CardInfo;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.Hex;
import pk.paysys.tap2pay.core.TapGate;
import pk.paysys.tap2pay.core.VasProbe;

/**
 * One tap: searchCard -> activate("CPU") -> script (Tap2Pay SELECT + PUSH INVOICE, or the Apple VAS probe) -> halt.
 * Every step is timed so we can compare against spec Table 14 (T_wait_sw, T_resp). {@link TapGate} keeps one
 * search or exchange on the RF at a time and drops late callbacks from a stopped search.
 */
final class TapSession {
    interface Log {
        void line(String s);
    }

    /** What to send once ISO-DEP is open; {@code t0} is the detection time. */
    private interface Script {
        void run(long t0) throws Exception;
    }

    private static final long SEARCH_TIMEOUT_MS = 30_000;

    private final RFCardHandlerImpl rf = RFCardHandlerImpl.getInstance();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TapGate gate = new TapGate();
    private final Log log;
    /** Last search's timeout, removed on Stop; each search's own callbacks remove their own copy. */
    private volatile Runnable pendingTimeout;
    private volatile long searchStartedAt;

    TapSession(Log log) {
        this.log = log;
    }

    boolean isSearching() {
        return gate.isSearching();
    }

    void start(final String label, final String payload, final int chunkSize) {
        final List<byte[]> commands = Apdu.pushInvoice(payload, chunkSize);
        begin("== " + label + ": " + payload.length() + " B in " + commands.size() + " APDU(s). Tap the phone now.",
                t0 -> pushInvoice(commands, t0));
    }

    void startVasProbe() {
        begin("== Apple VAS probe: SELECT OSE.VAS.01. Hold the iPhone on the reader with Wallet showing a card or pass.",
                this::vasProbe);
    }

    private void begin(String banner, final Script script) {
        final int id = gate.tryStart();
        if (id == 0) {
            log.line(gate.isExchanging() ? "Still talking to the last phone - wait for its RESULT line."
                    : "Already waiting for a phone - press Stop first.");
            return;
        }
        log.line(banner);
        searchStartedAt = SystemClock.elapsedRealtime();
        final Runnable timeout = () -> onSearchTimeout(id);
        pendingTimeout = timeout;
        main.postDelayed(timeout, SEARCH_TIMEOUT_MS);
        try {
            rf.searchCard(new RFSearchListener() {
                @Override
                public void onCardPass(int cardType, byte[] uid, CardInfo info) {
                    if (!gate.detected(id)) {
                        log.line("Ignored a card detected after the search ended.");
                        return;
                    }
                    main.removeCallbacks(timeout);
                    worker.execute(() -> exchange(cardType, uid, info, script));
                }

                @Override
                public void onFail(int code, String message) {
                    if (gate.timedOut(id)) {
                        main.removeCallbacks(timeout);
                        log.line("searchCard failed: " + code + " " + message);
                    }
                }
            });
        } catch (Exception e) {
            gate.timedOut(id);
            main.removeCallbacks(timeout);
            log.line("searchCard threw: " + e);
        }
    }

    /** Ends a search. During an exchange the RF is left alone; the exchange halts it when it finishes. */
    void stop() {
        Runnable t = pendingTimeout;
        if (t != null) main.removeCallbacks(t);
        if (gate.isExchanging()) {
            log.line("Exchange in progress - it will halt the field when done.");
            return;
        }
        gate.stopSearch();
        stopRf();
        log.line("Stopped.");
    }

    private void onSearchTimeout(int id) {
        if (gate.timedOut(id)) {
            log.line("No phone detected in " + SEARCH_TIMEOUT_MS / 1000 + " s.");
            stopRf();
        }
    }

    private void stopRf() {
        try {
            rf.stopSearch();
            rf.halt();
        } catch (Exception e) {
            log.line("stop threw: " + e);
        }
    }

    private void exchange(int cardType, byte[] uid, CardInfo info, Script script) {
        long t0 = SystemClock.elapsedRealtime();
        log.line("Detected after " + (t0 - searchStartedAt) + " ms: type=" + cardType
                + " uid=" + Hex.encode(uid)
                + (info != null ? " SAK=" + String.format("%02X", info.SAK) + " ATQA=" + Hex.encode(info.ATQA) : ""));
        try {
            byte[] ats = new byte[64];
            int act = rf.activate("CPU", ats);
            log.line("activate(CPU) = " + act + (act > 0 ? " ATS=" + Hex.encode(ats, 0, Math.min(act, ats.length)) : "")
                    + " [" + (SystemClock.elapsedRealtime() - t0) + " ms]");
            if (act < 0) {
                log.line("RESULT: FAIL - could not open ISO-DEP to the phone.");
                return;
            }
            script.run(t0);
        } catch (Exception e) {
            log.line("RESULT: FAIL - " + e + " [" + (SystemClock.elapsedRealtime() - t0) + " ms]");
        } finally {
            try {
                rf.halt();
            } catch (Exception ignored) {
            }
            gate.finished();
        }
    }

    private void pushInvoice(List<byte[]> commands, long t0) throws Exception {
        byte[] resp = send("SELECT", Apdu.SELECT);
        int sw = Apdu.swOf(resp);
        if (sw != Apdu.SW_OK) {
            log.line("RESULT: FAIL at SELECT (" + Apdu.swName(sw) + "). Is the wallet app installed, NFC on, screen on?");
            return;
        }
        log.line("FCI " + describeFci(resp));

        for (int i = 0; i < commands.size(); i++) {
            resp = send("PUSH " + (i + 1) + "/" + commands.size(), commands.get(i));
            sw = Apdu.swOf(resp);
            if (sw != Apdu.SW_OK) {
                log.line("RESULT: phone rejected invoice - " + Apdu.swName(sw)
                        + " [" + (SystemClock.elapsedRealtime() - t0) + " ms total]");
                return;
            }
        }
        log.line("RESULT: OK - invoice accepted by phone in " + (SystemClock.elapsedRealtime() - t0)
                + " ms (activate -> last SW). POS would now wait for payment (T_paywait).");
    }

    private void vasProbe(long t0) throws Exception {
        VasProbe.Result r = VasProbe.describe(send("SELECT OSE.VAS.01", VasProbe.SELECT));
        for (String line : r.lines) log.line("  " + line);
        log.line("RESULT: VAS " + r.verdict + " - " + r.verdict.summary
                + " [" + (SystemClock.elapsedRealtime() - t0) + " ms]");
    }

    private byte[] send(String name, byte[] cmd) throws Exception {
        long t = SystemClock.elapsedRealtime();
        byte[] resp = rf.exchangeApdu(cmd);
        long ms = SystemClock.elapsedRealtime() - t;
        log.line(name + " >> " + Hex.encode(cmd, 0, Math.min(cmd.length, 5)) + (cmd.length > 5 ? " +" + (cmd.length - 5) + "B" : "")
                + "  << " + (resp == null ? "null" : Apdu.swName(Apdu.swOf(resp))) + "  " + ms + " ms"
                + (ms > 1000 ? "  (over T_resp 1 s)" : ""));
        return resp;
    }

    /** Minimal FCI decode: 6F { 84 AID, A5 { 01 version, 02 capabilities } }. */
    private static String describeFci(byte[] resp) {
        String hex = Hex.encode(resp, 0, resp.length - 2).replace(" ", "");
        int v = hex.indexOf("A506");
        if (hex.startsWith("6F") && hex.contains("8407F0010203030605") && v >= 0 && hex.length() >= v + 16) {
            return "ok: version=" + hex.substring(v + 8, v + 10) + " capabilities=" + hex.substring(v + 14, v + 16);
        }
        return "not understood (" + hex + "), treating as v1";
    }
}
