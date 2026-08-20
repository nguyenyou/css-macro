package www

import scala.collection.mutable
import scala.quoted.*
import scala.reflect.NameTransformer

/** Runtime carrier for statically known CSS class-name fields. */
final class CssClassNames() extends Selectable {
  transparent inline def selectDynamic(inline name: String): String =
    NameTransformer.decode(name)
}

/** Flattens CSS nesting while preserving declarations, at-rules, and comments.
  */
object CssFlattener {
  private sealed trait Item

  private final case class Declaration(value: String) extends Item

  private final case class Raw(value: String) extends Item

  private final case class QualifiedRule(
      selector: String,
      items: List[Item]
  ) extends Item

  private final case class AtRule(
      header: String,
      items: Option[List[Item]]
  ) extends Item

  private enum Delimiter {
    case OpenBrace(position: Int)
    case Semicolon(position: Int)
    case CloseBrace(position: Int)
    case End
  }

  private final case class ParseFailure(message: String)
      extends RuntimeException(message)

  private[www] final case class Analysis(
      css: String,
      classNames: List[String]
  )

  def flatten(css: String): String = {
    process(css, "", collectClassNames = false) match {
      case Right(analysis) => analysis.css
      case Left(error)     => throw IllegalArgumentException(error)
    }
  }

  private[www] def analyze(
      css: String,
      dynamicMarkerPrefix: String
  ): Either[String, Analysis] =
    process(css, dynamicMarkerPrefix, collectClassNames = true)

  private def process(
      css: String,
      dynamicMarkerPrefix: String,
      collectClassNames: Boolean
  ): Either[String, Analysis] = {
    try {
      val items = Parser(css).parse()
      Right(Renderer(dynamicMarkerPrefix, collectClassNames).render(items))
    } catch {
      case failure: ParseFailure => Left(failure.message)
    }
  }

  private final class Parser(content: String) {
    private val length = content.length
    private var index = 0

    def parse(): List[Item] = parseItems(expectClosingBrace = false)

    private def parseItems(expectClosingBrace: Boolean): List[Item] = {
      val items = mutable.ListBuffer.empty[Item]
      var done = false

      while (!done) {
        skipWhitespace()

        if (index >= length) {
          if (expectClosingBrace) {
            fail("Unbalanced braces: unclosed '{' before end of input")
          }
          done = true
        } else if (content.charAt(index) == '}') {
          if (expectClosingBrace) {
            index += 1
            done = true
          } else {
            fail(s"Unexpected '}' at position $index - no matching '{'")
          }
        } else {
          val start = index
          findDelimiter(start) match {
            case Delimiter.OpenBrace(position) =>
              val header = content.substring(start, position).trim
              if (containsOnlyTrivia(header)) {
                fail(s"Missing selector before '{' at position $position")
              }

              index = position + 1
              val body = parseItems(expectClosingBrace = true)
              if (isAtRule(header)) {
                items += AtRule(header, Some(body))
              } else {
                items += QualifiedRule(header, body)
              }

            case Delimiter.Semicolon(position) =>
              val value = content.substring(start, position).trim
              index = position + 1
              if (value.nonEmpty) {
                if (containsOnlyTrivia(value)) {
                  items += Raw(s"$value;")
                } else if (isAtRule(value)) {
                  items += AtRule(value, None)
                } else {
                  items += Declaration(value)
                }
              }

            case Delimiter.CloseBrace(position) =>
              val value = content.substring(start, position).trim
              if (value.nonEmpty) {
                if (containsOnlyTrivia(value)) {
                  items += Raw(value)
                } else if (isAtRule(value)) {
                  items += AtRule(value, None)
                } else {
                  items += Declaration(value)
                }
              }

              if (expectClosingBrace) {
                index = position + 1
                done = true
              } else {
                fail(s"Unexpected '}' at position $position - no matching '{'")
              }

            case Delimiter.End =>
              val value = content.substring(start).trim
              if (value.nonEmpty) {
                if (containsOnlyTrivia(value)) {
                  items += Raw(value)
                } else if (expectClosingBrace) {
                  fail("Unbalanced braces: unclosed '{' before end of input")
                } else {
                  fail(s"Expected '{' or ';' after '$value'")
                }
              } else if (expectClosingBrace) {
                fail("Unbalanced braces: unclosed '{' before end of input")
              }
              index = length
              done = true
          }
        }
      }

      items.toList
    }

