//> using scala 3.7.3
//> using test.dep org.scalameta::munit::1.2.1

package www

import CssMacro.css

class CssMacroTest extends munit.FunSuite {

  test("extracts single class name") {
    val styles = css".button { color: red; }"
    assertEquals(styles.classNames.button, "button")
  }

  test("extracts multiple class names") {
    val styles = css"""
      .container { padding: 8px; }
      .header { font-size: 24px; }
      .footer { margin-top: 16px; }
    """
    assertEquals(styles.classNames.container, "container")
    assertEquals(styles.classNames.header, "header")
    assertEquals(styles.classNames.footer, "footer")
  }

  test("handles hyphenated class names with backticks") {
    val styles = css"""
      .my-component { display: block; }
      .callout--warning { background: yellow; }
    """
    assertEquals(styles.classNames.`my-component`, "my-component")
    assertEquals(styles.classNames.`callout--warning`, "callout--warning")
  }

  test("returns correct css string without interpolation") {
    val styles = css".box { width: 100px; }"
    assert(styles.css.contains(".box"))
    assert(styles.css.contains("width: 100px"))
  }

  test("returns correct css string with interpolation") {
    val width = "200px"
    val color = "blue"
    val styles = css".box { width: $width; color: $color; }"
    assert(styles.css.contains("width: 200px"))
    assert(styles.css.contains("color: blue"))
  }

  test("handles complex interpolation expressions") {
    val baseSize = 8
    val styles =
      css".box { padding: ${baseSize * 2}px; margin: ${baseSize}px; }"
    assert(styles.css.contains("padding: 16px"))
    assert(styles.css.contains("margin: 8px"))
  }

  test("handles empty css") {
    val styles = css""
    assertEquals(styles.css, "")
  }

  test("handles css with no class names") {
    val styles = css":host { display: block; }"
    assert(styles.css.contains(":host"))
    assert(styles.css.contains("display: block"))
  }

  test("handles nested class selectors") {
    val styles = css"""
      .parent {
        .child { color: red; }
      }
    """
    assertEquals(styles.classNames.parent, "parent")
    assertEquals(styles.classNames.child, "child")
  }

  test("deduplicates repeated class names") {
    val styles = css"""
      .button { color: red; }
      .button:hover { color: blue; }
    """
    assertEquals(styles.classNames.button, "button")
  }

  test("handles multiline css with interpolation") {
    val primary = "#3498db"
    val padding = "16px"
    val styles = css"""
      .card {
        padding: $padding;
        background: white;
      }
      .card-header {
        color: $primary;
      }
    """
    assert(styles.css.contains("padding: 16px"))
    assert(styles.css.contains("color: #3498db"))
    assertEquals(styles.classNames.card, "card")
    assertEquals(styles.classNames.`card-header`, "card-header")
  }

  test("handles class names with underscores") {
    val styles = css".my_class { display: flex; }"
    assertEquals(styles.classNames.my_class, "my_class")
  }

  test("handles class names with numbers") {
    val styles = css".col-12 { width: 100%; }"
    assertEquals(styles.classNames.`col-12`, "col-12")
  }

  // === Nested Selector Flattening Tests ===

  test("flattens simple nested selector") {
    val styles = css"""
      .parent {
        color: red;
        .child {
          color: blue;
        }
      }
    """
    assertEquals(styles.classNames.parent, "parent")
    assertEquals(styles.classNames.child, "child")
    // Parent should have its own rule with its properties
    assert(styles.css.contains(".parent {"), "parent should have its own rule")
    assert(styles.css.contains("color: red"), "parent properties preserved")
    // Child should be flattened to .parent .child
    assert(
      styles.css.contains(".parent .child"),
      "should have flattened selector"
    )
    assert(styles.css.contains("color: blue"), "child properties preserved")
    // Verify child is NOT nested inside parent (check that .child { doesn't appear after .parent {)
    val parentIdx = styles.css.indexOf(".parent {")
    val parentEnd = styles.css.indexOf("}", parentIdx)
    val childInParent = styles.css.substring(parentIdx, parentEnd + 1)
    assert(!childInParent.contains(".child"), "child should not be nested")
  }

  test("flattens deeply nested selectors") {
    val styles = css"""
      .level1 {
        margin: 1px;
        .level2 {
          margin: 2px;
          .level3 {
            margin: 3px;
          }
        }
      }
    """
    assert(
      styles.css.contains(".level1 .level2 .level3"),
      "should flatten 3 levels"
    )
    assert(styles.css.contains("margin: 3px"), "should preserve properties")
  }

