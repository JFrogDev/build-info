/*
 * Copyright (C) 2010 JFrog Ltd.
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

package org.jfrog.build.api.dependency;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Noam Y. Tenne
 */
public class PatternResultFileSet {

    private String repoUri;
    private String sourcePattern;
    private Set<String> files = new HashSet<>();

    public PatternResultFileSet() {
    }

    public PatternResultFileSet(String repoUri, String sourcePattern) {
        this.repoUri = repoUri;
        this.sourcePattern = sourcePattern;
    }

    public PatternResultFileSet(String repoUri, String sourcePattern, Set<String> files) {
        this.repoUri = repoUri;
        this.sourcePattern = sourcePattern;
        this.files = files;
    }

    public String getRepoUri() {
        return repoUri;
    }

    public void setRepoUri(String repoUri) {
        this.repoUri = repoUri;
    }

    public String getSourcePattern() {
        return sourcePattern;
    }

    public void setSourcePattern(String sourcePattern) {
        this.sourcePattern = sourcePattern;
    }

    public Set<String> getFiles() {
        return files;
    }

    public void setFiles(Set<String> files) {
        this.files = files;
    }

    public void addFile(String fileRelativePath) {
        if (files == null) {
            files = new HashSet<>();
        }

        files.add(fileRelativePath);
    }
}
