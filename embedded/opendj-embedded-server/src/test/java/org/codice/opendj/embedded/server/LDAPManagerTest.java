/**
 * Copyright (c) Codice Foundation
 * <p/>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or any later version.
 * <p/>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 **/
package org.codice.opendj.embedded.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;

import org.apache.camel.test.AvailablePortFinder;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import junit.framework.Assert;

public class LDAPManagerTest {

    private static final String TMP_FOLDER_NAME = "target/test_folder";

    static int adminPort;

    static int ldapPort;

    static int ldapsPort;

    private static Logger logger = LoggerFactory.getLogger(LDAPManagerTest.class);

    @BeforeClass
    public static void getPorts() {
        adminPort = AvailablePortFinder.getNextAvailable();
        logger.info("Using admin port: " + adminPort);
        ldapPort = AvailablePortFinder.getNextAvailable();
        logger.info("Using ldap port: " + adminPort);
        ldapsPort = AvailablePortFinder.getNextAvailable();
        logger.info("Using ldaps port: " + adminPort);
    }

    @Before
    public void setup() throws IOException {
        File systemKeystoreFile = new File("target/serverKeystore.jks");
        systemKeystoreFile.createNewFile();
        FileOutputStream systemKeyOutStream = new FileOutputStream(systemKeystoreFile);
        InputStream systemKeyStream = LDAPManager.class.getResourceAsStream("/serverKeystore.jks");
        IOUtils.copy(systemKeyStream, systemKeyOutStream);

        File systemTruststoreFile = new File("target/serverTruststore.jks");
        systemTruststoreFile.createNewFile();
        FileOutputStream systemTrustOutStream = new FileOutputStream(systemTruststoreFile);
        InputStream systemTrustStream = LDAPManager.class.getResourceAsStream(
                "/serverTruststore.jks");
        IOUtils.copy(systemTrustStream, systemTrustOutStream);

        IOUtils.closeQuietly(systemKeyStream);
        IOUtils.closeQuietly(systemKeyOutStream);
        IOUtils.closeQuietly(systemTrustStream);
        IOUtils.closeQuietly(systemTrustOutStream);

        System.setProperty("javax.net.ssl.keyStore", systemKeystoreFile.getAbsolutePath());
        System.setProperty("javax.net.ssl.trustStore", systemTruststoreFile.getAbsolutePath());

        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        System.setProperty("javax.net.ssl.keyStorePassword", "changeit");

        System.setProperty("javax.net.ssl.trustStoreType", "JKS");
        System.setProperty("javax.net.ssl.keyStoreType", "JKS");
        File pinfolder = new File("target/pinfolder");
        pinfolder.mkdir();
        System.setProperty("java.io.tmpdir", pinfolder.getAbsolutePath());
    }

    @Test
    public void TestStartServer() {
        logger.info("Testing starting and stopping server.");
        BundleContext mockContext = createMockContext(new File(TMP_FOLDER_NAME));
        LDAPManager manager = new LDAPManager(mockContext);
        HashMap config = new HashMap();
        config.put("admin.port", AvailablePortFinder.getNextAvailable());
        config.put("ldaps.port", AvailablePortFinder.getNextAvailable());
        config.put("ldap.port", AvailablePortFinder.getNextAvailable());
        manager.setAdminPort(adminPort);
        manager.setLDAPPort(ldapPort);
        manager.setLDAPSPort(ldapsPort);
        manager.setDataPath(new File(TMP_FOLDER_NAME).getAbsolutePath() + File.separator + "ldap");
        assertNotNull(manager);
        try {
            logger.info("Starting Server.");
            manager.startServer();
            manager.updateCallback(config);
            Assert.assertEquals(
                    "Admin port of " + manager.getAdminPort() + " expected " + config.get(
                            "admin.port"), config.get("admin.port"), manager.getAdminPort());
            Assert.assertEquals(
                    "LDAPS port of " + manager.getLDAPSPort() + " expected " + config.get(
                            "ldaps.port"), config.get("ldaps.port"), manager.getLDAPSPort());
            Assert.assertEquals("LDAP port of " + manager.getLDAPPort() + " expected " + config.get(
                    "ldap.port"), config.get("ldap.port"), manager.getLDAPPort());
            logger.info("Successfully started server, now stopping.");
            manager.stopServer();
        } catch (LDAPException le) {
            le.printStackTrace();
            fail(le.getMessage());
        } finally {
            try {
                manager.stopServer();
            } catch (LDAPException le) {
                le.printStackTrace();
                fail(le.getMessage());
            }
        }

    }

    @Test
    public void TestStopStopped() {
        logger.info("Testing case to stop an already stopped server.");
        BundleContext mockContext = createMockContext(new File(TMP_FOLDER_NAME));
        LDAPManager manager = new LDAPManager(mockContext);
        manager.setAdminPort(adminPort);
        manager.setLDAPPort(ldapPort);
        manager.setLDAPSPort(ldapsPort);
        assertNotNull(manager);
        try {
            manager.stopServer();
        } catch (Exception le) {
            fail("Server should not throw exception when trying to stop an already stopped server.");
        }
    }

    private BundleContext createMockContext(final File dataFolderPath) {
        Bundle mockBundle = Mockito.mock(Bundle.class);
        Mockito.when(mockBundle.findEntries(Mockito.anyString(),
                Mockito.anyString(),
                Mockito.anyBoolean()))
                .then(new Answer<Enumeration<URL>>() {

                    @Override
                    public Enumeration<URL> answer(InvocationOnMock invocation) throws Throwable {

                        Object[] arguments = invocation.getArguments();
                        String path = arguments[0].toString();
                        String filePattern = arguments[1].toString();
                        boolean recurse = (Boolean) arguments[2];
                        final URL url = this.getClass()
                                .getResource(path);
                        File pathFile = null;
                        try {
                            pathFile = new File(url.toURI());
                        } catch (URISyntaxException e) {
                            throw new RuntimeException("Unable to resolve file path", e);
                        }
                        final File[] files = pathFile.listFiles((FileFilter) new WildcardFileFilter(
                                filePattern));
                        Enumeration<URL> enumer = new Enumeration<URL>() {
                            int place = 0;

                            List<File> urlList = Arrays.asList(files);

                            @Override
                            public boolean hasMoreElements() {
                                return place < urlList.size();
                            }

                            @Override
                            public URL nextElement() {
                                File file = urlList.get(place++);
                                try {
                                    return file.toURL();
                                } catch (MalformedURLException e) {
                                    throw new RuntimeException("Unable to convert to URL", e);
                                }
                            }
                        };
                        return enumer;
                    }

                });
        Mockito.when(mockBundle.getResource(Mockito.anyString()))
                .then(new Answer<URL>() {

                    @Override
                    public URL answer(InvocationOnMock invocation) throws Throwable {
                        return this.getClass()
                                .getResource((String) invocation.getArguments()[0]);
                    }

                });
        BundleContext mockContext = Mockito.mock(BundleContext.class);
        Mockito.when(mockContext.getDataFile(Mockito.anyString()))
                .then(new Answer<File>() {

                    @Override
                    public File answer(InvocationOnMock invocation) throws Throwable {
                        String filename = invocation.getArguments()[0].toString();
                        if (dataFolderPath != null) {
                            return new File(dataFolderPath + "/" + filename);
                        } else {
                            return null;
                        }
                    }

                });
        Mockito.when(mockContext.getBundle())
                .thenReturn(mockBundle);

        return mockContext;
    }
}
