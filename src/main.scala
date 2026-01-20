//> using scala 3.8.0
//> using platform scala-js
//> using jsModuleKind es

package www

import CssMacro.css

@main
def main(): Unit =
  val primaryColor = "#3498db"
  val padding = "16px"

  // css"..." now returns a named tuple with .css and .classNames
  val styles = css"""
    .container {
      padding: $padding;
      background: white;

      .header {
        color: $primaryColor;
        font-size: 24px;
      }

      .content {
        padding: 8px;
        border: 1px solid #ccc;
      }
    }

    .button {
      background: $primaryColor;
      color: white;

      .icon {
        margin-right: 8px;
      }
    }
  """

  println(s"CSS output:\n${styles.css}")
  println(s"Container class: ${styles.classNames.container}")
  println(s"Header class: ${styles.classNames.header}")
  println(s"Button class: ${styles.classNames.button}")

  // Another example with hyphens in class names - use backticks to access
  val calloutStyles = css"""
    :host {
      display: block;
    }

    .callout {
      display: flex;
      align-items: center;
      padding: 8px;
    }

    .callout--warning {
      background-color: var(--color-warning);
    }
  """

  println(s"\nCallout CSS:\n${calloutStyles.css}")
  println(s"Callout class: ${calloutStyles.classNames.callout}")
  println(
    s"Callout warning class: ${calloutStyles.classNames.`callout--warning`}"
  )
