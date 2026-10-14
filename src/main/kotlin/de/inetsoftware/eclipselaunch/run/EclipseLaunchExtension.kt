package de.inetsoftware.eclipselaunch.run

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.io.FileUtil
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.sync.EclipseWorkspace
import de.inetsoftware.eclipselaunch.sync.LaunchSyncService
import java.io.File

/**
 * Resolves Eclipse variables when a configuration is launched, optionally points Eclipse output folders to the
 * IDEA output, and adds the "Eclipse" section to the run configuration editor.
 */
class EclipseLaunchExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is EclipseLinkedConfiguration

    override fun isEnabledFor(applicableConfiguration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean = true

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
        val config = configuration as? EclipseLinkedConfiguration ?: return
        val link = config.link ?: return
        val service = LaunchSyncService.getInstance(configuration.project)
        val entry = service.entry(link.path)
        val projectDir = entry?.context?.projectDir ?: EclipseWorkspace.findProjectDir(File(link.path).parentFile)
        val vars = service.workspace.variables(entry?.settings?.project ?: entry?.context?.eclipseProject, projectDir)
        val rewriter = if (link.rewriteOutput) OutputRewriter(service.workspace) else null
        val resolve = { s: String -> vars.resolve(s).let { rewriter?.rewrite(it) ?: it } }

        replaceAll(params.vmParametersList, resolve)
        replaceAll(params.programParametersList, resolve)
        params.workingDirectory?.let { params.workingDirectory = FileUtil.toSystemIndependentName(resolve(it)) }
        if (params.env.isNotEmpty()) params.env = params.env.mapValues { resolve(it.value) }
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

/** Replaces `<eclipse project>/bin/<source set>` with the output folder of the matching IDEA module. */
class OutputRewriter(private val workspace: EclipseWorkspace) {
    private val cache = HashMap<String, String?>()

    fun rewrite(text: String): String = PATTERN.replace(text) { m ->
        val dir = m.groupValues[1]
        val set = m.groupValues[2]
        val output = cache.getOrPut("$dir|$set") {
            val file = File(dir)
            if (file.isAbsolute && workspace.isProjectDir(file)) workspace.outputFor(file, set) else null
        }
        output?.let { FileUtil.toSystemIndependentName(it) } ?: m.value
    }

    companion object {
        /** An absolute path followed by `/bin/<name>`, ending at a path separator, quote, whitespace or the end */
        private val PATTERN = Regex("""((?:[A-Za-z]:)?/[^;:"'=\s]*?)/bin/([A-Za-z0-9_-]+)(?=$|[/;:"'\s])""")
    }
}
