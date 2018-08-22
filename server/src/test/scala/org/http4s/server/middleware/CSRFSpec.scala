package org.http4s.server.middleware

import java.time.{Clock, Instant, ZoneId}
import java.util.concurrent.atomic.AtomicLong
import cats.data.OptionT
import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.headers.Referer

class CSRFSpec extends Http4sSpec {

  /** Create a clock that always ticks forward once per millis() call.
    *
    * This is to emulate scenarios where we want to mitigate BREACH where, in
    * a real world service, a huge number of requests wouldn't be processed
    * before the clock at least traverses a millisecond.
    */
  val testClock: Clock = new Clock { self =>
    private lazy val clockTick = new AtomicLong(Instant.now().toEpochMilli)

    def withZone(zone: ZoneId): Clock = this

    def getZone: ZoneId = ZoneId.systemDefault()

    def instant(): Instant =
      Instant.ofEpochMilli(clockTick.incrementAndGet())
  }

  val dummyRoutes: HttpApp[IO] = HttpRoutes
    .of[IO] {
      case GET -> Root =>
        Ok()
      case POST -> Root =>
        Ok()
    }
    .orNotFound

  val dummyRequest: Request[IO] =
    Request[IO](method = Method.POST).putHeaders(Header("Origin", "http://localhost"))
  val passThroughRequest: Request[IO] = Request[IO]()

  val csrf: CSRF[IO, IO] = CSRF
    .withGeneratedKey[IO, IO](
      clock = testClock,
      headerCheck = CSRF.defaultOriginCheck[IO](_, "localhost", Uri.Scheme.http, None))
    .unsafeRunSync()

  "CSRF" should {
    "pass through and embed a new token for a safe, fresh request if set" in {
      val response = csrf.validate()(dummyRoutes)(passThroughRequest).unsafeRunSync()

      response.status must_== Status.Ok
      response.cookies.exists(_.name == csrf.cookieName) must_== true
    }

    "pass through and not embed a new token for a safe, fresh request" in {
      val csrfNoEmbed: CSRF[IO, IO] = CSRF
        .withGeneratedKey[IO, IO](
          clock = testClock,
          headerCheck = CSRF.defaultOriginCheck[IO](_, "localhost", Uri.Scheme.http, None),
          createIfNotFound = false)
        .unsafeRunSync()

      val response = csrfNoEmbed.validate()(dummyRoutes)(passThroughRequest).unsafeRunSync()

      response.status must_== Status.Ok
      response.cookies.exists(_.name == csrf.cookieName) must_== false
    }

    "Extract a valid token, with a slightly changed nonce, if present" in {
      val program = for {
        t <- csrf.generateToken
        req = dummyRequest.addCookie(RequestCookie(csrf.cookieName, t))
        newToken <- csrf.refreshedToken(req).value
      } yield newToken

      val newToken = program.unsafeRunSync()
      newToken.isDefined must_== true
      //Checks whether it was properly signed
      csrf.extractRaw(newToken.get).value.unsafeRunSync().isDefined must_== true
    }

    "pass a request with valid origin a request without any origin, even with a token" in {
      val program: IO[Response[IO]] = for {
        token <- csrf.generateToken
        req = Request[IO](POST).addCookie(RequestCookie(csrf.cookieName, token))
        v <- csrf.checkCSRFDefault(req, dummyRoutes.run(req))
      } yield v

      program.unsafeRunSync().status must_== Status.Forbidden
    }

    "fail a request with an invalid cookie, despite it being a safe method" in {
      val response =
        csrf
          .validate()(dummyRoutes)(
            passThroughRequest.addCookie(RequestCookie(csrf.cookieName, "MOOSE")))
          .unsafeRunSync()

      response.status must_== Status.Forbidden // Must fail
      !response.cookies.exists(_.name == csrf.cookieName) must_== true //Must not embed a new token
    }

    "pass through and embed a slightly different token for a safe request" in {
      val (oldToken, oldRaw, response, newToken, newRaw) =
        (for {
          oldToken <- csrf.generateToken
          raw1 <- csrf.extractRaw(oldToken).getOrElse("Invalid1")
          response <- csrf
            .validate()(dummyRoutes)(passThroughRequest.addCookie(csrf.cookieName, oldToken))
          newCookie <- IO.pure(
            response.cookies
              .find(_.name == csrf.cookieName)
              .getOrElse(ResponseCookie("invalid", "Invalid2")))
          raw2 <- csrf.extractRaw(newCookie.content).getOrElse("Invalid1")
        } yield (oldToken, raw1, response, newCookie, raw2)).unsafeRunSync()

      response.status must_== Status.Ok
      oldToken must_!= newToken.content
      oldRaw must_== newRaw
    }

    "not validate different tokens" in {
      (for {
        t1 <- csrf.generateToken
        t2 <- csrf.generateToken
      } yield CSRF.isEqual(t1, t2)).unsafeRunSync() must_== false
    }

    "validate for the correct csrf token and origin" in {
      (for {
        token <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          dummyRequest
            .putHeaders(Header(csrf.headerName, token))
            .addCookie(csrf.cookieName, token)
        )
      } yield res).unsafeRunSync().status must_== Status.Ok
    }

