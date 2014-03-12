package integration

import scala.concurrent.ExecutionContext.Implicits.global
import helpers.Neo4jRunnerSpec
import models._
import play.api.test.FakeRequest
import utils.ContributionVisibility
import controllers.portal.ReversePortal
import backend.ApiUser
import mocks.MockBufferedMailer
import com.google.common.net.HttpHeaders
import defines.EntityType
import backend.rest.PermissionDenied


class PortalSpec extends Neo4jRunnerSpec(classOf[PortalSpec]) {
  import mocks.{privilegedUser, unprivilegedUser}

  private val portalRoutes: ReversePortal = controllers.portal.routes.Portal

  override def getConfig = Map("recaptcha.skip" -> true)

  "Portal views" should {
    "view docs" in new FakeApp {
      val doc = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.browseDocument("c1").url)).get
      status(doc) must equalTo(OK)
    }

    "view repositories" in new FakeApp {
      val doc = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.browseRepository("r1").url)).get
      status(doc) must equalTo(OK)
    }

    "view historical agents" in new FakeApp {
      val doc = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.browseHistoricalAgent("a1").url)).get
      status(doc) must equalTo(OK)
    }

    "allow viewing profile" in new FakeApp {
      val prof = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.profile().url)).get
      status(prof) must equalTo(OK)
    }

    "allow editing profile" in new FakeApp {
      val testName = "Inigo Montoya"
      val data = Map(
        "identifier" -> Seq("???"), // Overridden...
        UserProfileF.NAME -> Seq(testName)
      )
      val update = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.updateProfilePost().url), data).get
      status(update) must equalTo(SEE_OTHER)

      val prof = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.profile().url)).get
      status(prof) must equalTo(OK)
      contentAsString(prof) must contain(testName)
    }

    "allow deleting profile with correct confirmation" in new FakeApp {
      // Fetch the current name
      implicit val apiUser = ApiUser(Some(privilegedUser.id))
      val cname = await(testBackend.get[UserProfile](privilegedUser.id)).model.name
      val data = Map("confirm" -> Seq(cname))
      val delete = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.deleteProfilePost().url), data).get
      status(delete) must equalTo(SEE_OTHER)

      // Check user has been anonymised...
      val cnameAfter = await(testBackend.get[UserProfile](privilegedUser.id)).model.name
      cname must not equalTo cnameAfter
    }

    "disallow deleting profile without correct confirmation" in new FakeApp {
      val data = Map("confirm" -> Seq("THE WRONG CONFIRMATION"))
      val delete = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.deleteProfilePost().url), data).get
      status(delete) must equalTo(BAD_REQUEST)
    }

    "allow following and unfollowing users" in new FakeApp {
      val follow = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.followUser(unprivilegedUser.id).url), "").get
      status(follow) must equalTo(SEE_OTHER)

      val following = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.followingForUser(privilegedUser.id).url)).get
      // Check the following page contains a link to the user we just followed
      contentAsString(following) must contain(
        portalRoutes.browseUser(unprivilegedUser.id).url)

      // Unfollow the sucker - he's boring...
      val unfollow = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.unfollowUser(unprivilegedUser.id).url), "").get
      status(unfollow) must equalTo(SEE_OTHER)

      val following2 = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.followingForUser(privilegedUser.id).url)).get
      // Check the following page contains no links to the user we just unfollowed
      contentAsString(following2) must not contain portalRoutes.browseUser(unprivilegedUser.id).url
    }

    "allow watching and unwatching items" in new FakeApp {
      val watch = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.watchItemPost("c1").url), "").get
      status(watch) must equalTo(SEE_OTHER)

      // Watched items show up on the profile - maybe change this?
      val watching = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.watching().url)).get
      // Check the following page contains a link to the user we just followed
      contentAsString(watching) must contain(
        portalRoutes.browseDocument("c1").url)

      // Unwatch
      val unwatch = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.unwatchItemPost("c1").url), "").get
      status(unwatch) must equalTo(SEE_OTHER)

      val watching2 = route(fakeLoggedInHtmlRequest(privilegedUser, GET,
        portalRoutes.watching().url)).get
      // Check the profile contains no links to the item we just unwatched
      contentAsString(watching2) must not contain portalRoutes.browseDocument("c1").url

    }

    "show correct activity for watched items and followed users" in new FakeApp {

    }

    "allow annotating items with correct visibility" in new FakeApp {
      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.annotatePost("c4", "cd4").url), testData).get
      status(post) must equalTo(CREATED)
      contentAsString(post) must contain(testBody)

      // Ensure the unprivileged user can't see the annotation...
      val doc = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc) must equalTo(OK)
      contentAsString(doc) must not contain testBody

    }

    "allow annotating fields with correct visibility" in new FakeApp {
      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.annotateFieldPost(
          "c4", "cd4", IsadG.SCOPE_CONTENT).url), testData).get
      status(post) must equalTo(CREATED)
      contentAsString(post) must contain(testBody)

      // Ensure the unprivileged user can't see the annotation...
      val doc = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc) must equalTo(OK)
      contentAsString(doc) must not contain testBody
    }

    "disallow creating annotations without permission" in new FakeApp {
      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(unprivilegedUser, POST,
        portalRoutes.annotateFieldPost(
          "c4", "cd4", IsadG.SCOPE_CONTENT).url), testData).get
      status(post) must throwA[PermissionDenied]
    }

    "allow updating and deleting annotations created by a given user" in new FakeApp {

      // First we need to grant permission by adding the user to the portal group
      val addGroup = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
          controllers.core.routes.Groups
            .addMemberPost("portal", EntityType.UserProfile, unprivilegedUser.id).url), "").get
      status(addGroup) must equalTo(SEE_OTHER)

      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(unprivilegedUser, POST,
        portalRoutes.annotateFieldPost(
          "c4", "cd4", IsadG.SCOPE_CONTENT).url), testData).get
      status(post) must equalTo(CREATED)
      contentAsString(post) must contain(testBody)

      header(HttpHeaders.LOCATION, post) must beSome.which { loc =>
        val aid = loc.substring(loc.lastIndexOf("/") + 1)
      println(s"ID: $aid ($loc)")
        val updateBody = "UPDATED TEST ANNOTATION!!!"
        val updateData = testData.updated(AnnotationF.BODY, Seq(updateBody))
        val udpost = route(fakeLoggedInHtmlRequest(unprivilegedUser, POST,
          portalRoutes.editAnnotationPost(aid).url),
          updateData).get
        status(udpost) must equalTo(OK)
        contentAsString(udpost) must contain(updateBody)

        val delpost = route(fakeLoggedInHtmlRequest(unprivilegedUser, POST,
          portalRoutes.deleteAnnotationPost(aid).url)).get
        status(delpost) must equalTo(OK)
      }
    }

    "allow changing annotation visibility" in new FakeApp {
      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.annotateFieldPost(
          "c4", "cd4", IsadG.SCOPE_CONTENT).url), testData).get
      status(post) must equalTo(CREATED)
      contentAsString(post) must contain(testBody)

      // Ensure the unprivileged user can't see the annotation...
      val doc = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc) must equalTo(OK)
      contentAsString(doc) must not contain testBody

      // Mmmn, need to get the id - this is faffy... assume there is
      // only one annotation on the item and fetch it via the api...
      implicit val apiUser = ApiUser(Some(privilegedUser.id))
      val aid = await(testBackend.getAnnotationsForItem("c4")).head.id

      // The privilegedUser and unprivilegedUser belong to the same group (kcl)
      // so if we set the visibility to groups it should be visible to the other
      // guy...
      val visData = Map(
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Groups.toString)
      )
      val setVis = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.setAnnotationVisibilityPost(aid).url), visData).get
      status(setVis) must equalTo(OK)

      // Ensure the unprivileged user CAN now see the annotation...
      val doc2 = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc2) must equalTo(OK)
      contentAsString(doc2) must contain(testBody)
    }

    "allow annotation promotion to increase visibility" in new FakeApp {
      val testBody = "Test Annotation!!!"
      val testData = Map(
        AnnotationF.BODY -> Seq(testBody),
        AnnotationF.ALLOW_PUBLIC -> Seq("true"),
        ContributionVisibility.PARAM -> Seq(ContributionVisibility.Me.toString)
      )

      val post = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
        portalRoutes.annotateFieldPost(
          "c4", "cd4", IsadG.SCOPE_CONTENT).url), testData).get
      status(post) must equalTo(CREATED)
      contentAsString(post) must contain(testBody)

      // Ensure the unprivileged user can't see the annotation...
      val doc = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc) must equalTo(OK)
      contentAsString(doc) must not contain testBody

      // Get a id via faff method and promote the item...
      implicit val apiUser = ApiUser(Some(privilegedUser.id))
      val aid = await(testBackend.getAnnotationsForItem("c4")).head.id
      await(testBackend.promote(aid))

      // Ensure the unprivileged user CAN now see the annotation...
      val doc2 = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET,
        portalRoutes.browseDocument("c4").url)).get
      status(doc2) must equalTo(OK)
      contentAsString(doc2) must contain(testBody)
    }

    "allow deleting annotations" in new FakeApp {

    }

    "allow linking items" in new FakeApp {

    }

    "allow deleting links" in new FakeApp {

    }

    "allow changing link visibility" in new FakeApp {

    }

    "allow anon feedback" in new FakeApp {
      val fbCount = mockFeedback.buffer.size
      val fb = Map("text" -> Seq("it doesn't work"))
      val post = route(fakeLoggedInHtmlRequest(privilegedUser, POST,
          portalRoutes.feedbackPost().url), fb).get
      status(post) must equalTo(SEE_OTHER)
      val newCount = mockFeedback.buffer.size
      newCount must equalTo(fbCount + 1)
      mockFeedback.buffer.get(newCount) must beSome.which { f =>
        f.text must equalTo("it doesn't work")
      }
    }
  }

  "Signup process" should {
    "create a validation token and send a mail on signup" in new FakeApp {
      val testEmail: String = "test@example.com"
      val numSentMails = MockBufferedMailer.mailBuffer.size
      val numAccounts = mocks.userFixtures.size
      val data: Map[String,Seq[String]] = Map(
        "name" -> Seq("Test Name"),
        "email" -> Seq(testEmail),
        "password" -> Seq("testpass"),
        "confirm" -> Seq("testpass"),
        CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
      )
      val signup = route(FakeRequest(POST, portalRoutes.signupPost().url)
        .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
      status(signup) must equalTo(SEE_OTHER)
      MockBufferedMailer.mailBuffer.size must beEqualTo(numSentMails + 1)
      MockBufferedMailer.mailBuffer.last.to must contain(testEmail)
      mocks.userFixtures.size must equalTo(numAccounts + 1)
      val userOpt = mocks.userFixtures.values.find(u => u.email == testEmail)
      userOpt must beSome.which { user =>
        user.verified must beFalse
      }
    }

    "allow unverified user to log in" in new FakeApp {
      val testEmail: String = "test@example.com"
      val testName: String = "Test Name"
      val data: Map[String,Seq[String]] = Map(
        "name" -> Seq(testName),
        "email" -> Seq(testEmail),
        "password" -> Seq("testpass"),
        "confirm" -> Seq("testpass"),
        CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
      )
      val signup = route(FakeRequest(POST, portalRoutes.signupPost().url)
        .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
      status(signup) must equalTo(SEE_OTHER)
      println(mocks.userFixtures)
      mocks.userFixtures.find(_._2.email == testEmail) must beSome.which { case(uid, u) =>
        // Ensure we can log in and view our profile
        val index = route(fakeLoggedInHtmlRequest(u, GET,
          portalRoutes.profile().url)).get
        status(index) must equalTo(OK)
        contentAsString(index) must contain(testName)
      }
    }
  }
}