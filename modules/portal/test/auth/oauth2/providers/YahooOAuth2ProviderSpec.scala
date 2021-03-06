package auth.oauth2.providers

import helpers.ResourceUtils
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.PlaySpecification

/**
   */
class YahooOAuth2ProviderSpec extends PlaySpecification with ResourceUtils {

  val testAccessData = resourceAsString("yahooAccessData.txt")
  val testUserData = resourceAsString("yahooUserData.txt")
  val config = new GuiceApplicationBuilder().build().configuration

  "Yahoo OAuth2 provider" should {
    "parse access data" in {
      YahooOAuth2Provider(config).parseAccessInfo(testAccessData) must beSome.which { d =>
        d.accessToken must equalTo("some-access-token")
        d.userGuid must equalTo(Some("123456789"))
        d.refreshToken must equalTo(Some("blah"))
        d.expiresIn must equalTo(Some(100))
        d.tokenType must equalTo(Some("bearer"))
      }
    }

     "parse user data" in {
       YahooOAuth2Provider(config).parseUserInfo(testUserData) must beSome.which { d =>
         d.name must equalTo("Any Name")
         d.email must equalTo("example1@example.com")
         d.providerId must equalTo("123456789")
       }
     }
   }
 }
