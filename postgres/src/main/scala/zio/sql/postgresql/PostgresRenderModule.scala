package zio.sql.postgresql

import java.time._
import java.time.format.DateTimeFormatter

import zio.Chunk
import zio.schema._
import zio.schema.StandardType._
import zio.sql.driver.Renderer

trait PostgresRenderModule extends PostgresSqlModule { self =>

  override def renderRead(read: self.Read[_]): String = {
    implicit val render: Renderer = Renderer()
    PostgresRenderer.renderReadImpl(read)
    render.toString
  }

  override def renderUpdate(update: Update[_]): String = {
    implicit val render: Renderer = Renderer()
    PostgresRenderer.renderUpdateImpl(update)
    render.toString
  }

  override def renderInsert[A: Schema](insert: self.Insert[_, A]): String = {
    implicit val render: Renderer = Renderer()
    PostgresRenderer.renderInsertImpl(insert)
    render.toString
  }

  override def renderDelete(delete: Delete[_]): String = {
    implicit val render: Renderer = Renderer()
    PostgresRenderer.renderDeleteImpl(delete)
    render.toString
  }

  object PostgresRenderer {

    def renderInsertImpl[A](insert: Insert[_, A])(implicit render: Renderer, schema: Schema[A]) = {
      render("INSERT INTO ")
      renderTable(insert.table)

      render(" (")
      renderColumnNames(insert.sources)
      render(") VALUES ")

      renderInsertValues(insert.values)
    }

    def renderInsertValues[A](col: Seq[A])(implicit render: Renderer, schema: Schema[A]): Unit =
      // TODO any performance penalty because of toList ?
      col.toList match {
        case head :: Nil  =>
          render("(")
          renderInserValue(head)
          render(");")
        case head :: next =>
          render("(")
          renderInserValue(head)(render, schema)
          render(" ),")
          renderInsertValues(next)
        case Nil          => ()
      }

    def renderInserValue[Z](z: Z)(implicit render: Renderer, schema: Schema[Z]): Unit =
      schema.toDynamic(z) match {
        case DynamicValue.Record(listMap) =>
          listMap.values.toList match {
            case head :: Nil  => renderDynamicValue(head)
            case head :: next =>
              renderDynamicValue(head)
              render(", ")
              renderDynamicValues(next)
            case Nil          => ()
          }
        case value                        => renderDynamicValue(value)
      }

    def renderDynamicValues(dynValues: List[DynamicValue])(implicit render: Renderer): Unit =
      dynValues match {
        case head :: Nil  => renderDynamicValue(head)
        case head :: tail =>
          renderDynamicValue(head)
          render(", ")
          renderDynamicValues(tail)
        case Nil          => ()
      }

    // TODO render each type according to their specifics & test it
    def renderDynamicValue(dynValue: DynamicValue)(implicit render: Renderer): Unit =
      dynValue match {
        case DynamicValue.Primitive(value, typeTag) =>
          // need to do this since StandardType is invariant in A
          StandardType.fromString(typeTag.tag) match {
            case Some(v) =>
              v match {
                case BigDecimalType                             =>
                  render(value)
                case StandardType.InstantType(formatter)        =>
                  render(s"'${formatter.format(value.asInstanceOf[Instant])}'")
                case CharType                                   => render(s"'${value}'")
                case IntType                                    => render(value)
                case StandardType.MonthDayType                  => render(s"'${value}'")
                case BinaryType                                 => render(s"'${value}'")
                case StandardType.MonthType                     => render(s"'${value}'")
                case StandardType.LocalDateTimeType(formatter)  =>
                  render(s"'${formatter.format(value.asInstanceOf[LocalDateTime])}'")
                case UnitType                                   => render("null") // None is encoded as Schema[Unit].transform(_ => None, _ => ())
                case StandardType.YearMonthType                 => render(s"'${value}'")
                case DoubleType                                 => render(value)
                case StandardType.YearType                      => render(s"'${value}'")
                case StandardType.OffsetDateTimeType(formatter) =>
                  render(s"'${formatter.format(value.asInstanceOf[OffsetDateTime])}'")
                case StandardType.ZonedDateTimeType(_)          =>
                  render(s"'${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.asInstanceOf[ZonedDateTime])}'")
                case BigIntegerType                             => render(s"'${value}'")
                case UUIDType                                   => render(s"'${value}'")
                case StandardType.ZoneOffsetType                => render(s"'${value}'")
                case ShortType                                  => render(value)
                case StandardType.LocalTimeType(formatter)      =>
                  render(s"'${formatter.format(value.asInstanceOf[LocalTime])}'")
                case StandardType.OffsetTimeType(formatter)     =>
                  render(s"'${formatter.format(value.asInstanceOf[OffsetTime])}'")
                case LongType                                   => render(value)
                case StringType                                 => render(s"'${value}'")
                case StandardType.PeriodType                    => render(s"'${value}'")
                case StandardType.ZoneIdType                    => render(s"'${value}'")
                case StandardType.LocalDateType(formatter)      =>
                  render(s"'${formatter.format(value.asInstanceOf[LocalDate])}'")
                case BoolType                                   => render(value)
                case DayOfWeekType                              => render(s"'${value}'")
                case FloatType                                  => render(value)
                case StandardType.DurationType                  => render(s"'${value}'")
              }
            case None    => ()
          }
        case DynamicValue.Tuple(left, right)        =>
          renderDynamicValue(left)
          render(", ")
          renderDynamicValue(right)
        case DynamicValue.SomeValue(value)          => renderDynamicValue(value)
        case DynamicValue.NoneValue                 => render("null")
        case _                                      => ()
      }

