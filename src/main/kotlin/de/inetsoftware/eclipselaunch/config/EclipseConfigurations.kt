package de.inetsoftware.eclipselaunch.config

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.openapi.project.Project
import de.inetsoftware.eclipselaunch.model.LaunchKind
import org.jdom.Element

/**
 * Link of a run configuration to its `.launch` file plus the settings that stay local to this IDE
 * and are never written to the file. Immutable, so cloned configurations can share it.
 */
data class LaunchLink(
    /** Absolute, system independent path of the `.launch` file */
    val path: String,
    /** Module chosen by the user instead of the automatic mapping */
    val moduleOverride: String? = null,
    /** Replace Eclipse output folders (`bin/main`) with the IDEA module output at launch */
    val rewriteOutput: Boolean = true,
) {
    fun write(element: Element) {
        val e = Element(TAG).setAttribute("path", path).setAttribute("rewrite-output", rewriteOutput.toString())
        moduleOverride?.let { e.setAttribute("module-override", it) }
        element.addContent(e)
    }

    companion object {
        private const val TAG = "eclipse-launch"

        fun read(element: Element): LaunchLink? {
            val e = element.getChild(TAG) ?: return null
            return LaunchLink(
                path = e.getAttributeValue("path") ?: return null,
                moduleOverride = e.getAttributeValue("module-override"),
                rewriteOutput = e.getAttributeValue("rewrite-output")?.toBoolean() ?: true,
            )
        }
    }
}

/** A run configuration of the [EclipseLaunchConfigurationType]. */
interface EclipseLinkedConfiguration : CommonJavaRunConfigurationParameters {
    var link: LaunchLink?
    val kind: LaunchKind

    /** The configuration itself, which is always a module based Java configuration. */
    val moduleBased: ModuleBasedConfiguration<*, *> get() = this as ModuleBasedConfiguration<*, *>
}

class EclipseJUnitConfiguration(name: String, project: Project, factory: ConfigurationFactory) :
    JUnitConfiguration(name, project, factory), EclipseLinkedConfiguration {

    override var link: LaunchLink? = null
    override val kind get() = LaunchKind.JUNIT

    override fun readExternal(element: Element) {
        super.readExternal(element)
        link = LaunchLink.read(element)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        link?.write(element)
    }
}

class EclipseApplicationConfiguration(name: String, project: Project, factory: ConfigurationFactory) :
    ApplicationConfiguration(name, project, factory), EclipseLinkedConfiguration {

    override var link: LaunchLink? = null
    override val kind get() = LaunchKind.APPLICATION

    override fun readExternal(element: Element) {
        super.readExternal(element)
        link = LaunchLink.read(element)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        link?.write(element)
    }
}
