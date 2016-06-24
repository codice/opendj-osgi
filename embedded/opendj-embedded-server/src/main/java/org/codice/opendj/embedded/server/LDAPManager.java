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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.opends.messages.Message;
import org.opends.server.api.Backend;
import org.opends.server.config.ConfigException;
import org.opends.server.core.DirectoryServer;
import org.opends.server.core.LockFileManager;
import org.opends.server.types.DirectoryEnvironmentConfig;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.InitializationException;
import org.opends.server.types.LDIFImportConfig;
import org.opends.server.types.LDIFImportResult;
import org.opends.server.util.EmbeddedUtils;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the starting and stopping of an embedded LDAP server. Utilizes OpenDJ
 * as the server software with a Berkeley DB Java Edition backend.
 */
public class LDAPManager {

    private static final int DEFAULT_LDAP_PORT = 1389;

    private static final int DEFAULT_LDAPS_PORT = 1636;

    private static final int DEFAULT_ADMIN_PORT = 4444;

    private static final String DEFAULT_TRUST_STORE_LOCATION = System.getProperty(
            "javax.net.ssl.trustStore");

    private static final String DEFAULT_KEY_STORE_LOCATION = System.getProperty(
            "javax.net.ssl.keyStore");

    private static final String DEFAULT_TRUST_STORE_PW = System.getProperty(
            "javax.net.ssl.trustStorePassword");

    private static final String DEFAULT_KEY_STORE_PW = System.getProperty(
            "javax.net.ssl.keyStorePassword");

    private static final String DEFAULT_TRUST_STORE_TYPE = System.getProperty(
            "javax.net.ssl.trustStoreType");

    private static final String DEFAULT_KEY_STORE_TYPE = System.getProperty(
            "javax.net.ssl.keyStoreType");

    private static final String DEFAULT_TRUST_STORE_PW_LOCACTION =
            System.getProperty("java.io.tmpdir") + "/" + UUID.randomUUID()
                    .toString();

    private static final String DEFAULT_KEY_STORE_PW_LOCACTION =
            System.getProperty("java.io.tmpdir") + "/" + UUID.randomUUID()
                    .toString();

    private static final String BASE_LDIF_STR = "base.ldif";

    private static final String DEFAULT_CONFIG_LOC = "/config/config.ldif";

    private static final String DEFAULT_ADMIN_BACKEND_LOC = "/config/admin-backend.ldif";

    private static final String DEFAULT_SCHEMA_LOC = "/config/schema/";

    private static final String DEFAULT_UPGRADE_SCHEMA_LOC = "/config/upgrade/schema.ldif.9086";

    private static final String DEFAULT_BUILDINFO_LOC = "/config/buildinfo";

    private static final String DEFAULT_DB_ID = "userRoot";

    private Logger logger = LoggerFactory.getLogger(LDAPManager.class);

    private String dataPath = "etc/org.codice.opendj/ldap";

    private String installDir;

    private BundleContext context;

    /**
     * Default constructor. Uses a {@link BundleContext} to retrieve files
     * located inside the bundle.
     *
     * @param context Used to obtain {@link InputStream} for files contain
     *                within the bundle resources.
     */
    public LDAPManager(BundleContext context) {
        this.context = context;
    }

