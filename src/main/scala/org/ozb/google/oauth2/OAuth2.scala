package org.ozb.google.oauth2

import java.io.{FileInputStream, InputStreamReader, File}

import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2._
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.io.StdIn
import scala.util.Try

object OAuth2 {
	lazy val log = LoggerFactory.getLogger(this.getClass)

	val toolName = "org.ozb.google.oauth2"
	val toolVersion = "0.1"

	object Commands extends Enumeration {
		val GetTokenResponse, RefreshAccessToken = Value
	}
	type Commands = Commands.Value

	case class Config(
		command: Commands = null,
		// either the clientId/clientSecret pair or the clientSecretJsonPath must be specified
		clientIdOpt: Option[String] = None,
		clientSecretOpt: Option[String] = None,
		clientSecretJson: Option[File] = None,
		scopes: String = null,
		redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
		refreshToken: Option[String] = None
	) {
		def scopesColl = scopes.split(" ").toList.asJava
		lazy val clientSecrets = loadGoogleClientSecrets(clientSecretJson.get)
		def clientId = clientIdOpt.getOrElse(clientSecrets.getDetails.getClientId)
		def clientSecret = clientSecretOpt.getOrElse(clientSecrets.getDetails.getClientSecret)
	}

	val cmdLineParser = new scopt.OptionParser[Config](toolName) {
		import Commands._
		override def showUsageOnError = true
		head(toolName, toolVersion, ": Google OAuth2 command-line utils")
		help("help") text "prints this usage text"
		opt[String]("clientId") action { (x, c) => c.copy(clientIdOpt = Some(x)) } text "clientId"
		opt[String]("clientSecret") action { (x, c) => c.copy(clientSecretOpt = Some(x)) } text "clientSecret"
		opt[File]("clientSecretJson") action { (x, c) => c.copy(clientSecretJson = Some(x)) } text "clientSecret JSON file path"
		opt[String]("redirectUri") action { (x, c) => c.copy(redirectUri = x) } text s"redirectUri (by default 'urn:ietf:wg:oauth:2.0:oob')"
		cmd(GetTokenResponse.toString) action { (_, c) => c.copy(command = GetTokenResponse) } text "\tGet OAuth2 authorization token (access token and refresh token)" children(
			opt[String]("scopes") action { (x, c) => c.copy(scopes = x) } text """scopes (e.g."https://spreadsheets.google.com/feeds")""" required()
		)
		cmd(RefreshAccessToken.toString) action { (_, c) => c.copy(command = RefreshAccessToken) } text "\tRefresh access token" children(
			opt[String]("refreshToken") action { (x, c) =>  c.copy(refreshToken = Some(x)) }
		)
		checkConfig { c =>
			(if (c.command == null) failure("No command specified") else success)
			.fold(f => Left(f), _ => // a technique to chain Eithers (if the first is a failure return it, otherwise evaluate next condition)
				c.clientSecretJson.map { _ =>
					try { c.clientSecrets; success } catch { case t: Throwable => failure("clientSecretJson: " + t.getMessage) }
				}.getOrElse(success)
			)//.fold(f => Left(f), _ => ... either failure("xxx") or success() ...)
		}
	}

	def main(args: Array[String]) {
		var config = cmdLineParser.parse(args, Config()).getOrElse(sys.exit(2))
		if (config.clientSecretJson.isEmpty) {
			if (config.clientIdOpt.isEmpty) {
				val clientId = StdIn.readLine("clientId: ")
				if (isEmpty(clientId)) parseError("clientId must be specified")
				config = config.copy(clientIdOpt = Some(clientId))
			}
			if (config.clientSecretOpt.isEmpty) {
				val clientSecret = StdIn.readLine("clientSecret: ")
				if (isEmpty(clientSecret)) parseError("clientSecret must be specified")
				config = config.copy(clientSecretOpt = Some(clientSecret))
			}
		}
		//log.debug(s"config = $config")
		config.command match {
			case Commands.GetTokenResponse => getTokenReponse(config)
			case Commands.RefreshAccessToken =>
				if (config.refreshToken.isEmpty) {
					val refreshToken = StdIn.readLine("refreshToken: ")
					if (isEmpty(refreshToken)) parseError("refreshToken must be specified")
					config = config.copy(refreshToken = Some(refreshToken))
				}
				refreshAccessToken(config)
		}
	}

