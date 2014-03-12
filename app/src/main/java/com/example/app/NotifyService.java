package com.example.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.example.app.util.LogUtil;
import com.example.app.util.NetworkUtil;
import com.example.app.util.NotificationUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;

public class NotifyService extends Service {
    private static final String TAG = NotifyService.class.getSimpleName();
    private final static int RESCHEDULE = 0;
    private final static int NO_ACTION = 1;
    private final static int NO_SCHEDULE = 2;
    private final static int PENDINGINTENT_ID_RECONNECT = 0;
    private final static int PENDINGINTENT_ID_PING = 1;
    private final static int PENDINGINTENT_ID_PONG = 2;

    private final static String keepalive_server = "192.168.0.65";
    private final static int keepalive_server_port_wifi = 2222;// 443;
    private final static int keepalive_server_port_mobile = 8124;// 5228;
    private final static String ACTION_START_KEEPALIVE = "action_start_keepalive";
    private final static String ACTION_ALARM_PING = "action_alarm_ping";
    private final static String ACTION_CHECK_PONG = "action_check_pong";
    private final static String INTENT_DEVICE_NAME = "intent_device_name";

    // pong受信時にtoast表示
    private final static boolean DEBUG_PONG = true;
    private final static long CHECK_PONG_TIMEOUT = 30 * 1000;
    private final static long CHECK_PONG_LIMIT = 60 * 1000;
    public final static long DEF_PING_INTERVAL = 1000 * 60;

    // private String deviceID;
    private Socket conn;
    private InputStream is;
    private OutputStream os;
    private BufferedReader reader;
    private BufferedWriter writer;
    private boolean disconning;
    private ConnectivityBroadcastReceiver mReceiver = null;
    PowerManager.WakeLock wl = null;
    private String mDeviceName;
    private Handler mHandler = new Handler();

