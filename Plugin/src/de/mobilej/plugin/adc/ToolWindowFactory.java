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
import com.android.tools.idea.model.AndroidModel;
import com.intellij.facet.ProjectFacetManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
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
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidFacetConfiguration;
import org.jetbrains.android.sdk.AndroidSdkUtils;
import org.jetbrains.annotations.NotNull;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.util.XmlPullUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.io.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Android Device Controller Plugin for Android Studio
 */
public class ToolWindowFactory implements com.intellij.openapi.wm.ToolWindowFactory {

    private static ResourceBundle resourceBundle = ResourceBundle.getBundle("de.mobilej.plugin.adc.Plugin");

    private static LocaleData[] LOCALES;

    static {
        ArrayList<LocaleData> data = new ArrayList<>();
        BufferedReader lr = null;
        try {
            InputStream is = ToolWindowFactory.class.getResourceAsStream("/de/mobilej/plugin/adc/locales.txt");
            lr = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ((line = lr.readLine()) != null) {
                if (line.indexOf("_") > 0 && line.indexOf("[") > 0) {
                    String lang = line.substring(0, line.indexOf("_"));
                    String cntry = line.substring(line.indexOf("_") + 1, line.indexOf(" "));
                    String desc = line.substring(line.indexOf("[") + 1, line.length() - 1);
                    LocaleData ld = new LocaleData(desc, lang, cntry);
                    data.add(ld);
                }
            }
        } catch (IOException ioe) {
            // ignored
        }
        LOCALES = data.toArray(new LocaleData[data.size()]);
    }

    private ComboBox devices;
    private AndroidDebugBridge adBridge;
    private JButton inputOnDeviceButton;
    private JButton clearDataButton;
    private JButton killProcessButton;


    private static class StringShellOutputReceiver implements IShellOutputReceiver {
        private StringBuffer result = new StringBuffer();

        void reset() {
            result.delete(0, result.length());
        }

        String getResult() {
            return result.toString();
        }

