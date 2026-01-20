https://github.com/user-attachments/assets/145834e5-87a4-469b-ab2b-65ed06fe048a

# CSS Macro for Scala 3

A compile-time CSS string interpolator with nested selector flattening, syntax validation, and type-safe class name access via Scala 3 named tuples.

## Features

- **Nested selector flattening**: Write SCSS-like nested CSS, get flat CSS output
- **Parent selector (`&`)**: Use `&:hover`, `&--modifier` for BEM-style selectors
- **CSS combinators**: Full support for `>`, `+`, `~` combinators
- **String interpolation**: Use `$variable` or `${expression}` for dynamic CSS values
- **Compile-time validation**: Catches unbalanced braces and missing selectors at compile-time
- **Compile-time class extraction**: All class names (including generated ones) extracted at compile-time
- **Type-safe class names**: Access class names as fields on a named tuple with full IDE support
- **Named tuple return**: Returns `(css: String, classNames: (...))` tuple

## Usage

### Basic Example

```scala
import www.CssMacro.css

val primaryColor = "#3498db"
val padding = "16px"

val styles = css"""
  .container {
    padding: $padding;
    background: white;
  }

  .button {
    background: $primaryColor;
    color: white;
  }
"""

// Access the CSS string
println(styles.css)

// Access class names (type-safe!)
println(styles.classNames.container)  // "container"
println(styles.classNames.button)     // "button"
```

### Nested Selectors

Write nested CSS like SCSS - it gets flattened automatically:

```scala
val styles = css"""
  .card {
    background: white;
    border: 1px solid #ccc;

    .header {
      font-size: 24px;
      font-weight: bold;
    }

    .content {
      padding: 16px;
    }
  }
"""

// Output CSS is flattened:
// .card { background: white; border: 1px solid #ccc; }
// .card .header { font-size: 24px; font-weight: bold; }
// .card .content { padding: 16px; }

println(styles.classNames.card)     // "card"
println(styles.classNames.header)   // "header"
println(styles.classNames.content)  // "content"
```

### Parent Selector (`&`)

Use `&` to reference the parent selector - perfect for BEM methodology:

```scala
val styles = css"""
  .button {
    color: blue;
    padding: 8px 16px;

    &:hover {
      color: darkblue;
    }

    &--primary {
      background: blue;
      color: white;
    }

    &--secondary {
      background: gray;
    }
  }
"""

// Output CSS:
// .button { color: blue; padding: 8px 16px; }
// .button:hover { color: darkblue; }
// .button--primary { background: blue; color: white; }
// .button--secondary { background: gray; }

// All generated class names are available!
println(styles.classNames.button)              // "button"
println(styles.classNames.`button--primary`)   // "button--primary"
println(styles.classNames.`button--secondary`) // "button--secondary"
```

### Full BEM Pattern

```scala
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

// Generates:
// .nav { display: flex; }
// .nav__list { list-style: none; }
// .nav__list-item { padding: 8px; }
// .nav__list-item--active { color: blue; }

println(styles.classNames.nav)                       // "nav"
println(styles.classNames.`nav__list`)               // "nav__list"
println(styles.classNames.`nav__list-item`)          // "nav__list-item"
println(styles.classNames.`nav__list-item--active`)  // "nav__list-item--active"
```

### CSS Combinators

Full support for child (`>`), adjacent sibling (`+`), and general sibling (`~`) combinators:

```scala
val styles = css"""
  .menu {
    display: flex;

    > .item {
      padding: 8px;
    }

    + .sibling {
      margin-top: 16px;
    }

    ~ .general {
      opacity: 0.5;
    }
  }
"""

// Generates:
// .menu { display: flex; }
// .menu > .item { padding: 8px; }
// .menu + .sibling { margin-top: 16px; }
// .menu ~ .general { opacity: 0.5; }
```

### Pseudo-classes and Pseudo-elements

```scala
val styles = css"""
  .tooltip {
    position: relative;

    &::before {
      content: "";
      position: absolute;
    }

    &:hover {
      opacity: 1;
    }

    &:not(:last-child) {
      margin-bottom: 8px;
    }

    &:nth-child(odd) {
      background: #f5f5f5;
    }
  }
"""

// Generates:
// .tooltip { position: relative; }
// .tooltip::before { content: ""; position: absolute; }
// .tooltip:hover { opacity: 1; }
// .tooltip:not(:last-child) { margin-bottom: 8px; }
// .tooltip:nth-child(odd) { background: #f5f5f5; }
```

### Attribute Selectors

