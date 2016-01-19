/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.ide.internal.tooling;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.internal.jvm.Jvm;
import org.gradle.jvm.JvmLibrarySpec;
import org.gradle.jvm.test.JvmTestSuiteSpec;
import org.gradle.jvm.test.JvmTestSuiteBinarySpec;
import org.gradle.language.base.LanguageSourceSet;
import org.gradle.language.base.plugins.ComponentModelBasePlugin;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.platform.base.BinaryContainer;
import org.gradle.platform.base.ComponentSpecContainer;
import org.gradle.platform.base.test.TestSuiteContainer;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.*;
import org.gradle.plugins.ide.internal.tooling.eclipse.*;
import org.gradle.plugins.ide.internal.tooling.java.DefaultJavaInstallation;
import org.gradle.plugins.ide.internal.tooling.java.DefaultJavaSourceSettings;
import org.gradle.tooling.internal.gradle.DefaultGradleProject;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.*;

public class EclipseModelBuilder implements ToolingModelBuilder {
    private final GradleProjectBuilder gradleProjectBuilder;

    private boolean projectDependenciesOnly;
    private DefaultEclipseProject result;
    private final Map<String, DefaultEclipseProject> projectMapping = new HashMap<String, DefaultEclipseProject>();
    private TasksFactory tasksFactory;
    private DefaultGradleProject<?> rootGradleProject;
    private Project currentProject;
    private final ModelRegistry modelRegistry;

    public EclipseModelBuilder(GradleProjectBuilder gradleProjectBuilder) {
        this(gradleProjectBuilder, null);
    }

