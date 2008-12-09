/*
 * Copyright (c) 2008, SQL Power Group Inc.
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in
 *       the documentation and/or other materials provided with the
 *       distribution.
 *     * Neither the name of SQL Power Group Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package ca.sqlpower.swingui.db;

import java.awt.Window;
import java.util.concurrent.Callable;

import javax.swing.JDialog;

import ca.sqlpower.sql.SPDataSource;
import ca.sqlpower.swingui.DataEntryPanelBuilder;
import ca.sqlpower.swingui.SPDataSourcePanel;

/**
 * A default factory implementation that just shows a plain data source edit
 * panel.
 */
public class DefaultDataSourceDialogFactory implements DataSourceDialogFactory {

    /**
     * Creates and shows a non-modal dialog box for editing the properties
     * of the given data source.
     */
    public JDialog showDialog(Window parentWindow, SPDataSource ds, final Runnable onAccept) {
        final SPDataSourcePanel dataSourcePanel = new SPDataSourcePanel(ds);
        
        Callable<Boolean> okCall = new Callable<Boolean>() {
            public Boolean call() {
                if (dataSourcePanel.applyChanges()) {
                    if (onAccept != null) {
                        onAccept.run();
                    }
                    return Boolean.TRUE;
                }
                return Boolean.FALSE;
            }
        };
    
        Callable<Boolean> cancelCall = new Callable<Boolean>() {
            public Boolean call() {
            	dataSourcePanel.discardChanges();
                return Boolean.TRUE;
            }
        };
        
        JDialog d = DataEntryPanelBuilder.createDataEntryPanelDialog(
                dataSourcePanel, parentWindow, "Data Source Properties", "OK", okCall, cancelCall);
        d.pack();
        d.setLocationRelativeTo(parentWindow);
        d.setVisible(true);
        return d;
    }

}
