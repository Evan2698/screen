/*
 ** Copyright 2015, Mohamed Naufal
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.github.xfalcon.vhosts.vservice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;


import com.example.screen.R;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;


public class VhostsService extends VpnService {
    private static final String TAG = VhostsService.class.getSimpleName();
    public static final String VPN_ADDRESS = "9.9.9.9";
    private static final String VPN_ADDRESS6 = "2002:0000:0000:0000:0000:0000:0909:0909";
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private static final String VPN_ROUTE6 = "::"; // Intercept everything
    private static final String VPN_DNS4 = "114.114.114.114";
    private static final String VPN_DNS6 = "2002:0000:0000:0000:0000:0000:7272:7272";

    public static final String ACTION_START = "com.github.xfalcon.vhosts.vservice.VhostsService.START";
    public static final String ACTION_STOP = "com.github.xfalcon.vhosts.vservice.VhostsService.STOP";
    public static final String ACTION_REQUEST_STATE = "com.github.xfalcon.vhosts.vservice.VhostsService.REQUEST_STATE";
    public static final String ACTION_STATE_CHANGED = "com.github.xfalcon.vhosts.vservice.VhostsService.STATE_CHANGED";
    public static final String EXTRA_IS_RUNNING = "EXTRA_IS_RUNNING";

    private static final String NOTIFICATION_CHANNEL_ID = "vhosts_channel_id";

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private ParcelFileDescriptor vpnInterface = null;
    private PendingIntent pendingIntent;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private ExecutorService executorService;
    private Selector udpSelector;
    private Selector tcpSelector;
    private ReentrantLock udpSelectorLock;
    private ReentrantLock tcpSelectorLock;


    @Override
    public void onCreate() {
        super.onCreate();
    }

    private void startVService() {
        if (isRunning.get()) {
            return;
        }
        startForeground(1, createNotification());

        if (setupVPN()) {
            isRunning.set(true);
            try {
                startVpnThreads();
                broadcastState();
                LogUtils.i(TAG, "Started");
            } catch (IOException e) {
                LogUtils.e(TAG, "Error starting service", e);
                stopVService();
            }
        } else {
            LogUtils.d(TAG, "Failed to setup VPN");
            stopVService();
        }
    }


    private boolean setupVPN() {
        if (vpnInterface != null) {
            return true;
        }
        Builder builder = new Builder();
        builder.addAddress(VPN_ADDRESS, 32);
        builder.addAddress(VPN_ADDRESS6, 128);
        builder.addRoute(VPN_ROUTE, 0);
        builder.addRoute(VPN_ROUTE6, 0);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network[] networks = cm.getAllNetworks();
        if (networks.length > 0) {
            try {
                builder.setUnderlyingNetworks(networks);
            } catch (Exception e) {
                Log.e("MyVPN", "Permission denied to set underlying networks.", e);
            }
        }

        builder.addDnsServer(VPN_DNS4);
        builder.addDnsServer(VPN_DNS6);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] vpnList = {"com.msmsdk.test"};
            for (String white : vpnList) {
                try {
                    builder.addAllowedApplication(white);
                } catch (PackageManager.NameNotFoundException e) {
                    LogUtils.e(TAG, e.getMessage(), e);
                }
            }
        }
        vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(pendingIntent).establish();
        return vpnInterface != null;
    }

    private void startVpnThreads() throws IOException {
        udpSelector = Selector.open();
        tcpSelector = Selector.open();
        udpSelectorLock = new ReentrantLock();
        tcpSelectorLock = new ReentrantLock();

        deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
        deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
        networkToDeviceQueue = new ConcurrentLinkedQueue<>();

        executorService = Executors.newFixedThreadPool(5);
        executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector, udpSelectorLock));
        executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, networkToDeviceQueue, udpSelector, udpSelectorLock, this));
        executorService.submit(new TCPInput(networkToDeviceQueue, tcpSelector, tcpSelectorLock));
        executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, tcpSelector, tcpSelectorLock, this));
        executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            LogUtils.d(TAG, "onStartCommand with action: " + action);
            if (action == null) return START_STICKY;
            switch (action) {
                case ACTION_START:
                    startVService();
                    return START_STICKY;
                case ACTION_STOP:
                    stopVService();
                    return START_NOT_STICKY;
                case ACTION_REQUEST_STATE:
                    broadcastState();
                    return START_STICKY;
            }
        }
        return START_STICKY;
    }

    private void broadcastState() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning.get());
        sendBroadcast(intent);
    }

    private void stopVService() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        cleanup();
        stopForeground(true);
        stopSelf();
        broadcastState();
        LogUtils.i(TAG, "Stopped");
    }

    @Override
    public void onRevoke() {
        LogUtils.d(TAG, "onRevoke:");
        stopVService();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        LogUtils.d(TAG, "onDestroy:");
        stopVService();
        super.onDestroy();
    }

    private void cleanup() {
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
        vpnInterface = null;
        udpSelectorLock = null;
        tcpSelectorLock = null;
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, "Vhosts", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("VPN service is running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (IOException e) {
                    LogUtils.e(TAG, "Error closing resource", e);
                }
            }
        }
    }

    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();
        private final FileDescriptor vpnFileDescriptor;
        private final ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private final ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private final ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            LogUtils.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();
            try {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                while (!Thread.currentThread().isInterrupted()) {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();

                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        Packet packet = new Packet(bufferToNetwork);
                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            LogUtils.w(TAG, "Unknown packet type");
                            ByteBufferPool.release(bufferToNetwork);
                        }
                    } else {
                        dataSent = false;
                    }
                    ByteBuffer bufferFromNetwork = networkToDeviceQueue.poll();
                    if (bufferFromNetwork != null) {
                        bufferFromNetwork.flip();
                        while (bufferFromNetwork.hasRemaining())
                            vpnOutput.write(bufferFromNetwork);
                        ByteBufferPool.release(bufferFromNetwork);
                    }

                    if (!dataSent && bufferFromNetwork == null) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (IOException e) {
                LogUtils.w(TAG, "VPNRunnable I/O error", e);
            } finally {
                closeResources(vpnInput, vpnOutput);
                LogUtils.d(TAG, "VPN routine is END!!!!");
            }
        }
    }
}