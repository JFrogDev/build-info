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

package org.jfrog.build.api;

import com.google.common.collect.Lists;
import org.jfrog.build.api.builder.PromotionStatusBuilder;
import org.jfrog.build.api.builder.dependency.BuildDependencyBuilder;
import org.jfrog.build.api.dependency.BuildDependency;
import org.jfrog.build.api.release.Promotion;
import org.jfrog.build.api.release.PromotionStatus;
import org.testng.annotations.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.testng.Assert.*;

/**
 * Tests the behavior of the build class
 *
 * @author Noam Y. Tenne
 */
@Test
public class BuildTest {

    /**
     * Validates the build values after initializing the default constructor
     */
    public void testEmptyConstructor() {
        Build build = new Build();

        assertEquals(build.getVersion(), "1.0.1", "Unexpected default build version.");
        assertNull(build.getName(), "Build name should have not been initialized.");
        assertNull(build.getNumber(), "Build number should have not been initialized.");
        assertNull(build.getAgent(), "Build agent should have not been initialized.");
        assertNull(build.getStarted(), "Build started should have not been initialized.");
        assertEquals(build.getDurationMillis(), 0, "Build duration should have not been initialized.");
        assertNull(build.getPrincipal(), "Build principal should have not been initialized.");
        assertNull(build.getArtifactoryPrincipal(), "Build artifactory principal should have not been initialized.");
        assertNull(build.getArtifactoryPluginVersion(), "Build Artifactory Plugin Version should have not been initialized.");
        assertNull(build.getUrl(), "Build URL should have not been initialized.");
        assertNull(build.getParentBuildId(), "Build parent build ID should have not been initialized.");
        assertNull(build.getModules(), "Build modules should have not been initialized.");
        assertNull(build.getProperties(), "Build properties should have not been initialized.");
        assertNull(build.getBuildDependencies(), "Build dependencies should have not been initialized.");
    }

    /**
     * Validates the build values after using the build setters
     */
    public void testSetters() {
        String version = "1.2.0";
        String name = "moo";
        String number = "15";
        Agent agent = new Agent("pop", "1.6");
        long durationMillis = 6L;
        String principal = "bob";
        String artifactoryPrincipal = "too";
        String artifactoryPluginVersion = "2.3.1";
        String url = "mitz";
        String parentName = "pooh";
        String parentNumber = "5";
        String vcsRevision = "2421";
        List<Module> modules = Lists.newArrayList();
        List<PromotionStatus> statuses = Lists.newArrayList();
        List<BuildDependency> buildDependencies = Arrays.asList(
                new BuildDependencyBuilder().name("foo").number("123").startedDate(new Date()).build(),
                new BuildDependencyBuilder().name("bar").number("456").startedDate(new Date()).build()
        );
        Properties properties = new Properties();

        Build build = new Build();
        build.setVersion(version);
        build.setName(name);
        build.setNumber(number);
        build.setAgent(agent);
        build.setDurationMillis(durationMillis);
        build.setPrincipal(principal);
        build.setArtifactoryPrincipal(artifactoryPrincipal);
        build.setArtifactoryPluginVersion(artifactoryPluginVersion);
        build.setUrl(url);
        build.setParentName(parentName);
        build.setParentNumber(parentNumber);
        build.setModules(modules);
        build.setStatuses(statuses);
        build.setProperties(properties);
        build.setVcsRevision(vcsRevision);
        build.setBuildDependencies(buildDependencies);

        assertEquals(build.getVersion(), version, "Unexpected build version.");
        assertEquals(build.getName(), name, "Unexpected build name.");
        assertEquals(build.getNumber(), number, "Unexpected build number.");
        assertEquals(build.getAgent(), agent, "Unexpected build agent.");
        assertEquals(build.getDurationMillis(), durationMillis, "Unexpected build duration millis.");
        assertEquals(build.getPrincipal(), principal, "Unexpected build principal.");
        assertEquals(build.getArtifactoryPrincipal(), artifactoryPrincipal, "Unexpected build artifactory principal.");
        assertEquals(build.getArtifactoryPluginVersion(), artifactoryPluginVersion, "Unexpected build artifactory principal.");
        assertEquals(build.getUrl(), url, "Unexpected build URL.");
        assertEquals(build.getParentName(), parentName, "Unexpected build parent build name.");
        assertEquals(build.getParentNumber(), parentNumber, "Unexpected build parent build number.");
        assertEquals(build.getVcsRevision(), vcsRevision, "Unexpected build vcs revision.");
        assertEquals(build.getModules(), modules, "Unexpected build modules.");
        assertTrue(build.getModules().isEmpty(), "Build modules list should not have been populated.");
        assertEquals(build.getStatuses(), statuses, "Unexpected build statuses.");
        assertTrue(build.getStatuses().isEmpty(), "Build statuses list should not have been populated.");
        assertEquals(build.getProperties(), properties, "Unexpected build properties.");
        assertTrue(build.getProperties().isEmpty(), "Build properties list should not have been populated.");
        assertEquals(build.getBuildDependencies(), buildDependencies, "Unexpected build dependencies list.");
    }

    /**
     * Validates the build start time values after using the build setters
     */
    public void testStartedSetters() throws ParseException {
        Build build = new Build();

        String started = "192-1212-1";
        build.setStarted(started);

        assertEquals(build.getStarted(), started, "Unexpected build started.");

        Date startedDate = new Date();
        build.setStartedDate(startedDate);

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(Build.STARTED_FORMAT);
        assertEquals(build.getStarted(), simpleDateFormat.format(startedDate), "Unexpected build started.");
    }

    public void testStatusAddMethod() {
        Build build = new Build();
        assertNull(build.getStatuses(), "Default status list should be null.");

        PromotionStatus promotionStatus = new PromotionStatusBuilder(Promotion.RELEASED).repository("bla").
                timestamp("bla").user("bla").build();
        build.addStatus(promotionStatus);

        assertFalse(build.getStatuses().isEmpty(), "Status object should have been added.");
        assertEquals(build.getStatuses().get(0), promotionStatus, "Unexpected status object.");

        PromotionStatus anotherPromotionStatus = new PromotionStatusBuilder(Promotion.RELEASED).repository("bla").
                timestamp("bla").user("bla").build();
        build.addStatus(anotherPromotionStatus);

        assertEquals(build.getStatuses().size(), 2, "Second status object should have been added.");
        assertEquals(build.getStatuses().get(1), anotherPromotionStatus, "Unexpected status object.");
    }

    public void testAddBuildDependencyMethod() {
        Build build = new Build();
        assertNull(build.getBuildDependencies(), "Default buildDependencies list should be null.");

        BuildDependency buildDependency = new BuildDependencyBuilder().name("foo").number("123").startedDate(new Date()).build();

        build.addBuildDependency(buildDependency);

        assertFalse(build.getBuildDependencies().isEmpty(), "BuildDependency object should have been added.");
        assertEquals(getOnlyElement(build.getBuildDependencies()), buildDependency, "Unexpected build dependency object.");

        BuildDependency otherBuildDependency = new BuildDependencyBuilder().name("bar").number("456").startedDate(new Date()).build();
        build.addBuildDependency(otherBuildDependency);

        assertEquals(build.getBuildDependencies().size(), 2, "Second BuildDependency object should have been added.");
        assertEquals(getLast(build.getBuildDependencies()), otherBuildDependency, "Unexpected build dependency object.");
    }
}