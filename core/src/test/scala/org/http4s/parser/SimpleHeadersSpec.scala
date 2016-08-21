package org.http4s
package parser

import java.net.InetAddress
import java.time.Instant

import cats.data.NonEmptyList
import org.http4s.batteries._
import org.http4s.headers._
import org.http4s.headers.ETag.EntityTag

class SimpleHeadersSpec extends Http4sSpec {

  "SimpleHeaders" should {

    "parse Connection" in {
      val header = Connection("closed".ci)
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)
    }

    "parse Content-Length" in {
      val header = `Content-Length`(4)
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val bad = Header(header.name.toString, "foo")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse Content-Encoding" in {
      val header = `Content-Encoding`(ContentCoding.`pack200-gzip`)
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)
    }

    "parse Content-Disposition" in {
      val header = `Content-Disposition`("foo", Map("one" -> "two", "three" -> "four"))
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val bad = Header(header.name.toString, "foo; bar")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse Date" in {       // mills are lost, get rid of them
      val header = Date(Instant.now).toRaw.parsed
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val bad = Header(header.name.toString, "foo")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse Host" in {
      val header1 = Host("foo", Some(5))
      HttpHeaderParser.parseHeader(header1.toRaw) must beXorRight(header1)

      val header2 = Host("foo", None)
      HttpHeaderParser.parseHeader(header2.toRaw) must beXorRight(header2)

      val bad = Header(header1.name.toString, "foo:bar")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse Last-Modified" in {
      val header = `Last-Modified`(Instant.now).toRaw.parsed
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val bad = Header(header.name.toString, "foo")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse If-Modified-Since" in {
      val header = `If-Modified-Since`(Instant.now).toRaw.parsed
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val bad = Header(header.name.toString, "foo")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }

    "parse ETag" in {
      ETag.EntityTag("hash", weak = true).toString() must_== "W/\"hash\""
      ETag.EntityTag("hash", weak = false).toString() must_== "\"hash\""

      val headers = Seq(ETag("hash"),
                        ETag("hash", true))

      foreach(headers){ header =>
        HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)
      }
    }

    "parse If-None-Match" in {
      val headers = Seq(`If-None-Match`(EntityTag("hash")),
                        `If-None-Match`(EntityTag("123-999")),
                        `If-None-Match`(EntityTag("123-999"), EntityTag("hash")),
                        `If-None-Match`(EntityTag("123-999", weak = true), EntityTag("hash")),
                        `If-None-Match`.`*`)
      foreach(headers){ header =>
        HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)
      }
    }

    "parse Transfer-Encoding" in {
      val header = `Transfer-Encoding`(TransferCoding.chunked)
      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val header2 = `Transfer-Encoding`(TransferCoding.compress)
      HttpHeaderParser.parseHeader(header2.toRaw) must beXorRight(header2)
    }

    "parse User-Agent" in {
      val header = `User-Agent`(AgentProduct("foo", Some("bar")), Seq(AgentComment("foo")))
      header.value must_== "foo/bar (foo)"

      HttpHeaderParser.parseHeader(header.toRaw) must beXorRight(header)

      val header2 = `User-Agent`(AgentProduct("foo"), Seq(AgentProduct("bar", Some("biz")), AgentComment("blah")))
      header2.value must_== "foo bar/biz (blah)"
      HttpHeaderParser.parseHeader(header2.toRaw) must beXorRight(header2)

      val headerstr = "Mozilla/5.0 (Android; Mobile; rv:30.0) Gecko/30.0 Firefox/30.0"
      HttpHeaderParser.parseHeader(Header.Raw(`User-Agent`.name, headerstr)) must beXorRight(
        `User-Agent`(AgentProduct("Mozilla", Some("5.0")), Seq(
            AgentComment("Android; Mobile; rv:30.0"),
            AgentProduct("Gecko", Some("30.0")),
            AgentProduct("Firefox", Some("30.0"))
          )
        )
      )
    }

    "parse X-Forward-Spec" in {
      val header1 = `X-Forwarded-For`(NonEmptyList(InetAddress.getLocalHost.some))
      HttpHeaderParser.parseHeader(header1.toRaw) must beXorRight(header1)

      val header2 = `X-Forwarded-For`(
        InetAddress.getLocalHost.some,
        InetAddress.getLoopbackAddress.some)
      HttpHeaderParser.parseHeader(header2.toRaw) must beXorRight(header2)

      val bad = Header(header1.name.toString, "foo")
      HttpHeaderParser.parseHeader(bad) must beXorLeft
    }
  }

}
