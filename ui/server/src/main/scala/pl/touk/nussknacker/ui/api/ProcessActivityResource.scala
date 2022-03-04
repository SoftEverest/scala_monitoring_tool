package pl.touk.nussknacker.ui.api

import java.io.File
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.ContentTypeResolver
import akka.http.scaladsl.settings.RoutingSettings
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import pl.touk.nussknacker.engine.api.process.VersionId
import pl.touk.nussknacker.ui.process.repository.{FetchingProcessRepository, ProcessActivityRepository}
import pl.touk.nussknacker.ui.util.{AkkaHttpResponse, CatsSyntax, EspPathMatchers}
import pl.touk.nussknacker.ui.security.api.LoggedUser

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ProcessActivityResource(processActivityRepository: ProcessActivityRepository,
                              val processRepository: FetchingProcessRepository[Future],
                              val processAuthorizer: AuthorizeProcess)
                             (implicit val ec: ExecutionContext, mat: Materializer)
  extends Directives with FailFastCirceSupport with RouteWithUser with ProcessDirectives with AuthorizeProcessDirectives with EspPathMatchers {

  private implicit final val plainBytes: FromEntityUnmarshaller[Array[Byte]] =
    Unmarshaller.byteArrayUnmarshaller

  def securedRoute(implicit user: LoggedUser) : Route = {
    path("processes" / Segment / "activity") { processName =>
      (get & processId(processName)) { processId =>
        complete {
          processActivityRepository.findActivity(processId)
        }
      }
    } ~ path("processes" / Segment / VersionIdSegment / "activity" / "comments") { (processName, versionId) =>
      (post & processId(processName)) { processId =>
        canWrite(processId) {
          entity(as[Array[Byte]]) { commentBytes =>
            complete {
              val comment = new String(commentBytes, java.nio.charset.Charset.forName("UTF-8"))
              processActivityRepository.addComment(processId.id, versionId, comment)
            }
          }
        }
      }
    } ~ path("processes" / Segment / "activity" / "comments" / LongNumber) { (processName, commentId) =>
      (delete & processId(processName)) { processId =>
        canWrite(processId) {
          complete {
            processActivityRepository.deleteComment(commentId)
          }
        }
      }
    }
  }
}

class AttachmentResources(attachmentService: ProcessAttachmentService,
                          val processRepository: FetchingProcessRepository[Future],
                          val processAuthorizer: AuthorizeProcess)
                         (implicit val ec: ExecutionContext, mat: Materializer)
  extends Directives with FailFastCirceSupport with RouteWithUser with ProcessDirectives with AuthorizeProcessDirectives with EspPathMatchers {

  def securedRoute(implicit user: LoggedUser) : Route = {
    path("processes" / Segment / VersionIdSegment / "activity" / "attachments") { (processName, versionId) =>
      (post & processId(processName)) { processId =>
        canWrite(processId) {
          fileUpload("attachment") { case (metadata, byteSource) =>
            complete {
              attachmentService.saveAttachment(processId.id, versionId, metadata.fileName, byteSource)
            }
          }
        }
      }
    } ~ path("processes" / Segment / VersionIdSegment / "activity" / "attachments" / LongNumber) { (processName, versionId, attachmentId) => //FIXME: are we sure about pass here versionId?
      (get & processId(processName)) { _ =>
        extractSettings { settings =>
          complete {
            val attachmentFile = attachmentService.readAttachment(attachmentId)
            CatsSyntax.futureOpt.map(attachmentFile) { case (file, attachment) =>
              AkkaHttpResponse.asFile(fileEntity(settings, file), attachment.fileName)
            }
          }
        }
      }
    }
  }

  private def fileEntity(settings: RoutingSettings, file: File): ResponseEntity = {
    val contentType = ContentTypeResolver.Default(file.getName)
    HttpEntity.Default(contentType, file.length, FileIO.fromPath(file.toPath))
  }
}
