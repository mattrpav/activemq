/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.spring.jetty;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Demonstration of using the XmlConfiguration to build up a server with multiple XML files and Properties.
 */
public class JettyServerBean implements InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(JettyServerBean.class);

    public static final String JETTY_PROPERTIES_DIRECTROY = "conf";
    public static final String JETTY_PROPERTIES_FILE = "jetty-spring.properties";
    public static final String PROPERTY_XML_FILES = "jettyXmlFiles";
    public static final String PROPERTY_HTTP_XML_FILES = "jettyHttpXmlFiles";
    public static final String PROPERTY_HTTPS_XML_FILES = "jettyHttpsXmlFiles";
    public static final String PROPERTY_EXTRA_XML_FILES = "jettyExtraXmlFiles";

    public static final int PROPERTY_XML_FILES_LIMIT = 128;
    public static final String PROPERTY_XML_FILES_SEPARATOR = ",";

    boolean httpEnabled = true;
    boolean httpsEnabled = false;
    String jettyXmlDirectory = Path.of(JETTY_PROPERTIES_DIRECTROY, "jetty").toString();
    String jettyConfDirectory = Path.of(JETTY_PROPERTIES_DIRECTROY).toString();
    String jettyPropertiesFile = Path.of(JETTY_PROPERTIES_DIRECTROY, JETTY_PROPERTIES_FILE).toString();
    String webAppsContext = null;

    // List of configured IDs from XML;
    Map<String, Object> idMap;

    // The list of XMLs in the order they should be executed.
    List<Resource> xmls = new ArrayList<>();

    private Server server;

    /**
     * Configure for the list of XML Resources and Properties.
     *
     * @param xmls the xml resources (in order of execution)
     * @param properties the properties to use with the XML
     * @return the ID Map of configured objects (key is the id name in the XML, and the value is configured object)
     * @throws Exception if unable to create objects or read XML
     */
    public Map<String, Object> configure(List<Resource> xmls, Map<String, String> properties) throws Exception {
        var idMap = new HashMap<String, Object>();

        // Configure everything
        for (var xmlResource : xmls) {
            var configuration = new XmlConfiguration(xmlResource);
            configuration.getIdMap().putAll(idMap);
            configuration.getProperties().putAll(properties);
            configuration.configure();
            idMap.putAll(configuration.getIdMap());
        }

        return idMap;
    }

    private static void ensureDirExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private static Map<String, String> loadProperties(Resource resource) throws IOException {
        var properties = new Properties();

        try (var in = resource.newInputStream()) {
            properties.load(in);
        }

        return properties.entrySet().stream().collect(
                Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue()),
                        (prev, next) -> next, HashMap::new
                ));
    }

    private static List<String> resolveXmlFiles(String propertyName, String jettyXmlsCSV) {
        if(jettyXmlsCSV == null ||
           jettyXmlsCSV.isBlank() ||
           jettyXmlsCSV.trim().isBlank()) {
            return List.of();
        }

        if(jettyXmlsCSV.contains(PROPERTY_XML_FILES_SEPARATOR)) {
            var splits = jettyXmlsCSV.split(PROPERTY_XML_FILES_SEPARATOR, PROPERTY_XML_FILES_LIMIT);
            if(splits.length >= PROPERTY_XML_FILES_LIMIT) {
                LOG.warn("Detected security exploit attempt or misconfiguration as maximum number files specified ({}) for property {}", PROPERTY_XML_FILES_LIMIT, propertyName);
            }
            return Arrays.stream(splits).map(String::trim).toList();
        } else {
            return List.of(jettyXmlsCSV.trim());
        }
    }

    @Override
    public void destroy() throws Exception {
        // TODO: review if we need to check
        //  && !server.isStopping()
        if(server != null) {
            server.stop();
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        try(var resourceFactory = ResourceFactory.closeable()) {
            var homeXmlResource = resourceFactory.newResource(Path.of(getJettyXmlDirectory()));
            var customBaseResource = resourceFactory.newResource(Path.of(getJettyConfDirectory()));
            var jettyProperties = loadProperties(customBaseResource.resolve(JETTY_PROPERTIES_FILE));

            for(var jettyXmlFile : resolveXmlFiles(PROPERTY_XML_FILES, jettyProperties.get(PROPERTY_XML_FILES))) {
                LOG.info("Loading jetty xml file: {}", jettyXmlFile);
                xmls.add(homeXmlResource.resolve(jettyXmlFile));
            }

            if (isHttpEnabled()) {
                for(var jettyHttpXmlFile : resolveXmlFiles(PROPERTY_HTTP_XML_FILES, jettyProperties.get(PROPERTY_HTTP_XML_FILES))) {
                    LOG.info("Loading jetty http xml file: {}", jettyHttpXmlFile);
                    xmls.add(homeXmlResource.resolve(jettyHttpXmlFile));
                }
            }
            if (isHttpsEnabled()) {
                for(var jettyHttpsXmlFile : resolveXmlFiles(PROPERTY_HTTPS_XML_FILES, jettyProperties.get(PROPERTY_HTTPS_XML_FILES))) {
                    LOG.info("Loading jetty https xml file: {}", jettyHttpsXmlFile);
                    xmls.add(homeXmlResource.resolve(jettyHttpsXmlFile));
                }
            }

            // example: jetty-customrequestlog.xml
            for(var jettyExtraXmlFile : resolveXmlFiles(PROPERTY_EXTRA_XML_FILES, jettyProperties.get(PROPERTY_EXTRA_XML_FILES))) {
                LOG.info("Loading jetty extra xml file: {}", jettyExtraXmlFile);
                xmls.add(homeXmlResource.resolve(jettyExtraXmlFile));
            }

            // Now we add our customizations
            // In this case, it's 2 ServletContextHandlers
            // xmls.add(homeXmlResource.resolve("jetty-webapps.xml/context-activemq-console.xml"));
            if(getWebAppsContext() != null) {
                xmls.add(homeXmlResource.resolve(getWebAppsContext()));
            }

            // Lets load our properties

            // Create a path suitable for output / work directory / etc.
            var outputPath = Paths.get("target/xmlserver-output");
            var resourcesPath = outputPath.resolve("resources");

            ensureDirExists(outputPath);
            ensureDirExists(outputPath.resolve("logs"));
            ensureDirExists(resourcesPath);
            ensureDirExists(resourcesPath.resolve("bar"));
            ensureDirExists(resourcesPath.resolve("foo"));

            // And define some common properties
            // These 2 properties are used in MANY PLACES, define them, even if you don't use them fully.
            jettyProperties.put("jetty.home", outputPath.toString());
            jettyProperties.put("jetty.base", outputPath.toString());
            // And define the resource paths for the contexts
            jettyProperties.put("custom.resources", resourcesPath.toString());
            jettyProperties.put("jetty.sslContext.keyStoreAbsolutePath", customBaseResource.resolve("keystore").toString());
            jettyProperties.put("jetty.sslContext.trustStoreAbsolutePath", customBaseResource.resolve("keystore").toString());

            // Now lets tie it all together
            idMap = configure(xmls, jettyProperties);
        }

        Server tmpServer = (Server)idMap.get("Server");
        tmpServer.start();

        var jettyServerOutput = new StringBuilder("Jetty Server listening on: ");
        for (var connector : tmpServer.getBeans(ServerConnector.class))
        {
            for (var connectionFactory : connector.getBeans(HttpConnectionFactory.class))
            {
                var scheme = "http";
                var httpConfiguration = connectionFactory.getHttpConfiguration();
                if (httpConfiguration.getSecurePort() == connector.getLocalPort())
                    scheme = httpConfiguration.getSecureScheme();
                var host = connector.getHost();
                if (host == null)
                    host = InetAddress.getLocalHost().getHostAddress();

                jettyServerOutput.append(String.format(" %s://%s:%s/%n", scheme, host, connector.getLocalPort()));
            }
        }
        LOG.info(jettyServerOutput.toString());
        // NOTE: do not call tmpServer.join() here - this is an embedded server
        // started from a Spring init method; joining would block the calling
        // thread until the server stops and leave this.server unassigned.
        this.server = tmpServer;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public boolean isHttpEnabled() {
        return this.httpEnabled;
    }

    public void setHttpsEnabled(boolean httpsEnabled) {
        this.httpsEnabled = httpsEnabled;;
    }

    public boolean isHttpsEnabled() {
        return this.httpsEnabled;
    }

    public void setJettyConfDirectory(String jettyConfDirectory) {
        this.jettyConfDirectory = jettyConfDirectory;
    }

    public String getJettyConfDirectory() {
        return jettyConfDirectory;
    }

    public void setJettyPropertiesfFile(String jettyPropertiesFile) {
        this.jettyPropertiesFile = jettyPropertiesFile;
    }

    public String getJettyPropertiesFile() {
        return jettyPropertiesFile;
    }

    public void setJettyXmlDirectory(String jettyXmlDirectory) {
        this.jettyXmlDirectory = jettyXmlDirectory;
    }

    public String getJettyXmlDirectory() {
        return jettyXmlDirectory;
    }

    public void setWebAppsContext(String webAppsContext) {
        this.webAppsContext = webAppsContext;
    }

    public String getWebAppsContext() {
        return webAppsContext;
    }

    /**
     * @return the started Jetty {@link Server}, or {@code null} if this bean has
     *         not yet been initialized.
     */
    public Server getServer() {
        return server;
    }
}