    def renderColumnNames(sources: SelectionSet[_])(implicit render: Renderer): Unit =
      sources match {
        case SelectionSet.Empty                       => () // table is a collection of at least ONE column
        case SelectionSet.Cons(columnSelection, tail) =>
          val _ = columnSelection.name.map { name =>
            render(quoted(name))
          }
          tail.asInstanceOf[SelectionSet[_]] match {
            case SelectionSet.Empty             => ()
            case next @ SelectionSet.Cons(_, _) =>
              render(", ")
              renderColumnNames(next.asInstanceOf[SelectionSet[_]])(render)
          }
      }

    def renderDeleteImpl(delete: Delete[_])(implicit render: Renderer) = {
      render("DELETE FROM ")
      renderTable(delete.table)
      delete.whereExpr match {
        case Expr.Literal(true) => ()
        case _                  =>
          render(" WHERE ")
          renderExpr(delete.whereExpr)
      }
    }

    def renderUpdateImpl(update: Update[_])(implicit render: Renderer) =
      update match {
        case Update(table, set, whereExpr) =>
          render("UPDATE ")
          renderTable(table)
          render(" SET ")
          renderSet(set)
          render(" WHERE ")
          renderExpr(whereExpr)
      }

    def renderSet(set: List[Set[_, _]])(implicit render: Renderer): Unit =
      set match {
        case head :: tail =>
          renderSetLhs(head.lhs)
          render(" = ")
          renderExpr(head.rhs)
          tail.foreach { setEq =>
            render(", ")
            renderSetLhs(setEq.lhs)
            render(" = ")
            renderExpr(setEq.rhs)
          }
        case Nil          => // TODO restrict Update to not allow empty set
      }

    private[zio] def renderLit[A, B](lit: self.Expr.Literal[_])(implicit render: Renderer): Unit = {
      import PostgresSpecific.PostgresTypeTag._
      import TypeTag._
      lit.typeTag match {
        case TDialectSpecific(tt) =>
          tt match {
            case tt @ TInterval                         => render(tt.cast(lit.value))
            case tt @ TTimestampz                       => render(tt.cast(lit.value))
            case _: PostgresSpecific.PostgresTypeTag[_] => ???
          }
        case TByteArray           =>
          render(
            lit.value.asInstanceOf[Chunk[Byte]].map("""\%03o""" format _).mkString("E\'", "", "\'")
          ) // todo fix `cast` infers correctly but map doesn't work for some reason
        case tt @ TChar           =>
          render("'", tt.cast(lit.value), "'") // todo is this the same as a string? fix escaping
        case tt @ TInstant        => render("TIMESTAMP '", tt.cast(lit.value), "'")
        case tt @ TLocalDate      => render("DATE '", tt.cast(lit.value), "'")
        case tt @ TLocalDateTime  => render("DATE '", tt.cast(lit.value), "'")
        case tt @ TLocalTime      => render(tt.cast(lit.value)) // todo still broken
        case tt @ TOffsetDateTime => render("DATE '", tt.cast(lit.value), "'")
        case tt @ TOffsetTime     => render(tt.cast(lit.value)) // todo still broken
        case tt @ TUUID           => render("'", tt.cast(lit.value), "'")
        case tt @ TZonedDateTime  => render("DATE '", tt.cast(lit.value), "'")

        case TByte       => render(lit.value)           // default toString is probably ok
        case TBigDecimal => render(lit.value)           // default toString is probably ok
        case TBoolean    => render(lit.value)           // default toString is probably ok
        case TDouble     => render(lit.value)           // default toString is probably ok
        case TFloat      => render(lit.value)           // default toString is probably ok
        case TInt        => render(lit.value)           // default toString is probably ok
        case TLong       => render(lit.value)           // default toString is probably ok
        case TShort      => render(lit.value)           // default toString is probably ok
        case TString     => render("'", lit.value, "'") // todo fix escaping
        case _           => render(lit.value)           // todo fix add TypeTag.Nullable[_] =>
      }
    }

