package de.inetsoftware.eclipselaunch.sync

import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.testframework.TestSearchScope
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersionUtil
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import de.inetsoftware.eclipselaunch.config.EclipseApplicationConfiguration
import de.inetsoftware.eclipselaunch.config.EclipseJUnitConfiguration
import de.inetsoftware.eclipselaunch.config.EclipseLinkedConfiguration
import de.inetsoftware.eclipselaunch.model.JavaElementMemento
import de.inetsoftware.eclipselaunch.model.Keys
import de.inetsoftware.eclipselaunch.model.LaunchSettings
import de.inetsoftware.eclipselaunch.model.TestTarget
import java.io.File

/** Where a launch file belongs: its Eclipse project and the IDEA module it runs in. */
data class LaunchContext(
    val eclipseProject: String?,
    val projectDir: File?,
    val module: Module?,
    val workspace: EclipseWorkspace,
)

/** The IDEA side values of a configuration, to find out what the user changed. */
data class IdeaSnapshot(
    val name: String,
    val module: String?,
    val vm: String,
    val program: String,
    val workingDirectory: String,
    val envs: Map<String, String>,
    val passParentEnvs: Boolean,
    val jreEnabled: Boolean,
    val jrePath: String?,
    val mainClass: String?,
    val testObject: String?,
    val testClass: String?,
    val testMethod: String?,
    val testPackage: String?,
    val testDirectory: String?,
)

object IdeaMapping {

    fun snapshot(config: EclipseLinkedConfiguration, name: String): IdeaSnapshot {
        val data = (config as? EclipseJUnitConfiguration)?.persistentData
        return IdeaSnapshot(
            name = name,
            module = config.moduleBased.configurationModule.module?.name,
            vm = config.vmParameters.orEmpty(),
            program = config.programParameters.orEmpty(),
            workingDirectory = config.workingDirectory.orEmpty(),
            envs = LinkedHashMap(config.envs),
            passParentEnvs = config.isPassParentEnvs,
            jreEnabled = config.isAlternativeJrePathEnabled,
            jrePath = config.alternativeJrePath?.ifEmpty { null },
            mainClass = (config as? EclipseApplicationConfiguration)?.mainClassName,
            testObject = data?.TEST_OBJECT,
            testClass = data?.MAIN_CLASS_NAME?.ifEmpty { null },
            testMethod = data?.METHOD_NAME?.ifEmpty { null },
            testPackage = data?.PACKAGE_NAME,
            testDirectory = data?.dirName?.ifEmpty { null },
        )
    }

    /** Applies the file's settings to the configuration. */
    fun apply(config: EclipseLinkedConfiguration, s: LaunchSettings, ctx: LaunchContext) {
        config.moduleBased.setModule(ctx.module)
        config.vmParameters = s.vmArguments
        config.programParameters = s.programArguments
        config.workingDirectory = workingDirectory(s, ctx)
        config.envs = s.environment
        config.isPassParentEnvs = s.appendEnvironment
        val sdk = findSdk(s.jreContainer)
        config.isAlternativeJrePathEnabled = sdk != null
        config.alternativeJrePath = sdk?.name
        when (config) {
            is EclipseApplicationConfiguration -> config.mainClassName = s.mainType
            is EclipseJUnitConfiguration -> applyTestTarget(config, s.testTarget, ctx)
        }
    }