        @Override
        public void addOutput(byte[] bytes, int i, int i1) {
            try {
                result.append(new String(bytes, i, i1, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
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
    private ComboBox localeChooser;
    private boolean userAction = false;
    private JButton goToActivityButton;

    private final Storage storage = ServiceManager.getService(Storage.class);

    public ToolWindowFactory() {
    }

    // Create the tool window content.
    public void createToolWindowContent(@NotNull final Project project, @NotNull final ToolWindow toolWindow) {
        ContentFactory contentFactory = ContentFactory.SERVICE.getInstance();
        JPanel framePanel = createPanel(project);
        disableAll();

        AndroidDebugBridge adb = AndroidSdkUtils.getDebugBridge(project);
        if (adb == null) {
            return;
        }

        if(adb.isConnected()){
            ToolWindowFactory.this.adBridge = adb;
            Logger.getInstance(ToolWindowFactory.class).info("Successfully obtained debug bridge");
            AndroidDebugBridge.addDeviceChangeListener(deviceChangeListener);
            updateDeviceComboBox();
        } else {
            Logger.getInstance(ToolWindowFactory.class).info("Unable to obtain debug bridge");
            String msg = MessageFormat.format(resourceBundle.getString("error.message.adb"), "");
            Messages.showErrorDialog(msg, resourceBundle.getString("error.title.adb"));
        }

        Content content = contentFactory.createContent(framePanel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    @NotNull
    private JPanel createPanel(@NotNull Project project) {
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


        localeChooser = new ComboBox(LOCALES);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        panel.add(localeChooser, c);

        localeChooser.addActionListener(e -> {
            final LocaleData ld = (LocaleData) localeChooser.getSelectedItem();
            if (ld == null) {
                return;
            }

            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                userAction = true;
                executeShellCommand("am start -a SETMYLOCALE --es language " + ld.language + " --es country " + ld.county, false);
                userAction = false;
            }, resourceBundle.getString("setting.values.title"), false, null);
        });


        goToActivityButton = new JButton(resourceBundle.getString("button.goto_activity"));
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        panel.add(goToActivityButton, c);

        goToActivityButton.addActionListener(e -> ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
            userAction = true;
            final String result = executeShellCommand("dumpsys activity top", false);
            userAction = false;

            if (result == null) {
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {

                String activity = result.substring(result.lastIndexOf("ACTIVITY ") + 9);
                activity = activity.substring(0, activity.indexOf(" "));
                String pkg = activity.substring(0, activity.indexOf("/"));
                String clz = activity.substring(activity.indexOf("/") + 1);
                if (clz.startsWith(".")) {
                    clz = pkg + clz;
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
                    Messages.showMessageDialog(project, clz, resourceBundle.getString("error.class_not_found"), Messages.getWarningIcon());
                    return;
                }

            });

        }, resourceBundle.getString("setting.values.title"), false, null));


        inputOnDeviceButton = new JButton(resourceBundle.getString("button.input_on_device"));
        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 2;
        panel.add(inputOnDeviceButton, c);

        inputOnDeviceButton.addActionListener(e -> {
            final String text2send = Messages.showMultilineInputDialog(project, resourceBundle.getString("send_text.message"), resourceBundle.getString("send_text.title"), storage.getLastSentText(), Messages.getQuestionIcon(), null);

            if (text2send != null) {
                storage.setLastSentText(text2send);

                ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                    ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                    userAction = true;
                    doInputOnDevice(text2send);
                    userAction = false;
                }, resourceBundle.getString("processing.title"), false, null);
            }
        });

        clearDataButton = new JButton(resourceBundle.getString("button.clear_data"));
        c.gridx = 0;
        c.gridy = 5;
        c.gridwidth = 2;
        panel.add(clearDataButton, c);
        clearDataButton.addActionListener(actionEvent -> {
            ArrayList<String> appIds = new ArrayList<String>();
            List<AndroidFacet> androidFacets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID);
            if (androidFacets != null) {
                for (AndroidFacet facet : androidFacets) {
                    AndroidFacetConfiguration facetConfig = facet.getConfiguration();
                    if (!facetConfig.isLibraryProject()) {
                        String appId = facetConfig.getModel().getApplicationId();
                        appIds.add(appId);
                    }
                }
            }

            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                userAction = true;
                for (String appId : appIds) {
                    executeShellCommand("pm clear " + appId, false);
                }
                userAction = false;
            }, resourceBundle.getString("processing.title"), false, null);
        });

        killProcessButton = new JButton(resourceBundle.getString("button.kill_process"));
        c.gridx = 0;
        c.gridy = 6;
        c.gridwidth = 2;
        panel.add(killProcessButton, c);
        killProcessButton.addActionListener(actionEvent -> {
            ArrayList<String> appIds = new ArrayList<String>();
            List<AndroidFacet> androidFacets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID);
            if (androidFacets != null) {
                for (AndroidFacet facet : androidFacets) {
                    AndroidFacetConfiguration facetConfig = facet.getConfiguration();
                    if (!facetConfig.isLibraryProject()) {
                        AndroidModel androidModel = facetConfig.getModel();
                        if (androidModel != null) {
                            String appId = androidModel.getApplicationId();
                            appIds.add(appId);
                        }
                    }
                }
            }

            ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
                ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
                userAction = true;
                for (String appId : appIds) {
                    String res = executeShellCommand("run-as "+appId+" ps -A", false);
                    if(res!=null) {
                        LineNumberReader lnr = new LineNumberReader(new StringReader(res));
                        try {
                            String pid = null;
                            String line = lnr.readLine();
                            while(line!=null){
                                line = lnr.readLine();
                                if(line!=null){
                                    if(line.contains(appId)){
                                        StringTokenizer toker = new StringTokenizer(line, " \t");
                                        if(toker.hasMoreTokens()){
                                            toker.nextToken();
                                            if(toker.hasMoreTokens()) {
                                                pid = toker.nextToken();
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if(pid!=null){
                                res = executeShellCommand("run-as "+appId+" kill "+pid, false);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                userAction = false;
            }, resourceBundle.getString("processing.title"), false, null);
        });

        JPanel framePanel = new JPanel(new BorderLayout());
        framePanel.add(panel, BorderLayout.NORTH);
        return framePanel;
    }

    private void doInputOnDevice(String text2send) {
        /*
        Syntax:
        `tap 130 150` -> sends "input tap 130 150" .... really just "input " and append the command
        `tap @my.package:id/text` -> first find the center of the given res-id (uiautomator dump /dev/tty)
        `tap @*:id/text` -> support wildcards
        `swipe 10 20 30 40` -> simple swipe (px)
        `swipe @*:id/my_id[10,20] @*:id/my_id[50,60]` -> swipe from 10% of x of given view, 20% of y of given view to 50% of x of the view to 60% of y of the view
        `tap @*:id/button[20,30]` syntax also works in general for @id things
        ``` -> escapes `
        `
        ` -> new line which is not sent
        `#500` -> wait 500 milliseconds
        */

        text2send = text2send.replace("```", "\u2764");
        text2send = text2send + "`";
        StringTokenizer tokenizer = new StringTokenizer(text2send, "`", true);
        boolean inCommand = false;
        String plainText = null;
        String commandText = null;
        while (tokenizer.hasMoreElements()) {
            String next = tokenizer.nextToken();

            if ("`".equals(next)) {
                if (!inCommand) {
                    inCommand = true;

                    if (plainText != null) {

                        StringTokenizer tokenizer2 = new StringTokenizer(plainText," \t\r\n", true);
                        while(tokenizer2.hasMoreElements()) {
                            String part = tokenizer2.nextToken();
                            String escaped = part.replace("\"", "\\\"").replace("\u2764", "\\`");
                            executeShellCommand("input text \"" + escaped + "\"", false);

                            try {
                                Thread.sleep(400); // wait a bit - give the device some time to process
                            } catch (InterruptedException e) {
                                //ignored
                            }
                        }
                        plainText = null;
                        commandText = null;
                    }
                } else {
                    inCommand = false;
                    if (commandText != null) {
                        commandText = commandText.replace("\r", "").replace("\n", "");
                        if (commandText.length() > 0) {
                            if (commandText.startsWith("#")) {
                                long timeToWait = Long.parseLong(commandText.substring(1));
                                try {
                                    Thread.sleep(timeToWait);
                                } catch (InterruptedException e) {
                                    // do nothing
                                }
                            } else {
                                if (commandText.contains("@")) {
                                    commandText = processViewIds(commandText);
                                }

                                executeShellCommand("input " + commandText, false);
                            }
                        }
                        commandText = null;
                        plainText = null;
                    }
                }
            } else {
                if (inCommand) {
                    commandText = next;
                    plainText = null;
                } else {
                    plainText = next;
                    commandText = null;
                }
            }
        }

        try {
            Thread.sleep(800); // wait a bit - give the device some time to process
        } catch (InterruptedException e) {
            //ignored
        }
    }

    private String processViewIds(String commandText) {
        /*
        @<id, supporting wildcards, if no ":" contained it will prepend "*:"> defaults to center of view
        @<id, supporting wildcards, if no ":" contained it will prepend "*:">[percentX,percentY] percentX/Y in view bounds
         */
        String views = executeShellCommand("uiautomator dump /dev/tty", false);
        views = views.substring(0, views.lastIndexOf(">") + 1);
        HashMap<String, Rectangle> resIdToBoundsMap = new HashMap<>();
        try {
            XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
            xpp.setInput(new StringReader(views));

            int eventType;
            while ((eventType = xpp.getEventType()) != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if ("node".equals(xpp.getName())) {
                        String bounds = XmlPullUtil.getAttributeValue(xpp, "bounds");
                        String resId = XmlPullUtil.getAttributeValue(xpp, "resource-id");

                        if (resId != null && resId.length() > 0) {
                            bounds = bounds.replace("][", ",");
                            bounds = bounds.replace("[", "");
                            bounds = bounds.replace("]", "");
                            String[] coords = bounds.split(",");
                            int x1 = Integer.parseInt(coords[0]);
                            int y1 = Integer.parseInt(coords[1]);
                            int x2 = Integer.parseInt(coords[2]);
                            int y2 = Integer.parseInt(coords[3]);

                            Rectangle rect = new Rectangle(x1, y1, x2 - x1, y2 - y1);
                            resIdToBoundsMap.put(resId, rect);
                        }
                    }
                }
                xpp.next();

            }

        } catch (XmlPullParserException | IOException e) {
            e.printStackTrace();
        }

        while (commandText.contains("@")) {
            int idx = commandText.indexOf("@");
            StringBuilder sb = new StringBuilder();
            int i = idx;
            while (i < commandText.length() && commandText.charAt(i) > ' ') {
                sb.append(commandText.charAt(i));
                i++;
            }

            String calculatedCoords = "0 0";
            String resIdToMatch = sb.substring(1);
            double percentX = 0.5;
            double percentY = 0.5;

            if (resIdToMatch.contains("[") && resIdToMatch.contains("]")) {
                String percentPart = resIdToMatch.substring(resIdToMatch.indexOf("[") + 1, resIdToMatch.indexOf("]"));
                resIdToMatch = resIdToMatch.substring(0, resIdToMatch.indexOf("["));
                String[] percentParts = percentPart.split(",");
                try {
                    percentX = Double.parseDouble(percentParts[0]) / 100;
                    percentY = Double.parseDouble(percentParts[1]) / 100;
                } catch (NumberFormatException nfe) {
                    nfe.printStackTrace();
                }
            }

            Rectangle rect = new Rectangle(0, 0, 0, 0);
            if (resIdToBoundsMap.containsKey(resIdToMatch)) {
                rect = resIdToBoundsMap.get(resIdToMatch);
            } else {
                if (!resIdToMatch.contains(":")) {
                    resIdToMatch = "*:" + resIdToMatch;
                }

                for (Map.Entry<String, Rectangle> entry : resIdToBoundsMap.entrySet()) {
                    if (wildcardMatch(resIdToMatch, entry.getKey())) {
                        rect = entry.getValue();
                        break;
                    }
                }
            }
            calculatedCoords = "" + (int) (rect.x + rect.width * percentX) + " " + (int) (rect.y + rect.height * percentY);

            commandText = commandText.substring(0, idx) + calculatedCoords + commandText.substring(idx + sb.length());
        }

        return commandText;
    }

    public static boolean wildcardMatch(final String toMatch, final String value) {
        StringBuilder patternStringBuilder = new StringBuilder();
        for (final char c : toMatch.toCharArray()) {
            switch (c) {
                case '?':
                    patternStringBuilder.append(".?");
                    break;
                case '*':
                    patternStringBuilder.append(".*");
                    break;
                default:
                    patternStringBuilder.append(Pattern.quote(String.valueOf(c)));
                    break;
            }
        }
        Pattern pattern = Pattern.compile(patternStringBuilder.toString());
        return pattern.matcher(value).matches();
    }

    private void updateFromDevice() {
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
            ProgressManager.getInstance().getProgressIndicator().setIndeterminate(true);
            IDevice selectedDevice = getSelectedDevice();
            if (selectedDevice != null) {

                setupDevice(selectedDevice);

                String debugLayoutProperty = getSysPropFromDevice("debug.layout", selectedDevice);
                if ("true".equals(debugLayoutProperty)) {
                    showLayoutBounds.setSelected(true);
                } else {
                    showLayoutBounds.setSelected(false);
                }

                String deviceLocale = getSysPropFromDevice("persist.sys.locale", selectedDevice);
                int i = 0;
                for (LocaleData ld : LOCALES) {
                    if (deviceLocale != null && deviceLocale.startsWith(ld.language) && deviceLocale.endsWith(ld.county)) {
                        final int toSelect = i;
                        SwingUtilities.invokeLater(() -> localeChooser.setSelectedIndex(toSelect));

                        break;
                    }
                    i++;
                }

                SwingUtilities.invokeLater(() -> enableAll());
            } else {
                SwingUtilities.invokeLater(() -> disableAll());
            }
        }, resourceBundle.getString("initializing.device.message"), false, null);
    }

    private String getSysPropFromDevice(String propName, IDevice selectedDevice) {
        try {
            return selectedDevice.getSystemProperty(propName).get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void disableAll() {
        showLayoutBounds.setEnabled(false);
        localeChooser.setEnabled(false);
        goToActivityButton.setEnabled(false);
        inputOnDeviceButton.setEnabled(false);
        clearDataButton.setEnabled(false);
        killProcessButton.setEnabled(false);
    }

    private void enableAll() {
        showLayoutBounds.setEnabled(true);
        localeChooser.setEnabled(true);
        goToActivityButton.setEnabled(true);
        inputOnDeviceButton.setEnabled(true);
        clearDataButton.setEnabled(true);
        killProcessButton.setEnabled(true);
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
        devices.removeActionListener(deviceSelectedListener);
        String selectedDevice = (String) devices.getSelectedItem();

        IDevice[] devs = adBridge.getDevices();
        Vector devicesList = new Vector();
        devicesList.add("-- none --");
        for (IDevice device : devs) {
            devicesList.add(device.toString());
        }
        devices.setModel(new DefaultComboBoxModel<>(devicesList));

        if (devicesList.size() == 1) {
            disableAll();
        } else {
            devices.setSelectedItem(selectedDevice);

            devices.setSelectedItem(devices.getSelectedItem());
            if (devices.getSelectedIndex() == 0) {
                disableAll();
            } else {
                enableAll();
            }
        }

        devices.addActionListener(deviceSelectedListener);
    }

    private String executeShellCommand(String cmd, boolean doPoke) {
        if (!userAction) {
            return null;
        }

        if (devices.getSelectedIndex() == 0) {
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
                    if (doPoke) {
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
        String serial = "<"+device.getSerialNumber()+">";
        String alreadyInstalledOn = storage.getInstalledOnDevices();
        if(alreadyInstalledOn==null){
            alreadyInstalledOn = "";
        }
        if(alreadyInstalledOn.contains(serial)){
            return;
        }
        storage.setInstalledOnDevices(alreadyInstalledOn+serial);

        // TODO no need to create the tmp file over and over again
        File tmpfile = File.createTempFile("enabler", "apk");
        FileOutputStream fos = null;
        InputStream is = null;
        try {
            is = getClass().getResourceAsStream("/de/mobilej/plugin/adc/enabler.apk");
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
            if (is != null) {
                is.close();
            }
        }

        try {
            device.installPackage(tmpfile.getAbsolutePath(), true);
        } catch (InstallException ie) {
            ie.printStackTrace();
        }

        try {
            device.executeShellCommand("pm grant mobilej.de.systemproppoker android.permission.CHANGE_CONFIGURATION", rcv);
        } catch (Exception e) {
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