    /**
     * Starts the underlying LDAP server. This method is set in blueprint and is
     * used when the bundle is being started.
     *
     * @throws LDAPException Generic error thrown when LDAP server is unable to
     *                       start. Usually thrown if default files could not be copied
     *                       over or there is a port conflict on the system.
     */
    public void startServer() throws LDAPException {
        logger.info("Starting LDAP Server Configuration.");
        File installFile = new File(dataPath);
        installDir = installFile.getAbsolutePath();
        boolean isFreshInstall;
        if (installFile.exists()) {
            isFreshInstall = false;
            logger.debug("Configuration already exists at {}, not setting up defaults.",
                    installDir);
        } else {
            isFreshInstall = true;
            logger.debug("No initial configuration found, setting defaults.");
            createDirectory(installDir);
            logger.info("Storing LDAP configuration at: " + installDir);

            logger.info("Copying default files to configuration location.");
            copyDefaultFiles();
        }

        try {
            createStorePinFiles();
            // General Configuration
            DirectoryEnvironmentConfig serverConfig = new DirectoryEnvironmentConfig();
            serverConfig.setServerRoot(installFile);
            serverConfig.setDisableConnectionHandlers(false);
            serverConfig.setMaintainConfigArchive(false);

            logger.debug("Starting LDAP Server.");
            EmbeddedUtils.startServer(serverConfig);
        } catch (InitializationException ie) {
            LDAPException le = new LDAPException(
                    "Could not initialize configuration for LDAP server.", ie);
            logger.warn(le.getMessage(), le);
            throw le;
        } catch (ConfigException ce) {
            LDAPException le = new LDAPException("Error while starting embedded server.", ce);
            logger.warn(le.getMessage(), le);
            throw le;
        } catch (IOException e) {
            LDAPException le = new LDAPException("Could not create password pin files.", e);
            logger.warn(le.getMessage(), le);
            throw le;
        }

        // post start tasks if first time being started
        if (isFreshInstall) {
            InputStream defaultLDIF = null;

            try {
                //we use the find because that searches fragments too
                Enumeration<URL> entries = context.getBundle()
                        .findEntries("/", "default-*.ldif", false);
                if (entries != null) {
                    while (entries.hasMoreElements()) {
                        URL url = entries.nextElement();
                        defaultLDIF = url.openStream();
                        logger.debug("Installing default LDIF file: {}", url);
                        // load into backend
                        loadLDIF(defaultLDIF);
                    }
                }
            } catch (IOException ioe) {
                // need to make sure that the server is stopped on error
                logger.warn(
                        "Error encountered during LDIF import, stopping server and cleaning up.");
                stopServer();
                throw new LDAPException(
                        "Error encountered during LDIF import, stopping server and cleaning up.",
                        ioe);
            } catch (LDAPException le) {
                // need to make sure that the server is stopped on error
                logger.warn(
                        "Error encountered during LDIF import, stopping server and cleaning up.");
                stopServer();
                throw le;
            } finally {
                IOUtils.closeQuietly(defaultLDIF);
            }

        }
        logger.info("LDAP server successfully started.");
    }

    private void createStorePinFiles() throws IOException {
        Path keyStorePin = Paths.get(DEFAULT_TRUST_STORE_PW_LOCACTION);
        Path trustStorePin = Paths.get(DEFAULT_KEY_STORE_PW_LOCACTION);
        if (Files.exists(keyStorePin)) {
            Files.delete(keyStorePin);
        }
        if (Files.exists(trustStorePin)) {
            Files.delete(trustStorePin);
        }

        Files.createFile(keyStorePin);
        Files.createFile(trustStorePin);

        BufferedWriter keyStorePinWriter = Files.newBufferedWriter(keyStorePin);
        try {
            keyStorePinWriter.write(DEFAULT_KEY_STORE_PW);
        } finally {
            keyStorePinWriter.close();
        }

        BufferedWriter trustStorePinWriter = Files.newBufferedWriter(trustStorePin);
        try {
            trustStorePinWriter.write(DEFAULT_TRUST_STORE_PW);
        } finally {
            trustStorePinWriter.close();
        }

        try {
            Set<PosixFilePermission> posixFilePermissions = new HashSet<PosixFilePermission>();
            posixFilePermissions.add(PosixFilePermission.OWNER_READ);
            posixFilePermissions.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(keyStorePin, posixFilePermissions);
            Files.setPosixFilePermissions(trustStorePin, posixFilePermissions);
        } catch (UnsupportedOperationException e) {
            logger.warn(
                    "Unable to set read/write permissions for temporary keystore password files. This might be normal if running in a Windows environment.",
                    e);
        }
    }

