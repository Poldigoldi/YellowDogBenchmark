import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.util.ArrayList;
import java.util.Map;
import org.apache.commons.io.FilenameUtils;

public class Report {
    public static void main(String[] args) throws IOException, ParseException {
        /*File report = new File("report.txt");
        BufferedWriter bw = new BufferedWriter(new FileWriter(report));

        bw.write("Date            CPU Score               GPU Score\n\n");

        Object obj = new JSONParser().parse(new FileReader("output/bm_output_0.json"));
        JSONObject jo = (JSONObject) obj;
        ArrayList<String> benchmarkArray = new ArrayList<>();
        Map benchmarks = ((Map)jo.get("benchmarks"));
        for (Map.Entry pair : (Iterable<Map.Entry>) benchmarks.entrySet()) {
            benchmarkArray.add(pair.getKey() + " : " + pair.getValue());
        }
        String vrayVersion = (String) jo.get("vrayVersion");
        bw.write(benchmarkArray + "       " + vrayVersion);
        bw.close();*/


        File report = new File("report.txt");
        BufferedWriter bw = new BufferedWriter(new FileWriter(report));

        bw.write("Date            CPU Score               GPU Score\n\n");

        File folder = new File("output");
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles != null && listOfFiles.length > 0) {
            for (File file : listOfFiles) {
                if (file.isFile() && FilenameUtils.getExtension(file.getName()).equals("json")) {
                    Object obj = new JSONParser().parse(new FileReader(file));
                    JSONObject jo = (JSONObject) obj;
                    ArrayList<String> benchmarkArray = new ArrayList<>();
                    Map benchmarks = ((Map)jo.get("benchmarks"));
                    for (Map.Entry pair : (Iterable<Map.Entry>) benchmarks.entrySet()) {
                        benchmarkArray.add(pair.getKey() + " : " + pair.getValue());
                    }
                    String vrayVersion = (String) jo.get("vrayVersion");
                    bw.write(benchmarkArray + "       " + vrayVersion);
                }
            }
        }
        bw.close();
    }
}
