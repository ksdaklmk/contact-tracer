package com.nuuneoi.lib.contacttracer.service;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import com.nuuneoi.lib.contacttracer.R;
import com.nuuneoi.lib.contacttracer.mock.IUser;
import com.nuuneoi.lib.contacttracer.mock.User;
import com.nuuneoi.lib.contacttracer.receiver.BootCompletedReceiver;
import com.nuuneoi.lib.contacttracer.utils.BluetoothUtils;
import com.nuuneoi.lib.contacttracer.utils.Constants;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

public class TracerService extends Service {

    private static final int FOREGROUND_NOTIFICATION_ID = 20011;

    public static final String ADVERTISING_MESSAGE =
            "com.nuuneoi.contacttracer.advertiser_message";
    public static final String ADVERTISING_MESSAGE_EXTRA_MESSAGE = "message";

    public static final String NEARBY_DEVICE_FOUND_MESSAGE =
            "com.nuuneoi.contacttracer.nearbydevicefound_message";
    public static final String NEARBY_DEVICE_FOUND_EXTRA_NAME = "name";
    public static final String NEARBY_DEVICE_FOUND_EXTRA_RSSI = "rssi";

    // Bluetooth General
    private BluetoothAdapter bluetoothAdapter;

    // Bluetooth Advertiser
    private BluetoothLeAdvertiser bluetoothLeAdvertiser;
    SampleAdvertiseCallback advertiseCallback;

    // Bluetooth Scanner
    private BluetoothLeScanner bluetoothLeScanner;
    private SampleScanCallback scanCallback;
    private Handler handler;

    // User
    IUser user;

    // Wake Lock
    PowerManager.WakeLock wakeLock;

    // Misc
    Runnable autoRefreshTimerRunnable;

    // Scanner Timer
    Runnable scannerStartTimerRunnable;

    private boolean isStoppingSelf = false;

