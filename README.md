# Pack Size Parser

A Java transformation for **Informatica Cloud Data Integration (CDI)** that turns messy supplier pack-size text into usable attributes to enable cross-supplier pricing.

In short, it:

1. reads messy free-text and structured pack-size attributes
2. cleans them and standardises each to the same numeric fields, enabling comparison
3. cross-checks that all the attributes are coherent, flagging any that mismatch
4. outputs the clean fields for downstream analysis

## What is a pack size?

Take twelve 330 ml cans of cola. A supplier can physically package those differently and how it is described is, most commonly, by a text column named "outer pack size". This outer pack size is a description of the packaging and the contents. With twelve cans of 330ml cola there are multiple ways to package and therefore write a pack size.

For example:
```
    "3 x 4 x 0.33l" describes 3 boxes of 4 cans, with each can being 330ml

        ┌────────────────────── case: 3 boxes ──────────────────────┐
        │   ┌─  box of 4 ─┐    ┌─  box of 4 ─┐    ┌─  box of 4 ─┐   │
        │   │  ▢  ▢  ▢  ▢ │    │  ▢  ▢  ▢  ▢ │    │  ▢  ▢  ▢  ▢ │   │
        │   └─────────────┘    └─────────────┘    └─────────────┘   │
        └───────────────────────────────────────────────────────────┘

```

Another supplier may be selling two packs of six cans (`2 x 6 x 33cl`), and a third as one case of twelve (`12 x 330ml`). These are different ways of writing the same thing; there are 12 total cans and each can is 330ml. Though they have different packing and use differing units (330ml vs 33cl vs 0.33l), you are getting the same number of cans with the same volume per can. Once they are standardised it is possible to compare across products and find the cheapest per 12x330ml (same number of cans and same volume per can), or even per 330ml can (same can volume but different numbers of cans), or per ml (not caring for packaging or base volume at all, e.g. against a 1l PET bottle).

The list below shows some of the ways suppliers package or describe the same twelve cans; it is far from exhaustive.

```
3 x 4 x 330ml     2 x 6 x 33cl      12 x 330ml        12x33cl           33cl x 12
330ml x 12        3 x 4 x 0.33l     12 x 0.33 litre   case of 12 x 330ml
```

These will all be output as:

```
        conversion  = 12        (3 × 4, or 2 × 6, or 12; how many units in the outer)
        base weight = 330 ml    (one unit, whatever unit of measure it was quoted in)
        total       = 3960 ml   (conversion × base weight)
        pack size   = 12x330ml
```

The packaging story isn't thrown away. The conversion parts output keeps the multipliers found (`[3.0, 4.0]` for "3 x 4 x 330ml", `[2.0, 6.0]` for "2 x 6 x 33cl"), so anyone who needs to know how the packaging is built up still can. The parts are deliberately *not* compared across attributes when looking for mismatches (the check exists in the code but is commented out), because two attributes describing different packaging for the same twelve cans is not a data error.

If the outer pack size column says `12x330ml` and the description says `cola 24 x 330ml`, those reduce to different conversions (12 vs 24) and totals (3960 vs 7920), and the row is flagged `MISMATCH (conversion, total_weight)` rather than silently trusting one column over the other.

## What it produces

The transformation doesn't just emit a pack-size string; it breaks a pack down into its component parts, numbers and labels a downstream user can work with, so they can take whichever piece they need:

- **conversion**: how many items are in the pack, e.g. 12 for a case of twelve.
- **base weight**: the weight (or volume) of one item, standardised to `g`, `ml` or `min`.
- **total weight**: conversion × base weight, the weight of the whole pack.
- **pack size**: the three above combined as `{conversion}x{base_weight}{unit}`, e.g. `12x100g`. When no base weight is found or given (non-food items sold by count, for instance) it is `1x{conversion}`, e.g. `1x800`.
- **mismatch flag**: raised when the attributes for one product disagree, e.g. the description says 100g but a structured column says 200g. It names the fields that conflicted.
- **conversion parts**: the multipliers the conversion was built from, so `1x2x3x400g` gives `[1.0, 2.0, 3.0]` (the `400g` has become the base weight). Pack words count as `1.0` and `doz` expands to `12.0`, so `5 x doz` gives `[5.0, 12.0]` and a conversion of 60.

Because the same product's pack is described in several attributes, every field except the pack size is reported as *all distinct values seen*, joined with ` & `. A clean row shows one value per field; a flagged row shows exactly which values collided, which is what makes a mismatch investigable rather than just detectable.

The output ports are:

| Output port | Field | Example |
|---|---|---|
| `java_outer_pack_size` | pack size, or the mismatch label if the attributes disagree | `6x450g` |
| `java_mismatch_flag` | `FALSE`, or which fields disagreed | `MISMATCH (base_weight, total_weight)` |
| `java_outer_uom` / `java_base_uom` | derived outer/base units of measure (`CAS`/`EA` for a multi-pack, `EA`/`EA` for a single, `KG`/`EA` for catch-weight) | `CAS` / `EA` |
| `java_outer_to_base_uom_conversion` | conversion, every distinct value seen | `12` or `6 & 12` |
| `java_smallest_to_base_uom_conversion` | base weight, every distinct value seen | `100 & 200` |
| `java_smallest_unit_of_measure` | unit, every distinct value seen | `g` |
| `java_total_weight_values_found` | total weight, every distinct value seen | `1200 & 2400` |
| `java_conversion_parts_found` | conversion parts, one list per attribute that had them | `[2.0, 3.0]` |

## How it works

A supplier record for a single product carries upwards of 150 attributes: nutritional values, allergens, product and supplier codes, and so on. Most have nothing to do with pack size, but a handful can each carry it independently, and they don't always agree. The transformation reads four of them:

- the free-text **outer pack size** column, e.g. `2x3x400-500g`;
- the **product description**, e.g. `Apple Pies 1 x 4 100g`, which often repeats the pack size in passing;
- the **structured UoM columns** (outer UoM, outer-to-base conversion, base UoM, smallest-to-base conversion, smallest UoM), e.g. `CAS | 12 | EA | 100 | G`;
- the **base weight** attribute, which is free text and only occasionally holds a pack size; one supplier in the MDM populates it that way.

The pipeline then runs in four steps, all in [`informatica/1_helper_code.java`](informatica/1_helper_code.java) and orchestrated per row in [`informatica/2_on_input_row.java`](informatica/2_on_input_row.java).

**1. Clean the text.** Lower-case (so unit detection sees `g`, `ml`, etc.), replace brackets with spaces so `(2 x 3)400g` becomes `2 x 3 400g` and not `2 x 3400g`, remove commas (which appear as thousands separators in some weights), and collapse repeated spaces. `(1 x 4) x 1,000g` becomes `1 x 4 x 1000g`.

**2. Find the pack size.** Twelve regular expressions (`P1` … `P12`) are tried in a fixed order and the first one that matches wins. The order matters because the patterns are nested: `1 x 2 x 400` is a sub-pattern of `1 x 2 x 400g`, so the more specific pattern must be tried first. Each pattern's capture groups are collected into a list, so `2x3x400-500g` becomes `["2", "3", "400-500g"]`.

When only a conversion is found (e.g. `1 x 4` in `apple pies 1 x 4 100g`), a second pass looks for a stand-alone weight elsewhere in the string so that `100g` isn't lost.

**3. Turn the groups into numbers.** Each group is classified as a multiplier (`2`, `3`, `pack`, `doz`, `pair`), a base weight with a unit (`400-500g`, `33cl`), or something to ignore (`ptn`: a portion count describes servings, not a pack level). Multipliers are multiplied together to get the conversion. Ranges like `400-500g` become their midpoint. Units are standardised to `g`, `ml` or `min` (`kg` → ×1000 `g`, `cl` → ×10 `ml`, `lb` → ×453.1 `g`, and so on). The structured UoM columns go through their own reader, `numericalNormaliser`, which interprets `CAS | 12 | EA | 100 | G` as conversion 12, base weight 100 g.

A number with no unit is always treated as a count, never as a weight. The product range includes non-food items sold by count, so `1 x 2 x 400` means 800 units (a case of two boxes of 400 forks, say), and the output is `1x800`. If a weight is intended, the source must carry the unit.

**4. Reconcile and validate.** The values from all four attributes go into distinct sets, one per field (conversion, base weight, unit, total weight). The identity

```
total_weight = conversion × base_weight
```

is then used to fill in anything missing: if one attribute gave only a total (typically `CAS | 1000 | G`, meaning the whole case weighs 1000 g) and another gave only a base weight, the conversion is derived, and vice versa. Finally, any set with more than one value is a mismatch, and the flag names the fields involved. Deriving first and checking second matters: it's how `6 x 500g` in the text gets caught against a structured total of `2500 G`.

## Examples

Free text only:

```
800g-1.2kgx2x3    ->  6x1000g       (2 × 3 = 6 units; 800g–1.2kg standardised to grams, midpoint 1000g)
33cl x 3 x 4      ->  12x330ml      (cl standardised to ml)
pack of 10        ->  1x10          (a count, no weight)
1 x 2 x 400       ->  1x800         (no unit, so 400 is a count)
per kg            ->  1x1000g
10-15g x 20       ->  20x12.5g
3 x 4.5 lb        ->  3x2039g
30cm x 40cm       ->  Not Provided  (dimensions are ignored: they describe the physical size of the pack but say nothing about conversion or weight)
450g              ->  Not Provided  (the weight is still output, but with no conversion there is no pack size)
```

Cross-attribute validation:

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

## Extending it

Suppliers invent new ways of writing the same thing, so the vocabulary is designed to grow. The pattern that isn't matched today, `gloves 100 pcs` (count before the pack word), is an example of the kind of thing you'd add when it shows up in the data.

To add a **unit of measure** (say `mg`, which currently matches nothing): add it to the `UOMS` regex so the patterns can see it, to `VALID_UNITS` so step 3 classifies it as a weight, and to `unitCleanse` so it standardises (`mg` → ÷1000 → `g`). All three are needed; a unit that's only in `UOMS` will be matched but never converted.

To add a **pack word** (say `sleeve`): add it to `R_NUM_REPLACEMENTS`. Pack words carry no weight so nothing else changes.

To add a **new shape of string**: add a pattern to `PACK_PATTERNS`, placing it before any pattern it's a superset of. Then add the string to the tests with its expected groups and run `./run.sh test`.

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

- Values are taken at face value. If a source says a base weight is 3,924,804 kg, that is what comes out; there is no sanity check that the result is a plausible pack. (Whole-number base weights above 2,147,483,647 are also capped at that value by an `int` cast in `normaliseBaseWeight`; harmless for real data, but a one-line fix.)
- Ranges use the midpoint (`900g-1kg` → 950 g), which loses the spread. For ESG reporting the business preferred the upper bound, on the basis that over-reporting weight is safer than under-reporting; the code can be changed to take the max of the two values instead.
- Base weights of 1000 or more are rounded to whole numbers; smaller values keep one decimal place, so `12.55g` becomes `12.6g` and `0.5g` stays `0.5g`.
- Only the first matching pattern is used per string. A string with two independent sizes in it (e.g. a description mentioning both the case and the inner) only contributes the first.