    private fun applyTestTarget(config: EclipseJUnitConfiguration, target: TestTarget?, ctx: LaunchContext) {
        val data = config.persistentData
        data.METHOD_NAME = ""
        data.PACKAGE_NAME = ""
        when (target) {
            is TestTarget.Class, null -> {
                data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
                data.MAIN_CLASS_NAME = (target as? TestTarget.Class)?.className.orEmpty()
            }
            is TestTarget.Method -> {
                data.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
                data.MAIN_CLASS_NAME = target.className
                // Eclipse adds the simple parameter types for JUnit 5: "test(String, int)"
                data.METHOD_NAME = target.method.substringBefore('(')
            }
            is TestTarget.Container -> {
                data.MAIN_CLASS_NAME = ""
                val root = target.rootPath
                if (target.packageName == null && root != null) {
                    data.TEST_OBJECT = JUnitConfiguration.TEST_DIRECTORY
                    data.dirName = FileUtil.toSystemIndependentName(File(ctx.projectDir ?: File("."), root).path)
                } else {
                    data.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
                    data.PACKAGE_NAME = target.packageName.orEmpty()
                }
                config.setSearchScope(TestSearchScope.SINGLE_MODULE)
            }
        }
    }

    /**
     * The file's settings after the user changed the configuration from [before] to [after].
     * Only fields that differ are taken from IDEA, so Eclipse variables in untouched fields are kept.
     */
    fun toSettings(
        config: EclipseLinkedConfiguration,
        loaded: LaunchSettings,
        before: IdeaSnapshot?,
        after: IdeaSnapshot,
        ctx: LaunchContext,
    ): LaunchSettings {
        // Without a "before" every field is taken from IDEA (new launch file)
        fun <T> changed(get: (IdeaSnapshot) -> T) = before == null || get(before) != get(after)
        var s = loaded
        // IDEA's editors may join Eclipse's multi-line arguments into one line: same arguments, no change
        fun argsChanged(get: (IdeaSnapshot) -> String) = before == null || ParametersListUtil.parse(get(before)) != ParametersListUtil.parse(get(after))
        if (argsChanged { it.vm }) s = s.copy(vmArguments = after.vm)
        if (argsChanged { it.program }) s = s.copy(programArguments = after.program)
        if (changed { it.workingDirectory }) s = s.copy(workingDirectory = toEclipsePath(after.workingDirectory, ctx))
        if (changed { it.envs }) s = s.copy(environment = after.envs)
        if (changed { it.passParentEnvs }) s = s.copy(appendEnvironment = after.passParentEnvs)
        if (changed { it.jreEnabled } || changed { it.jrePath }) {
            s = s.copy(jreContainer = if (after.jreEnabled && after.jrePath != null) jreContainer(after.jrePath) else null)
        }
        if (config is EclipseApplicationConfiguration && changed { it.mainClass }) s = s.copy(mainType = after.mainClass)
        if (config is EclipseJUnitConfiguration &&
            (changed { it.testObject } || changed { it.testClass } || changed { it.testMethod } || changed { it.testPackage } || changed { it.testDirectory })
        ) {
            testTarget(after, loaded, ctx)?.let { s = s.copy(testTarget = it) }
        }
        return s
    }

    /** `null` when IDEA's test kind (pattern, tags, …) has no Eclipse equivalent. */
    private fun testTarget(snap: IdeaSnapshot, loaded: LaunchSettings, ctx: LaunchContext): TestTarget? {
        val project = ctx.eclipseProject ?: loaded.project ?: return null
        val loadedRoot = (loaded.testTarget as? TestTarget.Container)?.rootMemento
        return when (snap.testObject) {
            JUnitConfiguration.TEST_CLASS -> snap.testClass?.let { TestTarget.Class(it) }
            JUnitConfiguration.TEST_METHOD -> {
                val cls = snap.testClass ?: return null
                val method = snap.testMethod ?: return TestTarget.Class(cls)
                // Keep Eclipse's parameter list if only the name part was unchanged
                val old = loaded.testTarget as? TestTarget.Method
                if (old != null && old.className == cls && old.method.substringBefore('(') == method) old else TestTarget.Method(cls, method)
            }
            JUnitConfiguration.TEST_PACKAGE -> {
                val pkg = snap.testPackage.orEmpty()
                if (pkg.isEmpty()) TestTarget.Container(project, null, null)
                else TestTarget.Container(project, loadedRoot ?: sourceRootOf(pkg, ctx), pkg)
            }
            JUnitConfiguration.TEST_DIRECTORY -> {
                val dir = snap.testDirectory ?: return null
                val rel = ctx.projectDir?.let { FileUtil.getRelativePath(it, File(dir)) }?.let(FileUtil::toSystemIndependentName) ?: return null
                val loadedContainer = loaded.testTarget as? TestTarget.Container
                if (loadedContainer?.rootPath == rel) TestTarget.Container(project, loadedContainer.rootMemento, null)
                else TestTarget.Container(project, JavaElementMemento.rootMemento(rel), null)
            }
            else -> null
        }
    }

