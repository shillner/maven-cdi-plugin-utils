package com.itemis.maven.plugins.cdi;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.itemis.maven.plugins.cdi.annotations.MojoProduces;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.internal.beans.CdiBeanWrapper;
import com.itemis.maven.plugins.cdi.internal.beans.CdiProducerBean;
import com.itemis.maven.plugins.cdi.internal.util.CDIUtil;
import com.itemis.maven.plugins.cdi.internal.util.MavenUtil;
import com.itemis.maven.plugins.cdi.internal.util.workflow.ProcessingWorkflow;
import com.itemis.maven.plugins.cdi.internal.util.workflow.WorkflowExecutor;
import com.itemis.maven.plugins.cdi.internal.util.workflow.WorkflowUtil;
import com.itemis.maven.plugins.cdi.internal.util.workflow.WorkflowValidator;

/**
 * An abstract Mojo that enables CDI-based dependency injection for the current maven plugin.<br>
 * This Mojo enables you to decouple different parts of your plugin implementation and also dynamically inject
 * additional funktionality into your plugin.<br>
 * <br>
 *
 * <b>ATTENTION:</b> Please do not use annotations such as {@code @javax.inject.Inject} or
 * {@code @javax.enterprise.inject.Produces} directly in your Mojo! There are special replacements for that in the
 * annotations package of this library. Using CDI annotations directly in the Mojo would trigger Maven's own CDI
 * adaption!<br>
 * <br>
 *
 * Using this abstract Mojo as the parent of your own Mojo, you can simply see the Mojo class as a data container whose
 * single responsibility is to provide parameters for your business logic implementations. Simply get the
 * Mojo parameters injected and use the producer annotation to provide the bean to your implementations:
 *
 * <pre>
 * &#64;Parameter
 * &#64;MojoProduces
 * &#64;Named("sourcePath")
 * private String sourcePath;
 *
 * &#64;Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
 * &#64;MojoProduces
 * &#64;Named("reactorProjects")
 * private List&lt;MavenProject&gt; reactorProjects;
 * </pre>
 *
 * Or use a producer method for the logger:
 *
 * <pre>
 * &#64;MojoProduces
 * public MavenLogWrapper createLogWrapper() {
 *   MavenLogWrapper log = new MavenLogWrapper(getLog());
 *   if (this.enableLogTimestamps) {
 *     log.enableLogTimestamps();
 *   }
 *   return log;
 * }
 * </pre>
 *
 * <b>ATTENTION:</b> Make sure to not override the {@link #execute()} method since this method is responsible for the
 * CDI setup and will
 * trigger your business logic impelementations automatically.<br>
 * Implement your business logic in one or more classes that are annotated with {@link ProcessingStep} and implement
 * {@link CDIMojoProcessingStep}. Then orchestrate your standard business workflow in a worflow descriptor file.<br>
 * <br>
 *
 * <h1>The Workflow Descriptor</h1>
 * <ul>
 * <li>The descriptor is located under <i>META-INF/workflows</i></li>
 * <li>The name of the workflow descriptor file must match the name of the goal. F.i. goal="perform"
 * workflow-file="META-INF/workflows/perform"</li>
 * <li>A simple workflow lists just all processing step ids in the respective order (each id on a new line).</li>
 * <li>Steps that are encapsuled in <code>parallel{}</code> are executed in parallel. All other steps will be executed
 * sequentially.</li>
 * <li>A line starting with a <code>#</code> will be treated as a comment.</li>
 * </ul>
 *
 * <h2>A Sample Workflow</h2>
 * goal=perform
 * workflow-file=META-INF/workflows/perform
 *
 * <pre>
 * init
 * # The following steps can be run in parallel since they do not modify the project but only perform some checks
 * parallel {
 *   checkUser
 *   checkConnection
 *   checkAether
 * }
 * compute
 * upload
 * validate
 * </pre>
 *
 * @author <a href="mailto:stanley.hillner@itemis.de">Stanley Hillner</a>
 * @since 1.0.0
 */
public class AbstractCDIMojo extends AbstractMojo implements Extension {
  private static final String SYSPROP_PRINT_WF = "printWorkflow";
  private static final String SYSPROP_PRINT_STEPS = "printSteps";

  @Component
  private ArtifactResolver _resolver;

