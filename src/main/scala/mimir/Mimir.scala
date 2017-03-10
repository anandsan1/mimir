package mimir;

import java.io._
import java.sql.SQLException
import java.util

import mimir.ctables.CTPercolator
import mimir.parser._
import mimir.sql._
import mimir.algebra._
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.{FromItem, PlainSelect, Select, SelectBody}
import org.rogach.scallop._;

import scala.collection.JavaConverters._


/**
 * The primary interface to Mimir.  Responsible for:
 * - Parsing and processing command line arguments.
 * - Initializing internal state (Database())
 * - Providing a command-line prompt (if appropriate)
 * - Invoking MimirJSqlParser and dispatching the 
 *   resulting statements to Database()
 *
 * Database() handles all of the logical dispatching,
 * Mimir provides a friendly command-line user 
 * interface on top of Database()
 */
object Mimir {

  var conf: MimirConfig = null;
  var db: Database = null;
  var usePrompt = true;

  def main(args: Array[String]) {

    val test = new mimir.util.JsonToCSV
//    test.singleFile(new File("dump-30.txt"),"twitter100Cols10kRows.csv","UTF-8",100,10000)
    conf = new MimirConfig(args);

    // Set up the database connection(s)
    db = new Database(conf.dbname(), new JDBCBackend(conf.backend(), conf.dbname()))
    db.backend.open()

    // Check for one-off commands
    if(conf.initDB()){
      println("Initializing Database...");
      db.initializeDBForMimir();
    } else if(conf.loadTable.get != None){
      db.loadTable(conf.loadTable(), conf.loadTable()+".csv");
    } else {
      var source: Reader = null;

      if(conf.file.get == None || conf.file() == "-"){
        source = new InputStreamReader(System.in);
        usePrompt = !conf.quiet();
      } else {
        source = new FileReader(conf.file());
        usePrompt = false;
      }

      eventLoop(source)
    }

    db.backend.close()
    if(!conf.quiet()) { println("\n\nDone.  Exiting."); }
  }

  def eventLoop(source: Reader): Unit = {
    var parser = new MimirJSqlParser(source);
    var done = false;
    do {
      try {
        if(usePrompt){ print("\nmimir> "); }

        val stmt: Statement = parser.Statement();

        if(stmt == null){ done = true; }
        else if(stmt.isInstanceOf[Select]){
          handleSelect(stmt.asInstanceOf[Select]);
        } else if(stmt.isInstanceOf[CreateLens]) {
          db.createLens(stmt.asInstanceOf[CreateLens]);
        } else if(stmt.isInstanceOf[Explain]) {
          handleExplain(stmt.asInstanceOf[Explain]);
        } else if(stmt.isInstanceOf[CreateAdaptiveSchema]) {
          handleAdaptiveSchema(stmt.asInstanceOf[CreateAdaptiveSchema]);
        } else {
          db.backend.update(stmt.toString())
        }

      } catch {
        case e: Throwable => {
          e.printStackTrace()
          println("Command Ignored");

          // The parser pops the input stream back onto the queue, so
          // the next call to Statement() will throw the same exact 
          // Exception.  To prevent this from happening, reset the parser:
          parser = new MimirJSqlParser(source);
        }
      }
    } while(!done)
  }

  def handleExplain(explain: Explain): Unit = {
    val raw = db.sql.convert(explain.getSelectBody())
    println("------ Raw Query ------")
    println(raw)
    db.check(raw)
    val optimized = db.optimize(raw)
    println("--- Optimized Query ---")
    println(optimized)
    db.check(optimized)
  }

  def handleSelect(sel: Select): Unit = {
    val raw = db.sql.convert(sel)
    val results = db.query(raw)
    results.open()
    db.dump(results)
    results.close()
  }

  def handleAdaptiveSchema(adaptiveSchema: CreateAdaptiveSchema): Unit = {
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM twitterSmallMediumCleanRAW;
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM CURESOURCE;
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM twitter100cols10krows;
    val ent = new FuncDep()
    val queryOper = db.sql.convert(adaptiveSchema.getSelectBody())
//    val schema = queryOper.schema
    val s : (List[(String,String)],String)= getOrderedSchema(adaptiveSchema.getSelectBody)
    val schema : List[(String,Type.T)] = s._1.map((tup) => {(tup._1,Type.TString)})
    val tableName :String = s._2

    val viewList : java.util.ArrayList[String] = ent.buildEntities(schema, db.backend.execute(adaptiveSchema.getSelectBody.toString()), tableName)
    viewList.asScala.map((view) => {
      db.backend.update(view)
    })
  }

