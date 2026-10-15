package de.inetsoftware.eclipselaunch.sync

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import de.inetsoftware.eclipselaunch.config.EclipseApplicationConfiguration
import de.inetsoftware.eclipselaunch.config.EclipseJUnitConfiguration
import de.inetsoftware.eclipselaunch.config.EclipseLaunchConfigurationType
import de.inetsoftware.eclipselaunch.model.Keys
import de.inetsoftware.eclipselaunch.model.LaunchFile
import de.inetsoftware.eclipselaunch.run.EclipseLaunchExtension

class LaunchSyncTest : HeavyPlatformTestCase() {

    private lateinit var projectDir: VirtualFile
    private lateinit var launch: VirtualFile
    private lateinit var original: String

    override fun setUp() {
        super.setUp()
        val root = tempDir.createVirtualDir()
        original = LaunchSyncTest::class.java.getResourceAsStream("/launch/junit-package.launch")!!.readBytes().toString(Charsets.UTF_8)
        WriteAction.run<Exception> {
            projectDir = root.createChildDirectory(this, "OdbcBridge")
            projectDir.createChildData(this, ".project").setBinaryContent(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<projectDescription>\n\t<name>OdbcBridge</name>\n</projectDescription>\n".toByteArray()
            )
            launch = projectDir.createChildData(this, "Odbc Test MS SQL.launch")
            launch.setBinaryContent(original.toByteArray())
        }
        PsiTestUtil.addContentRoot(module, projectDir)
        LaunchSyncService.getInstance(project).start(scan = false)
        LaunchSyncService.getInstance(project).syncAll(listOf(launch))
    }

    private fun settings(): RunnerAndConfigurationSettings? =
        RunManager.getInstance(project).getConfigurationSettingsList(EclipseLaunchConfigurationType.getInstance()).singleOrNull()

    private fun config() = settings()!!.configuration as EclipseJUnitConfiguration

    private fun text() = String(launch.contentsToByteArray(), Charsets.UTF_8)

