/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.integtests.tooling.r211
import org.gradle.api.JavaVersion
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.model.UnsupportedMethodException
import org.gradle.tooling.model.eclipse.EclipseProject

@ToolingApiVersion('>=2.11')
@TargetGradleVersion(">=2.11")
class ToolingApiEclipseModelCrossVersionSpec extends ToolingApiSpecification {

    def setup() {
        settingsFile << "rootProject.name = 'root'"
    }

    def "Java project has target bytecode level"() {
        given:
        buildFile << "apply plugin: 'java'"

        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetBytecodeVersion== JavaVersion.current()
    }

    def "Java project has jdk"() {
        given:
        buildFile << """
apply plugin: 'java'

description = org.gradle.internal.jvm.Jvm.current().javaHome.toString()
"""
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.jdk != null
        rootProject.javaSourceSettings.jdk.javaVersion == JavaVersion.current()
        rootProject.javaSourceSettings.jdk.javaHome.toString() == rootProject.gradleProject.description
    }

    def "target bytecode level respects explicit targetCompatibility configuration"() {
        given:
        buildFile << """
        apply plugin:'java'
        targetCompatibility = 1.5
"""
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetBytecodeVersion == JavaVersion.VERSION_1_5
    }

    def "target bytecode level respects explicit configured eclipse config"() {
        given:
        buildFile << """
        apply plugin:'java'
        apply plugin:'eclipse'

        targetCompatibility = 1.6

        eclipse {
            jdt {
                targetCompatibility = 1.5
            }
        }
        """
        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.javaSourceSettings.targetBytecodeVersion == JavaVersion.VERSION_1_5
    }

    @TargetGradleVersion("=2.9")
    def "older Gradle versions throw exception when querying target bytecode level"() {
        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        rootProject.javaSourceSettings.targetBytecodeVersion

        then:
        thrown(UnsupportedMethodException)
    }

    @TargetGradleVersion("=2.9")
    def "older Gradle versions throw exception when querying target runtime"() {
        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        rootProject.javaSourceSettings.jdk

        then:
        thrown(UnsupportedMethodException)
    }

    def "Multi-project build can define different target bytecode level for subprojects"() {
        given:
        settingsFile << """
            include 'subproject-a', 'subproject-b', 'subproject-c'
        """

        buildFile << """
            project(':subproject-a') {
                apply plugin: 'java'
                targetCompatibility = 1.1
            }
            project(':subproject-b') {
                apply plugin: 'java'
                apply plugin: 'eclipse'
                eclipse {
                    jdt {
                        targetCompatibility = 1.2
                    }
                }
            }
            project(':subproject-c') {
                apply plugin: 'java'
                apply plugin: 'eclipse'
                targetCompatibility = 1.6
                eclipse {
                    jdt {
                        targetCompatibility = 1.3
                    }
                }
            }
        """

        when:
        EclipseProject rootProject = loadEclipseProjectModel()
        EclipseProject subprojectA = rootProject.children.find { it.name == 'subproject-a' }
        EclipseProject subprojectB = rootProject.children.find { it.name == 'subproject-b' }
        EclipseProject subprojectC = rootProject.children.find { it.name == 'subproject-c' }

        then:
        subprojectA.javaSourceSettings.targetBytecodeVersion == JavaVersion.VERSION_1_1
        subprojectB.javaSourceSettings.targetBytecodeVersion == JavaVersion.VERSION_1_2
        subprojectC.javaSourceSettings.targetBytecodeVersion == JavaVersion.VERSION_1_3
    }

    def "Project with a single component and a single test suite can be imported into the IDE"() {
        given:
        buildFile << """
            plugins {
                id 'jvm-component'
                id 'java-lang'
                id 'junit-test-suite'
            }
            repositories {
                jcenter()
            }
            model {
                components {
                    main(JvmLibrarySpec)
                }
                testSuites {
                    test(JUnitTestSuiteSpec) {
                        jUnitVersion = '4.12'
                        testing \$.components.main
                    }
                }
            }
        """.stripIndent().trim()

        when:
        EclipseProject rootProject = loadEclipseProjectModel()

        then:
        rootProject.name == 'root'
        rootProject.projectDirectory == projectDir
        rootProject.javaSourceSettings != null
        rootProject.projectNatures.size() == 1
        rootProject.buildCommands.size() == 1
        rootProject.linkedResources.size() == 0
        rootProject.classpath.size() == 10
        rootProject.classpath.find({ it.file == file('src/main/java')})
        rootProject.classpath.find({ it.file == file('src/main/resources')})
        rootProject.classpath.find({ it.file == file('src/test/java')})
        rootProject.classpath.find({ it.file == file('src/test/resources')})
        rootProject.classpath.find({ it.file == file('build/classes/main/jar')})
        rootProject.classpath.find({ it.file == file('build/resources/main/jar')})
        rootProject.classpath.find({ it.file == file('build/classes/test/mainJarBinary')})
        rootProject.classpath.find({ it.file == file('build/resources/test/mainJarBinary')})
        rootProject.classpath.find({ it.file.name == 'junit-4.12.jar'})
        rootProject.classpath.find({ it.file.name == 'hamcrest-core-1.3.jar'})
        rootProject.projectDependencies.isEmpty()
    }

    private EclipseProject loadEclipseProjectModel() {
        withConnection { connection -> connection.getModel(EclipseProject) }
    }
}
