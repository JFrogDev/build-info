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

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.jfrog.aether.AetherUtils;
import org.jfrog.build.api.BuildInfoConfigProperties;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.BuildInfoExtractorUtils;

import java.util.Properties;

/**
 * @author Noam Y. Tenne
 */
@Component(role = AbstractMavenLifecycleParticipant.class)
public class BuildInfoRecorderLifecycleParticipant extends AbstractMavenLifecycleParticipant {

    @Requirement
    private Logger logger;

    @Requirement(role = BuildInfoRecorder.class, hint = "default", optional = false)
    BuildInfoRecorder recorder;

    private ArtifactoryClientConfiguration internalConfiguration = null;

    /**
     * When the session starts, register Artifactory RepositoryListener as part of the listener chain that is
     * used for repository manipulation.
     *
     * @param session The maven session.
     * @throws MavenExecutionException A maven execution exception that can happen during the maven build.
     */
    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        super.afterSessionStart(session);

        ArtifactoryClientConfiguration config = getConfiguration( session );
        AetherUtils.addRepositoryListener( session,
                                           "DefaultRepositorySystemSession",
                                           config.resolver.getUrlWithMatrixParams(),
                                           config.resolver.getUsername(),
                                           config.resolver.getPassword(),
                                           config.proxy.getHost(),
                                           config.proxy.getPort(),
                                           config.proxy.getUsername(),
                                           config.proxy.getPassword(),
                                           logger );
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        ArtifactoryClientConfiguration configuration = getConfiguration(session);
        Object activateRecorderObject = configuration.isActivateRecorder();
        if (activateRecorderObject == null) {
            logger.debug("Disabling Artifactory Maven3 Build-Info Recorder: activation property (" +
                    BuildInfoConfigProperties.ACTIVATE_RECORDER + ") not found.");
            return;
        }
        if (!Boolean.valueOf(activateRecorderObject.toString())) {
            logger.debug("Disabling Artifactory Maven3 Build-Info Recorder: activation property (" +
                    BuildInfoConfigProperties.ACTIVATE_RECORDER + ") value is either false or invalid.");
            return;
        }
        logger.debug("Activating Artifactory Maven3 Build-Info Recorder: activation property (" +
                BuildInfoConfigProperties.ACTIVATE_RECORDER + ") value is true.");
        configuration.info.setBuildStarted( System.currentTimeMillis() );
        ExecutionListener existingExecutionListener = session.getRequest().getExecutionListener();
        recorder.setListenerToWrap(existingExecutionListener);
        recorder.setConfiguration(configuration);
        session.getRequest().setExecutionListener(recorder);
    }

    private ArtifactoryClientConfiguration getConfiguration(MavenSession session) {
        if (internalConfiguration != null) {
            return internalConfiguration;
        }
        Properties allMavenProps = new Properties();
        allMavenProps.putAll(session.getSystemProperties());
        allMavenProps.putAll(session.getUserProperties());

        Properties allProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(allMavenProps);
        internalConfiguration = new ArtifactoryClientConfiguration(new Maven3BuildInfoLogger(logger));
        internalConfiguration.fillFromProperties(allProps);
        return internalConfiguration;
    }
}
