/**
 * Regression tests. Plain Java, no test framework, so they run with just javac/java.
 *
 * Every expected value here was produced by the transformation as it runs in
 * production, so these tests pin the current behaviour: if a future change to
 * the regexes or the parsing loop alters any of these outputs, a test fails
 * and you know before it reaches the pipeline.
 *
 * Run:  ./run.sh test      (or see README)
 */
public class PackSizeTransformationTest {

    private static int passed = 0;
    private static int failed = 0;
    private static final PackSizeTransformation T = new PackSizeTransformation();

    public static void main(String[] args) {

        // ---- Step 1: cleanText ------------------------------------------
        check("cleanText lowercases, drops brackets/commas, squashes spaces",
              PackSizeTransformation.cleanText("  Flour (Bread),  16KG "), "flour bread 16kg");
        check("cleanText null in, null out",
              PackSizeTransformation.cleanText(null), null);

        // ---- Step 2: extractPackSizeGroups (which pattern wins) ----------
        checkGroups("2x3x400-500g",         "[2, 3, 400-500g]");    // P1: n x n x range
        checkGroups("12x100g",              "[12, 100g]");          // P2: n x weight
        checkGroups("10-15g x 20",          "[10-15g, 20]");        // P3: range x n
        checkGroups("33cl x 3 x 4",         "[33cl, 3, 4]");        // P4: weight x n x n
        checkGroups("1 x 200 ea",           "[1, 200 ea]");         // P5: n x n pack-word
        checkGroups("box x 10",             "[box, 10]");           // P6: pack-word x n
        checkGroups("30cm x 40cm",          "[]");                  // P7: dimensions are rejected downstream
        checkGroups("1 x 4",                "[1, 4]");              // P8: conversion only
        checkGroups("pack of 10",           "[pack, 10]");          // P9: pack-word of n
        checkGroups("per kg",               "[per kg]");            // P10
        checkGroups("apple pies 1 x 4 100g","[1, 4, 100g]");        // P8 + follow-up P12 search for a weight
        checkGroups("450g",                 "[450g]");              // P12
        checkGroups("gloves 100 pcs",       "[]");                  // no pattern: number before pack-word

        // ---- Unit standardisation ----------------------------------------
        checkUnit(1.5,  "kg",    "1500", "g");
        checkUnit(33.0, "cl",    "330",  "ml");
        checkUnit(1.0,  "litre", "1000", "ml");
        checkUnit(4.5,  "lb",    "2039", "g");     // 4.5 * 453.1 = 2038.95 -> rounded (>= 1000)
        checkUnit(2.0,  "pint",  "1137", "ml");    // 2 * 568.3 = 1136.6 -> rounded (>= 1000)
        checkUnit(0.5,  "oz",    "14.2", "g");     // 14.175 -> 1 dp
        checkUnit(2.0,  "hr",    "120",  "min");

        // ---- End to end: free text only -----------------------------------
        checkPack("2x3x400-500g",   "6x450g",    "FALSE", "CAS", "EA");
        checkPack("pack of 10",     "1x10",      "FALSE", "CAS", "EA");
        checkPack("33cl x 3 x 4",   "12x330ml",  "FALSE", "CAS", "EA");
        checkPack("1 x 200 ea",     "1x200",     "FALSE", "CAS", "EA");
        checkPack("6 x 330ml",      "6x330ml",   "FALSE", "CAS", "EA");
        checkPack("per kg",         "1x1000g",   "FALSE", "EA",  "EA");
        checkPack("1x1kg",          "1x1000g",   "FALSE", "EA",  "EA");
        checkPack("10-15g x 20",    "20x12.5g",  "FALSE", "CAS", "EA");
        checkPack("2 x 4 x 200-250g","8x225g",   "FALSE", "CAS", "EA");
        checkPack("5 x doz",        "1x60",      "FALSE", "CAS", "EA");
        checkPack("2 x pair",       "1x4",       "FALSE", "CAS", "EA");
        checkPack("1 x 12 ptn",     "1x1",       "FALSE", "EA",  "EA");   // ptn is ignored, 1 remains
        checkPack("apple pies 1 x 4 100g", "4x100g", "FALSE", "CAS", "EA");
        checkPack("450g",           "Not Provided", "FALSE", "Not Provided", "Not Provided"); // weight but no conversion
        checkPack("30cm x 40cm",    "Not Provided", "FALSE", "Not Provided", "Not Provided"); // dimensions, not a pack
        checkPack("",               "Not Provided", "FALSE", "Not Provided", "Not Provided");
        checkPack(null,             "Not Provided", "FALSE", "Not Provided", "Not Provided");

        // ---- End to end: structured columns -------------------------------
        PackSizeTransformation.Result r;

        r = T.processRow(null, null, null, "CAS", 24, "EA", 330, "ML");
        check("CAS|24|EA|330|ML -> 24x330ml", r.java_outer_pack_size, "24x330ml");
        check("  ... total derived from conversion * base", r.java_total_weight_values_found, "7920");

        r = T.processRow(null, null, null, "EA", 330, "ML", null, null);
        check("EA|330|ML -> 1x330ml", r.java_outer_pack_size, "1x330ml");

        r = T.processRow(null, null, null, "CAS", "24", "EA", "330", "ML");
        check("numeric columns arriving as strings still parse", r.java_outer_pack_size, "24x330ml");

        r = T.processRow("bananas", null, null, "KG", 1, "KG", null, null);
        check("outer UoM KG forces KG/EA", r.java_outer_uom + "/" + r.java_base_uom, "KG/EA");

        // ---- End to end: cross-source validation ---------------------------
        r = T.processRow("12x100g", "biscuits 12 x 100g", null, "CAS", 12, "EA", 100, "G");
        check("three sources agree -> no flag", r.java_mismatch_flag, "FALSE");
        check("  ... and one pack size", r.java_outer_pack_size, "12x100g");

        r = T.processRow("12x100g", "biscuits 12 x 200g", null, "CAS", 12, "EA", 100, "G");
        check("description disagrees on weight -> flagged", r.java_mismatch_flag, "MISMATCH (base_weight, total_weight)");
        check("  ... pack size carries the flag", r.java_outer_pack_size, "MISMATCH (base_weight, total_weight)");
        check("  ... both weights are reported", r.java_smallest_to_base_uom_conversion, "100 & 200");

        r = T.processRow("6 x 500g", null, null, "CAS", 3000, "G", null, null);
        check("text 6x500g vs structured total 3000g agree", r.java_mismatch_flag, "FALSE");

        r = T.processRow("6 x 500g", null, null, "CAS", 2500, "G", null, null);
        check("text 6x500g vs structured total 2500g -> flagged", r.java_mismatch_flag, "MISMATCH (total_weight)");

        r = T.processRow("6 x 500g", "12 x 250g", null, null, null, null, null, null);
        check("same total, different split -> conversion and base flagged", r.java_mismatch_flag, "MISMATCH (conversion, base_weight)");
        check("  ... total agrees so it is not flagged", r.java_total_weight_values_found, "3000");

        // ---- Summary -------------------------------------------------------
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    private static void checkGroups(String input, String expected) {
        String actual = String.valueOf(
            PackSizeTransformation.extractPackSizeGroups(PackSizeTransformation.cleanText(input)));
        check("groups for \"" + input + "\"", actual, expected);
    }

    private static void checkUnit(double value, String unit, String expectedValue, String expectedUnit) {
        PackSizeTransformation.UnitResult u = PackSizeTransformation.unitCleanse(value, unit);
        check("unitCleanse " + value + " " + unit,
              PackSizeTransformation.formatNumberNoTrailingDotZero(u.baseWeight) + " " + u.unit,
              expectedValue + " " + expectedUnit);
    }

    private static void checkPack(String input, String packSize, String flag, String outerUom, String baseUom) {
        PackSizeTransformation.Result r = T.processRow(input, null, null, null, null, null, null, null);
        check("\"" + input + "\" pack size", r.java_outer_pack_size, packSize);
        check("\"" + input + "\" flag",      r.java_mismatch_flag,   flag);
        check("\"" + input + "\" uoms",      r.java_outer_uom + "/" + r.java_base_uom, outerUom + "/" + baseUom);
    }

    private static void check(String name, String actual, String expected) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name + "\n         expected: " + expected + "\n         actual:   " + actual);
        }
    }
}
