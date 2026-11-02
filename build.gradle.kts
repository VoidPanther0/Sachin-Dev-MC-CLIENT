plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "doomsday"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    implementation("com.google.code.gson:gson:2.11.0")
}

// agent jar: carries Premain-Class/Agent-Class so it can be used both as
// -javaagent:doomsday.jar at launch, and as the payload loaded dynamically
// by Injector via VirtualMachine.attach(pid).loadAgent(jar).
tasks.shadowJar {
    archiveBaseName.set("doomsday-agent")
    archiveClassifier.set("")
    manifest {
        attributes(
            "Premain-Class" to "doomsday.agent.DoomsdayAgent",
            "Agent-Class" to "doomsday.agent.DoomsdayAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Main-Class" to "doomsday.attach.Injector",
            "Implementation-Version" to version
        )
    }
}

// the plain `jar` task (bare classes, no deps, no manifest) is redundant — shadowJar is the
// only artifact anyone runs; disabling it stops `build` from producing a second, useless jar.
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
