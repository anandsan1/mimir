package mimir.ctables;

import java.util.Random;

import mimir._;
import mimir.algebra._;
import mimir.exec._;
import mimir.util._;
import mimir.optimizer._;

case class InvalidProvenance(msg: String, token: RowIdPrimitive) 
	extends Exception("Invalid Provenance Token ["+msg+"]: "+token);

abstract class Explanation(
	val reasons: List[Reason], 
	val token: RowIdPrimitive
) {
	def fields: List[(String, PrimitiveValue)]

	override def toString(): String = {
		(fields ++ List( 
			("Reasons", reasons.map("\n    "+_.toString).mkString("")),
			("Token", JSONBuilder.string(token.v))
		)).map((x) => x._1+": "+x._2).mkString("\n")
	}

	def toJSON(): String = {
		JSONBuilder.dict(
			fields.map( { case (k, v) => (k, JSONBuilder.prim(v)) } ) ++ 
			List(
				("reasons", 
					JSONBuilder.list(reasons.map( _.toJSON ) )),
				("token", JSONBuilder.string(token.v))
		))
	}
}

case class RowExplanation (
	val probability: Double, 
	override val reasons: List[Reason], 
	override val token: RowIdPrimitive
) extends Explanation(reasons, token) {
	def fields = List(
		("probability", FloatPrimitive(probability))
	)
}

class CellExplanation(
	val examples: List[PrimitiveValue],
	override val reasons: List[Reason], 
	override val token: RowIdPrimitive,
	val column: String
) extends Explanation(reasons, token) {
	def fields = List[(String,PrimitiveValue)](
		("examples", StringPrimitive(examples.map( _.toString ).mkString(", "))),
		("column", StringPrimitive(column))
	)
}

case class GenericCellExplanation (
	override val examples: List[PrimitiveValue],
	override val reasons: List[Reason], 
	override val token: RowIdPrimitive,
	override val column: String
) extends CellExplanation(examples, reasons, token, column) {
}

case class NumericCellExplanation (
	val bounds: Option[(PrimitiveValue, PrimitiveValue)],
	val mean: PrimitiveValue,
	val sttdev: PrimitiveValue,
	override val examples: List[PrimitiveValue],
	override val reasons: List[Reason], 
	override val token: RowIdPrimitive,
	override val column: String
) extends CellExplanation(examples, reasons, token, column) {
	override def fields = 
		(bounds match {
			case None => List[(String, PrimitiveValue)]()
			case Some((low, high)) => List(("bounds", StringPrimitive(low.toString+":"+high.toString)));
		}) ++ List(
			("mean", mean),
			("sttdev", sttdev)
		) ++ super.fields
}


class CTExplainer(db: Database) {

	val NUM_SAMPLES = 1000
	val NUM_EXAMPLES = 3
	val rnd = new Random();
	
	def explainRow(oper: Operator, token: RowIdPrimitive): RowExplanation =
	{
		val (tuple, expressions) = provenanceQuery(oper, token)
		val (provenance, probability) = 
			expressions.get(CTables.conditionColumn) match {
				case None => (BoolPrimitive(true), 1.0)
				case Some(cond) => {
					(	cond, 
						sampleExpression[(Int,Int)](cond, tuple, NUM_SAMPLES, (0,0), 
							(cnt: (Int,Int), present: PrimitiveValue) => 
								present match {
									case NullPrimitive() => (cnt._1, cnt._2)
									case BoolPrimitive(t) => 
										( cnt._1 + (if(t){ 1 } else { 0 }),
										  cnt._2 + 1
										 )
								}
						) match { 
							case (_, 0) => -1.0
							case (hits, total) => hits.toDouble / total.toDouble
						}
					)
				}
			}

		// println("tuple: "+tuple)
		// println("condition"+provenance)
		// println("probability: "+probability)

		RowExplanation(
			probability,
			getFocusedReasons(provenance, tuple),
			token
		)
	}

	def explainCell(oper: Operator, token: RowIdPrimitive, column: String): CellExplanation =
	{
		val (tuple, allExpressions) = provenanceQuery(oper, token)
		val expr = allExpressions.get(column).get
		val colType = Typechecker.typeOf(expr, tuple.mapValues( _.getType ))

		val examples = 
			sampleExpression[List[PrimitiveValue]](
				expr, tuple, NUM_EXAMPLES, 
				List[PrimitiveValue](), 
				(_++List(_)) 
			)

		colType match {
			case (Type.TInt | Type.TFloat) => 
				val (avg, stddev) = getStats(expr, tuple, NUM_SAMPLES)

				NumericCellExplanation(
					getBounds(expr, tuple),
					avg, 
					stddev, 
					examples,
					getFocusedReasons(expr, tuple),
					token, 
					column
				)

			case _ => 
				GenericCellExplanation(
					examples,
					getFocusedReasons(expr, tuple),
					token,
					column
				)
		}
	}

