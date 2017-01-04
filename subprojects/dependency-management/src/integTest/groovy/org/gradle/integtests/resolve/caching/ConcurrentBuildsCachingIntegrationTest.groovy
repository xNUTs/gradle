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

package org.gradle.integtests.resolve.caching

import org.gradle.integtests.fixtures.AbstractHttpDependencyResolutionTest
import org.gradle.test.fixtures.server.http.CyclicBarrierHttpServer
import org.junit.Rule

class ConcurrentBuildsCachingIntegrationTest extends AbstractHttpDependencyResolutionTest {
    @Rule CyclicBarrierHttpServer server1 = new CyclicBarrierHttpServer()
    @Rule CyclicBarrierHttpServer server2 = new CyclicBarrierHttpServer()

    def "can interleave resolution across multiple build processes"() {
        def mod1 = mavenHttpRepo.module("group1", "module1", "1.0").publish()
        def mod2 = mavenHttpRepo.module("group1", "module2", "0.99").dependsOn(mod1).publish()

        buildFile << """
repositories {
    maven { url "${mavenHttpRepo.uri}" }
}
configurations {
    a
    b
}
dependencies {
    a "group1:module1:1.0"
    b "group1:module2:0.99"
}
task a {
    doLast {
        configurations.a.files
    }
}
task b {
    doLast {
        configurations.b.files
    }
}
task block1 {
    doLast {
        new URL("$server1.uri").text
    }
}
block1.mustRunAfter a
b.mustRunAfter block1

task block2 {
    doLast {
        new URL("$server2.uri").text
    }
}
block2.mustRunAfter b
"""
        expect:
        // Build 1 should download module 1 and reuse cached module 2 state
        mod1.pom.expectGet()
        mod1.artifact.expectGet()

        // Build 2 should download module 2 and reuse cached module 1 state
        mod2.pom.expectGet()
        mod2.artifact.expectGet()

        // Start build 1 then wait until it has run task 'a'.
        executer.withTasks("a", "block1", "b")
        executer.withArgument("--info")
        def build1 = executer.start()
        server1.waitFor()

        // Start build 2 then wait until it has run both 'a' and 'b'.
        executer.withTasks("a", "b", "block2")
        executer.withArgument("--info")
        def build2 = executer.start()
        server2.waitFor()

        // Finish up build 1 and 2
        server1.release() // finish build 1 while build 2 is still running
        build1.waitForFinish()
        server2.release()
        build2.waitForFinish()
    }
}
