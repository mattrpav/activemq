package org.apache.activemq.spring.jetty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.apache.xbean.spring.context.ClassPathXmlApplicationContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.Test;

public class JettyServerBeanTest {

    @Test
    public void testJettyServerBean() throws Exception {
        var context = new ClassPathXmlApplicationContext("conf/jetty-spring.xml");
        context.start();

        var jettyServer = (JettyServerBean) context.getBean("jettyServer");
        assertNotNull(jettyServer);

        Server server = jettyServer.getServer();
        assertNotNull("Embedded Jetty Server should be created", server);
        assertTrue("Embedded Jetty Server should be running", server.isRunning());

        // The console is protected by the JAAS SecurityHandler wired in
        // jetty-security.xml: an unauthenticated request must be challenged
        // (HTTP 401) rather than served. A request from loopback also confirms
        // the InetAccessHandler allows local clients.
        int port = ((ServerConnector) server.getConnectors()[0]).getLocalPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("Web console must require authentication", 401, response.statusCode());

        context.stop();
    }
}