    private def findDelimiter(start: Int): Delimiter = {
      var cursor = start
      var parenthesisDepth = 0
      var bracketDepth = 0
      var result: Delimiter = Delimiter.End
      var done = false

      while (cursor < length && !done) {
        val current = content.charAt(cursor)
        if (current == '"' || current == '\'') {
          cursor = skipString(cursor, current)
        } else if (
          current == '/' && cursor + 1 < length && content.charAt(
            cursor + 1
          ) == '*'
        ) {
          cursor = skipComment(cursor)
        } else if (current == '\\') {
          cursor = skipEscape(cursor)
        } else {
          current match {
            case '(' =>
              parenthesisDepth += 1
              cursor += 1
            case ')' =>
              if (parenthesisDepth == 0) {
                fail(s"Unexpected ')' at position $cursor")
              }
              parenthesisDepth -= 1
              cursor += 1
            case '[' =>
              bracketDepth += 1
              cursor += 1
            case ']' =>
              if (bracketDepth == 0) {
                fail(s"Unexpected ']' at position $cursor")
              }
              bracketDepth -= 1
              cursor += 1
            case '{' if parenthesisDepth == 0 && bracketDepth == 0 =>
              val prefix = content.substring(start, cursor)
              if (isCustomProperty(prefix)) {
                cursor = skipComponentBlock(cursor)
              } else {
                result = Delimiter.OpenBrace(cursor)
                done = true
              }
            case ';' if parenthesisDepth == 0 && bracketDepth == 0 =>
              result = Delimiter.Semicolon(cursor)
              done = true
            case '}' if parenthesisDepth == 0 && bracketDepth == 0 =>
              result = Delimiter.CloseBrace(cursor)
              done = true
            case _ =>
              cursor += 1
          }
        }
      }

      if (!done) {
        if (parenthesisDepth != 0) {
          fail("Unclosed '(' before end of input")
        }
        if (bracketDepth != 0) {
          fail("Unclosed '[' before end of input")
        }
      }

      result
    }

    private def skipString(start: Int, quote: Char): Int = {
      var cursor = start + 1
      var closed = false

      while (cursor < length && !closed) {
        content.charAt(cursor) match {
          case '\\'                  => cursor = Math.min(cursor + 2, length)
          case char if char == quote =>
            cursor += 1
            closed = true
          case _ => cursor += 1
        }
      }

      if (!closed) {
        fail(s"Unclosed string starting at position $start")
      }
      cursor
    }

    private def skipComment(start: Int): Int = {
      var cursor = start + 2
      var closed = false

      while (cursor + 1 < length && !closed) {
        if (
          content.charAt(cursor) == '*' && content.charAt(cursor + 1) == '/'
        ) {
          cursor += 2
          closed = true
        } else {
          cursor += 1
        }
      }

      if (!closed) {
        fail(s"Unclosed comment starting at position $start")
      }
      cursor
    }

    private def skipEscape(start: Int): Int = {
      var cursor = start + 1
      val hexStart = cursor

      while (
        cursor < length &&
        cursor - hexStart < 6 &&
        isHexDigit(content.charAt(cursor))
      ) {
        cursor += 1
      }

      if (cursor > hexStart) {
        if (cursor < length && content.charAt(cursor).isWhitespace) {
          cursor += 1
        }
      } else if (cursor < length) {
        cursor += 1
      } else {
        fail(s"Invalid escape at position $start")
      }
      cursor
    }

    private def skipComponentBlock(start: Int): Int = {
      var cursor = start + 1
      var depth = 1

      while (cursor < length && depth > 0) {
        val current = content.charAt(cursor)
        if (current == '"' || current == '\'') {
          cursor = skipString(cursor, current)
        } else if (
          current == '/' && cursor + 1 < length && content.charAt(
            cursor + 1
          ) == '*'
        ) {
          cursor = skipComment(cursor)
        } else if (current == '\\') {
          cursor = skipEscape(cursor)
        } else {
          current match {
            case '{' =>
              depth += 1
              cursor += 1
            case '}' =>
              depth -= 1
              cursor += 1
            case _ => cursor += 1
          }
        }
      }

      if (depth != 0) {
        fail(s"Unclosed custom-property block starting at position $start")
      }
      cursor
    }

    private def isCustomProperty(prefix: String): Boolean = {
      val significant = removeLeadingTrivia(prefix)
      significant.startsWith("--") && significant.indexOf(':') >= 2
    }

    private def isHexDigit(char: Char): Boolean = {
      (char >= '0' && char <= '9') ||
      (char >= 'a' && char <= 'f') ||
      (char >= 'A' && char <= 'F')
    }

