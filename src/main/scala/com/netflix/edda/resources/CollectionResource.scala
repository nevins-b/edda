/**
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.edda.resources

import collection.mutable.{Set => MSet}

import javax.servlet.http.HttpServletRequest
import javax.ws.rs.{GET, Path}
import javax.ws.rs.core.{Response, Context, MediaType}
import java.io.ByteArrayOutputStream

import com.netflix.edda.web.FieldSelectorParser
import com.netflix.edda.web.FieldSelectorExpr
import com.netflix.edda.web.KeySelectExpr
import com.netflix.edda.web.MatchAnyExpr
import com.netflix.edda.CollectionManager
import com.netflix.edda.Record
import com.netflix.edda.Utils
import com.netflix.edda.Queryable

import org.codehaus.jackson.JsonEncoding.UTF8
import org.codehaus.jackson.util.DefaultPrettyPrinter
import org.codehaus.jackson.map.MappingJsonFactory

import org.slf4j.LoggerFactory

import org.joda.time.DateTime

/** resource class to query collections registered with the CollectionManager */
@Path("/v2")
class CollectionResource {

  import Utils._
  import Queryable._

  private val logger = LoggerFactory.getLogger(getClass)

  private val factory = new MappingJsonFactory

  private val collectionPathRx = """^([^:;]+?)(?:/?)((?:;[^/;]*(?:=[^/;]+)?)*)""".r
  private val fieldSelectorsRx = """(.*?)(:\(.*\))?$""".r

  /** generate json error response */
  private def fail(message: String, status: Response.Status): Response = {
    val output = new ByteArrayOutputStream()
    val gen = factory.createJsonGenerator(output, UTF8)
    gen.writeStartObject()
    gen.writeNumberField("code", status.getStatusCode)
    gen.writeStringField("name", status.name)
    gen.writeStringField("message", message)
    gen.writeEndObject()
    gen.close()

    Response.
      status(status).
      `type`(MediaType.APPLICATION_JSON).
      entity(output.toString("UTF-8")).
      build()
  }

  private def unseen(id: String, seen: MSet[String]): Boolean = {
    val in = seen.contains(id)
    if (!in) seen += id
    !in
  }

  /** make record set unique based on record.id unless _all matrix argument used */
  private def unique(recs: Seq[Record], details: ReqDetails): Seq[Record] = {
    if (details.metaArgs.contains("_all")) return recs
    val seen: MSet[String] = MSet()
    recs.filter(r => unseen(r.id, seen))
  }

  /** translate matrix arguments into a query that can be passed to Collection.query */
  private def makeQuery(details: ReqDetails): Map[String, Any] = {
    var query: Map[String, Any] = Map()

    val prefix = if (details.meta) "" else "data."

    details.matrixArgs.foreach {
      case (k, null) => query += prefix + k -> Map("$nin" -> List(null, ""))
      case (k, "true") => query += prefix + k -> true
      case (k, "false") => query += prefix + k -> false
      case (k, v: String) if v.contains(',') =>
        query += prefix + k -> Map("$in" -> v.split(',').toList)
      case (k, v) => query += prefix + k -> v
    }

    if (details.metaArgs.contains("_at") || details.live) {
      query += "stime" -> Map("$lte" -> details.at)
      query += "$or" -> List(
        Map("ltime" -> null),
        Map("ltime" -> Map("$gte" -> details.at)))
    }

    if (details.updated) {
      val since = Map("stime" -> Map("$gte" -> details.since))
      val until = Map("stime" -> Map("$lte" -> details.until))

      // if we only want updated then we don't care if something
      // was alive after _since or before _until
      if (details.metaArgs.contains("_since") && details.metaArgs.contains("_until")) {
        query += "$and" -> List(since, until)
      } else if (details.metaArgs.contains("_since")) {
        query ++= since
      } else if (details.metaArgs.contains("_until")) {
        query ++= until
      }
    } else {
      val since =
        Map("$or" -> List(
          Map("stime" -> Map("$gte" -> details.since)),
          Map("ltime" -> null),
          Map("ltime" -> Map("$gt" -> details.since))))
      val until =
        Map("$or" -> List(
          Map("stime" -> Map("$lte" -> details.until)),
          Map("ltime" -> Map("$lt" -> details.until))))
      if (details.metaArgs.contains("_since") && details.metaArgs.contains("_until")) {
        query += "$and" -> List(since, until)
      } else if (details.metaArgs.contains("_since")) {
        query ++= since
      } else if (details.metaArgs.contains("_until")) {
        query ++= until
      }
    }
    query
  }

