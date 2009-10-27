/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.gridmix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MiniMRCluster;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.CounterGroup;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.TaskReport;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.server.jobtracker.JTConfig;
import org.apache.hadoop.tools.rumen.JobStory;
import org.apache.hadoop.tools.rumen.TaskInfo;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import static org.apache.hadoop.mapreduce.TaskCounter.*;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import org.apache.commons.logging.LogFactory;
import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.log4j.Level;

public class TestGridmixSubmission {
  {
    ((Log4JLogger)LogFactory.getLog("org.apache.hadoop.mapred.gridmix")
        ).getLogger().setLevel(Level.DEBUG);
  }

  private static FileSystem dfs = null;
  private static MiniDFSCluster dfsCluster = null;
  private static MiniMRCluster mrCluster = null;

  private static final int NJOBS = 2;
  private static final long GENDATA = 50; // in megabytes
  private static final int GENSLOP = 100 * 1024; // +/- 100k for logs

  @BeforeClass
  public static void initCluster() throws IOException {
    Configuration conf = new Configuration();
    conf.setBoolean(JTConfig.JT_RETIREJOBS, false);
    conf.setInt(JTConfig.JT_RETIREJOB_CACHE_SIZE, 1000);
    conf.setBoolean(JTConfig.JT_PERSIST_JOBSTATUS, true);
    conf.setInt(JTConfig.JT_PERSIST_JOBSTATUS_HOURS, 1);
    dfsCluster = new MiniDFSCluster(conf, 3, true, null);
    dfs = dfsCluster.getFileSystem();
    mrCluster = new MiniMRCluster(3, dfs.getUri().toString(), 1, null, null,
        new JobConf(conf));
  }

  @AfterClass
  public static void shutdownCluster() throws IOException {
    if (mrCluster != null) {
      mrCluster.shutdown();
    }
    if (dfsCluster != null) {
      dfsCluster.shutdown();
    }
  }

  static class TestMonitor extends JobMonitor {

    static final long SLOPBYTES = 5 * 1024;
    private final int expected;
    private final BlockingQueue<Job> retiredJobs;

    public TestMonitor(int expected) {
      super();
      this.expected = expected;
      retiredJobs = new LinkedBlockingQueue<Job>();
    }

    public void verify(ArrayList<JobStory> submitted) throws Exception {
      final ArrayList<Job> succeeded = new ArrayList<Job>();
      assertEquals("Bad job count", expected, retiredJobs.drainTo(succeeded));
      final HashMap<String,JobStory> sub = new HashMap<String,JobStory>();
      for (JobStory spec : submitted) {
        sub.put(spec.getName(), spec);
      }
      for (Job job : succeeded) {
        final String jobname = job.getJobName();
        if ("GRIDMIX_GENDATA".equals(jobname)) {
          final Path in = new Path("foo").makeQualified(dfs);
          final Path out = new Path("/gridmix").makeQualified(dfs);
          final ContentSummary generated = dfs.getContentSummary(in);
          assertTrue("Mismatched data gen", // +/- 100k for logs
              (GENDATA << 20) < generated.getLength() + GENSLOP ||
              (GENDATA << 20) > generated.getLength() - GENSLOP);
          FileStatus[] outstat = dfs.listStatus(out);
          assertEquals("Mismatched job count", NJOBS, outstat.length);
          continue;
        }
        final JobStory spec =
          sub.get(job.getJobName().replace("GRIDMIX", "MOCKJOB"));
        assertNotNull("No spec for " + job.getJobName(), spec);
        assertNotNull("No counters for " + job.getJobName(), job.getCounters());

        final int nMaps = spec.getNumberMaps();
        final int nReds = spec.getNumberReduces();

        System.out.println(jobname + ": " + nMaps + "/" + nReds);
        final TaskReport[] mReports = job.getTaskReports(TaskType.MAP);
        assertEquals("Mismatched map count", nMaps, mReports.length);
        check(TaskType.MAP, job, spec, mReports,
            0, 1, nReds * SLOPBYTES, nReds + 1);

        final TaskReport[] rReports = job.getTaskReports(TaskType.REDUCE);
        assertEquals("Mismatched reduce count", nReds, rReports.length);
        check(TaskType.REDUCE, job, spec, rReports,
            nMaps * SLOPBYTES, nMaps + 1, 0, 1);
      }
    }

