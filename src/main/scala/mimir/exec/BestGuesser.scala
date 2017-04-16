package mimir.exec

import java.sql._
import com.typesafe.scalalogging.slf4j.LazyLogging

import mimir.Database
import mimir.algebra._
import mimir.ctables._
import mimir.provenance._
import mimir.optimizer._

object BestGuesser
  extends LazyLogging
{
  /**
   * Compile the query for best-guess-style evalaution.
   *
   * Includes:
   *  * Provenance Annotations
   *  * Taint Annotations
   *  * One result from the "Best Guess" world.
   */ 
  def apply(db: Database, operRaw: Operator, opts: Compiler.Optimizations = Compiler.standardOptimizations): (
    Operator,                   // The compiled query
    Seq[(String, Type)],        // The base schema
    Map[String, Expression],    // Column taint annotations
    Expression,                 // Row taint annotation
    Seq[String]                 // Provenance columns
  ) =
  {
    var oper = operRaw
    val rawColumns = operRaw.columnNames.toSet

    // We'll need the pristine pre-manipulation schema down the line
    // As a side effect, this also forces the typechecker to run, 
    // acting as a sanity check on the query before we do any serious
    // work.
    val outputSchema = oper.schema;
      
    // The names that the provenance compilation step assigns will
    // be different depending on the structure of the query.  As a 
    // result it is **critical** that this be the first step in 
    // compilation.  
    val provenance = Provenance.compile(oper)
    oper               = provenance._1
    val provenanceCols = provenance._2

    logger.debug(s"WITH-PROVENANCE (${provenanceCols.mkString(", ")}): $oper")


    // Tag rows/columns with provenance metadata
    val tagging = CTPercolator.percolateLite(oper)
    oper               = tagging._1
    val colDeterminism = tagging._2.filter( col => rawColumns(col._1) )
    val rowDeterminism = tagging._3

    logger.debug(s"PERCOLATED: $oper")

    // It's a bit of a hack for now, but provenance
    // adds determinism columns for provenance metadata, since
    // we have no way to explicitly track what's an annotation
    // and what's "real".  Remove this metadata now...
    val minimalSchema: Set[String] = 
      operRaw.columnNames.toSet ++ 
      provenanceCols.toSet ++
      (colDeterminism.map(_._2) ++ Seq(rowDeterminism)).flatMap( ExpressionUtils.getColumns(_) ).toSet


    oper = ProjectRedundantColumns(oper, minimalSchema)

    logger.debug(s"PRE-OPTIMIZED: $oper")

    oper = db.views.resolve(oper)

    logger.debug(s"INLINED: $oper")

    // Clean things up a little... make the query prettier, tighter, and 
    // faster
    oper = Compiler.optimize(oper, opts)

    logger.debug(s"OPTIMIZED: $oper")

    // Replace VG-Terms with their "Best Guess values"
    oper = bestGuessQuery(db, oper)

    logger.debug(s"GUESSED: $oper")

    return (
      oper,
      outputSchema,
      colDeterminism,
      rowDeterminism,
      provenanceCols
    )
  }

  /**
   * Remove all VGTerms in the query and replace them with the 
   * equivalent best guess values
   */
  def bestGuessQuery(db: Database, oper: Operator): Operator =
  {
    // Remove any VG Terms for which static best-guesses are possible
    // In other words, best guesses that don't depend on which row we're
    // looking at (like the Type Inference or Schema Matching lenses)
    val mostlyDeterministicOper =
      InlineVGTerms(oper)

    // Deal with the remaining VG-Terms.  
    if(db.backend.canHandleVGTerms()){
      // The best way to do this would be a database-specific "BestGuess" 
      // UDF if it's available.
      return mostlyDeterministicOper
    } else {
      // Unfortunately, this UDF may not always be available, so if needed
      // we fall back to the Guess Cache
      val fullyDeterministicOper =
        db.bestGuessCache.rewriteToUseCache(mostlyDeterministicOper)

      return fullyDeterministicOper
    }
  }
}