  test("flattens multiple nested selectors at same level") {
    val styles = css"""
      .container {
        padding: 8px;
        .header {
          font-size: 24px;
        }
        .footer {
          font-size: 12px;
        }
      }
    """
    assert(styles.css.contains(".container .header"))
    assert(styles.css.contains(".container .footer"))
    assert(styles.css.contains("font-size: 24px"))
    assert(styles.css.contains("font-size: 12px"))
  }

  test("preserves parent properties when flattening") {
    val styles = css"""
      .card {
        background: white;
        border: 1px solid gray;
        .title {
          font-weight: bold;
        }
      }
    """
    assert(styles.css.contains(".card {") || styles.css.contains(".card{"))
    assert(styles.css.contains("background: white"))
    assert(styles.css.contains("border: 1px solid gray"))
    assert(styles.css.contains(".card .title"))
    assert(styles.css.contains("font-weight: bold"))
  }

  test("handles nested selectors with interpolation") {
    val color = "red"
    val styles = css"""
      .wrapper {
        background: $color;
        .inner {
          color: $color;
        }
      }
    """
    assert(styles.css.contains("background: red"))
    assert(styles.css.contains(".wrapper .inner"))
    assert(styles.css.contains("color: red"))
  }

  test("handles & parent selector reference") {
    val styles = css"""
      .button {
        color: blue;
        &:hover {
          color: red;
        }
        &--primary {
          background: blue;
        }
      }
    """
    assert(styles.css.contains(".button:hover"))
    assert(styles.css.contains(".button--primary"))
    // Generated class names from & should be available in classNames
    assertEquals(styles.classNames.button, "button")
    assertEquals(styles.classNames.`button--primary`, "button--primary")
  }

  test("handles multiple & references") {
    val styles = css"""
      .link {
        color: blue;
        &:hover,
        &:focus {
          color: red;
        }
      }
    """
    assert(styles.css.contains(".link:hover"))
    assert(styles.css.contains(".link:focus"))
  }

  // === Edge Cases ===

  test("handles & in the middle of selector") {
    val styles = css"""
      .btn {
        color: blue;
        .icon & {
          color: red;
        }
      }
    """
    // .icon & means "when .icon is an ancestor of .btn"
    // The & is replaced with parent, so ".icon &" becomes ".icon .btn"
    assert(styles.css.contains(".icon .btn"))
  }

  test("handles ancestor selector with BEM and complex pseudo-selectors") {
    val styles = css"""
      .checkbox {
        display: flex;

        &__box {
          border: 1px solid gray;

          .js-focus-visible &:not(.focus-visible):focus {
            outline-color: transparent;
          }
        }
      }
    """
    // Should generate .js-focus-visible .checkbox__box:not(.focus-visible):focus
    assert(styles.css.contains(".checkbox {"))
    assert(styles.css.contains(".checkbox__box {"))
    assert(
      styles.css.contains(
        ".js-focus-visible .checkbox__box:not(.focus-visible):focus"
      )
    )
    assertEquals(styles.classNames.checkbox, "checkbox")
    assertEquals(styles.classNames.`checkbox__box`, "checkbox__box")
    assertEquals(styles.classNames.`js-focus-visible`, "js-focus-visible")
    assertEquals(styles.classNames.`focus-visible`, "focus-visible")
  }

  test("handles multiple & in same selector") {
    val styles = css"""
      .item {
        color: blue;
        & + & {
          margin-left: 8px;
        }
      }
    """
    assert(styles.css.contains(".item + .item"))
  }

  test("handles pseudo-elements") {
    val styles = css"""
      .tooltip {
        position: relative;
        &::before {
          content: "";
        }
        &::after {
          content: "";
        }
      }
    """
    assert(styles.css.contains(".tooltip::before"))
    assert(styles.css.contains(".tooltip::after"))
  }

  test("handles attribute selectors") {
    val styles = css"""
      .input {
        border: 1px solid gray;
        &[disabled] {
          opacity: 0.5;
        }
        &[type="text"] {
          padding: 8px;
        }
      }
    """
    assert(styles.css.contains(".input[disabled]"))
    assert(styles.css.contains(".input[type=\"text\"]"))
  }

  test("handles child combinator >") {
    val styles = css"""
      .menu {
        display: flex;
        > .item {
          padding: 8px;
        }
      }
    """
    assert(styles.css.contains(".menu > .item"))
    assertEquals(styles.classNames.menu, "menu")
    assertEquals(styles.classNames.item, "item")
  }

