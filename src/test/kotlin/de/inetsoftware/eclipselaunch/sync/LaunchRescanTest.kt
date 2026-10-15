package de.inetsoftware.eclipselaunch.sync

import com.intellij.execution.RunManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import de.inetsoftware.eclipselaunch.ui.LaunchFileIconProvider

/** A new project gets its content roots later (Gradle sync): the launch files must show up then. */
class LaunchRescanTest : HeavyPlatformTestCase() {

    fun testLaunchFilesAppearWhenContentRootIsAdded() {
        val text = LaunchRescanTest::class.java.getResourceAsStream("/launch/junit-package.launch")!!.readBytes()
        lateinit var dir: VirtualFile
        lateinit var launch: VirtualFile
        WriteAction.run<Exception> {
            dir = tempDir.createVirtualDir().createChildDirectory(this, "OdbcBridge")
            dir.createChildData(this, ".project").setBinaryContent("<projectDescription><name>OdbcBridge</name></projectDescription>".toByteArray())
            launch = dir.createChildData(this, "Odbc Test MS SQL.launch")
            launch.setBinaryContent(text)
        }
        LaunchSyncService.getInstance(project).start()
        assertEmpty(settings())

        PsiTestUtil.addContentRoot(module, dir)
        val deadline = System.currentTimeMillis() + 20_000
        while (settings().isEmpty() && System.currentTimeMillis() < deadline) {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
        }
        assertEquals(listOf("Odbc Test MS SQL"), settings().map { it.name })
        assertEquals("XML", launch.fileType.name)
        assertSame(EclipseLaunchConfigurationType.ICON, LaunchFileIconProvider().getIcon(launch, 0, project))
    }

    private fun settings() = RunManager.getInstance(project).getConfigurationSettingsList(EclipseLaunchConfigurationType.getInstance())
}
