/*
 * Copyright 2013 the original author or authors.
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
package org.gradle.api.internal.tasks.execution

import org.gradle.api.execution.TaskActionListener
import org.gradle.api.internal.TaskInternal
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.tasks.ContextAwareTaskAction
import org.gradle.api.internal.tasks.TaskExecutionContext
import org.gradle.api.internal.tasks.TaskExecutionOutcome
import org.gradle.api.internal.tasks.TaskStateInternal
import org.gradle.api.tasks.StopActionException
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.TaskExecutionException
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.logging.StandardOutputCapture
import spock.lang.Specification

import static java.util.Collections.emptyList

public class ExecuteActionsTaskExecuterTest extends Specification {
    private final TaskInternal task = Mock(TaskInternal);
    private final ContextAwareTaskAction action1 = Mock(ContextAwareTaskAction)
    private final ContextAwareTaskAction action2 = Mock(ContextAwareTaskAction)
    private final TaskStateInternal state = new TaskStateInternal("<task>")
    private final TaskExecutionContext executionContext = Mock(TaskExecutionContext)
    private final ScriptSource scriptSource = Mock(ScriptSource)
    private final StandardOutputCapture standardOutputCapture = Mock(StandardOutputCapture)
    private final TaskActionListener publicListener = Mock(TaskActionListener)
    private final TaskOutputsGenerationListener internalListener = Mock(TaskOutputsGenerationListener)
    private final ExecuteActionsTaskExecuter executer = new ExecuteActionsTaskExecuter(internalListener, publicListener)

    def setup() {
        ProjectInternal project = Mock(ProjectInternal)
        task.getProject() >> project;
        task.getState() >> state
        project.getBuildScriptSource() >> scriptSource
        task.getStandardOutputCapture() >> standardOutputCapture
    }

    void noMoreInteractions() {
        interaction {
            0 * action1._
            0 * action2._
            0 * executionContext._
            0 * standardOutputCapture._
            0 * publicListener._
            0 * internalListener._
        }
    }

    def doesNothingWhenTaskHasNoActions() {
        given:
        task.getTaskActions() >> emptyList()

        when:
        executer.execute(task, state, executionContext);

        then:
        1 * publicListener.beforeActions(task)

        then:
        1 * publicListener.afterActions(task)
        noMoreInteractions()

        state.outcome == TaskExecutionOutcome.UP_TO_DATE
        !state.didWork
        !state.executing
    }

    def executesEachActionInOrder() {
        given:
        task.getTaskActions() >> [action1, action2]

        when:
        executer.execute(task, state, executionContext)

        then:
        1 * publicListener.beforeActions(task)
        then:
        1 * internalListener.beforeTaskOutputsGenerated()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action1.contextualise(executionContext)
        then:
        1 * action1.execute(task) >> {
            assert state.executing
        }
        then:
        1 * action1.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action2.contextualise(executionContext)
        then:
        1 * action2.execute(task)
        then:
        1 * action2.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * publicListener.afterActions(task)
        noMoreInteractions()

        !state.executing
        state.didWork
        state.outcome == TaskExecutionOutcome.EXECUTED
        !state.failure
    }

    def executeDoesOperateOnNewActionListInstance() {
        given:
        interaction {
            task.getActions() >> [action1]
            task.getTaskActions() >> [action1]
        }

        when:
        executer.execute(task, state, executionContext)

        then:
        1 * publicListener.beforeActions(task)
        then:
        1 * internalListener.beforeTaskOutputsGenerated()
        then:
        1 * standardOutputCapture.start()

        then:
        1 * action1.contextualise(executionContext)
        then:
        1 * action1.execute(task) >> {
            task.getActions().add(action2)
        }
        then:
        1 * action1.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * publicListener.afterActions(task)
        noMoreInteractions()
    }

    def stopsAtFirstActionWhichThrowsException() {
        given:
        task.getTaskActions() >> [action1, action2]
        def failure = new RuntimeException("failure")
        action1.execute(task) >> {
            throw failure
        }

        when:
        executer.execute(task, state, executionContext)

        then:
        1 * publicListener.beforeActions(task)
        then:
        1 * internalListener.beforeTaskOutputsGenerated()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action1.contextualise(executionContext)
        then:
        1 * action1.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * publicListener.afterActions(task)

        !state.executing
        state.didWork
        state.outcome == TaskExecutionOutcome.EXECUTED

        TaskExecutionException wrappedFailure = (TaskExecutionException) state.failure
        wrappedFailure instanceof TaskExecutionException
        wrappedFailure.task == task
        wrappedFailure.message.startsWith("Execution failed for ")
        wrappedFailure.cause.is(failure)
    }

    def stopsAtFirstActionWhichThrowsStopExecutionException() {
        given:
        task.getTaskActions() >> [action1, action2]

        when:
        executer.execute(task, state, executionContext)

        then:
        1 * publicListener.beforeActions(task)
        then:
        1 * internalListener.beforeTaskOutputsGenerated()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action1.contextualise(executionContext)
        then:
        1 * action1.execute(task) >> {
            throw new StopExecutionException('stop')
        }
        then:
        1 * action1.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * publicListener.afterActions(task)
        state.didWork
        !state.executing
        state.outcome == TaskExecutionOutcome.EXECUTED
        !state.failure
        noMoreInteractions()
    }

    def skipsActionWhichThrowsStopActionException() {
        given:
        task.getTaskActions() >> [action1, action2]

        when:
        executer.execute(task, state, executionContext)

        then:
        1 * publicListener.beforeActions(task)
        then:
        1 * internalListener.beforeTaskOutputsGenerated()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action1.contextualise(executionContext)
        then:
        1 * action1.execute(task) >> {
            throw new StopActionException('stop')
        }
        then:
        1 * action1.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * standardOutputCapture.start()
        then:
        1 * action2.contextualise(executionContext)
        then:
        1 * action2.execute(task)
        then:
        1 * action2.contextualise(null)
        then:
        1 * standardOutputCapture.stop()
        then:
        1 * publicListener.afterActions(task)

        state.didWork
        state.outcome == TaskExecutionOutcome.EXECUTED
        !state.executing
        !state.failure

        noMoreInteractions()
    }
}
