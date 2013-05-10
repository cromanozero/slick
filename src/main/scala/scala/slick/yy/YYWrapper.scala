package scala.slick.yy

import scala.slick.lifted.Column
import scala.slick.lifted.Projection
import scala.slick.lifted.Projection2
import scala.slick.lifted.Query
import scala.slick.lifted.ColumnOrdered
import scala.slick.ast.Node
import scala.slick.ast.Library
import scala.slick.lifted.FunctionSymbolExtensionMethods
import scala.slick.ast.StaticType
import FunctionSymbolExtensionMethods._
import scala.slick.ast.LiteralNode
import StaticType._
import scala.slick.ast.TypedType
import scala.slick.lifted.Shape
import scala.slick.lifted.IdentityShape
import scala.slick.lifted.ColumnExtensionMethods
import scala.slick.lifted.PlainColumnExtensionMethods
import scala.slick.lifted.OptionMapper2
import scala.slick.ast.BaseTypedType
import scala.slick.lifted.ConstColumn
import scala.slick.lifted.Rep
import scala.slick.lifted.AbstractTable
import scala.slick.lifted.NonWrappingQuery
import scala.slick.lifted.WrappingQuery
import scala.slick.lifted.CanBeQueryCondition
import scala.slick.driver.JdbcDriver
import scala.slick.driver.H2Driver

trait YYWraper[UT] {
  type NonRep = UT
  def underlying: Rep[UT]
}

trait YYRep[T] extends YYWraper[T]

object YYValue {
  def applyUntyped[T](rep: Rep[T]): YYRep[T] = {
    rep match {
      case c: Column[_] => YYColumn(c)
      case t: AbstractTable[_] => YYTable(t)
      case tup: Projection2[_, _] => YYProjection(tup)
    }
  }

  def apply[T](column: Column[T]): YYColumn[T] = {
    YYColumn(column)
  }

  def apply[T](table: AbstractTable[T]): YYTable[T] = {
    YYTable(table)
  }

  def apply[T, E <: YYRep[T]](rep: Rep[T]): E = {
    YYValue.applyUntyped(rep).asInstanceOf[E]
  }
}

trait YYColumn[T] extends ColumnOps[T] with YYRep[T] {
  val column: Column[T]
  override def underlying = column
  def extendedColumn = new PlainColumnExtensionMethods(column)
  def n = Node(column)
  implicit def om[T2, TR] = OptionMapper2.plain.asInstanceOf[OptionMapper2[T, T, TR, T, T2, TR]]
  //  def asc = YYColumnOrdered(column.asc)
  //  def desc = YYColumnOrdered(column.desc)
}

// order stuff

import scala.slick.lifted.{ Ordered => LOrdered }

class YYOrdering[T](val ord: Ordering[T], val isReverse: Boolean = false) { self =>
  def reverse: YYOrdering[T] =
    new YYOrdering(ord.reverse, !isReverse) {
      override def toOrdered(x: Rep[T]) = new LOrdered(self.toOrdered(x).columns.map {
        case (n, ord) => (n, ord.reverse)
      })
    }

  def toOrdered(x: Rep[T]): LOrdered = YYOrdering.repToOrdered(x)
}

object YYOrdering {
  def apply[T](ord: YYOrdering[T]): YYOrdering[T] =
    ord

  def by[T, S](f: YYRep[T] => YYRep[S])(ord: YYOrdering[S]): YYOrdering[T] = {
    val newOrd = Ordering.by({ (x: T) => f(YYConstColumn(x)(null /*FIXME*/ )).asInstanceOf[YYConstColumn[S]].value })(ord.ord)
    new YYOrdering[T](newOrd) {
      override def toOrdered(x: Rep[T]): LOrdered = {
        val newX = f(YYValue(x)).underlying
        YYOrdering.repToOrdered(newX)
      }
    }
  }

  def repToOrdered[T](rep: Rep[T]): LOrdered = {
    rep match {
      case column: Column[T] => column.asc
      case product: Product => new LOrdered(
        product.productIterator.flatMap { x =>
          repToOrdered(x.asInstanceOf[Rep[_]]).columns
        }.toSeq)
    }
  }

  def fromOrdering[T](ord: Ordering[T]): YYOrdering[T] =
    new YYOrdering(ord)

  val String = fromOrdering(Ordering.String)
  val Int = fromOrdering(Ordering.Int)

  // FIXME generalize
  def Tuple2[T1, T2](ord1: YYOrdering[T1], ord2: YYOrdering[T2]): YYOrdering[(T1, T2)] =
    fromOrdering(Ordering.Tuple2(ord1.ord, ord2.ord))
}

