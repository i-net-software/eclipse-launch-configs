package de.inetsoftware.eclipselaunch.config

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import de.inetsoftware.eclipselaunch.model.LaunchKind
import javax.swing.Icon

/** Run configurations backed by an Eclipse `.launch` file. */
class EclipseLaunchConfigurationType : ConfigurationTypeBase(
    ID,
    "Eclipse Launch",
    "Eclipse launch configurations (.launch files), kept in sync with the file",
    ICON,
) {
    val junitFactory = Factory(this, LaunchKind.JUNIT, "Eclipse JUnit", "JUnit", AllIcons.RunConfigurations.Junit)
    val applicationFactory = Factory(this, LaunchKind.APPLICATION, "Eclipse Java Application", "Java Application", AllIcons.RunConfigurations.Application)

    init {
        addFactory(junitFactory)
        addFactory(applicationFactory)
    }

    fun factory(kind: LaunchKind): Factory = when (kind) {
        LaunchKind.JUNIT -> junitFactory
        LaunchKind.APPLICATION -> applicationFactory
    }

    class Factory(
        type: EclipseLaunchConfigurationType,
        val kind: LaunchKind,
        private val id: String,
        private val displayName: String,
        private val icon: Icon,
    ) : ConfigurationFactory(type) {
        override fun getId(): String = id
        override fun getName(): String = displayName
        override fun getIcon(): Icon = icon

        override fun createTemplateConfiguration(project: Project): RunConfiguration = when (kind) {
            LaunchKind.JUNIT -> EclipseJUnitConfiguration("", project, this)
            LaunchKind.APPLICATION -> EclipseApplicationConfiguration("", project, this)
        }
    }

    companion object {
        const val ID = "EclipseLaunchConfiguration"

        @JvmField
        val ICON: Icon = IconLoader.getIcon("/icons/eclipseLaunch.svg", EclipseLaunchConfigurationType::class.java)

        fun getInstance(): EclipseLaunchConfigurationType =
            ConfigurationTypeUtil.findConfigurationType(EclipseLaunchConfigurationType::class.java)
    }
}
