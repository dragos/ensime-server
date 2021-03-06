// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.server

import java.io._
import java.net.InetSocketAddress
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util._
import scala.util.Properties._

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import akka.util.Timeout
import com.google.common.base.Charsets
import com.google.common.io.Files
import io.netty.channel.Channel

import org.ensime.api._
import org.ensime.config._
import org.ensime.core._
import org.ensime.AkkaBackCompat
import org.ensime.server.tcp.TCPServer
import org.ensime.util.Slf4jSetup
import org.slf4j._

class ServerActor(
    config: EnsimeConfig,
    protocol: Protocol,
    interface: String = "127.0.0.1"
) extends Actor with ActorLogging {

  var channel: Channel = _

  override val supervisorStrategy = OneForOneStrategy() {
    case ex: Exception =>
      log.error(ex, s"Error with monitor actor ${ex.getMessage}")
      self ! ShutdownRequest(s"Monitor actor failed with ${ex.getClass} - ${ex.toString}", isError = true)
      Stop
  }

  def initialiseChildren(): Unit = {

    implicit val config: EnsimeConfig = this.config
    implicit val timeout: Timeout = Timeout(10 seconds)

    val broadcaster = context.actorOf(Broadcaster(), "broadcaster")
    val project = context.actorOf(Project(broadcaster), "project")

    val preferredTcpPort = PortUtil.port(config.cacheDir, "port")
    val shutdownOnLastDisconnect = Environment.shutdownOnDisconnectFlag
    context.actorOf(Props(
      new TCPServer(
        config.cacheDir, protocol, project,
        broadcaster, shutdownOnLastDisconnect, preferredTcpPort
      )
    ), "tcp-server")

    // async start the HTTP Server
    val selfRef = self
    val preferredHttpPort = PortUtil.port(config.cacheDir, "http")

    val hookHandlers: WebServer.HookHandlers = {
      outHandler =>
        val delegate = context.actorOf(Props(new Actor {
          def receive: Receive = {
            case res: RpcResponseEnvelope => outHandler(res)
          }
        }))
        val inHandler = context.actorOf(ConnectionHandler(project, broadcaster, delegate))

        { req => inHandler ! req }
    }

    val docs = DocJarReading.forConfig(config)
    WebServer.start(docs, preferredHttpPort.getOrElse(0), hookHandlers).onComplete {
      case Failure(ex) =>
        log.error(ex, s"Error binding http endpoint ${ex.getMessage}")
        selfRef ! ShutdownRequest(s"http endpoint failed to bind ($preferredHttpPort)", isError = true)

      case Success(ch) =>
        this.channel = ch
        log.info(s"ENSIME HTTP on ${ch.localAddress()}")
        try {
          val port = ch.localAddress().asInstanceOf[InetSocketAddress].getPort()
          PortUtil.writePort(config.cacheDir, port, "http")
        } catch {
          case ex: Throwable =>
            log.error(ex, s"Error initializing http endpoint ${ex.getMessage}")
            selfRef ! ShutdownRequest(s"http endpoint failed to initialise: ${ex.getMessage}", isError = true)
        }
    }(context.system.dispatcher)

    Environment.info foreach log.info
  }

  override def preStart(): Unit = {
    try {
      initialiseChildren()
    } catch {
      case t: Throwable =>
        log.error(t, s"Error during startup - ${t.getMessage}")
        self ! ShutdownRequest(t.toString, isError = true)
    }
  }
  override def receive: Receive = {
    case req: ShutdownRequest =>
      triggerShutdown(req)
  }

  def triggerShutdown(request: ShutdownRequest): Unit = {
    Server.shutdown(context.system, channel, request)
  }

}

object Server extends AkkaBackCompat {
  Slf4jSetup.init()

  val log = LoggerFactory.getLogger("Server")

  def main(args: Array[String]): Unit = {
    val ensimeFileStr = propOrNone("ensime.config").getOrElse(
      throw new RuntimeException("ensime.config (the location of the .ensime file) must be set")
    )

    val ensimeFile = new File(ensimeFileStr)
    if (!ensimeFile.exists() || !ensimeFile.isFile)
      throw new RuntimeException(s".ensime file ($ensimeFile) not found")

    implicit val config: EnsimeConfig = try {
      EnsimeConfigProtocol.parse(Files.toString(ensimeFile, Charsets.UTF_8))
    } catch {
      case e: Throwable =>
        log.error(s"There was a problem parsing $ensimeFile", e)
        throw e
    }

    Canon.config = config

    val protocol: Protocol = propOrElse("ensime.protocol", "swank") match {
      case "swanki" => new SwankiProtocol
      case "swank" => new SwankProtocol
      case other => throw new IllegalArgumentException(s"$other is not a valid ENSIME protocol")
    }

    val system = ActorSystem("ENSIME")
    system.actorOf(Props(new ServerActor(config, protocol)), "ensime-main")
  }

  def shutdown(system: ActorSystem, channel: Channel, request: ShutdownRequest): Unit = {
    val t = new Thread(new Runnable {
      def run(): Unit = {
        if (request.isError)
          log.error(s"Shutdown requested due to internal error: ${request.reason}")
        else
          log.info(s"Shutdown requested: ${request.reason}")

        log.info("Shutting down the ActorSystem")
        Try(system.terminate())

        log.info("Awaiting actor system termination")
        Try(Await.result(system.whenTerminated, Duration.Inf))

        log.info("Shutting down the Netty channel")
        Try(channel.close().sync())

        log.info("Shutdown complete")
        if (!propIsSet("ensime.server.test")) {
          if (request.isError)
            System.exit(1)
          else
            System.exit(0)
        }
      }
    })
    t.start()
  }
}
