/**
 * Runs the transformation against a handful of example rows and prints the
 * result, so anyone can see what the parser does without needing Informatica.
 *
 * You can also pass your own pack-size strings on the command line:
 *
 *   java -cp out Main "6 x 330ml" "case of 24" "2x3x400-500g"
 */
public class Main {

    public static void main(String[] args) {
        PackSizeTransformation t = new PackSizeTransformation();

        if (args.length > 0) {
            for (String s : args) {
                print(s, t.processRow(s, null, null, null, null, null, null, null));
            }
            return;
        }

        System.out.println("---- Free-text pack sizes (only the outer pack size column populated) ----");
        String[] samples = {
            "2x3x400-500g",
            "pack of 10",
            "33cl x 3 x 4",
            "1 x 200 ea",
            "6 x 330ml",
            "per kg",
            "10-15g x 20",
            "3 x 4.5 lb",
            "5 x doz",
            "30cm x 40cm",
            "450g",
        };
        for (String s : samples) {
            print(s, t.processRow(s, null, null, null, null, null, null, null));
        }

        System.out.println();
        System.out.println("---- Free text + structured UoM columns (CAS | 12 | EA | 100 | G) ----");

        // Description agrees with the structured columns: no flag, one pack size
        print("12x100g  /  'biscuits 12 x 100g'  /  CAS 12 EA 100 G",
              t.processRow("12x100g", "biscuits 12 x 100g", null, "CAS", 12, "EA", 100, "G"));

        // Description says 200g but the structured columns say 100g: flagged
        print("12x100g  /  'biscuits 12 x 200g'  /  CAS 12 EA 100 G",
              t.processRow("12x100g", "biscuits 12 x 200g", null, "CAS", 12, "EA", 100, "G"));

        // Only the structured columns are present
        print("(no text)  /  CAS 24 EA 330 ML",
              t.processRow(null, null, null, "CAS", 24, "EA", 330, "ML"));

        // Structured columns give a total (CAS of 3000 G); text gives 6 x 500g. They agree.
        print("6 x 500g  /  CAS 3000 G",
              t.processRow("6 x 500g", null, null, "CAS", 3000, "G", null, null));

        // ... and here they don't (6 x 500g is 3000g, not 2500g)
        print("6 x 500g  /  CAS 2500 G",
              t.processRow("6 x 500g", null, null, "CAS", 2500, "G", null, null));
    }

    private static void print(String input, PackSizeTransformation.Result r) {
        System.out.printf("%-58s -> %s%n", input, r.java_outer_pack_size);
        System.out.printf("%-58s    flag=%s  uom=%s/%s  conversions=%s  base=%s  unit=%s  total=%s%n",
            "", r.java_mismatch_flag, r.java_outer_uom, r.java_base_uom,
            r.java_outer_to_base_uom_conversion, r.java_smallest_to_base_uom_conversion,
            r.java_smallest_unit_of_measure, r.java_total_weight_values_found);
    }
}
