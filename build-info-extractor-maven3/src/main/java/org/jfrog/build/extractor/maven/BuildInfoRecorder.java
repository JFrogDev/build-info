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

package org.jfrog.build.extractor.maven;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.api.builder.*;
import org.jfrog.build.api.util.CommonUtils;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.BuildInfoExtractor;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.build.extractor.maven.resolver.ResolutionHelper;
import org.jfrog.build.extractor.packageManager.PackageManagerUtils;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.jfrog.build.extractor.BuildInfoExtractorUtils.getModuleIdString;
import static org.jfrog.build.extractor.BuildInfoExtractorUtils.getTypeString;

/**
 * Will be called for every project/module in the Maven project.
 *
 * @author Noam Y. Tenne
 */
@Component(role = BuildInfoRecorder.class)
public class BuildInfoRecorder extends AbstractExecutionListener implements BuildInfoExtractor<ExecutionEvent> {

    @Requirement
    private BuildInfoModelPropertyResolver buildInfoModelPropertyResolver;

    @Requirement
    private BuildDeploymentHelper buildDeploymentHelper;

    @Requirement
    private ResolutionHelper resolutionHelper;

    @Requirement
    private Logger logger;

    private final Set<Artifact> resolvedArtifacts = Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Set<Artifact>> currentModuleDependencies = new ThreadLocal<>();
    private final ThreadLocal<Set<Artifact>> currentModuleArtifacts = new ThreadLocal<>();
    private final ThreadLocal<ModuleBuilder> currentModule = new ThreadLocal<>();
    private Map<String, DeployDetails> deployableArtifactBuilderMap;
    /*
     * Key - dependency ID - group:artifact:version.
     * Value - parents path-to-module. See requestedBy field in org.jfrog.build.api.Dependency.
     */
    private Map<String, String[][]> dependencyParentsMaps;
    private volatile boolean projectHasTestFailures;
    private BuildInfoMavenBuilder buildInfoBuilder;
    private ArtifactoryClientConfiguration conf;
    private ExecutionListener wrappedListener;
    private DocumentBuilder documentBuilder;

    private final ThreadLocal<XPathExpression> xPathExpression = new ThreadLocal<XPathExpression>() {
        @Override
        protected XPathExpression initialValue() {
            XPathExpression result = null;
            try {
                result = XPathFactory.newInstance().newXPath()
                        .compile("/testsuite/@failures>0 or /testsuite/@errors>0");
            } catch (XPathExpressionException ex) {
                logger.error("Fail to create XPathExpression", ex);
            }
            return result;
        }
    };

    public void setListenerToWrap(ExecutionListener executionListener) {
        wrappedListener = executionListener;
    }

    public void setConfiguration(ArtifactoryClientConfiguration conf) {
        this.conf = conf;
    }

    public void setDependencyParentsMaps(Map<String, String[][]> dependencyParentsMaps) {
        this.dependencyParentsMaps = dependencyParentsMaps;
    }

    /**
     * The repository listeners (either ArtifactoryEclipseRepositoryListener or
     * ArtifactorySonatypeRepositoryListener) invoke this method with each
     * artifact being resolved by Maven.
     *
     * @param artifact The artifact being resolved by Maven.
     */
    public void artifactResolved(Artifact artifact) {
        if (artifact != null) {
            resolvedArtifacts.add(artifact);
        }
    }

