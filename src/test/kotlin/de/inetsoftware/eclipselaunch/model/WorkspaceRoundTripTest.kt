package de.inetsoftware.eclipselaunch.model

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Round trip over every `.launch` file of a real Eclipse workspace. Skipped when the folder does not exist;
 * set `-Declipse.workspace=…` (or the env variable ECLIPSE_WORKSPACE) to point it elsewhere.
 */
class WorkspaceRoundTripTest {

    @Test
    fun `all launch files of the workspace round trip`() {
        val root = File(System.getProperty("eclipse.workspace") ?: System.getenv("ECLIPSE_WORKSPACE") ?: "${System.getProperty("user.home")}/Documents/workspace")
        assumeTrue("No Eclipse workspace at $root", root.isDirectory)
        val files = root.walkTopDown()
            .onEnter { it.name !in setOf("node_modules", ".git", ".svn", ".gradle", "build") }
            .filter { it.isFile && it.name.endsWith(".launch") }
            .toList()
        assumeTrue(files.isNotEmpty())
        val failures = ArrayList<String>()
        var supported = 0
        for (f in files) {
            val text = f.readText()
            try {
                val file = LaunchFile(text)
                if (LaunchKind.of(file.type) != null) {
                    supported++
                    val original = LaunchSettings.read(file)
                    val changed = original.copy(vmArguments = original.vmArguments + " -Dx=1", environment = original.environment + ("ZZ" to "1"))
                    LaunchSettings.write(file, original, changed)
                    if (LaunchSettings.read(file) != changed) failures += "${f.path}: change not read back"
                    LaunchSettings.write(file, changed, original)
                }
                if (file.text != text) failures += "${f.path}: not byte identical"
            } catch (e: Exception) {
                failures += "${f.path}: $e"
            }
        }
        println("Round trip: ${files.size} files, $supported JUnit/Application, ${failures.size} failures")
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }
}
