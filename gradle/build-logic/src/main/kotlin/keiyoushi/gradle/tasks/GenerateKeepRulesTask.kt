package keiyoushi.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class GenerateKeepRulesTask : DefaultTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val extClass: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputDir.convention(project.layout.buildDirectory.dir("generated/keepRules/$name"))
    }

    @TaskAction
    fun action() {
        outputDir.get().file("extClass.keep").asFile.apply {
            parentFile.mkdirs()
            writeText("-keep class ${applicationId.get()}${extClass.get()} { <init>(); }\n")
        }
    }
}
