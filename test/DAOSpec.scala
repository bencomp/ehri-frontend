package test

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import org.neo4j.server.configuration.ThirdPartyJaxRsPackage
import eu.ehri.extension.test.utils.ServerRunner
import eu.ehri.extension.AbstractAccessibleEntityResource
import com.typesafe.config.ConfigFactory
import rest._
import play.api.libs.concurrent.Execution.Implicits._
import models.Entity
import play.api.libs.json.JsString
import org.specs2.specification.BeforeExample
import defines.{EntityType,ContentType,PermissionType}
import models.UserProfile
import models.{DocumentaryUnit,DocumentaryUnitF}
import models.forms.{UserProfileF}
import rest.RestPageParams

class DAOSpec extends Specification with BeforeExample {
  sequential

  val testPort = 7575
  val config = Map("neo4j.server.port" -> testPort)
  val userProfile = UserProfile(Entity.fromString("mike", EntityType.UserProfile))
  val entityType = EntityType.UserProfile

  val runner: ServerRunner = new ServerRunner(classOf[DAOSpec].getName, testPort)
  runner.getConfigurator
    .getThirdpartyJaxRsClasses()
    .add(new ThirdPartyJaxRsPackage(
      classOf[AbstractAccessibleEntityResource[_]].getPackage.getName, "/ehri"));
  runner.start

  def before = {
    runner.tearDown
    runner.setUp
  }

