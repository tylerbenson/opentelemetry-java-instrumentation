import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
  id("com.github.johnrengelman.shadow")
  id("otel.java-conventions")
  id("otel.japicmp-conventions")
  id("otel.publish-conventions")
}

group = "io.opentelemetry.instrumentation"

repositories {
  mavenCentral()
  // Needed for https://github.com/FoxSamu/ASM-Descriptor
  maven {
    url = uri("https://maven.shadew.net/")
  }
}

dependencies {
  implementation("com.google.guava:guava:31.1-jre")
  implementation("org.ow2.asm:asm:9.4")
  implementation("org.ow2.asm:asm-util:9.4")

  // https://github.com/FoxSamu/ASM-Descriptor
  implementation("net.shadew:descriptor:1.0") {
    exclude("org.ow2.asm", "asm-commons")
  }
}

tasks {
  val shadowJar by existing(ShadowJar::class) {
    archiveClassifier.set("")

    manifest {
      attributes(jar.get().manifest.attributes)
      attributes(
        "Main-Class" to "io.opentelemetry.instrumentation.classscanner.ClassScannerMain"
      )
    }
    minimize()
  }
}