  /** companion object to handle matrix arguments */
  object ReqDetails {
    def apply(req: HttpServletRequest, id: String, matrixStr: String, exprStr: String): ReqDetails = {
      val args: Map[String, String] = matrixStr match {
        case m if m == null || m == "" => Map()
        // skip null/or empty matrix (ie ";;a=b"), also map value null to matrix args missing value
        case _ =>
          matrixStr.tail.split(";").map(_ split "=").collect({
            case Array(k, v) => (k, v)
            case Array(m) if m != "" => (m, null)
            case v: Array[String] if v.size > 2 => (v.head, v.tail.fold("")(_ + "=" + _))
          }).toMap
      }
      val expr = if (exprStr == null) MatchAnyExpr
      else FieldSelectorParser.parse(exprStr)

      val metaArgs = args.filter(t => t._1.head == '_')
      val matrixArgs = args.filter(t => t._1.head != '_')
      new ReqDetails(req, id, metaArgs, matrixArgs, expr)
    }
  }

  /** container for query and connection metadata */
  case class ReqDetails(req: HttpServletRequest, id: String, metaArgs: Map[String, String], matrixArgs: Map[String, String], expr: FieldSelectorExpr) {
    lazy val baos = {
      val v = new ByteArrayOutputStream()
      if (cb != null) {
        v.write((cb + "(").getBytes, 0, (cb + "(").size)
      }
      v
    }

    lazy val gen = {
      val jg = factory.createJsonGenerator(baos, UTF8)
      val dpp = new DefaultPrettyPrinter
      dpp.indentArraysWith(new DefaultPrettyPrinter.Lf2SpacesIndenter)
      if (pp) jg.setPrettyPrinter(dpp)
      jg
    }

    val path = req.getRequestURI.drop(req.getServletPath.length)

    /** only show documens valid at specific time */
    var at = timeArg(metaArgs.get("_at"))
    /** only show documents that were "alive" since a specific time */
    var since = timeArg(metaArgs.get("_since"))
    /** only show documents that were "alive" before a specific time */
    var until = timeArg(metaArgs.get("_until"))
    /** show the id,stime,ltime,mtime,ctime as well as the data, Note: changes the document root */
    val meta = boolArg(metaArgs.get("_meta"))
    /** dont just return the first record for a given id, return all revisions that match the query criteria */
    val all = boolArg(metaArgs.get("_all")) || (id != null && (metaArgs.contains("_since") || metaArgs.contains("_until")))
    /** print the unified diff of the objects fetched to show the changes over time */
    val diff: Option[String] = metaArgs.get("_diff")
    /** pretty-print the document. Dates are transformed to be readable and white-space is added */
    val pp = diff != None || boolArg(metaArgs.get("_pp"))
    /** use the datastore, not the in-memory cache */
    val live = boolArg(metaArgs.get("_live"))
    /** when used with _since or _until it will only show the records that were updated during that time,
      * instead of any document that was valid during that time. */
    val updated = boolArg(metaArgs.get("_updated"))
    /** used for json callback */
    val cb = if (metaArgs.contains("_callback")) metaArgs("_callback") else null
    /** are we trying to fetch a single record? */
    val single = id != null && !id.contains(',') && !all
    /** limit the number of records returned */
    val limit = if (single) 1 else intArg(metaArgs.get("_limit"))
    /** when fechting an index get (just resource ids) expand the names into the full resource */
    val expand = id != null || meta || all || boolArg(metaArgs.get("_expand"))

    // if user requested pretty-print then reformat
    // the date-times to be human readable, otherwise
    // use the pass-through formatter
    val formatter = if (pp) Utils.dateFormatter(_) else (x: Any) => x

    /** flag used to know if we are going to go to the DataStore (we only store "live" instances
      * in memory, so when time travelling we will likely need expired resources from the DataStore
      */
    var timeTravelling = all || metaArgs.contains("_at") || metaArgs.contains("_since") || live

    /** Set of field names (object keys) extraced from the FieldSelector expression */
    val fields: Set[String] = extractFields(expr) match {
      case Some(set) => Set("id", "stime") ++ (if (meta) set else set.map("data." + _))
      case None => Set.empty
    }

    /** map field selector expression to a set of the key names used */
    def extractFields(expr: FieldSelectorExpr): Option[Set[String]] = {
      expr match {
        case e: KeySelectExpr => {
          val results: Set[String] = e.keys.map(pair => pair._1 -> extractFields(pair._2)).flatMap(
            pair => pair match {
              case (prefix: String, Some(set)) => set.map(v => prefix + "." + v)
              case (prefix: String, None) => Set(prefix)
            }
          ).toSet

          Some(results)
        }
        case _ => None
      }
    }

    /** generate the http response with proper headers */
    def response(): Response = {
      val builder = Response.status(Response.Status.OK)

      gen.close()

      if (cb != null) {
        builder.`type`("application/javascript")
        // finish off the javascript callback
        baos.write(')')
      } else if (diff != None) {
        builder.`type`(MediaType.TEXT_PLAIN)
      } else builder.`type`(MediaType.APPLICATION_JSON)

      builder.entity(baos.toString("UTF-8"))
      builder.build()
    }

    private def boolArg(v: Option[String]): Boolean = {
      v match {
        case Some(t) if t == "1" => true
        case Some(null) => true
        case _ => false
      }
    }

    private def timeArg(v: Option[String]): DateTime = {
      v match {
        case Some(t) => new DateTime(t.toLong, org.joda.time.DateTimeZone.UTC)
        case None => DateTime.now()
      }
    }

    private def intArg(v: Option[String]): Int = {
      v match {
        case Some(null) => 0
        case Some(t) => t.toInt
        case None => 0
      }
    }
  }

