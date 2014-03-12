package com.example.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.apache.http.conn.util.InetAddressUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Created by akihiroaida
 */
public class NetworkUtil {
    private static final String TAG = NetworkUtil.class.getSimpleName();

    /**
     * network状態をチェック
     *
     * @return
     */
    public static boolean checkNetwork(Context con) {
        ConnectivityManager mConnMgr = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = mConnMgr.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnectedOrConnecting()) {
            return true;
        }
        return false;
    }

    /**
     * @param con
     * @return
     */
    public static int getNetworkType(Context con) {
        ConnectivityManager mConnMgr = (ConnectivityManager) con
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = mConnMgr.getActiveNetworkInfo();
        if (networkInfo != null) {
            return networkInfo.getType();
        }
        return ConnectivityManager.TYPE_DUMMY;
    }

    /**
     * check connect with ip v4
     */
    public static String getIpv4Address(String keepalive_server) {
        String server_ipv4 = null;
        try {
            InetAddress[] addrs = InetAddress.getAllByName(keepalive_server);
            for (InetAddress addr : addrs) {
                String ip = addr.getHostAddress();
                LogUtil.d(TAG, "addr:" + ip + " " + InetAddressUtils.isIPv4Address(ip));
                if (InetAddressUtils.isIPv4Address(ip)) {
                    server_ipv4 = ip;
                    break;
                }
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return server_ipv4;
    }
}