```scala
val styles = css"""
  .input {
    border: 1px solid gray;

    &[disabled] {
      opacity: 0.5;
    }

    &[type="text"] {
      padding: 8px;
    }

    &[data-state="active"] {
      border-color: blue;
    }
  }
"""

// Generates:
// .input { border: 1px solid gray; }
// .input[disabled] { opacity: 0.5; }
// .input[type="text"] { padding: 8px; }
// .input[data-state="active"] { border-color: blue; }
```

### Element Selectors Nested in Classes

```scala
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

// Generates:
// .article h1 { font-size: 32px; }
// .article p { line-height: 1.6; }
// .article a { color: blue; }
```

### Class Names with Hyphens

CSS class names containing hyphens are preserved as-is. Use Scala backticks to access them:

```scala
val styles = css"""
  .callout--warning {
    background-color: yellow;
  }

  .my-component {
    display: block;
  }
"""

// Use backticks for names with hyphens
println(styles.classNames.`callout--warning`)  // "callout--warning"
println(styles.classNames.`my-component`)      // "my-component"
```

### Dynamic Values with Interpolation

The interpolator supports any Scala expression:

```scala
val baseSize = 8
val theme = Map("primary" -> "#3498db", "secondary" -> "#2ecc71")

val styles = css"""
  .card {
    padding: ${baseSize * 2}px;
    margin: ${baseSize}px;
    background: ${theme("primary")};
  }
"""

// Interpolation also works in selectors
val state = "hover"
val modifier = "primary"

val buttonStyles = css"""
  .btn {
    &:$state {
      opacity: 0.8;
    }
    &--$modifier {
      background: blue;
    }
  }
"""
// Generates: .btn:hover and .btn--primary
```

### Deep Nesting

Arbitrary nesting depth is supported:

```scala
val styles = css"""
  .app {
    .sidebar {
      .nav {
        .item {
          .icon {
            color: blue;
          }
        }
      }
    }
  }
"""

// Generates: .app .sidebar .nav .item .icon { color: blue; }
```

### Complex CSS Values

Full support for modern CSS features:

```scala
val styles = css"""
  .component {
    /* CSS Custom Properties */
    --primary-color: blue;
    --spacing: 8px;
    color: var(--primary-color);

    /* calc() expressions */
    width: calc(100% - 32px);
    height: calc(100vh - 64px);

    /* rgba/hsla colors */
    background: rgba(0, 0, 0, 0.5);
    border-color: hsla(210, 50%, 50%, 0.8);

    /* transforms */
    transform: translateX(100px) rotate(45deg) scale(1.5);

    /* transitions */
    transition: all 0.3s ease-in-out;

    /* box-shadow with multiple values */
    box-shadow: 0 2px 4px rgba(0,0,0,0.1), 0 4px 8px rgba(0,0,0,0.1);

    /* font-family with quotes */
    font-family: "Helvetica Neue", Arial, sans-serif;

    /* URLs */
    background-image: url(https://example.com/img.png);

    /* !important */
    display: block !important;
  }

  .icon::before {
    /* Content with special characters - braces in strings are handled correctly */
    content: "{";
  }
"""
```

## Compile-Time Validation

The macro validates CSS syntax at compile-time and reports helpful errors:

### Unbalanced Braces

```scala
// Missing closing brace - COMPILE ERROR
val styles = css".button { color: red;"
// Error: CSS syntax error: Unbalanced braces: 1 unclosed '{'

// Extra closing brace - COMPILE ERROR
val styles = css".button { color: red; }}"
// Error: CSS syntax error: Unexpected '}' at position X - no matching '{'
```

### Missing Selector

```scala
// Missing selector before brace - COMPILE ERROR
val styles = css"{ color: red; }"
// Error: CSS syntax error: Missing selector before '{'
```

### Braces in Strings

Braces inside quoted strings are correctly ignored:

```scala
// This compiles fine - braces in strings don't count
val styles = css"""
  .icon::before {
    content: "{";
  }
  .icon::after {
    content: "}";
  }
"""
```

## Return Type

The `css` interpolator returns a named tuple with two fields:

| Field | Type | Description |
|-------|------|-------------|
| `css` | `String` | The flattened CSS string with interpolated values |
| `classNames` | Named Tuple | A named tuple where each field is a class name |

## How It Works

1. **At compile-time**:
   - Validates CSS syntax (balanced braces, selectors present)
   - Flattens nested CSS selectors
   - Resolves `&` parent references
   - Extracts all class names from the flattened output
   - Builds a typed named tuple for class name access
2. **At runtime** (with interpolations): The CSS string is built and flattened
3. **Return value**: A named tuple with the CSS string and all extracted class names

## Development

```bash
# Run tests
scala-cli test src

# Run example
scala-cli run src
```
