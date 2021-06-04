/*
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

package org.apache.ignite.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.client.ClientConnectionException;
import org.apache.ignite.client.Config;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.client.SslMode;
import org.apache.ignite.configuration.ClientConfiguration;
import org.apache.ignite.configuration.ClientConnectorConfiguration;
import org.apache.ignite.configuration.ConnectorConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.client.GridClient;
import org.apache.ignite.internal.client.GridClientConfiguration;
import org.apache.ignite.internal.client.GridClientFactory;
import org.apache.ignite.internal.processors.metric.MetricRegistry;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.metric.BooleanMetric;
import org.apache.ignite.spi.metric.HistogramMetric;
import org.apache.ignite.spi.metric.IntMetric;
import org.apache.ignite.spi.metric.LongMetric;
import org.apache.ignite.ssl.SslContextFactory;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static java.sql.DriverManager.getConnection;
import static org.apache.ignite.Ignition.startClient;
import static org.apache.ignite.internal.client.GridClientFactory.start;
import static org.apache.ignite.internal.managers.discovery.GridDiscoveryManager.DISCO_METRICS;
import static org.apache.ignite.internal.processors.odbc.ClientListenerProcessor.CLIENT_CONNECTOR_METRIC_REGISTRY_NAME;
import static org.apache.ignite.internal.processors.rest.protocols.tcp.GridTcpRestProtocol.REST_CONNECTOR_METRIC_REGISTRY_NAME;
import static org.apache.ignite.internal.util.nio.GridNioServer.SESSIONS_CNT_METRIC_NAME;
import static org.apache.ignite.internal.util.nio.GridNioServer.SSL_ENABLED_METRIC_NAME;
import static org.apache.ignite.internal.util.nio.ssl.GridNioSslFilter.SSL_HANDSHAKE_DURATION_HISTOGRAM_METRIC_NAME;
import static org.apache.ignite.internal.util.nio.ssl.GridNioSslFilter.SSL_REJECTED_SESSIONS_CNT_METRIC_NAME;
import static org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi.COMMUNICATION_METRICS_GROUP_NAME;
import static org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi.SSL_REJECTED_CONNECTIONS_CNT_METRIC_NAME;
import static org.apache.ignite.testframework.GridTestUtils.assertThrowsWithCause;
import static org.apache.ignite.testframework.GridTestUtils.keyStorePassword;
import static org.apache.ignite.testframework.GridTestUtils.keyStorePath;
import static org.apache.ignite.testframework.GridTestUtils.sslTrustedFactory;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;

/** Checks SSL metrics for all types of connections to the node. */
public class SslConnectorsMetricTest extends GridCommonAbstractTest {
    /** Cipher suite supported by cluster nodes. */
    private static final String CIPHER_SUITE = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";

    /** Cipher suite not supported by cluster nodes. */
    private static final String UNSUPPORTED_CIPHER_SUITE = "TLS_RSA_WITH_AES_128_GCM_SHA256";

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();

