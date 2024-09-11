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
package org.apache.maven.plugins.dependency.analyze;

import javax.inject.Inject;
import javax.inject.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.dependency.utils.PomFileUtil;
import org.apache.maven.project.MavenProject;

/**
 * Analyzes the <code>&lt;dependencies/&gt;</code> and <code>&lt;dependencyManagement/&gt;</code> tags in the
 * <code>pom.xml</code> and determines the duplicate declared dependencies.
 * <p>
 * It will then update the pom.xml to fix any dependency issues found.
 *
 * @author <a href="mailto:jstehler@hubspot.com">Jared Stehler</a>
 * @since 3.7
 */
@Mojo(name = "fix-duplicate", threadSafe = true)
public class FixDuplicateMojo extends AnalyzeDuplicateMojo {

    // TODO: could not get this working via sisu DI
    // @Component
    // private PomFileUtil pomFileUtil;

    @Inject
    private ModelReader modelReader;

    @Inject
    private Provider<MavenProject> project;

    @Override
    protected void handle(Set<String> duplicateDependencies, Set<String> duplicateDependenciesManagement) {
        PomFileUtil pomFileUtil = new PomFileUtil(modelReader, project);

        List<String> pomLines = pomFileUtil.readPomFile();

        List<Dependency> dependenciesToRemove = new ArrayList<>();

        if (!duplicateDependencies.isEmpty()) {
            dependenciesToRemove.addAll(
                    findDuplicateDependencies(pomFileUtil.getDependencies(pomLines), duplicateDependencies));
        }
        if (!duplicateDependenciesManagement.isEmpty()) {
            dependenciesToRemove.addAll(findDuplicateDependencies(
                    pomFileUtil.getManagedDependencies(pomLines), duplicateDependenciesManagement));
        }

        for (Dependency dep : PomFileUtil.sortByLineNumberDescending(dependenciesToRemove)) {
            pomFileUtil.removeDependency(dep, pomLines);
        }

        pomFileUtil.writePomFile(pomLines);
    }

    private static List<Dependency> findDuplicateDependencies(List<Dependency> dependencies, Set<String> duplicates) {
        List<Dependency> duplicateDependencies = new ArrayList<>();

        for (String duplicate : duplicates) {
            AtomicBoolean seenFirst = new AtomicBoolean();

            for (Dependency dep : dependencies) {
                if (dep.getManagementKey().equals(duplicate)) {
                    if (seenFirst.getAndSet(true)) {
                        duplicateDependencies.add(dep);
                    }
                }
            }
        }

        return duplicateDependencies;
    }
}