    public void check(final TaskType type, Job job, JobStory spec,
          final TaskReport[] runTasks,
          long extraInputBytes, int extraInputRecords,
          long extraOutputBytes, int extraOutputRecords) throws Exception {

      long[] runInputRecords = new long[runTasks.length];
      long[] runInputBytes = new long[runTasks.length];
      long[] runOutputRecords = new long[runTasks.length];
      long[] runOutputBytes = new long[runTasks.length];
      long[] specInputRecords = new long[runTasks.length];
      long[] specInputBytes = new long[runTasks.length];
      long[] specOutputRecords = new long[runTasks.length];
      long[] specOutputBytes = new long[runTasks.length];

      for (int i = 0; i < runTasks.length; ++i) {
        final TaskInfo specInfo;
        final Counters counters = runTasks[i].getTaskCounters();
        switch (type) {
          case MAP:
             runInputBytes[i] = counters.findCounter("FileSystemCounters",
                 "HDFS_BYTES_READ").getValue();
             runInputRecords[i] =
               (int)counters.findCounter(MAP_INPUT_RECORDS).getValue();
             runOutputBytes[i] =
               counters.findCounter(MAP_OUTPUT_BYTES).getValue();
             runOutputRecords[i] =
               (int)counters.findCounter(MAP_OUTPUT_RECORDS).getValue();

            specInfo = spec.getTaskInfo(TaskType.MAP, i);
            break;
          case REDUCE:
             runInputBytes[i] =
               counters.findCounter(REDUCE_SHUFFLE_BYTES).getValue();
             runInputRecords[i] =
               (int)counters.findCounter(REDUCE_INPUT_RECORDS).getValue();
             runOutputBytes[i] =
               counters.findCounter("FileSystemCounters",
                   "HDFS_BYTES_WRITTEN").getValue();
             runOutputRecords[i] =
               (int)counters.findCounter(REDUCE_OUTPUT_RECORDS).getValue();

            specInfo = spec.getTaskInfo(TaskType.REDUCE, i);
            break;
          default:
            specInfo = null;
            fail("Unexpected type: " + type);
        }
        specInputBytes[i] = specInfo.getInputBytes();
        specInputRecords[i] = specInfo.getInputRecords();
        specOutputRecords[i] = specInfo.getOutputRecords();
        specOutputBytes[i] = specInfo.getOutputBytes();
        System.out.printf(type + " SPEC: %9d -> %9d :: %5d -> %5d\n",
             specInputBytes[i], specOutputBytes[i],
             specInputRecords[i], specOutputRecords[i]);
        System.out.printf(type + " RUN:  %9d -> %9d :: %5d -> %5d\n",
             runInputBytes[i], runOutputBytes[i],
             runInputRecords[i], runOutputRecords[i]);
      }

      // Check input bytes
      Arrays.sort(specInputBytes);
      Arrays.sort(runInputBytes);
      for (int i = 0; i < runTasks.length; ++i) {
        assertTrue("Mismatched input bytes " +
            specInputBytes[i] + "/" + runInputBytes[i],
            runInputBytes[i] - specInputBytes[i] <= extraInputBytes);
      }

      // Check input records
      Arrays.sort(specInputRecords);
      Arrays.sort(runInputRecords);
      for (int i = 0; i < runTasks.length; ++i) {
        assertTrue("Mismatched input records " +
            specInputRecords[i] + "/" + runInputRecords[i],
            runInputRecords[i] - specInputRecords[i] <= extraInputRecords);
      }

      // Check output bytes
      Arrays.sort(specOutputBytes);
      Arrays.sort(runOutputBytes);
      for (int i = 0; i < runTasks.length; ++i) {
        assertTrue("Mismatched output bytes " +
            specOutputBytes[i] + "/" + runOutputBytes[i],
            runOutputBytes[i] - specOutputBytes[i] <= extraOutputBytes);
      }

      // Check output records
      Arrays.sort(specOutputRecords);
      Arrays.sort(runOutputRecords);
      for (int i = 0; i < runTasks.length; ++i) {
        assertTrue("Mismatched output records " +
            specOutputRecords[i] + "/" + runOutputRecords[i],
            runOutputRecords[i] - specOutputRecords[i] <= extraOutputRecords);
      }

    }

    @Override
    protected void onSuccess(Job job) {
      retiredJobs.add(job);
    }
    @Override
    protected void onFailure(Job job) {
      fail("Job failure: " + job);
    }
  }

  static class DebugGridmix extends Gridmix {

    private DebugJobFactory factory;
    private TestMonitor monitor;

    public void checkMonitor() throws Exception {
      monitor.verify(factory.getSubmitted());
    }

    @Override
    protected JobMonitor createJobMonitor() {
      monitor = new TestMonitor(NJOBS + 1); // include data generation job
      return monitor;
    }

    @Override
    protected JobFactory createJobFactory(JobSubmitter submitter,
        String traceIn, Path scratchDir, Configuration conf,
        CountDownLatch startFlag) throws IOException {
      factory =
        new DebugJobFactory(submitter, scratchDir, NJOBS, conf, startFlag);
      return factory;
    }
  }

  @Test
  public void testSubmit() throws Exception {
    final Path in = new Path("foo").makeQualified(dfs);
    final Path out = new Path("/gridmix").makeQualified(dfs);
    final String[] argv = {
      "-D" + FilePool.GRIDMIX_MIN_FILE + "=0",
      "-D" + Gridmix.GRIDMIX_OUT_DIR + "=" + out,
      "-generate", String.valueOf(GENDATA) + "m",
      in.toString(),
      "-" // ignored by DebugGridmix
    };
    DebugGridmix client = new DebugGridmix();
    final Configuration conf = mrCluster.createJobConf();
    int res = ToolRunner.run(conf, client, argv);
    assertEquals("Client exited with nonzero status", 0, res);
    client.checkMonitor();
  }

}
