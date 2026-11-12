package pk.paysys.tap2pay.core;

/**
 * How the test phone answers a POS push. {@link #ACCEPT} is the NBP app's behaviour. The {@link Group#QR_CHECK}
 * cases answer exactly like it too, and also check that the POS sent the QR kind the tester expected (static or
 * dynamic, with or without an amount). The {@link Group#FAULT} cases make the phone answer badly on purpose.
 */
public enum PhoneCase {
    ACCEPT(Group.QR_CHECK, null, null, "Accept any QR (like NBP app)",
            "A normal customer phone with the NBP app: any valid Raast QR is accepted.",
            "Takes the whole QR. Answers 9000 (accepted) if its format and CRC are valid, 6A80 (invalid data) if "
                    + "not. Any QR kind passes.",
            "\"Sent to the customer's phone\" or the same success message the POS shows for a real phone."),
    SQRC_NO_AMOUNT(Group.QR_CHECK, RaastQr.TYPE_STATIC, Boolean.FALSE, "SQRC without amount",
            "A static merchant QR (SQRC, 01 = 11) with no amount: the customer types the amount in the app.",
            "Answers like the NBP app (9000). PASS only if the QR is static and has no amount (no tag 54); "
                    + "any other kind is a FAIL that names what arrived.",
            "\"Sent to the customer's phone\"; the static QR screen stays up."),
    SQRC_WITH_AMOUNT(Group.QR_CHECK, RaastQr.TYPE_STATIC, Boolean.TRUE, "SQRC with amount",
            "A static merchant QR (SQRC, 01 = 11) that also carries the amount the cashier entered.",
            "Answers like the NBP app (9000). PASS only if the QR is static and has an amount above 0 (tag 54).",
            "\"Sent to the customer's phone\"; the amount on the POS matches the amount in the details."),
    DQRC_WITH_AMOUNT(Group.QR_CHECK, RaastQr.TYPE_DYNAMIC, Boolean.TRUE, "DQRC with amount",
            "The normal Raast QR sale: a one-time dynamic QR (DQRC, 01 = 12) for the amount the cashier entered.",
            "Answers like the NBP app (9000). PASS only if the QR is dynamic and has an amount above 0 (tag 54).",
            "\"Sent to the customer's phone\"; the amount on the POS matches the amount in the details."),
    DQRC_NO_AMOUNT(Group.QR_CHECK, RaastQr.TYPE_DYNAMIC, Boolean.FALSE, "DQRC without amount",
            "A dynamic QR (01 = 12) with no amount. The Tap2Pay spec requires an amount on a dynamic QR, so a POS "
                    + "should normally never send this; use it to catch one that does.",
            "Answers like the NBP app (9000). PASS only if the QR is dynamic and has no amount (no tag 54).",
            "\"Sent to the customer's phone\"."),
    NOT_INSTALLED(Group.FAULT, null, null, "Phone has no Tap2Pay app",
            "A customer phone with NFC on but no Tap2Pay wallet installed (or the wallet is disabled).",
            "Refuses the first command, the Tap2Pay app selection (SELECT), with 6A82 \"application not found\", "
                    + "as Android does when no app has the Tap2Pay AID. PASS if the POS then stops; FAIL if it "
                    + "still pushes the QR.",
            "A clear \"no Tap2Pay app on this phone\" message, or it falls back to showing the QR to scan. "
                    + "No success message, no crash."),
    REJECT_DATA(Group.FAULT, null, null, "Wallet rejects the QR as invalid",
            "The wallet cannot read the QR it was sent: wrong format, bad CRC or a missing field.",
            "Receives the whole QR, then answers 6A80 \"invalid data\" instead of 9000.",
            "A failure message (not \"sent\"), and the cashier can retry or show the QR to scan."),
    REJECT_CONDITIONS(Group.FAULT, null, null, "Wallet refuses for a business reason",
            "The wallet read the QR but will not take it: the user is logged out, or the invoice is a duplicate "
                    + "or has expired.",
            "Receives the whole QR, then answers 6985 \"conditions not satisfied\".",
            "A failure message (not \"sent\"); ideally it tells the cashier to ask the customer to open the app."),
    REJECT_ERROR(Group.FAULT, null, null, "Wallet hits an internal error",
            "The wallet app crashes or hits an unexpected error while handling the QR.",
            "Receives the whole QR, then answers 6F00 \"no precise diagnosis\".",
            "A failure message (not \"sent\") and a retry option. No crash on the POS."),
    BREAK_SECOND_CHUNK(Group.FAULT, null, null, "Transfer breaks at the 2nd chunk",
            "A long QR is sent in several pieces (chunks) and one piece goes missing or arrives out of order.",
            "Accepts the 1st chunk (9000), refuses the 2nd with 6A86 \"wrong sequence\", then forgets the "
                    + "transfer. N/A if the POS sends the QR in a single command.",
            "A failure message; a retry starts again from the 1st chunk and then succeeds."),
    SLOW_ANSWER(Group.FAULT, null, null, "Phone answers slowly (2 s)",
            "A busy or slow phone that takes a while to answer the final chunk.",
            "Receives the whole QR, waits 2 seconds, then answers 9000. The spec lets the POS wait up to 5 s, "
                    + "so FAIL if the POS drops the link before the answer.",
            "It waits, then shows success. No timeout message."),
    NO_ANSWER(Group.FAULT, null, null, "Phone never answers",
            "The phone freezes, or the customer pulls it away just after the last chunk.",
            "Receives the whole QR and never answers it.",
            "A timeout message after about 5 s, no success message, and the cashier can tap again.");