    /**
     * Stops the underlying LDAP server. This method is set in blueprint and is
     * used when the bundle is being stopped.
     */
    public void stopServer() throws LDAPException {
        logger.info("Stopping LDAP Server");
        if (EmbeddedUtils.isRunning()) {
            EmbeddedUtils.stopServer(LDAPManager.class.getName(), Message.EMPTY);
            StringBuilder lockReleaseError = new StringBuilder();
            if (!LockFileManager.releaseLock(LockFileManager.getServerLockFileName(),
                    lockReleaseError)) {
                logger.warn(
                        "Could not release the main server lock file. You may need to terminate the JVM to "
                                + "restart the server. ERROR: {}", lockReleaseError.toString());
            }

            logger.info("LDAP Server successfully stopped.");
        } else {
            logger.info("Server was not started, it is still stopped.");
        }
    }

    /**
     * Restarts the underlying LDAP server. This method calls stopServer and
     * then startServer. It does not use the embedded LDAP server's restart
     * command due to a system.exit() that is in it.
     *
     * @throws LDAPException
     */
    public void restartServer() throws LDAPException {
        logger.info("--Restarting LDAP Server--");
        stopServer();
        startServer();
        logger.info("LDAP Server successfully restarted.");
    }

    /**
     * Retrieves the current port set to listen for LDAP calls. This
     * <i>should</i> be the current listening port, but if it was just changed
     * it will take calling the restartServer command to re-bind to ports.
     *
     * @return the integer value of the port
     */
    public int getLDAPPort() {
        return ConnectorType.LDAP.currentPort;
    }

    /**
     * Set the LDAP port for the server to listen on. <br/>
     * <br/>
     * <b>NOTE:</b> this will NOT automatically update the server to listen on
     * the new port. The configuration will need to be updated and the server
     * restarted for it to listen on the new port.
     *
     * @param ldapPortNumber new port to listen on.
     */
    public void setLDAPPort(int ldapPortNumber) {
        ConnectorType.LDAP.currentPort = ldapPortNumber;
    }

    /**
     * Retrieves the current port set to listen for LDAPS (SSL) calls. This
     * <i>should</i> be the current listening port, but if it was just changed
     * it will take calling the restartServer command to re-bind to ports.
     *
     * @return the integer value of the port
     */
    public int getLDAPSPort() {
        return ConnectorType.LDAPS.currentPort;
    }

    /**
     * Set the LDAPS port for the server to listen on. <br/>
     * <br/>
     * <b>NOTE:</b> this will NOT automatically update the server to listen on
     * the new port. The configuration will need to be updated and the server
     * restarted for it to listen on the new port.
     *
     * @param ldapsPortNumber new port to listen on.
     */
    public void setLDAPSPort(int ldapsPortNumber) {
        ConnectorType.LDAPS.currentPort = ldapsPortNumber;
    }

    /**
     * Retrieves the current port set to listen for ADMIN calls. This
     * <i>should</i> be the current listening port, but if it was just changed
     * it will take calling the restartServer command to re-bind to ports.
     *
     * @return the integer value of the port
     */
    public int getAdminPort() {
        return ConnectorType.ADMIN.currentPort;
    }

    /**
     * Set the ADMIN port for the server to listen on. <br/>
     * <br/>
     * <b>NOTE:</b> this will NOT automatically update the server to listen on
     * the new port. The configuration will need to be updated and the server
     * restarted for it to listen on the new port.
     *
     * @param adminPortNumber new port to listen on.
     */
    public void setAdminPort(int adminPortNumber) {
        ConnectorType.ADMIN.currentPort = adminPortNumber;
    }

    public String getDataPath() {
        return this.dataPath;
    }

    public void setDataPath(String dataPath) {
        this.dataPath = dataPath;
    }

