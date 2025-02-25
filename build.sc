import mill._
import mill.modules._
import scalalib._
import ammonite.ops._
import coursier.maven.MavenRepository
import mill.scalalib.{PublishModule, ScalaModule}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import $ivy.`com.lihaoyi::mill-contrib-versionfile:$MILL_VERSION`
import mill.contrib.versionfile.VersionFileModule

object versionFile extends VersionFileModule

object VersionOf {
  val cats             = "2.3.1"
  val config           = "1.4.1"
  val `kind-projector` = "0.11.3"
  val logback          = "1.2.3"
  val monix            = "3.3.0"
  val prometheus       = "0.10.0"
  val rocksdb          = "6.15.2"
  val scalacheck       = "1.15.2"
  val scalalogging     = "3.9.2"
  val scalatest        = "3.2.5"
  val scalanet         = "0.7.0"
  val shapeless        = "2.3.3"
  val `scodec-core`    = "1.11.7"
  val `scodec-bits`    = "1.1.12"
}

// Using 2.12.13 instead of 2.12.10 to access @nowarn, to disable certain deperaction
// warnings that come up in 2.13 but are too awkward to work around.
object metronome extends Cross[MetronomeModule]("2.12.13", "2.13.4")

class MetronomeModule(val crossScalaVersion: String) extends CrossScalaModule {

  // Get rid of the `metronome-2.13.4-` part from the artifact name. The JAR name suffix will shows the Scala version.
  // Check with `mill show metronome[2.13.4].__.artifactName` or `mill __.publishLocal`.
  private def removeCrossVersion(artifactName: String): String =
    "metronome-" + artifactName.split("-").drop(2).mkString("-")

  // In objects inheriting this trait, use `override def moduleDeps: Seq[PublishModule]`
  // to point at other modules that also get published. In other cases such as tests
  // it can be `override def moduleDeps: Seq[JavaModule]`, i.e. point at any module.
  trait Publishing extends PublishModule {
    def description: String

    // Make sure there's no newline in the file.
    override def publishVersion = versionFile.currentVersion().toString

    override def pomSettings = PomSettings(
      description = description,
      organization = "io.iohk",
      url = "https://github.com/input-output-hk/metronome",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("input-output-hk", "metronome"),
      // Add yourself if you make a PR!
      developers = Seq(
        Developer("aakoshh", "Akosh Farkash", "https://github.com/aakoshh"),
        Developer(
          "lemastero",
          "Piotr Paradzinski",
          "https://github.com/lemastero"
        )
      )
    )
  }

  /** Common properties for all Scala modules. */
  trait SubModule extends ScalaModule {
    override def scalaVersion = crossScalaVersion
    override def artifactName = removeCrossVersion(super.artifactName())

    override def ivyDeps = Agg(
      ivy"org.typelevel::cats-core:${VersionOf.cats}",
      ivy"org.typelevel::cats-effect:${VersionOf.cats}"
    )

    override def scalacOptions = Seq(
      "-unchecked",
      "-deprecation",
      "-feature",
      "-encoding",
      "utf-8",
      "-Xfatal-warnings",
      "-Ywarn-value-discard"
    ) ++ {
      crossScalaVersion.take(4) match {
        case "2.12" =>
          // These options don't work well with 2.13
          Seq(
            "-Xlint:unsound-match",
            "-Ywarn-inaccessible",
            "-Ywarn-unused-import",
            "-Ypartial-unification", // Required for the `>>` syntax.
            "-language:higherKinds",
            "-language:postfixOps"
          )
        case "2.13" =>
          Seq()
      }
    }

    // `extends Tests` uses the context of the module in which it's defined
    trait TestModule extends Tests {
      override def artifactName =
        removeCrossVersion(super.artifactName())

      override def scalacOptions =
        SubModule.this.scalacOptions

      override def testFrameworks =
        Seq(
          "org.scalatest.tools.Framework",
          "org.scalacheck.ScalaCheckFramework"
        )

      // It may be useful to see logs in tests.
      override def moduleDeps: Seq[JavaModule] =
        super.moduleDeps ++ Seq(logging)

      override def ivyDeps = Agg(
        ivy"org.scalatest::scalatest:${VersionOf.scalatest}",
        ivy"org.scalacheck::scalacheck:${VersionOf.scalacheck}",
        ivy"ch.qos.logback:logback-classic:${VersionOf.logback}"
      )

      def single(args: String*) = T.command {
        super.runMain("org.scalatest.run", args: _*)
      }
    }
  }

  /** Data models shared between all modules. */
  object core extends SubModule with Publishing {
    override def description: String =
      "Common data models."

    override def ivyDeps = Agg(
      ivy"com.chuusai::shapeless:${VersionOf.shapeless}"
    )
  }