  test("handles adjacent sibling combinator +") {
    val styles = css"""
      .heading {
        font-size: 24px;
        + .paragraph {
          margin-top: 16px;
        }
      }
    """
    assert(styles.css.contains(".heading + .paragraph"))
  }

  test("handles general sibling combinator ~") {
    val styles = css"""
      .checkbox {
        display: none;
        ~ .label {
          cursor: pointer;
        }
      }
    """
    assert(styles.css.contains(".checkbox ~ .label"))
  }

  test("handles 5 levels of nesting") {
    val styles = css"""
      .a {
        .b {
          .c {
            .d {
              .e {
                color: red;
              }
            }
          }
        }
      }
    """
    assert(styles.css.contains(".a .b .c .d .e"))
    assertEquals(styles.classNames.a, "a")
    assertEquals(styles.classNames.e, "e")
  }

  test("handles properties with colons in values") {
    val styles = css"""
      .image {
        background: url(https://example.com/img.png);
      }
    """
    assert(styles.css.contains("url(https://example.com/img.png)"))
  }

  test("handles CSS custom properties (variables)") {
    val styles = css"""
      .theme {
        --primary-color: blue;
        --spacing: 8px;
        color: var(--primary-color);
      }
    """
    assert(styles.css.contains("--primary-color: blue"))
    assert(styles.css.contains("--spacing: 8px"))
    assert(styles.css.contains("var(--primary-color)"))
  }

  test("handles !important declarations") {
    val styles = css"""
      .override {
        color: red !important;
        display: block !important;
      }
    """
    assert(styles.css.contains("color: red !important"))
    assert(styles.css.contains("display: block !important"))
  }

  test("handles multiple classes in selector") {
    val styles = css"""
      .btn.primary {
        background: blue;
      }
      .card.featured.large {
        padding: 32px;
      }
    """
    assert(styles.css.contains(".btn.primary"))
    assert(styles.css.contains(".card.featured.large"))
    assertEquals(styles.classNames.btn, "btn")
    assertEquals(styles.classNames.primary, "primary")
    assertEquals(styles.classNames.card, "card")
  }

  test("handles ID selectors") {
    val styles = css"""
      #app {
        min-height: 100vh;
      }
      .container #main {
        padding: 16px;
      }
    """
    assert(styles.css.contains("#app"))
    assert(styles.css.contains(".container #main"))
    assertEquals(styles.classNames.container, "container")
  }

  test("handles universal selector") {
    val styles = css"""
      .reset {
        * {
          margin: 0;
          padding: 0;
        }
      }
    """
    assert(styles.css.contains(".reset *"))
  }

  test("handles element selectors nested in class") {
    val styles = css"""
      .article {
        h1 {
          font-size: 32px;
        }
        p {
          line-height: 1.6;
        }
        a {
          color: blue;
        }
      }
    """
    assert(styles.css.contains(".article h1"))
    assert(styles.css.contains(".article p"))
    assert(styles.css.contains(".article a"))
  }

  test("handles empty nested rule") {
    val styles = css"""
      .parent {
        color: red;
        .empty {}
      }
    """
    assert(styles.css.contains(".parent"))
    assert(styles.css.contains("color: red"))
    // Empty rules are not output but parent is still valid
    assertEquals(styles.classNames.parent, "parent")
  }

  test("handles selector with no properties only children") {
    val styles = css"""
      .wrapper {
        .inner {
          color: blue;
        }
      }
    """
    assert(styles.css.contains(".wrapper .inner"))
    assert(styles.css.contains("color: blue"))
  }

  test("handles BEM pattern completely") {
    val styles = css"""
      .block {
        display: flex;

        &__element {
          padding: 8px;
        }

        &--modifier {
          background: gray;
        }

        &__element--modifier {
          color: red;
        }
      }
    """
    assert(styles.css.contains(".block {"))
    assert(styles.css.contains(".block__element"))
    assert(styles.css.contains(".block--modifier"))
    assert(styles.css.contains(".block__element--modifier"))
    assertEquals(styles.classNames.block, "block")
    assertEquals(styles.classNames.`block__element`, "block__element")
    assertEquals(styles.classNames.`block--modifier`, "block--modifier")
    assertEquals(
      styles.classNames.`block__element--modifier`,
      "block__element--modifier"
    )
  }

