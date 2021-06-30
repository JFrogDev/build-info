/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jfrog.build.extractor.clientConfiguration;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jfrog.build.api.*;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.api.util.Log;
import org.jfrog.build.extractor.clientConfiguration.util.IssuesTrackerUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Predicate;

import static org.jfrog.build.api.BuildInfoConfigProperties.*;
import static org.jfrog.build.api.BuildInfoFields.*;
import static org.jfrog.build.api.BuildInfoProperties.*;
import static org.jfrog.build.api.IssuesTrackerFields.*;
import static org.jfrog.build.api.LicenseControlFields.AUTO_DISCOVER;
import static org.jfrog.build.api.LicenseControlFields.VIOLATION_RECIPIENTS;
import static org.jfrog.build.extractor.ModuleParallelDeployHelper.DEFAULT_DEPLOYMENT_THREADS;
import static org.jfrog.build.extractor.clientConfiguration.ClientConfigurationFields.*;
import static org.jfrog.build.extractor.clientConfiguration.ClientProperties.*;

/**
 * @author freds
 */
public class ArtifactoryClientConfiguration {
    // Try checksum deploy of files greater than 10KB
    public static final transient int DEFAULT_MIN_CHECKSUM_DEPLOY_SIZE_KB = 10;
    public static final String DEFAULT_NUGET_PROTOCOL = "v2";

    public final ResolverHandler resolver;
    public final PublisherHandler publisher;
    public final BuildInfoHandler info;
    public final ProxyHandler proxy;
    public final PackageManagerHandler packageManagerHandler;
    public final NpmHandler npmHandler;
    public final PipHandler pipHandler;
    public final DotnetHandler dotnetHandler;
    public final DockerHandler dockerHandler;
    public final PrefixPropertyHandler root;
    /**
     * To configure the props builder itself, so all method of this classes delegated from here
     */
    private final PrefixPropertyHandler rootConfig;

    public ArtifactoryClientConfiguration(Log log) {
        this.root = new PrefixPropertyHandler(log, new ConcurrentSkipListMap<String, String>());
        this.rootConfig = new PrefixPropertyHandler(root, BUILD_INFO_CONFIG_PREFIX);
        this.resolver = new ResolverHandler();
        this.publisher = new PublisherHandler();
        this.info = new BuildInfoHandler();
        this.proxy = new ProxyHandler();
        this.packageManagerHandler = new PackageManagerHandler();
        this.npmHandler = new NpmHandler();
        this.pipHandler = new PipHandler();
        this.dotnetHandler = new DotnetHandler();
        this.dockerHandler = new DockerHandler();
    }

