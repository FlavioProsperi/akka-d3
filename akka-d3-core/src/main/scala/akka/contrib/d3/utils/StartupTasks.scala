package akka.contrib.d3.utils

import akka.Done
import akka.actor._
import akka.util.Reflect
import com.typesafe.config._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

object StartupTasks extends ExtensionId[StartupTasks]
    with ExtensionIdProvider {
  override def get(system: ActorSystem): StartupTasks = super.get(system)

  override def lookup(): ExtensionId[_ <: Extension] = StartupTasks

  override def createExtension(system: ExtendedActorSystem): StartupTasksImpl = {
    val cl = findClassLoader()
    val appConfig = system.settings.config
    new StartupTasksImpl(system, appConfig, cl)
  }

  final class Settings(classLoader: ClassLoader, cfg: Config) {
    val config: Config = {
      val config = cfg.withFallback(ConfigFactory.defaultReference(classLoader))
      config.checkValid(ConfigFactory.defaultReference(classLoader), "akka.contrib.d3.utils.startup-tasks")
      config
    }

    import config._

    val topology: String =
      getString("akka.contrib.d3.topology") match {
        case "local"   ⇒ "local"
        case "cluster" ⇒ "cluster"
        case other     ⇒ throw new IllegalArgumentException(s"Unknown value $other for setting akka.contrib.d3.topology")
      }

    val startupTaskProviderClass: String =
      Try(getString("akka.contrib.d3.utils.startup-tasks.provider")).toOption.getOrElse(topology) match {
        case "local"   ⇒ classOf[LocalStartupTaskProvider].getName
        case "cluster" ⇒ "akka.contrib.d3.utils.ClusterStartupTaskProvider"
        case fqcn      ⇒ fqcn
      }
  }

  private[d3] def findClassLoader(): ClassLoader = Reflect.findClassLoader()
}

abstract class StartupTasks
    extends Extension {
  def create(
    name:                String,
    task:                () ⇒ Future[Done],
    timeout:             FiniteDuration,
    minBackoff:          FiniteDuration,
    maxBackoff:          FiniteDuration,
    randomBackoffFactor: Double
  ): StartupTask
}

class StartupTasksImpl(
    val system:        ExtendedActorSystem,
    applicationConfig: Config,
    classLoader:       ClassLoader
) extends StartupTasks {
  import StartupTasks._

  final val settings: Settings = new Settings(classLoader, applicationConfig)

  protected val dynamicAccess: DynamicAccess = system.dynamicAccess

  override def create(
    name:                String,
    task:                () ⇒ Future[Done],
    timeout:             FiniteDuration,
    minBackoff:          FiniteDuration,
    maxBackoff:          FiniteDuration,
    randomBackoffFactor: Double
  ): StartupTask =
    startupTaskProvider.startupTask(name, task, timeout, minBackoff, maxBackoff, randomBackoffFactor)

  import settings._

  private val startupTaskProvider: StartupTaskProvider = try {
    val arguments = Vector(
      classOf[ExtendedActorSystem] → system
    )

    dynamicAccess.createInstanceFor[StartupTaskProvider](startupTaskProviderClass, arguments).get
  } catch {
    case NonFatal(e) ⇒
      throw e
  }

}
