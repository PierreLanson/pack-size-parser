# Pack Size Parser

A Java transformation for **Informatica Cloud Data Integration (CDI)** that turns messy supplier pack-size text into a standard `conversion x base_weight unit` form, then cross-checks it against every other place the same product's size is recorded and flags rows where the sources disagree.

Built for a retail / food-service product master, where the same product can arrive with its pack size written five different ways:

| Where it comes from | Example value |
|---|---|
| Free-text "outer pack size" column | `2x3x400-500g` |
| Product description | `Apple Pies 1 x 4 100g` |
| A third free-text attribute | `33cl x 3 x 4` |
| Structured UoM columns | `CAS` \| `12` \| `EA` \| `100` \| `G` |

All four of those describe a pack, but nothing in the source data guarantees they describe the *same* pack. This code reads each one, standardises it, and reports whether they agree.

## What it produces

For each input row, the transformation outputs:

| Output port | Meaning | Example |
|---|---|---|
| `java_outer_pack_size` | The reconciled pack size, or the mismatch label if the sources disagree | `6x450g` |
| `java_mismatch_flag` | `FALSE`, or which fields disagreed | `MISMATCH (base_weight, total_weight)` |
| `java_outer_uom` / `java_base_uom` | Derived outer/base units of measure | `CAS` / `EA` |
| `java_outer_to_base_uom_conversion` | Every distinct conversion seen across the sources | `12` or `6 & 12` |
| `java_smallest_to_base_uom_conversion` | Every distinct base weight seen | `100 & 200` |
| `java_smallest_unit_of_measure` | Every distinct unit seen (after standardising) | `g` |
| `java_total_weight_values_found` | Every distinct total weight seen | `1200 & 2400` |
| `java_conversion_parts_found` | The raw multiplier parts, e.g. `[2.0, 3.0]` for `2x3x...` | `[2.0, 3.0]` |

The `& `-joined "values found" outputs are what make a mismatch investigable: a data steward can see *which* values collided rather than just that something did.

## How it works

The pipeline runs in four steps, all in [`informatica/1_helper_code.java`](informatica/1_helper_code.java) and orchestrated per row in [`informatica/2_on_input_row.java`](informatica/2_on_input_row.java).

**1. Clean the text.** Lower-case, strip brackets and commas, collapse whitespace. `Flour (Bread), 16KG` becomes `flour bread 16kg`.

**2. Find the pack size.** Twelve regular expressions (`P1` … `P12`) are tried in a fixed order and the first one that matches wins. The order matters because the patterns are nested: `1 x 2 x 400` is a sub-pattern of `1 x 2 x 400g`, so the more specific pattern must be tried first. Each pattern's capture groups are collected into a list, so `2x3x400-500g` becomes `["2", "3", "400-500g"]`.

When only a conversion is found (e.g. `1 x 4` in `apple pies 1 x 4 100g`), a second pass looks for a standalone weight elsewhere in the string so that `100g` isn't lost.

**3. Turn the groups into numbers.** Each group is classified as a multiplier (`2`, `3`, `pack`, `doz`, `pair`), a base weight with a unit (`400-500g`, `33cl`), or something to ignore (`ptn`). Multipliers are multiplied together to get the conversion. Ranges like `400-500g` become their midpoint. Units are standardised to `g`, `ml` or `min` (`kg` → ×1000 `g`, `cl` → ×10 `ml`, `lb` → ×453.1 `g`, and so on). The structured UoM columns go through their own reader, `numericalNormaliser`, which interprets `CAS | 12 | EA | 100 | G` as conversion 12, base weight 100 g.

**4. Reconcile and validate.** The values from all four sources are added to distinct sets, one per field (conversion, base weight, unit, total weight). Then the identity

```
total_weight = conversion × base_weight
```

is used to fill in anything missing: if a source gave only a total and another gave only a base weight, the conversion is derived, and vice versa. Finally any set with more than one value is a mismatch, and the flag names the fields involved.

