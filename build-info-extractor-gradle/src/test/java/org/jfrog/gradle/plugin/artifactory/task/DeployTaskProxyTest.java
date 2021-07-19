package org.jfrog.gradle.plugin.artifactory.task;

import org.jfrog.build.api.util.Log;
import org.jfrog.build.api.util.NullLog;
import org.jfrog.build.client.ProxyConfiguration;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.testng.annotations.*;

import javax.annotation.Nullable;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

@Test
public class DeployTaskProxyTest {

    private static final String HTTPS_PROXY_HOST_SETTING = "https.proxyHost";
    private static final String HTTPS_PROXY_PORT_SETTING = "https.proxyPort";
    private static final String HTTP_PROXY_HOST_SETTING = "http.proxyHost";
    private static final String HTTP_PROXY_PORT_SETTING = "http.proxyPort";
    private static final String HTTP_NON_PROXY_HOSTS_SETTING = "http.nonProxyHosts";

    private final Log log = new NullLog();

    private String systemHttpsProxyHost = null;
    private String systemHttpsProxyPort = null;
    private String systemHttpProxyHost = null;
    private String systemHttpProxyPort = null;
    private String systemHttpNonProxyHosts = null;

    @BeforeMethod
    public void copySystemProperties() {
        systemHttpsProxyHost = System.getProperty(HTTPS_PROXY_HOST_SETTING);
        systemHttpsProxyPort = System.getProperty(HTTPS_PROXY_PORT_SETTING);
        systemHttpProxyHost = System.getProperty(HTTP_PROXY_HOST_SETTING);
        systemHttpProxyPort = System.getProperty(HTTP_PROXY_PORT_SETTING);
        systemHttpNonProxyHosts = System.getProperty(HTTP_NON_PROXY_HOSTS_SETTING);
    }

    @AfterMethod
    public void restoreSystemProperties() {
        setOrClearSystemProperty(HTTPS_PROXY_HOST_SETTING, systemHttpsProxyHost);
        setOrClearSystemProperty(HTTPS_PROXY_PORT_SETTING, systemHttpsProxyPort);
        setOrClearSystemProperty(HTTP_PROXY_HOST_SETTING, systemHttpProxyHost);
        setOrClearSystemProperty(HTTP_PROXY_PORT_SETTING, systemHttpProxyPort);
        setOrClearSystemProperty(HTTP_NON_PROXY_HOSTS_SETTING, systemHttpNonProxyHosts);
    }

    private static void setOrClearSystemProperty(String key, @Nullable String value) {
        if (value == null)
            System.clearProperty(key);
        else
            System.setProperty(key, value);
    }

    @Test
    public void givenExplicitProxyHostAndPort_usesExplicitProxyHostAndPort() {
        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        clientConfig.proxy.setHost("proxy-host");
        clientConfig.proxy.setPort(9999);

        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("", clientConfig, artifactoryManager);

        ProxyConfiguration result = artifactoryManager.getProxyConfiguration();
        assertEquals(result.host, "proxy-host");
        assertEquals(result.port, 9999);
    }

    @Test
    public void givenExplicitProxyHostButNoPort_doesNotUseProxy() {
        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        clientConfig.proxy.setHost("proxy-host");

        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenExplicitProxyPortButNoHost_doesNotUseProxy() {
        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        clientConfig.proxy.setPort(9999);

        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenExplicitAndSystemProxyHostAndPort_usesExplicitProxyHostAndPort() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "wrong-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(4444));

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        clientConfig.proxy.setHost("proxy-host");
        clientConfig.proxy.setPort(9999);

        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("", clientConfig, artifactoryManager);

        ProxyConfiguration result = artifactoryManager.getProxyConfiguration();
        assertEquals(result.host, "proxy-host");
        assertEquals(result.port, 9999);
    }

    @Test
    public void givenSystemProxyHostAndPort_usesSystemProxyHostAndPort() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(9999));

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("", clientConfig, artifactoryManager);

        ProxyConfiguration result = artifactoryManager.getProxyConfiguration();
        assertEquals(result.host, "proxy-host");
        assertEquals(result.port, 9999);
    }

    @Test
    public void givenSystemProxyHostAndPort_whenContextUrlIsHttp_usesSystemHttpProxyHostAndPort() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("http://example.com/artifacts", clientConfig, artifactoryManager);

        ProxyConfiguration result = artifactoryManager.getProxyConfiguration();
        assertEquals(result.host, "http-proxy-host");
        assertEquals(result.port, 2222);
    }

    @Test
    public void givenSystemProxyHostAndPort_whenContextUrlIsHttps_usesSystemHttpsProxyHostAndPort() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        ProxyConfiguration result = artifactoryManager.getProxyConfiguration();
        assertEquals(result.host, "https-proxy-host");
        assertEquals(result.port, 1111);
    }

    @Test
    public void givenSystemProxySettings_whenContextUrlMatchesNonProxyHostsExactly_doesNotUseProxy() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));
        System.setProperty(HTTP_NON_PROXY_HOSTS_SETTING, "example.com|other");

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenSystemProxySettings_whenContextUrlMatchesNonProxyHostsWithDot_doesNotUseProxy() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));
        System.setProperty(HTTP_NON_PROXY_HOSTS_SETTING, ".example.com|other");

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenSystemProxySettings_whenContextUrlMatchesNonProxyHostsByLeadingWildcard_doesNotUseProxy() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));
        System.setProperty(HTTP_NON_PROXY_HOSTS_SETTING, "other|*.example.com");

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenSystemProxySettings_whenContextUrlMatchesNonProxyHostsByCentralWildcard_doesNotUseProxy() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));
        System.setProperty(HTTP_NON_PROXY_HOSTS_SETTING, "other|ex*ple.com");

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }

    @Test
    public void givenSystemProxySettings_whenContextUrlMatchesNonProxyHostsWithSlash_doesNotUseProxy() {
        System.setProperty(HTTPS_PROXY_HOST_SETTING, "https-proxy-host");
        System.setProperty(HTTPS_PROXY_PORT_SETTING, String.valueOf(1111));
        System.setProperty(HTTP_PROXY_HOST_SETTING, "http-proxy-host");
        System.setProperty(HTTP_PROXY_PORT_SETTING, String.valueOf(2222));
        System.setProperty(HTTP_NON_PROXY_HOSTS_SETTING, "other|example.com/artifacts");

        ArtifactoryClientConfiguration clientConfig = new ArtifactoryClientConfiguration(log);
        ArtifactoryManager artifactoryManager = new ArtifactoryManager("", log);

        DeployTask.configureProxy("https://example.com/artifacts", clientConfig, artifactoryManager);

        assertNull(artifactoryManager.getProxyConfiguration());
    }
}
