'use strict';

var argscheck = require('cordova/argscheck');
var exec = require('cordova/exec');

var PLUGIN_NAME = 'CentralPlugin';
var getValue = argscheck.getValue;

function isNotAcceptable(val) {
    return val === undefined || val === null || val === '';
}

var Central = {

    startDeviceScan: function(options, successCallback, errorCallback) {
        var uuids = null;
        var args = [uuids];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'startDeviceScan', args);
    },

    stopScan: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, PLUGIN_NAME, 'stopScan', []);
    },

    monitorDeviceDisconnect: function(successCallback, errorCallback) {
        exec(successCallback, errorCallback, PLUGIN_NAME, 'monitorDeviceDisconnect', []);
    },

    connectToDevice: function(options, successCallback, errorCallback) {
        var deviceId = getValue(options.deviceId, undefined);

        if (isNotAcceptable(deviceId)) {
            throw new Error('Invalid arguments !');
        }

        var args = [deviceId];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'connectToDevice', args);
    },

    disconnectDevice: function(options, successCallback, errorCallback) {
        var deviceId = getValue(options.deviceId, undefined);

        if (isNotAcceptable(deviceId)) {
            throw new Error('Invalid arguments !');
        }

        var args = [deviceId];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'disconnectDevice', args);
    },

    isDeviceConnected: function(options, successCallback, errorCallback) {
        var deviceId = getValue(options.deviceId, undefined);

        if (isNotAcceptable(deviceId)) {
            throw new Error('Invalid arguments !');
        }

        var args = [deviceId];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'isDeviceConnected', args);
    },

    discoverServices: function(options, successCallback, errorCallback) {
        var deviceId = getValue(options.deviceId, undefined);

        if (isNotAcceptable(deviceId)) {
            throw new Error('Invalid arguments !');
        }

        var args = [deviceId];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'discoverServices', args);
    },

    discoverCharacteristics: function(options, successCallback, errorCallback) {
        var deviceId = getValue(options.deviceId, undefined);
        var serviceUUID = getValue(options.serviceUUID, undefined);

        console.log(deviceId);
        console.log(serviceUUID);

        if (isNotAcceptable(deviceId) || isNotAcceptable(serviceUUID)) {
            throw new Error('Invalid arguments !');
        }

        var args = [deviceId, serviceUUID];
        exec(successCallback, errorCallback, PLUGIN_NAME, 'discoverCharacteristics', args);
    }
};

module.exports = Central;
