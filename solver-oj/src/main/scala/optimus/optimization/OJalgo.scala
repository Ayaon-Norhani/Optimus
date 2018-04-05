/*
 *
 *    /\\\\\
 *   /\\\///\\\
 *  /\\\/  \///\\\    /\\\\\\\\\     /\\\       /\\\
 *  /\\\      \//\\\  /\\\/////\\\ /\\\\\\\\\\\ \///    /\\\\\  /\\\\\     /\\\    /\\\  /\\\\\\\\\\
 *  \/\\\       \/\\\ \/\\\\\\\\\\ \////\\\////   /\\\  /\\\///\\\\\///\\\ \/\\\   \/\\\ \/\\\//////
 *   \//\\\      /\\\  \/\\\//////     \/\\\      \/\\\ \/\\\ \//\\\  \/\\\ \/\\\   \/\\\ \/\\\\\\\\\\
 *     \///\\\  /\\\    \/\\\           \/\\\_/\\  \/\\\ \/\\\  \/\\\  \/\\\ \/\\\   \/\\\ \////////\\\
 *        \///\\\\\/     \/\\\           \//\\\\\   \/\\\ \/\\\  \/\\\  \/\\\ \//\\\\\\\\\  /\\\\\\\\\\
 *           \/////       \///             \/////    \///  \///   \///   \///  \/////////   \//////////
 *
 *  Copyright (C) 2014 Evangelos Michelioudakis, Anastasios Skarlatidis
 *       
 */

package optimus.optimization

import optimus.algebra.{ConstraintRelation, Expression}
import org.ojalgo.constant.BigMath
import org.ojalgo.optimisation.{ExpressionsBasedModel, Optimisation, Variable}
import optimus.algebra._
import optimus.optimization.enums.{PreSolve, ProblemStatus}

/**
  * OJalgo solver.
  */
final class OJalgo extends AbstractMPSolver {

  var nbRows = 0
  var nbCols = 0
  var solution = Array[Double]()
  var objectiveValue = 0.0
  var status = ProblemStatus.NOT_SOLVED

  val model = new ExpressionsBasedModel

  // Internal flag for keeping optimization state
  private var minimize = true

  private var constantTerm = 0d

  /**
   * Problem builder, should configure the solver and append
   * mathematical model variables.
   *
   * @param nbRows rows in the model
   * @param nbCols number of variables in the model
   */
  def buildProblem(nbRows: Int, nbCols: Int) = {

    logger.info { "\n" +
      """        _________      ______               """ + "\n" +
      """  ____________  /_____ ___  /______ ______  """ + "\n" +
      """  _  __ \__ _  /_  __  /_  /__  __  /  __ \ """ + "\n" +
      """  / /_/ / /_/ / / /_/ /_  / _  /_/ // /_/ / """ + "\n" +
      """  \____/\____/  \__._/ /_/  _\__. / \____/  """ + "\n" +
      """                            /____/          """ + "\n"
    }

    logger.info("Model oJalgo: " + nbRows + "x" + nbCols)

    this.nbRows = nbRows
    this.nbCols = nbCols

    for(i <- 1 to nbCols) model.addVariable(Variable.make(i.toString))
  }

  /**
   * Get value of the variable in the specified position. Solution
   * should exist in order for a value to exist.
   *
   * @param colId position of the variable
   * @return the value of the variable in the solution
   */
  def getValue(colId: Int): Double = solution(colId)

  /**
   * Set bounds of variable in the specified position.
   *
   * @param colId position of the variable
   * @param lower domain lower bound
   * @param upper domain upper bound
   */
  def setBounds(colId: Int, lower: Double, upper: Double) = {
    if(upper == Double.PositiveInfinity) model.getVariable(colId).upper(null)
    else model.getVariable(colId).upper(upper)

    if(lower == Double.NegativeInfinity) model.getVariable(colId).lower(null)
    else model.getVariable(colId).lower(lower)
  }

  /**
   * Set lower bound to unbounded (infinite)
   *
   * @param colId position of the variable
   */
  def setUnboundUpperBound(colId: Int) = {
    model.getVariable(colId).upper(null)
  }

