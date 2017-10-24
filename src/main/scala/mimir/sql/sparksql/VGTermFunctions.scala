package mimir.sql.sparksql

import java.sql.SQLException

import mimir.algebra._
import mimir.Database
import mimir.ctables.vgterm.BestGuess


class BestGuessVGTerm(db: Database) extends Serializable {

//  def bestGuessVGTerm(modelName : String, idx: Int, args:Seq[Long]) : Long = {
    def bestGuessVGTerm(modelName : String, idk: java.lang.Long, idx: java.lang.Long, arg1: java.lang.Long, arg2: java.lang.Long, arg3: java.lang.Long) : Long = {
      10
    }
//    val value_mimir : ( Int,Type) => PrimitiveValue = (idx, t) => {
//      t match {
//        case TInt()    => IntPrimitive(args(idx).asInstanceOf[Long])
//        case TFloat()  => FloatPrimitive(args(idx).asInstanceOf[Double])
//        case TAny()    => args(idx) match {
//                case intVal : Int => IntPrimitive(intVal.toLong)
//                case longVal : Long => IntPrimitive(longVal)
//                case doubleVal : Double   => FloatPrimitive(doubleVal)
//                case strVal : String => StringPrimitive(strVal)
//                case null    => null
//              }
//         case _       => TextUtils.parsePrimitive(t, args(idx).toString)
//      }
//    }
//
//    val model = db.models.get(modelName)
//    val argList = model.argTypes(idx).
//      zipWithIndex.
//      map(arg => value_mimir(arg._2+2, arg._1))
//    val hintList = model.hintTypes(idx).
//      zipWithIndex.
//      map(arg => value_mimir(arg._2+argList.length+2, arg._1))
//    val guess = model.bestGuess(idx, argList, hintList)
//
//    guess match {
//      case IntPrimitive(i)      => i
//      case FloatPrimitive(f)    => f
//      case StringPrimitive(s)   => s
//      case d:DatePrimitive      => d.asString
//      case BoolPrimitive(true)  => 1
//      case BoolPrimitive(false) => 0
//      case RowIdPrimitive(r)    => r
//      case NullPrimitive()      => null
//    }
//  }
}


object VGTermFunctions 
{

  def bestGuessVGTermFn = "BEST_GUESS_VGTERM"

  def register(db: Database, spark:org.apache.spark.sql.SparkSession): Unit =
  {
//    val bestGuess: Int => Int = (in: Int) => 10
//    spark.udf.register("GET_TEN", bestGuess)
//    db.functions.register(
//      "GET_TEN",
//      (args) => { new IntPrimitive(10) },
//      (_) => TInt()
//    )
    spark.udf.register(bestGuessVGTermFn, new BestGuessVGTerm(db).bestGuessVGTerm _)
    db.functions.register(
      bestGuessVGTermFn,
      (args) => { throw new SQLException("Mimir Cannot Execute VGTerm Functions Internally") },
      (_) => TInt()
    )
  }

  def specialize(e: Expression): Expression = {
    e match {
      case BestGuess(model, idx, args, hints) =>
        Function(
          bestGuessVGTermFn,
          Seq(StringPrimitive(model.name), IntPrimitive(idx))++
            args.map(specialize(_))++
            hints.map(specialize(_))
        )
      case VGTerm(model, idx, args, hints) => 
        Function(
          bestGuessVGTermFn, 
          List(StringPrimitive(model), IntPrimitive(idx))++
            args.map(specialize(_))++
            hints.map(specialize(_))
        )
      case _ => e.recur(specialize(_))
    }
  }

  def specialize(o: Operator): Operator =
    o.recur(specialize(_)).recurExpressions(specialize(_))
}