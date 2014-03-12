package com.example.app.util;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.v4.app.NotificationCompat;

import com.example.app.MainActivity;
import com.example.app.R;

/**
 * Created by akihiroaida on 2014/03/11.
 */
public class NotificationUtil {
    public final static int NOTIFICATION_ID = 1;

    /**
     * notification作成、表示
     */
    public static Notification makeNotify(Context con, CharSequence text, boolean isTicker) {
        Intent notifyIntent = new Intent(con, MainActivity.class);
        PendingIntent pendIntent = PendingIntent.getActivity(con, 0, notifyIntent, 0);
        NotificationManager notificationManager = (NotificationManager) con.getSystemService(Context.NOTIFICATION_SERVICE);
        String title = con.getString(R.string.app_name);

        boolean nonDisplay = false;
        Notification notification;
        if (nonDisplay && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            notification = new Notification(0, text, System.currentTimeMillis());
            notification.setLatestEventInfo(con, title, text,
                    pendIntent);
        } else {
            NotificationCompat.Builder nb = new NotificationCompat.Builder(con);
            nb.setContentTitle(title);
            nb.setContentText(text);
            nb.setAutoCancel(true);
            // boolean isConnected = false;
            int sIcon, lIcon;
            sIcon = R.drawable.ic_notify;
            lIcon = R.drawable.ic_launcher;
            nb.setSmallIcon(sIcon);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                nb.setLargeIcon(BitmapFactory.decodeResource(con.getResources(), lIcon));
            }
            if (nonDisplay)
                nb.setPriority(Notification.PRIORITY_MIN);
            if (isTicker)
                nb.setTicker(text);
            nb.setContentIntent(pendIntent);
            notification = nb.getNotification();
        }
        // startForeground(NOTIFICATION_ID, notification);
        notificationManager.notify(NOTIFICATION_ID, notification);
        return notification;
    }
}