    private def isAtRule(value: String): Boolean =
      removeLeadingTrivia(value).startsWith("@")

    private def containsOnlyTrivia(value: String): Boolean = {
      var cursor = 0
      var onlyTrivia = true

      while (cursor < value.length && onlyTrivia) {
        if (value.charAt(cursor).isWhitespace) {
          cursor += 1
        } else if (
          value.charAt(cursor) == '/' &&
          cursor + 1 < value.length &&
          value.charAt(cursor + 1) == '*'
        ) {
          val close = value.indexOf("*/", cursor + 2)
          if (close < 0) {
            onlyTrivia = false
          } else {
            cursor = close + 2
          }
        } else {
          onlyTrivia = false
        }
      }

      onlyTrivia
    }

    private def removeLeadingTrivia(value: String): String = {
      var cursor = 0
      var scanning = true

      while (cursor < value.length && scanning) {
        if (value.charAt(cursor).isWhitespace) {
          cursor += 1
        } else if (
          value.charAt(cursor) == '/' &&
          cursor + 1 < value.length &&
          value.charAt(cursor + 1) == '*'
        ) {
          val close = value.indexOf("*/", cursor + 2)
          if (close < 0) {
            scanning = false
          } else {
            cursor = close + 2
          }
        } else {
          scanning = false
        }
      }

      value.substring(cursor).trim
    }

    private def skipWhitespace(): Unit = {
      while (index < length && content.charAt(index).isWhitespace) {
        index += 1
      }
    }

    private def fail(message: String): Nothing = throw ParseFailure(message)
  }

