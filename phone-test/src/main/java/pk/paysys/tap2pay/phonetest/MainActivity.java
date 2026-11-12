package pk.paysys.tap2pay.phonetest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import pk.paysys.tap2pay.core.CaseResult;
import pk.paysys.tap2pay.core.PhoneCase;
import pk.paysys.tap2pay.core.TapRecord;

/**
 * Pick a case, hold the phone on the Urovo or Sunmi reader, read the result under that case. While this screen is
 * in front it is the preferred service for the Tap2Pay AID, so it wins over the NBP app (which registers the same
 * AID). Optional extra {@code case} (a {@link PhoneCase} name) selects the case, e.g. from adb.
 * Portrait: one column. Landscape: status on the left, cases on the right.
 * Results are kept in memory since the app started; every finished tap is also appended to the log file.
 */
public class MainActivity extends Activity implements TestStore.Listener {
    static final String EXTRA_CASE = "case";
    private static final String KEY_EXPANDED = "expanded";
    private static final String AID = "F0010203030605";
    private static final String NBP_APP = "com.paysys.nbpdigital";

    // Tapsys brand tokens (Merchant_App/tapsys-merchant-instant-payment-app/mobile/src/theme/colors.ts)
    private static final int PAGE = 0xFFF7F8FB;        // bg
    private static final int SURFACE2 = 0xFFF0F2F6;    // surface2
    private static final int INK = 0xFF1C2533;         // fg
    private static final int MUTED = 0xFF6E7889;       // muted
    private static final int LINE = 0xFFE2E6EE;        // border
    private static final int ACCENT = 0xFF3D74E5;      // accent, friendly blue
    private static final int ACCENT_FILL = 0xFFE9F0FB; // accentSoft
    private static final int ACCENT_INK = 0xFF2150B5;  // accentInk
    private static final int BRAND = 0xFF1F47A8;       // accentDeep, authority blue
    private static final int GOOD = 0xFF1F9655;        // success
    private static final int GOOD_FILL = 0xFFE5F5EB;   // successSoft
    private static final int WARN = 0xFFD08018;        // warn
    private static final int WARN_FILL = 0xFFFBF1E2;   // warn on a soft tint
    private static final int BAD = 0xFFD8341F;         // danger
    private static final int BAD_FILL = 0xFFFAEAE7;    // dangerSoft
    private static final Typeface DISPLAY = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** The views under one case that change after a tap. */
    private static final class CaseViews {
        final PhoneCase phoneCase;
        final LinearLayout card;
        final TextView radio;
        final TextView title;
        final TextView badge;
        final TextView chevron;
        final LinearLayout body;
        final TextView explanation;
        final TextView tally;
        final Button details;

        CaseViews(PhoneCase phoneCase, LinearLayout card, TextView radio, TextView title, TextView badge,
                  TextView chevron, LinearLayout body, TextView explanation, TextView tally, Button details) {
            this.phoneCase = phoneCase;
            this.card = card;
            this.radio = radio;
            this.title = title;
            this.badge = badge;
            this.chevron = chevron;
            this.body = body;
            this.explanation = explanation;
            this.tally = tally;
            this.details = details;
        }
    }

    private TestStore store;
    private TextView nfcStatus;
    private TextView nextCase;
    private TextView summary;
    private TextView deviceStatus;
    private final List<CaseViews> caseViews = new ArrayList<>();
    private AlertDialog dialog;
    /** The one case card that is open, or null when all are closed. */
    private PhoneCase expanded;
    private CardEmulation cardEmulation;
    private ComponentName service;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = TestStore.get(this);
        expanded = savedInstanceState != null
                ? parseOrNull(savedInstanceState.getString(KEY_EXPANDED)) : store.currentCase();

        LinearLayout status = column();
        status.addView(statusCard());

