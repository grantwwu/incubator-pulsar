/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin;

import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.bookkeeper.mledger.proto.PendingBookieOpsStats;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.pulsar.broker.admin.v1.BrokerStats;
import org.apache.pulsar.broker.admin.v1.Brokers;
import org.apache.pulsar.broker.admin.v1.Clusters;
import org.apache.pulsar.broker.admin.v1.Namespaces;
import org.apache.pulsar.broker.admin.v1.PersistentTopics;
import org.apache.pulsar.broker.admin.v1.Properties;
import org.apache.pulsar.broker.admin.v1.ResourceQuotas;
import org.apache.pulsar.broker.admin.v2.SchemasResource;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.cache.ConfigurationCacheService;
import org.apache.pulsar.broker.web.PulsarWebResource;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.common.conf.InternalConfigurationData;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.AutoFailoverPolicyData;
import org.apache.pulsar.common.policies.data.AutoFailoverPolicyType;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.NamespaceIsolationData;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.ResourceQuota;
import org.apache.pulsar.common.stats.AllocatorStats;
import org.apache.pulsar.common.stats.Metrics;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.policies.data.loadbalancer.LocalBrokerData;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooDefs.Ids;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class AdminTest extends MockedPulsarServiceBaseTest {
    private final String configClusterName = "use";
    private ConfigurationCacheService configurationCache;
    private Clusters clusters;
    private Properties properties;
    private Namespaces namespaces;
    private PersistentTopics persistentTopics;
    private Brokers brokers;
    private ResourceQuotas resourceQuotas;
    private BrokerStats brokerStats;
    private SchemasResource schemasResource;
    private Field uriField;
    private Clock mockClock = Clock.fixed(
        Instant.ofEpochSecond(365248800),
        ZoneId.of("-05:00")
    );

    public AdminTest() {
        super();
        conf.setClusterName(configClusterName);
    }

    @Override
    @BeforeMethod
    public void setup() throws Exception {
        super.internalSetup();

        configurationCache = pulsar.getConfigurationCache();

        clusters = spy(new Clusters());
        clusters.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(clusters).globalZk();
        doReturn(configurationCache.clustersCache()).when(clusters).clustersCache();
        doReturn(configurationCache.clustersListCache()).when(clusters).clustersListCache();
        doReturn(configurationCache.namespaceIsolationPoliciesCache()).when(clusters).namespaceIsolationPoliciesCache();
        doReturn("test").when(clusters).clientAppId();
        doNothing().when(clusters).validateSuperUserAccess();

        properties = spy(new Properties());
        properties.setServletContext(new MockServletContext());
        properties.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(properties).globalZk();
        doReturn(configurationCache.propertiesCache()).when(properties).tenantsCache();
        doReturn("test").when(properties).clientAppId();
        doNothing().when(properties).validateSuperUserAccess();

        namespaces = spy(new Namespaces());
        namespaces.setServletContext(new MockServletContext());
        namespaces.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(namespaces).globalZk();
        doReturn(mockZookKeeper).when(namespaces).localZk();
        doReturn(configurationCache.propertiesCache()).when(namespaces).tenantsCache();
        doReturn(configurationCache.policiesCache()).when(namespaces).policiesCache();
        doReturn("test").when(namespaces).clientAppId();
        doReturn(Sets.newTreeSet(Lists.newArrayList("use", "usw", "usc", "global"))).when(namespaces).clusters();
        doNothing().when(namespaces).validateAdminAccessForTenant("my-tenant");
        doNothing().when(namespaces).validateAdminAccessForTenant("other-tenant");
        doNothing().when(namespaces).validateAdminAccessForTenant("new-property");

        brokers = spy(new Brokers());
        brokers.setServletContext(new MockServletContext());
        brokers.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(brokers).globalZk();
        doReturn(mockZookKeeper).when(brokers).localZk();
        doReturn(configurationCache.clustersListCache()).when(brokers).clustersListCache();
        doReturn("test").when(brokers).clientAppId();
        doNothing().when(brokers).validateSuperUserAccess();

        uriField = PulsarWebResource.class.getDeclaredField("uri");
        uriField.setAccessible(true);

        persistentTopics = spy(new PersistentTopics());
        persistentTopics.setServletContext(new MockServletContext());
        persistentTopics.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(persistentTopics).globalZk();
        doReturn(mockZookKeeper).when(persistentTopics).localZk();
        doReturn(configurationCache.propertiesCache()).when(persistentTopics).tenantsCache();
        doReturn(configurationCache.policiesCache()).when(persistentTopics).policiesCache();
        doReturn("test").when(persistentTopics).clientAppId();
        doReturn("persistent").when(persistentTopics).domain();
        doReturn(Sets.newTreeSet(Lists.newArrayList("use", "usw", "usc"))).when(persistentTopics).clusters();
        doNothing().when(persistentTopics).validateAdminAccessForTenant("my-tenant");
        doNothing().when(persistentTopics).validateAdminAccessForTenant("other-tenant");
        doNothing().when(persistentTopics).validateAdminAccessForTenant("prop-xyz");

        resourceQuotas = spy(new ResourceQuotas());
        resourceQuotas.setServletContext(new MockServletContext());
        resourceQuotas.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(resourceQuotas).globalZk();
        doReturn(mockZookKeeper).when(resourceQuotas).localZk();
        doReturn(configurationCache.propertiesCache()).when(resourceQuotas).tenantsCache();
        doReturn(configurationCache.policiesCache()).when(resourceQuotas).policiesCache();

        brokerStats = spy(new BrokerStats());
        brokerStats.setServletContext(new MockServletContext());
        brokerStats.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(brokerStats).globalZk();
        doReturn(mockZookKeeper).when(brokerStats).localZk();
        doReturn(configurationCache.propertiesCache()).when(brokerStats).tenantsCache();
        doReturn(configurationCache.policiesCache()).when(brokerStats).policiesCache();

        schemasResource = spy(new SchemasResource(mockClock));
        schemasResource.setServletContext(new MockServletContext());
        schemasResource.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(schemasResource).globalZk();
        doReturn(mockZookKeeper).when(schemasResource).localZk();
        doReturn(configurationCache.propertiesCache()).when(schemasResource).tenantsCache();
        doReturn(configurationCache.policiesCache()).when(schemasResource).policiesCache();
    }

    @Override
    @AfterMethod
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    void internalConfiguration() throws Exception {
        InternalConfigurationData expectedData = new InternalConfigurationData(
            pulsar.getConfiguration().getZookeeperServers(),
            pulsar.getConfiguration().getConfigurationStoreServers(),
            new ClientConfiguration().getZkLedgersRootPath(),
            pulsar.getWorkerConfig().map(wc -> wc.getStateStorageServiceUrl()).orElse(null));

        assertEquals(brokers.getInternalConfigurationData(), expectedData);
    }

    @Test
    void clusters() throws Exception {
        assertEquals(clusters.getClusters(), Lists.newArrayList());
        verify(clusters, never()).validateSuperUserAccess();

        clusters.createCluster("use", new ClusterData("http://broker.messaging.use.example.com"));
        verify(clusters, times(1)).validateSuperUserAccess();
        // ensure to read from ZooKeeper directly
        clusters.clustersListCache().clear();
        assertEquals(clusters.getClusters(), Lists.newArrayList("use"));

        // Check creating existing cluster
        try {
            clusters.createCluster("use", new ClusterData("http://broker.messaging.use.example.com"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }

        // Check deleting non-existing cluster
        try {
            clusters.deleteCluster("usc");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        assertEquals(clusters.getCluster("use"), new ClusterData("http://broker.messaging.use.example.com"));
        verify(clusters, times(4)).validateSuperUserAccess();

        clusters.updateCluster("use", new ClusterData("http://new-broker.messaging.use.example.com"));
        verify(clusters, times(5)).validateSuperUserAccess();

        assertEquals(clusters.getCluster("use"), new ClusterData("http://new-broker.messaging.use.example.com"));
        verify(clusters, times(6)).validateSuperUserAccess();

        try {
            clusters.getNamespaceIsolationPolicies("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 404);
        }
        NamespaceIsolationData policyData = new NamespaceIsolationData();
        policyData.namespaces = new ArrayList<String>();
        policyData.namespaces.add("dummy/colo/ns");
        policyData.primary = new ArrayList<String>();
        policyData.primary.add("localhost" + ":" + BROKER_WEBSERVICE_PORT);
        policyData.secondary = new ArrayList<String>();
        policyData.auto_failover_policy = new AutoFailoverPolicyData();
        policyData.auto_failover_policy.policy_type = AutoFailoverPolicyType.min_available;
        policyData.auto_failover_policy.parameters = new HashMap<String, String>();
        policyData.auto_failover_policy.parameters.put("min_limit", "1");
        policyData.auto_failover_policy.parameters.put("usage_threshold", "90");
        clusters.setNamespaceIsolationPolicy("use", "policy1", policyData);
        clusters.getNamespaceIsolationPolicies("use");

        try {
            clusters.deleteCluster("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 412);
        }

        clusters.deleteNamespaceIsolationPolicy("use", "policy1");
        assertTrue(clusters.getNamespaceIsolationPolicies("use").isEmpty());

        clusters.deleteCluster("use");
        verify(clusters, times(13)).validateSuperUserAccess();
        assertEquals(clusters.getClusters(), Lists.newArrayList());

        try {
            clusters.getCluster("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 404);
        }

        try {
            clusters.updateCluster("use", new ClusterData());
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 404);
        }

        try {
            clusters.getNamespaceIsolationPolicies("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 404);
        }

        // Test zk failures
        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        configurationCache.clustersListCache().clear();
        try {
            clusters.getClusters();
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            clusters.createCluster("test", new ClusterData("http://broker.messaging.test.example.com"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            clusters.updateCluster("test", new ClusterData("http://broker.messaging.test.example.com"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            clusters.getCluster("test");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failAfter(0, Code.SESSIONEXPIRED);
        try {
            clusters.deleteCluster("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failAfter(1, Code.SESSIONEXPIRED);
        try {
            clusters.deleteCluster("use");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        // Check name validations
        try {
            clusters.createCluster("bf@", new ClusterData("http://dummy.messaging.example.com"));
            fail("should have filed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }
    }

    @Test
    void properties() throws Exception {
        assertEquals(properties.getTenants(), Lists.newArrayList());
        verify(properties, times(1)).validateSuperUserAccess();

        Set<String> allowedClusters = Sets.newHashSet();
        TenantInfo tenantInfo = new TenantInfo(Sets.newHashSet("role1", "role2"), allowedClusters);
        properties.createTenant("test-property", tenantInfo);
        verify(properties, times(2)).validateSuperUserAccess();

        assertEquals(properties.getTenants(), Lists.newArrayList("test-property"));
        verify(properties, times(3)).validateSuperUserAccess();

        assertEquals(properties.getTenantAdmin("test-property"), tenantInfo);
        verify(properties, times(4)).validateSuperUserAccess();

        TenantInfo newPropertyAdmin = new TenantInfo(Sets.newHashSet("role1", "other-role"), allowedClusters);
        properties.updateTenant("test-property", newPropertyAdmin);
        verify(properties, times(5)).validateSuperUserAccess();

        // Wait for updateTenant to take effect
        Thread.sleep(100);

        assertEquals(properties.getTenantAdmin("test-property"), newPropertyAdmin);
        assertNotSame(properties.getTenantAdmin("test-property"), tenantInfo);
        verify(properties, times(7)).validateSuperUserAccess();

        // Check creating existing property
        try {
            properties.createTenant("test-property", tenantInfo);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }

        // Check non-existing property
        try {
            properties.getTenantAdmin("non-existing");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        try {
            properties.updateTenant("xxx-non-existing", newPropertyAdmin);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        // Check deleting non-existing property
        try {
            properties.deleteTenant("non-existing");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        // Test zk failures
        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            properties.getTenants();
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            properties.getTenantAdmin("my-tenant");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            properties.updateTenant("my-tenant", newPropertyAdmin);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            properties.createTenant("test", tenantInfo);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            properties.deleteTenant("my-tenant");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        properties.createTenant("error-property", tenantInfo);
        mockZookKeeper.failAfter(2, Code.SESSIONEXPIRED);
        try {
            properties.deleteTenant("error-property");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        }

        properties.deleteTenant("test-property");
        properties.deleteTenant("error-property");
        assertEquals(properties.getTenants(), Lists.newArrayList());

        // Create a namespace to test deleting a non-empty property
        clusters.createCluster("use", new ClusterData());
        newPropertyAdmin = new TenantInfo(Sets.newHashSet("role1", "other-role"), Sets.newHashSet("use"));
        properties.createTenant("my-tenant", newPropertyAdmin);

        namespaces.createNamespace("my-tenant", "use", "my-namespace", new BundlesData());

        try {
            properties.deleteTenant("my-tenant");
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

        // Check name validation
        try {
            properties.createTenant("test&", tenantInfo);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        // Check tenantInfo is null
        TenantInfo nullTenantInfo = new TenantInfo();
        properties.createTenant("tenant-config-is-null", null);
        assertEquals(properties.getTenantAdmin("tenant-config-is-null"), nullTenantInfo);

        AsyncResponse response = mock(AsyncResponse.class);
        namespaces.deleteNamespace(response, "my-tenant", "use", "my-namespace", false);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(response, timeout(5000).times(1)).resume(captor.capture());
        assertEquals(captor.getValue().getStatus(), Status.OK.getStatusCode());
        properties.deleteTenant("my-tenant");
        properties.deleteTenant("tenant-config-is-null");
    }

    @Test
    void brokers() throws Exception {
        clusters.createCluster("use", new ClusterData("http://broker.messaging.use.example.com",
                "https://broker.messaging.use.example.com:4443"));

        URI requestUri = new URI(
                "http://broker.messaging.use.example.com" + ":" + BROKER_WEBSERVICE_PORT + "/admin/brokers/use");
        UriInfo mockUri = mock(UriInfo.class);
        doReturn(requestUri).when(mockUri).getRequestUri();
        Field uriField = PulsarWebResource.class.getDeclaredField("uri");
        uriField.setAccessible(true);
        uriField.set(brokers, mockUri);

        Set<String> activeBrokers = brokers.getActiveBrokers("use");
        assertEquals(activeBrokers.size(), 1);
        assertEquals(activeBrokers, Sets.newHashSet(pulsar.getAdvertisedAddress() + ":" + BROKER_WEBSERVICE_PORT));
    }

    @Test
    void resourceQuotas() throws Exception {
        // get Default Resource Quota
        ResourceQuota quota = resourceQuotas.getDefaultResourceQuota();
        assertNotNull(quota);
        assertTrue(quota.getBandwidthIn() > 0);

        // set Default Resource Quota
        double defaultBandwidth = 1000;
        quota.setBandwidthIn(defaultBandwidth);
        quota.setBandwidthOut(defaultBandwidth);
        resourceQuotas.setDefaultResourceQuota(quota);
        assertTrue(resourceQuotas.getDefaultResourceQuota().getBandwidthIn() == defaultBandwidth);
        assertTrue(resourceQuotas.getDefaultResourceQuota().getBandwidthOut() == defaultBandwidth);

        String property = "prop-xyz";
        String cluster = "use";
        String namespace = "ns";
        String bundleRange = "0x00000000_0xffffffff";
        Policies policies = new Policies();
        doReturn(policies).when(resourceQuotas).getNamespacePolicies(NamespaceName.get(property, cluster, namespace));
        doReturn("client-id").when(resourceQuotas).clientAppId();

        try {
            resourceQuotas.setNamespaceBundleResourceQuota(property, cluster, namespace, bundleRange, quota);
            fail();
        } catch (Exception e) {
            // OK : should fail without creating policies
        }

        try {
            resourceQuotas.removeNamespaceBundleResourceQuota(property, cluster, namespace, bundleRange);
            fail();
        } catch (Exception e) {
            // OK : should fail without creating policies
        }

        // create policies
        TenantInfo admin = new TenantInfo();
        admin.getAllowedClusters().add(cluster);
        mockZookKeeper.create(PulsarWebResource.path(POLICIES, property),
                ObjectMapperFactory.getThreadLocal().writeValueAsBytes(admin), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        // customized bandwidth for this namespace
        double customizeBandwidth = 3000;
        quota.setBandwidthIn(customizeBandwidth);
        quota.setBandwidthOut(customizeBandwidth);

        // set and get Resource Quota
        resourceQuotas.setNamespaceBundleResourceQuota(property, cluster, namespace, bundleRange, quota);
        ResourceQuota bundleQuota = resourceQuotas.getNamespaceBundleResourceQuota(property, cluster, namespace,
                bundleRange);
        assertEquals(quota, bundleQuota);

        // remove quota which sets to default quota
        resourceQuotas.removeNamespaceBundleResourceQuota(property, cluster, namespace, bundleRange);
        bundleQuota = resourceQuotas.getNamespaceBundleResourceQuota(property, cluster, namespace, bundleRange);
        assertTrue(bundleQuota.getBandwidthIn() == defaultBandwidth);
        assertTrue(bundleQuota.getBandwidthOut() == defaultBandwidth);
    }

    @Test
    void brokerStats() throws Exception {
        doReturn("client-id").when(brokerStats).clientAppId();
        Collection<Metrics> metrics = brokerStats.getMetrics();
        assertNotNull(metrics);
        LocalBrokerData loadReport = (LocalBrokerData) brokerStats.getLoadReport();
        assertNotNull(loadReport);
        assertNotNull(loadReport.getCpu());
        Collection<Metrics> mBeans = brokerStats.getMBeans();
        assertTrue(!mBeans.isEmpty());
        AllocatorStats allocatorStats = brokerStats.getAllocatorStats("default");
        assertNotNull(allocatorStats);
        Map<String, Map<String, PendingBookieOpsStats>> bookieOpsStats = brokerStats.getPendingBookieOpsStats();
        assertTrue(bookieOpsStats.isEmpty());
        StreamingOutput topic = brokerStats.getTopics2();
        assertNotNull(topic);
        try {
            brokerStats.getBrokerResourceAvailability("prop", "use", "ns2");
            fail("should have failed as ModularLoadManager doesn't support it");
        } catch (RestException re) {
            // Ok
        }
    }

    @Test
    void persistentTopics() throws Exception {

        final String property = "prop-xyz";
        final String cluster = "use";
        final String namespace = "ns";
        final String topic = "ds1";
        Policies policies = new Policies();
        doReturn(policies).when(resourceQuotas).getNamespacePolicies(NamespaceName.get(property, cluster, namespace));
        doReturn("client-id").when(resourceQuotas).clientAppId();
        // create policies
        TenantInfo admin = new TenantInfo();
        admin.getAllowedClusters().add(cluster);
        ZkUtils.createFullPathOptimistic(mockZookKeeper, PulsarWebResource.path(POLICIES, property, cluster, namespace),
                ObjectMapperFactory.getThreadLocal().writeValueAsBytes(new Policies()), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        AsyncResponse response = mock(AsyncResponse.class);
        persistentTopics.getList(response, property, cluster, namespace);
        verify(response, times(1)).resume(Lists.newArrayList());
        // create topic
        assertEquals(persistentTopics.getPartitionedTopicList(property, cluster, namespace), Lists.newArrayList());
        persistentTopics.createPartitionedTopic(property, cluster, namespace, topic, 5);
        assertEquals(persistentTopics.getPartitionedTopicList(property, cluster, namespace), Lists
                .newArrayList(String.format("persistent://%s/%s/%s/%s", property, cluster, namespace, topic)));

        CountDownLatch notificationLatch = new CountDownLatch(2);
        configurationCache.policiesCache().registerListener((path, data, stat) -> {
            notificationLatch.countDown();
        });

        // grant permission
        final Set<AuthAction> actions = Sets.newHashSet(AuthAction.produce);
        final String role = "test-role";
        persistentTopics.grantPermissionsOnTopic(property, cluster, namespace, topic, role, actions);
        // verify permission
        Map<String, Set<AuthAction>> permission = persistentTopics.getPermissionsOnTopic(property, cluster,
                namespace, topic);
        assertEquals(permission.get(role), actions);
        // remove permission
        persistentTopics.revokePermissionsOnTopic(property, cluster, namespace, topic, role);

        // Wait for cache to be updated
        notificationLatch.await();

        // verify removed permission
        permission = persistentTopics.getPermissionsOnTopic(property, cluster, namespace, topic);
        assertTrue(permission.isEmpty());
    }

    @Test
    public void testRestExceptionMessage() {
        String message = "my-message";
        RestException exception = new RestException(Status.PRECONDITION_FAILED, message);
        assertEquals(exception.getMessage(), message);

    }

}
