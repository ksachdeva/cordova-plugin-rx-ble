package com.ksachdeva.opensource.ble.central.converters;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import com.polidea.rxandroidble.RxBleDevice;

public class RxBleDeviceConverter  {

    private interface Metadata {
        String UUID = "uuid";
        String NAME = "name";
        String RSSI = "rssi";
        String CONNECTABLE = "isConnectable";
    }

    public JSONObject toJSObject(RxBleDevice value) {
        JSONObject result = new JSONObject();

        try {

            result.put(Metadata.UUID, value.getMacAddress());
            result.put(Metadata.NAME, value.getName());
            result.put(Metadata.RSSI, null);
            // TODO: Get if it's connectable?
            result.put(Metadata.CONNECTABLE, null);

        }catch(JSONException jsoEx) {
            // ignored !!
        }

        return result;
    }
}
