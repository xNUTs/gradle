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

package org.gradle.integtests.resolve
/**
 * Variant of the configuration attributes resolution integration test which makes use of the strongly typed attributes notation.
 */
class StronglyTypedConfigurationAttributesResolveIntegrationTest extends AbstractConfigurationAttributesResolveIntegrationTest {
    @Override
    String getTypeDefs() {
        '''
            @groovy.transform.Canonical
            class Flavor {
                static Flavor of(String value) { return new Flavor(value:value) }
                String value
                String toString() { value }
            }
            enum BuildType {
                debug,
                release
            }

            def flavor = Attribute.of(Flavor)
            def buildType = Attribute.of(BuildType)
            def extra = Attribute.of('extra', String)

            allprojects {
               dependencies {
                   attributesSchema {
                      attribute(flavor)
                      attribute(buildType)
                      attribute(extra)
                   }
               }
            }
        '''
    }

    @Override
    String getDebug() {
        '(buildType): BuildType.debug'
    }

    @Override
    String getFree() {
        '(flavor): Flavor.of("free")'
    }

    @Override
    String getRelease() {
        '(buildType): BuildType.release'
    }

    @Override
    String getPaid() {
        '(flavor): Flavor.of("paid")'
    }

    // This documents the current behavior, not necessarily the one we would want. Maybe
    // we will prefer failing with an error indicating incompatible types
    def "fails if two configurations use the same attribute name with different types"() {
        given:
        file('settings.gradle') << "include 'a', 'b'"
        buildFile << """
            $typeDefs

            project(':a') {
                configurations {
                    _compileFreeDebug.attributes($freeDebug)
                    _compileFreeRelease.attributes($freeRelease)
                }
                dependencies {
                    _compileFreeDebug project(':b')
                    _compileFreeRelease project(':b')
                }
                task checkDebug(dependsOn: configurations._compileFreeDebug) {
                    doLast {
                       assert configurations._compileFreeDebug.collect { it.name } == ['b-default.jar']
                    }
                }
                task checkRelease(dependsOn: configurations._compileFreeRelease) {
                    doLast {
                       assert configurations._compileFreeRelease.collect { it.name } == ['b-default.jar']
                    }
                }
            }
            project(':b') {
                dependencies {
                    attributesSchema {
                        attribute(Attribute.of('flavor', String))
                        attribute(Attribute.of('buildType', String))
                    }
                }
                configurations {
                    create('default')
                    foo {
                        attributes(flavor: 'free', buildType: 'debug') // use String type instead of Flavor/BuildType
                    }
                    bar {
                        attributes(flavor: 'free', buildType: 'release') // use String type instead of Flavor/BuildType
                    }
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                artifacts {
                    'default' file('b-default.jar')
                    foo fooJar
                    bar barJar
                }
            }

        """

        when:
        run ':a:checkDebug'

        then:
        notExecuted ':b:fooJar', ':b:barJar'

        when:
        run ':a:checkRelease'

        then:
        notExecuted ':b:fooJar', ':b:barJar'
    }

    def "selects best compatible match when multiple are possible"() {
        given:
        file('settings.gradle') << "include 'a', 'b'"
        buildFile << """
            $typeDefs

            project(':a') {
               dependencies {
                   attributesSchema {
                      attribute(flavor) {
                          compatibilityRules.@rules.clear() // dirty hack only for testing, don't do this at home!
                          compatibilityRules.add { details ->
                             details.compatible()
                          }
                          disambiguationRules.add { details ->
                             details.candidateValues.each { producerValue ->
                                if (producerValue.value.toLowerCase() == producerValue.value) {
                                    details.closestMatch(producerValue)
                                }
                             }
                          }
                      }
                   }
               }
            }

            project(':a') {
                configurations {
                    _compileFreeDebug.attributes($freeDebug)
                    _compileFreeRelease.attributes($freeRelease)
                }
                dependencies {
                    _compileFreeDebug project(':b')
                    _compileFreeRelease project(':b')
                }
                task checkDebug(dependsOn: configurations._compileFreeDebug) {
                    doLast {
                       assert configurations._compileFreeDebug.collect { it.name } == ['b-foo2.jar']
                    }
                }
            }
            project(':b') {
                configurations {
                    foo {
                        attributes((buildType): BuildType.debug, (flavor): Flavor.of("FREE"))
                    }
                    foo2 {
                        attributes($freeDebug)
                    }
                    bar {
                        attributes($freeRelease)
                    }
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task foo2Jar(type: Jar) {
                   baseName = 'b-foo2'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                artifacts {
                    foo fooJar
                    foo2 foo2Jar
                    bar barJar
                }
            }

        """

        when:
        run ':a:checkDebug'

        then:
        executedAndNotSkipped ':b:foo2Jar'
        notExecuted ':b:fooJar', ':b:barJar'

    }

