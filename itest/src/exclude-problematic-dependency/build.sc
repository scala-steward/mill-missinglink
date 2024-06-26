import $file.plugins

import mill._, scalalib._
import io.github.hoangmaihuy.missinglink._

object `exclude-problematic-dependency` extends RootModule with ScalaModule with MissinglinkCheckModule {

  override def scalaVersion = "2.13.14"

  override def runIvyDeps = super.runIvyDeps() ++ Seq(ivy"com.google.guava:guava:18.0")

  override def ivyDeps = super.ivyDeps() ++ Seq(ivy"com.google.guava:guava:14.0")

  override def missinglinkExcludedDependencies = Seq(DependencyFilter(organization = "com.google.guava"))

}
