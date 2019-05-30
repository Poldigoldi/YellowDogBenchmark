import co.yellowdog.model.*;
import co.yellowdog.services.client.PlatformClient;
import co.yellowdog.services.objectstore.client.TransferStatus;
import co.yellowdog.services.objectstore.client.download.DownloadBatch;
import co.yellowdog.services.objectstore.client.shared.TransferStatistics;
import co.yellowdog.util.BinaryUnit;
import co.yellowdog.util.CollectionUtils;

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

            int numOfAgents=3;
            ArrayList<TaskGroup> taskGroups = new ArrayList<>();

            for (int i=0; i<numOfAgents; i++) {
                taskGroups.add(TaskGroup.builder()
                        .name(Integer.toString(i))
                        .runSpecification(RunSpecification.builder()
                                .runType(TaskRunType.BATCH)
                                .taskType("docker")
                                .minimumQueueConcurrency(1)
                                .idealQueueConcurrency(1)
                                .maximumTaskRetries(3)
                                .machineConfiguration(MachineConfiguration.builder()
                                        .instanceType(Integer.toString(i))
                                        .imageId("-")
                                        .build())
                                .build())
                        .task(Task.builder()
                                .name("vRay")
                                .taskType("docker")
                                .initData("-e 'INSTANCE=" + i + "' poldigoldi/bm_30th_may_new_vray:1.0.0")
                                .outputFromWorkerDirectory("bm_output_" + i + ".txt")
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
                    .sourceObjects("Benchmark", String.format("%s/**/vRay/bm_output_*.txt", workReqId))
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
            createReport();


        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void createReport() throws IOException {
        File report = new File("report.txt");
        BufferedWriter bw = new BufferedWriter(new FileWriter(report));
        String st;
        String dateTime, CPUScore, GPUScore;

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
