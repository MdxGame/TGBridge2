plugins {
    java
}

group = "dev.tgbridge"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Purpur = форк Paper. Компилируем против самой старой поддерживаемой версии (26.2),
    // тогда плагин работает и на 26.2, и на 26.3+
    compileOnly("io.papermc.paper:paper-api:26.2.build.119-stable")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveFileName.set("TgBridge.jar")
}
