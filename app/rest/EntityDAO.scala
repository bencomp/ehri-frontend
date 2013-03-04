package rest

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import play.api.libs.ws.{WS,Response => WSResponse}
import play.api.libs.json.{ JsArray, JsValue }
import defines.{EntityType,ContentType}
import models.Entity
import play.api.libs.json.Json
import models.UserProfile
import java.net.ConnectException
import models.base.Persistable
import play.api.Logger

/**
 * Class representing a page of data.
 *
 * @param total
 * @param offset
 * @param limit
 * @param items
 * @tparam T
 */
case class Page[+T](
  total: Long,
  offset: Int,
  limit: Int,
  items: Seq[T]
) extends utils.AbstractPage[T]

/**
 * Decode a JSON page representation.
 *
 */
object PageReads {

  import play.api.libs.json._
  import play.api.libs.json.util._
  import play.api.libs.json.Reads._
  import play.api.libs.functional.syntax._

  implicit def pageReads[T](implicit r: Reads[T]): Reads[Page[T]] = (
    (__ \ "total").read[Long] and
    (__ \ "offset").read[Int] and
    (__ \ "limit").read[Int] and
    (__ \ "values").lazyRead(list[T](r))
  )(Page[T] _)
}

object RestPageParams {
  final val ACCESSOR_PARAM = "accessibleTo"
  final val ORDER_PARAM = "sort"
  final val FILTER_PARAM = "filter"
  final val OFFSET_PARAM = "offset"
  final val LIMIT_PARAM = "limit"
  final val PAGE_PARAM = "page"

  final val DEFAULT_LIST_LIMIT = 20
}

/**
 * Class for handling page parameter data
 * @param page
 * @param limit
 * @param filters
 * @param sort
 */
case class RestPageParams(page: Option[Int] = None, limit: Option[Int] = None, filters: List[String] = Nil, sort: List[String] = Nil) {

  import RestPageParams._

  def mergeWith(default: RestPageParams): RestPageParams = RestPageParams(
    page = page.orElse(default.page),
    limit = limit.orElse(default.limit),
    filters = if (filters.isEmpty) default.filters else filters,
    sort = if (sort.isEmpty) default.sort else sort
  )

  override def toString = {
    val offset = (page.getOrElse(1) - 1) * limit.getOrElse(DEFAULT_LIST_LIMIT)
    "?" + List(
      s"${OFFSET_PARAM}=${offset}",
      s"${LIMIT_PARAM}=${limit.getOrElse(DEFAULT_LIST_LIMIT)}",
      multiParam(FILTER_PARAM, filters),
      multiParam(ORDER_PARAM, sort)
    ).filterNot(_.trim.isEmpty).mkString("&")
  }

  private def multiParam(key: String, values: List[String]) = values.map(s => s"${key}=${s}").mkString("&")

}

object EntityDAO {
  implicit val entityReads = Entity.entityReads

  def jsonToEntity(js: JsValue): Entity = {
    js.validate.fold(
      valid = { item =>
        new Entity(item.id, item.`type`, item.data, item.relationships)
      },
      invalid = { errors =>
        sys.error("Error getting item: %s\n%s".format(errors, js))
      }
    )
  }

  /**
   * Global listeners for CUD events
   */
  import scala.collection.mutable.ListBuffer
  private val onCreate: ListBuffer[Entity => Unit] = ListBuffer()
  private val onUpdate: ListBuffer[Entity => Unit] = ListBuffer()
  private val onDelete: ListBuffer[String => Unit] = ListBuffer()

  def addCreateHandler(f: Entity => Unit): Unit = onCreate += f
  def addUpdateHandler(f: Entity => Unit): Unit = onUpdate += f
  def addDeleteHandler(f: String => Unit): Unit = onDelete += f

  def handleCreate(e: Entity): Entity = {
    onCreate.foreach(f => f(e))
    e
  }
  def handleUpdate(e: Entity): Entity = {
    onUpdate.foreach(f => f(e))
    e
  }
  def handleDelete(id: String): Unit = onDelete.foreach(f => f(id))
}

/**
 * Data Access Object for fetching data about generic entity types.
 *
 * @param entityType
 * @param userProfile
 */
