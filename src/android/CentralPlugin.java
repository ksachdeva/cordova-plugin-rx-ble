package com.ksachdeva.opensource.ble.central;

import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.pm.LauncherApps;
import android.util.Log;
import android.content.Context;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.internal.RxBleLog;


import java.util.HashMap;
import java.util.UUID;

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

    private RxBleScanResultConverter scanResult = new RxBleScanResultConverter();
    private ErrorConverter errorConverter = new ErrorConverter();
    private RxBleDeviceConverter deviceConverter = new RxBleDeviceConverter();

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

    private void onDeviceDisconnected(RxBleDevice device) {
        connectingDevices.removeSubscription(device.getMacAddress());
        connectionMap.remove(device.getMacAddress());
        // sendEvent(Event.DisconnectionEvent, converter.device.toJSCallback(device));
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

    private Context getApplicationContext() {
      return cordova.getActivity();
    }
}
