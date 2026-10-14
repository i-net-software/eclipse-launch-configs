package de.inetsoftware.eclipselaunch.sync

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the live sync of the Eclipse launch files when a project opens. */
class LaunchSyncStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        LaunchSyncService.getInstance(project).start()
    }
}
