package com.itemis.maven.plugins.cdi.internal.util.workflow;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.logging.Log;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;

/**
 * An executor for a {@link ProcessingWorkflow} which takes care of executing the steps of the workflow in the correct
 * order as well as rolling back the steps in the correct order in case of a failure.
 *
 * @author <a href="mailto:stanley.hillner@itemis.de">Stanley Hillner</a>
 * @since 2.0.0
 */
public class WorkflowExecutor {
  private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

  private ProcessingWorkflow workflow;
  private Map<String, CDIMojoProcessingStep> processingSteps;
  private Stack<Pair<CDIMojoProcessingStep, ExecutionContext>> executedSteps;
  private PluginParameterExpressionEvaluator expressionEvaluator;

  public WorkflowExecutor(ProcessingWorkflow workflow, Map<String, CDIMojoProcessingStep> processingSteps,
      PluginParameterExpressionEvaluator expressionEvaluator) {
    this.workflow = workflow;
    this.processingSteps = processingSteps;
    this.expressionEvaluator = expressionEvaluator;
  }

  /**
   * Performs a validation of the workflow with respect to the configured set of processing steps this plugin provides.
   * <br>
   * It is verified that each workflow step has a corresponding implementation providing the same step-id as specified
   * in the workflow.
   *
   * @param isOnlineExecution whether Maven is executed in online mode or not.
   * @throws MojoExecutionException if there are missing processing step implementations for one or more ids of the
   *           workflow. The exception message will list all missing ids.
   */
  public void validate(boolean isOnlineExecution) throws MojoExecutionException {
    Set<String> unknownIds = Sets.newHashSet();
    Iterable<WorkflowStep> stepsToCheck = Iterables
        .unmodifiableIterable(Iterables.concat(this.workflow.getProcessingSteps(), this.workflow.getFinallySteps()));
    for (WorkflowStep workflowStep : stepsToCheck) {
      if (workflowStep.isParallel()) {
        ParallelWorkflowStep parallelWorkflowStep = (ParallelWorkflowStep) workflowStep;
        for (SimpleWorkflowStep simpleWorkflowStep : parallelWorkflowStep.getSteps()) {
          CDIMojoProcessingStep step = this.processingSteps.get(simpleWorkflowStep.getStepId());
          if (step == null) {
            unknownIds.add(simpleWorkflowStep.getStepId());
          } else {
            verifyOnlineStatus(step, isOnlineExecution);
          }
        }
      } else {
        SimpleWorkflowStep simpleWorkflowStep = (SimpleWorkflowStep) workflowStep;
        CDIMojoProcessingStep step = this.processingSteps.get(simpleWorkflowStep.getStepId());
        if (step == null) {
          unknownIds.add(simpleWorkflowStep.getStepId());
        } else {
          verifyOnlineStatus(step, isOnlineExecution);
        }
      }
    }

    if (!unknownIds.isEmpty()) {
      throw new MojoExecutionException(
          "There are no implementations for the following processing step ids specified in the workflow: "
              + Joiner.on(',').join(unknownIds));
    }
  }

  private void verifyOnlineStatus(CDIMojoProcessingStep step, boolean isOnlineExecution) throws MojoExecutionException {
    ProcessingStep stepAnnotation = step.getClass().getAnnotation(ProcessingStep.class);
    if (stepAnnotation.requiresOnline() && !isOnlineExecution) {
      throw new MojoExecutionException(
          "The execution of this Mojo requires Maven to operate in online mode but Maven has been started using the offline option.");
    }
  }

  /**
   * Performs the actual workflow execution in the correct order.<br>
   * If an exceptional case is reached, all already executed steps will be rolled back prior to throwing the exception.
   *
   * @throws MojoExecutionException if any of the processing steps of the workflow throw such an exception.
   * @throws MojoFailureException if any of the processing steps of the workflow throw such an exception.
   */
  public void execute() throws MojoExecutionException, MojoFailureException {
    this.log.info("Executing the standard workflow of the goal");
    this.executedSteps = new Stack<Pair<CDIMojoProcessingStep, ExecutionContext>>();

    try {
      for (WorkflowStep workflowStep : this.workflow.getProcessingSteps()) {
        executeSequentialWorkflowStep(workflowStep);
        executeParallelWorkflowSteps(workflowStep);
      }
    } catch (MojoExecutionException e) {
      executeFinallySteps();
      throw e;
    } catch (MojoFailureException e) {
      executeFinallySteps();
      throw e;
    } catch (RuntimeException e) {
      executeFinallySteps();
      throw e;
    }
  }