	def getTestArguments: Array[String] = {
		var tstArgs = Array.empty[String]
		//		tstArgs = """ GetTokenResponse --clientId xyz --clientSecret abc --scopes https://spreadsheets.google.com/feeds """.trim.split(" ")
		val clientSecretJson = "/Users/ajo/Dev/Workspaces/AJO/PersoProjects/metal-report/src/main/resources/client_secret.json"
		tstArgs = s""" GetTokenResponse --clientSecretJson $clientSecretJson  --scopes https://spreadsheets.google.com/feeds """.trim.split(" ")
		tstArgs = s""" RefreshAccessToken --clientSecretJson $clientSecretJson --refreshToken balbalablablabal """.trim.split(" ")
		tstArgs = s""" RefreshAccessToken --clientSecretJson $clientSecretJson """.trim.split(" ")
		log.debug(s"parsing command line from test arguments [${tstArgs.mkString(" ")}]")
		tstArgs
	}

	def parseError(msg: String): Unit = {
		cmdLineParser.reportError(msg)
		cmdLineParser.showUsage
		sys.exit(2)
	}

	// ~~~~~~~~~~~~~~~~~ Business code ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	lazy val jsonFactory = JacksonFactory.getDefaultInstance
	lazy val httpTransport = GoogleNetHttpTransport.newTrustedTransport()

	def getTokenReponse(config: Config): Unit = {
		val authorizationUrl = new GoogleAuthorizationCodeRequestUrl(config.clientId, config.redirectUri, config.scopesColl).build()
		println("\nPlease visit the given URL, and copy the generated authorization code")
		println(s"\t$authorizationUrl\n")
		val authCode = StdIn.readLine("code: ")
		//log.debug(s"authCode = $authCode")
		if (isEmpty(authCode)) {
			Console.err.println("No auth code entered, exiting.")
			sys.exit(1)
		}

		catching(recoverTokenRespExc) {
			val tokenResponse = new GoogleAuthorizationCodeTokenRequest(httpTransport, jsonFactory, config.clientId,
					config.clientSecret, authCode, config.redirectUri).execute()
			dumpTokenResponse(tokenResponse)
		}

	}

	def refreshAccessToken(config: Config): Unit = {
		catching(recoverTokenRespExc) {
			val tokenResponse = new GoogleRefreshTokenRequest(httpTransport, jsonFactory, config.refreshToken.get, config.clientId, config.clientSecret).execute()
			dumpTokenResponse(tokenResponse)
		}
	}

	private def dumpTokenResponse(token: GoogleTokenResponse): Unit = {
		println("\nToken response details:")
		println(s"\taccess_token  = ${token.getAccessToken}")
		if (token.getRefreshToken != null)
			println(s"\trefresh_token = ${token.getRefreshToken}")
		println(s"\texpires_in    = ${token.getExpiresInSeconds}")
		println(s"\ttoken_type    = ${token.getTokenType}")
		println("")
	}

	private def loadGoogleClientSecrets(json: File): GoogleClientSecrets = {
		val is = new FileInputStream(json)
		try {
			GoogleClientSecrets.load(jsonFactory, new InputStreamReader(is))
		} finally {
			is.close()
		}
	}

	private def recoverTokenRespExc: PartialFunction[Throwable, Unit] = {
		case e: TokenResponseException =>
			Console.err.println(s"\n${e.getClass.getSimpleName}: ${e.getMessage}")
			sys.exit(3)
	}

	// ~~~~~~~~~~~~~~~~~ Utilities ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

	def isEmpty(str: String) = str == null || str.trim.isEmpty

	// see http://stackoverflow.com/questions/21180973/scala-abstract-type-pattern-a-is-unchecked-since-it-is-eliminated-by-erasure
	def catching(recoverFunc: PartialFunction[Throwable, Unit])(block: => Unit): Unit = {
		Try {
			block
		} recover recoverFunc
	}
//	def catching[E <: Exception](block: => Unit): Unit = {
//		try {
//			block
//		} catch {
//			case e: E =>
//				Console.err.println(s"\n${e.getClass.getSimpleName}: ${e.getMessage}")
//				sys.exit(3)
//		}
//	}

}