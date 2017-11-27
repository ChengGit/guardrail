package tests.generators.AkkaHttp

import _root_.io.swagger.parser.SwaggerParser
import cats.instances.all._
import com.twilio.swagger.codegen.generators.AkkaHttp
import com.twilio.swagger.codegen.{Context, Server, Servers, ServerGenerator, CodegenApplication, Target}
import org.scalatest.{FunSuite, Matchers}

class AkkaHttpServerTest extends FunSuite with Matchers {
  import scala.meta._

  val spec: String = s"""
    |swagger: '2.0'
    |host: petstore.swagger.io
    |paths:
    |  "/store/order/{order_id}":
    |    get:
    |      tags:
    |      - store
    |      x-scala-package: store
    |      operationId: getOrderById
    |      produces:
    |      - application/xml
    |      - application/json
    |      parameters:
    |      - name: order_id
    |        in: path
    |        required: true
    |        type: integer
    |        format: int64
    |      responses:
    |        '200':
    |          description: successful operation
    |          schema:
    |            "$$ref": "#/definitions/Order"
    |        '400':
    |          description: Invalid ID supplied
    |        '404':
    |          description: Order not found
    |  /foo/:
    |    get:
    |      x-scala-package: store
    |      operationId: getFoo
    |      responses:
    |        200:
    |          schema:
    |            type: boolean
    |  "/foo/{bar}":
    |    get:
    |      x-scala-package: baz
    |      x-scala-tracing-label: "completely-custom-label"
    |      operationId: getFoo
    |      parameters:
    |      - name: bar
    |        in: path
    |        required: true
    |        type: integer
    |        format: int64
    |      responses:
    |        200:
    |          schema:
    |            type: boolean
    |definitions:
    |  Order:
    |    type: object
    |    properties:
    |      id:
    |        type: integer
    |        format: int64
    |      petId:
    |        type: integer
    |        format: int64
    |      quantity:
    |        type: integer
    |        format: int32
    |      shipDate:
    |        type: string
    |        format: date-time
    |      status:
    |        type: string
    |        description: Order Status
    |        enum:
    |        - placed
    |        - approved
    |        - delivered
    |      complete:
    |        type: boolean
    |        default: false
    |    xml:
    |      name: Order
    |""".stripMargin

