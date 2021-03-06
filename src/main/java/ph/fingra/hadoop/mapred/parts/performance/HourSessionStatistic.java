/**
 * Copyright 2014 tgrape Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ph.fingra.hadoop.mapred.parts.performance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import ph.fingra.hadoop.common.ConstantVars;
import ph.fingra.hadoop.common.FingraphConfig;
import ph.fingra.hadoop.common.HfsPathInfo;
import ph.fingra.hadoop.common.LfsPathInfo;
import ph.fingra.hadoop.common.ConstantVars.LogParserType;
import ph.fingra.hadoop.common.ConstantVars.LogValidation;
import ph.fingra.hadoop.common.domain.TargetDate;
import ph.fingra.hadoop.common.logger.ErrorLogger;
import ph.fingra.hadoop.common.logger.WorkLogger;
import ph.fingra.hadoop.common.util.ArgsOptionUtil;
import ph.fingra.hadoop.common.util.FormatUtil;
import ph.fingra.hadoop.mapred.common.CopyToLocalFile;
import ph.fingra.hadoop.mapred.common.HdfsFileUtil;
import ph.fingra.hadoop.mapred.parse.CommonLogParser;
import ph.fingra.hadoop.mapred.parse.ComponentLogParser;
import ph.fingra.hadoop.mapred.parts.performance.domain.HourSessionEntity;
import ph.fingra.hadoop.mapred.parts.performance.domain.HourSessionKey;

public class HourSessionStatistic extends Configured implements Tool {
    
    private static class HourSessionCount {
        
        Map<Integer, Long> map = new HashMap<Integer, Long>();
        
        public void addItem(Integer key, Long count) {
            if (map.containsKey(key)) {
                Long prev_count = map.get(key);
                map.remove(key);
                map.put(key, prev_count + count);
            }
            else {
                map.put(key, count);
            }
        }
        
        public Map<Integer, Long> getMap() {
            return this.map;
        }
    }
    
    @Override
    public int run(String[] args) throws Exception {
        
        String opt_mode = "";
        String opt_target = "";
        int opt_numreduce = 0;
        
        FingraphConfig fingraphConfig = new FingraphConfig();
        TargetDate targetDate = null;
        
        Configuration conf = getConf();
        Path[] inputPaths = null;
        Path outputPath = null;
        
        // get -D optional value
        opt_mode = conf.get(ConstantVars.DOPTION_RUNMODE, "");
        opt_target = conf.get(ConstantVars.DOPTION_TARGETDATE, "");
        opt_numreduce = conf.getInt(ConstantVars.DOPTION_NUMREDUCE, 0);
        
        // runmode & targetdate check
        if (ArgsOptionUtil.checkRunmode(opt_mode)==false) {
            throw new Exception("option value of -Drunmode is not correct");
        }
        if (opt_target.isEmpty()==false) {
            if (ArgsOptionUtil.checkTargetDateByMode(opt_mode, opt_target)==false) {
                throw new Exception("option value of -Dtargetdate is not correct");
            }
        }
        else {
            opt_target = ArgsOptionUtil.getDefaultTargetDateByMode(opt_mode);
        }
        
        // get TargetDate info from opt_target
        targetDate = ArgsOptionUtil.getTargetDate(opt_mode, opt_target);
        
        WorkLogger.log(HourSessionStatistic.class.getSimpleName()
                + " : [run mode] " + opt_mode
                + " , [target date] " + targetDate.getFulldate()
                + " , [reducer count] " + opt_numreduce);
        
        // get this job's input path - transform log file
        inputPaths = HdfsFileUtil.getTransformInputPaths(fingraphConfig, opt_mode,
                targetDate.getYear(), targetDate.getMonth(), targetDate.getDay(),
                targetDate.getHour(), targetDate.getWeek());
        if (inputPaths==null) {
            WorkLogger.log("There is no input data.");
            return 0;
        }
        
        // get this job's output path
        HfsPathInfo hfsPath = new HfsPathInfo(fingraphConfig, opt_mode);
        outputPath = new Path(hfsPath.getHoursession());
        
        // delete previous output path if is exist
        FileSystem fs = FileSystem.get(conf);
        List<Path> deletePaths = new ArrayList<Path>();
        deletePaths.add(outputPath);
        for (Path deletePath : deletePaths) {
            fs.delete(deletePath, true);
        }
        
        Job job = createJob(conf, inputPaths, outputPath, opt_numreduce,
                fingraphConfig);
        
        int status = job.waitForCompletion(true) ? 0 : 1;
        
        // copy to local result paths
        LfsPathInfo lfsPath = new LfsPathInfo(fingraphConfig, targetDate);
        CopyToLocalFile copier = new CopyToLocalFile();
        copier.dirToFile(outputPath.toString(), lfsPath.getHoursession());
        
        return status;
    }
    
    public Job createJob(Configuration conf, Path[] inputpaths, Path outputpath,
            int numreduce, FingraphConfig finconfig) throws IOException {
        
        conf.setBoolean("verbose", finconfig.getDebug().isDebug_show_verbose());
        conf.setBoolean("counter", finconfig.getDebug().isDebug_show_counter());
        
        Job job = new Job(conf);
        String jobName = "perform/hoursession job";
        job.setJobName(jobName);
        
        job.setJarByClass(HourSessionStatistic.class);
        
        for (int i=0; i<inputpaths.length; i++) {
            FileInputFormat.addInputPath(job, inputpaths[i]);
        }
        FileOutputFormat.setOutputPath(job, outputpath);
        
        job.setMapperClass(HourSessionMapper.class);
        job.setReducerClass(HourSessionReducer.class);
        
        job.setMapOutputKeyClass(HourSessionKey.class);
        job.setMapOutputValueClass(HourSessionEntity.class);
        
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(LongWritable.class);
        
        job.setPartitionerClass(HourSessionPartitioner.class);
        job.setSortComparatorClass(HourSessionSortComparator.class);
        job.setGroupingComparatorClass(HourSessionGroupComparator.class);
        
        job.setNumReduceTasks(numreduce);
        
        return job;
    }
    
    static class HourSessionMapper
        extends Mapper<LongWritable, Text, HourSessionKey, HourSessionEntity> {
        
        private boolean verbose = false;
        private boolean counter = false;
        
        private CommonLogParser commonparser = new CommonLogParser();
        private ComponentLogParser compoparser = new ComponentLogParser();
        
        private HourSessionKey out_key = new HourSessionKey();
        private HourSessionEntity out_val = new HourSessionEntity();
        
        protected void setup(Context context)
                throws IOException, InterruptedException {
            verbose = context.getConfiguration().getBoolean("verbose", false);
            counter = context.getConfiguration().getBoolean("counter", false);
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            
            // logtype check
            LogParserType logtype = FormatUtil.getLogParserType(value.toString());
            
            if (logtype.equals(LogParserType.CommonLog)) {
                
                // CommonLog : STARTSESS/PAGEVIEW/ENDSESS
                commonparser.parse(value);
                if (commonparser.hasError() == false) {
                    
                    out_key.set(commonparser.getAppkey(), commonparser.getSession(),
                            commonparser.getLocaltime());
                    out_val.set(commonparser.getSession(), commonparser.getLocaltime(),
                            commonparser.getCmd());
                    
                    context.write(out_key, out_val);
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(commonparser.getErrorLevel()).increment(1);
            }
            else if (logtype.equals(LogParserType.ComponentLog)) {
                
                // ComponentLog : COMPONENT
                compoparser.parse(value);
                if (compoparser.hasError() == false) {
                    
                    out_key.set(compoparser.getAppkey(), compoparser.getSession(),
                            compoparser.getLocaltime());
                    out_val.set(compoparser.getSession(), compoparser.getLocaltime(),
                            compoparser.getCmd());
                    
                    context.write(out_key, out_val);
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(compoparser.getErrorLevel()).increment(1);
            }
            else {
                if (verbose)
                    System.err.println("Ignoring corrupt input: " + value);
                if (counter)
                    context.getCounter(LogValidation.MALFORMED).increment(1);
            }
        }
    }
    
    static class HourSessionReducer
        extends Reducer<HourSessionKey, HourSessionEntity, Text, LongWritable> {
        
        private Text out_key = new Text();
        private LongWritable out_val = new LongWritable(0);
        
        private int LTIME_LENGTH = ConstantVars.LOG_DATE_FORMAT.length();
        private int HOUR_INDEX = 8;
        private int HOUR_LENGTH = 2;
        
        @Override
        protected void reduce(HourSessionKey key, Iterable<HourSessionEntity> values,
                Context context) throws IOException, InterruptedException {
            
            HourSessionCount item = new HourSessionCount();
            
            String prev_session = "";
            for (HourSessionEntity cur_val : values) {
                
                // values :
                // - grouped by appkey
                // - and order by appkey/session/localtime
                
                if (prev_session.equals(cur_val.session) == false) {
                    String localtime_hour = "";
                    if (cur_val.localtime.length()==LTIME_LENGTH) {
                        localtime_hour = cur_val.localtime.substring(HOUR_INDEX, HOUR_INDEX+HOUR_LENGTH);
                        
                        item.addItem(Integer.parseInt(localtime_hour), 1l);
                    }
                }
                
                prev_session = cur_val.session;
            }
            
            Map<Integer, Long> map = item.getMap();
            Iterator<Integer> iter = map.keySet().iterator();
            while (iter.hasNext()) {
                int hour = iter.next();
                Long session_count = map.get(hour);
                
                out_key.set(key.appkey + ConstantVars.RESULT_FIELD_SEPERATER
                        + ((hour<10) ? "0":"") + String.valueOf(hour));
                out_val.set(session_count);
                
                context.write(out_key, out_val);
            }
        }
    }
    
    private static class HourSessionPartitioner
        extends Partitioner<HourSessionKey, HourSessionEntity> {
        @Override
        public int getPartition(HourSessionKey key, HourSessionEntity value,
                int numPartitions) {
            return Math.abs((key.appkey).hashCode() * 127) % numPartitions;
        }
    }
    
    private static class HourSessionSortComparator
        extends WritableComparator {
        protected HourSessionSortComparator() {
            super(HourSessionKey.class, true);
        }
        @SuppressWarnings("rawtypes")
        @Override
        public int compare(WritableComparable w1, WritableComparable w2) {
            HourSessionKey k1 = (HourSessionKey) w1;
            HourSessionKey k2 = (HourSessionKey) w2;
            
            // ordered by HourSessionKey compareTo
            int ret = k1.compareTo(k2);
            
            return ret;
        }
    }
    
    private static class HourSessionGroupComparator
        extends WritableComparator {
        protected HourSessionGroupComparator() {
            super(HourSessionKey.class, true);
        }
        @SuppressWarnings("rawtypes")
        @Override
        public int compare(WritableComparable w1, WritableComparable w2) {
            HourSessionKey k1 = (HourSessionKey) w1;
            HourSessionKey k2 = (HourSessionKey) w2;
            
            // grouped by appkey
            int ret = k1.appkey.compareTo(k2.appkey);
            
            return ret;
        }
    }
    
    /**
     * 
     * @param args
     */
    public static void main(String[] args) {
        
        long start_time=0, end_time=0;
        int exitCode = 0;
        
        start_time = System.currentTimeMillis();
        
        WorkLogger.log(HourSessionStatistic.class.getSimpleName()
                + " : Start mapreduce job");
        
        try {
            exitCode = ToolRunner.run(new HourSessionStatistic(), args);
            
            WorkLogger.log(HourSessionStatistic.class.getSimpleName()
                    + " : End mapreduce job");
        }
        catch (Exception e) {
            ErrorLogger.log(HourSessionStatistic.class.getSimpleName()
                    + " : Error : " + e.getMessage());
            WorkLogger.log(HourSessionStatistic.class.getSimpleName()
                    + " : Failed mapreduce job");
        }
        
        end_time = System.currentTimeMillis();
        
        try {
            FingraphConfig config = new FingraphConfig();
            if (config.getDebug().isDebug_show_spenttime())
                WorkLogger.log("DEBUG - run times : "
                        + FormatUtil.getDurationFromMillitimes(end_time - start_time));
        }
        catch (IOException ignore) {}
        
        System.exit(exitCode);
    }
}
