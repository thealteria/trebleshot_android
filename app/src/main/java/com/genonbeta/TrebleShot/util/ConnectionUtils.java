/*
 * Copyright (C) 2019 Veli Tasalı
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.genonbeta.TrebleShot.util;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.*;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.genonbeta.CoolSocket.CoolSocket;
import com.genonbeta.TrebleShot.R;
import com.genonbeta.TrebleShot.config.AppConfig;
import com.genonbeta.TrebleShot.config.Keyword;
import com.genonbeta.TrebleShot.database.Kuick;
import com.genonbeta.TrebleShot.object.DeviceAddress;
import com.genonbeta.TrebleShot.object.DeviceConnection;
import com.genonbeta.TrebleShot.object.NetworkDevice;
import com.genonbeta.TrebleShot.service.backgroundservice.BackgroundTask;
import com.genonbeta.TrebleShot.service.backgroundservice.TaskMessage;
import com.genonbeta.TrebleShot.util.communicationbridge.CommunicationException;
import com.genonbeta.TrebleShot.util.communicationbridge.DifferentClientException;
import com.genonbeta.TrebleShot.util.communicationbridge.NotAllowedException;
import com.genonbeta.TrebleShot.util.communicationbridge.NotTrustedException;
import com.genonbeta.android.framework.ui.callback.SnackbarPlacementProvider;
import com.genonbeta.android.framework.util.Stoppable;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static com.genonbeta.TrebleShot.adapter.NetworkDeviceListAdapter.*;

/**
 * created by: veli
 * date: 15/04/18 18:37
 */
public class ConnectionUtils
{
    public static final String TAG = ConnectionUtils.class.getSimpleName();

    private Context mContext;
    private WifiManager mWifiManager;
    private LocationManager mLocationManager;
    private ConnectivityManager mConnectivityManager;
    private boolean mWirelessEnableRequested = false;

    public ConnectionUtils(Context context)
    {
        mContext = context;
        mWifiManager = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        mLocationManager = (LocationManager) getContext().getApplicationContext().getSystemService(
                Context.LOCATION_SERVICE);
        mConnectivityManager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public static String getCleanNetworkName(String networkName)
    {
        if (networkName == null)
            return "";

        return networkName.replace("\"", "");
    }

    public boolean canAccessLocation()
    {
        return hasLocationPermission() && isLocationServiceEnabled();
    }

    public boolean canReadScanResults()
    {
        return getWifiManager().isWifiEnabled() && (Build.VERSION.SDK_INT < 23 || canAccessLocation());
    }

    public static WifiConfiguration createWifiConfig(ScanResult result, String password)
    {
        WifiConfiguration config = new WifiConfiguration();
        config.hiddenSSID = false;
        config.BSSID = result.BSSID;
        config.status = WifiConfiguration.Status.ENABLED;

        if (result.capabilities.contains("WEP")) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
            config.SSID = "\"" + result.SSID + "\"";
            config.wepTxKeyIndex = 0;
            config.wepKeys[0] = password;
        } else if (result.capabilities.contains("PSK")) {
            config.SSID = "\"" + result.SSID + "\"";
            config.preSharedKey = "\"" + password + "\"";
        } else if (result.capabilities.contains("EAP")) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            config.SSID = "\"" + result.SSID + "\"";
            config.preSharedKey = "\"" + password + "\"";
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            config.SSID = "\"" + result.SSID + "\"";
            config.preSharedKey = null;
        }