    /**
     * Callback method to update the properties for the server. This method will
     * restart the server if any of the properties being updated require a
     * restart.
     *
     * @param properties Map of properties to be updated.
     * @throws LDAPException If any error occurs during the updating process,
     *                       including an error on server restart.
     */
    public void updateCallback(Map<String, Object> properties) throws LDAPException {
        boolean needsRestart = false;
        logger.debug("Got an update with {} items in it.", properties.size());
        Set<Entry<String, Object>> entries = properties.entrySet();
        for (Entry<String, Object> curEntry : entries) {
            logger.debug(curEntry.toString());
            if (ConnectorType.LDAP.portVariable.equals(curEntry.getKey())) {
                int newPort = Integer.parseInt(curEntry.getValue()
                        .toString());
                if (newPort == ConnectorType.LDAP.currentPort) {
                    logger.debug("LDAP Port unchanged, not updating.");
                    continue;
                }
                setLDAPPort(newPort);
                needsRestart = true;
            } else if (ConnectorType.LDAPS.portVariable.equals(curEntry.getKey())) {
                int newPort = Integer.parseInt(curEntry.getValue()
                        .toString());
                if (newPort == ConnectorType.LDAPS.currentPort) {
                    logger.debug("LDAPS Port unchanged, not updating.");
                    continue;
                }
                setLDAPSPort(newPort);
                needsRestart = true;
            } else if (ConnectorType.ADMIN.portVariable.equals(curEntry.getKey())) {
                int newPort = Integer.parseInt(curEntry.getValue()
                        .toString());
                if (newPort == ConnectorType.ADMIN.currentPort) {
                    logger.debug("Admin Port unchanged, not updating.");
                    continue;
                }
                setAdminPort(newPort);
                needsRestart = true;
            } else if (BASE_LDIF_STR.equals(curEntry.getKey())) {
                InputStream ldifStream = null;
                String ldifLocation = curEntry.getValue()
                        .toString();
                if (ldifLocation.isEmpty()) {
                    logger.debug("No new base ldif file, not loading.");
                    continue;
                }
                try {
                    ldifStream = new FileInputStream(ldifLocation);
                    loadLDIF(ldifStream);
                } catch (FileNotFoundException fnfe) {
                    logger.warn("Base LDIF file not found at {}. Could not update base entries.",
                            ldifLocation);
                } finally {
                    IOUtils.closeQuietly(ldifStream);
                }
            } else if ("dataPath".equals(curEntry.getKey())) {
                String newDataPath = curEntry.getValue()
                        .toString();
                if (StringUtils.equals(dataPath, newDataPath)) {
                    logger.debug("Data path unchanged, not updating.");
                    continue;
                }
                setDataPath(newDataPath);
                needsRestart = true;
            }
        }
        if (needsRestart) {
            copyConfig(DEFAULT_CONFIG_LOC, installDir + DEFAULT_CONFIG_LOC);
            logger.debug("Calling restart to update configurations.");
            restartServer();
        }
    }

    /**
     * Loads a LDIF file into the default backend db. All existing data in the
     * backend will be cleared and only the entries from this LDIF will be
     * available.
     *
     * @param ldifStream InputStream of an LDIF file to load.
     * @throws LDAPException Thrown if any errors occur during import process.
     */
    private void loadLDIF(InputStream ldifStream) throws LDAPException {
        LDIFImportConfig ldifConfig = null;
        try {
            ldifConfig = new LDIFImportConfig(ldifStream);
            ldifConfig.setAppendToExistingData(false);
            ldifConfig.setClearBackend(true);
            ldifConfig.setValidateSchema(false);
            ldifConfig.setSkipDNValidation(false);
            Backend backend = DirectoryServer.getBackend(DEFAULT_DB_ID);
            logger.debug("Got reference to backend: {}", backend.getBackendID());
            String lockFile = LockFileManager.getBackendLockFileName(backend);
            LockFileManager.acquireExclusiveLock(lockFile, new StringBuilder());
            backend.finalizeBackend();
            LDIFImportResult importResult = backend.importLDIF(ldifConfig);
            logger.debug("Complete result of import: {}", importResult);
            backend.initializeBackend();
            LockFileManager.releaseLock(lockFile, new StringBuilder());
            logger.info("{} entries imported.", importResult.getEntriesImported());
        } catch (DirectoryException de) {
            LDAPException le = new LDAPException("Error while trying to import LDIF.", de);
            logger.warn(le.getMessage(), le);
            throw le;
        } catch (ConfigException ce) {
            LDAPException le = new LDAPException(
                    "Error with configuration while re-starting backend database.", ce);
            logger.warn(le.getMessage(), le);
            throw le;
        } catch (InitializationException ie) {
            LDAPException le = new LDAPException(
                    "Error while trying to re-initialize backend database.", ie);
            logger.warn(le.getMessage(), le);
            throw le;
        } finally {
            if (ldifConfig != null) {
                ldifConfig.close();
            }
        }

    }

