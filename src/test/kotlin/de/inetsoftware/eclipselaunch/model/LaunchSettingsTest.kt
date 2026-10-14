package de.inetsoftware.eclipselaunch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaunchSettingsTest {

    private fun read(name: String) = LaunchFile(
        LaunchSettingsTest::class.java.getResourceAsStream("/launch/$name")!!.readBytes().toString(Charsets.UTF_8)
    )

    @Test
    fun `application settings`() {
        val s = LaunchSettings.read(read("app-large-vmargs.launch"))
        assertEquals(LaunchKind.APPLICATION, s.kind)
        assertEquals("InetServerCore", s.project)
        assertEquals("com.inet.Start", s.mainType)
        assertEquals("\${workspace_loc:CC}", s.workingDirectory)
        assertTrue(s.vmArguments.startsWith("-D_test.versionOverride=19.0.83\n-Xmx512m"))
        assertTrue(s.appendEnvironment)
    }

    @Test
    fun `junit package container`() {
        val s = LaunchSettings.read(read("junit-package.launch"))
        val target = s.testTarget as TestTarget.Container
        assertEquals("OdbcBridge", target.project)
        assertEquals("test", target.rootPath)
        assertEquals("com.inet.odbc", target.packageName)
        assertEquals("=OdbcBridge/test=/test=/true=/<com.inet.odbc", target.toMemento())
    }

    @Test
    fun `junit containers without attributes, folders and projects`() {
        assertEquals(
            TestTarget.Container("HelpDeskServerCore", "test", "com.inet.helpdesk.webapi.ticket"),
            JavaElementMemento.parseContainer("=HelpDeskServerCore/test<com.inet.helpdesk.webapi.ticket"),
        )
        val folder = JavaElementMemento.parseContainer("=Datasources/test=/test=/true=/")!!
        assertEquals("test", folder.rootPath)
        assertNull(folder.packageName)
        assertEquals("=Datasources/test=/test=/true=/", folder.toMemento())
        val project = JavaElementMemento.parseContainer("=Authentication-Token")!!
        assertEquals(TestTarget.Container("Authentication-Token", null, null), project)
        assertEquals("=Authentication-Token", project.toMemento())
        assertEquals("src/main/java", JavaElementMemento.parseContainer("=P/src\\/main\\/java<a.b")!!.rootPath)
    }

    @Test
    fun `junit method and class`() {
        val method = LaunchSettings.read(read("junit-method.launch")).testTarget
        assertTrue(method is TestTarget.Method)
        assertEquals("testUserNoAccessToMembersWithAdminContext", (method as TestTarget.Method).method)
        assertTrue(LaunchSettings.read(read("junit5-class.launch")).testTarget is TestTarget.Class)
    }

    @Test
    fun `switching a junit launch from package to method and back`() {
        val file = read("junit-package.launch")
        val text = file.text
        val original = LaunchSettings.read(file)
        val method = original.copy(testTarget = TestTarget.Method("com.inet.odbc.FooTest", "testBar"))
        LaunchSettings.write(file, original, method)
        assertEquals("", file.getString(Keys.JUNIT_CONTAINER))
        assertEquals("com.inet.odbc.FooTest", file.getString(Keys.MAIN_TYPE))
        assertEquals("testBar", file.getString(Keys.JUNIT_TESTNAME))
        assertEquals(method, LaunchSettings.read(file))
        LaunchSettings.write(file, method, original)
        assertEquals(text, file.text)
    }

    @Test
    fun `environment and append flag`() {
        val file = read("junit-method.launch")
        val text = file.text
        val original = LaunchSettings.read(file)
        val changed = original.copy(environment = mapOf("A" to "1", "B" to "\${env_var:HOME}"), appendEnvironment = false)
        LaunchSettings.write(file, original, changed)
        assertEquals(changed, LaunchSettings.read(file))
        assertEquals(false, file.getBoolean(Keys.APPEND_ENVIRONMENT))
        LaunchSettings.write(file, changed, original)
        assertEquals(text, file.text)
    }

    @Test
    fun `jre containers`() {
        assertEquals(LaunchSettings.Jre("JavaSE-21", 21), LaunchSettings.parseJreContainer("org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"))
        assertEquals(LaunchSettings.Jre("JavaSE-1.8", 8), LaunchSettings.parseJreContainer("org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-1.8"))
        assertEquals(LaunchSettings.Jre("AdoptOpenJDK 11", null), LaunchSettings.parseJreContainer("org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.launching.macosx.MacOSXType/AdoptOpenJDK 11/"))
        assertNull(LaunchSettings.parseJreContainer("org.eclipse.jdt.launching.JRE_CONTAINER"))
        assertEquals("org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25", LaunchSettings.jreContainerFor(25))
    }

    @Test
    fun `variables`() {
        val ws = File("/ws")
        val vars = EclipseVariables(
            workspaceRoot = ws,
            currentProject = "CC",
            projectLocator = { name -> if (name == "Nested") File("/ws/Group/Nested") else null },
            env = { if (it == "HOME") "/home/me" else null },
            systemProperty = { if (it == "user.name") "me" else null },
        )
        assertEquals("/ws/CC/bin/main", vars.resolve("\${workspace_loc}/CC/bin/main"))
        assertEquals("/ws/Group/Nested/bin/main", vars.resolve("\${workspace_loc:Nested}/bin/main"))
        assertEquals("/ws/Group/Nested/src/x", vars.resolve("\${workspace_loc:/Nested/src/x}"))
        assertEquals("/ws/CC", vars.resolve("\${project_loc}"))
        assertEquals("/ws/Group/Nested", vars.resolve("\${project_loc:Nested}"))
        assertEquals("/home/me|me", vars.resolve("\${env_var:HOME}|\${system_property:user.name}"))
        assertEquals("/ws/Group/Nested", vars.resolve("\${workspace_loc:\${project_name:Nested}}"))
        assertFalse(vars.unresolved.isNotEmpty())
        assertEquals("\${unknown_var:x} and \$NOT_A_VAR", vars.resolve("\${unknown_var:x} and \$NOT_A_VAR"))
        assertEquals(setOf("unknown_var"), vars.unresolved)
    }
}
