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

package org.gradle.integtests

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Issue

class StaleOutputIntegrationTest extends AbstractIntegrationSpec {
    @Issue(['GRADLE-2440', 'GRADLE-2579'])
    def 'stale classes are removed after Java sources are removed'() {
        setup:
        buildScript("apply plugin: 'java'")
        def fooJavaFile = file('src/main/java/Foo.java') << 'public class Foo {}'
        def fooClassFile = file('build/classes/main/Foo.class')
        def barJavaFile = file('src/main/java/com/example/Bar.java') << '''
            package com.example;

            public class Bar {}
        '''
        def barClassFile = file('build/classes/main/com/example/Bar.class')

        when:
        succeeds('compileJava')

        then:
        fooClassFile.exists()
        nonSkippedTasks.contains(':compileJava')

        when:
        fooJavaFile.delete()
        barJavaFile.delete()

        and:
        succeeds('compileJava')

        then:
        !fooClassFile.exists()
        !barClassFile.exists()
        nonSkippedTasks.contains(':compileJava')

        and:
        succeeds('compileJava')

        then:
        !fooClassFile.exists()
        !barClassFile.exists()
        skippedTasks.contains(':compileJava')
    }

    @Issue(['GRADLE-2440', 'GRADLE-2579'])
    def 'stale output file is removed after input source directory is emptied.'() {
        def inputFile = file("src/data/input.txt")
        inputFile << "input"
        def outputFile = file("build/output/data/input.txt")

        buildFile << """
            task test {
                def sources = files("src")
                inputs.dir sources skipWhenEmpty()
                outputs.dir "build/output"
                doLast {
                    file("build/output").mkdirs()
                    sources.asFileTree.visit { details ->
                        if (!details.directory) {
                            def output = file("build/output/\$details.relativePath")
                            output.parentFile.mkdirs()
                            output.text = details.file.text
                        }
                    }
                }
            }
        """

        when:
        succeeds('test')

        then:
        outputFile.exists()
        nonSkippedTasks.contains(':test')

        when:
        inputFile.parentFile.deleteDir()

        and:
        succeeds('test')

        then:
        !outputFile.exists()
        nonSkippedTasks.contains(':test')

        and:
        succeeds('test')

        then:
        !outputFile.exists()
        skippedTasks.contains(':test')
    }
}
