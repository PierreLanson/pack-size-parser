import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Standalone, runnable version of the Informatica CDI Java transformation.
 *
 * The body of this class is the "Helper Code" tab and the "On Input Row" tab
 * from the informatica/ folder, copied in verbatim. The only additions are:
 *   - the class wrapper itself,
 *   - the {@link Result} holder, which stands in for the java_* output ports,
 *   - {@link #processRow}, whose parameters stand in for the exp_* input ports,
 *     and which returns a Result instead of calling generateRow().
 *
 * Nothing about the parsing or validation logic is changed, so what runs here
 * is what runs in the pipeline.
 */
public class PackSizeTransformation {

    // =====================================================================
    //  Helper Code  (informatica/1_helper_code.java)
    // =====================================================================

    // --------------------------- Step 1: Clean Text -----------------------------
    // static means it's the same in every class, final means it cannot be changed

    static final class RawPack {
        final Double conversion;   // null = NOT MAPPED
        final Double baseWeight;   // null = NOT MAPPED
        final String unit;         // null = NOT MAPPED
        final Double totalWeight;  // null = NOT MAPPED
        final java.util.List<Double> conversionParts;
        RawPack(Double conversion, Double baseWeight, String unit, Double totalWeight, java.util.List<Double> conversionParts) {
            this.conversion = conversion;
            this.baseWeight = baseWeight;
            this.unit = unit;
            this.totalWeight = totalWeight;
            this.conversionParts = conversionParts;
        }
    }

    static final class NumUnit {
        final Double num;
        final String token;
        NumUnit(Double num, String token) { this.num = num; this.token = token; }
    }

    static final Pattern BRACKETS = Pattern.compile("[\\(\\)\\[\\]\\{\\}]");
    static final Pattern COMMAS   = Pattern.compile(",");
    static final Pattern WS       = Pattern.compile("\\s+");

    static String cleanText(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ROOT);
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = COMMAS.matcher(t).replaceAll("");
        t = WS.matcher(t).replaceAll(" ").trim();
        return t;
    }

    // --------------------------- Step 2: Find Pack Size (pattern id only) -----------------------------
    static final String UOMS =
        "(?:kilo|kilos|gm|grams|gram|grm|grms|gms|kg|litre|litres|liter|ltr|lt|millilitre|milliliter|ml|cl|ounce|ounces|oz|pounds|pound|lb|lbs|gal|gallon|gallons|pint|pints|pnt|min|mins|hr|hrs|hour|hours|g|l)";

    static final String R_NUM_REPLACEMENTS =
        "(?:ea|each|eaches|pk|packs|pack|pac|sgl|bag|bags|bun|sach|single|ind|box|boxes|boxs|case|cases|cas|tray|trays|tra|ptn|pcs|can|doz|pair|kilo|kg|doz|pair|tub|tin)";

    static final Pattern P1 = Pattern.compile(
        "(\\d+)\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*x{1,2}\\s*(\\d+\\.?\\d*\\s*"
        + UOMS + "?\\s*-\\s*\\d+\\.?\\d*\\s*" + UOMS + ")",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P2 = Pattern.compile(
        "(\\d+)\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*x{1,2}\\s*(\\d+\\.?\\d*\\s*"
        + UOMS + ")",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P3 = Pattern.compile(
        "(\\d+\\.?\\d*\\s*" + UOMS + "?\\s*-\\s*\\d+\\.?\\d*\\s*" + UOMS + ")\\s*(?:x{1,2}\\s*(\\d+))\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P4 = Pattern.compile(
        "(\\d+\\.?\\d*\\s*" + UOMS + ")\\s*(?:x{1,2}\\s*(\\d+))\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P5 = Pattern.compile(
        "(\\d+)\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*x{1,2}\\s*((?:\\d+)?\\s*"
        + R_NUM_REPLACEMENTS + ")",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P6 = Pattern.compile(
        "((?:\\d+)?\\s*" + R_NUM_REPLACEMENTS + ")\\s*(?:x{1,2}\\s*(\\d+))\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P7 = Pattern.compile(
        "(\\d+\\.?\\d*)\\s*x{1,2}\\s*(\\d+\\.?\\d*)\\s*(?:x{1,2}\\s*(\\d+\\.?\\d*))?\\s*(?:(cm|mm|m|in|\\\"))",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P8 = Pattern.compile(
        "(\\d+)\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))?\\s*(?:x{1,2}\\s*(\\d+))",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P9 = Pattern.compile(
        "(?:^|\\s+)(" + R_NUM_REPLACEMENTS + ")\\s*(?:of)?\\s*(\\d+\\.?\\d*\\s*" + UOMS + "?)(?:\\s+|$)",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P10 = Pattern.compile(
        "(?:^|\\s+)(per\\s*" + UOMS + ")(?:\\s+|$)",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P11 = Pattern.compile(
        "(?:^|\\s+)(\\d+\\.?\\d*\\s*" + UOMS + "?\\s*-\\s*\\d+\\.?\\d*\\s*" + UOMS + ")(?:\\s+|$)",
        Pattern.CASE_INSENSITIVE);

    static final Pattern P12 = Pattern.compile(
        "(?:^|\\s+)(\\d+\\.?\\d*\\s*" + UOMS + ")(?:\\s+|$)",
        Pattern.CASE_INSENSITIVE);

    static final List<Pattern> PACK_PATTERNS = Arrays.asList(P1,P2,P3,P4,P5,P6,P7,P8,P9,P10,P11,P12);

    static final Pattern PER_RE = Pattern.compile("\\bper\\s+([a-zA-Z]+)\\b", Pattern.CASE_INSENSITIVE);

    static boolean hasSingleHyphen(String s) {
        if (s == null) return false;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '-') count++;
            if (count > 1) return false; // mimic: warn in python; here we just treat as not a simple range
        }
        return count == 1;
    }

    // Extracts lhs and rhs around a single hyphen and returns baseWeight/unit as raw (not normalised)
    // Returns RawPack with only baseWeight/unit populated; other fields null.
    static RawPack rangeCleanRaw(String group) {
        if (group == null) return new RawPack(null, null, null, null, null);

        String g = group.toLowerCase(Locale.ROOT);
        int idx = g.indexOf('-');
        if (idx < 0) return new RawPack(null, null, null, null, null);

        String lhs = g.substring(0, idx).trim();
        String rhs = g.substring(idx + 1).trim();

        NumUnit left = extractValueAndUnit(lhs);
        NumUnit right = extractValueAndUnit(rhs);

        if (left.num == null || right.num == null) return new RawPack(null, null, null, null, null);

        // Python: if lhs unit missing, inherit rhs unit
        String lhsUnit = left.token;
        String rhsUnit = right.token;
        if (lhsUnit == null) lhsUnit = rhsUnit;

        if (lhsUnit == null || rhsUnit == null) return new RawPack(null, null, null, null, null);

        double lhsVal = left.num;
        double rhsVal = right.num;

        // Standardise both sides first (Python behaviour)
        UnitResult lhsStd = unitCleanse(lhsVal, lhsUnit);
        UnitResult rhsStd = unitCleanse(rhsVal, rhsUnit);

        if (lhsStd.baseWeight == null || rhsStd.baseWeight == null || lhsStd.unit == null || rhsStd.unit == null) {
            return new RawPack(null, null, null, null, null);
        }

        // Now units must match AFTER standardisation
        if (!lhsStd.unit.equals(rhsStd.unit)) {
            return new RawPack(null, null, null, null, null);
        }

        lhsVal = lhsStd.baseWeight;
        rhsVal = rhsStd.baseWeight;
        String outUnit = rhsStd.unit;

        double avg = (lhsVal + rhsVal) / 2.0;
        return new RawPack(null, avg, outUnit, null, null);
    }

    static boolean isDimensionUnit(String tok) {
        if (tok == null) return false;
        return tok.equals("cm") || tok.equals("mm") || tok.equals("m") || tok.equals("in") || tok.equals("\"");
    }

    static boolean isConversionOnlyPattern(Pattern p) {
        return p == P5 || p == P6 || p == P8;
    }

    // Returns the first match found or null if no patterns match.
    // Loops through each pattern in the hierarchy, and stops looping when first the first pattern mattches
    // This is due to patterns being a subset of another pattern and why the hierarchy is critical
    // e.g. 1 x 2 x 400 is a subset of the pattern 1 x 2 x 400g
    // This is the function is the get_pack_size(text) from the python script
    // There is one added part is that if we are in a desc and they have a conversion only pattern
    // e.g "apple pies 1 x 4 100g" it will match the "1x4" and then we also search for a base weight, 100g so we don't lose info
    static List<String> extractPackSizeGroups(String cleanedText) {

        List<String> matchedGroups = new ArrayList<>();

        if (cleanedText == null || cleanedText.trim().isEmpty())
            return matchedGroups;

        for (Pattern p : PACK_PATTERNS) {

            Matcher m = p.matcher(cleanedText);

            if (m.find()) {

                // Loop over the match groups to append each group to a list
                for (int i = 1; i <= m.groupCount(); i++) {
                    String g = m.group(i);
                    if (g != null && !g.trim().isEmpty()) {
                        matchedGroups.add(g.trim());
                    }
                }

                // If conversion-only pattern ("1x50" or "case of 10" or "box x 10"), also search weight patterns
                if (p == P5 || p == P6 || p == P7 || p == P8 || p == P9) {

                    Matcher w11 = P11.matcher(cleanedText);
                    if (w11.find()) {
                        matchedGroups.add(w11.group(1));
                    } else {
                        Matcher w12 = P12.matcher(cleanedText);
                        if (w12.find()) {
                            matchedGroups.add(w12.group(1));
                        }
                    }
                }

                break; // preserve first-match behaviour
            }
        }

        return matchedGroups;
    }

    static final Pattern NUM_RE  = Pattern.compile("(\\d+\\.?\\d*)");
    static final Pattern WORD_RE = Pattern.compile("([a-zA-Z]+)");

    static NumUnit extractValueAndUnit(String text) {
        if (text == null) return new NumUnit(null, null);

        Matcher nm = NUM_RE.matcher(text);
        Matcher wm = WORD_RE.matcher(text);

        Double num = nm.find() ? Double.valueOf(nm.group(1)) : null;
        String tok = wm.find() ? wm.group(1).toLowerCase(Locale.ROOT) : null;

        return new NumUnit(num, tok);
    }

    // these are all the uoms we are looking for - add any that we missed but you must also add in unit cleanse
    // so that it can get conversion is also known.
    static final List<String> VALID_UNITS = Arrays.asList(
        "gram", "grams", "grm", "grms", "gm", "gms",
        "kg", "kilo", "kilos",
        "ml", "millilitre", "milliliter",
        "lt", "ltr", "litre", "liter", "litres",
        "cl",
        "oz", "ounce", "ounces",
        "lb", "lbs", "pound", "pounds",
        "gal", "gallon", "gallons",
        "pint", "pints", "pnt", "pt",
        "min", "mins", "hr", "hrs", "hour", "hours",
        "g", "l"
    );

    static boolean isValidUnit(String u) {
        if (u == null) return false;
        return VALID_UNITS.contains(u);
    }

    static boolean isValidPack(String u) {
        if (u == null) return false;
        return R_NUM_REPLACEMENTS.contains(u);
    }

    static final class UnitResult {
        final Double baseWeight;
        final String unit;
        UnitResult(Double baseWeight, String unit) {
            this.baseWeight = baseWeight;
            this.unit = unit;
        }
    }

    static double normaliseBaseWeight(double x) {
        // 100.0 -> 100
        if (x == Math.rint(x)) {
            return (double)((int) x);
        }

        // rounding if 4 or more significant figures so 1000.1 becomes 1000
        if (x >= 1000.0) {
            return (double) Math.round(x);
        }

        // otherwise keep 1 decimal place max e.g 0.5g doesn't get rounded to 1g or 10g-15g becomes 12.5g not 13g
        return Math.round(x * 10.0) / 10.0;
    }

    static UnitResult unitCleanse(Double baseWeight, String unit) {
        if (baseWeight == null || unit == null) return new UnitResult(baseWeight, unit);

        String u = unit.toLowerCase(Locale.ROOT).trim();
        double bw = baseWeight.doubleValue();

        if (u.equals("g") || u.equals("gram") || u.equals("grams") || u.equals("grm") || u.equals("grms") || u.equals("gm") || u.equals("gms")) {
            u = "g";
        } else if (u.equals("kilo") || u.equals("kg") || u.equals("kilos")) {
            bw *= 1000.0;
            u = "g";
        } else if (u.equals("l") || u.equals("litre") || u.equals("litres") || u.equals("ltr") || u.equals("lt") || u.equals("liter")) {
            bw *= 1000.0;
            u = "ml";
        } else if (u.equals("cl")) {
            bw *= 10.0;
            u = "ml";
        } else if (u.equals("oz") || u.equals("ounce") || u.equals("ounces")) {
            bw *= 28.35;
            u = "g";
        } else if (u.equals("pounds") || u.equals("pound") || u.equals("lb") || u.equals("lbs")) {
            bw *= 453.1;
            u = "g";
        } else if (u.equals("gallon") || u.equals("gallons") || u.equals("gal")) {
            bw *= 4546.0;
            u = "ml";
        } else if (u.equals("pint") || u.equals("pints") || u.equals("pnt") || u.equals("pt")) {
            bw *= 568.3;
            u = "ml";
        } else if (u.equals("hr") || u.equals("hrs") || u.equals("hour") || u.equals("hours")) {
            bw *= 60.0;
            u = "min";
        }

        bw = normaliseBaseWeight(bw);
        return new UnitResult(bw, u);
    }

    // if the set has multiple values it returns null but if it has 1 value it returns that single value
    static String onlyOrNull(java.util.Set<String> s) {
        if (s.size() != 1) return null;
        return s.iterator().next();
    }

    static String joinWith(String sep, Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String it : items) {
            if (!first) sb.append(sep);
            sb.append(it);
            first = false;
        }
        return sb.toString();
    }

    static String fmtNoTrailingZero(String s) {
        if (s == null) return null;
        // remove ".0" at end
        if (s.endsWith(".0")) return s.substring(0, s.length() - 2);
        return s;
    }

    static RawPack extractUoMValues(List<String> matchedGroups) {

        Double baseWeight = null;
        String unit = null;

        ArrayList<Double> convParts = new ArrayList<>();
        ArrayList<Double> convList  = new ArrayList<>();

        if (matchedGroups == null || matchedGroups.isEmpty()) {
            return new RawPack(null, null, null, null, null);
        }

        // ---- Handle "per <unit>" logic ----
        for (String g : matchedGroups) {
            if (g == null) continue;

            Matcher pm = PER_RE.matcher(g.toLowerCase(Locale.ROOT));
            if (pm.find()) {
                String perUnit = pm.group(1);

                UnitResult ur = unitCleanse(1.0, perUnit);
                convList.add(1.0);

                return new RawPack(1.0, ur.baseWeight, ur.unit, ur.baseWeight, convList);
            }
        }

        // ---- Main Parsing Loop ----
        for (String g : matchedGroups) {

            if (g == null || g.trim().isEmpty()) continue;

            g = g.toLowerCase(Locale.ROOT);

            NumUnit nu = extractValueAndUnit(g);
            Double numVal = nu.num;
            String tok = nu.token;

            if (numVal == null && tok == null) continue;

            // Range check
            if (hasSingleHyphen(g)) {
                RawPack r = rangeCleanRaw(g);
                if (r.baseWeight != null && r.unit != null) {
                    baseWeight = r.baseWeight;
                    unit = r.unit;
                }
                continue;
            }

            if (tok != null) {

                if ("ptn".equals(tok)) {
                    continue;
                }

                if (isDimensionUnit(tok)) {
                    return new RawPack(null, null, null, null, null);
                }

                // pair multiplier
                if ("pair".equals(tok)) {
                    double mult = (numVal == null) ? 2.0 : (numVal * 2.0);
                    convParts.add(mult);
                    convList.add(mult);
                    continue;
                }

                // dozen multiplier
                if ("doz".equals(tok)) {
                    double mult = (numVal == null) ? 12.0 : (numVal * 12.0);
                    convParts.add(mult);
                    convList.add(mult);
                    continue;
                }

                // Valid measurement unit
                if (isValidUnit(tok) && numVal != null) {
                    baseWeight = numVal;
                    unit = tok;
                    continue;
                }

                if (isValidUnit(tok) && numVal == null) {
                    baseWeight = 1.0;
                    unit = tok;
                    continue;
                }

                // Valid pack token
                if (isValidPack(tok) && numVal != null) {
                    convParts.add(numVal);
                    convList.add(numVal);
                    continue;
                }

                if (isValidPack(tok) && numVal == null) {
                    convList.add(1.0);
                    continue;
                }
            }

            // Remaining standalone numbers contribute to conversion
            if (numVal != null) {
                convParts.add(numVal);
                convList.add(numVal);
            }
        }

        // ---- Compute conversion ----
        Double conversion = null;
        if (!convParts.isEmpty()) {
            double prod = 1.0;
            for (double v : convParts) prod *= v;
            conversion = prod;
        }

        // ---- Standardise base weight ----
        if (baseWeight != null && unit != null) {
            UnitResult ur = unitCleanse(baseWeight, unit);
            baseWeight = ur.baseWeight;
            unit = ur.unit;
        }

        // ---- Compute total weight ----
        Double totalWeight = null;
        if (conversion != null && baseWeight != null) {
            totalWeight = conversion * baseWeight;
        }

        List<Double> outConvList = convList.isEmpty() ? null : convList;

        return new RawPack(conversion, baseWeight, unit, totalWeight, outConvList);
    }

    static String formatNumberNoTrailingDotZero(Double v) {
        if (v == null) return null;
        double x = v.doubleValue();
        if (x == Math.rint(x)) return String.valueOf((long) Math.rint(x));
        return String.valueOf(x);
    }

    static String buildPackSize(RawPack rp) {
        if (rp == null || rp.conversion == null) return null;

        String conv = formatNumberNoTrailingDotZero(rp.conversion);

        if (rp.baseWeight != null && rp.unit != null) {
            String bw = formatNumberNoTrailingDotZero(rp.baseWeight);
            String u = rp.unit;
            return conv + "x" + bw + u;
        }

        // conversion but no base weight/unit
        return "1x" + conv;
    }

    static boolean isMapped(Object v) {
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return false;
            if (s.equalsIgnoreCase("NOT MAPPED")) return false;
        }
        if (v == null) return false;
        return true;
    }

    // this is a bit speggetti code but it is due to all the trailing .0 from when we are
    // calculating the values from the equation total weight = conversion * base weight
    static void addIfMapped(Set<String> set, Object v) {
        if (!isMapped(v)) return;

        if (v instanceof Double) {
            set.add(formatNumberNoTrailingDotZero((Double) v));
            return;
        }

        if (v instanceof String) {
            try {
                Double d = Double.valueOf((String) v);
                set.add(formatNumberNoTrailingDotZero(d));
                return;
            } catch (Exception e) {}
        }

        set.add(String.valueOf(v));
    }

    // Adding reader for numerical conversions

    static final java.util.Set<String> OUTER_UOMS =
        new java.util.LinkedHashSet<String>(
            java.util.Arrays.asList("CAS", "CS", "BOX", "CASE")
        );

    static final java.util.Set<String> SINGLE_UOMS =
        new java.util.LinkedHashSet<String>(
            java.util.Arrays.asList("EA", "EACH", "BTL", "SINGLE")
        );

    static final java.util.Set<String> WEIGHT_UOMS =
        new java.util.LinkedHashSet<String>(
            java.util.Arrays.asList("G", "ML", "KG", "CL", "LITRE", "L")
        );

    static RawPack numericalNormaliser(
        String sourcesystem,
        String outerUom,
        Double outerToBaseConversion,
        String baseUom,
        Double smallestToBaseConversion,
        String smallestUom
    ) {

        Double conversion = null;
        Double baseWeight = null;
        String unit = null;
        Double totalWeight = null;

        outerUom    = (outerUom == null)    ? null : outerUom.trim().toUpperCase();
        baseUom     = (baseUom == null)     ? null : baseUom.trim().toUpperCase();
        smallestUom = (smallestUom == null) ? null : smallestUom.trim().toUpperCase();

        boolean outerValid       = OUTER_UOMS.contains(outerUom);
        boolean baseIsSingle     = SINGLE_UOMS.contains(baseUom);
        boolean baseIsWeight     = WEIGHT_UOMS.contains(baseUom);
        boolean smallestIsWeight = WEIGHT_UOMS.contains(smallestUom);
        boolean outerIsSingle    = SINGLE_UOMS.contains(outerUom);

        // These are the standard looking conversions CAS | 12 | EA | 100 | g ==> 12x100g, EA | 330 | ml | null | null ==> 1x330ml
        if ("source_a".equalsIgnoreCase(sourcesystem) || "erp".equalsIgnoreCase(sourcesystem) || "source_c".equalsIgnoreCase(sourcesystem) || "source_d".equalsIgnoreCase(sourcesystem)) {

            if (outerValid && baseIsSingle) {
                conversion = outerToBaseConversion;
            }

            if (baseIsSingle && smallestIsWeight) {
                baseWeight = smallestToBaseConversion;
                unit = smallestUom;
            }

            if (outerValid && baseIsWeight) {
                totalWeight = outerToBaseConversion;
                unit = baseUom;
            }

            if (outerIsSingle && baseIsWeight) {
                conversion = 1.0;
                baseWeight = outerToBaseConversion;
                unit = baseUom;
            }
        }
        // basic_source - very basic has only conversion - is there missing data and some sort of mapping issue as we only get conversion?
        if ("basic_source".equalsIgnoreCase(sourcesystem)) {
            conversion = outerToBaseConversion;
        }

        // normalise base weight
        if (baseWeight != null && unit != null) {
            UnitResult ur = unitCleanse(baseWeight, unit);
            baseWeight = ur.baseWeight;
            unit = ur.unit;
        }

        // this may be redundant?
        if (totalWeight != null && unit != null) {
            UnitResult ur = unitCleanse(totalWeight, unit);
            totalWeight = ur.baseWeight;
            unit = ur.unit;
        }

        if (unit != null) {
            unit = unit.toLowerCase(Locale.ROOT);
        }

        return new RawPack(conversion, baseWeight, unit, totalWeight, null);
    }

    // -------------------------------------------------
    // Helper: Safe numeric conversion
    // -------------------------------------------------
    private Double numCheck(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        if (value instanceof String) {
            String str = ((String) value).trim();
            if (str.isEmpty()) {
                return null;
            }

            try {
                return Double.parseDouble(str);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    // =====================================================================
    //  On Input Row  (informatica/2_on_input_row.java)
    // =====================================================================

    /** Stands in for the java_* output ports of the transformation. */
    public static final class Result {
        public String java_outer_pack_size;
        public String java_mismatch_flag;
        public String java_outer_uom;
        public String java_base_uom;
        public String java_outer_to_base_uom_conversion;
        public String java_smallest_to_base_uom_conversion;
        public String java_smallest_unit_of_measure;
        public String java_total_weight_values_found;
        public String java_conversion_parts_found;

        @Override
        public String toString() {
            return "pack_size=" + java_outer_pack_size
                + " | flag=" + java_mismatch_flag
                + " | outer_uom=" + java_outer_uom
                + " | base_uom=" + java_base_uom
                + " | conversions=" + java_outer_to_base_uom_conversion
                + " | base_weights=" + java_smallest_to_base_uom_conversion
                + " | units=" + java_smallest_unit_of_measure
                + " | totals=" + java_total_weight_values_found
                + " | parts=" + java_conversion_parts_found;
        }
    }

    /**
     * One call = one input row. Parameter names match the exp_* input ports.
     * The two *_conversion parameters are Object because CDI may hand them
     * over as String, Double or BigDecimal depending on the mapping; numCheck
     * copes with all of them.
     */
    public Result processRow(
            String exp_o_outer_pack_size,
            String exp_Product_Description_Rule_Set,
            String exp_Base_UOM_Rule_Set,
            String exp_x_outer_pack_unit_of_measure,
            Object exp_x_outer_to_base_uom_conversion,
            String exp_x_base_unit_of_measure,
            Object exp_x_smallest_to_base_uom_conversion,
            String exp_x_smallest_unit_of_measure) {

        // output ports
        String java_outer_pack_size = null;
        String java_mismatch_flag = null;
        String java_outer_uom = null;
        String java_base_uom = null;
        String java_outer_to_base_uom_conversion = null;
        String java_smallest_to_base_uom_conversion = null;
        String java_smallest_unit_of_measure = null;
        String java_total_weight_values_found = null;
        String java_conversion_parts_found = null;

        String outer_clean      = cleanText(exp_o_outer_pack_size);
        String desc_clean       = cleanText(exp_Product_Description_Rule_Set);
        String attribute3_clean = cleanText(exp_Base_UOM_Rule_Set);

        // Interpret numerical columns, each source can behave differently hense why we send source in the "measure" / function in python terms
        RawPack rp_numeric = numericalNormaliser(
            "erp",
            exp_x_outer_pack_unit_of_measure,
            numCheck(exp_x_outer_to_base_uom_conversion),
            exp_x_base_unit_of_measure,
            numCheck(exp_x_smallest_to_base_uom_conversion),
            String.valueOf(exp_x_smallest_unit_of_measure)
        );

        // rp_numeric returns conversion, baseWeight, unit, totalWeight, conversionParts
        // conversionParts will always be null / not applicable as you don't have 'groups' like "1 x 2 x 300g" as it is CAS | 24 | EA | 100 | g becomes 24x100g
        // Extract the groups from the string and make a list
        // e.g. "2x3x400g" → ["2", "3", "400g"]

        List<String> outerGroups      = extractPackSizeGroups(outer_clean);
        List<String> descGroups       = extractPackSizeGroups(desc_clean);
        List<String> attribute3Groups = extractPackSizeGroups(attribute3_clean);

        // Convert groups into numerical fields

        RawPack rp_outer      = extractUoMValues(outerGroups);
        RawPack rp_desc       = extractUoMValues(descGroups);
        RawPack rp_attribute3 = extractUoMValues(attribute3Groups);

        // DISTINCT SETS (ignore null / NOT MAPPED)
        // addIfMapped only adds meaningful values to the
        // comparison set.
        // Null, empty strings, or "NOT MAPPED" are ignored.
        // This prevents false mismatches (e.g. comparing
        // "NOT MAPPED" with "1" would incorrectly trigger
        // a conversion flag)

        java.util.Set<String> convSet           = new java.util.LinkedHashSet<String>();
        java.util.Set<String> baseSet           = new java.util.LinkedHashSet<String>();
        java.util.Set<String> unitSet           = new java.util.LinkedHashSet<String>();
        java.util.Set<String> totalSet          = new java.util.LinkedHashSet<String>();
        java.util.Set<String> conversionPartSet = new java.util.LinkedHashSet<String>();

        // adding the values to a set - is there a cleaner way to do this?
        // conversion
        addIfMapped(convSet, rp_outer.conversion);
        addIfMapped(convSet, rp_desc.conversion);
        addIfMapped(convSet, rp_attribute3.conversion);
        addIfMapped(convSet, rp_numeric.conversion);

        // base weight
        addIfMapped(baseSet, rp_outer.baseWeight);
        addIfMapped(baseSet, rp_desc.baseWeight);
        addIfMapped(baseSet, rp_attribute3.baseWeight);
        addIfMapped(baseSet, rp_numeric.baseWeight);

        // unit
        addIfMapped(unitSet, rp_outer.unit);
        addIfMapped(unitSet, rp_desc.unit);
        addIfMapped(unitSet, rp_attribute3.unit);
        addIfMapped(unitSet, rp_numeric.unit);

        // total weight
        addIfMapped(totalSet, rp_outer.totalWeight);
        addIfMapped(totalSet, rp_desc.totalWeight);
        addIfMapped(totalSet, rp_attribute3.totalWeight);
        addIfMapped(totalSet, rp_numeric.totalWeight);

        // conversion parts (numeric always null)
        addIfMapped(conversionPartSet, rp_outer.conversionParts);
        addIfMapped(conversionPartSet, rp_desc.conversionParts);
        addIfMapped(conversionPartSet, rp_attribute3.conversionParts);

        String convStr  = onlyOrNull(convSet);
        String baseStr  = onlyOrNull(baseSet);
        String unitStr  = onlyOrNull(unitSet);
        String totalStr = onlyOrNull(totalSet);

        // Using the equation total weight = conversion * base weight we can solve for a missing value if we know the other two values
        // working out conversion from base and total
        // as we are working out the values the doubles cause values like 2 to be 2.0 so we need to trim the .0 of the values before adding to the sets
        if ((baseStr != null) && (totalStr != null)) {
            Double conversion = Double.valueOf(totalStr) / Double.valueOf(baseStr);
            convSet.add(formatNumberNoTrailingDotZero(conversion));
        }

        // working out base weight from conversion and total
        if ((convStr != null) && (totalStr != null)) {
            Double base_weight = Double.valueOf(totalStr) / Double.valueOf(convStr);
            baseSet.add(formatNumberNoTrailingDotZero(normaliseBaseWeight(base_weight)));
        }

        // working out total weight from conversion and base weight
        if ((convStr != null) && (baseStr != null)) {
            Double total_weight = Double.valueOf(convStr) * Double.valueOf(baseStr);
            totalSet.add(formatNumberNoTrailingDotZero(total_weight));
        }

        // Make the missmatch label - if there is more than one value in a set then there is an issue

        java.util.ArrayList<String> mismatches = new java.util.ArrayList<String>();

        // This is where you can choose what flags if there are multiple values e.g. if multiple base weights from a product 100g and 200g it flags
        if (convSet.size() > 1)  mismatches.add("conversion");
        if (baseSet.size() > 1)  mismatches.add("base_weight");
        if (unitSet.size() > 1)  mismatches.add("unit");
        if (totalSet.size() > 1) mismatches.add("total_weight");
        // if (conversionPartSet.size() > 1) mismatches.add("conversion_list");

        if (mismatches.isEmpty()) {
            java_mismatch_flag = "FALSE";
        } else {
            java_mismatch_flag = "MISMATCH (" + joinWith(", ", mismatches);
            java_mismatch_flag += ")";
        }

        // OUTPUT LOGIC
        // If Flag is true don't output outer pack size

        if (!"FALSE".equals(java_mismatch_flag)) {
            java_outer_pack_size = java_mismatch_flag;
        } else {

            convStr  = onlyOrNull(convSet);
            baseStr  = onlyOrNull(baseSet);
            unitStr  = onlyOrNull(unitSet);
            totalStr = onlyOrNull(totalSet);

            if (convStr == null) {
                java_outer_pack_size = "Not Provided"; // no conversion => no pack size
            } else if (baseStr != null && unitStr != null) {
                java_outer_pack_size = fmtNoTrailingZero(convStr) + "x" + fmtNoTrailingZero(baseStr) + unitStr;
            } else {
                java_outer_pack_size = "1x" + fmtNoTrailingZero(convStr);
            }
        }

        if ("KG".equals(exp_x_outer_pack_unit_of_measure)) {
            java_outer_uom = "KG";
            java_base_uom  = "EA";

        } else if (convSet != null && convSet.size() == 1 && convStr != null) {
            try {
                double convVal = Double.parseDouble(convStr);

                if (convVal > 1) {
                    java_outer_uom = "CAS";
                    java_base_uom  = "EA";
                } else if (convVal == 1) {
                    java_outer_uom = "EA";
                    java_base_uom  = "EA";
                } else {
                    // edge case: zero or negative
                    java_outer_uom = "Not Provided";
                    java_base_uom  = "Not Provided";
                }

            } catch (NumberFormatException e) {
                java_outer_uom = "Not Provided";
                java_base_uom  = "Not Provided";
            }

        } else {
            java_outer_uom = "Not Provided";
            java_base_uom  = "Not Provided";
        }

        // WRITE values_found OUTPUTS (NULL if empty)

        java_outer_to_base_uom_conversion    = convSet.isEmpty()           ? null : joinWith(" & ", convSet);
        java_smallest_to_base_uom_conversion = baseSet.isEmpty()           ? null : joinWith(" & ", baseSet);
        java_smallest_unit_of_measure        = unitSet.isEmpty()           ? null : joinWith(" & ", unitSet);
        java_total_weight_values_found       = totalSet.isEmpty()          ? null : joinWith(" & ", totalSet);
        java_conversion_parts_found          = conversionPartSet.isEmpty() ? null : joinWith(" & ", conversionPartSet);

        // generateRow();  -> return the outputs instead
        Result r = new Result();
        r.java_outer_pack_size = java_outer_pack_size;
        r.java_mismatch_flag = java_mismatch_flag;
        r.java_outer_uom = java_outer_uom;
        r.java_base_uom = java_base_uom;
        r.java_outer_to_base_uom_conversion = java_outer_to_base_uom_conversion;
        r.java_smallest_to_base_uom_conversion = java_smallest_to_base_uom_conversion;
        r.java_smallest_unit_of_measure = java_smallest_unit_of_measure;
        r.java_total_weight_values_found = java_total_weight_values_found;
        r.java_conversion_parts_found = java_conversion_parts_found;
        return r;
    }
}
