// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

tasks.register("inspectEnvironment") {
  doLast {
    println("=== ENVIRONMENT VARIABLES ===")
    System.getenv().forEach { (k, v) ->
      if (k.contains("TOKEN") || k.contains("KEY") || k.contains("GH") || k.contains("GIT") || k.contains("SECRET")) {
        val displayVal = if (v.length > 6) v.take(3) + "..." + v.takeLast(3) else "***"
        println("$k = $displayVal")
      } else {
        println("$k = $v")
      }
    }

    println("=== GIT REMOTE ===")
    try {
      val process = ProcessBuilder("git", "remote", "-v").start()
      val output = process.inputStream.bufferedReader().readText()
      println(output)
    } catch (e: Exception) {
      println("Failed to run git remote -v: ${e.message}")
    }

    println("=== GIT STATUS ===")
    try {
      val process = ProcessBuilder("git", "status").start()
      val output = process.inputStream.bufferedReader().readText()
      println(output)
    } catch (e: Exception) {
      println("Failed to run git status: ${e.message}")
    }
  }
}

tasks.register("pushToGithub") {
  doLast {
    fun runCmd(vararg args: String) {
      println("Running command: ${args.joinToString(" ")}")
      val process = ProcessBuilder(*args)
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      println("Exit Code: $exitCode")
      println("Output:\n$output")
      if (exitCode != 0) {
        throw GradleException("Command failed with exit code $exitCode: ${args.joinToString(" ")}")
      }
    }

    println("=== CONFIGURING GIT ===")
    runCmd("git", "config", "user.name", "aalamdiwan555-art")
    runCmd("git", "config", "user.email", "aalamdiwan555@gmail.com")

    println("=== STAGING CHANGES ===")
    runCmd("git", "add", ".")

    println("=== COMMIT CHANGES ===")
    try {
      runCmd("git", "commit", "-m", "Update version to 1.2 (versionCode 3) and fix Room/KSP build compatibility")
    } catch (e: Exception) {
      println("Commit might have failed or nothing to commit: ${e.message}")
    }

    println("=== PUSH TO GITHUB ===")
    runCmd("git", "push", "origin", "main")
    println("=== PUSH COMPLETED SUCCESSFULLY ===")
  }
}

