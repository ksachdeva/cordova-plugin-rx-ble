package com.ksachdeva.opensource.ble.central.converters;

import android.bluetooth.BluetoothGattService;

import org.json.JSONException;
import org.json.JSONObject;

public class BluetoothGattServiceConverter {
    private interface Metadata {
        String UUID = "uuid";
        String IS_PRIMARY = "isPrimary";
    }

    public JSONObject toJSObject(BluetoothGattService value) {
        JSONObject result = new JSONObject();
        try {
            result.put(Metadata.UUID, value.getUuid().toString());
            result.put(Metadata.IS_PRIMARY, value.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY);
        }catch(JSONException ex) {
            // ignored !
        }
        return result;
    }
}
