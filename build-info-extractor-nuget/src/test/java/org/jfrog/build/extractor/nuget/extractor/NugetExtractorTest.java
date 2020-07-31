package org.jfrog.build.extractor.nuget.extractor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jfrog.build.IntegrationTestsBase;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.Module;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryBuildInfoClientBuilder;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryDependenciesClientBuilder;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.testng.Assert.*;

@Test
public class NugetExtractorTest extends IntegrationTestsBase {

    private static final String NUGET_LOCAL_REPO = "build-info-tests-nuget-local";
    private static final String NUGET_REMOTE_REPO = "build-info-tests-nuget-remote";
    private static final String NUGET_VIRTUAL_REPO = "build-info-tests-nuget-virtual";
    private static final String CUSTOM_MODULE = "custom-module-name";

    private static final Path PROJECTS_ROOT = Paths.get(".").toAbsolutePath().normalize().resolve(Paths.get("src", "test", "resources", "org", "jfrog", "build", "extractor"));

    private ArtifactoryDependenciesClientBuilder dependenciesClientBuilder;
    private Map<String, String> env = new HashMap<>();

    public NugetExtractorTest() {
        localRepo = NUGET_LOCAL_REPO;
        remoteRepo = NUGET_REMOTE_REPO;
        virtualRepo = NUGET_VIRTUAL_REPO;
    }

    private enum Project {
        // Test projects
        PACKAGESCONFIG("packagesconfig", "NugetExtractorTest-PackagesConfig", new String[]{"packagesconfig"}, 6),
        REFERENCE("reference", "NugetExtractorTest-Reference", new String[]{"reference"}, 6),
        MULTIPACKAGESCONFIG("multipackagesconfig", "NugetExtractorTest-MultiPackagesConfig", new String[]{"proj1", "proj2"}, 4,3),
        MULTIREFERENCE("multireference", "NugetExtractorTest-MultiReference", new String[]{"proj1", "proj2"}, 5,3);


        private File projectOrigin;
        private String targetDir;
        private int[] dependenciesCount;
        private String[] moduleNames;

        Project(String sourceDir, String targetDir, String[] moduleNames, int... dependenciesCounts) {
            this.projectOrigin = PROJECTS_ROOT.resolve(sourceDir).toFile();
            this.targetDir = targetDir;
            this.moduleNames = moduleNames;
            this.dependenciesCount = dependenciesCounts;
        }

    }

    @BeforeClass
    private void setUp() throws IOException {
        dependenciesClientBuilder = new ArtifactoryDependenciesClientBuilder().setArtifactoryUrl(getUrl()).setUsername(getUsername()).setPassword(getPassword()).setLog(getLog());
        buildInfoClientBuilder = new ArtifactoryBuildInfoClientBuilder().setArtifactoryUrl(getUrl()).setUsername(getUsername()).setPassword(getPassword()).setLog(getLog());
    }

    Object[][] packagesConfigTestsInfo = new Object[][]{
            {Project.PACKAGESCONFIG, "restore", null, Project.PACKAGESCONFIG.moduleNames, 6},
            {Project.MULTIPACKAGESCONFIG, "restore", null, Project.MULTIPACKAGESCONFIG.moduleNames, 4, 3},
            {Project.MULTIPACKAGESCONFIG, "restore", CUSTOM_MODULE, new String[]{CUSTOM_MODULE}, 6},
            {Project.MULTIPACKAGESCONFIG, "restore ./proj1/ -SolutionDirectory .", null, new String[]{"proj1"}, 4},
            {Project.MULTIPACKAGESCONFIG, "restore proj2/packages.config -SolutionDirectory .", null, new String[]{"proj2"}, 3},
    };

    Object[][] referenceTestsInfo = new Object[][]{
            {Project.REFERENCE, "restore", null, Project.PACKAGESCONFIG.REFERENCE.moduleNames, 6},
            {Project.MULTIREFERENCE, "restore", null, Project.PACKAGESCONFIG.MULTIREFERENCE.moduleNames,5, 3},
            {Project.MULTIREFERENCE, "restore", CUSTOM_MODULE, new String[]{CUSTOM_MODULE},6},
            {Project.MULTIREFERENCE, "restore src/multireference.proj1/proj1.csproj", null, new String[]{"proj1"},5},
            {Project.MULTIREFERENCE, "restore ./multireference.sln", CUSTOM_MODULE, new String[]{CUSTOM_MODULE},6},
    };

