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

/**
 * Defines the different types of builds
 *
 * @author Noam Y. Tenne
 * @deprecated Use {@link org.jfrog.build.api.BuildAgent} instead.
 */
public enum BuildType {
    GENERIC("Generic"), MAVEN("Maven"), ANT("Ant"), IVY("Ivy"), GRADLE("Gradle");

    private String name;

    /**
     * Main constructor
     *
     * @param name Build type name
     */
    BuildType(String name) {
        this.name = name;
    }

    /**
     * Returns the name of the build type
     *
     * @return Build type name
     */
    public String getName() {
        return name;
    }
}
