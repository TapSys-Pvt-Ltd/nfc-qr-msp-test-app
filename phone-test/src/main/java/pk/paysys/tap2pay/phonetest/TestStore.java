package pk.paysys.tap2pay.phonetest;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import pk.paysys.tap2pay.core.PhoneCase;
import pk.paysys.tap2pay.core.PhoneResponder;
import pk.paysys.tap2pay.core.TapRecord;

/**
 * Process-wide state shared by the HCE service and the screen: the chosen case (kept in preferences, so it
 * survives a process kill), one {@link PhoneResponder} (so tap numbers and "same QR" carry across service
 * instances) and the taps. Main thread only, except the file writes, which run on their own thread.
 */
final class TestStore {
    interface Listener {
        void onTapsChanged();
    }

    static final String TAG = "Tap2PayPhoneTest";
    private static final String PREFS = "phone_test";
    private static final String KEY_CASE = "case";
    private static final int MAX_TAPS = 100;

    private static TestStore instance;

    private final SharedPreferences prefs;
    private final File logFile;
    private final ExecutorService fileWriter = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<TapRecord> taps = new ArrayList<>();
    private Listener listener;
    final PhoneResponder responder;

    static TestStore get(Context context) {
        if (instance == null) instance = new TestStore(context.getApplicationContext());
        return instance;
    }

    private TestStore(Context app) {
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File dir = app.getExternalFilesDir(null);
        logFile = dir != null ? new File(dir, "taps.txt") : new File(app.getFilesDir(), "taps.txt");
        responder = new PhoneResponder(new PhoneResponder.Clock() {
            @Override
            public long monotonicMs() {
                return SystemClock.elapsedRealtime();
            }

            @Override
            public long wallMs() {
                return System.currentTimeMillis();
            }
        }, this::currentCase, new PhoneResponder.Listener() {
            @Override
            public void onTapChanged(TapRecord tap) {
                if (!taps.contains(tap)) {
                    taps.add(0, tap);
                    while (taps.size() > MAX_TAPS) taps.remove(taps.size() - 1);
                }
                notifyListener();
            }

            @Override
            public void onTapClosed(TapRecord tap) {
                onTapChanged(tap);
                save(tap);
            }
        });
        append("---- app started " + stamp(System.currentTimeMillis()) + " ----\n");
    }

    PhoneCase currentCase() {
        return PhoneCase.parse(prefs.getString(KEY_CASE, null));
    }

    void setCase(PhoneCase c) {
        prefs.edit().putString(KEY_CASE, c.name()).apply();
        notifyListener();
    }

    List<TapRecord> taps() {
        return taps;
    }

    void clear() {
        TapRecord open = responder.openTap();
        taps.clear();
        if (open != null) taps.add(open);
        notifyListener();
    }

    String logPath() {
        return logFile.getAbsolutePath();
    }

    void setListener(Listener l) {
        listener = l;
    }

    private void notifyListener() {
        final Listener l = listener;
        if (l != null) main.post(l::onTapsChanged);
    }

    private void save(TapRecord tap) {
        String text = stamp(tap.startedWallMs) + "\n" + tap.describe(true) + "\n";
        for (String line : text.split("\n")) Log.i(TAG, line);
        append(text);
    }

    private void append(final String text) {
        fileWriter.execute(() -> {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (IOException e) {
                Log.w(TAG, "could not write " + logFile + ": " + e.getMessage());
            }
        });
    }

    static String stamp(long wallMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(wallMs));
    }
}
