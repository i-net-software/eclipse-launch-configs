package de.inetsoftware.eclipselaunch.run

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.io.FileUtil
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.sync.LaunchSyncService

/**
 * Resolves Eclipse variables when a configuration is launched, optionally points Eclipse output folders to the
 * IDEA output, and adds the "Eclipse" section to the run configuration editor.
 */
class EclipseLaunchExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is EclipseLinkedConfiguration

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean = true

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
        val config = configuration as? EclipseLinkedConfiguration ?: return
        if (config.link == null) return
        val resolver = LaunchResolver(config)
        ApplicationManager.getApplication().runReadAction {
            replaceAll(params.vmParametersList, resolver::resolve)
            replaceAll(params.programParametersList, resolver::resolve)
            params.workingDirectory?.let { params.workingDirectory = FileUtil.toSystemIndependentName(resolver.resolve(it)) }
            if (params.env.isNotEmpty()) params.env = params.env.mapValues { resolver.resolve(it.value) }
        }
        report(configuration, resolver)
    }

    private fun report(configuration: RunConfigurationBase<*>, resolver: LaunchResolver) {
        val rewriter = resolver.rewriter
        val text = buildString {
            if (resolver.variables.unresolved.isNotEmpty()) append("Unknown variables: ").append(resolver.variables.unresolved.joinToString { "\${$it}" }).append('\n')
            if (rewriter != null) append(rewriter.report())
        }
        if (text.isNotEmpty()) LOG.info("Launch of \"${configuration.name}\":\n$text")
        val problems = (rewriter?.unmapped.orEmpty()).toList()
        if (problems.isNotEmpty() || resolver.variables.unresolved.isNotEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup(LaunchSyncService.NOTIFICATION_GROUP).createNotification(
                "\"${configuration.name}\": not everything was redirected",
                buildString {
                    if (problems.isNotEmpty()) append("Eclipse output folders without IDEA module (left as is): ").append(problems.joinToString(", ")).append(". ")
                    if (resolver.variables.unresolved.isNotEmpty()) append("Unknown variables: ").append(resolver.variables.unresolved.joinToString { "\${$it}" }).append('.')
                },
                NotificationType.WARNING,
            ).notify(configuration.project)
        }
    }

    private fun replaceAll(list: ParametersList, resolve: (String) -> String) {
        val params = list.list
        val resolved = params.map(resolve)
        if (resolved != params) {
            list.clearAll()
            list.addAll(resolved)
        }
    }

    override fun patchCommandLine(
        configuration: RunConfigurationBase<*>,
        runnerSettings: RunnerSettings?,
        cmdLine: GeneralCommandLine,
        runnerId: String,
    ) {
    }

    override fun <P : RunConfigurationBase<*>> createFragments(configuration: P): List<SettingsEditor<P>> {
        val editor = EclipseSettingsEditor<P>(configuration.project)
        @Suppress("UNCHECKED_CAST")
        return listOf(SettingsEditorFragment.createWrapper("eclipse.launch", "Eclipse", "Eclipse", editor) { true } as SettingsEditor<P>)
    }
}

private val LOG = logger<EclipseLaunchExtension>()
