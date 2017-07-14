package org.jfrog.gradle.plugin.artifactory.task;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import groovy.lang.Closure;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.ConfigureUtil;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.util.DeployableArtifactsUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpecs;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.client.ArtifactoryBuildInfoClient;
import org.jfrog.build.extractor.retention.Utils;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryPluginUtil;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.dsl.PropertiesConfig;
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleArtifactoryClientConfigUpdater;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleBuildInfoExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleClientLogger;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Date: 3/20/13 Time: 10:32 AM
 *
 * @author freds
 */
public abstract class BuildInfoBaseTask extends DefaultTask {
    public static final String BUILD_INFO_TASK_NAME = "artifactoryPublish";
    public static final String PUBLISH_ARTIFACTS = "publishArtifacts";
    public static final String PUBLISH_BUILD_INFO = "publishBuildInfo";
    public static final String PUBLISH_IVY = "publishIvy";
    public static final String PUBLISH_POM = "publishPom";

    private static final Logger log = Logging.getLogger(BuildInfoBaseTask.class);
    private final Map<String, Boolean> flags = Maps.newHashMap();
    private boolean evaluated = false;
    public final Set<GradleDeployDetails> deployDetails = Sets.newTreeSet();

    List<BuildInfoBaseTask> artifactoryTasks = null;
	List<BuildInfoBaseTask> remainingTasks = null;

    public abstract void checkDependsOnArtifactsToPublish();

    public abstract void collectDescriptorsAndArtifactsForUpload() throws IOException;

    public abstract boolean hasModules();

    private final Multimap<String, CharSequence> properties = ArrayListMultimap.create();

    @Input
    public Multimap<String, CharSequence> getProperties() {
        return properties;
    }

    @Input
    public final ArtifactSpecs artifactSpecs = new ArtifactSpecs();

    @Input
    public boolean skip = false;

