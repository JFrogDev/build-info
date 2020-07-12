package org.jfrog.build.extractor.buildTool;

import org.jfrog.build.extractor.BuildInfoExtractorUtils;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;

import java.util.Properties;

/**
 * Created by Bar Belity on 12/07/2020.
 */
public class BuildToolUtils {

    /**
     * Create a new client configuration from the 'buildInfoConfig.propertiesFile' and environment variables.
     *
     * @return a new client configuration
     */
    public static ArtifactoryClientConfiguration createArtifactoryClientConfiguration() {
        BuildToolLogger log = new BuildToolLogger();
        ArtifactoryClientConfiguration clientConfiguration = new ArtifactoryClientConfiguration(log);
        Properties allBuildProps = new Properties();
        allBuildProps.putAll(System.getenv());
        Properties allProps = BuildInfoExtractorUtils.mergePropertiesWithSystemAndPropertyFile(allBuildProps, log);
        clientConfiguration.fillFromProperties(allProps);
        return clientConfiguration;
    }

}
