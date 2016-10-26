package controllers.portal

import javax.inject.{Inject, Singleton}

import backend.rest.cypher.Cypher
import controllers.Components
import controllers.generic.Search
import controllers.portal.base.{Generic, PortalController}
import defines.EntityType
import models.{Country, Repository}
import utils.search._

import scala.concurrent.Future.{successful => immediate}


@Singleton
case class Countries @Inject()(
  components: Components,
  cypher: Cypher
) extends PortalController
  with Generic[Country]
  with Search
  with FacetConfig {

  private val portalCountryRoutes = controllers.portal.routes.Countries

  def searchAll = UserBrowseAction.async { implicit request =>
    findType[Country](
      facetBuilder = countryFacets,
      defaultOrder = SearchOrder.Name
    ).map { result =>
      Ok(views.html.country.list(result, portalCountryRoutes.searchAll(), request.watched))
    }
  }

  def browse(id: String) = GetItemAction(id).async { implicit request =>
    if (isAjax) immediate(Ok(views.html.country.itemDetails(request.item, request.annotations, request.links, request.watched)))
    else {
      findType[Repository](
        filters = Map(SearchConstants.COUNTRY_CODE -> request.item.id),
        facetBuilder = localRepoFacets,
        defaultOrder = SearchOrder.Name
      ).map { result =>
        Ok(views.html.country.show(request.item, result, request.annotations,
          request.links, portalCountryRoutes.search(id), request.watched))
      }
    }
  }

  def search(id: String) = GetItemAction(id).async {  implicit request =>
    findType[Repository](
      filters = Map(SearchConstants.COUNTRY_CODE -> request.item.id),
      facetBuilder = localRepoFacets,
      defaultOrder = SearchOrder.Name
    ).map { result =>
      if (isAjax) Ok(views.html.country.childItemSearch(request.item, result,
        portalCountryRoutes.search(id), request.watched))
      else Ok(views.html.country.search(request.item, result,
        portalCountryRoutes.search(id), request.watched))
    }
  }

  def export(id: String, asFile: Boolean) = OptionalUserAction.async { implicit request =>
    exportXml(EntityType.Country, id, Seq("eag"), asFile)
  }
}