        LinearLayout cases = column();
        PhoneCase.Group group = null;
        for (PhoneCase c : PhoneCase.values()) {
            if (c.group != group) {
                group = c.group;
                cases.addView(sectionTitle(group.title));
            }
            cases.addView(caseCard(c));
        }
        cases.addView(footnote("Results are kept since the app started. Every finished tap is also saved to "
                + store.logPath()));

        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = column();
        root.setBackgroundColor(PAGE);
        root.addView(appBar());
        if (landscape) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(scroll(status), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2));
            row.addView(scroll(cases), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
            root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            LinearLayout all = column();
            all.addView(status);
            all.addView(cases);
            root.addView(scroll(all), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        setContentView(root);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        service = new ComponentName(this, PhoneTestService.class);
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        if (adapter != null) cardEmulation = CardEmulation.getInstance(adapter);
        applyCaseExtra(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyCaseExtra(intent);
    }

    private void applyCaseExtra(Intent intent) {
        String name = intent != null ? intent.getStringExtra(EXTRA_CASE) : null;
        if (name != null) {
            store.setCase(PhoneCase.parse(name));
            expanded = store.currentCase();
        }
        showCurrentCase();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_EXPANDED, expanded == null ? "" : expanded.name());
    }

    /** The case with this exact name, or null (PhoneCase.parse would fall back to ACCEPT). */
    private static PhoneCase parseOrNull(String name) {
        for (PhoneCase c : PhoneCase.values()) if (c.name().equals(name)) return c;
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.setListener(this);
        if (cardEmulation != null) cardEmulation.setPreferredService(this, service);
        onTapsChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cardEmulation != null) cardEmulation.unsetPreferredService(this);
        store.setListener(null);
    }

    @Override
    protected void onDestroy() {
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }

    @Override
    public void onTapsChanged() {
        if (isFinishing() || isDestroyed()) return;
        showStatus();
        showCurrentCase();
        List<TapRecord> taps = store.taps();
        int pass = 0;
        int fail = 0;
        int check = 0;
        int notRun = 0;
        for (CaseViews v : caseViews) {
            CaseResult r = CaseResult.of(taps, v.phoneCase);
            showResult(v, r);
            switch (r.status) {
                case PASS: pass++; break;
                case FAIL: fail++; break;
                case CHECK_POS: check++; break;
                case NOT_RUN: notRun++; break;
                default: break;
            }
        }
        summary.setText(String.format(Locale.US, "%d\u00a0PASS  ·  %d\u00a0FAIL  ·  %d\u00a0CHECK\u00a0POS  ·  %d\u00a0NOT\u00a0RUN",
                pass, fail, check, notRun));
    }

    // ---------------------------------------------------------------- cases

    /**
     * Closed: name and result badge only. Tapping the header picks the case for the next tap and opens it (closing
     * the one that was open); tapping an open header closes it. Open: what it simulates, the result and Details.
     */
    private View caseCard(PhoneCase c) {
        LinearLayout card = column();

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(14), dp(12), dp(14));
        TextView radio = text("○", 20, MUTED);
        radio.setPadding(0, 0, dp(10), 0);
        top.addView(radio);
        TextView title = text(c.label, 16, INK);
        title.setTypeface(DISPLAY);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView badge = text("", 11, INK);
        badge.setTypeface(DISPLAY);
        badge.setLetterSpacing(0.06f);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(badge);
        TextView chevron = text("", 14, MUTED);
        chevron.setPadding(dp(10), 0, 0, 0);
        top.addView(chevron);
        top.setClickable(true);
        top.setFocusable(true);
        top.setOnClickListener(v -> onCaseHeaderClicked(c));
        card.addView(top);

        LinearLayout body = column();
        body.setPadding(dp(16), 0, dp(16), dp(6));
        TextView simulates = text(c.simulates, 14, INK);
        simulates.setLineSpacing(0, 1.1f);
        body.addView(simulates);

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dl.topMargin = dp(12);
        dl.bottomMargin = dp(8);
        body.addView(divider, dl);

        TextView resultLabel = text("RESULT", 11, MUTED);
        resultLabel.setTypeface(Typeface.DEFAULT_BOLD);
        resultLabel.setLetterSpacing(0.08f);
        body.addView(resultLabel);
        TextView explanation = text("", 14, INK);
        explanation.setPadding(0, dp(3), 0, 0);
        explanation.setLineSpacing(0, 1.1f);
        body.addView(explanation);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView tally = text("", 12, MUTED);
        bottom.addView(tally, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button details = new Button(this, null, android.R.attr.borderlessButtonStyle);
        details.setText("Details");
        details.setAllCaps(false);
        details.setTextColor(ACCENT);
        details.setTextSize(14);
        details.setOnClickListener(v -> showDetails(c));
        bottom.addView(details);
        body.addView(bottom);
        card.addView(body);

        caseViews.add(new CaseViews(c, card, radio, title, badge, chevron, body, explanation, tally, details));
        return withMargins(card);
    }

    private void onCaseHeaderClicked(PhoneCase c) {
        if (store.currentCase() != c) {
            store.setCase(c);
            expanded = c;
        } else {
            expanded = expanded == c ? null : c;
        }
        showCurrentCase();
    }

    private void showCurrentCase() {
        PhoneCase current = store.currentCase();
        for (CaseViews v : caseViews) {
            boolean on = v.phoneCase == current;
            v.card.setBackground(rounded(on ? ACCENT_FILL : Color.WHITE, on ? ACCENT : LINE, on ? 2 : 1, 16));
            v.card.setSelected(on);
            v.radio.setText(on ? "◉" : "○");
            v.radio.setTextColor(on ? ACCENT : MUTED);
            v.title.setTextColor(on ? ACCENT_INK : INK);
            boolean open = v.phoneCase == expanded;
            v.body.setVisibility(open ? View.VISIBLE : View.GONE);
            v.chevron.setText(open ? "▲" : "▼");
            v.card.getChildAt(0).setContentDescription(v.phoneCase.label + (on ? ", selected for the next tap" : "")
                    + (open ? ", open" : ", closed"));
        }
        if (nextCase != null) nextCase.setText(current.label);
    }

    private void showResult(CaseViews v, CaseResult r) {
        v.badge.setText(r.status.label);
        v.badge.setBackground(rounded(badgeFill(r.status), badgeStroke(r.status), 1, 20));
        v.badge.setTextColor(badgeInk(r.status));
        v.explanation.setText(r.explanation());
        v.explanation.setTextColor(r.status == CaseResult.Status.FAIL ? BAD
                : r.status == CaseResult.Status.PASS ? GOOD : r.status == CaseResult.Status.NOT_RUN ? MUTED : INK);
        v.tally.setText(r.tally());
    }

    /** The rest of the case: what it simulates, what the phone does, what the POS should show, and its log. */
    private void showDetails(PhoneCase c) {
        CaseResult r = CaseResult.of(store.taps(), c);
        LinearLayout content = column();
        content.setPadding(dp(20), dp(4), dp(20), dp(12));
        TextView simulates = text(c.simulates, 14, INK);
        simulates.setLineSpacing(0, 1.1f);
        content.addView(simulates);
        if (c.checksQrKind()) content.addView(labelled("EXPECTS", c.expectedKind(), ACCENT_INK));
        content.addView(labelled("PHONE", c.phoneDoes, INK));
        content.addView(labelled("POS SHOULD SHOW", c.checkOnPos, INK));
        content.addView(labelled("RESULT", r.status.label + (r.tally().isEmpty() ? "" : "  ·  " + r.tally())
                + "\n" + r.explanation(), badgeInk(r.status)));

        TextView logLabel = text(r.taps.isEmpty() ? "LOG" : "LOG, NEWEST TAP FIRST", 11, MUTED);
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        logLabel.setLetterSpacing(0.08f);
        logLabel.setPadding(0, dp(14), 0, dp(4));
        content.addView(logLabel);
        final String log = detailsText(r);
        TextView logText = text(r.taps.isEmpty() ? "No tap with this case yet." : log, 11, INK);
        if (!r.taps.isEmpty()) {
            logText.setTypeface(Typeface.MONOSPACE);
            logText.setTextIsSelectable(true);
        }
        logText.setPadding(dp(10), dp(8), dp(10), dp(8));
        logText.setBackground(rounded(SURFACE2, SURFACE2, 1, 8));
        content.addView(logText);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        if (dialog != null) dialog.dismiss();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(c.label)
                .setView(scroll)
                .setPositiveButton("Close", null);
        if (!r.taps.isEmpty()) {
            b.setNeutralButton("Copy log", (d, w) -> copy(c.label, log,
                    r.taps.size() + (r.taps.size() == 1 ? " tap copied" : " taps copied")));
        }
        dialog = b.create();
        dialog.show();
    }

    /** Every tap of the case, newest first, with every byte sent and received. */
    private static String detailsText(CaseResult r) {
        StringBuilder sb = new StringBuilder();
        for (TapRecord t : r.taps) {
            sb.append(TestStore.stamp(t.startedWallMs)).append('\n').append(t.describe(true)).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- header and status

    private View appBar() {
        LinearLayout bar = column();
        bar.setBackgroundColor(Color.WHITE);
        bar.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.tapsys_wordmark);
        logo.setAdjustViewBounds(true);
        logo.setContentDescription("Tapsys");
        row.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)));
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(1), dp(22));
        rp.setMargins(dp(12), 0, dp(12), 0);
        row.addView(rule, rp);
        TextView title = text("Tap2Pay Phone Test", 18, INK);
        title.setTypeface(DISPLAY);
        row.addView(title);
        bar.addView(row);
        TextView sub = text("Hold this phone on the POS reader to test its Tap2Pay push", 13, MUTED);
        sub.setPadding(0, dp(6), 0, 0);
        bar.addView(sub);

        LinearLayout wrap = column();
        wrap.addView(bar);
        View border = new View(this);
        border.setBackgroundColor(LINE);
        wrap.addView(border, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    private View statusCard() {
        LinearLayout box = column();
        nfcStatus = text("", 14, INK);
        nfcStatus.setTypeface(DISPLAY);
        box.addView(nfcStatus);

        TextView nextLabel = text("NEXT TAP USES", 11, MUTED);
        nextLabel.setTypeface(Typeface.DEFAULT_BOLD);
        nextLabel.setLetterSpacing(0.08f);
        nextLabel.setPadding(0, dp(12), 0, 0);
        box.addView(nextLabel);
        nextCase = text("", 20, ACCENT_INK);
        nextCase.setTypeface(DISPLAY);
        box.addView(nextCase);
        TextView hint = text("Tap a case below to change it.", 12, MUTED);
        box.addView(hint);

        summary = text("", 13, INK);
        summary.setTypeface(DISPLAY);
        summary.setPadding(dp(10), dp(8), dp(10), dp(8));
        summary.setBackground(rounded(SURFACE2, SURFACE2, 1, 10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        box.addView(summary, sp);

        deviceStatus = text("", 12, MUTED);
        deviceStatus.setPadding(0, dp(10), 0, 0);
        box.addView(deviceStatus);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(6), 0, 0);
        Button clear = new Button(this, null, android.R.attr.borderlessButtonStyle);
        clear.setText("Clear results");
        clear.setAllCaps(false);
        clear.setTextColor(BAD);
        clear.setOnClickListener(v -> {
            if (dialog != null) dialog.dismiss();
            dialog = new AlertDialog.Builder(this)
                    .setMessage("Clear the results of every case? The log file keeps them.")
                    .setPositiveButton("Clear", (d, w) -> store.clear())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        Button copy = new Button(this, null, android.R.attr.borderlessButtonStyle);
        copy.setText("Copy all taps");
        copy.setAllCaps(false);
        copy.setTextColor(ACCENT);
        copy.setOnClickListener(v -> copyAll());
        buttons.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(buttons);
        return card(box);
    }

    private void showStatus() {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        boolean ready = adapter != null && adapter.isEnabled();
        String nfc = adapter == null ? "●  No NFC hardware"
                : adapter.isEnabled() ? "●  NFC on" : "●  NFC is OFF: turn it on in Settings";
        if (cardEmulation != null && adapter != null && adapter.isEnabled()) {
            boolean routed = cardEmulation.isDefaultServiceForAid(service, AID);
            ready &= routed;
            nfc += routed ? ", Tap2Pay taps come to this app" : ", Tap2Pay taps not routed here yet";
        }
        nfcStatus.setText(nfc);
        nfcStatus.setTextColor(ready ? GOOD : BAD);

        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("  ·  Android ").append(Build.VERSION.RELEASE);
        if (installed(NBP_APP)) sb.append("\nNBP app is also installed: keep this screen open while tapping.");
        deviceStatus.setText(sb.toString());
    }

    private static int badgeFill(CaseResult.Status s) {
        switch (s) {
            case PASS: return GOOD_FILL;
            case FAIL: return BAD_FILL;
            case CHECK_POS: return WARN_FILL;
            case IN_PROGRESS: return ACCENT_FILL;
            case NA: return SURFACE2;
            default: return Color.WHITE;
        }
    }

    private static int badgeStroke(CaseResult.Status s) {
        return s == CaseResult.Status.NOT_RUN ? LINE : badgeFill(s);
    }

    private static int badgeInk(CaseResult.Status s) {
        switch (s) {
            case PASS: return GOOD;
            case FAIL: return BAD;
            case CHECK_POS: return WARN;
            case IN_PROGRESS: return ACCENT_INK;
            default: return MUTED;
        }
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void copyAll() {
        StringBuilder sb = new StringBuilder();
        for (TapRecord t : store.taps()) {
            sb.append(TestStore.stamp(t.startedWallMs)).append('\n').append(t.describe(true)).append('\n');
        }
        copy("Tap2Pay taps", sb.toString(), store.taps().size() + " taps copied");
    }

    private void copy(String label, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- view helpers

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setPadding(0, dp(6), 0, dp(12));
        s.setClipToPadding(false);
        s.addView(content);
        return s;
    }

    private View card(View content) {
        LinearLayout box = column();
        box.setBackground(rounded(Color.WHITE, LINE, 1, 16));
        box.setPadding(dp(16), dp(14), dp(16), dp(8));
        box.addView(content);
        return withMargins(box);
    }

    private View withMargins(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(6), dp(12), dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    /** A small caps label above its text, e.g. "PHONE" then what the phone does. */
    private View labelled(String label, String value, int valueColour) {
        LinearLayout l = column();
        l.setPadding(0, dp(8), 0, 0);
        TextView k = text(label, 11, MUTED);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setLetterSpacing(0.08f);
        l.addView(k);
        TextView v = text(value, 13, valueColour);
        v.setLineSpacing(0, 1.1f);
        l.addView(v);
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 12, BRAND);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(16), dp(16), dp(16), dp(4));
        return t;
    }

    private TextView footnote(String s) {
        TextView t = text(s, 11, MUTED);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        return t;
    }

    private TextView text(String s, int sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
