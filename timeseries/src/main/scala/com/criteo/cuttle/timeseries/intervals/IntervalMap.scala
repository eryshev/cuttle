package com.criteo.cuttle.timeseries.intervals

import scala.math.Ordered.orderingToOrdered
import cats._
import de.sciss.fingertree.{FingerTree, Measure}
import FingerTree._
import Bound.{Bottom, Top}

private[timeseries] class MeasureKey[A, B] extends Measure[(A, B), Option[A]] {
  override def toString = "Key"
  val zero: Option[A] = None
  def apply(x: (A, B)) = Some(x._1)

  def |+|(a: Option[A], b: Option[A]) = b
}

private[timeseries] object IntervalMap {

  private[intervals] type Elem[A, B] = (Interval[A], B)

  def apply[A: Ordering, B](elems: Elem[A, B]*)(implicit m: MeasureKey[Interval[A], B]): IntervalMap[A, B] = {
    val sortedElems = elems.sortBy(_._1.lo)
    val isOverlapping = sortedElems.toList.sliding(2).exists {
      case List((Interval(_, hi), v1), (Interval(lo, _), v2)) =>
        hi > lo || (hi == lo && v1 == v2)
      case _ => false
    }
    if (isOverlapping)
      throw new IllegalArgumentException("non-canonical intervals")

    new Impl(FingerTree(sortedElems: _*))
  }

  def empty[A: Ordering, B](implicit m: MeasureKey[Interval[A], B]): IntervalMap[A, B] =
    new Impl(FingerTree.empty)

  private[intervals] class Impl[A: Ordering, B](val tree: FingerTree[Option[Interval[A]], Elem[A, B]])
                                               (implicit val m: MeasureKey[Interval[A], B]) extends IntervalMap[A, B] {
    def toList = tree.toList

    def lower(interval: Interval[A]): (Option[Interval[A]] => Boolean) = {
      case None                  => true
      case Some(Interval(lo, _)) => lo < interval.lo
    }

    def greater(interval: Interval[A]): Option[Interval[A]] => Boolean = {
      case None                  => true
      case Some(Interval(_, hi)) => hi <= interval.hi
    }

    def intersect(interval: Interval[A]) = {
      val (leftOfLow, rightOfLow) = tree.span(lower(interval))

      val withoutLow = leftOfLow.viewRight match {
        case ViewRightCons(_, (Interval(_, hi), v)) if hi > interval.lo =>
          (Interval(interval.lo, hi) -> v) +: rightOfLow
        case _ =>
          rightOfLow
      }
      val (leftOfHigh, rightOfHigh) = withoutLow.span(greater(interval))

      val newTree = rightOfHigh.viewLeft match {
        case ViewLeftCons((Interval(lo, _), v), _) if lo < interval.hi =>
          leftOfHigh :+ (Interval(lo, interval.hi) -> v)
        case _ =>
          leftOfHigh
      }

      new Impl(newTree)
    }

    def update(interval: Interval[A], value: B) = {
      val left = tree.takeWhile(lower(interval))
      val (remainingLeft, newLo) = left.viewRight match {
        case ViewRightCons(init, (Interval(lo, hi), v)) if hi >= interval.lo =>
          if (v == value)
            (init, lo)
          else
            (init :+ (Interval(lo, interval.lo) -> v), interval.lo)
        case _ =>
          (left, interval.lo)
      }

      val right = tree.dropWhile(greater(interval))
      val (newHi, remainingRight) = right.viewLeft match {
        case ViewLeftCons((Interval(lo, hi), v), tail) if lo <= interval.hi =>
          if (v == value)
            (hi, tail)
          else
            (interval.hi, (Interval(interval.hi, hi) -> v) +: tail)
        case _ =>
          (interval.hi, right)
      }

      new Impl((remainingLeft :+ (Interval(newLo, newHi) -> value)) ++ remainingRight)
    }

    def mapKeys[K: Ordering](f: A => K)(implicit m: MeasureKey[Interval[K], B]) =
      new Impl(FingerTree(tree.toList.map {
        case (interval, v) => (interval.map(f) -> v)
      }: _*))

    def whenIsDef[C](other: IntervalMap[A, C]) =
      new Impl(FingerTree((for {
        (interval, _) <- other.toList
        m <- this.intersect(interval).toList
      } yield m): _*))

    def whenIsUndef[C](other: IntervalMap[A, C]) = {
      val bounds = for {
        (Interval(lo, hi), _) <- other.toList
        b <- List(lo, hi)
      } yield b
      val newBounds =
        if (bounds.length == 0) {
          List(Bottom, Top)
        } else {
          val first = bounds.head
          val last = bounds.last
          val middle = bounds.tail.take(bounds.length - 2)
          (if (first == Bottom) List() else List(Bottom, first)) ++
            middle ++
            (if (last == Top) List() else List(last, Top))
        }
      val newIntervals = newBounds.grouped(2).toList.collect { case List(l, h) if l != h => Interval(l, h) }
      new Impl(FingerTree((for {
        interval <- newIntervals
        m <- this.intersect(interval).toList
      } yield m): _*))
    }

    override def equals(other: Any) = other match {
      case otherImpl: Impl[_, _] =>
        this.toList == otherImpl.toList
      case _ =>
        false
    }

    override def toString = toList.toString
  }

  private[timeseries] implicit def eqInstance[K: Ordering, A] = Eq.fromUniversalEquals[IntervalMap[K, A]]

  private[timeseries] implicit class FunctorEmptyInstance[K: Ordering, A](val m: IntervalMap[K, A]) {
    def map[B](f: A => B)(implicit m2: MeasureKey[Interval[K], B]): IntervalMap[K, B] = m match {
      case impl: Impl[K, A] =>
        val elems = impl.tree.toList.map {
          case (itvl, v) => itvl -> f(v)
        }
        elems.foldLeft(IntervalMap.empty[K, B]) {
          case (imap, (interval, value)) =>
            imap.update(interval, value)
        }
    }

    def mapFilter[B](f: A => Option[B])(implicit m2: MeasureKey[Interval[K], B]): IntervalMap[K, B] = m match {
      case impl: Impl[K, A] =>
        val elems = impl.tree.toList.map {
          case (itvl, v) => f(v).map(itvl -> _)
        }
        elems.foldLeft(IntervalMap.empty[K, B]) {
          case (imap, Some((interval, value))) =>
            imap.update(interval, value)
          case (imap, None) =>
            imap
        }
    }

    def collect[B](f: PartialFunction[A, B])(implicit m: MeasureKey[Interval[K], B]): IntervalMap[K, B] = mapFilter(f.lift)

    def filter(f: A => Boolean)(implicit m: MeasureKey[Interval[K], A]): IntervalMap[K, A] = mapFilter(a => Some(a).filter(f))
  }

}

private[timeseries] sealed trait IntervalMap[A, B] {
  import IntervalMap._

  def toList: List[Elem[A, B]]
  def update(interval: Interval[A], value: B): IntervalMap[A, B]
  def intersect(interval: Interval[A]): IntervalMap[A, B]
  def mapKeys[K: Ordering](f: A => K)(implicit m: MeasureKey[Interval[K], B]): IntervalMap[K, B]
  def whenIsDef[C](other: IntervalMap[A, C]): IntervalMap[A, B]
  def whenIsUndef[C](other: IntervalMap[A, C]): IntervalMap[A, B]
}