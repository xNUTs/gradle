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
package org.gradle.plugins.ide.idea
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectIdentifier
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.internal.reflect.Instantiator
import org.gradle.jvm.internal.JarBinarySpecInternal
import org.gradle.language.base.plugins.ComponentModelBasePlugin
import org.gradle.model.Model
import org.gradle.model.ModelMap
import org.gradle.model.RuleSource
import org.gradle.model.internal.registry.ModelRegistry
import org.gradle.plugins.ide.api.XmlFileContentMerger
import org.gradle.plugins.ide.idea.internal.IdeaNameDeduper
import org.gradle.plugins.ide.idea.internal.IdeaScalaConfigurer
import org.gradle.plugins.ide.idea.model.*
import org.gradle.plugins.ide.internal.IdePlugin

import javax.inject.Inject
/**
 * Adds a GenerateIdeaModule task. When applied to a root project, also adds a GenerateIdeaProject task.
 * For projects that have the Java plugin applied, the tasks receive additional Java-specific configuration.
 */
class IdeaPlugin extends IdePlugin {
    private final Instantiator instantiator
    private IdeaModel ideaModel
    ModelRegistry modelRegistry

    @Inject
    IdeaPlugin(Instantiator instantiator, ModelRegistry modelRegistry) {
        this.modelRegistry = modelRegistry
        this.instantiator = instantiator
    }

    public IdeaModel getModel() {
        if (project.plugins.hasPlugin(ComponentModelBasePlugin)) {
            List<IdeaModule> modules = modelRegistry.find("ideaModules", List)
            // intellij currently ignores both additional modules
            // and additional source directories on the single module assumed
            // for the project
            // Uncomment the following line and comment out the one below it
            // to try it out
            //ideaModel.module.sourceDirs = modules.collectMany { m -> m.sourceDirs }
            ideaModel.project.modules = modules
        }
        ideaModel
    }

    @Override protected String getLifecycleTaskName() {
        return 'idea'
    }

    @Override protected void onApply(Project project) {
        lifecycleTask.description = 'Generates IDEA project files (IML, IPR, IWS)'
        cleanTask.description = 'Cleans IDEA project files (IML, IPR)'

        ideaModel = project.extensions.create("idea", IdeaModel)

        configureIdeaWorkspace(project)
        configureIdeaProject(project)
        configureIdeaModule(project)
        configureForJavaPlugin(project)
        configureForScalaPlugin()

        hookDeduplicationToTheRoot(project)
    }

    static class SoftwareModelRules extends RuleSource {

        @Model
        List<IdeaModule> ideaModules(ModelMap<JarBinarySpecInternal> components, ProjectIdentifier project) {
            def buildDir = new File(project.projectDir, "build")
            def modules = [] as List<IdeaModule>
            components.values().each({ JarBinarySpecInternal binary ->
                def library = binary.getLibrary()

                IdeaModule module = new IdeaModule(project, null)
                module.name = library.name
                module.sourceDirs = library.getSources().collectMany { it.source.srcDirs }
                module.contentRoot = project.projectDir
                module.testSourceDirs = [] as LinkedHashSet
                def file = { String relativePath -> new File(project.projectDir, relativePath) }
                module.excludeDirs = [buildDir, file('.gradle'), file('.idea')] as LinkedHashSet
                module.scopes = [
                    PROVIDED: [plus: [], minus: []],
                    COMPILE: [plus: [], minus: []],
                    RUNTIME: [plus: [], minus: []],
                    TEST: [plus: [], minus: []]
                ]
                module.outputDir = binary.assembly.classDirectories.first()
                module.singleEntryLibraries = [
                    RUNTIME: binary.assembly.classDirectories,
                    TEST: []
                ]
                PathFactory factory = new PathFactory()
                factory.addPathVariable('MODULE_DIR', project.projectDir)
                module.pathVariables.each { key, value ->
                    factory.addPathVariable(key, value)
                }
                module.pathFactory = factory
                modules.add(module)
            })
            return modules
        }
    }

    void hookDeduplicationToTheRoot(Project project) {
        if (isRoot(project)) {
            project.gradle.projectsEvaluated {
                makeSureModuleNamesAreUnique()
            }
        }
    }

    public void makeSureModuleNamesAreUnique() {
        new IdeaNameDeduper().configureRoot(project.rootProject)
    }

    private configureIdeaWorkspace(Project project) {
        if (isRoot(project)) {
            def task = project.task('ideaWorkspace', description: 'Generates an IDEA workspace file (IWS)', type: GenerateIdeaWorkspace) {
                workspace = new IdeaWorkspace(iws: new XmlFileContentMerger(xmlTransformer))
                ideaModel.workspace = workspace
                outputFile = new File(project.projectDir, project.name + ".iws")
            }
            addWorker(task, false)
        }
    }