    @DataProvider
    private Object[][] nugetRunProvider() {
        return ArrayUtils.addAll(packagesConfigTestsInfo, referenceTestsInfo);
    }

    @SuppressWarnings("unused")
    @Test(dataProvider = "nugetRunProvider")
    private void nugetRunTest(Project project, String args, String moduleName, String[] expectedModules, int... expectedDependencies) {
        Path projectDir = null;
        try {
            // Run nuget restore install
            projectDir = createProjectDir(project);
            NugetRun nugetRun = new NugetRun(dependenciesClientBuilder, remoteRepo, false, args, log, projectDir, env, moduleName, getUsername(), getPassword());
            executeAndAssertBuildInfo(nugetRun, expectedModules, expectedDependencies);
        } catch (Exception e) {
            fail(ExceptionUtils.getStackTrace(e));
        } finally {
            if (projectDir != null) {
                FileUtils.deleteQuietly(projectDir.toFile());
            }
        }
    }

    @DataProvider
    private Object[][] dotnetCliRunProvider() {
        return referenceTestsInfo;
    }

    @SuppressWarnings("unused")
    @Test(dataProvider = "dotnetCliRunProvider")
    private void dotnetCliRunTest(Project project, String args, String moduleName, String[] expectedModules, int... expectedDependencies) {
        Path projectDir = null;
        try {
            // Run nuget restore install
            projectDir = createProjectDir(project);
            NugetRun nugetRun = new NugetRun(dependenciesClientBuilder, remoteRepo, true, args, log, projectDir, env, moduleName, getUsername(), getPassword());
            executeAndAssertBuildInfo(nugetRun, expectedModules, expectedDependencies);
        } catch (Exception e) {
            fail(ExceptionUtils.getStackTrace(e));
        } finally {
            if (projectDir != null) {
                FileUtils.deleteQuietly(projectDir.toFile());
            }
        }
    }

    private void executeAndAssertBuildInfo(NugetRun nugetRun, String[] expectedModules, int... expectedDependencies) {
        Build build = nugetRun.execute();
        assertEquals(build.getModules().size(), expectedModules.length);
        for (int i = 0; i < expectedModules.length; i++) {
            Module module = build.getModules().get(i);
            // Check correctness of the module and dependencies
            assertEquals(module.getId(), expectedModules[i]);
            assertTrue(module.getDependencies().size() > 0);
        }
    }

    private Path createProjectDir(Project project) throws IOException {
        File projectDir = Files.createTempDirectory(project.targetDir).toFile();
        FileUtils.copyDirectory(project.projectOrigin, projectDir);
        return projectDir.toPath();
    }

    @DataProvider
    private Object[][] projectRootProvider() {
        return new Object[][]{
                {"restore", "example.sln"},
                {"restore .", "example.sln"},
                {"restore ./example.sln", "example.sln"},
                {"restore example.sln", "example.sln"},
                {"restore ./packagesConfigDir/", "packages.config"},
                {"restore packagesConfigDir/packages.config", "packages.config"},
                {"restore ./packagesConfigDir/example.csproj", "example.csproj"},
                {"restore projectAssetsDir/", "another_example.csproj"},
                {"restore ./projectAssetsDir/another_example.csproj", "another_example.csproj"},
        };
    }

    @SuppressWarnings("unused")
    @Test(dataProvider = "projectRootProvider")
    private void getProjectRootTest(String args, String expectedProjectRootFileName) {
        try {
            File rootDir = PROJECTS_ROOT.resolve("projectRootTestDir").toFile();
            NugetRun nugetRun = new NugetRun(dependenciesClientBuilder, remoteRepo, false, args, log, rootDir.toPath(), env, null, getUsername(), getPassword());
            File projectRoot = nugetRun.getProjectRootPath();
            assertTrue(projectRoot.getPath().endsWith(expectedProjectRootFileName));
        } catch (Exception e) {
            fail(ExceptionUtils.getStackTrace(e));
        }
    }
}