package com.itemis.maven.plugins.cdi.internal.util.workflow;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.internal.util.workflow.ParallelWorkflowStep.Builder;

import de.vandermeer.asciitable.v2.RenderedTable;
import de.vandermeer.asciitable.v2.V2_AsciiTable;
import de.vandermeer.asciitable.v2.render.V2_AsciiTableRenderer;
import de.vandermeer.asciitable.v2.render.WidthLongestLine;
import de.vandermeer.asciitable.v2.row.ContentRow;
import de.vandermeer.asciitable.v2.themes.V2_E_TableThemes;

/**
 * A utility class all around workflow processing, except the actual execution of the workflows.
 *
 * @author <a href="mailto:stanley.hillner@itemis.de">Stanley Hillner</a>
 * @since 2.0.0
 */
public class WorkflowUtil {

  public static final String CONTEXT_DATA_MAP_ASSIGNMENT = "=>";
  public static final String CONTEXT_DATA_SEPARATOR = ",";
  private static final String DEFAULT_WORKFLOW_DIR = "META-INF/workflows";

  private static final Logger log = LoggerFactory.getLogger(WorkflowUtil.class);

  /**
   * Parses a workflow from its descriptor representation.
   *
   * @param is the input stream to read the workflow descriptor from. This stream will be closed after reading the
   *          workflow descriptor.
   * @param goalName the name of the goal this workflow is designed for.
   * @return the parsed processing workflow.
   */
  // TODO rework parser! -> too many decision branches!
  public static ProcessingWorkflow parseWorkflow(InputStream is, String goalName) {
    ProcessingWorkflow workflow = new ProcessingWorkflow(goalName);

    BufferedReader br = null;
    try {
      Builder parallelStepBuilder = null;
      SimpleWorkflowStep currentStep = null;
      boolean isTryBlock = false;
      boolean isFinallyBlock = false;

      br = new BufferedReader(new InputStreamReader(is));
      String line;
      while ((line = br.readLine()) != null) {
        line = line.trim();
        if (line.startsWith(WorkflowConstants.KW_COMMENT) || line.isEmpty()) {
          continue;
        }

        if (line.startsWith(WorkflowConstants.KW_TRY)) {
          isTryBlock = true;
          isFinallyBlock = false;
        } else if (line.startsWith(WorkflowConstants.KW_PARALLEL)) {
          parallelStepBuilder = ParallelWorkflowStep.builder();
        } else if (Objects.equal(WorkflowConstants.KW_BLOCK_CLOSE, line)) {
          if (currentStep != null) {
            currentStep = null;
          } else if (isFinallyBlock) {
            isFinallyBlock = false;
          } else if (parallelStepBuilder != null) {
            workflow.addProcessingStep(parallelStepBuilder.build());
          }
        } else if (line.startsWith(WorkflowConstants.KW_BLOCK_CLOSE)) {
          if (isTryBlock) {
            isTryBlock = false;
            String substring = line.substring(1).trim();
            if (substring.startsWith(WorkflowConstants.KW_FINALLY)
                && substring.endsWith(WorkflowConstants.KW_BLOCK_OPEN)) {
              isFinallyBlock = true;
            }
          }
        } else {
          if (currentStep == null) {
            String id = parseId(line);
            Optional<String> qualifier = parseQualifier(line);
            SimpleWorkflowStep step = new SimpleWorkflowStep(id, qualifier);
            if (line.endsWith(WorkflowConstants.KW_BLOCK_OPEN)) {
              currentStep = step;
            }

            if (isFinallyBlock) {
              workflow.addFinallyStep(step);
            } else {
              if (parallelStepBuilder == null) {
                workflow.addProcessingStep(step);
              } else {
                parallelStepBuilder.addSteps(step);
              }
            }
          } else {
            setDefaultExecutionData(currentStep, line);
            setDefaultRollbackData(currentStep, line);
          }
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Unable to read the workflow descriptor from the provided input stream.", e);
    } finally {
      Closeables.closeQuietly(br);
    }

    return workflow;
  }

  private static String parseId(String line) {
    int qualifierOpen = line.indexOf(WorkflowConstants.KW_QUALIFIER_OPEN);
    int blockOpen = line.indexOf(WorkflowConstants.KW_BLOCK_OPEN);
    int toIndex;
    if (qualifierOpen > -1) {
      toIndex = qualifierOpen;
    } else if (blockOpen > -1) {
      toIndex = blockOpen;
    } else {
      toIndex = line.length();
    }
    return line.substring(0, toIndex).trim();
  }

  private static Optional<String> parseQualifier(String line) {
    String qualifier = null;
    int qualifierOpen = line.indexOf(WorkflowConstants.KW_QUALIFIER_OPEN);
    if (qualifierOpen > -1) {
      int qualifierClose = line.indexOf(WorkflowConstants.KW_QUALIFIER_CLOSE, qualifierOpen);
      qualifier = line.substring(qualifierOpen + 1, qualifierClose);
    }
    return Optional.fromNullable(qualifier);
  }

  private static void setDefaultExecutionData(SimpleWorkflowStep step, String line) {
    if (line.startsWith(WorkflowConstants.KW_DATA)) {
      int startIndex = line.indexOf(WorkflowConstants.KW_DATA_ASSIGNMENT) + 1;
      step.setDefaultExecutionData(line.substring(startIndex).trim());
    }
  }

  private static void setDefaultRollbackData(SimpleWorkflowStep step, String line) {
    if (line.startsWith(WorkflowConstants.KW_ROLLBACK_DATA)) {
      int startIndex = line.indexOf(WorkflowConstants.KW_DATA_ASSIGNMENT) + 1;
      step.setDefaultRollbackData(line.substring(startIndex).trim());
    }
  }

  public static void addExecutionContexts(ProcessingWorkflow workflow) {
    Iterable<WorkflowStep> steps = Iterables
        .unmodifiableIterable(Iterables.concat(workflow.getProcessingSteps(), workflow.getFinallySteps()));
    for (WorkflowStep step : steps) {
      if (step.isParallel()) {
        ParallelWorkflowStep parallelStep = (ParallelWorkflowStep) step;
        for (SimpleWorkflowStep simpleStep : parallelStep.getSteps()) {
          workflow.addExecutionContext(simpleStep.getCompositeStepId(), createExecutionContext(simpleStep));
        }
      } else {
        SimpleWorkflowStep simpleStep = (SimpleWorkflowStep) step;
        workflow.addExecutionContext(simpleStep.getCompositeStepId(), createExecutionContext(simpleStep));
      }
    }
  }

  private static ExecutionContext createExecutionContext(SimpleWorkflowStep step) {
    com.itemis.maven.plugins.cdi.ExecutionContext.Builder builder = ExecutionContext.builder(step.getStepId());
    if (step.getQualifier().isPresent()) {
      builder.setQualifier(step.getQualifier().get());
    }

    String dataPropertyValue = System.getProperty(step.getCompositeStepId());
    if (dataPropertyValue == null) {
      dataPropertyValue = step.getDefaultExecutionData().orNull();
    }
    if (dataPropertyValue != null) {
      Iterable<String> split = Splitter.on(CONTEXT_DATA_SEPARATOR).split(dataPropertyValue);
      for (String token : split) {
        String date = Strings.emptyToNull(token.trim());
        if (date != null) {
          List<String> dataSplit = Splitter.on(CONTEXT_DATA_MAP_ASSIGNMENT).splitToList(date);
          if (dataSplit.size() == 1) {
            builder.addData(dataSplit.get(0));
          } else {
            builder.addData(dataSplit.get(0), dataSplit.get(1));
          }
        }
      }
    }

    String dataRollbackPropertyValue = System.getProperty(step.getCompositeStepId() + "-rollback");
    if (dataRollbackPropertyValue == null) {
      dataRollbackPropertyValue = step.getDefaultRollbackData().orNull();
    }
    if (dataRollbackPropertyValue != null) {
      Iterable<String> split = Splitter.on(CONTEXT_DATA_SEPARATOR).split(dataRollbackPropertyValue);
      for (String token : split) {
        String date = Strings.emptyToNull(token.trim());
        if (date != null) {
          List<String> dataSplit = Splitter.on(CONTEXT_DATA_MAP_ASSIGNMENT).splitToList(date);
          if (dataSplit.size() == 1) {
            builder.addRollbackData(dataSplit.get(0));
          } else {
            builder.addRollbackData(dataSplit.get(0), dataSplit.get(1));
          }
        }
      }
    }

    return builder.build();
  }

  public static InputStream getWorkflowDescriptor(String goalName, PluginDescriptor pluginDescriptor,
      Optional<File> customWorkflowDescriptor) throws MojoExecutionException {
    log.info("Constructing workflow for processing");
    String goalPrefix = pluginDescriptor.getGoalPrefix();

    if (customWorkflowDescriptor.isPresent()) {
      File customDescriptor = customWorkflowDescriptor.get();
      log.debug("Requested overriding of workflow with file: " + customDescriptor.getAbsolutePath());

      if (customDescriptor.exists() && customDescriptor.isFile()) {
        try {
          log.info("Workflow of goal '" + goalPrefix + ':' + goalName + "' will be overriden by file '"
              + customDescriptor.getAbsolutePath() + "'.");
          return new FileInputStream(customDescriptor);
        } catch (Exception e) {
          throw new MojoExecutionException("Unable to load custom workflow for goal " + goalName, e);
        }
      } else {
        throw new MojoExecutionException("Unable to load custom workflow for goal " + goalPrefix + ':' + goalName
            + ". The workflow file '" + customDescriptor.getAbsolutePath() + "' does not exist!");
      }
    }

    log.info("Goal '" + goalPrefix + ':' + goalName + "' will use default workflow packaged with the plugin.");
    return Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_WORKFLOW_DIR + "/" + goalName);
  }

  public static void printWorkflow(
      String goalName, PluginDescriptor pluginDescriptor, Optional<File> customWorkflowDescriptor) throws MojoExecutionException {
    StringBuilder sb = new StringBuilder();
    if (StringUtils.isNotBlank(pluginDescriptor.getGoalPrefix())) {
      sb.append(pluginDescriptor.getGoalPrefix());
    } else {
      sb.append(pluginDescriptor.getGroupId()).append(':').append(pluginDescriptor.getArtifactId()).append(':')
          .append(pluginDescriptor.getVersion());
    }
    sb.append(':').append(goalName);

    log.info("Default workflow for '" + sb + "':");

    InputStream workflowDescriptor = getWorkflowDescriptor(goalName, pluginDescriptor, customWorkflowDescriptor);
    try {
      int x = 77 - goalName.length();
      int a = x / 2;
      int b = x % 2 == 1 ? a + 1 : a;
      StringBuilder separator = new StringBuilder();
      separator.append(Strings.repeat("=", a)).append(' ').append(goalName).append(' ').append(Strings.repeat("=", b));

      System.out.println(separator);
      ByteStreams.copy(workflowDescriptor, System.out);
      System.out.println(separator);
    } catch (IOException e) {
      throw new MojoExecutionException("A problem occurred during the serialization of the defualt workflow.", e);
    } finally {
      Closeables.closeQuietly(workflowDescriptor);
    }
  }

  public static boolean printAvailableSteps(Map<String, ProcessingStep> steps)
      throws MojoExecutionException {
    V2_AsciiTable table = new V2_AsciiTable();
    table.addRule();
    ContentRow header = table.addRow("ID", "DESCRIPTION", "REQUIRES ONLINE");
    header.setAlignment(new char[] { 'c', 'c', 'c' });
    table.addStrongRule();

    List<String> sortedIds = Lists.newArrayList(steps.keySet());
    Collections.sort(sortedIds);
    for (String id : sortedIds) {
      ProcessingStep annotation = steps.get(id);
      ContentRow data = table.addRow(annotation.id(), annotation.description(), annotation.requiresOnline());
      data.setAlignment(new char[] { 'l', 'l', 'c' });
      table.addRule();
    }

    V2_AsciiTableRenderer renderer = new V2_AsciiTableRenderer();
    renderer.setTheme(V2_E_TableThemes.UTF_STRONG_DOUBLE.get());
    renderer.setWidth(new WidthLongestLine().add(10, 20).add(20, 50).add(10, 10));
    RenderedTable renderedTable = renderer.render(table);

    log.info(
        "The following processing steps are available on classpath and can be configured as part of a custom workflow.");
    System.out.println(renderedTable);

    return true;
  }
}