  private void executeFinallySteps() throws MojoExecutionException, MojoFailureException {
    if (!this.workflow.getFinallySteps().isEmpty()) {
      this.log.info("Executing the finally workflow of the goal");
      this.executedSteps.clear();

      for (SimpleWorkflowStep step : this.workflow.getFinallySteps()) {
        executeSequentialWorkflowStep(step);
      }
    }
  }

  private void executeSequentialWorkflowStep(WorkflowStep workflowStep)
      throws MojoExecutionException, MojoFailureException {
    if (workflowStep.isParallel()) {
      return;
    }

    SimpleWorkflowStep simpleWorkflowStep = (SimpleWorkflowStep) workflowStep;
    ExecutionContext executionContext = this.workflow.getExecutionContext(simpleWorkflowStep.getCompositeStepId());
    CDIMojoProcessingStep step = this.processingSteps.get(simpleWorkflowStep.getStepId());
    try {
      this.executedSteps.push(Pair.of(step, executionContext));
      executionContext.expandProjectVariables(this.expressionEvaluator);
      step.execute(executionContext);
    } catch (Throwable t) {
      this.log.error("An exception was caught while processing the workflow step with id '"
          + simpleWorkflowStep.getCompositeStepId() + "'.", t);
      rollback(t);

      // throw original exception after rollback!
      if (t instanceof MojoExecutionException) {
        throw (MojoExecutionException) t;
      } else if (t instanceof MojoFailureException) {
        throw (MojoFailureException) t;
      } else if (t instanceof RuntimeException) {
        throw (RuntimeException) t;
      } else {
        throw new RuntimeException(t);
      }
    }
  }

  private void executeParallelWorkflowSteps(WorkflowStep workflowStep)
      throws MojoExecutionException, MojoFailureException {
    if (!workflowStep.isParallel()) {
      return;
    }

    Queue<Future<?>> results = new LinkedList<Future<?>>();
    final Collection<Throwable> thrownExceptions = Lists.newArrayList();

    ParallelWorkflowStep parallelWorkflowStep = (ParallelWorkflowStep) workflowStep;
    ExecutorService executorService = Executors.newFixedThreadPool(parallelWorkflowStep.getSteps().size());
    for (final SimpleWorkflowStep simpleWorkflowStep : parallelWorkflowStep.getSteps()) {
      results.offer(executorService.submit(new Runnable() {
        @Override
        public void run() {
          CDIMojoProcessingStep step = WorkflowExecutor.this.processingSteps.get(simpleWorkflowStep.getStepId());
          try {
            ExecutionContext executionContext = WorkflowExecutor.this.workflow
                .getExecutionContext(simpleWorkflowStep.getCompositeStepId());
            WorkflowExecutor.this.executedSteps.push(Pair.of(step, executionContext));
            executionContext.expandProjectVariables(WorkflowExecutor.this.expressionEvaluator);
            step.execute(executionContext);
          } catch (Throwable t) {
            WorkflowExecutor.this.log.error("An exception was caught while processing the workflow step with id '"
                + simpleWorkflowStep.getCompositeStepId() + "'.", t);
            thrownExceptions.add(t);
          }
        }
      }));
    }

    while (!results.isEmpty()) {
      Future<?> result = results.poll();
      try {
        result.get();
      } catch (InterruptedException e) {
        results.offer(result);
      } catch (ExecutionException e) {
        // do nothing since this exception will be handled later!
      }
    }

    Throwable firstError = Iterables.getFirst(thrownExceptions, null);
    if (firstError != null) {
      rollback(firstError);
      // throw original exception after rollback!
      if (firstError instanceof MojoExecutionException) {
        throw (MojoExecutionException) firstError;
      } else if (firstError instanceof MojoFailureException) {
        throw (MojoFailureException) firstError;
      } else if (firstError instanceof RuntimeException) {
        throw (RuntimeException) firstError;
      } else {
        throw new RuntimeException(firstError);
      }
    }
  }

