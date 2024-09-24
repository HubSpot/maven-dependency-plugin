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

import java.io.File;
import java.io.StringWriter;
import java.util.*;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.dependency.utils.StringUtils;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.dependency.analyzer.DependencyUsage;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalysis;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzer;
import org.apache.maven.shared.dependency.analyzer.ProjectDependencyAnalyzerException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.PrettyPrintXMLWriter;

/**
 * Analyzes the dependencies of this project and determines which are: used and declared; used and undeclared; unused
 * and declared; compile scoped but only used in tests.
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 * @since 2.0-alpha-5
 */
public abstract class AbstractAnalyzeMojo extends AbstractMojo {
    // fields -----------------------------------------------------------------

    protected static final String DEPENDENCY_OVERRIDES = "maven-dependency-plugin.dep-overrides";

    /**
     * The plexusContainer to look-up the right {@link ProjectDependencyAnalyzer} implementation depending on the mojo
     * configuration.
     */
    @Component
    private PlexusContainer plexusContainer;

    /**
     * The Maven project to analyze.
     */
    @Component
    private MavenProject project;

    /**
     * Specify the project dependency analyzer to use (plexus component role-hint). By default,
     * <a href="/shared/maven-dependency-analyzer/">maven-dependency-analyzer</a> is used. To use this, you must declare
     * a dependency for this plugin that contains the code for the analyzer. The analyzer must have a declared Plexus
     * role name, and you specify the role name here.
     *
     * @since 2.2
     */
    @Parameter(property = "analyzer", defaultValue = "default")
    private String analyzer;

    /**
     * Whether to fail the build if a dependency warning is found.
     */
    @Parameter(property = "failOnWarning", defaultValue = "false")
    private boolean failOnWarning;

    /**
     * Output used dependencies.
     */
    @Parameter(property = "verbose", defaultValue = "false")
    private boolean verbose;

    /**
     * Ignore Runtime/Provided/Test/System scopes for unused dependency analysis.
     * <p>
     * <code><b>Non-test scoped</b></code> list will be not affected.
     */
    @Parameter(property = "ignoreNonCompile", defaultValue = "false")
    private boolean ignoreNonCompile;

    /**
     * Ignore Runtime scope for unused dependency analysis.
     *
     * @since 3.2.0
     */
    @Parameter(property = "ignoreUnusedRuntime", defaultValue = "false")
    private boolean ignoreUnusedRuntime;

    /**
     * Ignore all dependencies that are used only in test but not test-scoped. Setting
     * this flag has the same effect as adding all dependencies that have been flagged with
     * the <i>Non-test scoped test only dependencies found</i> warning to the
     * <code>&lt;ignoredNonTestScopedDependencies&gt;</code> configuration.
     *
     * @since 3.3.1-SNAPSHOT
     */
    @Parameter(property = "ignoreAllNonTestScoped", defaultValue = "false")
    private boolean ignoreAllNonTestScoped;

    /**
     * Output the xml for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "outputXML", defaultValue = "false")
    private boolean outputXML;

    /**
     * Output scriptable values for the missing dependencies (used but not declared).
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "scriptableOutput", defaultValue = "false")
    private boolean scriptableOutput;

    /**
     * Flag to use for scriptable output.
     *
     * @since 2.0-alpha-5
     */
    @Parameter(property = "scriptableFlag", defaultValue = "$$$%%%")
    private String scriptableFlag;

    /**
     * Flag to use for scriptable output
     *
     * @since 2.0-alpha-5
     */
    @Parameter(defaultValue = "${basedir}", readonly = true)
    private File baseDir;

    /**
     * Target folder
     *
     * @since 2.0-alpha-5
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File outputDirectory;

    /**
     * Force dependencies as used, to override incomplete result caused by bytecode-level analysis. Dependency format is
     * <code>groupId:artifactId</code>.
     *
     * @since 2.6
     */
    @Parameter
    private String[] usedDependencies;

    /**
     * Skip plugin execution completely.
     *
     * @since 2.7
     */
    @Parameter(property = "mdep.analyze.skip", defaultValue = "false")
    private boolean skip;

    /**
     * List of dependencies that will be ignored. Any dependency on this list will be excluded from the "declared but
     * unused", the "used but undeclared", and the "non-test scoped" list. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     * <p>
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are used but undeclared. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     * <p>
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredUsedUndeclaredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are declared but unused. The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     * <p>
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 2.10
     */
    @Parameter
    private String[] ignoredUnusedDeclaredDependencies = new String[0];

