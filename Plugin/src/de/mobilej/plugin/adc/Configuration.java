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

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Configuration for the plugin
 *
 * Created by bjoern on 24.07.2017.
 */
public class Configuration implements Configurable {

    private Storage storage;

    private boolean modified = false;
    private boolean clearDevicesClicked = false;

    public Configuration(Project project){
        this.storage = ServiceManager.getService(project, Storage.class);
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "ADC";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        JComponent panel = new JPanel();
        BoxLayout layout = new BoxLayout(panel, BoxLayout.PAGE_AXIS);
        panel.setLayout(layout);

        JButton clearDevices = new JButton("Clear known devices");
        panel.add(clearDevices);

        clearDevices.addActionListener(actionEvent -> {
            modified = true;
            clearDevicesClicked = true;
        });

        return panel;
    }

    @Override
    public boolean isModified() {
        return modified;
    }

    @Override
    public void apply() throws ConfigurationException {
        if(clearDevicesClicked) {
            storage.setInstalledOnDevices("");
        }
    }
}
