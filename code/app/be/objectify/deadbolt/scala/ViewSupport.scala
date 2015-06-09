package be.objectify.deadbolt.scala

import java.util.Optional
import java.util.concurrent.Callable
import java.util.regex.Pattern
import javax.inject.{Inject, Singleton}

import be.objectify.deadbolt.core.models.Subject
import be.objectify.deadbolt.core.{DeadboltAnalyzer, PatternType}
import play.api.{Configuration, Logger}
import play.api.mvc.Request
import play.cache.Cache

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Try, Success}

/**
 *
 * @author Steve Chaloner (steve@objectify.be)
 */
@Singleton
class ViewSupport @Inject() (config: Configuration,
                             analyzer: ScalaAnalyzer,
                             templateFailureListener: TemplateFailureListener) {

  val logger: Logger = Logger("deadbolt.template")

  val timeout: Long = config.getLong("deadbolt.scala.view-timeout").getOrElse(1000L)
  logger.info(s"Default timeout period for blocking views is [$timeout]ms")

  val defaultTimeout: () => Long = () => timeout

  /**
   * Used for subjectPresent and subjectNotPresent tags in the template.
   *
   * @param deadboltHandler application hook
   * @return true if the view can be accessed, otherwise false
   */
  def viewSubjectPresent(deadboltHandler: DeadboltHandler,
                         timeoutInMillis: Long,
                         request: Request[Any]): Boolean = {
    tryToComplete(deadboltHandler.getSubject(request).map((subjectOption: Option[Subject]) => {
      subjectOption match {
        case Some(subject) => true
        case None => false
      }
    }), timeoutInMillis)
  }

  /**
   * Used for restrict tags in the template.
   *
   * @param roles a list of String arrays.  Within an array, the roles are ANDed.  The arrays in the list are OR'd, so
   *              the first positive hit will allow access.
   * @param deadboltHandler application hook
   * @return true if the view can be accessed, otherwise false
   */
  def viewRestrict(roles: List[Array[String]],
                   deadboltHandler: DeadboltHandler,
                   timeoutInMillis: Long,
                   request: Request[Any]): Boolean = {
    def check(analyzer: DeadboltAnalyzer, subject: Optional[Subject], current: Array[String], remaining: List[Array[String]]): Boolean = {
        if (analyzer.checkRole(subject, current)) true
        else if (remaining.isEmpty) false
        else check(analyzer, subject, remaining.head, remaining.tail)
    }

    tryToComplete(deadboltHandler.getSubject(request).map((subjectOption: Option[Subject]) => {
      subjectOption match {
        case Some(subject) => check(new DeadboltAnalyzer(), Optional.ofNullable(subject), roles.head, roles.tail)
        case None => false
      }
    }), timeoutInMillis)
  }

  /**
   * Used for dynamic tags in the template.
   *
   * @param name the name of the resource
   * @param meta meta information on the resource
   * @return true if the view can be accessed, otherwise false
   */
  def viewDynamic(name: String,
                  meta: String,
                  deadboltHandler: DeadboltHandler,
                  timeoutInMillis: Long,
                  request: Request[Any]): Boolean = {
    tryToComplete(deadboltHandler.getDynamicResourceHandler(request).flatMap((drhOption: Option[DynamicResourceHandler]) => {
      drhOption match {
        case Some(drh) => drh.isAllowed(name, meta, deadboltHandler, request)
        case None => throw new RuntimeException("A dynamic resource is specified but no dynamic resource handler is provided")
      }
    }), timeoutInMillis)
  }

  /**
   *
   * @param value the value of the pattern, e.g. the regex
   * @param patternType the type of pattern
   * @param deadboltHandler the handler to use for this request
   * @param request the request
   * @return
   */
  def viewPattern(value: String,
                  patternType: PatternType,
                  deadboltHandler: DeadboltHandler,
                  timeoutInMillis: Long,
                  request: Request[Any]): Boolean = {
    def getPattern(patternValue: String): Pattern =
      Cache.getOrElse("Deadbolt." + patternValue,
                      new Callable[Pattern]{
                        def call() = Pattern.compile(patternValue)
                      },
                      0)

    tryToComplete(deadboltHandler.getSubject(request).map((subjectOption: Option[Subject]) => {
      subjectOption match {
        case None => false
        case Some(subject) => patternType match {
          case PatternType.EQUALITY => new DeadboltAnalyzer().checkPatternEquality(Optional.ofNullable(subject), Optional.ofNullable(value))
          case PatternType.REGEX => new DeadboltAnalyzer().checkRegexPattern(Optional.ofNullable(subject), Optional.ofNullable(getPattern(value)))
          case PatternType.CUSTOM =>
            val future: Future[Boolean] = deadboltHandler.getDynamicResourceHandler(request).map((drhOption: Option[DynamicResourceHandler]) => {
              drhOption match {
                case Some(drh) =>
                  Await.result(drh.checkPermission(value, deadboltHandler, request), timeoutInMillis milliseconds)
                case None =>
                  logger.error("A custom pattern is specified but no dynamic resource handler is provided")
                  throw new scala.RuntimeException("A custom pattern is specified but no dynamic resource handler is provided")
              }
            })
            Await.result(future, timeoutInMillis milliseconds)
          case _ => false
        }
      }
    }), timeoutInMillis)
  }

  /**
   * Attempts to complete the future within the given number of milliseconds.
   *
   * @param future the future to complete
   * @param timeoutInMillis the number of milliseconds to wait for a result
   * @return false if the future times out, otherwise the result of the future
   */
  private def tryToComplete(future: Future[Boolean], timeoutInMillis: Long): Boolean =
    Try(Await.result(future, timeoutInMillis milliseconds)) match {
      case Success(allowed) => allowed
      case Failure(ex) =>
        logger.error("Error when checking view constraint", ex)
        templateFailureListener.failure(s"Error when checking view constraint: [${ex.getMessage}]",
                                        timeout)
        false
    }
}