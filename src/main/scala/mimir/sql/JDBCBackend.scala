package mimir.sql;

import java.sql._

import mimir.Database
import mimir.Methods
import mimir.algebra._
import mimir.util.JDBCUtils
import mimir.sql.sqlite._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import com.typesafe.scalalogging.slf4j.LazyLogging

class JDBCBackend(val backend: String, val filename: String) 
  extends Backend
  with LazyLogging
{
  var conn: Connection = null
  var openConnections = 0
  var inliningAvailable = false;

  def driver() = backend

  val tableSchemas: scala.collection.mutable.Map[String, Seq[(String, Type)]] = mutable.Map()

  def open() = {
    this.synchronized({
      assert(openConnections >= 0)
      if (openConnections == 0) {
        conn = backend match {
          case "sqlite" =>
            Class.forName("org.sqlite.JDBC")
            val path = java.nio.file.Paths.get(filename).toString
            var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + path)
            SQLiteCompat.registerFunctions(c)
            tableSchemas.put("SQLITE_MASTER", Seq(
              ("NAME", TString()),
              ("TYPE", TString())
            ))
            c


          case "oracle" =>
            Methods.getConn()

          case x =>
            println("Unsupported backend! Exiting..."); System.exit(-1); null
        }
      }

      assert(conn != null)
      openConnections = openConnections + 1
    })
  }

  def enableInlining(db: Database): Unit =
  {
    backend match {
      case "sqlite" => 
        sqlite.VGTermFunctions.register(db, conn)
        inliningAvailable = true
    }
  }

  def close(): Unit = {
    this.synchronized({
      if (openConnections > 0) {
        openConnections = openConnections - 1
        if (openConnections == 0) {
          conn.close()
          conn = null
        }
      }

      assert(openConnections >= 0)
      if (openConnections == 0) assert(conn == null)
    })
  }

  def execute(sel: String): ResultSet = 
  {
    this.synchronized({
      try {
        if(conn == null) {
          throw new SQLException("Trying to use unopened connection!")
        }
        val stmt = conn.createStatement()
        val ret = stmt.executeQuery(sel)
        stmt.closeOnCompletion()
        ret
      } catch { 
        case e: SQLException => println(e.toString+"during\n"+sel)
          throw new SQLException("Error in "+sel, e)
      }
    })
  }
  def execute(sel: String, args: Seq[PrimitiveValue]): ResultSet = 
  {
    this.synchronized({
      try {
        if(conn == null) {
          throw new SQLException("Trying to use unopened connection!")
        }
        val stmt = conn.prepareStatement(sel)
        setArgs(stmt, args)
        stmt.executeQuery()
      } catch { 
        case e: SQLException => println(e.toString+"during\n"+sel+" <- "+args)
          throw new SQLException("Error", e)
      }
    })
  }
  
  def update(upd: String): Unit =
  {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }
      val stmt = conn.createStatement()
      stmt.executeUpdate(upd)
      stmt.close()
    })
  }

  def update(upd: TraversableOnce[String]): Unit =
  {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }
      val stmt = conn.createStatement()
      upd.foreach( u => stmt.addBatch(u) )
      stmt.executeBatch()
      stmt.close()
    })
  }

  def update(upd: String, args: Seq[PrimitiveValue]): Unit =
  {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }
      val stmt = conn.prepareStatement(upd);
      setArgs(stmt, args)
      stmt.execute()
      stmt.close()
    })
  }

  def fastUpdateBatch(upd: String, argsList: Iterable[Seq[PrimitiveValue]]): Unit =
  {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }
      if(backend.equals("sqlite")){
        // Borrowing some advice from :
        // http://blog.quibb.org/2010/08/fast-bulk-inserts-into-sqlite/
        update("PRAGMA synchronous=OFF")
        update("PRAGMA journal_mode=MEMORY")
        update("PRAGMA temp_store=MEMORY")
      }
      conn.setAutoCommit(false)
      try {
        val stmt = conn.prepareStatement(upd);
        var idx = 0
        argsList.foreach( (args) => {
          idx += 1
          setArgs(stmt, args)
          stmt.execute()
          if(idx % 500000 == 0){ conn.commit() }
        })
        stmt.close()
      } finally {
        conn.commit()
        conn.setAutoCommit(true)
        if(backend.equals("sqlite")){
          update("PRAGMA synchronous=ON")
          update("PRAGMA journal_mode=DELETE")
          update("PRAGMA temp_store=DEFAULT")
        }
      }
    })
  }
  
  def getTableSchema(table: String): Option[Seq[(String, Type)]] =
  {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }

      tableSchemas.get(table) match {
        case x: Some[_] => x
        case None =>
          val tables = this.getAllTables().map{(x) => x.toUpperCase}
          if(!tables.contains(table.toUpperCase)) return None

          val cols: Option[List[(String, Type)]] = backend match {
            case "sqlite" => {
              // SQLite doesn't recognize anything more than the simplest possible types.
              // Type information is persisted but not interpreted, so conn.getMetaData() 
              // is useless for getting schema information.  Instead, we need to use a
              // SQLite-specific PRAGMA operation.
              SQLiteCompat.getTableSchema(conn, table)
            }
            case "oracle" => 
              val columnRet = conn.getMetaData().getColumns(null, "ARINDAMN", table, "%")  // TODO Generalize
              var ret = List[(String, Type)]()
              while(columnRet.isBeforeFirst()){ columnRet.next(); }
              while(!columnRet.isAfterLast()){
                ret = ret ++ List((
                  columnRet.getString("COLUMN_NAME").toUpperCase,
                  JDBCUtils.convertSqlType(columnRet.getInt("DATA_TYPE"))
                  ))
                columnRet.next()
              }
              columnRet.close()
              Some(ret)
          }
          
          cols match { case None => (); case Some(s) => tableSchemas += table -> s }
          cols
      }
    })
  }

  def getAllTables(): Seq[String] = {
    this.synchronized({
      if(conn == null) {
        throw new SQLException("Trying to use unopened connection!")
      }

      val metadata = conn.getMetaData()
      val tables = backend match {
        case "sqlite" => metadata.getTables(null, null, "%", null)
        case "oracle" => metadata.getTables(null, "ARINDAMN", "%", null) // TODO Generalize
      }

      val tableNames = new ListBuffer[String]()

      while(tables.next()) {
        tableNames.append(tables.getString("TABLE_NAME"))
      }

      tables.close()
      tableNames.toList
    })
  }

  override def getView(name: String, table: String): Option[Seq[Seq[PrimitiveValue]]] = {
    Some(this.resultRows(s"SELECT query FROM $table WHERE name = ?",
      List(StringPrimitive(name.toUpperCase))
    ))
  }

  def canHandleVGTerms(): Boolean = inliningAvailable

  def specializeQuery(q: Operator): Operator = {
    backend match {
      case "sqlite" if inliningAvailable => 
        VGTermFunctions.specialize(SpecializeForSQLite(q))
      case "sqlite" => SpecializeForSQLite(q)
      case "oracle" => q
    }
  }

  def setArgs(stmt: PreparedStatement, args: Seq[PrimitiveValue]): Unit =
  {
    args.zipWithIndex.foreach(a => {
      val i = a._2+1
      a._1 match {
        case p:StringPrimitive   => stmt.setString(i, p.v)
        case p:IntPrimitive      => stmt.setLong(i, p.v)
        case p:FloatPrimitive    => stmt.setDouble(i, p.v)
        case _:NullPrimitive     => stmt.setNull(i, Types.VARCHAR)
        case d:DatePrimitive     => stmt.setDate(i, JDBCUtils.convertDate(d))
        case r:RowIdPrimitive    => stmt.setString(i,r.v)
        case t:TypePrimitive     => stmt.setString(i, t.t.toString)
      }
    })
  }

  def setDB(db:Database) = ???

  def listTablesQuery: Operator = 
  {
    backend match {
      case "sqlite" => 
        Project(
          Seq(
            ProjectArg("TABLE_NAME", Var("NAME"))
          ),
          Select(
            ExpressionUtils.makeInTest(Var("TYPE"), Seq(StringPrimitive("table"), StringPrimitive("view"))),
            Table("SQLITE_MASTER", "SQLITE_MASTER", Seq(("NAME", TString()), ("TYPE", TString())), Seq())
          )
        )

      case "oracle" => ???
    }
  }
  def listAttrsQuery: Operator = 
  {
    backend match {
      case "sqlite" => {
        logger.warn("SQLITE has no programatic way to access attributes in SQL")
        EmptyTable(Seq(
          ("TABLE_NAME", TString()), 
          ("ATTR_NAME", TString()),
          ("ATTR_TYPE", TString()),
          ("IS_KEY", TBool())
        ));
      }

      case "oracle" => ???
    }
  }
  
}