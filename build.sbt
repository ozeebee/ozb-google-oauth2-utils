organization := "org.ozb"

name := "ozb-google-oauth2-utils"

version := "0.1"

scalaVersion := "2.11.7"

resolvers ++= Seq(
	"Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"
)

libraryDependencies ++= Seq(
	// google-apis
	"com.google.api-client" % "google-api-client" % "1.20.0",
	"com.google.api-client" % "google-api-client-java6" % "1.20.0",
	"com.google.gdata" % "core" % "1.47.1",
	// Command line parsing
	"com.github.scopt" %% "scopt" % "3.3.0",
	// logging
	"org.slf4j" %  "slf4j-api" % "1.7.+",
	"ch.qos.logback" % "logback-classic" % "1.1.3"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature")

//////////////////////
// sbt-native-packager

enablePlugins(JavaAppPackaging)

mainClass in Compile := Some("org.ozb.google.oauth2.OAuth2")
