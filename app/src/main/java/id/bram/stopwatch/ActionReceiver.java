package id.bram.stopwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ActionReceiver extends BroadcastReceiver {
    public static final String PAUSE = "id.bram.stopwatch.PAUSE";
    public static final String RESUME = "id.bram.stopwatch.RESUME";
    public static final String LAP = "id.bram.stopwatch.LAP";
    public static final String RESET = "id.bram.stopwatch.RESET";
    public static final String T_PAUSE = "id.bram.stopwatch.T_PAUSE";
    public static final String T_RESUME = "id.bram.stopwatch.T_RESUME";
    public static final String T_ADD = "id.bram.stopwatch.T_ADD";
    public static final String T_CANCEL = "id.bram.stopwatch.T_CANCEL";
    public static final String T_STOP = "id.bram.stopwatch.T_STOP";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        Store st = Store.get(c);
        Store.Sw s = st.byId(i.getLongExtra("id", -1));
        switch (a) {
            case PAUSE: if (s != null) st.pause(s); break;
            case RESUME: if (s != null) st.start(s); break;
            case LAP: if (s != null) st.lap(s); break;
            case RESET: if (s != null) st.reset(s); break;
            case T_PAUSE: st.timerPause(); break;
            case T_RESUME: st.timerStart(); break;
            case T_ADD: st.timerAdd(60000); break;
            case T_CANCEL: st.timerCancel(); break;
            case T_STOP:
                Notif.stopAlarm(c);
                if (st.timer.state == Store.Tm.DONE) {
                    st.timer.state = Store.Tm.IDLE;
                    st.timer.remain = st.timer.duration;
                }
                break;
        }
        st.changed();
    }
}
