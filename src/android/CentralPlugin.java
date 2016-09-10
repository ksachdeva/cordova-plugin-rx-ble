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
import android.support.v4.util.Pair;
import android.util.Base64;

import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleDeviceServices;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.exceptions.BleCharacteristicNotFoundException;

import rx.Observer;
import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;

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
      } else if (action.equals("discoverCharacteristics")) {
          discoverCharacteristics(args, callbackContext);
          return true;
      } else if (action.equals("monitorCharacteristic")) {
          monitorCharacteristic(args, callbackContext);
          return true;
      } else if (action.equals("cancelTransaction")) {
          cancelTransaction(args, callbackContext);
          return true;
      } else if (action.equals("readCharacteristic")) {
          readCharacteristic(args, callbackContext);
          return true;
      } else if (action.equals("writeCharacteristic")) {
          writeCharacteristic(args, callbackContext);
          return true;
      }

      return false;
    }

    private void createClient() {
        rxBleClient = RxBleClient.create(this.getApplicationContext());
    }

    private void destroyClient() {
        if (scanSubscription != null && !scanSubscription.isUnsubscribed()) {
            scanSubscription.unsubscribe();
            scanSubscription = null;
        }

        rxBleClient = null;
    }

    private void cancelTransaction(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final String transactionId = args.getString(0);
        transactions.removeSubscription(transactionId);
        callbackContext.success();
    }

    private void writeCharacteristic(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final String deviceId = args.getString(0);
        final String serviceUUIDStr = args.getString(1);
        final String charUUIDStr = args.getString(2);
        final String valueBase64 = args.getString(3);
        final boolean response = args.getBoolean(4);
        final String transactionId = args.getString(5);

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

        final UUID[] UUIDs = UUIDConverter.convert(serviceUUIDStr, charUUIDStr);
        if (UUIDs == null) {
            sendError(callbackContext, BleError.invalidUUIDs(serviceUUIDStr, charUUIDStr).toJS(), false);
            return;
        }

        final byte[] value;
        try {
            value = Base64.decode(valueBase64, Base64.DEFAULT);
        } catch (Throwable e) {
            sendError(callbackContext, BleError.invalidWriteDataForCharacteristic(valueBase64, charUUIDStr).toJS(), false);
            return;
        }

        final UUID serviceUUID = UUIDs[0];
        final UUID charUUID = UUIDs[1];

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                final Subscription subscription = rxBleConnection.discoverServices()
                        .flatMap(new Func1<RxBleDeviceServices, Observable<BluetoothGattCharacteristic>>() {
                            @Override
                            public Observable<BluetoothGattCharacteristic> call(RxBleDeviceServices rxBleDeviceServices) {
                                return rxBleDeviceServices.getCharacteristic(serviceUUID, charUUID);
                            }
                        })
                        .flatMap(new Func1<BluetoothGattCharacteristic, Observable<byte[]>>() {
                            @Override
                            public Observable<byte[]> call(BluetoothGattCharacteristic bluetoothGattCharacteristic) {
                                bluetoothGattCharacteristic.setWriteType(
                                        response ?
                                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT :
                                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                                return rxBleConnection.writeCharacteristic(bluetoothGattCharacteristic, value);
                            }
                        }, new Func2<BluetoothGattCharacteristic, byte[], Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public Pair<BluetoothGattCharacteristic, byte[]> call(BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bytes) {
                                return new Pair<BluetoothGattCharacteristic, byte[]>(bluetoothGattCharacteristic, bytes);
                            }
                        })
                        .doOnUnsubscribe(new Action0() {
                            @Override
                            public void call() {
                                // BleError.cancelled().reject(promise);
                                transactions.removeSubscription(transactionId);
                            }
                        })
                        .subscribe(new Observer<Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public void onCompleted() {
                                transactions.removeSubscription(transactionId);
                            }

                            @Override
                            public void onError(Throwable e) {
                                if (e instanceof BleCharacteristicNotFoundException) {
                                    sendError(callbackContext, BleError.characteristicNotFound(UUIDConverter.fromUUID(charUUID)).toJS(), false);
                                    return;
                                }
                                sendError(callbackContext, errorConverter.toError(e).toJS(), false);
                                transactions.removeSubscription(transactionId);
                            }

                            @Override
                            public void onNext(Pair<BluetoothGattCharacteristic, byte[]> result) {
                                try {
                                    JSONObject jsObject = characteristicConverter.toJSObject(result.first);
                                    jsObject.put("deviceUUID", deviceId);
                                    jsObject.put("serviceUUID", serviceUUID);
                                    jsObject.put("value", Base64.encodeToString(result.second, Base64.DEFAULT));
                                    sendSuccess(callbackContext, jsObject, false);
                                } catch(JSONException jsonEx) {
                                    // ignored !!
                                }
                            }
                        });

                transactions.replaceSubscription(transactionId, subscription);

            }
        });
    }


    private void readCharacteristic(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final String deviceId = args.getString(0);
        final String serviceUUIDStr = args.getString(1);
        final String charUUIDStr = args.getString(2);
        final String transactionId = args.getString(3);

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

        final UUID[] UUIDs = UUIDConverter.convert(serviceUUIDStr, charUUIDStr);
        if (UUIDs == null) {
            sendError(callbackContext, BleError.invalidUUIDs(serviceUUIDStr, charUUIDStr).toJS(), false);
            return;
        }

        final UUID serviceUUID = UUIDs[0];
        final UUID charUUID = UUIDs[1];

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                final Subscription subscription = rxBleConnection.discoverServices()
                        .flatMap(new Func1<RxBleDeviceServices, Observable<BluetoothGattCharacteristic>>() {
                            @Override
                            public Observable<BluetoothGattCharacteristic> call(RxBleDeviceServices rxBleDeviceServices) {
                                return rxBleDeviceServices.getCharacteristic(serviceUUID, charUUID);
                            }
                        })
                        .flatMap(new Func1<BluetoothGattCharacteristic, Observable<Observable<byte[]>>>() {
                            @Override
                            public Observable<Observable<byte[]>> call(BluetoothGattCharacteristic bluetoothGattCharacteristic) {
                                return rxBleConnection.setupNotification(bluetoothGattCharacteristic);
                            }
                        }, new Func2<BluetoothGattCharacteristic, Observable<byte[]>, Pair<BluetoothGattCharacteristic, Observable<byte[]>>>() {
                            @Override
                            public Pair<BluetoothGattCharacteristic, Observable<byte[]>> call(BluetoothGattCharacteristic bluetoothGattCharacteristic, Observable<byte[]> observable) {
                                return new Pair<BluetoothGattCharacteristic, Observable<byte[]>>(bluetoothGattCharacteristic, observable);
                            }
                        })
                        .flatMap(new Func1<Pair<BluetoothGattCharacteristic, Observable<byte[]>>, Observable<byte[]>>() {
                            @Override
                            public Observable<byte[]> call(Pair<BluetoothGattCharacteristic, Observable<byte[]>> bluetoothGattCharacteristicObservablePair) {
                                return bluetoothGattCharacteristicObservablePair.second;
                            }
                        }, new Func2<Pair<BluetoothGattCharacteristic, Observable<byte[]>>, byte[], Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public Pair<BluetoothGattCharacteristic, byte[]> call(Pair<BluetoothGattCharacteristic, Observable<byte[]>> bluetoothGattCharacteristicObservablePair, byte[] bytes) {
                                return new Pair<BluetoothGattCharacteristic, byte[]>(bluetoothGattCharacteristicObservablePair.first, bytes);
                            }
                        })
                        .doOnUnsubscribe(new Action0() {
                            @Override
                            public void call() {
                                transactions.removeSubscription(transactionId);
                                // sendError(callbackContext, BleError.cancelled().toJS(), false);
                            }
                        })
                        .subscribe(new Observer<Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public void onCompleted() {
                                transactions.removeSubscription(transactionId);
                            }

                            @Override
                            public void onError(Throwable e) {
                                transactions.removeSubscription(transactionId);
                                sendError(callbackContext, errorConverter.toError(e).toJS(), false);
                            }

                            @Override
                            public void onNext(Pair<BluetoothGattCharacteristic, byte[]> result) {
                                try {
                                    JSONObject jsObject = characteristicConverter.toJSObject(result.first);
                                    jsObject.put("deviceUUID", deviceId);
                                    jsObject.put("serviceUUID", serviceUUID);
                                    jsObject.put("value", Base64.encodeToString(result.second, Base64.DEFAULT));
                                    sendSuccess(callbackContext, jsObject, false);
                                } catch(JSONException jsonEx) {
                                    // ignored !!
                                }
                            }
                        });

                transactions.replaceSubscription(transactionId, subscription);

            }
        });

    }

    private void monitorCharacteristic(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final String deviceId = args.getString(0);
        final String serviceUUIDStr = args.getString(1);
        final String charUUIDStr = args.getString(2);
        final String transactionId = args.getString(3);

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

        final UUID[] UUIDs = UUIDConverter.convert(serviceUUIDStr, charUUIDStr);
        if (UUIDs == null) {
            sendError(callbackContext, BleError.invalidUUIDs(serviceUUIDStr, charUUIDStr).toJS(), false);
            return;
        }

        final UUID serviceUUID = UUIDs[0];
        final UUID charUUID = UUIDs[1];

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

                final Subscription subscription = rxBleConnection.discoverServices()
                        .flatMap(new Func1<RxBleDeviceServices, Observable<BluetoothGattCharacteristic>>() {
                            @Override
                            public Observable<BluetoothGattCharacteristic> call(RxBleDeviceServices rxBleDeviceServices) {
                                return rxBleDeviceServices.getCharacteristic(serviceUUID, charUUID);
                            }
                        })
                        .flatMap(new Func1<BluetoothGattCharacteristic, Observable<Observable<byte[]>>>() {
                            @Override
                            public Observable<Observable<byte[]>> call(BluetoothGattCharacteristic bluetoothGattCharacteristic) {
                                return rxBleConnection.setupNotification(bluetoothGattCharacteristic);
                            }
                        }, new Func2<BluetoothGattCharacteristic, Observable<byte[]>, Pair<BluetoothGattCharacteristic, Observable<byte[]>>>() {
                            @Override
                            public Pair<BluetoothGattCharacteristic, Observable<byte[]>> call(BluetoothGattCharacteristic bluetoothGattCharacteristic, Observable<byte[]> observable) {
                                return new Pair<BluetoothGattCharacteristic, Observable<byte[]>>(bluetoothGattCharacteristic, observable);
                            }
                        })
                        .flatMap(new Func1<Pair<BluetoothGattCharacteristic, Observable<byte[]>>, Observable<byte[]>>() {
                            @Override
                            public Observable<byte[]> call(Pair<BluetoothGattCharacteristic, Observable<byte[]>> bluetoothGattCharacteristicObservablePair) {
                                return bluetoothGattCharacteristicObservablePair.second;
                            }
                        }, new Func2<Pair<BluetoothGattCharacteristic, Observable<byte[]>>, byte[], Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public Pair<BluetoothGattCharacteristic, byte[]> call(Pair<BluetoothGattCharacteristic, Observable<byte[]>> bluetoothGattCharacteristicObservablePair, byte[] bytes) {
                                return new Pair<BluetoothGattCharacteristic, byte[]>(bluetoothGattCharacteristicObservablePair.first, bytes);
                            }
                        })
                        .doOnUnsubscribe(new Action0() {
                            @Override
                            public void call() {
                                removeNotification(UUIDConverter.fromUUID(charUUID), transactionId);
                                // sendError(callbackContext, BleError.cancelled().toJS(), false);
                            }
                        })
                        .subscribe(new Observer<Pair<BluetoothGattCharacteristic, byte[]>>() {
                            @Override
                            public void onCompleted() {
                                removeNotification(UUIDConverter.fromUUID(charUUID), transactionId);
                            }

                            @Override
                            public void onError(Throwable e) {
                                removeNotification(UUIDConverter.fromUUID(charUUID), transactionId);
                                sendError(callbackContext, errorConverter.toError(e).toJS(), false);
                            }

                            @Override
                            public void onNext(Pair<BluetoothGattCharacteristic, byte[]> result) {
                                sendNotification(
                                        device.getMacAddress(),
                                        UUIDConverter.fromUUID(serviceUUID),
                                        UUIDConverter.fromUUID(charUUID),
                                        result.second,
                                        transactionId,
                                        result.first,
                                        callbackContext);
                            }
                        });

                transactions.replaceSubscription(transactionId, subscription);

            }
        });

    }

    private void sendNotification(final String deviceId,
                                  final String serviceUUID,
                                  final String characteristicUUID,
                                  final byte[] value,
                                  final String transactionId,
                                  final BluetoothGattCharacteristic characteristic,
                                  final CallbackContext callbackContext) {

        synchronized (notificationMap) {
            String id = notificationMap.get(characteristicUUID);
            if (id == null || id.equals(transactionId)) {
                notificationMap.put(characteristicUUID, transactionId);

                try {
                    JSONObject jsObject = characteristicConverter.toJSObject(characteristic);
                    jsObject.put("deviceUUID", deviceId);
                    jsObject.put("serviceUUID", serviceUUID);
                    jsObject.put("value", Base64.encodeToString(value, Base64.DEFAULT));
                    sendSuccess(callbackContext, jsObject, true);
                } catch(JSONException jsonEx) {
                    // ignored !!
                }
            }
        }
    }

    private void removeNotification(final String characteristicUUID,
                                    final String transactionId) {
        synchronized (notificationMap) {
            String id = notificationMap.get(characteristicUUID);
            if (id != null && id.equals(transactionId)) {
                notificationMap.remove(characteristicUUID);
            }
        }
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

    public void discoverCharacteristics(final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        final String deviceId = args.getString(0);
        final String serviceUUIDStr = args.getString(1);

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

        final UUID serviceUUID = UUIDConverter.convert(serviceUUIDStr);
        if (serviceUUID == null) {
            sendError(callbackContext, BleError.invalidUUIDs(serviceUUIDStr).toJS(), false);
            return;
        }

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {


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
                                BluetoothGattService foundService = null;
                                for (BluetoothGattService service : bluetoothGattServices.getBluetoothGattServices()) {
                                    if (service.getUuid().equals(serviceUUID)) {
                                        foundService = service;
                                        break;
                                    }
                                }

                                if (foundService == null) {
                                    sendError(callbackContext, BleError.serviceNotFound(serviceUUIDStr).toJS(), false);
                                    return;
                                }

                                try {

                                    JSONArray jsCharacteristics = new JSONArray();
                                    for (BluetoothGattCharacteristic characteristic : foundService.getCharacteristics()) {
                                        JSONObject value = characteristicConverter.toJSObject(characteristic);
                                        value.put("deviceUUID", device.getMacAddress());
                                        value.put("serviceUUID", UUIDConverter.fromUUID(serviceUUID));
                                        jsCharacteristics.put(value);
                                    }
                                    sendSuccess(callbackContext, jsCharacteristics, false);

                                } catch (JSONException jsex) {
                                    // ignored !!
                                }
                            }
                        });
            }});

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

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {

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
                                } catch (JSONException ex) {
                                    // ignored !
                                }
                            }
                        });

            }});
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
