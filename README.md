# Eclipse Launch Configs

IntelliJ IDEA plugin that uses Eclipse launch configurations (`.launch` files) as **live** run configurations,
so a team can keep sharing them between Eclipse and IntelliJ IDEA.

- JUnit (`org.eclipse.jdt.junit.launchconfig`) and Java Application (`org.eclipse.jdt.launching.localJavaApplication`)
  launch files inside the project folders appear as **Eclipse Launch** run configurations, grouped by Eclipse project.
  Run, debug, coverage and the usual IDEA editors work as for normal JUnit/Application configurations.
- Changes made in IDEA are written back to the `.launch` file. Only the changed attribute is replaced; unknown
  attributes, attribute order, indentation, line separators and entity spelling stay byte for byte as Eclipse
  wrote them. New attributes are inserted where Eclipse would put them (sorted by key).
- Changes to the file (VCS update, Eclipse, the editor) are loaded immediately. Renaming or deleting the file
  renames or removes the configuration; renaming the configuration renames the file.
- New configurations of the Eclipse Launch type get a new `.launch` file in the Eclipse project of their module.
- Launch files in the Eclipse workspace metadata (`.metadata/.plugins/org.eclipse.debug.core/.launches`) are
  private to one Eclipse installation and are ignored.

## Mapping

| Eclipse | IntelliJ IDEA |
|---|---|
| Project (`PROJECT_ATTR`) | Module whose content root is the folder of the Eclipse project (`.project`); for Gradle builds the source set module `….test` (JUnit) or `….main` (Application). A module chosen in IDEA stays local and is not written to the file. |
| JUnit class / method | Test kind *Class* / *Method* |
| JUnit container: project, source folder, package | *All in package* (whole module), *Directory*, *Package* (module scope) |
| VM / program arguments, environment (append/replace) | same, Eclipse variables are kept in the text and resolved at launch |
| Working directory | resolved path; written back as `${workspace_loc:Project/…}` |
| JRE (`JRE_CONTAINER`) | Alternative JRE: SDK with the same name, else same Java version |
| `TEST_KIND`, classpath, source lookup, favorites, … | not used by IDEA, kept unchanged in the file |

Variables resolved at launch: `${workspace_loc}`, `${workspace_loc:/Project/path}`, `${project_loc}`,
`${project_name}`, `${resource_loc:…}`, `${env_var:X}`, `${system_property:X}` and the user defined string
substitution variables of the Eclipse workspace (`.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.core.variables.prefs`).
Unknown variables are passed through unchanged.

**Output folders:** Eclipse/Buildship compile into `bin/main`, `bin/test`, … Configurations often pass these
folders in system properties (plugin class paths). With *Use IDEA output folders* (Eclipse section of the editor,
on by default) `<project>/bin/<source set>` is replaced at launch by the output folder of the module
`<module>.<source set>`. The file keeps `bin/main`. IDEA only builds the modules the configuration depends on,
so build the project first when the referenced modules are not dependencies.

## Build

```bash
gradle check buildPlugin
gradle runIdeWithWorkspace   # sandbox IDE with the Multi-Project Workspace plugin
```

`WorkspaceRoundTripTest` checks every `.launch` file of a local Eclipse workspace (`~/Documents/workspace`,
`-Declipse.workspace=…` or `ECLIPSE_WORKSPACE`); it is skipped when the folder does not exist.

## License

Apache License 2.0, © i-net software
