import co.yellowdog.model.*;
import co.yellowdog.services.client.PlatformClient;
import co.yellowdog.services.objectstore.client.TransferStatus;
import co.yellowdog.services.objectstore.client.download.DownloadBatch;
import co.yellowdog.services.objectstore.client.shared.TransferStatistics;
import co.yellowdog.util.BinaryUnit;

import java.io.FileReader;
import java.sql.Timestamp;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;

import java.io.*;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Collectors;

import java.util.List;
import java.util.*;

import static co.yellowdog.util.ObjectUtils.formatBinaryValue;
import static java.util.stream.Collectors.*;


public class BenchmarkController {

    private static void outputRequirement(ComputeRequirement cr) {
        String instanceCounts = cr.getInstances().stream()
                .collect(groupingBy(Instance::getProvisionSourceName, toList()))
                .entrySet().stream()
                .map(e -> String.format("  %s : %s", e.getKey(), buildInstancesDescription(e.getValue())))
                .collect(Collectors.joining("\n"));

        long runningInstanceCount = cr.getInstances().stream()
                .map(Instance::getStatus)
                .filter(InstanceStatus.RUNNING::equals)
                .count();

        System.out.println(String.format("\n%s/%s : %s (%d RUNNING INSTANCES, INSTANCE COUNT: %s)\n%s", cr.getNamespace(), cr.getName(), cr.getStatus(), runningInstanceCount, cr.getInstanceCount(), instanceCounts));
    }

    private static void outputRequirement(WorkRequirement wr) {

        long taskCount = wr.getTaskGroups().stream()
                .map(TaskGroup::getTasks)
                .flatMap(List::stream)
                .map(Task::getStatus)
                .filter(TaskStatus.COMPLETED::equals)
                .count();

        System.out.println(String.format("\n%s/%s [%s] : %s (%d COMPLETED TASKS)", wr.getNamespace(), wr.getName(), wr.getId(), wr.getStatus(), taskCount));
        for (TaskGroup taskGroup : wr.getTaskGroups()) {
            System.out.println(String.format("\t%s [%s] : %s", taskGroup.getName(), taskGroup.getStatus(), buildTaskGroupTasksDescription(taskGroup)));
        }
    }

