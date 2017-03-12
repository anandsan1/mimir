package mimir.adaptive

import java.io._
import com.typesafe.scalalogging.slf4j.LazyLogging
import scala.collection.JavaConverters._

import mimir.Database
import mimir.algebra._
import mimir.lenses._
import mimir.models._
import mimir.statistics.FuncDep

object DiscalaAbadiNormalizer
  extends Multilens
  with LazyLogging
{
  def initSchema(db: Database, config: MultilensConfig): TraversableOnce[Model] =
  {
    logger.debug(s"Creating DiscalaAbadiNormalizer: $config")

    logger.debug("Creating FD Graph")
      val fd = new FuncDep()
      fd.buildEntities(db, config.query, config.schema)

    logger.debug("Dumping Base Schema")
      val schTable = s"MIMIR_DA_SCH_${config.schema}"
      val fullSchema = Seq((("ROOT", TRowId()), -1)) ++ fd.sch.zipWithIndex

      db.backend.update(s"""
        CREATE TABLE $schTable (ATTR_NAME string, ATTR_NODE int, ATTR_TYPE string);
      """)
      db.backend.fastUpdateBatch(s"""
        INSERT INTO $schTable (ATTR_NAME, ATTR_NODE, ATTR_TYPE) VALUES (?, ?, ?);
      """, 
        fullSchema.map { case ((attr, t), idx) => 
          Seq(StringPrimitive(attr), IntPrimitive(idx), TypePrimitive(t))
        }
      )

    logger.debug("Dumping FD Graph")
      val fdTable = s"MIMIR_DA_FDG_${config.schema}"
      db.backend.update(s"""
        CREATE TABLE $fdTable (MIMIR_FD_PARENT int, MIMIR_FD_CHILD int, MIMIR_FD_PATH_LENGTH int);
      """)
      db.backend.fastUpdateBatch(s"""
        INSERT INTO $fdTable (MIMIR_FD_PARENT, MIMIR_FD_CHILD, MIMIR_FD_PATH_LENGTH) VALUES (?, ?, ?);
      """, 
        // Add the basic edges
        fd.fdGraph.getEdges.asScala.map { case (edge_parent, edge_child) =>
          Seq(
            IntPrimitive(edge_parent), 
            IntPrimitive(edge_child),
            if(fd.parentTable.getOrElse(edge_parent, Set[Int]()) contains edge_child){ IntPrimitive(2) } 
              else { IntPrimitive(1) }
          )
        } ++
        // And add in each blacklisted node as an edge off of the root
        fd.blackList.map { col => 
          Seq(
            IntPrimitive(-1),
            IntPrimitive(col),
            IntPrimitive(2)
          )
        }
      )

    val (_, models) = KeyRepairLens.create(
      db, s"MIMIR_DA_CHOSEN_${config.schema}",
      db.getTableOperator(fdTable),
      Seq(Var("MIMIR_FD_CHILD"), Function("SCORE_BY", Seq(Var("MIMIR_FD_PATH_LENGTH"))))
    )

    models
  }

  final def spanningTreeLens(db: Database, config: MultilensConfig): Operator =
  {
    val model = db.models.get(s"MIMIR_DA_CHOSEN_${config.schema}:MIMIR_FD_PARENT")
    KeyRepairLens.assemble(
      db.getTableOperator(s"MIMIR_DA_FDG_${config.schema}"),
      Seq("MIMIR_FD_CHILD"), 
      Seq(("MIMIR_FD_PARENT", model)),
      Some("MIMIR_FD_PATH_LENGTH")
    )
  }

  final def convertNodesToNamesInQuery(
    db: Database, 
    config: MultilensConfig, 
    nodeCol: String, 
    labelCol: String, 
    typeCol: Option[String],
    query: Operator
  ): Operator = 
  {
    Project(
      query.schema.map(_._1).map { col => 
        if(col.equals(nodeCol)){ 
          ProjectArg(labelCol, Var("ATTR_NAME")) 
        } else { 
          ProjectArg(col, Var(col))
        } 
      } ++ typeCol.map { col =>
        ProjectArg(col, Var("ATTR_TYPE"))
      },
      Select(Comparison(Cmp.Eq, Var(nodeCol), Var("ATTR_NODE")),
        Join(      
          db.getTableOperator(s"MIMIR_DA_SCH_${config.schema}"),
          query
        )
      )
    )
  }


  def tableCatalogFor(db: Database, config: MultilensConfig): Operator =
  {
    val spanningTree = spanningTreeLens(db, config)
    logger.trace(s"Table Catalog Spanning Tree: \n$spanningTree")
    val tableQuery = 
      convertNodesToNamesInQuery(db, config, "TABLE_NODE", "TABLE_NAME", None,
        OperatorUtils.makeDistinct(
          Project(Seq(ProjectArg("TABLE_NODE", Var("MIMIR_FD_PARENT"))),
            spanningTree
          )
        )
      )
    logger.trace(s"Table Catalog Query: \n$tableQuery")
    return tableQuery
  }
  def attrCatalogFor(db: Database, config: MultilensConfig): Operator =
  {
    val spanningTree = spanningTreeLens(db, config)
    logger.trace(s"Attr Catalog Spanning Tree: \n$spanningTree")
    val childAttributeQuery =
      OperatorUtils.projectInColumn("IS_KEY", BoolPrimitive(false),
        convertNodesToNamesInQuery(db, config, "MIMIR_FD_CHILD", "ATTR_NAME", Some("ATTR_TYPE"),
          convertNodesToNamesInQuery(db, config, "MIMIR_FD_PARENT", "TABLE_NAME", None,
            spanningTree
          )
        )
      )
    val parentAttributeQuery =
      Project(Seq(
          ProjectArg("TABLE_NAME", Var("TABLE_NAME")),
          ProjectArg("ATTR_NAME", Var("TABLE_NAME")),
          ProjectArg("ATTR_TYPE", Var("ATTR_TYPE")),
          ProjectArg("IS_KEY", BoolPrimitive(true))
        ),
        convertNodesToNamesInQuery(db, config, "TABLE_NODE", "TABLE_NAME", Some("ATTR_TYPE"),
          // SQLite does something stupid with FIRST that prevents it from figuring out that 
          // -1 is an integer.  Add 1 to force it to realize that it's dealing with a number
          Select(Comparison(Cmp.Gt, Arithmetic(Arith.Add, Var("TABLE_NODE"), IntPrimitive(1)), IntPrimitive(0)),
            OperatorUtils.makeDistinct(
              Project(Seq(ProjectArg("TABLE_NODE", Var("MIMIR_FD_PARENT"))),
                spanningTree
              )
            )
          )
        )
      )
    val jointQuery =
      Union(childAttributeQuery, parentAttributeQuery)
    logger.trace(s"Attr Catalog Query: \n$jointQuery")
    return jointQuery
  }
  def viewFor(db: Database, config: MultilensConfig, table: String): Operator = 
  {
    ???
  }

}


// val fdStats = new FuncDep()
// val query = sql.convert(adaptiveSchema.getSelectBody())
// val schema : Seq[(String,Type)] = query.schema

// val viewList : java.util.ArrayList[String] = 
//   fdStats.buildEntities(
//     schema, 
//     backend.execute(adaptiveSchema.getSelectBody.toString()), 
//     adaptiveSchema.getTable.getName
//   )
// viewList.foreach((view) => {
//   println(view)
//   // update(view)
// })      