    "validate for the correct csrf token, no origin but with a referrer" in {
      (for {
        token <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          Request[IO](POST)
            .putHeaders(
              Header(csrf.headerName, token),
              Referer(Uri.unsafeFromString("http://localhost/lol")))
            .addCookie(csrf.cookieName, token)
        )
      } yield res).unsafeRunSync().status must_== Status.Ok
    }

    "fail a request without any origin, even with a token" in {
      val program: IO[Response[IO]] = for {
        token <- csrf.generateToken
        req = Request[IO](POST).addCookie(RequestCookie(csrf.cookieName, token))
        v <- csrf.checkCSRFDefault(req, dummyRoutes.run(req))
      } yield v

      program.unsafeRunSync().status must_== Status.Forbidden
    }

    "fail a request with an incorrect origin and incorrect referrer, even with a token" in {
      (for {
        token <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          Request[IO](POST)
            .putHeaders(
              Header(csrf.headerName, token),
              Header("Origin", "http://example.com"),
              Referer(Uri.unsafeFromString("http://example.com/lol")))
            .addCookie(csrf.cookieName, token)
        )
      } yield res).unsafeRunSync().status must_== Status.Forbidden
    }

    "not validate if token is missing in both" in {
      csrf
        .validate()(dummyRoutes)(dummyRequest)
        .unsafeRunSync()
        .status must_== Status.Forbidden
    }

    "not validate for token missing in header" in {
      (for {
        token <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          dummyRequest.addCookie(csrf.cookieName, token)
        )
      } yield res).unsafeRunSync().status must_== Status.Forbidden
    }

    "not validate for token missing in cookie" in {
      (for {
        token <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          dummyRequest.putHeaders(Header(csrf.headerName, token))
        )
      } yield res).unsafeRunSync().status must_== Status.Forbidden
    }

    "not validate for different tokens" in {
      (for {
        token1 <- csrf.generateToken
        token2 <- csrf.generateToken
        res <- csrf.validate()(dummyRoutes)(
          dummyRequest
            .withHeaders(Headers(Header(csrf.headerName, token1)))
            .addCookie(csrf.cookieName, token2)
        )
      } yield res).unsafeRunSync().status must_== Status.Forbidden
    }

    "not return the same token to mitigate BREACH" in {
      (for {
        token <- OptionT.liftF(csrf.generateToken)
        raw1 <- csrf.extractRaw(token)
        res <- OptionT.liftF(
          csrf.validate()(dummyRoutes)(
            dummyRequest
              .putHeaders(Header(csrf.headerName, token))
              .addCookie(csrf.cookieName, token)
          ))
        r <- OptionT.fromOption[IO](res.cookies.find(_.name == csrf.cookieName).map(_.content))
        raw2 <- csrf.extractRaw(r)
      } yield r != token && raw1 == raw2).value.unsafeRunSync() must beSome(true)
    }

    "not return a token for a failed CSRF check" in {
      val response = (for {
        token1 <- OptionT.liftF(csrf.generateToken)
        token2 <- OptionT.liftF(csrf.generateToken)
        res <- OptionT.liftF(
          csrf.validate()(dummyRoutes)(
            dummyRequest
              .putHeaders(Header(csrf.headerName, token1))
              .addCookie(csrf.cookieName, token2)
          ))
      } yield res).getOrElse(Response.notFound).unsafeRunSync()

      response.status must_== Status.Forbidden
      !response.cookies.exists(_.name == csrf.cookieName) must_== true
    }
  }

}
