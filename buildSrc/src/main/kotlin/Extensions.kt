import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = extra.get("baseVersionCode") as Int
    set(value) = extra.set("baseVersionCode", value)

private var reverseDependencyCache: Map<String, Set<Project>>? = null

fun Project.getDependents(): Set<Project> {
    if (reverseDependencyCache == null) {
        reverseDependencyCache = rootProject.allprojects
            .flatMap { p ->
                try {
                    p.configurations.flatMap { config ->
                        try {
                            // Only check resolvable configurations that can have dependencies
                            if (!config.isCanBeResolved) return@flatMap emptyList()
                            config.dependencies
                                .filterIsInstance<ProjectDependency>()
                                .map { dep -> dep.path to p }
                        } catch (_: Exception) {
                            // Skip configurations that can't be resolved or accessed
                            emptyList()
                        }
                    }
                } catch (_: Exception) {
                    // Skip projects that can't be processed
                    emptyList()
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, projects) -> projects.toSet() }
    }

    return reverseDependencyCache?.get(path).orEmpty()
}

fun Project.printDependentExtensions() =
    printDependentExtensions(mutableSetOf())

private fun Project.printDependentExtensions(visited: MutableSet<String>) {
    if (!visited.add(this.path)) return

    getDependents().forEach { project ->
        when {
            project.path.startsWith(":src:") -> {
                println(project.path)
            }

            project.path.startsWith(":lib-multisrc:") -> {
                project.getDependents().forEach {
                    if (visited.add(it.path)) println(it.path)
                }
            }

            project.path.startsWith(":lib:") -> {
                project.printDependentExtensions(visited)
            }
        }
    }
}