  private final class Renderer(
      dynamicMarkerPrefix: String,
      shouldCollectClassNames: Boolean
  ) {
    private val output = StringBuilder()
    private val classNames = mutable.LinkedHashSet.empty[String]

    def render(items: List[Item]): Analysis = {
      renderItems(items, Nil, indentation = 0)
      Analysis(output.toString, classNames.toList)
    }

    private def renderItems(
        items: List[Item],
        parentSelectors: List[String],
        indentation: Int
    ): Unit = {
      val pending = mutable.ListBuffer.empty[Item]

      def flushPending(): Unit = {
        if (pending.nonEmpty) {
          if (parentSelectors.nonEmpty) {
            renderSelectorBlock(parentSelectors, pending.toList, indentation)
          } else {
            renderLooseItems(pending.toList, indentation)
          }
          pending.clear()
        }
      }

      items.foreach {
        case declaration: Declaration => pending += declaration
        case raw: Raw                 => pending += raw
        case atRule @ AtRule(_, None) => pending += atRule
        case rule: QualifiedRule      =>
          flushPending()
          renderQualifiedRule(rule, parentSelectors, indentation)
        case atRule: AtRule =>
          flushPending()
          renderAtRule(atRule, parentSelectors, indentation)
      }

      flushPending()
    }

    private def renderQualifiedRule(
        rule: QualifiedRule,
        parentSelectors: List[String],
        indentation: Int
    ): Unit = {
      val childSelectors = splitSelectorList(rule.selector)
      val selectors = combineSelectors(parentSelectors, childSelectors)
      if (shouldCollectClassNames) {
        selectors.foreach(collectClassNames)
      }

      if (rule.items.isEmpty) {
        renderSelectorBlock(selectors, Nil, indentation)
      } else {
        renderItems(rule.items, selectors, indentation)
      }
    }

    private def renderAtRule(
        atRule: AtRule,
        parentSelectors: List[String],
        indentation: Int
    ): Unit = {
      appendIndent(indentation)
      output.append(atRule.header)
      output.append(" {\n")
      atRule.items.foreach(renderItems(_, parentSelectors, indentation + 1))
      appendIndent(indentation)
      output.append("}\n")
    }

    private def renderSelectorBlock(
        selectors: List[String],
        items: List[Item],
        indentation: Int
    ): Unit = {
      appendIndent(indentation)
      output.append(selectors.mkString(", "))
      output.append(" {\n")
      renderLeafItems(items, indentation + 1)
      appendIndent(indentation)
      output.append("}\n")
    }

    private def renderLooseItems(items: List[Item], indentation: Int): Unit = {
      renderLeafItems(items, indentation)
    }

    private def renderLeafItems(items: List[Item], indentation: Int): Unit = {
      items.foreach {
        case Declaration(value) =>
          appendIndent(indentation)
          output.append(value)
          output.append(";\n")
        case Raw(value) =>
          appendIndent(indentation)
          output.append(value)
          output.append("\n")
        case AtRule(header, None) =>
          appendIndent(indentation)
          output.append(header)
          output.append(";\n")
        case _ =>
          throw IllegalStateException("Nested rule reached leaf renderer")
      }
    }

    private def combineSelectors(
        parentSelectors: List[String],
        childSelectors: List[String]
    ): List[String] = {
      if (parentSelectors.isEmpty) {
        childSelectors
      } else {
        parentSelectors.flatMap { parent =>
          childSelectors.map { child =>
            if (containsParentReference(child)) {
              replaceParentReferences(child, parent)
            } else {
              s"$parent $child"
            }
          }
        }
      }
    }

    private def splitSelectorList(selector: String): List[String] = {
      val selectors = mutable.ListBuffer.empty[String]
      var start = 0
      var cursor = 0
      var parenthesisDepth = 0
      var bracketDepth = 0

      while (cursor < selector.length) {
        val current = selector.charAt(cursor)
        if (current == '"' || current == '\'') {
          cursor = skipQuoted(selector, cursor, current)
        } else if (
          current == '/' &&
          cursor + 1 < selector.length &&
          selector.charAt(cursor + 1) == '*'
        ) {
          cursor = skipComment(selector, cursor)
        } else if (current == '\\') {
          cursor = skipEscape(selector, cursor)
        } else {
          current match {
            case '(' =>
              parenthesisDepth += 1
              cursor += 1
            case ')' =>
              parenthesisDepth -= 1
              cursor += 1
            case '[' =>
              bracketDepth += 1
              cursor += 1
            case ']' =>
              bracketDepth -= 1
              cursor += 1
            case ',' if parenthesisDepth == 0 && bracketDepth == 0 =>
              val part = selector.substring(start, cursor).trim
              if (part.nonEmpty) {
                selectors += part
              }
              cursor += 1
              start = cursor
            case _ => cursor += 1
          }
        }
      }

      val last = selector.substring(start).trim
      if (last.nonEmpty) {
        selectors += last
      }
      selectors.toList
    }

    private def containsParentReference(selector: String): Boolean = {
      var cursor = 0
      var bracketDepth = 0
      var found = false

      while (cursor < selector.length && !found) {
        val current = selector.charAt(cursor)
        if (current == '"' || current == '\'') {
          cursor = skipQuoted(selector, cursor, current)
        } else if (
          current == '/' &&
          cursor + 1 < selector.length &&
          selector.charAt(cursor + 1) == '*'
        ) {
          cursor = skipComment(selector, cursor)
        } else if (current == '\\') {
          cursor = skipEscape(selector, cursor)
        } else {
          current match {
            case '[' =>
              bracketDepth += 1
              cursor += 1
            case ']' =>
              bracketDepth -= 1
              cursor += 1
            case '&' if bracketDepth == 0 => found = true
            case _                        => cursor += 1
          }
        }
      }

      found
    }

    private def replaceParentReferences(
        selector: String,
        parent: String
    ): String = {
      val replaced = StringBuilder(selector.length + parent.length)
      var cursor = 0
      var bracketDepth = 0

      while (cursor < selector.length) {
        val current = selector.charAt(cursor)
        if (current == '"' || current == '\'') {
          val end = skipQuoted(selector, cursor, current)
          replaced.append(selector.substring(cursor, end))
          cursor = end
        } else if (
          current == '/' &&
          cursor + 1 < selector.length &&
          selector.charAt(cursor + 1) == '*'
        ) {
          val end = skipComment(selector, cursor)
          replaced.append(selector.substring(cursor, end))
          cursor = end
        } else if (current == '\\') {
          val end = skipEscape(selector, cursor)
          replaced.append(selector.substring(cursor, end))
          cursor = end
        } else {
          current match {
            case '[' =>
              bracketDepth += 1
              replaced.append(current)
              cursor += 1
            case ']' =>
              bracketDepth -= 1
              replaced.append(current)
              cursor += 1
            case '&' if bracketDepth == 0 =>
              replaced.append(parent)
              cursor += 1
            case _ =>
              replaced.append(current)
              cursor += 1
          }
        }
      }

      replaced.toString
    }

    private def collectClassNames(selector: String): Unit = {
      var cursor = 0
      var bracketDepth = 0

      while (cursor < selector.length) {
        val current = selector.charAt(cursor)
        if (current == '"' || current == '\'') {
          cursor = skipQuoted(selector, cursor, current)
        } else if (
          current == '/' &&
          cursor + 1 < selector.length &&
          selector.charAt(cursor + 1) == '*'
        ) {
          cursor = skipComment(selector, cursor)
        } else if (current == '\\') {
          cursor = skipEscape(selector, cursor)
        } else {
          current match {
            case '[' =>
              bracketDepth += 1
              cursor += 1
            case ']' =>
              bracketDepth -= 1
              cursor += 1
            case '.'
                if bracketDepth == 0 && isIdentifierStart(
                  selector,
                  cursor + 1
                ) =>
              val (name, rawName, end) = readIdentifier(selector, cursor + 1)
              if (
                name.nonEmpty &&
                (dynamicMarkerPrefix.isEmpty || !rawName.contains(
                  dynamicMarkerPrefix
                ))
              ) {
                classNames += name
              }
              cursor = end
            case _ => cursor += 1
          }
        }
      }
    }

    private def readIdentifier(
        value: String,
        start: Int
    ): (String, String, Int) = {
      val decoded = StringBuilder()
      var cursor = start
      var scanning = true

      while (cursor < value.length && scanning) {
        val current = value.charAt(cursor)
        if (isIdentifierPart(current)) {
          decoded.append(current)
          cursor += 1
        } else if (current == '\\' && cursor + 1 < value.length) {
          val (escaped, end) = readEscape(value, cursor)
          decoded.append(escaped)
          cursor = end
        } else {
          scanning = false
        }
      }

      (decoded.toString, value.substring(start, cursor), cursor)
    }

    private def readEscape(value: String, start: Int): (String, Int) = {
      var cursor = start + 1
      val hexStart = cursor

      while (
        cursor < value.length &&
        cursor - hexStart < 6 &&
        isHexDigit(value.charAt(cursor))
      ) {
        cursor += 1
      }

      if (cursor > hexStart) {
        val parsedCodePoint = Integer.parseInt(
          value.substring(hexStart, cursor),
          16
        )
        val codePoint =
          if (
            parsedCodePoint == 0 ||
            parsedCodePoint > Character.MAX_CODE_POINT ||
            (parsedCodePoint >= Character.MIN_SURROGATE.toInt &&
              parsedCodePoint <= Character.MAX_SURROGATE.toInt)
          ) {
            0xfffd
          } else {
            parsedCodePoint
          }
        if (cursor < value.length && value.charAt(cursor).isWhitespace) {
          cursor += 1
        }
        (String(Character.toChars(codePoint)), cursor)
      } else if (cursor < value.length) {
        (value.charAt(cursor).toString, cursor + 1)
      } else {
        ("", cursor)
      }
    }

    private def isIdentifierStart(value: String, position: Int): Boolean = {
      position < value.length && {
        val char = value.charAt(position)
        char == '-' ||
        char == '_' ||
        char == '\\' ||
        char.isLetter ||
        char >= 128
      }
    }

    private def isIdentifierPart(char: Char): Boolean = {
      char == '-' ||
      char == '_' ||
      char.isLetterOrDigit ||
      char >= 128
    }

    private def isHexDigit(char: Char): Boolean = {
      (char >= '0' && char <= '9') ||
      (char >= 'a' && char <= 'f') ||
      (char >= 'A' && char <= 'F')
    }

    private def skipQuoted(value: String, start: Int, quote: Char): Int = {
      var cursor = start + 1
      var closed = false

      while (cursor < value.length && !closed) {
        value.charAt(cursor) match {
          case '\\' => cursor = Math.min(cursor + 2, value.length)
          case char if char == quote =>
            cursor += 1
            closed = true
          case _ => cursor += 1
        }
      }
      cursor
    }

    private def skipComment(value: String, start: Int): Int = {
      val close = value.indexOf("*/", start + 2)
      if (close < 0) value.length else close + 2
    }

    private def skipEscape(value: String, start: Int): Int = {
      var cursor = start + 1
      val hexStart = cursor

      while (
        cursor < value.length &&
        cursor - hexStart < 6 &&
        isHexDigit(value.charAt(cursor))
      ) {
        cursor += 1
      }

      if (cursor > hexStart) {
        if (cursor < value.length && value.charAt(cursor).isWhitespace) {
          cursor += 1
        }
      } else if (cursor < value.length) {
        cursor += 1
      }
      cursor
    }

    private def appendIndent(indentation: Int): Unit = {
      var count = 0
      while (count < indentation) {
        output.append("  ")
        count += 1
      }
    }
  }
}

