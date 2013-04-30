package controllers.base

import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits._
import models.{Entity, UserProfile}
import solr.facet.{AppliedFacet, FacetClass}
import solr.{ItemPage, SolrDispatcher, SearchParams}
import defines.EntityType
import play.api.Play._
import solr.facet.AppliedFacet
import play.api.data.Form

/**
 * Controller trait searching via the Solr interface. Eventually
 * we should try and genericise this so it's not tied to Solr.
 */
trait EntitySearch extends Controller with AuthController with ControllerHelpers {

  lazy val solrDispatcher: solr.Dispatcher = current.plugin(classOf[solr.Dispatcher]).get

  /**
   * Inheriting controllers should override a list of facets that
   * are available for the given entity type.
   */
  val entityFacets: List[FacetClass]
  val searchEntities: List[EntityType.Value]

  def bindFacetsFromRequest(facetClasses: List[FacetClass])(implicit request: Request[AnyContent]): List[AppliedFacet] = {
    val qs = request.queryString
    facetClasses.flatMap { fc =>
      qs.get(fc.param).map { values =>
        AppliedFacet(fc.key, values.toList)
      }
    }
  }


  /**
   * Short cut search action without the ability to provide default filters
   * @param f
   * @return
   */
  def searchAction(f: solr.ItemPage[(Entity,String)] => SearchParams => List[AppliedFacet] => Option[UserProfile] => Request[AnyContent] => Result): Action[AnyContent] = {
    searchAction(Map.empty[String,Any])(f)
  }

  /**
   * Action that restricts the search to the inherited entity type
   * and applies
   * @param f
   * @return
   */
  def searchAction(filters: Map[String,Any] = Map.empty, defaultParams: Option[SearchParams] = None)(
      f: solr.ItemPage[(Entity,String)] => SearchParams => List[AppliedFacet] => Option[UserProfile] => Request[AnyContent] => Result): Action[AnyContent] = {
    userProfileAction { implicit userOpt => implicit request =>
      Secured {
        // Override the entity type with the controller entity type
        val sp = solr.SearchParams.form.bindFromRequest
            .value.getOrElse(SearchParams())
            .copy(entities = searchEntities)
            .setDefault(defaultParams)
        val facets: List[AppliedFacet] = bindFacetsFromRequest(entityFacets)
        AsyncRest {
          solrDispatcher.list(sp, facets, entityFacets, filters).map { resOrErr =>
            resOrErr.right.map { res =>
              val ids = res.items.map(_.id)
              val itemIds = res.items.map(_.itemId)
              AsyncRest {
                rest.SearchDAO(userOpt).list(itemIds).map { listOrErr =>
                  listOrErr.right.map { list =>
                    f(res.copy(items = list.zip(ids)))(sp)(facets)(userOpt)(request)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  def filterAction(entityType: EntityType.Value)(
      f: ItemPage[(String,String)] => Option[UserProfile] => Request[AnyContent] => Result): Action[AnyContent] = {
    userProfileAction { implicit userOpt => implicit request =>
      import play.api.data.Form
      import play.api.data.Forms._
      val (q, page, limit) = Form(tuple(
        "q" -> text,
        "page" -> optional(number),
        "limit" -> optional(number)
      )).bindFromRequest.value.getOrElse(("",None,None))

      AsyncRest {
        solrDispatcher.filter(q, entityType, page, limit).map { resOrErr =>
          resOrErr.right.map { res =>
            f(res)(userOpt)(request)
          }
        }
      }
    }
  }
}