## Examples

Free text only:

```
2x3x400-500g      ->  6x450g        (2 × 3 = 6 units; midpoint of 400–500 g)
33cl x 3 x 4      ->  12x330ml      (cl standardised to ml)
pack of 10        ->  1x10          (a count, no weight)
per kg            ->  1x1000g
10-15g x 20       ->  20x12.5g
3 x 4.5 lb        ->  3x2039g
30cm x 40cm       ->  Not Provided  (dimensions are not a pack size)
450g              ->  Not Provided  (a weight, but no conversion)
```

Cross-source validation:

```
outer text     description           structured columns    result
12x100g        biscuits 12 x 100g    CAS 12 EA 100 G       12x100g, flag FALSE
12x100g        biscuits 12 x 200g    CAS 12 EA 100 G       MISMATCH (base_weight, total_weight)
                                                           base weights found: 100 & 200
6 x 500g       -                     CAS 3000 G            6x500g, flag FALSE (3000 = 6 × 500)
6 x 500g       -                     CAS 2500 G            MISMATCH (total_weight)
6 x 500g       12 x 250g             -                     MISMATCH (conversion, base_weight)
                                                           (totals agree at 3000, so total is not flagged)
```

## Running it locally

Informatica isn't needed to try the logic. `src/PackSizeTransformation.java` is the same code wrapped in a plain class, with a `processRow(...)` method standing in for the transformation's input and output ports. You need a JDK (8 or newer) on your PATH.

```bash
./run.sh                                  # run the examples above
./run.sh test                             # run the regression tests (87 checks)
./run.sh "6 x 330ml" "case of 24"         # parse your own strings
```

On Windows without bash:

```
javac -d out src\*.java test\*.java
java -cp out Main
java -cp out PackSizeTransformationTest
```

## Repository layout

```
informatica/
  1_helper_code.java        contents of the Java transformation's "Helper Code" tab
  2_on_input_row.java       contents of the "On Input Row" tab (runs once per row)
src/
  PackSizeTransformation.java   the two tabs above spliced into a runnable class, unchanged
  Main.java                     example runner
test/
  PackSizeTransformationTest.java   regression tests, no framework needed
run.sh
```

The Informatica files are the source of truth; `src/PackSizeTransformation.java` contains them verbatim so the same code can be run and tested outside the pipeline.

## Notes on the Informatica side

In CDI the Java transformation has separate tabs. Helper Code holds static methods and constants that CDI compiles into a generated class, which is why there's no `class` declaration in that file. On Input Row is the body of a method CDI calls per row; the `exp_*` inputs and `java_*` outputs are ports declared on the transformation and injected as variables, and `generateRow()` emits the output row. The `numericalNormaliser` takes a source-system name because each upstream system populates the structured UoM columns slightly differently. Source-system names in this repo have been replaced with generic placeholders.

## Known limitations and ideas for next steps

These are deliberate scope boundaries of the current version rather than bugs, and are written up here so they can be tackled one at a time with the tests as a safety net.

- Conversion-only strings without a unit are ambiguous: `1 x 2 x 400` yields a conversion of 800, because with no unit on the `400` there is no way to know it was meant as a weight. The pattern order comment in `extractPackSizeGroups` explains why.
- A count that precedes its pack word (`gloves 100 pcs`, `3 pair`) doesn't match any pattern; the patterns expect `pack of 10` / `10 x pcs` ordering.
- `ptn` (portion) is deliberately ignored, so `1 x 12 ptn` becomes `1x1`.
- Range midpoints are used for `400-500g`; a min/max pair might serve some downstream uses better.
- Ounce, pound, pint and gallon factors are UK values rounded to one decimal place.
- Only the first matching pattern is used per string. A string with two independent sizes in it (e.g. a description mentioning both the case and the inner) only contributes the first.