    /**
     * Copies over a default set of configuration files for the LDAP server. The
     * server generally needs all of these files to function properly and this
     * method will fail if any of the individual file copies fail.
     *
     * @throws IOException Thrown when any of the file copy operations encounter
     *                     an error. This should stop the entire starting process and
     *                     prevent the server from being started.
     */
    private void copyDefaultFiles() throws LDAPException {
        // Create default folder locations
        // Config folders
        createDirectory(installDir + "/config/schema");
        // Lock folder
        createDirectory(installDir + "/locks");
        // Log folder
        createDirectory(installDir + "/logs");
        // DB folders
        createDirectory(installDir + "/db/userRoot");
        // Upgrade folder
        createDirectory(installDir + "/config/upgrade");

        // Default config files, main config uses a different method
        copyConfig(DEFAULT_CONFIG_LOC, installDir + DEFAULT_CONFIG_LOC);
        copyFile(DEFAULT_ADMIN_BACKEND_LOC, installDir + DEFAULT_ADMIN_BACKEND_LOC);

        // Copy the buildinfo file
        copyFile(DEFAULT_BUILDINFO_LOC, installDir + DEFAULT_BUILDINFO_LOC);

        // Default schema files
        // This also copies any fragment schema files
        copyFile(DEFAULT_SCHEMA_LOC, installDir + DEFAULT_SCHEMA_LOC, "*.ldif");

        // Default upgrade schema files checks to see if schemas changed
        copyFile(DEFAULT_UPGRADE_SCHEMA_LOC, installDir + DEFAULT_UPGRADE_SCHEMA_LOC);
    }

    /**
     * Performs a copy of a config file from one area to another. This method is
     * for configuration files that contain variables to configure during the
     * copy process. Current variables are ${ldapPort} and ${ldapsPort}.
     *
     * @param from location of the original file with variables
     * @param to   location to put the final file with variables converted.
     * @throws LDAPException if file does not exist or stream could not be
     *                       created
     */
    private void copyConfig(String from, String to) throws LDAPException {
        InputStream fromStream = null;
        StringWriter writer = new StringWriter();
        OutputStream toStream = null;
        StringReader reader = null;
        try {
            fromStream = context.getBundle()
                    .getResource(from)
                    .openStream();
            toStream = new FileOutputStream(to);
            IOUtils.copy(fromStream, writer);

            String configStr = writer.toString();

            configStr = updatePort(ConnectorType.LDAP, configStr);
            configStr = updatePort(ConnectorType.LDAPS, configStr);
            configStr = updatePort(ConnectorType.ADMIN, configStr);
            configStr = updateStore(KeystoreInfo.KEY_STORE, configStr);
            configStr = updateStore(KeystoreInfo.TRUST_STORE, configStr);

            reader = new StringReader(configStr);
            logger.debug("Copying {} to {}", from, to);
            IOUtils.copy(reader, toStream);
        } catch (IOException ioe) {
            LDAPException le = new LDAPException("Could not copy file " + from + " to " + to, ioe);
            logger.warn(le.getMessage(), le);
            throw le;
        } finally {
            IOUtils.closeQuietly(fromStream);
            IOUtils.closeQuietly(writer);
            IOUtils.closeQuietly(toStream);
            IOUtils.closeQuietly(reader);
        }
    }