//trait YYOrdered {
//  type UT <: scala.slick.lifted.Ordered
//  def underlying: UT
//}
//
//case class YYColumnOrdered[T](val columnOrdered: ColumnOrdered[T]) extends YYOrdered {
//  override type UT = ColumnOrdered[T]
//  override def underlying = columnOrdered
//  def asc = YYColumnOrdered(columnOrdered.asc)
//  def desc = YYColumnOrdered(columnOrdered.desc)
//}
//
//object YYColumnOrdered {
//  def apply[T](yyColumn: YYColumn[T]): YYColumnOrdered[T] =
//    yyColumn.asc
//}

object YYColumn {
  def apply[T](c: Column[T]): YYColumn[T] = new YYColumn[T] {
    val column = c
  }
}

trait YYTableRow

trait YYTable[T] extends YYRep[T] {
  val table: AbstractTable[T]
  override def underlying = table
}

object YYTable {
  def apply[T](t: AbstractTable[T]): YYTable[T] = {
    new YYTable[T] {
      val table = t
    }
  }
}

class YYConstColumn[T](val constColumn: ConstColumn[T]) extends YYColumn[T] {
  override val column = constColumn
  val value = constColumn.value
}

object YYConstColumn {
  //  def apply[T: TypedType](v: T): YYColumn[T] = YYColumn(ConstColumn[T](v))
  def apply[T: TypedType](v: T): YYColumn[T] = new YYConstColumn(ConstColumn[T](v))
}

trait ColumnOps[T] { self: YYColumn[T] =>

  def isNull: YYColumn[Boolean] = YYColumn(extendedColumn.isNull)
  def isNotNull: YYColumn[Boolean] = YYColumn(extendedColumn.isNotNull)

  def is[T2](e: YYColumn[T2]): YYColumn[Boolean] =
    YYColumn(extendedColumn is e.column)
  def ===[T2](e: YYColumn[T2]): YYColumn[Boolean] =
    YYColumn(extendedColumn === e.column)
  def >[T2](e: YYColumn[T2]): YYColumn[Boolean] =
    YYColumn(extendedColumn > e.column)
  def <[T2](e: YYColumn[T2]): YYColumn[Boolean] =
    YYColumn(extendedColumn < e.column)
}

object YYShape {
  def ident[U] = Shape.impureShape.asInstanceOf[Shape[Rep[U], U, Rep[U]]]
}

trait YYQuery[U] extends QueryOps[U] with YYRep[Seq[U]] {
  val query: Query[Rep[U], U]
  def repValue: Rep[U] = query match {
    case nwq: NonWrappingQuery[_, _] => nwq.unpackable.value
    case wq: WrappingQuery[_, _] => wq.base.value
  }
  type E <: YYRep[U]
  def value: E = YYValue[U, E](repValue)
  override def underlying = query
  object BooleanRepCanBeQueryCondition extends CanBeQueryCondition[Rep[Boolean]] {
    def apply(value: Rep[Boolean]) = value.asInstanceOf[Column[Boolean]]
  }

  // FIXME
  implicit def session = YYUtils.provideSession

  // FIXME it should be generalized to use all drivers
  def first: U = H2Driver.Implicit.queryToQueryInvoker(query).first
  def toSeq: Seq[U] = H2Driver.Implicit.queryToQueryInvoker(query).list.toSeq
}

object YYQuery {
  def create[U](q: Query[Rep[U], U], e: Rep[U]): YYQuery[U] = {
    class YYQueryInst[E1 <: YYRep[U]] extends YYQuery[U] {
      type E = E1
      val query = q
      override def repValue: Rep[U] = e
    }
    e match {
      case col: Column[U] => new YYQueryInst[YYColumn[U]] {}
      case tab: AbstractTable[U] => new YYQueryInst[YYTable[U]] {}
      case tupN: Projection[U] => new YYQueryInst[YYProjection[U]]
    }
  }

  def fromQuery[U](q: Query[Rep[U], U]): YYQuery[U] = {
    val e = YYUtils.valueOfQuery(q)
    create(q, e)
  }

  def apply[U](v: YYRep[U]): YYQuery[U] = {
    val query = Query(v.underlying)(YYShape.ident[U])
    create(query, v.underlying)
  }

  def apiApply[U <: YYRep[_]](v: U): YYQuery[v.NonRep] = {
    apply(v.asInstanceOf[YYRep[v.NonRep]])
  }

}