    /**
     * List of dependencies that will be ignored if they are in not test scope but are only used in test classes.
     * The filter syntax is:
     *
     * <pre>
     * [groupId]:[artifactId]:[type]:[version]
     * </pre>
     * <p>
     * where each pattern segment is optional and supports full and partial <code>*</code> wildcards. An empty pattern
     * segment is treated as an implicit wildcard. *
     * <p>
     * For example, <code>org.apache.*</code> will match all artifacts whose group id starts with
     * <code>org.apache.</code>, and <code>:::*-SNAPSHOT</code> will match all snapshot artifacts.
     * </p>
     *
     * @since 3.3.0
     */
    @Parameter
    private String[] ignoredNonTestScopedDependencies = new String[0];

    /**
     * List of project packaging that will be ignored.
     * <br/>
     * <b>Default value is<b>: <code>pom, ear</code>
     *
     * @since 3.2.1
     */
    // defaultValue value on @Parameter - not work with Maven 3.2.5
    // When is set defaultValue always win, and there is no possibility to override by plugin configuration.
    @Parameter
    private List<String> ignoredPackagings = Arrays.asList("pom", "ear");

    /**
     * List Excluded classes patterns from analyze. Java regular expression pattern is applied to full class name.
     *
     * @since 3.7.0
     */
    @Parameter(property = "mdep.analyze.excludedClasses")
    private Set<String> excludedClasses;

    // Mojo methods -----------------------------------------------------------

    /*
     * @see org.apache.maven.plugin.Mojo#execute()
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (isSkip()) {
            getLog().info("Skipping plugin execution");
            return;
        }

        if (ignoredPackagings.contains(project.getPackaging())) {
            getLog().info("Skipping " + project.getPackaging() + " project");
            return;
        }

        if (outputDirectory == null || !outputDirectory.exists()) {
            getLog().info("Skipping project with no build directory");
            return;
        }

        boolean warning = checkDependencies();

        if (warning && isFailOnWarning()) {
            throw new MojoExecutionException("Dependency problems found");
        }
    }

    /**
     * @return {@link ProjectDependencyAnalyzer}
     * @throws MojoExecutionException in case of an error.
     */
    protected ProjectDependencyAnalyzer createProjectDependencyAnalyzer() throws MojoExecutionException {

        try {
            return plexusContainer.lookup(ProjectDependencyAnalyzer.class, analyzer);
        } catch (ComponentLookupException exception) {
            throw new MojoExecutionException(
                    "Failed to instantiate ProjectDependencyAnalyser" + " / role-hint " + analyzer, exception);
        }
    }

    protected MavenProject getProject() {
        return project;
    }

    /**
     * @return {@link #skip}
     */
    protected final boolean isSkip() {
        return skip;
    }

    protected boolean isFailOnWarning() {
        return failOnWarning;
    }

    protected boolean isOutputXML() {
        return outputXML;
    }

    protected void handle(Set<Artifact> usedUndeclared, Set<Artifact> unusedDeclared) {
        // for subclasses to use
    }

    protected Set<String> getManagedDependencies() {
        if (project.getDependencyManagement() == null
                || project.getDependencyManagement().getDependencies() == null) {
            return Collections.emptySet();
        } else {
            Set<String> managedDependencies = new HashSet<String>();
            for (Dependency dependency : project.getDependencyManagement().getDependencies()) {
                managedDependencies.add(dependency.getManagementKey());
            }
            return managedDependencies;
        }
    }

    // private methods --------------------------------------------------------

