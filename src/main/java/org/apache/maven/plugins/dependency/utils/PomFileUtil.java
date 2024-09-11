/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.dependency.utils;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelProcessor;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.StringModelSource;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
@Singleton
public class PomFileUtil {
    private final Logger logger = LoggerFactory.getLogger(PomFileUtil.class);

    private final ModelReader modelReader;
    private final Provider<MavenProject> project;

    @Inject
    public PomFileUtil(ModelReader modelReader, Provider<MavenProject> project) {
        this.modelReader = modelReader;
        this.project = project;
    }

    public List<Dependency> getDependencies(List<String> pomLines) {
        return rebuildModel(pomLines).getDependencies();
    }

    public List<Dependency> getManagedDependencies(List<String> pomLines) {
        return rebuildModel(pomLines).getDependencyManagement().getDependencies();
    }

    private Model rebuildModel(List<String> pomLines) {
        String pom = StringUtils.join(pomLines, '\n');
        ModelSource modelSource =
                new StringModelSource(pom, project.get().getFile().getPath());
        InputSource inputSource = new InputSource();

        Map<String, Object> options = new HashMap<String, Object>();
        options.put(ModelProcessor.IS_STRICT, true);
        options.put(ModelProcessor.INPUT_SOURCE, inputSource);
        options.put(ModelProcessor.SOURCE, modelSource);

        final Model model;
        try {
            model = modelReader.read(modelSource.getInputStream(), options);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        inputSource.setModelId(project.get().getModel().getId());
        inputSource.setLocation(project.get().getFile().getAbsolutePath());

        return model;
    }

    public void removeDependency(Dependency dependency, List<String> pomLines) {
        String pomLocation = project.get().getFile().toString();

        InputLocation inputLocation = dependency.getLocation("");
        InputSource inputSource = inputLocation.getSource();
        String dependencySource = inputSource == null ? null : inputSource.getLocation();
        if (!pomLocation.equals(dependencySource)) {
            logger.warn("Unable to fix dependency because it comes from parent: {}", dependencySource);
        } else {
            int lineIndex = inputLocation.getLineNumber() - 1; // line numbers start at 1
            logger.debug("Starting removal of {} at index {}", dependency, lineIndex);

            while (!pomLines.get(lineIndex).contains("</dependency>")) {
                logger.debug("Removing line {}", pomLines.get(lineIndex));
                pomLines.remove(lineIndex);
            }

            // remove that last </dependency> line
            logger.debug("Removing line {}", pomLines.get(lineIndex));
            pomLines.remove(lineIndex);
        }
    }

    public List<String> readPomFile() {
        File pomFile = project.get().getFile();
        return PomFileUtil.readLines(pomFile);
    }

    public static List<String> readLines(File file) {
        try {
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writePomFile(List<String> pomLines) {
        File pomFile = project.get().getFile();
        logger.info("Writing updated POM to {}", pomFile);
        PomFileUtil.writeLines(pomFile, pomLines);
    }

    public static void writeLines(File file, List<String> lines) {
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<Dependency> sortByLineNumberAscending(List<Dependency> unsorted) {
        Comparator<Dependency> lineNumberComparator = new Comparator<Dependency>() {

            @Override
            public int compare(Dependency dep1, Dependency dep2) {
                Integer line1 = startIndex(dep1);
                Integer line2 = startIndex(dep2);

                return line1.compareTo(line2);
            }
        };

        List<Dependency> sorted = new ArrayList<Dependency>(unsorted);
        Collections.sort(sorted, lineNumberComparator);
        return sorted;
    }

    public static List<Dependency> sortByLineNumberDescending(List<Dependency> unsorted) {
        List<Dependency> ascending = new ArrayList<Dependency>(sortByLineNumberAscending(unsorted));
        Collections.reverse(ascending);
        return ascending;
    }

    public static int startIndex(Dependency dependency) {
        // line numbers start at 1, but we want it 0-indexed
        return dependency.getLocation("").getLineNumber() - 1;
    }
}
