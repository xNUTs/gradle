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

package org.gradle.api.internal.changedetection.state

import org.gradle.api.internal.cache.HeapProportionalCacheSizer
import org.gradle.util.SetSystemProperties
import org.junit.Rule
import spock.lang.Specification


class CacheCapSizerTest extends Specification {
    @Rule SetSystemProperties systemProperties = new SetSystemProperties()

    def "cache cap sizer adjusts caps based on maximum heap size"() {
        given:
        def capSizer = new CacheCapSizer(maxHeapMB)

        when:
        def caps = capSizer.calculateCaps()

        then:
        caps == expectedCaps

        where:
        maxHeapMB | expectedCaps
        100       | [taskArtifacts:400, compilationState:200, fileHashes:80000, fileSnapshots:2000, jvmClassHashes:80000]
        200       | [taskArtifacts:400, compilationState:200, fileHashes:80000, fileSnapshots:2000, jvmClassHashes:80000]
        768       | [taskArtifacts: 1600, compilationState: 800, fileHashes: 325200, fileSnapshots: 8100, jvmClassHashes:325200]
        1024      | [taskArtifacts: 2300, fileHashes: 459900, compilationState: 1100, fileSnapshots: 11500, jvmClassHashes:459900]
        1536      | [taskArtifacts: 3600, fileHashes: 729400, compilationState: 1800, fileSnapshots: 18200, jvmClassHashes:729400]
        2048      | [taskArtifacts: 4900, fileHashes: 998900, compilationState: 2400, fileSnapshots: 24900, jvmClassHashes:998900]
    }

    def "cache cap sizer honors reserved space when specified"() {
        given:
        System.setProperty(HeapProportionalCacheSizer.CACHE_RESERVED_SYSTEM_PROPERTY, reserved.toString())
        def capSizer = new CacheCapSizer(maxHeapMB)

        when:
        def caps = capSizer.calculateCaps()

        then:
        caps == expectedCaps

        where:
        maxHeapMB | reserved | expectedCaps
        100       | 50       | [taskArtifacts: 400, compilationState: 200, fileHashes: 80000, fileSnapshots: 2000, jvmClassHashes:80000]
        200       | 200      | [taskArtifacts: 400, compilationState: 200, fileHashes: 80000, fileSnapshots: 2000, jvmClassHashes:80000]
        968       | 200      | [taskArtifacts: 1600, compilationState: 800, fileHashes: 325200, fileSnapshots: 8100, jvmClassHashes:325200]
        1224      | 200      | [taskArtifacts: 2300, fileHashes: 459900, compilationState: 1100, fileSnapshots: 11500, jvmClassHashes:459900]
        2036      | 500      | [taskArtifacts: 3600, fileHashes: 729400, compilationState: 1800, fileSnapshots: 18200, jvmClassHashes:729400]
        4096      | 2048     | [taskArtifacts: 4900, fileHashes: 998900, compilationState: 2400, fileSnapshots: 24900, jvmClassHashes:998900]
    }
}
