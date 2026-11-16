package id.bram.stopwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.service.notification.StatusBarNotification;

import java.util.HashSet;

public class Notif {
    static final String CH_RUN = "berjalan";
    static final String CH_ALARM = "alarm_timer";
    static final int ID_TIMER = 1;
    static final int ID_ALARM = 2;
    static final int SW_BASE = 1000;

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CH_RUN) == null) {
            NotificationChannel ch = new NotificationChannel(CH_RUN, "Stopwatch & timer berjalan", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            NotificationChannel ch = new NotificationChannel(CH_ALARM, "Alarm timer selesai", NotificationManager.IMPORTANCE_HIGH);
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            ch.setSound(u, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
            ch.setBypassDnd(true);
            nm.createNotificationChannel(ch);
        }
    }

    static int swNotifId(Store.Sw s) { return SW_BASE + (int) (Math.abs(s.id) % 100000); }

    static PendingIntent action(Context c, String act, long id, int req) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(act).putExtra("id", id);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent open(Context c, int page, long swId, int req) {
        Intent i = new Intent(c, MainActivity.class)
                .putExtra("page", page).putExtra("sw", swId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Tampilkan / hapus notifikasi sesuai keadaan stopwatch & timer. */
    public static void refresh(Context c) {
        try {
            channels(c);
            Store st = Store.get(c);
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            HashSet<Integer> keep = new HashSet<>();

            for (Store.Sw s : st.sws) {
                long el = s.elapsed();
                int nid = swNotifId(s);
                if (el <= 0 && !s.running) continue;
                keep.add(nid);
                int r = nid * 10;
                Notification.Builder b = new Notification.Builder(c, CH_RUN)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(s.name)
                        .setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_STOPWATCH)
                        .setContentIntent(open(c, 0, s.id, r))
                        .setGroup("sw");
                String lapTxt = s.laps.isEmpty() ? "" : "  •  Lap " + s.laps.size();
                if (s.running) {
                    b.setOngoing(true)
                            .setUsesChronometer(true)
                            .setShowWhen(true)
                            .setWhen(System.currentTimeMillis() - el)
                            .setContentText("Berjalan" + lapTxt)
                            .addAction(new Notification.Action.Builder(null, "Jeda", action(c, ActionReceiver.PAUSE, s.id, r + 1)).build())
                            .addAction(new Notification.Action.Builder(null, "Lap", action(c, ActionReceiver.LAP, s.id, r + 2)).build());
                } else {
                    b.setOngoing(false)
                            .setShowWhen(false)
                            .setContentText("Dijeda  " + Store.fmt(el) + lapTxt)
                            .addAction(new Notification.Action.Builder(null, "Lanjut", action(c, ActionReceiver.RESUME, s.id, r + 3)).build())
                            .addAction(new Notification.Action.Builder(null, "Reset", action(c, ActionReceiver.RESET, s.id, r + 4)).build());
                }
                nm.notify(nid, b.build());
            }

            // Timer
            Store.Tm t = st.timer;
            if (t.state == Store.Tm.RUNNING || t.state == Store.Tm.PAUSED) {
                Notification.Builder b = new Notification.Builder(c, CH_RUN)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("Timer " + Store.fmtShort(t.duration))
                        .setOnlyAlertOnce(true)
                        .setOngoing(t.state == Store.Tm.RUNNING)
                        .setContentIntent(open(c, 1, 0, 50));
                if (t.state == Store.Tm.RUNNING) {
                    b.setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
                            .setWhen(System.currentTimeMillis() + t.remaining())
                            .setContentText("Hitung mundur berjalan")
                            .addAction(new Notification.Action.Builder(null, "Jeda", action(c, ActionReceiver.T_PAUSE, 0, 51)).build())
                            .addAction(new Notification.Action.Builder(null, "+1 menit", action(c, ActionReceiver.T_ADD, 0, 52)).build())
                            .addAction(new Notification.Action.Builder(null, "Batal", action(c, ActionReceiver.T_CANCEL, 0, 53)).build());
                } else {
                    b.setShowWhen(false).setContentText("Dijeda  sisa " + Store.fmtDown(t.remaining()))
                            .addAction(new Notification.Action.Builder(null, "Lanjut", action(c, ActionReceiver.T_RESUME, 0, 54)).build())
                            .addAction(new Notification.Action.Builder(null, "Batal", action(c, ActionReceiver.T_CANCEL, 0, 53)).build());
                }
                nm.notify(ID_TIMER, b.build());
            } else {
                nm.cancel(ID_TIMER);
            }

            // Hapus notifikasi stopwatch yang sudah tidak berlaku
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                int id = sbn.getId();
                if (id >= SW_BASE && !keep.contains(id)) nm.cancel(id);
            }
        } catch (Exception ignored) {}
    }

    /** Notifikasi alarm yang berbunyi terus sampai dihentikan. */
    public static void alarm(Context c) {
        channels(c);
        Store st = Store.get(c);
        Notification.Builder b = new Notification.Builder(c, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Waktu habis!")
                .setContentText("Timer " + Store.fmtShort(st.timer.duration) + " selesai")
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(open(c, 1, 0, 60))
                .setDeleteIntent(action(c, ActionReceiver.T_STOP, 0, 61))
                .addAction(new Notification.Action.Builder(null, "Stop", action(c, ActionReceiver.T_STOP, 0, 62)).build());
        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        c.getSystemService(NotificationManager.class).notify(ID_ALARM, n);
    }

    public static void stopAlarm(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID_ALARM);
    }
}