  test("handles mixed flat and nested selectors") {
    val styles = css"""
      .flat-one {
        color: red;
      }

      .nested {
        color: blue;
        .child {
          color: green;
        }
      }

      .flat-two {
        color: yellow;
      }
    """
    assert(styles.css.contains(".flat-one"))
    assert(styles.css.contains(".nested {"))
    assert(styles.css.contains(".nested .child"))
    assert(styles.css.contains(".flat-two"))
  }

  test("handles numeric values in properties") {
    val styles = css"""
      .grid {
        grid-template-columns: repeat(12, 1fr);
        z-index: 9999;
        opacity: 0.5;
      }
    """
    assert(styles.css.contains("repeat(12, 1fr)"))
    assert(styles.css.contains("z-index: 9999"))
    assert(styles.css.contains("opacity: 0.5"))
  }

  test("handles calc() expressions") {
    val styles = css"""
      .flexible {
        width: calc(100% - 32px);
        height: calc(100vh - 64px);
      }
    """
    assert(styles.css.contains("calc(100% - 32px)"))
    assert(styles.css.contains("calc(100vh - 64px)"))
  }

  test("handles rgba/hsla colors") {
    val styles = css"""
      .overlay {
        background: rgba(0, 0, 0, 0.5);
        border-color: hsla(210, 50%, 50%, 0.8);
      }
    """
    assert(styles.css.contains("rgba(0, 0, 0, 0.5)"))
    assert(styles.css.contains("hsla(210, 50%, 50%, 0.8)"))
  }

  test("handles transform property") {
    val styles = css"""
      .animated {
        transform: translateX(100px) rotate(45deg) scale(1.5);
      }
    """
    assert(styles.css.contains("translateX(100px) rotate(45deg) scale(1.5)"))
  }

  test("handles transition property") {
    val styles = css"""
      .smooth {
        transition: all 0.3s ease-in-out;
      }
    """
    assert(styles.css.contains("transition: all 0.3s ease-in-out"))
  }

  test("handles box-shadow with multiple values") {
    val styles = css"""
      .elevated {
        box-shadow: 0 2px 4px rgba(0,0,0,0.1), 0 4px 8px rgba(0,0,0,0.1);
      }
    """
    assert(
      styles.css.contains(
        "box-shadow: 0 2px 4px rgba(0,0,0,0.1), 0 4px 8px rgba(0,0,0,0.1)"
      )
    )
  }

  test("handles font-family with quotes") {
    val styles = css"""
      .text {
        font-family: "Helvetica Neue", Arial, sans-serif;
      }
    """
    assert(
      styles.css.contains("font-family: \"Helvetica Neue\", Arial, sans-serif")
    )
  }

  test("handles content property with special characters") {
    val styles = css"""
      .icon {
        &::before {
          content: "→";
        }
        &::after {
          content: "×";
        }
      }
    """
    assert(styles.css.contains("content: \"→\""))
    assert(styles.css.contains("content: \"×\""))
  }

  test("handles data attribute selectors") {
    val styles = css"""
      .component {
        &[data-state="active"] {
          background: green;
        }
        &[data-size="large"] {
          padding: 24px;
        }
      }
    """
    assert(styles.css.contains(".component[data-state=\"active\"]"))
    assert(styles.css.contains(".component[data-size=\"large\"]"))
  }

  test("handles :not() pseudo-class") {
    val styles = css"""
      .item {
        color: blue;
        &:not(:last-child) {
          margin-bottom: 8px;
        }
      }
    """
    assert(styles.css.contains(".item:not(:last-child)"))
  }

  test("handles :nth-child() pseudo-class") {
    val styles = css"""
      .row {
        &:nth-child(odd) {
          background: #f5f5f5;
        }
        &:nth-child(2n+1) {
          color: gray;
        }
      }
    """
    assert(styles.css.contains(".row:nth-child(odd)"))
    assert(styles.css.contains(".row:nth-child(2n+1)"))
  }

  test("handles deeply nested with & at each level") {
    val styles = css"""
      .nav {
        display: flex;
        &__list {
          list-style: none;
          &-item {
            padding: 8px;
            &--active {
              color: blue;
            }
          }
        }
      }
    """
    assert(styles.css.contains(".nav {"))
    assert(styles.css.contains(".nav__list"))
    assert(styles.css.contains(".nav__list-item"))
    assert(styles.css.contains(".nav__list-item--active"))
  }