    @Override
    public IBinder onBind(Intent arg0) {
        LogUtil.d(TAG, "onBind");
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.d(TAG, "onCreate");
        // create wakelock once
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wl.setReferenceCounted(false);
        sendMessage("service", "create");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int result = super.onStartCommand(intent, flags, startId);
        LogUtil.d(TAG, "onStart " + intent + ", " + startId);
        // boradcast receiver init
        receiverInit();
        disconning = false;
        boolean isConnected = false;
        if (intent == null) {
            return result;
        }

        String action = intent.getAction();
        if (intent.hasExtra(INTENT_DEVICE_NAME) && intent.getStringExtra(INTENT_DEVICE_NAME) != null
                && intent.getStringExtra(INTENT_DEVICE_NAME).length() > 0)
            mDeviceName = intent.getStringExtra(INTENT_DEVICE_NAME);
        // ping用alarmの時の処理
        if (ACTION_ALARM_PING.equals(action)) {
            LogUtil.d(TAG, "action:alarm ping");
            // connection維持とは別Threadでwriterに書き込む, 安全か？
            if (conn != null && conn.isConnected() && writer != null) {
                isConnected = true;
                LogUtil.d(TAG, "connected!! let ping...");
                wl.acquire(6 * 1000); // WAKELOCKTIMEOUT
                try {
                    writer.write("ping");
                    writer.flush();
                    sendMessage("connection", "write ping.");
                } catch (IOException e) {
                    LogUtil.e(TAG, "ping error occur");
                    e.printStackTrace();
                    sendMessage("connection", "error ping writing..");
                }
                // pingAlarmを新しくする, pongAlarmを設定する
                cancelPingAlarm();
                alarmForCheckPong();
                alarmForPing();
                wl.release();
            } else {
                LogUtil.e(TAG, "not connected..");
            }
        }
        // pongがきてるかチェック
        else if (ACTION_CHECK_PONG.equals(action)) {
            // pongを受け取ってなかったら再接続
            // 最終pong受信日が現在時間より1分以上前なら再接続
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
            if (SystemClock.elapsedRealtime()
                    - settings.getLong(PreferenceKeyValue.LAST_PONG_RECEIVED_TIME, 0) > CHECK_PONG_LIMIT) {
                scheduleNext();
            }
        }

        // device名が空なら何もしない
        if (mDeviceName == null || mDeviceName.length() == 0) {
            LogUtil.e(TAG, "device name is empty!!");
            // stopSelf();
            return result;
        }
        if (!NetworkUtil.checkNetwork(getApplicationContext()))
            return result;
        // keep foreground
        foregroundKeeper("start wait");
        if (isConnected)
            return result;

        Runnable runnable = new Runnable() {
            public void run() {
                // 接続済みなら接続しない
                if (conn != null && conn.isConnected()) {
                    LogUtil.d(TAG, "Already connected..");
                    return;
                } else if (conn != null && !conn.isClosed()) {
                    try {
                        conn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                // ipv4アドレスを取得
                String server_ipv4 = NetworkUtil.getIpv4Address(keepalive_server);
                // TODO v6でつながっているときはサーバの対応が必要
                if (server_ipv4 == null) {
                    scheduleNext();
                }

                // TODO 実際にはNATを回避するため、3G or Wifiでportを変えるといい
                int port = keepalive_server_port_wifi;
                int ret = startThread(server_ipv4, port, mDeviceName);
                if (!NetworkUtil.checkNetwork(getApplicationContext())) {
                    sendMessage("service", "Network is unreachable..");
                    stopSelf();
                } else if (ret == RESCHEDULE) {
                    scheduleNext();
                    LogUtil.d(TAG, "service stoppped!");
                } else if (ret == NO_SCHEDULE) {
                    // stopSelf();
                    LogUtil.d(TAG, "service finished!");
                }
            }
        };
        (new Thread(runnable)).start();
        return result;
    }

    @Override
    public void onDestroy() {
        LogUtil.d(TAG, "onDestroy");
        receiverDestroy();
        stopThread();
        disconning = true;
        sendMessage("service", "destory");
        cancelPingAlarm();
    }

    /**
     * 接続維持スレッド用, 排他制御する
     */
    private synchronized int startThread(String server, int port, String name) {
        LogUtil.d(TAG, "startThreadSimple " + server + ":" + port);
        // 接続済みなら何もしない
        if (conn != null && conn.isConnected()) {
            LogUtil.d(TAG, "Already connected..");
            return NO_ACTION;
        }
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            LogUtil.d(TAG, "start connection, name:" + name);
            sendMessage("service", "start connection, name:" + name);
            conn = new Socket(server, port);
            is = conn.getInputStream();
            os = conn.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(is));
            writer = new BufferedWriter(new OutputStreamWriter(os));
            String str = "";
            LogUtil.d(TAG, "loop start");
            while ((str = reader.readLine()) != null) {
                LogUtil.d(TAG, "while:" + str);
                wl.acquire(6 * 1000); // WAKELOCKTIMEOUT
                if (str.startsWith("exit")) {
                    LogUtil.d(TAG, "exit");
                    wl.release();
                    sendMessage("connection", "exit");
                    break;
                }
                // pong received
                else if (str.startsWith("pong")) {
                    LogUtil.d(TAG, "pong");
                    // debug用のpong受信Toast
                    if (mHandler != null && DEBUG_PONG) {
                        mHandler.post(new Runnable() {
                            public void run() {
                                Toast.makeText(getApplicationContext(), "pong", Toast.LENGTH_SHORT)
                                        .show();
                            }
                        });
                    }
                    sendMessage("connection", "pong");
                    // pong受信時間を記録
                    settings.edit()
                            .putLong(PreferenceKeyValue.LAST_PONG_RECEIVED_TIME,
                                    SystemClock.elapsedRealtime()).commit();
                }
                // ping received
                else if (str.startsWith("ping")) {
                    LogUtil.d(TAG, "ping");
                    writer.write("pong");
                    writer.flush();
                    sendMessage("connection", "ping, reply pong");
                }
                // regist in nodejs memory
                else if (str.startsWith("welcome to ")) {
                    LogUtil.d(TAG, "connected");
                    writer.write("regist " + name);
                    writer.flush();
                    sendMessage("connection", "regist " + name);
                    // Ping用Alarmを設定
                    alarmForPing();
                }
                // receive something
                else if (str.length() > 0) {
                    NotificationUtil.makeNotify(this, str, true);
                    sendMessage("connection", "receive notify, " + str);
                }
                wl.release();
            }
            // close
            conn.close();
            LogUtil.d(TAG, "close");
            sendMessage("connection", "close");
        } catch (Exception e) {
            e.printStackTrace();
            LogUtil.d(TAG, "error:" + e.toString());
            if (conn != null && conn.isConnected()) {
                try {
                    conn.close();
                    sendMessage("connection", "close with error");
                } catch (Exception exp) {
                    e.printStackTrace();
                    LogUtil.d(TAG, "error:" + exp.toString());
                    sendMessage("connection", "error in closing");
                }
            }
        } finally {
            conn = null;
        }
        cancelPingAlarm();
        if (disconning) {
            return NO_SCHEDULE;
        }
        sendMessage("service", "reschedule");
        return RESCHEDULE;
    }

    /**
     * forground の通知を作る
     */
    private void foregroundKeeper(CharSequence text) {
        LogUtil.d(TAG, "foreground");
        startForeground(NotificationUtil.NOTIFICATION_ID, NotificationUtil.makeNotify(this, text, false));
    }


