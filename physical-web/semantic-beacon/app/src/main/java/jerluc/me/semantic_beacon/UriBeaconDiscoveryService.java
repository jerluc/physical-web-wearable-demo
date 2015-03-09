package jerluc.me.semantic_beacon;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;

import org.json.JSONObject;
import org.uribeacon.beacon.UriBeacon;
import org.uribeacon.scan.compat.BluetoothLeScannerCompat;
import org.uribeacon.scan.compat.BluetoothLeScannerCompatProvider;
import org.uribeacon.scan.compat.ScanCallback;
import org.uribeacon.scan.compat.ScanFilter;
import org.uribeacon.scan.compat.ScanResult;
import org.uribeacon.scan.compat.ScanSettings;
import org.uribeacon.scan.util.RegionResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class UriBeaconDiscoveryService extends Service implements MetadataResolver.MetadataResolverCallback {

    private static final String TAG = UriBeaconDiscoveryService.class.getName();
    private static final String NOTIFICATION_GROUP_KEY = "SEMANTIC_URI_BEACON_NOTIFICATIONS";
    private static final int NOTIFICATION_ID = 17;
    private static final int TIMEOUT_FOR_OLD_BEACONS = 2;
    private static final int NOTIFICATION_PRIORITY = NotificationCompat.PRIORITY_HIGH;
    private static final long NOTIFICATION_UPDATE_GATE_DURATION = 1000;
    private boolean mCanUpdateNotifications = false;
    private NotificationManagerCompat mNotificationManager;
    private Handler mHandler;
    private ScreenBroadcastReceiver mScreenStateBroadcastReceiver;
    private RegionResolver mRegionResolver;
    private HashMap<String, MetadataResolver.UrlMetadata> mUrlToUrlMetadata;
    private List<String> mSortedDevices;
    private HashMap<String, String> mDeviceAddressToUrl;
    private Runnable mNotificationUpdateGateTimeout = new Runnable() {
        @Override
        public void run() {
            mCanUpdateNotifications = true;
            updateNotifications();
        }
    };
    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult scanResult) {
            switch (callbackType) {
                case ScanSettings.CALLBACK_TYPE_FIRST_MATCH:
                    handleFoundDevice(scanResult);
                    break;
                case ScanSettings.CALLBACK_TYPE_ALL_MATCHES:
                    handleFoundDevice(scanResult);
                    break;
                default:
                    Log.e(TAG, "Unrecognized callback type constant received: " + callbackType);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> scanResults) {
            Log.i(TAG, "WAHAHSADHSAHDLSAD");
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.d(TAG, "onScanFailed  " + "errorCode: " + errorCode);
        }
    };
    private Comparator<String> mSortByRegionResolverRegionComparator = new Comparator<String>() {
        @Override
        public int compare(String address, String otherAddress) {
            // Check if one of the addresses is the nearest
            final String nearest = mRegionResolver.getNearestAddress();
            if (address.equals(nearest)) {
                return -1;
            }
            if (otherAddress.equals(nearest)) {
                return 1;
            }
            // Otherwise sort by region
            int r1 = mRegionResolver.getRegion(address);
            int r2 = mRegionResolver.getRegion(otherAddress);
            if (r1 != r2) {
                return ((Integer) r1).compareTo(r2);
            }
            // The two devices are in the same region, sort by device address.
            return address.compareTo(otherAddress);
        }
    };

    private void initialize() {
        mNotificationManager = NotificationManagerCompat.from(this);
        mHandler = new Handler();
        initializeScreenStateBroadcastReceiver();
    }
    private void initializeScreenStateBroadcastReceiver() {
        mScreenStateBroadcastReceiver = new ScreenBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenStateBroadcastReceiver, intentFilter);
    }

    private void initializeLists() {
        mRegionResolver = new RegionResolver();
        mUrlToUrlMetadata = new HashMap<>();
        mSortedDevices = null;
        mDeviceAddressToUrl = new HashMap<>();
    }

    private BluetoothLeScannerCompat getLeScanner() {
        return BluetoothLeScannerCompatProvider.getBluetoothLeScannerCompat(getApplicationContext());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initialize();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Since sometimes the lists have values when onStartCommand gets called
        initializeLists();
        // Start scanning only if the screen is on
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager.isScreenOn()) {
            mCanUpdateNotifications = false;
            mHandler.postDelayed(mNotificationUpdateGateTimeout, NOTIFICATION_UPDATE_GATE_DURATION);
            startSearchingForUriBeacons();
        }

        //make sure the service keeps running
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy:  service exiting");
        mHandler.removeCallbacks(mNotificationUpdateGateTimeout);
        stopSearchingForUriBeacons();
        unregisterReceiver(mScreenStateBroadcastReceiver);
        mNotificationManager.cancelAll();
    }

    @Override
    public void onUrlMetadataReceived(String url, MetadataResolver.UrlMetadata urlMetadata) {
        mUrlToUrlMetadata.put(url, urlMetadata);
        updateNotifications();
    }

    @Override
    public void onUrlMetadataIconReceived() {
        updateNotifications();
    }

    private void startSearchingForUriBeacons() {
        ScanSettings settings = new ScanSettings.Builder()
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build();

        List<ScanFilter> filters = new ArrayList<>();

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceData(UriBeacon.URI_SERVICE_UUID,
                        new byte[]{},
                        new byte[]{})
                .build();

        filters.add(filter);

        boolean started = getLeScanner().startScan(filters, settings, mScanCallback);
        Log.v(TAG, started ? "... scan started" : "... scan NOT started");
    }

    private void stopSearchingForUriBeacons() {
        getLeScanner().stopScan(mScanCallback);
    }

    private void handleFoundDevice(ScanResult scanResult) {
        long timeStamp = scanResult.getTimestampNanos();
        long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
        if (now - timeStamp < TimeUnit.SECONDS.toNanos(TIMEOUT_FOR_OLD_BEACONS)) {
            UriBeacon uriBeacon = UriBeacon.parseFromBytes(scanResult.getScanRecord().getBytes());
            if (uriBeacon != null) {
                String address = scanResult.getDevice().getAddress();
                int rssi = scanResult.getRssi();
                int txPower = uriBeacon.getTxPowerLevel();
                String url = uriBeacon.getUriString();
                // If we haven't yet seen this url
                if (!mUrlToUrlMetadata.containsKey(url)) {
                    mUrlToUrlMetadata.put(url, null);
                    mDeviceAddressToUrl.put(address, url);
                    // Fetch the metadata for this url
                    MetadataResolver.findUrlMetadata(this, UriBeaconDiscoveryService.this, url, txPower, rssi);
                }
                // Update the ranging data
                mRegionResolver.onUpdate(address, rssi, txPower);
            }
        }
    }

    private ArrayList<String> getSortedBeaconsWithMetadata() {
        ArrayList<String> unSorted = new ArrayList<>(mDeviceAddressToUrl.size());
        for (String key : mDeviceAddressToUrl.keySet()) {
            if (mUrlToUrlMetadata.get(mDeviceAddressToUrl.get(key)) != null) {
                unSorted.add(key);
            }
        }
        // Sort using the region resolver regions
        Collections.sort(unSorted, mSortByRegionResolverRegionComparator);
        return unSorted;
    }

    private void updateNotifications() {
        if (!mCanUpdateNotifications) {
            return;
        }

        mSortedDevices = getSortedBeaconsWithMetadata();

        // If no beacons have been found
        if (mSortedDevices.size() == 0) {
            // Remove all existing notifications
            mNotificationManager.cancelAll();
        } else if (mSortedDevices.size() == 1) {
            updateNearbyBeaconNotification(true, mDeviceAddressToUrl.get(mSortedDevices.get(0)),
                    NOTIFICATION_ID);
        }
    }

    private void updateNearbyBeaconNotification(boolean single, String url, int notificationId) {
        MetadataResolver.UrlMetadata urlMetadata = mUrlToUrlMetadata.get(url);
        if (urlMetadata == null) {
            return;
        }

        // Create an intent that will open the browser to the beacon's url
        // if the user taps on the notification
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlMetadata.siteUrl));
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(getSmallIcon(urlMetadata))
                .setLargeIcon(urlMetadata.image)
                .setContentTitle(urlMetadata.name)
                .setContentText(urlMetadata.description)
                .setPriority(NOTIFICATION_PRIORITY)
                .setContentIntent(pendingIntent);
        try {
            for (int i = 0; i < urlMetadata.potentialActions.length(); i++) {
                JSONObject action = urlMetadata.potentialActions.getJSONObject(i);
                Uri actionUri = Uri.parse(action.getJSONObject("target").getString("urlTemplate"));
                PendingIntent actionIntent = PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    new Intent(Intent.ACTION_VIEW, actionUri),
                    PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(
                    getActionIcon(action.getString("@type")),
                    action.getString("name"),
                    actionIntent);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Oh noeeess!", ex);
        }

        // For some reason if there is only one notification and you call setGroup
        // the notification doesn't show up on the N7 running kit kat
        if (!single) {
            builder = builder.setGroup(NOTIFICATION_GROUP_KEY);
        }
        Notification notification = builder.build();

        mNotificationManager.notify(notificationId, notification);
    }

    private int getSmallIcon(MetadataResolver.UrlMetadata urlMetadata) {
        switch (urlMetadata.type) {
            case "Person":
                return R.drawable.person;
            case "Restaurant":
                return R.drawable.restaurant;
            case "Organization":
                return R.drawable.organization;
            default:
                return android.R.drawable.ic_menu_help;
        }
    }

    private int getActionIcon(String actionType) {
        switch (actionType) {
            case "ViewAction":
                return android.R.drawable.ic_menu_view;
            case "SendAction":
                return android.R.drawable.sym_action_email;
            case "CommunicateAction":
                return android.R.drawable.ic_menu_call;
            case "ReviewAction":
                return android.R.drawable.star_big_off;
            case "ListenAction":
                return android.R.drawable.ic_lock_silent_mode_off;
            case "FollowAction":
                return android.R.drawable.ic_menu_add;
            case "ReserveAction":
                return android.R.drawable.ic_menu_today;
            default:
                return android.R.drawable.ic_menu_help;
        }
    }

    /**
     * This is the class that listens for screen on/off events
     */
    private class ScreenBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean isScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            initializeLists();
            mNotificationManager.cancelAll();
            if (isScreenOn) {
                mCanUpdateNotifications = false;
                mHandler.postDelayed(mNotificationUpdateGateTimeout, NOTIFICATION_UPDATE_GATE_DURATION);
                startSearchingForUriBeacons();
            } else {
                mHandler.removeCallbacks(mNotificationUpdateGateTimeout);
                stopSearchingForUriBeacons();
            }
        }
    }
}
