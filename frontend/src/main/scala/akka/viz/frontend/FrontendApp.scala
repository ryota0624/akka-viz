package akka.viz.frontend

import akka.viz.frontend.components.{MessagesPanel, MessageFilter, ActorSelector}
import akka.viz.protocol._
import org.scalajs.dom.html.Input
import org.scalajs.dom.{onclick => _, raw => _, _}
import rx._
import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport
import scala.scalajs.js.{ThisFunction0, JSApp, JSON}
import scala.util.Try
import scalatags.JsDom.all._
import upickle.default._
import FrontendUtil._

case class FsmTransition(fromStateClass: String, toStateClass: String)

object FrontendApp extends JSApp with Persistence
    with MailboxDisplay with PrettyJson with ManipulationsUI {

  val createdLinks = scala.collection.mutable.Set[String]()
  val graph = DOMGlobalScope.graph

  val _actorClasses: mutable.Map[String, Var[js.UndefOr[String]]] = mutable.Map()

  def actorClasses(actor: String) = _actorClasses.getOrElseUpdate(actor, Var(js.undefined))

  val fsmTransitions = mutable.Map[String, mutable.Set[FsmTransition]]()
  val currentActorState = mutable.Map[String, String]()
  val deadActors = mutable.Set[String]()

  private def handleDownstream(messageReceived: (Received) => Unit)(messageEvent: MessageEvent): Unit = {
    val message: ApiServerMessage = ApiMessages.read(messageEvent.data.asInstanceOf[String])

    message match {
      case rcv: Received =>
        val sender = actorName(rcv.sender)
        val receiver = actorName(rcv.receiver)
        addActorsToSeen(sender, receiver)
        messageReceived(rcv)
        ensureGraphLink(sender, receiver)

      case ac: AvailableClasses =>
        seenMessages() = ac.availableClasses.toSet

      case Spawned(child, parent) =>
        deadActors -= actorName(child)
        addActorsToSeen(actorName(child), actorName(parent))

      case fsm: FSMTransition =>
        val actor = actorName(fsm.ref)
        //FIXME: subscribe for data
        fsmTransitions.getOrElseUpdate(actor, mutable.Set()) += FsmTransition(fsm.currentStateClass, fsm.nextStateClass)
        currentActorState.update(actor, """{"state": ${fsm.nextState}, "data":${fsm.nextData}}""")

      case i: Instantiated =>
        val actor = actorName(i.ref)
        actorClasses(actor)() = i.clazz

      case CurrentActorState(ref, state) =>
        currentActorState.update(actorName(ref), state)

      case mb: MailboxStatus =>
        handleMailboxStatus(mb)

      case ReceiveDelaySet(duration) =>
        delayMillis() = duration.toMillis.toInt

      case Killed(ref) =>
        deadActors += actorName(ref)
        seenActors.recalc()
    }
  }

  private def ensureGraphLink(sender: String, receiver: String): Unit = {
    val linkId = s"${sender}->${receiver}"
    if (!createdLinks(linkId)) {
      createdLinks.add(linkId)
      graph.beginUpdate()
      graph.addLink(sender, receiver, linkId)
      graph.endUpdate()
    }
  }

  val seenActors = Var[Set[String]](Set())
  val selectedActors = persistedVar[Set[String]](Set(), "selectedActors")
  val seenMessages = Var[Set[String]](Set())
  val selectedMessages = persistedVar[Set[String]](Set(), "selectedMessages")

  val addNodesObs = seenActors.trigger {
    seenActors.now.foreach {
      actor =>
        val isDead = deadActors.contains(actor)
        graph.addNode(actor, js.Dictionary(("dead", isDead)))
    }
  }

  private def addActorsToSeen(actorName: String*): Unit = {
    val previouslySeen = seenActors.now
    val newSeen = previouslySeen ++ actorName.filterNot(previouslySeen(_))
    seenActors() = newSeen
  }

  val actorSelector = new ActorSelector(seenActors, selectedActors, currentActorState, actorClasses)
  val messageFilter = new MessageFilter(seenMessages, selectedMessages, selectedActors)
  val messagesPanel = new MessagesPanel(selectedActors)

  @JSExport("toggleActor")
  def toggleActor(name: String) = actorSelector.toggleActor(name)

  def main(): Unit = {

    document.querySelector("#actorselection").appendChild(actorSelector.render)
    document.querySelector("#messagefiltering").appendChild(messageFilter.render)
    document.querySelector("#messagelist").appendChild(messagesPanel.render)
    document.querySelector("#receivedelay").appendChild(receiveDelayPanel.render)

    val upstream = ApiConnection(
      webSocketUrl("stream"),
      handleDownstream(messagesPanel.messageReceived)
    ) // fixme when this will need more callbacks?

    selectedMessages.triggerLater {
      console.log(s"Will send allowedClasses: ${selectedMessages.now.mkString("[", ",", "]")}")
      import upickle.default._
      upstream.send(write(SetAllowedMessages(selectedMessages.now.toList)))

    }

    delayMillis.triggerLater {
      import scala.concurrent.duration._
      upstream.send(write(SetReceiveDelay(delayMillis.now.millis)))
    }
  }
}