    public EclipseModelBuilder(GradleProjectBuilder gradleProjectBuilder, ModelRegistry modelRegistry) {
        this.gradleProjectBuilder = gradleProjectBuilder;
        this.modelRegistry = modelRegistry;
    }

    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject")
            || modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
    }

    public DefaultEclipseProject buildAll(String modelName, Project project) {
        boolean includeTasks = modelName.equals("org.gradle.tooling.model.eclipse.EclipseProject");
        tasksFactory = new TasksFactory(includeTasks);
        projectDependenciesOnly = modelName.equals("org.gradle.tooling.model.eclipse.HierarchicalEclipseProject");
        currentProject = project;
        Project root = project.getRootProject();
        rootGradleProject = gradleProjectBuilder.buildAll(project);
        tasksFactory.collectTasks(root);
        applyEclipsePlugin(root);
        buildHierarchy(root);
        populate(root);
        return result;
    }

    private void applyEclipsePlugin(Project root) {
        Set<Project> allProjects = root.getAllprojects();
        for (Project p : allProjects) {
            p.getPluginManager().apply(EclipsePlugin.class);
        }
        root.getPlugins().getPlugin(EclipsePlugin.class).makeSureProjectNamesAreUnique();
    }

    private void addProject(Project project, DefaultEclipseProject eclipseProject) {
        if (project == currentProject) {
            result = eclipseProject;
        }
        projectMapping.put(project.getPath(), eclipseProject);
    }

    private void populate(Project project) {
        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        EclipseClasspath classpath = eclipseModel.getClasspath();

        classpath.setProjectDependenciesOnly(projectDependenciesOnly);
        List<ClasspathEntry> entries = classpath.resolveDependencies();

        final List<DefaultEclipseExternalDependency> externalDependencies = new LinkedList<DefaultEclipseExternalDependency>();
        final List<DefaultEclipseProjectDependency> projectDependencies = new LinkedList<DefaultEclipseProjectDependency>();
        final List<DefaultEclipseSourceDirectory> sourceDirectories = new LinkedList<DefaultEclipseSourceDirectory>();

        for (ClasspathEntry entry : entries) {
            //we don't handle Variables at the moment because users didn't request it yet
            //and it would probably push us to add support in the tooling api to retrieve the variable mappings.
            if (entry instanceof Library) {
                AbstractLibrary library = (AbstractLibrary) entry;
                final File file = library.getLibrary().getFile();
                final File source = library.getSourcePath() == null ? null : library.getSourcePath().getFile();
                final File javadoc = library.getJavadocPath() == null ? null : library.getJavadocPath().getFile();
                externalDependencies.add(new DefaultEclipseExternalDependency(file, javadoc, source, library.getModuleVersion(), library.isExported()));
            } else if (entry instanceof ProjectDependency) {
                final ProjectDependency projectDependency = (ProjectDependency) entry;
                final String path = StringUtils.removeStart(projectDependency.getPath(), "/");
                projectDependencies.add(new DefaultEclipseProjectDependency(path, projectMapping.get(projectDependency.getGradlePath()), projectDependency.isExported()));
            } else if (entry instanceof SourceFolder) {
                final SourceFolder sourceFolder = (SourceFolder) entry;
                String path = sourceFolder.getPath();
                sourceDirectories.add(new DefaultEclipseSourceDirectory(path, sourceFolder.getDir()));
            }
        }

        DefaultEclipseProject eclipseProject = projectMapping.get(project.getPath());
        eclipseProject.setClasspath(externalDependencies);
        eclipseProject.setProjectDependencies(projectDependencies);
        eclipseProject.setSourceDirectories(sourceDirectories);

        List<DefaultEclipseLinkedResource> linkedResources = new LinkedList<DefaultEclipseLinkedResource>();
        for (Link r : eclipseModel.getProject().getLinkedResources()) {
            linkedResources.add(new DefaultEclipseLinkedResource(r.getName(), r.getType(), r.getLocation(), r.getLocationUri()));
        }
        eclipseProject.setLinkedResources(linkedResources);

        List<DefaultEclipseTask> tasks = new ArrayList<DefaultEclipseTask>();
        for (Task t : tasksFactory.getTasks(project)) {
            tasks.add(new DefaultEclipseTask(eclipseProject, t.getPath(), t.getName(), t.getDescription()));
        }
        eclipseProject.setTasks(tasks);

        List<DefaultEclipseProjectNature> natures = new ArrayList<DefaultEclipseProjectNature>();
        for(String n: eclipseModel.getProject().getNatures()) {
            natures.add(new DefaultEclipseProjectNature(n));
        }
        eclipseProject.setProjectNatures(natures);

        List<DefaultEclipseBuildCommand> buildCommands = new ArrayList<DefaultEclipseBuildCommand>();
        for (BuildCommand b : eclipseModel.getProject().getBuildCommands()) {
            buildCommands.add(new DefaultEclipseBuildCommand(b.getName(), b.getArguments()));
        }
        eclipseProject.setBuildCommands(buildCommands);
        EclipseJdt jdt = eclipseModel.getJdt();
        if (jdt != null) {
            final Jvm currentJvm = Jvm.current();
            eclipseProject.setJavaSourceSettings(new DefaultJavaSourceSettings()
                .setSourceLanguageLevel(jdt.getSourceCompatibility())
                .setTargetBytecodeLevel(jdt.getTargetCompatibility())
                .setTargetRuntime(new DefaultJavaInstallation(currentJvm.getJavaHome(), currentJvm.getJavaVersion()))
            );
        }

        populateSoftwareModelSpike(project);

        for (Project childProject : project.getChildProjects().values()) {
            populate(childProject);
        }
    }

    private void populateSoftwareModelSpike(Project project) {
        if (modelRegistry == null || !project.getPlugins().hasPlugin(ComponentModelBasePlugin.class)) {
            return;
        }
        DefaultEclipseProject eclipseProject = projectMapping.get(project.getPath());
        boolean addJavaBuilder = true;
        for (DefaultEclipseBuildCommand builder : eclipseProject.getBuildCommands()) {
            if (builder.getName().equals("org.eclipse.jdt.core.javabuilder")) {
                addJavaBuilder = false;
            }
        }
        if (addJavaBuilder) {
            eclipseProject.getBuildCommands().add(new DefaultEclipseBuildCommand("org.eclipse.jdt.core.javabuilder", Collections.EMPTY_MAP));
        }
        boolean addJavaNature = true;
        for (DefaultEclipseProjectNature nature : eclipseProject.getProjectNatures()) {
            if (nature.getId().equals("org.eclipse.jdt.core.javanature")) {
                addJavaNature = false;
            }
        }
        if (addJavaNature) {
            eclipseProject.getProjectNatures().add(new DefaultEclipseProjectNature("org.eclipse.jdt.core.javanature"));
        }
        List<File> registeredSourceDirFiles = new ArrayList<File>();
        List<DefaultEclipseSourceDirectory> sourceDirectories = new ArrayList<DefaultEclipseSourceDirectory>();
        List<DefaultEclipseExternalDependency> externalDependencies = new ArrayList<DefaultEclipseExternalDependency>();
        ComponentSpecContainer components = modelRegistry.find("components", ComponentSpecContainer.class);
        if (components != null) {
            for (JvmLibrarySpec component : components.withType(JvmLibrarySpec.class).values()) {
                for (LanguageSourceSet lss : component.getSources().values()) {
                    for (File srcDir : lss.getSource().getSrcDirs()) {
                        if(registeredSourceDirFiles.contains(srcDir.getAbsoluteFile())) {
                            break;
                        }
                        if(!srcDir.exists()) {
                            srcDir.mkdirs();
                        }
                        sourceDirectories.add(new DefaultEclipseSourceDirectory(project.relativePath(srcDir), srcDir));
                        externalDependencies.add(new DefaultEclipseExternalDependency(srcDir, null, srcDir, null, true));
                        registeredSourceDirFiles.add(srcDir.getAbsoluteFile());
                    }
                }
            }
        }
        TestSuiteContainer testSuites = modelRegistry.find("testSuites", TestSuiteContainer.class);
        if (testSuites != null) {
            for (JvmTestSuiteSpec testSuite : testSuites.withType(JvmTestSuiteSpec.class).values()) {
                for (LanguageSourceSet lss : testSuite.getSources().values()) {
                    for (File srcDir : lss.getSource().getSrcDirs()) {
                        if(registeredSourceDirFiles.contains(srcDir.getAbsoluteFile())) {
                            break;
                        }
                        if(!srcDir.exists()) {
                            srcDir.mkdirs();
                        }
                        sourceDirectories.add(new DefaultEclipseSourceDirectory(project.relativePath(srcDir), srcDir));
                        externalDependencies.add(new DefaultEclipseExternalDependency(srcDir, null, srcDir, null, false));
                        registeredSourceDirFiles.add(srcDir.getAbsoluteFile());
                    }
                }
            }
        }
        BinaryContainer binaries = modelRegistry.find("binaries", BinaryContainer.class);
        if (binaries != null) {
            for (JvmTestSuiteBinarySpec binary : binaries.withType(JvmTestSuiteBinarySpec.class).values()) {
                for (LanguageSourceSet lss : binary.getSources().values()) {
                    for (File srcDir : lss.getSource().getSrcDirs()) {
                        if(registeredSourceDirFiles.contains(srcDir.getAbsoluteFile())) {
                            break;
                        }
                        if(!srcDir.exists()) {
                            srcDir.mkdirs();
                        }
                        sourceDirectories.add(new DefaultEclipseSourceDirectory(project.relativePath(srcDir), srcDir));
                        externalDependencies.add(new DefaultEclipseExternalDependency(srcDir, null, srcDir, null, true));
                        registeredSourceDirFiles.add(srcDir.getAbsoluteFile());
                    }
                }
                for(File file : binary.getRuntimeClasspath().getFiles()) {
                    if(registeredSourceDirFiles.contains(file.getAbsoluteFile())) {
                        break;
                    }
                    externalDependencies.add(new DefaultEclipseExternalDependency(file, null, null, null, false));
                }
            }
        }
        eclipseProject.setSourceDirectories(sourceDirectories);
        eclipseProject.setClasspath(externalDependencies);
        final Jvm currentJvm = Jvm.current();
        eclipseProject.setJavaSourceSettings(new DefaultJavaSourceSettings()
            .setSourceLanguageLevel(JavaVersion.VERSION_1_6)
            .setTargetBytecodeLevel(JavaVersion.VERSION_1_6)
            .setTargetRuntime(new DefaultJavaInstallation(currentJvm.getJavaHome(), currentJvm.getJavaVersion()))
        );
    }

    private DefaultEclipseProject buildHierarchy(Project project) {
        List<DefaultEclipseProject> children = new ArrayList<DefaultEclipseProject>();
        for (Project child : project.getChildProjects().values()) {
            children.add(buildHierarchy(child));
        }

        EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
        org.gradle.plugins.ide.eclipse.model.EclipseProject internalProject = eclipseModel.getProject();
        String name = internalProject.getName();
        String description = GUtil.elvis(internalProject.getComment(), null);
        DefaultEclipseProject eclipseProject =
            new DefaultEclipseProject(name, project.getPath(), description, project.getProjectDir(), children)
                .setGradleProject(rootGradleProject.findByPath(project.getPath()));

        for (DefaultEclipseProject child : children) {
            child.setParent(eclipseProject);
        }
        addProject(project, eclipseProject);
        return eclipseProject;
    }
}