    /**
     * Updates the port for a connector in the given configuration file.
     * Replaces the variables in the config file.
     *
     * @param connector Connector to update
     * @param configStr String containing the entire configuration file
     * @return The configuration file as a string with the ports updated in it.
     */
    private String updatePort(ConnectorType connector, String configStr) {
        String newConfig = configStr.trim();
        if (connector.currentPort == 0) {
            logger.info("Disabling {} connector.", connector.connectorName);
            newConfig = newConfig.replaceFirst(connector.enableVariable, Boolean.FALSE.toString());
            // server does not like 0 in the config for the port, resetting it
            // to the default even though it is disabled
            newConfig = newConfig.replaceFirst(connector.portVariable,
                    Integer.toString(connector.defaultPort));
        } else {
            logger.info("Updating port for {} connector to {}", connector.connectorName,
                    connector.currentPort);
            newConfig = newConfig.replaceFirst(connector.enableVariable, Boolean.TRUE.toString());
            newConfig = newConfig.replaceFirst(connector.portVariable,
                    Integer.toString(connector.currentPort));
        }
        return newConfig;
    }

    /**
     * Updates the key store for the given configuration file.
     * Replaces the variables in the config file.
     *
     * @param keystoreInfo KeystoreInfo to update
     * @param configStr    String containing the entire configuration file
     * @return The configuration file as a string with the ports updated in it.
     */
    private String updateStore(KeystoreInfo keystoreInfo, String configStr) {
        String newConfig = configStr.trim();
        newConfig = newConfig.replaceAll(keystoreInfo.locationVar, Matcher.quoteReplacement(keystoreInfo.location));
        newConfig = newConfig.replaceAll(keystoreInfo.passwordVar, keystoreInfo.password);
        newConfig = newConfig.replaceAll(keystoreInfo.typeVar, keystoreInfo.type);
        newConfig = newConfig.replaceAll(keystoreInfo.passwordPinVar, Matcher.quoteReplacement(keystoreInfo.passwordPin));
        return newConfig;
    }

    /**
     * Performs a copy of a file from one area to another. In the context of
     * this class it is used to put files into the persistent cache location.
     *
     * @param from File name to copy (within the current class's context. Paths ending in "/" are assumed to be directories
     * @param to   Area to store file, should be within the persistent cache. Paths ending in "/" are assumed to be directories
     * @throws IOException if file does not exist or stream could not be created
     */
    private void copyFile(String from, String to) throws LDAPException {
        if (StringUtils.isNotEmpty(from) && StringUtils.isNotEmpty(to)) {
            if (from.endsWith("/") && to.endsWith("/")) {
                copyFile(from, to, "*");
            } else if (from.endsWith("/")) {
                copyFile(from, to.substring(0, to.lastIndexOf("/") + 1),
                        to.substring(to.lastIndexOf("/") + 1));
            } else if (to.endsWith("/")) {
                copyFile(from.substring(0, from.lastIndexOf("/") + 1), to,
                        from.substring(from.lastIndexOf("/") + 1));
            } else {
                copyFile(from.substring(0, from.lastIndexOf("/") + 1),
                        to.substring(0, to.lastIndexOf("/") + 1),
                        from.substring(from.lastIndexOf("/") + 1));
            }
        }
    }

    /**
     * Performs a copy of a file from one area to another. In the context of
     * this class it is used to put files into the persistent cache location.
     *
     * @param from    File name to copy (within the current class's context
     * @param to      Area to store file, should be within the persistent cache.
     * @param pattern FileFilter pattern String to search for
     * @throws LDAPException
     */
    private void copyFile(String from, String to, String pattern) throws LDAPException {
        InputStream fromStream = null;
        OutputStream toStream = null;
        Enumeration<URL> entries = context.getBundle()
                .findEntries(from, pattern, false);
        URL currentURL = null;
        if (entries != null) {
            while (entries.hasMoreElements()) {
                currentURL = entries.nextElement();
                try {
                    logger.debug("Copying {} to {}", currentURL, to);
                    fromStream = currentURL.openStream();
                    if (to.endsWith("/")) {
                        String urlString = currentURL.toString();
                        toStream = new FileOutputStream(
                                to + urlString.substring(urlString.lastIndexOf("/") + 1));
                    } else {
                        toStream = new FileOutputStream(to);
                    }
                    IOUtils.copy(fromStream, toStream);
                } catch (IOException ioe) {
                    LDAPException le = new LDAPException(
                            "Could not copy file " + currentURL + " to " + to, ioe);
                    logger.warn(le.getMessage(), le);
                    throw le;
                } finally {
                    IOUtils.closeQuietly(fromStream);
                    IOUtils.closeQuietly(toStream);
                }
            }
        }
    }