    def "cannot select best compatible match when multiple best matches are possible"() {
        given:
        file('settings.gradle') << "include 'a', 'b'"
        buildFile << """
            $typeDefs

            project(':a') {
               dependencies.attributesSchema {
                  attribute(flavor) {
                      compatibilityRules.add { details ->
                         if (details.consumerValue.value.equalsIgnoreCase(details.producerValue.value)) {
                             details.compatible()
                         }
                      }
                      disambiguationRules.add { details ->
                         details.candidateValues.each { producerValue ->
                            if (producerValue.value.toLowerCase() == producerValue.value) {
                                details.closestMatch(producerValue)
                            }
                         }
                      }
                  }
               }
            }

            project(':a') {
                configurations {
                    _compileFreeDebug.attributes($freeDebug)
                    _compileFreeRelease.attributes($freeRelease)
                }
                dependencies {
                    _compileFreeDebug project(':b')
                    _compileFreeRelease project(':b')
                }
                task checkDebug(dependsOn: configurations._compileFreeDebug) {
                    doLast {
                       assert configurations._compileFreeDebug.collect { it.name } == []
                    }
                }
            }
            project(':b') {
                configurations {
                    foo {
                        attributes((buildType): BuildType.debug, (flavor): Flavor.of("FREE"))
                    }
                    foo2 {
                        attributes($freeDebug)
                    }
                    foo3 {
                        attributes($freeDebug)
                    }
                    bar {
                        attributes($freeRelease)
                    }
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task foo2Jar(type: Jar) {
                   baseName = 'b-foo2'
                }
                task foo3Jar(type: Jar) {
                   baseName = 'b-foo3'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                artifacts {
                    foo fooJar
                    foo2 foo2Jar
                    bar barJar
                }
            }

        """

        when:
        fails ':a:checkDebug'

        then:
        failsWith("""Cannot choose between the following configurations on 'project :b' : foo2, foo3. All of them match the consumer attributes:
   - Configuration 'foo2' :
      - Required buildType 'debug' and found compatible value 'debug'.
      - Required flavor 'free' and found compatible value 'free'.
   - Configuration 'foo3' :
      - Required buildType 'debug' and found compatible value 'debug'.
      - Required flavor 'free' and found compatible value 'free'.""")

    }

    def "can select best compatible match when single best matches are found on individual attributes"() {
        given:
        file('settings.gradle') << "include 'a', 'b'"
        buildFile << """
            $typeDefs

            project(':a') {
               dependencies.attributesSchema {
                  attribute(flavor) {
                      compatibilityRules.add { details ->
                         if (details.consumerValue.value.equalsIgnoreCase(details.producerValue.value)) {
                             details.compatible()
                         }
                      }
                      disambiguationRules.add { details ->
                         details.candidateValues.each { producerValue ->
                            if (producerValue.value.toLowerCase() == producerValue.value) {
                                details.closestMatch(producerValue)
                            }
                         }
                      }
                  }

                  // for testing purposes, this strategy says that all build types are compatible, but returns the debug value as best
                  attribute(buildType) {
                     compatibilityRules.add { details ->
                        details.compatible()
                     }
                     disambiguationRules.add { details ->
                         details.closestMatch(BuildType.debug)
                     }
                  }
               }
            }

            project(':a') {
                configurations {
                    _compileFreeDebug.attributes($freeDebug)
                    _compileFreeRelease.attributes($freeRelease)
                }
                dependencies {
                    _compileFreeDebug project(':b')
                    _compileFreeRelease project(':b')
                }
                task checkDebug(dependsOn: configurations._compileFreeDebug) {
                    doLast {
                       assert configurations._compileFreeDebug.collect { it.name } == ['b-foo2.jar']
                    }
                }
            }
            project(':b') {
                configurations {
                    foo {
                        attributes((buildType): BuildType.debug, (flavor): Flavor.of("FREE"))
                    }
                    foo2 {
                        attributes((buildType): BuildType.debug, (flavor): Flavor.of("free"))
                    }
                    bar {
                        attributes((buildType): BuildType.release, (flavor): Flavor.of("FREE"))
                    }
                    bar2 {
                        attributes((buildType): BuildType.release, (flavor): Flavor.of("free"))
                    }
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task foo2Jar(type: Jar) {
                   baseName = 'b-foo2'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                task bar2Jar(type: Jar) {
                   baseName = 'b-bar2'
                }
                artifacts {
                    foo fooJar
                    foo2 foo2Jar
                    bar barJar
                    bar2 bar2Jar
                }
            }

        """

        when:
        run ':a:checkDebug'

        then:
        executedAndNotSkipped ':b:foo2Jar'
        notExecuted ':b:fooJar', ':b:barJar', ':b:bar2Jar'
    }