	def getBounds(expr: Expression, tuple: Map[String,PrimitiveValue]): 
		Option[(PrimitiveValue,PrimitiveValue)] =
	{
		try {
			val (lbound, ubound) = CTBounds.compile(expr);
			Some( (
				Eval.eval(lbound, tuple),
				Eval.eval(ubound, tuple)
			))
		} catch {
			case BoundsUnsupportedException(_, _) => None
		}
	}

	def sampleExpression[A](
		expr: Expression, bindings: Map[String,PrimitiveValue], count: Int, 
		init: A, accum: ((A, PrimitiveValue) => A)
	): A = 
	{
		val sampleExpr = CTAnalyzer.compileSample(expr, Var(CTables.SEED_EXP))
        (0 until count).
        	map( (i) => 
        		try {
	        		Eval.eval(
	        			sampleExpr, 
		        		bindings ++ Map("__SEED" -> IntPrimitive(rnd.nextInt()))
		        	)
		        } catch {
		        	case TypeException(_,_,_) => NullPrimitive()
		        }
        	).
	        foldLeft(init)(accum)

	}

	def getStats(expr: Expression, tuple: Map[String,PrimitiveValue], count: Integer): 
		(PrimitiveValue, PrimitiveValue) =
	{
		val (tot, totSq) =
			sampleExpression[(PrimitiveValue, PrimitiveValue)](
				expr, tuple, count,
				(IntPrimitive(0), IntPrimitive(0)),
				{ case ((tot, totSq), v) => 
					(
						Eval.applyArith(Arith.Add, tot, v),
						Eval.applyArith(Arith.Add, totSq,
							Eval.applyArith(Arith.Mult, v, v))
					)
				}
			)
		val avg = Eval.applyArith(Arith.Div, tot, FloatPrimitive(count.toDouble))
		val stddev =
			Eval.eval(
				Function("ABS", List(
					Arithmetic(Arith.Sub, 
						Arithmetic(Arith.Div, totSq, FloatPrimitive(count.toDouble)),
						Arithmetic(Arith.Mult, avg, avg)
					)
				))
			)

		(avg, stddev)
	}


	def getFocusedReasons(expr: Expression, tuple: Map[String,PrimitiveValue]): 
		List[Reason] =
	{
		getFocusedReasons( Eval.inline(expr, tuple) );
	}

	def getFocusedReasons(expr: Expression):
		List[Reason] =
	{
		// println(expr.toString)
		expr match {
			case v: VGTerm => List(v.reason)

			case Conditional(c, t, e) =>
				getFocusedReasons(c) ++ (
					if(Eval.evalBool(InlineVGTerms.inline(c))){
						getFocusedReasons(t)
					} else {
						getFocusedReasons(e)
					}
				);

			case Arithmetic(op @ (Arith.And | Arith.Or), a, b) =>
				getFocusedReasons(a) ++ (
					(op, Eval.evalBool(InlineVGTerms.inline(a))) match {
						case (Arith.And, true) => getFocusedReasons(b)
						case (Arith.Or, false) => getFocusedReasons(b)
						case _ => List()
					}
				)

			case _ => 
				expr.children.flatMap( getFocusedReasons(_) )
		}
	}

	def provenanceQuery(oper: Operator, token: RowIdPrimitive): 
		(Map[String,PrimitiveValue], Map[String, Expression]) =
	{
		val optQuery = db.compiler.optimize(
			CTPercolator.propagateRowIDs(oper, true),
			NonDeterminism.Classic
		)
		val (expressions, sourceQuery, _) = 
			delveToProjection(optQuery, token)
		val iterator = db.compiler.buildDeterministicIterator(
			Select(
				Comparison(Cmp.Eq, 
					expressions.find( _._1.equals(CTPercolator.ROWID_KEY) ).get._2, token),
				sourceQuery
			)
		)

		iterator.open()
		if(!iterator.getNext()){ throw InvalidProvenance("INVALID TOKEN", token); }
		val tuple = iterator.currentTuple()
		iterator.close();

		(  tuple, expressions  )
	}

	def delveToProjection(oper: Operator, token: RowIdPrimitive):
		(Map[String, Expression], Operator, RowIdPrimitive) =
	{
		oper match {
			case Union(lhs, rhs) =>
				val (newToken, isLHS) = stripUnionProvenance(token);
				delveToProjection(if(isLHS){ lhs } else { rhs }, newToken);
			case Project(targets, source) => 
				return (
					targets.map ( { case ProjectArg(n,e) => (n,e) } ).toMap,
					source, 
					token
				)
			case _ =>
				throw InvalidProvenance("PERCOLATE", token)
		}
	}

	def stripUnionProvenance(token: RowIdPrimitive): 
		(RowIdPrimitive, Boolean) =
	{
		val payload = token.payload.toString;
		if(payload.endsWith(".left")){
			return (RowIdPrimitive(payload.substring(0, payload.length() - 5)), true)
		}
		else if(payload.endsWith(".right")){
			return (RowIdPrimitive(payload.substring(0, payload.length() - 5)), false)
		}
		else {
			throw InvalidProvenance("UNION", token)
		}
	}
}