    private boolean checkDependencies() throws MojoExecutionException {
        final MavenProject project;
        Object dependencyOverrides = getPluginContext().get(DEPENDENCY_OVERRIDES);
        if (dependencyOverrides == null) {
            project = this.project;
        } else {
            project = this.project.clone();
            project.setDependencyArtifacts((Set<Artifact>) dependencyOverrides);
        }

        ProjectDependencyAnalysis analysis;
        try {
            analysis = createProjectDependencyAnalyzer().analyze(project, excludedClasses);

            if (usedDependencies != null) {
                analysis = analysis.forceDeclaredDependenciesUsage(usedDependencies);
            }
        } catch (ProjectDependencyAnalyzerException exception) {
            throw new MojoExecutionException("Cannot analyze dependencies", exception);
        }

        if (ignoreNonCompile) {
            analysis = analysis.ignoreNonCompile();
        }

        Set<Artifact> usedDeclared = new LinkedHashSet<>(analysis.getUsedDeclaredArtifacts());
        Map<Artifact, Set<DependencyUsage>> usedUndeclaredWithUsages =
                new LinkedHashMap<>(analysis.getUsedUndeclaredArtifactsWithUsages());
        Set<Artifact> unusedDeclared = new LinkedHashSet<>(analysis.getUnusedDeclaredArtifacts());
        Set<Artifact> nonTestScope = new LinkedHashSet<>(analysis.getTestArtifactsWithNonTestScope());

        Set<Artifact> ignoredUsedUndeclared = new LinkedHashSet<>();
        Set<Artifact> ignoredUnusedDeclared = new LinkedHashSet<>();
        Set<Artifact> ignoredNonTestScope = new LinkedHashSet<>();

        if (ignoreUnusedRuntime) {
            filterArtifactsByScope(unusedDeclared, Artifact.SCOPE_RUNTIME);
        }

        ignoredUsedUndeclared.addAll(filterDependencies(usedUndeclaredWithUsages.keySet(), ignoredDependencies));
        ignoredUsedUndeclared.addAll(
                filterDependencies(usedUndeclaredWithUsages.keySet(), ignoredUsedUndeclaredDependencies));

        ignoredUnusedDeclared.addAll(filterDependencies(unusedDeclared, ignoredDependencies));
        ignoredUnusedDeclared.addAll(filterDependencies(unusedDeclared, ignoredUnusedDeclaredDependencies));

        if (ignoreAllNonTestScoped) {
            ignoredNonTestScope.addAll(filterDependencies(nonTestScope, new String[] {"*"}));
        } else {
            ignoredNonTestScope.addAll(filterDependencies(nonTestScope, ignoredDependencies));
            ignoredNonTestScope.addAll(filterDependencies(nonTestScope, ignoredNonTestScopedDependencies));
        }

        boolean reported = false;
        boolean warning = false;

        if (verbose && !usedDeclared.isEmpty()) {
            getLog().info("Used declared dependencies found:");

            logArtifacts(analysis.getUsedDeclaredArtifacts(), false);
            reported = true;
        }

        if (!usedUndeclaredWithUsages.isEmpty()) {
            logDependencyWarning("Used undeclared dependencies found:");

            logArtifacts(usedUndeclaredWithUsages, true);

            reported = true;
            warning = true;
        }

        if (!unusedDeclared.isEmpty()) {
            logDependencyWarning("Unused declared dependencies found:");

            logArtifacts(unusedDeclared, true);
            reported = true;
            warning = true;
        }

        if (!nonTestScope.isEmpty()) {
            logDependencyWarning("Non-test scoped test only dependencies found:");

            logArtifacts(nonTestScope, true);
            reported = true;
            warning = true;
        }

        if (verbose && !ignoredUsedUndeclared.isEmpty()) {
            getLog().info("Ignored used undeclared dependencies:");

            logArtifacts(ignoredUsedUndeclared, false);
            reported = true;
        }

        if (verbose && !ignoredUnusedDeclared.isEmpty()) {
            getLog().info("Ignored unused declared dependencies:");

            logArtifacts(ignoredUnusedDeclared, false);
            reported = true;
        }

        if (verbose && !ignoredNonTestScope.isEmpty()) {
            getLog().info("Ignored non-test scoped test only dependencies:");

            logArtifacts(ignoredNonTestScope, false);
            reported = true;
        }

        if (isOutputXML()) {
            writeDependencyXML(usedUndeclaredWithUsages.keySet());
        }

        if (scriptableOutput) {
            writeScriptableOutput(usedUndeclaredWithUsages.keySet());
        }

        if (!reported) {
            getLog().info("No dependency problems found");
        }

        handle(usedUndeclaredWithUsages.keySet(), unusedDeclared);

        return warning;
    }

    private void filterArtifactsByScope(Set<Artifact> artifacts, String scope) {
        artifacts.removeIf(artifact -> artifact.getScope().equals(scope));
    }

