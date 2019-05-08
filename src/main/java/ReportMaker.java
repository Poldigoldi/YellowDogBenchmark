import java.io.*;
import java.util.ArrayList;

public class ReportMaker {
    public static void main(String[] args) throws IOException {
        File report = new File("report.txt");
        BufferedWriter bw = new BufferedWriter(new FileWriter(report));
        String st;
        String dateTime = "", CPUScore = "", GPUScore = "";

        bw.write("Date            CPU Score               GPU Score\n\n");

        File folder = new File("output");
        File[] listOfFiles = folder.listFiles();
        String filename;

        if (listOfFiles != null && listOfFiles.length > 0) {
            for (File listOfFile : listOfFiles) {
                if (listOfFile.isFile()) {
                    ArrayList<String> benchmarkInfo = new ArrayList<>();
                    filename = listOfFile.getName();
                    File file = new File("output/" + filename);
                    BufferedReader br = new BufferedReader(new FileReader(file));

                    while ((st = br.readLine()) != null) {
                        if (st.contains("[benchmark]")) {
                            dateTime = st.substring(st.indexOf("]") + 2);
                            benchmarkInfo.add(dateTime);
                        } else if (st.contains("V-Ray score:")) {
                            CPUScore = st.substring(st.indexOf(":") + 2);
                            benchmarkInfo.add(CPUScore);
                        } else if (st.contains("V-Ray GPU score:")) {
                            GPUScore = st.substring(st.indexOf(":") + 2);
                            benchmarkInfo.add(GPUScore);
                        }
                    }
                    if (benchmarkInfo.size() > 0) {
                        bw.write(benchmarkInfo.toString() + "\n");
                    }
                }
            }
        }
        bw.close();
    }
}