    /*
     * PostgreSQL doesn't allow for `tableName.columnName = value` format in update statement,
     * instead requires `columnName = value`.
     */
    private[zio] def renderSetLhs[A, B](expr: self.Expr[_, A, B])(implicit render: Renderer): Unit =
      expr match {
        case Expr.Source(_, column) =>
          column.name match {
            case Some(columnName) => render(quoted(columnName))
            case _                => ()
          }
        case _                      => ()
      }

    private[zio] def quoted(name: String): String = "\"" + name + "\""

    private[zio] def renderExpr[A, B](expr: self.Expr[_, A, B])(implicit render: Renderer): Unit = expr match {
      case Expr.Subselect(subselect)                                                    =>
        render(" (")
        render(renderRead(subselect))
        render(") ")
      case Expr.Source(table, column)                                                   =>
        (table, column.name) match {
          case (tableName: TableName, Some(columnName)) =>
            render(quoted(tableName), ".", quoted(columnName))
          case _                                        => ()
        }
      case Expr.Unary(base, op)                                                         =>
        render(" ", op.symbol)
        renderExpr(base)
      case Expr.Property(base, op)                                                      =>
        renderExpr(base)
        render(" ", op.symbol)
      case Expr.Binary(left, right, op)                                                 =>
        renderExpr(left)
        render(" ", op.symbol, " ")
        renderExpr(right)
      case Expr.Relational(left, right, op)                                             =>
        renderExpr(left)
        render(" ", op.symbol, " ")
        renderExpr(right)
      case Expr.In(value, set)                                                          =>
        renderExpr(value)
        renderReadImpl(set)
      case lit: Expr.Literal[_]                                                         => renderLit(lit)
      case Expr.AggregationCall(p, aggregation)                                         =>
        render(aggregation.name.name, "(")
        renderExpr(p)
        render(")")
      case Expr.ParenlessFunctionCall0(fn)                                              =>
        val _ = render(fn.name)
      case Expr.FunctionCall0(fn)                                                       =>
        render(fn.name.name)
        render("(")
        val _ = render(")")
      case Expr.FunctionCall1(p, fn)                                                    =>
        render(fn.name.name, "(")
        renderExpr(p)
        render(")")
      case Expr.FunctionCall2(p1, p2, fn)                                               =>
        render(fn.name.name, "(")
        renderExpr(p1)
        render(",")
        renderExpr(p2)
        render(")")
      case Expr.FunctionCall3(p1, p2, p3, fn)                                           =>
        render(fn.name.name, "(")
        renderExpr(p1)
        render(",")
        renderExpr(p2)
        render(",")
        renderExpr(p3)
        render(")")
      case Expr.FunctionCall4(p1, p2, p3, p4, fn)                                       =>
        render(fn.name.name, "(")
        renderExpr(p1)
        render(",")
        renderExpr(p2)
        render(",")
        renderExpr(p3)
        render(",")
        renderExpr(p4)
        render(")")
      case Expr.FunctionCall5(param1, param2, param3, param4, param5, function)         =>
        render(function.name.name)
        render("(")
        renderExpr(param1)
        render(",")
        renderExpr(param2)
        render(",")
        renderExpr(param3)
        render(",")
        renderExpr(param4)
        render(",")
        renderExpr(param5)
        render(")")
      case Expr.FunctionCall6(param1, param2, param3, param4, param5, param6, function) =>
        render(function.name.name)
        render("(")
        renderExpr(param1)
        render(",")
        renderExpr(param2)
        render(",")
        renderExpr(param3)
        render(",")
        renderExpr(param4)
        render(",")
        renderExpr(param5)
        render(",")
        renderExpr(param6)
        render(")")
      case Expr.FunctionCall7(
            param1,
            param2,
            param3,
            param4,
            param5,
            param6,
            param7,
            function
          ) =>
        render(function.name.name)
        render("(")
        renderExpr(param1)
        render(",")
        renderExpr(param2)
        render(",")
        renderExpr(param3)
        render(",")
        renderExpr(param4)
        render(",")
        renderExpr(param5)
        render(",")
        renderExpr(param6)
        render(",")
        renderExpr(param7)
        render(")")
    }

