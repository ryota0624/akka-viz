package akkaviz.frontend

import akkaviz.frontend.components._
import org.scalajs.dom._
import org.scalajs.dom.raw.HTMLElement
import rx.Ctx

import scala.scalajs.js

class TabManager(repo: ActorRepository, upstreamConnection: ApiConnection.Upstream)(implicit ctx: Ctx.Owner) {

  val tabs: js.Dictionary[Tab] = js.Dictionary.empty

  private[this] def attachTab(tab: Tab): Tab = {
    tab.attach(document.querySelector("#right-pane"))
    tab.onCreate()
    tab match {
      case tab: ClosableTab =>
        tab.tab.querySelector("a.close-tab").onClick({ () => close(tab) })
        tab.tab.querySelector("a[data-toggle]").addEventListener("click", handleMiddleClick(tab) _)
      case _ =>
    }
    tab
  }

  def openActorDetails(actorRef: ActorPath): Unit = {
    activate(tabs.getOrElseUpdate(ActorStateTab.stateTabId(actorRef), {
      val stateVar = repo.state(actorRef)
      attachTab(new ActorStateTab(stateVar, upstreamConnection.send))
    }))
  }

  def openLinkDetails(link: ActorLink): Unit = {
    activate(tabs.getOrElseUpdate(LinkStateTab.stateTabId(link), {
      attachTab(new LinkStateTab(link))
    }))
  }

  def openActorMessages(actorRef: ActorPath): Unit = {
    activate(tabs.getOrElseUpdate(ActorMessagesTab.stateTabId(actorRef), {
      attachTab(new ActorMessagesTab(actorRef))
    }))
  }

  def activate(tab: Tab): Unit = {
    document.querySelector(s"""a[href*="${tab.tabId}"]""").asInstanceOf[HTMLElement].click()
  }

  def close(target: ClosableTab): Unit = {
    if (target.isActive) activateSiblingOf(target)
    target.tab.parentNode.removeChild(target.tab)
    target.tabBody.parentNode.removeChild(target.tabBody)
    target.onClose()
    tabs.delete(target.tabId)
  }

  private[this] def activateSiblingOf(ct: ClosableTab): Unit = {
    Option(ct.tab.nextElementSibling).orElse(Option(ct.tab.previousElementSibling)).map { s =>
      s.querySelector("a[data-toggle]").asInstanceOf[HTMLElement]
    }.foreach {
      _.click()
    }

  }

  private[this] def handleMiddleClick(tab: ClosableTab)(e: MouseEvent): Unit = {
    if (e.button == 1) {
      e.preventDefault()
      close(tab)
    }
  }
}