  "EntityDAO" should {
    "get an item by id" in {
      running(FakeApplication(additionalConfiguration = config)) {
        await(EntityDAO(entityType, Some(userProfile)).get(userProfile.identifier)) must beRight
      }
    }

    "create an item" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfileF(id=None, identifier = "foobar", name = "Foobar")
        await(EntityDAO(entityType, Some(userProfile)).create(user)) must beRight
      }
    }

    "create an item in (agent) context" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val doc = DocumentaryUnitF(id = None, identifier = "foobar", name = "Foobar")
        val r = await(EntityDAO(EntityType.Agent, Some(userProfile)).createInContext("r1", ContentType.DocumentaryUnit, doc))
        r must beRight
        DocumentaryUnit(r.right.get).holder must beSome
        DocumentaryUnit(r.right.get).holder.get.identifier must equalTo("r1")
      }
    }

    "create an item in (doc) context" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val doc = DocumentaryUnitF(id = None, identifier = "foobar", name = "Foobar")
        val r = await(EntityDAO(EntityType.DocumentaryUnit, Some(userProfile)).createInContext("c1", ContentType.DocumentaryUnit, doc))
        r must beRight
        DocumentaryUnit(r.right.get).parent must beSome
        DocumentaryUnit(r.right.get).parent.get.identifier must equalTo("c1")
      }
    }
        
    "update an item by id" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfileF(id=None, identifier = "foobar", name = "Foobar")
        val entity = await(EntityDAO(entityType, Some(userProfile)).create(user)).right.get
        val udata = UserProfile(entity).to.copy(location = Some("London"))
        val res = await(EntityDAO(entityType, Some(userProfile)).update(entity.id, udata))
        res must beRight
      }
    }

    "error when creating an item with a non-unique id" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfileF(id=None, identifier = "foobar", name = "Foobar")
        await(EntityDAO(entityType, Some(userProfile)).create(user))
        val err = await(EntityDAO(entityType, Some(userProfile)).create(user))
        err must beLeft
        err.left.get must beAnInstanceOf[ValidationError]
      }
    }

    "error when fetching a non-existing item" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val err = await(EntityDAO(entityType, Some(userProfile)).get("blibidyblob"))
        err must beLeft
        err.left.get must beAnInstanceOf[ItemNotFound]

      }
    }

    "delete an item by id" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfileF(id=None, identifier = "foobar", name = "Foobar")
        val entity = await(EntityDAO(entityType, Some(userProfile)).create(user)).right.get
        await(EntityDAO(entityType, Some(userProfile)).delete(entity.id)) must beRight
      }
    }

    "page items" in {
      running(FakeApplication(additionalConfiguration = config)) {
        await(EntityDAO(entityType, Some(userProfile)).page(RestPageParams())) must beRight
      }
    }    
  }

  "PermissionDAO" should {
    "be able to fetch user's own permissions" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val perms = await(PermissionDAO[UserProfile](Some(userProfile)).get)
        perms must beRight
        perms.right.get.get(ContentType.DocumentaryUnit, PermissionType.Create) must beSome
      }
    }
    
    "be able to set a user's permissions" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfile(Entity.fromString("reto", EntityType.UserProfile))
        val data = Map("agent" -> List("create", "update", "delete"), "documentaryUnit" -> List("create", "update","delete"))
        val perms = await(PermissionDAO(Some(userProfile)).get(user))
        perms.right.get.get(ContentType.DocumentaryUnit, PermissionType.Create) must beNone
        perms.right.get.get(ContentType.DocumentaryUnit, PermissionType.Update) must beNone
        perms.right.get.get(ContentType.Agent, PermissionType.Create) must beNone
        perms.right.get.get(ContentType.Agent, PermissionType.Update) must beNone
        val permset = await(PermissionDAO(Some(userProfile)).set(user, data))
        permset must beRight
        val newperms = permset.right.get
        newperms.get(ContentType.DocumentaryUnit, PermissionType.Create) must beSome
        newperms.get(ContentType.DocumentaryUnit, PermissionType.Update) must beSome
        newperms.get(ContentType.Agent, PermissionType.Create) must beSome
        newperms.get(ContentType.Agent, PermissionType.Update) must beSome
      }
    }

    "be able to set a user's permissions within a scope" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfile(Entity.fromString("reto", EntityType.UserProfile))
        val data = Map(ContentType.DocumentaryUnit.toString -> List("create", "update","delete"))
        val perms = await(PermissionDAO(Some(userProfile)).get(user))
        perms.right.get.get(ContentType.DocumentaryUnit, PermissionType.Create) must beNone
        perms.right.get.get(ContentType.DocumentaryUnit, PermissionType.Update) must beNone
        perms.right.get.get(ContentType.Agent, PermissionType.Create) must beNone
        perms.right.get.get(ContentType.Agent, PermissionType.Update) must beNone
        await(PermissionDAO(Some(userProfile)).setScope(user, "r1", data))
        // Since c1 is held by r1, we should now have permissions to update and delete c1.
        val permset = await(PermissionDAO(Some(userProfile)).getItem(user, ContentType.DocumentaryUnit, "c1"))
        permset must beRight
        val newItemPerms = permset.right.get
        newItemPerms.get(PermissionType.Create) must beSome
        newItemPerms.get(PermissionType.Update) must beSome
        newItemPerms.get(PermissionType.Delete) must beSome
      }
    }

    "be able to set a user's permissions for an item" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val user = UserProfile(Entity.fromString("reto", EntityType.UserProfile))
        // NB: Currently, there's already a test permission grant for Reto-create on c1...
        val data = List("update","delete")
        val perms = await(PermissionDAO(Some(userProfile)).getItem(user, ContentType.DocumentaryUnit, "c1"))
        perms.right.get.get(PermissionType.Update) must beNone
        perms.right.get.get(PermissionType.Delete) must beNone
        val permReq = await(PermissionDAO(Some(userProfile)).setItem(user, ContentType.DocumentaryUnit, "c1", data))
        permReq must beRight
        val newItemPerms = permReq.right.get
        newItemPerms.get(PermissionType.Update) must beSome
        newItemPerms.get(PermissionType.Delete) must beSome
      }
    }
  }
  
  "VisibilityDAO" should {
    "set visibility correctly" in {
      running(FakeApplication(additionalConfiguration = config)) {
        
        // First, fetch an object and assert its accessibility
        val c1a = await(EntityDAO(EntityType.DocumentaryUnit, Some(userProfile)).get("c1")).right.get
        DocumentaryUnit(c1a).accessors.map(_.identifier) must haveTheSameElementsAs(List("admin", "mike"))
        
        val set = await(VisibilityDAO(Some(userProfile)).set(c1a.id, List("mike", "reto", "admin")))
        set must beRight
        val c1b = await(EntityDAO(EntityType.DocumentaryUnit, Some(userProfile)).get("c1")).right.get
        DocumentaryUnit(c1b).accessors.map(_.identifier) must haveTheSameElementsAs(List("admin", "mike", "reto"))
      }
    }
  }

/*  "CypherDAO" should {
    "get a JsValue for a graph item" in {
      running(FakeApplication(additionalConfiguration = config)) {
        val dao = rest.cypher.CypherDAO()
        
        //println(await(WS.url("http://localhost:7575/db/data/").get).body)
        
        // FIXME: Cypher seems
        val res = await(dao.cypher("START n = node:userProfile('identifier:*') RETURN n.identifier, n.name"))
        res.right.map { r =>
          println(r)
        }
        res.left.map { err =>
          println(err + ": " + err.message + " - " + err.stacktrace)
        }
        res must beRight
        true must beTrue
      }
    }
  }
*/  
  step {
    runner.stop
  }
}