    private static String buildTaskGroupTasksDescription(TaskGroup taskGroup) {
        return taskGroup.getTasks().stream()
                .collect(groupingBy(Task::getStatus, counting()))
                .entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> String.format("%d %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining(", "));
    }

    private static String buildInstancesDescription(Collection<Instance> instances) {
        return instances.stream()
                .collect(groupingBy(Instance::getStatus, counting()))
                .entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> String.format("%d %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining(", "));
    }


    public static void main(String[] args) {
        ServicesSchema schema = ServicesSchema.defaultSchema("http://localhost/");

        try (PlatformClient client = PlatformClient.create(schema)) {
            client.startTransfers();
            client.setUserCredential("0000");

            int numOfAgents = 2; /*SET THE NUMBER OF RUNNING AGENTS HERE!*/
            ArrayList<TaskGroup> taskGroups = new ArrayList<>();

            for (int i=0; i<numOfAgents; i++) {
                String instance = Integer.toString(i);
                taskGroups.add(TaskGroup.builder()
                        .name(instance)
                        .runSpecification(RunSpecification.builder()
                                .runType(TaskRunType.BATCH)
                                .taskType("benchmark")
                                .minimumQueueConcurrency(1)
                                .idealQueueConcurrency(1)
                                .maximumTaskRetries(5)
                                .machineConfiguration(MachineConfiguration.builder()
                                        .instanceType(instance)
                                        .imageId("-")
                                        .build())
                                .build())
                        .task(Task.builder()
                                .name("vRay")
                                .taskType("benchmark")
                                .environment("INSTANCE", instance)
                                .outputFromWorkerDirectory("bm_output_"+ instance + ".json")
                                .outputFromTaskProcess()
                                .build())
                        .build());
            }

            /*WORK REQUIREMENT*/
            String workReqId = "WorkReq_".concat(UUID.randomUUID().toString());
            WorkRequirement workRequirement = WorkRequirement.builder()
                    .namespace("Benchmark")
                    .name(workReqId)
                    .taskGroups(taskGroups)
                    .build();

            WorkRequirement submittedWorkRequirement = client.submitWorkRequirement(workRequirement);
            client.addWorkRequirementListener(submittedWorkRequirement, BenchmarkController::outputRequirement);

            // wait for the work requirement to finish
            client.getWorkRequirementHelper(submittedWorkRequirement)
                    .whenRequirementMatches(wr -> wr.getStatus().isFinished())
                    .get();

            /*DOWNLOAD FROM OBJECT STORE*/
            Path destinationFolderPath = FileSystems.getDefault().getPath("output");
            /*1*/
            DownloadBatch downloadBatch = client.buildDownloadBatch()
                    .destinationFolder(destinationFolderPath)
                    .sourceObjects("Benchmark", String.format("%s/**/vRay/bm_output_*.json", workReqId))
                    .flattenFilePaths(FlattenPath.FILE_NAME_ONLY)
                    .buildIfObjectsFound()
                    .orElseThrow(() -> new RuntimeException("Could not find benchmark outputs in object store"));

            downloadBatch.start();
            downloadBatch.whenStatusMatches(TransferStatus::isFinished).get();

            TransferStatistics downloadStatistics = downloadBatch.getStatistics();
            System.out.println(String.format("Final benchmark output download %s (%s at %s)",
                    downloadBatch.getStatus(),
                    formatBinaryValue(downloadStatistics.getBytesTransferred(), BinaryUnit.BYTE),
                    formatBinaryValue(downloadStatistics.getTransferSpeedBitsPerSecond(), BinaryUnit.BIT)));


            /*REPORTMAKER*/
            if (downloadStatistics.getBytesTransferred() > 0) {
                createReport();
            } else {
                System.out.println("No report created. Reason: no data was downloaded.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void createReport() throws IOException, ParseException {
        File report = new File("report.txt");
        BufferedWriter bw = new BufferedWriter(new FileWriter(report));

        File folder = new File("output");
        File[] listOfFiles = folder.listFiles();

        Timestamp reportCreationDate = new Timestamp(System.currentTimeMillis());
        bw.write("Benchmark report from " + reportCreationDate + "\n\n");

        String headers = String.format("%-20s %-10s %-30s %-50s", "Filename", "cpuScore", "OS", "CPUs");
        bw.write(headers + "\n");
        for (int i=0; i<headers.length(); i++) {
            bw.write("-");
        }
        bw.write("\n");

        if (listOfFiles != null && listOfFiles.length > 0) {
            for (File file : listOfFiles) {
                if (file.isFile() && FilenameUtils.getExtension(file.getName()).equals("json")) {
                    Object obj = new JSONParser().parse(new FileReader(file));
                    JSONObject jo = (JSONObject) obj;

                    ArrayList<String> benchmarkArray = new ArrayList<>();
                    Map benchmarks = ((Map)jo.get("benchmarks"));
                    for (Map.Entry pair : (Iterable<Map.Entry>) benchmarks.entrySet()) {
                        benchmarkArray.add(pair.getValue().toString());
                    }

                    String OS = jo.get("os").toString();

                    ArrayList<String> cpusArray = new ArrayList<>();
                    Map cpus = ((Map)jo.get("cpus"));
                    for (Map.Entry pair : (Iterable<Map.Entry>) cpus.entrySet()) {
                        cpusArray.add(pair.getKey() + " : " + pair.getValue());
                    }

                    String row = String.format("%-20s %-10s %-30s %-50s", file.getName(), benchmarkArray.toString(), OS, cpusArray.toString());
                    bw.write(row + "\n");
                }
            }
        }
        bw.close();
    }
}