    /**
     * 次の接続用alarmを設定
     */
    private void alarmNext() {
        LogUtil.d(TAG, "alarmNext called");
        Intent intent = new Intent(NotifyService.this, NotifyService.class);
        intent.setAction(ACTION_START_KEEPALIVE);
        intent.putExtra(INTENT_DEVICE_NAME, mDeviceName);
        long now = System.currentTimeMillis();
        PendingIntent alarmSender = PendingIntent.getService(NotifyService.this,
                PENDINGINTENT_ID_RECONNECT, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC, now + 10 * 1000, alarmSender);
    }

    private PendingIntent makePingPendingIntent() {
        Intent intent = new Intent(NotifyService.this, NotifyService.class);
        intent.setAction(ACTION_ALARM_PING);
        intent.putExtra(INTENT_DEVICE_NAME, mDeviceName);
        PendingIntent alarmSender = PendingIntent.getService(NotifyService.this,
                PENDINGINTENT_ID_PING, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        return alarmSender;
    }

    /**
     * 次のping用alarmを設定
     */
    private void alarmForPing() {
        LogUtil.d(TAG, "alarmForPing called");
        long now = SystemClock.elapsedRealtime();
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + DEF_PING_INTERVAL, makePingPendingIntent());
    }

    /**
     * 次のping用alarmを設定
     */
    private void alarmForCheckPong() {
        LogUtil.d(TAG, "alarmForCheckPong called");
        long now = SystemClock.elapsedRealtime();
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(NotifyService.this, NotifyService.class);
        intent.setAction(ACTION_CHECK_PONG);
        intent.putExtra(INTENT_DEVICE_NAME, mDeviceName);
        PendingIntent alarmSender = PendingIntent.getService(NotifyService.this,
                PENDINGINTENT_ID_PONG, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + CHECK_PONG_TIMEOUT, alarmSender);
    }

    /**
     * ping用Alarmを解除
     */
    private void cancelPingAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.cancel(makePingPendingIntent());
    }

    /**
     * 次の接続用alarmを設定して終了
     */
    private void scheduleNext() {
        alarmNext();
        stopSelf();
    }

    /**
     * stop thread when connectivity changed, or process died
     */
    private int stopThread() {
        LogUtil.d(TAG, "stopThread");
        int ret = 0;
        try {
            conn.close();
            conn = null;
            ret = 1;
        } catch (Exception e) {
            e.printStackTrace();
            LogUtil.d(TAG, "stopThread:" + e.toString());
        }
        return ret;
    }

    /**
     * send message to activity.
     */
    private void sendMessage(String event, String str) {
        LogUtil.d(TAG, "NOTIFY=>" + str);
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(PreferenceKeyValue.SHARED_TEXT, str);
        editor.commit();
    }

    /**
     * receiver register
     */
    private void receiverInit() {
        if (mReceiver == null) {
            mReceiver = new ConnectivityBroadcastReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction("MY_ACTION");
            getBaseContext().registerReceiver(mReceiver, filter);
        }
    }

    /**
     * receiver unregister
     */
    private void receiverDestroy() {
        if (mReceiver != null) {
            getBaseContext().unregisterReceiver(mReceiver);
        }
    }

    /**
     * connectivity check
     */
    private class ConnectivityBroadcastReceiver extends BroadcastReceiver {
        private String conn_type = null;
        private String curr_conn_type = null;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            LogUtil.d(TAG, "action=" + action);
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni != null) {
                conn_type = ni.getTypeName();
                // conn_typeが変化した
                if (curr_conn_type != null && !curr_conn_type.equals(conn_type)) {
                    LogUtil.d(TAG, "Conntype Change! from " + curr_conn_type + " to " + conn_type);
                    stopThread();
                }
                curr_conn_type = conn_type;
                if (ni.isConnectedOrConnecting()) {
                    LogUtil.d(TAG, "network :connected  " + mDeviceName);
                    alarmNext();
                } else {
                    LogUtil.d(TAG, "network :disconnected");
                }
            }
        }
    }

    ;

    /**
     * connectionスタート
     */
    public static void startService(Context context, String name) {
        LogUtil.d(TAG, "startService:" + name);
        Intent intent = new Intent(context, NotifyService.class);
        intent.setAction(ACTION_START_KEEPALIVE);
        intent.putExtra(INTENT_DEVICE_NAME, name);
        context.startService(intent);
    }

    /**
     * 手動ping用
     */
    public static void manualPing(Context context, String name) {
        LogUtil.d(TAG, "manualPing:" + name);
        Intent intent = new Intent(context, NotifyService.class);
        intent.setAction(ACTION_ALARM_PING);
        intent.putExtra(INTENT_DEVICE_NAME, name);
        context.startService(intent);
    }

}
