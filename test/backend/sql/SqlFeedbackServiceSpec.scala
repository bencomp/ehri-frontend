package backend.sql

import akka.actor.ActorSystem
import helpers._
import models.Feedback
import play.api.db.Database
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.PlaySpecification
import utils.{Page, PageParams}

import scala.concurrent.ExecutionContext

class SqlFeedbackServiceSpec extends PlaySpecification {

  implicit val actorSystem = new GuiceApplicationBuilder().build().injector.instanceOf[ActorSystem]
  implicit val executionContext = new GuiceApplicationBuilder().build().injector.instanceOf[ExecutionContext]

  def feedbackService(implicit db: Database) = SqlFeedbackService()(db, actorSystem)

  "Feedback service" should {
    "locate items correctly" in {
      withDatabaseFixture("feedback-fixtures.sql") { implicit db =>
        val q = await(feedbackService.get("nVlf4EpZjN"))
        q.text must_== Some("Testing...")
      }
    }

    "list items correctly" in {
      withDatabaseFixture("feedback-fixtures.sql") { implicit db =>
        val feedback: Page[Feedback] = await(feedbackService.list(PageParams.empty))
        feedback.total must_== 2
        feedback.items.map(_.objectId).sorted must_== Seq(Some("SmRoRZ7U4j"), Some("nVlf4EpZjN"))
      }
    }

    "delete items correctly" in {
      withDatabaseFixture("feedback-fixtures.sql") { implicit db =>
        await(feedbackService.delete("nVlf4EpZjN")) must_== true
        await(feedbackService.list(PageParams.empty)).total must_== 1
      }
    }

    "create items correctly" in {
      withDatabaseFixture("feedback-fixtures.sql") { implicit db =>
        val f = Feedback(text = Some("Problem..."), mode = Some(play.api.Mode.Prod))
        val id = await(feedbackService.create(f))
        val f2 = await(feedbackService.get(id))
        f2.mode must_== Some(play.api.Mode.Prod)
        // If we delete it successfully assume all good...
        await(feedbackService.delete(id)) must_== true
      }
    }
  }
}