    public void fillFromProperties(Map<String, String> props, IncludeExcludePatterns patterns) {
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String varKey = entry.getKey();
            if (PatternMatcher.pathConflicts(varKey, patterns)) {
                continue;
            }
            root.setStringValue(varKey, entry.getValue());
        }
    }

    /**
     * Add properties to the client configuration.
     *
     * @param props The properties to be added.
     */
    public void fillFromProperties(Properties props) {
        fillFromProperties(props, null);
    }

    /**
     * Add properties to the client configuration, excluding specific properties, if they already exist in the client configuration.
     *
     * @param props                  The properties to be added to the client configuration.
     * @param excludeIfAlreadyExists A collection of property names which will not be added to the client configuration
     *                               if they already exist in it.
     */
    public void fillFromProperties(Properties props, Set<String> excludeIfAlreadyExists) {
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            String key = (String) entry.getKey();
            if (excludeIfAlreadyExists == null ||
                    !excludeIfAlreadyExists.contains(key) || root.getStringValue(key) == null) {
                root.setStringValue(key, (String) entry.getValue());
            }
        }
    }

    public Map<String, String> getAllProperties() {
        return root.props;
    }

    public Map<String, String> getAllRootConfig() {
        return rootConfig.props;
    }

    public Log getLog() {
        return root.getLog();
    }

    public void persistToPropertiesFile() {
        if (StringUtils.isEmpty(getPropertiesFile())) {
            return;
        }
        Properties props = new Properties();
        props.putAll(filterMapNullValues(root.props));
        props.putAll(filterMapNullValues(rootConfig.props));
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(new File(getPropertiesFile()).getCanonicalFile());
            props.store(fos, "BuildInfo configuration property file");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            IOUtils.closeQuietly(fos);
        }
    }

    public static Map<String, String> filterMapNullValues(Map<String, String> map) {
        Map<String, String> result = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (StringUtils.isNotBlank(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * A fallback method for backward compatibility. If publisher/resolver context url is requested but not found this
     * method is called.
     *
     * @return URL of Artifactory server from the property artifactory.contextUrl
     * @deprecated Use only as a fallback when explicit publisher/resolver context url is missing
     */
    @Deprecated
    public String getContextUrl() {
        return root.getStringValue(PROP_CONTEXT_URL);
    }

    public void setTimeoutSec(Integer timeout) {
        setTimeout(timeout);
    }

    public Integer getTimeout() {
        return root.getIntegerValue(PROP_TIMEOUT);
    }

    public void setTimeout(Integer timeout) {
        root.setIntegerValue(PROP_TIMEOUT, timeout);
    }

    public void setConnectionRetries(Integer connectionRetries) {
        root.setIntegerValue(PROP_CONNECTION_RETRIES, connectionRetries);
    }

    public Integer getConnectionRetries() {
        return root.getIntegerValue(PROP_CONNECTION_RETRIES);
    }

    public boolean getInsecureTls() {
        return root.getBooleanValue(PROP_INSECURE_TLS, false);
    }

    public Integer getSocketTimeout() {
        return root.getIntegerValue(PROP_SO_TIMEOUT);
    }

    public void setSocketTimeout(Integer socketTimeout) {
        root.setIntegerValue(PROP_SO_TIMEOUT, socketTimeout);
    }

    public Integer getMaxTotalConnection() {
        return root.getIntegerValue(PROP_MAX_TOTAL_CO);
    }

    public void setMaxTotalConnection(Integer maxTotalConnection) {
        root.setIntegerValue(PROP_MAX_TOTAL_CO, maxTotalConnection);
    }

    public Integer getMaxConnectionPerRoute() {
        return root.getIntegerValue(PROP_MAX_CO_PER_ROUTE);
    }

    public void setMaxConnectionPerRoute(Integer maxConnectionPerRoute) {
        root.setIntegerValue(PROP_MAX_CO_PER_ROUTE, maxConnectionPerRoute);
    }

    public String getPropertiesFile() {
        return rootConfig.getStringValue(PROPERTIES_FILE);
    }

    public void setPropertiesFile(String propertyFile) {
        rootConfig.setStringValue(PROPERTIES_FILE, propertyFile);
    }

    public String getExportFile() {
        return rootConfig.getStringValue(EXPORT_FILE);
    }

    public void setExportFile(String exportFile) {
        rootConfig.setStringValue(EXPORT_FILE, exportFile);
    }

    public void setIncludeEnvVars(Boolean enabled) {
        rootConfig.setBooleanValue(INCLUDE_ENV_VARS, enabled);
    }

    public Boolean isIncludeEnvVars() {
        return rootConfig.getBooleanValue(INCLUDE_ENV_VARS, false);
    }

    public String getEnvVarsIncludePatterns() {
        return rootConfig.getStringValue(ENV_VARS_INCLUDE_PATTERNS);
    }

    public void setEnvVarsIncludePatterns(String patterns) {
        rootConfig.setStringValue(ENV_VARS_INCLUDE_PATTERNS, patterns);
    }

    public String getEnvVarsExcludePatterns() {
        return rootConfig.getStringValue(ENV_VARS_EXCLUDE_PATTERNS);
    }

    public void setEnvVarsExcludePatterns(String patterns) {
        rootConfig.setStringValue(ENV_VARS_EXCLUDE_PATTERNS, patterns);
    }

    public void setActivateRecorder(Boolean activateRecorder) {
        root.setBooleanValue(ACTIVATE_RECORDER, activateRecorder);
    }

    public Boolean isActivateRecorder() {
        return root.getBooleanValue(ACTIVATE_RECORDER, false);
    }

    public class ResolverHandler extends RepositoryConfiguration {

        public ResolverHandler() {
            super(PROP_RESOLVE_PREFIX);
        }

        public String getDownloadUrl() {
            // Legacy property from Gradle plugin apply from technique
            return root.getStringValue("artifactory.downloadUrl");
        }

        @SuppressWarnings({"deprecation"})
        public String getContextUrl() {
            String contextUrl = getStringValue(CONTEXT_URL);
            if (StringUtils.isBlank(contextUrl)) {
                // fallback to root contextUrl for backward compatibility
                contextUrl = ArtifactoryClientConfiguration.this.getContextUrl();
            }
            return contextUrl;
        }

        public void setContextUrl(String contextUrl) {
            setStringValue(CONTEXT_URL, contextUrl);
        }

        public boolean isIvyRepositoryDefined() {
            return root.getBooleanValue(IVY_REPO_DEFINED, false);
        }

        public void setIvyRepositoryDefined(boolean ivyRepositoryDefined) {
            root.setBooleanValue(IVY_REPO_DEFINED, ivyRepositoryDefined);
        }

        public String getBuildRoot() {
            return getMatrixParams().get(BUILD_ROOT);
        }

        public void setBuildRoot(String buildRoot) {
            addMatrixParam(BUILD_ROOT, buildRoot);
        }

        @Override
        public String getMatrixParamPrefix() {
            return getPrefix() + MATRIX;
        }

        public String getDownloadSnapshotRepoKey() {
            return getStringValue(DOWN_SNAPSHOT_REPO_KEY);
        }

        public void setDownloadSnapshotRepoKey(String repoKey) {
            setStringValue(DOWN_SNAPSHOT_REPO_KEY, repoKey);
        }
    }

    public class PublisherHandler extends RepositoryConfiguration {
        public PublisherHandler() {
            super(PROP_PUBLISH_PREFIX);
        }

        @SuppressWarnings({"deprecation"})
        public String getContextUrl() {
            String contextUrl = getStringValue(CONTEXT_URL);
            if (StringUtils.isBlank(contextUrl)) {
                // fallback to root contextUrl for backward compatibility
                contextUrl = ArtifactoryClientConfiguration.this.getContextUrl();
            }
            return contextUrl;
        }

        public void setContextUrl(String contextUrl) {
            setStringValue(CONTEXT_URL, contextUrl);
        }

        public String getSnapshotRepoKey() {
            return getStringValue(SNAPSHOT_REPO_KEY);
        }

        public void setSnapshotRepoKey(String repoKey) {
            setStringValue(SNAPSHOT_REPO_KEY, repoKey);
        }

        public String getReleaseRepoKey() {
            return getStringValue(RELEASE_REPO_KEY);
        }

        public void setReleaseRepoKey(String repoKey) {
            setStringValue(RELEASE_REPO_KEY, repoKey);
        }

        public void setPublishArtifacts(Boolean enabled) {
            setBooleanValue(PUBLISH_ARTIFACTS, enabled);
        }

        public Boolean isPublishArtifacts() {
            return getBooleanValue(PUBLISH_ARTIFACTS, true);
        }

        public void setPublishBuildInfo(Boolean enabled) {
            setBooleanValue(PUBLISH_BUILD_INFO, enabled);
        }

        public Boolean isPublishBuildInfo() {
            return getBooleanValue(PUBLISH_BUILD_INFO, true);
        }

        public void setPublishForkCount(int value) {
            setIntegerValue(PUBLISH_FORK_COUNT, value);
        }

        public Integer getPublishForkCount() {
            return getIntegerValue(PUBLISH_FORK_COUNT, DEFAULT_DEPLOYMENT_THREADS);
        }

        public boolean isRecordAllDependencies() {
            return getBooleanValue(RECORD_ALL_DEPENDENCIES, false);
        }

        public void setRecordAllDependencies(Boolean enabled) {
            setBooleanValue(RECORD_ALL_DEPENDENCIES, enabled);
        }

        public boolean isRecordImmediateDependencies() {
            return getBooleanValue(RECORD_IMMEDIATE_DEPENDENCIES, false);
        }

        public void setRecordImmediateDependencies(Boolean enabled) {
            setBooleanValue(RECORD_IMMEDIATE_DEPENDENCIES, enabled);
        }

        public String getIncludePatterns() {
            return getStringValue(INCLUDE_PATTERNS);
        }

        public void setIncludePatterns(String patterns) {
            setStringValue(INCLUDE_PATTERNS, patterns);
        }

        public boolean isFilterExcludedArtifactsFromBuild() {
            return getBooleanValue(FILTER_EXCLUDED_ARTIFACTS_FROM_BUILD, false);
        }

        public void setFilterExcludedArtifactsFromBuild(boolean excludeArtifactsFromBuild) {
            setBooleanValue(FILTER_EXCLUDED_ARTIFACTS_FROM_BUILD, excludeArtifactsFromBuild);
        }

        public String getExcludePatterns() {
            return getStringValue(EXCLUDE_PATTERNS);
        }

        public void setExcludePatterns(String patterns) {
            setStringValue(EXCLUDE_PATTERNS, patterns);
        }

        public void setEvenUnstable(Boolean enabled) {
            setBooleanValue(EVEN_UNSTABLE, enabled);
        }

        public Boolean isEvenUnstable() {
            return getBooleanValue(EVEN_UNSTABLE, false);
        }

        public String getBuildRoot() {
            return getMatrixParams().get(BUILD_ROOT);
        }

        public void setBuildRoot(String buildRoot) {
            addMatrixParam(BUILD_ROOT, buildRoot);
        }

        @Override
        public String getMatrixParamPrefix() {
            return PROP_DEPLOY_PARAM_PROP_PREFIX;
        }

        public ArtifactSpecs getArtifactSpecs() {
            String specs = getStringValue(ARTIFACT_SPECS);
            return new ArtifactSpecs(specs);
        }

        public void setArtifactSpecs(String artifactSpecs) {
            setStringValue(ARTIFACT_SPECS, artifactSpecs);
        }

        public String getPublications() {
            return getStringValue(PUBLICATIONS);
        }

        public void setPublications(String publications) {
            setStringValue(PUBLICATIONS, publications);
        }

        public int getMinChecksumDeploySizeKb() {
            return getIntegerValue(MIN_CHECKSUM_DEPLOY_SIZE_KB, DEFAULT_MIN_CHECKSUM_DEPLOY_SIZE_KB);
        }

        public void setMinChecksumDeploySizeKb(int minChecksumDeploySizeKb) {
            setIntegerValue(MIN_CHECKSUM_DEPLOY_SIZE_KB, minChecksumDeploySizeKb);
        }
    }

    public class ProxyHandler extends AuthenticationConfiguration {
        public ProxyHandler() {
            super(PROP_PROXY_PREFIX);
        }

        // TODO: Support proxy type SSL or not

        public String getHost() {
            return getStringValue(HOST);
        }

        public void setHost(String host) {
            setStringValue(HOST, host);
        }

        public Integer getPort() {
            return getIntegerValue(PORT);
        }

        public void setPort(Integer port) {
            setIntegerValue(PORT, port);
        }
    }

    // Used by other build tools (npm, pip...).
    public class PackageManagerHandler extends PrefixPropertyHandler {

        public PackageManagerHandler() {
            super(root, PROP_PACKAGE_MANAGER_PREFIX);
        }

        public String getArgs() {
            return rootConfig.getStringValue(PACKAGE_MANAGER_ARGS);
        }

        public void setArgs(String packageManagerArgs) {
            rootConfig.setStringValue(PACKAGE_MANAGER_ARGS, packageManagerArgs);
        }

        public String getPath() {
            return rootConfig.getStringValue(PACKAGE_MANAGER_PATH);
        }

        public void setPath(String packageManagerPath) {
            rootConfig.setStringValue(PACKAGE_MANAGER_PATH, packageManagerPath);
        }

        public String getModule() {
            return rootConfig.getStringValue(PACKAGE_MANAGER_MODULE);
        }

        public void setModule(String packageManagerModule) {
            rootConfig.setStringValue(PACKAGE_MANAGER_MODULE, packageManagerModule);
        }
    }

    public class NpmHandler extends PrefixPropertyHandler {
        public NpmHandler() {
            super(root, PROP_NPM_PREFIX);
        }

        public boolean isCiCommand() {
            return rootConfig.getBooleanValue(NPM_CI_COMMAND, false);
        }

        public void setCiCommand(boolean ciCommand) {
            rootConfig.setBooleanValue(NPM_CI_COMMAND, ciCommand);
        }
    }

    public class PipHandler extends PrefixPropertyHandler {
        public PipHandler() {
            super(root, PROP_PIP_PREFIX);
        }

        public String getEnvActivation() {
            return rootConfig.getStringValue(PIP_ENV_ACTIVATION);
        }

        public void setEnvActivation(String envActivation) {
            rootConfig.setStringValue(PIP_ENV_ACTIVATION, envActivation);
        }
    }

    public class DotnetHandler extends PrefixPropertyHandler {
        public DotnetHandler() {
            super(root, PROP_DOTNET_PREFIX);
        }

        public boolean useDotnetCoreCli() {
            return rootConfig.getBooleanValue(DOTNET_USE_DOTNET_CORE_CLI, false);
        }

        public void setUseDotnetCli(boolean useDotnetCli) {
            rootConfig.setBooleanValue(DOTNET_USE_DOTNET_CORE_CLI, useDotnetCli);
        }

        public String apiProtocol() {
            return rootConfig.getStringValue(DOTNET_NUGET_PROTOCOL, DEFAULT_NUGET_PROTOCOL);
        }

        public void setApiProtocol(String apiProtocol) {
            rootConfig.setStringValue(DOTNET_NUGET_PROTOCOL, apiProtocol);
        }
    }

    public class DockerHandler extends PrefixPropertyHandler {
        public DockerHandler() {
            super(root, PROP_DOCKER_PREFIX);
        }

        public String getImageTag() {
            return rootConfig.getStringValue(DOCKER_IMAGE_TAG);
        }

        public void setImageTag(String imageTag) {
            rootConfig.setStringValue(DOCKER_IMAGE_TAG, imageTag);
        }

        public String getHost() {
            return rootConfig.getStringValue(DOCKER_HOST);
        }

        public void setHost(String host) {
            rootConfig.setStringValue(DOCKER_HOST, host);
        }

        public String getKanikoImageFile() {
            return rootConfig.getStringValue(KANIKO_IMAGE_FILE);
        }

        public void setKanikoImageFile(String kanikoImageFile) {
            rootConfig.setStringValue(KANIKO_IMAGE_FILE, kanikoImageFile);
        }

        public String getJibImageFile() {
            return rootConfig.getStringValue(JIB_IMAGE_FILE);
        }

        public void setJibImageFile(String jibImageFile) {
            rootConfig.setStringValue(JIB_IMAGE_FILE, jibImageFile);
        }
    }

    public class AuthenticationConfiguration extends PrefixPropertyHandler {
        public AuthenticationConfiguration(String prefix) {
            super(root, prefix);
        }

        public String getUsername() {
            return getStringValue(USERNAME);
        }

        public void setUsername(String userName) {
            setStringValue(USERNAME, userName);
        }

        public String getPassword() {
            return getStringValue(PASSWORD);
        }

        public void setPassword(String password) {
            setStringValue(PASSWORD, password);
        }
    }

    public abstract class RepositoryConfiguration extends AuthenticationConfiguration {

        private ImmutableMap<String, String> calculatedMatrixParams;

        protected RepositoryConfiguration(String prefix) {
            super(prefix);
        }

        public String getName() {
            return getStringValue(NAME);
        }

        public void setName(String name) {
            setStringValue(NAME, name);
        }

        public String urlWithMatrixParams(String rootUrl) {
            if (rootUrl == null) {
                return null;
            }
            rootUrl = StringUtils.stripEnd(rootUrl, "/;");
            Map<String, String> matrixParams = getMatrixParams();
            if (matrixParams.isEmpty()) {
                return rootUrl;
            } else {
                StringBuilder builder = new StringBuilder(rootUrl);
                for (Map.Entry<String, String> entry : matrixParams.entrySet()) {
                    builder.append(';').append(entry.getKey()).append('=').append(entry.getValue());
                }
                builder.append(';');
                return builder.toString();
            }
        }

        public String getUrlWithMatrixParams() {
            return urlWithMatrixParams(getUrl());
        }

        public String getUrl() {
            String value = getStringValue(URL);
            if (StringUtils.isBlank(value)) {
                String repoKey = getRepoKey();
                if (StringUtils.isNotBlank(repoKey)) {
                    String contextUrl = getContextUrl();
                    if (StringUtils.isNotBlank(contextUrl)) {
                        contextUrl = StringUtils.stripEnd(contextUrl, "/ ");
                        return contextUrl + "/" + getRepoKey();
                    }
                }
            }
            return StringUtils.removeEnd(value, "/");
        }

        public void setUrl(String url) {
            setStringValue(URL, url);
        }

        public String getUrl(String repo) {
            String value = getStringValue(URL);
            if (StringUtils.isBlank(value)) {
                if (StringUtils.isNotBlank(repo)) {
                    String contextUrl = getContextUrl();
                    if (StringUtils.isNotBlank(contextUrl)) {
                        contextUrl = StringUtils.stripEnd(contextUrl, "/ ");
                        return contextUrl + "/" + repo;
                    }
                }
            }
            return StringUtils.removeEnd(value, "/");
        }

        public String getRepoKey() {
            return getStringValue(REPO_KEY);
        }

        public void setRepoKey(String repoKey) {
            setStringValue(REPO_KEY, repoKey);
        }

        /**
         * In the context of a publisher it is used to publish a pom. In the context of a resolver it is used to add a
         * maven resolver (e.g. in Gradle).
         *
         * @param enabled true for enabling Maven resolution
         */
        public void setMaven(boolean enabled) {
            setBooleanValue(MAVEN, enabled);
        }

        public Boolean isMaven() {
            return getBooleanValue(MAVEN, null);
        }

        public void setIvy(Boolean enabled) {
            setBooleanValue(IVY, enabled);
        }

        public Boolean isIvy() {
            return getBooleanValue(IVY, null);
        }

        public boolean isM2Compatible() {
            return getBooleanValue(IVY_M2_COMPATIBLE, true);
        }

        public void setM2Compatible(Boolean enabled) {
            setBooleanValue(IVY_M2_COMPATIBLE, enabled);
        }

        public String getIvyArtifactPattern() {
            String value = getStringValue(IVY_ART_PATTERN);
            if (StringUtils.isBlank(value)) {
                return LayoutPatterns.M2_PATTERN;
            }
            return value.trim();
        }

        public void setIvyArtifactPattern(String artPattern) {
            setStringValue(IVY_ART_PATTERN, artPattern);
        }

        public String getIvyPattern() {
            String value = getStringValue(IVY_IVY_PATTERN);
            if (StringUtils.isBlank(value)) {
                return LayoutPatterns.DEFAULT_IVY_PATTERN;
            }
            return value.trim();
        }

        public void setIvyPattern(String ivyPattern) {
            setStringValue(IVY_IVY_PATTERN, ivyPattern);
        }

        public abstract String getMatrixParamPrefix();

        public abstract String getContextUrl();

        public void setMatrixParam(String key, String value) {
            putMatrixParam(key, value, true);
        }

        public void addMatrixParam(String key, String value) {
            putMatrixParam(key, value, false);
        }

        private void putMatrixParam(String key, String value, boolean override) {
            if (StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
                return;
            }
            String matrixParamPrefix = getMatrixParamPrefix();
            if (!key.startsWith(matrixParamPrefix)) {
                key = matrixParamPrefix + key;
            }
            if (!override && props.get(key) != null) {
                value = props.get(key) + "," + value;
            }
            props.put(key, value);
        }

        public String getMatrixParam(String key) {
            if (StringUtils.isBlank(key)) {
                return null;
            }
            String matrixParamPrefix = getMatrixParamPrefix();
            if (!key.startsWith(matrixParamPrefix)) {
                key = matrixParamPrefix + key;
            }
            return props.get(key);
        }

        // INTERNAL METHOD
        public void addMatrixParams(Map<String, String> vars) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                addMatrixParam(entry.getKey(), entry.getValue());
            }
        }

        public void addMatrixParams(ArrayListMultimap<String, String> vars) {
            for (Map.Entry<String, String> entry : vars.entries()) {
                addMatrixParam(entry.getKey(), entry.getValue());
            }
        }

        public ImmutableMap<String, String> getMatrixParams() {
            if (calculatedMatrixParams != null) {
                return calculatedMatrixParams;
            }
            Map<String, String> result = new HashMap<>();
            String matrixPrefix = getMatrixParamPrefix();
            for (Map.Entry<String, String> entry : props.entrySet()) {
                if (entry.getKey().startsWith(matrixPrefix)) {
                    result.put(entry.getKey().substring(matrixPrefix.length()), entry.getValue());
                }
            }
            this.calculatedMatrixParams = ImmutableMap.copyOf(result);
            return calculatedMatrixParams;
        }
    }

    public class LicenseControlHandler extends PrefixPropertyHandler {
        public LicenseControlHandler() {
            super(root, BUILD_INFO_LICENSE_CONTROL_PREFIX);
        }

        public void setRunChecks(Boolean enabled) {
            setBooleanValue(LicenseControlFields.RUN_CHECKS, enabled);
        }

        public Boolean isRunChecks() {
            return getBooleanValue(LicenseControlFields.RUN_CHECKS, false);
        }

        public String getViolationRecipients() {
            return getStringValue(VIOLATION_RECIPIENTS);
        }

        public void setViolationRecipients(String recipients) {
            setStringValue(VIOLATION_RECIPIENTS, recipients);
        }

        public void setIncludePublishedArtifacts(Boolean enabled) {
            setBooleanValue(LicenseControlFields.INCLUDE_PUBLISHED_ARTIFACTS, enabled);
        }

        public Boolean isIncludePublishedArtifacts() {
            return getBooleanValue(LicenseControlFields.INCLUDE_PUBLISHED_ARTIFACTS, false);
        }

        public String getScopes() {
            return getStringValue(LicenseControlFields.SCOPES);
        }

        public void setScopes(String scopes) {
            setStringValue(LicenseControlFields.SCOPES, scopes);
        }

        public void setAutoDiscover(Boolean enabled) {
            setBooleanValue(AUTO_DISCOVER, enabled);
        }

        public Boolean isAutoDiscover() {
            return getBooleanValue(AUTO_DISCOVER, false);
        }
    }

    public class IssuesTrackerHandler extends PrefixPropertyHandler {
        public IssuesTrackerHandler() {
            super(root, BUILD_INFO_ISSUES_TRACKER_PREFIX);
        }

        public String getIssueTrackerName() {
            return getStringValue(ISSUES_TRACKER_NAME);
        }

        public void setIssueTrackerName(String issueTrackerName) {
            setStringValue(ISSUES_TRACKER_NAME, issueTrackerName);
        }

        public String getIssueTrackerVersion() {
            return getStringValue(ISSUES_TRACKER_VERSION);
        }

        public void setIssueTrackerVersion(String issueTrackerVersion) {
            setStringValue(ISSUES_TRACKER_VERSION, issueTrackerVersion);
        }

        public boolean getAggregateBuildIssues() {
            return getBooleanValue(AGGREGATE_BUILD_ISSUES, Boolean.FALSE);
        }

        public void setAggregateBuildIssues(boolean aggregateBuildIssues) {
            setBooleanValue(AGGREGATE_BUILD_ISSUES, aggregateBuildIssues);
        }

        public String getAggregationBuildStatus() {
            return getStringValue(AGGREGATION_BUILD_STATUS);
        }

        public void setAggregationBuildStatus(String aggregationBuildStatus) {
            setStringValue(AGGREGATION_BUILD_STATUS, aggregationBuildStatus);
        }

        public String getAffectedIssues() {
            return getStringValue(AFFECTED_ISSUES);
        }

        public void setAffectedIssues(String affectedIssues) {
            setStringValue(AFFECTED_ISSUES, affectedIssues);
        }

        public Set<Issue> getAffectedIssuesSet() {
            return IssuesTrackerUtils.getAffectedIssuesSet(getAffectedIssues());
        }
    }

    public class BlackDuckPropertiesHandler extends PrefixPropertyHandler {
        public BlackDuckPropertiesHandler() {
            super(root, BUILD_INFO_BLACK_DUCK_PROPERTIES_PREFIX);
        }

        public boolean isRunChecks() {
            return getBooleanValue(BlackDuckPropertiesFields.RUN_CHECKS, false);
        }

        public void setRunChecks(boolean blackDuckRunChecks) {
            setBooleanValue(BlackDuckPropertiesFields.RUN_CHECKS, blackDuckRunChecks);
        }

        public String getAppName() {
            return getStringValue(BlackDuckPropertiesFields.APP_NAME);
        }

        public void setAppName(String blackDuckAppName) {
            setStringValue(BlackDuckPropertiesFields.APP_NAME, blackDuckAppName);
        }

        public String getAppVersion() {
            return getStringValue(BlackDuckPropertiesFields.APP_VERSION);
        }

        public void setAppVersion(String blackDuckAppVersion) {
            setStringValue(BlackDuckPropertiesFields.APP_VERSION, blackDuckAppVersion);
        }

        public String getReportRecipients() {
            return getStringValue(BlackDuckPropertiesFields.REPORT_RECIPIENTS);
        }

        public void setReportRecipients(String reportRecipients) {
            setStringValue(BlackDuckPropertiesFields.REPORT_RECIPIENTS, reportRecipients);
        }

        public String getScopes() {
            return getStringValue(BlackDuckPropertiesFields.SCOPES);
        }

        public void setScopes(String scopes) {
            setStringValue(BlackDuckPropertiesFields.SCOPES, scopes);
        }

        public boolean isIncludePublishedArtifacts() {
            return getBooleanValue(BlackDuckPropertiesFields.INCLUDE_PUBLISHED_ARTIFACTS, Boolean.TRUE);
        }

        public void setIncludePublishedArtifacts(boolean includePublishedArtifacts) {
            setBooleanValue(BlackDuckPropertiesFields.INCLUDE_PUBLISHED_ARTIFACTS, includePublishedArtifacts);
        }

        public boolean isAutoCreateMissingComponentRequests() {
            return getBooleanValue(BlackDuckPropertiesFields.AutoCreateMissingComponentRequests, Boolean.TRUE);
        }

        public void setAutoCreateMissingComponentRequests(boolean autoCreateMissingComponentRequests) {
            setBooleanValue(BlackDuckPropertiesFields.AutoCreateMissingComponentRequests,
                    autoCreateMissingComponentRequests);
        }

        public boolean isAutoDiscardStaleComponentRequests() {
            return getBooleanValue(BlackDuckPropertiesFields.AutoDiscardStaleComponentRequests, Boolean.TRUE);
        }

        public void setAutoDiscardStaleComponentRequests(boolean autoDiscardStaleComponentRequests) {
            setBooleanValue(BlackDuckPropertiesFields.AutoDiscardStaleComponentRequests,
                    autoDiscardStaleComponentRequests);
        }

        public BlackDuckProperties copyBlackDuckProperties() {
            BlackDuckProperties blackDuckProperties = new BlackDuckProperties();
            blackDuckProperties.setRunChecks(isRunChecks());
            blackDuckProperties.setAppName(getAppName());
            blackDuckProperties.setAppVersion(getAppVersion());
            blackDuckProperties.setReportRecipients(getReportRecipients());
            blackDuckProperties.setScopes(getScopes());
            blackDuckProperties.setIncludePublishedArtifacts(isIncludePublishedArtifacts());
            blackDuckProperties.setAutoCreateMissingComponentRequests(isAutoCreateMissingComponentRequests());
            blackDuckProperties.setAutoDiscardStaleComponentRequests(isAutoDiscardStaleComponentRequests());
            return blackDuckProperties;
        }
    }

    public class BuildInfoHandler extends PrefixPropertyHandler {
        public final LicenseControlHandler licenseControl = new LicenseControlHandler();
        public final IssuesTrackerHandler issues = new IssuesTrackerHandler();
        public final BlackDuckPropertiesHandler blackDuckProperties = new BlackDuckPropertiesHandler();

        private final Predicate<String> buildVariablesPredicate;
        private final Predicate<String> buildRunParametersPredicate;

        public BuildInfoHandler() {
            super(root, BUILD_INFO_PREFIX);
            buildVariablesPredicate = input -> input.startsWith(BUILD_INFO_PREFIX + ENVIRONMENT_PREFIX);
            buildRunParametersPredicate = input -> input.startsWith(BUILD_INFO_PREFIX + RUN_PARAMETERS);
        }

        public String getProject() {
            return getStringValue(BUILD_PROJECT);
        }

        public void setProject(String project) {
            setStringValue(BUILD_PROJECT, project);
        }

        public String getBuildName() {
            return getStringValue(BUILD_NAME);
        }

        public void setBuildName(String buildName) {
            setStringValue(BUILD_NAME, buildName);
        }

        public String getBuildNumber() {
            return getStringValue(BUILD_NUMBER);
        }

        public void setBuildNumber(String buildNumber) {
            setStringValue(BUILD_NUMBER, buildNumber);
        }

        public String getBuildTimestamp() {
            return getStringValue(BUILD_TIMESTAMP);
        }

        public void setBuildTimestamp(String timestamp) {
            setStringValue(BUILD_TIMESTAMP, timestamp);
        }

        public void setBuildStarted(String isoStarted) {
            setStringValue(BUILD_STARTED, isoStarted);
        }

        public String getBuildStarted() {
            return getStringValue(BUILD_STARTED);
        }

        public void setBuildStarted(long timestamp) {
            setBuildStarted(Build.formatBuildStarted(timestamp));
        }

        public String getPrincipal() {
            return getStringValue(PRINCIPAL);
        }

        public void setPrincipal(String principal) {
            setStringValue(PRINCIPAL, principal);
        }

        public String getArtifactoryPluginVersion() {
            return getStringValue(ARTIFACTORY_PLUGIN_VERSION);
        }

        public void setArtifactoryPluginVersion(String artifactoryPluginVersion) {
            setStringValue(ARTIFACTORY_PLUGIN_VERSION, artifactoryPluginVersion);
        }

        public String getBuildUrl() {
            return getStringValue(BUILD_URL);
        }

        public void setBuildUrl(String buildUrl) {
            setStringValue(BUILD_URL, buildUrl);
        }

        public String getVcsRevision() {
            return getStringValue(VCS_REVISION);
        }

        public void setVcsRevision(String vcsRevision) {
            setStringValue(VCS_REVISION, vcsRevision);
        }

        public String getVcsUrl() {
            return getStringValue(VCS_URL);
        }

        public void setVcsUrl(String vcsUrl) {
            setStringValue(VCS_URL, vcsUrl);
        }

        public String getAgentName() {
            return getStringValue(AGENT_NAME);
        }

        public void setAgentName(String agentName) {
            setStringValue(AGENT_NAME, agentName);
        }

        public String getAgentVersion() {
            return getStringValue(AGENT_VERSION);
        }

        public void setAgentVersion(String agentVersion) {
            setStringValue(AGENT_VERSION, agentVersion);
        }

        public String getBuildAgentName() {
            return getStringValue(BUILD_AGENT_NAME);
        }

        public void setBuildAgentName(String buildAgentName) {
            setStringValue(BUILD_AGENT_NAME, buildAgentName);
        }

        public String getBuildAgentVersion() {
            return getStringValue(BUILD_AGENT_VERSION);
        }

        public void setBuildAgentVersion(String buildAgentVersion) {
            setStringValue(BUILD_AGENT_VERSION, buildAgentVersion);
        }

        public String getParentBuildName() {
            return getStringValue(BUILD_PARENT_NAME);
        }

        public void setParentBuildName(String parentBuildName) {
            setStringValue(BUILD_PARENT_NAME, parentBuildName);
        }

        public String getParentBuildNumber() {
            return getStringValue(BUILD_PARENT_NUMBER);
        }

        public void setParentBuildNumber(String parentBuildNumber) {
            setStringValue(BUILD_PARENT_NUMBER, parentBuildNumber);
        }

        public void setDeleteBuildArtifacts(Boolean deleteBuildArtifacts) {
            setBooleanValue(DELETE_BUILD_ARTIFACTS, deleteBuildArtifacts);
        }

        public Boolean isDeleteBuildArtifacts() {
            return getBooleanValue(DELETE_BUILD_ARTIFACTS, true);
        }

        public void setAsyncBuildRetention(Boolean asyncBuildRetention) {
            setBooleanValue(BUILD_RETENTION_ASYNC, asyncBuildRetention);
        }

        public Boolean isAsyncBuildRetention() {
            return getBooleanValue(BUILD_RETENTION_ASYNC, false);
        }

        public void setBuildRetentionMaxDays(Integer daysToKeep) {
            setBuildRetentionDays(daysToKeep);
        }

        public Integer getBuildRetentionDays() {
            return getIntegerValue(BUILD_RETENTION_DAYS);
        }

        public void setBuildRetentionDays(Integer daysToKeep) {
            setIntegerValue(BUILD_RETENTION_DAYS, daysToKeep);
        }

        public Integer getBuildRetentionCount() {
            return getIntegerValue(BUILD_RETENTION_COUNT);
        }

        public void setBuildRetentionCount(Integer buildsToKeep) {
            setIntegerValue(BUILD_RETENTION_COUNT, buildsToKeep);
        }

        public String getBuildRetentionMinimumDate() {
            return getStringValue(BUILD_RETENTION_MINIMUM_DATE);
        }

        public void setBuildRetentionMinimumDate(String date) {
            setStringValue(BUILD_RETENTION_MINIMUM_DATE, date);
        }

        public String[] getBuildNumbersNotToDelete() {
            String value = getStringValue(BUILD_NUMBERS_NOT_TO_DELETE);
            if (StringUtils.isNotBlank(value)) {
                return StringUtils.split(value, ",");
            }
            return new String[0];
        }

        public void setBuildNumbersNotToDelete(String buildNumbersNotToDelete) {
            setStringValue(BUILD_NUMBERS_NOT_TO_DELETE, buildNumbersNotToDelete);
        }

        public String getReleaseComment() {
            return getStringValue(RELEASE_COMMENT);
        }

        public void setReleaseComment(String comment) {
            setStringValue(RELEASE_COMMENT, comment);
        }

        public void setReleaseEnabled(Boolean enabled) {
            setBooleanValue(RELEASE_ENABLED, enabled);
        }

        public Boolean isReleaseEnabled() {
            return getBooleanValue(RELEASE_ENABLED, false);
        }

        public String getBuildRoot() {
            return getStringValue(BUILD_ROOT);
        }

        public void setBuildRoot(String buildRoot) throws UnsupportedEncodingException {
            publisher.setBuildRoot(buildRoot);
            resolver.setBuildRoot(URLEncoder.encode(buildRoot, "UTF-8"));
            setStringValue(BUILD_ROOT, buildRoot);
        }

        public void setGeneratedBuildInfoFilePath(String generatedBuildInfo) {
            setStringValue(GENERATED_BUILD_INFO, generatedBuildInfo);
        }

        public String getGeneratedBuildInfoFilePath() {
            return getStringValue(GENERATED_BUILD_INFO);
        }

        public void setDeployableArtifactsFilePath(String deployableArtifacts) {
            setStringValue(DEPLOYABLE_ARTIFACTS, deployableArtifacts);
        }

        @Deprecated
        public void setBackwardCompatibleDeployableArtifactsFilePath(String deployableArtifacts) {
            setStringValue(BACKWARD_COMPATIBLE_DEPLOYABLE_ARTIFACTS, deployableArtifacts);
        }

        public String getDeployableArtifactsFilePath() {
            String path = getStringValue(DEPLOYABLE_ARTIFACTS);
            return StringUtils.isNotEmpty(path) ? path : getStringValue(BACKWARD_COMPATIBLE_DEPLOYABLE_ARTIFACTS);
        }

        public boolean isBackwardCompatibleDeployableArtifacts() {
            return StringUtils.isEmpty(getStringValue(DEPLOYABLE_ARTIFACTS));
        }

        public void addBuildVariables(Map<String, String> buildVariables, IncludeExcludePatterns patterns) {
            for (Map.Entry<String, String> entry : buildVariables.entrySet()) {
                String varKey = entry.getKey();
                if (PatternMatcher.pathConflicts(varKey, patterns)) {
                    continue;
                }
                addEnvironmentProperty(varKey, entry.getValue());
            }
        }

        public void addEnvironmentProperty(String key, String value) {
            setStringValue(ENVIRONMENT_PREFIX + key, value);
        }

        /*
         * Use for Multi-configuration/Matrix builds
         */
        public void addRunParameters(String key, String value) {
            setStringValue(RUN_PARAMETERS + key, value);
        }

        public Map<String, String> getRunParameters() {
            Map<String, String> tempMap = CommonUtils.filterMapKeys(props, buildRunParametersPredicate);
            Map<String, String> runParameters = new HashMap<>();
            for (Map.Entry<String, String> param : tempMap.entrySet()) {
                runParameters.put(param.getKey().replace(BUILD_INFO_PREFIX + RUN_PARAMETERS, StringUtils.EMPTY),
                        param.getValue());
            }

            return runParameters;
        }

        public Boolean isIncremental() {
            return getBooleanValue(INCREMENTAL, Boolean.FALSE);
        }

        public void setIncremental(Boolean incremental) {
            setBooleanValue(INCREMENTAL, incremental);
        }
    }
}
