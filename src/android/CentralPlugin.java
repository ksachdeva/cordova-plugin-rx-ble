package com.ksachdeva.opensource.ble.central;

import java.util.HashMap;
import java.util.UUID;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.pm.LauncherApps;
import android.telecom.Call;
import android.util.Log;
import android.content.Context;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.internal.RxBleLog;

import rx.Observer;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

import com.ksachdeva.opensource.ble.central.utils.DisposableMap;
import com.ksachdeva.opensource.ble.central.utils.UUIDConverter;
import com.ksachdeva.opensource.ble.central.errors.ErrorConverter;
import com.ksachdeva.opensource.ble.central.errors.BleError;
import com.ksachdeva.opensource.ble.central.converters.RxBleScanResultConverter;
import com.ksachdeva.opensource.ble.central.converters.RxBleDeviceConverter;
import com.ksachdeva.opensource.ble.central.converters.BluetoothGattCharacteristicConverter;
import com.ksachdeva.opensource.ble.central.converters.BluetoothGattServiceConverter;


public class CentralPlugin extends CordovaPlugin {

    private static final String TAG = "CentralPlugin";

    private RxBleClient rxBleClient;
    private HashMap<String, RxBleConnection> connectionMap;
    private final HashMap<String, String> notificationMap = new HashMap<String, String>();

    private Subscription scanSubscription;
    private final DisposableMap transactions = new DisposableMap();
    private final DisposableMap connectingDevices = new DisposableMap();

    // various callback context
    private CallbackContext deviceScanCallbackContext;
    private CallbackContext monitorDeviceDisconnectCallbackContext;

    private RxBleScanResultConverter scanResult = new RxBleScanResultConverter();
    private ErrorConverter errorConverter = new ErrorConverter();
    private RxBleDeviceConverter deviceConverter = new RxBleDeviceConverter();
    private BluetoothGattServiceConverter serviceConverter = new BluetoothGattServiceConverter();
    private BluetoothGattCharacteristicConverter characteristicConverter = new BluetoothGattCharacteristicConverter();

    @Override
    protected void pluginInitialize() {
        super.pluginInitialize();

        connectionMap = new HashMap<String, RxBleConnection>();

        this.createClient();
        RxBleClient.setLogLevel(RxBleLog.DEBUG);
    }

    @Override
    public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {

      if (action.equals("startDeviceScan")) {
          startDeviceScan(args, callbackContext);
          return true;
      } else if (action.equals("stopScan")) {
          stopScan(callbackContext);
          return true;
      } else if (action.equals("connectToDevice")) {
          connectToDevice(args, callbackContext);
          return true;
      } else if (action.equals("monitorDeviceDisconnect")) {
          monitorDeviceDisconnect(args, callbackContext);
          return true;
      } else if (action.equals("disconnectDevice")) {
          disconnectDevice(args, callbackContext);
          return true;
      } else if (action.equals("isDeviceConnected")) {
          isDeviceConnected(args, callbackContext);
          return true;
      } else if (action.equals("discoverServices")) {
          discoverServices(args, callbackContext);
          return true;
      }

      return false;
    }

    public void createClient() {
        rxBleClient = RxBleClient.create(this.getApplicationContext());
    }

    public void destroyClient() {
        if (scanSubscription != null && !scanSubscription.isUnsubscribed()) {
            scanSubscription.unsubscribe();
            scanSubscription = null;
        }

        rxBleClient = null;
    }

    private void monitorDeviceDisconnect(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        // let's keep the callback
        this.monitorDeviceDisconnectCallbackContext = callbackContext;
    }