  /** Generic Peer-to-Peer components that can multiplex protocols
    * from different modules over a single authenticated TLS connection.
    */
  object networking extends SubModule

  /** Storage abstractions, e.g. a generic key-value store. */
  object storage extends SubModule {
    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.typelevel::cats-free:${VersionOf.cats}",
      ivy"org.scodec::scodec-bits:${VersionOf.`scodec-bits`}",
      ivy"org.scodec::scodec-core:${VersionOf.`scodec-core`}"
    )

    object test extends TestModule
  }

  /** Emit trace events, abstracting away logs and metrics.
    *
    * Based on https://github.com/input-output-hk/iohk-monitoring-framework/tree/master/contra-tracer
    */
  object tracing extends SubModule with Publishing {
    override def description: String =
      "Abstractions for contravariant tracing."

    def scalacPluginIvyDeps = Agg(
      ivy"org.typelevel:::kind-projector:${VersionOf.`kind-projector`}"
    )
  }

  /** Additional crypto utilities such as threshold signature. */
  object crypto extends SubModule with Publishing {
    override def description: String =
      "Cryptographic primitives to support HotStuff and BFT proof verification."

    // TODO: Use crypto library from Mantis.
    object test extends TestModule
  }

  /** Generic HotStuff BFT library. */
  object hotstuff extends SubModule {

    /** Pure consensus models. */
    object consensus extends SubModule {
      override def moduleDeps: Seq[PublishModule] =
        Seq(core, crypto)

      object test extends TestModule
    }

    /** Expose forensics events via tracing. */
    object forensics extends SubModule

    /** Implements peer-to-peer communication, state and block synchronisation.
      *
      * Includes the remote communication protocol messages and networking.
      */
    object service extends SubModule {
      override def moduleDeps: Seq[JavaModule] =
        Seq(storage, tracing, crypto, hotstuff.consensus, hotstuff.forensics)

      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"io.iohk::scalanet:${VersionOf.scalanet}"
      )

      object test extends TestModule
    }
  }

  /** Components realising the checkpointing functionality using HotStuff. */
  object checkpointing extends SubModule {

    /** Library to be included on the PoW side to talk to the checkpointing service.
      *
      * Includes the certificate models, the local communication protocol messages and networking.
      */
    object interpreter extends SubModule with Publishing {
      override def description: String =
        "Components to implement a PoW side checkpointing interpreter."

      override def ivyDeps = Agg(
        ivy"io.iohk::scalanet:${VersionOf.scalanet}"
      )

      override def moduleDeps: Seq[PublishModule] =
        Seq(tracing, crypto)
    }

    /** Implements the checkpointing functionality, the ledger rules,
      * and state synchronisation, which is not an inherent part of
      * HotStuff, but applies to the checkpointing use case.
      *
      * If it was published, it could be directly included in the checkpoint assisted blockchain application,
      * so the service and the interpreter can share data in memory.
      */
    object service extends SubModule {
      override def moduleDeps: Seq[JavaModule] =
        Seq(tracing, hotstuff.service, checkpointing.interpreter)

      object test extends TestModule
    }

    /** Executable application for running HotStuff and checkpointing as a stand-alone process,
      * communicating with the interpreter over TCP.
      */
    object app extends SubModule {
      override def moduleDeps: Seq[JavaModule] =
        Seq(hotstuff.service, checkpointing.service, rocksdb, logging, metrics)

      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"com.typesafe:config:${VersionOf.config}",
        ivy"ch.qos.logback:logback-classic:${VersionOf.logback}",
        ivy"io.iohk::scalanet-discovery:${VersionOf.scalanet}",
        ivy"io.monix::monix:${VersionOf.monix}"
      )

      object test extends TestModule
    }
  }

  /** Implements tracing abstractions to do structured logging. */
  object logging extends SubModule {
    override def moduleDeps: Seq[JavaModule] =
      Seq(tracing)

    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.typesafe.scala-logging::scala-logging:${VersionOf.scalalogging}"
    )
  }

  /** Implements tracing abstractions to expose metrics to Prometheus. */
  object metrics extends SubModule {
    override def moduleDeps: Seq[JavaModule] =
      Seq(tracing)

    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"io.prometheus:simpleclient:${VersionOf.prometheus}",
      ivy"io.prometheus:simpleclient_httpserver:${VersionOf.prometheus}"
    )
  }

  /** Implements the storage abstractions using RocksDB. */
  object rocksdb extends SubModule {
    override def moduleDeps: Seq[JavaModule] =
      Seq(storage)

    override def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.rocksdb:rocksdbjni:${VersionOf.rocksdb}"
    )

    object test extends TestModule {
      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"io.monix::monix:${VersionOf.monix}"
      )
    }
  }
}