        return config;
    }

    /**
     * @return True if disabling the network was successful
     * @deprecated Do not use this method with 10 and above.
     */
    @Deprecated
    public boolean disableCurrentNetwork()
    {
        // WONTFIX: Android 10 makes this obsolete.
        // NOTTODO: Networks added by other applications will possibly reconnect even if we disconnect them
        // This is because we are only allowed to manipulate the connections that we added.
        // And if it is the case, then the return value of disableNetwork will be false.
        return isConnectedToAnyNetwork() && getWifiManager().disconnect()
                && getWifiManager().disableNetwork(getWifiManager().getConnectionInfo().getNetworkId());
    }

    public boolean enableNetwork(int networkId)
    {
        Log.d(TAG, "enableNetwork: Enabling network: " + networkId);
        if (getWifiManager().enableNetwork(networkId, true))
            return true;

        Log.d(TAG, "toggleConnection: Could not enable the network");
        return false;
    }

    @WorkerThread
    public InetAddress establishHotspotConnection(Stoppable stoppable, InfoHolder holder)
    {
        Object specifier = holder.object();
        int pingTimeout = 1000; // ms
        long startTime = System.nanoTime();
        boolean connectionToggled = specifier instanceof NetworkSuggestion; // suggestions comes pretested and initiated

        while (true) {
            DhcpInfo wifiDhcpInfo = getWifiManager().getDhcpInfo();

            Log.d(TAG, "establishHotspotConnection(): Waiting to reach to the network. DhcpInfo: "
                    + wifiDhcpInfo.toString());

            if (Build.VERSION.SDK_INT < 29 && !getWifiManager().isWifiEnabled()) {
                Log.d(TAG, "establishHotspotConnection(): Wifi is off. Making a request to turn it on");

                if (!getWifiManager().setWifiEnabled(true)) {
                    Log.d(TAG, "establishHotspotConnection(): Wifi was off. The request has failed. Exiting.");
                    break;
                }
            } else if (specifier instanceof NetworkDescription) {
                Log.d(TAG, "establishHotspotConnection: The network is not ready to be used yet.");

                if (Build.VERSION.SDK_INT < 29)
                    getWifiManager().startScan();

                NetworkDescription description = (NetworkDescription) specifier;
                String ssid = description.ssid;
                String bssid = description.bssid;
                String password = description.password;
                ScanResult result = findFromScanResults(ssid, bssid);

                if (result == null)
                    Log.e(TAG, "establishHotspotConnection: No network found with the name " + ssid);
                else {
                    specifier = new InfoHolder(createWifiConfig(result, password));
                    Log.d(TAG, "establishHotspotConnection: Created HotspotNetwork object from scan results");
                }
            } else if (specifier instanceof WifiConfiguration && !isConnectedToNetwork((WifiConfiguration) specifier)
                    && !connectionToggled) {
                connectionToggled = toggleConnection((WifiConfiguration) specifier);
                Log.d(TAG, "establishHotspotConnection(): Requested network toggle " + connectionToggled);
            } else if (wifiDhcpInfo.gateway != 0) {
                try {
                    Inet4Address testAddress = NetworkUtils.convertInet4Address(wifiDhcpInfo.gateway);
                    NetworkInterface networkInterface = NetworkUtils.findNetworkInterface(testAddress);

                    if (testAddress.isReachable(pingTimeout) || testAddress.isReachable(networkInterface,
                            3600, pingTimeout)) {
                        Log.d(TAG, "establishHotspotConnection(): AP has been reached. Returning OK state.");
                        return testAddress;
                    } else
                        Log.d(TAG, "establishHotspotConnection(): Connection check ping failed");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else
                Log.d(TAG, "establishHotspotConnection(): No DHCP provided or connection not ready. Looping...");

            if (System.nanoTime() - startTime > AppConfig.DEFAULT_SOCKET_TIMEOUT_LARGE * 1e6
                    || stoppable.isInterrupted()) {
                Log.d(TAG, "establishHotspotConnection(): Timed out or onTimePassed returned true. Exiting...");
                break;
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }
        }

        return null;
    }

    /**
     * @param configuration The configuration that contains network SSID, BSSID, other fields required to filter the
     *                      network
     * @see #findFromConfigurations(String, String)
     */
    @Deprecated
    public WifiConfiguration findFromConfigurations(WifiConfiguration configuration)
    {
        return findFromConfigurations(configuration.SSID, configuration.BSSID);
    }

    /**
     * @param ssid  The SSID that will be used to filter.
     * @param bssid The MAC address of the network. Its use is prioritized when not null since it is unique.
     * @return The matching configuration or null if no configuration matched with the given parameters.
     * @deprecated The use of this method is limited to Android version 9 and below due to the deprecation of the
     * APIs it makes use of.
     */
    @Deprecated
    public WifiConfiguration findFromConfigurations(String ssid, @Nullable String bssid)
    {
        List<WifiConfiguration> list = getWifiManager().getConfiguredNetworks();
        for (WifiConfiguration config : list)
            if (bssid == null) {
                if (ssid.equalsIgnoreCase(config.SSID))
                    return config;
            } else {
                if (bssid.equalsIgnoreCase(config.BSSID))
                    return config;
            }

        return null;
    }

    public ScanResult findFromScanResults(String ssid, @Nullable String bssid) throws SecurityException
    {
        if (canReadScanResults()) {
            for (ScanResult result : getWifiManager().getScanResults())
                if (result.SSID.equalsIgnoreCase(ssid) && (bssid == null || result.BSSID.equalsIgnoreCase(bssid))) {
                    Log.d(TAG, "findFromScanResults: Found the network with capabilities: " + result.capabilities);
                    return result;
                }
        } else {
            Log.e(TAG, "findFromScanResults: Cannot read scan results");
            throw new SecurityException("You do not have permission to read the scan results");
        }

        Log.d(TAG, "findFromScanResults: Could not find the related Wi-Fi network with SSID " + ssid);
        return null;
    }

    public boolean hasLocationPermission()
    {
        return ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public Context getContext()
    {
        return mContext;
    }

    public ConnectivityManager getConnectivityManager()
    {
        return mConnectivityManager;
    }

    public LocationManager getLocationManager()
    {
        return mLocationManager;
    }

    public WifiManager getWifiManager()
    {
        return mWifiManager;
    }

    public boolean isConnectionToHotspotNetwork()
    {
        WifiInfo wifiInfo = getWifiManager().getConnectionInfo();
        return wifiInfo != null && getCleanNetworkName(wifiInfo.getSSID()).startsWith(AppConfig.PREFIX_ACCESS_POINT);
    }

    /**
     * @return True if connected to a Wi-Fi network.
     * @deprecated Do not use this method with 10 and above.
     */
    @Deprecated
    public boolean isConnectedToAnyNetwork()
    {
        NetworkInfo info = getConnectivityManager().getActiveNetworkInfo();
        return info != null && info.getType() == ConnectivityManager.TYPE_WIFI && info.isConnected();
    }

    public boolean isConnectedToNetwork(WifiConfiguration config)
    {
        if (!isConnectedToAnyNetwork())
            return false;

        String bssid = config.BSSID;
        Log.d(TAG, "isConnectedToNetwork: " + bssid + " othr: " + getWifiManager().getConnectionInfo().getBSSID());
        return bssid != null && bssid.equalsIgnoreCase(getWifiManager().getConnectionInfo().getBSSID());
    }

    public boolean isLocationServiceEnabled()
    {
        return mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /**
     * @return True if the mobile data connection is active.
     * @deprecated Do not use this method above 9, there is a better method in-place.
     */
    @Deprecated
    public boolean isMobileDataActive()
    {
        return mConnectivityManager.getActiveNetworkInfo() != null
                && mConnectivityManager.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE;
    }

    public boolean notifyWirelessRequestHandled()
    {
        boolean returnedState = mWirelessEnableRequested;
        mWirelessEnableRequested = false;
        return returnedState;
    }
    
    public static void postConnectionRejectionInformation(JSONObject clientResponse) throws NotAllowedException,
            NotTrustedException
    {
        try {
            if (clientResponse.has(Keyword.ERROR)) {
                String error = clientResponse.getString(Keyword.ERROR);
                if (error.equals(Keyword.ERROR_NOT_ALLOWED))
                    throw new NotAllowedException();
                else if (error.equals(Keyword.ERROR_NOT_TRUSTED))
                    throw new NotTrustedException();
            }
            // FIXME: 27.03.2020 Also handle unknown errors
            //postUnknownError(context, task, retryCallback);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    // TODO: 26.03.2020 Use the error create above and make this a part of it
    public static void postUnknownError(Context context, BackgroundTask task, TaskMessage.Callback retryCallback)
    {
        task.post(TaskMessage.newInstance()
                .setMessage(context, R.string.mesg_somethingWentWrong)
                .addAction(context, R.string.butn_close, Dialog.BUTTON_NEGATIVE, null)
                .addAction(context, R.string.butn_retry, Dialog.BUTTON_POSITIVE, retryCallback));
    }

    @WorkerThread
    public static DeviceAddress setupConnection(Context context, InetAddress inetAddress, int pin)
            throws DifferentClientException, TimeoutException, CommunicationException, IOException, JSONException,
            NotAllowedException, NotTrustedException
    {
        Kuick kuick = AppUtils.getKuick(context);
        CommunicationBridge.Client client = new CommunicationBridge.Client(kuick, pin);
        CoolSocket.ActiveConnection activeConnection = client.communicate(inetAddress, false);

        activeConnection.reply(new JSONObject()
                .put(Keyword.REQUEST, Keyword.REQUEST_ACQUAINTANCE)
                .toString());

        NetworkDevice device = client.getDevice();
        JSONObject receivedReply = new JSONObject(activeConnection.receive().response);

        if (receivedReply.has(Keyword.RESULT) && receivedReply.getBoolean(Keyword.RESULT) && device.id != null) {
            DeviceConnection connection = NetworkDeviceLoader.processConnection(kuick, device,
                    inetAddress.getHostAddress());
            return new DeviceAddress(device, connection);
        } else
            postConnectionRejectionInformation(receivedReply);
        // TODO: 26.03.2020 This should be done by postConnectionRejectionInformation()
        throw new CommunicationException("Something went wrong. Duh?");
    }

    public void showConnectionOptions(Activity activity, SnackbarPlacementProvider provider,
                                      int locationPermRequestId)
    {
        if (!getWifiManager().isWifiEnabled())
            provider.createSnackbar(R.string.mesg_suggestSelfHotspot)
                    .setAction(R.string.butn_enable, view -> {
                        mWirelessEnableRequested = true;
                        turnOnWiFi(activity, provider);
                    })
                    .show();
        else if (validateLocationPermission(activity, locationPermRequestId)) {
            provider.createSnackbar(R.string.mesg_scanningSelfHotspot)
                    .setAction(R.string.butn_wifiSettings, view -> activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .show();
        }
    }

    /**
     * Enable and connect to the given network specification.
     *
     * @param config The network specifier that will be connected to.
     * @return True when the request is successful and false when it fails.
     * @deprecated The use of this method is limited to Android version 9 and below due to the deprecation of the
     * APIs it makes use of.
     */
    public boolean startConnection(WifiConfiguration config)
    {
        if (isConnectedToNetwork(config)) {
            Log.d(TAG, "toggleConnection: Already connected to the network");
            return true;
        }

        if (isConnectedToAnyNetwork()) {
            Log.d(TAG, "toggleConnection: Connected to some other network, will try to disable it.");
            disableCurrentNetwork();
        }

        try {
            WifiConfiguration existingConfig = findFromConfigurations(config);
            getWifiManager().disconnect();

            if (existingConfig != null) {
                Log.d(TAG, "toggleConnection: Network already exists, will try to enable it.");
                return enableNetwork(existingConfig.networkId);
            } else {
                Log.d(TAG, "toggleConnection: Network did not exist before, adding it.");
                return enableNetwork(getWifiManager().addNetwork(config));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        disableCurrentNetwork();

        return false;
    }

    @TargetApi(29)
    public int suggestNetwork(NetworkSuggestion suggestion)
    {
        final List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
        suggestions.add(suggestion.object);
        return getWifiManager().addNetworkSuggestions(suggestions);
    }

    /**
     * This method activates or deactivates a given network depending on its state.
     *
     * @param config The network specifier that you want to toggle the connection to.
     * @return True when the request is successful, false if otherwise.
     * @deprecated The use of this method is limited to Android version 9 and below due to the deprecation of the
     * APIs it makes use of.
     */
    @Deprecated
    public boolean toggleConnection(WifiConfiguration config)
    {
        return isConnectedToNetwork(config) ? getWifiManager().disconnect() : startConnection(config);
    }

    public void toggleHotspot(FragmentActivity activity, SnackbarPlacementProvider provider, HotspotManager manager,
                              boolean conditional, int locationPermRequestId)
    {
        if (!HotspotManager.isSupported())
            return;

        if (conditional) {
            if (Build.VERSION.SDK_INT >= 26 && !validateLocationPermission(activity, locationPermRequestId))
                return;

            else if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(activity)) {
                new AlertDialog.Builder(activity)
                        .setMessage(R.string.mesg_errorHotspotPermission)
                        .setNegativeButton(R.string.butn_cancel, null)
                        .setPositiveButton(R.string.butn_settings, (dialog, which) -> {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                    .setData(Uri.parse("package:" + activity.getPackageName()))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        })
                        .show();

                return;
            } else if (Build.VERSION.SDK_INT < 26 && !manager.isEnabled()
                    && isMobileDataActive()) {
                new AlertDialog.Builder(activity)
                        .setMessage(R.string.mesg_warningHotspotMobileActive)
                        .setNegativeButton(R.string.butn_cancel, null)
                        .setPositiveButton(R.string.butn_skip, (dialog, which) -> {
                            // no need to call watcher due to recycle
                            toggleHotspot(activity, provider, manager, false, locationPermRequestId);
                        })
                        .show();

                return;
            }
        }

        WifiConfiguration wifiConfiguration = manager.getConfiguration();

        if (!manager.isEnabled() || (wifiConfiguration != null
                && AppUtils.getHotspotName(activity).equals(wifiConfiguration.SSID)))
            provider.createSnackbar(manager.isEnabled() ? R.string.mesg_stoppingSelfHotspot
                    : R.string.mesg_startingSelfHotspot).show();

        toggleHotspot(activity);
    }

    private void toggleHotspot(Activity activity)
    {
        try {
            AppUtils.getBgService(activity).toggleHotspot();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    public void turnOnWiFi(Activity activity, SnackbarPlacementProvider provider)
    {
        if (Build.VERSION.SDK_INT >= 29)
            activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        else if (getWifiManager().setWifiEnabled(true)) {
            provider.createSnackbar(R.string.mesg_turningWiFiOn).show();
        } else
            new AlertDialog.Builder(activity)
                    .setMessage(R.string.mesg_wifiEnableFailed)
                    .setNegativeButton(R.string.butn_close, null)
                    .setPositiveButton(R.string.butn_settings, (dialog, which) -> activity.startActivity(
                            new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .show();
    }

    public boolean validateLocationPermission(Activity activity, int permRequestId)
    {
        if (Build.VERSION.SDK_INT < 23)
            return true;

        if (!hasLocationPermission()) {
            new AlertDialog.Builder(activity)
                    .setMessage(R.string.mesg_locationPermissionRequiredSelfHotspot)
                    .setNegativeButton(R.string.butn_cancel, null)
                    .setPositiveButton(R.string.butn_ask, (dialog, which) -> {
                        // No, I am not going to add an if statement since when it is not needed
                        // the main method returns true.
                        activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, permRequestId);
                    })
                    .show();
        } else if (!isLocationServiceEnabled()) {
            new AlertDialog.Builder(getContext())
                    .setMessage(R.string.mesg_locationDisabledSelfHotspot)
                    .setNegativeButton(R.string.butn_cancel, null)
                    .setPositiveButton(R.string.butn_locationSettings, (dialog, which) -> activity.startActivity(new Intent(
                            Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    .show();
        } else
            return true;

        return false;
    }
}
