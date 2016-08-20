package mimir.semistructured

import java.io._;
import java.util.Random;
import scala.collection.JavaConversions._

import mimir._;
import mimir.sql._;
import mimir.algebra._;
import mimir.ctables._;
import mimir.test._;
import mimir.util._;
import mimir.lenses._;

object ShredderLensSpec
  extends SQLTestSpecification("shredderLensTestDB", Map("reset" -> "NO"))
{

  val testTable = "JSONDATA"
  var discala:FuncDep = null
  val testData = new File(
    "../test/data/JSONOUTPUTWIDE.csv"
    // "../test/data/Bestbuy_raw_noquote.csv"
  )
  val extractorName = "TEST_EXTRACTOR"

  sequential

  "The DiScala Extractor" should {

    // "be initializable" >> {
    //   LoadCSV.handleLoadTable(db, testTable, testData)
    //   val schema = db.getTableSchema(testTable).get
    //   discala = new FuncDep()
    //   discala.buildAbadi(schema, db.query(db.getTableOperator(testTable)))
    //   discala.entityPairMatrix must not beNull
    // }

    // "be serializable" >> {
    //   discala.serializeTo(db, extractorName) 
    //   discala.entityPairMatrix must not beNull

    //   val blob1 = 
    //     db.backend.singletonQuery(
    //       "SELECT data FROM "+FuncDep.BACKSTORE_TABLE_NAME+" WHERE name='"+extractorName+"'"
    //     )
    //   Runtime.getRuntime().exec(Array("cp", "databases/shredderLensTestDB", "shreddb"))
    //   blob1 must beAnInstanceOf[BlobPrimitive]
    //   blob1.asInstanceOf[BlobPrimitive].v.length must be greaterThan(0)
    // }

    "be deserializable" >> {
      discala = FuncDep.deserialize(db, extractorName)
      discala.entityPairMatrix must not beNull
    }

    "contain enough information to create a lens" >> {
      val entities = discala.entityPairList.flatMap( x => List(x._1, x._2) ).toSet.toList
      println(entities.toString)
      entities must not beEmpty

      val primaryEntity = entities(new Random().nextInt(entities.size))
      val possibleSecondaries = 
        discala.entityPairList.flatMap({ case (a, b) => 
          if(a == primaryEntity){ Some(b) }
          else if(b == primaryEntity){ Some(a) }
          else { None }
        })

      val entityObject = (e:Integer) => (e.toInt, discala.parentTable.get(e).toList.map(_.toInt))

      val input = db.getTableOperator(testTable)
      val lens = new ShredderLens(
        "TEST_LENS",
        discala,
        entityObject(primaryEntity),
        possibleSecondaries.map( entityObject(_) ),
        input
      )

      println(lens.view)
      val inputSchema = input.schema
      val targetSchema = 
        (primaryEntity :: discala.parentTable.get(primaryEntity).toList).map(
          inputSchema(_)
        )
      lens.schema must be equalTo(targetSchema)

    }

  }

}