  test("handles interpolation in nested selectors") {
    val state = "hover"
    val mod = "primary"
    val styles = css"""
      .btn {
        color: blue;
        &:$state {
          color: red;
        }
        &--$mod {
          background: blue;
        }
      }
    """
    assert(styles.css.contains(".btn:hover"))
    assert(styles.css.contains(".btn--primary"))
  }

  test("handles only whitespace in css") {
    val styles = css"   "
    assertEquals(styles.css, "")
  }

  test("handles single property without trailing semicolon") {
    val styles = css".minimal { color: red }"
    assert(styles.css.contains("color: red"))
  }

  test("preserves order of rules") {
    val styles = css"""
      .first { order: 1; }
      .second { order: 2; }
      .third { order: 3; }
    """
    val firstIdx = styles.css.indexOf(".first")
    val secondIdx = styles.css.indexOf(".second")
    val thirdIdx = styles.css.indexOf(".third")
    assert(firstIdx < secondIdx, "first should come before second")
    assert(secondIdx < thirdIdx, "second should come before third")
  }

  test("preserves order of nested rules") {
    val styles = css"""
      .parent {
        .child-a { order: 1; }
        .child-b { order: 2; }
        .child-c { order: 3; }
      }
    """
    val aIdx = styles.css.indexOf(".parent .child-a")
    val bIdx = styles.css.indexOf(".parent .child-b")
    val cIdx = styles.css.indexOf(".parent .child-c")
    assert(aIdx < bIdx, "child-a should come before child-b")
    assert(bIdx < cIdx, "child-b should come before child-c")
  }

  // === Web Component Tests ===

  test("handles :host selector") {
    val styles = css"""
      :host {
        display: block;
        contain: content;
      }
    """
    assert(styles.css.contains(":host {"))
    assert(styles.css.contains("display: block"))
    assert(styles.css.contains("contain: content"))
  }

  test("handles :host with attribute selectors") {
    val styles = css"""
      :host {
        display: block;

        &([disabled]) {
          opacity: 0.5;
          pointer-events: none;
        }

        &([size="large"]) {
          padding: 24px;
        }

        &([variant="primary"]) {
          background: blue;
          color: white;
        }

        &(:hover) {
          box-shadow: 0 2px 8px rgba(0,0,0,0.1);
        }
      }
    """
    assert(styles.css.contains(":host {"))
    assert(styles.css.contains(":host([disabled])"))
    assert(styles.css.contains(":host([size=\"large\"])"))
    assert(styles.css.contains(":host([variant=\"primary\"])"))
    assert(styles.css.contains(":host(:hover)"))
    assert(styles.css.contains("opacity: 0.5"))
    assert(styles.css.contains("padding: 24px"))
  }

  test("handles :host-context selector") {
    val styles = css"""
      :host-context(.dark-theme) {
        background: #1a1a1a;
        color: white;
      }

      :host-context([dir="rtl"]) {
        direction: rtl;
      }
    """
    assert(styles.css.contains(":host-context(.dark-theme)"))
    assert(styles.css.contains(":host-context([dir=\"rtl\"])"))
  }

  test("handles ::slotted selector") {
    val styles = css"""
      ::slotted(*) {
        margin: 0;
        padding: 0;
      }

      ::slotted(p) {
        line-height: 1.6;
      }

      ::slotted(.highlight) {
        background: yellow;
      }

      ::slotted([slot="header"]) {
        font-size: 24px;
        font-weight: bold;
      }
    """
    assert(styles.css.contains("::slotted(*)"))
    assert(styles.css.contains("::slotted(p)"))
    assert(styles.css.contains("::slotted(.highlight)"))
    assert(styles.css.contains("::slotted([slot=\"header\"])"))
    assert(styles.css.contains("line-height: 1.6"))
  }

  test("handles ::slotted with :host nesting") {
    val styles = css"""
      :host {
        display: block;

        ::slotted(*) {
          box-sizing: border-box;
        }

        ::slotted(p:first-child) {
          margin-top: 0;
        }

        ::slotted(p:last-child) {
          margin-bottom: 0;
        }
      }
    """
    assert(styles.css.contains(":host {"))
    assert(styles.css.contains(":host ::slotted(*)"))
    assert(styles.css.contains(":host ::slotted(p:first-child)"))
    assert(styles.css.contains(":host ::slotted(p:last-child)"))
  }

