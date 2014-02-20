package models.sql

import models.{Account,AccountDAO,HashedPassword}
import java.util.UUID

case class MockAccount(id: String, email: String, verified: Boolean = false, staff: Boolean = false) extends Account {
  def updatePassword(hashed: HashedPassword): Account = this
  def setPassword(data: HashedPassword): Account = this
  def verify(token: String): Account = this.copy(verified = true)
  def delete(): Boolean = {
    mocks.userFixtures -= id
    true
  }
  def createResetToken(token: UUID) = MockAccountDAO.tokens += ((token.toString, id, false))
  def createValidationToken(token: UUID) = MockAccountDAO.tokens += ((token.toString, id, true))
  def expireTokens() = {  // Bit gross this, dealing with Mutable state...
    val indicesToDelete = for {
      (t, i) <- MockAccountDAO.tokens.zipWithIndex if t._2 == id
    } yield i
    for (i <- (MockAccountDAO.tokens.size -1) to 0 by -1) if (indicesToDelete contains i) MockAccountDAO.tokens remove i
  }
}

/**
 * Find a user given their profile from the fixture store.
 */
object MockAccountDAO extends AccountDAO {
  val tokens = collection.mutable.ListBuffer.empty[(String,String,Boolean)]

  // Mock authentication
  override def authenticate(email: String, pw: String, verified: Boolean = true)
      = mocks.userFixtures.find(u => u._2.email == email && u._2.verified == verified).map(_._2)

  def findByProfileId(id: String, verified: Boolean = true): Option[Account]
        = mocks.userFixtures.get(id).filter(p => p.verified == verified)

  def findByEmail(email: String, verified: Boolean = true): Option[Account]
  = mocks.userFixtures.values.find(u => u.email == email && u.verified == verified)

  def create(id: String, email: String, verified: Boolean = false, staff: Boolean = false): Account = {
    val user = MockAccount(id, email, staff)
    mocks.userFixtures += id -> user
    user
  }

  def createWithPassword(id: String, email: String, verified: Boolean = false, staff: Boolean = false, hashed: HashedPassword): Account = {
    create(id, email, verified, staff)
  }

  def findByResetToken(token: String, isSignUp: Boolean = false): Option[Account]
      = MockAccountDAO.tokens.find(t => t._1 == token && t._3 == isSignUp).flatMap { case (t, p, s) =>
    findByProfileId(p)
  }
}