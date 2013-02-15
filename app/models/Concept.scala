package models

import base._
import play.api.data._
import play.api.data.Forms._

import models.base.Persistable
import models.base.DescribedEntity
import defines.EntityType
import play.api.libs.json.Json
import defines.EnumWriter.enumWrites

object ConceptType extends Enumeration {
  type Type = Value

}

object ConceptF {
  val LANGUAGE = "languageCode"
  val PREFLABEL = "prefLabel"
  val ALTLABEL = "altLabel"
  val DEFINITION = "definition"
  val SCOPENOTE = "scopeNote"

}

case class ConceptF(
  val id: Option[String],
  val identifer: String,
  @Annotations.Relation(DescribedEntity.DESCRIBES_REL) val descriptions: List[ConceptDescriptionF] = Nil
) extends Persistable {
  val isA = EntityType.Concept

  def toJson = {
    import ConceptF._

    Json.obj(
      Entity.ID -> id,
      Entity.TYPE -> isA,
      Entity.DATA -> Json.obj(
        Entity.IDENTIFIER -> identifer
      ),
      Entity.RELATIONSHIPS -> Json.obj(
        DescribedEntity.DESCRIBES_REL -> Json.toJson(descriptions.map(_.toJson).toSeq)
      )
    )
  }
}

case class ConceptDescriptionF(
  val id: Option[String],
  val languageCode: String,
  val prefLabel: String,
  val altLabels: Option[List[String]] = None,
  val definition: Option[String] = None,
  val scopeNote: Option[String] = None
) extends Persistable {
  val isA = EntityType.ConceptDescription

  def toJson = {
    import ConceptF._

    Json.obj(
      Entity.ID -> id,
      Entity.TYPE -> isA,
      Entity.DATA -> Json.obj(
        LANGUAGE -> languageCode,
        PREFLABEL -> prefLabel,
        ALTLABEL -> altLabels,
        DEFINITION -> definition,
        SCOPENOTE -> scopeNote
      )
    )
  }
}


object ConceptDescriptionForm {

  import ConceptF._

  val form = Form(mapping(
    Entity.ID -> optional(nonEmptyText),
    LANGUAGE -> nonEmptyText,
    PREFLABEL -> nonEmptyText,
    ALTLABEL -> optional(list(nonEmptyText)),
    DEFINITION -> optional(nonEmptyText),
    SCOPENOTE -> optional(nonEmptyText)
  )(ConceptDescriptionF.apply)(ConceptDescriptionF.unapply))
}


object ConceptForm {
  val form = Form(
    mapping(
      Entity.ID -> optional(nonEmptyText),
      Entity.IDENTIFIER -> nonEmptyText,
      "descriptions" -> list(ConceptDescriptionForm.form.mapping)
    )(ConceptF.apply)(ConceptF.unapply)
  )
}

object Concept {
  final val VOCAB_REL = "inCvoc"
  final val NT_REL = "narrower"
}

/**
 * User: mike
 * Date: 24/01/13
 */
case class Concept(e: Entity)
  extends NamedEntity
  with AccessibleEntity
  with AnnotatableEntity
  with DescribedEntity
  with HierarchicalEntity[Concept]
  with Formable[ConceptF] {

  val hierarchyRelationName = Concept.NT_REL

  override val nameProperty = ConceptF.PREFLABEL

  override def descriptions: List[ConceptDescription] = e.relations(DescribedEntity.DESCRIBES_REL).map(ConceptDescription(_))
  val vocabulary: Option[Vocabulary] = e.relations(Concept.VOCAB_REL).headOption.map(Vocabulary(_))
  val broaderTerms: List[Concept] = e.relations(Concept.NT_REL).map(Concept(_))

  def formable: ConceptF = new ConceptF(Some(e.id), identifier, descriptions.map(_.formable))

  // Because we (currently) have no 'name' property on Concept, get the first available preflabel
  override def toString = descriptions.headOption.flatMap(_.stringProperty(ConceptF.PREFLABEL)).getOrElse(identifier)
}

case class ConceptDescription(val e: Entity)
  extends Description
  with Formable[ConceptDescriptionF] {

  import ConceptF._

  def formable: ConceptDescriptionF = new ConceptDescriptionF(
    id = Some(e.id),
    languageCode = e.stringProperty(LANGUAGE).getOrElse(sys.error(s"No language code found on concept data: ${e}")),
    prefLabel = e.stringProperty(PREFLABEL).getOrElse(sys.error(s"No prefLabel found on concept data: ${e}")),
    altLabels = e.listProperty(ALTLABEL),
    definition = e.stringProperty(DEFINITION),
    scopeNote = e.stringProperty(SCOPENOTE)
  )
}