object CssMacro {
  private val dynamicMarkerPrefix = "__CSS_MACRO_INTERPOLATION_"

  /** Enables assigning a CSS result directly to `String` when callers opt in to
    * `scala.language.implicitConversions`.
    */
  given cssResultToString[T]: Conversion[(css: String, classNames: T), String] =
    result => result.css

  extension (inline sc: StringContext) {
    transparent inline def css(inline args: Any*): Any = ${
      cssInterpolatorImpl('sc, 'args)
    }
  }

  private def cssInterpolatorImpl(
      scExpr: Expr[StringContext],
      argsExpr: Expr[Seq[Any]]
  )(using Quotes): Expr[Any] = {
    val parts: List[String] = scExpr match {
      case '{ StringContext(${ Varargs(Exprs(parts)) }*) } => parts.toList
      case _                                               =>
        quotes.reflect.report.errorAndAbort(
          "css interpolator requires literal string parts"
        )
    }

    val skeleton = buildSkeleton(parts)
    val analysis = CssFlattener.analyze(skeleton, dynamicMarkerPrefix) match {
      case Right(value) => value
      case Left(error)  =>
        quotes.reflect.report.errorAndAbort(s"CSS syntax error: $error")
    }

    val (classNamesExpr, classNamesType) =
      buildClassNames(analysis.classNames)

    val cssStringExpr = if (parts.length == 1) {
      buildStaticCssExpression(analysis.css)
    } else {
      '{
        val partsIterator = $scExpr.parts.iterator
        val argumentsIterator = $argsExpr.iterator
        val builder = StringBuilder(${ Expr(skeleton.length + 64) })
        while (partsIterator.hasNext) {
          builder.append(partsIterator.next())
          if (argumentsIterator.hasNext) {
            builder.append(argumentsIterator.next().toString)
          }
        }
        CssFlattener.flatten(builder.toString)
      }
    }