    private void logArtifacts(Set<Artifact> artifacts, boolean warn) {
        if (artifacts.isEmpty()) {
            getLog().info("   None");
        } else {
            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                if (warn) {
                    logDependencyWarning("   " + artifact);
                } else {
                    getLog().info("   " + artifact);
                }
            }
        }
    }

    private void logArtifacts(Map<Artifact, Set<DependencyUsage>> artifacts, boolean warn) {
        if (artifacts.isEmpty()) {
            getLog().info("   None");
        } else {
            for (Map.Entry<Artifact, Set<DependencyUsage>> entry : artifacts.entrySet()) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                entry.getKey().isSnapshot();

                List<String> messages = new ArrayList<>(toMessages(entry.getValue()));
                Collections.sort(messages, Comparator.comparing(String::length));

                int total = messages.size();
                if (!verbose && total > 5) {
                    int extra = total - 5;
                    messages = new ArrayList<>(messages.subList(0, 5));
                    messages.add(String.format("... and %d more", extra));
                }

                if (warn) {
                    logDependencyWarning("   " + entry.getKey());
                    for (String message : messages) {
                        logDependencyWarning("      class " + message);
                    }
                } else {
                    getLog().info("   " + entry.getKey());
                    for (String message : messages) {
                        getLog().info("      class " + message);
                    }
                }
            }
        }
    }

    private void logDependencyWarning(CharSequence content) {
        if (isFailOnWarning()) {
            getLog().error(content);
        } else {
            getLog().warn(content);
        }
    }

    private void writeDependencyXML(Set<Artifact> artifacts) {
        if (!artifacts.isEmpty()) {
            getLog().info("Add the following to your pom to correct the missing dependencies: ");

            StringWriter out = new StringWriter();
            PrettyPrintXMLWriter writer = new PrettyPrintXMLWriter(out);

            Set<String> managedDependencies = getManagedDependencies();
            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                writer.startElement("dependency");
                writer.startElement("groupId");
                writer.writeText(artifact.getGroupId());
                writer.endElement();
                writer.startElement("artifactId");
                writer.writeText(artifact.getArtifactId());
                writer.endElement();

                writer.startElement("is in managed dep");
                writer.writeText(String.valueOf(!managedDependencies.contains(artifact.getDependencyConflictId())));
                writer.endElement();
                writer.startElement("version");
                writer.writeText(managedDependencies.contains(artifact.getDependencyConflictId()) ? "" : artifact.getBaseVersion());
                writer.endElement();
                if (!managedDependencies.contains(artifact.getDependencyConflictId())) {
                    writer.startElement("version");
                    writer.writeText(artifact.getBaseVersion());
                    writer.endElement();
                }
                String classifier = artifact.getClassifier();
                if (!StringUtils.isEmpty(classifier)) {
                    writer.startElement("classifier");
                    writer.writeText(classifier);
                    writer.endElement();
                }

                if (!Artifact.SCOPE_COMPILE.equals(artifact.getScope())
                        && !Artifact.SCOPE_PROVIDED.equals(artifact.getScope())) {
                    writer.startElement("scope");
                    writer.writeText(artifact.getScope());
                    writer.endElement();
                }
                writer.endElement();
            }

            getLog().info(System.lineSeparator() + out.getBuffer());
        }
    }

    private void writeScriptableOutput(Set<Artifact> artifacts) {
        if (!artifacts.isEmpty()) {
            getLog().info("Missing dependencies: ");
            String pomFile = baseDir.getAbsolutePath() + File.separatorChar + "pom.xml";
            StringBuilder buf = new StringBuilder();

            for (Artifact artifact : artifacts) {
                // called because artifact will set the version to -SNAPSHOT only if I do this. MNG-2961
                artifact.isSnapshot();

                buf.append(scriptableFlag)
                        .append(":")
                        .append(pomFile)
                        .append(":")
                        .append(artifact.getDependencyConflictId())
                        .append(":")
                        .append(artifact.getClassifier())
                        .append(":")
                        .append(artifact.getBaseVersion())
                        .append(":")
                        .append(artifact.getScope())
                        .append(System.lineSeparator());
            }
            getLog().info(System.lineSeparator() + buf);
        }
    }

    private static Collection<String> toMessages(Collection<DependencyUsage> usages) {
        String messageFormat = "%s is referenced in %s";

        Collection<String> messages = new ArrayList<String>();
        for (DependencyUsage usage : usages) {
            messages.add(String.format(messageFormat, usage.getDependencyClass(), usage.getUsedBy()));
        }

        return messages;
    }

    private List<Artifact> filterDependencies(Set<Artifact> artifacts, String[] excludes) {
        ArtifactFilter filter = new StrictPatternExcludesArtifactFilter(Arrays.asList(excludes));
        List<Artifact> result = new ArrayList<>();

        for (Iterator<Artifact> it = artifacts.iterator(); it.hasNext(); ) {
            Artifact artifact = it.next();
            if (!filter.include(artifact)) {
                it.remove();
                result.add(artifact);
            }
        }

        return result;
    }
}