  @Parameter(defaultValue = "${settings}", readonly = true, required = true)
  private Settings _settings;

  @Parameter(readonly = true, defaultValue = "${repositorySystemSession}")
  private RepositorySystemSession _repoSystemSession;

  @Parameter(readonly = true, defaultValue = "${project.remotePluginRepositories}")
  private List<RemoteRepository> _pluginRepos;

  @Parameter(property = "mojoExecution", readonly = true)
  private MojoExecution _mojoExecution;

  @Parameter(property = "session", readonly = true)
  private MavenSession _session;

  @Parameter(property = "workflow")
  private File workflowDescriptor;

  private ProcessingWorkflow workflow;

  private Map<String, ProcessingStep> allAvailableProcessingSteps = Maps.newHashMap();

  @Override
  public final void execute() throws MojoExecutionException, MojoFailureException {
    if (System.getProperty(SYSPROP_PRINT_WF) != null) {
      WorkflowUtil.printWorkflow(getGoalName(), getPluginDescriptor(), Optional.fromNullable(this.workflowDescriptor));
      return;
    }

    System.setProperty("org.jboss.logging.provider", "slf4j");
    String logLevel = "info";
    if (getLog().isDebugEnabled()) {
      logLevel = "debug";
    }
    System.setProperty("org.slf4j.simpleLogger.log.org.jboss.weld", logLevel);

    Weld weld = new Weld();
    weld.addExtension(this);
    addPluginDependencies(weld);
    WeldContainer weldContainer = null;
    try {
      weldContainer = weld.initialize();
      if (System.getProperty(SYSPROP_PRINT_STEPS) != null) {
        WorkflowUtil.printAvailableSteps(this.allAvailableProcessingSteps);
        return;
      }

      WorkflowUtil.addExecutionContexts(getWorkflow());
      Map<String, CDIMojoProcessingStep> processingSteps = getAllProcessingSteps(weldContainer);

      PluginParameterExpressionEvaluator expressionEvaluator = new PluginParameterExpressionEvaluator(this._session,
          this._mojoExecution);
      WorkflowExecutor executor = new WorkflowExecutor(getWorkflow(), processingSteps, expressionEvaluator);
      executor.validate(!this._settings.isOffline());
      executor.execute();
    } finally {
      if (weldContainer != null && weldContainer.isRunning()) {
        weldContainer.shutdown();
      }
    }
  }

  private ProcessingWorkflow getWorkflow() throws MojoExecutionException, MojoFailureException {
    if (this.workflow == null) {
      InputStream wfDescriptor = WorkflowUtil.getWorkflowDescriptor(getGoalName(), getPluginDescriptor(),
          Optional.fromNullable(this.workflowDescriptor));

      try {
        WorkflowValidator.validateSyntactically(wfDescriptor);
      } catch (RuntimeException e) {
        throw new MojoFailureException(e.getMessage());
      }

      wfDescriptor = WorkflowUtil.getWorkflowDescriptor(getGoalName(), getPluginDescriptor(),
          Optional.fromNullable(this.workflowDescriptor));
      this.workflow = WorkflowUtil.parseWorkflow(wfDescriptor, getGoalName());
    }
    return this.workflow;
  }

  @SuppressWarnings("unused")
  // will be called automatically by the CDI container for all annotated types
  private void skipUnusedStepsFromBeanDiscovery(@Observes ProcessAnnotatedType<?> event, BeanManager beanManager)
      throws MojoExecutionException, MojoFailureException {
    // https://github.com/shillner/maven-cdi-plugin-utils/issues/14
    Class<?> type = event.getAnnotatedType().getJavaClass();
    ProcessingStep annotation = type.getAnnotation(ProcessingStep.class);
    if (annotation != null) {
      // adding the step to the list of all available processing steps
      String id = annotation.id();
      Preconditions.checkState(!this.allAvailableProcessingSteps.containsKey(id),
          "The processing step id '" + id + "' is not unique!");
      this.allAvailableProcessingSteps.put(id, annotation);

      // vetoing the bean discovery of a step that is not part of the current workflow
      // this prevents the issue that data shall be injected that isn't produced anywhere!
      ProcessingWorkflow workflow = getWorkflow();
      if (!workflow.containsStep(annotation.id())) {
        event.veto();
      }
    }
  }