    private configureIdeaProject(Project project) {
        if (isRoot(project)) {
            def task = project.task('ideaProject', description: 'Generates IDEA project file (IPR)', type: GenerateIdeaProject) {
                def ipr = new XmlFileContentMerger(xmlTransformer)
                ideaProject = instantiator.newInstance(IdeaProject, project, ipr)

                ideaModel.project = ideaProject

                ideaProject.outputFile = new File(project.projectDir, project.name + ".ipr")
                ideaProject.conventionMapping.jdkName = { JavaVersion.current().toString() }
                ideaProject.conventionMapping.languageLevel = {
                    JavaVersion maxSourceCompatibility = getMaxJavaModuleCompatibilityVersionFor {it.sourceCompatibility}
                    new IdeaLanguageLevel(maxSourceCompatibility)
                }
//                ideaProject.conventionMapping.targetBytecodeVersion = {
//                    return getMaxJavaModuleCompatibilityVersionFor {it.targetCompatibility}
//                }
                ideaProject.wildcards = ['!?*.java', '!?*.groovy'] as Set
                ideaProject.conventionMapping.modules = {
                    project.rootProject.allprojects.findAll { it.plugins.hasPlugin(IdeaPlugin) }.collect { it.idea.module }
                }

                ideaProject.conventionMapping.pathFactory = {
                    new PathFactory().addPathVariable('PROJECT_DIR', outputFile.parentFile)
                }
            }
            addWorker(task)
        }
    }

    private JavaVersion getMaxJavaModuleCompatibilityVersionFor(Closure collectClosure) {
        List<JavaVersion> allProjectJavaVersions = project.rootProject.allprojects.findAll { it.plugins.hasPlugin(IdeaPlugin) && it.plugins.hasPlugin(JavaBasePlugin) }.collect(collectClosure)
        JavaVersion maxJavaVersion = allProjectJavaVersions.isEmpty() ? JavaVersion.VERSION_1_6 : Collections.max(allProjectJavaVersions)
        maxJavaVersion
    }

    private configureIdeaModule(Project project) {
        def task = project.task('ideaModule', description: 'Generates IDEA module files (IML)', type: GenerateIdeaModule) {
            def iml = new IdeaModuleIml(xmlTransformer, project.projectDir)
            module = instantiator.newInstance(IdeaModule, project, iml)

            ideaModel.module = module

            module.conventionMapping.sourceDirs = { [] as LinkedHashSet }
            module.conventionMapping.name = { project.name }
            module.conventionMapping.contentRoot = { project.projectDir }
            module.conventionMapping.testSourceDirs = { [] as LinkedHashSet }
            module.conventionMapping.excludeDirs = { [project.buildDir, project.file('.gradle')] as LinkedHashSet }

            module.conventionMapping.pathFactory = {
                PathFactory factory = new PathFactory()
                factory.addPathVariable('MODULE_DIR', outputFile.parentFile)
                module.pathVariables.each { key, value ->
                    factory.addPathVariable(key, value)
                }
                factory
            }
        }

        addWorker(task)
    }

    private configureForJavaPlugin(Project project) {
        project.plugins.withType(JavaPlugin) {
            configureIdeaModuleForJava(project)
        }
    }

    private configureIdeaModuleForJava(Project project) {
        project.ideaModule {
            module.conventionMapping.sourceDirs = { project.sourceSets.main.allSource.srcDirs as LinkedHashSet }
            module.conventionMapping.testSourceDirs = { project.sourceSets.test.allSource.srcDirs as LinkedHashSet }
            module.scopes = [
                    PROVIDED: [plus: [], minus: []],
                    COMPILE: [plus: [], minus: []],
                    RUNTIME: [plus: [], minus: []],
                    TEST: [plus: [], minus: []]
            ]
            module.conventionMapping.singleEntryLibraries = {
                [
                        RUNTIME: project.sourceSets.main.output.dirs,
                        TEST: project.sourceSets.test.output.dirs
                ]
            }
            dependsOn {
                project.sourceSets.main.output.dirs + project.sourceSets.test.output.dirs
            }
        }
    }

    private void configureForScalaPlugin() {
        project.plugins.withType(ScalaBasePlugin) {
            //see IdeaScalaConfigurer
            project.tasks.ideaModule.dependsOn(project.rootProject.tasks.ideaProject)
        }
        if (isRoot(project)) {
            new IdeaScalaConfigurer(project).configure()
        }
    }

    private boolean isRoot(Project project) {
        return project.parent == null
    }
}