    buildResult(cssStringExpr, classNamesExpr, classNamesType)
  }

  private def buildSkeleton(parts: List[String]): String = {
    val builder = StringBuilder(parts.iterator.map(_.length).sum + 64)
    var index = 0

    parts.foreach { part =>
      if (index > 0) {
        builder.append(dynamicMarkerPrefix)
        builder.append(index - 1)
        builder.append("__")
      }
      builder.append(part)
      index += 1
    }
    builder.toString
  }

  private def buildStaticCssExpression(
      css: String
  )(using Quotes): Expr[String] = {
    val maximumChunkLength = 16000
    if (css.length <= maximumChunkLength) {
      Expr(css)
    } else {
      val chunksExpression = Expr.ofList(
        css.grouped(maximumChunkLength).map(Expr(_)).toList
      )
      '{
        val builder = StringBuilder(${ Expr(css.length) })
        $chunksExpression.foreach(builder.append)
        builder.toString
      }
    }
  }

  private def buildClassNames(
      classNames: List[String]
  )(using q: Quotes): (Expr[Any], q.reflect.TypeRepr) = {
    val baseType = q.reflect.TypeRepr.of[CssClassNames]
    val refinementBlockSize = 128
    var level: IndexedSeq[q.reflect.TypeRepr] = classNames
      .grouped(refinementBlockSize)
      .map { names =>
        names.foldLeft(baseType) { (owner, name) =>
          q.reflect.Refinement(owner, name, q.reflect.TypeRepr.of[String])
        }
      }
      .toIndexedSeq

    while (level.length > 1) {
      val next = mutable.ArrayBuffer.empty[q.reflect.TypeRepr]
      var index = 0
      while (index < level.length) {
        if (index + 1 < level.length) {
          next += q.reflect.AndType(level(index), level(index + 1))
        } else {
          next += level(index)
        }
        index += 2
      }
      level = next.toIndexedSeq
    }

    val classNamesType = level.headOption.getOrElse(baseType)
    val expression = classNamesType.asType match {
      case '[classNamesType] =>
        '{ CssClassNames().asInstanceOf[classNamesType] }
    }
    (expression, classNamesType)
  }

  private def buildResult(
      cssExpr: Expr[String],
      classNamesExpr: Expr[Any],
      classNamesType: Any
  )(using q: Quotes): Expr[Any] = {
    classNamesType.asInstanceOf[q.reflect.TypeRepr].asType match {
      case '[classNamesType] =>
        '{
          (
            css = $cssExpr,
            classNames = $classNamesExpr.asInstanceOf[classNamesType]
          )
        }
    }
  }
}
