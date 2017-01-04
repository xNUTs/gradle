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

package org.gradle.api.internal.changedetection.rules

import com.google.common.hash.HashCode
import com.google.common.hash.Hashing
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.changedetection.state.TaskExecution
import org.gradle.internal.Cast
import org.gradle.internal.classloader.ClassLoaderHierarchyHasher
import spock.lang.Specification

class TaskTypeTaskStateChangesTest extends Specification {
    def taskLoaderHash = HashCode.fromLong(123)
    def taskActionsLoaderHash = Hashing.md5().hashBytes(taskLoaderHash.asBytes())
    def taskLoader = SimpleTask.getClassLoader()
    def hasher = Mock(ClassLoaderHierarchyHasher) {
        getClassLoaderHash(taskLoader) >> taskLoaderHash
    }

    def "up-to-date when task is the same"() {
        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> taskLoaderHash
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes.empty
    }

    def "not up-to-date when task name changed"() {
        def previous = Mock(TaskExecution) {
            getTaskClass() >> PreviousTask.name
            getTaskClassLoaderHash() >> taskLoaderHash
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' has changed type from '$PreviousTask.name' to '$SimpleTask.name'." as String]
    }

    def "not up-to-date when class-loader has changed"() {
        def previousHash = HashCode.fromLong(987)
        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> previousHash
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' class path has changed from ${previousHash} to ${taskLoaderHash}." as String]
    }

    def "not up-to-date when action class-loader has changed"() {
        def previousHash = HashCode.fromLong(987)
        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> taskLoaderHash
            getTaskActionsClassLoaderHash() >> previousHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' additional action class path has changed from ${previousHash} to ${taskActionsLoaderHash}." as String]
    }

    def "not up-to-date when task is loaded with an unknown classloader"() {
        def taskClassLoader = new GroovyClassLoader(getClass().getClassLoader())
        Class<? extends TaskInternal> simpleTaskClass = Cast.uncheckedCast(taskClassLoader.parseClass("""
            import org.gradle.api.*

            class SimpleTask extends DefaultTask {}
        """))

        def previous = Mock(TaskExecution) {
            getTaskClass() >> simpleTaskClass.name
            getTaskClassLoaderHash() >> null
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", simpleTaskClass, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' was loaded with an unknown classloader"]
    }

    def "not up-to-date when task action is loaded with an unknown classloader"() {
        def taskActionLoader = new GroovyClassLoader(getClass().getClassLoader())

        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> taskLoaderHash
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskActionLoader], hasher))

        expect:
        changes == ["Task ':test' has a custom action that was loaded with an unknown classloader"]
    }

    def "not up-to-date when task was previously loaded with an unknown classloader"() {
        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> null
            getTaskActionsClassLoaderHash() >> taskActionsLoaderHash
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' was loaded with an unknown classloader during the previous execution"]
    }

    def "not up-to-date when task action was previously loaded with an unknown classloader"() {
        def previous = Mock(TaskExecution) {
            getTaskClass() >> SimpleTask.name
            getTaskClassLoaderHash() >> taskLoaderHash
            getTaskActionsClassLoaderHash() >> null
        }
        def current = Mock(TaskExecution)

        def changes = collectChanges(new TaskTypeTaskStateChanges(previous, current, ":test", SimpleTask, [taskLoader], hasher))

        expect:
        changes == ["Task ':test' had a custom action that was loaded with an unknown classloader during the previous execution"]
    }

    List<String> collectChanges(TaskTypeTaskStateChanges stateChanges) {
        List<DescriptiveChange> changes = []
        stateChanges.addAllChanges(changes)
        return changes*.message
    }

    private class SimpleTask extends DefaultTask {}
    private class PreviousTask extends DefaultTask {}
    private class SimpleAction implements Action<Void> {
        @Override
        void execute(Void value) {
        }
    }
}