    @Override
    public void onCreate() {
        super.onCreate();

        goForeground();

        initInstances();

        initBluetoothInstances();

        if (bluetoothAdapter == null)
            exithWithToast(R.string.ble_not_supported);

        initBluetoothAdvertiser();

        initWakeLock();

        startAdvertising();
        startAdvertisingAutoRefresh();

        initBluetoothScanner();
        startScanning();
        startScannerTimer();

        initAlarm();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();

        stopAdvertising();
        stopForeground(true);
        stopAdvertisingAutoRefresh();

        stopScannerTimer();
        stopScanning();

        if (!isStoppingSelf)
            broadcastHealthCheck();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceTask = new Intent(getApplicationContext(), this.getClass());
        restartServiceTask.setPackage(getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getService(getApplicationContext(), 1, restartServiceTask, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager myAlarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        myAlarmService.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 1000,
                restartPendingIntent);

        broadcastHealthCheck();

        super.onTaskRemoved(rootIntent);
    }

    private void exithWithToast(int string_resource_id) {
        Toast.makeText(this, string_resource_id, Toast.LENGTH_SHORT).show();
        isStoppingSelf = true;
        stopSelf();
    }

    private void initInstances() {
        user = new User(TracerService.this);

        handler = new Handler();
        autoRefreshTimerRunnable = new Runnable() {
            @Override
            public void run() {
                refreshAdvertiser();
                startAdvertisingAutoRefresh();
            }
        };
        scannerStartTimerRunnable = new Runnable() {
            @Override
            public void run() {
                startScanning();
                startScannerTimer();
            }
        };
    }

    /*************
     * Bluetooth *
     *************/

    private void initBluetoothInstances() {
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    /************************
     * Bluetooth Advertiser *
     ************************/

    private void initBluetoothAdvertiser() {
        if (bluetoothAdapter == null)
            return;
        if (bluetoothAdapter.getState() != BluetoothAdapter.STATE_ON)
            return;
        if (bluetoothLeAdvertiser == null) {
            final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (bluetoothManager != null) {
                bluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            } else {

            }
        }
    }

    /**
     * Move service to the foreground, to avoid execution limits on background processes.
     * <p>
     * Callers should call stopForeground(true) when background work is complete.
     */
    private void goForeground() {
        Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, 0);

        String channelId = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            channelId = createNotificationChannel("contact_tracer_service", "Contact Tracer Background Service");

        int icon_id = getResourceId("ic_contact_tracer_noti", "drawable", getPackageName());
        if (icon_id <= 0)
            icon_id = R.drawable.ic_contact_tracer_noti_default;

        Notification n = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Advertising device via Bluetooth")
                .setContentText("This device is discoverable to others nearby.")
                .setSmallIcon(icon_id)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(FOREGROUND_NOTIFICATION_ID, n);
    }

    private int getResourceId(String variableName, String resourceName, String packageName)
    {
        try {
            return getResources().getIdentifier(variableName, resourceName, packageName);
        } catch (Exception e) {
            return -1;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(String channelId, String channelName) {
        NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
        return channelId;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Starts BLE Advertising.
     */
    private void startAdvertising() {
        // Multiple Advertisement Required
        if (!BluetoothUtils.isMultipleAdvertisementSupported(bluetoothAdapter))
            return;

        if (bluetoothAdapter.getState() != BluetoothAdapter.STATE_ON)
            return;

        if (advertiseCallback == null) {
            sendSignalAndLog("Service: Starting Advertising");

            AdvertiseSettings settings = buildAdvertiseSettings();
            AdvertiseData data = buildAdvertiseData();
            advertiseCallback = new SampleAdvertiseCallback();
            if (bluetoothLeAdvertiser != null) {
                bluetoothLeAdvertiser.startAdvertising(settings, data,
                        advertiseCallback);
            }
        }
    }

    /**
     * Stops BLE Advertising.
     */
    private void stopAdvertising() {
        sendSignalAndLog("Service: Stopping Advertising");

        if (bluetoothAdapter.getState() != BluetoothAdapter.STATE_ON) {
            advertiseCallback = null;
            return;
        }

        if (bluetoothLeAdvertiser != null && advertiseCallback != null)
            bluetoothLeAdvertiser.stopAdvertising(advertiseCallback);
        advertiseCallback = null;
    }

    /**
     * Refresh Advertising
     */
    private void refreshAdvertiser() {
        sendSignalAndLog("Refresh Advertiser");
        stopAdvertising();
        startAdvertising();
    }

    /**
     * Setup Timer to Auto Refresh Advertising
     */
    private void startAdvertisingAutoRefresh() {
        handler.postDelayed(autoRefreshTimerRunnable, Constants.ADVERTISER_REFRESH_INTERVAL);
    }

    /**
     * Stop Advertising Auto Refresh Timer
     */
    private void stopAdvertisingAutoRefresh() {
        handler.removeCallbacks(autoRefreshTimerRunnable);
    }

    /**
     * Returns an AdvertiseData object which includes the Service UUID and Device Name.
     */
    private AdvertiseData buildAdvertiseData() {
        /**
         * Note: There is a strict limit of 31 Bytes on packets sent over BLE Advertisements.
         *  This includes everything put into AdvertiseData including UUIDs, device info, &
         *  arbitrary service or manufacturer data.
         *  Attempting to send packets over this limit will result in a failure with error code
         *  AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE. Catch this error in the
         *  onStartFailure() method of an AdvertiseCallback implementation.
         */
        String id = user.getUserId();
        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
        dataBuilder.addServiceUuid(Constants.Service_UUID);
        //dataBuilder.setIncludeDeviceName(true);
        //dataBuilder.addServiceData(Constants.Service_UUID, ByteUtils.intToByteArray(id));
        dataBuilder.addServiceData(Constants.Service_UUID, id.getBytes());
        /* For example - this will cause advertising to fail (exceeds size limit) */
        // String failureData = "asdghkajsghalkxcjhfa;sghtalksjcfhalskfjhasldkjfhdskf";
        // dataBuilder.addServiceData(Constants.Service_UUID, failureData.getBytes());
        return dataBuilder.build();
    }

    /**
     * Returns an AdvertiseSettings object set to use low power (to help preserve battery life)
     * and disable the built-in timeout since this code uses its own timeout runnable.
     */

    private AdvertiseSettings buildAdvertiseSettings() {
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER);
        settingsBuilder.setTimeout(0);
        return settingsBuilder.build();
    }

    /**
     * Custom callback after Advertising succeeds or fails to start. Broadcasts the error code
     * in an Intent to be picked up by AdvertiserFragment and stops this Service.
     */
    private class SampleAdvertiseCallback extends AdvertiseCallback {
        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            sendSignalAndLog("Advertising failed");
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            sendSignalAndLog("Advertising successfully started");
        }
    }

    private void sendSignalAndLog(CharSequence text) {
        Intent failureIntent = new Intent();
        failureIntent.setAction(ADVERTISING_MESSAGE);
        failureIntent.putExtra(ADVERTISING_MESSAGE_EXTRA_MESSAGE, text);
        sendBroadcast(failureIntent);
    }


    private void sendNearbyDeviceFoundMessage(CharSequence name, int rssi) {
        Intent failureIntent = new Intent();
        failureIntent.setAction(NEARBY_DEVICE_FOUND_MESSAGE);
        failureIntent.putExtra(NEARBY_DEVICE_FOUND_EXTRA_NAME, name);
        failureIntent.putExtra(NEARBY_DEVICE_FOUND_EXTRA_RSSI, rssi);
        sendBroadcast(failureIntent);
    }

    /*********************
     * Bluetooth Scanner *
     *********************/

    private void initBluetoothScanner() {
        if (bluetoothAdapter == null)
            return;
        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
    }

    /**
     * Setup Timer to Auto Refresh Advertising
     */
    private void startScannerTimer() {
        handler.postDelayed(scannerStartTimerRunnable, Constants.SCAN_INTERVAL);
    }

    /**
     * Stop Advertising Auto Refresh Timer
     */
    private void stopScannerTimer() {
        handler.removeCallbacks(scannerStartTimerRunnable);
    }

    /**
     * Start scanning for BLE Advertisements (& set it up to stop after a set period of time).
     */
    public void startScanning() {
        if (bluetoothAdapter.getState() != BluetoothAdapter.STATE_ON)
            return;

        if (scanCallback == null) {
            sendSignalAndLog("Start Scanning");

            // Will stop the scanning after a set time.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopScanning();
                }
            }, Constants.SCAN_PERIOD);
            // Kick off a new scan.
            scanCallback = new SampleScanCallback();
            if (bluetoothLeScanner != null)
                bluetoothLeScanner.startScan(buildScanFilters(), buildScanSettings(), scanCallback);
        } else {

        }
    }
    /**
     * Stop scanning for BLE Advertisements.
     */
    public void stopScanning() {
        sendSignalAndLog("Stop Scanning");

        if (bluetoothAdapter.getState() != BluetoothAdapter.STATE_ON) {
            scanCallback = null;
            return;
        }

        // Stop the scan, wipe the callback.
        if (bluetoothLeScanner != null && scanCallback != null)
            bluetoothLeScanner.stopScan(scanCallback);
        scanCallback = null;
        // Even if no new results, update 'last seen' times.
        //mAdapter.notifyDataSetChanged();
    }


    /**
     * Return a List of {@link ScanFilter} objects to filter by Service UUID.
     */
    private List<ScanFilter> buildScanFilters() {
        List<ScanFilter> scanFilters = new ArrayList<>();
        ScanFilter.Builder builder = new ScanFilter.Builder();
        // Comment out the below line to see all BLE devices around you
        builder.setServiceUuid(Constants.Service_UUID);
        scanFilters.add(builder.build());
        return scanFilters;
    }
    /**
     * Return a {@link ScanSettings} object set to use low power (to preserve battery life).
     */
    private ScanSettings buildScanSettings() {
        ScanSettings.Builder builder = new ScanSettings.Builder();
        builder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER);
        return builder.build();
    }

    /**
     * Custom ScanCallback object - adds to adapter on success, displays error on failure.
     */
    private class SampleScanCallback extends ScanCallback {
        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            for (ScanResult result : results) {
                String value = getUserIdFromResult(result);

                sendNearbyDeviceFoundMessage(value, result.getRssi());
            }
        }
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            String value = getUserIdFromResult(result);

            sendNearbyDeviceFoundMessage(value, result.getRssi());
        }
        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            //Toast.makeText(TracerService.this, "Scan failed with error: " + errorCode, Toast.LENGTH_LONG)
            //        .show();
        }
        private String getUserIdFromResult(ScanResult result) {
            String value;
            byte[] data = result.getScanRecord().getServiceData(Constants.Service_UUID);

            if (data != null)
                value = new String(data);
            else
                value = result.getDevice().getName();
            return value;

        }
    }

    /**
     * Wake lock
     */
    private void initWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "AdvertiserService::lock");
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {

        }
    }

    /**
     * Alarm for forever running
     */

    private void initAlarm() {
        JobScheduler mJobScheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo.Builder builder = new JobInfo.Builder(1,
                new ComponentName(getPackageName(), SchedulerService.class.getName()));
        builder.setPeriodic(Constants.SERVICE_HEALTH_CHECK_INTERVAL);
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        mJobScheduler.schedule(builder.build());

        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(TracerService.this, BootCompletedReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(TracerService.this, 0, i, 0);
        am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, Constants.SERVICE_HEALTH_CHECK_INTERVAL, pi); // Millisec * Second * Minute
    }

    private void broadcastHealthCheck() {
        Intent intent = new Intent("com.thaialert.servicehealthcheck");
        sendBroadcast(intent);
    }

    /**
     * Helper
     */

    public static boolean isRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (TracerService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Advertising", Context.MODE_PRIVATE);
        return prefs.getBoolean("service_enabled", false);
    }

    public static void enable(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Advertising", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("service_enabled", true);
        editor.apply();
    }

    public static void disable(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("Advertising", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("service_enabled", false);
        editor.apply();
    }

}
