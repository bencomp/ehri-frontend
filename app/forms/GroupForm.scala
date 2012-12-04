package forms

import play.api.data._
import play.api.data.Forms._

import models._

object GroupForm {

  val form = Form(
      mapping(
    		Entity.ID -> optional(text),
    		Entity.IDENTIFIER -> nonEmptyText,
    		"name" -> nonEmptyText
      )(Group.apply)(Group.unapply)
  ) 
}
