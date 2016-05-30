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

package de.mobilej.plugin.adc;

import com.android.ddmlib.*;
import com.android.tools.idea.ddms.EdtExecutor;
import com.android.tools.idea.ddms.adb.AdbService;
import com.android.tools.idea.monitor.AndroidToolWindowFactory;
import com.google.common.io.LineReader;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.ResourceBundle;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

/**
 * Android Device Controller Plugin for Android Studio
 *
 */
public class ToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {

    private static ResourceBundle resourceBundle = ResourceBundle.getBundle("de.mobilej.plugin.adc.Plugin");

    private static LocaleData[] LOCALES;

    static {
        ArrayList<LocaleData> data = new ArrayList<>();
        LineReader lr = null;
        try {
            InputStream is = ToolWindowFactory.class.getResourceAsStream("/de/mobilej/plugin/adc/locales.txt");
            lr = new LineReader(new InputStreamReader(is));
            String line = null;
            while ((line = lr.readLine()) != null) {
                if(line.indexOf("_")>0 && line.indexOf("[")>0) {
                    String lang = line.substring(0, line.indexOf("_"));
                    String cntry = line.substring(line.indexOf("_") + 1, line.indexOf(" "));
                    String desc = line.substring(line.indexOf("[") + 1, line.length() - 1);
                    LocaleData ld = new LocaleData(desc, lang, cntry);
                    data.add(ld);
                }
            }
        } catch(IOException ioe){
            // ignored
        }
        LOCALES = data.toArray(new LocaleData[data.size()]);
    }

    private ComboBox devices;
    private AndroidDebugBridge adBridge;
    private JButton inputOnDeviceButton;


    private class StringShellOutputReceiver implements IShellOutputReceiver {
        private StringBuffer result = new StringBuffer();

        void reset() {
            result.delete(0,result.length());
        }

        String getResult(){
            return result.toString();
        }

        @Override
        public void addOutput(byte[] bytes, int i, int i1) {
            result.append(new String(bytes,i,i1));
        }

        @Override
        public void flush() {

        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    private StringShellOutputReceiver rcv = new StringShellOutputReceiver();

    private AndroidDebugBridge.IDeviceChangeListener deviceChangeListener = new AndroidDebugBridge.IDeviceChangeListener() {
        @Override
        public void deviceConnected(IDevice iDevice) {
            updateDeviceComboBox();
        }

        @Override
        public void deviceDisconnected(IDevice iDevice) {
            updateDeviceComboBox();
        }

        @Override
        public void deviceChanged(IDevice iDevice, int i) {
        }
    };

    private ActionListener deviceSelectedListener = e -> updateFromDevice();

    private JBCheckBox showLayoutBounds;
    private ComboBox localeCHooser;
    private boolean userAction = false;
    private JButton goToActivityButton;

    public ToolWindowFactory() {
    }

    // Create the tool window content.
    public void createToolWindowContent(@NotNull final Project project, @NotNull final ToolWindow toolWindow) {
        final File adb = AndroidSdkUtils.getAdb(project);
        if (adb == null) {
            return;
        }

        ListenableFuture<AndroidDebugBridge> future = AdbService.getInstance().getDebugBridge(adb);
        Futures.addCallback(future, new FutureCallback<AndroidDebugBridge>() {
            @Override
            public void onSuccess(@Nullable AndroidDebugBridge bridge) {
                ToolWindowFactory.this.adBridge = bridge;
                Logger.getInstance(AndroidToolWindowFactory.class).info("Successfully obtained debug bridge");

                AndroidDebugBridge.addDeviceChangeListener(deviceChangeListener);

                devices.removeActionListener(deviceSelectedListener);
                updateDeviceComboBox();
                devices.addActionListener(deviceSelectedListener);

            }

            @Override
            public void onFailure(@NotNull Throwable t) {
                // If we cannot connect to ADB in a reasonable amount of time (10 seconds timeout in AdbService), then something is seriously
                // wrong. The only identified reason so far is that some machines have incompatible versions of adb that were already running.
                // e.g. Genymotion, some HTC flashing software, Ubuntu's adb package may all conflict with the version of adb in the SDK.
                Logger.getInstance(AndroidToolWindowFactory.class).info("Unable to obtain debug bridge", t);
                String msg = MessageFormat.format(resourceBundle.getString("error.message.adb"), adb.getAbsolutePath());
                Messages.showErrorDialog(msg, resourceBundle.getString("error.title.adb"));
            }
        }, EdtExecutor.INSTANCE);


        ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();

        // Create Panel and Content
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_START;

        devices = new ComboBox(new String[]{resourceBundle.getString("device.none")});

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Device"), c);
        c.gridx = 1;
        c.gridy = 0;
        panel.add(devices, c);


        showLayoutBounds = new JBCheckBox(resourceBundle.getString("show.layout.bounds"));
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        panel.add(showLayoutBounds, c);

        showLayoutBounds.addActionListener(e -> {
            final String what = showLayoutBounds.isSelected() ? "true" : "\"\"";
            final String cmd = "setprop debug.layout " + what;

            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                userAction = true;
                executeShellCommand(cmd, true);
                userAction = false;
            }, resourceBundle.getString("setting.values.title"), false, null);
        });


