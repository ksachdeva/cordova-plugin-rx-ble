package com.ksachdeva.opensource.ble.central.utils;

import org.json.JSONArray;

import java.util.UUID;

public class UUIDConverter {

    static public UUID convert(String sUUID) {
        try {
            return UUID.fromString(sUUID);
        } catch (Throwable e) {
            return null;
        }
    }

    static public UUID[] convert(JSONArray sUUIDs) {
        UUID[] UUIDs = new UUID[sUUIDs.length()];
        for (int i = 0; i < sUUIDs.length(); i++) {
            try {
                UUIDs[i] = UUID.fromString(sUUIDs.getString(i));
            } catch (Throwable e) {
                return null;
            }
        }
        return UUIDs;
    }

    static public UUID[] convert(String... sUUIDs) {
        UUID[] UUIDs = new UUID[sUUIDs.length];
        for (int i = 0; i < sUUIDs.length; i++) {
            try {
                UUIDs[i] = UUID.fromString(sUUIDs[i]);
            } catch (Throwable e) {
                return null;
            }
        }
        return UUIDs;
    }

    static public String fromUUID(UUID uuid) {
        return uuid.toString().toLowerCase();
    }
}