    private fun changed(rs: RunnerAndConfigurationSettings) {
        RunManagerImpl.getInstanceImpl(project).fireRunConfigurationChanged(rs)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    fun testLoadsLaunchFile() {
        val rs = settings()!!
        assertEquals("Odbc Test MS SQL", rs.name)
        assertEquals("OdbcBridge", rs.folderName)
        val config = config()
        assertEquals(module, config.configurationModule.module)
        assertEquals(JUnitConfiguration.TEST_PACKAGE, config.persistentData.TEST_OBJECT)
        assertEquals("com.inet.odbc", config.persistentData.PACKAGE_NAME)
        assertTrue(config.vmParameters.startsWith("\"-Dodbctest.url="))
        assertEquals(projectDir.path, config.workingDirectory)
    }

    fun testWritesOnlyTheChangedValue() {
        val rs = settings()!!
        val oldVm = LaunchFile(original).getString(Keys.VM_ARGUMENTS)!!
        config().vmParameters = "-Dfoo=\"1\""
        changed(rs)
        assertEquals(original.replace(LaunchFile.escape(oldVm), "-Dfoo=&quot;1&quot;"), text())

        // Back to the old value gives the original file
        config().vmParameters = oldVm
        changed(rs)
        assertEquals(original, text())
    }

    fun testUnchangedConfigurationDoesNotTouchTheFile() {
        val stamp = launch.modificationStamp
        changed(settings()!!)
        assertEquals(stamp, launch.modificationStamp)
    }

    fun testTestTargetIsWrittenBack() {
        val rs = settings()!!
        val data = config().persistentData
        data.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
        data.MAIN_CLASS_NAME = "com.inet.odbc.FooTest"
        data.METHOD_NAME = "testBar"
        changed(rs)
        val file = LaunchFile(text())
        assertEquals("", file.getString(Keys.JUNIT_CONTAINER))
        assertEquals("com.inet.odbc.FooTest", file.getString(Keys.MAIN_TYPE))
        assertEquals("testBar", file.getString(Keys.JUNIT_TESTNAME))
    }

    fun testExternalChangesAreLoaded() {
        val changedText = original.replace("value=\"=OdbcBridge/test=/test=/true=/&lt;com.inet.odbc\"", "value=\"\"")
            .replace("<stringAttribute key=\"org.eclipse.jdt.launching.MAIN_TYPE\" value=\"\"/>", "<stringAttribute key=\"org.eclipse.jdt.launching.MAIN_TYPE\" value=\"com.inet.odbc.OtherTest\"/>")
        WriteAction.run<Exception> { launch.setBinaryContent(changedText.toByteArray()) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(JUnitConfiguration.TEST_CLASS, config().persistentData.TEST_OBJECT)
        assertEquals("com.inet.odbc.OtherTest", config().persistentData.MAIN_CLASS_NAME)
    }

    fun testDeletedFileRemovesConfiguration() {
        WriteAction.run<Exception> { launch.delete(this) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertNull(settings())
    }

    fun testRenameInIdeaRenamesFile() {
        val rs = settings()!!
        rs.name = "Renamed"
        changed(rs)
        assertEquals("Renamed.launch", launch.name)
        assertEquals(original, text())
        assertEquals(launch.path, config().link?.path)
    }

    fun testRenamedFileKeepsConfiguration() {
        val before = config()
        WriteAction.run<Exception> { launch.rename(this, "Other.launch") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertSame(before, config())
        assertEquals("Other", settings()!!.name)
    }

    fun testVariablesAreResolvedAtLaunch() {
        val params = JavaParameters()
        params.vmParametersList.addParametersString("-Dws=\${workspace_loc:OdbcBridge}/bin/main -Dp=\${project_loc} -Dkeep=\${unknown_var}")
        assertEquals("-Dws=\${workspace_loc:OdbcBridge}/bin/main", params.vmParametersList.list[0])
        params.workingDirectory = "\${workspace_loc:OdbcBridge}"
        EclipseLaunchExtension().updateJavaParameters(config(), params, null)
        assertEquals(listOf("-Dws=${projectDir.path}/bin/main", "-Dp=${projectDir.path}", "-Dkeep=\${unknown_var}"), params.vmParametersList.list)
        assertEquals(projectDir.path, params.workingDirectory)
    }

    fun testParametersListKeepsEclipseVariables() {
        val list = ParametersList()
        list.addParametersString("\"-Dx=\${workspace_loc}/a b\" -Dy=\${env_var:HOME}")
        assertEquals(listOf("-Dx=\${workspace_loc}/a b", "-Dy=\${env_var:HOME}"), list.list)
    }

    fun testJoinedMultiLineArgumentsAreNoChange() {
        val file = LaunchFile(original)
        file.setString(Keys.VM_ARGUMENTS, "-Da=1\n-Db=2")
        WriteAction.run<Exception> { launch.setBinaryContent(file.text.toByteArray()) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val stamp = launch.modificationStamp
        config().vmParameters = "-Da=1 -Db=2"
        changed(settings()!!)
        assertEquals(stamp, launch.modificationStamp)
    }

    fun testNewConfigurationDoesNotWriteAFileByItself() {
        val rm = RunManager.getInstance(project)
        val rs = rm.createConfiguration("New One", EclipseLaunchConfigurationType.getInstance().applicationFactory)
        (rs.configuration as EclipseApplicationConfiguration).setModule(module)
        rm.addConfiguration(rs)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(".project", "Odbc Test MS SQL.launch"), projectDir.children.map { it.name }.sorted())
    }

    fun testReplacedByEditedCopyKeepsTheLink() {
        // What the run configuration dialog may do: add an edited copy, then remove the original
        val rm = RunManagerImpl.getInstanceImpl(project)
        val original = settings()!!
        val copy = RunManagerImpl.getInstanceImpl(project).createConfiguration(original.configuration.clone(), original.factory)
        copy.name = original.name
        rm.removeConfiguration(original)
        rm.addConfiguration(copy)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(listOf(".project", "Odbc Test MS SQL.launch"), projectDir.children.map { it.name }.sorted())
        (copy.configuration as EclipseJUnitConfiguration).vmParameters = "-Dcopy=1"
        changed(copy)
        assertEquals("-Dcopy=1", LaunchFile(text()).getString(Keys.VM_ARGUMENTS))
    }

    fun testCopyDoesNotChangeTheOriginalFile() {
        val rm = RunManagerImpl.getInstanceImpl(project)
        val original = settings()!!
        val copy = rm.createConfiguration(original.configuration.clone(), original.factory)
        copy.name = "Copy of it"
        rm.addConfiguration(copy)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        (copy.configuration as EclipseJUnitConfiguration).vmParameters = "-Dcopy=1"
        changed(copy)
        assertEquals(this.original, text())
        assertNull((copy.configuration as EclipseJUnitConfiguration).link)
        assertEquals("Odbc Test MS SQL.launch", launch.name)
    }

    fun testOutputFoldersMapToTheModuleCompilingTheSources() {
        val src = WriteAction.compute<VirtualFile, Exception> { projectDir.createChildDirectory(this, "src") }
        PsiTestUtil.addSourceRoot(module, src)
        val out = projectDir.path + "/build/classes/java/main"
        ModuleRootModificationUtil.updateModel(module) { model ->
            val ext = model.getModuleExtension(CompilerModuleExtension::class.java)
            ext.inheritCompilerOutputPath(false)
            ext.setCompilerOutputPath(VfsUtilCore.pathToUrl(out))
        }
        val params = JavaParameters()
        params.vmParametersList.add("-Dcp=\${workspace_loc}/OdbcBridge/bin/main;\${workspace_loc}/Unknown/bin/main")
        EclipseLaunchExtension().updateJavaParameters(config(), params, null)
        assertEquals("-Dcp=$out;${projectDir.parent.path}/Unknown/bin/main", params.vmParametersList.list.single())
    }

    fun testLaunchRunsInItsProjectNotInTheFolderOfTheFile() {
        // CC/Start Reporting Server.launch has PROJECT_ATTR InetServerCore
        lateinit var core: VirtualFile
        lateinit var other: VirtualFile
        WriteAction.run<Exception> {
            core = projectDir.parent.createChildDirectory(this, "InetServerCore")
            core.createChildData(this, ".project").setBinaryContent("<projectDescription><name>InetServerCore</name></projectDescription>".toByteArray())
        }
        // The module first: a launch file loaded before its module exists is only remapped later (modulesAdded)
        val coreModule = createModule("InetServerCore")
        PsiTestUtil.addContentRoot(coreModule, core)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        WriteAction.run<Exception> {
            other = projectDir.createChildData(this, "Runs Elsewhere.launch")
            other.setBinaryContent(original.replace("value=\"OdbcBridge\"/>", "value=\"InetServerCore\"/>").toByteArray())
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        LaunchSyncService.getInstance(project).syncAll(listOf(launch, other))
        val rs = RunManager.getInstance(project).getConfigurationSettingsList(EclipseLaunchConfigurationType.getInstance()).single { it.name == "Runs Elsewhere" }
        val config = rs.configuration as EclipseJUnitConfiguration
        val info = "core=${core.path} roots=" + com.intellij.openapi.module.ModuleManager.getInstance(project).modules.joinToString {
            it.name + ":" + com.intellij.openapi.roots.ModuleRootManager.getInstance(it).contentRoots.joinToString { r -> r.path }
        } + " projectDir(InetServerCore)=" + LaunchSyncService.getInstance(project).workspace.projectDir("InetServerCore")
        assertEquals(info, coreModule, config.configurationModule.module)
        assertEquals(core.path, config.workingDirectory)
        assertEquals("OdbcBridge", rs.folderName)
    }

    fun testApplicationRunsWithTestCodeLikeEclipse() {
        // Eclipse puts test code and test dependencies on an application's class path unless ATTR_EXCLUDE_TEST_CODE is set
        val mainModule = createModule(module.name + ".main")
        val testModule = createModule(module.name + ".test")
        fun app(name: String, exclude: Boolean?): VirtualFile = WriteAction.compute<VirtualFile, Exception> {
            projectDir.createChildData(this, "$name.launch").also {
                it.setBinaryContent(
                    ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<launchConfiguration type=\"org.eclipse.jdt.launching.localJavaApplication\">\n" +
                        (if (exclude != null) "    <booleanAttribute key=\"org.eclipse.jdt.launching.ATTR_EXCLUDE_TEST_CODE\" value=\"$exclude\"/>\n" else "") +
                        "    <stringAttribute key=\"org.eclipse.jdt.launching.MAIN_TYPE\" value=\"a.Main\"/>\n" +
                        "    <stringAttribute key=\"org.eclipse.jdt.launching.PROJECT_ATTR\" value=\"OdbcBridge\"/>\n</launchConfiguration>\n").toByteArray()
                )
            }
        }
        val withTests = app("With Tests", null)
        val without = app("Without Tests", true)
        LaunchSyncService.getInstance(project).syncAll(listOf(launch, withTests, without))
        val all = RunManager.getInstance(project).getConfigurationSettingsList(EclipseLaunchConfigurationType.getInstance())
        fun moduleOf(name: String) = (all.single { it.name == name }.configuration as com.intellij.execution.configurations.ModuleBasedConfiguration<*, *>).configurationModule.module
        assertEquals(testModule, moduleOf("With Tests"))
        assertEquals(mainModule, moduleOf("Without Tests"))
    }
}