    def "can select configuration thanks to producer schema disambiguation"() {
        given:
        file('settings.gradle') << "include 'a', 'b'"
        buildFile << """
            $typeDefs

            project(':b') {
               dependencies.attributesSchema {
                  attribute(buildType)
                  attribute(flavor) {
                       disambiguationRules.add { details ->
                            details.closestMatch(details.candidateValues.sort { it }.first())
                       }

                  }
               }
            }

            project(':a') {
                dependencies.attributesSchema {
                    def field = delegate.class.superclass.getDeclaredField('strategies')
                    field.accessible = true
                    field.get(delegate).remove(flavor) // for tests only, don't do this at home!!!
                }
                configurations {
                    compile.attributes($debug)
                }
                dependencies {
                    compile project(':b')
                }
                task check(dependsOn: configurations.compile) {
                    doLast {
                       assert configurations.compile.collect { it.name } == ['b-foo.jar']
                    }
                }
            }
            project(':b') {
                dependencies.attributesSchema {
                    attribute(flavor) {
                        compatibilityRules.assumeCompatibleWhenMissing()
                    }
                }
                configurations {
                    foo.attributes($free, $debug)
                    bar.attributes($paid, $debug)
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                artifacts {
                    foo fooJar
                    bar barJar
                }
            }

        """

        when:
        run ':a:check'

        then:
        executedAndNotSkipped ':b:fooJar'
        notExecuted ':b:barJar'
    }

    def "both dependencies will choose the same default value"() {
        given:
        file('settings.gradle') << "include 'a', 'b', 'c'"
        buildFile << """
            enum Arch {
               x86,
               arm64
            }
            def arch = Attribute.of(Arch)
            def dummy = Attribute.of('dummy', String)

            allprojects {
               dependencies {
                   attributesSchema {
                      attribute(dummy)
                   }
               }
            }

            project(':b') {
               dependencies.attributesSchema {
                    attribute(arch) {
                       compatibilityRules.assumeCompatibleWhenMissing()
                       disambiguationRules.pickLast { a,b -> a<=>b }
                  }
               }
            }
            project(':c') {
                dependencies.attributesSchema {
                    attribute(arch) {
                       compatibilityRules.assumeCompatibleWhenMissing()
                       disambiguationRules.pickLast { a,b -> a<=>b }
                    }
                }
            }

            project(':a') {
                configurations {
                    compile.attributes(dummy: 'dummy')
                }
                dependencies {
                    compile project(':b')
                    compile project(':c')
                }
                task check(dependsOn: configurations.compile) {
                    doLast {
                       assert configurations.compile.collect { it.name } == ['b-bar.jar', 'c-bar.jar']
                    }
                }
            }
            project(':b') {
                configurations {
                    foo.attributes((arch): Arch.x86, (dummy): 'dummy')
                    bar.attributes((arch): Arch.arm64, (dummy): 'dummy')
                }
                task fooJar(type: Jar) {
                   baseName = 'b-foo'
                }
                task barJar(type: Jar) {
                   baseName = 'b-bar'
                }
                artifacts {
                    foo fooJar
                    bar barJar
                }
            }
            project(':c') {
                configurations {
                    foo.attributes((arch): Arch.x86, (dummy): 'dummy')
                    bar.attributes((arch): Arch.arm64, (dummy): 'dummy')
                }
                task fooJar(type: Jar) {
                   baseName = 'c-foo'
                }
                task barJar(type: Jar) {
                   baseName = 'c-bar'
                }
                artifacts {
                    foo fooJar
                    bar barJar
                }
            }

        """

        when:
        run ':a:check'

        then:
        executedAndNotSkipped ':b:barJar', ':c:barJar'
        notExecuted ':b:fooJar', ':c:fooJar'
    }


}
