package de.inetsoftware.eclipselaunch.ui

import com.intellij.ide.highlighter.XmlLikeFileType
import com.intellij.lang.xml.XMLLanguage
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import javax.swing.Icon

/** Eclipse `.launch` files: XML with their own icon. */
object EclipseLaunchFileType : XmlLikeFileType(XMLLanguage.INSTANCE) {
    override fun getName() = "Eclipse Launch"
    override fun getDescription() = "Eclipse launch configuration"
    override fun getDefaultExtension() = "launch"
    override fun getIcon(): Icon = EclipseLaunchConfigurationType.ICON
}