  @SuppressWarnings("unused")
  // will be called automatically by the CDI container once the bean discovery has finished
  private void processMojoCdiProducerFields(@Observes AfterBeanDiscovery event, BeanManager beanManager)
      throws MojoExecutionException {
	  
	Class<?> cls = getClass();
    Set<Field> fields = Sets.newHashSet();

    while (cls != AbstractCDIMojo.class) {
    	fields.addAll(Sets.newHashSet(cls.getFields()));
    	fields.addAll(Sets.newHashSet(cls.getDeclaredFields()));
    	cls = cls.getSuperclass();
    }
    
    for (Field f : fields) {
      if (f.isAnnotationPresent(MojoProduces.class)) {
        try {
          f.setAccessible(true);
          event.addBean(
              new CdiBeanWrapper<Object>(f.get(this), f.getGenericType(), f.getType(), CDIUtil.getCdiQualifiers(f)));
        } catch (Throwable t) {
          throw new MojoExecutionException("Could not process CDI producer field of the Mojo.", t);
        }
      }
    }
  }

  @SuppressWarnings({ "unused", "unchecked", "rawtypes" })
  // will be called automatically by the CDI container once the bean discovery has finished
  private void processMojoCdiProducerMethods(@Observes AfterBeanDiscovery event, BeanManager beanManager)
      throws MojoExecutionException {
    // no method parameter injection possible at the moment since the container is not yet initialized at this point!
    Class<?> cls = getClass();
    Set<Method> methods = Sets.newHashSet();

    while (cls != AbstractCDIMojo.class) {
    	methods.addAll(Sets.newHashSet(cls.getMethods()));
    	methods.addAll(Sets.newHashSet(cls.getDeclaredMethods()));
    	cls = cls.getSuperclass();
    }
    
    for (Method m : methods) {
      if (m.getReturnType() != Void.class && m.isAnnotationPresent(MojoProduces.class)) {
        try {
          event.addBean(new CdiProducerBean(m, this, beanManager, m.getGenericReturnType(), m.getReturnType(),
              CDIUtil.getCdiQualifiers(m)));
        } catch (Throwable t) {
          throw new MojoExecutionException("Could not process CDI producer method of the Mojo.", t);
        }
      }
    }
  }

  private void addPluginDependencies(Weld weld) throws MojoExecutionException {
    PluginDescriptor pluginDescriptor = getPluginDescriptor();
    List<Dependency> dependencies = pluginDescriptor.getPlugin().getDependencies();
    for (Dependency d : dependencies) {
      Optional<File> f = MavenUtil.resolvePluginDependency(d, this._pluginRepos, this._resolver,
          this._repoSystemSession);
      if (f.isPresent()) {
        CDIUtil.addAllClasses(weld, getClass().getClassLoader(), f.get(), getLog());
      } else {
        throw new MojoExecutionException("Could not resolve the following plugin dependency: " + d);
      }
    }
  }

  private Map<String, CDIMojoProcessingStep> getAllProcessingSteps(WeldContainer weldContainer) {
    Map<String, CDIMojoProcessingStep> steps = Maps.newHashMap();
    Collection<CDIMojoProcessingStep> beans = CDIUtil.getAllBeansOfType(weldContainer, CDIMojoProcessingStep.class);
    for (CDIMojoProcessingStep bean : beans) {
      ProcessingStep annotation = bean.getClass().getAnnotation(ProcessingStep.class);
      if (annotation != null) {
        String id = annotation.id();
        Preconditions.checkState(!steps.containsKey(id), "The processing step id '" + id + "' is not unique!");
        steps.put(id, bean);
      }
    }
    return steps;
  }

  private String getGoalName() {
    PluginDescriptor pluginDescriptor = getPluginDescriptor();
    for (MojoDescriptor mojoDescriptor : pluginDescriptor.getMojos()) {
      if (mojoDescriptor.getImplementation().equals(getClass().getName())) {
        return mojoDescriptor.getGoal();
      }
    }
    return null;
  }

  private PluginDescriptor getPluginDescriptor() {
    return (PluginDescriptor) getPluginContext().get("pluginDescriptor");
  }

  private MavenProject getProject() {
    return (MavenProject) getPluginContext().get("project");
  }
}