    private[zio] def renderReadImpl(read: self.Read[_])(implicit render: Renderer): Unit =
      read match {
        case Read.Mapped(read, _) => renderReadImpl(read)

        case read0 @ Read.Subselect(_, _, _, _, _, _, _, _) =>
          object Dummy {
            type F
            type Repr
            type Source
            type Head
            type Tail <: SelectionSet[Source]
          }
          val read =
            read0.asInstanceOf[Read.Subselect[Dummy.F, Dummy.Repr, Dummy.Source, Dummy.Source, Dummy.Head, Dummy.Tail]]
          import read._

          render("SELECT ")
          renderSelection(selection.value)
          table.foreach { t =>
            render(" FROM ")
            renderTable(t)
          }
          whereExpr match {
            case Expr.Literal(true) => ()
            case _                  =>
              render(" WHERE ")
              renderExpr(whereExpr)
          }
          groupByExprs match {
            case Read.ExprSet.ExprCons(_, _) =>
              render(" GROUP BY ")
              renderExprList(groupByExprs)

              havingExpr match {
                case Expr.Literal(true) => ()
                case _                  =>
                  render(" HAVING ")
                  renderExpr(havingExpr)
              }
            case Read.ExprSet.NoExpr         => ()
          }
          orderByExprs match {
            case _ :: _ =>
              render(" ORDER BY ")
              renderOrderingList(orderByExprs)
            case Nil    => ()
          }
          limit match {
            case Some(limit) => render(" LIMIT ", limit)
            case None        => ()
          }
          offset match {
            case Some(offset) => render(" OFFSET ", offset)
            case None         => ()
          }

        case Read.Union(left, right, distinct) =>
          renderReadImpl(left)
          render(" UNION ")
          if (!distinct) render("ALL ")
          renderReadImpl(right)

        case Read.Literal(values) =>
          render(" (", values.mkString(","), ") ") // todo fix needs escaping
      }

    def renderExprList(expr: Read.ExprSet[_])(implicit render: Renderer): Unit =
      expr match {
        case Read.ExprSet.ExprCons(head, tail) =>
          renderExpr(head)
          tail.asInstanceOf[Read.ExprSet[_]] match {
            case Read.ExprSet.ExprCons(_, _) =>
              render(", ")
              renderExprList(tail.asInstanceOf[Read.ExprSet[_]])
            case Read.ExprSet.NoExpr         => ()
          }
        case Read.ExprSet.NoExpr               => ()
      }

    def renderOrderingList(expr: List[Ordering[Expr[_, _, _]]])(implicit render: Renderer): Unit =
      expr match {
        case head :: tail =>
          head match {
            case Ordering.Asc(value)  => renderExpr(value)
            case Ordering.Desc(value) =>
              renderExpr(value)
              render(" DESC")
          }
          tail match {
            case _ :: _ =>
              render(", ")
              renderOrderingList(tail)
            case Nil    => ()
          }
        case Nil          => ()
      }

    def renderSelection[A](selectionSet: SelectionSet[A])(implicit render: Renderer): Unit =
      selectionSet match {
        case cons0 @ SelectionSet.Cons(_, _) =>
          object Dummy {
            type Source
            type A
            type B <: SelectionSet[Source]
          }
          val cons = cons0.asInstanceOf[SelectionSet.Cons[Dummy.Source, Dummy.A, Dummy.B]]
          import cons._
          renderColumnSelection(head)
          if (tail != SelectionSet.Empty) {
            render(", ")
            renderSelection(tail)
          }
        case SelectionSet.Empty              => ()
      }

    def renderColumnSelection[A, B](columnSelection: ColumnSelection[A, B])(implicit render: Renderer): Unit =
      columnSelection match {
        case ColumnSelection.Constant(value, name) =>
          render(value) // todo fix escaping
          name match {
            case Some(name) => render(" AS ", name)
            case None       => ()
          }
        case ColumnSelection.Computed(expr, name)  =>
          renderExpr(expr)
          name match {
            case Some(name) =>
              Expr.exprName(expr) match {
                case Some(sourceName) if name != sourceName => render(" AS ", name)
                case _                                      => ()
              }
            case _          => () // todo what do we do if we don't have a name?
          }
      }

    def renderTable(table: Table)(implicit render: Renderer): Unit =
      table match {
        case Table.DialectSpecificTable(tableExtension) =>
          tableExtension match {
            case PostgresSpecific.PostgresSpecificTable.LateraLTable(left, derivedTable) =>
              renderTable(left)

              render(" ,lateral ")

              renderTable(derivedTable)
          }
        // The outer reference in this type test cannot be checked at run time?!
        case sourceTable: self.Table.Source             => render(quoted(sourceTable.name))
        case Table.DerivedTable(read, name)             =>
          render(" ( ")
          render(renderRead(read.asInstanceOf[Read[_]]))
          render(" ) ")
          render(name)
        case Table.Joined(joinType, left, right, on)    =>
          renderTable(left)
          render(joinType match {
            case JoinType.Inner      => " INNER JOIN "
            case JoinType.LeftOuter  => " LEFT JOIN "
            case JoinType.RightOuter => " RIGHT JOIN "
            case JoinType.FullOuter  => " OUTER JOIN "
          })
          renderTable(right)
          render(" ON ")
          renderExpr(on)
          render(" ")
      }
  }
}
