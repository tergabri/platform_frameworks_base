/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.midi;

import android.midi.IMidiDeviceServer;
import android.midi.IMidiListener;
import android.midi.MidiDeviceInfo;
import android.os.Bundle;
import android.os.IBinder;

/** @hide */
interface IMidiManager
{
    MidiDeviceInfo[] getDeviceList();

    // for device creation & removal notifications
    void registerListener(IBinder token, in IMidiListener listener);
    void unregisterListener(IBinder token, in IMidiListener listener);

    // for communicating with MIDI devices
    IMidiDeviceServer openDevice(IBinder token, in MidiDeviceInfo device);

    // for implementing virtual MIDI devices
    MidiDeviceInfo registerDeviceServer(in IMidiDeviceServer server, int numInputPorts,
            int numOutputPorts, in Bundle properties, boolean isPrivate, int type);
    void unregisterDeviceServer(in IMidiDeviceServer server);
}