  private void rollback(Throwable t) {
    this.log.info("Rolling back after execution errors - please find the error messages and stack traces above.");
    while (!this.executedSteps.empty()) {
      Pair<CDIMojoProcessingStep, ExecutionContext> pair = this.executedSteps.pop();
      rollback(pair.getLeft(), pair.getRight(), t);
    }
  }

  private void rollback(CDIMojoProcessingStep step, ExecutionContext executionContext, Throwable t) {
    // get rollback methods and sort alphabetically
    List<Method> rollbackMethods = getRollbackMethods(step, t.getClass());
    Collections.sort(rollbackMethods, new Comparator<Method>() {
      @Override
      public int compare(Method m1, Method m2) {
        return m1.getName().compareTo(m2.getName());
      }
    });

    // call rollback methods
    for (Method rollbackMethod : rollbackMethods) {
      rollbackMethod.setAccessible(true);
      try {
        Class<?>[] parameterTypes = rollbackMethod.getParameterTypes();
        switch (parameterTypes.length) {
          case 0:
            rollbackMethod.invoke(step);
            break;
          case 1:
            if (ExecutionContext.class == parameterTypes[0]) {
              rollbackMethod.invoke(step, executionContext);
            } else {
              rollbackMethod.invoke(step, t);
            }
            break;
          case 2:
            if (ExecutionContext.class == parameterTypes[0]) {
              rollbackMethod.invoke(step, executionContext, t);
            } else {
              rollbackMethod.invoke(step, t, executionContext);
            }
            break;
        }
      } catch (ReflectiveOperationException e) {
        this.log.error("An exception was caught while rolling back the workflow step with id '"
            + executionContext.getCompositeStepId() + "'. Proceeding with the rollback of the next steps.", e);
      }
    }
  }

  private <T extends Throwable> List<Method> getRollbackMethods(CDIMojoProcessingStep mojo, Class<T> causeType) {
    List<Method> rollbackMethods = Lists.newArrayList();
    for (Method m : mojo.getClass().getDeclaredMethods()) {
      RollbackOnError rollbackAnnotation = m.getAnnotation(RollbackOnError.class);
      if (rollbackAnnotation != null) {
        boolean considerMethod = false;

        // consider method for inclusion if no error types are declared or if one of the declared error types is a
        // supertype of the caught exception
        Class<? extends Throwable>[] errorTypes = rollbackAnnotation.value();
        if (errorTypes.length == 0) {
          considerMethod = true;
        } else {
          for (Class<? extends Throwable> errorType : errorTypes) {
            if (errorType.isAssignableFrom(causeType)) {
              considerMethod = true;
              break;
            }
          }
        }

        // now check also the method parameters (0 or one exception type)
        if (considerMethod) {
          Class<?>[] parameterTypes = m.getParameterTypes();
          switch (parameterTypes.length) {
            case 0:
              rollbackMethods.add(m);
              break;
            case 1:
              if (parameterTypes[0].isAssignableFrom(causeType)) {
                rollbackMethods.add(m);
              } else if (parameterTypes[0] == ExecutionContext.class) {
                rollbackMethods.add(m);
              }
              break;
            case 2:
              if (parameterTypes[0] == ExecutionContext.class && parameterTypes[1].isAssignableFrom(causeType)) {
                rollbackMethods.add(m);
              } else if (parameterTypes[0].isAssignableFrom(causeType) && parameterTypes[1] == ExecutionContext.class) {
                rollbackMethods.add(m);
              }
              break;
            default:
              this.log.warn(
                  "Found rollback method with more than two parameters! Only zero, one or two parameters of type <T extends Throwable> and ExecutionContext are allowed!");
              break;
          }
        }
      }
    }

    return rollbackMethods;
  }
}