  def handleAdaptiveSchema(adaptiveSchema: CreateAdaptiveSchema,database: Database): Unit = {
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM twitterSmallMediumCleanRAW;
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM CURESOURCE;
    // CREATE ADAPTIVESCHEMA TEST AS SELECT * FROM twitter100cols10krows;
    val ent = new FuncDep()
    val queryOper = database.sql.convert(adaptiveSchema.getSelectBody())
    //    val schema = queryOper.schema
    val s : (List[(String,String)],String)= getOrderedSchema(adaptiveSchema.getSelectBody,database)
    val schema : List[(String,Type.T)] = s._1.map((tup) => {(tup._1,Type.TString)})
    val tableName :String = s._2

    val viewList : java.util.ArrayList[String] = ent.buildEntities(schema, database.backend.execute(adaptiveSchema.getSelectBody.toString()), tableName)
    viewList.asScala.map((view) => {
      database.backend.update(view)
    })
  }

  // Passing a database allows for testing from test suites, just pass the db you're using
  def getOrderedSchema(sel : SelectBody, database: Database) : (List[(String,String)],String) = {
    var schema : (List[(String,String)],String) = null
    if(sel.isInstanceOf[PlainSelect]){
      schema = getOrderedSchema(sel.asInstanceOf[PlainSelect].getFromItem,database)
    }
    else{
      throw new Exception("Only plainselect is supported for this demo")
    }
    schema
  }

  def getOrderedSchema(fi : FromItem, database: Database) : (List[(String,String)],String) = {

    if (fi.isInstanceOf[net.sf.jsqlparser.schema.Table]) {
      val name =
        fi.asInstanceOf[net.sf.jsqlparser.schema.Table].
          getName.toUpperCase
      var alias =
        fi.asInstanceOf[net.sf.jsqlparser.schema.Table].
          getAlias
      if (alias == null) {
        alias = name
      }
      else {
        alias = alias.toUpperCase
      }

      // Bind the table to a source:
      database.getView(name) match {
        case None =>
          val sch = database.getTableSchema(name) match {
            case Some(sch) => sch
            case None => throw new SQLException("Unknown table or view: " + name);
          }
          return (sch.map((x) => (x._1, alias + "_" + x._1)),name)

        case Some(view) =>
          val sch = view.schema.map(_._1)
          return (sch.map((x) => (x, alias + "_" + x)),name)
      }
    }
    else {
      throw new Exception("Joins are not supported for the demo")
    }
  }


  def getOrderedSchema(sel : SelectBody) : (List[(String,String)],String) = {
    var schema : (List[(String,String)],String) = null
    if(sel.isInstanceOf[PlainSelect]){
      schema = getOrderedSchema(sel.asInstanceOf[PlainSelect].getFromItem)
    }
    else{
      throw new Exception("Only plainselect is supported for this demo")
    }
    schema
  }


  def getOrderedSchema(fi : FromItem) : (List[(String,String)],String) = {

    if (fi.isInstanceOf[net.sf.jsqlparser.schema.Table]) {
      val name =
        fi.asInstanceOf[net.sf.jsqlparser.schema.Table].
          getName.toUpperCase
      var alias =
        fi.asInstanceOf[net.sf.jsqlparser.schema.Table].
          getAlias
      if (alias == null) {
        alias = name
      }
      else {
        alias = alias.toUpperCase
      }

      // Bind the table to a source:
      db.getView(name) match {
        case None =>
          val sch = db.getTableSchema(name) match {
            case Some(sch) => sch
            case None => throw new SQLException("Unknown table or view: " + name);
          }
          return (sch.map((x) => (x._1, alias + "_" + x._1)),name)

        case Some(view) =>
          val sch = view.schema.map(_._1)
          return (sch.map((x) => (x, alias + "_" + x)),name)
      }
    }
    else {
      throw new Exception("Joins are not supported for the demo")
    }
  }


//  def connectSqlite(filename: String): java.sql.Connection =
//  {
//    Class.forName("org.sqlite.JDBC");
//    java.sql.DriverManager.getConnection("jdbc:sqlite:"+filename);
//  }
//
//  def connectOracle(filename: String): java.sql.Connection =
//  {
//    Methods.getConn()
//  }

}

class MimirConfig(arguments: Seq[String]) extends ScallopConf(arguments)
{
  //   val start = opt[Long]("start", default = Some(91449149))
  //   val end = opt[Long]("end", default = Some(99041764))
  //   val version_count = toggle("vcount", noshort = true, default = Some(false))
  //   val exclude = opt[Long]("xclude", default = Some(91000000))
  //   val summarize = toggle("summary-create", default = Some(false))
  //   val cleanSummary = toggle("summary-clean", default = Some(false))
  //   val sampleCount = opt[Int]("samples", noshort = true, default = None)
  val loadTable = opt[String]("loadTable", descr = "Don't do anything, just load a CSV file")
  val backend = opt[String]("driver", descr = "Which backend database to use? ([sqlite],oracle)",
    default = Some("sqlite"))
  val dbname = opt[String]("db", descr = "Connect to the database with the specified name",
    default = Some("debug.db"))
  val initDB = toggle("init", default = Some(false))
  val quiet  = toggle("quiet", default = Some(false))
  val file = trailArg[String](required = false)
}