package akka.contrib.d3.writeside

import akka.actor._
import akka.contrib.d3._

private[d3] object AggregateManager {
  sealed trait AggregateQuery
  @SerialVersionUID(1L) final case class GetState(id: AggregateId) extends AggregateQuery
  @SerialVersionUID(1L) final case class Exists[A <: AggregateLike](id: AggregateId, pred: A ⇒ Boolean) extends AggregateQuery
  @SerialVersionUID(1L) final case class CommandMessage(id: AggregateId, command: AggregateCommand)
  @SerialVersionUID(1L) final case class RequestPassivation(stopMessage: Any)

  def props[E <: AggregateEntity](
    entityFactory: E#Id ⇒ E,
    settings:      AggregateSettings
  ): Props =
    Props(new AggregateManager[E](entityFactory, settings))
}

private[d3] class AggregateManager[E <: AggregateEntity](
    entityFactory: E#Id ⇒ E,
    settings:      AggregateSettings
) extends Actor with ActorLogging {
  import AggregateManager._

  type Aggregate = E
  type Command = E#Command
  type Event = E#Event
  type Id = E#Id

  var idByRef = Map.empty[ActorRef, Id]
  var refById = Map.empty[Id, ActorRef]
  var passivating = Set.empty[ActorRef]
  var messageBuffers = Map.empty[Id, Vector[(Command, ActorRef)]]

  def totalBufferSize: Int = messageBuffers.foldLeft(0) { (sum, entity) ⇒ sum + entity._2.size }

  // Internal messaging
  case class AggregateStarted(id: Id, ref: ActorRef)
  case class AggregateStopped(id: Id, ref: ActorRef)

  // Receive
  override def receive: Receive =
    receiveCommandMessage orElse
      receiveQuery orElse
      receiveTerminated orElse
      receivePassivate

  def receiveCommandMessage: Receive = {
    case CommandMessage(Id(id), Command(cmd)) ⇒
      deliverCommand(id, cmd, sender())
  }

  def receiveQuery: Receive = {
    case GetState(Id(id)) ⇒
      getAggregate(id).tell(AggregateActor.GetState(sender()), sender())
    case Exists(Id(id), p: (Aggregate ⇒ Boolean) @unchecked) ⇒
      getAggregate(id).tell(AggregateActor.Exists(sender(), p), sender())
  }

  def receiveTerminated: Receive = {
    case Terminated(ref) if idByRef.contains(ref) ⇒
      aggregateTerminated(idByRef(ref))
  }

  def receivePassivate: Receive = {
    case RequestPassivation(stopMessage) if idByRef.contains(sender()) ⇒
      passivate(idByRef(sender()), stopMessage)
  }

  // Other

  def aggregateTerminated(id: Id): Unit = {
    val messageBuffer = messageBuffers.getOrElse(id, Vector.empty)
    val ref = refById(id)
    if (messageBuffer.nonEmpty) {
      log.debug("Re-starting aggregate {}, re-sending {} commands", id, messageBuffer.size)
      sendMessageBuffer(AggregateStarted(id, ref))
    } else {
      passivationCompleted(AggregateStopped(id, ref))
    }

    passivating = passivating - ref
  }

  def passivate(id: Id, stopMessage: Any): Unit = {
    if (!messageBuffers.contains(id)) {
      log.debug("Passivation started for aggregate {}", id)

      passivating = passivating + refById(id)
      messageBuffers = messageBuffers.updated(id, Vector.empty)
      refById(id) ! stopMessage
    }
  }

  def passivationCompleted(event: AggregateStopped): Unit = {
    log.debug("Aggregate {} stopped", event.id)

    refById -= event.id
    idByRef -= event.ref

    messageBuffers = messageBuffers - event.id
  }

  def sendMessageBuffer(event: AggregateStarted): Unit = {
    val messageBuffer = messageBuffers.getOrElse(event.id, Vector.empty)
    messageBuffers = messageBuffers - event.id

    if (messageBuffer.nonEmpty) {
      log.debug("Sending buffer of {} commands to aggregate {}", messageBuffer.size, event.id)
      getAggregate(event.id)

      messageBuffer.foreach {
        case (cmd, requester) ⇒ deliverCommand(event.id, cmd, requester)
      }
    }
  }

  def deliverCommand(id: Id, command: Command, requester: ActorRef): Unit = {
    messageBuffers.get(id) match {
      case None ⇒
        deliverTo(id, command, requester)
      case Some(buffer) if totalBufferSize >= settings.bufferSize ⇒
        log.debug("Buffer is full, dropping command for aggregate {}", id)
        context.system.deadLetters ! CommandMessage(id, command)
      case Some(buffer) ⇒
        log.debug("Command for aggregate {} buffered", id)
        messageBuffers = messageBuffers.updated(id, buffer :+ ((command, requester)))
    }
  }

  def deliverTo(id: Id, command: Command, requester: ActorRef): Unit = {
    getAggregate(id).tell(command, requester)
  }

  def getAggregate(id: Id): ActorRef = {
    val name = id.value
    context.child(name).getOrElse {
      log.debug("Starting aggregate {}", id)

      val aggregate = context.watch(context.actorOf(aggregateProps(id).withDispatcher(settings.dispatcher), id.value))
      refById = refById.updated(id, aggregate)
      idByRef = idByRef.updated(aggregate, id)
      aggregate
    }
  }

  def aggregateProps(id: Id): Props = {
    AggregateActor.props[Aggregate](id, entityFactory, settings)
  }

  // Extractors

  object Id {
    def unapply(id: AggregateId): Option[Id] =
      Option(id.asInstanceOf[Id])
  }

  object Command {
    def unapply(cmd: AggregateCommand): Option[Command] =
      Option(cmd.asInstanceOf[Command])
  }

}
