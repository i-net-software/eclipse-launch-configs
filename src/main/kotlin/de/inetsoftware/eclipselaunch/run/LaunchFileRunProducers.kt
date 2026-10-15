package de.inetsoftware.eclipselaunch.run

import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.model.LaunchKind

/** "Run"/"Debug" on a `.launch` file starts its run configuration. */
abstract class LaunchFileRunProducer(private val kind: LaunchKind) : LazyRunConfigurationProducer<RunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory = EclipseLaunchConfigurationType.getInstance().factory(kind)

    private fun launchPath(context: ConfigurationContext): String? {
        val file = context.location?.virtualFile ?: context.psiLocation?.containingFile?.virtualFile ?: return null
        return file.path.takeIf { file.extension == "launch" }
    }

    override fun isConfigurationFromContext(configuration: RunConfiguration, context: ConfigurationContext): Boolean {
        val path = launchPath(context) ?: return false
        return (configuration as? EclipseLinkedConfiguration)?.link?.path == path
    }

    override fun setupConfigurationFromContext(configuration: RunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val path = launchPath(context) ?: return false
        // Only for files that already have a configuration; it is found again by isConfigurationFromContext
        val existing = RunManager.getInstance(context.project).getConfigurationSettingsList(EclipseLaunchConfigurationType.getInstance())
            .firstOrNull { (it.configuration as? EclipseLinkedConfiguration)?.link?.path == path } ?: return false
        val linked = existing.configuration as? EclipseLinkedConfiguration ?: return false
        if (linked.kind != kind) return false
        (configuration as EclipseLinkedConfiguration).link = linked.link
        configuration.name = existing.name
        return true
    }
}

class JUnitLaunchFileRunProducer : LaunchFileRunProducer(LaunchKind.JUNIT)

class ApplicationLaunchFileRunProducer : LaunchFileRunProducer(LaunchKind.APPLICATION)