  /**
   * Set upper bound to unbounded (infinite)
   *
   * @param colId position of the variable
   */
  def setUnboundLowerBound(colId: Int) = {
    model.getVariable(colId).lower(null)
  }

  /**
   * Set the column/variable as an integer variable
   *
   * @param colId position of the variable
   */
  def setInteger(colId: Int) {
    model.getVariable(colId).integer(true)
  }

  /**
   * Set the column / variable as an binary integer variable
   *
   * @param colId position of the variable
   */
  def setBinary(colId: Int) {
    model.getVariable(colId).binary()
  }

  /**
   * Set the column/variable as a float variable
   *
   * @param colId position of the variable
   */
  def setFloat(colId: Int) {
    model.getVariable(colId).integer(false)
  }

  /**
   * Add objective expression to be optimized by the solver.
   *
   * @param objective the expression to be optimized
   * @param minimize flag for minimization instead of maximization
   */
  def addObjective(objective: Expression, minimize: Boolean) = {

    if(objective.getOrder == ExpressionType.GENERIC)
      throw new IllegalArgumentException("oJalgo cannot handle expressions of higher order!")

    val objectiveFunction = model.addExpression("objective")
    objectiveFunction.weight(BigMath.ONE)

    val iterator = objective.terms.iterator
    while(iterator.hasNext) {
      iterator.advance()
      val indexes = decode(iterator.key)
      if(indexes.length == 1) objectiveFunction.set(model.getVariable(indexes.head), iterator.value)
      else objectiveFunction.set(model.getVariable(indexes.head), model.getVariable(indexes(1)), iterator.value)
    }

    constantTerm = objective.constant

    if(!minimize) this.minimize = false else this.minimize = true
  }

  /**
   * Add a mathematical programming constraint to the solver.
   *
   * @param mpConstraint the mathematical programming constraint
   */
  def addConstraint(mpConstraint: MPConstraint) = {

    val lhs = mpConstraint.constraint.lhs - mpConstraint.constraint.rhs
    val operator = mpConstraint.constraint.operator

    val constraint = model.addExpression(mpConstraint.index.toString)

    val iterator = lhs.terms.iterator
    while(iterator.hasNext) {
      iterator.advance()
      val indexes = decode(iterator.key)
      if(indexes.length == 1) constraint.set(model.getVariable(indexes.head), iterator.value)
      else constraint.set(model.getVariable(indexes.head), model.getVariable(indexes(1)), iterator.value)
    }

    operator match {
      case ConstraintRelation.GE => constraint.lower(-lhs.constant)
      case ConstraintRelation.LE => constraint.upper(-lhs.constant)
      case ConstraintRelation.EQ => constraint.level(-lhs.constant)
    }
  }

  /**
   * Solve the problem.
   *
   * @return status code indicating the nature of the solution
   */
  def solveProblem(preSolve: PreSolve = PreSolve.DISABLED): ProblemStatus = {

    if(preSolve != PreSolve.DISABLED) logger.info("oJalgo does not support pre-solving!")
    
    val result = if(this.minimize) model.minimise() else model.maximise()

    result.getState match {

      case Optimisation.State.OPTIMAL | Optimisation.State.DISTINCT =>
        solution = Array.tabulate(nbCols)(col => result.get(col).doubleValue())
        objectiveValue = result.getValue + constantTerm
        ProblemStatus.OPTIMAL

      case Optimisation.State.INFEASIBLE =>
        ProblemStatus.INFEASIBLE

      case Optimisation.State.UNBOUNDED =>
        ProblemStatus.UNBOUNDED

      case _ =>
        solution = Array.tabulate(nbCols)(col => result.get(col).doubleValue())
        ProblemStatus.SUBOPTIMAL
    }
  }

  /**
   * Release the memory of this solver
   */
  def release() = {
    model.dispose()
  }

  /**
   * Set a time limit for solver optimization. After the limit
   * is reached the solver stops running.
   *
   * @param limit the time limit
   */
  def setTimeout(limit: Int) = {
    require(0 <= limit)
    model.options.time_abort = limit
  }
}
