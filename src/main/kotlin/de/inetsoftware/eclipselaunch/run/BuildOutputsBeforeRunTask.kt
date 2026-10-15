package de.inetsoftware.eclipselaunch.run

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Key
import com.intellij.task.ProjectTaskManager
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import javax.swing.Icon

class BuildOutputsBeforeRunTask : BeforeRunTask<BuildOutputsBeforeRunTask>(ID) {
    init {
        isEnabled = true
    }

    companion object {
        val ID: Key<BuildOutputsBeforeRunTask> = Key.create("EclipseLaunch.BuildOutputs")
    }
}

/**
 * Builds the modules whose output replaces Eclipse output folders (`bin/main`) in the arguments. IDEA's own "Build"
 * only compiles the modules the configuration depends on; plugin class paths passed in system properties are not.
 */
class BuildOutputsBeforeRunTaskProvider : BeforeRunTaskProvider<BuildOutputsBeforeRunTask>() {
    override fun getId() = BuildOutputsBeforeRunTask.ID
    override fun getName() = "Build modules of Eclipse output folders"
    override fun getIcon(): Icon = AllIcons.Actions.Compile
    override fun getDescription(task: BuildOutputsBeforeRunTask) = name

    override fun createTask(configuration: RunConfiguration): BuildOutputsBeforeRunTask? =
        if (configuration is EclipseLinkedConfiguration) BuildOutputsBeforeRunTask() else null

    override fun canExecuteTask(configuration: RunConfiguration, task: BuildOutputsBeforeRunTask) = configuration is EclipseLinkedConfiguration

    override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: BuildOutputsBeforeRunTask): Boolean {
        val config = configuration as? EclipseLinkedConfiguration ?: return true
        if (config.link?.rewriteOutput == false) return true
        val resolver = LaunchResolver(config)
        val rewriter = resolver.rewriter ?: return true
        ReadAction.run<RuntimeException> {
            resolver.resolve(config.vmParameters.orEmpty())
            resolver.resolve(config.programParameters.orEmpty())
            config.envs.values.forEach { resolver.resolve(it) }
        }
        val modules = rewriter.modules.toTypedArray()
        if (modules.isEmpty()) return true
        LOG.info("Building ${modules.size} modules for \"${configuration.name}\": ${modules.joinToString { it.name }}")
        val result = ProjectTaskManager.getInstance(configuration.project).build(*modules).blockingGet(Int.MAX_VALUE) ?: return false
        return !result.isAborted && !result.hasErrors()
    }
}

private val LOG = logger<BuildOutputsBeforeRunTaskProvider>()
