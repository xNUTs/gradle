/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.integtests.tooling.r31

import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.test.fixtures.file.TestFile
import org.gradle.tooling.model.eclipse.EclipseProject
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
/**
 * Dependency substitution is performed for models in a composite build
 */
@TargetGradleVersion(">=3.1")
class PersistentCompositeDependencySubstitutionCrossVersionSpec extends ToolingApiSpecification {
    TestFile buildA
    TestFile buildB

    def setup() {

        buildA = singleProjectBuildInRootFolder("buildA") {
            buildFile << """
                apply plugin: 'java'
                dependencies {
                    compile "org.test:b1:1.0"
                }
            """
            settingsFile << """
                includeBuild 'buildB'
            """
        }

        buildB = multiProjectBuildInSubFolder("buildB", ['b1', 'b2']) {
            buildFile << """
                allprojects {
                    apply plugin: 'java'
                }
            """
        }
    }

    def "EclipseProject model has dependencies substituted in composite"() {
        when:
        def eclipseProject = loadToolingModel(EclipseProject)

        then:
        assert eclipseProject.classpath.empty
        assert eclipseProject.projectDependencies.size() == 1
        with(eclipseProject.projectDependencies.first()) {
            it.path == 'b1'
            it.targetProject == null
        }
    }

    def "EclipseProject model honours custom project name"() {
        when:
        buildB.buildFile << """
            subprojects {
                apply plugin: 'eclipse'
                eclipse {
                    project.name = project.name + "-renamed"
                }
            }
            project(":b1") {
                dependencies {
                    compile project(":b2")
                }
            }
"""

        def eclipseProject = loadToolingModel(EclipseProject)

        then:
        eclipseProject.projectDependencies.size() == 2
        eclipseProject.projectDependencies.find { it.path == 'b1-renamed' }

        and:
        eclipseProject.projectDependencies.find { it.path == 'b2-renamed' }
    }

    @ToolingApiVersion(">=3.2")
    def "Idea model has dependencies substituted in composite"() {
        when:
        def ideaModule = loadToolingModel(IdeaProject).modules[0]

        then:
        ideaModule.dependencies.size() == 1
        with(ideaModule.dependencies.first()) {
            it instanceof IdeaModuleDependency
            dependencyModule == null
            targetModuleName == "b1"
        }
    }

    @ToolingApiVersion(">=3.2")
    def "Idea model honours custom module name"() {
        when:
        buildB.buildFile << """
            subprojects {
                apply plugin: 'idea'
                idea {
                    module.name = module.name + "-renamed"
                }
            }
            project(":b1") {
                dependencies {
                    compile project(":b2")
                }
            }
"""

        def ideaModule = loadToolingModel(IdeaProject).modules[0]

        then:
        ideaModule.dependencies.size() == 2
        ideaModule.dependencies.any { it instanceof IdeaModuleDependency && it.targetModuleName == "b1-renamed" }
        ideaModule.dependencies.any { it instanceof IdeaModuleDependency && it.targetModuleName == "b2-renamed" }

    }

    @TargetGradleVersion(">=3.3")
    def "Does not execute tasks for included builds when generating IDE models"() {
        when:
        def modelOperation = withModel(modelType)
        def modelInstance = modelOperation.model

        then:
        modelType.isInstance modelInstance
        modelOperation.result.executedTasks.empty

        where:
        modelType << [EclipseProject, IdeaProject]
    }
}