    /**
     * Creates a directory (and all sub-directories). Throws an exception if
     * they were not all created.
     *
     * @param dir Absolute path of all of the directories to create.
     * @throws LDAPException Thrown if any of the underlying directories could
     *                       not be created.
     */
    private void createDirectory(String dir) throws LDAPException {
        if (!new File(dir).mkdirs()) {
            throw new LDAPException("Could not create folders for " + dir);
        }
    }

    /**
     * Enumeration used to describe the various types of connectors used in the
     * LDAP server. This is used to keep track of their current status (ports)
     * and also variables in the config file.
     */
    private enum ConnectorType {
        LDAP("LDAP", "ldap.port", "ldap.enable", DEFAULT_LDAP_PORT), LDAPS("LDAPS", "ldaps.port",
                "ldaps.enable", DEFAULT_LDAPS_PORT), ADMIN("ADMIN", "admin.port", "admin.enable",
                DEFAULT_ADMIN_PORT);

        private String connectorName;

        private String portVariable;

        private String enableVariable;

        private int currentPort;

        private int defaultPort;

        /**
         * Default constructor for a connector.
         *
         * @param connectorName  Name of the connector (ie LDAP) that will be
         *                       used in logs.
         * @param portVariable   Variable for the port that is inside the config
         *                       file (ex: ldap.port)
         * @param enableVariable Variable for the enable setting inside the
         *                       config file (ex: ldap.enable)
         * @param defaultPort    Default port number for the connector
         */
        ConnectorType(String connectorName, String portVariable, String enableVariable,
                int defaultPort) {
            this.connectorName = connectorName;
            this.portVariable = portVariable;
            this.enableVariable = enableVariable;
            this.currentPort = defaultPort;
            this.defaultPort = defaultPort;
        }

    }

    private enum KeystoreInfo {

        TRUST_STORE(DEFAULT_TRUST_STORE_LOCATION, "trust\\.store\\.loc", DEFAULT_TRUST_STORE_PW,
                "trust\\.store\\.pw", DEFAULT_TRUST_STORE_PW_LOCACTION, "trust\\.store\\.pin\\.loc",
                DEFAULT_TRUST_STORE_TYPE, "trust\\.store\\.type"), KEY_STORE(
                DEFAULT_KEY_STORE_LOCATION, "key\\.store\\.loc", DEFAULT_KEY_STORE_PW,
                "key\\.store\\.pw", DEFAULT_KEY_STORE_PW_LOCACTION, "key\\.store\\.pin\\.loc",
                DEFAULT_KEY_STORE_TYPE, "key\\.store\\.type");

        private String location;

        private String password;

        private String passwordPin;

        private String locationVar;

        private String passwordVar;

        private String passwordPinVar;

        private String type;

        private String typeVar;

        KeystoreInfo(String location, String locationVar, String password, String passwordVar,
                String passwordPin, String passwordPinVar, String type, String typeVar) {
            this.location = Paths.get(location)
                    .toFile()
                    .getAbsolutePath();
            this.password = password;
            this.passwordPin = Paths.get(passwordPin)
                    .toFile()
                    .getAbsolutePath();
            this.locationVar = locationVar;
            this.passwordVar = passwordVar;
            this.passwordPinVar = passwordPinVar;
            this.type = type;
            this.typeVar = typeVar;
        }
    }

}
