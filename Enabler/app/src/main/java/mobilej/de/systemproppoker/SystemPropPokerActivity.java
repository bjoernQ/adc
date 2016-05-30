/*
 *    Copyright (C) 2016 Björn Quentin
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package mobilej.de.systemproppoker;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Created by bjoernquentin on 08.11.15.
 */
public class SystemPropPokerActivity extends Activity {

    private static int SYSPROPS_TRANSACTION = ('_' << 24) | ('S' << 16) | ('P' << 8) | 'R';
    private static final String TAG = "poker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new SystemPropPoker().execute();
        finish();
    }

    static class SystemPropPoker extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            try {
                String[] services;
                try {
                    Method listServicesMethod = Class.forName("android.os.ServiceManager").getDeclaredMethod("listServices");
                    services = (String[]) listServicesMethod.invoke(null);
                } catch (Exception e) {
                    return null;
                }

                if (services == null || services.length == 0) {
                    services = new String[]{"phone", "iphonesubinfo", "simphonebook", "isms", "media_router", "print", "assetatlas", "dreams", "commontime_management", "samplingprofiler", "diskstats", "appwidget", "backup", "uimode", "serial", "usb", "audio", "wallpaper", "dropbox", "search", "country_detector", "location", "notification", "updatelock", "servicediscovery", "connectivity", "wifi", "wifip2p", "netpolicy", "netstats", "textservices", "network_management", "clipboard", "statusbar", "device_policy", "lock_settings", "mount", "accessibility", "input_method", "input", "window", "alarm", "consumer_ir", "vibrator", "battery", "hardware", "content", "account", "user", "entropy", "permission", "cpuinfo", "dbinfo", "gfxinfo", "meminfo", "procstats", "activity", "package", "scheduling_policy", "telephony.registry", "display", "appops", "usagestats", "batterystats", "power", "sensorservice", "batterypropreg", "media.audio_policy", "media.camera", "media.player", "media.audio_flinger", "drm.drmManager", "SurfaceFlinger", "android.security.keystore"}; // TODO find the right services ...
                }

                for (String service : services) {
                    Method checkServiceMethod = Class.forName("android.os.ServiceManager").getDeclaredMethod("checkService", String.class);
                    IBinder obj = (IBinder) checkServiceMethod.invoke(null, service);
                    if (obj != null) {
                        Parcel data = Parcel.obtain();
                        try {
                            obj.transact(SYSPROPS_TRANSACTION, data, null, 0);
                        } catch (RemoteException e) {
                        } catch (Exception e) {
                            Log.i(TAG, "Someone wrote a bad service '" + service
                                    + "' that doesn't like to be poked: " + e);
                        }
                        data.recycle();
                    }
                }

            } catch (Exception e) {
                return null;
            }

            return null;
        }
    }
}
