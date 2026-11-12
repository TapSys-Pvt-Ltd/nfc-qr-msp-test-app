package pk.paysys.tap2pay.postag;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-process log shared by the HCE service and the screen; also mirrored to logcat (tag Tap2PayTag). */
final class EventLog {
    interface Listener {
        void onChanged();
    }

    static final String TAG = "Tap2PayTag";
    private static final int MAX_LINES = 300;
    private static final List<String> LINES = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;

    private EventLog() {}

    static synchronized void add(String line) {
        Log.i(TAG, line);
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        LINES.add(0, ts + "  " + line);
        if (LINES.size() > MAX_LINES) LINES.remove(LINES.size() - 1);
        notifyListener();
    }

    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    static synchronized void clear() {
        LINES.clear();
        notifyListener();
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    private static void notifyListener() {
        final Listener l = listener;
        if (l != null) MAIN.post(l::onChanged);
    }
}
