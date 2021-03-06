package com.github.deprosun.dataflattener

import com.github.deprosun.dataflattener.model._
import org.json4s.JsonAST.{JField, JNothing, JObject, JValue}
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods._
import org.slf4j.Logger

trait Transformer {

  val logger: Logger

  type MapFunc = List[JValue] => JValue

  val udfMap: Map[String, MapFunc]

  /**
    * Traverses and extracts the value specified by the path context
    *
    */
  def traversePath(json: JValue, path: JsonPathContext): JValue = {
    //json path context can be of 2 types:
    //1. map_func: user defined function applied to the value
    //2. simple_json_path: simple struct traversed value

    def mapFunctionJsonPathContext(m: MapFunctionJsonPathContext): JValue = {

      //get the function
      val mapFunc = udfMap.getOrElse(m.funcName,
        throw new RuntimeException(s"Unknown user defined function '${m.funcName}'"))

      //get the values
      val values = m.functionParams map { param => traversePath(json, param) }

      //apply the function
      mapFunc(values)
    }

    def simpleJsonPathContext(s: SimpleJsonPathContext, j: JValue): JValue = {

      def traverse(path: List[PathName], currentJson: JValue): JValue = {
        path match {
          case Nil => currentJson
          case x :: rest if x.isNumber =>
            val newJ = currentJson.children(x.id.toInt)
            traverse(rest, newJ)
          case x :: rest =>
            val newJ = currentJson \ x.id
            traverse(rest, newJ)
        }
      }

      traverse(s.path, json)
    }

    path match {
      case m: MapFunctionJsonPathContext => mapFunctionJsonPathContext(m)
      case s: SimpleJsonPathContext => simpleJsonPathContext(s, json)
    }

  }

  def getValueFromStraight(json: JValue, mappingContext: StraightMappingContext): Column = {
    val traversed: JValue = traversePath(json, mappingContext.path)
    Column(mappingContext, traversed)
  }

  private def getFlatColumns(json: JValue, mappings: List[MappingContext]): List[Column] = {
    //first get all the columns that are to be retrieved from this json
    mappings filter (_.isInstanceOf[StraightMappingContext]) map { case x: StraightMappingContext =>
      getValueFromStraight(json, x)
    }
  }

  private def getRowsFromExplode(json: JValue, mappings: List[MappingContext]): List[Row] = {
    //first get all the columns that are to be retrieved from this json
    mappings filter (_.isInstanceOf[ExplodeMappingContext]) flatMap { case y: ExplodeMappingContext =>

      val carryOvers = y.copiedKeys.toList map { case (k, v) =>
        JObject(JField(k, traversePath(json, v)))
      }

      val explodedJson = traversePath(json, y.path)

      val (straightMappings, moreExplodeMappings) =
        y.mappingContext.partition(_.isInstanceOf[StraightMappingContext])


      explodedJson.children flatMap { j =>

        val json = carryOvers.foldLeft(j)((acc, x) =>
          acc merge x
        )

        val flattenedRow = Row(getFlatColumns(json, straightMappings))

        val moreInnerRows: List[Row] = getRowsFromExplode(j, moreExplodeMappings)

        if (moreInnerRows.nonEmpty) {
          moreInnerRows map { row => row.copy(columns = flattenedRow.columns ++ row.columns) }
        } else {
          flattenedRow :: Nil
        }

      }
    }

  }

  /**
    * Transform the JSON input into multiple tables
    *
    * @param json          JSON value to convert
    * @param mapperContext mapper configuration
    * @return List of Table objects
    */
  def transform(json: JValue, mapperContext: MapperContext, additionalValues: List[JValue] = Nil): List[Table] = {

    def start: List[Table] = {

      //therefore, if fromField is mentioned, we need to get mapping values for each element.
      //if fromField is not provided then, the current json is the only one that needs to be used
      //for extraction
      val sourceData: List[JValue] = mapperContext.fromField match {
        case None =>
          val workingJson: JValue = additionalValues.foldLeft(json) { case (acc, x) =>
            acc merge x
          }

          workingJson :: Nil // probably a root, no alteration needed
        case Some(f) =>
          traversePath(json, f).children map { j =>
            additionalValues.foldLeft(j) { case (acc, x) =>
              acc merge x
            }

          }
      }

      //get the straight mappings and explode mappings
      val (straightMappings, explodeMappings) =
        mapperContext.mappings.partition(_.isInstanceOf[StraightMappingContext])

      val tableRows: List[Row] = sourceData flatMap { sourceJson =>

        //get a row that from all the straight mappings
        val flattenedRow = Row(getFlatColumns(sourceJson, straightMappings))

        //get all the rows from exploded mappings
        val moreInnerRows: List[Row] = getRowsFromExplode(sourceJson, explodeMappings)

        //for all additional rows from explosion, add the single row we got from the set of StraightMappings
        if (moreInnerRows.nonEmpty) {
          moreInnerRows map { row => row.copy(columns = flattenedRow.columns ++ row.columns) }
        } else {
          flattenedRow :: Nil
        }
      }

      //get the children recursively
      val children: List[Table] = mapperContext.children flatMap { childMapper =>

        sourceData flatMap { sourceJson =>
          //get the additional values needed for all children rows
          val additionalValues: List[JValue] = childMapper.copiedKeys.toList map { case (k, path) =>
            JObject(JField(k, traversePath(sourceJson, path)))
          }
          transform(sourceJson, childMapper, additionalValues)
        }


      }

      //table name
      val tableName = mapperContext.tableName

      //finally, return the whole list of tables
      Table(tableName, tableRows) :: children


      //      null
      //      val rootColumnValues = getFlatColumns(json, mapperContext.mappings)

      //all column values for this table
      //      val rows: List[Row] = getRows(json, mapperContext.mappings) //getColumnValues(json, mapperContext)

      //if the mapper has children configuration defined, make tables for those

    }

    logger.info(s"Transforming ${mapperContext.tableName}")

    mapperContext.filter match {
      case Some(Filter(path1, path2)) =>
        val value1 = traversePath(json, path1)
        val value2 = traversePath(json, path2)
        if (value1 == value2) start else Nil
      case _ => start

    }
  }


}

case class Column(mappingContext: StraightMappingContext, value: JValue) {
  def toJson: JValue = mappingContext.desiredColumnName -> value
}

case class Row(columns: List[Column]) {

  def toJson: JValue = {
    val initial: JValue = JNothing
    columns.foldLeft(initial) { (acc, x) =>
      acc merge x.toJson
    }
  }
}

case class Table(tableName: String, rows: List[Row]) {
  def toJson: JValue = tableName -> (rows map (_.toJson))

  def toJsonString: String = compact {
    render {
      toJson
    }
  }
}