trait QueryOps[T] { self: YYQuery[T] =>
  private def underlyingProjection[S](projection: YYRep[T] => YYRep[S]): Rep[T] => Rep[S] = {
    def underlyingProjection(x: Rep[T]): Rep[S] = projection({
      YYValue[T, E](x)
    }).underlying

    val res = underlyingProjection _
    res
  }
  def map[S](projection: YYRep[T] => YYRep[S]): YYQuery[S] = {
    val liftedResult = query.map(underlyingProjection(projection))(YYShape.ident[S])
    YYQuery.fromQuery(liftedResult)
  }
  def filter(projection: YYRep[T] => YYRep[Boolean]): YYQuery[T] = {
    val liftedResult = query.filter(underlyingProjection(projection))(BooleanRepCanBeQueryCondition)
    YYQuery.fromQuery(liftedResult)
  }
  def withFilter(projection: YYRep[T] => YYRep[Boolean]): YYQuery[T] =
    filter(projection)
  def flatMap[S](projection: YYRep[T] => YYQuery[S]): YYQuery[S] = {
    def qp(x: Rep[T]): Query[Rep[S], S] = projection({
      YYValue[T, E](x)
    }).query
    YYQuery.fromQuery(query.flatMap(qp))
  }

  // FIXME it considers only ascending order
  //  def repToOrdered[S](rep: Rep[S]): scala.slick.lifted.Ordered = {
  //    rep match {
  //      case column: Column[S] => column.asc
  //      case product: Product => new scala.slick.lifted.Ordered(
  //        product.productIterator.flatMap { x =>
  //          repToOrdered(x.asInstanceOf[Rep[_]]).columns
  //        }.toSeq)
  //    }
  //  }

  //  def sortBy[S](f: YYRep[T] => YYRep[S]): YYQuery[T] = {
  //    val newView = (x: Rep[S]) => repToOrdered(x)
  //    val liftedResult = query.sortBy(underlyingProjection(f))(newView)
  //    //    val liftedResult = query.sortBy(underlyingF)(newView)
  //    YYQuery.fromQuery(liftedResult)
  //  }
  def sortBy[S](f: YYRep[T] => YYRep[S])(ord: YYOrdering[S]): YYQuery[T] = {
    //    val newView = (x: Rep[S]) => repToOrdered(x)
    val newView = (x: Rep[S]) => ord.toOrdered(x)
    val liftedResult = query.sortBy(underlyingProjection(f))(newView)
    //    val liftedResult = query.sortBy(underlyingF)(newView)
    YYQuery.fromQuery(liftedResult)
  }
  def sorted(ord: YYOrdering[T]): YYQuery[T] = {
    val newView = (x: Rep[T]) => ord.toOrdered(x)
    val liftedResult = query.sorted(newView)
    YYQuery.fromQuery(liftedResult)
  }

  //  def sortBy[S <: YYOrdered](f: YYRep[T] => S): YYQuery[T] = ??? // TODO

  // ugly!!!
  def take(i: YYColumn[Int]): YYQuery[T] = {
    val v = i.asInstanceOf[YYConstColumn[Int]].constColumn.value
    YYQuery.fromQuery(query.take(v))
  }

  def drop(i: YYColumn[Int]): YYQuery[T] = {
    val v = i.asInstanceOf[YYConstColumn[Int]].constColumn.value
    YYQuery.fromQuery(query.drop(v))
  }

  def length: YYColumn[Int] =
    YYColumn(query.length)
}

sealed trait YYProjection[T <: Product] extends YYRep[T] with Product {
  def canEqual(that: Any): Boolean = that.isInstanceOf[YYProjection[_]]
}

object YYProjection {
  // TODO generalize it for TupleN
  def apply[T1, T2](tuple2: Projection2[T1, T2]): YYProjection2[T1, T2] = {
    new YYProjection2[T1, T2] {
      def _1 = YYColumn(tuple2._1)
      def _2 = YYColumn(tuple2._2)
      override def underlying = _1.underlying ~ _2.underlying
    }
  }

  // TODO generalize it for TupleN
  def apply[T1, T2](_1: Column[T1], _2: Column[T2]): YYProjection2[T1, T2] = {
    apply(_1 ~ _2)
  }

  // TODO generalize it for TupleN
  def fromYY[T1, T2](_1: YYColumn[T1], _2: YYColumn[T2]): YYProjection2[T1, T2] = {
    apply(_1.underlying ~ _2.underlying)
  }
}

// TODO generalize it for TupleN
trait YYProjection2[T1, T2] extends Product2[YYColumn[T1], YYColumn[T2]] with YYProjection[(T1, T2)] {
  override def toString = "YY(" + _1 + ", " + _2 + ")"
}

object YYUtils {

  def valueOfQuery[U, T <: Rep[U]](query: Query[T, U]): T = query match {
    case nwq: NonWrappingQuery[_, _] => nwq.unpackable.value
    case wq: WrappingQuery[_, _] => wq.base.value
  }

  // FIXME hack!
  val conn = H2Driver.simple.Database.forURL("jdbc:h2:mem:test1", driver = "org.h2.Driver")
  private var session = conn.createSession
  def provideSession: JdbcDriver.Backend#Session = session
  def closeSession {
    session.close
    session = conn.createSession
  }
}

object YYDebug {
  def apply(a: Any) {
    System.err.println(a)
  }
}