    /** Where the case is listed on the screen. */
    public enum Group {
        /** Phone answers like the NBP app; the result checks what the POS sent. */
        QR_CHECK("Payment QR checks: phone accepts like the NBP app"),
        /** Phone answers badly on purpose; the result is checked on the POS. */
        FAULT("Failure scenarios: phone misbehaves on purpose");

        public final String title;

        Group(String title) {
            this.title = title;
        }
    }

    public static final long SLOW_ANSWER_MS = 2000L;

    public final Group group;
    /** {@link RaastQr#TYPE_STATIC}, {@link RaastQr#TYPE_DYNAMIC}, or null for any. */
    public final String expectType;
    /** TRUE = tag 54 with an amount above 0, FALSE = no tag 54, null = either. */
    public final Boolean expectAmount;
    public final String label;
    /** The real-world situation the case stands for. */
    public final String simulates;
    /** What the test phone does, and when the phone-side result is PASS or FAIL. */
    public final String phoneDoes;
    /** What a correct POS shows. */
    public final String checkOnPos;

    PhoneCase(Group group, String expectType, Boolean expectAmount, String label, String simulates,
              String phoneDoes, String checkOnPos) {
        this.group = group;
        this.expectType = expectType;
        this.expectAmount = expectAmount;
        this.label = label;
        this.simulates = simulates;
        this.phoneDoes = phoneDoes;
        this.checkOnPos = checkOnPos;
    }

    /** True when the result also checks the QR kind. */
    public boolean checksQrKind() {
        return expectType != null || expectAmount != null;
    }

    /** "static QR without amount" etc., or null when any QR will do. */
    public String expectedKind() {
        if (!checksQrKind()) return null;
        String type = expectType == null ? "any" : expectType;
        String amount = expectAmount == null ? "" : expectAmount ? " with amount" : " without amount";
        return type + " QR" + amount;
    }

    /**
     * Null when {@code qr} is the kind this case expects, else why not. A tag 54 that is present but zero or not
     * a number fails both "with" and "without amount": the POS sent something the NBP app cannot show sensibly.
     */
    public String kindMismatch(RaastQr qr) {
        if (!checksQrKind()) return null;
        StringBuilder got = new StringBuilder(qr.type).append(" QR");
        RaastQr.AmountState a = qr.amountState();
        if (a == RaastQr.AmountState.POSITIVE) got.append(" with amount PKR ").append(qr.amount);
        else if (a == RaastQr.AmountState.NONE) got.append(" without amount");
        else got.append(" with an unusable amount \"").append(qr.amount).append('"');

        boolean typeOk = expectType == null || expectType.equals(qr.type);
        boolean amountOk = expectAmount == null
                || (expectAmount ? a == RaastQr.AmountState.POSITIVE : a == RaastQr.AmountState.NONE);
        if (typeOk && amountOk) return null;
        return "expected " + expectedKind() + ", got " + got;
    }

    /** The case with this name, or {@link #ACCEPT} for null or an unknown name. */
    public static PhoneCase parse(String name) {
        if (name != null) {
            for (PhoneCase c : values()) if (c.name().equalsIgnoreCase(name.trim())) return c;
        }
        return ACCEPT;
    }
}
