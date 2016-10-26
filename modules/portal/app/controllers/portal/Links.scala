package controllers.portal

import javax.inject.{Inject, Singleton}

import backend.rest.cypher.Cypher
import controllers.Components
import controllers.generic.Search
import controllers.portal.base.{Generic, PortalController}
import models.Link


@Singleton
case class Links @Inject()(
  components: Components,
  cypher: Cypher
) extends PortalController
  with Generic[Link]
  with Search
  with FacetConfig {

  def browse(id: String) = GetItemAction(id).apply { implicit request =>
    Ok(views.html.link.show(request.item))
  }
}
