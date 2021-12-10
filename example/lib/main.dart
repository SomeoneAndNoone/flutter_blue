// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_blue/flutter_blue.dart';
import 'package:flutter_blue_example/widgets.dart';
import 'package:rxdart/rxdart.dart';

void main() {
  runApp(FlutterBlueApp());
}

class FlutterBlueApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      color: Colors.lightBlue,
      home: StreamBuilder<BluetoothState>(
          stream: FlutterBlue.instance.state,
          initialData: BluetoothState.unknown,
          builder: (c, snapshot) {
            final state = snapshot.data;
            if (state == BluetoothState.on) {
              return FindDevicesScreen();
            }
            return BluetoothOffScreen(state: state);
          }),
    );
  }
}

class BluetoothOffScreen extends StatelessWidget {
  const BluetoothOffScreen({Key? key, this.state}) : super(key: key);

  final BluetoothState? state;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.lightBlue,
      appBar: AppBar(
        title: Text('Bluetooth'),
        actions: [
          ElevatedButton(
            onPressed: () async {
              bool result = await FlutterBlue.instance.enableAdapter();
              print('enable adapter result: $result');
            },
            child: Text('enable'),
          ),
          ElevatedButton(
            onPressed: () async {
              bool result = await FlutterBlue.instance.disableAdapter();
              print('disable adapter result: $result');
            },
            child: Text('disable'),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(
              Icons.bluetooth_disabled,
              size: 200.0,
              color: Colors.white54,
            ),
            Text(
              'Bluetooth Adapter is ${state != null ? state.toString().substring(15) : 'not available'}.',
              style: Theme.of(context).primaryTextTheme.headline1?.copyWith(color: Colors.white),
            ),
          ],
        ),
      ),
    );
  }
}

class FindDevicesScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    List<String> filteredNames = []; // ['Mi Smart Band 4', 'Soter', 'SoterDFU'];
    List<String> filteredMacAddresses = []; // ['DA:02:DF:7F:71:92'];
    return Scaffold(
      appBar: AppBar(
        title: Text('Find Devices'),
        actions: [
          ElevatedButton(
            onPressed: () async {
              bool result = await FlutterBlue.instance.enableAdapter();
              print('enable adapter result: $result');
            },
            child: Text('enable'),
          ),
          ElevatedButton(
            onPressed: () async {
              bool result = await FlutterBlue.instance.disableAdapter();
              print('disable adapter result: $result');
            },
            child: Text('disable'),
          ),
        ],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 350,
            child: ListView(
              children: <Widget>[
                StreamBuilder<List<ScanResult>>(
                  stream: FlutterBlue.instance.scanResults,
                  initialData: [],
                  builder: (c, snapshot) => Column(
                    children: snapshot.data!.map(
                      (r) {
                        return ScanResultTile(
                          result: r,
                          onTap: () async {
                            // todo khamidjon connect
                            print('CONNECTING TRYING. DEVICE: ${r.device.id.id}');
                            await r.device.disconnect();
                            await r.device.connect(autoConnect: false);
                            print('CONNECTED DEVCIE: ${r.device.id.id}');
                          },
                        );
                      },
                    ).toList(),
                  ),
                ),
              ],
            ),
          ),
          Container(
            width: double.infinity,
            height: 3,
            color: Colors.yellow,
          ),
          SizedBox(
            height: 10,
          ),
          Text(
            'Connected Devices',
            style: TextStyle(
              color: Colors.green,
              fontWeight: FontWeight.bold,
              fontSize: 20,
            ),
          ),
          SizedBox(
            height: 300,
            child: ListView(
              children: [
                StreamBuilder<List<BluetoothDevice>>(
                  stream: Stream.periodic(Duration(seconds: 2)).asyncMap(
                    (_) => FlutterBlue.instance.connectedDevices,
                  ),
                  initialData: [],
                  builder: (c, snapshot) => Column(
                    children: snapshot.data!
                        .map((d) => ListTile(
                              title: Text(d.name),
                              subtitle: Text(d.id.toString()),
                              trailing: StreamBuilder<BluetoothDeviceState>(
                                stream: d.state,
                                initialData: BluetoothDeviceState.disconnected,
                                builder: (c, snapshot) {
                                  if (snapshot.data == BluetoothDeviceState.connected) {
                                    return ElevatedButton(
                                      child: Text('DISCONNECT'),
                                      onPressed: () async {
                                        await d.disconnect();
                                      },
                                    );
                                  }
                                  return Text(snapshot.data.toString());
                                },
                              ),
                            ))
                        .toList(),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      floatingActionButton: StreamBuilder<bool>(
        stream: FlutterBlue.instance.isScanning,
        initialData: false,
        builder: (c, snapshot) {
          if (snapshot.data!) {
            return FloatingActionButton(
              child: Icon(Icons.stop),
              onPressed: () => FlutterBlue.instance.stopScan(),
              backgroundColor: Colors.red,
            );
          } else {
            return FloatingActionButton(
              child: Icon(Icons.search),
              onPressed: () {
                _internalScan(filteredNames, filteredMacAddresses).listen((event) {
                  print('Khamidjon: event came listening: ${event.device.id.id}');
                }).onError((error) {
                  print('Khamidjon: error in listener: $error');
                });
              },
            );
          }
        },
      ),
    );
  }

  Stream<ScanResult> _internalScan(
    List<String> filteredNames,
    List<String> filteredMacAddresses, {
    int scanDepth = 0,
  }) {
    print(
        'Khamidjon: Started scanning, scanDepth: $scanDepth, filterNames: $filteredNames, filterMacs: $filteredMacAddresses');
    return FlutterBlue.instance
        .scan(
      timeout: Duration(hours: 24),
      filterNames: filteredNames,
      filterMacAddresses: filteredMacAddresses,
    )
        .map((event) {
      print('Khamidjon: new result in map: ${event.device.id}');
      return event;
    }).switchMap((ScanResult result) async* {
      print('Khamidjon: got result: ${result.device.id.id}');
      if (result.isEmptyScanResultError) {
        print('EMPTY SCAN RESULT ERROR');
      }
      if (result.isScanError) {
        print('Khamidjon: ERROR: code: ${result.errorCode}');
      }
      if (result.isScanError) {
        print('Khamidjon: scan depth: $scanDepth');
        if (scanDepth > 3) {
          print('Khamidjon: returning error: scanDepth: $scanDepth');
          yield* Stream<ScanResult>.error(Exception('Khamidjon: scanDepth: $scanDepth'));
        } else {
          print('Khamidjon: stopping scan');
          FlutterBlue.instance.stopScan();
          print('Khamidjon: restarting adapter');
          await Future.delayed(Duration(seconds: 1));
          FlutterBlue.instance.disableAdapter();
          await Future.delayed(Duration(seconds: 1));
          FlutterBlue.instance.enableAdapter();
          await Future.delayed(Duration(seconds: 5));
          print('Khamidjon: starting scan');
          yield* _internalScan(filteredNames, filteredMacAddresses, scanDepth: scanDepth + 1);
        }
      } else {
        yield result;
      }
    }).doOnError((object, stackTrace) {
      print('Khamidjon: Stream error: $object');
    });
  }
}