    @Input
    @Optional
    @Nullable
    public Boolean getPublishBuildInfo() {
        return getFlag(PUBLISH_BUILD_INFO);
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishArtifacts() {
        return getFlag(PUBLISH_ARTIFACTS);
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishIvy() {
        return getFlag(PUBLISH_IVY);
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishPom() {
        return getFlag(PUBLISH_POM);
    }

    @TaskAction
    public void collectProjectBuildInfo() throws IOException {
        log.debug("Task '{}' activated", getPath());
        // Only the last buildInfo execution performs the deployment
        List<BuildInfoBaseTask> orderedTasks = getAllArtifactoryTasks();
        if (orderedTasks.indexOf(this) == -1) {
            log.error("Could not find my own task {} in the task graph!", getPath());
            return;
        }

        if (isLastTask()) {
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
    }

    /**
     * Indicates whether this ArtifactoryTask is the last task to be executed.
     *
     * @return true if this is the last ArtifactoryTask task.
     */
    private boolean isLastTask() {
		//        return getCurrentTaskIndex() == (getAllArtifactoryTasks().size() - 1);
		updateRemainingTasks();
		return remainingTasks.size() <= 1;
    }

    /**
     * Return the index of this ArtifactoryTask in the list of all tasks of type BuildInfoBaseTask.
     *
     * @return The task index.
     */
    private int getCurrentTaskIndex() {
        List<BuildInfoBaseTask> tasks = getAllArtifactoryTasks();
        int currentTaskIndex = tasks.indexOf(this);
        if (currentTaskIndex == -1) {
            throw new IllegalStateException(String.format("Could not find the current task %s in the task graph", getPath()));
        }
        return currentTaskIndex;
    }

    /**
     * Analyze the task graph ordered and extract a list of build info tasks
     *
     * @return An ordered list of build info tasks
     */
    private List<BuildInfoBaseTask> getAllArtifactoryTasks() {
        if (artifactoryTasks == null) {
            List<BuildInfoBaseTask> tasks = new ArrayList<BuildInfoBaseTask>();
            for (Task task : getProject().getGradle().getTaskGraph().getAllTasks()) {
                if (task instanceof BuildInfoBaseTask) {
                    tasks.add(((BuildInfoBaseTask) task));
                }
            }
            artifactoryTasks = tasks;
        }
        return artifactoryTasks;
    }

	/**
     * Analyze the task graph ordered and extract a list of build info tasks
     *
     * @return An ordered list of build info tasks
     */
    private void updateRemainingTasks() {
		if(remainingTasks==null) {
			remainingTasks = new ArrayList<BuildInfoBaseTask>(artifactoryTasks);
		}
		List<Task> toRemove = new ArrayList<Task>();
		for (Task task : remainingTasks) {
			if(task.getState().getExecuted()) {
				toRemove.add(task);
			}
		}
		remainingTasks.removeAll(toRemove);
    }
	
    public void projectsEvaluated() {
        Project project = getProject();
        if (isSkip()) {
            log.debug("artifactoryPublish task '{}' skipped for project '{}'.",
                    this.getPath(), project.getName());
        } else {
            ArtifactoryPluginConvention convention = ArtifactoryPluginUtil.getPublisherConvention(project);
            if (convention != null) {
                ArtifactoryClientConfiguration acc = convention.getClientConfig();
                artifactSpecs.clear();
                artifactSpecs.addAll(acc.publisher.getArtifactSpecs());

                // Configure the task using the "defaults" closure (delegate to the task)
                PublisherConfig config = convention.getPublisherConfig();
                if (config != null) {
                    Closure defaultsClosure = config.getDefaultsClosure();
                    ConfigureUtil.configure(defaultsClosure, this);
                }
            }

            // Depend on buildInfo task in sub-projects
            for (Project sub : project.getSubprojects()) {
                Task subBiTask = sub.getTasks().findByName(BUILD_INFO_TASK_NAME);
                if (subBiTask != null) {
                    dependsOn(subBiTask);
                }
            }

            checkDependsOnArtifactsToPublish();
        }
        evaluated = true;
    }

    public boolean isEvaluated() {
        return evaluated;
    }

    public boolean isSkip() {
        return skip;
    }

    public void setProperties(Map<String, CharSequence> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        properties.clear();
        for (Map.Entry<String, CharSequence> entry : props.entrySet()) {
            // The key cannot be lazy eval, but we keep the value as GString as long as possible
            String key = entry.getKey();
            if (StringUtils.isNotBlank(key)) {
                CharSequence value = entry.getValue();
                if (value != null) {
                    // Make sure all GString are now Java Strings for key,
                    // and don't call toString for value (keep lazy eval as long as possible)
                    // So, don't use HashMultimap this will call equals on the GString
                    this.properties.put(key, value);
                }
            }
        }
    }

    public Set<GradleDeployDetails> getDeployDetails() {
        return deployDetails;
    }

    //For testing
    public ArtifactSpecs getArtifactSpecs() {
        return artifactSpecs;
    }

    /**
     * Setters (Object and DSL)
     **/

    public void properties(Closure closure) {
        Project project = getProject();
        PropertiesConfig propertiesConfig = new PropertiesConfig(project);
        ConfigureUtil.configure(closure, propertiesConfig);
        artifactSpecs.clear();
        artifactSpecs.addAll(propertiesConfig.getArtifactSpecs());
    }

    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    public void setPublishIvy(Object publishIvy) {
        setFlag(PUBLISH_IVY, toBoolean(publishIvy));
    }

    public void setPublishPom(Object publishPom) {
        setFlag(PUBLISH_POM, toBoolean(publishPom));
    }

    //Publish build-info to Artifactory (true by default)
    public void setPublishBuildInfo(Object publishBuildInfo) {
        setFlag(PUBLISH_BUILD_INFO, toBoolean(publishBuildInfo));
    }

    //Publish artifacts to Artifactory (true by default)
    public void setPublishArtifacts(Object publishArtifacts) {
        setFlag(PUBLISH_ARTIFACTS, toBoolean(publishArtifacts));
    }

    private void configureProxy(ArtifactoryClientConfiguration clientConf, ArtifactoryBuildInfoClient client) {
        ArtifactoryClientConfiguration.ProxyHandler proxy = clientConf.proxy;
        String proxyHost = proxy.getHost();
        if (StringUtils.isNotBlank(proxyHost) && proxy.getPort() != null) {
            log.debug("Found proxy host '{}'", proxyHost);
            String proxyUserName = proxy.getUsername();
            if (StringUtils.isNotBlank(proxyUserName)) {
                log.debug("Found proxy user name '{}'", proxyUserName);
                client.setProxyConfiguration(proxyHost, proxy.getPort(), proxyUserName, proxy.getPassword());
            } else {
                log.debug("No proxy user name and password found, using anonymous proxy");
                client.setProxyConfiguration(proxyHost, proxy.getPort());
            }
        }
    }

    protected void configConnectionTimeout(ArtifactoryClientConfiguration clientConf, ArtifactoryBuildInfoClient client) {
        if (clientConf.getTimeout() != null) {
            client.setConnectionTimeout(clientConf.getTimeout());
        }
    }

    protected void configRetriesParams(ArtifactoryClientConfiguration clientConf, ArtifactoryBuildInfoClient client) {
        if (clientConf.getConnectionRetries() != null) {
            client.setConnectionRetries(clientConf.getConnectionRetries());
        }
    }

    private File getExportFile(ArtifactoryClientConfiguration clientConf) {
        String fileExportPath = clientConf.getExportFile();
        if (StringUtils.isNotBlank(fileExportPath)) {
            return new File(fileExportPath);
        }
        Project rootProject = getProject().getRootProject();
        return new File(rootProject.getBuildDir(), "build-info.json");
    }

    private void exportBuildInfo(Build build, File toFile) throws IOException {
        log.debug("Exporting generated build info to '{}'", toFile.getAbsolutePath());
        BuildInfoExtractorUtils.saveBuildInfoToFile(build, toFile);
    }

    private void exportDeployableArtifacts(Set<GradleDeployDetails> allDeployDetails, File toFile) throws IOException {
        log.debug("Exporting deployable artifacts to '{}'", toFile.getAbsolutePath());
        Set<DeployDetails> deploySet = Sets.newLinkedHashSet();
        for (GradleDeployDetails details : allDeployDetails) {
            deploySet.add(details.getDeployDetails());
        }
        DeployableArtifactsUtils.saveDeployableArtifactsToFile(deploySet, toFile);
    }

    @Nonnull
    private Boolean isPublishArtifacts(ArtifactoryClientConfiguration acc) {
        Boolean publishArtifacts = getPublishArtifacts();
        if (publishArtifacts == null) {
            return acc.publisher.isPublishArtifacts();
        }
        return publishArtifacts;
    }

    @Nonnull
    private Boolean isPublishBuildInfo(ArtifactoryClientConfiguration acc) {
        Boolean publishBuildInfo = getPublishBuildInfo();
        if (publishBuildInfo == null) {
            return acc.publisher.isPublishBuildInfo();
        }
        return publishBuildInfo;
    }

    @Nonnull
    private Boolean isGenerateBuildInfoToFile(ArtifactoryClientConfiguration acc) {
        return !StringUtils.isEmpty(acc.info.getGeneratedBuildInfoFilePath());
    }

    @Nonnull
    private Boolean isGenerateDeployableArtifactsToFile(ArtifactoryClientConfiguration acc) {
        return !StringUtils.isEmpty(acc.info.getDeployableArtifactsFilePath());
    }

    @Nullable
    private Boolean getFlag(String flagName) {
        return flags.get(flagName);
    }

    private Boolean toBoolean(Object o) {
        return Boolean.valueOf(o.toString());
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

        Set<GradleDeployDetails> allDeployDetails = Sets.newTreeSet();
        List<BuildInfoBaseTask> orderedTasks = getAllArtifactoryTasks();
        for (BuildInfoBaseTask bit : orderedTasks) {
            if (bit.getDidWork()) {
                ArtifactoryClientConfiguration.PublisherHandler publisher =
                        ArtifactoryPluginUtil.getPublisherHandler(bit.getProject());

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

                    bit.collectDescriptorsAndArtifactsForUpload();
                    if (publisher.isPublishArtifacts()) {
                        ArtifactoryBuildInfoClient client = null;
                        try {
                            client = new ArtifactoryBuildInfoClient(contextUrl, username, password,
                                    new GradleClientLogger(log));

                            log.debug("Uploading artifacts to Artifactory at '{}'", contextUrl);
                            IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                                    publisher.getIncludePatterns(),
                                    publisher.getExcludePatterns());
                            configureProxy(accRoot, client);
                            configConnectionTimeout(accRoot, client);
                            configRetriesParams(accRoot, client);
                            deployArtifacts(bit.deployDetails, client, patterns);
                        } finally {
                            if (client != null) {
                                client.close();
                            }
                        }
                    }
                    allDeployDetails.addAll(bit.deployDetails);
                }
            }
        }

        ArtifactoryBuildInfoClient client = null;
        String contextUrl = accRoot.publisher.getContextUrl();
        String username = accRoot.publisher.getUsername();
        String password = accRoot.publisher.getPassword();
        if (contextUrl != null) {
            if (StringUtils.isBlank(username)) {
                username = "";
            }
            if (StringUtils.isBlank(password)) {
                password = "";
            }
            try {
                client = new ArtifactoryBuildInfoClient(
                        accRoot.publisher.getContextUrl(),
                        accRoot.publisher.getUsername(),
                        accRoot.publisher.getPassword(),
                        new GradleClientLogger(log));
                configureProxy(accRoot, client);
                configConnectionTimeout(accRoot, client);
                configRetriesParams(accRoot, client);
                GradleBuildInfoExtractor gbie = new GradleBuildInfoExtractor(accRoot, allDeployDetails);
                Build build = gbie.extract(getProject().getRootProject());
                exportBuildInfo(build, getExportFile(accRoot));
                if (isPublishBuildInfo(accRoot)) {
                    // If export property set always save the file before sending it to artifactory
                    exportBuildInfo(build, getExportFile(accRoot));
                    if (accRoot.info.isIncremental()) {
                        log.debug("Publishing build info modules to artifactory at: '{}'", contextUrl);
                        client.sendModuleInfo(build);
                    } else {
                        log.debug("Publishing build info to artifactory at: '{}'", contextUrl);
                        Utils.sendBuildAndBuildRetention(client, build, accRoot);
                    }
                }
                if (isGenerateBuildInfoToFile(accRoot)) {
                    try {
                        exportBuildInfo(build, new File(accRoot.info.getGeneratedBuildInfoFilePath()));
                    } catch (Exception e) {
                        log.error("Failed writing build info to file: ", e);
                        throw new IOException("Failed writing build info to file", e);
                    }
                }
                if (isGenerateDeployableArtifactsToFile(accRoot)) {
                    try {
                        exportDeployableArtifacts(allDeployDetails, new File(accRoot.info.getDeployableArtifactsFilePath()));
                    } catch (Exception e) {
                        log.error("Failed writing deployable artifacts to file: ", e);
                        throw new RuntimeException("Failed writing deployable artifacts to file", e);
                    }
                }
            } finally {
                if (client != null) {
                    client.close();
                }
            }
        }
    }

    private void deployArtifacts(Set<GradleDeployDetails> allDeployDetails, ArtifactoryBuildInfoClient client,
                                 IncludeExcludePatterns patterns)
            throws IOException {
        for (GradleDeployDetails detail : allDeployDetails) {
            DeployDetails deployDetails = detail.getDeployDetails();
            String artifactPath = deployDetails.getArtifactPath();
            if (PatternMatcher.pathConflicts(artifactPath, patterns)) {
                log.log(LogLevel.LIFECYCLE, "Skipping the deployment of '" + artifactPath +
                        "' due to the defined include-exclude patterns.");
                continue;
            }
            client.deployArtifact(deployDetails);
        }
    }

    private void setFlag(String flagName, Boolean newValue) {
        flags.put(flagName, newValue);
    }
}
