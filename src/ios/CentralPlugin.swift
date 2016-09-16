
import Foundation
import CoreBluetooth
import RxBluetoothKit
import RxSwift

@objc(CentralPlugin) class CentralPlugin: CDVPlugin {

  private var manager: BluetoothManager!;
  private var connectedDevices: Dictionary<String, Peripheral>!;
  private var monitoredCharacteristics: Dictionary<CBUUID, Observable<Characteristic>>!;

  // Disposables
  private var disposeBag: DisposeBag!;
  private var scanSubscription: SerialDisposable!;
  private var connectingDevices: DisposableMap!;
  private var transactions: DisposableMap!;

  // callback contextx
  private var monitorDeviceCallbackId: String!;
  private var monitorStateCallbackId: String!;

  override func pluginInitialize() {
    manager = BluetoothManager();
    disposeBag = DisposeBag();
    scanSubscription = SerialDisposable();
    connectingDevices = DisposableMap();
    transactions = DisposableMap();
    connectedDevices = Dictionary<String, Peripheral>();
    monitoredCharacteristics = Dictionary<CBUUID, Observable<Characteristic>>();

    disposeBag.addDisposable(manager.rx_state.subscribeNext { [weak self] newState in
      self?.onStateChange(newState)
    })
  }

  private func onStateChange(state: BluetoothState) {
    if (self.monitorStateCallbackId != nil) {
      let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsString: state.asJSObject);
      pluginResult.setKeepCallbackAsBool(true);
      commandDelegate.sendPluginResult(pluginResult, callbackId: self.monitorStateCallbackId);
    }
  }

  func getState(command: CDVInvokedUrlCommand) {
    sendSuccess(command, result: manager.state.asJSObject, keepCallback: false);
  }

  func monitorState(command: CDVInvokedUrlCommand) {
    self.monitorStateCallbackId = command.callbackId;
  }

  func startDeviceScan(command: CDVInvokedUrlCommand) {

    let uuids: [CBUUID]? = nil;

    scanSubscription.disposable = manager.scanForPeripherals(uuids, options: nil)
      .subscribe(onNext: { [weak self] scannedPeripheral in
        self?.sendSuccessWithDictionary(command, result: scannedPeripheral.asJSObject, keepCallback: true)
        }, onError: { [weak self] errorType in
        self?.sendErrorWithDictionary(command, result: errorType.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false)
    })
  }

  func stopScan(command: CDVInvokedUrlCommand) {
    scanSubscription.disposable = NopDisposable.instance;
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func monitorDeviceDisconnect(command: CDVInvokedUrlCommand) {
    self.monitorDeviceCallbackId = command.callbackId;
  }

  func connectToDevice(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;

    guard let nsuuid = NSUUID(UUIDString: deviceIdentifier) else {
      self.sendErrorWithDictionary(command, result: BleError.invalidUUID(deviceIdentifier).toJS as! Dictionary<String, AnyObject>, keepCallback: false)
      return
    }

    let connectionDisposable = manager.retrievePeripheralsWithIdentifiers([nsuuid])
      .flatMap { devices -> Observable<Peripheral> in
        guard let device = devices.first else {
          return Observable.error(BleError.peripheralNotFound(deviceIdentifier))
        }
        return Observable.just(device)
    }
      .flatMap { $0.connect() }
      .subscribe(
        onNext: { [weak self] peripheral in
          self?.connectedDevices[deviceIdentifier] = peripheral
        },
        onError: { error in
          self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false)
        },
        onCompleted: { [weak self] in
          if let device = self?.connectedDevices[deviceIdentifier] {
            _ = self?.manager.monitorPeripheralDisconnection(device)
              .take(1)
              .subscribeNext { peripheral in
                self?.onDeviceDisconnected(peripheral)
            }
            self?.sendSuccessWithDictionary(command, result: device.asJSObject, keepCallback: true)
          } else {
            self?.sendErrorWithDictionary(command, result: BleError.peripheralNotFound(deviceIdentifier).toJS as! Dictionary<String, AnyObject>, keepCallback: false)
          }
        },
        onDisposed: { [weak self] in
          self?.connectingDevices.removeDisposable(deviceIdentifier)
        }
    );

    // TODO: Call reject when cancelled.
    connectingDevices.replaceDisposable(deviceIdentifier, disposable: connectionDisposable)
  }

  func disconnectDevice(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;

    if let device = connectedDevices[deviceIdentifier] {
      _ = device.cancelConnection()
        .subscribe(
          onNext: nil,
          onError: { error in
            self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          },
          onCompleted: {
            self.sendSuccessWithDictionary(command, result: device.asJSObject, keepCallback: false);
          },
          onDisposed: { [weak self] in
            self?.connectedDevices[deviceIdentifier] = nil;
          }
      );
    } else {
      connectingDevices.removeDisposable(deviceIdentifier);
      self.sendErrorWithDictionary(command, result: BleError.cancelled().toJS as! Dictionary<String, AnyObject>, keepCallback: false);
    }
  }

  func isDeviceConnected(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;

    if let device = connectedDevices[deviceIdentifier] {
      self.sendSuccessWithBoolean(command, result: device.isConnected, keepCallback: false);
    } else {
      self.sendSuccessWithBoolean(command, result: false, keepCallback: false);
    }
  }

  func discoverServices(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;

    guard let device = connectedDevices[deviceIdentifier] else {
      self.sendErrorWithDictionary(command, result: BleError.peripheralNotConnected(deviceIdentifier).toJS as! Dictionary<String, AnyObject>, keepCallback: false);
      return;
    }

    _ = Observable.from(device.discoverServices(nil))
      .subscribe(
        onNext: nil,
        onError: { error in
          self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
        },
        onCompleted: {
          let services = device.services?.map { $0.asJSObject } ?? [];
          self.sendSuccessWithArray(command, result: services, keepCallback: false);
        }
    )

  }

  func discoverCharacteristics(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;
    let serviceUUID = command.argumentAtIndex(1) as! String;

    guard let device = connectedDevices[deviceIdentifier] else {
      self.sendErrorWithDictionary(command, result: BleError.peripheralNotConnected(deviceIdentifier).toJS as! Dictionary<String, AnyObject>, keepCallback: false);
      return;
    }

    let services = device.services?.filter {
      if let uuid = serviceUUID.toCBUUID() {
        return uuid == $0.UUID
      }
      return false
    } ?? [];

    if (services.count > 0) {

      let service = services.first;

      _ = service?.discoverCharacteristics(nil)
        .subscribe(onNext: nil,
          onError: { error in
            self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          },
          onCompleted: {
            let chars = service?.characteristics?.map { $0.asJSObject } ?? [];
            self.sendSuccessWithArray(command, result: chars, keepCallback: false);
      })

    } else {
      self.sendSuccessWithArray(command, result: [], keepCallback: false);
    }

  }

  func cancelTransaction(command: CDVInvokedUrlCommand) {
    let transactionId = command.argumentAtIndex(0) as! String;
    transactions.removeDisposable(transactionId);
    self.sendSuccessWithBoolean(command, result: true, keepCallback: false);
  }

  func monitorCharacteristic(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;
    let serviceUUID = command.argumentAtIndex(1) as! String;
    let characteristicUUID = command.argumentAtIndex(2) as! String;
    let transactionId = command.argumentAtIndex(3) as! String;

    guard let uuid = characteristicUUID.toCBUUID() else {
      self.sendErrorWithDictionary(command, result: BleError.invalidUUID(characteristicUUID).toJS as! Dictionary<String, AnyObject>, keepCallback: false);
      return
    }

    let observable: Observable<Characteristic>

    if let monitoringObservable = monitoredCharacteristics[uuid] {
      observable = monitoringObservable
    } else {
      observable = characteristicObservable(deviceIdentifier,
        serviceUUID: serviceUUID,
        characteristicUUID: characteristicUUID)
        .flatMap { [weak self] characteristic -> Observable<Characteristic> in
          return Observable.using({
            return AnonymousDisposable {
              _ = characteristic.setNotifyValue(false).subscribe();
              self?.monitoredCharacteristics[uuid] = nil;
            }
            }, observableFactory: { _ in
            return characteristic.setNotificationAndMonitorUpdates();
          })
      }
        .doOn(onNext: { [weak self] characteristic in
          self?.sendSuccessWithDictionary(command, result: characteristic.asJSObject, keepCallback: true);

          }, onError: { [weak self] error in
          self?.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
      })
        .publish()
        .refCount()

      monitoredCharacteristics[uuid] = observable;
    }

    let disposable = observable.subscribe(onNext: nil, onError: nil, onCompleted: nil, onDisposed: { [weak self] in
      self?.transactions.removeDisposable(transactionId)
      // resolve(nil)
    })

    transactions.replaceDisposable(transactionId, disposable: disposable)
  }

  func readCharacteristic(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;
    let serviceUUID = command.argumentAtIndex(1) as! String;
    let charUUID = command.argumentAtIndex(2) as! String;
    let transactionId = command.argumentAtIndex(3) as! String;

    var readCharacteristic: Characteristic?
    var finished = false

    let disposable = characteristicObservable(deviceIdentifier,
      serviceUUID: serviceUUID,
      characteristicUUID: charUUID)
      .flatMap { $0.readValue() }
      .subscribe(
        onNext: { characteristic in
          readCharacteristic = characteristic
        },
        onError: { error in
          self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          finished = true
        },
        onCompleted: {
          if let characteristic = readCharacteristic {
            self.sendSuccessWithDictionary(command, result: characteristic.asJSObject, keepCallback: false);

          } else {
            self.sendErrorWithDictionary(command, result: BleError.characteristicNotFound(charUUID).toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          }
          finished = true
        },
        onDisposed: { [weak self] in
          self?.transactions.removeDisposable(transactionId)
          if (!finished) {
            self?.sendErrorWithDictionary(command, result: BleError.cancelled().toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          }
        }
    )

    transactions.replaceDisposable(transactionId, disposable: disposable)

  }

  func writeCharacteristic(command: CDVInvokedUrlCommand) {
    let deviceIdentifier = command.argumentAtIndex(0) as! String;
    let serviceUUID = command.argumentAtIndex(1) as! String;
    let charUUID = command.argumentAtIndex(2) as! String;
    let valueBase64 = command.argumentAtIndex(3) as! String;
    let response = command.argumentAtIndex(4) as! Bool;
    let transactionId = command.argumentAtIndex(5) as! String;

    var writeCharacteristic: Characteristic?;
    var finished = false;

    let disposable = characteristicObservable(deviceIdentifier,
      serviceUUID: serviceUUID,
      characteristicUUID: charUUID)
      .flatMap { characteristic -> Observable<Characteristic> in
        guard let data = NSData(base64EncodedString: valueBase64, options: .IgnoreUnknownCharacters) else {
          return Observable.error(BleError.invalidWriteDataForCharacteristic(
            charUUID, data: valueBase64))
        }
        return characteristic.writeValue(data, type: response ? .WithResponse : .WithoutResponse);
    }
      .subscribe(
        onNext: {
          characteristic in writeCharacteristic = characteristic
        },
        onError: { error in
          self.sendErrorWithDictionary(command, result: error.bleError.toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          finished = true;
        },
        onCompleted: {
          if let characteristic = writeCharacteristic {
            self.sendSuccessWithDictionary(command, result: characteristic.asJSObject, keepCallback: false);
          } else {
            self.sendErrorWithDictionary(command, result: BleError.characteristicNotFound(charUUID).toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          }
          finished = true
        },
        onDisposed: { [weak self] in
          self?.transactions.removeDisposable(transactionId)
          if (!finished) {
            self?.sendErrorWithDictionary(command, result: BleError.cancelled().toJS as! Dictionary<String, AnyObject>, keepCallback: false);
          }
        }
    )

    transactions.replaceDisposable(transactionId, disposable: disposable)

  }

  func onDeviceDisconnected(device: Peripheral) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsDictionary: device.asJSObject);
    pluginResult.setKeepCallbackAsBool(true);
    commandDelegate.sendPluginResult(pluginResult, callbackId: self.monitorDeviceCallbackId);
  }

  private func characteristicObservable(deviceIdentifier: String,
    serviceUUID: String,
    characteristicUUID: String) -> Observable<Characteristic> {
      guard let serviceCBUUID = serviceUUID.toCBUUID(),
        let characteristicCBUUID = characteristicUUID.toCBUUID() else {
          return Observable.error(BleError.invalidUUIDs([serviceUUID, characteristicUUID]))
      }

      return Observable.deferred { [weak self] in
        guard let device = self?.connectedDevices[deviceIdentifier] else {
          return Observable.error(BleError.peripheralNotConnected(deviceIdentifier))
        }

        let characteristics = device.services?
          .filter { serviceCBUUID == $0.UUID }
          .flatMap { $0.characteristics ?? [] }
          .filter { characteristicCBUUID == $0.UUID } ?? []

        guard let characteristic = characteristics.first else {
          return Observable.error(BleError.characteristicNotFound(characteristicUUID))
        }

        return Observable.just(characteristic)
      }
  }

  func sendSuccess(command: CDVInvokedUrlCommand, result: String, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsString: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func sendSuccessWithBoolean(command: CDVInvokedUrlCommand, result: Bool, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsBool: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func sendSuccessWithDictionary(command: CDVInvokedUrlCommand, result: Dictionary<String, AnyObject>, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsDictionary: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func sendSuccessWithArray(command: CDVInvokedUrlCommand, result: Array<Dictionary<String, AnyObject>>, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_OK, messageAsArray: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func sendErrorWithDictionary(command: CDVInvokedUrlCommand, result: Dictionary<String, AnyObject>, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAsDictionary: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

  func sendError(command: CDVInvokedUrlCommand, result: String, keepCallback: Bool) {
    let pluginResult = CDVPluginResult(status: CDVCommandStatus_ERROR, messageAsString: result);
    pluginResult.setKeepCallbackAsBool(keepCallback);
    commandDelegate.sendPluginResult(pluginResult, callbackId: command.callbackId);
  }

}
