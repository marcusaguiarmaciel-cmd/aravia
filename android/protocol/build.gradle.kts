plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The watch protocol without I/O: plain Kotlin, tested on the JVM against the iOS vectors.
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.test {
    outputs.upToDateWhen { false }
}
