import org.jetbrains.intellij.IntelliJPluginExtension

apply { plugin("kotlin") }

repositories {
    androidDxJarRepo(project)
}

configureIntellijPlugin {
    setExtraDependencies("jps-build-test")
    setPlugins("android", "gradle", "junit")
}

val robolectricClasspath by configurations.creating
val androidSdk by configurations.creating
val androidJar by configurations.creating

dependencies {
    testCompile(project(":compiler:util"))
    testCompile(project(":compiler:backend"))
    testCompile(project(":compiler:cli"))
    testCompile(project(":plugins:android-extensions-compiler"))
    testCompile(project(":plugins:android-extensions-ide"))
    testCompile(project(":kotlin-android-extensions-runtime"))
    testCompile(project(":plugins:android-extensions-jps"))
    testCompile(project(":allopen-ide-plugin")) { isTransitive = false }
    testCompile(project(":kotlin-allopen-compiler-plugin"))
    testCompile(project(":noarg-ide-plugin")) { isTransitive = false }
    testCompile(project(":kotlin-noarg-compiler-plugin"))
    testCompile(project(":plugins:annotation-based-compiler-plugins-ide-support")) { isTransitive = false }
    testCompile(project(":sam-with-receiver-ide-plugin")) { isTransitive = false }
    testCompile(project(":kotlin-sam-with-receiver-compiler-plugin"))
    testCompile(project(":idea:idea-android")) { isTransitive = false }
    testCompile(project(":plugins:lint")) { isTransitive = false }
    testCompile(project(":plugins:uast-kotlin"))
    testCompile(projectDist(":kotlin-test:kotlin-test-jvm"))
    testCompile(projectTests(":compiler:tests-common"))
    testCompile(projectTests(":jps-plugin"))
    testCompile(commonDep("junit:junit"))
    testRuntime(project(":jps-plugin"))
    testRuntime(projectTests(":compiler:tests-common-jvm6"))
    testRuntime(androidDxJar())
    robolectricClasspath(commonDep("org.robolectric", "robolectric"))
    androidSdk(project(":custom-dependencies:android-sdk", configuration = "androidSdk"))
    androidJar(project(":custom-dependencies:android-sdk", configuration = "androidJar"))
}

afterEvaluate {
    dependencies {
        testCompileOnly(intellij { include("jps-builders.jar", "jps-model.jar") })
        testCompileOnly(intellijPlugin("android") { include("**/android-jps-plugin.jar") })
        testCompile(intellijExtra("jps-build-test"))
        testRuntime(intellij())
        testRuntime(intellijPlugin("junit") { include("idea-junit.jar", "resources_en.jar") })
        testRuntime(intellijPlugins("gradle", "android"))
    }
}

sourceSets {
    "main" {}
    "test" { projectDefault() }
}

testsJar {}

evaluationDependsOn(":kotlin-android-extensions-runtime")

projectTest {
    environment("ANDROID_EXTENSIONS_RUNTIME_CLASSES", getSourceSetsFrom(":kotlin-android-extensions-runtime")["main"].output.classesDirs.asPath)
    dependsOnTaskIfExistsRec("dist", project = rootProject)
    dependsOn(androidSdk)
    dependsOn(androidJar)
    workingDir = rootDir
    doFirst {
        val androidPluginPath = project.the<IntelliJPluginExtension>().pluginDependencies.find { it.id == "android" }?.jarFiles?.first()?.parentFile?.canonicalPath
                                ?: throw GradleException("idea android plugin is not configured")
        systemProperty("ideaSdk.androidPlugin.path", androidPluginPath)
        systemProperty("robolectric.classpath", robolectricClasspath.asPath)
        systemProperty("android.sdk", androidSdk.singleFile.canonicalPath)
        systemProperty("android.jar", androidJar.singleFile.canonicalPath)
    }
}
