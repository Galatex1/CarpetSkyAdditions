import com.diffplug.gradle.spotless.YamlExtension.JacksonYamlGradleConfig

class Versions(properties: ExtraPropertiesExtension) {
  val mod = properties["mod_version"] as String
  val java = JavaVersion.toVersion(properties["java_version"] as String)
  val minecraft = properties["minecraft_version"] as String
  val minecraftCompatibility = properties["compatible_minecraft_versions"] as String
  val project = "$minecraft-$mod"
  val yarnMappings = properties["yarn_mappings"] as String
  val fabricLoader = properties["loader_version"] as String
  val fabricApi = properties["fabric_version"] as String
  val carpet = properties["carpet_core_version"] as String
  val clothConfig = properties["cloth_config_version"] as String
  val modmenu = properties["modmenu_version"] as String
}

val versions = Versions(project.extra)
val modId = project.extra["mod_id"] as String

plugins {
  id("fabric-loom") version "1.1-SNAPSHOT"
  id("com.diffplug.spotless") version "latest.release"
  id("io.github.juuxel.loom-quiltflower") version "latest.release"
  id("org.jetbrains.changelog") version "latest.release"
  id("com.modrinth.minotaur") version "latest.release"
}

base {
  archivesName.set(modId)
}
version = versions.project

val fillBuildTemplate = tasks.register<Copy>("fillBuildTemplate") {
  description = "Generates the Build.java file containing build parameters"
  val templateContext = mapOf(
    "id" to project.extra["mod_id"] as String,
    "name" to project.extra["mod_name"] as String,
    "version" to version,
    "minecraft_version" to versions.minecraft,
    "yarn_mappings" to versions.yarnMappings,
  )
  inputs.properties(templateContext)
  from("src/template/java")
  into(layout.buildDirectory.dir("generated/java"))
  expand(templateContext)
}
sourceSets.main.get().java.srcDir(layout.buildDirectory.dir("generated/java"))

tasks {
  withType<JavaCompile> {
    dependsOn(fillBuildTemplate)
    options.encoding = "UTF-8"
    targetCompatibility = versions.java.toString()
    sourceCompatibility = targetCompatibility
    options.release.set(versions.java.ordinal + 1)
  }

  withType<AbstractArchiveTask> {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

  processResources {
    val templateContext = mapOf(
      "name" to project.extra["mod_name"],
      "version" to version,
      "mc_compatibility" to versions.minecraftCompatibility,
    )

    inputs.properties(templateContext)
    filesMatching("fabric.mod.json") {
      expand(templateContext)
    }
  }

  java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(versions.java.ordinal + 1)) }
    sourceCompatibility = versions.java
    targetCompatibility = versions.java
    withSourcesJar()
  }
  jar {
    // Embed license in output jar
    from("LICENSE")
  }
}

loom {
  accessWidenerPath.set(file("src/main/resources/$modId.accesswidener"))
}

repositories {
  maven("https://masa.dy.fi/maven") {
    content {
      includeGroup("carpet")
    }
  }
  maven("https://maven.shedaniel.me") {
    content {
      includeGroup("me.shedaniel.cloth")
    }
  }
  maven("https://maven.terraformersmc.com/releases/") {
    content {
      includeGroup("com.terraformersmc")
    }
  }
}

dependencies {
  minecraft("com.mojang", "minecraft", versions.minecraft)
  mappings("net.fabricmc", "yarn", versions.yarnMappings, null, "v2")
  modImplementation("net.fabricmc", "fabric-loader", versions.fabricLoader)
  modImplementation("carpet", "fabric-carpet", versions.carpet)

  arrayOf(
    "fabric-object-builder-api-v1",
    "fabric-registry-sync-v0",
    "fabric-resource-loader-v0",
    "fabric-transitive-access-wideners-v1",
  ).forEach { modImplementation(fabricApi.module(it, versions.fabricApi)) }

  modImplementation("me.shedaniel.cloth", "cloth-config-fabric", versions.clothConfig) {
    exclude("net.fabricmc.fabric-api")
  }
  modImplementation("com.terraformersmc", "modmenu", versions.modmenu)
}

tasks.assemble.get().dependsOn(
  tasks.register<Zip>("zipTranslationPack") {
    description = "Zips the Translations resource pack"
    val tempDir = layout.buildDirectory.dir("translations-pack")
    copy {
      into(tempDir)
      from("translations-pack")

      into("assets/$modId/lang") {
        from("src/main/resources/assets/$modId/lang") {
          exclude("en_us.json")
        }
      }
    }
    archiveClassifier.set("translations")
    group = "build"
    from(tempDir)
    destinationDirectory.set(base.distsDirectory)
  },
  tasks.register<Zip>("zipSkyBlockDatapack") {
    description = "Zips the SkyBlock datapack"
    val tempDir = layout.buildDirectory.dir("datapack/skyblock")
    copy {
      into(tempDir)
      from("src/main/resources/resourcepacks/skyblock")
    }
    archiveClassifier.set("datapack")
    group = "build"
    from(tempDir)
    destinationDirectory.set(base.distsDirectory)
  },
)

spotless {
  format("misc") {
    target("*.md", ".gitignore")
    indentWithSpaces(2)
    endWithNewline()
  }

  java {
    target("src/*/java/**/*.java")
    palantirJavaFormat()
    toggleOffOn()
    removeUnusedImports()
    importOrder()
  }

  kotlinGradle {
    target("*.gradle.kts")
    ktlint()
  }

  json {
    target("**/*.json")
    targetExclude("$buildDir/**", "run/**")
    gson().indentWithSpaces(2)
  }

  yaml {
    target("**/*.yml", "**/*.yaml")
    (jackson() as JacksonYamlGradleConfig).yamlFeature("WRITE_DOC_START_MARKER", false)
  }

  changelog {
    version.set(versions.mod)
    repositoryUrl.set(project.extra["repository"] as String)
    introduction.set(
      """
        All notable changes to this project will be documented in this file.

        The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
      """.trimIndent(),
    )
    groups.set(listOf("Added", "Changed", "Deprecated", "Removed", "Fixed"))

    // Curseforge markdown doesn't recognize "-"
    itemPrefix.set("*")
  }

  modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set("carpet-sky-additions")
    versionNumber.set(providers.gradleProperty("mod_version"))
    versionName.set("${versions.mod} for Minecraft ${versions.minecraft}")
    versionType.set("release")
    uploadFile.set(tasks.remapJar)
    gameVersions.add(versions.minecraft)
    loaders.add("fabric")
    changelog.set(
      provider {
        project.changelog.renderItem(project.changelog.get(versions.mod).withHeader(false).withEmptySections(false).withLinks(false))
      },
    )
    dependencies {
      required.project("fabric-api")
      required.project("carpet")
      required.project("cloth-config")
      optional.project("modmenu")
    }
  }
  tasks.modrinth.get().dependsOn(tasks.patchChangelog)
}
