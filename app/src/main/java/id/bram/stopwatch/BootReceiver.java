package id.bram.stopwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Store st = Store.get(c);
        if (st.timer.state == Store.Tm.RUNNING) {
            long r = st.timer.remaining();
            if (r <= 0) { TimerReceiver.finish(c); return; }
            TimerReceiver.schedule(c, r);
        }
        Notif.refresh(c);
    }
}