  test("Ensure routes are generated") {
    val swagger = new SwaggerParser().parse(spec)

    val Servers(output, _) = Target.unsafeExtract(ServerGenerator.fromSwagger[CodegenApplication](Context.empty, swagger).foldMap(AkkaHttp))
    val _ :: Server(pkg, extraImports, genHandler :: genResource :: Nil) :: Nil = output

    val handler = q"""
      trait StoreHandler {
        def getOrderById(respond: StoreResource.getOrderByIdResponse.type)(orderId: Long): scala.concurrent.Future[StoreResource.getOrderByIdResponse]
        def getFoo(respond: StoreResource.getFooResponse.type)(): scala.concurrent.Future[StoreResource.getFooResponse]
      }
    """
    val resource = q"""
      object StoreResource {
        import cats.syntax.either._
        def discardEntity(implicit mat: akka.stream.Materializer): Directive0 = extractRequest.flatMap { req =>
          req.discardEntityBytes().future
          Directive.Empty
        }
        def routes(handler: StoreHandler)(implicit mat: akka.stream.Materializer): Route = {
          (get & path("store" / "order" / LongNumber) & discardEntity) {
            orderId => complete(handler.getOrderById(getOrderByIdResponse)(orderId))
          } ~ (get & (pathPrefix("foo") & pathEndOrSingleSlash) & discardEntity) {
            complete(handler.getFoo(getFooResponse)())
          }
        }
        sealed abstract class getOrderByIdResponse(val statusCode: StatusCode)
        case class getOrderByIdResponseOK(value: Order) extends getOrderByIdResponse(StatusCodes.OK)
        case object getOrderByIdResponseBadRequest extends getOrderByIdResponse(StatusCodes.BadRequest)
        case object getOrderByIdResponseNotFound extends getOrderByIdResponse(StatusCodes.NotFound)
        object getOrderByIdResponse {
          implicit val getOrderByIdTRM: ToResponseMarshaller[getOrderByIdResponse] = Marshaller { implicit ec =>
            resp => getOrderByIdTR(resp)
          }
          implicit def getOrderByIdTR(value: getOrderByIdResponse)(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[List[Marshalling[HttpResponse]]] = value match {
            case r @ getOrderByIdResponseOK(value) =>
              Marshal(value).to[ResponseEntity].map {
                entity => Marshalling.Opaque(() => HttpResponse(r.statusCode, entity = entity)) :: Nil
              }
            case r: getOrderByIdResponseBadRequest.type =>
              scala.concurrent.Future.successful(Marshalling.Opaque(() => HttpResponse(r.statusCode)) :: Nil)
            case r: getOrderByIdResponseNotFound.type =>
              scala.concurrent.Future.successful(Marshalling.Opaque(() => HttpResponse(r.statusCode)) :: Nil)
          }
          def apply[T](value: T)(implicit ev: T => getOrderByIdResponse): getOrderByIdResponse = ev(value)
          implicit def OKEv(value: Order): getOrderByIdResponse = OK(value)
          def OK(value: Order): getOrderByIdResponse = getOrderByIdResponseOK(value)
          def BadRequest: getOrderByIdResponse = getOrderByIdResponseBadRequest
          def NotFound: getOrderByIdResponse = getOrderByIdResponseNotFound
        }
        sealed abstract class getFooResponse(val statusCode: StatusCode)
        case class getFooResponseOK(value: Boolean) extends getFooResponse(StatusCodes.OK)
        object getFooResponse {
          implicit val getFooTRM: ToResponseMarshaller[getFooResponse] = Marshaller { implicit ec =>
            resp => getFooTR(resp)
          }
          implicit def getFooTR(value: getFooResponse)(implicit ec: scala.concurrent.ExecutionContext): scala.concurrent.Future[List[Marshalling[HttpResponse]]] = value match {
            case r @ getFooResponseOK(value) =>
              Marshal(value).to[ResponseEntity].map {
                entity => Marshalling.Opaque(() => HttpResponse(r.statusCode, entity = entity)) :: Nil
              }
          }
          def apply[T](value: T)(implicit ev: T => getFooResponse): getFooResponse = ev(value)
          implicit def OKEv(value: Boolean): getFooResponse = OK(value)
          def OK(value: Boolean): getFooResponse = getFooResponseOK(value)
        }
      }
    """

    genHandler.structure shouldEqual handler.structure
    genResource.structure shouldEqual resource.structure
  }

  test("Ensure routes are generated with tracing") {
    val swagger = new SwaggerParser().parse(spec)

    val Servers(output, _) = Target.unsafeExtract(ServerGenerator.fromSwagger[CodegenApplication](Context.empty.copy(tracing=true), swagger).foldMap(AkkaHttp))
    val Server(pkg, extraImports, genHandler :: genResource :: Nil) :: _ :: Nil = output

    val handler = q"""
      trait BazHandler {
        def getFoo(bar: Long)(implicit traceBuilder: TraceBuilder[Either[Throwable, HttpResponse], IgnoredEntity]): scala.concurrent.Future[Boolean]
      }
    """
    val resource = q"""
      object BazResource {
        def discardEntity(implicit mat: akka.stream.Materializer): Directive0 = extractRequest.flatMap { req =>
          req.discardEntityBytes().future
          Directive.Empty
        }
        def routes(handler: BazHandler, trace: String => Directive1[TraceBuilder[Either[Throwable, HttpResponse], IgnoredEntity]])(implicit mat: akka.stream.Materializer): Route = {
          (get & path("foo" / LongNumber) & discardEntity & trace("completely-custom-label")) { (bar, traceBuilder) =>
            complete(handler.getFoo(bar)(traceBuilder))
          }
        }
      }
    """

//    TODO: Unsupported for akka-http
//    genHandler.structure should equal(handler.structure)
//    genResource.structure should equal(resource.structure)
  }
}