        GridClientFactory.stopAll(false);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids(true);
    }

    /** Checks the status of the SSL metric if SSL is not configured on the node. */
    @Test
    public void testSslDisabled() throws Exception {
        IgniteEx srv = startGrid();

        assertFalse(mreg(srv, DISCO_METRICS).<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertFalse(mreg(srv, COMMUNICATION_METRICS_GROUP_NAME).<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertFalse(mreg(srv, CLIENT_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertNull(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME));

        assertNull(mreg(srv, DISCO_METRICS).<BooleanMetric>findMetric(SSL_REJECTED_CONNECTIONS_CNT_METRIC_NAME));
        assertNull(mreg(srv, COMMUNICATION_METRICS_GROUP_NAME).<BooleanMetric>findMetric(SSL_REJECTED_SESSIONS_CNT_METRIC_NAME));
        assertNull(mreg(srv, CLIENT_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_REJECTED_SESSIONS_CNT_METRIC_NAME));
        assertNull(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_REJECTED_SESSIONS_CNT_METRIC_NAME));

        assertNull(mreg(srv, COMMUNICATION_METRICS_GROUP_NAME).<BooleanMetric>findMetric(SSL_HANDSHAKE_DURATION_HISTOGRAM_METRIC_NAME));
        assertNull(mreg(srv, CLIENT_CONNECTOR_METRIC_REGISTRY_NAME)
            .<BooleanMetric>findMetric(SSL_HANDSHAKE_DURATION_HISTOGRAM_METRIC_NAME));
        assertNull(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_HANDSHAKE_DURATION_HISTOGRAM_METRIC_NAME));

        assertNotNull(mreg(srv, COMMUNICATION_METRICS_GROUP_NAME).<BooleanMetric>findMetric(SESSIONS_CNT_METRIC_NAME));
        assertNotNull(mreg(srv, CLIENT_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SESSIONS_CNT_METRIC_NAME));
        assertNull(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SESSIONS_CNT_METRIC_NAME));

        stopAllGrids();

        srv = startGrid(getConfiguration().setConnectorConfiguration(new ConnectorConfiguration()));

        assertFalse(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertNotNull(mreg(srv, REST_CONNECTOR_METRIC_REGISTRY_NAME).<BooleanMetric>findMetric(SESSIONS_CNT_METRIC_NAME));
    }

    /** Tests SSL metrics produced by JDBC connection. */
    @Test
    public void testJdbc() throws Exception {
        MetricRegistry reg = mreg(startClusterNode(0), CLIENT_CONNECTOR_METRIC_REGISTRY_NAME);

        try (Connection ignored = getConnection(jdbcConfiguration("thinClient", "trusttwo", CIPHER_SUITE, "TLSv1.2"))) {
            assertSslCommunicationMetrics(reg, 1, 1, 0);
        }

        assertSslCommunicationMetrics(reg, 1, 0, 0);

        // Tests untrusted certificate.
        assertThrowsWithCause(() ->
            getConnection(jdbcConfiguration("client", "trusttwo", CIPHER_SUITE, "TLSv1.2")),
            SQLException.class);

        assertSslCommunicationMetrics(reg, 2, 0, 1);

        // Tests unsupported cipher suite.
        assertThrowsWithCause(() ->
            getConnection(jdbcConfiguration("thinClient", "trusttwo", UNSUPPORTED_CIPHER_SUITE, "TLSv1.2")),
            SQLException.class);

        assertSslCommunicationMetrics(reg, 3, 0, 2);

        assertThrowsWithCause(() ->
            getConnection(jdbcConfiguration("thinClient", "trusttwo", null, "TLSv1.1")),
            SQLException.class);

        assertSslCommunicationMetrics(reg, 4, 0, 3);
    }

    /** Tests SSL metrics produced by REST TCP client connection. */
    @Test
    public void testRestClientConnector() throws Exception {
        MetricRegistry reg = mreg(startClusterNode(0), REST_CONNECTOR_METRIC_REGISTRY_NAME);

        try (
            GridClient ignored = start(gridClientConfiguration("connectorClient", "trustthree", CIPHER_SUITE, "TLSv1.2"))
        ) {
            assertSslCommunicationMetrics(reg, 1, 1, 0);
        }

        assertSslCommunicationMetrics(reg, 1, 0, 0);

        // Tests untrusted certificate.
        try (GridClient ignored = start(gridClientConfiguration("client", "trustthree", CIPHER_SUITE, "TLSv1.2"))) {
            // GridClient makes 2 additional silent connection attempts if an SSL error occurs.
        }

        assertSslCommunicationMetrics(reg, 4, 0, 3);

        // Tests unsupported cipher suite.
        try (
            GridClient ignored = start(gridClientConfiguration("connectorClient", "trustthree",
                UNSUPPORTED_CIPHER_SUITE, "TLSv1.2"))
        ) {
            // GridClient makes 2 additional silent connection attempts if an SSL error occurs.
        }

        assertSslCommunicationMetrics(reg, 7, 0, 6);

        // Tests mismatched protocol versions.
        try (GridClient ignored = start(gridClientConfiguration("connectorClient", "trustthree", null, "TLSv1.1"))) {
            // GridClient makes 2 additional  silent connection attempts if an SSL error occurs.
        }

        assertSslCommunicationMetrics(reg, 10, 0, 9);
    }

    /** Tests SSL discovery metrics produced by node connection. */
    @Test
    public void testDiscovery() throws Exception {
        MetricRegistry reg = mreg(startClusterNode(0), DISCO_METRICS);

        startGrid(nodeConfiguration(1, true, "client", "trustone", CIPHER_SUITE, "TLSv1.2"));

        assertTrue(reg.<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertEquals(0, reg.<LongMetric>findMetric(SSL_REJECTED_CONNECTIONS_CNT_METRIC_NAME).value());

        // Tests untrusted certificate.
        assertNodeJoinFails(2, true, "thinClient", "trusttwo", CIPHER_SUITE, "TLSv1.2");
        assertNodeJoinFails(2, false, "thinClient", "trusttwo", CIPHER_SUITE, "TLSv1.2");
        // Tests untrusted cipher suites.
        assertNodeJoinFails(2, true, "client", "trustone", UNSUPPORTED_CIPHER_SUITE, "TLSv1.2");
        assertNodeJoinFails(2, false, "node01", "trustone", UNSUPPORTED_CIPHER_SUITE, "TLSv1.2");

        // Tests mismatched protocol versions.
        assertNodeJoinFails(2, true, "client", "trustone", null, "TLSv1.1");
        assertNodeJoinFails(2, false, "node01", "trustone", null, "TLSv1.1");

        // In case of an SSL error, the client and server nodes make 2 additional connection attempts.
        assertTrue(waitForCondition(() ->
            18 == reg.<LongMetric>findMetric(SSL_REJECTED_CONNECTIONS_CNT_METRIC_NAME).value(),
            getTestTimeout()));
    }

    /** Tests SSL communication metrics produced by node connection. */
    @Test
    public void testCommunication() throws Exception {
        MetricRegistry reg = mreg(startClusterNode(0), COMMUNICATION_METRICS_GROUP_NAME);

        assertSslCommunicationMetrics(reg, 0, 0, 0);

        try (
            IgniteEx cliNode = startGrid(nodeConfiguration(1, true, "client", "trustone", CIPHER_SUITE, "TLSv1.2"));
            IgniteEx srvNode = startGrid(nodeConfiguration(2, false, "node01", "trustone", CIPHER_SUITE, "TLSv1.2"))
        ) {
            assertSslCommunicationMetrics(reg, 2, 2, 0);
            assertSslCommunicationMetrics(mreg(cliNode, COMMUNICATION_METRICS_GROUP_NAME), 0, 1, 0);
            assertSslCommunicationMetrics(mreg(srvNode, COMMUNICATION_METRICS_GROUP_NAME), 0, 1, 0);
        }

        assertSslCommunicationMetrics(reg, 2, 0, 0);
    }

    /** Tests SSL metrics produced by thin client connection. */
    @Test
    public void testClientConnector() throws Exception {
        MetricRegistry reg = mreg(startClusterNode(0), CLIENT_CONNECTOR_METRIC_REGISTRY_NAME);

        try (IgniteClient ignored = startClient(clientConfiguration("thinClient", "trusttwo", CIPHER_SUITE, "TLSv1.2"))) {
            assertSslCommunicationMetrics(reg, 1, 1, 0);
        }

        assertSslCommunicationMetrics(reg, 1, 0, 0);

        // Tests untrusted certificate.
        assertThrowsWithCause(() ->
            startClient(clientConfiguration("client", "trustboth", CIPHER_SUITE, "TLSv1.2")),
            ClientConnectionException.class);

        assertSslCommunicationMetrics(reg, 2, 0, 1);

        // Tests unsupported cipher suites.
        assertThrowsWithCause(() ->
            startClient(clientConfiguration("thinClient", "trusttwo", UNSUPPORTED_CIPHER_SUITE, "TLSv1.2")),
            ClientConnectionException.class
        );

        assertSslCommunicationMetrics(reg, 3, 0, 2);

        // Tests mismatched protocol versions.
        assertThrowsWithCause(() ->
            startClient(clientConfiguration("thinClient", "trusttwo", null, "TLSv1.1")),
            ClientConnectionException.class
        );

        assertSslCommunicationMetrics(reg, 4, 0, 3);
    }

    /** Starts node that imitates a cluster server node to which connections will be performed. */
    private IgniteEx startClusterNode(int idx) throws Exception {
        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(idx));

        cfg.setSslContextFactory(sslContextFactory("server", "trustone", CIPHER_SUITE, "TLSv1.2"));

        cfg.setClientConnectorConfiguration(new ClientConnectorConfiguration()
            .setSslEnabled(true)
            .setSslClientAuth(true)
            .setUseIgniteSslContextFactory(false)
            .setSslContextFactory(sslContextFactory("thinServer", "trusttwo", CIPHER_SUITE, "TLSv1.2")));

        cfg.setConnectorConfiguration(new ConnectorConfiguration()
            .setSslClientAuth(true)
            .setSslEnabled(true)
            .setSslFactory(sslContextFactory("connectorServer", "trustthree", CIPHER_SUITE, "TLSv1.2")));

        return startGrid(cfg);
    }

    /** @return JDBC connection configuration with specified SSL options. */
    private String jdbcConfiguration(String keyStore, String trustStore, String cipherSuite, String protocol) {
        String res = "jdbc:ignite:thin://" + Config.SERVER + "?sslMode=require" +
            "&sslClientCertificateKeyStoreUrl=" + keyStorePath(keyStore) +
            "&sslClientCertificateKeyStorePassword=" + keyStorePassword() +
            "&sslTrustCertificateKeyStoreUrl=" + keyStorePath(trustStore) +
            "&sslTrustCertificateKeyStorePassword=" + keyStorePassword() +
            "&sslProtocol=" + protocol;

        if (cipherSuite != null)
            res += "&sslCipherSuites=" + cipherSuite;

        return res;
    }

    /** @return Node connection configuration with specified SSL options. */
    private IgniteConfiguration nodeConfiguration(
        int idx,
        boolean client,
        String keyStore,
        String trustStore,
        String cipherSuite,
        String protocol
    ) throws Exception {
       return getConfiguration(getTestIgniteInstanceName(idx))
            .setSslContextFactory(sslContextFactory(keyStore, trustStore, cipherSuite, protocol))
            .setClientMode(client);
    }

    /** @return Grid client connection configuration with specified SSL options. */
    private GridClientConfiguration gridClientConfiguration(
        String keyStore,
        String trustStore,
        String cipherSuite,
        String protocol
    ) {
        SslContextFactory sslCtxFactory = sslContextFactory(keyStore, trustStore, cipherSuite, protocol);

        return new GridClientConfiguration()
            .setServers(Collections.singleton("127.0.0.1:11211"))
            .setSslContextFactory(sslCtxFactory::create);
    }

    /** @return Thin client connection configuration with specified SSL options. */
    private ClientConfiguration clientConfiguration(
        String keyStore,
        String trustStore,
        String cipherSuite,
        String protocol
    ) {
       return new ClientConfiguration()
            .setAddresses("127.0.0.1:10800")
            .setSslMode(SslMode.REQUIRED)
            .setSslContextFactory(sslContextFactory(keyStore, trustStore, cipherSuite, protocol));
    }

    /** Asserts that the node join failed if the connection was performed with the specified SSL options. */
    @SuppressWarnings("ThrowableNotThrown")
    private void assertNodeJoinFails(
        int idx,
        boolean client,
        String keyStore,
        String trustStore,
        String cipherSuite,
        String protocol
    ) throws Exception {
        IgniteConfiguration cfg = nodeConfiguration(idx, client, keyStore, trustStore, cipherSuite, protocol);

        if (client)
            ((TcpDiscoverySpi)cfg.getDiscoverySpi()).setJoinTimeout(1); // To prevent client multiple join attempts.

        GridTestUtils.assertThrowsWithCause(() -> startGrid(cfg), IgniteCheckedException.class);
    }

    /** Obtains the metric registry with the specified name from Ignite instance. */
    private MetricRegistry mreg(IgniteEx ignite, String name) {
        return ignite.context().metric().registry(name);
    }

    /** Checks SSL communication metrics. */
    private void assertSslCommunicationMetrics(
        MetricRegistry mreg,
        long handshakeCnt,
        long sesCnt,
        long rejectedSesCnt
    ) throws Exception {
        assertEquals(true, mreg.<BooleanMetric>findMetric(SSL_ENABLED_METRIC_NAME).value());
        assertTrue(waitForCondition(() ->
            sesCnt == mreg.<IntMetric>findMetric(SESSIONS_CNT_METRIC_NAME).value(),
            getTestTimeout()));
        assertTrue(waitForCondition(() ->
            handshakeCnt == Arrays.stream(
                mreg.<HistogramMetric>findMetric(SSL_HANDSHAKE_DURATION_HISTOGRAM_METRIC_NAME).value()
            ).sum(),
            getTestTimeout()));
        assertTrue(waitForCondition(() ->
            rejectedSesCnt == mreg.<LongMetric>findMetric(SSL_REJECTED_SESSIONS_CNT_METRIC_NAME).value(),
            getTestTimeout()));
    }

    /** Creates {@link SslContextFactory} with specified options. */
    private SslContextFactory sslContextFactory(String keyStore, String trustStore, String cipherSuite, String protocol) {
        SslContextFactory res = (SslContextFactory)sslTrustedFactory(keyStore, trustStore);

        if (cipherSuite != null)
            res.setCipherSuites(cipherSuite);

        res.setProtocols(protocol);

        return res;
    }
}
