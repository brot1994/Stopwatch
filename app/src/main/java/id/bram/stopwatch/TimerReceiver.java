package id.bram.stopwatch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

public class TimerReceiver extends BroadcastReceiver {

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 77, new Intent(c, TimerReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void schedule(Context c, long inMs) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = SystemClock.elapsedRealtime() + Math.max(0, inMs);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi(c));
        }
    }

    public static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(pi(c));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        Store st = Store.get(c);
        Store.Tm t = st.timer;
        if (t.state != Store.Tm.RUNNING) return;
        long r = t.remaining();
        if (r > 1500) { schedule(c, r); return; }
        finish(c);
    }

    static void finish(Context c) {
        Store st = Store.get(c);
        st.timer.state = Store.Tm.DONE;
        st.timer.remain = 0;
        Notif.alarm(c);
        st.changed();
    }
}