    private void startDeviceScan(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

        this.deviceScanCallbackContext = callbackContext;

        final UUID[] uuids;

        // JSONArray filteredUUIDs = args.get(0) == null ? null : args.getJSONArray(0);
        JSONArray filteredUUIDs = null;

        if (filteredUUIDs != null) {
            uuids = UUIDConverter.convert(filteredUUIDs);
        } else {
            uuids = null;
        }

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                scanSubscription = rxBleClient
                        .scanBleDevices(uuids)
                        .subscribe(new Action1<RxBleScanResult>() {
                            @Override
                            public void call(RxBleScanResult rxBleScanResult) {
                                sendSuccess(callbackContext, scanResult.toJSObject(rxBleScanResult), true);
                            }
                        }, new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                sendError(callbackContext, errorConverter.toError(throwable).toJS(), true);
                            }
                        });
            }});
    }

    private void stopScan(final CallbackContext callbackContext) {
        if (scanSubscription != null) {
            scanSubscription.unsubscribe();
            scanSubscription = null;
        }
    }

    private void disconnectDevice(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        String deviceId = args.getString(0);

        final RxBleDevice device = rxBleClient.getBleDevice(deviceId);

        if (connectingDevices.removeSubscription(deviceId) && device != null) {
            sendSuccess(callbackContext, deviceConverter.toJSObject(device), false);
        } else {
            if (device == null) {
                sendError(callbackContext, BleError.deviceNotFound(deviceId).toJS(), false);
            } else {
                sendError(callbackContext, BleError.deviceNotConnected(deviceId).toJS(), false);
            }
        }
    }

    private void isDeviceConnected(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        String deviceId = args.getString(0);

        final RxBleDevice device = rxBleClient.getBleDevice(deviceId);

        if (device == null) {
            sendError(callbackContext, BleError.deviceNotFound(deviceId).toJS(), false);
            return;
        }

        boolean connected = device.getConnectionState()
                .equals(RxBleConnection.RxBleConnectionState.CONNECTED);

        sendSuccess(callbackContext, connected, false);
    }

    private void connectToDevice(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

        String deviceId = args.getString(0);

        final RxBleDevice device = rxBleClient.getBleDevice(deviceId);

        if (device == null) {
            sendError(callbackContext, BleError.deviceNotFound(deviceId).toJS(), false);
            return;
        }

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                final Subscription subscription = device
                        .establishConnection(getApplicationContext(), false)
                        .doOnUnsubscribe(new Action0() {
                            @Override
                            public void call() {
                                // BleError.cancelled().reject(promise);
                                sendError(callbackContext, BleError.cancelled().toJS(), false);
                                onDeviceDisconnected(device);
                            }
                        })
                        .subscribe(new Observer<RxBleConnection>() {
                            @Override
                            public void onCompleted() {
                            }

                            @Override
                            public void onError(Throwable e) {
                                sendError(callbackContext, errorConverter.toError(e).toJS(), false);
                                onDeviceDisconnected(device);
                            }

                            @Override
                            public void onNext(RxBleConnection connection) {
                                connectionMap.put(device.getMacAddress(), connection);
                                sendSuccess(callbackContext, deviceConverter.toJSObject(device), false);
                            }
                        });

                connectingDevices.replaceSubscription(device.getMacAddress(), subscription);
            }
        });
    }

    public void discoverServices(final JSONArray args, final CallbackContext callbackContext) throws JSONException {

        final String deviceId = args.getString(0);

        final RxBleDevice device = rxBleClient.getBleDevice(deviceId);

        if (device == null) {
            sendError(callbackContext, BleError.deviceNotFound(deviceId).toJS(), false);
            return;
        }

        final RxBleConnection rxBleConnection = connectionMap.get(deviceId);
        if (rxBleConnection == null) {
            sendError(callbackContext, BleError.deviceNotConnected(deviceId).toJS(), false);
            return;
        }

        rxBleConnection.discoverServices()
                .subscribe(new Observer<RxBleDeviceServices>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        sendError(callbackContext, BleError.deviceNotFound(deviceId).toJS(), false);
                    }

                    @Override
                    public void onNext(RxBleDeviceServices bluetoothGattServices) {

                        try {
                            JSONArray services = new JSONArray();
                            for (BluetoothGattService service : bluetoothGattServices.getBluetoothGattServices()) {
                                JSONObject jsService = serviceConverter.toJSObject(service);
                                jsService.put("deviceUUID", device.getMacAddress());
                                services.put(jsService);
                            }
                            sendSuccess(callbackContext, services, false);
                        }catch(JSONException ex) {
                            // ignored !
                        }
                    }
                });
    }

    private void onDeviceDisconnected(RxBleDevice device) {
        connectingDevices.removeSubscription(device.getMacAddress());
        connectionMap.remove(device.getMacAddress());

        if (this.monitorDeviceDisconnectCallbackContext != null) {
            sendSuccess(this.monitorDeviceDisconnectCallbackContext, deviceConverter.toJSObject(device), true);
        }
    }

    private void sendError(final CallbackContext callbackContext, JSONObject object, boolean keepCallback) {
        PluginResult result = new PluginResult(PluginResult.Status.ERROR, object);
        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    private void sendSuccess(final CallbackContext callbackContext, JSONObject object, boolean keepCallback) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, object);
        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    private void sendSuccess(final CallbackContext callbackContext, JSONArray object, boolean keepCallback) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, object);
        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    private void sendSuccess(final CallbackContext callbackContext, boolean object, boolean keepCallback) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, object);
        result.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(result);
    }

    private Context getApplicationContext() {
      return cordova.getActivity();
    }
}
