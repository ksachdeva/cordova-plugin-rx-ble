package com.ksachdeva.opensource.ble.central.converters;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import com.polidea.rxandroidble.RxBleScanResult;

public class RxBleScanResultConverter {

    interface Metadata {
        String UUID = "uuid";
        String NAME = "name";
        String RSSI = "rssi";
        String CONNECTABLE = "isConnectable";
    }

    public JSONObject toJSObject(RxBleScanResult value) {
        JSONObject result = new JSONObject();

        try {

            result.put(Metadata.UUID, value.getBleDevice().getMacAddress());
            result.put(Metadata.NAME, value.getBleDevice().getName());
            result.put(Metadata.RSSI, value.getRssi());
            // TODO: Implement
            result.put(Metadata.CONNECTABLE, null);

        } catch(JSONException ex) {
            // ignored !! Damm checked exceptions !!
        }

        return result;
    }

}
