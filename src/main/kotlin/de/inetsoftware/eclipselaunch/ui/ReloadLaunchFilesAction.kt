package de.inetsoftware.eclipselaunch.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import de.inetsoftware.eclipselaunch.sync.LaunchSyncService

/** Reads all `.launch` files of the project again. */
class ReloadLaunchFilesAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LaunchSyncService.getInstance(project).rescan(force = true)
    }
}
