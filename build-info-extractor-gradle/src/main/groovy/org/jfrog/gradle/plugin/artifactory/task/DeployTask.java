package org.jfrog.gradle.plugin.artifactory.task;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.client.ArtifactoryUploadResponse;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployableArtifactsUtils;
import org.jfrog.build.extractor.retention.Utils;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryPluginUtil;
import org.jfrog.gradle.plugin.artifactory.extractor.*;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author Ruben Perez
 */
public class DeployTask extends DefaultTask {

    private static final Logger log = Logging.getLogger(DeployTask.class);

    private List<ModuleInfoFileProducer> moduleInfoFileProducers = new ArrayList<>();

    @TaskAction
    public void taskAction() throws IOException {
        log.debug("Task '{}' activated", getPath());
        collectProjectBuildInfo();
    }

    private void collectProjectBuildInfo() throws IOException {
        log.debug("Starting build info extraction for project '{}' using last task in graph '{}'",
                new Object[]{getProject().getPath(), getPath()});
        prepareAndDeploy();
        String propertyFilePath = System.getenv(BuildInfoConfigProperties.PROP_PROPS_FILE);
        if (StringUtils.isBlank(propertyFilePath)) {
            propertyFilePath = System.getenv(BuildInfoConfigProperties.ENV_BUILDINFO_PROPFILE);
        }
        if (StringUtils.isNotBlank(propertyFilePath)) {
            File file = new File(propertyFilePath);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    /**
     * This method will be activated only at the "end" of the build, when we reached the root project.
     *
     * @throws java.io.IOException In case the deployment fails.
     */
    private void prepareAndDeploy() throws IOException {
        ArtifactoryClientConfiguration accRoot =
                ArtifactoryPluginUtil.getArtifactoryConvention(getProject()).getClientConfig();

        Map<String, String> propsRoot = accRoot.publisher.getProps();

        // Reset the default properties, they may have changed
        GradleArtifactoryClientConfigUpdater.setMissingBuildAttributes(
                accRoot, getProject().getRootProject());

        Map<String, Set<DeployDetails>> allDeployDetails = new ConcurrentHashMap<>();
        List<ArtifactoryTask> orderedTasks = findArtifactoryPublishTasks(getProject().getGradle().getTaskGraph());

        int publishForkCount = getPublishForkCount(accRoot);
        if (publishForkCount <= 1) {
            orderedTasks.forEach(t -> deployArtifacts(accRoot, propsRoot, allDeployDetails, t, null));
        } else {
            try {
                ExecutorService executor = Executors.newFixedThreadPool(publishForkCount);
                CompletableFuture<Void> allUploads = CompletableFuture.allOf(orderedTasks.stream()
                        .map(t -> CompletableFuture.runAsync(() -> deployArtifacts(accRoot, propsRoot, allDeployDetails, t, "[" + Thread.currentThread().getName() + "]"), executor))
                        .toArray(CompletableFuture[]::new));
                allUploads.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        // Extract Build Info.
        GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(accRoot, moduleInfoFileProducers);
        Build build = gbie.extract(getProject().getRootProject());
        exportBuildInfo(build, getExportFile(accRoot));

        // Export generated.
        generateBuildInfoJson(accRoot, build);

        // Handle deployment.
        handleBuildInfoDeployment(accRoot, build, allDeployDetails);
    }

    private void generateBuildInfoJson(ArtifactoryClientConfiguration accRoot, Build build) throws IOException {
        if (isGenerateBuildInfoToFile(accRoot)) {
            try {
                exportBuildInfo(build, new File(accRoot.info.getGeneratedBuildInfoFilePath()));
            } catch (Exception e) {
                log.error("Failed writing build info to file: ", e);
                throw new IOException("Failed writing build info to file", e);
            }
        }
    }

    private void handleBuildInfoDeployment(ArtifactoryClientConfiguration accRoot, Build build, Map<String, Set<DeployDetails>> allDeployDetails) throws IOException {
        String contextUrl = accRoot.publisher.getContextUrl();
        if (contextUrl != null) {
            try (ArtifactoryManager artifactoryManager = new ArtifactoryManager(
                    accRoot.publisher.getContextUrl(),
                    accRoot.publisher.getUsername(),
                    accRoot.publisher.getPassword(),
                    new GradleClientLogger(log))) {

                if (isPublishBuildInfo(accRoot)) {
                    // If export property set always save the file before sending it to artifactory
                    exportBuildInfo(build, getExportFile(accRoot));
                    if (accRoot.info.isIncremental()) {
                        log.debug("Publishing build info modules to artifactory at: '{}'", contextUrl);
                        artifactoryManager.sendModuleInfo(build);
                    } else {
                        log.debug("Publishing build info to artifactory at: '{}'", contextUrl);
                        Utils.sendBuildAndBuildRetention(artifactoryManager, build, accRoot);
                    }
                }
                if (isGenerateDeployableArtifactsToFile(accRoot)) {
                    try {
                        exportDeployableArtifacts(allDeployDetails, new File(accRoot.info.getDeployableArtifactsFilePath()), accRoot.info.isBackwardCompatibleDeployableArtifacts());
                    } catch (Exception e) {
                        log.error("Failed writing deployable artifacts to file: ", e);
                        throw new RuntimeException("Failed writing deployable artifacts to file", e);
                    }
                }
            }
        }
    }

    private void deployArtifacts(ArtifactoryClientConfiguration accRoot, Map<String, String> propsRoot, Map<String,
            Set<DeployDetails>> allDeployDetails, ArtifactoryTask artifactoryTask, String logPrefix) {
        try {
            if (artifactoryTask.getDidWork()) {
                ArtifactoryClientConfiguration.PublisherHandler publisher =
                        ArtifactoryPluginUtil.getPublisherHandler(artifactoryTask.getProject());

                if (publisher != null && publisher.getContextUrl() != null) {
                    Map<String, String> moduleProps = new HashMap<String, String>(propsRoot);
                    moduleProps.putAll(publisher.getProps());
                    publisher.getProps().putAll(moduleProps);
                    String contextUrl = publisher.getContextUrl();
                    String username = publisher.getUsername();
                    String password = publisher.getPassword();
                    if (StringUtils.isBlank(username)) {
                        username = "";
                    }
                    if (StringUtils.isBlank(password)) {
                        password = "";
                    }

                    if (publisher.isPublishArtifacts()) {
                        try (ArtifactoryManager artifactoryManager = new ArtifactoryManager(contextUrl, username, password,
                                new GradleClientLogger(log))) {
                            log.debug("Uploading artifacts to Artifactory at '{}'", contextUrl);
                            IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                                    publisher.getIncludePatterns(),
                                    publisher.getExcludePatterns());
                            configureProxy(contextUrl, accRoot, artifactoryManager);
                            configConnectionTimeout(accRoot, artifactoryManager);
                            configRetriesParams(accRoot, artifactoryManager);
                            deployArtifacts(artifactoryTask.deployDetails, artifactoryManager, patterns, logPrefix, publisher.getMinChecksumDeploySizeKb());
                        }
                    }

                    if (!artifactoryTask.deployDetails.isEmpty()) {
                        Set<DeployDetails> deployDetailsSet = new LinkedHashSet<>();
                        for (GradleDeployDetails details : artifactoryTask.deployDetails) {
                            deployDetailsSet.add(details.getDeployDetails());
                        }
                        allDeployDetails.put(artifactoryTask.getProject().getName(), deployDetailsSet);
                    }
                }
            } else {
                log.debug("Task '{}' did no work", artifactoryTask.getPath());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    static void configureProxy(String contextUrl, ArtifactoryClientConfiguration clientConf, ArtifactoryManager artifactoryManager) {
        ArtifactoryClientConfiguration.ProxyHandler proxy = clientConf.proxy;
        String proxyHost = proxy.getHost();
        Integer proxyPort = proxy.getPort();

        boolean isHttps = !contextUrl.startsWith("http://");

        // If no proxyHost is explicitly set, check for the JVM system proxyHost property:
        if (StringUtils.isBlank(proxyHost)) {
            // Note: "http.nonProxyHosts" is used for both http and https, despite its prefix
            String systemNonProxyHostsString = System.getProperty("http.nonProxyHosts");
            String[] systemNonProxyHosts = StringUtils.split(systemNonProxyHostsString, '|');

            String contextUrlHost = getHost(contextUrl);
            boolean isContextUrlOnNonProxyList = false;
            for (String nonProxyHost : systemNonProxyHosts) {
                if (matchesProxyHostWildcardPattern(contextUrlHost, nonProxyHost)) {
                    isContextUrlOnNonProxyList = true;
                    break;
                }
            }

            if (!isContextUrlOnNonProxyList) {
                String systemPropertyName = isHttps ? "https.proxyHost" : "http.proxyHost";
                proxyHost = System.getProperty(systemPropertyName);
            }
        }

        // If no proxyPort is explicitly set, check for the JVM system proxyPort property:
        if (proxyPort == null) {
            String systemPropertyName = isHttps ? "https.proxyPort" : "http.proxyPort";
            String systemProxyPort = System.getProperty(systemPropertyName);
            if (StringUtils.isNotBlank(systemProxyPort)) {
                proxyPort = Integer.valueOf(systemProxyPort);
            }
        }

        if (StringUtils.isNotBlank(proxyHost) && proxyPort != null) {
            log.debug("Found proxy host '{}'", proxyHost);
            String proxyUserName = proxy.getUsername();
            if (StringUtils.isNotBlank(proxyUserName)) {
                log.debug("Found proxy user name '{}'", proxyUserName);
                artifactoryManager.setProxyConfiguration(proxyHost, proxyPort, proxyUserName, proxy.getPassword());
            } else {
                log.debug("No proxy user name and password found, using anonymous proxy");
                artifactoryManager.setProxyConfiguration(proxyHost, proxyPort);
            }
        }
    }

    private static String getHost(String url) {
        return url.contains("://") ? StringUtils.substringAfter(url, "://") : url;
    }

    /**
     * Checks whether a string matches a pattern with * wildcards. * is the only supported wildcard character.
     *
     * @param string  The string to check.
     * @param pattern The wildcard pattern to match with the string.
     * @return True if the string matches the wildcard pattern, or if the pattern contains no wildcards and is equal to
     * the string. False otherwise.
     */
    private static boolean matchesProxyHostWildcardPattern(@Nonnull String string, @Nonnull String pattern) {
        String[] patternParts = Arrays.stream(StringUtils.split(pattern, "*"))
                // Drop leading '.', so that string with "example.com" will match pattern part ".example.com":
                .map(it -> it.startsWith(".") ? it.substring(1) : it)
                .toArray(String[]::new);

        if (!pattern.startsWith("*") && !string.startsWith(patternParts[0])) {
            // Shortcut loop if string doesn't start with an exact match:
            return false;
        } else {
            String remainingString = string;
            for (String patternPart : patternParts) {
                if (remainingString.contains(patternPart)) {
                    remainingString = StringUtils.substringAfter(remainingString, patternPart);
                } else {
                    return false;
                }
            }
            return true;
        }
    }

    private void configConnectionTimeout(ArtifactoryClientConfiguration clientConf, ArtifactoryManager artifactoryManager) {
        if (clientConf.getTimeout() != null) {
            artifactoryManager.setConnectionTimeout(clientConf.getTimeout());
        }
    }

    private void configRetriesParams(ArtifactoryClientConfiguration clientConf, ArtifactoryManager artifactoryManager) {
        if (clientConf.getConnectionRetries() != null) {
            artifactoryManager.setConnectionRetries(clientConf.getConnectionRetries());
        }
    }

    private void exportBuildInfo(Build build, File toFile) throws IOException {
        log.debug("Exporting generated build info to '{}'", toFile.getAbsolutePath());
        BuildInfoExtractorUtils.saveBuildInfoToFile(build, toFile);
    }

    private void exportDeployableArtifacts(Map<String, Set<DeployDetails>> allDeployDetails, File toFile, boolean exportBackwardCompatibleDeployableArtifacts) throws IOException {
        log.debug("Exporting deployable artifacts to '{}'", toFile.getAbsolutePath());
        DeployableArtifactsUtils.saveDeployableArtifactsToFile(allDeployDetails, toFile, exportBackwardCompatibleDeployableArtifacts);
    }

    private File getExportFile(ArtifactoryClientConfiguration clientConf) {
        String fileExportPath = clientConf.getExportFile();
        if (StringUtils.isNotBlank(fileExportPath)) {
            return new File(fileExportPath);
        }
        Project rootProject = getProject().getRootProject();
        return new File(rootProject.getBuildDir(), "build-info.json");
    }

    @Nonnull
    private Boolean isPublishBuildInfo(ArtifactoryClientConfiguration acc) {
        return acc.publisher.isPublishBuildInfo();
    }

    private int getPublishForkCount(ArtifactoryClientConfiguration acc) {
        return acc.publisher.getPublishForkCount();
    }

    @Nonnull
    private Boolean isGenerateBuildInfoToFile(ArtifactoryClientConfiguration acc) {
        return !StringUtils.isEmpty(acc.info.getGeneratedBuildInfoFilePath());
    }

    @Nonnull
    private Boolean isGenerateDeployableArtifactsToFile(ArtifactoryClientConfiguration acc) {
        return !StringUtils.isEmpty(acc.info.getDeployableArtifactsFilePath());
    }

    private void deployArtifacts(Set<GradleDeployDetails> allDeployDetails, ArtifactoryManager artifactoryManager,
                                 IncludeExcludePatterns patterns, String logPrefix, int minChecksumDeploySizeKb)
            throws IOException {
        for (GradleDeployDetails detail : allDeployDetails) {
            DeployDetails deployDetails = detail.getDeployDetails();
            String artifactPath = deployDetails.getArtifactPath();
            if (PatternMatcher.pathConflicts(artifactPath, patterns)) {
                log.log(LogLevel.LIFECYCLE, "Skipping the deployment of '" + artifactPath +
                        "' due to the defined include-exclude patterns.");
                continue;
            }
            try {
                ArtifactoryUploadResponse response = artifactoryManager.upload(deployDetails, logPrefix, minChecksumDeploySizeKb);
                detail.getDeployDetails().setDeploySucceeded(true);
                detail.getDeployDetails().setSha256(response.getChecksums().getSha256());
            } catch (IOException e){
                detail.getDeployDetails().setDeploySucceeded(false);
                detail.getDeployDetails().setSha256("");
                throw e;
            }
        }
    }

    private List<ArtifactoryTask> findArtifactoryPublishTasks(TaskExecutionGraph graph) {
        List<ArtifactoryTask> tasks = new ArrayList<ArtifactoryTask>();
        for (Task task : graph.getAllTasks()) {
            if (task instanceof ArtifactoryTask) {
                tasks.add(((ArtifactoryTask) task));
            }
        }
        return tasks;
    }

    /**
     * Returns a file collection containing all of the module info files that this task aggregates.
     */
    @InputFiles
    public FileCollection getModuleInfoFiles() {
        ConfigurableFileCollection moduleInfoFiles = getProject().files();
        moduleInfoFileProducers.forEach(moduleInfoFileProducer -> {
            moduleInfoFiles.from(moduleInfoFileProducer.getModuleInfoFiles());
            moduleInfoFiles.builtBy(moduleInfoFileProducer.getModuleInfoFiles().getBuildDependencies());
        });
        return moduleInfoFiles;
    }

    /**
     * Registers a producer of module info files for this task to aggregate.
     *
     * @param moduleInfoFileProducer
     */
    public void registerModuleInfoProducer(ModuleInfoFileProducer moduleInfoFileProducer) {
        this.moduleInfoFileProducers.add(moduleInfoFileProducer);
    }
}
