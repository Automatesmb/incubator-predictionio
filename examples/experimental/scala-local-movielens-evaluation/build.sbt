
import AssemblyKeys._

assemblySettings

name := "scala-local-movielens-evaluation"

organization := "myorg"

version := "0.0.1-SNAPSHOT"

libraryDependencies ++= Seq(
  "org.apache.predictionio"    %% "core"          % "0.9.1" % "provided",
  "org.apache.predictionio"    %% "engines"          % "0.9.1" % "provided",
  "org.apache.spark" %% "spark-core"    % "1.2.0" % "provided")