  /** if _meta was used we need to print the whole document, otherwise
    * just print the document 'data' element
    */
  def writeMaybeMeta(r: Record, details: ReqDetails) {
    val data =
      if (details.meta)
        details.expr.select(r.toMap)
      else
        details.expr.select(r.data)

    if( data.isDefined ) {
        Utils.writeJson(details.gen, data.get, details.formatter)
    }
  }

  /** dispatch routine to handle collections.  If custome responses are require this class can be subclassed
    * and this method overloaded
    */
  def dispatch(collName: String, details: ReqDetails): Response = {
    collName match {
      case c if CollectionManager.names().contains(c) => handleBasicCollection(collName, details)
      case _ => fail("invalid collection: " + collName + "." + details.id, Response.Status.BAD_REQUEST)
    }
  }

  /** get query response an setup formatting for output */
  def handleBasicCollection(collName: String, details: ReqDetails): Response = {
    var response: Response = null
    Utils.SYNC {
      selectRecords(collName, details) {
        case Failure(error) => response = fail("query failed: " + error, Response.Status.INTERNAL_SERVER_ERROR)
        case Success(results) => {
          val recs = results.asInstanceOf[Seq[Record]]
          // if only single item requested either print it out, or see if it exists and is
          // expired in which case send GONE, otherwise send NOT_FOUND
          // if not single just open json array for output
          if (details.single) {
            if (recs.isEmpty) {
              if (!details.timeTravelling) {
                selectRecords(collName, details.copy(metaArgs = details.metaArgs ++ Map("_live" -> null, "_since" -> "0", "_limit" -> "1"))) {
                  case Failure(error) => response = fail("query failed: " + error, Response.Status.INTERNAL_SERVER_ERROR)
                  case Success(results) => {
                    val recs = results.asInstanceOf[Seq[Record]]
                    if (!recs.isEmpty) {
                      response = fail("record \"" + details.id + "\" is no longer valid in collection " + collName + ". Use _at, _since or _all arguments to fetch historical records.  Last seen at " + recs.head.stime.getMillis, Response.Status.GONE)
                      throw new java.lang.RuntimeException("return")
                    }
                    else {
                      response = fail("record \"" + details.id + "\" not found in collection " + collName, Response.Status.NOT_FOUND)
                      throw new java.lang.RuntimeException("return")
                    }
                  }
                }
              }
            }
          } else if (details.diff == None && !details.single) details.gen.writeStartArray()
          
          // handle diffing records if _diff is used or expand records if _expand is specified
          if (details.diff != None && details.id != null) {
            if (recs.size == 1) {
              response = fail("_diff requires at least 2 documents, only 1 found", Response.Status.BAD_REQUEST)
              throw new java.lang.RuntimeException("return")
            }
            
            val prefix = details.req.getContextPath + details.req.getServletPath + "/v2/";
            
            val diff = Utils.diffRecords(
              recs,
              details.diff.collect({
                case x: String => x.toInt
              }),
              prefix + collName.replace('.','/')
            )
            val bytes = diff.getBytes
            details.baos.write(bytes, 0, bytes.size)
            
          } else {
            details.expand match {
              case true => recs.foreach(writeMaybeMeta(_, details))
              case _ => recs.map(r => r.id).foreach(details.gen.writeString(_))
            }
          }
          if (details.diff == None && (!details.single)) details.gen.writeEndArray()
          response = details.response()
        }
      }
    }
    response
  }
      
