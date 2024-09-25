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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    protected void handle(
            Set<String> duplicateDependencies,
            Set<String> duplicateDependenciesManagement,
            Set<String> redundantDependencyVersions) {
        PomFileUtil pomFileUtil = new PomFileUtil(modelReader, project);

        List<String> pomLines = pomFileUtil.readPomFile();

        DuplicateDependenciesResult result = new DuplicateDependenciesResult();

        if (!duplicateDependencies.isEmpty()) {
            result.add(findDuplicateDependencies(pomFileUtil.getDependencies(pomLines), duplicateDependencies));
        }

        if (!result.dependenciesToUpdate.isEmpty()) {
            int inserts = 0;
            for (Dependency dep : PomFileUtil.sortByLineNumberDescending(result.dependenciesToUpdate)) {
                inserts += pomFileUtil.updateDependency(dep, pomLines);
            }

            // re-resolve duplicate dependencies after making POM inserts
            if (inserts > 0) {
                result = new DuplicateDependenciesResult();
                result.add(findDuplicateDependencies(pomFileUtil.getDependencies(pomLines), duplicateDependencies));
            }
        }

        if (!duplicateDependenciesManagement.isEmpty()) {
            // dep mgmt doesn't have scope, requires version
            result.dependenciesToRemove.addAll(findDuplicateDependencies(
                            pomFileUtil.getManagedDependencies(pomLines), duplicateDependenciesManagement)
                    .dependenciesToRemove);
        }

        for (Dependency dep : PomFileUtil.sortByLineNumberDescending(result.dependenciesToRemove)) {
            pomFileUtil.removeDependency(dep, pomLines);
        }

        List<Dependency> redundantManagedVersions =
                findRedundantManagedVersions(pomFileUtil.getDependencies(pomLines), redundantDependencyVersions);
        if (!redundantManagedVersions.isEmpty()) {
            PomFileUtil.sortByLineNumberDescending(redundantManagedVersions)
                    .forEach(dep -> pomFileUtil.updateDependency(dep, pomLines));
        }

        pomFileUtil.writePomFile(pomLines);
    }

    private static DuplicateDependenciesResult findDuplicateDependencies(
            List<Dependency> dependencies, Set<String> duplicates) {
        DuplicateDependenciesResult result = new DuplicateDependenciesResult();

        for (String duplicate : duplicates) {
            List<Dependency> foundDuplicates = new ArrayList<>();

            for (Dependency dep : dependencies) {
                if (dep.getManagementKey().equals(duplicate)) {
                    foundDuplicates.add(dep);
                }
            }

            Dependency lastDuplicate = foundDuplicates.get(foundDuplicates.size() - 1);
            String lastDefinedVersion = lastDuplicate.getVersion();
            String lastDefinedScope =
                    Optional.ofNullable(lastDuplicate.getScope()).orElse("compile");

            Dependency depToRetain = foundDuplicates.remove(0);
            boolean needsUpdate = false;

            if (!Objects.equals(lastDefinedVersion, depToRetain.getVersion())) {
                depToRetain.setVersion(lastDefinedVersion);
                needsUpdate = true;
            }

            if (!lastDefinedScope.equals(
                    Optional.ofNullable(depToRetain.getScope()).orElse("compile"))) {
                depToRetain.setScope("compile".equals(lastDefinedScope) ? null : lastDefinedScope);
                needsUpdate = true;
            }

            if (needsUpdate) {
                result.dependenciesToUpdate.add(depToRetain);
            }

            result.dependenciesToRemove.addAll(foundDuplicates);
        }

        return result;
    }

    private static List<Dependency> findRedundantManagedVersions(
            List<Dependency> dependencies, Set<String> redundantDependencyVersions) {
        Map<String, String> redundantDependencyVersionsByKey = redundantDependencyVersions.stream()
                .map(dv -> {
                    int split = dv.lastIndexOf(":");
                    return new String[] {dv.substring(0, split), dv.substring(split + 1)};
                })
                .collect(Collectors.toMap(dv -> dv[0], dv -> dv[1]));

        List<Dependency> depsToUpdate = new ArrayList<>();

        for (Dependency dep : dependencies) {
            if (dep.getVersion() != null) {
                String version = redundantDependencyVersionsByKey.get(dep.getManagementKey());
                if (version != null && version.equals(dep.getVersion())) {
                    dep.setVersion(null);
                    depsToUpdate.add(dep);
                }
            }
        }

        return depsToUpdate;
    }

    private static class DuplicateDependenciesResult {
        private final List<Dependency> dependenciesToUpdate = new ArrayList<>();
        private final List<Dependency> dependenciesToRemove = new ArrayList<>();

        public void add(DuplicateDependenciesResult other) {
            dependenciesToUpdate.addAll(other.dependenciesToUpdate);
            dependenciesToRemove.addAll(other.dependenciesToRemove);
        }
    }
}
