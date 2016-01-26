package akka.viz.frontend

import akka.viz.protocol._
import org.scalajs.dom.{onclick => oc, _}
import rx._
import scala.scalajs.js.annotation.JSExport
import scala.scalajs.js.{JSApp, JSON}
import scalatags.JsDom.all._
import upickle.default._

object FrontendApp extends JSApp with FrontendUtil with Persistence {

  val createdLinks = scala.collection.mutable.Set[String]()
  val graph = DOMGlobalScope.graph

  private def handleDownstream(messageEvent: MessageEvent): Unit = {
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
  val selectedActor = Var("")
  val seenMessages = Var[Set[String]](Set())
  val selectedMessages = persistedVar[Set[String]](Set(), "selectedMessages")

  private def addActorsToSeen(actorName: String*): Unit = {
    val previouslySeen = seenActors.now
    val newSeen = previouslySeen ++ actorName.filterNot(previouslySeen(_))
    seenActors() = newSeen
  }

  lazy val messagesContent = document.getElementById("messagespanelbody").getElementsByTagName("tbody")(0).asInstanceOf[Element]

  private def messageReceived(rcv: Received): Unit = {
    def insert(e: Element): Unit = {
      messagesContent.insertBefore(e, messagesContent.firstChild)
    }
    val sender = actorName(rcv.sender)
    val receiver = actorName(rcv.receiver)
    val selected = selectedActor.now
    val fn = () => {
      if (rcv.payload.isDefined) {
        alert(JSON.stringify(JSON.parse(rcv.payload.get)))
      }
    }
    selected match {
      case s if s == sender => insert(tr(td(i(`class` := "material-icons", "chevron_right")), td(receiver), td(rcv.payloadClass), onclick := fn).render)
      case s if s == receiver => insert(tr(td(i(`class` := "material-icons", "chevron_left")), td(receiver), td(rcv.payloadClass), onclick := fn).render)
      case _ =>
    }

  }

  @JSExport("pickActor")
  def pickActor(actorPath: String): Unit = {
    if (selectedActor.now == actorPath) {
      console.log(s"Unselected '$actorPath' actor")
      selectedActor() = ""
    } else {
      console.log(s"Selected '$actorPath' actor")
      selectedActor() = actorPath
    }
  }

  def main(): Unit = {
    val upstream = ApiConnection(webSocketUrl("stream"), handleDownstream)

    val actorsObs = Rx.unsafe {
      (seenActors(), selectedActor())
    }.triggerLater {
      val seen = seenActors.now.toList.sorted
      val selected = selectedActor.now

      val content = seen.map {
        actorName =>
          val isSelected = selected == actorName
          tr(td(if (isSelected) input(`type` := "radio", checked := true) else input(`type` := "radio")), td(if (isSelected) b(actorName) else actorName), onclick := {
            () => pickActor(actorName)
          })
      }

      val actorTree = document.getElementById("actortree").getElementsByTagName("tbody")(0).asInstanceOf[Element]
      actorTree.innerHTML = ""
      actorTree.appendChild(content.render)
    }

    val messagesObs = Rx.unsafe {
      (seenMessages(), selectedMessages())
    }.triggerLater {

      val seen = seenMessages.now.toList.sorted
      val selected = selectedMessages.now

      val content = seen.map {
        clazz =>
          val contains = selected(clazz)
          tr(
            td(if (contains) input(`type` := "checkbox", checked := true) else input(`type` := "checkbox")),
            td(if (contains) b(clazz) else clazz),
            onclick := {
              () =>
                console.log(s"Toggling ${clazz} now it will be ${!contains}")
                selectedMessages() = if (contains) selected - clazz else selected + clazz
            })
      }

      val messages = document.getElementById("messagefilter").getElementsByTagName("tbody")(0).asInstanceOf[Element]
      messages.innerHTML = ""
      messages.appendChild(content.render)

      console.log(s"Will send allowedClasses: ${selected.mkString("[", ",", "]")}")
      import upickle.default._
      upstream.send(write(SetAllowedMessages(selected.toList)))

      selectedActor.trigger {
        if (selectedActor.now == "") {
          document.getElementById("messagespaneltitle").innerHTML = s"Select actor to show its messages"
        } else {
          document.getElementById("messagespaneltitle").innerHTML = s"Messages for ${selectedActor.now}"
        }
        messagesContent.innerHTML = ""
      }

    }

    def clearFilters() = {
      selectedMessages() = Set.empty
    }

    def selectAllFilters() = {
      selectedMessages() = seenMessages.now
    }

    document.querySelector("a#messagefilter-select-none").addEventListener("click", {(e: Event) => clearFilters()}, true)
    document.querySelector("a#messagefilter-select-all").addEventListener("click", {(e: Event) => selectAllFilters()}, true)
  }
}