    /** Source folder (relative to the Eclipse project) that contains the package, as root memento. */
    private fun sourceRootOf(pkg: String, ctx: LaunchContext): String? {
        val module = ctx.module ?: return null
        val projectDir = ctx.projectDir ?: return null
        val path = pkg.replace('.', '/')
        val root = ModuleRootManager.getInstance(module).sourceRoots.firstOrNull { it.findFileByRelativePath(path) != null } ?: return null
        val rel = FileUtil.getRelativePath(projectDir, File(root.path)) ?: return null
        return JavaElementMemento.rootMemento(FileUtil.toSystemIndependentName(rel))
    }

    private fun workingDirectory(s: LaunchSettings, ctx: LaunchContext): String {
        val raw = s.workingDirectory ?: return ctx.projectDir?.path?.let(FileUtil::toSystemIndependentName).orEmpty()
        val vars = ctx.workspace.variables(s.project, ctx.projectDir)
        val resolved = vars.resolve(raw)
        return if (vars.unresolved.isEmpty()) FileUtil.toSystemIndependentName(resolved) else raw
    }

    /** An absolute path as Eclipse would store it: `${workspace_loc:Project/sub}` inside a known project. */
    fun toEclipsePath(path: String, ctx: LaunchContext): String? {
        // IDEA's default working directories mean the module folder, which is Eclipse's default too
        if (path.isBlank() || path.startsWith("\$MODULE_") || path == "\$PROJECT_DIR\$") return null
        val file = File(path)
        if (!file.isAbsolute) return path
        var dir: File? = file
        while (dir != null) {
            if (ctx.workspace.isProjectDir(dir)) {
                val name = ctx.workspace.projectName(dir) ?: break
                val rel = FileUtil.getRelativePath(dir, file)?.let(FileUtil::toSystemIndependentName)
                return if (rel.isNullOrEmpty() || rel == ".") "\${workspace_loc:$name}" else "\${workspace_loc:$name/$rel}"
            }
            dir = dir.parentFile
        }
        return FileUtil.toSystemIndependentName(path)
    }

    fun findSdk(container: String?): Sdk? {
        val jre = LaunchSettings.parseJreContainer(container) ?: return null
        val table = ProjectJdkTable.getInstance()
        table.findJdk(jre.name)?.let { return it }
        val version = jre.executionEnvironmentVersion ?: Regex("""(\d+)""").findAll(jre.name).map { it.value.toInt() }.firstOrNull { it >= 5 } ?: return null
        return table.getSdksOfType(JavaSdk.getInstance())
            .filter { JavaSdkVersionUtil.getJavaSdkVersion(it)?.maxLanguageLevel?.feature() == version }
            .maxByOrNull { it.versionString.orEmpty() }
    }

    private fun jreContainer(jrePath: String): String {
        val sdk = ProjectJdkTable.getInstance().findJdk(jrePath)
        val version = sdk?.let { JavaSdkVersionUtil.getJavaSdkVersion(it)?.maxLanguageLevel?.feature() }
        return if (version != null) LaunchSettings.jreContainerFor(version)
        else "${Keys.JRE_CONTAINER_PREFIX}/${Keys.STANDARD_VM_TYPE}/${sdk?.name ?: File(jrePath).name}"
    }
}
