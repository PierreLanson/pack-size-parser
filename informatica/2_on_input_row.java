// =====================================================================
//  Informatica CDI - Java Transformation - "On Input Row" tab
//
//  Runs once per row. Input ports (exp_*) and output ports (java_*)
//  are declared on the transformation itself; CDI injects them as
//  variables, which is why they are never declared here.
//
//  Inputs:
//    exp_o_outer_pack_size                 free-text pack size, e.g. "2x3x400-500g"
//    exp_Product_Description_Rule_Set      product description, e.g. "apple pies 1 x 4 100g"
//    exp_Base_UOM_Rule_Set                 a third free-text attribute that sometimes holds a size
//    exp_x_outer_pack_unit_of_measure      structured UoM columns, e.g. CAS | 12 | EA | 100 | G
//    exp_x_outer_to_base_uom_conversion
//    exp_x_base_unit_of_measure
//    exp_x_smallest_to_base_uom_conversion
//    exp_x_smallest_unit_of_measure
//
//  Outputs:
//    java_outer_pack_size                  e.g. "6x450g", or the mismatch label, or "Not Provided"
//    java_mismatch_flag                    "FALSE" or "MISMATCH (conversion, base_weight, ...)"
//    java_outer_uom / java_base_uom        CAS/EA, EA/EA, KG/EA or Not Provided
//    java_*_values_found                   every distinct value seen per field, joined with " & "
// =====================================================================

// Clean columns
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

generateRow();
