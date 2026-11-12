package pk.paysys.tap2pay.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the phone test screen shows under one case: the latest tap made with that case, a one-line explanation of
 * it, and how many taps the case has had. A tap belongs to the case read at its SELECT ({@link TapRecord#phoneCase}),
 * so changing the case mid-tap does not move the tap.
 */
public final class CaseResult {
    /** The badge under a case. */
    public enum Status {
        NOT_RUN("NOT RUN"),
        IN_PROGRESS("IN PROGRESS"),
        PASS("PASS"),
        FAIL("FAIL"),
        /** The phone injected its fault; whether the POS handled it is read on the POS. */
        CHECK_POS("CHECK POS"),
        NA("N/A");

        public final String label;

        Status(String label) {
            this.label = label;
        }
    }

    public final PhoneCase phoneCase;
    /** This case's taps, newest first. */
    public final List<TapRecord> taps;
    public final Status status;
    public final int passed;
    public final int failed;

    private CaseResult(PhoneCase phoneCase, List<TapRecord> taps) {
        this.phoneCase = phoneCase;
        this.taps = Collections.unmodifiableList(taps);
        this.status = taps.isEmpty() ? Status.NOT_RUN : status(taps.get(0));
        int p = 0;
        int f = 0;
        for (TapRecord t : taps) {
            if (t.verdict() == TapRecord.Verdict.PASS) p++;
            else if (t.verdict() == TapRecord.Verdict.FAIL) f++;
        }
        passed = p;
        failed = f;
    }

    /** The result for {@code c} from {@code newestFirst} (all taps, any case, newest first). */
    public static CaseResult of(List<TapRecord> newestFirst, PhoneCase c) {
        List<TapRecord> mine = new ArrayList<>();
        for (TapRecord t : newestFirst) if (t.phoneCase == c) mine.add(t);
        return new CaseResult(c, mine);
    }

    public static Status status(TapRecord t) {
        switch (t.verdict()) {
            case PASS: return Status.PASS;
            case FAIL: return Status.FAIL;
            case DONE: return Status.CHECK_POS;
            case NA: return Status.NA;
            default: return Status.IN_PROGRESS;
        }
    }

    /** The latest tap, or null when the case has not been run. */
    public TapRecord latest() {
        return taps.isEmpty() ? null : taps.get(0);
    }

    /** One or two sentences for the card: what the latest tap showed and what the tester still has to check. */
    public String explanation() {
        TapRecord t = latest();
        if (t == null) return "Select this case, then hold the phone on the POS reader.";
        String reason = capitalise(t.reason());
        switch (status) {
            case IN_PROGRESS:
                return "Tap #" + t.number + " in progress.";
            case CHECK_POS:
                return reason + ". The phone's part is done: now check the POS shows: " + phoneCase.checkOnPos;
            case PASS:
                return reason + ". Also check the POS shows: " + phoneCase.checkOnPos;
            default:
                return reason + ".";
        }
    }

    /** "Tap #7 · 3 taps: 2 PASS, 1 FAIL", or "" when not run. */
    public String tally() {
        TapRecord t = latest();
        if (t == null) return "";
        StringBuilder sb = new StringBuilder("Tap #").append(t.number);
        if (taps.size() > 1) {
            sb.append(" · ").append(taps.size()).append(" taps");
            if (passed > 0 || failed > 0) {
                sb.append(": ").append(passed).append(" PASS, ").append(failed).append(" FAIL");
            }
        }
        return sb.toString();
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