  /** apply query to appropriate collection.  */
  def selectRecords(collName: String, details: ReqDetails)(events: EventHandlers = DefaultEventHandlers): Nothing = {
    val coll = CollectionManager.get(collName).get
    val query = if (details.id != null) {
      val idQuery = if (details.id.contains(',')) {
        Map("$in" -> details.id.split(',').toList)
      } else details.id
      makeQuery(details) + ("id" -> idQuery)
    } else makeQuery(details)
    logger.info(coll + " query: " + Utils.toJson(query))
    val keys: Set[String] = if (details.expand) details.fields else Set("id")
    // unique(coll.query(query, details.limit, details.timeTravelling, keys, replicaOk = true), details)
    coll.query(query, details.limit, details.timeTravelling, keys, replicaOk = true) {
      case Success(results: QueryResult) => {
        events(Success(unique(results.records, details)))
      }
      case msg @ Failure(error) => events(msg)
    }
  }

  /** handle HTTP request.  Map uri path to collection name, matrix arguments and field selectors */
  @GET
  @Path("{paths: .+}")
  def getCollection(@Context req: HttpServletRequest): Response = {
    // +4 for length("/v2/")
    val realPath = req.getRequestURI.drop(req.getContextPath.length + req.getServletPath.length + 4)
    val fieldSelectorsRx(path,exprStr) = realPath
    path match {
      case collectionPathRx(collPath, matrixStr) => {
        val name = collPath.replace('/', '.')
        val (collName, id) =
          if (CollectionManager.names().contains(name)) {
            (name, null)
          } else {
            val parts = collPath.split('/')
            (parts.init mkString ".", parts.last)
          }
        val details = ReqDetails(req, id, matrixStr, exprStr)
        if (details.id == null && details.diff != None) {
          fail("_diff argument requires use of resource id: " + req.getServletPath + collName + "/<id>", Response.Status.BAD_REQUEST)
        } else {
          dispatch(collName, details)
        }
      }
      case _ => fail("invalid path: " + path, Response.Status.BAD_REQUEST)
    }
  }
}