        localeCHooser = new ComboBox(LOCALES);
        c.gridx=0;
        c.gridy=2;
        c.gridwidth=2;
        panel.add(localeCHooser, c);

        localeCHooser.addActionListener(e -> {
            final LocaleData ld = (LocaleData) localeCHooser.getSelectedItem();
            if(ld==null){
                return;
            }

            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                userAction = true;
                executeShellCommand("am start -a SETMYLOCALE --es language "+ld.language+" --es country "+ld.county,false);
                userAction = false;
            }, resourceBundle.getString("setting.values.title"), false, null);
        });


        goToActivityButton = new JButton(resourceBundle.getString("button.goto_activity"));
        c.gridx=0;
        c.gridy=3;
        c.gridwidth=2;
        panel.add(goToActivityButton, c);

        goToActivityButton.addActionListener(e -> ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
            userAction = true;
            final String result = executeShellCommand("dumpsys activity top",false);
            userAction = false;

            if(result==null){
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {

                String activity = result.substring(result.indexOf("ACTIVITY ")+9);
                activity = activity.substring(0, activity.indexOf(" "));
                String pkg = activity.substring(0,activity.indexOf("/"));
                String clz = activity.substring(activity.indexOf("/")+1);
                if(clz.startsWith(".")){
                    clz = pkg+clz;
                }

                GlobalSearchScope scope = GlobalSearchScope.allScope(project);
                PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(clz, scope);

                if (psiClass != null) {
                    FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);
                    //Open the file containing the class
                    VirtualFile vf = psiClass.getContainingFile().getVirtualFile();
                    //Jump there
                    new OpenFileDescriptor(project, vf, 1, 0).navigateInEditor(project, false);
                } else {
                    Messages.showMessageDialog(project, clz, resourceBundle.getString("error.class_not_found"),  Messages.getWarningIcon());
                    return;
                }

            });

        }, resourceBundle.getString("setting.values.title"), false, null));



        inputOnDeviceButton = new JButton(resourceBundle.getString("button.input_on_device"));
        c.gridx=0;
        c.gridy=4;
        c.gridwidth=2;
        panel.add(inputOnDeviceButton, c);

        inputOnDeviceButton.addActionListener(e -> {
            final String text2send = Messages.showMultilineInputDialog(project, resourceBundle.getString("send_text.message"), resourceBundle.getString("send_text.title"),"",Messages.getQuestionIcon(), null);

            if(text2send!=null) {
                ProgressManager.getInstance().runProcessWithProgressSynchronously(new Runnable() {
                    @Override
                    public void run() {
                        ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                        userAction = true;
                        String escaped = text2send.replace("\\", "\\\\").replace("\"", "\\");
                        executeShellCommand("input text \"" + escaped + "\"", false);
                        userAction = false;
                    }
                }, resourceBundle.getString("setting.values.title"), false, null);
            }
        });


        JPanel framePanel = new JPanel(new BorderLayout());
        framePanel.add(panel, BorderLayout.NORTH);

        disableAll();

        Content content = contentFactory.createContent(framePanel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private void updateFromDevice() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
            IDevice selectedDevice = getSelectedDevice();
            if (selectedDevice != null) {

                setupDevice(selectedDevice);

                String debugLayoutProperty = getSysPropFromDevice("debug.layout",selectedDevice);
                if ("true".equals(debugLayoutProperty)) {
                    showLayoutBounds.setSelected(true);
                } else {
                    showLayoutBounds.setSelected(false);
                }

                String deviceLocale = getSysPropFromDevice("persist.sys.locale",selectedDevice);
                int i = 0;
                for(LocaleData ld : LOCALES){
                    if(deviceLocale!=null && deviceLocale.startsWith(ld.language) && deviceLocale.endsWith(ld.county)) {
                        final int toSelect = i;
                        SwingUtilities.invokeLater(() -> localeCHooser.setSelectedIndex(toSelect));

                        break;
                    }
                    i++;
                }


                enableAll();
            } else {
                disableAll();
            }
        }, resourceBundle.getString("initializing.device.message"), false, null);
    }

    private String getSysPropFromDevice(String propName, IDevice selectedDevice){
        try {
            return selectedDevice.getSystemProperty(propName).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void disableAll() {
        showLayoutBounds.setEnabled(false);
        localeCHooser.setEnabled(false);
        goToActivityButton.setEnabled(false);
        inputOnDeviceButton.setEnabled(false);
    }

    private void enableAll() {
        showLayoutBounds.setEnabled(true);
        localeCHooser.setEnabled(true);
        goToActivityButton.setEnabled(true);
        inputOnDeviceButton.setEnabled(true);
    }

    private void setupDevice(final IDevice selectedDevice) {

        try {
            installEnablerApk(selectedDevice);
        } catch (IOException e) {
            e.printStackTrace();
        }


    }

    @SuppressWarnings("unchecked")
    private void updateDeviceComboBox() {
        IDevice[] devs = adBridge.getDevices();
        Vector devicesList = new Vector();
        devicesList.add("-- none --");
        for (IDevice device : devs) {
            devicesList.add(device.toString());
        }
        devices.setModel(new DefaultComboBoxModel<>(devicesList));
    }

    private String executeShellCommand(String cmd, boolean doPoke) {
        if (!userAction) {
            return null;
        }

        if(devices.getSelectedIndex()==0){
            return null;
        }

        String res = null;
        String selDevice = (String) devices.getSelectedItem();
        for (IDevice device : adBridge.getDevices()) {
            if (selDevice.equals(device.toString())) {

                try {
                    rcv.reset();
                    device.executeShellCommand(cmd, rcv);
                    res = rcv.getResult();
                    if(doPoke) {
                        device.executeShellCommand("am start -a POKESYSPROPS", rcv);
                    }
                } catch (TimeoutException | AdbCommandRejectedException | ShellCommandUnresponsiveException | IOException e1) {
                    e1.printStackTrace();
                }

            }
        }
        return res;
    }

    private void installEnablerApk(IDevice device) throws IOException {
        // TODO no need to create the tmp file over and over again
        File tmpfile = File.createTempFile("enabler", "apk");
        FileOutputStream fos = null;
        try {
            InputStream is = getClass().getResourceAsStream("/de/mobilej/plugin/adc/enabler.apk");
            fos = new FileOutputStream(tmpfile);
            byte[] buffer = new byte[4096];
            int len = 0;
            while ((len = is.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
        } finally {
            if (fos != null) {
                fos.flush();
                fos.close();
            }
        }

        try {
            device.installPackage(tmpfile.getAbsolutePath(), false);
        } catch (InstallException ie) {
            ie.printStackTrace();
        }

        try {
            device.executeShellCommand("pm grant mobilej.de.systemproppoker android.permission.CHANGE_CONFIGURATION", rcv);
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private IDevice getSelectedDevice() {
        String selDevice = (String) devices.getSelectedItem();
        for (IDevice device : adBridge.getDevices()) {
            if (selDevice.equals(device.toString())) {
                return device;
            }
        }
        return null;
    }

    /**
     * Holder for Locale Data
     */
    private static class LocaleData {
        final String name;
        final String language;
        final String county;

        LocaleData(String name, String language, String county) {
            this.name = name;
            this.language = language;
            this.county = county;
        }

        @Override
        public String toString() {
            return name;
        }
    }

}