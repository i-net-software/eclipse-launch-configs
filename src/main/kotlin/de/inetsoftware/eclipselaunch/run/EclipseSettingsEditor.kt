package de.inetsoftware.eclipselaunch.run

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.model.Keys
import de.inetsoftware.eclipselaunch.sync.LaunchSyncService
import javax.swing.JComponent

/** The "Eclipse" section of the run configuration editor. */
class EclipseSettingsEditor<T>(private val project: Project) : SettingsEditor<T>() {

    private val fileLabel = JBLabel()
    private val projectLabel = JBLabel()
    private val keptLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val rewriteOutput = JBCheckBox("Use IDEA output folders for Eclipse output folders (bin/main, bin/test) at launch")
    private var path: String? = null

    override fun resetEditorFrom(settings: T & Any) {
        val config = settings as? EclipseLinkedConfiguration ?: return
        val link = config.link
        path = link?.path
        fileLabel.text = link?.path ?: "Not saved yet: a launch file is created in the Eclipse project of the module"
        val entry = link?.path?.let { LaunchSyncService.getInstance(project).entry(it) }
        projectLabel.text = entry?.let { e ->
            val module = config.moduleBased.configurationModule.module?.name
            "${e.context.eclipseProject ?: "?"}  →  module ${module ?: "(none found)"}" + if (link.moduleOverride != null) " (chosen in IDEA, not saved to the file)" else ""
        } ?: ""
        val kept = entry?.keys?.filterNot { it in MAPPED }.orEmpty()
        keptLabel.text = if (kept.isEmpty()) "" else
            "<html>Kept unchanged in the file: ${kept.joinToString(", ") { it.substringAfterLast('.') }}</html>"
        rewriteOutput.isSelected = link?.rewriteOutput ?: true
    }

    override fun applyEditorTo(settings: T & Any) {
        val config = settings as? EclipseLinkedConfiguration ?: return
        config.link = config.link?.copy(rewriteOutput = rewriteOutput.isSelected)
    }

    override fun createEditor(): JComponent = panel {
        row("Launch file:") {
            cell(fileLabel).align(AlignX.FILL)
            link("Open") {
                val file = path?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: return@link
                OpenFileDescriptor(project, file).navigate(true)
            }
        }
        row("Eclipse project:") { cell(projectLabel) }
        row { cell(rewriteOutput).onChanged { fireEditorStateChanged() } }
        row { cell(keptLabel).align(AlignX.FILL) }
        row {
            comment("Values are written back to the .launch file. Eclipse variables such as \${workspace_loc} are resolved at launch.")
        }
    }

    companion object {
        private val MAPPED = setOf(
            Keys.PROJECT, Keys.MAIN_TYPE, Keys.VM_ARGUMENTS, Keys.PROGRAM_ARGUMENTS, Keys.WORKING_DIRECTORY,
            Keys.JRE_CONTAINER, Keys.ENVIRONMENT, Keys.APPEND_ENVIRONMENT, Keys.JUNIT_CONTAINER, Keys.JUNIT_TESTNAME,
        )
    }
}
