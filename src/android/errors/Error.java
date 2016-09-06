package com.ksachdeva.opensource.ble.central.errors;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

public class Error {
    private int code;
    private String message;
    private boolean isCancelled;

    public Error(String message, int code,  boolean isCancelled) {
        this.code = code;
        this.message = message;
        this.isCancelled = isCancelled;
    }

    public Error(String message, int code) {
        this.code = code;
        this.message = message;
        this.isCancelled = false;
    }

    public JSONObject toJS() {
        JSONObject error = new JSONObject();

        try {
            error.put("code", code);
            error.put("message", message);
            if (isCancelled) {
                error.put("isCancelled", true);
            }
        } catch(JSONException ex) {
            // ignored !!
        }

        return error;
    }
}

