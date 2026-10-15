package de.inetsoftware.eclipselaunch.ui

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import javax.swing.Icon

/** Eclipse `.launch` files (registered as XML in plugin.xml) get the Eclipse Launch icon. */
class LaunchFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (!file.isDirectory && file.extension == "launch") EclipseLaunchConfigurationType.ICON else null
}
