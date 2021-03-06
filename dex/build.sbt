import java.nio.charset.StandardCharsets

import Dependencies.Version
import DexDockerKeys._
import VersionSourcePlugin.V
import com.typesafe.sbt.SbtNativePackager.Universal
import com.typesafe.sbt.packager.archetypes.TemplateWriter

enablePlugins(RewriteSwaggerConfigPlugin,
              JavaServerAppPackaging,
              UniversalDeployPlugin,
              JDebPackaging,
              SystemdPlugin,
              DexDockerPlugin,
              GitVersioning,
              VersionSourcePlugin)

V.scalaPackage := "com.wavesplatform.dex"
V.subProject := "dex"

resolvers += "dnvriend" at "https://dl.bintray.com/dnvriend/maven"
libraryDependencies ++= Dependencies.Module.dex

val packageSettings = Seq(
  maintainer := "wavesplatform.com",
  packageSummary := "DEX",
  packageDescription := "Decentralized Exchange for Turtle Network."
)

packageSettings
inScope(Global)(packageSettings)

lazy val swaggerUiVersionSourceTask = Def.task {
  val versionFile = sourceManaged.value / "com" / "wavesplatform" / "dex" / "api" / "http" / "SwaggerUiVersion.scala"
  IO.write(
    versionFile,
    s"""package com.wavesplatform.dex.api.http
       |
       |object SwaggerUiVersion {
       |  val VersionString = "${Version.swaggerUi}"
       |}
       |""".stripMargin,
    charset = StandardCharsets.UTF_8
  )
  Seq(versionFile)
}

inConfig(Compile)(
  Seq(
    sourceGenerators += swaggerUiVersionSourceTask.taskValue,
    discoveredMainClasses := Seq(
      "com.wavesplatform.dex.Application",
      "com.wavesplatform.dex.WavesDexCli"
    ),
    mainClass := discoveredMainClasses.value.headOption,
    run / fork := true
  ))

// Docker
inTask(docker)(
  Seq(
    additionalFiles ++= Seq(
      (Universal / stage).value,
      (Compile / sourceDirectory).value / "container" / "start.sh",
      (Compile / sourceDirectory).value / "container" / "default.conf"
    )
  )
)

// Packaging
executableScriptName := "tn-dex"

// ZIP archive and mappings for all artifacts
inConfig(Universal)(
  Seq(
    packageName := s"tn-dex-${version.value}", // An archive file name
    // Common JVM parameters
    // -J prefix is required by a parser
    javaOptions ++= Seq(
      "-Xmx2g",
      "-Xms128m",
    ).map(x => s"-J$x"),
    mappings ++= sbt.IO
      .listFiles((Compile / packageSource).value / "doc")
      .map { file =>
        file -> s"doc/${file.getName}"
      }
      .toSeq
  ))

// DEB package
inConfig(Linux)(Seq(
  name := "tn-dex", // A staging directory name
  normalizedName := name.value, // An archive file name
  packageName := name.value // In a control file
))

inConfig(Debian)(
  Seq(
    linuxStartScriptTemplate := (packageSource.value / "systemd.service").toURI.toURL,
    debianPackageDependencies += "java8-runtime-headless",
    serviceAutostart := false,
    maintainerScripts := maintainerScriptsFromDirectory(packageSource.value / "debian", Seq("preinst", "postinst", "postrm", "prerm")),
    linuxPackageMappings ++= {
      val upstartScript = {
        val src    = packageSource.value / "upstart.conf"
        val dest   = (target in Debian).value / "upstart" / s"${packageName.value}.conf"
        val result = TemplateWriter.generateScript(src.toURI.toURL, linuxScriptReplacements.value)
        IO.write(dest, result)
        dest
      }

      Seq(upstartScript -> s"/etc/init/${packageName.value}.conf").map(packageMapping(_).withConfig().withPerms("644"))
    },
    linuxScriptReplacements += "detect-loader" ->
      """is_systemd() {
        |    which systemctl >/dev/null 2>&1 && \
        |    systemctl | grep -- -\.mount >/dev/null 2>&1
        |}
        |is_upstart() {
        |    /sbin/init --version | grep upstart >/dev/null 2>&1
        |}
        |""".stripMargin
  )
)
