package com.github.xfalcon.vhosts.util;

import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class NetworkHelper {

    static String TAG = NetworkHelper.class.getSimpleName();

    public static class IpAddress{
        public Inet4Address ipv4;
        public Inet6Address ipv6;

        public boolean ok = false;

        boolean check(){
            return (ipv4 != null && ipv6 != null);
        }
    }

    public static IpAddress getHotspotIP(){

        IpAddress ip = new IpAddress();
        InetAddress inetAddress;
        try{
            for (Enumeration<NetworkInterface> networkInterface = NetworkInterface.getNetworkInterfaces(); networkInterface.hasMoreElements(); ) {
                NetworkInterface singleInterface = networkInterface.nextElement();
                for (Enumeration<InetAddress> IpAddresses = singleInterface.getInetAddresses(); IpAddresses.hasMoreElements(); ) {
                    inetAddress = IpAddresses.nextElement();
                    if (inetAddress.isLoopbackAddress()){
                        continue;
                    }
                    if (singleInterface.getDisplayName().contains("wlan0") ){
                        continue;
                    }
                    if (singleInterface.getDisplayName().contains("wlan") ||
                            singleInterface.getDisplayName().contains("eth0")||
                            singleInterface.getDisplayName().contains("ap0")){
                        if(inetAddress.getAddress().length == 4){
                            ip.ipv4 = (Inet4Address)inetAddress;
                            Log.d(TAG, ip.ipv4.getHostAddress() + ":v4: " + singleInterface.getDisplayName());
                        }else {
                            ip.ipv6 = (Inet6Address)inetAddress;
                            Log.d(TAG, ip.ipv6.getHostAddress() + ":v6: " + singleInterface.getDisplayName());
                        }

                        ip.ok = ip.check();
                        if (ip.ok){
                            break;
                        }
                    }
                }
            }

        }catch (SocketException e){
            Log.d(TAG, "network error!", e);
        }
        return ip;
    }
}
