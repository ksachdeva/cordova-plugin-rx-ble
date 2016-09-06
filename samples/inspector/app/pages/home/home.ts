import * as _ from 'lodash';
import { Observable } from 'rxjs';
import { Component, NgZone } from '@angular/core';
import { NavController } from 'ionic-angular';
import { Diagnostic } from 'ionic-native';

import { Central } from './../../plugin';

@Component({
  templateUrl: 'build/pages/home/home.html'
})
export class HomePage {

  devices: Array<any>;

  constructor(public navCtrl: NavController, private ngZone: NgZone) {
    this.devices = [];
  }

  startScan() {

    Diagnostic.requestRuntimePermission(Diagnostic.permission.ACCESS_COARSE_LOCATION).then(() => {

      Central.startDeviceScan(null).subscribe((result) => {
        // console.log(result);

        this.ngZone.run(() => {
          // if we already have the device with the same id then simply update
          // its rssi
          const device = _.find(this.devices, (d) => result.uuid === d.uuid);

          if (device === undefined) {
            // console.log('pushing ..');
            this.devices.push(result);
          } else {
            // console.log('found it already thre ..');
            device.rssi = result.rssi;
          }
        });
      },
        (error) => console.error(error),
        () => console.log('completed'));

    }).catch(error => console.error(error));
  }

  stopScan() {
    Central.stopScan().then((result) => console.log(result))
      .catch(error => console.error(error));
  }

  connectToDevice(deviceId: string) {
    Central.connectToDevice({
      deviceId
    }).then((result) => console.log(result))
      .catch(error => console.error(error));
  }
}
