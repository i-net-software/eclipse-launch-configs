package de.inetsoftware.eclipselaunch.model

import java.io.File

/**
 * Resolves Eclipse string substitution variables such as `${workspace_loc:/Project/folder}`.
 *
 * Unknown variables stay unchanged and are reported in [unresolved].
 */
class EclipseVariables(
    /** The Eclipse workspace folder (the one containing `.metadata`). */
    private val workspaceRoot: File?,
    /** The project of the launch configuration, used by `${project_loc}` and `${project_name}` without argument. */
    private val currentProject: String?,
    /** Finds the folder of an Eclipse project by its name. */
    private val projectLocator: (String) -> File?,
    private val env: (String) -> String? = System::getenv,
    private val systemProperty: (String) -> String? = System::getProperty,
    /** User defined string substitution variables of the Eclipse workspace. */
    private val userVariables: Map<String, String> = emptyMap(),
    /** Asks the user for `${string_prompt}` and friends; returns `null` when cancelled. */
    private val prompt: (variable: String, description: String?) -> String? = { _, d -> d ?: "" },
) {
    val unresolved: MutableSet<String> = linkedSetOf()

    fun resolve(text: String): String {
        if (!text.contains("\${")) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("\${", i)
            if (start < 0) break
            val end = findEnd(text, start)
            if (end < 0) break
            sb.append(text, i, start)
            // Nested variables in the argument are resolved first, like Eclipse does
            val inner = resolve(text.substring(start + 2, end))
            val colon = inner.indexOf(':')
            val name = if (colon < 0) inner else inner.substring(0, colon)
            val arg = if (colon < 0) null else inner.substring(colon + 1)
            val value = evaluate(name, arg)
            if (value == null) {
                unresolved.add(name)
                sb.append("\${").append(inner).append('}')
            } else {
                sb.append(value)
            }
            i = end + 1
        }
        sb.append(text, i, text.length)
        return sb.toString()
    }

    private fun findEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            if (text.startsWith("\${", i)) {
                depth++; i += 2; continue
            }
            if (text[i] == '}') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun evaluate(name: String, arg: String?): String? = when (name) {
        "workspace_loc" -> if (arg.isNullOrEmpty()) workspaceRoot?.path else resourceLocation(arg)
        "project_loc" -> projectOf(arg)?.let { resourceLocation(it) }
        "project_name" -> projectOf(arg)
        "project_path" -> projectOf(arg)?.let { "/$it" }
        "resource_loc", "container_loc" -> arg?.let { resourceLocation(it) }
        "env_var" -> arg?.let { env(it) } ?: if (arg != null) "" else null
        "system_property" -> arg?.let { systemProperty(it) } ?: if (arg != null) "" else null
        "string_prompt", "password_prompt", "file_prompt", "folder_prompt" -> prompt(name, arg?.substringAfter(':', arg))
        else -> userVariables[name]
    }

    private fun projectOf(arg: String?): String? =
        if (arg.isNullOrEmpty()) currentProject else arg.trimStart('/').substringBefore('/')

    /** `/Project/folder/file` or `Project/folder` → absolute path */
    private fun resourceLocation(path: String): String? {
        val trimmed = path.trimStart('/')
        val project = trimmed.substringBefore('/')
        val rest = trimmed.substringAfter('/', "")
        val dir = projectLocator(project) ?: workspaceRoot?.resolve(project) ?: return null
        return if (rest.isEmpty()) dir.path else File(dir, rest).path
    }
}
