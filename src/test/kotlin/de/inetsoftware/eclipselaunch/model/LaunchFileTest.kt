package de.inetsoftware.eclipselaunch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaunchFileTest {

    private val NEW_KEY = "org.eclipse.jdt.launching.NEW_ATTRIBUTE"

    private fun resource(name: String): String =
        LaunchFileTest::class.java.getResourceAsStream("/launch/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val allResources: List<String> =
        File(LaunchFileTest::class.java.getResource("/launch")!!.toURI()).list()!!.filter { it.endsWith(".launch") }.sorted()

    @Test
    fun `every sample survives an unchanged round trip byte for byte`() {
        for (name in allResources) {
            val text = resource(name)
            val file = LaunchFile(text)
            LaunchKind.of(file.type)?.let { LaunchSettings.write(file, LaunchSettings.read(file), LaunchSettings.read(file)) }
            assertEquals(name, text, file.text)
        }
    }

    @Test
    fun `changing and restoring a value gives the original bytes`() {
        for (name in allResources) {
            val text = resource(name)
            val file = LaunchFile(text)
            val kind = LaunchKind.of(file.type) ?: continue
            val original = LaunchSettings.read(file)
            val changed = original.copy(
                vmArguments = original.vmArguments + " -Dchanged=1",
                programArguments = original.programArguments + " x",
            )
            LaunchSettings.write(file, original, changed)
            assertEquals(name, changed, LaunchSettings.read(file))
            LaunchSettings.write(file, changed, original)
            assertEquals(name, text, file.text)
            assertEquals(name, original, LaunchSettings.read(file))
            assertEquals(kind, LaunchSettings.read(file).kind)
        }
    }

    @Test
    fun `only the changed value is touched`() {
        val text = resource("app-large-vmargs.launch")
        val file = LaunchFile(text)
        file.setString(Keys.MAIN_TYPE, "com.inet.Other")
        assertEquals(text.replace("value=\"com.inet.Start\"", "value=\"com.inet.Other\""), file.text)
    }

    @Test
    fun `values are escaped like Eclipse does`() {
        val file = LaunchFile(resource("junit-package.launch"))
        file.setString(Keys.VM_ARGUMENTS, "-Da=\"x & y\"\n-Db=<z>")
        assertTrue(file.text.contains("value=\"-Da=&quot;x &amp; y&quot;&#10;-Db=&lt;z&gt;\""))
        assertEquals("-Da=\"x & y\"\n-Db=<z>", file.getString(Keys.VM_ARGUMENTS))
    }

    @Test
    fun `new attributes are inserted sorted by key with the file's indentation and line separator`() {
        val text = resource("app-crlf-progargs.launch")
        val file = LaunchFile(text)
        file.setString(NEW_KEY, "\${workspace_loc:Foo}")
        val lines = file.text.split("\r\n")
        val index = lines.indexOfFirst { it.contains(NEW_KEY) }
        assertTrue(lines[index].startsWith("    <stringAttribute"))
        val keys = Regex("""^\s*<\w+ key="([^"]+)"""").let { r -> lines.mapNotNull { r.find(it)?.groupValues?.get(1) } }
        assertEquals(keys.sorted(), keys)
        assertFalse(file.text.replace("\r\n", "").contains('\n'))
        file.setString(NEW_KEY, null)
        assertEquals(text, file.text)
    }

    @Test
    fun `last attribute can be appended and removed again`() {
        val text = resource("junit-method.launch")
        val file = LaunchFile(text)
        file.setString("zzz.last", "1")
        assertTrue(file.text.contains("<stringAttribute key=\"zzz.last\" value=\"1\"/>\n</launchConfiguration>"))
        file.setString("zzz.last", null)
        assertEquals(text, file.text)
    }

    @Test
    fun `maps keep their order and can be removed`() {
        val text = resource("app-env.launch")
        val file = LaunchFile(text)
        val env = file.getMap(Keys.ENVIRONMENT)!!
        file.setMap(Keys.ENVIRONMENT, env + ("ZZ_NEW" to "v"))
        assertEquals(env.keys.toList() + "ZZ_NEW", file.getMap(Keys.ENVIRONMENT)!!.keys.toList())
        file.setMap(Keys.ENVIRONMENT, env)
        assertEquals(text, file.text)
        file.setMap(Keys.ENVIRONMENT, null)
        assertNull(file.getMap(Keys.ENVIRONMENT))
        assertTrue(file.keys.isNotEmpty())
    }

    @Test
    fun `lists are written in Eclipse layout`() {
        val file = LaunchFile(resource("junit-method.launch"))
        file.setList(Keys.MAPPED_RESOURCE_PATHS, listOf("/A/b.java", "/C"))
        assertTrue(
            file.text.contains(
                "    <listAttribute key=\"${Keys.MAPPED_RESOURCE_PATHS}\">\n" +
                    "        <listEntry value=\"/A/b.java\"/>\n" +
                    "        <listEntry value=\"/C\"/>\n" +
                    "    </listAttribute>"
            )
        )
        assertEquals(listOf("/A/b.java", "/C"), file.getList(Keys.MAPPED_RESOURCE_PATHS))
    }

    @Test
    fun `unknown attributes of other launch types are readable`() {
        val file = LaunchFile(resource("gradle-test.launch"))
        assertEquals("org.eclipse.buildship.core.launch.test.runconfiguration", file.type)
        assertTrue(file.keys.contains("tests"))
        assertNull(LaunchKind.of(file.type))
    }
}