  test("handles :host with nested class selectors") {
    val styles = css"""
      :host {
        display: flex;

        .container {
          flex: 1;

          .header {
            font-size: 20px;
          }

          .content {
            padding: 16px;
          }
        }
      }
    """
    assert(styles.css.contains(":host {"))
    assert(styles.css.contains(":host .container"))
    assert(styles.css.contains(":host .container .header"))
    assert(styles.css.contains(":host .container .content"))
    assertEquals(styles.classNames.container, "container")
    assertEquals(styles.classNames.header, "header")
    assertEquals(styles.classNames.content, "content")
  }

  test("handles ::part selector") {
    val styles = css"""
      ::part(button) {
        background: blue;
        color: white;
      }

      ::part(button):hover {
        background: darkblue;
      }

      ::part(input):focus {
        outline: 2px solid blue;
      }
    """
    assert(styles.css.contains("::part(button) {"))
    assert(styles.css.contains("::part(button):hover"))
    assert(styles.css.contains("::part(input):focus"))
  }

  test("handles complete web component styling pattern") {
    val styles = css"""
      :host {
        --component-bg: white;
        --component-color: black;
        display: block;

        &([theme="dark"]) {
          --component-bg: #1a1a1a;
          --component-color: white;
        }
      }

      .wrapper {
        background: var(--component-bg);
        color: var(--component-color);

        &__header {
          padding: 16px;
          border-bottom: 1px solid #eee;
        }

        &__body {
          padding: 16px;
        }
      }

      ::slotted(*) {
        margin: 8px 0;
      }
    """
    assert(styles.css.contains(":host {"))
    assert(styles.css.contains(":host([theme=\"dark\"])"))
    assert(styles.css.contains("--component-bg: white"))
    assert(styles.css.contains(".wrapper {"))
    assert(styles.css.contains(".wrapper__header"))
    assert(styles.css.contains(".wrapper__body"))
    assert(styles.css.contains("::slotted(*)"))
    assertEquals(styles.classNames.wrapper, "wrapper")
    assertEquals(styles.classNames.`wrapper__header`, "wrapper__header")
    assertEquals(styles.classNames.`wrapper__body`, "wrapper__body")
  }

  // === Validation Tests (compile-time errors) ===
  // These tests verify that invalid CSS produces compile-time errors
  // We use compiletime.testing.typeCheckErrors to verify error messages

  test("validation: unbalanced braces - missing close") {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """import www.CssMacro.css; css".button { color: red;" """
    )
    assert(errors.nonEmpty, "Should have compile error for missing '}'")
    assert(
      errors.exists(_.message.contains("Unbalanced braces")),
      s"Error should mention unbalanced braces, got: ${errors.map(_.message)}"
    )
  }

  test("validation: unbalanced braces - extra close") {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """import www.CssMacro.css; css".button { color: red; }}" """
    )
    assert(errors.nonEmpty, "Should have compile error for extra '}'")
    assert(
      errors.exists(_.message.contains("Unexpected '}'")),
      s"Error should mention unexpected }, got: ${errors.map(_.message)}"
    )
  }

  test("validation: missing selector before brace") {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """import www.CssMacro.css; css"{ color: red; }" """
    )
    assert(errors.nonEmpty, "Should have compile error for missing selector")
    assert(
      errors.exists(_.message.contains("Missing selector")),
      s"Error should mention missing selector, got: ${errors.map(_.message)}"
    )
  }

  test("validation: nested unbalanced braces") {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """import www.CssMacro.css; css".parent { .child { color: red; }" """
    )
    assert(errors.nonEmpty, "Should have compile error for nested unclosed")
    assert(
      errors.exists(_.message.contains("Unbalanced braces")),
      s"Error should mention unbalanced braces, got: ${errors.map(_.message)}"
    )
  }

  test("validation: multiple unclosed braces") {
    val errors = scala.compiletime.testing.typeCheckErrors(
      """import www.CssMacro.css; css".a { .b { .c {" """
    )
    assert(errors.nonEmpty, "Should have compile error for multiple unclosed")
    assert(
      errors.exists(_.message.contains("Unbalanced braces")),
      s"Error should mention unbalanced braces, got: ${errors.map(_.message)}"
    )
  }

  test("validation: braces inside strings are ignored") {
    // This should NOT produce an error - braces in strings don't count
    val styles = css"""
      .icon::before {
        content: "{";
      }
    """
    assert(styles.css.contains("content: \"{\""))
  }

  test("validation: valid deeply nested CSS passes") {
    // This should compile without error
    val styles = css"""
      .a {
        .b {
          .c {
            .d {
              color: red;
            }
          }
        }
      }
    """
    assert(styles.css.contains(".a .b .c .d"))
  }
}
