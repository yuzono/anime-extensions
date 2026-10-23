import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.tasks.PackageAndroidArtifact
import keiyoushi.gradle.extensions.DeeplinkFilter
import keiyoushi.gradle.extensions.DeeplinkSpec
import keiyoushi.gradle.extensions.KeiyoushiExtension
import keiyoushi.gradle.extensions.KeiyoushiMultisrcExtension
import keiyoushi.gradle.extensions.VALID_LIB_VERSIONS
import keiyoushi.gradle.extensions.alias
import keiyoushi.gradle.extensions.compileOnly
import keiyoushi.gradle.extensions.implementation
import keiyoushi.gradle.extensions.kei
import keiyoushi.gradle.extensions.libs
import keiyoushi.gradle.extensions.plugins
import keiyoushi.gradle.tasks.GenerateExtensionManifestTask
import keiyoushi.gradle.tasks.GenerateKeepRulesTask
import keiyoushi.gradle.utils.assertWithoutFlag
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class PluginExtension : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)

            alias(kei.plugins.android.base)
            alias(kei.plugins.spotless)
        }

        val keiyoushi = extensions.create("yuzono", KeiyoushiExtension::class.java)
        val applicationIdSuffix = "${project.parent?.name}.${project.name}"

        android {
            namespace = "eu.kanade.tachiyomi.animeextension"

            defaultConfig {
                this.applicationIdSuffix = applicationIdSuffix
            }

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = true
                    proguardFiles(rootProject.file("common/proguard-rules.pro"))
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            buildTypes {
                configureEach {
                    buildConfigField("String", "MEGACLOUD_API", "\"https://script.google.com/macros/s/AKfycbxHbYHbrGMXYD2-bC-C43D3njIbU-wGiYQuJL61H4vyy6YVXkybMNNEPJNPPuZrD1gRVA/exec\"")
                    buildConfigField("String", "KISSKH_API", "\"https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id=\"")
                    buildConfigField("String", "KISSKH_SUB_API", "\"https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id=\"")
                    buildConfigField("String", "KAISVA", "\"https://c-kai-8090.amarullz.com\"")
                    buildConfigField("String", "TMDB_API", "\"${System.getenv("TMDB_API")}\"")
                    buildConfigField("String", "WYZIE_API", "\"${System.getenv("WYZIE_API")}\"")
                }
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        val extClassProvider = keiyoushi.className.map { if (it.startsWith(".")) it else ".$it" }

        val versionCodeProvider = keiyoushi.theme.map { themeName ->
            val themeProject = project(":lib-multisrc:$themeName")
            val themeKeiyoushi = themeProject.extensions.findByType(KeiyoushiMultisrcExtension::class.java)
                ?: throw AssertionError("Theme project ${themeProject.path} must apply kei.plugins.multisrc")
            val themeLibVersion = themeKeiyoushi.libVersion.get()
            val extLibVersion = keiyoushi.libVersion.get()
            // A lib 16 extension may implement the hoster flow on top of a theme that still uses the legacy shims
            assertWithoutFlag(extLibVersion.toDouble() >= themeLibVersion.toDouble()) {
                "Extension libVersion ($extLibVersion) must not be lower than multisrc ($themeName) libVersion ($themeLibVersion)."
            }
            themeKeiyoushi.baseVersionCode.get() + keiyoushi.versionCode.get()
        }.orElse(keiyoushi.versionCode)

        val versionNameProvider = keiyoushi.libVersion.flatMap { libVersion ->
            assertWithoutFlag(libVersion in VALID_LIB_VERSIONS) {
                "libVersion $libVersion is not supported. Supported versions: $VALID_LIB_VERSIONS"
            }
            versionCodeProvider.map { "$libVersion.$it" }
        }

        val appNameProvider = keiyoushi.name.map { name ->
            assertWithoutFlag(name.all { it.code < 0x180 }) { "Extension name should be romanized" }
            "Aniyomi: $name"
        }

        val nsfwProvider = keiyoushi.contentWarning.map { if (it == ContentWarning.SAFE) "0" else "1" }

        val torrentProvider = keiyoushi.torrent.map { if (it) "1" else "0" }.orElse("0")

        val contentWarningProvider = keiyoushi.contentWarning.map {
            when (it) {
                ContentWarning.SAFE -> "0"
                ContentWarning.MIXED -> "1"
                ContentWarning.NSFW -> "2"
            }
        }

        val deeplinksProvider = provider {
            val defaultHost = keiyoushi.baseUrl.orNull
                ?.split("://")?.getOrNull(1)
                ?.split("/")?.first()
                ?.takeIf { it.isNotEmpty() }
            val localSpecs = keiyoushi.deeplinks.getOrElse(emptyList())
            val themeSpecs = keiyoushi.theme.orNull?.let { themeName ->
                project(":lib-multisrc:$themeName")
                    .extensions.findByType(KeiyoushiMultisrcExtension::class.java)
                    ?.deeplinks?.getOrElse(emptyList())
            }.orEmpty()
            specsToFilters(localSpecs + themeSpecs, defaultHost)
        }

        val manifestTask = tasks.register<GenerateExtensionManifestTask>("generateExtensionManifest") {
            this.filters.set(deeplinksProvider)
        }

        androidComponents {
            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }

                @Suppress("UnstableApiUsage")
                val keepRules = variant.sources.keepRules
                if (keepRules != null) {
                    val task = tasks.register<GenerateKeepRulesTask>("generate${variantName}KeepRules") {
                        this.applicationId.set(variant.applicationId)
                        this.extClass.set(extClassProvider)
                    }
                    keepRules.addGeneratedSourceDirectory(task) { it.outputDir }
                }

                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")
                variant.sources.manifests.addGeneratedManifestFile(manifestTask) { it.outputFile }

                variant.outputs.forEach { output ->
                    output.versionCode.set(versionCodeProvider)
                    output.versionName.set(versionNameProvider)
                }

                variant.manifestPlaceholders.put("appName", appNameProvider)
                variant.manifestPlaceholders.put("tachiyomix.name", keiyoushi.name)
                variant.manifestPlaceholders.put("extClass", extClassProvider)
                variant.manifestPlaceholders.put("nsfw", nsfwProvider)
                variant.manifestPlaceholders.put("torrent", torrentProvider)
                variant.manifestPlaceholders.put("tachiyomix.contentWarning", contentWarningProvider)
                variant.manifestPlaceholders.put("tachiyomix.extensionLib", keiyoushi.libVersion)
            }
        }

        base {
            archivesName.set(versionNameProvider.map { "aniyomi-$applicationIdSuffix-v$it" })
        }

        afterEvaluate {
            val themeName = keiyoushi.theme.orNull
            if (themeName != null) {
                evaluationDependsOn(":lib-multisrc:$themeName")
            }

            dependencies {
                if (themeName != null) {
                    implementation(project(":lib-multisrc:$themeName"))
                }
                implementation(project(":core"))
                compileOnly(libs.bundles.common)
            }

            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private fun specsToFilters(specs: List<DeeplinkSpec>, defaultHost: String?): List<DeeplinkFilter> = specs.mapNotNull { spec ->
    val hosts = spec.hosts.getOrElse(emptyList()).ifEmpty { listOfNotNull(defaultHost) }
    val paths = spec.pathPatterns.getOrElse(emptyList())
    if (paths.isNotEmpty()) {
        check(hosts.isNotEmpty()) {
            "deeplink has path patterns but no host could be resolved — set baseUrl or specify host() explicitly"
        }
        DeeplinkFilter(hosts, paths)
    } else {
        null
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.base(block: BasePluginExtension.() -> Unit) {
    extensions.configure(block)
}
