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
import android.content.res.Configuration;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Created by bjoernquentin on 08.11.15.
 */
public class SetLocaleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String lang = getIntent().getStringExtra("language");
        String country = getIntent().getStringExtra("country");
        SetLocaleActivity.updateLocale(new Locale(lang, country));
        finish();
    }


    public static void updateLocale(Locale locale) {
        try {
            System.out.println("in");
            Method activityManagerNativegetDefaultMethod = Class.forName("android.app.ActivityManagerNative").getDeclaredMethod("getDefault");
            Object am = activityManagerNativegetDefaultMethod.invoke(null);

            Configuration config = (Configuration) am.getClass().getDeclaredMethod("getConfiguration").invoke(am);

            config.locale = locale;

            // indicate this isn't some passing default - the user wants this remembered
            Field userSetLocaleField = Configuration.class.getDeclaredField("userSetLocale");
            userSetLocaleField.set(config, true);

            am.getClass().getDeclaredMethod("updateConfiguration", Configuration.class).invoke(am, config);
            // Trigger the dirty bit for the Settings Provider.
            // BackupManager.dataChanged("com.android.providers.settings");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