case class EntityDAO(entityType: EntityType.Type, userProfile: Option[UserProfile] = None) extends RestDAO {

  private final val LOG_MESSAGE_HEADER_NAME = "logMessage"

  import EntityDAO._
  import play.api.http.Status._

  def msgHeader(msg: Option[String]): Seq[(String,String)] = msg.map(m => Seq(LOG_MESSAGE_HEADER_NAME -> m)).getOrElse(Seq[(String,String)]())

  def requestUrl = "http://%s:%d/%s/%s".format(host, port, mount, entityType)

  def get(id: String): Future[Either[RestError, Entity]] = {
    Logger.logger.debug(enc(requestUrl, id))
    WS.url(enc(requestUrl, id)).withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkError(response).right.map(r => jsonToEntity(r.json))
    }
  }

  def get(key: String, value: String): Future[Either[RestError, Entity]] = {
    WS.url(requestUrl).withHeaders(authHeaders.toSeq: _*)
      .withQueryString("key" -> key, "value" -> value)
      .get.map { response =>
        checkError(response).right.map(r => jsonToEntity(r.json))
    }
  }

  def create(item: Persistable, accessors: List[String] = Nil,
      params: Map[String,Seq[String]] = Map(),
      logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    val qs = utils.joinQueryString(params)
    WS.url(enc(requestUrl, "?%s".format((accessors.map(a => s"${RestPageParams.ACCESSOR_PARAM}=${a}") ++ List(qs)).mkString("&"))))
        .withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .post(item.toJson).map { response =>
        checkError(response).right.map(r => EntityDAO.handleCreate(jsonToEntity(r.json)))
    }
  }

  def createDescription(id: String, descriptionType: EntityType.Value,
      item: Persistable,
      logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    WS.url(enc(requestUrl, id, descriptionType.toString))
      .withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .post(item.toJson).map { response =>
      checkError(response).right.map(r => EntityDAO.handleUpdate(jsonToEntity(r.json)))
    }
  }

  def createInContext(id: String, contentType: ContentType.Value,
      item: Persistable, accessors: List[String] = Nil,
      logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    println("LOG MESSAGE HEADER: " + msgHeader(logMsg))
    WS.url(enc(requestUrl, id, contentType, "?%s".format(accessors.map(a => s"${RestPageParams.ACCESSOR_PARAM}=${a}").mkString("&"))))
        .withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .post(item.toJson).map { response =>
        checkError(response).right.map(r => EntityDAO.handleCreate(jsonToEntity(r.json)))
    }
  }

  def update(id: String, item: Persistable, logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    Logger.logger.debug("Posting update: {}", item)
    WS.url(enc(requestUrl, id)).withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .put(item.toJson).map { response =>
        checkError(response).right.map(r => EntityDAO.handleUpdate(jsonToEntity(r.json)))
    }
  }

  def updateDescription(id: String, descriptionType: EntityType.Value,
      did: String, item: Persistable, logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    WS.url(enc(requestUrl, id, descriptionType.toString, did)).withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .put(item.toJson).map { response =>
      checkError(response).right.map(r => EntityDAO.handleUpdate(jsonToEntity(r.json)))
    }
  }

  def delete(id: String, logMsg: Option[String] = None): Future[Either[RestError, Boolean]] = {
    WS.url(enc(requestUrl, id)).withHeaders(authHeaders.toSeq: _*).delete.map { response =>
      // FIXME: Check actual error content...
      checkError(response).right.map(r => {
        EntityDAO.handleDelete(id)
        r.status == OK
      })
    }
  }

  def deleteDescription(id: String, descriptionType: EntityType.Value, did: String, logMsg: Option[String] = None): Future[Either[RestError, Entity]] = {
    WS.url(enc(requestUrl, id, descriptionType.toString, did)).withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
        .delete.map { response =>
      checkError(response).right.map(r => {
        // FIXME: This is unfortunate. Since descriptions are indexed as individual
        // items, deleting one means deleting it individually by ID
        EntityDAO.handleDelete(did)
        jsonToEntity(r.json)
      })
    }
  }

  def page(params: RestPageParams): Future[Either[RestError, Page[Entity]]] = {
    import Entity.entityReads
    implicit val entityPageReads = PageReads.pageReads
    WS.url(enc(requestUrl, "page", params.toString))
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkError(response).right.map { r =>
        r.json.validate[Page[models.Entity]].fold(
          valid = { page => page },
          invalid = { e =>
            sys.error("Unable to decode paginated list result: " + e.toString)
          }
        )
      }
    }
  }

  def pageChildren(id: String, params: RestPageParams): Future[Either[RestError, Page[Entity]]] = {
    import Entity.entityReads
    implicit val entityPageReads = PageReads.pageReads
    WS.url(enc(requestUrl, id, "page", params.toString))
      .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkError(response).right.map { r =>
        r.json.validate[Page[models.Entity]].fold(
          valid = { page => page },
          invalid = { e =>
            sys.error("Unable to decode paginated list result: " + e.toString)
          }
        )
      }
    }
  }

}