    @Override
    public void projectDiscoveryStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.projectDiscoveryStarted(event);
        }
    }

    @Override
    public void sessionStarted(ExecutionEvent event) {
        try {
            logger.info("Initializing Artifactory Build-Info Recording");
            buildInfoBuilder = buildInfoModelPropertyResolver.resolveProperties(event, conf);
            deployableArtifactBuilderMap = new ConcurrentHashMap<>();

            if (wrappedListener != null) {
                wrappedListener.sessionStarted(event);
            }
        } catch (Throwable t) {
            String message = getClass().getName() + ".sessionStarted() listener has failed: ";
            logger.error(message, t);
            throw new RuntimeException(message, t);
        }
    }

    @Override
    public void sessionEnded(ExecutionEvent event) {
        try {
            Build build = extract(event);
            if (build != null) {
                File basedir = event.getSession().getTopLevelProject().getBasedir();
                buildDeploymentHelper.deploy(build, conf, deployableArtifactBuilderMap, projectHasTestFailures, basedir);
            }
            deployableArtifactBuilderMap.clear();
            if (wrappedListener != null) {
                wrappedListener.sessionEnded(event);
            }
        } catch (Throwable t) {
            String message = getClass().getName() + ".sessionEnded() listener has failed: ";
            logger.error(message, t);
            throw new RuntimeException(message, t);
        } finally {
            String propertyFilePath = System.getenv(BuildInfoConfigProperties.PROP_PROPS_FILE); // This is used in Jenkins jobs
            if (StringUtils.isBlank(propertyFilePath)) {
                propertyFilePath = conf.getPropertiesFile(); // This is used in the Artifactory maven plugin and Bamboo
            }
            if (StringUtils.isNotBlank(propertyFilePath)) {
                File file = new File(propertyFilePath);
                if (file.exists()) {
                    boolean deleteFailed = !file.delete();
                    if (deleteFailed) {
                        logger.warn("Failed to delete properties file with sensitive data: " + propertyFilePath);
                    }
                }
            }
        }
    }

    @Override
    public void projectSkipped(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.projectSkipped(event);
        }
    }

    @Override
    public void projectStarted(ExecutionEvent event) {
        MavenProject project = event.getProject();
        initModule(project);

        if (wrappedListener != null) {
            wrappedListener.projectStarted(event);
        }
    }

    @Override
    public void projectSucceeded(ExecutionEvent event) {
        finalizeModule(event.getProject());
        if (wrappedListener != null) {
            wrappedListener.projectSucceeded(event);
        }
    }

    @Override
    public void projectFailed(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.projectFailed(event);
        }
    }

    @Override
    public void forkStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkStarted(event);
        }
    }

    @Override
    public void forkSucceeded(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkSucceeded(event);
        }
    }

    @Override
    public void forkFailed(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkFailed(event);
        }
    }

    @Override
    public void forkedProjectStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkedProjectStarted(event);
        }
    }

    @Override
    public void forkedProjectSucceeded(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkedProjectSucceeded(event);
        }
    }

    @Override
    public void forkedProjectFailed(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.forkedProjectFailed(event);
        }
    }

    @Override
    public void mojoSkipped(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.mojoSkipped(event);
        }
    }

    @Override
    public void mojoStarted(ExecutionEvent event) {
        if (wrappedListener != null) {
            wrappedListener.mojoStarted(event);
        }
    }

    @Override
    public void mojoSucceeded(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info dependency extraction: Null project.");
            return;
        }
        if (!projectHasTestFailures && "maven-surefire-plugin".equals((event).getMojoExecution().getPlugin().getArtifactId())) {
            List<File> resultsFile = getSurefireResultsFile(project);
            if (isTestsFailed(resultsFile)) {
                projectHasTestFailures = true;
            }
        }
        extractModuleDependencies(project);
        if (wrappedListener != null) {
            wrappedListener.mojoSucceeded(event);
        }
    }

    private List<File> getSurefireResultsFile(MavenProject project) {
        List<File> surefireReports = new ArrayList<>();
        File surefireDirectory = new File(new File(project.getFile().getParentFile(), "target"), "surefire-reports");
        String[] xmls;
        try {
            xmls = surefireDirectory.list((dir, name) -> name.endsWith("xml"));
        } catch (Exception e) {
            logger.error("Error occurred: " + e.getMessage() + " while retrieving surefire descriptors at: "
                    + surefireDirectory.getAbsolutePath(), e);
            return new ArrayList<>();
        }
        if (xmls != null) {
            for (String xml : xmls) {
                surefireReports.add(new File(surefireDirectory, xml));
            }
        }
        return surefireReports;
    }

    private boolean isTestsFailed(List<File> surefireReports) {

        for (File report : surefireReports) {
            try (FileInputStream stream = new FileInputStream(report)) {
                Document doc = getDocumentBuilder().parse(new InputSource(stream));
                Boolean evaluate = ((Boolean) xPathExpression.get().evaluate(doc, XPathConstants.BOOLEAN));

                if (evaluate != null && evaluate) {
                    return true;
                }
            } catch (FileNotFoundException e) {
                logger.error("File '" + report.getAbsolutePath() + "' does not exist.", e);
            } catch (XPathExpressionException e) {
                logger.error("Expression '/testsuite/@failures>0 or /testsuite/@errors>0' is invalid.", e);
            } catch (Exception e) {
                logger.error("Expression caught while checking build tests result.", e);
            }
        }
        return false;
    }

    @Override
    public void mojoFailed(ExecutionEvent event) {
        MavenProject project = event.getProject();
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info dependency extraction: Null project.");
            return;
        }
        extractModuleDependencies(project);
        if (wrappedListener != null) {
            wrappedListener.mojoFailed(event);
        }
    }

    private void initModule(MavenProject project) {
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info module initialization: Null project.");
            return;
        }

        ModuleBuilder module = new ModuleBuilder().type(ModuleType.MAVEN);
        module.id(getModuleIdString(project.getGroupId(), project.getArtifactId(), project.getVersion()));
        module.properties(project.getProperties());

        currentModule.set(module);

        currentModuleArtifacts.set(Collections.synchronizedSet(new HashSet<>()));
        currentModuleDependencies.set(Collections.synchronizedSet(new HashSet<>()));
    }

    private void extractArtifactsAndDependencies(MavenProject project) {
        if (project == null) {
            logger.warn("Skipping Artifactory Build-Info artifact and dependency extraction: Null project.");
            return;
        }
        Set<Artifact> artifacts = currentModuleArtifacts.get();
        if (artifacts == null) {
            logger.warn("Skipping Artifactory Build-Info project artifact extraction: Null current module artifacts.");
        } else {
            extractModuleArtifact(project, artifacts);
            extractModuleAttachedArtifacts(project, artifacts);
        }

        extractModuleDependencies(project);
    }

    private void finalizeModule(MavenProject project) {
        try {
            extractArtifactsAndDependencies(project);
            ModuleBuilder module = currentModule.get();
            if (module == null) {
                logger.warn("Skipping Artifactory Build-Info module finalization: Null current module.");
                return;
            }
            addModuleToBuild(project, module);
        } finally {
            cleanUpModule();
        }

    }

    //In case of Pom project, the Artifact will be the Pom file.
    private void extractModuleArtifact(MavenProject project, Set<Artifact> artifacts) {
        Artifact artifact = project.getArtifact();
        if (artifact == null) {
            logger.warn("Skipping Artifactory Build-Info project artifact extraction: Null artifact.");
            return;
        }
        artifacts.add(artifact);
    }


    /*
     * Attached Artifacts- are the artifacts/assemblies like tests, sources and so on....
     *   Not include the Pom file
     */
    private void extractModuleAttachedArtifacts(MavenProject project, Set<Artifact> artifacts) {
        List<Artifact> attachedArtifacts = project.getAttachedArtifacts();
        if (attachedArtifacts != null) {
            artifacts.addAll(attachedArtifacts);
        }
    }

    private void extractModuleDependencies(MavenProject project) {
        Set<Artifact> moduleDependencies = currentModuleDependencies.get();
        if (moduleDependencies == null) {
            logger.warn("Skipping Artifactory Build-Info project dependency extraction: Null current module dependencies.");
            return;
        }

        mergeProjectDependencies(conf.publisher.isRecordImmediateDependencies() ? project.getDependencyArtifacts() : project.getArtifacts());
    }

    /**
     * Merge the dependencies taken from the MavenProject object with those
     * collected inside the resolvedArtifacts collection.
     *
     * @param projectDependencies The artifacts taken from the MavenProject
     *                            object.
     */
    private void mergeProjectDependencies(Set<Artifact> projectDependencies) {
        // Go over all the artifacts taken from the MavenProject object, and replace their equals method, so that we are
        // able to merge them together with the artifacts inside the resolvedArtifacts set:
        Set<Artifact> dependecies = new HashSet<>();
        for (Artifact artifact : projectDependencies) {
            String classifier = artifact.getClassifier();
            classifier = classifier == null ? "" : classifier;
            String scope = artifact.getScope();
            scope = StringUtils.isBlank(scope) ? Artifact.SCOPE_COMPILE : scope;  // HAP-909
            DefaultArtifact art = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(),
                    scope, artifact.getType(), classifier, artifact.getArtifactHandler());

            art.setFile(artifact.getFile());
            dependecies.add(art);
        }

        // Now we merge the artifacts from the two collections. In case an artifact is included in both collections, we'd like to keep
        // the one that was taken from the MavenProject, because of the scope it has.
        // The merge is done only if the client is configured to do so.
        Set<Artifact> moduleDependencies = currentModuleDependencies.get();
        Set<Artifact> tempSet = new HashSet<>(moduleDependencies);
        moduleDependencies.clear();
        moduleDependencies.addAll(dependecies);
        moduleDependencies.addAll(tempSet);
        if (conf.publisher.isRecordAllDependencies()) {
            moduleDependencies.addAll(resolvedArtifacts);
        }
    }

    private void addModuleToBuild(MavenProject project, ModuleBuilder module) {
        addArtifactsToCurrentModule(project, module);
        addDependenciesToCurrentModule(module);
        setModuleRepo(module);

        buildInfoBuilder.addModule(module.build());
    }

    private void addArtifactsToCurrentModule(MavenProject project, ModuleBuilder module) {
        Set<Artifact> moduleArtifacts = currentModuleArtifacts.get();
        if (moduleArtifacts == null) {
            logger.warn("Skipping Artifactory Build-Info module artifact addition: Null current module artifact list.");
            return;
        }

        ArtifactoryClientConfiguration.PublisherHandler publisher = conf.publisher;
        IncludeExcludePatterns patterns = new IncludeExcludePatterns(
                publisher.getIncludePatterns(), publisher.getExcludePatterns());
        boolean excludeArtifactsFromBuild = publisher.isFilterExcludedArtifactsFromBuild();

        boolean pomFileAdded = false;
        Artifact nonPomArtifact = null;
        String pomFileName = null;

        for (Artifact moduleArtifact : moduleArtifacts) {
            String groupId = moduleArtifact.getGroupId();
            String artifactId = moduleArtifact.getArtifactId();
            String artifactVersion = moduleArtifact.getVersion();
            String artifactClassifier = moduleArtifact.getClassifier();
            String artifactExtension = moduleArtifact.getArtifactHandler().getExtension();
            String type = getTypeString(moduleArtifact.getType(), artifactClassifier, artifactExtension);

            String artifactName = getArtifactName(artifactId, artifactVersion, artifactClassifier, artifactExtension);

            ArtifactBuilder artifactBuilder = new ArtifactBuilder(artifactName)
                    .remotePath(getRemotePath(groupId, artifactId, artifactVersion))
                    .type(type);
            File artifactFile = moduleArtifact.getFile();

            if ("pom".equals(type)) {
                pomFileAdded = true;
                // For pom projects take the file from the project if the artifact file is null.
                if (moduleArtifact.equals(project.getArtifact())) {
                    artifactFile = project.getFile();   // project.getFile() returns the project pom file
                }
            } else {
                for (ArtifactMetadata metadata : moduleArtifact.getMetadataList()) {
                    if (metadata instanceof ProjectArtifactMetadata) {
                        nonPomArtifact = moduleArtifact;
                        pomFileName = StringUtils.removeEnd(artifactName, artifactExtension) + "pom";
                        break;
                    }
                }
            }

            org.jfrog.build.api.Artifact artifact = artifactBuilder.build();
            String deploymentPath = getDeploymentPath(groupId, artifactId, artifactVersion, artifactClassifier, artifactExtension);
            if (artifactFile != null && artifactFile.isFile()) {
                boolean pathConflicts = PatternMatcher.pathConflicts(deploymentPath, patterns);
                addArtifactToBuildInfo(artifact, pathConflicts, excludeArtifactsFromBuild, module);
                addDeployableArtifact(artifact, artifactFile, pathConflicts, groupId, artifactId, artifactVersion, artifactClassifier, artifactExtension);
            }
        }
        /*
         * In case of non packaging Pom project module, we need to create the pom file from the ProjectArtifactMetadata on the Artifact
         */
        if (!pomFileAdded && nonPomArtifact != null) {
            String deploymentPath = getDeploymentPath(
                    nonPomArtifact.getGroupId(),
                    nonPomArtifact.getArtifactId(),
                    nonPomArtifact.getVersion(),
                    nonPomArtifact.getClassifier(), "pom");

            addPomArtifact(nonPomArtifact, module, patterns, deploymentPath, pomFileName, excludeArtifactsFromBuild);
        }
    }

    private void addPomArtifact(Artifact nonPomArtifact, ModuleBuilder module,
                                IncludeExcludePatterns patterns, String deploymentPath, String pomFileName, boolean excludeArtifactsFromBuild) {

        for (ArtifactMetadata metadata : nonPomArtifact.getMetadataList()) {
            if (metadata instanceof ProjectArtifactMetadata) { // The pom metadata
                ArtifactBuilder artifactBuilder = new ArtifactBuilder(pomFileName)
                        .remotePath(getRemotePath(metadata.getGroupId(), metadata.getArtifactId(), metadata.getBaseVersion()))
                        .type("pom");
                File pomFile = ((ProjectArtifactMetadata) metadata).getFile();
                org.jfrog.build.api.Artifact pomArtifact = artifactBuilder.build();

                if (pomFile != null && pomFile.isFile()) {
                    boolean pathConflicts = PatternMatcher.pathConflicts(deploymentPath, patterns);
                    addArtifactToBuildInfo(pomArtifact, pathConflicts, excludeArtifactsFromBuild, module);
                    addDeployableArtifact(pomArtifact, pomFile, pathConflicts, nonPomArtifact.getGroupId(), nonPomArtifact.getArtifactId(), nonPomArtifact.getVersion(), nonPomArtifact.getClassifier(), "pom");
                }
                break;
            }
        }
    }

    /**
     * If excludeArtifactsFromBuild and the PatternMatcher found conflicts, add the excluded artifact to the excluded artifacts list in the build info.
     * Otherwise, add the artifact to the regular artifacts list.
     */
    private void addArtifactToBuildInfo(org.jfrog.build.api.Artifact artifact, boolean pathConflicts, boolean isFilterExcludedArtifactsFromBuild, ModuleBuilder module) {
        if (isFilterExcludedArtifactsFromBuild && pathConflicts) {
            module.addExcludedArtifact(artifact);
        } else {
            module.addArtifact(artifact);
        }
    }

    private String getArtifactName(String artifactId, String version, String classifier, String fileExtension) {
        StringBuilder nameBuilder = new StringBuilder(artifactId).append("-").append(version);
        if (StringUtils.isNotBlank(classifier)) {
            nameBuilder.append("-").append(classifier);
        }

        return nameBuilder.append(".").append(fileExtension).toString();
    }

    private void addDeployableArtifact(org.jfrog.build.api.Artifact artifact, File artifactFile, boolean pathConflicts,
                                       String groupId, String artifactId, String version, String classifier, String fileExtension) {
        if (pathConflicts) {
            logger.info("'" + artifact.getName() + "' will not be deployed due to the defined include-exclude patterns.");
            return;
        }
        String deploymentPath = getDeploymentPath(groupId, artifactId, version, classifier, fileExtension);
        // deploy to snapshots or releases repository based on the deploy version
        String targetRepository = getTargetRepository(deploymentPath);

        DeployDetails deployable = new DeployDetails.Builder().artifactPath(deploymentPath).file(artifactFile).
                targetRepository(targetRepository).addProperties(conf.publisher.getMatrixParams())
                .packageType(DeployDetails.PackageType.MAVEN).build();
        String myArtifactId = BuildInfoExtractorUtils.getArtifactId(currentModule.get().build().getId(),
                artifact.getName());

        deployableArtifactBuilderMap.put(myArtifactId, deployable);
    }

    /**
     * @param deployPath the full path string to extract the repo from
     * @return Return the target deployment repository. Either the releases
     * repository (default) or snapshots if defined and the deployed file is a
     * snapshot.
     */
    public String getTargetRepository(String deployPath) {
        String snapshotsRepository = conf.publisher.getSnapshotRepoKey();
        if (snapshotsRepository != null && deployPath.contains("-SNAPSHOT")) {
            return snapshotsRepository;
        }
        return conf.publisher.getRepoKey();
    }

    private String getRemotePath(String groupId, String artifactId, String version) {
        return groupId.replace(".", "/") + "/" + artifactId + "/" + version;
    }

    private String getDeploymentPath(String groupId, String artifactId, String version, String classifier,
                                     String fileExtension) {
        return getRemotePath(groupId, artifactId, version) + "/" + getArtifactName(artifactId, version, classifier, fileExtension);
    }

    private void addDependenciesToCurrentModule(ModuleBuilder module) {
        Set<Artifact> moduleDependencies = currentModuleDependencies.get();
        if (moduleDependencies == null) {
            logger.warn("Skipping Artifactory Build-Info module dependency addition: Null current module dependency "
                    + "list.");
            return;
        }
        for (Artifact dependency : moduleDependencies) {
            File depFile = dependency.getFile();
            String gav = getModuleIdString(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
            DependencyBuilder dependencyBuilder = new DependencyBuilder()
                    .id(gav)
                    .requestedBy(dependencyParentsMaps.get(gav))
                    .type(getTypeString(dependency.getType(),
                            dependency.getClassifier(), getExtension(depFile)));
            String scopes = dependency.getScope();
            if (StringUtils.isNotBlank(scopes)) {
                dependencyBuilder.scopes(CommonUtils.newHashSet(scopes));
            }
            setDependencyChecksums(depFile, dependencyBuilder);
            module.addDependency(dependencyBuilder.build());
        }
    }

    private String getExtension(File depFile) {
        String extension = StringUtils.EMPTY;
        if (depFile != null) {
            String fileName = depFile.getName();
            if (fileName != null) {
                int lastDot = fileName.lastIndexOf('.');
                if (lastDot > 0 && lastDot + 1 < fileName.length()) {
                    extension = fileName.substring(lastDot + 1);
                }
            }
        }
        return extension;
    }

    private void setDependencyChecksums(File dependencyFile, DependencyBuilder dependencyBuilder) {
        if ((dependencyFile != null) && (dependencyFile.isFile())) {
            try {
                Map<String, String> checksumsMap
                        = FileChecksumCalculator.calculateChecksums(dependencyFile, "md5", "sha1");
                dependencyBuilder.md5(checksumsMap.get("md5"));
                dependencyBuilder.sha1(checksumsMap.get("sha1"));
            } catch (NoSuchAlgorithmException | IOException e) {
                logger.error("Could not set checksum values on '" + dependencyBuilder.build().getId() + "': "
                        + e.getMessage(), e);
            }
        }
    }

    public void setModuleRepo(ModuleBuilder module) {
        String repo = deployableArtifactBuilderMap.values().stream()
                .map(DeployDetails::getTargetRepository)
                .filter(StringUtils::isNotBlank)
                .findAny()
                .orElse("");
        module.repository(repo);
    }

    @Override
    public Build extract(ExecutionEvent event) {
        MavenSession session = event.getSession();
        if (!session.getResult().hasExceptions()) {
            Date finish = new Date();
            long time = finish.getTime() - session.getRequest().getStartTime().getTime();

            Build build = buildInfoBuilder.durationMillis(time).build();
            PackageManagerUtils.collectEnvIfNeeded(conf, build);
            return build;
        }

        return null;
    }

    public ResolutionHelper getResolutionHelper() {
        return resolutionHelper;
    }

    private DocumentBuilder getDocumentBuilder() throws ParserConfigurationException {
        if (documentBuilder == null) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            factory.setFeature("http://xml.org/sax/features/validation", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            documentBuilder = factory.newDocumentBuilder();
        }
        return documentBuilder;
    }

    private void cleanUpModule() {
        currentModule.remove();
        currentModuleArtifacts.remove();
        currentModuleDependencies.remove();
        resolvedArtifacts.clear();
    }
}
